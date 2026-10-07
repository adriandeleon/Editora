package com.editora.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.editora.completion.Completion;
import com.editora.completion.CompletionProvider;
import com.editora.diagram.DiagramKind;
import com.editora.editops.AutoClose;
import com.editora.editops.BraceMatcher;
import com.editora.editops.Commenter;
import com.editora.editops.IndentWindow;
import com.editora.editops.Indenter;
import com.editora.editops.LineIndent;
import com.editora.logviewer.LogLevel;
import com.editora.markdown.MarkdownEdit;
import com.editora.markdown.MarkdownHeading;
import com.editora.markdown.MarkdownInline;
import com.editora.markdown.MarkdownLines;
import com.editora.markdown.MarkdownLint;
import com.editora.markdown.MarkdownTable;
import com.editora.markdown.MarkdownToc;
import com.editora.snippet.ParsedSnippet;
import com.editora.snippet.Snippet;
import com.editora.snippet.SnippetParser;
import com.editora.snippet.SnippetSessions;
import com.editora.snippet.VariableResolver;
import com.editora.structured.StructuredParser;
import com.editora.structured.XmlParser;
import com.editora.typst.TypstMarkup;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.util.UndoUtils;
import org.fxmisc.undo.UndoManager;
import org.reactfx.Subscription;

import static com.editora.i18n.Messages.tr;

/** A single open document: a RichTextFX {@link CodeArea} plus its backing file, language, and dirty state. */
public class EditorBuffer implements TabContent {

    private final BufferCompletion completionActions = new BufferCompletion(new BufferCompletion.Host() {
        @Override
        public CodeArea area() {
            return area;
        }

        @Override
        public AnchorPane root() {
            return root;
        }

        @Override
        public CodeArea area2() {
            return area2;
        }

        @Override
        public AnchorPane root2() {
            return root2;
        }

        @Override
        public boolean largeFile() {
            return largeFile;
        }

        @Override
        public long docVersion() {
            return docVersion;
        }

        @Override
        public boolean lspActive() {
            return lspActive;
        }

        @Override
        public java.util.Set<Character> lspTriggerChars() {
            return lspTriggerChars;
        }

        @Override
        public java.util.Set<Character> lspSignatureTriggerChars() {
            return lspSignatureTriggerChars;
        }

        @Override
        public java.util.function.Consumer<Character> signatureHelpRequester() {
            return signatureHelpRequester;
        }

        @Override
        public String language() {
            return language;
        }

        @Override
        public String fontFamily() {
            return fontFamily;
        }

        @Override
        public int fontSize() {
            return fontSize;
        }

        @Override
        public CodeArea getFocusedArea() {
            return EditorBuffer.this.getFocusedArea();
        }

        @Override
        public boolean isDiagram() {
            return EditorBuffer.this.isDiagram();
        }

        @Override
        public void sendLspChange() {
            EditorBuffer.this.sendLspChange();
        }

        @Override
        public boolean multiCaretActiveOn(CodeArea a) {
            return EditorBuffer.this.multiCaretActiveOn(a);
        }

        @Override
        public boolean isProse() {
            return EditorBuffer.this.isProse();
        }

        @Override
        public String getSpellLanguage() {
            return EditorBuffer.this.getSpellLanguage();
        }

        @Override
        public boolean isEditable() {
            return EditorBuffer.this.isEditable();
        }

        @Override
        public boolean hasActiveSnippet() {
            return EditorBuffer.this.hasActiveSnippet();
        }

        @Override
        public Runnable beginCompletionUndo() {
            return CompletionUndoManager.begin(area, area2);
        }

        @Override
        public void finishSnippet() {
            snippetSession.finish();
        }

        @Override
        public int snippetDepth() {
            return snippetSession.depth();
        }

        @Override
        public void startSnippet(CodeArea a, Snippet snippet, int from, int to, boolean reindent) {
            EditorBuffer.this.startSnippet(a, snippet, from, to, reindent);
        }

        @Override
        public int lspOffset(CodeArea a, int line, int col) {
            return LspEditPlacement.offset(a, line, col);
        }

        @Override
        public int[] lspPosition(CodeArea a, int offset) {
            return LspEditPlacement.position(a, offset);
        }
    });

    /** Auto-rename-tag: mirrors an HTML/XML tag-name edit onto the paired tag once the edit has committed. */
    private final TagRenameMirror tagRename = new TagRenameMirror(
            this::getLanguage, () -> !this.largeFile && !this.hugeFile && isEditable() && !hasMultipleCarets());

    private final CodeArea area = tagRename.newArea(null);
    private final IndentKeys indentKeys = new IndentKeys(area);
    private final VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(area);
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);
    /** The last saved/loaded content; the buffer is dirty only when the text differs from this. */
    private String cleanText = "";
    /** True when the current content has no durable identity even if it equals the in-memory baseline. */
    private boolean forcedDirty;
    /** The line ending the file had when loaded or last saved; a different one is an unsaved change. */
    private String cleanLineEnding = LineEndings.LF;
    /**
     * Emacs narrowing: the document text before/after the accessible region, held aside while the area
     * itself holds only the region. Both null when the buffer is widened (the normal state).
     */
    private String narrowPrefix;

    private String narrowSuffix;
    /** Fired whenever narrowing turns on or off, from wherever — the UI reconciles off this, not off the
     *  command, so a widen forced by a whole-document write cannot leave a stale indicator or a suspended
     *  language server behind. */
    private Runnable onNarrowChanged = () -> {};

    /** Orientation of an optional second, synced view of this document. */
    public enum Split {
        NONE,
        SIDE_BY_SIDE,
        STACKED
    }

    /** Wraps the scroll pane so we can overlay the column-80 ruler line and dock the minimap. */
    private final AnchorPane root = new AnchorPane();
    /** Tab content: shows either {@link #root} alone or a SplitPane of [root, secondary view]. */
    private final StackPane viewHost = new StackPane(root);
    /** Outermost tab content: a "View Mode" banner (top, read-only only) above {@link #viewHost}. */
    private final javafx.scene.layout.BorderPane outer = new javafx.scene.layout.BorderPane(viewHost);
    /** The MS-Word-style read-only banner (lazy); its "Enable Editing" runs {@link #onEnableEditing}. */
    private HBox viewModeBar;

    private boolean viewModeBarVisible;

    private Button enableEditingButton;
    /** Expert-mode exit control hosted over this buffer's primary code viewport, when it is active. */
    private Node expertExitControl;

    /**
     * The one row holding every floating control at the top-right of the code pane (view-mode toggle,
     * open-in-browser, log controls, Expert exit). Sharing a row is what keeps them from overlapping.
     */
    private final HBox cornerControls = cornerControlRow();

    private Label viewModeNote;
    /** When true, a non-writable-on-disk file offers "Edit as Administrator" instead of a dead-end note. */
    private boolean adminEditAvailable;

    private Boolean writableOnDisk; // null: ask Files.isWritable; else what the window found out (remote files)

    /** IntelliJ-style "install language support?" banner (lazy), stacked above the view-mode bar; driven by
     *  MainController via {@link #setInstallPrompt}/{@link #showInstallBar}. Generic (strings + runnables),
     *  so {@code editor} stays decoupled from {@code install}/{@code ui}. */
    private HBox installBar;

    private Label installMessageLabel;
    private Button installActionButton;
    private Button installDismissButton;
    private javafx.scene.control.ProgressIndicator installProgress;
    private Runnable onInstallAction;
    private Runnable onInstallDismiss;
    private boolean installBarShown;
    /** Invoked by the banner's "Enable Editing" button; the controller persists + refreshes indicators. */
    private Runnable onEnableEditing = () -> setViewMode(false);
    /** A second editable view sharing this document (created lazily on first split). */
    private CodeArea area2;

    private VirtualizedScrollPane<CodeArea> scrollPane2;
    /** The secondary view's container (scroll pane + its own minimap), mounted in the SplitPane. */
    private AnchorPane root2;

    private SecondaryPane pane2;

    private Minimap minimap2;
    private Split split = Split.NONE;

    /** Multiple cursors + Alt+drag column/box selection (RichTextFX fork add-on). Installed on {@link #area}
     *  (and {@link #area2} when split) while {@link #multiCaretEnabled}; transparent with one caret. */
    private boolean multiCaretEnabled;

    private MultiCarets multiCaret;
    private MultiCarets multiCaret2;

    /** IntelliJ-style Markdown preview modes (only meaningful for Markdown files). */
    public enum MarkdownViewMode {
        EDITOR,
        SPLIT,
        PREVIEW
    }

    private MarkdownViewMode markdownViewMode = MarkdownViewMode.EDITOR;

    /** Which renderer a Markwhen preview uses (toggled per file, persisted like the view mode). */
    public enum MarkwhenView {
        TIMELINE,
        CALENDAR
    }

    private MarkwhenView markwhenView = MarkwhenView.TIMELINE;
    /** Fired (FX thread) when the Markwhen view flips, so the controller persists it. */
    private Runnable onMarkwhenViewChanged = () -> {};
    /** Re-entrancy guard for the SPLIT-mode editor↔preview scroll sync. */
    private boolean syncingScroll;
    /** Rendered-preview pane (lazy); its content is rebuilt by {@link MarkdownRenderer}. */
    private ScrollPane previewPane;
    /** Wraps the preview so the floating control can overlay it in PREVIEW mode (no code pane then). */
    private StackPane previewHost;
    /** Centered spinner + message shown over the preview while content is expected but not yet rendered
     *  (e.g. an AI explanation streaming in) — otherwise the pane just looks blank. Toggled by
     *  {@link #setPreviewLoading}; always present in {@link #previewHost()}, hidden by default. */
    private Node previewLoadingOverlay;

    private Label previewLoadingLabel;
    /** The preview's −/+ zoom + light/dark control (overlaid top-left of the preview when previewing). */
    private HBox zoomControl;
    /** The preview light/dark toggle button (a sun/moon glyph); reflects the current effective theme. */
    private Button previewThemeButton;
    /** Markdown preview color theme: "" (follow app), "light", or "dark" — set by the controller. */
    private String previewThemeMode = "";
    /** Whether the app/editor theme is dark (used to resolve "follow app" + the toggle glyph). */
    private boolean previewAppDark;
    /** Runs the controller's global preview-theme toggle (injected, like the snippet/completion providers). */
    private Runnable previewThemeToggle = () -> {};
    /** Preview text zoom factor (1.0 = 100%); scales the rendered preview's base font size. */
    private double previewFontScale = 1.0;
    /** Base preview font size in px (matches {@code .markdown-preview-wrap} in app.css); headings use em. */
    private static final double BASE_PREVIEW_FONT = 15;
    /** The floating Editor/Split/Preview control overlaid top-right (injected for Markdown buffers). */
    private Node viewModeControl;
    /** The floating "open in browser" control overlaid top-right (injected for HTML buffers). */
    private Node htmlPreviewControl;
    /** The floating log-viewer control overlaid top-right (Follow / level / regex; injected for log buffers). */
    private Node logControl;
    /** The CSV/TSV grid preview node (a ui-layer TableView panel), injected for CSV buffers when the feature
     *  is on; non-null doubles as the CSV-preview enablement gate (mirrors {@link #htmlPreviewControl}). */
    private Node csvPreviewNode;
    /** Repopulates the injected CSV grid from the buffer text; run on the debounced preview pulse. */
    private Runnable csvPreviewRefresh = () -> {};
    /** Wraps the CSV grid so the floating Editor/Split/Preview toggle can overlay it in PREVIEW mode. */
    private StackPane csvPreviewHost;
    /** The HTTP response panel (a ui-layer {@code HttpClientPanel}), injected for {@code .http} buffers when
     *  the feature is on; non-null doubles as the HTTP-preview enablement gate (mirrors {@link
     *  #csvPreviewNode}). Unlike every other preview it is <b>not</b> derived from the buffer text — it shows
     *  the result of running a request — so the debounced pulse deliberately never re-renders it. */
    private Node httpPreviewNode;
    /** Wraps the HTTP panel so the floating Editor/Split/Preview toggle can overlay it in PREVIEW mode. */
    private StackPane httpPreviewHost;
    /** Structured-data (JSON/YAML/TOML) preview: on when the feature is enabled (pushed from settings). */
    private boolean structuredPreviewEnabled;
    /** SVG image preview for .svg files: on when the feature is enabled (pushed from settings). */
    private boolean svgPreviewEnabled;
    /** Typst document preview for .typ files: on when the feature is enabled (pushed from settings). */
    private boolean typstPreviewEnabled;
    /** Resolves the typst {@code --root} for a saved .typ file (project root for a multi-file doc); injected
     *  from MainController since the editor package can't reach project state. Null ⇒ use the file's folder. */
    private java.util.function.UnaryOperator<java.nio.file.Path> typstRootResolver;
    /** Crontab schedule preview for crontab files: on when the feature is enabled (pushed from settings). */
    private boolean crontabPreviewEnabled;
    /** fstab mount preview for /etc/fstab files: on when the feature is enabled (pushed from settings). */
    private boolean fstabPreviewEnabled;
    /** systemd unit preview: on when the feature is enabled (pushed from settings). */
    private boolean systemdPreviewEnabled;
    /** SSH client-config preview: on when the feature is enabled (pushed from settings). */
    private boolean sshConfigPreviewEnabled;
    /** Dockerfile stage preview: on when the feature is enabled (pushed from settings). */
    private boolean dockerfilePreviewEnabled;
    /** GitHub Actions workflow preview (content-detected YAML): on when the feature is enabled. */
    private boolean githubActionsPreviewEnabled;
    /** Maven pom.xml summary preview: on when the feature is enabled (pushed from settings). */
    private boolean pomPreviewEnabled;
    /** Per-buffer, session-only: this pom is showing the generic XML tree instead of its summary. */
    private boolean pomShowXml;
    /** How much of an XML file's head is read to decide whether it is a pom (a {@code <project>} preamble). */
    private static final int POM_SNIFF_CHARS = 4096;
    /** Holds the self-scrolling structured preview node (tree or OpenAPI docs); the Split/Preview side. */
    private StackPane structuredContentHolder;
    /** PREVIEW-mode wrapper for {@link #structuredContentHolder} so the mode toggle can overlay it. */
    private StackPane structuredPreviewHost;
    /** Tri-state view for a structured doc: {@code null}=auto (API docs for a spec, else tree), else forced. */
    private Boolean structuredShowApiDocs;
    /** Whether the last structured render detected an OpenAPI/Swagger spec (drives the view-toggle status). */
    private boolean lastStructuredOpenApi;
    /** Forces log-viewer mode on a buffer whose extension isn't {@code .log} ("View as Log"). */
    private boolean logViewForced;
    /** Filter, follow and trim state of the log viewer; a filtered view is read-only (see {@link LogView}). */
    private final LogView logView = new LogView(area, this::applyEditable);
    /** Fired from the debounced edit pulse while this is an HTML buffer (drives HTML live-preview reload). */
    private Runnable htmlPreviewDirtyListener;
    /** One document subscription and timer sequence for all differently-timed settled-edit work. */
    private final SettledEditDispatcher settledEdits = new SettledEditDispatcher();

    private Subscription settledEditSub;
    /** Bumped per preview render request; background results discard if stale. */
    private long previewGen;
    /**
     * Off-thread Markdown parsing + syntax tokenizing, shared across <b>all</b> open buffers so the
     * threads don't accumulate one pair per file (each pool thread is a ~1-2 MB daemon stack — a session
     * with dozens of tabs previously meant dozens of mostly-idle threads). Sharing is safe because every
     * background result is re-validated against the per-buffer {@link #previewGen}/{@link #highlightGen}
     * counters (bumped on the FX thread at submit) before it touches buffer state — overlapping work for
     * one buffer just means only the latest generation's result is applied. Different buffers already
     * tokenize concurrently against the shared {@link GrammarRegistry} grammar, so a shared pool adds no
     * new grammar-concurrency. Daemon, app-lifetime (mirrors {@link PreviewImageLoader}/{@code MermaidImages}).
     */
    private static final ExecutorService PREVIEW_POOL =
            Executors.newFixedThreadPool(2, daemonFactory("markdown-preview"));

    private static final ExecutorService HIGHLIGHT_POOL = Executors.newFixedThreadPool(
            Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2)),
            daemonFactory("editor-highlighter"));

    /** A daemon {@link java.util.concurrent.ThreadFactory} naming threads {@code <name>-N}. */
    private static java.util.concurrent.ThreadFactory daemonFactory(String name) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private Runnable onViewModeChanged = () -> {};
    /** Files at/above this size skip syntax highlighting and the minimap to stay responsive. */
    public static final long LARGE_FILE_BYTES = 5L * 1024 * 1024;
    /** Files at/above this size are opened read-only (and truncated by the loader). */
    public static final long HUGE_FILE_BYTES = 50L * 1024 * 1024;
    /** Whether the minimap is shown; applied to every split pane's minimap. */
    private boolean minimapVisible = true;
    /** Whether this buffer is the visible tab (see {@link #setRenderingActive}); starts true because a
     *  freshly built buffer measures itself before the controller marks background tabs inactive. */
    private boolean renderingActive = true;
    /** Large-file mode: syntax highlighting and the minimap are disabled regardless of settings. */
    private boolean largeFile;
    /** Intermediate "large source file" tier (below the 5 MB hard mode): the minimap and LSP are
     *  disabled — but syntax highlighting and editing stay — so a very long single file (e.g. a
     *  13k-line source) stays responsive. Triggered by line count at load (see {@code setHeavyFile});
     *  toggleable per-buffer. {@code largeFile} implies the same minimap/LSP suppression. */
    private boolean heavyFile;
    /** Huge-file mode: implies large-file mode plus read-only (no undo, not editable). */
    private boolean hugeFile;
    /** The loader could only read part of this file (huge-file cap / log tail) — saving would truncate it. */
    private boolean truncatedLoad;
    /** User "View mode": non-editable but keeps all normal editor features (separate from huge-file). */
    private boolean viewMode;
    /** Temporary non-editable shell while file content is loaded; never presented as user View mode. */
    private boolean loading;
    /** The most recently focused view (primary or secondary); drives "active area" for commands. */
    private CodeArea focusedArea = area;
    /** {@link #focusedArea} as a value to observe, for what shows the caret of the view the user is in. */
    private final javafx.beans.property.ReadOnlyObjectWrapper<CodeArea> focusedView =
            new javafx.beans.property.ReadOnlyObjectWrapper<>(area);
    /** Floating Markdown format bar (lazily created), shown on a non-empty selection in a Markdown buffer. */
    private MarkdownFormatBar formatBar;

    private boolean formatBarEnabled = true;
    private boolean formatBarUpdatePending;
    /** Floating AI selection-actions bar (Explain/Rewrite), lazily created; shown on any non-empty
     *  selection while AI Actions is enabled + a cached connectivity probe says the endpoint is reachable
     *  (see {@code AiCoordinator.applySupport} — never re-probed per selection). */
    private AiActionsBar aiActionsBar;

    private boolean aiActionsEnabled;
    private boolean aiActionsBarUpdatePending;
    private Runnable aiExplainHandler = () -> {};
    private Runnable aiRewriteHandler = () -> {};
    /** Opens a URL externally (injected from the controller's HostServices); Ctrl/Cmd-click a link. */
    private java.util.function.Consumer<String> openUrlHandler = u -> {};
    /** Injected by the controller: opens the table-size picker, then inserts a table. */
    private Runnable insertTableHandler;

    private Runnable insertTypstTableHandler;
    private Runnable typstImagePasteHandler;
    /** Preview right-click menu actions (injected from the controller's export/print commands). */
    private Runnable previewExportPdfHandler = () -> {};
    /** Runs the {@code pom.toggleView} command (summary ⇄ XML tree) from the preview's right-click menu. */
    private Runnable pomViewToggleHandler = () -> {};
    /** The menu item for that switch — relabelled per show, and hidden for a non-pom tree preview. */
    private MenuItem pomViewItem;

    private Runnable previewExportPngHandler = () -> {};
    private Runnable previewExportSvgHandler = () -> {};

    private Runnable previewPrintHandler = () -> {};
    private Runnable previewExportDocxHandler = () -> {};
    private Runnable previewExportOdtHandler = () -> {};
    /** Markwhen preview "Export to JSON" action (controller command); null = no-op. */
    private Runnable previewExportJsonHandler = () -> {};

    private javafx.scene.control.ContextMenu previewContextMenu;
    private javafx.scene.control.ContextMenu treePreviewContextMenu;
    /** Active snippet expansion (Tab cycles its fields), or null when none is in progress. */
    private final SnippetSessions snippetSession = new SnippetSessions(CompletionUndoManager::joinLast);
    /** Resolves (language, prefix) → snippet for Tab-expand; injected by the controller (default: none). */
    private java.util.function.BiFunction<String, String, Snippet> snippetProvider = (lang, prefix) -> null;
    /** Resolves completions for the typed prefix; injected by the controller (default: none). */

    // Settings: auto-show docs beside the list
    // per-open session flag (reset on each popup open; Ctrl+Q toggles)
    // generation guard for the debounced/async doc fetch

    /**
     * Monotonic count of text changes to this buffer's document — a cheap "has the document moved under me"
     * stamp for the async/deferred completion paths (a caret offset alone can't tell: an edit can land the
     * caret back on the same offset). Bumped for edits made in either view (they share the document).
     */
    private long docVersion;

    /** Coalesces brace-match recomputes to one per pulse (caret moves rapidly while typing). */
    private boolean braceMatchPending;
    /**
     * Emacs-style goal column for line-up/line-down ({@code C-p}/{@code C-n}): the column to aim for
     * when moving vertically, preserved across short lines until any other caret move resets it.
     * {@code -1} means "recompute from the caret on the next vertical move".
     */
    private int goalColumn = -1;
    /** True only while {@link #moveLine} is updating the caret, so its own move doesn't reset the goal. */
    private boolean movingByLine;

    private final ColumnRuler columnRuler = new ColumnRuler(area);
    private final Minimap minimap = new Minimap(area);
    private final WhitespaceOverlay whitespace = new WhitespaceOverlay(area);
    private final SpellCheckOverlay spellOverlay = new SpellCheckOverlay(area);
    private LogHighlightOverlay logOverlay; // lazily attached on first activation — see logOverlay()
    private final InlineValuesOverlay inlineValues = new InlineValuesOverlay(area);
    /** Git change bars + blame column, kept on the right lines through unsaved edits. */
    private final GitGutterLines gitLines = new GitGutterLines(area, dirty, this::refreshGutter, minimap);
    /** Fixed annotation-column width in px, computed from the widest author+date when blame is set, so
     *  line numbers stay aligned regardless of which row's gutter is (re)built. */
    private double blameColumnWidth;
    /** Render size (px) of the blame annotation text — must match {@code .blame-author}/{@code .blame-date}
     *  in {@code app.css} so the measured column width matches what's actually drawn (not the editor font). */
    private static final double BLAME_FONT_SIZE = 10;

    private MermaidLintOverlay lintOverlay; // lazily attached — see lintOverlay()
    private final MarkdownLintOverlay mdLintOverlay = new MarkdownLintOverlay(area);
    private LspDiagnosticOverlay lspOverlay; // lazily attached — see lspOverlay()
    /** Severity stripe over the editor scrollbar (shown whenever LSP is active), so diagnostics stay locatable. */
    private final DiagnosticStripe diagnosticStripe = new DiagnosticStripe(area);
    /** TODO/highlight overview stripe over the scrollbar (beside the diagnostic stripe). */
    private final TodoStripe todoStripe = new TodoStripe(area);
    /** Markdown-lint overview stripe over the scrollbar (beside the TODO/diagnostic stripes). */
    private final MarkdownLintStripe mdLintStripe = new MarkdownLintStripe(area);
    /** Files above this size are never scanned for compact-source detection (keeps it off the hot path). */
    private static final int COMPACT_SCAN_LIMIT = 256 * 1024;
    /** Whether this buffer can run as a local file (remote buffers cannot). */
    private boolean runFeatureEnabled = true;
    /** Whether shell scripts may show the Run glyph (gated by the Bash LSP server toggle, under the LSP
     *  feature) — separate from Java/Python, which only need {@link #runFeatureEnabled}. */
    private boolean shellRunEnabled;
    /** Whether the HTTP Client feature is enabled (gated by {@code Settings.httpClientSupport} + ijhttp
     *  detection) — a {@code .http} file then shows a Run glyph on every request line. */
    private boolean httpFeatureEnabled;
    /** The 0-based start lines of each request in a {@code .http} buffer (each gets a Run glyph). */
    private java.util.List<Integer> httpRequestLines = java.util.List.of();
    /** Fired with the clicked request's start line when a {@code .http} Run glyph is clicked. */
    private java.util.function.IntConsumer httpRunHandler = i -> {};
    /** 0-based definition line → target name for a Makefile buffer (each line gets a Run glyph → {@code
     *  make <target>}); empty for non-Makefile buffers. */
    private java.util.Map<Integer, String> makeTargets = java.util.Map.of();
    /** Fired with the clicked target's name when a Makefile Run glyph is clicked (runs {@code make <name>}). */
    private java.util.function.Consumer<String> makeRunHandler = t -> {};
    /** JUnit test-gutter gate (Test Runner feature + a detected JVM build tool), pushed by MainController. */
    private boolean testGutterEnabled;
    /** 0-based line → JUnit test target (class-decl line + each test method) for the gutter ▶; empty otherwise. */
    private java.util.Map<Integer, com.editora.test.JavaTestScanner.TestTarget> testLines = java.util.Map.of();
    /** Fired with a test target when its gutter ▶ is clicked (runs one class/method via the build tool). */
    private java.util.function.Consumer<com.editora.test.JavaTestScanner.TestTarget> testRunHandler = t -> {};
    /** Fired with a test target from the editor context menu (debugs one class/method via the build tool). */
    private java.util.function.Consumer<com.editora.test.JavaTestScanner.TestTarget> testDebugHandler = t -> {};
    /** Main-method gutter gate (a Java file in a Maven/Gradle project with run/debug available), pushed by
     *  MainController. When on, {@code public static void main} lines get a green ▶ to run the project class. */
    private boolean mainGutterEnabled;
    /** 0-based line → project {@code main} entry point for the gutter ▶; empty otherwise. */
    private java.util.Map<Integer, com.editora.run.MainMethodScanner.MainMethod> mainLines = java.util.Map.of();
    /** Fired with a main method when its gutter ▶ is clicked (runs that project main class). */
    private java.util.function.Consumer<com.editora.run.MainMethodScanner.MainMethod> mainRunHandler = m -> {};
    /** Fired with a main method for the editor right-click "Debug Main Class" item. */
    private java.util.function.Consumer<com.editora.run.MainMethodScanner.MainMethod> mainDebugHandler = m -> {};
    /** Whether this file is runnable (a Java 25 compact source file, a Python script, or — when the Bash
     *  LSP is enabled — a shell script) — drives the gutter Run glyph + the Run tool window. */
    private boolean runnable;
    /** 0-based line the gutter Run glyph sits on (a compact file's {@code main}, a Python {@code __main__}
     *  guard, else the first line), or -1 when not runnable. */
    private int runLine = -1;
    /** Fired (FX thread) when the runnable status flips, so the controller refreshes the Run button. */
    private Runnable onRunnableChanged = () -> {};

    private SearchHighlightOverlay searchOverlay; // lazily attached — see searchOverlay()
    private OccurrenceHighlightOverlay occurrenceOverlay; // lazily attached — see occurrenceOverlay() (#675)
    /** Highlights configured TODO/FIXME-style patterns (per-pattern color), behind the text. */
    private final TodoHighlightOverlay todoOverlay = new TodoHighlightOverlay(area);
    /** Injected matcher (compiled patterns) + on/off gate; null/false = no highlight. */
    private TodoMatcher todoMatcher;

    private boolean todoEnabled;
    private AceJumpOverlay aceJump; // lazily attached — see aceJump()
    /** Async maid validator (text, callback) injected by the controller; null = no linting. */
    private java.util.function.BiConsumer<
                    String, java.util.function.Consumer<java.util.List<com.editora.mermaid.MaidOutput.Diagnostic>>>
            mermaidValidator;

    private boolean mermaidLintEnabled;
    private javafx.scene.control.Tooltip lintTooltip;
    /** Message currently shown by {@link #lintTooltip} — skips a re-{@code show()} (flicker) on each move. */
    private String lintTooltipText;
    /** Async Markdown linter (text, callback) injected by the controller; null = no linting. */
    private java.util.function.BiConsumer<String, java.util.function.Consumer<java.util.List<MarkdownLint.Diagnostic>>>
            markdownLintValidator;

    private boolean markdownLintEnabled;
    private javafx.scene.control.Tooltip mdLintTooltip;
    private String mdLintTooltipText;
    /** Injected handler for image files dropped onto a Markdown buffer (controller copies + inserts links). */
    private java.util.function.Consumer<java.util.List<java.io.File>> imageDropHandler;
    /** Injected handler for a raw image / image URL dragged from a browser (image, url — either may be null). */
    private java.util.function.BiConsumer<javafx.scene.image.Image, String> webImageDropHandler;
    /** LSP: overlay active (diagnostics + hover), the debounced didChange sink, and the hover tooltip. */
    private boolean lspActive;

    private final java.util.List<CompletionEditTracker> completionEditTrackers = new java.util.ArrayList<>();

    private java.util.function.Consumer<String> lspChangeListener;
    /** {@link #docVersion} of the document text last sent to the server, so a send whose content hasn't changed
     *  since (the completion flush and the debounced pulse both fire for one edit) skips the whole-document
     *  {@code getText()} + {@code didChange}. Reset to -1 on LSP (de)activation so a re-attach always re-sends. */
    private long lastLspSentVersion = -1;
    /** Debounced pull-diagnostics request (servers that answer textDocument/diagnostic); null = none. */
    private Runnable lspDiagnosticsRequester;
    /** Debounced semantic-tokens request (servers advertising range semantic tokens); null = none. */
    private Runnable semanticTokensRequester;
    /** LSP semantic highlighting active for this buffer (server supports it + the feature is on). */
    private boolean semanticActive;
    /** Latest server semantic tokens (absolute positions), overlaid onto the TextMate highlight. */
    private java.util.List<SemanticToken> semanticTokens = java.util.List.of();
    /** True once the document changed after {@link #semanticTokens} were captured — suppresses the overlay
     *  (so we never mis-color shifted text) until a fresh response lands via {@link #setSemanticTokens}. */
    private boolean semanticStale;
    /** Bumped on every edit and read when a semantic-tokens request is issued, so a response computed against
     *  an older document is dropped instead of re-anchoring stale tokens onto the current text (see
     *  {@link #semanticGen()} / {@link #setSemanticTokens(java.util.List, long)}). */
    private long semanticGen;
    /** Debounces a viewport semantic-tokens re-request after scrolling settles (so a new region gets
     *  tokens without firing a server request per scroll frame). */
    private final javafx.animation.PauseTransition semanticScrollDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
    /** Completion trigger characters the server advertised (e.g. {@code .} for Java, {@code <} for HTML). */
    private java.util.Set<Character> lspTriggerChars = java.util.Set.of();
    /** Signature-help trigger chars (usually '(' and ',') — see setLspSignatureTriggerChars (#674). */
    private java.util.Set<Character> lspSignatureTriggerChars = java.util.Set.of();
    /** Fired (deferred a pulse) when a signature trigger char is typed; null = disabled. */
    private java.util.function.Consumer<Character> signatureHelpRequester;

    private javafx.scene.control.Tooltip lspTooltip;
    /** Message currently shown by {@link #lspTooltip} — skips a re-{@code show()} (flicker) on each move. */
    private String lspTooltipText;

    private final FoldManager folds = new FoldManager(area, this::documentTextSnapshot);

    {
        folds.setLineNumbers(logView::lineNumberAt, logView::lineNumberSpan); // a filtered log keeps its numbers
    }

    /** Pinned enclosing-scope headers over the top of the code pane; see {@link StickyScroll}. */
    private final StickyScrollBar stickyScroll = new StickyScrollBar();

    private boolean stickyScrollEnabled;
    private boolean stickyScrollPending;
    private final BookmarkManager bookmarks = new BookmarkManager(area);
    /** Handles a bookmark add/remove request for a line (from the right-click menu): the controller adds,
     *  or confirms a removal. Default: toggle. */
    private java.util.function.BiConsumer<EditorBuffer, Integer> bookmarkToggleRequest =
            (buffer, line) -> buffer.toggleBookmark(line);
    /** Breakpoints for this buffer (gutter strip + persistence + sent to a live DAP session). */
    private final BreakpointManager breakpoints = new BreakpointManager(area);
    /** When true, the leftmost breakpoint strip is reserved + clickable (debugging is enabled). */
    private boolean debugEnabled = false;
    /** Handles a breakpoint-strip click; default toggles. The controller overrides to persist + re-send. */
    private java.util.function.BiConsumer<EditorBuffer, Integer> gutterBreakpointClick =
            (buffer, line) -> buffer.toggleBreakpoint(line);
    /** Personal Notes for this buffer (gutter marker + highlight + hover). */
    private final NoteManager notes = new NoteManager(area);

    private final NoteHighlightOverlay noteOverlay = new NoteHighlightOverlay(area);
    /** When false, the Personal Notes feature is disabled for this buffer (no "Add Note" menu items). */
    private boolean notesEnabled = false;
    /** When false, the note inline marker + highlight are hidden (the {@code showNoteIndicators} setting). */
    private boolean noteIndicators = true;
    /** Invoked when the user clicks a note's inline start marker — the controller opens the note editor. */
    private java.util.function.BiConsumer<EditorBuffer, com.editora.config.PersonalNote> noteMarkerClick = (b, n) -> {};
    /** Reused hover tooltip + the id of the note it's currently showing (so we only update on change). */
    private final javafx.scene.control.Tooltip noteTip = PersonalNoteTooltip.create();

    private java.util.UUID hoverNoteId;

    /** Handles a gutter note-marker click (the controller opens/edits that line's note). Default: no-op. */
    /** Handles a click on a line's blame annotation (the controller shows that line's commit). Default: no-op. */
    private java.util.function.BiConsumer<EditorBuffer, Integer> gutterBlameClick = (buffer, line) -> {};
    /** Invoked by the "Add Personal Note" context-menu item (the controller prompts + creates). */
    private java.util.function.Consumer<EditorBuffer> addNoteHandler = b -> {};
    /** "Run File" context-menu handler (compact source files); null hides the item. */
    private Runnable runHandler;
    /** LSP navigation actions (controller-supplied); shown in the context menu only while LSP is active. */
    private Runnable lspGotoDefinitionAction = () -> {};

    private Runnable lspFindReferencesAction = () -> {};
    private Runnable lspHoverAction = () -> {};
    private Runnable lspFormatAction = () -> {};
    private Runnable lspCodeActionsAction = () -> {};
    private Runnable lspRenameAction = () -> {};
    private Runnable lspGotoImplementationAction = () -> {};
    private Runnable lspGotoTypeDefinitionAction = () -> {};
    /** Whether this buffer's server advertises whole-document formatting (refreshed when it reports ready). */
    private boolean lspFormatAvailable;
    /** Whether this buffer's server advertises code actions (quick fixes) — gates the menu item (#670). */
    private boolean lspCodeActionsAvailable;
    /** Whether this buffer's server advertises rename — gates the menu item (#676). */
    private boolean lspRenameAvailable;
    /** Whether this buffer's server advertises {@code textDocument/implementation} — gates the item (#735). */
    private boolean lspImplementationAvailable;
    /** Whether this buffer's server advertises {@code textDocument/typeDefinition} — gates the item (#736). */
    private boolean lspTypeDefinitionAvailable;
    /** Whether this buffer's server advertises range formatting — enables Tab to re-indent the line. */
    private boolean lspRangeFormatAvailable;
    /** Injected range-formatter (the controller wires it to the LSP manager); null = none. */
    private LspRangeFormatter lspRangeFormatter;
    /** Injected on-type formatter (#740); null = none. */
    private LspOnTypeFormatter lspOnTypeFormatter;
    /** The characters this buffer's server formats on ({@code ;}, {@code }}, {@code \n} for jdtls) — #740. */
    private java.util.Set<Character> lspOnTypeTriggers = java.util.Set.of();
    /** {@code Settings.lspOnTypeFormatting} — the master gate for on-type formatting (#740). */
    private boolean onTypeFormattingEnabled;
    /** Generation guard so a stale async re-indent result can't clobber a later edit. */
    private long reindentGen;
    /** Chars of context captured before/after a note's selection (for re-anchoring). */
    private static final int CONTEXT_CHARS = 40;

    private Path path;
    /** Suggested name for a still-unsaved buffer (e.g. from {@code --new-file=foo.txt}); drives the tab
     *  title and extension-based highlighting while {@link #path} stays null (so Save prompts Save-As). */
    private String displayName;
    /** Last-known on-disk identity used to detect external changes; modified time -1 means unknown. */
    private long diskModifiedMillis = -1;

    private long diskSize = -1;
    /** SHA-256 of the exact bytes last loaded/saved; used for remote save conflict detection. */
    private String diskFingerprint;
    /** Language name for the current file (drives fold strategy); see {@link LanguageRegistry}. */
    private String language = LanguageRegistry.plaintext();
    /** TextMate grammar for the current file, or {@code null} when no grammar is bundled. */
    private IGrammar grammar;
    /** {@code --source N} version from a Java compact-source shebang (for the run command), else null. */
    private Integer shebangJavaSource;
    /** True once the user explicitly picked a language (status bar), so shebang detection won't fight it. */
    private boolean languageUserOverride;
    // --- Spell checking (Lucene Hunspell via SpellCheckOverlay); off until enabled by the controller. ---
    private SpellChecker spellChecker;
    private boolean spellCheckOn;
    private String spellLanguage = "en_US";
    private java.util.Set<String> spellUserWords = new java.util.HashSet<>();
    private boolean spellUserWordsEnabled = true; // honor the personal dictionary (Settings.personalDictionary)
    private boolean spellTechnicalEnabled = true; // honor the technical dictionary (Settings.technicalDictionary)
    private java.util.function.Consumer<String> onAddToDictionary = w -> {};
    /** Bumped on every highlight request (FX thread only); lets background results discard if stale. */
    /** Volatile: bumped on the FX thread, read per line by the background tokenize's cancel check. */
    private volatile long highlightGen;
    /** Bumped on every language/grammar change (FX thread only); drops a stale deferred grammar load. */
    private long languageGen;
    /** Per-line grammar end-states and bracket depths of the last applied pass (see {@link HighlightPass});
     *  null = none, so the next pass tokenizes the whole document. Replaced, never modified. */
    private HighlightPass.Lines highlightLines;
    /** The range the next highlight pass owes: every edit since a pass last applied. */
    private final HighlightDirty highlightDirty = new HighlightDirty();
    /** Where {@link #semanticTokens} are no longer painted (edits, lexical restyles) since they were anchored. */
    private final HighlightDirty semanticDirty = new HighlightDirty();
    /** Line count of the text {@link #semanticTokens} were anchored to. */
    private int semanticLines;
    /** A grammar or CSV pass may have styled the document, so losing the grammar leaves styles to clear. */
    private boolean styled;
    /** Named definitions from the last tokenization (FX-thread confined); drives the Structure view. */
    private List<TextMateHighlighter.Symbol> symbols = List.of();
    /** Notified (on the FX thread) after {@link #symbols} is refreshed. */
    private Runnable onSymbolsChanged = () -> {};

    private String fontFamily = "monospace";
    private int fontSize = 14;
    /** Whether {@link #setFont} has run at least once. The fields above are only *assumed* defaults — the
     *  area's inline style and the overlays' fonts are set by that call, so the first one must always
     *  apply even when it happens to match them. */
    private boolean fontApplied;
    /** Current-line highlight fill; varies per editor theme (see {@link #setLineHighlightColor}). */
    private Color lineHighlightColor = Color.web("#dfe7f0");
    /** Minimap block + viewport colors; vary per editor theme (see {@link #setMinimapColors}). */
    private Color minimapText = Color.web("#9aa5b1");

    private Color minimapViewport = Color.web("#0969da", 0.14);
    /** Visual tab width (columns); applied to the minimap and persisted via Settings. */
    private int tabSize = 4;
    // EditorConfig overrides (null = no override → fall back to detection/global). See com.editora.editorconfig.
    /** One full-text materialization shared by every consumer of the current {@link #docVersion}. */
    private final DocumentSnapshots documentSnapshots = new DocumentSnapshots();

    /** Per-buffer Emacs mark ring (session-only); shifted through edits by the subscription in the ctor. */
    private final com.editora.editops.MarkRing markRing = new com.editora.editops.MarkRing();

    private Boolean indentInsertSpacesOverride;
    private Integer indentSizeOverride;
    private String eolOverride; // "LF"/"CRLF"/"CR" forced by EditorConfig end_of_line; null = none
    /** The file's own line ending: detected on load or chosen by a conversion. The document holds bare LF. */
    private String lineEnding = LineEndings.LF;

    private Integer rulerColumnOverride; // null = default 80; EditorConfigProperties.OFF = hide
    private String detectedCharset = com.editora.editorconfig.EditorConfigCharset.UTF_8;
    private String charsetOverride; // EditorConfig charset to write; null = keep detected
    /** The declared charset could not decode the file, so {@link #detectedCharset} is a lossless stand-in. */
    private boolean charsetAssumed;

    private com.editora.editorconfig.EditorConfigProperties editorConfigProps =
            com.editora.editorconfig.EditorConfigProperties.EMPTY;
    /** Whether the user enabled the 80-column ruler. The line is only actually shown when a visible
     *  line reaches column 80 (see {@link #measureAndPlaceRuler}). */
    private boolean rulerVisible;

    private boolean lineNumbersVisible = true;
    /** When false (Simple UI mode), the entire gutter is removed (null paragraph-graphic factory) — no
     *  line numbers, fold chevrons, bookmark/note/run/breakpoint slots, or git change bars. */
    private boolean gutterVisible = true;
    /** Coalesces ruler re-measurement onto a later pulse (see {@link #scheduleRulerMeasure}). */
    private boolean rulerMeasurePending;
    /** Viewport width at the last scheduled ruler measure, so a viewport-dirty event that did not
     *  resize the area (a vertical scroll, an edit) doesn't pay for a re-measure. */
    private double lastRulerViewportWidth = -1;
    /** Set when something the ruler's x depends on changed other than the viewport width or the
     *  horizontal scroll — the gutter, the font, wrap, the ruler column. Cleared by the measure. */
    private boolean rulerInputsDirty = true;

    private final ColumnAdvance columnAdvance = new ColumnAdvance();

    /** Max undo entries kept per view; caps undo memory (RichTextFX defaults to unlimited). */
    private static final int UNDO_HISTORY = 300;

    public void setCompletionProvider(CompletionProvider provider) {
        completionActions.setCompletionProvider(provider);
    }

    public void setAutocomplete(boolean enabled, boolean prose, boolean snippets, boolean mermaid) {
        completionActions.setAutocomplete(enabled, prose, snippets, mermaid);
    }

    public void setAiCompletionProvider(AiCompletionProvider provider) {
        completionActions.setAiCompletionProvider(provider);
    }

    public void setAiCompletionEnabled(boolean enabled) {
        completionActions.setAiCompletionEnabled(enabled);
    }

    public void setCompletionDocResolver(
            java.util.function.BiConsumer<Object, java.util.function.Consumer<String>> resolver) {
        completionActions.setCompletionDocResolver(resolver);
    }

    public void setCompletionDocEnabled(boolean enabled) {
        completionActions.setCompletionDocEnabled(enabled);
    }

    public void toggleCompletionDoc() {
        completionActions.toggleCompletionDoc();
    }

    public boolean codeActionsShowing() {
        return completionActions.codeActionsShowing();
    }

    public void showCodeActions(List<CodeAction> actions, java.util.function.Consumer<CodeAction> onAccept) {
        completionActions.showCodeActions(actions, onAccept);
    }

    public void hideCodeActions() {
        completionActions.hideCodeActions();
    }

    public boolean completionShowing() {
        return completionActions.completionShowing();
    }

    public void cancelCompletion() {
        completionActions.cancelCompletion();
    }

    public void triggerCompletion() {
        completionActions.triggerCompletion();
    }

    public void setLspCompletionSource(com.editora.completion.CompletionSource source) {
        completionActions.setLspCompletionSource(source);
    }

    public void setLspCompletionProvider(
            java.util.function.BiConsumer<int[], java.util.function.Consumer<java.util.List<Completion>>> provider) {
        completionActions.setLspCompletionProvider(provider);
    }

    public boolean detectInsertSpaces(int tabSize) {
        if (indentInsertSpacesOverride != null) {
            return indentInsertSpacesOverride;
        }
        return !indentKeys.unit(tabSize, null, null).contains("\t");
    }

    public EditorBuffer() {
        refreshGutter();
        TabStops.apply(viewHost, tabSize); // JavaFX's own tab stop is 8 columns; ours starts at the default 4
        // Gutter click: route to the injectable handler (the controller adds, or confirms a removal);
        // defaults to a plain toggle so the editor works standalone (and in tests).
        folds.setBookmarkHooks(bookmarks::isBookmarked);
        folds.setSplitViews(() -> focusedArea, () -> area2);
        // Personal-Notes markers are drawn inline at each note's start by noteOverlay (no gutter slot).
        notes.setOnLinesRepaint(lines -> Platform.runLater(() -> lines.forEach(this::refreshGutterLine)));
        gitLines.attach(folds); // change bars: slot reserved only while tracking is on; hunk text on hover
        // Gutter Run glyph: reserved for a runnable file — one entry line for a script, or one per
        // request for a .http file.
        folds.setRunHooks(
                () -> runnable || !mainLines.isEmpty(),
                this::isRunGlyphLine,
                this::onRunGlyph,
                line -> testLines.containsKey(line) ? "test-run-marker" : null,
                this::runGlyphTooltip);
        // Gutter breakpoint strip: reserved only while debugging is enabled; click toggles a breakpoint.
        folds.setBreakpointHooks(
                () -> debugEnabled,
                breakpoints::isBreakpoint,
                breakpoints::styleClasses,
                line -> gutterBreakpointClick.accept(this, line));
        folds.setBreakpointTooltip(breakpoints::tooltip);
        // Gutter blame "Annotate" column (leftmost): reserved only while blame is on; the per-line
        // author/date/heatmap come from the controller-supplied list, click shows that line's commit.
        folds.setBlameHooks(
                this::isBlameOn,
                gitLines::blameAt,
                () -> blameColumnWidth,
                line -> gutterBlameClick.accept(this, line));
        breakpoints.setOnLinesRepaint(lines -> Platform.runLater(() -> lines.forEach(this::refreshGutterLine)));
        addViewModePaging(area); // Space/Backspace = page down/up while in read-only View mode
        completionActions.addCompletionKeys(area); // popup owns Enter/Tab before snippet and indentation filters
        completionActions.installCommitCharacters(area);
        addSnippetKeys(area); // Tab expands/cycles snippets (else falls through to indent)
        addAutoClose(area); // auto-close ()[]{} and quotes (before auto-indent so it sees the keystroke first)
        addAutoIndent(area); // Enter auto-indents; closers de-indent (per-language smart indent)
        completionActions.installCompletionTrigger(area);
        installOccurrenceTrigger(area); // LSP document highlight (#675)
        installCodeLensClick(area);
        // When an edit shifts bookmarks, repaint the affected lines' gutter markers after the edit's own
        // graphic rebuild settles (deferred to the next pulse), so the moved marker follows its line.
        bookmarks.setOnLinesRepaint(lines -> Platform.runLater(() -> lines.forEach(this::refreshGutterLine)));
        area.getStyleClass().add("editor-area");
        area.setWrapText(false);
        area.setUndoManager(boundedUndoManager());
        // Word/line-level undo: end the current undo group after an edit that finishes a word or line, so
        // one C-z undoes a word/line rather than the whole typing burst (the idle break is built into the
        // manager via UndoMerge.PAUSE). Subscribe AFTER setUndoManager so the manager records the change
        // first; both views share the document, so this one subscription covers edits made in either.
        area.plainTextChanges().subscribe(c -> breakUndoGroupIfBoundary(c.getInserted(), c.getRemoved()));
        // Document-change stamp for the deferred/async completion paths. On plainTextChanges (emitted
        // synchronously by the edit) rather than the debounced stream, so a change is visible the instant it
        // happens; both views share the document, so this one subscription covers either. One long++ per
        // edit — off the per-keystroke cost budget.
        area.plainTextChanges().subscribe(c -> {
            docVersion++;
            // Drop our reference to the previous version immediately. In-flight background consumers keep
            // their own immutable String; the next settled consumer materializes the new version once.
            documentSnapshots.invalidate();
            completionActions.documentChanged(c);
            if (!completionEditTrackers.isEmpty()) {
                int[] start = LspEditPlacement.position(area, c.getPosition());
                int[] before = completionChangeEnd(start, c.getRemoved());
                int[] after = completionChangeEnd(start, c.getInserted());
                var change = new LspEditShift.Change(start[0], start[1], before[0], before[1], after[0], after[1]);
                for (var tracker : completionEditTrackers)
                    tracker.changed(
                            c.getPosition(),
                            c.getRemoved().length(),
                            c.getInserted().length(),
                            change);
            }
        });
        // Mark ring: shift stored offsets across every edit so a mark still points at its text after
        // typing (one cheap pass over <=16 ints per edit; skipped when the ring is empty, the common case).
        area.plainTextChanges()
                .subscribe(c -> markRing.shift(
                        c.getPosition(),
                        c.getRemoved().length(),
                        c.getInserted().length()));
        area.setLineHighlighterFill(lineHighlightColor);
        // Track the changed range immediately (the settled dispatcher coalesces intermediate edits, so
        // the dirty range must be accumulated here), then re-highlight after a pause. Multi-change undo
        // reports each replacement in the coordinate space where that replacement ran, which is the
        // order HighlightDirty maps its range through them.
        configureSettledEditDispatcher();
        settledEditSub = area.multiPlainChanges().subscribe(changes -> {
            shiftCodeLenses(changes);
            gitLines.edited(changes, this::refreshGutterLine, area2); // bars + blame follow inserted/removed lines
            for (var change : changes) {
                int removed = change.getRemoved().length();
                int inserted = change.getInserted().length();
                highlightDirty.edited(change.getPosition(), removed, inserted);
                if (semanticActive) {
                    semanticDirty.edited(change.getPosition(), removed, inserted);
                }
                // The matched pair moves with the text, so the next re-match clears it where it now is: a
                // pass no longer restyles everything below an edit, which used to hide a stale position.
                int[] pair = completionActions.braceMatch;
                if (pair != null) {
                    pair[0] = HighlightDirty.moved(pair[0], change.getPosition(), removed, inserted);
                    pair[1] = HighlightDirty.moved(pair[1], change.getPosition(), removed, inserted);
                }
            }
            // The cached semantic tokens now point at shifted offsets; suppress the overlay until the
            // next response re-anchors them (one boolean write — off the per-char path's cost budget). The
            // generation bump invalidates any in-flight request so its (now-stale) response is dropped.
            if (semanticActive) {
                semanticGen++;
                semanticStale = true;
            }
            // LSP diagnostics are anchored to absolute line/col and only replaced when the server re-pushes
            // (a debounced didChange + round-trip, ~300 ms+). Until then every keystroke would repaint the OLD
            // squiggles/stripe/minimap ticks at their OLD lines — underlining whatever text now sits there.
            // Clear them on the edit so nothing paints on shifted lines; setLspDiagnostics re-anchors on the
            // next push (which always follows an edit — jdtls republishes, pull servers re-pull) (#417).
            if (lspActive) {
                suppressStaleDiagnostics();
            }
            settledEdits.changed();
        });
        // After scrolling settles, re-request semantic tokens for the now-visible region (debounced so a
        // drag-scroll doesn't fire a request per frame). Inert unless semantic highlighting is active.
        semanticScrollDebounce.setOnFinished(e -> {
            if ((semanticActive || inlayHintsActive) && semanticTokensRequester != null) {
                semanticTokensRequester.run(); // drives semantic tokens AND inlay hints (#681)
            }
        });
        // Dirty only when the content differs from the last saved/loaded text, so reverting an edit
        // (undo or manual) clears the marker. Driven off plainTextChanges (not textProperty): subscribing
        // to textProperty would force RichTextFX to materialize the whole document String on every
        // keystroke — O(n) allocation per char on a very large single buffer (e.g. minified JS on one
        // line, past the line-count heavy-file tier). See dirtyAfterEdit for when the text is compared.
        // A log filter or followed append rewrites the area without being a user edit: dirty is carried over.
        area.plainTextChanges().filter(c -> !logView.adjusting()).subscribe(c -> dirtyAfterEdit());
        // Auto-fill: break the line at a word boundary when it grows past the fill column (off by default,
        // so the very first check short-circuits for every buffer that hasn't turned it on).
        area.plainTextChanges().subscribe(this::maybeAutoFill);
        // Abbrev auto-expand: when abbrev-mode is on, typing a word terminator expands the word before it.
        area.plainTextChanges().subscribe(this::maybeExpandAbbrev);
        area.caretPositionProperty().addListener((obs, old, now) -> {
            resetGoalColumn();
            scheduleBraceMatch();
        });
        area.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                focusedArea = area;
                focusedView.set(area);
            }
        });
        installContextMenu(area);
        installFormatBarListeners(area);
        installSplitScrollSync();
        installOverlays();
    }

    /**
     * Registers every document-level idle milestone on one timer sequence. Feature predicates are checked
     * before a milestone is armed and again before its actions run, so inactive services add no timer work.
     * The order within a shared delay matches the former independent ReactFX subscriptions.
     */
    private void configureSettledEditDispatcher() {
        settledEdits.at(Duration.ofMillis(150), () -> true, () -> {
            resolveDirty();
            applyHighlighting();
            recomputeRun(true); // re-evaluate the Run glyph when a top-level main / __main__ appears/leaves
        });
        settledEdits.at(
                Duration.ofMillis(250),
                () -> (isHtml() && htmlPreviewDirtyListener != null)
                        || (!largeFile && markdownViewMode != MarkdownViewMode.EDITOR),
                () -> {
                    if (isHtml() && htmlPreviewDirtyListener != null) {
                        htmlPreviewDirtyListener.run();
                    }
                    if (!largeFile && markdownViewMode != MarkdownViewMode.EDITOR) {
                        scheduleRenderPreview();
                    }
                });
        settledEdits.at(
                Duration.ofMillis(90),
                () -> lspActive
                        && completionActions.autocompleteEnabled
                        && focusedArea != null
                        && focusedArea.isFocused(),
                () -> completionActions.updateCompletion(focusedArea, false));
        settledEdits.at(
                Duration.ofMillis(280),
                () -> !lspActive
                        && completionActions.autocompleteEnabled
                        && focusedArea != null
                        && focusedArea.isFocused(),
                () -> completionActions.updateCompletion(focusedArea, false));
        settledEdits.at(
                Duration.ofMillis(300), () -> todoEnabled || lspActive || semanticActive || inlayHintsActive, () -> {
                    if (todoEnabled) {
                        refreshTodoMarks();
                    }
                    if (lspActive) {
                        sendLspChange();
                    }
                    if (lspActive && lspDiagnosticsRequester != null) {
                        lspDiagnosticsRequester.run();
                    }
                    if ((semanticActive || inlayHintsActive) && semanticTokensRequester != null) {
                        semanticTokensRequester.run(); // drives semantic tokens AND inlay hints (#681)
                    }
                });
        settledEdits.at(
                UndoMerge.PAUSE,
                () -> !disposed && !largeFile && area.getLength() <= UNDO_HISTORY_MAX_BYTES,
                this::captureUndoCheckpoint);
        settledEdits.at(Duration.ofMillis(450), () -> mermaidLintEnabled || markdownLintEnabled, () -> {
            scheduleMermaidLint();
            scheduleMarkdownLint();
        });
        settledEdits.at(
                Duration.ofMillis(600),
                () -> completionActions.aiCompletionEnabled && focusedArea != null && focusedArea.isFocused(),
                () -> completionActions.maybeRequestAiCompletion(focusedArea));
    }

    /**
     * Keeps the editor and the rendered preview aligned in Markdown SPLIT mode: the pane the mouse is over
     * drives the other, mapped by scroll <i>fraction</i> so the two track even though their content heights
     * differ. Gating on which pane is <b>hovered</b> (the wheel target) makes the sync strictly
     * one-directional at any moment — the mouse is over at most one pane — so it cannot oscillate (RichTextFX
     * refines {@code estimatedScrollY} as paragraphs are measured, and a naïve bidirectional copy would feed
     * that back, the same pitfall {@code DiffViewerPane} guards against). A {@code syncingScroll} re-entrancy
     * flag wraps each programmatic set as a second guard. The preview→editor half is wired in
     * {@link #previewPane()}.
     */
    private void installSplitScrollSync() {
        area.estimatedScrollYProperty().addListener((o, ov, nv) -> {
            if (markdownViewMode == MarkdownViewMode.SPLIT
                    && !syncingScroll
                    && previewPane != null
                    && !previewPane.isHover()) {
                syncPreviewToEditorScroll();
            }
        });
    }

    /** Preview content height last seen by the scroll-sync listener; see the listener in {@link #previewPane()}. */
    private double previewContentHeight = -1;
    /** When the preview's content height last changed, so layout-driven vvalue moves can be ignored. */
    private long previewLayoutChangedAt;
    /** How long after a preview content-height change to keep treating vvalue moves as layout, not scrolling. */
    private static final long PREVIEW_SETTLE_NANOS = 250_000_000L;

    /**
     * Whether the preview's content height moved enough to call this vvalue change layout-driven. Pure.
     * The epsilon keeps sub-pixel layout noise from being mistaken for a real resize.
     */
    static boolean previewHeightChanged(double lastHeight, double newHeight) {
        return Math.abs(newHeight - lastHeight) > 0.5;
    }

    /**
     * Whether we're still inside the settle window after the preview's content last resized, i.e. a vvalue
     * change should be read as the pane re-anchoring rather than as the user scrolling. Pure.
     *
     * <p>A window rather than a single-event check because a progressive render (typst pages, diagrams,
     * images) resizes over many pulses, and the vvalue changes it provokes don't all land on the same pulse
     * as the resize itself.
     */
    static boolean previewSettling(long nanosSinceResize, long settleNanos) {
        return nanosSinceResize < settleNanos;
    }

    /** The rendered preview's current content height, or -1 when there's nothing to measure. */
    private double previewContentHeight() {
        javafx.scene.Node c = previewPane == null ? null : previewPane.getContent();
        return c == null ? -1 : c.getLayoutBounds().getHeight();
    }

    /** editor → preview: copy the editor's scroll fraction onto the preview's vvalue. */
    private void syncPreviewToEditorScroll() {
        if (previewPane == null) {
            return;
        }
        double scrollable = editorScrollableHeight();
        if (scrollable <= 1) {
            return;
        }
        Double y = area.estimatedScrollYProperty().getValue();
        double frac = clamp01((y == null ? 0 : y) / scrollable);
        double vmin = previewPane.getVmin();
        double vmax = previewPane.getVmax();
        syncingScroll = true;
        try {
            previewPane.setVvalue(vmin + frac * (vmax - vmin));
        } finally {
            syncingScroll = false;
        }
    }

    /** preview → editor: copy the preview's scroll fraction onto the editor's estimated scroll-Y. */
    private void syncEditorToPreviewScroll() {
        if (previewPane == null) {
            return;
        }
        double scrollable = editorScrollableHeight();
        if (scrollable <= 1) {
            return;
        }
        double range = previewPane.getVmax() - previewPane.getVmin();
        double frac = range > 0 ? clamp01((previewPane.getVvalue() - previewPane.getVmin()) / range) : 0;
        syncingScroll = true;
        try {
            area.estimatedScrollYProperty().setValue(frac * scrollable);
        } finally {
            syncingScroll = false;
        }
    }

    private double editorScrollableHeight() {
        Double total = area.totalHeightEstimateProperty().getValue();
        return (total == null ? 0 : total) - area.getHeight();
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** Show/reposition the Markdown format bar + the AI selection-actions bar as the selection or scroll
     *  changes (each coalesced per pulse; {@link #updateFormatBar()} always runs first so the AI bar can
     *  stack above it when both apply to the same selection). */
    private void installFormatBarListeners(CodeArea a) {
        a.selectionProperty().addListener((obs, old, now) -> {
            scheduleFormatBar();
            scheduleAiActionsBar();
            selectionChanged.run();
        });
        a.estimatedScrollYProperty().addListener((obs, old, now) -> {
            scheduleFormatBar();
            scheduleAiActionsBar();
        });
        a.estimatedScrollYProperty().addListener((obs, old, now) -> scheduleStickyScroll());
        a.estimatedScrollXProperty().addListener((obs, old, now) -> {
            scheduleFormatBar();
            scheduleAiActionsBar();
        });
        a.estimatedScrollYProperty().addListener((obs, old, now) -> {
            if (semanticActive) {
                semanticScrollDebounce.playFromStart(); // re-request tokens for the scrolled-to region
            }
        });
        a.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                hideFormatBar();
                hideAiActionsBar();
            }
        });
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (e.getCode() == KeyCode.ESCAPE) {
                hideFormatBar(); // don't consume — let other Escape behavior run
                hideAiActionsBar();
            }
        });
        // Ctrl/Cmd-click: open a Markdown link in the browser, or (in a code buffer with a live language
        // server) jump to the definition of the clicked symbol — the IDE "go to definition" gesture.
        a.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (!e.isShortcutDown() || e.getButton() != MouseButton.PRIMARY) {
                return;
            }
            try {
                int off = a.hit(e.getX(), e.getY()).getInsertionIndex();
                if (isMarkdown()) {
                    String url = MarkdownInline.linkAround(a.getText(), off);
                    if (url != null) {
                        openUrlHandler.accept(url);
                        e.consume();
                    }
                    return;
                }
                if (isLspActive()) {
                    // Move the caret to the clicked symbol + focus this area, then fire go-to-definition
                    // (the controller reads getFocusedArea()'s caret). Deferred so focus/caret settle first.
                    a.moveTo(off);
                    a.requestFocus();
                    Runnable go = lspGotoDefinitionAction;
                    javafx.application.Platform.runLater(go::run);
                    e.consume();
                }
            } catch (RuntimeException ignored) {
                // hit-test off the text — ignore
            }
        });
    }

    /** Injects the external-URL opener (controller's HostServices) used by Ctrl/Cmd-click + open-link. */
    public void setOpenUrlHandler(java.util.function.Consumer<String> handler) {
        this.openUrlHandler = handler == null ? u -> {} : handler;
    }

    /** Opens the link under the caret externally; returns false when the caret is not on a link. */
    public boolean openLinkUnderCaret() {
        String url = linkUnderCaret();
        if (url == null) {
            return false;
        }
        openUrlHandler.accept(url);
        return true;
    }

    /** Enables/disables the floating Markdown format bar (driven from Settings). */
    public void setFormatBarEnabled(boolean enabled) {
        this.formatBarEnabled = enabled;
        if (!enabled) {
            hideFormatBar();
        } else {
            scheduleFormatBar();
        }
    }

    private void scheduleFormatBar() {
        if (formatBarUpdatePending || !MarkdownFormatBar.updateNeeded(formatBar, formatBarEnabled, focusedArea)) {
            return;
        }
        formatBarUpdatePending = true;
        Platform.runLater(this::updateFormatBar);
    }

    private void scheduleStickyScroll() {
        if (stickyScrollPending || !stickyScrollEnabled) {
            return;
        }
        stickyScrollPending = true;
        Platform.runLater(this::updateStickyScroll);
    }

    /**
     * Turns the pinned scope headers on or off for this buffer. Off in large-file mode along with the
     * other per-viewport work, and off for a buffer with no fold regions to speak of — there is nothing
     * to pin in prose.
     */
    public void setStickyScrollEnabled(boolean enabled) {
        boolean effective = enabled && !largeFile;
        if (effective == stickyScrollEnabled) {
            return;
        }
        stickyScrollEnabled = effective;
        if (effective) {
            scheduleStickyScroll();
        } else {
            stickyScroll.hide();
            stickyLines = java.util.List.of(); // clear the state too, or the bar and the model disagree
        }
    }

    public boolean isStickyScrollEnabled() {
        return stickyScrollEnabled;
    }

    /**
     * Places a navigation target at the top of the usable editor viewport, below any sticky-scroll rows.
     * Call after moving the caret and after the editor has been laid out.
     */
    public void showParagraphAtTopClearOfStickyScroll(CodeArea targetArea, int line) {
        int firstVisible =
                stickyScrollEnabled ? StickyScroll.navigationFirstVisible(folds.regions(), line) : Math.max(0, line);
        targetArea.showParagraphAtTop(firstVisible);
    }

    /** Package-visible for the FX test: what is pinned right now, as 0-based lines. */
    java.util.List<Integer> stickyScrollLines() {
        return stickyLines;
    }

    /** Package-visible for the FX test: the bar node itself, so a test can assert it is actually laid out. */
    javafx.scene.Node stickyScrollNode() {
        return stickyScroll.node();
    }

    private java.util.List<Integer> stickyLines = java.util.List.of();

    private void updateStickyScroll() {
        stickyScrollPending = false;
        if (!stickyScrollEnabled) {
            stickyScroll.hide();
            stickyLines = java.util.List.of();
            return;
        }
        CodeArea a = focusedArea;
        if (a == null) {
            return;
        }
        int first;
        try {
            first = Math.max(0, a.firstVisibleParToAllParIndex());
        } catch (RuntimeException ex) {
            return; // not laid out yet; the next scroll or edit will bring us back
        }
        stickyLinesFor(first);
    }

    /**
     * Recomputes and renders the pinned headers for a viewport starting at {@code firstVisible}.
     *
     * <p>Split from {@link #updateStickyScroll()} so the first visible line is a parameter rather than a
     * reading of a live viewport: under the headless test platform nothing is laid out, so the real
     * reading is meaningless there and the wiring between the buffer's fold regions and the pinned
     * result would otherwise be untestable.
     */
    void stickyLinesFor(int firstVisible) {
        // Attach here rather than trusting attachControlToCodePane(). That runs from rebuildViewHost(),
        // which only fires when the view mode or split CHANGES — and `viewHost` is built in a field
        // initializer, so a plain buffer that never changes mode (i.e. every ordinary code file) never
        // called it and the bar was never in the scene graph at all. This is the one place that is
        // guaranteed to run right before the bar is needed, and placeStickyScroll is idempotent.
        placeStickyScroll();
        stickyLines = stickyScroll.pinnedLines(folds.regions(), firstVisible);
        stickyScroll.update(focusedArea, stickyLines, getTabSize(), this::jumpToLine);
    }

    private void hideFormatBar() {
        if (formatBar != null) {
            formatBar.node().setVisible(false);
        }
    }

    private void updateFormatBar() {
        formatBarUpdatePending = false;
        CodeArea a = focusedArea != null ? focusedArea : area;
        boolean show = formatBarEnabled
                && (isMarkdown() || isTypst())
                && isEditable()
                && !hugeFile
                && markdownViewMode != MarkdownViewMode.PREVIEW
                && a.getSelection().getLength() > 0;
        if (!show) {
            hideFormatBar();
            return;
        }
        int selStart = a.getSelection().getStart();
        Bounds screen = a.getCharacterBoundsOnScreen(selStart, Math.min(a.getLength(), selStart + 1))
                .orElse(null);
        if (screen == null) {
            hideFormatBar();
            return;
        }
        javafx.scene.layout.AnchorPane targetPane = (a == area2 && root2 != null) ? root2 : root;
        Bounds local = targetPane.screenToLocal(screen);
        if (local == null) {
            hideFormatBar();
            return;
        }
        if (formatBar == null) {
            formatBar = new MarkdownFormatBar(this);
        }
        javafx.scene.Node bar = formatBar.node();
        if (bar.getParent() != targetPane) {
            if (bar.getParent() instanceof javafx.scene.layout.AnchorPane ap) {
                ap.getChildren().remove(bar);
            }
            targetPane.getChildren().add(bar);
        }
        bar.setVisible(true);
        bar.applyCss();
        double w = bar.prefWidth(-1);
        double h = bar.prefHeight(-1);
        double x = Math.max(0, Math.min(local.getMinX(), Math.max(0, targetPane.getWidth() - w)));
        double y = local.getMinY() - h - 4;
        if (y < 0) {
            y = local.getMaxY() + 4; // no room above the selection — place below it
        }
        bar.resizeRelocate(x, y, w, h);
        bar.toFront();
    }

    /** Injects the Explain/Rewrite callbacks (wired once from the controller, like {@link #setOpenUrlHandler}) —
     *  each is a no-arg action reading the current selection off {@link #getFocusedArea()} itself. */
    public void setAiActionHandlers(Runnable onExplainSelection, Runnable onRewriteSelection) {
        this.aiExplainHandler = onExplainSelection == null ? () -> {} : onExplainSelection;
        this.aiRewriteHandler = onRewriteSelection == null ? () -> {} : onRewriteSelection;
    }

    /** {@link AiActionsBar}'s Explain button. */
    void requestExplainSelection() {
        aiExplainHandler.run();
    }

    /** {@link AiActionsBar}'s Rewrite button. */
    void requestRewriteSelection() {
        aiRewriteHandler.run();
    }

    /** Enables/disables the floating AI selection-actions bar — the effective gate (setting + a cached
     *  connectivity probe), pushed from the controller; never toggled per-selection/keystroke. */
    public void setAiActionsEnabled(boolean enabled) {
        this.aiActionsEnabled = enabled;
        if (!enabled) {
            hideAiActionsBar();
        } else {
            scheduleAiActionsBar();
        }
    }

    private void scheduleAiActionsBar() {
        if (aiActionsBarUpdatePending || !AiActionsBar.updateNeeded(aiActionsBar, aiActionsEnabled, focusedArea)) {
            return;
        }
        aiActionsBarUpdatePending = true;
        Platform.runLater(this::updateAiActionsBar);
    }

    private void hideAiActionsBar() {
        if (aiActionsBar != null) {
            aiActionsBar.node().setVisible(false);
        }
    }

    private void updateAiActionsBar() {
        aiActionsBarUpdatePending = false;
        CodeArea a = focusedArea != null ? focusedArea : area;
        boolean show = aiActionsEnabled
                && !hugeFile
                && markdownViewMode != MarkdownViewMode.PREVIEW
                && a.getSelection().getLength() > 0;
        if (!show) {
            hideAiActionsBar();
            return;
        }
        int selStart = a.getSelection().getStart();
        Bounds screen = a.getCharacterBoundsOnScreen(selStart, Math.min(a.getLength(), selStart + 1))
                .orElse(null);
        if (screen == null) {
            hideAiActionsBar();
            return;
        }
        javafx.scene.layout.AnchorPane targetPane = (a == area2 && root2 != null) ? root2 : root;
        Bounds local = targetPane.screenToLocal(screen);
        if (local == null) {
            hideAiActionsBar();
            return;
        }
        if (aiActionsBar == null) {
            aiActionsBar = new AiActionsBar(this);
        }
        aiActionsBar.setEditable(isEditable());
        javafx.scene.Node bar = aiActionsBar.node();
        if (bar.getParent() != targetPane) {
            if (bar.getParent() instanceof javafx.scene.layout.AnchorPane ap) {
                ap.getChildren().remove(bar);
            }
            targetPane.getChildren().add(bar);
        }
        bar.setVisible(true);
        bar.applyCss();
        double w = bar.prefWidth(-1);
        double h = bar.prefHeight(-1);
        double x = Math.max(0, Math.min(local.getMinX(), Math.max(0, targetPane.getWidth() - w)));
        // The Markdown format bar anchors at the same point — updateFormatBar() runs first (see
        // installFormatBarListeners), so stack above it when both apply to the same selection.
        double stackAbove = (formatBar != null && formatBar.node().isVisible())
                ? formatBar.node().getLayoutBounds().getHeight() + 4
                : 0;
        double y = local.getMinY() - h - 4 - stackAbove;
        if (y < 0) {
            y = local.getMaxY() + 4 + stackAbove;
        }
        bar.resizeRelocate(x, y, w, h);
        bar.toFront();
    }

    /** True when this buffer can show Markdown formatting actions (markdown + editable). */
    public boolean canFormatMarkdown() {
        return isMarkdown() && isEditable();
    }

    /** True when this buffer supports the shared markup-formatting actions — Markdown <em>or</em> Typst,
     *  editable. The inline wrap ({@code *}/{@code _}/{@code `}), bullet toggle, heading, and link work for
     *  both (the heading/link cores dispatch on {@link #isTypst()}); Markdown-only actions (strikethrough,
     *  task list, tables, TOC) keep the narrower {@link #canFormatMarkdown()} gate. */
    public boolean canFormatMarkup() {
        return (isMarkdown() || isTypst()) && isEditable();
    }

    private void applyMarkdownEdit(MarkdownEdit edit) {
        if (edit == null) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        a.replaceText(edit.from(), edit.to(), edit.replacement());
        a.selectRange(edit.selStart(), edit.selEnd());
        a.requestFocus();
    }

    /**
     * True when this buffer's language has comment syntax and the buffer is editable — i.e. the
     * "Comment / Uncomment" action is offered (right-click menu) and would do something. Plaintext has
     * no comment syntax, so it's excluded.
     */
    public boolean supportsComments() {
        Commenter.CommentStyle s = Commenter.styleFor(language);
        return (s.hasLine() || s.hasBlock()) && isEditable() && !largeFile;
    }

    /**
     * Toggles line/region comments on the selection (or the caret's line when nothing is selected),
     * Emacs comment-dwim style. Returns {@code false} when the language has no comment syntax or the
     * buffer isn't editable. Kept in {@code editor} (mirroring the Markdown format methods) so the editor
     * context menu can invoke it without depending on {@code ui}; {@code MainController.toggleComment}
     * delegates here.
     */
    public boolean toggleComment() {
        if (!isEditable() || largeFile) {
            return false;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        // A collapsed header is commented together with its (then expanded) body.
        int[] span = folds.expandHeaderSpan(
                a.getSelection().getStart(), a.getSelection().getEnd());
        Commenter.Edit edit = Commenter.toggle(a.getText(), span[0], span[1], Commenter.styleFor(language));
        if (edit == null) {
            return false;
        }
        a.replaceText(edit.from(), edit.to(), edit.replacement());
        a.selectRange(edit.selStart(), edit.selEnd());
        a.requestFocus();
        return true;
    }

    /** Toggles an inline marker ({@code **}/{@code *}/{@code ~~}/{@code `}) around the selection. */
    public void formatInline(String marker) {
        if (!canFormatMarkup()) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        var sel = a.getSelection();
        applyMarkdownEdit(MarkdownInline.toggle(a.getText(), sel.getStart(), sel.getEnd(), marker));
    }

    /** Wraps the selection as a link with {@code url} — Markdown {@code [sel](url)} or Typst
     *  {@code #link("url")[sel]}; a blank {@code url} leaves the caret in the empty destination slot. */
    public void formatLink(String url) {
        if (!canFormatMarkup()) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        var sel = a.getSelection();
        applyMarkdownEdit(
                isTypst()
                        ? TypstMarkup.link(a.getText(), sel.getStart(), sel.getEnd(), url)
                        : MarkdownInline.link(a.getText(), sel.getStart(), sel.getEnd(), url));
    }

    /** Link button / make-link command: use a clipboard URL when present, else an empty link. */
    public void formatLinkFromClipboard() {
        String clip = javafx.scene.input.Clipboard.getSystemClipboard().getString();
        String url = clip != null && clip.strip().matches("(?i)(https?|ftp|mailto):\\S+") ? clip.strip() : "";
        formatLink(url);
    }

    /** Inserts {@code text} at the focused area's caret (replacing any selection), then focuses it. */
    public void insertAtCaret(String text) {
        CodeArea a = focusedArea != null ? focusedArea : area;
        a.replaceSelection(text);
        a.requestFocus();
    }

    /** Smart paste: if a URL is on the clipboard and a single-line selection is active, wrap it as a link
     *  ({@code [selection](url)}) and return true; otherwise false so the caller does a normal paste. */
    public boolean trySmartLinkPaste() {
        if (!canFormatMarkdown()) {
            return false;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        var sel = a.getSelection();
        if (sel.getLength() == 0 || a.getText(sel.getStart(), sel.getEnd()).contains("\n")) {
            return false;
        }
        String clip = javafx.scene.input.Clipboard.getSystemClipboard().getString();
        if (clip == null || !clip.strip().matches("(?i)(https?|ftp|mailto):\\S+")) {
            return false;
        }
        formatLink(clip.strip());
        return true;
    }

    /** Promote ({@code delta<0}) / demote ({@code delta>0}) the heading level of the selected line(s)
     *  ({@code #} for Markdown, {@code =} for Typst). */
    public void formatHeading(int delta) {
        if (!canFormatMarkup()) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        var sel = a.getSelection();
        applyMarkdownEdit(
                isTypst()
                        ? TypstMarkup.heading(a.getText(), sel.getStart(), sel.getEnd(), delta)
                        : MarkdownHeading.apply(a.getText(), sel.getStart(), sel.getEnd(), delta));
    }

    /** Sets the selected line(s) to an absolute heading level (0 = Normal; {@code #} for Markdown,
     *  {@code =} for Typst). */
    public void setHeadingLevel(int level) {
        if (!canFormatMarkup()) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        var sel = a.getSelection();
        applyMarkdownEdit(
                isTypst()
                        ? TypstMarkup.setHeadingLevel(a.getText(), sel.getStart(), sel.getEnd(), level)
                        : MarkdownHeading.setLevel(a.getText(), sel.getStart(), sel.getEnd(), level));
    }

    /** Toggles a {@code "- "} bullet on the selected line(s) (valid in both Markdown and Typst). */
    public void formatBulletList() {
        if (!canFormatMarkup()) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        var sel = a.getSelection();
        applyMarkdownEdit(MarkdownLines.toggleBullet(a.getText(), sel.getStart(), sel.getEnd()));
    }

    /** Toggles a GFM task-list checkbox ({@code "- [ ] "}) on the selected line(s). */
    public void formatTaskList() {
        if (!canFormatMarkdown()) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        var sel = a.getSelection();
        applyMarkdownEdit(MarkdownLines.toggleTask(a.getText(), sel.getStart(), sel.getEnd()));
    }

    /** Reflows the GFM table around the caret; returns false when the caret is not on a table. */
    public boolean reflowTable() {
        if (!canFormatMarkdown()) {
            return false;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        int[] b = MarkdownTable.blockBounds(a.getText(), a.getCaretPosition());
        if (b == null) {
            return false;
        }
        String block = a.getText().substring(b[0], b[1]);
        String reflowed = MarkdownTable.reflow(block);
        if (!reflowed.equals(block)) {
            a.replaceText(b[0], b[1], reflowed);
        }
        a.requestFocus();
        return true;
    }

    /** Inserts a fresh, aligned GFM table ({@code rowsTotal} rows incl. header × {@code cols}) at the caret. */
    public void insertTable(int rowsTotal, int cols) {
        if (!canFormatMarkdown()) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        MarkdownTable.Nav g = MarkdownTable.generate(rowsTotal, cols);
        int caret = a.getCaretPosition();
        // Put the table on its own line(s): add a leading newline if not already at column 0, and a trailing one.
        boolean needLeading = caret > 0 && a.getText().charAt(caret - 1) != '\n';
        String prefix = needLeading ? "\n" : "";
        String text = prefix + g.block() + "\n";
        a.replaceText(caret, caret, text);
        int pos = caret
                + prefix.length()
                + Math.max(0, Math.min(g.caret(), g.block().length()));
        a.moveTo(pos);
        a.requestFollowCaret();
        a.requestFocus();
    }

    /** Shows the interactive table-size picker (wired by the controller); a no-op until injected. */
    public void insertTableInteractive() {
        if (canFormatMarkdown() && insertTableHandler != null) {
            insertTableHandler.run();
        }
    }

    /** Injected by the controller: opens the table-size picker, then calls {@link #insertTable}. */
    public void setInsertTableHandler(Runnable handler) {
        this.insertTableHandler = handler;
    }

    /** Opens the size picker for a Typst {@code #table} (Typst buffers only); the controller runs the picker. */
    public void insertTypstTableInteractive() {
        if (isTypst() && isEditable() && insertTypstTableHandler != null) {
            insertTypstTableHandler.run();
        }
    }

    /** Injected by the controller: opens the size picker, then calls {@link #insertTypstTable}. */
    public void setInsertTypstTableHandler(Runnable handler) {
        this.insertTypstTableHandler = handler;
    }

    /** Injected by the controller: opens an image file chooser → copies to {@code assets/} → inserts
     *  {@code #image("…")} (the "Insert Image" menu item for Typst buffers). */
    public void setTypstImageHandler(Runnable handler) {
        this.typstImagePasteHandler = handler;
    }

    /** Inserts a {@code #table(...)} skeleton at the caret (on its own line), caret in the first cell. */
    public void insertTypstTable(int rows, int cols) {
        if (!isTypst() || !isEditable()) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        TypstMarkup.Table t = TypstMarkup.table(rows, cols);
        int caret = a.getCaretPosition();
        boolean needLeading = caret > 0 && a.getText().charAt(caret - 1) != '\n';
        String prefix = needLeading ? "\n" : "";
        a.replaceText(caret, caret, prefix + t.text() + "\n");
        a.moveTo(caret + prefix.length() + t.caretOffset());
        a.requestFollowCaret();
        a.requestFocus();
    }

    /** Inserts a {@code #outline()} table-of-contents call at the caret (Typst buffers only). */
    public void insertTypstOutline() {
        if (!isTypst() || !isEditable()) {
            return;
        }
        insertAtCaret(TypstMarkup.OUTLINE);
    }

    /** Adds a row below the caret's row in the GFM table; false when the caret is not on a table. */
    public boolean tableAddRow() {
        return applyTableNav(MarkdownTable::addRow);
    }

    /** Deletes the caret's data row; false when not on a table (or the caret is on the header/delimiter). */
    public boolean tableDeleteRow() {
        return applyTableNav(MarkdownTable::deleteRow);
    }

    /** Adds a column to the right of the caret's column; false when the caret is not on a table. */
    public boolean tableAddColumn() {
        return applyTableNav(MarkdownTable::addColumn);
    }

    /** Deletes the caret's column; false when not on a table (or only one column remains). */
    public boolean tableDeleteColumn() {
        return applyTableNav(MarkdownTable::deleteColumn);
    }

    /** Sets the caret column's alignment; false when the caret is not on a table. */
    public boolean tableSetAlignment(MarkdownTable.Align align) {
        return applyTableNav((block, caret) -> MarkdownTable.setAlignment(block, caret, align));
    }

    private boolean applyTableNav(java.util.function.BiFunction<String, Integer, MarkdownTable.Nav> op) {
        if (!canFormatMarkdown()) {
            return false;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        int caret = a.getCaretPosition();
        int[] b = MarkdownTable.blockBounds(a.getText(), caret);
        if (b == null) {
            return false;
        }
        MarkdownTable.Nav nav = op.apply(a.getText().substring(b[0], b[1]), caret - b[0]);
        if (nav == null) {
            return false;
        }
        a.replaceText(b[0], b[1], nav.block());
        a.moveTo(b[0] + Math.max(0, Math.min(nav.caret(), nav.block().length())));
        a.requestFollowCaret();
        a.requestFocus();
        return true;
    }

    /**
     * Converts CSV into a Markdown table: the non-empty selection is treated as CSV and replaced in place,
     * else the clipboard CSV is inserted as a table at the caret. Returns false when there's nothing
     * parseable (no selection + empty/non-CSV clipboard, or a non-Markdown buffer).
     */
    public boolean tableFromCsv() {
        if (!canFormatMarkdown()) {
            return false;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        String sel = a.getSelectedText();
        boolean fromSelection = sel != null && !sel.isBlank();
        String csv = fromSelection
                ? sel
                : LineEndings.toLf(Clipboard.getSystemClipboard().getString());
        if (csv == null || csv.isBlank()) {
            return false;
        }
        String table = MarkdownTable.fromCsv(csv);
        if (table == null) {
            return false;
        }
        if (fromSelection) {
            var s = a.getSelection();
            a.replaceText(s.getStart(), s.getEnd(), table);
            a.moveTo(s.getStart() + table.length());
        } else {
            int caret = a.getCaretPosition();
            boolean needLeading = caret > 0 && a.getText().charAt(caret - 1) != '\n';
            String text = (needLeading ? "\n" : "") + table + "\n";
            a.replaceText(caret, caret, text);
            a.moveTo(caret + text.length());
        }
        a.requestFollowCaret();
        a.requestFocus();
        return true;
    }

    /** Copies the caret's Markdown table to the system clipboard as CSV; false when the caret isn't on a table. */
    public boolean tableToCsv() {
        if (!canFormatMarkdown()) {
            return false;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        int[] b = MarkdownTable.blockBounds(a.getText(), a.getCaretPosition());
        if (b == null) {
            return false;
        }
        String csv = MarkdownTable.toCsv(a.getText().substring(b[0], b[1]));
        if (csv == null) {
            return false;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(csv);
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }

    /** Injected file-export for the caret's Markdown table: {@code (csvText, format)}, {@code format} ∈
     *  {@code csv}/{@code xlsx}/{@code ods}. The editor renders the table to CSV; the controller owns the
     *  FileChooser + writers ({@code editor} can't depend on {@code ui}/{@code office}). */
    private java.util.function.BiConsumer<String, String> tableFileExporter;

    public void setTableFileExporter(java.util.function.BiConsumer<String, String> exporter) {
        this.tableFileExporter = exporter;
    }

    /** Exports the caret's Markdown table to a file via the injected exporter ({@code format} =
     *  {@code csv}/{@code xlsx}/{@code ods}); false when the caret isn't on a table or no exporter is wired. */
    public boolean exportTableFile(String format) {
        if (!canFormatMarkdown() || tableFileExporter == null) {
            return false;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        int[] b = MarkdownTable.blockBounds(a.getText(), a.getCaretPosition());
        if (b == null) {
            return false;
        }
        String csv = MarkdownTable.toCsv(a.getText().substring(b[0], b[1]));
        if (csv == null) {
            return false;
        }
        tableFileExporter.accept(csv, format);
        return true;
    }

    /**
     * Inserts a Markdown table of contents (a nested list of heading links) wrapped in {@code <!-- toc -->}
     * markers at the caret, or — when the document already has such a marker block — regenerates it in place.
     * Returns false when not a Markdown buffer or the document has no headings.
     */
    public boolean insertOrUpdateToc() {
        if (!canFormatMarkdown()) {
            return false;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        String doc = a.getText();
        String updated = MarkdownToc.updated(doc, 1, 6);
        if (updated != null) {
            replaceVisibleText(a, updated); // undoable; records only the TOC block that changed
            a.requestFocus();
            return true;
        }
        String body = MarkdownToc.build(doc, 1, 6);
        if (body.isEmpty()) {
            return false; // no headings to list
        }
        String block = MarkdownToc.wrapped(body);
        int caret = a.getCaretPosition();
        boolean needLeading = caret > 0 && a.getText().charAt(caret - 1) != '\n';
        String text = (needLeading ? "\n" : "") + block + "\n";
        a.replaceText(caret, caret, text);
        a.moveTo(caret + text.length());
        a.requestFollowCaret();
        a.requestFocus();
        return true;
    }

    /** The URL of the link under the caret, or {@code null} — for the "open link" command / Ctrl-click. */
    public String linkUnderCaret() {
        CodeArea a = focusedArea != null ? focusedArea : area;
        return MarkdownInline.linkAround(a.getText(), a.getCaretPosition());
    }

    private void installOverlays() {
        columnRuler.endYProperty().bind(root.heightProperty());
        // Re-measure the ruler whenever the rendered text moves: horizontal scroll, and any layout
        // change (first paint, resize, vertical scroll, gutter widening). Always deferred via
        // runLater so we never query character bounds synchronously inside the layout pass — doing
        // that re-enters layout and blanks the editor.
        area.estimatedScrollXProperty().addListener((obs, old, now) -> scheduleRulerMeasure());
        // viewportDirtyEvents fires for a vertical scroll and for every edit, neither of which can move the
        // ruler: its x is (glyph advance, gutter width, horizontal scroll), and the visibility test needs the
        // viewport width. Each measure costs TWO forced VirtualFlow layouts plus character-bounds queries, so
        // measuring on every dirty event was ~6-7 full measures per chrome toggle and a needless one per
        // keystroke. Horizontal scroll has its own listener above; the rest arrive as a width change or via
        // markRulerInputsDirty().
        area.viewportDirtyEvents().subscribe(ignore -> {
            double w = area.getWidth();
            if (rulerInputsDirty || w != lastRulerViewportWidth) {
                lastRulerViewportWidth = w;
                scheduleRulerMeasure();
            }
        });

        // Editor scroll pane fills the area, leaving room on the right for the minimap; the minimap
        // is docked to the right edge; the column ruler floats on top of everything.
        root.getChildren()
                .addAll(
                        scrollPane,
                        noteOverlay,
                        todoOverlay,
                        whitespace,
                        spellOverlay,
                        mdLintOverlay,
                        inlineValues,
                        minimap,
                        diagnosticStripe,
                        mdLintStripe,
                        todoStripe,
                        columnRuler);
        // searchOverlay / logOverlay / lintOverlay / lspOverlay / aceJump are attached lazily on first
        // activation (see their getters) so an off-feature buffer never builds their Canvas/subscriptions.
        AnchorPane.setTopAnchor(scrollPane, 0d);
        AnchorPane.setBottomAnchor(scrollPane, 0d);
        AnchorPane.setLeftAnchor(scrollPane, 0d);
        AnchorPane.setRightAnchor(scrollPane, Minimap.WIDTH);
        // The overlays share the text rectangle with the scroll pane (and track it when the minimap is
        // toggled — see anchorOverText); they are mouse-transparent so clicks reach the editor.
        anchorOverText(whitespace);
        anchorOverText(spellOverlay); // red squiggles
        spellChecker = new SpellChecker(spellLanguage, spellUserWords);
        spellChecker.setUserWordsEnabled(spellUserWordsEnabled);
        spellChecker.setTechnicalWordsEnabled(spellTechnicalEnabled);
        spellOverlay.setChecker(spellChecker);
        spellOverlay.setProseMode(isProse());
        spellOverlay.setMarkdown(isMarkdown()); // skip fenced ``` code blocks from spell check
        anchorOverText(mdLintOverlay);
        installImageDrop(area);
        anchorOverText(inlineValues); // inline debugger values (active only while suspended in this file)
        anchorOverText(todoOverlay);
        anchorOverText(noteOverlay);
        noteOverlay.setSpans(notes::activeSpans);
        noteOverlay.setActive(true);
        installPointerHovers(area);
        AnchorPane.setTopAnchor(minimap, 0d);
        AnchorPane.setBottomAnchor(minimap, 0d);
        AnchorPane.setRightAnchor(minimap, 0d);
        AnchorPane.setTopAnchor(diagnosticStripe, 0d);
        AnchorPane.setBottomAnchor(diagnosticStripe, 0d);
        AnchorPane.setRightAnchor(diagnosticStripe, 0d);
        diagnosticStripe.setOnActivate(this::jumpToLine);
        AnchorPane.setTopAnchor(todoStripe, 0d);
        AnchorPane.setBottomAnchor(todoStripe, 0d);
        AnchorPane.setRightAnchor(todoStripe, 0d);
        todoStripe.setOnActivate(this::jumpToLine);
        AnchorPane.setTopAnchor(mdLintStripe, 0d);
        AnchorPane.setBottomAnchor(mdLintStripe, 0d);
        AnchorPane.setRightAnchor(mdLintStripe, 0d);
        mdLintStripe.setOnActivate(this::jumpToLine);
    }

    /** Moves the caret to the start of {@code line} (0-based), scrolls it into view, and focuses the editor. */
    public void jumpToLine(int line) {
        int total = area.getParagraphs().size();
        if (total == 0) {
            return;
        }
        int p = Math.max(0, Math.min(line, total - 1));
        area.moveTo(p, 0);
        area.requestFollowCaret();
        area.requestFocus();
    }

    /** A misspelled word under the cursor: its text and absolute [start, end) offsets. */
    private record SpellHit(String word, int start, int end) {}

    private final ContextMenu contextMenu = new ContextMenu();
    /** The view the context menu was last asked for: its items act where the user clicked, in either pane. */
    private CodeArea menuView = area;
    /** Supplies extra right-click items (plugin contributions), injected by the controller; null = none. */
    private java.util.function.Supplier<List<MenuItem>> menuContributor;
    /** Build-tool actions for this file, shown next to the LSP submenu rather than at the menu's foot. */
    private java.util.function.Supplier<List<MenuItem>> buildMenuContributor;

    private void installContextMenu(CodeArea a) {
        contextMenu.getStyleClass().setAll("context-menu", "editor-context-menu");
        a.setOnContextMenuRequested(e -> {
            menuView = a;
            a.requestFocus(); // "at the caret" items read the focused view
            List<MenuItem> items = new java.util.ArrayList<>();
            // First, and for every item below: one position the menu is about (and the caret moved to a
            // right-click outside the selection), so Paste, Run Test, the LSP items and Add Bookmark agree.
            ContextMenuTarget at = ContextMenuTarget.resolve(a, e, this::collapseCarets);
            // A JUnit test file runs/debugs the method there (or the class from its declaration)
            // rather than offering generic "Run File"; anything else runnable keeps that generic action.
            com.editora.test.JavaTestScanner.TestTarget testTarget = testTargetAt(at.line(), false);
            if (testTarget != null) {
                boolean method = testTarget.methodName() != null;
                MenuItem runTests = new MenuItem(tr(method ? "editmenu.runTestMethod" : "editmenu.runTests"));
                runTests.setGraphic(FoldManager.runGlyph("test-run-marker")); // blue, matching the test gutter ▶
                runTests.setOnAction(ev -> testRunHandler.accept(testTarget));
                MenuItem debugTest =
                        new MenuItem(tr(method ? "testrunner.menu.debugTest" : "testrunner.menu.debugClass"));
                debugTest.setGraphic(MenuIcons.debug());
                debugTest.setOnAction(ev -> testDebugHandler.accept(testTarget));
                items.addAll(java.util.List.of(runTests, debugTest));
                items.add(new SeparatorMenuItem());
            } else if (runnable && runHandler != null) {
                MenuItem run = new MenuItem(tr("command.file.run"));
                run.setGraphic(FoldManager.runGlyph()); // green play icon, matching the gutter glyph
                run.setOnAction(ev -> runHandler.run());
                items.add(run);
                items.add(new SeparatorMenuItem());
            } else {
                // A Java class with a project main() gets Run/Debug Main Class items (green ▶ + bug icon).
                com.editora.run.MainMethodScanner.MainMethod mainTarget = mainTargetAt(at.line());
                if (mainTarget != null) {
                    String simple = com.editora.test.TestSourceLocator.simpleName(mainTarget.fqn());
                    MenuItem runMain = new MenuItem(tr("editmenu.runMainClass", simple));
                    runMain.setGraphic(FoldManager.runGlyph());
                    runMain.setOnAction(ev -> mainRunHandler.accept(mainTarget));
                    MenuItem debugMain = new MenuItem(tr("editmenu.debugMainClass", simple));
                    debugMain.setGraphic(MenuIcons.debug());
                    debugMain.setOnAction(ev -> mainDebugHandler.accept(mainTarget));
                    items.add(runMain);
                    items.add(debugMain);
                    items.add(new SeparatorMenuItem());
                }
            }
            // LSP navigation (only when this buffer is served by a language server), for the symbol there.
            if (lspActive) {
                items.add(lspMenu(at.offset()));
                items.add(new SeparatorMenuItem());
            }
            // Beside the LSP submenu, not with the plugin-contributed items at the foot of the menu: both
            // are "what this file's toolchain can do", and reading them together is the point.
            if (buildMenuContributor != null) {
                List<MenuItem> build = buildMenuContributor.get();
                if (build != null && !build.isEmpty()) {
                    items.addAll(build);
                    items.add(new SeparatorMenuItem());
                }
            }
            // Use the same effective gate as the floating selection bar: the master + feature settings
            // are on and the provider's latest connectivity check succeeded. Keep the submenu available
            // even without a selection so the menu remains a stable map of enabled features; its
            // selection-scoped actions are disabled until there is text to act on.
            if (aiActionsEnabled) {
                items.add(aiActionsMenu());
                items.add(new SeparatorMenuItem());
            }
            SpellHit hit = spellHitAt(at.offset());
            if (hit != null) {
                items.addAll(spellMenuItems(hit));
                items.add(new SeparatorMenuItem());
            }
            // One submenu per markup language, the way the LSP and build-tool actions are already grouped:
            // eight flat "Typst: …" entries pushed cut/copy/paste and the spelling suggestions down the menu
            // and made the file-type actions indistinguishable from the editing ones. Run/Debug stay at the
            // top level above — they are the actions you reach for without reading the menu.
            if (canFormatMarkdown()) {
                items.add(markupMenu(tr("editmenu.markdown"), markdownMenuItems()));
                items.add(new SeparatorMenuItem());
            } else if (isTypst() && isEditable()) {
                items.add(markupMenu(tr("editmenu.typst"), typstMenuItems()));
                items.add(new SeparatorMenuItem());
            }
            if (supportsComments()) {
                MenuItem comment = new MenuItem(tr("editmenu.toggleComment"));
                comment.setGraphic(MenuIcons.comment());
                comment.setOnAction(ev -> toggleComment());
                items.add(comment);
                items.add(new SeparatorMenuItem());
            }
            items.addAll(standardMenuItems());
            if (menuContributor != null) { // plugin-contributed items
                List<MenuItem> extra = menuContributor.get();
                if (extra != null && !extra.isEmpty()) {
                    items.add(new SeparatorMenuItem());
                    items.addAll(extra);
                }
            }
            // Bookmarks: the gutter marker is display-only, so add/remove lives here (and in the palette).
            if (path != null) {
                items.add(new SeparatorMenuItem());
                int clickedLine = at.line();
                boolean marked = bookmarks.isBookmarked(clickedLine);
                MenuItem bookmark = new MenuItem(tr(marked ? "editmenu.removeBookmark" : "editmenu.addBookmark"));
                bookmark.setGraphic(MenuIcons.bookmark());
                bookmark.setOnAction(ev -> bookmarkToggleRequest.accept(this, clickedLine));
                items.add(bookmark);
            }
            if (path != null && notesEnabled) {
                items.add(new SeparatorMenuItem());
                boolean hasSelection = a.getSelection().getLength() > 0;
                MenuItem addNote = new MenuItem(tr(hasSelection ? "editmenu.addNoteSelection" : "editmenu.addNote"));
                addNote.setGraphic(MenuIcons.note());
                addNote.setOnAction(ev -> addNoteHandler.accept(this));
                items.add(addNote);
            }
            contextMenu.getItems().setAll(items);
            contextMenu.show(a, at.screenX(), at.screenY());
            e.consume();
        });

        // A left-click in the editor dismisses an open context menu. RichTextFX consumes the
        // mouse press before the popup's auto-hide fires, so close it explicitly. The event is
        // not consumed, so the click still positions the caret as usual.
        a.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (contextMenu.isShowing() && e.getButton() == MouseButton.PRIMARY) {
                contextMenu.hide();
            }
        });
    }

    /** Injects a supplier of extra right-click menu items (plugin contributions); null clears it. */
    public void setMenuContributor(java.util.function.Supplier<List<MenuItem>> contributor) {
        this.menuContributor = contributor;
    }

    /** Items placed immediately after the LSP submenu — see {@code installContextMenu}. */
    public void setBuildMenuContributor(java.util.function.Supplier<List<MenuItem>> contributor) {
        this.buildMenuContributor = contributor;
    }

    /**
     * The language-server actions, gathered under one <b>LSP</b> submenu.
     *
     * <p>A submenu rather than the flat list they used to be: a served buffer contributes up to seven items,
     * which pushed the editing actions everyone uses — cut/copy/paste, the spelling suggestions — far enough
     * down the menu to hunt for. Grouping them also names what they are, which a bare "Go to Definition"
     * sitting between "Paste" and a spelling suggestion does not.
     */
    /**
     * Wraps a markup language's formatting actions in one titled submenu — the {@link #lspMenu} shape.
     *
     * <p>The items keep their palette titles ("Markdown: Bold"), matching the Maven submenu rather than the
     * LSP one: those titles are the command titles, shared with the palette and the keybinding editor, and
     * duplicating them purely to drop a prefix inside the submenu would mean a second set of strings in six
     * catalogs that could then drift from the commands they name.
     */
    private Menu markupMenu(String title, List<MenuItem> actions) {
        Menu menu = new Menu(title, MenuIcons.textFormat());
        menu.getItems().addAll(actions);
        return menu;
    }

    /** AI selection actions, grouped under one row while the effective enabled-and-connected gate is on. */
    private Menu aiActionsMenu() {
        boolean hasSelection = menuView.getSelection().getLength() > 0;
        MenuItem explain = new MenuItem(tr("command.ai.explainSelection"), MenuIcons.explain());
        explain.setDisable(!hasSelection);
        explain.setOnAction(e -> requestExplainSelection());
        MenuItem rewrite = new MenuItem(tr("command.ai.rewriteSelection"), MenuIcons.rewrite());
        rewrite.setDisable(!hasSelection || !isEditable());
        rewrite.setOnAction(e -> requestRewriteSelection());
        Menu menu = new Menu(tr("editmenu.aiActions"), MenuIcons.ai());
        menu.getItems().addAll(explain, rewrite);
        return menu;
    }

    private Menu lspMenu(int offset) {
        Menu menu = new Menu(tr("editmenu.lsp"), MenuIcons.code());
        menu.getItems().addAll(lspMenuItems(offset));
        return menu;
    }

    /** Go to Definition / Find References / Show Documentation — each moves the caret to {@code offset}
     *  first so it targets the right-clicked symbol, then runs the controller-supplied action. */
    private List<MenuItem> lspMenuItems(int offset) {
        List<MenuItem> items = new java.util.ArrayList<>();
        items.add(lspItem("command.lsp.gotoDefinition", MenuIcons.gotoDefinition(), offset, lspGotoDefinitionAction));
        if (lspImplementationAvailable) {
            items.add(lspItem(
                    "command.lsp.gotoImplementation",
                    MenuIcons.gotoImplementation(),
                    offset,
                    lspGotoImplementationAction));
        }
        if (lspTypeDefinitionAvailable) {
            items.add(lspItem(
                    "command.lsp.gotoTypeDefinition",
                    MenuIcons.gotoTypeDefinition(),
                    offset,
                    lspGotoTypeDefinitionAction));
        }
        items.add(lspItem("command.lsp.findReferences", MenuIcons.find(), offset, lspFindReferencesAction));
        items.add(lspItem("command.lsp.hover", MenuIcons.about(), offset, lspHoverAction));
        if (lspCodeActionsAvailable) { // quick fixes target the right-clicked spot
            items.add(lspItem("command.lsp.codeActions", MenuIcons.codeAction(), offset, lspCodeActionsAction));
        }
        if (lspRenameAvailable) { // rename the right-clicked symbol
            items.add(lspItem("command.lsp.rename", MenuIcons.rename(), offset, lspRenameAction));
        }
        if (lspFormatAvailable) {
            MenuItem format = new MenuItem(tr("command.lsp.formatDocument"));
            format.setGraphic(MenuIcons.code());
            format.setOnAction(e -> lspFormatAction.run()); // whole-document — no caret positioning needed
            items.add(format);
        }
        return items;
    }

    private MenuItem lspItem(String titleKey, Node icon, int offset, Runnable action) {
        MenuItem item = new MenuItem(tr(titleKey));
        item.setGraphic(icon);
        item.setOnAction(e -> {
            menuView.moveTo(offset); // in the view that was right-clicked
            action.run();
        });
        return item;
    }

    /** Markdown inline-format actions for the right-click menu (markdown buffers only). */
    private List<MenuItem> markdownMenuItems() {
        MenuItem bold = new MenuItem(tr("command.markdown.bold"));
        bold.setGraphic(MenuIcons.bold());
        bold.setOnAction(e -> formatInline("**"));
        MenuItem italic = new MenuItem(tr("command.markdown.italic"));
        italic.setGraphic(MenuIcons.italic());
        italic.setOnAction(e -> formatInline("*"));
        MenuItem strike = new MenuItem(tr("command.markdown.strikethrough"));
        strike.setGraphic(MenuIcons.strikethrough());
        strike.setOnAction(e -> formatInline("~~"));
        MenuItem code = new MenuItem(tr("command.markdown.code"));
        code.setGraphic(MenuIcons.code());
        code.setOnAction(e -> formatInline("`"));
        MenuItem link = new MenuItem(tr("command.markdown.link"));
        link.setGraphic(MenuIcons.link());
        link.setOnAction(e -> formatLinkFromClipboard());
        MenuItem toc = new MenuItem(tr("command.markdown.toc"));
        toc.setGraphic(MenuIcons.bulletList());
        toc.setOnAction(e -> insertOrUpdateToc());
        return List.of(bold, italic, strike, code, link, tableMenu(), toc);
    }

    /** Typst inline-format actions for the right-click menu (Typst buffers only). Typst has no native
     *  strikethrough/task/pipe-table, so the set is bold/emphasis/raw/link/bullet. */
    private List<MenuItem> typstMenuItems() {
        MenuItem bold = new MenuItem(tr("command.typst.bold"));
        bold.setGraphic(MenuIcons.bold());
        bold.setOnAction(e -> formatInline("*"));
        MenuItem emph = new MenuItem(tr("command.typst.emph"));
        emph.setGraphic(MenuIcons.italic());
        emph.setOnAction(e -> formatInline("_"));
        MenuItem raw = new MenuItem(tr("command.typst.raw"));
        raw.setGraphic(MenuIcons.code());
        raw.setOnAction(e -> formatInline("`"));
        MenuItem link = new MenuItem(tr("command.typst.link"));
        link.setGraphic(MenuIcons.link());
        link.setOnAction(e -> formatLinkFromClipboard());
        MenuItem bullet = new MenuItem(tr("command.typst.bulletList"));
        bullet.setGraphic(MenuIcons.bulletList());
        bullet.setOnAction(e -> formatBulletList());
        MenuItem table = new MenuItem(tr("command.typst.insertTable"));
        table.setGraphic(MenuIcons.table());
        table.setOnAction(e -> insertTypstTableInteractive());
        MenuItem outline = new MenuItem(tr("command.typst.outline"));
        outline.setGraphic(MenuIcons.bulletList());
        outline.setOnAction(e -> insertTypstOutline());
        MenuItem image = new MenuItem(tr("command.typst.insertImage"));
        image.setGraphic(MenuIcons.download());
        image.setOnAction(e -> {
            if (typstImagePasteHandler != null) {
                typstImagePasteHandler.run();
            }
        });
        return List.of(bold, emph, raw, link, bullet, table, outline, image);
    }

    /** The "Table" submenu: insert + add/delete row & column + column alignment. */
    private javafx.scene.control.Menu tableMenu() {
        javafx.scene.control.Menu menu = new javafx.scene.control.Menu(tr("menu.markdown.table"));
        menu.setGraphic(MenuIcons.table());
        menu.getItems()
                .addAll(
                        tableItem("command.markdown.insertTable", MenuIcons.table(), this::insertTableInteractive),
                        new javafx.scene.control.SeparatorMenuItem(),
                        tableItem("command.markdown.tableAddRow", MenuIcons.add(), this::tableAddRow),
                        tableItem("command.markdown.tableDeleteRow", MenuIcons.remove(), this::tableDeleteRow),
                        tableItem("command.markdown.tableAddColumn", MenuIcons.add(), this::tableAddColumn),
                        tableItem("command.markdown.tableDeleteColumn", MenuIcons.remove(), this::tableDeleteColumn),
                        new javafx.scene.control.SeparatorMenuItem(),
                        tableItem(
                                "command.markdown.tableAlignLeft",
                                MenuIcons.alignLeft(),
                                () -> tableSetAlignment(MarkdownTable.Align.LEFT)),
                        tableItem(
                                "command.markdown.tableAlignCenter",
                                MenuIcons.alignCenter(),
                                () -> tableSetAlignment(MarkdownTable.Align.CENTER)),
                        tableItem(
                                "command.markdown.tableAlignRight",
                                MenuIcons.alignRight(),
                                () -> tableSetAlignment(MarkdownTable.Align.RIGHT)),
                        new javafx.scene.control.SeparatorMenuItem(),
                        tableItem("command.markdown.tableFromCsv", MenuIcons.paste(), this::tableFromCsv),
                        tableItem("command.markdown.tableToCsv", MenuIcons.copy(), this::tableToCsv),
                        new javafx.scene.control.SeparatorMenuItem(),
                        tableItem(
                                "command.markdown.tableExportCsv", MenuIcons.download(), () -> exportTableFile("csv")),
                        tableItem(
                                "command.markdown.tableExportExcel",
                                MenuIcons.download(),
                                () -> exportTableFile("xlsx")),
                        tableItem(
                                "command.markdown.tableExportOds", MenuIcons.download(), () -> exportTableFile("ods")));
        return menu;
    }

    private static MenuItem tableItem(String key, Node icon, Runnable action) {
        MenuItem mi = new MenuItem(tr(key));
        mi.setGraphic(icon);
        mi.setOnAction(e -> action.run());
        return mi;
    }

    /** The Cut/Copy/Paste/Undo/Redo/Select All items, with state for the current selection/clipboard. */
    private List<MenuItem> standardMenuItems() {
        // Cut/Copy/Paste route through the multi-caret-aware path first (falling back to the native single-
        // caret op), so a box/column selection copies *every* caret's selection — matching the edit.cut/
        // copy/paste commands. Without this, copy() only grabs the primary caret's row. Like one caret,
        // several carets with nothing selected at any of them have nothing to cut or copy here: the fork's
        // multi-caret cut/copy would otherwise take their whole lines, whatever the "copy the line when
        // nothing is selected" setting says (this menu never did that for a single caret either).
        CodeArea view = menuView;
        MultiCarets carets = view == area2 ? multiCaret2 : multiCaret;
        boolean canCopy =
                carets != null ? carets.anySelection() : view.getSelection().getLength() > 0;
        boolean editable = isEditable();
        boolean hasClipboardText = Clipboard.getSystemClipboard().hasString();
        MenuItem cut = tableItem("editmenu.cut", MenuIcons.cut(), () -> {
            if (!multiCaretCut()) {
                view.cut();
            }
        });
        cut.setDisable(!canCopy || !editable);
        MenuItem copy = tableItem("editmenu.copy", MenuIcons.copy(), () -> {
            if (!multiCaretCopy()) {
                view.copy();
            }
        });
        copy.setDisable(!canCopy);
        MenuItem paste = tableItem("editmenu.paste", MenuIcons.paste(), () -> {
            if (!multiCaretPaste()) {
                view.paste();
            }
        });
        paste.setDisable(!hasClipboardText || !editable);
        MenuItem undo = tableItem("editmenu.undo", MenuIcons.undo(), () -> undoOrRedo(view, false));
        undo.setDisable(!view.isUndoAvailable());
        MenuItem redo = tableItem("editmenu.redo", MenuIcons.redo(), () -> undoOrRedo(view, true));
        redo.setDisable(!view.isRedoAvailable());
        MenuItem selectAll = tableItem("editmenu.selectAll", MenuIcons.selectAll(), view::selectAll);
        return List.of(cut, copy, paste, new SeparatorMenuItem(), undo, redo, new SeparatorMenuItem(), selectAll);
    }

    /** Suggestion items (replace the word) plus "Add to Dictionary"/"Ignore" for a misspelled word. */
    private List<MenuItem> spellMenuItems(SpellHit hit) {
        List<MenuItem> items = new java.util.ArrayList<>();
        List<String> suggestions = spellChecker.suggest(hit.word());
        if (suggestions.isEmpty()) {
            MenuItem none = new MenuItem(tr("editmenu.noSuggestions"));
            none.setGraphic(MenuIcons.spellcheck());
            none.setDisable(true);
            items.add(none);
        } else {
            for (String s : suggestions) {
                MenuItem mi = new MenuItem(s);
                mi.setGraphic(MenuIcons.spellcheck());
                mi.getStyleClass().add("spell-suggestion");
                mi.setOnAction(e -> {
                    if (isEditable()) {
                        area.replaceText(hit.start(), hit.end(), s);
                    }
                });
                items.add(mi);
            }
        }
        items.add(new SeparatorMenuItem());
        MenuItem add = new MenuItem(tr("editmenu.addToDictionary"));
        add.setGraphic(MenuIcons.add());
        add.setOnAction(e -> addToDictionary(hit.word()));
        MenuItem ignore = new MenuItem(tr("editmenu.ignore"));
        ignore.setGraphic(MenuIcons.block());
        ignore.setOnAction(e -> {
            spellChecker.ignore(hit.word());
            spellOverlay.refresh();
        });
        items.add(add);
        items.add(ignore);
        return items;
    }

    /** The misspelled word at document {@code offset}, or {@code null}. */
    private SpellHit spellHitAt(int offset) {
        if (!spellCheckOn || spellChecker == null || !spellChecker.ready() || largeFile) {
            return null;
        }
        var pos = area.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward);
        int paragraph = pos.getMajor();
        int col = pos.getMinor();
        String line = area.getParagraph(paragraph).getText();
        for (int[] span : SpellChecker.wordSpans(line)) {
            if (col >= span[0] && col <= span[1]) {
                int absStart = area.getAbsolutePosition(paragraph, span[0]);
                if (!spellEligible(absStart) || SpellChecker.partOfStructuredToken(line, span[0], span[1])) {
                    return null; // not eligible, or part of a URL/path/identifier — not a misspelling
                }
                String word = line.substring(span[0], span[1]);
                return spellChecker.isMisspelled(word)
                        ? new SpellHit(word, absStart, area.getAbsolutePosition(paragraph, span[1]))
                        : null;
            }
        }
        return null;
    }

    /** Mirror of {@link SpellCheckOverlay}'s eligibility: which words are checked in this buffer. */
    private boolean spellEligible(int abs) {
        java.util.Collection<String> style = area.getStyleOfChar(abs);
        return isProse()
                ? !style.contains("code") && !style.contains("link")
                : style.contains("comment") || style.contains("string");
    }

    private void addToDictionary(String word) {
        if (word == null || word.isBlank()) {
            return;
        }
        String lower = word.toLowerCase(java.util.Locale.ROOT);
        // Persist FIRST. The callback (ConfigManager.addUserWord) adds the word to the shared dictionary set
        // and writes dictionary.txt — but it only writes when the word is newly added to that set, and
        // spellUserWords *is* that shared set. Adding here first would make the callback see the word as
        // already present and silently skip the file write (the word then works this session but never
        // persists). Let the callback add + persist; the local add below is a no-op when shared, and only
        // matters when no persist callback is wired.
        onAddToDictionary.accept(lower);
        spellUserWords.add(lower);
        spellOverlay.refresh();
    }

    /** Drops this buffer's memoized spell verdicts and repaints. Call after the shared user-word set changes:
     *  the set is shared, but each buffer's overlay memoizes its own per-word results, so without this the
     *  other tabs keep squiggling a word that was just added to the dictionary. */
    public void refreshSpell() {
        spellOverlay.refresh();
    }

    /** The primary view. Tool windows, overlays, folding and highlighting all bind to this one. */
    public CodeArea getArea() {
        return area;
    }

    /** The view that currently has focus (primary or the split's secondary); for caret/edit commands. */
    public CodeArea getFocusedArea() {
        return focusedArea;
    }

    /** The split's second view while the split is shown, else null. */
    public CodeArea getSplitView() {
        return split == Split.NONE ? null : area2;
    }

    public javafx.beans.property.ReadOnlyObjectProperty<CodeArea> focusedAreaProperty() {
        return focusedView.getReadOnlyProperty();
    }

    private Runnable selectionChanged = () -> {};

    /** Runs {@code listener} when the selection of either view changes, or the other view comes into use. */
    public void onSelectionChanged(Runnable listener) {
        selectionChanged = listener;
        focusedView.addListener((obs, was, now) -> listener.run());
    }

    // --- Lazily-attached feature overlays --------------------------------------------------------------
    // These overlays are inert for most buffers (LSP off, not a diagram, not a log, never searched/ace-jumped),
    // so they are built + wired only on first activation rather than per buffer. Each is inserted just below a
    // fixed eager sibling to preserve the z-order of the original construction.

    /** Marks a node anchored by {@link #anchorOverText}, so {@link #setMinimapVisible} can find them all. */
    private static final String OVER_TEXT = SecondaryPane.OVER_TEXT;

    /**
     * Anchors {@code overlay} over the text rectangle — exactly the scroll pane's, whatever the minimap is
     * doing right now. Every overlay drawn on the text goes through here: one that kept the minimap's inset
     * while the minimap was hidden stopped 90 px short of the edge, clipping highlights and squiggles there.
     */
    private void anchorOverText(Node overlay) {
        AnchorPane.setTopAnchor(overlay, 0d);
        AnchorPane.setBottomAnchor(overlay, 0d);
        AnchorPane.setLeftAnchor(overlay, 0d);
        AnchorPane.setRightAnchor(overlay, AnchorPane.getRightAnchor(scrollPane));
        overlay.getProperties().put(OVER_TEXT, Boolean.TRUE);
    }

    /** Attaches {@code overlay} to the editor pane just below {@code below}, with the standard text-rect anchors. */
    private <T extends javafx.scene.layout.Region> T attachLazyOverlay(T overlay, javafx.scene.Node below) {
        anchorOverText(overlay);
        if (overlay instanceof TabSurface surface) {
            surface.setRenderingActive(renderingActive); // first activated while its tab is in the background
        }
        int idx = root.getChildren().indexOf(below);
        if (idx < 0) {
            idx = root.getChildren().indexOf(minimap); // fallback: just under the minimap
        }
        root.getChildren().add(idx < 0 ? root.getChildren().size() : idx, overlay);
        if (pane2 != null) {
            pane2.follow(root.getChildren()); // the split's second view gets its twin
        }
        return overlay;
    }

    private SearchHighlightOverlay searchOverlay() {
        if (searchOverlay == null) {
            searchOverlay = attachLazyOverlay(new SearchHighlightOverlay(area), todoOverlay);
        }
        return searchOverlay;
    }

    private OccurrenceHighlightOverlay occurrenceOverlay() {
        if (occurrenceOverlay == null) {
            occurrenceOverlay = attachLazyOverlay(new OccurrenceHighlightOverlay(area), todoOverlay);
        }
        return occurrenceOverlay;
    }

    private LogHighlightOverlay logOverlay() {
        if (logOverlay == null) {
            logOverlay = attachLazyOverlay(
                    new LogHighlightOverlay(area, new LogHighlightOverlay.LevelSource() {
                        @Override
                        public boolean filtered() {
                            return logView.filtered();
                        }

                        @Override
                        public LogLevel levelAt(int paragraph) {
                            return logView.levelAt(paragraph);
                        }
                    }),
                    whitespace);
        }
        return logOverlay;
    }

    private MermaidLintOverlay lintOverlay() {
        if (lintOverlay == null) {
            lintOverlay = attachLazyOverlay(new MermaidLintOverlay(area), mdLintOverlay);
        }
        return lintOverlay;
    }

    private LspDiagnosticOverlay lspOverlay() {
        if (lspOverlay == null) {
            lspOverlay = attachLazyOverlay(new LspDiagnosticOverlay(area), inlineValues);
        }
        return lspOverlay;
    }

    private AceJumpOverlay aceJump() {
        if (aceJump == null) {
            aceJump = attachLazyOverlay(new AceJumpOverlay(area), minimap);
        }
        return aceJump;
    }

    /**
     * Highlights all find matches ({@code [start,end)} offset pairs) behind the text, emphasizing the
     * match at {@code activeIndex}. No-op in large-file mode (the find bar still selects the current
     * match). Pass an empty result (or call {@link #clearSearchMatches}) to remove the highlight.
     */
    public void setSearchMatches(SearchMatches matches, int activeIndex) {
        if (largeFile) {
            return;
        }
        searchOverlay().setMatches(matches, activeIndex);
    }

    /** Clears the find-match highlight overlay. */
    public void clearSearchMatches() {
        if (searchOverlay != null) {
            searchOverlay.setMatches(SearchMatches.EMPTY, -1);
        }
    }

    /**
     * Washes every occurrence of the symbol under the caret (LSP document highlight, #675) — Read vs Write
     * shaded differently. Positions are converted to offsets against the document as it is NOW, so the
     * caller drops a response whose {@code docVersion} moved. Empty clears + releases the overlay texture.
     */
    public void setOccurrenceSpans(java.util.List<OccurrenceSpan> spans) {
        if (largeFile || spans == null || spans.isEmpty()) {
            clearOccurrenceSpans();
            return;
        }
        java.util.List<int[]> triples = new java.util.ArrayList<>(spans.size());
        for (OccurrenceSpan s : spans) {
            int from = LspEditPlacement.offset(area, s.startLine(), s.startCol());
            int to = LspEditPlacement.offset(area, s.endLine(), s.endCol());
            if (to > from) {
                triples.add(new int[] {from, to, s.write() ? 1 : 0});
            }
        }
        occurrenceOverlay().setSpans(triples);
    }

    /** Clears the occurrence wash (no symbol under the caret / LSP off / buffer deactivated). */
    public void clearOccurrenceSpans() {
        if (occurrenceOverlay != null) {
            occurrenceOverlay.setSpans(java.util.List.of());
        }
    }

    /** One LSP inlay hint, positioned: 0-based line + column, and the label to render there. */
    public record InlayHint(int line, int col, String label) {}

    /** 0-based line → the hints on it. Read live by the areas' inlay factory. */
    private java.util.Map<Integer, java.util.List<org.fxmisc.richtext.Inlay>> inlayHintsByLine = java.util.Map.of();

    private boolean inlayFactoryInstalled;

    /**
     * Sets the LSP inlay hints, rendered <b>inline at their own column</b> — each hint displaces the glyphs
     * after it instead of being parked at the end of the line (#824).
     *
     * <p>Hints are <em>decorations, not text</em>: they never enter the document, never shift an offset, and
     * never appear in a selection or a copy. See {@code org.fxmisc.richtext.Inlay}.
     */
    public void setInlayHints(java.util.List<InlayHint> hints) {
        if (largeFile || hints == null || hints.isEmpty()) {
            if (!inlayHintsByLine.isEmpty()) {
                inlayHintsByLine = java.util.Map.of();
                refreshInlayAreas();
            }
            return;
        }
        java.util.Map<Integer, java.util.List<org.fxmisc.richtext.Inlay>> byLine = new java.util.HashMap<>();
        for (InlayHint h : hints) {
            byLine.computeIfAbsent(h.line(), k -> new java.util.ArrayList<>())
                    .add(new org.fxmisc.richtext.Inlay(h.col(), h.label(), "inlay-hint"));
        }
        inlayHintsByLine = byLine;
        ensureInlayFactory();
        refreshInlayAreas();
    }

    /**
     * Installs the inlay factory on both views, once. The factory closes over the field rather than the
     * value, so pushing new hints is a map swap plus a refresh — no rebinding, and the split view stays in
     * step for free.
     */
    private void ensureInlayFactory() {
        if (inlayFactoryInstalled) {
            return;
        }
        inlayFactoryInstalled = true;
        java.util.function.IntFunction<java.util.List<org.fxmisc.richtext.Inlay>> factory = this::inlaysOn;
        area.setInlayFactory(factory);
        if (area2 != null) {
            area2.setInlayFactory(factory);
        }
    }

    /** The inlays of one line: its hints, then — after the last character — its code lens. */
    private java.util.List<org.fxmisc.richtext.Inlay> inlaysOn(int line) {
        java.util.List<org.fxmisc.richtext.Inlay> hints = inlayHintsByLine.get(line);
        java.util.List<CodeLens> lenses = codeLensByLine.get(line);
        if (lenses == null || line < 0 || line >= area.getParagraphs().size()) {
            return hints;
        }
        StringBuilder lens = new StringBuilder();
        for (CodeLens l : lenses) {
            lens.append(lens.isEmpty() ? "" : "  ·  ").append(l.label());
        }
        java.util.List<org.fxmisc.richtext.Inlay> out =
                hints == null ? new java.util.ArrayList<>(1) : new java.util.ArrayList<>(hints);
        out.add(new org.fxmisc.richtext.Inlay(area.getParagraphLength(line), lens.toString(), CODE_LENS_STYLE));
        return out;
    }

    /** One code lens: its 0-based line, its text ({@code 3 references}) and the click handler's own token. */
    public record CodeLens(int line, String label, Object token) {}

    private static final String CODE_LENS_STYLE = "code-lens";

    /** 0-based line → the lenses drawn after it, joined. Read live by the areas' inlay factory. */
    private java.util.Map<Integer, java.util.List<CodeLens>> codeLensByLine = java.util.Map.of();

    private java.util.function.BiConsumer<Integer, java.util.List<CodeLens>> codeLensHandler = (line, lenses) -> {};

    /**
     * Sets the code lenses, each drawn after the end of its line through the same inlay mechanism as the
     * hints (so it is not part of the document either). Several lenses on one line are joined. Null or
     * empty clears them.
     */
    public void setCodeLenses(java.util.List<CodeLens> lenses) {
        java.util.Map<Integer, java.util.List<CodeLens>> byLine = CodeLensShift.byLine(lenses, CodeLens::line);
        if (byLine.equals(codeLensByLine)) {
            return;
        }
        codeLensByLine = byLine.isEmpty() ? java.util.Map.of() : byLine;
        ensureInlayFactory();
        refreshInlayAreas();
    }

    /** What a click on a line's code lens does: gets the line as it is now, and the lenses on it. */
    public void setCodeLensHandler(java.util.function.BiConsumer<Integer, java.util.List<CodeLens>> handler) {
        this.codeLensHandler = handler == null ? (line, lenses) -> {} : handler;
    }

    /** The lenses on {@code line} (0-based) — empty when it has none. */
    public java.util.List<CodeLens> codeLensesOn(int line) {
        return codeLensByLine.getOrDefault(line, java.util.List.of());
    }

    /** Runs the click action of the lenses on {@code line}, as a click on them does. */
    public void activateCodeLens(int line) {
        java.util.List<CodeLens> lenses = codeLensByLine.get(line);
        if (lenses != null) {
            codeLensHandler.accept(line, lenses);
        }
    }

    /** Keeps the lenses on their declarations until the next answer arrives ({@link CodeLensShift}). */
    private void shiftCodeLenses(java.util.List<org.fxmisc.richtext.model.PlainTextChange> changes) {
        var moved = CodeLensShift.afterChanges(codeLensByLine, changes, area);
        if (moved != null) {
            codeLensByLine = moved;
            refreshInlayAreas();
        }
    }

    /** A click on a code lens runs its action instead of placing the caret. */
    private void installCodeLensClick(CodeArea a) {
        a.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY || codeLensByLine.isEmpty()) {
                return;
            }
            for (javafx.scene.Node n = e.getPickResult().getIntersectedNode(); n != null && n != a; n = n.getParent()) {
                if (n.getStyleClass().contains(CODE_LENS_STYLE)) {
                    int offset = a.hit(e.getX(), e.getY()).getInsertionIndex();
                    int line = a.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                            .getMajor();
                    e.consume();
                    activateCodeLens(line);
                    return;
                }
            }
        });
    }

    private void refreshInlayAreas() {
        if (!inlayFactoryInstalled) {
            return;
        }
        area.refreshInlays();
        if (area2 != null) {
            area2.refreshInlays();
        }
    }

    /** Injects the document-highlight request (the coordinator asks the server and pushes spans back);
     *  null disables the caret-idle trigger entirely. */
    public void setOccurrenceRequester(Runnable requester) {
        this.occurrenceRequester = requester;
    }

    /** Fired ~300 ms after the caret comes to rest while LSP-active; null = feature off. */
    private Runnable occurrenceRequester;

    /** Debounces the caret-rest trigger for document highlight (#675) — one per buffer, both views. */
    private final javafx.animation.PauseTransition occurrenceIdle =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));

    /**
     * Caret-idle trigger for document highlight: any caret move clears the wash at once (the old spans
     * are for the old symbol — leaving them paints the wrong word) and re-arms the 300 ms idle timer;
     * on rest, the injected requester asks the server. Three field checks per caret move when off.
     */
    private void installOccurrenceTrigger(CodeArea a) {
        a.caretPositionProperty().addListener((o, ov, nv) -> {
            if (occurrenceRequester == null || !lspActive || largeFile) {
                return;
            }
            clearOccurrenceSpans();
            occurrenceIdle.setOnFinished(ev -> {
                if (occurrenceRequester != null && lspActive && a.isFocused()) {
                    occurrenceRequester.run();
                }
            });
            occurrenceIdle.playFromStart();
        });
    }

    /** Enables/disables the TODO-pattern highlight for this buffer and re-scans (the controller pushes the
     *  effective {@code Settings.todoHighlight}). */
    public void setTodoHighlightEnabled(boolean enabled) {
        this.todoEnabled = enabled;
        minimap.setTodoEnabled(enabled && !largeFile);
        updateTodoStripe(); // (de)activate + position the scrollbar overview stripe
        refreshTodoMarks();
    }

    /** Injects the compiled-pattern matcher (decoupled from the {@code todo}/{@code config} packages) and
     *  re-scans. */
    public void setTodoMatcher(TodoMatcher matcher) {
        this.todoMatcher = matcher;
        refreshTodoMarks();
    }

    /** Discards an in-flight background TODO scan whose result would be stale (mirrors {@link #highlightGen}). */
    private long todoGen;

    /** Re-scans the buffer text and updates the highlight overlay + the scrollbar/minimap overview stripes;
     *  inert when off / no matcher / huge file. The multi-pattern scan runs off the FX thread (a generation
     *  counter discards a superseded result), so a large-but-under-cap file doesn't spend tens of ms scanning
     *  on the FX thread per debounced edit pulse — mirroring how {@link #applyHighlighting} tokenizes. */
    private void refreshTodoMarks() {
        if (disposed) {
            return; // see the disposed field
        }
        long gen = ++todoGen; // any newer pulse (or dispose) supersedes an in-flight scan
        if (!todoEnabled || todoMatcher == null || largeFile) {
            todoOverlay.setMarks(java.util.List.of());
            todoStripe.setMarks(java.util.List.of());
            minimap.setTodoMarks(java.util.List.of());
            return;
        }
        TodoMatcher matcher = todoMatcher; // may be reassigned on the FX thread; capture the current one
        String text = documentTextSnapshot();
        HIGHLIGHT_POOL.execute(() -> {
            java.util.List<TodoMark> marks;
            try {
                marks = matcher.match(text);
            } catch (RuntimeException e) {
                return; // never let a pathological user pattern kill the scan thread
            }
            Platform.runLater(() -> {
                if (gen != todoGen) {
                    return; // a newer edit/scan superseded this pass
                }
                todoOverlay.setMarks(marks);
                todoStripe.setMarks(marks);
                minimap.setTodoMarks(marks);
            });
        });
    }

    /** Starts AceJump: the next typed character labels its visible occurrences to jump the caret. */
    public void startAceJump() {
        aceJump().start();
    }

    /** Starts AceJump line-mode: every visible line is labeled at once; type a label to jump to that line
     *  (its first non-whitespace character). */
    public void startAceJumpLine() {
        aceJump().startLine();
    }

    /** The node to place in the scene: the read-only banner (when shown) above the editor view. */
    public Region getNode() {
        return outer;
    }

    /** {@link TabContent}: the editor node shown in the tab (delegates to {@link #getNode()}). */
    @Override
    public Region node() {
        return outer;
    }

    /** {@link TabContent}: the tab label (delegates to {@link #getTitle()}). */
    @Override
    public String title() {
        return getTitle();
    }

    public Split getSplit() {
        return split;
    }

    /** Toggles {@code orientation}: turns it off if already active, otherwise switches to it. */
    public void toggleSplit(Split orientation) {
        setSplit(split == orientation ? Split.NONE : orientation);
    }

    /** Shows or hides a second, synced view of this document beside ({@code SIDE_BY_SIDE}) or below it. */
    public void setSplit(Split orientation) {
        this.split = orientation;
        if (orientation != Split.NONE) {
            this.markdownViewMode = MarkdownViewMode.EDITOR; // a code split supersedes the Markdown preview
            ensureSecondaryView();
        }
        rebuildViewHost();
    }

    // --- Markdown preview (IntelliJ-style 3-mode view) -----------------------------------------

    public boolean isMarkdown() {
        return "markdown".equals(language);
    }

    /** A standalone Mermaid diagram file (.mmd) — the whole buffer is one diagram. */
    public boolean isDiagram() {
        return "mermaid".equals(language);
    }

    /** The rendered diagram-as-code kind (Graphviz DOT / PlantUML) for this buffer, or {@code null}. The
     *  whole buffer renders to one image via an external CLI (see {@link DiagramImages}); Mermaid stays on
     *  its own {@link #isDiagram()} path. */
    public DiagramKind diagramKind() {
        return DiagramKind.fromLanguage(language);
    }

    /** Whether this buffer is a DOT/PlantUML diagram (renders via {@link DiagramImages}). */
    public boolean isRenderedDiagram() {
        return diagramKind() != null;
    }

    /** A Markwhen timeline file (.mw/.markwhen) — the whole buffer renders as one timeline preview. */
    public boolean isMarkwhen() {
        return "markwhen".equals(language);
    }

    /** The current Markwhen preview renderer (timeline vs. calendar). */
    public MarkwhenView getMarkwhenView() {
        return markwhenView;
    }

    /** Sets the Markwhen preview renderer (restore path — no callback/re-render side effects here beyond
     *  a re-render when a preview is showing). */
    public void setMarkwhenView(MarkwhenView view) {
        if (view != null && view != markwhenView) {
            markwhenView = view;
            if (markdownViewMode != MarkdownViewMode.EDITOR) {
                scheduleRenderPreview();
            }
        }
    }

    /** Flips timeline ⇄ calendar, re-renders the preview, and notifies the controller to persist. */
    public void toggleMarkwhenView() {
        markwhenView = markwhenView == MarkwhenView.TIMELINE ? MarkwhenView.CALENDAR : MarkwhenView.TIMELINE;
        if (markdownViewMode != MarkdownViewMode.EDITOR) {
            scheduleRenderPreview();
        }
        onMarkwhenViewChanged.run();
    }

    /** Injects the persist callback fired when the Markwhen view flips. */
    public void setOnMarkwhenViewChanged(Runnable callback) {
        this.onMarkwhenViewChanged = callback == null ? () -> {} : callback;
    }

    /** An HTML file (.html/.htm/.xhtml) — eligible for the HTML Live Preview "open in browser" control. */
    public boolean isHtml() {
        return "html".equals(language);
    }

    /** A delimiter-separated-values file (.csv/.tsv) — drives the status-bar column readout + CSV commands. */
    public boolean isCsv() {
        return "csv".equals(language);
    }

    /** Whether this buffer supports the 3-mode preview: Markdown always, Mermaid only while the feature
     *  is enabled (so a .mmd file is plain text with no preview affordance when Mermaid is off), and CSV
     *  only once the grid preview node has been injected (the feature-on gate — see {@link #hasCsvPreview}). */
    /**
     * Leaves the preview when — and only when — this buffer has no preview left. Called after any
     * {@code set*PreviewEnabled} flip.
     *
     * <p>Each setter used to ask "is this file my format?" instead, which is wrong both ways. One setting can
     * back several formats (an XML file's DOM tree rides {@code structuredPreview}, but {@code isStructured()}
     * is false for XML) — so the buffer was left stranded in a preview whose feature is off: every branch of
     * {@link #scheduleRenderPreview} then misses and falls through to the Markdown tail, rendering the source
     * as Markdown, while {@code hasPreview()} being false takes away the toggle needed to escape. And one file
     * can have two previews (a workflow is also YAML) — so turning off the unrelated one clobbered a
     * per-file view mode that is persisted in {@code WorkspaceState.markdownViewModes}.
     */
    private void reconcilePreviewMode() {
        if (!hasPreview() && markdownViewMode != MarkdownViewMode.EDITOR) {
            setMarkdownViewMode(MarkdownViewMode.EDITOR);
        }
    }

    public boolean hasPreview() {
        return isMarkdown()
                || isMarkwhen()
                || (isDiagram() && MermaidImages.isEnabled())
                || (isRenderedDiagram() && DiagramImages.isEnabled())
                || hasCsvPreview()
                || hasHttpPreview()
                || hasStructuredPreview()
                || hasPomPreview()
                || hasXmlPreview()
                || hasSvgPreview()
                || hasCrontabPreview()
                || hasFstabPreview()
                || hasSystemdPreview()
                || hasSshConfigPreview()
                || hasDockerfilePreview()
                || hasGithubActionsPreview()
                || hasTypstPreview();
    }

    /** A CSV buffer whose grid preview node has been injected (i.e. the CSV preview feature is on). The
     *  injected node doubles as the enablement gate, mirroring {@link #htmlPreviewControl}. */
    public boolean hasCsvPreview() {
        return isCsv() && csvPreviewNode != null;
    }

    /** A {@code .http} buffer whose response panel has been injected (i.e. the HTTP Client feature is on).
     *  The injected node doubles as the enablement gate, mirroring {@link #hasCsvPreview()}. */
    public boolean hasHttpPreview() {
        return isHttpFile() && httpPreviewNode != null;
    }

    /** A standalone SVG file (by {@code .svg} extension — the buffer stays XML text, so it also gets XML
     *  highlighting/LSP, but gains a rendered preview). */
    public boolean isSvg() {
        String name = path != null ? path.getFileName().toString() : (displayName == null ? "" : displayName);
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(".svg");
    }

    /** Whether the SVG image preview should be offered (feature on + a .svg buffer + not a huge file). */
    public boolean hasSvgPreview() {
        return svgPreviewEnabled && isSvg() && !hugeFile;
    }

    /** Pushes the SVG-preview feature gate (from Settings); drops back to source when turned off. */
    public void setSvgPreviewEnabled(boolean enabled) {
        if (this.svgPreviewEnabled == enabled) {
            return;
        }
        this.svgPreviewEnabled = enabled;
        reconcilePreviewMode();
    }

    /** A Typst document buffer (.typ). The whole buffer renders to a multi-page image preview via the typst
     *  CLI (see {@link TypstImages}). */
    public boolean isTypst() {
        return "typst".equals(language);
    }

    /** Whether the Typst document preview should be offered (feature on + a .typ buffer + not a huge file). */
    public boolean hasTypstPreview() {
        return typstPreviewEnabled && isTypst() && !hugeFile;
    }

    /** Pushes the Typst-preview feature gate (from Settings); drops back to source when turned off. */
    public void setTypstPreviewEnabled(boolean enabled) {
        if (this.typstPreviewEnabled == enabled) {
            return;
        }
        this.typstPreviewEnabled = enabled;
        reconcilePreviewMode();
    }

    /** Injects the typst {@code --root} resolver (file path → root dir); see {@link #typstRootResolver}. */
    public void setTypstRootResolver(java.util.function.UnaryOperator<java.nio.file.Path> resolver) {
        this.typstRootResolver = resolver;
    }

    /** A structured-data buffer (JSON/YAML/TOML) whose format the {@link StructuredParser} recognizes. */
    public boolean isStructured() {
        return structuredFormat() != null;
    }

    /** The structured format for this buffer's language, or {@code null} if it isn't one. */
    public StructuredParser.Format structuredFormat() {
        return StructuredParser.Format.forLanguage(language);
    }

    /** Whether the structured tree/OpenAPI preview should be offered (feature on, and not a huge file). */
    public boolean hasStructuredPreview() {
        return structuredPreviewEnabled && isStructured() && !hugeFile;
    }

    /** An XML buffer (excluding {@code .svg}, which renders as an image). Reuses the structured-preview gate. */
    public boolean isXml() {
        return "xml".equals(language) && !isSvg();
    }

    /** Whether the XML DOM-tree preview should be offered (structured-preview feature on, XML, not huge). */
    public boolean hasXmlPreview() {
        return structuredPreviewEnabled && isXml() && !hugeFile;
    }

    /** A crontab buffer (language {@code crontab}: a crontab / *.cron / cron.d file — see ConfigFileType). */
    public boolean isCrontab() {
        return "crontab".equals(language);
    }

    /** Whether the crontab schedule preview should be offered (feature on + a crontab buffer + not huge). */
    public boolean hasCrontabPreview() {
        return crontabPreviewEnabled && isCrontab() && !hugeFile;
    }

    /** Pushes the crontab-preview feature gate (from Settings); drops back to source when turned off. */
    public void setCrontabPreviewEnabled(boolean enabled) {
        if (this.crontabPreviewEnabled == enabled) {
            return;
        }
        this.crontabPreviewEnabled = enabled;
        reconcilePreviewMode();
    }

    /** An {@code /etc/fstab} buffer (language {@code fstab} — see ConfigFileType). */
    public boolean isFstab() {
        return "fstab".equals(language);
    }

    /** Whether the fstab mount preview should be offered (feature on + an fstab buffer + not huge). */
    public boolean hasFstabPreview() {
        return fstabPreviewEnabled && isFstab() && !hugeFile;
    }

    /** Pushes the fstab-preview feature gate (from Settings); drops back to source when turned off. */
    public void setFstabPreviewEnabled(boolean enabled) {
        if (this.fstabPreviewEnabled == enabled) {
            return;
        }
        this.fstabPreviewEnabled = enabled;
        reconcilePreviewMode();
    }

    /** A systemd unit buffer (language {@code systemd}: .service/.timer/.socket/… — see LanguageRegistry). */
    public boolean isSystemd() {
        return "systemd".equals(language);
    }

    /** Whether the systemd unit preview should be offered (feature on + a systemd buffer + not huge). */
    public boolean hasSystemdPreview() {
        return systemdPreviewEnabled && isSystemd() && !hugeFile;
    }

    /** Pushes the systemd-preview feature gate (from Settings); drops back to source when turned off. */
    public void setSystemdPreviewEnabled(boolean enabled) {
        if (this.systemdPreviewEnabled == enabled) {
            return;
        }
        this.systemdPreviewEnabled = enabled;
        reconcilePreviewMode();
    }

    /** An SSH client-config buffer (language {@code ssh-config} — see ConfigFileType). */
    public boolean isSshConfig() {
        return "ssh-config".equals(language);
    }

    /** Whether the SSH-config preview should be offered (feature on + an ssh-config buffer + not huge). */
    public boolean hasSshConfigPreview() {
        return sshConfigPreviewEnabled && isSshConfig() && !hugeFile;
    }

    /** Pushes the ssh-config-preview feature gate (from Settings); drops back to source when turned off. */
    public void setSshConfigPreviewEnabled(boolean enabled) {
        if (this.sshConfigPreviewEnabled == enabled) {
            return;
        }
        this.sshConfigPreviewEnabled = enabled;
        reconcilePreviewMode();
    }

    /** A Dockerfile buffer (language {@code dockerfile} — see ConfigFileType). */
    public boolean isDockerfile() {
        return "dockerfile".equals(language);
    }

    /** Whether the Dockerfile stage preview should be offered (feature on + a Dockerfile buffer + not huge). */
    public boolean hasDockerfilePreview() {
        return dockerfilePreviewEnabled && isDockerfile() && !hugeFile;
    }

    /** Pushes the Dockerfile-preview feature gate (from Settings); drops back to source when turned off. */
    public void setDockerfilePreviewEnabled(boolean enabled) {
        if (this.dockerfilePreviewEnabled == enabled) {
            return;
        }
        this.dockerfilePreviewEnabled = enabled;
        reconcilePreviewMode();
    }

    /** A GitHub Actions workflow buffer — a YAML file whose content matches the workflow signature (on+jobs).
     *  Detected by content (not path/extension) so it works anywhere; reads only a bounded head of the file. */
    public boolean isGithubActions() {
        if (!"yaml".equals(language)) {
            return false;
        }
        int len = area.getLength();
        String head = area.getText(0, Math.min(len, 65536));
        return com.editora.ghactions.GithubActions.looksLikeWorkflow(head);
    }

    /** Whether the GitHub Actions workflow preview should be offered (feature on + a workflow + not huge). */
    public boolean hasGithubActionsPreview() {
        return githubActionsPreviewEnabled && !hugeFile && isGithubActions();
    }

    /** Pushes the GitHub-Actions-preview feature gate (from Settings); drops back to the YAML tree/source when off. */
    public void setGithubActionsPreviewEnabled(boolean enabled) {
        if (this.githubActionsPreviewEnabled == enabled) {
            return;
        }
        this.githubActionsPreviewEnabled = enabled;
        reconcilePreviewMode();
    }

    /**
     * A Maven pom.xml — an XML buffer named {@code pom.xml}/{@code *.pom}, or any other XML file carrying a
     * {@code <project>} root with a {@code <modelVersion>} (so a {@code pom-template.xml} or a generated
     * {@code effective-pom.xml} is recognized too). Reads only a bounded head, like {@link #isGithubActions()}.
     */
    public boolean isPom() {
        if (!isXml()) {
            return false;
        }
        String fileName = path != null ? path.getFileName().toString() : displayName;
        if (fileName != null && ("pom.xml".equalsIgnoreCase(fileName) || fileName.endsWith(".pom"))) {
            return true;
        }
        String head = area.getText(0, Math.min(area.getLength(), POM_SNIFF_CHARS));
        return head.contains("<modelVersion") && head.contains("<project");
    }

    /**
     * Whether the pom summary is the preview to render — the feature is on, this is a pom, and the user
     * hasn't switched this buffer to the generic XML tree. Independent of {@link #hasXmlPreview()}'s
     * structured-preview gate, so turning the XML tree off still leaves a pom its own preview.
     */
    public boolean hasPomPreview() {
        return pomPreviewEnabled && !hugeFile && !pomShowXml && isPom();
    }

    /** Pushes the pom-preview feature gate (from Settings); drops back to the XML tree/source when off. */
    public void setPomPreviewEnabled(boolean enabled) {
        if (this.pomPreviewEnabled == enabled) {
            return;
        }
        this.pomPreviewEnabled = enabled;
        reconcilePreviewMode();
    }

    /** Whether this pom buffer is currently showing the generic XML tree instead of the pom summary. */
    public boolean isPomShowingXml() {
        return pomShowXml;
    }

    /**
     * Flips a pom between its summary and the standard XML tree, then re-renders. Refuses the XML direction
     * when the structured-data preview (which owns the XML tree) is switched off, rather than flipping into a
     * view that isn't there and stranding the buffer back in the editor.
     *
     * @return false when the flip was refused for that reason
     */
    public boolean togglePomView() {
        if (!pomShowXml && !structuredPreviewEnabled) {
            return false;
        }
        pomShowXml = !pomShowXml;
        if (markdownViewMode != MarkdownViewMode.EDITOR) {
            scheduleRenderPreview();
        }
        return true;
    }

    /** Whether this buffer uses the self-scrolling tree host (structured, XML, crontab, or fstab) — shared surface. */
    private boolean hasTreePreview() {
        return hasStructuredPreview()
                || hasPomPreview()
                || hasXmlPreview()
                || hasCrontabPreview()
                || hasFstabPreview()
                || hasSystemdPreview()
                || hasSshConfigPreview()
                || hasDockerfilePreview()
                || hasGithubActionsPreview();
    }

    /** Pushes the structured-preview feature gate (from Settings); re-attaches the toggle if it flipped. */
    public void setStructuredPreviewEnabled(boolean enabled) {
        if (this.structuredPreviewEnabled == enabled) {
            return;
        }
        this.structuredPreviewEnabled = enabled;
        reconcilePreviewMode();
    }

    /** Whether the last render of this structured buffer detected an OpenAPI/Swagger spec. */
    public boolean isStructuredOpenApi() {
        return lastStructuredOpenApi;
    }

    /** Flips a structured doc between the tree and the OpenAPI-docs view, then re-renders the preview. */
    public void toggleStructuredView() {
        boolean currentDocs = structuredShowApiDocs == null || structuredShowApiDocs;
        structuredShowApiDocs = !currentDocs;
        if (markdownViewMode != MarkdownViewMode.EDITOR) {
            scheduleRenderPreview();
        }
    }

    // --- Live Mermaid linting (maid) ----------------------------------------------------------------

    /** Injects the async maid validator: {@code accept(text, diagnostics->…)}; null disables linting. */
    public void setMermaidValidator(
            java.util.function.BiConsumer<
                            String,
                            java.util.function.Consumer<java.util.List<com.editora.mermaid.MaidOutput.Diagnostic>>>
                    validator) {
        this.mermaidValidator = validator;
    }

    /** Turns live linting on/off for this buffer (controller gates on mermaid enabled + maid detected). */
    public void setMermaidLintEnabled(boolean on) {
        this.mermaidLintEnabled = on && isDiagram();
        if (this.mermaidLintEnabled || lintOverlay != null) {
            lintOverlay().setActive(this.mermaidLintEnabled);
        }
        if (this.mermaidLintEnabled) {
            scheduleMermaidLint();
        }
    }

    private void scheduleMermaidLint() {
        if (!mermaidLintEnabled || !isDiagram() || hugeFile || mermaidValidator == null) {
            return;
        }
        mermaidValidator.accept(documentTextSnapshot(), lintOverlay()::setDiagnostics);
    }

    /** Shows the maid message(s) in a tooltip when hovering a squiggled span (overlay is mouse-transparent). */
    private void installLintHover(CodeArea a) {
        a.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (!mermaidLintEnabled
                    || lintOverlay == null
                    || lintOverlay.diagnostics().isEmpty()) {
                if (lintTooltip != null) {
                    lintTooltip.hide();
                }
                return;
            }
            try {
                var hit = a.hit(e.getX(), e.getY());
                var pos = a.offsetToPosition(
                        hit.getInsertionIndex(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
                var hits = lintOverlay.at(pos.getMajor(), pos.getMinor());
                if (hits.isEmpty()) {
                    if (lintTooltip != null) {
                        lintTooltip.hide();
                    }
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (var d : hits) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(d.message());
                }
                String text = sb.toString();
                // Already showing this exact message → don't re-show (re-positioning per pixel = flicker).
                if (lintTooltip != null && lintTooltip.isShowing() && text.equals(lintTooltipText)) {
                    return;
                }
                if (lintTooltip == null) {
                    lintTooltip = new javafx.scene.control.Tooltip();
                    lintTooltip.getStyleClass().add("mermaid-lint-tooltip");
                    lintTooltip.setWrapText(true);
                    lintTooltip.setMaxWidth(420);
                }
                lintTooltip.setText(text);
                lintTooltipText = text;
                lintTooltip.show(a, e.getScreenX() + 12, e.getScreenY() + 16);
            } catch (RuntimeException ignored) {
                // viewport mid-layout / hit miss — ignore
            }
        });
        a.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (lintTooltip != null) {
                lintTooltip.hide();
            }
        });
    }

    // --- Live Markdown linting -----------------------------------------------------------------------

    /** Injects the async Markdown linter: {@code accept(text, diagnostics->…)}; null disables linting. */
    public void setMarkdownLintValidator(
            java.util.function.BiConsumer<String, java.util.function.Consumer<java.util.List<MarkdownLint.Diagnostic>>>
                    validator) {
        this.markdownLintValidator = validator;
    }

    /** Turns Markdown linting on/off for this buffer (controller gates on the setting; Markdown buffers only). */
    public void setMarkdownLintEnabled(boolean on) {
        this.markdownLintEnabled = on && isMarkdown() && !hugeFile;
        mdLintOverlay.setActive(this.markdownLintEnabled);
        minimap.setLintEnabled(this.markdownLintEnabled);
        updateMarkdownLintStripe();
        if (this.markdownLintEnabled) {
            scheduleMarkdownLint();
        } else {
            mdLintStripe.setDiagnostics(java.util.List.of());
            minimap.setLintMarks(java.util.List.of());
        }
    }

    private void scheduleMarkdownLint() {
        if (!markdownLintEnabled || !isMarkdown() || hugeFile || markdownLintValidator == null) {
            return;
        }
        markdownLintValidator.accept(documentTextSnapshot(), this::applyMarkdownLintDiagnostics);
    }

    /** Pushes fresh lint diagnostics to the squiggle overlay + the scrollbar stripe + the minimap ticks. */
    private void applyMarkdownLintDiagnostics(java.util.List<MarkdownLint.Diagnostic> diags) {
        mdLintOverlay.setDiagnostics(diags);
        mdLintStripe.setDiagnostics(diags);
        minimap.setLintMarks(diags);
    }

    /** Shows the lint message(s) in a tooltip when hovering a squiggled span (overlay is mouse-transparent). */
    private void installMarkdownLintHover(CodeArea a) {
        a.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (!markdownLintEnabled || mdLintOverlay.diagnostics().isEmpty()) {
                if (mdLintTooltip != null) {
                    mdLintTooltip.hide();
                }
                return;
            }
            try {
                var hit = a.hit(e.getX(), e.getY());
                var pos = a.offsetToPosition(
                        hit.getInsertionIndex(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
                var hits = mdLintOverlay.at(pos.getMajor(), pos.getMinor());
                if (hits.isEmpty()) {
                    if (mdLintTooltip != null) {
                        mdLintTooltip.hide();
                    }
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (var d : hits) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(d.code()).append(": ").append(d.message());
                }
                String text = sb.toString();
                if (mdLintTooltip != null && mdLintTooltip.isShowing() && text.equals(mdLintTooltipText)) {
                    return;
                }
                if (mdLintTooltip == null) {
                    mdLintTooltip = new javafx.scene.control.Tooltip();
                    mdLintTooltip.getStyleClass().add("mermaid-lint-tooltip");
                    mdLintTooltip.setWrapText(true);
                    mdLintTooltip.setMaxWidth(420);
                }
                mdLintTooltip.setText(text);
                mdLintTooltipText = text;
                mdLintTooltip.show(a, e.getScreenX() + 12, e.getScreenY() + 16);
            } catch (RuntimeException ignored) {
                // viewport mid-layout / hit miss — ignore
            }
        });
        a.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (mdLintTooltip != null) {
                mdLintTooltip.hide();
            }
        });
    }

    /** Injects the handler for image files dropped onto a Markdown buffer; null disables the drop affordance. */
    public void setImageDropHandler(java.util.function.Consumer<java.util.List<java.io.File>> handler) {
        this.imageDropHandler = handler;
    }

    /**
     * Injects the handler for a raw image (or image URL) dragged from a web browser onto a Markdown buffer;
     * the controller downloads/encodes it into {@code assets/} and inserts a link. Args: the dragged
     * {@code Image} (or null) and the image URL/data-URI (or null) — at least one is non-null.
     */
    public void setWebImageDropHandler(java.util.function.BiConsumer<javafx.scene.image.Image, String> handler) {
        this.webImageDropHandler = handler;
    }

    /** The image URL a browser drag carries (its URL, an {@code <img src>} in the HTML, or an image string). */
    private static String webImageUrl(javafx.scene.input.Dragboard db) {
        return com.editora.markdown.MarkdownImagePaste.imageUrlFromDrag(
                db.hasUrl() ? db.getUrl() : null,
                db.hasHtml() ? db.getHtml() : null,
                db.hasString() ? db.getString() : null);
    }

    /**
     * Accepts dropped images on a Markdown buffer (copy/download into {@code assets/} + insert {@code ![](…)}):
     * image <em>files</em> from the OS, and a raw image / image URL dragged from a web browser.
     */
    private void installImageDrop(CodeArea a) {
        a.addEventHandler(javafx.scene.input.DragEvent.DRAG_OVER, e -> {
            if ((!isMarkdown() && !isTypst()) || !isEditable()) {
                return;
            }
            javafx.scene.input.Dragboard db = e.getDragboard();
            boolean file = imageDropHandler != null && db.hasFiles() && hasImageFile(db.getFiles());
            boolean web = webImageDropHandler != null && (db.hasImage() || webImageUrl(db) != null);
            if (file || web) {
                e.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
                e.consume();
            }
        });
        a.addEventHandler(javafx.scene.input.DragEvent.DRAG_DROPPED, e -> {
            if ((!isMarkdown() && !isTypst()) || !isEditable()) {
                return;
            }
            javafx.scene.input.Dragboard db = e.getDragboard();
            // Prefer real image files (self-contained copy); fall back to a browser image (download/encode).
            if (imageDropHandler != null && db.hasFiles()) {
                java.util.List<java.io.File> images =
                        db.getFiles().stream().filter(EditorBuffer::isImageFile).toList();
                if (!images.isEmpty()) {
                    caretToDrop(a, e);
                    imageDropHandler.accept(images);
                    e.setDropCompleted(true);
                    e.consume();
                    return;
                }
            }
            if (webImageDropHandler != null) {
                String url = webImageUrl(db);
                javafx.scene.image.Image img = db.hasImage() ? db.getImage() : null;
                if (url != null || img != null) {
                    caretToDrop(a, e);
                    webImageDropHandler.accept(img, url);
                    e.setDropCompleted(true);
                    e.consume();
                }
            }
        });
    }

    /** A drop lands under the pointer, in the view it was dropped on — a window dragged into has no focus yet. */
    private void caretToDrop(CodeArea a, javafx.scene.input.DragEvent e) {
        focusedArea = a;
        focusedView.set(a);
        EditorMouse.moveCaretToDrop(a, e.getX(), e.getY());
    }

    private static boolean hasImageFile(java.util.List<java.io.File> files) {
        return files.stream().anyMatch(EditorBuffer::isImageFile);
    }

    private static boolean isImageFile(java.io.File f) {
        String n = f.getName().toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".png")
                || n.endsWith(".jpg")
                || n.endsWith(".jpeg")
                || n.endsWith(".gif")
                || n.endsWith(".bmp")
                || n.endsWith(".webp")
                || n.endsWith(".svg");
    }

    // --- LSP (Language Server Protocol) integration ---------------------------------------------

    /** Language ids that have a language server (Java, JS/TS/JSX/TSX, Python, XML, JSON, shell, YAML, Go,
     *  Rust, PHP, Ruby, C/C++, HTML, CSS, Kotlin, Lua, Dockerfile, SQL, Terraform, TOML, Typst, Astro). Hardcoded here
     *  so {@code editor} need not depend on the {@code lsp} package (kept in sync with
     *  {@code LspServerRegistry}). */
    private static final java.util.Set<String> LSP_LANGUAGES = java.util.Set.of(
            "java",
            "javascript",
            "javascriptreact",
            "typescript",
            "typescriptreact",
            "python",
            "xml",
            "json",
            "shell",
            "yaml",
            "go",
            "rust",
            "php",
            "ruby",
            "c",
            "cpp",
            "html",
            "css",
            "kotlin",
            "lua",
            "dockerfile",
            "sql",
            "terraform",
            "toml",
            "csharp",
            "typst",
            "astro");

    /** Whether this buffer's language has a language server. */
    public boolean isLspLanguage() {
        return LSP_LANGUAGES.contains(language);
    }

    /** Injects the debounced didChange sink (text → controller → server); null disables change notices. */
    public void setLspChangeListener(java.util.function.Consumer<String> listener) {
        this.lspChangeListener = listener;
    }

    /**
     * Sends the current document text to the language server — unless it already has this version (the same
     * {@link #docVersion} as the last send). Both the completion flush and the debounced edit pulse call this for
     * one edit; the guard drops the second, avoiding a redundant whole-document {@code getText()} + {@code
     * didChange}. Split views share the document, so {@code area.getText()} is the canonical full text.
     */
    public void sendLspChange() {
        if (lspChangeListener == null || docVersion == lastLspSentVersion) {
            return;
        }
        lastLspSentVersion = docVersion;
        lspChangeListener.accept(documentTextSnapshot());
    }

    /** Injects the debounced pull-diagnostics request (fired on the same pulse as didChange); null disables. */
    public void setLspDiagnosticsRequester(Runnable requester) {
        this.lspDiagnosticsRequester = requester;
    }

    /**
     * Installs the foldable regions a language server reported (#738), replacing the brace/indent heuristic;
     * an empty list restores it. Keeps {@code editor} free of lsp4j — the caller maps the LSP response to
     * {@link FoldRegions.Region} first.
     */
    public void setLspFoldingRegions(java.util.List<FoldRegions.Region> regions) {
        folds.setServerRegions(regions);
    }

    /**
     * The document offset each line starts at, for translating an LSP line/character position to an offset
     * (#739). Index {@code i} is line {@code i}'s start; the array has one entry per paragraph.
     */
    public int[] lineStartOffsets() {
        int count = area.getParagraphs().size();
        int[] starts = new int[count];
        int offset = 0;
        for (int i = 0; i < count; i++) {
            starts[i] = offset;
            offset += area.getParagraph(i).length() + 1; // + the newline
        }
        return starts;
    }

    /** Async on-type formatter (#740): the edits the server would apply after {@code ch} at a position. */
    public interface LspOnTypeFormatter {
        void format(int line, int character, char ch, java.util.function.Consumer<java.util.List<LspTextEdit>> cb);
    }

    /** Injects the on-type formatting request (#740); null disables it. */
    public void setLspOnTypeFormatter(LspOnTypeFormatter formatter) {
        this.lspOnTypeFormatter = formatter;
    }

    /** The characters the server formats on (empty = none / not yet known), refreshed when it reports ready. */
    public void setLspOnTypeTriggers(java.util.Set<Character> chars) {
        this.lspOnTypeTriggers = chars == null ? java.util.Set.of() : chars;
    }

    /** The {@code Settings.lspOnTypeFormatting} master gate (#740), pushed by {@code applyViewSettings}. */
    public void setOnTypeFormattingEnabled(boolean enabled) {
        this.onTypeFormattingEnabled = enabled;
    }

    /** Sets the completion trigger characters the server advertised (empty = none / not yet known). */
    public void setLspTriggerChars(java.util.Set<Character> chars) {
        this.lspTriggerChars = chars == null ? java.util.Set.of() : chars;
    }

    /** Sets the signature-help trigger characters the server advertised — typing one fires the injected
     *  requester (#674). Empty = none / not yet known. */
    public void setLspSignatureTriggerChars(java.util.Set<Character> chars) {
        this.lspSignatureTriggerChars = chars == null ? java.util.Set.of() : chars;
    }

    /**
     * Injects the signature-help request (the coordinator shows the popup); null disables auto-trigger.
     * The argument is the <b>trigger character just typed</b> — the server is told
     * {@code triggerKind=TriggerCharacter} with that char rather than a blanket {@code Invoked} (#725).
     */
    public void setSignatureHelpRequester(java.util.function.Consumer<Character> requester) {
        this.signatureHelpRequester = requester;
    }

    /** True if this file can be run (a Java 25 compact source file, a Python script, or a shell script
     *  when the Bash LSP is enabled). */
    public boolean isRunnable() {
        return runnable;
    }

    /** True when the single-file Run entry is a compact Java source method (rather than a test glyph). */
    public boolean isCompactSource() {
        return "java".equals(language) && runLine >= 0;
    }

    /** True specifically for Python (the controller picks {@code python}, vs {@code java}, as the runner). */
    public boolean isPython() {
        return "python".equals(language);
    }

    /** True specifically for a shell script (the controller picks {@code bash} as the runner). */
    public boolean isShell() {
        return "shell".equals(language);
    }

    /** Sets the callback fired when {@link #isRunnable()} flips (so the controller refreshes the Run button). */
    public void setOnRunnableChanged(Runnable callback) {
        this.onRunnableChanged = callback == null ? () -> {} : callback;
    }

    /** Enables/disables the local-file Run affordance. When off, the gutter Run glyph,
     *  the right-click Run item, and the Run tool window all disappear. */
    public void setRunEnabled(boolean enabled) {
        if (enabled != runFeatureEnabled) {
            runFeatureEnabled = enabled;
            recomputeRun();
        }
    }

    /** Enables/disables the Run glyph for shell scripts (gated by the Bash LSP server toggle). Java/Python
     *  runnability is unaffected — this only governs whether a {@code .sh}/bash file shows the glyph. */
    public void setShellRunEnabled(boolean enabled) {
        if (enabled != shellRunEnabled) {
            shellRunEnabled = enabled;
            recomputeRun();
        }
    }

    /** True for a {@code .http}/{@code .rest} buffer (drives the HTTP Client run glyphs + tool window). */
    public boolean isHttpFile() {
        return "http".equals(language);
    }

    /** Enables/disables the HTTP Client Run glyphs for a {@code .http} buffer (gated by the feature +
     *  ijhttp detection). */
    public void setHttpEnabled(boolean enabled) {
        if (enabled != httpFeatureEnabled) {
            httpFeatureEnabled = enabled;
            recomputeRun();
        }
    }

    /** Injects the handler run with a request's start line when its {@code .http} gutter ▶ is clicked. */
    public void setHttpRunHandler(java.util.function.IntConsumer handler) {
        this.httpRunHandler = handler == null ? i -> {} : handler;
    }

    /** True for a GNU Makefile buffer — each rule target line then shows a Run glyph ({@code make <target>}),
     *  and the generic "Run File" command runs the default goal ({@code make}). */
    public boolean isMakefile() {
        return "makefile".equals(language);
    }

    /** Injects the handler run with a target's name when its Makefile gutter ▶ is clicked. */
    public void setMakeRunHandler(java.util.function.Consumer<String> handler) {
        this.makeRunHandler = handler == null ? t -> {} : handler;
    }

    /** Enables/disables the JUnit test gutter ▶ (gated by the Test Runner feature + a detected JVM build
     *  tool). When on, a Java buffer's test class + each test method get a Run glyph. */
    public void setTestGutterEnabled(boolean enabled) {
        if (enabled != testGutterEnabled) {
            testGutterEnabled = enabled;
            recomputeRun();
        }
    }

    /** Injects the handler run with the clicked test target (class ▶ has {@code methodName == null}). */
    public void setTestRunHandler(java.util.function.Consumer<com.editora.test.JavaTestScanner.TestTarget> handler) {
        this.testRunHandler = handler == null ? t -> {} : handler;
    }

    /** Injects the handler that debugs the test class/method selected by the editor context menu. */
    public void setTestDebugHandler(java.util.function.Consumer<com.editora.test.JavaTestScanner.TestTarget> handler) {
        this.testDebugHandler = handler == null ? t -> {} : handler;
    }

    /** Gate for the project {@code main}-method gutter ▶ (a Java file in a Maven/Gradle project with run/debug
     *  available); pushed by MainController. */
    public void setMainGutterEnabled(boolean enabled) {
        if (enabled != mainGutterEnabled) {
            mainGutterEnabled = enabled;
            recomputeRun();
        }
    }

    /** Injects the handler run with a clicked {@code main}-method gutter ▶ (runs that project main class). */
    public void setMainRunHandler(java.util.function.Consumer<com.editora.run.MainMethodScanner.MainMethod> handler) {
        this.mainRunHandler = handler == null ? m -> {} : handler;
    }

    /** Injects the handler for the editor "Debug Main Class" item on a {@code main} method. */
    public void setMainDebugHandler(java.util.function.Consumer<com.editora.run.MainMethodScanner.MainMethod> handler) {
        this.mainDebugHandler = handler == null ? m -> {} : handler;
    }

    /** The project {@code main} entry point at (or nearest above) the caret, else the file's first main, else
     *  {@code null} — backs the editor right-click Run/Debug Main Class items. */
    public com.editora.run.MainMethodScanner.MainMethod mainTargetAtCaret() {
        return mainTargetAt(focusedArea.getCurrentParagraph());
    }

    private com.editora.run.MainMethodScanner.MainMethod mainTargetAt(int caret) {
        if (mainLines.isEmpty()) {
            return null;
        }
        com.editora.run.MainMethodScanner.MainMethod best = null;
        for (var e : mainLines.entrySet()) {
            if (e.getKey() <= caret && (best == null || e.getKey() > best.line())) {
                best = e.getValue();
            }
        }
        return best != null ? best : mainLines.values().iterator().next();
    }

    /**
     * The whole-class JUnit target for this buffer (the "Run Tests" context-menu item), or {@code null} when
     * this isn't a test file / the test gutter is off.
     */
    public com.editora.test.JavaTestScanner.TestTarget testClassTarget() {
        for (var t : testLines.values()) {
            if (t.methodName() == null) {
                return t; // the class-declaration target the scanner emits first
            }
        }
        return null;
    }

    /**
     * The JUnit test target at or nearest above the caret, for {@code test.runAtCaret}/{@code runClassAtCaret}.
     * {@code classLevel} forces the whole-class target (methodName null). Returns null when the caret isn't in
     * a test class.
     */
    public com.editora.test.JavaTestScanner.TestTarget testTargetAtCaret(boolean classLevel) {
        return testTargetAt(focusedArea.getCurrentParagraph(), classLevel);
    }

    private com.editora.test.JavaTestScanner.TestTarget testTargetAt(int caret, boolean classLevel) {
        com.editora.test.JavaTestScanner.TestTarget best = null;
        for (var e : testLines.entrySet()) {
            if (e.getKey() <= caret && (best == null || e.getKey() > best.line())) {
                best = e.getValue();
            }
        }
        if (best == null) {
            return null;
        }
        return classLevel
                ? new com.editora.test.JavaTestScanner.TestTarget(best.line(), best.className(), null, false)
                : best;
    }

    /** Whether {@code line} draws a Run glyph: every request line for an enabled {@code .http} buffer,
     *  else the single script entry line. */
    private boolean isRunGlyphLine(int line) {
        if (mainLines.containsKey(line)) {
            return true; // a project main ▶ draws independently of `runnable` (see recomputeRun)
        }
        if (!runnable) {
            return false;
        }
        if (testLines.containsKey(line)) {
            return true;
        }
        if (httpFeatureEnabled && isHttpFile()) {
            return httpRequestLines.contains(line);
        }
        if (isMakefile()) {
            return makeTargets.containsKey(line);
        }
        return line == runLine;
    }

    /** Dispatches a Run-glyph click: a {@code .http} request runner (with the clicked line), a Makefile
     *  target runner (with the clicked target's name), else the script run handler. */
    /** Hover text for a gutter Run glyph: explains what the blue JUnit ▶ runs (class vs method). */
    private String runGlyphTooltip(int line) {
        com.editora.run.MainMethodScanner.MainMethod m = mainLines.get(line);
        if (m != null) {
            return tr("editmenu.runMainTooltip", com.editora.test.TestSourceLocator.simpleName(m.fqn()));
        }
        com.editora.test.JavaTestScanner.TestTarget t = testLines.get(line);
        if (t == null) {
            return null; // the plain script/Makefile/.http ▶ keeps its untooltipped look
        }
        return t.methodName() == null
                ? tr("testrunner.gutter.runClass", com.editora.test.TestSourceLocator.displayName(t.className()))
                : tr("testrunner.gutter.runMethod", t.methodName());
    }

    private void onRunGlyph(int line) {
        com.editora.test.JavaTestScanner.TestTarget testTarget = testLines.get(line);
        com.editora.run.MainMethodScanner.MainMethod mainTarget = mainLines.get(line);
        if (testTarget != null) {
            testRunHandler.accept(testTarget); // a JUnit class/method ▶ (never coincides with a compact-source main)
        } else if (mainTarget != null) {
            mainRunHandler.accept(mainTarget); // a project main class ▶ (disjoint from test/compact-source lines)
        } else if (httpFeatureEnabled && isHttpFile()) {
            httpRunHandler.accept(line);
        } else if (isMakefile()) {
            String target = makeTargets.get(line);
            if (target != null) {
                makeRunHandler.accept(target);
            }
        } else if (runHandler != null) {
            runHandler.run();
        }
    }

    /** Discards an in-flight background run scan whose result would be stale (mirrors {@link #todoGen}). */
    private long runScanGen;

    /** Recomputes runnable status + the gutter entry line(s); refreshes the gutter and fires the callback. */
    private void recomputeRun() {
        recomputeRun(false);
    }

    /**
     * As {@link #recomputeRun()}; {@code settled} is the pass after an edit settles, whose scan of the text
     * ({@link RunScan}) runs off the FX thread — a generation counter drops a superseded result, as for
     * {@link #refreshTodoMarks}. Every other caller (load, Save As, a feature toggle) needs the answer now.
     */
    private void recomputeRun(boolean settled) {
        maybeApplyShebang(); // an interpreter shebang can set the language before we gate the Run glyph
        long gen = ++runScanGen; // any newer pass (or dispose) supersedes an in-flight scan
        boolean small = !largeFile && area.getLength() <= COMPACT_SCAN_LIMIT;
        // Test gutter: independent of the LSP-gated run feature, so it has its own eligibility (a Java buffer
        // in a JVM project with the Test Runner on). Additive — a test file also keeps any compact-source ▶.
        // Project main-method gutter: a Java file in a Maven/Gradle project (gated by MainController).
        RunScan.Inputs in = new RunScan.Inputs(
                runFeatureEnabled && small,
                httpFeatureEnabled && small && isHttpFile(),
                testGutterEnabled && small && "java".equals(language),
                mainGutterEnabled && small && "java".equals(language),
                language,
                path != null ? path.getFileName().toString() : displayName,
                shellRunEnabled,
                shebangJavaSource != null);
        // Only materialize the whole document when we actually scan it (compact-source / .http / test/main
        // detection). Otherwise editing a moderately large file (256 KB–5 MB) would allocate the full text on
        // every 150 ms edit pulse just to discard it as run-ineligible.
        String text = in.needsText() ? documentTextSnapshot() : "";
        if (!settled || !in.needsText()) {
            applyRunScan(RunScan.scan(in, text));
            return;
        }
        HIGHLIGHT_POOL.execute(() -> {
            RunScan.Result found = RunScan.scan(in, text);
            Platform.runLater(() -> {
                if (gen == runScanGen) {
                    applyRunScan(found);
                }
            });
        });
    }

    private void applyRunScan(RunScan.Result now) {
        boolean changed = now.runnable() != runnable;
        boolean linesChanged = !now.httpLines().equals(httpRequestLines)
                || !now.makeTargets().equals(makeTargets)
                || !now.testLines().equals(testLines)
                || !now.mainLines().equals(mainLines);
        int oldLine = runLine;
        runnable = now.runnable();
        runLine = now.line();
        httpRequestLines = now.httpLines();
        makeTargets = now.makeTargets();
        testLines = now.testLines();
        mainLines = now.mainLines();
        if (changed) {
            onRunnableChanged.run();
            refreshGutter(); // the Run slot appeared/disappeared on every row — rebuild the factory
        } else if (linesChanged) {
            refreshGutter(); // requests/targets/test/main methods added/removed — relight the glyphs
        } else if (runLine != oldLine) {
            // The entry line moved (edits above it) — repaint just the old and new gutter rows.
            if (oldLine >= 0) {
                refreshGutterLine(oldLine);
            }
            if (runLine >= 0) {
                refreshGutterLine(runLine);
            }
        }
    }

    /** Turns LSP rendering (diagnostics overlay + hover) on/off for this buffer. The controller drives
     *  document open/close + requests; this only gates the editor surface. */
    public void setLspActive(boolean on) {
        // Large-file mode disables LSP just like syntax highlighting and the minimap: a 5–50 MB document
        // would flood the FX thread with a full-text didOpen and tens/hundreds of thousands of diagnostics
        // to map + render (the Problems tree, minimap + scrollbar stripes), freezing the editor. largeFile
        // is implied by hugeFile (see setReadOnly), so this single guard covers both.
        this.lspActive = on && isLspLanguage() && !largeFile && !heavyFile;
        lastLspSentVersion = -1; // an (de)activation re-syncs via didOpen; force the next change-send to fire
        if (this.lspActive || lspOverlay != null) {
            lspOverlay().setActive(this.lspActive);
        }
        // The minimap stripes only draw while LSP is active for this file, regardless of any stale list.
        minimap.setDiagnosticsEnabled(this.lspActive);
        if (!this.lspActive) {
            if (lspOverlay != null) {
                lspOverlay.setDiagnostics(java.util.List.of());
            }
            minimap.setDiagnostics(java.util.List.of());
            diagnosticStripe.setDiagnostics(java.util.List.of());
        }
        updateDiagnosticStripe();
    }

    public boolean isLspActive() {
        return lspActive;
    }

    /** Pushes the latest diagnostics for this buffer into the overlay + minimap/scrollbar stripes. */
    public void setLspDiagnostics(java.util.List<LspDiagnostic> diagnostics) {
        if (lspActive || lspOverlay != null) {
            lspOverlay().setDiagnostics(diagnostics);
        }
        minimap.setDiagnostics(diagnostics);
        diagnosticStripe.setDiagnostics(diagnostics);
    }

    /** Clears the diagnostic overlay/stripe/minimap on an edit so their line/col-anchored marks don't paint on
     *  shifted lines until the server re-pushes (#417). A no-op cost beyond the repaint the edit already does. */
    private void suppressStaleDiagnostics() {
        if (lspOverlay != null) {
            lspOverlay.setDiagnostics(java.util.List.of());
        }
        minimap.setDiagnostics(java.util.List.of());
        diagnosticStripe.setDiagnostics(java.util.List.of());
    }

    /** The debounced semantic-tokens request (fired on the 300 ms didChange pulse while active); null = none.
     *  Also drives inlay hints (#681) — fired when {@link #inlayHintsActive} even if semantic is off. */
    public void setSemanticTokensRequester(Runnable requester) {
        this.semanticTokensRequester = requester;
    }

    /** Whether inlay hints should re-request on the edit/scroll pulse — independent of semantic highlighting
     *  (#681, which was silently coupled to {@code semanticActive} so hints never fired with it off). */
    public void setInlayHintsActive(boolean active) {
        this.inlayHintsActive = active;
    }

    private boolean inlayHintsActive;

    /** Turns LSP semantic highlighting on/off for this buffer (server supports it + feature enabled). Off in
     *  large-file mode, like the diagnostics overlay. Re-applies the highlight so the overlay appears/clears. */
    public void setSemanticActive(boolean on) {
        boolean next = on && !largeFile;
        if (next == semanticActive) {
            return;
        }
        semanticActive = next;
        if (!semanticActive) {
            semanticTokens = java.util.List.of();
            semanticStale = false;
        }
        semanticDirty.applied();
        invalidateHighlighting(); // force a full re-tokenize so the overlay is added/removed everywhere
        applyHighlighting();
    }

    public boolean isSemanticActive() {
        return semanticActive;
    }

    /** The generation to capture when issuing a semantic-tokens request; pass it back to
     *  {@link #setSemanticTokens(java.util.List, long)} so a response for an older document is dropped. */
    public long semanticGen() {
        return semanticGen;
    }

    /** Pushes fresh server semantic tokens (absolute positions) and re-applies so they overlay the
     *  TextMate highlight immediately. No-op when semantic highlighting is off for this buffer. */
    public void setSemanticTokens(java.util.List<SemanticToken> tokens) {
        setSemanticTokens(tokens, semanticGen);
    }

    /**
     * Applies a semantic-tokens response only if the document hasn't changed since the request was issued
     * ({@code requestGen == } the current {@link #semanticGen()}). A stale response — the server computed it
     * against an older version, or an older request's reply arrives after a newer one — is <b>dropped</b>,
     * leaving {@link #semanticStale} set so the overlay stays suppressed rather than re-anchoring the old
     * tokens onto the shifted text (which mis-colored characters/lines until the next response). A response
     * identical to the tokens already shown restyles nothing; a different one restyles the lines that differ.
     */
    public void setSemanticTokens(java.util.List<SemanticToken> tokens, long requestGen) {
        if (!semanticActive || requestGen != semanticGen) {
            return;
        }
        java.util.List<SemanticToken> next = tokens == null ? java.util.List.of() : tokens;
        int lines = area.getParagraphs().size();
        int erasedFirst = semanticStale ? lineOfOffset(semanticDirty.start()) : -1;
        int[] range = SemanticToken.changedLines(
                semanticTokens, next, erasedFirst, lineOfOffset(semanticDirty.end()), lines - semanticLines);
        if (range != null || semanticStale) {
            semanticTokens = next;
        }
        semanticStale = false; // anchored to the current (unchanged since request) text → safe to overlay
        semanticDirty.applied();
        semanticLines = lines;
        if (range != null) { // else the same tokens over the same text: nothing to restyle
            // Style-only: the pass re-tokenizes just these lines, from the stored end-state above them.
            int last = Math.min(range[1], lines - 1);
            highlightDirty.include(
                    area.getAbsolutePosition(Math.min(range[0], last), 0),
                    area.getAbsolutePosition(last, area.getParagraphLength(last)));
            applyHighlighting();
        }
    }

    private int lineOfOffset(int offset) {
        return area.offsetToPosition(
                        Math.max(0, Math.min(offset, area.getLength())),
                        org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
    }

    /** The semantic tokens a pass dispatched now should overlay, or null (off, stale, or none). */
    private java.util.List<SemanticToken> semanticOverlay() {
        return semanticActive && !semanticStale && !semanticTokens.isEmpty() ? semanticTokens : null;
    }

    /** The inclusive 0-based line range currently visible in the editor (for a viewport semantic-tokens
     *  request). Falls back to the whole document if the viewport indices aren't available yet. */
    public int[] visibleLineWindow() {
        int paragraphs = area.getParagraphs().size();
        try {
            int first = area.firstVisibleParToAllParIndex();
            int last = area.lastVisibleParToAllParIndex();
            if (last >= first && first >= 0) {
                return new int[] {first, Math.min(last, Math.max(0, paragraphs - 1))};
            }
        } catch (RuntimeException ignore) {
            // viewport not laid out yet — fall through to the whole-document window
        }
        return new int[] {0, Math.max(0, paragraphs - 1)};
    }

    /** The scrollbar stripe is shown whenever LSP is active for this buffer (minimap on or off). It sits
     *  over the editor's vertical scrollbar: at the far-right edge when the minimap is hidden, else just
     *  inside the minimap (over the editor scrollbar, which ends at the minimap's left edge). */
    private void updateDiagnosticStripe() {
        boolean minimapShown = minimapVisible && !largeFile && !heavyFile;
        AnchorPane.setRightAnchor(diagnosticStripe, minimapShown ? Minimap.WIDTH : 0d);
        diagnosticStripe.setActive(lspActive);
        updateMarkdownLintStripe();
    }

    /** Positions the Markdown-lint overview stripe beside the diagnostic stripe (shifted left by its width
     *  when LSP is also active — never the case for a Markdown buffer, but kept general). */
    private void updateMarkdownLintStripe() {
        boolean minimapShown = minimapVisible && !largeFile && !heavyFile;
        double base = minimapShown ? Minimap.WIDTH : 0d;
        AnchorPane.setRightAnchor(mdLintStripe, base + (lspActive ? DiagnosticStripe.WIDTH : 0d));
        mdLintStripe.setActive(markdownLintEnabled && !largeFile);
        updateTodoStripe();
    }

    /** Positions the TODO overview stripe over the scrollbar: at the edge (inside the minimap when shown),
     *  shifted left by the diagnostic + lint stripes' widths when those are active, so they sit side by side. */
    private void updateTodoStripe() {
        boolean minimapShown = minimapVisible && !largeFile && !heavyFile;
        double base = minimapShown ? Minimap.WIDTH : 0d;
        double offset = (lspActive ? DiagnosticStripe.WIDTH : 0d)
                + (markdownLintEnabled && !largeFile ? MarkdownLintStripe.WIDTH : 0d);
        AnchorPane.setRightAnchor(todoStripe, base + offset);
        todoStripe.setActive(todoEnabled && !largeFile);
    }

    /** Injects the LSP actions surfaced in the right-click menu while {@link #isLspActive()} (Format and
     *  Code Actions are shown only when the server advertises them — see {@link #setLspFormatAvailable}/
     *  {@link #setLspCodeActionsAvailable}). */
    public void setLspNavActions(
            Runnable gotoDefinition,
            Runnable findReferences,
            Runnable hover,
            Runnable format,
            Runnable codeActions,
            Runnable rename,
            Runnable gotoImplementation,
            Runnable gotoTypeDefinition) {
        this.lspGotoDefinitionAction = gotoDefinition == null ? () -> {} : gotoDefinition;
        this.lspFindReferencesAction = findReferences == null ? () -> {} : findReferences;
        this.lspHoverAction = hover == null ? () -> {} : hover;
        this.lspFormatAction = format == null ? () -> {} : format;
        this.lspCodeActionsAction = codeActions == null ? () -> {} : codeActions;
        this.lspRenameAction = rename == null ? () -> {} : rename;
        this.lspGotoImplementationAction = gotoImplementation == null ? () -> {} : gotoImplementation;
        this.lspGotoTypeDefinitionAction = gotoTypeDefinition == null ? () -> {} : gotoTypeDefinition;
    }

    /** Whether to offer "Go to Implementation" in the right-click menu (the server's capability, #735). */
    public void setLspImplementationAvailable(boolean available) {
        this.lspImplementationAvailable = available;
    }

    /** Whether to offer "Go to Type Definition" in the right-click menu (the server's capability, #736). */
    public void setLspTypeDefinitionAvailable(boolean available) {
        this.lspTypeDefinitionAvailable = available;
    }

    /** Whether to offer "Format Document" in the right-click menu (the server's formatting capability). */
    public void setLspFormatAvailable(boolean available) {
        this.lspFormatAvailable = available;
    }

    /** Whether to offer "Code Actions" in the right-click menu (the server's codeAction capability, #670). */
    public void setLspCodeActionsAvailable(boolean available) {
        this.lspCodeActionsAvailable = available;
    }

    /** Whether to offer "Rename" in the right-click menu (the server's rename capability, #676). */
    public void setLspRenameAvailable(boolean available) {
        this.lspRenameAvailable = available;
    }

    /** Requests jdtls's paste auto-import for the freshly pasted range (#742); controller-wired. The
     *  positions are line/character pairs of the post-paste span; {@code stillValid} must be consulted
     *  before applying the async answer. Keeps {@code editor} free of lsp4j. */
    public interface LspPasteImportsRequester {
        void request(
                int startLine,
                int startChar,
                int endLine,
                int endChar,
                String pastedText,
                java.util.function.BooleanSupplier stillValid);
    }

    private LspPasteImportsRequester lspPasteImportsRequester;
    /** Master gate for paste auto-import ({@code Settings.lspPasteImports}), pushed from the controller. */
    private boolean lspPasteImportsEnabled = true;

    public void setLspPasteImportsRequester(LspPasteImportsRequester requester) {
        this.lspPasteImportsRequester = requester;
    }

    public void setLspPasteImportsEnabled(boolean enabled) {
        this.lspPasteImportsEnabled = enabled;
    }

    /**
     * After a paste inserted {@code [start..end)} (absolute offsets), asks the language server for the
     * imports the pasted code needs (#742). No-op unless the feature is on, a requester is wired, the
     * buffer is an editable, non-narrowed LSP buffer (a narrowed buffer's coordinates are region-relative
     * — the same reason LSP sync is suspended there), and the span is non-empty.
     *
     * <p>The pending didChange is flushed FIRST ({@link #sendLspChange}) so the server computes against
     * the post-paste document — JSON-RPC preserves order, the {@code requestLspCompletion} idiom. The
     * {@code stillValid} supplier the requester must consult before applying is a {@link #docVersion}
     * check: an answer that arrives after the user kept typing is dropped, never applied to text it
     * wasn't computed for.
     */
    public void requestLspPasteImports(int start, int end) {
        if (!lspPasteImportsEnabled
                || lspPasteImportsRequester == null
                || !lspActive
                || isNarrowed()
                || !isEditable()
                || end <= start
                || end > area.getLength()) {
            return;
        }
        sendLspChange();
        long version = docVersion;
        var s = area.offsetToPosition(start, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
        var e = area.offsetToPosition(end, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward);
        lspPasteImportsRequester.request(
                s.getMajor(),
                s.getMinor(),
                e.getMajor(),
                e.getMinor(),
                area.getText(start, end),
                () -> docVersion == version);
    }

    /** Async range-formatter for Tab line re-indent: requests the edits the server would apply to a line
     *  range and delivers them on the FX thread. Keeps {@code editor} free of lsp4j. */
    public interface LspRangeFormatter {
        void format(
                int startLine,
                int startChar,
                int endLine,
                int endChar,
                java.util.function.Consumer<java.util.List<LspTextEdit>> callback);
    }

    /** Injects the range-formatter used by Tab to re-indent the current line (controller-wired; null off). */
    public void setLspRangeFormatter(LspRangeFormatter formatter) {
        this.lspRangeFormatter = formatter;
    }

    /** Whether the server advertises range formatting — gates Tab's LSP line re-indent (else plain Tab). */
    public void setLspRangeFormatAvailable(boolean available) {
        this.lspRangeFormatAvailable = available;
    }

    /** Current document text (for an initial didOpen). */
    public String text() {
        return documentTextSnapshot();
    }

    /** Shows the LSP diagnostic message(s) in a tooltip when hovering a squiggled span. */
    private void installLspHover(CodeArea a) {
        a.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (!lspActive || lspOverlay == null || lspOverlay.diagnostics().isEmpty()) {
                if (lspTooltip != null) {
                    lspTooltip.hide();
                }
                return;
            }
            try {
                var hit = a.hit(e.getX(), e.getY());
                var pos = a.offsetToPosition(
                        hit.getInsertionIndex(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
                var hits = lspOverlay.at(pos.getMajor(), pos.getMinor());
                if (hits.isEmpty()) {
                    if (lspTooltip != null) {
                        lspTooltip.hide();
                    }
                    lspTooltipText = null;
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (var d : hits) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    String origin = d.origin();
                    sb.append(d.message());
                    if (!origin.isEmpty()) {
                        sb.append("  (").append(origin).append(')');
                    }
                }
                String text = sb.toString();
                // Already showing this exact message → don't re-show; re-showing on every MOUSE_MOVED
                // re-positions the popup to the cursor each pixel, which reads as flicker.
                if (lspTooltip != null && lspTooltip.isShowing() && text.equals(lspTooltipText)) {
                    return;
                }
                if (lspTooltip == null) {
                    lspTooltip = new javafx.scene.control.Tooltip();
                    lspTooltip.getStyleClass().add("lsp-diagnostic-tooltip");
                    lspTooltip.setWrapText(true);
                    lspTooltip.setMaxWidth(480);
                }
                lspTooltip.setText(text);
                lspTooltipText = text;
                lspTooltip.show(a, e.getScreenX() + 12, e.getScreenY() + 16);
            } catch (RuntimeException ignored) {
                // viewport mid-layout / hit miss — ignore
            }
        });
        a.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (lspTooltip != null) {
                lspTooltip.hide();
            }
        });
    }

    // --- Debugger editor surfaces: inline values + hover value tooltip --------------------------

    /** While suspended: the frame's variable name → value map painted as grey end-of-line annotations on the
     *  visible lines of the function stopped at {@code frameLine}; null/empty clears (resume/terminate). */
    public void setInlineValues(java.util.Map<String, String> values, int frameLine) {
        inlineValues.setValues(hugeFile ? null : values, frameLine);
    }

    /** IntelliJ-style blame "Annotate" gutter column: per-0-based-line annotations (null/empty clears it).
     *  Already formatted + localized by the controller (author/date/tooltip/heatmap), so {@code editor}
     *  stays git-free. Off on huge files. Computes the fixed column width from the widest author+date, then
     *  rebuilds the gutter so the column appears/disappears. */
    public void setBlame(java.util.List<BlameInfo> lines) {
        if (!gitLines.setBlame(hugeFile ? null : lines)) {
            return; // every git refresh comes through here, mostly with nothing: no gutter rebuild for that
        }
        this.blameColumnWidth = gitLines.blame() == null ? 0 : measureBlameColumnWidth(gitLines.blame());
        refreshGutter();
    }

    /** Measures the annotation column once: the widest "author + date" across all lines, in the actual
     *  (small) gutter font so the column isn't padded for the larger editor font, capped so a runaway long
     *  author can't let it dominate (it ellipsizes instead), plus a little cell padding. Off-scene
     *  {@code Text} layout bounds use the font directly, so this is safe. */
    private double measureBlameColumnWidth(java.util.List<BlameInfo> lines) {
        javafx.scene.text.Text probe = new javafx.scene.text.Text();
        probe.setFont(javafx.scene.text.Font.font(fontFamily, BLAME_FONT_SIZE));
        double max = 0;
        for (BlameInfo bi : lines) {
            if (bi == null || bi.isEmpty()) {
                continue;
            }
            probe.setText(bi.author() + "  " + bi.date());
            max = Math.max(max, probe.getLayoutBounds().getWidth());
        }
        if (max <= 0) {
            return 0;
        }
        double capped = Math.min(max, BLAME_FONT_SIZE * 17.0); // bound a runaway author; longer ones ellipsize
        return Math.ceil(capped) + 10; // + cell padding / inter-label gap
    }

    /** Whether blame annotations are currently showing (non-null per-line data). */
    public boolean isBlameOn() {
        return gitLines.blame() != null;
    }

    /** The commit hash that last touched {@code line} (for "show this commit"), or null. */
    public String blameHashAt(int line) {
        BlameInfo bi = gitLines.blameAt(line);
        return bi == null ? null : bi.hash();
    }

    /** The commit hash that last touched the caret line (for "show this commit"), or null. */
    public String blameHashAtCaret() {
        return blameHashAt(focusedArea.getCurrentParagraph());
    }

    /** Async evaluator injected by the controller (DAP {@code evaluate} with context "hover"):
     *  given the hovered identifier, deliver its rendered value (null/blank = show nothing). */
    private java.util.function.BiConsumer<String, java.util.function.Consumer<String>> debugHoverEvaluator;

    private boolean debugHoverActive;
    private javafx.scene.control.Tooltip debugTooltip;
    private String debugHoverWord;

    public void setDebugHoverEvaluator(
            java.util.function.BiConsumer<String, java.util.function.Consumer<String>> evaluator) {
        this.debugHoverEvaluator = evaluator;
    }

    /** Flipped by the controller on suspend/resume so hovering costs nothing outside a pause. */
    public void setDebugHoverActive(boolean on) {
        this.debugHoverActive = on && !hugeFile;
        if (!this.debugHoverActive) {
            hideDebugTooltip();
        }
    }

    /** IntelliJ's value popup: hovering an identifier while suspended evaluates it in the selected
     *  frame and shows {@code name = value}. One evaluation per distinct hovered word. */
    private void installDebugHover(CodeArea a) {
        a.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (!debugHoverActive || debugHoverEvaluator == null) {
                return;
            }
            try {
                var hit = a.hit(e.getX(), e.getY());
                var pos = a.offsetToPosition(
                        hit.getInsertionIndex(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
                String line = a.getParagraph(pos.getMajor()).getText();
                String word = DebugIdentifiers.wordAt(line, pos.getMinor());
                if (word == null) {
                    hideDebugTooltip();
                    return;
                }
                if (word.equals(debugHoverWord)) {
                    return; // already showing (or evaluating) this word
                }
                debugHoverWord = word;
                double sx = e.getScreenX();
                double sy = e.getScreenY();
                debugHoverEvaluator.accept(word, value -> {
                    if (!debugHoverActive || value == null || value.isBlank() || !word.equals(debugHoverWord)) {
                        return; // evaluation failed / mouse moved on — show nothing
                    }
                    if (debugTooltip == null) {
                        debugTooltip = new javafx.scene.control.Tooltip();
                        debugTooltip.getStyleClass().add("debug-value-tooltip");
                        debugTooltip.setWrapText(true);
                        debugTooltip.setMaxWidth(480);
                    }
                    debugTooltip.setText(word + " = " + value);
                    debugTooltip.show(a, sx + 12, sy + 16);
                });
            } catch (RuntimeException ignored) {
                // viewport mid-layout / hit miss — ignore
            }
        });
        a.addEventHandler(MouseEvent.MOUSE_EXITED, e -> hideDebugTooltip());
    }

    private void hideDebugTooltip() {
        if (debugTooltip != null) {
            debugTooltip.hide();
        }
        debugHoverWord = null;
    }

    public MarkdownViewMode getMarkdownViewMode() {
        return markdownViewMode;
    }

    public void setOnViewModeChanged(Runnable callback) {
        this.onViewModeChanged = callback == null ? () -> {} : callback;
    }

    /** Overlays the Editor/Split/Preview control top-right of this buffer's view; {@code null} removes it. */
    public void setViewModeControl(Node control) {
        this.viewModeControl = control;
        rebuildViewHost();
    }

    /** Whether the floating Editor/Split/Preview control is currently attached. */
    public boolean hasViewModeControl() {
        return viewModeControl != null;
    }

    /** Overlays the HTML "open in browser" control top-right of the code pane; {@code null} removes it. */
    public void setHtmlPreviewControl(Node control) {
        if (htmlPreviewControl != null && htmlPreviewControl != control) {
            removeCornerControl(htmlPreviewControl);
        }
        this.htmlPreviewControl = control;
        rebuildViewHost();
    }

    /** Whether the floating HTML "open in browser" control is currently attached. */
    public boolean hasHtmlPreviewControl() {
        return htmlPreviewControl != null;
    }

    /** Injects the CSV grid preview node (a ui-layer {@code CsvGridPanel}); {@code null} removes it. Non-null
     *  makes the buffer previewable ({@link #hasPreview()}), so the Editor/Split/Preview toggle attaches. */
    public void setCsvPreviewNode(Node node) {
        if (this.csvPreviewNode == node) {
            return;
        }
        this.csvPreviewNode = node;
        if (node == null) {
            csvPreviewHost = null;
            if (markdownViewMode != MarkdownViewMode.EDITOR) {
                markdownViewMode = MarkdownViewMode.EDITOR; // the grid is gone — fall back to source
            }
        }
        rebuildViewHost();
    }

    /** Injects the callback that repopulates the CSV grid from the buffer text (run on the debounced pulse). */
    public void setCsvPreviewRefresh(Runnable refresh) {
        this.csvPreviewRefresh = refresh == null ? () -> {} : refresh;
    }

    /** Injects the HTTP response panel (a ui-layer {@code HttpClientPanel}); {@code null} removes it. Non-null
     *  makes the buffer previewable ({@link #hasPreview()}), so the Editor/Split/Preview toggle attaches. */
    public void setHttpPreviewNode(Node node) {
        if (this.httpPreviewNode == node) {
            return;
        }
        this.httpPreviewNode = node;
        if (node == null) {
            httpPreviewHost = null;
            if (markdownViewMode != MarkdownViewMode.EDITOR) {
                markdownViewMode = MarkdownViewMode.EDITOR; // the panel is gone — fall back to source
            }
        }
        rebuildViewHost();
    }

    /**
     * Shows the HTTP response preview beside the source, used when a request is run from the gutter ▶ while
     * the buffer is still in EDITOR mode. A buffer already in SPLIT or PREVIEW is left alone, so the per-file
     * mode the user last chose (persisted in {@code WorkspaceState.markdownViewModes}) wins over this nudge.
     */
    public void revealHttpPreview() {
        if (hasHttpPreview() && markdownViewMode == MarkdownViewMode.EDITOR) {
            setMarkdownViewMode(MarkdownViewMode.SPLIT);
        }
    }

    // --- Log viewer ----------------------------------------------------------------------------------

    /** Whether this is a log buffer: a {@code .log} file (language {@code "log"}) or forced "View as Log". */
    public boolean isLog() {
        return "log".equals(language) || logViewForced;
    }

    /** Whether this buffer is shown as a log by request ("View as Log", or its content) rather than by name. */
    public boolean isLogViewForced() {
        return logViewForced;
    }

    /** Forces (or clears) log-viewer mode on a buffer whose name isn't a log's; rebuilds the host. */
    public void setLogViewForced(boolean forced) {
        if (this.logViewForced == forced) {
            return;
        }
        this.logViewForced = forced;
        rebuildViewHost();
    }

    /**
     * Docks the log control (Follow / level / pattern) above the text; {@code null} removes it. It is a bar of
     * its own rather than a control floating in the corner: log lines are the longest lines the editor shows,
     * and a floating control sat on top of the end of the first two.
     */
    public void setLogControl(Node control) {
        this.logControl = control;
        refreshTopBars();
    }

    /** Whether the floating log control is currently attached. */
    public boolean hasLogControl() {
        return logControl != null;
    }

    /** Turns the size-independent level overlay on/off (controller gates on the feature + {@link #isLog()}). */
    public void setLogHighlightEnabled(boolean on) {
        boolean want = on && isLog();
        if (want || logOverlay != null) {
            logOverlay().setActive(want);
        }
    }

    /** Auto-close tags: typing the {@code >} of an HTML/XML open tag inserts the matching closer. */
    private boolean autoCloseTags;

    /** Enables/disables tag auto-closing (pushed from the controller's view settings). */
    public void setAutoCloseTags(boolean on) {
        autoCloseTags = on;
    }

    /**
     * Typing the {@code >} that completes an open tag inserts {@code </name>} after the caret (the
     * VS Code auto-closing-tags behavior), leaving the caret between the tags. Gated to editable
     * html/xml buffers; the decision is the pure {@link com.editora.editops.TagAutoClose} over a
     * bounded window before the caret, so the per-keystroke cost is one short backward scan.
     */
    private boolean applyTagAutoClose(CodeArea a) {
        if (!autoCloseTags || a.getSelection().getLength() > 0) {
            return false;
        }
        String lang = getLanguage();
        boolean html = "html".equals(lang);
        if (!html && !"xml".equals(lang)) {
            return false;
        }
        int caret = a.getCaretPosition();
        int from = Math.max(0, caret - com.editora.editops.TagAutoClose.MAX_TAG_SCAN);
        String closer = com.editora.editops.TagAutoClose.closer(a.getText(from, caret), html);
        if (closer == null) {
            return false;
        }
        a.replaceText(caret, caret, ">" + closer);
        a.moveTo(caret + 1);
        return true;
    }

    /** Enables/disables the paired-tag auto-rename (pushed from the controller's view settings). */
    public void setAutoRenameTag(boolean on) {
        tagRename.setEnabled(on);
    }

    /**
     * After each document change, mirrors a tag-name edit onto the paired open/close tag (the VS
     * Code Auto Rename Tag behavior) via the pure {@link com.editora.editops.TagRename}. Runs only
     * for editable html/xml buffers below the large-file tier, never during undo/redo (undoing the
     * user's edit and the mirror separately must not re-mirror), and never re-entrantly. Cost when
     * it doesn't apply is one boolean/language check; the document lex runs only when the change
     * sits inside a tag name.
     */
    private boolean autoFill;

    private int fillColumn = com.editora.editops.Filler.DEFAULT_FILL_COLUMN;
    private boolean applyingAutoFill;

    /** Enables/disables auto-fill (break-as-you-type) for this buffer; pushed from Settings by the controller. */
    public void setAutoFillEnabled(boolean on) {
        this.autoFill = on;
    }

    public void setFillColumn(int column) {
        this.fillColumn = column > 0 ? column : com.editora.editops.Filler.DEFAULT_FILL_COLUMN;
    }

    /**
     * Emacs {@code auto-fill-mode}: after an insertion, break the current line at a word boundary if it has
     * grown past the fill column. Prose only ({@link #isProse()}), so it never wraps code. Runs on the
     * post-edit {@code plainTextChanges} (the tag-rename pattern) — the first field check short-circuits when
     * off, keeping the per-keystroke cost at one boolean for every buffer that hasn't enabled it.
     */
    private java.util.Map<String, String> abbrevTable = java.util.Map.of();

    private boolean abbrevMode;
    private boolean applyingAbbrev;

    /** Pushes the abbreviation dictionary (lower-cased keys) and the auto-expand mode from Settings. */
    public void setAbbrevs(java.util.Map<String, String> table, boolean autoExpand) {
        this.abbrevTable = table == null ? java.util.Map.of() : table;
        this.abbrevMode = autoExpand;
    }

    /**
     * Emacs {@code expand-abbrev} ({@code C-x a e}): expand the abbreviation immediately before the caret,
     * regardless of abbrev-mode. Returns whether anything expanded.
     */
    public boolean expandAbbrevAtCaret() {
        if (!isEditable() || abbrevTable.isEmpty()) {
            return false;
        }
        CodeArea a = getFocusedArea();
        com.editora.editops.Abbrev.Edit edit =
                com.editora.editops.Abbrev.expand(a.getText(), a.getCaretPosition(), abbrevTable);
        if (edit == null) {
            return false;
        }
        a.replaceText(edit.from(), edit.to(), edit.replacement());
        return true;
    }

    /**
     * Abbrev-mode auto-expand: when the just-typed single character is a word terminator (anything but a
     * letter/digit), expand the word that ended just before it. The terminator stays. Same guarded
     * {@code plainTextChanges} shape as {@link #maybeAutoFill}; the first field check short-circuits when off.
     */
    /** Longest word an abbreviation lookup will scan back over — bounds the per-keystroke text slice below. */
    private static final int MAX_ABBREV_LOOKBACK = 256;

    private void maybeExpandAbbrev(org.fxmisc.richtext.model.PlainTextChange c) {
        if (!abbrevMode || applyingAbbrev || hugeFile || !isEditable() || abbrevTable.isEmpty()) {
            return;
        }
        if (!c.getRemoved().isEmpty() || !com.editora.editops.Abbrev.terminates(c.getInserted())) {
            return; // not a typed terminator (still inside a word, or a paste)
        }
        if (hasActiveSnippet() || area.getUndoManager().isPerformingAction()) {
            return;
        }
        CodeArea a = getFocusedArea();
        if (multiCaretActiveOn(a)) {
            return;
        }
        // The terminator sits at c.getPosition(); the word ending there fits in a bounded window before it, so
        // slice just that window instead of materializing the whole document — this runs per word-terminator
        // keystroke. expand()'s offsets are window-relative; shift them back into document coordinates.
        int point = Math.min(c.getPosition(), a.getLength());
        int windowStart = Math.max(0, point - MAX_ABBREV_LOOKBACK);
        String window = a.getText(windowStart, point);
        com.editora.editops.Abbrev.Edit edit = com.editora.editops.Abbrev.expand(window, window.length(), abbrevTable);
        if (edit == null) {
            return;
        }
        int from = windowStart + edit.from();
        int to = windowStart + edit.to();
        int caret = caretPast(a, c);
        int delta = edit.replacement().length() - (to - from);
        applyingAbbrev = true;
        try {
            a.replaceText(from, to, edit.replacement());
        } finally {
            applyingAbbrev = false;
        }
        // The edit lies before the caret (the terminator we just typed), so shift the caret by the length
        // change. Deferred for the same reason as auto-fill: RichTextFX re-applies the triggering
        // insertion's caret after this subscriber returns, which would otherwise mis-place it.
        // Enter-with-indent and an auto-closed pair place their own caret once this returns (after the
        // indent, between the pair), so for those it is read back then rather than predicted now.
        boolean oneChar = c.getInserted().length() == 1;
        javafx.application.Platform.runLater(
                () -> a.moveTo(Math.clamp((oneChar ? caret : a.getCaretPosition()) + delta, 0, a.getLength())));
    }

    /**
     * {@code a}'s caret once it is past the just-typed {@code c}. The split's second view moves its caret
     * only after this buffer's change listeners have run, so there it is still in front of the character.
     */
    private static int caretPast(CodeArea a, org.fxmisc.richtext.model.PlainTextChange c) {
        return a.getCaretPosition() == c.getPosition() ? c.getInsertionEnd() : a.getCaretPosition();
    }

    private void maybeAutoFill(org.fxmisc.richtext.model.PlainTextChange c) {
        if (!autoFill || applyingAutoFill || hugeFile || !isEditable() || !isProse()) {
            return;
        }
        // Only a single typed character (Emacs self-insert): no deletion, exactly one char, not a newline
        // (which starts a fresh line). This excludes pastes, file loads/reloads and programmatic reflows —
        // auto-fill breaks as you *type*, it never rewraps a block that arrived some other way.
        if (!c.getRemoved().isEmpty()
                || c.getInserted().length() != 1
                || c.getInserted().charAt(0) == '\n') {
            return;
        }
        if (hasActiveSnippet() || area.getUndoManager().isPerformingAction()) {
            return;
        }
        CodeArea a = getFocusedArea();
        if (multiCaretActiveOn(a)) {
            return;
        }
        int par = a.getCurrentParagraph();
        String line = a.getParagraph(par).getText();
        if (line.length() <= fillColumn) {
            return;
        }
        com.editora.editops.AutoFill.Break brk = com.editora.editops.AutoFill.computeProse(
                line,
                fillColumn,
                com.editora.editops.Commenter.styleFor(getLanguage()).line());
        if (brk == null) {
            return;
        }
        int lineStart = a.getAbsolutePosition(par, 0);
        int caret = caretPast(a, c);
        int delta = brk.insert().length() - brk.removeLen();
        int end = lineStart + brk.at() + brk.removeLen();
        int restored = caret >= end ? caret + delta : caret; // the user's caret, shifted past the inserted prefix
        applyingAutoFill = true;
        try {
            a.replaceText(lineStart + brk.at(), end, brk.insert());
        } finally {
            applyingAutoFill = false;
        }
        // Restore the caret AFTER the change settles: we ran inside the triggering insertion's
        // plainTextChanges, and RichTextFX re-applies that insertion's own caret position once our
        // subscriber returns — which would strand the caret inside the inserted prefix (delta > 0) and send
        // the next characters to the wrong place. Deferring makes our position the last one to win.
        javafx.application.Platform.runLater(() -> a.moveTo(Math.min(restored, a.getLength())));
    }

    /** Per-column "rainbow" coloring for CSV/TSV buffers (replaces the source.csv grammar highlighting). */
    private boolean csvRainbow;

    /** Enables/disables rainbow per-column CSV coloring; re-highlights the whole buffer on a change. */
    public void setCsvRainbowEnabled(boolean on) {
        boolean want = on && isCsv();
        if (want != csvRainbow) {
            csvRainbow = want;
            invalidateHighlighting();
            applyHighlighting();
        }
    }

    /**
     * Creates a manual fold range from the focused view's selection (VS Code's
     * {@code createFoldingRangeFromSelection}) and collapses it. A selection ending at column 0 does not
     * include that line (the {@code LineTransforms.lineBounds} convention). Returns false when the
     * selection spans fewer than two lines — there is nothing to fold into the header.
     */
    public boolean createManualFoldFromSelection() {
        CodeArea a = focusedArea;
        var sel = a.getSelection();
        if (sel.getLength() == 0) {
            return false;
        }
        int startLine = a.offsetToPosition(sel.getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        var endPos = a.offsetToPosition(sel.getEnd(), org.fxmisc.richtext.model.TwoDimensional.Bias.Backward);
        int endLine = endPos.getMajor();
        if (endPos.getMinor() == 0 && endLine > startLine) {
            endLine--; // a selection merely touching the next line's column 0 doesn't include it
        }
        if (endLine <= startLine) {
            return false;
        }
        return folds.addManualFold(new FoldRegions.Region(startLine, endLine));
    }

    /** Removes every manual fold range (see {@link FoldManager#removeManualFolds}); returns the count. */
    public int removeManualFolds() {
        return folds.removeManualFolds();
    }

    /** Tint each bracket by nesting depth (see {@link BracketColors}). */
    private boolean bracketColors;

    /**
     * Enables/disables bracket-pair colorization. Re-highlights the whole buffer on a change: the colours
     * ride the token spans, so an incremental pass would leave the untouched prefix coloured as it was.
     */
    public void setBracketColorsEnabled(boolean on) {
        if (on != bracketColors) {
            bracketColors = on;
            invalidateHighlighting();
            applyHighlighting();
        }
    }

    /** Whether a level/pattern filter is currently narrowing the visible lines. */
    public boolean isLogFiltered() {
        return logView.filtered();
    }

    /** Whether the buffer is auto-scrolling as the file grows ({@code tail -f}). */
    public boolean isLogFollowing() {
        return logView.following();
    }

    public void setLogFollowing(boolean following) {
        logView.setFollowing(following);
    }

    /** True once follow mode dropped the oldest lines: the buffer is a tail of its file. Never save it. */
    public boolean isLogTrimmed() {
        return logView.trimmed();
    }

    /**
     * Narrows the visible lines to the records at or above {@code minLevel} that match {@code pattern} (a
     * regular expression, or plain text when it is not a valid one); {@code null}/{@code null} clears the
     * filter. {@link #getContent()} stays the whole log throughout.
     */
    public void applyLogFilter(LogLevel minLevel, String pattern) {
        widen(); // the filter is derived from the area, which must therefore be the whole document
        logView.applyFilter(minLevel, pattern);
    }

    /** Whether {@code minLevel}/{@code pattern} is the filter already showing. */
    public boolean showsLogFilter(LogLevel minLevel, String pattern) {
        return logView.showsFilter(minLevel, pattern);
    }

    /**
     * The text a log filter is computed from, and {@link #logFilterEpoch()} the token to hand back with the
     * result: a large log is filtered off the FX thread, and {@link #installLogFilter} takes the result only
     * if the text has done nothing but grow since.
     */
    public String logFilterSource() {
        widen();
        return logView.filterSource();
    }

    public int logFilterEpoch() {
        return logView.epoch();
    }

    /** Shows a filter computed from {@link #logFilterSource()}; false (and no change) when it is stale. */
    public boolean installLogFilter(
            com.editora.logviewer.LogFilter.Run run, int epoch, LogLevel minLevel, String pattern) {
        return logView.install(run, epoch, minLevel, pattern);
    }

    /** The current level floor of the active filter (null when unfiltered or no floor). */
    public LogLevel getLogMinLevel() {
        return logView.minLevel();
    }

    /** The pattern of the active filter as typed (null when unfiltered or no pattern). */
    public String getLogPattern() {
        return logView.query();
    }

    /** Lines the active filter shows, and lines in the whole log. Only meaningful while {@link #isLogFiltered()}. */
    public int logVisibleLines() {
        return logView.visibleLines();
    }

    public int logTotalLines() {
        return logView.totalLines();
    }

    /** Runs when the log view's counts, follow or trimmed state change (the log control shows them). */
    public void setOnLogStateChanged(Runnable listener) {
        logView.setOnStateChanged(listener);
    }

    /** Appends {@code text} read from the file's tail (filtered when a filter is active); never dirties. */
    public void appendLogText(String text) {
        logView.append(text);
    }

    /** Replaces the whole buffer with {@code fullText} (e.g. on log rotation), keeping any active filter. */
    public void resetLogContent(String fullText) {
        logView.reset(fullText);
    }

    /** The charset the file was decoded with — what text read from its tail must be decoded with too. */
    public java.nio.charset.Charset logCharset() {
        return com.editora.editorconfig.EditorConfigCharset.charsetFor(detectedCharset);
    }

    /** Injects the debounced HTML-edit listener (fires the live-preview reload); {@code null} disables it. */
    public void setHtmlPreviewDirtyListener(Runnable listener) {
        this.htmlPreviewDirtyListener = listener;
    }

    /** Switches the Markdown view mode and rebuilds the view host. */
    public void setMarkdownViewMode(MarkdownViewMode mode) {
        MarkdownViewMode target = mode == null ? MarkdownViewMode.EDITOR : mode;
        boolean changed = this.markdownViewMode != target;
        this.markdownViewMode = target;
        scheduleFormatBar(); // hide the format bar when entering pure PREVIEW, re-evaluate otherwise
        scheduleAiActionsBar(); // same, for the AI selection-actions bar
        if (target != MarkdownViewMode.EDITOR) {
            this.split = Split.NONE; // preview supersedes any code split
            scheduleRenderPreview();
        }
        rebuildViewHost();
        setMinimapVisible(minimapVisible); // re-apply: the minimap is hidden while the preview is shown
        // The paging-focus + scroll-sync tail is Markdown-preview-specific (the CSV grid, the HTTP response
        // panel and the structured tree/docs scroll themselves and have no ScrollPane host), so skip those.
        if (!hasCsvPreview() && !hasHttpPreview() && !hasTreePreview()) {
            if (target == MarkdownViewMode.PREVIEW) {
                // Focus the preview so the paging keys (Space/PageDown/Backspace/PageUp) work without a click.
                Platform.runLater(previewPane()::requestFocus);
            } else if (target == MarkdownViewMode.SPLIT) {
                // Align the freshly-shown preview to the editor's current scroll position (metrics settle first).
                Platform.runLater(this::syncPreviewToEditorScroll);
            }
        }
        if (changed) {
            onViewModeChanged.run();
        }
    }

    private ScrollPane previewPane() {
        if (previewPane == null) {
            previewPane = new ScrollPane();
            previewPane.setFitToWidth(true);
            previewPane.getStyleClass().add("markdown-preview-pane");
            // Apply the independent preview theme picked before the pane existed.
            if ("light".equals(previewThemeMode)) {
                previewPane.getStyleClass().add("md-light");
            } else if ("dark".equals(previewThemeMode)) {
                previewPane.getStyleClass().add("md-dark");
            }
            // Keyboard scrolling in the preview: Space / PageDown page down, Backspace / PageUp page up
            // (C-v / M-v go through nav.pageDown/Up → pagePreview). Focus it on click so the keys land.
            previewPane.setFocusTraversable(true);
            previewPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                // A press in the preview dismisses an open preview context menu. The ScrollPane consumes
                // the press before the popup's auto-hide can fire (same as the editor menu — see
                // installContextMenu), so close it explicitly; the click still falls through to focus.
                if (previewContextMenu != null && previewContextMenu.isShowing()) {
                    previewContextMenu.hide();
                }
                previewPane.requestFocus();
            });
            // Right-click menu: Select All / Copy (rendered plain text) + Export to PDF / Print.
            previewPane.setOnContextMenuRequested(e -> {
                showPreviewContextMenu(e.getScreenX(), e.getScreenY());
                e.consume();
            });
            previewPane.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShortcutDown()) {
                    return; // leave modifier combos to the global keymap (e.g. C-v / M-v / zoom)
                }
                if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.PAGE_DOWN) {
                    scrollPreviewPage(true);
                    e.consume();
                } else if (e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.PAGE_UP) {
                    scrollPreviewPage(false);
                    e.consume();
                }
            });
            // SPLIT-mode scroll sync (preview → editor); the editor → preview half is in installSplitScrollSync.
            previewPane.vvalueProperty().addListener((o, ov, nv) -> {
                if (syncingScroll) {
                    return; // our own programmatic set
                }
                // A ScrollPane's vvalue moves for two quite different reasons: the user wheeling, and the
                // pane re-anchoring as its content grows. isHover() can't tell them apart, and a preview
                // that renders progressively (typst pages, Mermaid diagrams, images resolving) changes
                // height many times over a second or two — each one arriving here looking exactly like a
                // scroll and dragging the editor with it. That was visible as the editor drifting ~100
                // lines down and back over ~2 s after opening a file in SPLIT, with the mouse merely
                // resting over the preview.
                double h = previewContentHeight();
                if (previewHeightChanged(previewContentHeight, h)) {
                    previewContentHeight = h;
                    previewLayoutChangedAt = System.nanoTime();
                }
                if (previewSettling(System.nanoTime() - previewLayoutChangedAt, PREVIEW_SETTLE_NANOS)) {
                    // Still settling: the editor is the source of truth, so re-anchor the preview to it
                    // rather than letting a layout-driven vvalue move the editor.
                    syncPreviewToEditorScroll();
                    return;
                }
                if (markdownViewMode == MarkdownViewMode.SPLIT && previewPane.isHover()) {
                    syncEditorToPreviewScroll();
                }
            });
        }
        return previewPane;
    }

    /** Injects the preview right-click "Export to PDF" / "Print" actions (controller commands). */
    public void setPreviewExportPdfHandler(Runnable handler) {
        this.previewExportPdfHandler = handler == null ? () -> {} : handler;
    }

    /** Injects the pom preview's "Show as XML" / "Show POM Summary" action (the controller command, so the
     *  menu route and the palette route report the same status and share the refusal guard). */
    public void setPomViewToggleHandler(Runnable handler) {
        this.pomViewToggleHandler = handler == null ? () -> {} : handler;
    }

    /** Injects the Typst preview right-click "Export to PNG" / "Export to SVG" actions (controller commands). */
    public void setPreviewExportPngHandler(Runnable handler) {
        this.previewExportPngHandler = handler == null ? () -> {} : handler;
    }

    public void setPreviewExportSvgHandler(Runnable handler) {
        this.previewExportSvgHandler = handler == null ? () -> {} : handler;
    }

    public void setPreviewPrintHandler(Runnable handler) {
        this.previewPrintHandler = handler == null ? () -> {} : handler;
    }

    /** Injects the preview right-click "Export to Word (.docx)" / "Export to OpenDocument (.odt)" actions. */
    public void setPreviewExportDocxHandler(Runnable handler) {
        this.previewExportDocxHandler = handler == null ? () -> {} : handler;
    }

    public void setPreviewExportOdtHandler(Runnable handler) {
        this.previewExportOdtHandler = handler == null ? () -> {} : handler;
    }

    /** Injects the Markwhen preview "Export to JSON" action (controller command). */
    public void setPreviewExportJsonHandler(Runnable handler) {
        this.previewExportJsonHandler = handler == null ? () -> {} : handler;
    }

    /**
     * Copies the preview to the clipboard — rendered plain text for Markdown, the source for a diagram.
     *
     * <p>Markdown additionally carries a {@code text/html} flavor ({@link MarkdownClipboardHtml}), so the
     * same copy pastes as <em>formatted</em> text into Word / Teams / Outlook / Gmail while a plain-text
     * target still gets the markup-stripped text. Both flavors always go on together: the consumer picks,
     * so there is nothing for the user to configure. The preview's own nodes aren't selectable, so this is
     * whole-document (as it already was for plain text).
     */
    public void copyPreviewToClipboard() {
        String text = isMarkdown() ? MarkdownRenderer.plainText(getContent()) : getContent();
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text == null ? "" : text);
        if (isMarkdown()) {
            cc.putHtml(MarkdownClipboardHtml.toHtml(getContent(), MathImages.isEnabled()));
        }
        Clipboard.getSystemClipboard().setContent(cc);
    }

    /**
     * Copies the preview's HTML <em>markup</em> as plain text (for pasting into an HTML file), as opposed to
     * {@link #copyPreviewToClipboard}, which puts rendered rich text on the clipboard. Markdown only.
     */
    public boolean copyPreviewHtmlSource() {
        if (!isMarkdown()) {
            return false;
        }
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(MarkdownClipboardHtml.toHtml(getContent(), MathImages.isEnabled()));
        Clipboard.getSystemClipboard().setContent(cc);
        return true;
    }

    private void showPreviewContextMenu(double screenX, double screenY) {
        if (previewContextMenu == null) {
            MenuItem selectAll = new MenuItem(tr("editmenu.selectAll"));
            selectAll.setGraphic(MenuIcons.selectAll());
            selectAll.setOnAction(ev -> copyPreviewToClipboard());
            MenuItem copy = new MenuItem(tr("editmenu.copy"));
            copy.setGraphic(MenuIcons.copy());
            copy.setOnAction(ev -> copyPreviewToClipboard());
            previewContextMenu = new javafx.scene.control.ContextMenu(selectAll, copy);
            if (isMarkdown()) {
                // Copy puts rendered rich text on the clipboard; this one puts the HTML markup itself.
                MenuItem copyHtml = new MenuItem(tr("command.preview.copyHtml"));
                copyHtml.setGraphic(MenuIcons.code());
                copyHtml.setOnAction(ev -> copyPreviewHtmlSource());
                previewContextMenu.getItems().add(copyHtml);
            }
            previewContextMenu.getItems().add(new SeparatorMenuItem());
            if (isMarkwhen()) {
                // Markwhen: JSON export + a timeline⇄calendar view switch (PDF/Word/Print don't apply).
                MenuItem json = new MenuItem(tr("command.markwhen.exportJson"));
                json.setGraphic(MenuIcons.download());
                json.setOnAction(ev -> previewExportJsonHandler.run());
                MenuItem viewToggle = new MenuItem();
                viewToggle.setGraphic(MenuIcons.table());
                viewToggle.setOnAction(ev -> toggleMarkwhenView());
                MenuItem pdf = new MenuItem(tr("command.preview.exportPdf"));
                pdf.setGraphic(MenuIcons.download());
                pdf.setOnAction(ev -> previewExportPdfHandler.run());
                MenuItem print = new MenuItem(tr("command.preview.print"));
                print.setGraphic(MenuIcons.print());
                print.setOnAction(ev -> previewPrintHandler.run());
                previewContextMenu.getItems().addAll(json, viewToggle, new SeparatorMenuItem(), pdf, print);
                previewContextMenu.setOnShowing(ev -> viewToggle.setText(
                        markwhenView == MarkwhenView.TIMELINE
                                ? tr("markwhen.switchToCalendar")
                                : tr("markwhen.switchToTimeline")));
            } else {
                MenuItem pdf = new MenuItem(tr("command.preview.exportPdf"));
                pdf.setGraphic(MenuIcons.download());
                pdf.setOnAction(ev -> previewExportPdfHandler.run());
                previewContextMenu.getItems().add(pdf);
                // Typst also exports to PNG / SVG natively (typst -f png/svg).
                if (isTypst()) {
                    MenuItem png = new MenuItem(tr("command.typst.exportPng"));
                    png.setGraphic(MenuIcons.download());
                    png.setOnAction(ev -> previewExportPngHandler.run());
                    MenuItem svg = new MenuItem(tr("command.typst.exportSvg"));
                    svg.setGraphic(MenuIcons.download());
                    svg.setOnAction(ev -> previewExportSvgHandler.run());
                    previewContextMenu.getItems().addAll(png, svg);
                }
                // Word / OpenDocument export — Markdown only (not standalone diagrams).
                if (isMarkdown()) {
                    MenuItem docx = new MenuItem(tr("command.preview.exportDocx"));
                    docx.setGraphic(MenuIcons.download());
                    docx.setOnAction(ev -> previewExportDocxHandler.run());
                    MenuItem odt = new MenuItem(tr("command.preview.exportOdt"));
                    odt.setGraphic(MenuIcons.download());
                    odt.setOnAction(ev -> previewExportOdtHandler.run());
                    previewContextMenu.getItems().addAll(docx, odt);
                }
                MenuItem print = new MenuItem(tr("command.preview.print"));
                print.setGraphic(MenuIcons.print());
                print.setOnAction(ev -> previewPrintHandler.run());
                previewContextMenu.getItems().addAll(new SeparatorMenuItem(), print);
            }
            previewContextMenu.getStyleClass().add("editor-context-menu");
        }
        previewContextMenu.show(previewPane, screenX, screenY);
    }

    /** Scrolls the preview by ~one viewport page (down or up); no-op if there's nothing to scroll. */
    private void scrollPreviewPage(boolean down) {
        if (previewPane == null || previewPane.getContent() == null) {
            return;
        }
        double viewport = previewPane.getViewportBounds().getHeight();
        double contentH = previewPane.getContent().getLayoutBounds().getHeight();
        double scrollable = contentH - viewport;
        if (scrollable <= 0) {
            return; // content fits — nothing to page
        }
        double pageFraction = (viewport * 0.9) / scrollable; // ~90% of a page, leaving a little overlap
        double range = previewPane.getVmax() - previewPane.getVmin();
        double next = previewPane.getVvalue() + (down ? pageFraction : -pageFraction) * range;
        previewPane.setVvalue(Math.max(previewPane.getVmin(), Math.min(previewPane.getVmax(), next)));
    }

    /**
     * Pages the Markdown preview when it's the active scroll target (full PREVIEW mode, or a SPLIT whose
     * preview has focus). Lets {@code nav.pageDown}/{@code nav.pageUp} (C-v / M-v) scroll the preview
     * instead of the hidden/unfocused editor. Returns whether it handled the request.
     */
    public boolean pagePreview(boolean down) {
        boolean previewActive =
                markdownViewMode == MarkdownViewMode.PREVIEW || (previewPane != null && previewPane.isFocusWithin());
        if (!previewActive || previewPane == null) {
            return false;
        }
        scrollPreviewPage(down);
        return true;
    }

    /**
     * Releases this buffer's resources. Must be called by the controller when the tab is
     * <em>actually closed</em> (not on a plain tab switch). The preview/highlight executors are now
     * shared, app-lifetime pools ({@link #PREVIEW_POOL}/{@link #HIGHLIGHT_POOL}), so disposal does not
     * shut them down — it instead bumps {@link #previewGen}/{@link #highlightGen} so any in-flight task
     * submitted by this buffer discards its result (the gen guard) instead of touching the now-dead
     * buffer. The reactfx subscriptions are on the buffer's own {@code area}/{@code area2} and die with
     * it on GC; the shared settled-edit subscription is dropped eagerly too. Idempotent. Once disposed the
     * buffer must not be reused.
     */
    public void dispose() {
        snippetSession.cancel();
        cancelWrapRemeasure();
        completionEditTrackers.clear();
        completionActions.cancelCompletion();
        disposed = true; // reject any LATER dispatch (see below) — the gen bumps only cover in-flight work
        // Drop the undo checkpoints eagerly. They go with the buffer on GC (BufferReleasedOnCloseFxTest
        // pins that the whole buffer becomes collectable when its tab closes), but they are the single
        // largest thing hanging off it -- up to MAX whole-document snapshots -- and releasing them at the
        // moment the tab closes returns that memory now rather than at the next collection.
        undoHistory.clear();
        documentSnapshots.invalidate();
        PreviewSurfaces.release(path, this); // rendered Mermaid/Typst pages only this document could show
        if (settledEditSub != null) {
            settledEditSub.unsubscribe();
            settledEditSub = null;
        }
        settledEdits.dispose();
        disposeMultiCaret();
        previewGen++; // discard any in-flight preview result for this (now closed) buffer
        highlightGen++; // discard any in-flight highlight result
        todoGen++; // discard any in-flight TODO scan result
        runScanGen++; // discard any in-flight run scan result
        languageGen++; // discard any in-flight deferred grammar load
        // RichTextFX's own teardown, last: it stops the caret blink timer — a running timer is a GC root, so a
        // buffer closed while its editor had focus stayed reachable, with its whole window — and closes the
        // undo manager and the area's streams. The document text stays readable.
        area.dispose();
        if (area2 != null) {
            area2.dispose();
        }
    }

    /**
     * Set on {@link #dispose()}; {@link #applyHighlighting()} and the TODO scan refuse to dispatch once
     * true. The gen bumps above only invalidate work already in flight — the still-subscribed debounced
     * edit pulse fires once more ~300 ms after the last change, and before this flag it would re-dispatch
     * a fresh full tokenize of the closed buffer whose generation was then CURRENT, so nothing cancelled
     * it. For a large file that ghost pass held the shared per-grammar monitor for however long the whole
     * document takes (minutes on a slow machine), starving every open buffer of the same language — found
     * as a CI-only suite wedge, but it is an app defect too: closing a big tab right after an edit did
     * exactly this.
     */
    private volatile boolean disposed;

    // --- Multiple cursors + column/box selection (RichTextFX fork) -------------------------------

    /**
     * Enables/disables the multi-caret add-on. When on, installs {@link MultiCarets} on the
     * primary area (and the secondary split view if present); when off, removes it (collapsing to one
     * caret). Skipped for huge files (editing is already inert there). Idempotent.
     */
    public void setMultiCaretEnabled(boolean enabled) {
        this.multiCaretEnabled = enabled;
        if (enabled && !hugeFile) {
            if (multiCaret == null) {
                multiCaret = MultiCarets.install(area, this::tabEdit);
            }
            if (area2 != null && multiCaret2 == null) {
                multiCaret2 = MultiCarets.install(area2, this::tabEdit);
            }
        } else {
            disposeMultiCaret();
        }
    }

    private void disposeMultiCaret() {
        if (multiCaret != null) {
            multiCaret.dispose();
            multiCaret = null;
        }
        if (multiCaret2 != null) {
            multiCaret2.dispose();
            multiCaret2 = null;
        }
    }

    /** The multi-caret add-on of the area that has focus (secondary split view, else primary), or null. */
    private MultiCarets activeCarets() {
        return multiCaret2 != null && area2 != null && area2.isFocused() ? multiCaret2 : multiCaret;
    }

    /** Runs {@code op} on {@link #activeCarets()} when it has extra carets; false → the single-caret path. */
    private boolean withExtras(java.util.function.Consumer<MultiCarets> op) {
        MultiCarets m = activeCarets();
        if (m == null || !m.hasExtras()) {
            return false;
        }
        op.accept(m);
        return true;
    }

    /** True while any area of this buffer has more than the primary caret (extra carets / box selection). */
    public boolean hasMultipleCarets() {
        return (multiCaret != null && multiCaret.hasExtras()) || (multiCaret2 != null && multiCaret2.hasExtras());
    }

    /** Whether any caret of the focused area, primary or extra, has text selected. */
    public boolean anyCaretSelection() {
        MultiCarets m = activeCarets();
        return m != null ? m.anySelection() : getFocusedArea().getSelection().getLength() > 0;
    }

    /** Undo ({@code redo == false}) or Redo on {@code a}; extra carets keep their places and stay usable. */
    public void undoOrRedo(CodeArea a, boolean redo) {
        MultiCarets m = a == area2 ? multiCaret2 : multiCaret;
        Runnable op = redo ? a::redo : a::undo;
        if (m == null) {
            op.run();
        } else {
            m.keepingPrimary(op);
        }
    }

    /** Selects the next occurrence of the current selection as an additional caret (VS Code Cmd/Ctrl+D). */
    public void addCaretNextOccurrence() {
        MultiCarets m = activeCarets();
        if (m != null) {
            m.addNextOccurrence();
        }
    }

    /** Cap on carets a single "select all" places, so a common word can't create hundreds of thousands. */
    public static final int MAX_OCCURRENCE_CARETS = 10_000;

    /**
     * Places a caret with selection at each of {@code ranges} (the one containing {@code anchorStart} stays
     * primary), for "select all occurrences" / "select all matches". Capped at
     * {@link #MAX_OCCURRENCE_CARETS}. Returns the number of carets placed; 0 when {@code ranges} is empty.
     */
    public int placeOccurrenceCarets(List<int[]> ranges, int anchorStart) {
        if (ranges == null || ranges.isEmpty()) {
            return 0;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        int primaryIdx = com.editora.editops.SelectOccurrences.primaryIndex(ranges, anchorStart);
        int[] primary = ranges.get(primaryIdx);
        MultiCarets carets = activeCarets();
        if (carets == null) { // multi-caret unavailable → just select the primary occurrence
            a.selectRange(primary[0], primary[1]);
            a.requestFollowCaret();
            return 1;
        }
        carets.collapse();
        a.selectRange(primary[0], primary[1]);
        int placed = 1;
        for (int i = 0; i < ranges.size() && placed < MAX_OCCURRENCE_CARETS; i++) {
            if (i == primaryIdx) {
                continue;
            }
            int[] r = ranges.get(i);
            carets.getManager().addCaretWithSelection(r[0], r[1]);
            placed++;
        }
        a.requestFollowCaret();
        return placed;
    }

    /**
     * Selects every occurrence of the current selection (or, with none, the word under the caret) as a
     * multi-caret selection — VS Code's {@code selectHighlights} (Ctrl+Shift+L). Case-sensitive literal
     * matching (whole-word when seeded from the caret's word). Returns the number of carets placed (0 when there's nothing to match).
     */
    public int selectAllOccurrences() {
        CodeArea a = focusedArea != null ? focusedArea : area;
        String text = a.getText();
        String query;
        int anchor;
        String sel = a.getSelectedText();
        if (!sel.isEmpty()) {
            query = sel;
            anchor = a.getSelection().getStart();
        } else {
            int[] w = com.editora.editops.SelectOccurrences.wordAt(text, a.getCaretPosition());
            if (w == null) {
                return 0;
            }
            query = text.substring(w[0], w[1]);
            anchor = w[0];
        }
        // A query seeded from the word under a bare caret matches whole words only (id, not valid / width).
        List<int[]> matches = SearchMatcher.matches(text, query, true, false, sel.isEmpty());
        return placeOccurrenceCarets(matches, anchor);
    }

    /** Adds a caret on the line above the topmost caret. */
    public void addCaretAbove() {
        addCaretOnNextLine(false);
    }

    /** Adds a caret on the line below the bottommost caret. */
    public void addCaretBelow() {
        addCaretOnNextLine(true);
    }

    private void addCaretOnNextLine(boolean down) {
        MultiCarets m = activeCarets();
        if (m != null) {
            m.addCaretOnNextLine(down);
        }
    }

    // --- Multi-caret movement fan-out (#635) ------------------------------------------------------
    // The fork's multi-caret movement lives in its node-level InputMap, which the scene-level
    // KeyDispatcher preempts for the Emacs chords. These expose that movement so MainController's
    // nav.* commands can fan out to every caret when extras exist; each returns whether it handled the
    // move (false → no extra carets, so the caller runs the normal single-caret motion). {@code select}
    // extends the selection (= Emacs mark active), mirroring {@code selPolicy()}.

    /** Moves every caret horizontally by {@code amount} chars (or words); see {@link #addCaretBelow}. */
    public boolean multiMoveHorizontal(int amount, boolean byWord, boolean select) {
        return withExtras(m -> m.moveHorizontal(amount, byWord, select));
    }

    /** Moves every caret one line up ({@code down=false}) or down. */
    public boolean multiMoveVertical(boolean down, boolean select) {
        return withExtras(m -> m.moveVertical(down, select));
    }

    /** Moves every caret to its line start ({@code toEnd=false}) or line end. */
    public boolean multiMoveLineBoundary(boolean toEnd, boolean select) {
        return withExtras(m -> m.moveLineBoundary(toEnd, select));
    }

    /** True when {@code a} currently has extra carets, so this buffer's single-caret KEY filters
     *  (auto-indent/close, snippets, completion, view paging) should stand down and let the fork's
     *  multi-caret input map handle the key for every caret. */
    private boolean multiCaretActiveOn(CodeArea a) {
        if (a == area) {
            return multiCaret != null && multiCaret.hasExtras();
        }
        if (a == area2) {
            return multiCaret2 != null && multiCaret2.hasExtras();
        }
        return false;
    }

    /** Copies every caret's selection to the clipboard (VS Code one-line-per-caret) when extra carets
     *  exist; returns whether it handled it (so the caller can fall back to the single-caret copy). */
    public boolean multiCaretCopy() {
        return withExtras(m -> m.copy());
    }

    /** Cuts every caret's selection (multi-caret aware); returns whether it handled it. */
    public boolean multiCaretCut() {
        return withExtras(m -> m.cut());
    }

    /** Pastes at every caret (distributing clipboard lines one per caret); returns whether it handled it. */
    public boolean multiCaretPaste() {
        return withExtras(m -> m.paste());
    }

    /** Puts the whole current line (including a trailing newline) on the clipboard — the empty-selection
     *  Copy of VS Code's {@code editor.emptySelectionClipboard}. Leaves the document untouched. */
    public void copyCurrentLine() {
        CodeArea a = focusedArea != null ? focusedArea : area;
        int p = a.getCurrentParagraph();
        LineClipboard.copy(a, p, folds.hiddenRunEnd(p)); // a collapsed fold's header takes its body along
    }

    /** Cuts the whole current line — copies it then deletes the line as one undoable edit. */
    public void cutCurrentLine() {
        CodeArea a = focusedArea != null ? focusedArea : area;
        int p = a.getCurrentParagraph();
        LineClipboard.cut(a, p, folds.hiddenRunEnd(p));
    }

    /** Collapses any extra carets / box selection back to a single caret. */
    public void collapseCarets() {
        if (multiCaret != null) {
            multiCaret.collapse();
        }
        if (multiCaret2 != null) {
            multiCaret2.collapse();
        }
    }

    /**
     * Forces a preview re-render (e.g. after the Mermaid feature/theme toggles so {@code ```mermaid}
     * blocks switch between diagram and code). No-op in Editor mode.
     */
    public void refreshPreview() {
        scheduleRenderPreview();
    }

    private void scheduleRenderPreview() {
        if (markdownViewMode == MarkdownViewMode.EDITOR) {
            return;
        }
        if (hasCsvPreview()) {
            csvPreviewRefresh.run(); // the coordinator re-parses the buffer text into its per-buffer grid
            return;
        }
        if (hasHttpPreview()) {
            // The HTTP panel shows the result of *running* a request, not a rendering of the buffer text —
            // re-rendering on the debounced edit pulse would be meaningless (and firing requests would be
            // catastrophic). It is repopulated only by HttpClientCoordinator when a run completes. This
            // branch exists so the buffer never falls through to the Markdown tail below.
            return;
        }
        if (hasGithubActionsPreview()) {
            // A workflow is also YAML, so this must precede the structured (YAML tree) branch to win. Parse
            // off-thread, build the specialized workflow digest on the FX thread into the shared host.
            String src = documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    com.editora.ghactions.Workflow parsed = com.editora.ghactions.Workflow.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(GithubActionsPreview.build(parsed));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (hasStructuredPreview()) {
            // Whole file is one JSON/YAML/TOML doc: parse off-thread (pure, cheap), build the tree / OpenAPI
            // docs on the FX thread into the self-scrolling structured host. The previewGen guard drops a
            // superseded render (mirrors the Markwhen branch).
            StructuredParser.Format sfmt = structuredFormat();
            String src = documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    StructuredParser.Parsed parsed = StructuredParser.parse(src, sfmt);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            renderStructured(parsed);
                        }
                    });
                } catch (Throwable t) {
                    // submit() would swallow a Throwable (e.g. a NoClassDefFoundError while loading a Jackson
                    // format factory on this pool thread) into its Future — a silent blank preview. Surface it.
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (hasPomPreview()) {
            // Whole file is a Maven pom: parse off-thread into the display summary (coordinates, properties,
            // dependencies, plugins, profiles), build the section list on the FX thread into the shared
            // self-scrolling host. Must precede the XML branch — a pom is XML, and its summary wins unless
            // the user switched this buffer to the generic tree.
            String src = documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    com.editora.maven.PomSummary parsed = com.editora.maven.PomSummary.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(PomPreview.build(parsed));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (hasXmlPreview()) {
            // Whole file is one XML doc: parse off-thread (JDK DOM), build the DOM tree on the FX thread into
            // the same self-scrolling host as the JSON/YAML/TOML tree. The previewGen guard drops a stale render.
            String src = documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    XmlParser.Parsed parsed = XmlParser.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            renderXml(parsed);
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (hasCrontabPreview()) {
            // Whole file is a crontab: parse off-thread (pure, cheap), decode each schedule + compute the next
            // fire times, build the preview node on the FX thread into the shared self-scrolling host. The
            // "now" is captured here on the FX thread so the render is deterministic (mirrors the tree branch).
            String src = documentTextSnapshot();
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    com.editora.cron.Crontab parsed = com.editora.cron.Crontab.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(CrontabPreview.build(parsed, now));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (hasFstabPreview()) {
            // Whole file is an /etc/fstab: parse off-thread (pure, cheap), decode each mount line into English,
            // build the preview node on the FX thread into the shared self-scrolling host. previewGen-guarded.
            String src = documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    java.util.List<com.editora.fstab.FstabEntry> parsed = com.editora.fstab.Fstab.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(FstabPreview.build(parsed));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (hasSystemdPreview()) {
            // Whole file is a systemd unit: parse off-thread, decode directives (+ OnCalendar next runs) into
            // English, build the preview node on the FX thread into the shared self-scrolling host.
            String src = documentTextSnapshot();
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    com.editora.systemd.SystemdUnit parsed = com.editora.systemd.SystemdUnit.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(SystemdPreview.build(parsed, now));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (hasSshConfigPreview()) {
            // Whole file is an SSH client config: parse off-thread into Host/Match blocks, decode into English.
            String src = documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    java.util.List<com.editora.sshconfig.SshConfig.Block> parsed =
                            com.editora.sshconfig.SshConfig.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(SshConfigPreview.build(parsed));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (hasDockerfilePreview()) {
            // Whole file is a Dockerfile: parse off-thread into build stages, distill each into a digest.
            String src = documentTextSnapshot();
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                try {
                    com.editora.dockerfile.Dockerfile parsed = com.editora.dockerfile.Dockerfile.parse(src);
                    Platform.runLater(() -> {
                        if (gen == previewGen) {
                            structuredContentHolder().getChildren().setAll(DockerfilePreview.build(parsed));
                        }
                    });
                } catch (Throwable t) {
                    surfaceTreePreviewError(gen, t);
                }
            });
            return;
        }
        if (isDiagram()) {
            // Whole file is one diagram: build the (async-filling) node on the FX thread directly. The
            // preview zoom scales the fit width (not font size, which doesn't affect an ImageView); the
            // content-hash cache makes a zoom re-render a cheap re-fit of the same image. Don't call
            // applyPreviewScale() here — it re-enters this branch for diagrams (would recurse).
            double v = previewPane().getVvalue();
            double scale = previewFontScale;
            // A stable per-buffer surface key so live-editing pulses coalesce (only the latest render spawns
            // mmdc) instead of piling up ~4 s Chromium renders on every 250 ms pause (#458).
            String surfaceKey = PreviewSurfaces.mermaid(path, this);
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(
                    MermaidImages.node(documentTextSnapshot(), lw -> lw * scale, surfaceKey));
            box.getStyleClass().add("markdown-preview");
            StackPane wrap = new StackPane(box);
            wrap.getStyleClass().add("markdown-preview-wrap");
            previewPane().setContent(wrap);
            Platform.runLater(() -> previewPane().setVvalue(v));
            return;
        }
        if (hasSvgPreview()) {
            // Whole file is one SVG: rasterize off-thread via JSVG (in-process, no CLI), same async-image
            // model as Mermaid/diagrams. Zoom scales the fit width; the content-hash cache makes a zoom
            // re-render a cheap re-fit.
            double v = previewPane().getVvalue();
            double scale = previewFontScale;
            javafx.scene.layout.VBox box =
                    new javafx.scene.layout.VBox(SvgImages.node(documentTextSnapshot(), lw -> lw * scale));
            box.getStyleClass().add("markdown-preview");
            StackPane wrap = new StackPane(box);
            wrap.getStyleClass().add("markdown-preview-wrap");
            previewPane().setContent(wrap);
            Platform.runLater(() -> previewPane().setVvalue(v));
            return;
        }
        DiagramKind dk = diagramKind();
        if (dk != null) {
            // Whole file is one DOT/PlantUML diagram — same async-image model as Mermaid (see above), via
            // the generic DiagramImages façade. Zoom scales the fit width; the content-hash cache makes a
            // zoom re-render a cheap re-fit. Don't call applyPreviewScale() here (it re-enters this branch).
            double v = previewPane().getVvalue();
            double scale = previewFontScale;
            String surfaceKey = "diagram@" + System.identityHashCode(this);
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(
                    DiagramImages.node(dk, documentTextSnapshot(), lw -> lw * scale, surfaceKey));
            box.getStyleClass().add("markdown-preview");
            StackPane wrap = new StackPane(box);
            wrap.getStyleClass().add("markdown-preview-wrap");
            previewPane().setContent(wrap);
            Platform.runLater(() -> previewPane().setVvalue(v));
            return;
        }
        if (hasTypstPreview()) {
            // Whole file is one Typst document rendered to stacked page images via the typst CLI — the same
            // async-image model as Mermaid/diagrams, but multi-page. Zoom scales the fit width; the
            // content-hash cache + retain-last-image (in TypstImages) make live editing update in place
            // without flicker.
            double v = previewPane().getVvalue();
            double scale = previewFontScale;
            // The throwaway input is written in the file's own folder so relative #image/#import resolve as
            // on disk; --root is that folder or a higher project root (via the injected resolver) so a
            // multi-file project's up-references resolve too. Local saved files only — a remote (SFTP) path
            // can't be a working dir for the local typst process, so both fall back to an isolated temp root.
            boolean local = path != null && path.getFileSystem() == java.nio.file.FileSystems.getDefault();
            java.nio.file.Path fileDir = local ? path.getParent() : null;
            java.nio.file.Path root = local && typstRootResolver != null ? typstRootResolver.apply(path) : fileDir;
            String retainKey = PreviewSurfaces.typstRetain(path, this);
            String surfaceKey = PreviewSurfaces.typst(this);
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(TypstImages.node(
                    documentTextSnapshot(), lw -> lw * scale, retainKey, surfaceKey, fileDir, root, getDisplayName()));
            box.getStyleClass().add("markdown-preview");
            StackPane wrap = new StackPane(box);
            wrap.getStyleClass().add("markdown-preview-wrap");
            previewPane().setContent(wrap);
            Platform.runLater(() -> previewPane().setVvalue(v));
            return;
        }
        if (isMarkwhen()) {
            // Whole file is one timeline. Parse off-thread (pure, cheap), build the node on the FX thread.
            // Horizontal zoom scales the axis width; preserve both scroll positions (the axis scrolls
            // horizontally). The previewGen guard drops a superseded render.
            String src = documentTextSnapshot();
            double vw = previewPane().getViewportBounds().getWidth();
            double scale = previewFontScale;
            long gen = ++previewGen;
            PREVIEW_POOL.submit(() -> {
                com.editora.markwhen.Timeline model = com.editora.markwhen.MarkwhenParser.parse(src);
                Platform.runLater(() -> {
                    if (gen != previewGen) {
                        return;
                    }
                    double v = previewPane().getVvalue();
                    double h = previewPane().getHvalue();
                    Node timeline = markwhenView == MarkwhenView.CALENDAR
                            ? MarkwhenCalendar.build(model, scale, vw)
                            : MarkwhenTimeline.build(model, scale, vw);
                    javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(timeline);
                    box.getStyleClass().add("markdown-preview");
                    StackPane wrap = new StackPane(box);
                    wrap.getStyleClass().add("markdown-preview-wrap");
                    previewPane().setContent(wrap);
                    previewPane().setVvalue(v);
                    previewPane().setHvalue(h);
                });
            });
            return;
        }
        String md = documentTextSnapshot();
        Path baseDir = path == null ? null : path.getParent();
        long gen = ++previewGen;
        PREVIEW_POOL.submit(() -> {
            // Guarded like every other preview branch. commonmark 0.30.0 added input limits that *throw*
            // rather than degrade — a table over a million cells is the reachable one (the nesting limits
            // fall back to plain text instead). Unguarded, submit() parks that in a Future nobody reads, so
            // the preview would sit blank with nothing logged and no way to tell it apart from a slow render.
            try {
                org.commonmark.node.Node ast = MarkdownRenderer.parseToDocument(md);
                Platform.runLater(() -> {
                    if (gen != previewGen) {
                        return; // a newer render superseded this one
                    }
                    double v = previewPane().getVvalue();
                    previewPane().setContent(MarkdownRenderer.renderDocument(ast, baseDir, openUrlHandler));
                    applyPreviewScale(); // keep the current zoom on the freshly rendered content
                    Platform.runLater(() -> previewPane().setVvalue(v)); // best-effort scroll preserve
                });
            } catch (Throwable t) {
                surfaceMarkdownPreviewError(gen, t);
            }
        });
    }

    /**
     * Rebuilds {@link #viewHost} for the current modes. The floating control is parented to the editor
     * pane ({@link #root}) in Editor mode, at the right edge of the code area; in Split and Preview it
     * overlays the preview — on a split's half-width code pane it sat over the first line of text, and
     * this way it also stays in the tab's top-right corner in all three modes.
     */
    private void rebuildViewHost() {
        detachViewModeControl();
        Node content;
        if (markdownViewMode != MarkdownViewMode.EDITOR) {
            StackPane host = previewModeHost();
            if (viewModeControl != null) {
                StackPane.setAlignment(viewModeControl, Pos.TOP_RIGHT);
                StackPane.setMargin(viewModeControl, new Insets(6, 10, 0, 0));
                host.getChildren().add(viewModeControl);
            }
            content = host;
            if (markdownViewMode == MarkdownViewMode.SPLIT) {
                SplitPane pane = new SplitPane(root, host);
                pane.setOrientation(Orientation.HORIZONTAL);
                pane.setDividerPositions(0.5);
                attachControlToCodePane();
                content = pane;
            }
        } else if (split != Split.NONE) {
            ensureSecondaryView();
            SplitPane pane = new SplitPane(root, root2);
            pane.setOrientation(split == Split.SIDE_BY_SIDE ? Orientation.HORIZONTAL : Orientation.VERTICAL);
            pane.setDividerPositions(0.5);
            attachControlToCodePane();
            content = pane;
        } else {
            attachControlToCodePane();
            content = root;
        }
        boolean hadFocus = area2 != null && area2.isFocused(); // leaving a split takes that view off the scene
        int top2 = hadFocus ? ScrollAnchor.firstVisibleLine(area2) : -1;
        viewHost.getChildren().setAll(content);
        if (area2 != null && (split == Split.NONE || markdownViewMode != MarkdownViewMode.EDITOR)) {
            focusedArea = area;
            focusedView.set(area);
            if (hadFocus) {
                // The user was looking at the second view: the one that stays takes over its caret, its
                // selection and its place in the file, and the keyboard (or JavaFX hands that to the first
                // control in the window).
                ScrollAnchor.adopt(area, area2, top2);
                area.requestFocus();
            }
        }
    }

    /**
     * The PREVIEW-mode wrapper for whichever preview this buffer has. Each self-scrolling preview (CSV grid,
     * HTTP responses, the structured/tree family) brings its own {@code StackPane} so the floating mode toggle
     * can be overlaid on it; everything else falls through to the Markdown-style {@link #previewHost()}.
     */
    private StackPane previewModeHost() {
        if (hasCsvPreview()) {
            return csvPreviewHost();
        }
        if (hasHttpPreview()) {
            return httpPreviewHost();
        }
        if (hasTreePreview()) {
            return structuredPreviewHost();
        }
        return previewHost(); // preview (+ zoom for markdown)
    }

    private StackPane previewHost() {
        if (previewHost == null) {
            previewHost = new StackPane();
        }
        // preview content + the −/+ zoom control (top-left, clear of the mode toggle at top-right) + the
        // (normally hidden) centered loading overlay.
        previewHost.getChildren().setAll(previewPane(), zoomControl(), previewLoadingOverlay());
        StackPane.setAlignment(zoomControl(), Pos.TOP_LEFT);
        StackPane.setMargin(zoomControl(), new Insets(6, 0, 0, 6));
        StackPane.setAlignment(previewLoadingOverlay(), Pos.CENTER);
        return previewHost;
    }

    /** Wraps the injected CSV grid in a StackPane so the floating Editor/Split/Preview toggle can overlay it
     *  in PREVIEW mode (the grid has no scroll-pane host like Markdown; it manages its own scrolling). */
    private StackPane csvPreviewHost() {
        if (csvPreviewHost == null) {
            csvPreviewHost = new StackPane();
        }
        csvPreviewHost.getChildren().setAll(csvPreviewNode); // the toggle is added by rebuildViewHost()
        return csvPreviewHost;
    }

    /** Wraps the injected HTTP response panel so the mode toggle can overlay it in PREVIEW mode (the panel
     *  scrolls its own response body, so it needs no ScrollPane host — mirrors {@link #csvPreviewHost()}). */
    private StackPane httpPreviewHost() {
        if (httpPreviewHost == null) {
            httpPreviewHost = new StackPane();
        }
        httpPreviewHost.getChildren().setAll(httpPreviewNode); // the toggle is added by rebuildViewHost()
        return httpPreviewHost;
    }

    /** Sets the freshly parsed structured node (tree / OpenAPI docs / error) into the self-scrolling holder. */
    private void renderStructured(StructuredParser.Parsed parsed) {
        lastStructuredOpenApi = parsed.isOpenApi();
        Node node;
        if (!parsed.ok()) {
            Label err = new Label(parsed.error());
            err.getStyleClass().add("structured-error");
            err.setWrapText(true);
            node = err;
        } else {
            boolean showDocs = parsed.isOpenApi() && (structuredShowApiDocs == null || structuredShowApiDocs);
            node = showDocs ? OpenApiDoc.build(parsed.openApi()) : StructuredTree.build(parsed.root());
        }
        structuredContentHolder().getChildren().setAll(node);
    }

    /** Sets the freshly parsed XML DOM tree (or an error label) into the shared self-scrolling holder. */
    private void renderXml(XmlParser.Parsed parsed) {
        Node node;
        if (!parsed.ok()) {
            Label err = new Label(parsed.error());
            err.getStyleClass().add("structured-error");
            err.setWrapText(true);
            node = err;
        } else {
            node = XmlTree.build(parsed.root());
        }
        structuredContentHolder().getChildren().setAll(node);
    }

    /**
     * Surfaces a Throwable from a {@code PREVIEW_POOL.submit(...)} tree-preview task (structured/XML) that the
     * task's {@code Future} would otherwise swallow into a silent blank — the {@code exec.submit}
     * Error-swallowing trap the project conventions warn about — as a logged warning + a visible error label
     * in the shared holder, so a failed render is diagnosable rather than a blank pane.
     */
    /** The {@link #surfaceTreePreviewError} counterpart for the Markdown preview, which has its own pane. */
    private void surfaceMarkdownPreviewError(long gen, Throwable t) {
        java.util.logging.Logger.getLogger(EditorBuffer.class.getName())
                .log(java.util.logging.Level.WARNING, "markdown preview render failed", t);
        String msg = t.getMessage() != null ? t.getMessage() : t.toString();
        Platform.runLater(() -> {
            if (gen != previewGen) {
                return;
            }
            Label err = new Label(msg);
            err.getStyleClass().add("structured-error");
            err.setWrapText(true);
            previewPane().setContent(err);
        });
    }

    private void surfaceTreePreviewError(long gen, Throwable t) {
        java.util.logging.Logger.getLogger(EditorBuffer.class.getName())
                .log(java.util.logging.Level.WARNING, "tree preview render failed", t);
        String msg = t.getMessage() != null ? t.getMessage() : t.toString();
        Platform.runLater(() -> {
            if (gen != previewGen) {
                return;
            }
            Label err = new Label(msg);
            err.getStyleClass().add("structured-error");
            err.setWrapText(true);
            structuredContentHolder().getChildren().setAll(err);
        });
    }

    /** The self-scrolling structured preview node holder — used directly as the Split preview side. */
    private StackPane structuredContentHolder() {
        if (structuredContentHolder == null) {
            structuredContentHolder = new StackPane();
            // The JSON/YAML/TOML + XML trees host their own TreeView (not previewPane), so they need their
            // own right-click menu — an Export to PDF (a full snapshot of the tree), like every other preview.
            structuredContentHolder.setOnContextMenuRequested(
                    e -> showTreePreviewContextMenu(e.getScreenX(), e.getScreenY()));
        }
        return structuredContentHolder;
    }

    private void showTreePreviewContextMenu(double screenX, double screenY) {
        if (treePreviewContextMenu == null) {
            MenuItem pdf = new MenuItem(tr("command.preview.exportPdf"));
            pdf.setGraphic(MenuIcons.download());
            pdf.setOnAction(ev -> previewExportPdfHandler.run());
            MenuItem print = new MenuItem(tr("command.preview.print"));
            print.setGraphic(MenuIcons.print());
            print.setOnAction(ev -> previewPrintHandler.run());
            pomViewItem = new MenuItem();
            pomViewItem.setGraphic(MenuIcons.code());
            pomViewItem.setOnAction(ev -> pomViewToggleHandler.run());
            treePreviewContextMenu = new javafx.scene.control.ContextMenu(pomViewItem, pdf, print);
            treePreviewContextMenu.getStyleClass().add("editor-context-menu");
        }
        // The pom summary is the only tree-hosted preview with a second rendering of the same file, so its
        // switch is the only item here that isn't shown for every one of them. Relabelled per show (it names
        // the view it switches *to*), since the buffer can be flipped from the palette between two shows.
        boolean pom = isPom();
        pomViewItem.setVisible(pom);
        if (pom) {
            pomViewItem.setText(tr(pomShowXml ? "pom.menu.showSummary" : "pom.menu.showXml"));
        }
        treePreviewContextMenu.show(structuredContentHolder(), screenX, screenY);
    }

    // ---- Preview → PDF snapshot (SVG / Markwhen / JSON-YAML-TOML / XML) -------------------------------
    /** Max rows captured for a tree PDF (the parser already caps nodes at 50k; this bounds the image size). */
    private static final int MAX_PRINT_ROWS = 4000;
    /** Rows per snapshot chunk — each chunk is one bounded image, so a big tree can't build a giant texture. */
    private static final int ROWS_PER_CHUNK = 250;
    /** Fixed width the Markwhen timeline is re-laid-out to for its export snapshot. */
    private static final double EXPORT_TIMELINE_WIDTH = 1100;

    /**
     * Renders the current image/tree preview to a list of PNG images for PDF export (a full snapshot of the
     * whole tree/timeline, captured in bounded chunks — not just the visible viewport). Returns {@code null}
     * for a buffer whose preview isn't snapshot-based (Markdown/CSV/Mermaid/diagram export semantically /
     * via their CLI instead). FX thread only.
     */
    public java.util.List<byte[]> snapshotPreviewChunks(String lightUaStylesheet) {
        if (hasGithubActionsPreview()) {
            javafx.scene.layout.VBox box = GithubActionsPreview.content(
                    com.editora.ghactions.Workflow.parse(area.getText()), EXPORT_TIMELINE_WIDTH);
            box.getStyleClass().add("markdown-preview");
            byte[] png = snapshotNodePng(box, lightUaStylesheet);
            return png == null ? null : java.util.List.of(png);
        }
        if (isStructured()) {
            StructuredParser.Parsed p = StructuredParser.parse(area.getText(), structuredFormat());
            return p.ok()
                    ? snapshotRows(StructuredTree.printableRows(p.root()), "structured-tree", lightUaStylesheet)
                    : null;
        }
        if (hasPomPreview()) {
            javafx.scene.layout.VBox box =
                    PomPreview.content(com.editora.maven.PomSummary.parse(area.getText()), EXPORT_TIMELINE_WIDTH);
            box.getStyleClass().add("markdown-preview");
            byte[] png = snapshotNodePng(box, lightUaStylesheet);
            return png == null ? null : java.util.List.of(png);
        }
        if (isXml()) {
            XmlParser.Parsed p = XmlParser.parse(area.getText());
            return p.ok() ? snapshotRows(XmlTree.printableRows(p.root()), "xml-tree", lightUaStylesheet) : null;
        }
        if (isCrontab()) {
            com.editora.cron.Crontab parsed = com.editora.cron.Crontab.parse(area.getText());
            javafx.scene.layout.VBox box =
                    CrontabPreview.content(parsed, java.time.LocalDateTime.now(), EXPORT_TIMELINE_WIDTH);
            box.getStyleClass().add("markdown-preview");
            byte[] png = snapshotNodePng(box, lightUaStylesheet);
            return png == null ? null : java.util.List.of(png);
        }
        if (isFstab()) {
            javafx.scene.layout.VBox box =
                    FstabPreview.content(com.editora.fstab.Fstab.parse(area.getText()), EXPORT_TIMELINE_WIDTH);
            box.getStyleClass().add("markdown-preview");
            byte[] png = snapshotNodePng(box, lightUaStylesheet);
            return png == null ? null : java.util.List.of(png);
        }
        if (isSystemd()) {
            javafx.scene.layout.VBox box = SystemdPreview.content(
                    com.editora.systemd.SystemdUnit.parse(area.getText()),
                    java.time.LocalDateTime.now(),
                    EXPORT_TIMELINE_WIDTH);
            box.getStyleClass().add("markdown-preview");
            byte[] png = snapshotNodePng(box, lightUaStylesheet);
            return png == null ? null : java.util.List.of(png);
        }
        if (isSshConfig()) {
            javafx.scene.layout.VBox box = SshConfigPreview.content(
                    com.editora.sshconfig.SshConfig.parse(area.getText()), EXPORT_TIMELINE_WIDTH);
            box.getStyleClass().add("markdown-preview");
            byte[] png = snapshotNodePng(box, lightUaStylesheet);
            return png == null ? null : java.util.List.of(png);
        }
        if (isDockerfile()) {
            javafx.scene.layout.VBox box = DockerfilePreview.content(
                    com.editora.dockerfile.Dockerfile.parse(area.getText()), EXPORT_TIMELINE_WIDTH);
            box.getStyleClass().add("markdown-preview");
            byte[] png = snapshotNodePng(box, lightUaStylesheet);
            return png == null ? null : java.util.List.of(png);
        }
        if (isMarkwhen()) {
            com.editora.markwhen.Timeline model = com.editora.markwhen.MarkwhenParser.parse(area.getText());
            Node timeline = markwhenView == MarkwhenView.CALENDAR
                    ? MarkwhenCalendar.build(model, 1.0, EXPORT_TIMELINE_WIDTH)
                    : MarkwhenTimeline.build(model, 1.0, EXPORT_TIMELINE_WIDTH);
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(timeline);
            box.getStyleClass().add("markdown-preview");
            byte[] png = snapshotNodePng(box, lightUaStylesheet);
            return png == null ? null : java.util.List.of(png);
        }
        return null;
    }

    /** Snapshots a flat, indented row list in bounded chunks (one image each) styled as {@code treeClass}. */
    private java.util.List<byte[]> snapshotRows(java.util.List<Node> rows, String treeClass, String lightUa) {
        int total = Math.min(rows.size(), MAX_PRINT_ROWS);
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        for (int i = 0; i < total; i += ROWS_PER_CHUNK) {
            javafx.scene.layout.VBox chunk = new javafx.scene.layout.VBox();
            chunk.getStyleClass().add(treeClass);
            chunk.setFillWidth(false);
            chunk.setPadding(new javafx.geometry.Insets(6));
            chunk.getChildren().addAll(rows.subList(i, Math.min(i + ROWS_PER_CHUNK, total)));
            byte[] png = snapshotNodePng(chunk, lightUa);
            if (png != null) {
                out.add(png);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Lays a node out off-screen (a throwaway {@link javafx.scene.Scene} carrying this buffer's app/syntax
     * stylesheets so the token CSS resolves) at its preferred size and snapshots it to PNG bytes. The scene's
     * user-agent stylesheet is forced to {@code lightUaStylesheet} (Primer Light) when given, so the
     * {@code -color-*}-based tree/timeline colors resolve to an ink-friendly light palette regardless of the
     * app theme (a snapshot PDF/print is always light, like the native-vector exporters). Returns {@code null}
     * on a zero-size result / render failure.
     */
    private byte[] snapshotNodePng(Node node, String lightUaStylesheet) {
        try {
            javafx.scene.Group holder = new javafx.scene.Group(node);
            javafx.scene.Scene sc = new javafx.scene.Scene(holder);
            javafx.scene.Scene live = getNode().getScene();
            if (live != null) {
                sc.getStylesheets().setAll(live.getStylesheets());
            }
            // Force a light user-agent theme for the export (else the scene inherits the app's global UA,
            // dark or light). A null falls back to the inherited/global UA (e.g. in a headless test).
            if (lightUaStylesheet != null) {
                sc.setUserAgentStylesheet(lightUaStylesheet);
            } else if (live != null && live.getUserAgentStylesheet() != null) {
                sc.setUserAgentStylesheet(live.getUserAgentStylesheet());
            }
            holder.applyCss();
            holder.layout();
            javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
            sp.setFill(javafx.scene.paint.Color.WHITE); // backstop behind any transparent margins
            javafx.scene.image.WritableImage img = node.snapshot(sp, null);
            if (img == null || img.getWidth() < 1 || img.getHeight() < 1) {
                return null;
            }
            return PreviewImageLoader.imageToPng(img);
        } catch (RuntimeException e) {
            java.util.logging.Logger.getLogger(EditorBuffer.class.getName())
                    .log(java.util.logging.Level.WARNING, "preview snapshot failed", e);
            return null;
        }
    }

    /** Wraps the structured holder so the floating Editor/Split/Preview toggle can overlay it in PREVIEW mode. */
    private StackPane structuredPreviewHost() {
        if (structuredPreviewHost == null) {
            structuredPreviewHost = new StackPane();
        }
        structuredPreviewHost
                .getChildren()
                .setAll(structuredContentHolder()); // the toggle is added by rebuildViewHost()
        return structuredPreviewHost;
    }

    /**
     * Centered spinner + message overlaid on the preview while it's expected to render soon but hasn't
     * yet — e.g. an AI-generated explanation streams into the buffer, but the debounced preview re-render
     * only fires ~250ms after the text stops changing, so a fast continuous stream can leave the preview
     * blank for its whole duration with no feedback otherwise. Toggled by {@link #setPreviewLoading}.
     */
    private Node previewLoadingOverlay() {
        if (previewLoadingOverlay == null) {
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setMaxSize(32, 32);
            previewLoadingLabel = new Label();
            previewLoadingLabel.getStyleClass().add("markdown-preview-loading-label");
            VBox box = new VBox(10, spinner, previewLoadingLabel);
            box.setAlignment(Pos.CENTER);
            box.getStyleClass().add("markdown-preview-loading");
            box.setVisible(false);
            box.setManaged(false);
            box.setMouseTransparent(true); // never intercepts clicks meant for the preview underneath
            previewLoadingOverlay = box;
        }
        return previewLoadingOverlay;
    }

    /**
     * Shows or hides the preview's centered loading spinner (see {@link #previewLoadingOverlay()}).
     * {@code message} replaces the label text when non-null; pass {@code false} once real content is
     * ready (or generation fails) to hide it again.
     */
    public void setPreviewLoading(boolean loading, String message) {
        Node overlay = previewLoadingOverlay();
        if (message != null) {
            previewLoadingLabel.setText(message);
        }
        overlay.setVisible(loading);
        overlay.setManaged(loading);
    }

    private HBox zoomControl() {
        if (zoomControl == null) {
            Button out = PreviewButtons.named("−", tr("project.map.preview.zoomOut"), this::zoomPreviewOut);
            Button in = PreviewButtons.named("+", tr("project.map.preview.zoomIn"), this::zoomPreviewIn);
            // Light/dark preview-theme toggle (independent of the app theme). Glyph shows what a click
            // switches TO: a moon while the preview is light, a sun while it's dark.
            previewThemeButton = PreviewButtons.named("", "", () -> previewThemeToggle.run());
            updateThemeButtonGlyph();
            zoomControl = new HBox(out, in, previewThemeButton);
            zoomControl.getStyleClass().add("md-zoom");
            zoomControl.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            zoomControl.setPickOnBounds(false);
        }
        return zoomControl;
    }

    /** Injects the controller's global preview light/dark toggle (run by the floating sun/moon button). */
    public void setPreviewThemeToggle(Runnable toggle) {
        if (toggle != null) {
            this.previewThemeToggle = toggle;
        }
    }

    /**
     * Applies the Markdown preview color theme, independent of the app theme. {@code mode} is "" (follow
     * the app theme), "light", or "dark"; {@code appDark} is the app theme's brightness (for "follow" +
     * the toggle glyph). Adds {@code md-light}/{@code md-dark} to the preview pane (which redefine the
     * looked-up colors for the preview subtree) and refreshes the toggle glyph.
     */
    public void applyPreviewTheme(String mode, boolean appDark) {
        this.previewThemeMode = mode == null ? "" : mode;
        this.previewAppDark = appDark;
        if (previewPane != null) {
            previewPane.getStyleClass().removeAll("md-light", "md-dark");
            if ("light".equals(previewThemeMode)) {
                previewPane.getStyleClass().add("md-light");
            } else if ("dark".equals(previewThemeMode)) {
                previewPane.getStyleClass().add("md-dark");
            }
        }
        updateThemeButtonGlyph();
    }

    /** Whether the preview currently renders dark: explicit "dark", or "follow app" with a dark app theme. */
    private boolean previewEffectiveDark() {
        return "dark".equals(previewThemeMode) || (previewThemeMode.isEmpty() && previewAppDark);
    }

    private void updateThemeButtonGlyph() {
        if (previewThemeButton == null) {
            return;
        }
        boolean dark = previewEffectiveDark();
        previewThemeButton.setText(dark ? "☀" : "☾"); // ☀ (→ light) when dark; ☾ (→ dark) when light
        PreviewButtons.name(
                previewThemeButton, tr(dark ? "markdown.previewTheme.toLight" : "markdown.previewTheme.toDark"));
    }

    /** Zooms the preview text in/out (multiplicative steps, clamped) or resets to 100%. */
    public void zoomPreviewIn() {
        setPreviewFontScale(previewFontScale * 1.1);
    }

    public void zoomPreviewOut() {
        setPreviewFontScale(previewFontScale / 1.1);
    }

    public void resetPreviewZoom() {
        setPreviewFontScale(1.0);
    }

    private void setPreviewFontScale(double scale) {
        previewFontScale = Math.max(0.5, Math.min(3.0, scale));
        applyPreviewScale();
    }

    /** Applies the current zoom: re-fits a diagram to the scaled width, else overrides the preview's base
     *  font size (headings use em). */
    private void applyPreviewScale() {
        if (previewPane == null || previewPane.getContent() == null) {
            return;
        }
        if (isDiagram() || isRenderedDiagram() || hasSvgPreview() || hasTypstPreview()) {
            scheduleRenderPreview(); // re-fit the diagram/SVG/Typst image to the new zoom (cache hit — cheap)
            return;
        }
        if (isMarkwhen()) {
            scheduleRenderPreview(); // re-render the timeline at the new axis scale (parse is cheap + off-thread)
            return;
        }
        previewPane.getContent().setStyle("-fx-font-size: " + (BASE_PREVIEW_FONT * previewFontScale) + "px;");
    }

    /** Removes the floating corner control(s) from whichever pane currently hosts them. */
    private void detachViewModeControl() {
        removeCornerControl(viewModeControl);
        removeCornerControl(htmlPreviewControl);
    }

    private void removeCornerControl(Node control) {
        if (control == null) {
            return;
        }
        cornerControls.getChildren().remove(control);
        if (cornerControls.getChildren().isEmpty()) {
            root.getChildren().remove(cornerControls);
        }
        if (previewHost != null) {
            previewHost.getChildren().remove(control);
        }
        if (csvPreviewHost != null) {
            csvPreviewHost.getChildren().remove(control);
        }
    }

    /** Overlays the corner control(s) at the top-right of the code pane ({@link #root}), clear of its minimap.
     *  They share one row ({@link #cornerControls}), so however many are present they sit side by side. */
    private void attachControlToCodePane() {
        placeCornerControl(markdownViewMode == MarkdownViewMode.EDITOR ? viewModeControl : null);
        placeCornerControl(htmlPreviewControl);
        placeStickyScroll();
    }

    /**
     * Anchors the sticky-scroll bar across the top of the code pane. Unlike the corner controls it spans
     * the width, so it clears the scrollbar gutter on the right and the minimap beyond it — a pinned line
     * running under either would be clipped mid-token.
     */
    private void placeStickyScroll() {
        Node bar = stickyScroll.node();
        StickyScrollBar.anchor(bar, codePaneControlInset()); // writes only a changed constraint
        if (bar.getParent() != root) {
            root.getChildren().add(bar);
            // The bar is attached lazily when scrolling first produces pinned lines, while file-specific
            // controls may already be present. AnchorPane paints later children on top, so without an
            // explicit order that timing lets the full-width bar cover Markdown's view-mode toggle or
            // HTML's browser button. A control row attached after the bar is added last and so is above it.
            if (cornerControls.getParent() == root) {
                cornerControls.toFront();
            }
        }
    }

    /**
     * Adds {@code control} to the shared top-right row, mounting the row on first use. The Expert exit
     * control stays outermost so it does not move when a file-specific control comes and goes.
     */
    private void placeCornerControl(Node control) {
        if (control == null) {
            return;
        }
        List<Node> row = cornerControls.getChildren();
        if (!row.contains(control)) {
            int expert = control == expertExitControl ? -1 : row.indexOf(expertExitControl);
            row.add(expert < 0 ? row.size() : expert, control);
        }
        if (cornerControls.getParent() != root) {
            root.getChildren().add(cornerControls);
            AnchorPane.setTopAnchor(cornerControls, 6d);
        }
        AnchorPane.setRightAnchor(cornerControls, codePaneControlInset());
    }

    /**
     * Width the editor's own vertical scrollbar takes on the right edge of the code pane. The floating
     * controls clear it: sitting on the thumb both looks wrong and swallows the drag, since the control is
     * on top.
     */
    private static final double SCROLLBAR_GUTTER = 14;

    private double codePaneControlInset() {
        return SCROLLBAR_GUTTER + ((minimapVisible && !largeFile && !heavyFile) ? Minimap.WIDTH + 6 : 10);
    }

    /** Keeps every floating editor control clear of the minimap when it toggles (no full view rebuild). */
    private void positionCornerControls() {
        if (cornerControls.getParent() == root) {
            AnchorPane.setRightAnchor(cornerControls, codePaneControlInset());
        }
    }

    /**
     * Mounts the Expert-mode exit control inside the primary code pane, clear of its scrollbar and minimap,
     * beside any file-specific corner control. Passing {@code null} detaches the current control. A
     * controller moves the one shared button as the active tab changes, so inactive buffers retain no
     * overlay node.
     */
    public void setExpertExitControl(Node control) {
        if (expertExitControl != control) {
            removeCornerControl(expertExitControl);
        }
        expertExitControl = control;
        placeCornerControl(control);
    }

    private static HBox cornerControlRow() {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setFillHeight(false); // each control keeps its own height
        row.setPickOnBounds(false); // clicks between controls fall through to the editor
        return row;
    }

    /** Lazily builds the secondary view (scroll pane + its own minimap) sharing this document. */
    private void ensureSecondaryView() {
        if (area2 != null) {
            refreshGutter(); // marks and folds changed while it was off screen
            return;
        }
        area2 = tagRename.newArea(area.getContent()); // shares the EditableStyledDocument
        area2.getStyleClass().add("editor-area");
        area2.wrapTextProperty().bind(area.wrapTextProperty()); // one Word Wrap setting, two views
        area2.setLineHighlighterOn(area.isLineHighlighterOn());
        area2.setUndoManager(area.getUndoManager()); // one history for the shared document
        area2.setEditable(area.isEditable());
        addViewModePaging(area2); // same pager keys in the secondary split view
        completionActions.addCompletionKeys(area2);
        completionActions.installCommitCharacters(area2);
        addSnippetKeys(area2);
        addAutoClose(area2);
        addAutoIndent(area2);
        completionActions.installCompletionTrigger(area2);
        installOccurrenceTrigger(area2); // LSP document highlight (#675)
        installCodeLensClick(area2);
        installImageDrop(area2);
        if (multiCaretEnabled && !hugeFile && multiCaret2 == null) {
            multiCaret2 = MultiCarets.install(area2, this::tabEdit); // same multi-caret add-on in the split view
        }
        area2.setLineHighlighterFill(lineHighlightColor);
        refreshGutter();
        area2.setStyle("-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;");
        area2.caretPositionProperty().addListener((obs, old, now) -> {
            resetGoalColumn();
            scheduleBraceMatch();
        });
        area2.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                focusedArea = area2;
                focusedView.set(area2);
            }
        });
        installFormatBarListeners(area2);
        installContextMenu(area2);
        scrollPane2 = new VirtualizedScrollPane<>(area2);
        // Give the secondary view its own minimap (tracks this pane's viewport), docked like the primary.
        minimap2 = minimap.follower(area2);
        pane2 = new SecondaryPane(area2, scrollPane2, minimap2, scrollPane, minimap);
        root2 = pane2.root();
        pane2.follow(root.getChildren()); // the same overlays and stripes as pane 1, in the same stack
        installPointerHovers(area2);
        applyMinimap(scrollPane2, minimap2, minimapVisible && !largeFile && !heavyFile);
    }

    /** Docks/undocks a minimap on the right of its scroll pane (shared by both split panes). */
    private static void applyMinimap(Region scroll, Minimap mm, boolean visible) {
        mm.setVisible(visible);
        mm.setManaged(visible);
        AnchorPane.setRightAnchor(scroll, visible ? Minimap.WIDTH : 0d);
    }

    /** Applies the editor font, overriding the stylesheet defaults. */
    public void setFont(String family, int size) {
        if (fontApplied && java.util.Objects.equals(family, fontFamily) && size == fontSize) {
            return; // unchanged: skip the area-wide restyle + the ruler re-measure (a forced VirtualFlow layout)
        }
        fontApplied = true;
        this.fontFamily = family;
        this.fontSize = size;
        String style = "-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;";
        area.setStyle(style);
        if (area2 != null) {
            area2.setStyle(style);
        }
        whitespace.setFont(family, size);
        inlineValues.setFont(family, size);
        stickyScroll.setFont(family, size);
        if (gitLines.blame() != null) {
            // The blame annotation column width is font-relative — recompute + rebuild so it stays aligned.
            blameColumnWidth = measureBlameColumnWidth(gitLines.blame());
            refreshGutter();
        }
        markRulerInputsDirty(); // the glyph advance changed
    }

    /** Show/hide the column-80 ruler overlay. */
    public void setColumnRulerVisible(boolean visible) {
        if (visible == rulerVisible) {
            return; // unchanged: measuring the ruler forces a VirtualFlow layout, so don't re-do it for nothing
        }
        this.rulerVisible = visible;
        if (visible) {
            markRulerInputsDirty();
        } else {
            columnRuler.place(null, 0);
        }
    }

    /** The document's fixed-size undo history (bounded, so it can't grow without limit), shared by both views. */
    private UndoManager<?> boundedUndoManager() {
        // A followed log's new lines are not edits: they stay out of the history (see LogView#adjusting).
        return CompletionUndoFactory.forDocument(
                area, () -> focusedArea, UNDO_HISTORY, UndoMerge.PAUSE, logView::adjusting);
    }

    /** Ends the current undo group at a word/line boundary (see {@link UndoMerge}); no-op for huge files. */
    private void breakUndoGroupIfBoundary(String inserted, String removed) {
        if (!largeFile && UndoMerge.breakAfter(inserted, removed)) {
            area.getUndoManager().preventMerge();
        }
    }

    // --- Undo History (the Undo History tool window) ------------------------------------------------

    /** Above this document size the Undo History is disabled (full-text snapshots would cost too much). */
    private static final int UNDO_HISTORY_MAX_BYTES = 1_000_000;

    private final UndoHistory undoHistory = new UndoHistory();
    private Runnable onUndoHistoryChanged;

    public UndoHistory getUndoHistory() {
        return undoHistory;
    }

    /** Notified after a checkpoint is recorded, so the Undo History panel can refresh (active buffer only). */
    public void setOnUndoHistoryChanged(Runnable r) {
        this.onUndoHistoryChanged = r;
    }

    /** Snapshots the current document state into the history (no-op for huge/oversized files). */
    public void captureUndoCheckpoint() {
        // The capture rides a debounced (UndoMerge.PAUSE) subscription that is still live when the tab
        // closes, so an edit made just before closing would otherwise snapshot a whole document INTO a
        // buffer that dispose() has already emptied — pointless work that also undoes the clear. Same
        // reason applyHighlighting() checks this flag.
        if (disposed || largeFile || area.getLength() > UNDO_HISTORY_MAX_BYTES) {
            return;
        }
        if (undoHistory.add(documentTextSnapshot(), area.getCaretPosition(), System.currentTimeMillis())
                && onUndoHistoryChanged != null) {
            onUndoHistoryChanged.run();
        }
    }

    /** Restores a checkpoint's document text + caret (a single undoable edit). */
    public void restoreUndoCheckpoint(UndoHistory.Checkpoint c) {
        if (c == null || !isEditable()) {
            return;
        }
        replaceVisibleText(area, c.text());
        area.moveTo(UndoHistory.clamp(c.caret(), area.getLength()));
        area.requestFocus();
    }

    /** Toggle the highlight on the line containing the caret. */
    public void setLineHighlightOn(boolean on) {
        area.setLineHighlighterOn(on);
        if (area2 != null) {
            area2.setLineHighlighterOn(on);
        }
    }

    /** Sets the current-line highlight color (varies per editor theme; not stylable via CSS). */
    public void setLineHighlightColor(Color color) {
        this.lineHighlightColor = color;
        area.setLineHighlighterFill(color);
        if (area2 != null) {
            area2.setLineHighlighterFill(color);
        }
    }

    /** Colors the collapsed-region hover preview to match the editor theme. */
    public void setFoldPreviewColors(Color background, Color foreground) {
        folds.setPreviewColors(background, foreground);
    }

    /** Forces the minimap(s) to re-render (after layout/theme settle; the first render may run early). */
    public void refreshMinimap() {
        minimap.refresh();
    }

    /**
     * Marks whether this buffer is the active (visible) tab. A background tab releases per-buffer
     * GPU-backed caches — currently the minimap snapshot — so retained VRAM doesn't scale with the
     * number of open files; they regenerate when the tab is shown again. Called by the controller on
     * tab selection and when a tab is added in the background.
     */
    public void setRenderingActive(boolean active) {
        boolean wasInactive = !renderingActive;
        renderingActive = active;
        if (active && wasInactive) {
            scheduleRulerMeasure(); // catch up on measures skipped while the tab was hidden
        }
        minimap.setRenderingActive(active);
        // A full-viewport overlay keeps a Canvas + RTTexture alive for a tab the user cannot see. The
        // stripes are narrow (small textures); every overlay that can hold a viewport-sized one lets go.
        for (Node child : root.getChildren()) {
            if (child instanceof TabSurface surface) {
                surface.setRenderingActive(active);
            }
        }
    }

    /**
     * Nudges the just-revealed editor area to repaint after a {@code TabPane} tab switch. Flowless's
     * {@code VirtualFlow} lays out synchronously while the tab content is being swapped in — before its
     * viewport bounds are established — so it can present <em>blank</em> until the next layout pulse (which,
     * before this, only a second click into the area supplied). A deferred {@code requestLayout} runs on the
     * following pulse, once bounds are set, forcing a re-measure + repaint of the visible paragraphs. It does
     * <em>not</em> move the scroll position or steal focus, so a Find-in-Files preview that keeps focus in its
     * results tree is unaffected. Called by the controller on tab selection.
     */
    public void onTabShown() {
        javafx.application.Platform.runLater(() -> {
            area.requestLayout();
            if (area2 != null) {
                area2.requestLayout();
            }
        });
    }

    /** Sets the minimap's block and viewport-overlay colors (the minimap is canvas-drawn, not CSS). */
    public void setMinimapColors(Color text, Color viewport) {
        this.minimapText = text;
        this.minimapViewport = viewport;
        minimap.setColors(text, viewport);
    }

    /** Show/hide the line-number gutter. The fold-chevron column is always present. */
    public void setLineNumbersVisible(boolean visible) {
        if (visible == lineNumbersVisible) {
            return; // unchanged: refreshGutter() rebuilds every visible row's graphic, so don't re-do it
        }
        this.lineNumbersVisible = visible;
        refreshGutter();
    }

    /** Show/hide the entire gutter (Simple UI mode removes it completely). */
    public void setGutterVisible(boolean visible) {
        if (visible == gutterVisible) {
            return; // unchanged: see setLineNumbersVisible
        }
        this.gutterVisible = visible;
        refreshGutter();
    }

    /** Rebuilds the gutter graphic factory (line numbers + fold chevrons + markers) from current state, or
     *  removes the gutter entirely (null factory) when {@link #gutterVisible} is off. */
    public void refreshGutter() {
        markRulerInputsDirty(); // the gutter's width is where column 0 starts
        noteOverlay.refresh();
        area.setParagraphGraphicFactory(gutterVisible ? folds.gutterFactory(lineNumbersVisible) : null);
        applyNoGutterStyle(area);
        if (area2 != null) { // the same gutter: fold chevrons, bookmark, breakpoint and run markers
            area2.setParagraphGraphicFactory(area.getParagraphGraphicFactory());
            applyNoGutterStyle(area2);
        }
    }

    /** With no gutter (Simple UI mode; a second view without line numbers) the text would sit flush against
     *  the editor's left edge; the {@code .no-gutter} class adds a small left padding so it doesn't. The
     *  gutter itself supplies that inset when present, so the class is removed then. */
    private void applyNoGutterStyle(CodeArea a) {
        a.getStyleClass().remove("no-gutter");
        if (a.getParagraphGraphicFactory() == null) {
            a.getStyleClass().add("no-gutter");
        }
    }

    public FoldManager getFoldManager() {
        return folds;
    }

    /** Sets the Git change bars (0-based on-disk line → CSS class); {@code null} = not tracked, no slot.
     *  Toggling tracking rebuilds the gutter factory; otherwise only the changed lines repaint. Off in
     *  large-file mode (the gutter is minimal there). */
    public void setChangeBars(java.util.Map<Integer, String> lineClasses) {
        setChangeBars(lineClasses, null);
    }

    /** As {@link #setChangeBars(java.util.Map)} plus a per-line hunk-text map for the change-bar tooltip. */
    public void setChangeBars(java.util.Map<Integer, String> lineClasses, java.util.Map<Integer, String> hunkText) {
        // Never tracked in large/huge-file mode. A null answer = the reserved slot appeared or disappeared.
        java.util.Set<Integer> repaint = gitLines.setBars(largeFile ? null : lineClasses, hunkText);
        if (repaint == null) {
            refreshGutter(); // rebuild the factory
        } else {
            repaint.forEach(this::refreshGutterLine);
        }
    }

    /** Whether this buffer currently has Git change tracking on (a reserved change-bar slot). */
    public boolean hasChangeBars() {
        return gitLines.barsTracked();
    }

    /** The Git changes behind the bars: hunks, where they sit under unsaved edits, bar clicks. */
    public GitGutterLines gitGutter() {
        return gitLines;
    }

    public BookmarkManager getBookmarkManager() {
        return bookmarks;
    }

    /** Sets the bookmark add/remove handler ({@code (buffer, line)}) used by the right-click menu item;
     *  the controller adds, or confirms a removal. */
    public void setBookmarkToggleRequest(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        if (handler != null) {
            this.bookmarkToggleRequest = handler;
        }
    }

    /** Callback fired after any bookmark change (for persistence + the global Bookmarks panel). */
    public void setOnBookmarksChanged(Runnable callback) {
        bookmarks.setOnChanged(callback);
    }

    /** Rebuilds a single line's gutter graphic (cheap, viewport-safe — same primitive folds use). */
    public void refreshGutterLine(int line) {
        if (line >= 0 && line < area.getParagraphs().size()) {
            area.recreateParagraphGraphic(line);
            if (area2 != null && split != Split.NONE) {
                area2.recreateParagraphGraphic(line); // the split's second view shows the same markers
            }
        }
    }

    /** Toggles the bookmark on {@code line} and refreshes just that line's gutter marker. */
    public boolean toggleBookmark(int line) {
        boolean on = bookmarks.toggle(line);
        refreshGutterLine(line);
        return on;
    }

    /** Removes the bookmark on {@code line} (if any) and refreshes that line's gutter. */
    public void removeBookmark(int line) {
        bookmarks.remove(line);
        refreshGutterLine(line);
    }

    /**
     * Replaces this buffer's bookmarks from persisted state and repaints the gutter. Returns
     * {@code true} if a bookmark was re-anchored to its content (the file changed outside the editor),
     * so the caller can persist the corrected indices.
     */
    public boolean applyBookmarks(List<com.editora.config.Bookmark> saved) {
        boolean reanchored = bookmarks.restore(saved);
        refreshGutter();
        return reanchored;
    }

    // ---- Breakpoints (debugging) ----

    public BreakpointManager getBreakpointManager() {
        return breakpoints;
    }

    /** Enables/disables the leftmost breakpoint gutter strip (rebuilds the gutter when it changes). */
    public void setBreakpointsEnabled(boolean enabled) {
        if (enabled != debugEnabled) {
            debugEnabled = enabled;
            refreshGutter(); // the breakpoint slot appeared/disappeared on every row
        }
    }

    public boolean isBreakpointsEnabled() {
        return debugEnabled;
    }

    /** Sets the breakpoint-strip click handler ({@code (buffer, line)}) — the controller persists + re-sends. */
    public void setGutterBreakpointClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        if (handler != null) {
            this.gutterBreakpointClick = handler;
        }
    }

    /** Callback fired after any breakpoint change (persist + re-send to a live DAP session). */
    public void setOnBreakpointsChanged(Runnable callback) {
        breakpoints.setOnChanged(callback);
    }

    /** Toggles the breakpoint on {@code line} and refreshes just that line's gutter strip. */
    public boolean toggleBreakpoint(int line) {
        boolean on = breakpoints.toggle(line);
        refreshGutterLine(line);
        return on;
    }

    /** Replaces this buffer's breakpoints from persisted state; returns whether any was re-anchored. */
    public boolean applyBreakpoints(List<com.editora.config.Breakpoint> saved) {
        boolean reanchored = breakpoints.restore(saved);
        refreshGutter();
        return reanchored;
    }

    /** The debugger's execution-point highlight (see {@link ExecutionLine}). */
    private final ExecutionLine executionLine = new ExecutionLine();

    /**
     * Marks {@code line} as the current execution point (a distinct paragraph background) and scrolls/moves
     * the caret there so the built-in current-line highlight reinforces it. Clears any previous mark.
     */
    public void setExecutionLine(int line) {
        if (executionLine.set(area, folds, line)) {
            jumpToLine(line);
        }
    }

    /** Removes the execution-point highlight (if any). */
    public void clearExecutionLine() {
        executionLine.clear(area);
    }

    // ---- Personal Notes ----

    public NoteManager getNoteManager() {
        return notes;
    }

    /** Sets the blame-annotation click handler ({@code (buffer, line)}) — the controller shows that
     *  line's commit. */
    public void setGutterBlameClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        if (handler != null) {
            this.gutterBlameClick = handler;
        }
    }

    /** Sets the "Add Personal Note" context-menu handler (the controller prompts for the body + creates). */
    public void setAddNoteHandler(java.util.function.Consumer<EditorBuffer> handler) {
        if (handler != null) {
            this.addNoteHandler = handler;
        }
    }

    /** Sets the "Run File" context-menu handler (controller runs the compact source file); null disables it. */
    public void setRunHandler(Runnable handler) {
        this.runHandler = handler;
    }

    /** Callback fired after any note change (for persistence + the Notes panel). */
    public void setOnNotesChanged(Runnable callback) {
        notes.setOnChanged(callback);
    }

    /** Replaces this buffer's notes from persisted state and repaints the gutter. Returns true if any note
     *  was re-anchored or (un)orphaned, so the caller can persist the self-healed state. */
    public boolean applyNotes(List<com.editora.config.PersonalNote> saved) {
        boolean moved = notes.restore(saved);
        refreshGutter();
        return moved;
    }

    /**
     * Captures a note draft (scope + anchor) from the current selection/caret: a multi-line selection is a
     * {@link com.editora.config.NoteScope#RANGE}, a single-line selection a {@code WORD}, and no selection a
     * {@code LINE} anchored to the caret's line. Used by the controller's "Add Personal Note" flow.
     */
    public NoteDraft captureNoteDraft() {
        org.fxmisc.richtext.model.TwoDimensional.Bias fwd = org.fxmisc.richtext.model.TwoDimensional.Bias.Forward;
        CodeArea a = focusedArea; // the view the user is in
        String doc = a.getText();
        var sel = a.getSelection();
        if (sel.getLength() > 0) {
            int start = sel.getStart();
            int end = sel.getEnd();
            var sp = a.offsetToPosition(start, fwd);
            var ep = a.offsetToPosition(end, fwd);
            com.editora.config.NoteScope scope = sp.getMajor() == ep.getMajor()
                    ? com.editora.config.NoteScope.WORD
                    : com.editora.config.NoteScope.RANGE;
            String prefix = doc.substring(Math.max(0, start - CONTEXT_CHARS), start);
            String suffix = doc.substring(end, Math.min(doc.length(), end + CONTEXT_CHARS));
            var anchor = new com.editora.config.TextAnchor(
                    sp.getMajor(), sp.getMinor(), ep.getMajor(), ep.getMinor(), a.getSelectedText(), prefix, suffix);
            return new NoteDraft(scope, anchor);
        }
        return captureLineNoteDraft(a.getCurrentParagraph());
    }

    /** Captures a LINE note anchor for a specific line, independent of the current selection/caret. */
    public NoteDraft captureLineNoteDraft(int requestedLine) {
        int line = Math.max(0, Math.min(requestedLine, area.getParagraphs().size() - 1));
        String doc = area.getText();
        String lineText = area.getParagraph(line).getText();
        int lineLen = lineText.length();
        // Capture surrounding context for a LINE note too (like WORD/RANGE): the lines a LINE note lands on
        // (`    }`, `});`, `end`, a repeated import) are the most-duplicated in a file, so with empty context
        // relocation collapsed to pure proximity and the note re-anchored to the nearest identical line (#453).
        int lineStart = area.getAbsolutePosition(line, 0);
        int lineEnd = lineStart + lineLen;
        String linePrefix = doc.substring(Math.max(0, lineStart - CONTEXT_CHARS), lineStart);
        String lineSuffix = doc.substring(lineEnd, Math.min(doc.length(), lineEnd + CONTEXT_CHARS));
        var anchor = new com.editora.config.TextAnchor(line, 0, line, lineLen, lineText, linePrefix, lineSuffix);
        return new NoteDraft(com.editora.config.NoteScope.LINE, anchor);
    }

    /** Content-hash file identity of this buffer's file (for notes); {@code null} when the buffer is unsaved. */
    public com.editora.config.FileIdentity fileIdentity() {
        return path == null ? null : com.editora.config.FileIdentity.of(path);
    }

    /** Enables/disables the Personal Notes feature for this buffer (gates the "Add Note" menu items). */
    public void setNotesEnabled(boolean on) {
        this.notesEnabled = on;
    }

    /** Shows/hides the note gutter markers + highlight (the {@code showNoteIndicators} setting). */
    public void setNoteIndicatorsVisible(boolean on) {
        if (noteIndicators == on) {
            return;
        }
        noteIndicators = on;
        noteOverlay.setActive(on);
        if (!on) {
            hideNoteTip();
        }
        refreshGutter();
    }

    /** The popups that follow the pointer over {@code area} (either pane), each shown at the pointer. */
    private void installPointerHovers(CodeArea a) {
        installLintHover(a); // reads lintOverlay only while mermaid lint is active (lazily attached)
        installMarkdownLintHover(a);
        installLspHover(a); // reads lspOverlay only while LSP is active (lazily attached)
        installDebugHover(a);
        installNoteHover(a);
    }

    /** Hover popup over a note's span shows its body (updated only when the hovered note changes). */
    private void installNoteHover(CodeArea a) {
        a.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (!noteIndicators) {
                hideNoteTip();
                return;
            }
            com.editora.config.PersonalNote n;
            try {
                // MOUSE_MOVED bubbles from RichTextFX's paragraph/text nodes. MouseEvent#getX/Y are
                // relative to that original event source, not reliably to the CodeArea on which this
                // filter is installed, so feeding them directly to CodeArea.hit() misses the note (and
                // changes as the pointer crosses child nodes). Screen coordinates are stable across the
                // dispatch chain; translate those back into the area's coordinate space first.
                javafx.geometry.Point2D local = a.screenToLocal(e.getScreenX(), e.getScreenY());
                n = notes.noteAt(a.hit(local.getX(), local.getY()).getInsertionIndex());
            } catch (RuntimeException ex) {
                n = null;
            }
            if (n == null || n.body().isBlank()) {
                hideNoteTip();
            } else if (!n.id().equals(hoverNoteId)) {
                hoverNoteId = n.id();
                noteTip.setText(null);
                noteTip.setGraphic(renderNoteTooltip(n.body()));
                if (noteTip.isShowing()) {
                    noteTip.hide();
                }
                noteTip.show(a, e.getScreenX() + 12, e.getScreenY() + 16);
            }
        });
        a.addEventFilter(MouseEvent.MOUSE_EXITED, e -> hideNoteTip());
        // Clicking the inline note marker (the ~7px amber triangle at a note's start) opens the editor —
        // restoring the click-to-edit the gutter glyph used to give. A click elsewhere is untouched.
        a.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (!noteIndicators || e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 1) {
                return;
            }
            for (int[] span : notes.activeSpans()) {
                Bounds scr;
                try {
                    scr = NoteHighlightOverlay.markerBoundsOnScreen(a, span[0]);
                } catch (RuntimeException ex) {
                    scr = null;
                }
                if (scr == null) {
                    continue;
                }
                if (e.getScreenX() >= scr.getMinX()
                        && e.getScreenX() <= scr.getMinX() + 11
                        && e.getScreenY() >= scr.getMinY()
                        && e.getScreenY() <= scr.getMinY() + 11) {
                    com.editora.config.PersonalNote n = notes.noteAt(span[0]);
                    if (n != null) {
                        noteMarkerClick.accept(this, n);
                        e.consume();
                    }
                    return;
                }
            }
        });
    }

    /** Sets the handler invoked when the user clicks a note's inline start marker (controller edits it). */
    public void setNoteMarkerClick(
            java.util.function.BiConsumer<EditorBuffer, com.editora.config.PersonalNote> handler) {
        if (handler != null) {
            this.noteMarkerClick = handler;
        }
    }

    /** Moves the caret to the next TODO/highlight match after the caret (wrapping); false if none exist. */
    public boolean jumpToNextTodo() {
        return jumpTodoMark(true);
    }

    /** Moves the caret to the previous TODO/highlight match before the caret (wrapping); false if none. */
    public boolean jumpToPreviousTodo() {
        return jumpTodoMark(false);
    }

    private boolean jumpTodoMark(boolean forward) {
        // Same gates as refreshTodoMarks: without them a huge file (where the highlight is deliberately off
        // and no marks are drawn) still ran the full multi-pattern scan over the whole document on the FX
        // thread, and jumped to a match the user cannot see.
        if (todoMatcher == null || !todoEnabled || largeFile) {
            return false;
        }
        java.util.List<TodoMark> marks = todoMatcher.match(area.getText());
        if (marks.isEmpty()) {
            return false;
        }
        int caret = area.getCaretPosition();
        TodoMark target = null;
        if (forward) {
            for (TodoMark m : marks) {
                if (m.start() > caret) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                target = marks.get(0);
            }
        } else {
            for (int i = marks.size() - 1; i >= 0; i--) {
                if (marks.get(i).start() < caret) {
                    target = marks.get(i);
                    break;
                }
            }
            if (target == null) {
                target = marks.get(marks.size() - 1);
            }
        }
        area.moveTo(Math.min(target.start(), area.getLength()));
        area.requestFollowCaret();
        return true;
    }

    /**
     * Renders a note body as the hover tooltip's graphic: the body is parsed as Markdown
     * ({@link MarkdownRenderer}) so formatting shows in the popup, the editor's own font family/size is
     * applied as the base font, and the app + syntax stylesheets are attached to the node (the tooltip
     * lives in its own popup scene) so the {@code .markdown-preview} rules resolve. Falls back to a plain
     * wrapped label if rendering fails.
     */
    private javafx.scene.Node renderNoteTooltip(String body) {
        javafx.scene.Node node;
        try {
            node = MarkdownRenderer.renderDocument(
                    MarkdownRenderer.parseToDocument(body), path != null ? path.getParent() : null);
        } catch (RuntimeException ex) {
            javafx.scene.control.Label fallback = new javafx.scene.control.Label(body);
            fallback.setWrapText(true);
            node = fallback;
        }
        node.getStyleClass().add("personal-note-tooltip-content");
        node.setStyle("-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;");
        if (node instanceof javafx.scene.Parent parent) {
            addStylesheet(parent, "/com/editora/styles/app.css");
            addStylesheet(parent, "/com/editora/styles/syntax.css");
        }
        if (node instanceof javafx.scene.layout.Region region) {
            // Pin the node to a definite size so the tooltip hugs the rendered content. A TextFlow inside a
            // tooltip otherwise computes its height at a near-zero width (one char per line → a tall, empty
            // popup). Measure in a throwaway Scene so the inline font + stylesheets actually apply (a
            // detached node measures at the default font and mis-sizes): prefWidth(-1) is the natural
            // one-line width (capped so long notes wrap), then the height at that width — so the box matches
            // the rendered text (measuring the raw Markdown source would over-size it by the markup chars).
            javafx.scene.Scene measureScene = new javafx.scene.Scene(region);
            region.applyCss();
            region.layout();
            double width = Math.min(480, Math.ceil(region.prefWidth(-1)));
            region.setPrefWidth(width);
            region.setMaxWidth(width);
            double height = Math.ceil(region.prefHeight(width)) + 1;
            measureScene.setRoot(new javafx.scene.Group()); // release the node to reuse as the tooltip graphic
            region.setMinHeight(height);
            region.setPrefHeight(height);
            region.setMaxHeight(height);
        }
        return node;
    }

    private void addStylesheet(javafx.scene.Parent parent, String resource) {
        java.net.URL url = getClass().getResource(resource);
        if (url != null) {
            parent.getStylesheets().add(url.toExternalForm());
        }
    }

    private void hideNoteTip() {
        hoverNoteId = null;
        if (noteTip.isShowing()) {
            noteTip.hide();
        }
    }

    /** Removes all bookmarks in this buffer and repaints the gutter. */
    public void clearBookmarks() {
        bookmarks.clear();
        refreshGutter();
    }

    /** Collapse every foldable region in the document. */
    public void foldAll() {
        folds.foldAll();
    }

    /** Expand every collapsed region in the document. */
    public void unfoldAll() {
        folds.unfoldAll();
    }

    /** Show/hide the minimap overview (on every split pane); reclaims its width for the editor when hidden. */
    /**
     * Whether the minimap should actually show: the user's setting, but forced off for large/heavy files
     * (too costly) and in full {@link MarkdownViewMode#PREVIEW} (no editor surface to map). It stays on in
     * {@link MarkdownViewMode#SPLIT} — the editor is still visible there. Pure, so it's unit-tested.
     */
    static boolean minimapEffective(boolean visible, boolean largeFile, boolean heavyFile, MarkdownViewMode mode) {
        return visible && !largeFile && !heavyFile && mode != MarkdownViewMode.PREVIEW;
    }

    /** A load found a line too long to wrap cheaply: the word-wrap preference is held off for this buffer. */
    private boolean wrapSuppressed;

    public boolean isWrapSuppressed() {
        return wrapSuppressed;
    }

    public void setWrapSuppressed(boolean wrapSuppressed) {
        this.wrapSuppressed = wrapSuppressed;
    }

    /** Toggles soft word wrap on the editor surface (and the split view); the 80-column ruler stays visible. */
    public void setWordWrap(boolean wrap) {
        boolean changed = wrap != area.isWrapText();
        if (changed) {
            markRulerInputsDirty(); // wrapping re-flows the text and removes the horizontal scroll
        }
        area.setWrapText(wrap); // the split's second view is bound to this
        if (!changed) {
            return;
        }
        cancelWrapRemeasure();
        if (wrap) {
            startWrapRemeasure();
        }
    }

    /** Per-pulse time budget for {@link #startWrapRemeasure}, so a big document never blocks a frame. */
    private static final long WRAP_REMEASURE_BUDGET_NANOS = 6_000_000L;

    private javafx.animation.AnimationTimer wrapRemeasure;

    /**
     * Makes a just-enabled word wrap take effect. Flowless caches the minimum width of every paragraph it has
     * ever laid out and lays all cells out at the widest cached value, but on a change it only drops the
     * entries of cells that are currently realized. An unwrapped long line scrolled past earlier therefore
     * keeps the whole view at its old width: nothing wraps and the horizontal scrollbar stays. There is no
     * public way to clear that cache, so this realizes every non-empty paragraph once (in budgeted slices,
     * one per pulse) so the next layout pass re-measures it, after which Flowless drops the cell again.
     */
    private void startWrapRemeasure() {
        List<CodeArea> areas = area2 == null ? List.of(area) : List.of(area, area2);
        wrapRemeasure = new javafx.animation.AnimationTimer() {
            private int areaIndex;
            private int next;

            @Override
            public void handle(long now) {
                long deadline = System.nanoTime() + WRAP_REMEASURE_BUDGET_NANOS;
                while (areaIndex < areas.size()) {
                    CodeArea target = areas.get(areaIndex);
                    if (target.getScene() == null) {
                        areaIndex++; // not showing: no layout pass would release the realized cells
                        next = 0;
                        continue;
                    }
                    int size = target.getParagraphs().size();
                    while (next < size && System.nanoTime() < deadline) {
                        if (target.getParagraphLength(next) > 0) {
                            target.getParagraphLinesCount(next); // realizes the cell (an empty line is never wide)
                        }
                        next++;
                    }
                    target.requestLayout();
                    if (next < size) {
                        return; // continue after this pulse's layout has re-measured the slice
                    }
                    areaIndex++;
                    next = 0;
                }
                cancelWrapRemeasure();
            }
        };
        wrapRemeasure.start();
    }

    private void cancelWrapRemeasure() {
        if (wrapRemeasure != null) {
            wrapRemeasure.stop();
            wrapRemeasure = null;
        }
    }

    public void setMinimapVisible(boolean visible) {
        this.minimapVisible = visible;
        boolean effective = minimapEffective(visible, largeFile, heavyFile, markdownViewMode);
        applyMinimap(scrollPane, minimap, effective);
        for (Node child : root.getChildren()) {
            if (child.getProperties().containsKey(OVER_TEXT)) {
                AnchorPane.setRightAnchor(child, AnchorPane.getRightAnchor(scrollPane));
            }
        }
        if (minimap2 != null) {
            applyMinimap(scrollPane2, minimap2, effective);
        }
        updateDiagnosticStripe(); // show the scrollbar stripe when the minimap no longer carries marks
        positionCornerControls(); // keep floating editor controls clear of the minimap
    }

    /** Show/hide the "hidden characters" markers (spaces, tabs, line ends). */
    public void setWhitespaceVisible(boolean visible) {
        whitespace.setActive(visible);
    }

    /**
     * Coalesces a ruler re-measurement onto the next pulse. Measuring queries character bounds, which
     * must not happen synchronously inside a layout/viewport event (it re-enters layout and blanks the
     * editor), so we always defer it via {@link Platform#runLater}.
     */
    /**
     * Records that an input to the ruler's position changed other than the viewport width or the horizontal
     * scroll (the gutter, the font, wrap, the ruler column), and schedules the re-measure. The flag survives
     * until a measure actually runs, so a change made before the area has been laid out is not lost.
     */
    private void markRulerInputsDirty() {
        rulerInputsDirty = true;
        scheduleRulerMeasure();
    }

    private void scheduleRulerMeasure() {
        // Measuring queries the viewport (a forced VirtualFlow layout) + character bounds. A background tab
        // is not on screen, so doing that for it is pure cost — and a settings apply dirties every open
        // buffer's viewport at once, which is what made a Simple-mode toggle scale with the number of tabs.
        // setRenderingActive re-schedules when the tab is shown again, so nothing is left stale.
        if (!rulerVisible || rulerMeasurePending || !renderingActive) {
            return;
        }
        rulerMeasurePending = true;
        Platform.runLater(() -> {
            rulerMeasurePending = false;
            measureAndPlaceRuler();
        });
    }

    /**
     * Positions the ruler at the 80-column boundary, drawn whether or not any text reaches column 80.
     * The boundary is column 0's on-screen x plus 80 advances of the editor font (see {@link #columnRulerX}),
     * so it is exact regardless of which glyphs are present. The ruler is hidden when column 80 falls outside
     * the visible text width (e.g. the window is too narrow, or the text is scrolled past it).
     */
    /**
     * How many times the ruler has been measured, app-wide. A test seam, not state the editor reads: how
     * often this runs is a measured property (each measure is two forced {@code VirtualFlow} layouts plus
     * character-bounds queries), and without a counter a regression to the old measure-on-every-viewport-event
     * subscription would be invisible — the ruler would still land in the right place, just far more often.
     * See {@code ColumnRulerFxTest}.
     */
    static final java.util.concurrent.atomic.AtomicInteger RULER_MEASURES_FOR_TEST =
            new java.util.concurrent.atomic.AtomicInteger();

    private void measureAndPlaceRuler() {
        RULER_MEASURES_FOR_TEST.incrementAndGet();
        lastRulerViewportWidth = area.getWidth();
        if (!rulerVisible
                || rulerColumnOverride != null
                        && rulerColumnOverride == com.editora.editorconfig.EditorConfigProperties.OFF) {
            columnRuler.place(null, 0);
            rulerInputsDirty = false; // nothing to place; a later show() re-marks via setColumnRulerVisible
            return;
        }
        boolean inputsChanged = rulerInputsDirty;
        Double x = columnRulerX();
        // Clear the pending flag only on a measure that actually produced a position. Before the area's
        // first layout the geometry is unmeasurable and columnRulerX returns null; clearing the flag there
        // would leave the gate waiting for a viewport WIDTH change that may never come, and the ruler would
        // stay hidden for the life of the buffer.
        if (x != null) {
            rulerInputsDirty = false;
            if (inputsChanged) {
                confirmRulerAfterLayout();
            }
        }
        columnRuler.place(x, scrollPane.getWidth());
    }

    /**
     * Measures once more two frames after a measure that followed a changed input (gutter, font, wrap). That
     * first measure runs from {@code runLater}, which can land before the pulse that lays the new gutter out;
     * it then reads column 0 at its old x and nothing else would ever correct it. One-shot, and the second
     * measure does not re-arm it. A changed input while one is waiting starts the two frames again: the
     * confirm that was about to fire would otherwise also run before the new layout, and be the last word.
     */
    private void confirmRulerAfterLayout() {
        rulerConfirmFrames = 0;
        if (rulerConfirm != null) {
            return;
        }
        rulerConfirm = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                if (++rulerConfirmFrames < 2) {
                    return;
                }
                stop();
                rulerConfirm = null;
                if (rulerVisible && renderingActive) {
                    measureAndPlaceRuler();
                }
            }
        };
        rulerConfirm.start();
    }

    private javafx.animation.AnimationTimer rulerConfirm;
    private int rulerConfirmFrames;

    /** Whether a ruler measure is still to come (the deferred one or its confirmation); for tests to wait on. */
    boolean rulerMeasurePending() {
        return rulerMeasurePending || rulerConfirm != null;
    }

    /**
     * Root-local x of column 80: where column 0 starts on screen (the left edge of the first character of any
     * visible line — a property of the gutter and the horizontal scroll, not of the text) plus 80 columns of
     * the editor font's own advance (see {@link ColumnAdvance}; never derived from the visible text, which a
     * short line, a tab or a wide glyph skewed). Returns {@code null} when no visible line has a character.
     */
    private Double columnRulerX() {
        int col = rulerColumnOverride != null && rulerColumnOverride > 0 ? rulerColumnOverride : 80;
        try {
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(area.getParagraphs().size() - 1, area.lastVisibleParToAllParIndex());
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p) || area.getParagraphLength(p) == 0) {
                    continue; // collapsed or empty: no character to take column 0 from
                }
                Bounds start = caretBounds(p, 0);
                if (start != null) {
                    return start.getMinX() + col * columnAdvance.of(fontFamily, fontSize);
                }
            }
        } catch (RuntimeException ignored) {
            // Viewport mid-layout; a later event will re-measure.
        }
        return null;
    }

    /**
     * Root-local bounds of the caret at {@code column} in paragraph {@code p}, or {@code null}.
     *
     * <p><b>Never query the zero-width {@code getCharacterBoundsOnScreen(abs, abs)} form here.</b> For an
     * empty range {@code GenericStyledArea} allocates a throwaway {@link org.fxmisc.richtext.CaretNode} to
     * measure with, and a {@code CaretNode} starts a 500 ms {@code restartableTicks} blink timer that
     * nothing ever stops — so every call permanently registers a running JavaFX {@code Timeline} as a pulse
     * receiver. This method runs 2–3 times per ruler measurement and the ruler re-measures on
     * {@code viewportDirtyEvents}, i.e. on every edit: measured at <b>+2 leaked timers per keystroke</b>,
     * with typing latency degrading 5.6 ms → 28 ms over 2000 keystrokes and never recovering (the leak
     * outlives the window). Measuring a <em>one-character</em> range takes a different path in RichTextFX
     * and allocates nothing; at end-of-paragraph there is no character to the right, so we measure the last
     * character and take its right edge, which is the same x the caret would sit at.
     */
    private Bounds caretBounds(int p, int column) {
        int abs = area.getAbsolutePosition(p, column);
        int len = area.getParagraphLength(p);
        Bounds screen;
        if (column < len) {
            screen = area.getCharacterBoundsOnScreen(abs, abs + 1).orElse(null);
        } else if (len > 0) {
            Bounds last = area.getCharacterBoundsOnScreen(abs - 1, abs).orElse(null);
            screen = last == null
                    ? null
                    : new javafx.geometry.BoundingBox(last.getMaxX(), last.getMinY(), 0, last.getHeight());
        } else {
            return null; // empty paragraph: nothing to measure an advance from
        }
        return screen == null ? null : root.screenToLocal(screen);
    }

    public Path getPath() {
        return path;
    }

    /** Records file metadata as last loaded/saved, for external-change detection. */
    public void setDiskSnapshot(long modifiedMillis, long size) {
        setDiskSnapshot(modifiedMillis, size, null);
    }

    /** Records metadata plus the exact-content identity used by save-time remote conflict checks. */
    public void setDiskSnapshot(long modifiedMillis, long size, String fingerprint) {
        this.diskModifiedMillis = modifiedMillis;
        this.diskSize = size;
        this.diskFingerprint = fingerprint;
    }

    /** Whether {@code modifiedMillis}/{@code size} differ from the last recorded on-disk snapshot. */
    public boolean diskChangedFrom(long modifiedMillis, long size) {
        return diskModifiedMillis >= 0 && (modifiedMillis != diskModifiedMillis || size != diskSize);
    }

    /** Immutable copy of the last loaded/saved disk identity, captured on the FX thread for background I/O. */
    public record DiskSnapshot(long modifiedMillis, long size, String fingerprint) {
        public boolean differsFrom(long currentModifiedMillis, long currentSize) {
            return modifiedMillis >= 0 && (modifiedMillis != currentModifiedMillis || size != currentSize);
        }

        /** Compares exact bytes when both snapshots have them, otherwise falls back to metadata. */
        public boolean differsFrom(long currentModifiedMillis, long currentSize, String currentFingerprint) {
            if (fingerprint != null && currentFingerprint != null) {
                return !fingerprint.equals(currentFingerprint);
            }
            return differsFrom(currentModifiedMillis, currentSize);
        }
    }

    public DiskSnapshot diskSnapshot() {
        return new DiskSnapshot(diskModifiedMillis, diskSize, diskFingerprint);
    }

    /** Associates this buffer with a file and selects the grammar and fold language from its extension. */
    public void setPath(Path path) {
        this.path = path;
        writableOnDisk =
                path == null || path.getFileSystem() != java.nio.file.FileSystems.getDefault() ? writableOnDisk : null;
        // The full path (not just the basename) so location-based rules resolve — e.g. ~/.ssh/config,
        // /etc/hosts, .git/config (see ConfigFileType). The registries reduce it to the basename for
        // ordinary extension lookups.
        String lookup = path == null ? null : path.toString();
        applyLanguageFor(lookup == null ? LanguageRegistry.plaintext() : LanguageRegistry.forFileName(lookup), lookup);
        recomputeRun(); // a Save-As to a runnable file type can show the gutter Run glyph
    }

    /** Applies language {@code name} with the grammar {@code lookup} (a file name or path, or null) maps to. */
    private void applyLanguageFor(String name, String lookup) {
        GrammarRegistry reg = GrammarRegistry.shared();
        if (lookup == null || !reg.hasGrammarFor(lookup)) {
            applyLanguage(name, null); // no bundled grammar for this type — nothing to load
        } else {
            IGrammar cached = reg.cachedForFileName(lookup);
            if (cached != null) {
                applyLanguage(name, cached); // already compiled this session — apply instantly (no flash)
            } else {
                applyLanguageDeferred(name, lookup); // first file of this type: compile off the FX thread
            }
        }
    }

    /**
     * Applies the language immediately (plain text, with fold/spell modes updated) but resolves its
     * not-yet-cached grammar <b>off the FX thread</b>, then re-highlights when it arrives — so opening the
     * first file of a given type during session restore doesn't block the UI on the Oniguruma grammar
     * compile. A {@link #languageGen} guard (bumped by every {@link #applyLanguage}) drops the result if a
     * later setPath / language override / dispose superseded this load. The brief unstyled flash matches
     * the existing deferred content-load behavior; cached files (every subsequent open) skip this path.
     */
    private void applyLanguageDeferred(String name, String fileName) {
        applyLanguage(name, null); // bumps languageGen; show plain text now
        long gen = languageGen;
        HIGHLIGHT_POOL.execute(() -> {
            IGrammar g = GrammarRegistry.shared().forFileName(fileName);
            Platform.runLater(() -> {
                if (gen != languageGen) {
                    return; // a newer language change superseded this load
                }
                this.grammar = g;
                invalidateHighlighting();
                applyHighlighting();
            });
        });
    }

    /**
     * Gives a still-unsaved buffer a suggested file name: it becomes the tab title and selects the
     * grammar/fold language by extension, while {@link #path} stays null so the first Save prompts for a
     * location (Save-As). No-op once the buffer has a real path.
     */
    public void setDisplayName(String name) {
        this.displayName = name == null || name.isBlank() ? null : name;
        if (path == null && displayName != null) {
            applyLanguageFor(LanguageRegistry.forFileName(displayName), displayName);
        }
    }

    /** The suggested name for an unsaved buffer, or null (used as the Save-As default). */
    public String getDisplayName() {
        return displayName;
    }

    /** The current language name (see {@link LanguageRegistry}); drives fold strategy and the status bar. */
    public String getLanguage() {
        return language;
    }

    /**
     * Overrides the language/grammar for this buffer regardless of its file extension (e.g. chosen
     * from the status bar). Pass {@link LanguageRegistry#plaintext()} to disable highlighting.
     */
    public void setLanguageOverride(String name) {
        String resolved = name == null ? LanguageRegistry.plaintext() : name;
        languageUserOverride = true; // the user's pick wins; don't re-detect a shebang over it
        IGrammar g = GrammarRegistry.shared().forLanguageName(resolved);
        applyLanguage(resolved, g);
    }

    /**
     * When the file's extension resolves to plain text, promotes it to the language named by a
     * first-line interpreter shebang (e.g. {@code #!/usr/bin/env python3} → Python; a
     * {@code java --source N} shebang → Java compact source). Resolves the language + grammar
     * through the normal {@code forFileName} path (via a synthetic {@code "shebang.<ext>"} name) so a
     * shebang file behaves exactly like a real file of that type. No-op for a real extension, a
     * user-overridden language, an empty buffer, or an unrecognized/absent shebang.
     */
    private void maybeApplyShebang() {
        if (languageUserOverride || !LanguageRegistry.plaintext().equals(language)) {
            return; // a real extension or the user's pick already decided the language
        }
        int len = area.getLength();
        if (len < 2) {
            return;
        }
        String head = area.getText(0, Math.min(len, 256));
        int nl = head.indexOf('\n');
        String firstLine = nl >= 0 ? head.substring(0, nl) : head;
        if (!firstLine.startsWith("#!")) {
            return;
        }
        Shebang.Result r = Shebang.parse(firstLine);
        if (r == null) {
            return;
        }
        shebangJavaSource = r.javaSource();
        String synthetic = "shebang." + r.extension();
        String lang = LanguageRegistry.forFileName(synthetic);
        if (lang.equals(language)) {
            return;
        }
        IGrammar cached = GrammarRegistry.shared().cachedForFileName(synthetic);
        if (cached != null) {
            applyLanguage(lang, cached); // grammar already compiled this session — apply instantly
        } else {
            applyLanguageDeferred(lang, synthetic); // first file of this type — compile off the FX thread
        }
    }

    /** The {@code --source N} version if this is a Java compact-source shebang file, else {@code null}. */
    public Integer getShebangJavaSource() {
        return shebangJavaSource;
    }

    /** Applies a language name + grammar: updates fold strategy and re-highlights. */
    private void applyLanguage(String name, IGrammar g) {
        languageGen++; // supersede any in-flight deferred grammar load (see applyLanguageDeferred)
        this.language = name;
        this.grammar = g;
        folds.setLanguage(language);
        spellOverlay.setProseMode(isProse()); // prose checks all words; code only comments/strings
        // Must be re-pushed here, not just from installOverlays(): that runs in the constructor, before the
        // language is known, so it always saw plaintext ⇒ markdown stayed false forever and ``` fenced code
        // blocks WERE spell-checked (sudo/cd/xzf squiggled inside a README's bash block).
        spellOverlay.setMarkdown(isMarkdown());
        invalidateHighlighting(); // grammar changed with no text edit — re-tokenize the whole document
        applyHighlighting();
    }

    /**
     * Chooses the line ending the next save writes, and marks the buffer unsaved when that changes it. The
     * document always holds bare {@code \n} (RichTextFX splits paragraphs on any terminator), so there is
     * nothing to rewrite in the editor: the choice is applied to the bytes on save.
     */
    public void convertLineEndings(boolean crlf) {
        String target = crlf ? LineEndings.CRLF : LineEndings.LF;
        if (!target.equals(lineEnding)) {
            lineEnding = target;
            dirty.set(differsFromSaved()); // converting back to the file's own ending is no change at all
        }
    }

    /** {@code "CRLF"} if {@code text} contains any Windows line ending, else {@code "LF"}. */
    public static String detectLineEnding(String text) {
        return text != null && text.contains("\r\n") ? "CRLF" : "LF";
    }

    /** The line ending a save writes: the EditorConfig override when set, else the file's own. */
    public String getLineEnding() {
        return eolOverride != null ? eolOverride : lineEnding;
    }

    /** Whether {@code .editorconfig} fixes the line ending, so a manual conversion cannot take effect. */
    public boolean isLineEndingForced() {
        return eolOverride != null;
    }

    /** Sets the visual tab width: how wide a tab is drawn in both panes, and the minimap's. */
    public void setTabSize(int tabSize) {
        this.tabSize = tabSize;
        TabStops.apply(viewHost, tabSize);
        minimap.setTabSize(tabSize);
    }

    public int getTabSize() {
        return tabSize;
    }

    // --- EditorConfig overrides ------------------------------------------------------------------

    /** Forces the indent unit (EditorConfig {@code indent_style}/{@code indent_size}); null = auto-detect. */
    public void setIndentOverride(Boolean insertSpaces, Integer size) {
        this.indentInsertSpacesOverride = insertSpaces;
        this.indentSizeOverride = size;
    }

    /** The EditorConfig line ending to write ({@code "LF"}/{@code "CRLF"}/{@code "CR"}); null = no override. */
    public void setEolOverride(String eol) {
        this.eolOverride = LineEndings.isLabel(eol) ? eol : null;
    }

    /** The ruler column (EditorConfig {@code max_line_length}); null = default, OFF = hide; re-measures. */
    public void setRulerColumn(Integer column) {
        if (java.util.Objects.equals(column, rulerColumnOverride)) {
            return; // unchanged: this runs for every buffer on every settings apply (applyEditorConfig)
        }
        this.rulerColumnOverride = column;
        markRulerInputsDirty(); // deferred, not a synchronous measure: character bounds must never be
        // queried inside a layout pass, and the caller may be mid-apply
    }

    public void setDetectedCharset(String charset) {
        setDetectedCharset(charset, false);
    }

    /** {@code assumed}: a lossless stand-in for a file its declared charset could not decode. */
    public void setDetectedCharset(String charset, boolean assumed) {
        this.detectedCharset = charset == null ? com.editora.editorconfig.EditorConfigCharset.UTF_8 : charset;
        this.charsetAssumed = assumed;
    }

    /** The charset the file has on disk: the one it was decoded with, or the one a save last wrote. */
    public String getDetectedCharset() {
        return detectedCharset;
    }

    public void setCharsetOverride(String charset) {
        this.charsetOverride = charset;
    }

    /** True when the text was decoded with a stand-in charset: it is the file's bytes, not its real text. */
    public boolean isCharsetAssumed() {
        return charsetAssumed;
    }

    /**
     * The charset to write: the EditorConfig override if set, else the charset detected on open. An assumed
     * charset wins over the override — re-encoding text that was decoded with a stand-in would rewrite bytes
     * the override never understood.
     */
    public String getEffectiveCharset() {
        return charsetOverride != null && !charsetAssumed ? charsetOverride : detectedCharset;
    }

    public void setEditorConfigProps(com.editora.editorconfig.EditorConfigProperties props) {
        this.editorConfigProps = props == null ? com.editora.editorconfig.EditorConfigProperties.EMPTY : props;
    }

    public com.editora.editorconfig.EditorConfigProperties getEditorConfigProps() {
        return editorConfigProps;
    }

    // --- Spell checking ---------------------------------------------------------------------------

    /** Whether this buffer is prose (plaintext/Markdown → check all words) vs code (comments/strings only). */
    public boolean isProse() {
        return LanguageRegistry.plaintext().equals(language) || "markdown".equals(language);
    }

    /** Enables/disables spell checking for this buffer (driven from Settings by the controller). */
    public void setSpellCheckEnabled(boolean on) {
        this.spellCheckOn = on;
        if (on) {
            SpellDictionaries.ensureBuilt(spellLanguage, spellOverlay::refresh);
        }
        applySpellActive();
    }

    public boolean isSpellCheckEnabled() {
        return spellCheckOn;
    }

    /** Sets the dictionary language id (e.g. {@code en_US}); rebuilds the checker and redraws when ready. */
    public void setSpellLanguage(String langId) {
        if (langId == null || langId.equals(spellLanguage)) {
            return;
        }
        this.spellLanguage = langId;
        if (spellChecker != null) {
            spellChecker.setLanguage(langId, spellOverlay::refresh);
        }
        spellOverlay.refresh();
    }

    public String getSpellLanguage() {
        return spellLanguage;
    }

    /** Supplies the shared (persisted) user-dictionary word set; words added here are never flagged. */
    public void setSpellUserWords(java.util.Set<String> words) {
        if (words == null || words == spellUserWords) {
            return;
        }
        this.spellUserWords = words;
        spellChecker = new SpellChecker(spellLanguage, spellUserWords);
        spellChecker.setUserWordsEnabled(spellUserWordsEnabled);
        spellChecker.setTechnicalWordsEnabled(spellTechnicalEnabled);
        spellOverlay.setChecker(spellChecker);
    }

    /** Enables/disables the personal dictionary (user words); off re-flags those words. Repaints squiggles. */
    public void setUserDictionaryEnabled(boolean enabled) {
        spellUserWordsEnabled = enabled;
        if (spellChecker != null) {
            spellChecker.setUserWordsEnabled(enabled);
            spellOverlay.refresh();
        }
    }

    /** Enables/disables the bundled technical dictionary; off re-flags those terms. Repaints squiggles. */
    public void setTechnicalDictionaryEnabled(boolean enabled) {
        spellTechnicalEnabled = enabled;
        if (spellChecker != null) {
            spellChecker.setTechnicalWordsEnabled(enabled);
            spellOverlay.refresh();
        }
    }

    /** Called when the user picks "Add to Dictionary"; the controller persists the word. */
    public void setOnAddToDictionary(java.util.function.Consumer<String> callback) {
        this.onAddToDictionary = callback == null ? w -> {} : callback;
    }

    /** The overlay is active only when enabled and not in large-file mode (highlighting is off there). */
    private void applySpellActive() {
        spellOverlay.setActive(spellCheckOn && !largeFile);
    }

    /**
     * Enables large-file mode: skips syntax highlighting and hides the minimap (regardless of the
     * user's view settings) so very large or pathologically shaped documents stay responsive. The loader
     * sets it before content is inserted; see {@link #LARGE_FILE_BYTES}.
     */
    public void setLargeFile(boolean large) {
        if (this.largeFile == large) {
            return;
        }
        this.largeFile = large;
        highlightGen++; // discard any in-flight highlight result
        folds.setHeuristicEnabled(!large); // never schedule a whole-document fold scan for a large file
        setMinimapVisible(minimapVisible); // re-apply with the large-file guard
        applySpellActive(); // spell checking is off in large-file mode (like highlighting)
        whitespace.setSuppressed(large); // so are the whitespace markers, whatever the setting says
        // Large files don't need (and shouldn't pay the memory for) undo history.
        applyUndoMode();
        if (!large) {
            applyHighlighting(); // re-enable highlighting if we ever leave large-file mode
        }
    }

    /** Intermediate large-file tier: hides the minimap and (with the controller's help) disables LSP,
     *  while keeping syntax highlighting + editing. Set at load by line count, or toggled per-buffer.
     *  The controller re-runs its LSP sync after toggling so the session starts/stops accordingly. */
    public void setHeavyFile(boolean heavy) {
        if (this.heavyFile == heavy) {
            return;
        }
        this.heavyFile = heavy;
        setMinimapVisible(minimapVisible); // re-apply with the heavy-file guard
        setLspActive(lspActive); // re-evaluate (forces off while heavy); controller re-syncs the session
    }

    public boolean isHeavyFile() {
        return heavyFile;
    }

    /** The document's line (paragraph) count — used to decide the large-file tier at load. */
    public int lineCount() {
        return area.getParagraphs().size();
    }

    /**
     * The length of the document's last line, in characters. Together with {@link #lineCount} this describes
     * the document <em>end</em>, which an LSP range request must be clamped to: a range naming a line past
     * the end makes a server answer with an empty result rather than an error, which is indistinguishable
     * from "nothing to report" (#715).
     */
    public int lastLineLength() {
        var paragraphs = area.getParagraphs();
        return paragraphs.isEmpty() ? 0 : paragraphs.get(paragraphs.size() - 1).length();
    }

    /**
     * A 0-based line's text, or {@code ""} when the index is out of range — so a caller reading lines a
     * server reported against a document that has since moved gets an empty string rather than an
     * exception. Used by inlay-hint filtering to classify the argument at a hint's column (#823).
     */
    public String lineText(int line) {
        var paragraphs = area.getParagraphs();
        return (line < 0 || line >= paragraphs.size())
                ? ""
                : paragraphs.get(line).getText();
    }

    /**
     * Enables huge-file (read-only) mode: implies large-file mode, and makes the views non-editable
     * with no undo. Used for files the loader had to truncate; see {@link #HUGE_FILE_BYTES}.
     */
    public void setReadOnly(boolean readOnly) {
        this.hugeFile = readOnly;
        applyEditable();
        if (readOnly) {
            setLargeFile(true); // also disables highlighting + minimap (and undo via applyUndoMode)
        } else {
            applyUndoMode();
        }
    }

    /**
     * Marks that the loader could only read <b>part</b> of the file — the huge-file cap (first
     * {@link #HUGE_FILE_BYTES}) or a log's tail (the <em>last</em> chunk). The buffer's content is then not
     * the file, and writing it back would <b>truncate the user's file on disk</b>, so the save path refuses.
     * Set by the loader on every load (cleared for a normal, complete read).
     */
    public void setTruncatedLoad(boolean truncated) {
        this.truncatedLoad = truncated;
    }

    /** True when this buffer holds only a slice of its file — see {@link #setTruncatedLoad}. Never save it. */
    public boolean isTruncatedLoad() {
        return truncatedLoad;
    }

    /**
     * User "View mode": makes the buffer non-editable without disabling any normal editor feature
     * (highlighting, minimap, folding, scrolling, and undo all stay on) — unlike {@link #setReadOnly},
     * which is the huge-file mechanism. Edits are blocked at the keyboard via {@code setEditable(false)};
     * the controller additionally guards its own edit commands (see {@code MainController.activeEditable}).
     */
    public void setViewMode(boolean viewMode) {
        this.viewMode = viewMode;
        applyEditable();
    }

    public boolean isViewMode() {
        return viewMode;
    }

    /** Keeps an empty loading shell non-editable without showing the user-facing View Mode banner. */
    public void setLoading(boolean loading) {
        this.loading = loading;
        applyEditable();
    }

    /** True while this is a shell whose document has not arrived: its (empty) text is not the file. */
    public boolean isLoading() {
        return loading;
    }

    /** True when the buffer accepts edits — no huge-file, View mode, loading shell or log filter is active. */
    public boolean isEditable() {
        return !hugeFile && !viewMode && !loading && !logView.filtered();
    }

    /** Applies editability to both views from the current flags, and tags the surface for CSS. */
    private void applyEditable() {
        boolean editable = isEditable();
        area.setEditable(editable);
        if (area2 != null) {
            area2.setEditable(editable);
        }
        toggleStyleClass(area, "read-only", !editable);
        if (area2 != null) {
            toggleStyleClass(area2, "read-only", !editable);
        }
        updateViewModeBar();
    }

    /** Lets the controller wire the banner's "Enable Editing" action (persist + refresh indicators). */
    public void setOnEnableEditing(Runnable onEnableEditing) {
        this.onEnableEditing = onEnableEditing == null ? () -> setViewMode(false) : onEnableEditing;
    }

    /**
     * When true, a file that isn't writable on disk shows "Edit as Administrator" in the View-mode banner
     * (Save then routes through an elevated write) rather than a dead-end "read-only on disk" note. The
     * controller pushes this = admin-save enabled + the OS elevation tool is available + the file is local.
     */
    public void setAdminEditAvailable(boolean available) {
        if (available != adminEditAvailable) {
            adminEditAvailable = available;
            updateViewModeBar();
        }
    }

    /**
     * Whether the file's permissions allow writing it, from the layer that can tell: {@code Files.isWritable}
     * answers yes for any existing SFTP file. {@code null} (the default) asks the file system directly.
     */
    public void setWritableOnDisk(Boolean writable) {
        writableOnDisk = writable;
        updateViewModeBar();
    }

    /**
     * Shows/hides the MS-Word-style "View Mode" banner above the editor: visible only in user View mode
     * (not huge-file mode, which can't be made editable). The "Enable Editing" button appears only when
     * the file is writable; otherwise a "read-only on disk" note replaces it.
     */
    private void updateViewModeBar() {
        boolean show = viewMode && !hugeFile;
        if (show) {
            if (viewModeBar == null) {
                viewModeBar = buildViewModeBar();
            }
            boolean canEdit = path == null || (writableOnDisk != null ? writableOnDisk : Files.isWritable(path));
            // A non-writable file offers "Edit as Administrator" when elevation is available (Linux/pkexec),
            // instead of a dead-end "read-only on disk" note. Enabling editing routes the eventual Save
            // through the elevated write.
            boolean adminOffer = !canEdit && adminEditAvailable;
            enableEditingButton.setText(tr(adminOffer ? "viewmode.editAsAdmin" : "viewmode.enableEditing"));
            // A filtered log is a read-only subset whatever the mode: offering to edit it would do nothing.
            boolean offer = (canEdit || adminOffer) && !logView.filtered();
            enableEditingButton.setVisible(offer);
            enableEditingButton.setManaged(offer);
            viewModeNote.setVisible(!canEdit && !adminOffer);
            viewModeNote.setManaged(!canEdit && !adminOffer);
        }
        viewModeBarVisible = show;
        refreshTopBars();
    }

    /** Puts the active top bars into {@code outer.setTop}: the install banner above the view-mode banner
     *  (a {@code VBox} when both show), or {@code null} when neither does. */
    private void refreshTopBars() {
        java.util.List<javafx.scene.Node> bars = new java.util.ArrayList<>(3);
        if (installBarShown && installBar != null) {
            bars.add(installBar);
        }
        if (viewModeBarVisible && viewModeBar != null) {
            bars.add(viewModeBar);
        }
        if (logControl != null) {
            bars.add(logControl);
        }
        if (bars.isEmpty()) {
            outer.setTop(null);
        } else if (bars.size() == 1) {
            outer.setTop(bars.get(0));
        } else {
            outer.setTop(new javafx.scene.layout.VBox(bars.toArray(new javafx.scene.Node[0])));
        }
    }

    /**
     * Sets the install banner's content + actions (built lazily). Shown/hidden via {@link #showInstallBar}.
     * {@code onInstall}/{@code onDismiss} are wired by MainController; the banner stays toolkit-only.
     */
    public void setInstallPrompt(String message, String actionLabel, Runnable onInstall, Runnable onDismiss) {
        if (installBar == null) {
            buildInstallBar();
        }
        installMessageLabel.setText(message);
        installActionButton.setText(actionLabel);
        this.onInstallAction = onInstall;
        this.onInstallDismiss = onDismiss;
    }

    /** Shows or hides the install banner (no-op visual until {@link #setInstallPrompt} has set content). */
    public void showInstallBar(boolean show) {
        installBarShown = show && installBar != null;
        refreshTopBars();
    }

    public boolean isInstallBarShown() {
        return installBarShown;
    }

    /** Reflects an in-progress install: spins the indicator + disables the buttons. */
    public void setInstallBarBusy(boolean busy) {
        if (installBar == null) {
            return;
        }
        installActionButton.setDisable(busy);
        installDismissButton.setDisable(busy);
        installProgress.setVisible(busy);
        installProgress.setManaged(busy);
    }

    private void buildInstallBar() {
        installMessageLabel = new Label();
        installMessageLabel.getStyleClass().add("lsp-install-title");
        installMessageLabel.setWrapText(true);
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        installProgress = new javafx.scene.control.ProgressIndicator();
        installProgress.setPrefSize(16, 16);
        installProgress.setVisible(false);
        installProgress.setManaged(false);
        installActionButton = new Button();
        installActionButton.getStyleClass().add("accent");
        installActionButton.setOnAction(e -> {
            if (onInstallAction != null) {
                onInstallAction.run();
            }
        });
        installDismissButton = new Button(tr("install.banner.dismiss"));
        installDismissButton.getStyleClass().add("lsp-install-dismiss");
        installDismissButton.setOnAction(e -> {
            if (onInstallDismiss != null) {
                onInstallDismiss.run();
            }
        });
        HBox bar =
                new HBox(10, installMessageLabel, spacer, installProgress, installActionButton, installDismissButton);
        bar.getStyleClass().add("lsp-install-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        installBar = bar;
    }

    private HBox buildViewModeBar() {
        Label title = new Label(tr("viewmode.title"));
        title.getStyleClass().add("view-mode-title");
        Label desc = new Label(tr("viewmode.desc"));
        desc.getStyleClass().add("view-mode-desc");
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        enableEditingButton = new Button(tr("viewmode.enableEditing"));
        enableEditingButton.getStyleClass().add("accent"); // AtlantaFX accent button — themed, not a hard yellow
        enableEditingButton.setOnAction(e -> onEnableEditing.run());
        viewModeNote = new Label(tr("viewmode.note"));
        viewModeNote.getStyleClass().add("view-mode-note");
        HBox bar = new HBox(10, title, desc, spacer, viewModeNote, enableEditingButton);
        bar.getStyleClass().add("view-mode-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    /**
     * Pager-style navigation while in read-only View mode: an unmodified Space pages down and Backspace
     * pages up (like {@code less}/man). Installed as a key <em>filter</em> so it runs before RichTextFX's
     * own (no-op, since non-editable) handling, and only acts while {@link #viewMode} is on — normal
     * editing keeps Space/Backspace untouched. Modifier combos (Ctrl/Alt/Meta) are left for the keymap.
     */
    private void addViewModePaging(CodeArea a) {
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (!viewMode || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                return;
            }
            if (e.getCode() == KeyCode.SPACE) {
                a.nextPage(SelectionPolicy.CLEAR);
                e.consume();
            } else if (e.getCode() == KeyCode.BACK_SPACE) {
                a.prevPage(SelectionPolicy.CLEAR);
                e.consume();
            }
        });
    }

    /** Injects the snippet lookup used by Tab-expand (set by the controller). */
    public void setSnippetProvider(java.util.function.BiFunction<String, String, Snippet> provider) {
        if (provider != null) {
            this.snippetProvider = provider;
        }
    }

    /** True while a snippet's tab stops are being navigated (Tab cycles fields). */
    public boolean hasActiveSnippet() {
        return snippetSession.isActive();
    }

    /**
     * Tab/Shift-Tab/Escape handling for snippets, as a key filter (runs before RichTextFX's own Tab
     * indent). With an active snippet, Tab/Shift-Tab cycle fields and Escape cancels; otherwise Tab
     * tries to expand the identifier before the caret and only consumes the event when one matched —
     * so a plain Tab still indents.
     */
    private void addSnippetKeys(CodeArea a) {
        // Typing a printable char into a MIRRORED snippet field is applied atomically (the char + its mirror
        // update as one undo unit) so a single Ctrl-Z reverts them together instead of leaving the document
        // half-reverted (#415). Only mirrored fields are intercepted; everything else (single-occurrence fields,
        // newlines, control keys, paste) falls through to the normal insert + reactive mirror, which is already
        // one undo unit when there's nothing to mirror.
        a.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (e.isConsumed()) return;
            if (!hasActiveSnippet() || multiCaretActiveOn(a) || !isEditable()) {
                return;
            }
            String ch = e.getCharacter();
            if (ch == null || ch.length() != 1 || e.isControlDown() || e.isMetaDown()) {
                return;
            }
            char c = ch.charAt(0);
            if (c < 0x20 || c == 0x7F) {
                return; // control / non-printable (Enter, Tab, Backspace handled elsewhere)
            }
            if (snippetSession.replaceInActiveField(
                    a.getSelection().getStart(), a.getSelection().getEnd(), ch)) {
                e.consume(); // handled atomically; don't let the area also insert the char
            }
        });
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) return;
            if (multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (hasActiveSnippet()) {
                if (e.getCode() == KeyCode.TAB) {
                    if (e.isShiftDown()) {
                        snippetSession.previous();
                    } else {
                        snippetSession.next();
                    }
                    e.consume();
                } else if (e.getCode() == KeyCode.ESCAPE) {
                    snippetSession.cancel();
                    e.consume();
                }
                return;
            }
            if (e.getCode() == KeyCode.TAB
                    && !e.isControlDown()
                    && !e.isAltDown()
                    && !e.isMetaDown()
                    && !completionActions.completionShowing()) {
                // Plain Tab first tries to expand a snippet prefix; otherwise smart-indent (Shift-Tab
                // always dedents). Skipped while a completion popup/ghost is showing, so Tab accepts
                // the completion instead.
                if (tryMarkdownTableTab(a, !e.isShiftDown())) {
                    e.consume(); // Tab/Shift-Tab move between table cells (and reflow)
                } else if (!e.isShiftDown() && expandPrefixAtCaret(a)) {
                    e.consume();
                } else if (!e.isShiftDown() && tryLspReindentLine(a)) {
                    e.consume(); // LSP re-indents the current line (async); see tryLspReindentLine
                } else if (applySmartTab(a, e.isShiftDown())) {
                    e.consume();
                }
            }
        });
    }

    /** Tab/Shift-Tab cell navigation inside a Markdown pipe table (reflows + moves the caret). */
    private boolean tryMarkdownTableTab(CodeArea a, boolean forward) {
        if (!isMarkdown() || !isEditable() || hugeFile || a.getSelection().getLength() > 0) {
            return false;
        }
        if (!MarkdownTable.isRow(a.getText(a.getCurrentParagraph()))) {
            return false; // the caret's line alone says "not in a table": no need for the document
        }
        String text = a.getText();
        int caret = a.getCaretPosition();
        int[] bounds = MarkdownTable.blockBounds(text, caret);
        if (bounds == null) {
            return false;
        }
        MarkdownTable.Nav nav = MarkdownTable.tab(text.substring(bounds[0], bounds[1]), caret - bounds[0], forward);
        if (nav == null) {
            return false;
        }
        a.replaceText(bounds[0], bounds[1], nav.block());
        a.moveTo(bounds[0] + Math.max(0, Math.min(nav.caret(), nav.block().length())));
        a.requestFollowCaret();
        return true;
    }

    /**
     * Smart Tab / Shift-Tab via the pure {@link Indenter#smartTab}: block-indent a selection, indent the
     * current line in leading whitespace, insert one indent unit mid-line, and Shift-Tab dedents — using
     * the file's indent unit. Prose and unstyled languages get the same minus the context re-indent
     * ({@link com.editora.editops.PlainTab}). Returns false (leaving the default Tab) only in
     * read-only/large-file mode.
     */
    private boolean applySmartTab(CodeArea a, boolean shift) {
        Indenter.TabEdit edit =
                tabEdit(a.getSelection().getStart(), a.getSelection().getEnd(), shift);
        if (edit == null) {
            return false;
        }
        if (edit.from() != edit.to() || !edit.replacement().isEmpty()) {
            a.replaceText(edit.from(), edit.to(), edit.replacement());
        }
        a.selectRange(edit.selStart(), edit.selEnd());
        return true;
    }

    /** The Tab edit for one selection (every caret's, with several); null = leave the key. Smart Tab, or
     *  for PLAIN (prose/plaintext) no context re-indent but still the file's indent unit — both computed
     *  from the lines around the selection, not from the whole document. */
    private Indenter.TabEdit tabEdit(int selStart, int selEnd, boolean shift) {
        if (!isEditable() || hugeFile) {
            return null;
        }
        return IndentWindow.tabEdit(IndentKeys.doc(area), selStart, selEnd, language, tabSize, shift, indentUnit());
    }

    /** The indent unit for this buffer: the EditorConfig override, else the document's own (cached). */
    private String indentUnit() {
        return indentKeys.unit(tabSize, indentInsertSpacesOverride, indentSizeOverride);
    }

    /**
     * Plain Tab re-indents the current line to the language server's convention (the chosen behavior:
     * indentation only, not a full line reformat). Returns {@code true} when it takes over the keystroke
     * (LSP active + the server supports range formatting + a single caret on a non-blank line) and kicks
     * off an async request; the result adjusts only the line's leading whitespace. Returns {@code false}
     * to fall back to the normal smart-Tab indent (no server, unsupported, blank line, or a selection).
     */
    private boolean tryLspReindentLine(CodeArea a) {
        if (!lspActive || !lspRangeFormatAvailable || lspRangeFormatter == null) {
            return false;
        }
        if (!isEditable() || hugeFile || largeFile || a.getSelection().getLength() > 0) {
            return false;
        }
        int par = a.getCurrentParagraph();
        String line = a.getParagraph(par).getText();
        // Formatters strip a blank line's whitespace, so they can't indent a fresh line you're about to
        // type on — leave those to the local smart-Tab indent.
        if (line.isBlank()) {
            return false;
        }
        int caret = a.getCaretPosition();
        long gen = ++reindentGen;
        lspRangeFormatter.format(par, 0, par, line.length(), edits -> {
            if (gen != reindentGen
                    || a.getScene() == null
                    || a.getCurrentParagraph() != par
                    || !a.getParagraph(par).getText().equals(line)) {
                return; // stale, detached, or the line changed under us
            }
            applyLspLineIndent(a, par, line, caret, edits, true);
        });
        return true;
    }

    /**
     * Adopts the formatter's leading whitespace for the current line, leaving the rest of the line as-is.
     *
     * <p>{@code fromTab} distinguishes the two callers, and only matters when the indent is already right:
     * a Tab pressed from inside the leading whitespace must still put the caret where typing starts (the
     * same rule the re-indent below applies), whereas on-type formatting fires while the user is typing and
     * must never move the caret out from under them.
     */
    private void applyLspLineIndent(
            CodeArea a, int par, String line, int caret, java.util.List<LspTextEdit> edits, boolean fromTab) {
        String newIndent = LineIndent.formattedIndent(line, edits, par);
        if (newIndent == null) {
            return; // unusable (multi-line) result → leave the line untouched
        }
        String oldIndent = LineIndent.leadingWhitespace(line);
        if (newIndent.equals(oldIndent)) {
            // Already correctly indented: no edit, but Tab from within the indent still moves the caret to
            // its end — otherwise Tab looks like it did nothing at all.
            int indentEnd = a.getAbsolutePosition(par, 0) + oldIndent.length();
            if (fromTab && caret < indentEnd && indentEnd <= a.getLength()) {
                a.moveTo(indentEnd);
            }
            return;
        }
        int lineStart = a.getAbsolutePosition(par, 0);
        int oldEnd = lineStart + oldIndent.length();
        a.replaceText(lineStart, oldEnd, newIndent);
        int delta = newIndent.length() - oldIndent.length();
        int newCaret = caret <= oldEnd ? lineStart + newIndent.length() : caret + delta;
        newCaret = Math.max(lineStart, Math.min(newCaret, a.getLength()));
        a.moveTo(newCaret);
        // Re-indenting isn't the user typing a word — don't let the edit we just made pop the completion.
        completionActions.suppressCompletionAtVersion = docVersion;
    }

    /**
     * Auto/smart indentation (installed on {@code area}/{@code area2}). On <b>Enter</b>, inserts a
     * newline indented per {@link Indenter} (inherit + block-opener +1 + matching-pair split). When a
     * <b>closing token</b> is typed — a {@code )]}} bracket alone on the line, or a closer keyword like
     * {@code end}/{@code fi} completed — the line is re-aligned to its opener's indent. Inert in
     * read-only mode and while a snippet session owns the keys.
     */
    private void addAutoIndent(CodeArea a) {
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) return;
            if (multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (e.getCode() != KeyCode.ENTER
                    || e.isShiftDown()
                    || e.isControlDown()
                    || e.isAltDown()
                    || e.isMetaDown()) {
                return;
            }
            if (!isEditable() || hasActiveSnippet()) {
                return;
            }
            applyEnter(a);
            e.consume(); // we inserted the newline+indent ourselves
        });
        // Smart backspace: when the caret is in a line's leading whitespace, one Backspace clears the
        // whole indent — and on an otherwise-blank (auto-indented) line it also removes the newline, so
        // a single press jumps back to the end of the previous line ("back to where you hit Enter").
        // Only consumes when it removes more than one char, so a normal single-char Backspace still runs
        // everywhere else. The auto-close empty-pair handler is registered earlier and gets first dibs: it
        // consumes when it deletes a pair, but JavaFX runs *every* filter on a node regardless of consume()
        // (consume only stops propagation to other nodes), so we must re-check isConsumed() here ourselves —
        // otherwise Backspace on an empty pair inside leading whitespace deletes the pair AND the indent.
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) { // the empty-pair handler already consumed this Backspace
                return;
            }
            if (multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (e.getCode() != KeyCode.BACK_SPACE
                    || viewMode
                    || !isEditable()
                    || hasActiveSnippet()
                    || e.isControlDown()
                    || e.isAltDown()
                    || e.isMetaDown()
                    || e.isShiftDown()
                    || a.getSelection().getLength() > 0) {
                return;
            }
            int par = a.getCurrentParagraph();
            int lineStart = a.getAbsolutePosition(par, 0);
            int caret = a.getCaretPosition();
            int col = caret - lineStart;
            if (col <= 0) {
                return; // at column 0 — let normal Backspace join the previous line
            }
            String line = a.getParagraph(par).getText();
            // Markdown: Backspace at the end of an empty list/quote item ("- ", "1. ", "> ", "- [ ] ")
            // clears the whole marker → a blank line (the SDD "smart backspace" helper).
            int marker = isTypst()
                    ? TypstMarkup.emptyMarkerDeleteLength(line, col)
                    : (isMarkdown() ? MarkdownLines.emptyMarkerDeleteLength(line, col) : 0);
            if (marker > 0) {
                a.deleteText(caret - marker, caret);
                e.consume();
                return;
            }
            int del = Indenter.smartBackspaceCount(line.substring(0, col), line.substring(col), par > 0);
            if (del > 1) {
                a.deleteText(caret - del, caret);
                e.consume();
            }
        });
        a.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (e.isConsumed()) { // the auto-close KEY_TYPED handler (registered earlier) already acted
                return;
            }
            if (multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (!isEditable()
                    || hasActiveSnippet()
                    || e.getCharacter().length() != 1
                    || !TypedText.isText(e)
                    || a.getSelection().getLength() > 0) {
                return;
            }
            char typed = e.getCharacter().charAt(0);
            // Fires while the caret is still where the user typed and the document has no ';' yet — the
            // state the server's answer is expressed in. Never consumes, so the char inserts as normal.
            maybeSmartSemicolon(a, typed); // #746
            applyCloserDedent(a, typed);
            // #740. Deferred: this filter runs BEFORE the character is inserted, and both the request's
            // position and its staleness baseline must describe the line with the character in it.
            Platform.runLater(() -> maybeOnTypeFormat(a, typed));
        });
    }

    /** Async smart-semicolon lookup; delivers the target {@code {line, character}} or null on the FX thread. */
    public interface LspSmartSemicolonRequester {
        void request(int line, int character, java.util.function.Consumer<int[]> callback);
    }

    private LspSmartSemicolonRequester smartSemicolonRequester;
    /** {@code Settings.lspSmartSemicolon} — OFF by default, as in VS Code. */
    private boolean smartSemicolonEnabled;

    private long smartSemicolonGen;

    public void setLspSmartSemicolonRequester(LspSmartSemicolonRequester requester) {
        this.smartSemicolonRequester = requester;
    }

    public void setSmartSemicolonEnabled(boolean enabled) {
        this.smartSemicolonEnabled = enabled;
    }

    /**
     * Smart-semicolon detection (#746): a {@code ;} typed part-way through an expression belongs at the end
     * of the statement — {@code compute(1, 2|)} should become {@code compute(1, 2);|}.
     *
     * <p><b>Type first, correct after</b> — the semicolon is never held back waiting for the server. Making
     * a keystroke wait on a round trip is exactly the latency the performance rules forbid, and a slow or
     * cold server would let the character land after whatever was typed next. So the {@code ;} inserts
     * normally and the answer, if it disagrees, moves it — as a single ranged {@code replaceText}, hence one
     * undo step rather than a delete plus an insert.
     *
     * <p>The consequence, stated rather than hidden: if the user types straight through (a very common
     * {@code ;}-then-Enter), {@link #docVersion} has moved by more than the semicolon and the correction is
     * <b>dropped</b>. That is deliberate — the alternative is rewriting text the user has already moved past
     * — and it leaves the semicolon exactly where it would have been with the feature off, never worse.
     */
    private void maybeSmartSemicolon(CodeArea a, char typed) {
        if (typed != ';' || !smartSemicolonEnabled || !lspActive || smartSemicolonRequester == null) {
            return;
        }
        if (!isEditable() || hugeFile || largeFile || isNarrowed() || hasActiveSnippet()) {
            return;
        }
        int typedAt = a.getCaretPosition();
        int line = a.getCurrentParagraph();
        int column = a.getCaretColumn();
        long version = docVersion;
        long gen = ++smartSemicolonGen;
        sendLspChange(); // the position refers to the pre-';' text, so the server must have exactly that
        smartSemicolonRequester.request(line, column, target -> {
            // docVersion bumps once per change, so "+1" is precisely "only the semicolon landed since".
            if (gen != smartSemicolonGen || target == null || a.getScene() == null || docVersion != version + 1) {
                return;
            }
            moveTypedSemicolon(a, typedAt, line, column, target);
        });
    }

    /**
     * Moves the just-typed semicolon from {@code typedAt} to the server's target, which is expressed in
     * <b>pre-insert</b> coordinates — so a target on the same line has to be shifted past the semicolon
     * that now sits before it, while a target on a later line needs no adjustment (its columns are
     * untouched and its absolute offset already includes the inserted character).
     */
    private void moveTypedSemicolon(CodeArea a, int typedAt, int typedLine, int typedColumn, int[] target) {
        int targetPost;
        try {
            targetPost = target[0] == typedLine && target[1] >= typedColumn
                    ? a.getAbsolutePosition(target[0], target[1] + 1)
                    : a.getAbsolutePosition(target[0], target[1]);
        } catch (RuntimeException outOfRange) {
            return; // a target the document can no longer address
        }
        if (targetPost <= typedAt + 1 || targetPost > a.getLength()) {
            return; // already where it was typed (the ordinary answer), or past the end
        }
        // Rewrite [typedAt, targetPost) as "the text after the semicolon, then the semicolon" — one edit,
        // so one Ctrl-Z puts it back where it was typed.
        a.replaceText(typedAt, targetPost, a.getText(typedAt + 1, targetPost) + ";");
    }

    /**
     * On-type formatting (#740): after a character the server named as a trigger ({@code ;}, {@code }},
     * {@code \n} for jdtls), ask it how the line should be indented and adopt only that.
     *
     * <p><b>The local assist runs first and the server refines it.</b> That is the resolution of the
     * conflict between this and {@link #applyCloserDedent}/{@link #applyEnter}, which already act on
     * {@code }} and Enter: making a very common keystroke wait on a round-trip would be felt, and the local
     * result is usually already right — in which case {@link #applyLspLineIndent} finds the indent unchanged
     * and does nothing at all. So the two never fight; the server only corrects a disagreement.
     *
     * <p>Indentation only, never a full reformat — the user is mid-edit, and rewriting the line under them
     * is the behaviour that makes on-type formatting infuriating in other editors.
     *
     * <p>Inert unless the setting is on, the server advertises a trigger set containing this character, and
     * the buffer is an editable, normal-sized, single-caret LSP buffer with no snippet session. A trigger
     * consumed by auto-close (typing {@code }} to skip over an inserted one) doesn't reach here — that path
     * leaves the line already correct.
     */
    private void maybeOnTypeFormat(CodeArea a, char typed) {
        if (disposed || !onTypeFormattingEnabled || !lspActive || lspOnTypeFormatter == null) {
            return;
        }
        if (!lspOnTypeTriggers.contains(typed)) {
            return;
        }
        if (!isEditable()
                || hugeFile
                || largeFile
                || hasActiveSnippet()
                || a.getSelection().getLength() > 0) {
            return;
        }
        int par = a.getCurrentParagraph();
        String line = a.getParagraph(par).getText();
        int caret = a.getCaretPosition();
        if (a.getCaretColumn() == 0 || line.charAt(a.getCaretColumn() - 1) != typed) {
            return; // the keystroke did not insert its character after all
        }
        long gen = ++reindentGen; // shares the Tab re-indent's generation: both adjust the same line's indent
        lspOnTypeFormatter.format(par, a.getCaretColumn(), typed, edits -> {
            if (gen != reindentGen
                    || a.getScene() == null
                    || a.getCurrentParagraph() != par
                    || !a.getParagraph(par).getText().equals(line)) {
                return; // stale, detached, or the user kept typing and the line moved on
            }
            applyLspLineIndent(a, par, line, caret, edits, false);
        });
    }

    /**
     * The body of the Enter auto-indent (Markdown list/blockquote continuation, else {@link Indenter}):
     * clears any selection, inserts the indented newline, and moves the caret. Shared by the {@code ENTER}
     * key filter and macro replay ({@link #typeChar} for {@code '\n'}).
     */
    private void applyEnter(CodeArea a) {
        if (a.getSelection().getLength() > 0) {
            a.replaceSelection("");
        }
        int caret = a.getCaretPosition();
        // Markdown-only: Enter on a table's last row appends a new row.
        if (isMarkdown() && MarkdownTable.isRow(a.getText(a.getCurrentParagraph()))) {
            int[] tb = MarkdownTable.blockBounds(a.getText(), caret);
            if (tb != null) {
                MarkdownTable.Nav nav = MarkdownTable.enter(a.getText().substring(tb[0], tb[1]), caret - tb[0]);
                if (nav != null) {
                    a.replaceText(tb[0], tb[1], nav.block());
                    a.moveTo(tb[0]
                            + Math.max(0, Math.min(nav.caret(), nav.block().length())));
                    a.requestFollowCaret();
                    return;
                }
            }
        }
        // List continuation (Markdown + Typst): continue the marker on the next line, or end the list when
        // Enter is pressed on an empty item. Typst uses its own markers (-, +, N. — never *, which is bold).
        if (isMarkdown() || isTypst()) {
            int par = a.getCurrentParagraph();
            int lineStart = a.getAbsolutePosition(par, 0);
            String line = a.getParagraph(par).getText();
            int markerLen = isTypst()
                    ? TypstMarkup.markerLength(line)
                    : MarkdownLines.listMarkerLength(a::getText, lineStart, line);
            if (markerLen > 0 && caret - lineStart >= markerLen) {
                boolean empty = isTypst() ? TypstMarkup.isEmptyItem(line) : MarkdownLines.isEmptyItem(line);
                if (empty) {
                    a.replaceText(lineStart, lineStart + line.length(), ""); // exit list (clear marker)
                    a.requestFollowCaret();
                    return;
                }
                String cont = isTypst() ? TypstMarkup.continuation(line) : MarkdownLines.continuation(line);
                if (cont != null) {
                    a.replaceText(caret, caret, "\n" + cont);
                    a.moveTo(caret + 1 + cont.length());
                    a.requestFollowCaret();
                    return;
                }
            }
        }
        applyCloserDedent(a, '\n'); // Enter finishes a closer keyword (fi, end, done): align it first
        caret = a.getCaretPosition();
        Indenter.EnterEdit edit = IndentWindow.enterEdit(IndentKeys.doc(a), caret, language, indentUnit());
        a.replaceText(caret, caret, edit.insert());
        a.moveTo(caret + edit.caretOffset());
        a.requestFollowCaret();
    }

    /**
     * Auto-close decision for a typed character (see {@link AutoClose}): insert a pair / type over a
     * closer / wrap the selection. Returns {@code true} when it acted (so the caller consumes the event and
     * skips normal insertion), {@code false} for ordinary typing. Shared by the auto-close key filter and
     * macro replay ({@link #typeChar}).
     */
    private boolean applyAutoCloseTyped(CodeArea a, char c) {
        if (c == '>' && applyTagAutoClose(a)) {
            return true; // html/xml: the > completed an open tag and the closer was inserted
        }
        if (AutoClose.closerFor(c) == 0 && !AutoClose.isCloser(c)) {
            return false; // not a bracket or quote
        }
        int caret = a.getCaretPosition();
        int len = a.getLength();
        char prev = caret > 0 ? a.getText(caret - 1, caret).charAt(0) : 0;
        char next = caret < len ? a.getText(caret, caret + 1).charAt(0) : 0;
        boolean hasSel = a.getSelection().getLength() > 0;
        AutoClose.Decision d = AutoClose.decide(c, prev, next, hasSel);
        switch (d.action()) {
            case INSERT_PAIR -> {
                a.replaceText(caret, caret, "" + c + d.closer());
                a.moveTo(caret + 1);
                return true;
            }
            case SKIP_OVER -> {
                a.moveTo(caret + 1);
                return true;
            }
            case WRAP_SELECTION -> {
                int s = a.getSelection().getStart();
                String sel = a.getSelectedText();
                a.replaceText(s, a.getSelection().getEnd(), "" + c + sel + d.closer());
                a.selectRange(s + 1, s + 1 + sel.length());
                return true;
            }
            case NONE -> {
                return false; // normal typing (and the auto-indent closer-dedent may run)
            }
        }
        return false;
    }

    /**
     * When a closing token is typed alone on a line (a {@code )]}} bracket, or the character/Enter that
     * finishes a closer keyword like {@code end}/{@code fi}), re-aligns the line's indent to its opener. Does
     * <em>not</em> insert {@code c} (the caller does). Shared by the key filters and macro replay.
     */
    private void applyCloserDedent(CodeArea a, char c) {
        Indenter.Style style = Indenter.styleFor(language);
        int caret = a.getCaretPosition();
        int lineStart = a.getAbsolutePosition(a.getCurrentParagraph(), 0);
        String beforeCaret = a.getText(lineStart, caret);
        boolean bracket = Indenter.isCloserChar(style, c) && !beforeCaret.isEmpty() && beforeCaret.isBlank();
        boolean keyword = Indenter.completesCloserKeyword(style, beforeCaret + c);
        if (!bracket && !keyword) {
            return;
        }
        // Re-align this line's indent to its opener; the typed char then inserts normally (not consumed).
        String currentIndent = completionActions.leadingIndent(beforeCaret);
        String aligned = IndentWindow.closerAlignIndent(style, IndentKeys.doc(a), caret, tabSize, currentIndent, c);
        if (!aligned.equals(currentIndent)) {
            a.replaceText(lineStart, lineStart + currentIndent.length(), aligned);
            a.moveTo(caret + aligned.length() - currentIndent.length()); // back after the closer, not the indent
        }
    }

    /**
     * Replays a run of literally-typed text from a recorded macro, routing each character through the same
     * typing assists the live key filters use (auto-close, Enter auto-indent, closer dedent, smart Tab), so
     * a replayed {@code (} pairs and a replayed newline re-indents exactly as when typed by hand.
     */
    public void typeString(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        for (int i = 0; i < text.length(); i++) {
            typeChar(text.charAt(i));
        }
    }

    /**
     * True when {@code target} is (or sits inside) one of this buffer's editor areas — i.e. a key event
     * aimed at the document itself rather than at an overlay, the find bar, or a tool-window field.
     *
     * <p>The macro recorder needs this because the key hooks live on a <b>scene</b> filter, which sees every
     * key in the window: without it, the text typed into the command palette or the find bar was recorded
     * and then replayed straight into the document.
     */
    public boolean ownsKeyTarget(javafx.event.EventTarget target) {
        for (javafx.scene.Node n = target instanceof javafx.scene.Node node ? node : null;
                n != null;
                n = n.getParent()) {
            if (n == area || (area2 != null && n == area2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replays a bare editing/navigation key press from a recorded macro — Backspace, Delete, an arrow,
     * Home/End, Page Up/Down — by {@code KeyCode} name.
     *
     * <p>The token carries the modifiers ({@code BACK_SPACE}, {@code S-DOWN}, {@code C-LEFT}), because the
     * area acts on modified variants too — Shift-Down extends the selection, Ctrl-Left goes a word left —
     * and replaying those as a bare arrow would move the caret instead.
     *
     * <p>Unlike {@link #typeString}, there is no "apply" method to reuse here: the behavior of these keys
     * lives in the area's own input map (and in the Backspace filters — smart-backspace, the auto-close
     * empty-pair delete, the Markdown empty-marker delete). So this fires a real {@code KEY_PRESSED} at the
     * focused area, which runs exactly the handlers a hand-pressed key runs. Recording only ever captures
     * keys bound to no command, so the re-dispatch can't also fire a chord; the macro recorder is inert
     * during replay in any case.
     */
    public void pressKey(String macroKeyToken) {
        com.editora.macro.MacroKey.Decoded k = com.editora.macro.MacroKey.decode(macroKeyToken);
        if (k == null || !isEditable() || hugeFile) {
            return;
        }
        KeyCode code;
        try {
            code = KeyCode.valueOf(k.keyCodeName());
        } catch (IllegalArgumentException e) {
            return; // a hand-edited macros.json / a step typed into the Settings editor
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        a.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, k.shift(), k.ctrl(), k.alt(), k.meta()));
    }

    /** Types a single character through the editor's typing assists. See {@link #typeString}. */
    public void typeChar(char c) {
        CodeArea a = focusedArea != null ? focusedArea : area;
        if (!isEditable() || hugeFile) {
            return;
        }
        if (c == '\n' || c == '\r') {
            applyEnter(a);
            return;
        }
        if (c == '\t') {
            if (!applySmartTab(a, false)) {
                a.replaceSelection("\t");
            }
            a.requestFollowCaret();
            return;
        }
        // Mirror the live KEY_TYPED order: auto-close first; if it didn't act, closer-dedent then insert.
        if (applyAutoCloseTyped(a, c)) {
            a.requestFollowCaret();
            return;
        }
        if (a.getSelection().getLength() == 0) {
            applyCloserDedent(a, c);
        }
        a.replaceSelection(String.valueOf(c));
        a.requestFollowCaret();
    }

    /**
     * Auto-closes brackets/quotes (installed on {@code area}/{@code area2}). A {@code KEY_TYPED} filter
     * inserts the matching closer / types over an existing one / wraps a selection (see {@link AutoClose});
     * a {@code KEY_PRESSED} filter removes both halves of an empty pair on Backspace. Added before
     * {@link #addAutoIndent} so it sees the keystroke first; when it does nothing it leaves the event
     * for normal typing (and the indent closer-dedent).
     */
    private void addAutoClose(CodeArea a) {
        a.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (e.isConsumed()) return;
            if (multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (!isEditable() || hasActiveSnippet() || e.getCharacter().length() != 1 || !TypedText.isText(e)) {
                return;
            }
            char c = e.getCharacter().charAt(0);
            if (applyAutoCloseTyped(a, c)) {
                e.consume();
            }
        });
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) return;
            if (multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (e.getCode() != KeyCode.BACK_SPACE
                    || viewMode
                    || !isEditable()
                    || hasActiveSnippet()
                    || e.isControlDown()
                    || e.isAltDown()
                    || e.isMetaDown()
                    || e.isShiftDown()
                    || a.getSelection().getLength() > 0) {
                return;
            }
            int caret = a.getCaretPosition();
            if (caret <= 0 || caret >= a.getLength()) {
                return;
            }
            char prev = a.getText(caret - 1, caret).charAt(0);
            char next = a.getText(caret, caret + 1).charAt(0);
            if (AutoClose.isEmptyPair(prev, next)) {
                a.deleteText(caret - 1, caret + 1); // remove both halves of the empty pair
                e.consume();
            }
        });
    }

    /** Coalesces a matching-bracket recompute to the next pulse (caret moves rapidly while typing). */
    private void scheduleBraceMatch() {
        if (braceMatchPending) {
            return;
        }
        braceMatchPending = true;
        Platform.runLater(this::updateBraceMatch);
    }

    /** Clears any previous match highlight and highlights the pair adjacent to the focused caret. */
    private void updateBraceMatch() {
        braceMatchPending = false;
        clearBraceMatch();
        if (largeFile) {
            return; // brace matching off in large-file mode (highlighting is disabled there too)
        }
        CodeArea a = focusedArea;
        int caret = a.getCaretPosition();
        // BraceMatcher answers from the two characters beside the caret and never scans beyond
        // DEFAULT_MAX_SCAN chars from it: read those two first, and build the window (up to 100,000 chars,
        // on every caret move — the hot path) only when one of them is a bracket.
        int max = BraceMatcher.DEFAULT_MAX_SCAN;
        int winStart = Math.max(0, caret - max - 1);
        int winEnd = Math.min(a.getLength(), caret + max + 1);
        String beside = a.getText(Math.max(0, caret - 1), Math.min(a.getLength(), caret + 1));
        if (!BraceMatcher.needsScan(
                caret > 0 ? beside.charAt(0) : 0, beside.isEmpty() ? 0 : beside.charAt(beside.length() - 1))) {
            return;
        }
        int[] m = BraceMatcher.match(a.getText(winStart, winEnd), caret - winStart, max);
        if (m != null) {
            addBraceClass(m[0] + winStart);
            addBraceClass(m[1] + winStart);
            completionActions.braceMatch = new int[] {m[0] + winStart, m[1] + winStart};
        }
    }

    private void clearBraceMatch() {
        if (completionActions.braceMatch != null) {
            removeBraceClass(completionActions.braceMatch[0]);
            removeBraceClass(completionActions.braceMatch[1]);
            completionActions.braceMatch = null;
        }
    }

    // The match style combines with the char's syntax classes, so the brace keeps its token color; the
    // syntax highlighter overwrites it on re-highlight, after which scheduleBraceMatch() re-applies.
    private void addBraceClass(int pos) {
        if (pos < 0 || pos >= area.getLength()) {
            return;
        }
        java.util.List<String> style = new java.util.ArrayList<>(area.getStyleOfChar(pos));
        if (!style.contains("brace-match")) {
            style.add("brace-match");
            area.setStyle(pos, pos + 1, style);
        }
    }

    private void removeBraceClass(int pos) {
        if (pos < 0 || pos >= area.getLength()) {
            return;
        }
        java.util.List<String> style = new java.util.ArrayList<>(area.getStyleOfChar(pos));
        if (style.remove("brace-match")) {
            area.setStyle(pos, pos + 1, style);
        }
    }

    /** Inserts a snippet from the picker, replacing the selection (if any), and starts its session. */
    public void insertSnippet(Snippet snippet) {
        if (snippet == null || !isEditable()) {
            return;
        }
        CodeArea a = focusedArea;
        int from = a.getSelection().getLength() > 0 ? a.getSelection().getStart() : a.getCaretPosition();
        int to = a.getSelection().getLength() > 0 ? a.getSelection().getEnd() : from;
        startSnippet(a, snippet, from, to);
        a.requestFocus();
    }

    /** Expands the token before the caret if it matches a snippet prefix; returns whether it did. */
    private boolean expandPrefixAtCaret(CodeArea a) {
        if (!isEditable() || a.getSelection().getLength() > 0) {
            return false;
        }
        // A snippet prefix is a short token ending at the caret: look at the same bounded stretch the
        // completion prefix uses instead of building the whole document on every plain Tab.
        int base = Math.max(0, a.getCaretPosition() - BufferCompletion.PREFIX_LOOKBACK);
        String text = a.getText(base, a.getCaretPosition());
        int caret = text.length();
        int identStart = caret;
        while (identStart > 0 && completionActions.isPrefixChar(text.charAt(identStart - 1))) {
            identStart--;
        }
        // Plenty of snippet prefixes aren't identifiers — `#include`/`#ifndef` (c/cpp), `!` (the emmet html
        // skeleton), `?xml`, `---` (yaml), `->` (ruby), `[PSCustomObject]` — so try the whole
        // non-whitespace token first and fall back to the identifier run. Matching only the identifier run
        // left 42 bundled snippets unreachable from the keyboard: at `#inc` the scan stops on the `#` and
        // looks up "inc", which no snippet is registered under.
        int tokenStart = completionActions.snippetTokenStart(text, caret);
        if (tokenStart < identStart) {
            Snippet wide = snippetProvider.apply(language, text.substring(tokenStart, caret));
            if (wide != null) {
                startSnippet(a, wide, base + tokenStart, base + caret);
                return true;
            }
        }
        if (identStart == caret) {
            return false;
        }
        Snippet snippet = snippetProvider.apply(language, text.substring(identStart, caret));
        if (snippet == null) {
            return false;
        }
        startSnippet(a, snippet, base + identStart, base + caret);
        return true;
    }

    /** Parses {@code snippet}, replaces {@code [from,to)} with the expansion, and begins a session. */
    private void startSnippet(CodeArea a, Snippet snippet, int from, int to) {
        startSnippet(a, snippet, from, to, true);
    }

    private void startSnippet(CodeArea a, Snippet snippet, int from, int to, boolean reindent) {
        String fileName = path == null ? "" : path.getFileName().toString();
        String directory = path == null || path.toAbsolutePath().getParent() == null
                ? ""
                : path.toAbsolutePath().getParent().toString();
        String filePath = path == null ? "" : path.toAbsolutePath().toString();
        String clip = javafx.scene.input.Clipboard.getSystemClipboard().hasString()
                ? LineEndings.toLf(
                        javafx.scene.input.Clipboard.getSystemClipboard().getString())
                : "";
        int line = a.offsetToPosition(from, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        String currentLine = a.getParagraph(line).getText();
        VariableResolver vars =
                new VariableResolver(fileName, directory, filePath, a.getSelectedText(), clip, line, currentLine);
        ParsedSnippet parsed = SnippetParser.parse(snippet.body(), vars);
        String indent = reindent ? completionActions.leadingIndent(currentLine) : "";
        // asIs (no reindent) keeps the text untouched; otherwise the body's tabs become the buffer's unit.
        String unit = reindent ? indentUnit() : null;
        snippetSession.start(a, parsed, from, to, indent, unit);
    }

    /**
     * Inserts an already-parsed file template into this (typically empty, untitled) buffer over its full
     * content, honoring the template's {@code ${cursor}} ({@code $0}) final caret and any {@code $1…} tab
     * stops via a snippet session. No-op when not editable.
     */
    public void applyTemplate(ParsedSnippet parsed) {
        if (parsed == null || !isEditable()) {
            return;
        }
        snippetSession.cancel();
        snippetSession.start(area, parsed, 0, area.getLength(), "");
        area.requestFocus();
    }

    // ---- Autocomplete (snippet + dictionary completions; see com.editora.completion) ----

    // ---- AI inline completion (ghost text from an AI provider; see ui.AiCoordinator) ----

    /** Requests a short AI continuation of the text around the caret; the result must be delivered on
     *  the FX thread (null/empty = nothing to show). Kept editor-neutral so {@code editor} stays free
     *  of {@code ai}/{@code ui}. */
    public interface AiCompletionProvider {
        void complete(String language, String prefix, String suffix, java.util.function.Consumer<String> onResult);
    }

    // ---- Completion documentation side-popup (IntelliJ "quick documentation") ----

    /**
     * Popup navigation/accept/dismiss while it's open. Registered <b>after</b> the snippet/indent filters
     * so it runs first: with the popup open, Tab/Enter accept the selection (instead of expanding a
     * snippet or inserting a newline); ↑/↓ move; Esc closes; caret-moving keys dismiss. With the popup
     * closed it does nothing, so normal Tab/Enter behavior is unaffected.
     */

    /**
     * Shows {@code actions} at the caret and calls {@code onAccept} with the chosen one.
     *
     * <p>Focus stays in the editor — the popup is focus-less and this buffer's key filter drives it — which
     * is why the caret keeps blinking where the fix will land while the list is open.
     */

    /**
     * Monotonic count of edits made to this buffer's document. Callers that hand work to an async round-trip
     * and then apply its result to the document (e.g. resolving a completion's auto-import edits) capture
     * this first and drop the result if it moved — the offsets a server computed against one revision are
     * meaningless against another.
     */
    public long docVersion() {
        return docVersion;
    }

    /**
     * Immutable whole-document text for the current version. Must be called on the FX thread, like
     * RichTextFX's {@code getText()}; repeated consumers of the same version receive the same String.
     */
    private String documentTextSnapshot() {
        return documentSnapshots.get(docVersion, area::getText).text();
    }

    /** Number of whole-document strings this buffer has materialized through the shared cache (tests). */
    long documentSnapshotMaterializations() {
        return documentSnapshots.materializations();
    }

    /** Number of settled-edit tasks sharing this buffer's one document subscription (tests). */
    int settledEditTaskCount() {
        return settledEdits.taskCount();
    }

    /** Whether the one shared settled-edit document subscription is active (tests). */
    boolean settledEditSubscriptionActive() {
        return settledEditSub != null;
    }

    /** Emacs {@code C-SPC}: record {@code pos} on this buffer's mark ring so {@code popMark} can return. */
    public void pushMark(int pos) {
        markRing.push(pos);
    }

    /**
     * Emacs {@code pop-to-mark}: the position to move point to (cycling), given where the caret is now, or
     * empty when the ring holds no marks.
     */
    public java.util.OptionalInt popMark(int currentPoint) {
        return markRing.pop(currentPoint);
    }

    public int markRingSize() {
        return markRing.size();
    }

    private static int[] completionChangeEnd(int[] start, String text) {
        int line = start[0];
        int col = start[1];
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 0;
            } else col++;
        }
        return new int[] {line, col};
    }

    /** Capture the acceptance independently of future completions and track safe typing until resolve lands. */
    public java.util.function.Consumer<java.util.List<LspTextEdit>> trackCompletionAdditionalEdits() {
        var shift = completionActions.pendingCompletionShift;
        int start = shift == null
                ? area.getCaretPosition()
                : LspEditPlacement.offset(area, shift.startLine(), shift.startCol());
        int end = start;
        String line = area.getParagraph(
                        area.offsetToPosition(start, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                                .getMajor())
                .getText();
        int column = LspEditPlacement.position(area, start)[1];
        while (column < line.length() && Character.isJavaIdentifierPart(line.charAt(column++))) end++;
        var tracker = new CompletionEditTracker(shift, start, end);
        if (completionEditTrackers.size() >= 8) completionEditTrackers.removeFirst();
        completionEditTrackers.add(tracker);
        var undo = CompletionUndoManager.captureAdditionalEdits(area, area2);
        return edits -> {
            boolean tracked = completionEditTrackers.remove(tracker);
            if (tracked && !disposed) {
                undo.accept(() -> applyLspEdits(tracker.translate(edits), true));
                completionActions.suppressCompletionAtVersion = docVersion;
            }
        };
    }

    /**
     * Applies the {@code additionalTextEdits} a {@code completionItem/resolve} returned for the completion this
     * buffer last accepted (an auto-import line). Unlike {@link #applyLspEdits}, the positions are first
     * translated across the accept's own insertion, which moved everything below the caret after the server
     * computed them — see {@link LspEditShift}.
     */
    public void applyCompletionAdditionalEdits(java.util.List<LspTextEdit> edits) {
        // Keep the caret where the accept left it. The import lands *above* the caret, and both apply paths
        // (replaceText, and MultiChangeBuilder.commit) leave the caret at the end of what they inserted — so
        // without this, accepting `List<Item` threw the caret onto the newly inserted `import` line (#834).
        applyLspEdits(LspEditShift.shift(edits, completionActions.pendingCompletionShift), true);
    }

    /**
     * Applies language-server text edits (e.g. Format Document's whole-file edit set, or a completion's
     * {@code additionalTextEdits} — the {@code import} line an auto-import adds). Edits apply as one undoable
     * commit. Column positions beyond a line's length are clamped (the LSP spec's rule), and the position one
     * past the last line, column 0, is the document end. An edit that <em>starts</em> further out than that
     * is skipped, not clamped — no valid edit begins there, so the server computed against a stale
     * revision, and clamping would apply the edit at a wrong place (#667). Inert when not editable.
     */
    public void applyLspEdits(java.util.List<LspTextEdit> edits) {
        applyLspEdits(edits, false);
    }

    /**
     * As {@link #applyLspEdits(java.util.List)}, but all-or-nothing: when any edit cannot be placed — it
     * overlaps another, starts or ends outside the document, or carries a negative position — <b>nothing</b>
     * is applied and {@code false} is returned. A workspace edit spans files the user is not looking at, so
     * an edit dropped there is a half-applied refactoring reported as done; the lenient variant stays for
     * the single-buffer paths that have always skipped a stale edit.
     */
    public boolean applyLspEditsAtomically(java.util.List<LspTextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return true;
        }
        return isEditable() && applyLspEditsNow(edits, false, true);
    }

    /** Whether {@link #applyLspEditsAtomically} would apply {@code edits}; changes nothing. */
    public boolean canPlaceLspEdits(java.util.List<LspTextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return true;
        }
        return isEditable() && LspEditPlacement.place(focusedArea != null ? focusedArea : area, edits, true) != null;
    }

    /**
     * As {@link #applyLspEdits(java.util.List)}, but optionally restoring the caret to the position it
     * addressed <em>before</em> the edits. Used by the auto-import path, where the inserted line sits above
     * the caret and would otherwise drag it away from what the user was typing (#834).
     */
    private void applyLspEdits(java.util.List<LspTextEdit> edits, boolean preserveCaret) {
        if (preserveCaret) snippetSession.withExternalEdits(() -> applyLspEditsNow(edits, true, false));
        else applyLspEditsNow(edits, false, false);
    }

    /** Returns whether the edits were placed; lenient mode always answers true. */
    private boolean applyLspEditsNow(java.util.List<LspTextEdit> edits, boolean preserveCaret, boolean strict) {
        if (edits == null || edits.isEmpty() || !isEditable()) {
            return !strict;
        }
        return LspEditPlacement.apply(focusedArea != null ? focusedArea : area, edits, preserveCaret, strict);
    }

    private static void toggleStyleClass(Node node, String styleClass, boolean on) {
        if (on) {
            if (!node.getStyleClass().contains(styleClass)) {
                node.getStyleClass().add(styleClass);
            }
        } else {
            node.getStyleClass().remove(styleClass);
        }
    }

    /** Picks the undo manager for the current mode: none for large/huge files, bounded otherwise. */
    private void applyUndoMode() {
        area.setUndoManager(largeFile ? UndoUtils.noOpUndoManager() : boundedUndoManager());
        if (area2 != null) {
            area2.setUndoManager(area.getUndoManager());
        }
    }

    public boolean isReadOnly() {
        return hugeFile;
    }

    public boolean isLargeFile() {
        return largeFile;
    }

    /** Replaces the document content (e.g. after loading a file) and resets the dirty flag. */
    public void setContent(String content) {
        setInitialContent(content);
    }

    /**
     * Installs freshly-loaded content without immediately copying the whole RichTextFX document back out
     * merely to establish the clean baseline. Size-based modes should be applied before this call, so the
     * initial replacement never enters an undo manager or feature that the load profile will disable.
     */
    public void setInitialContent(String content) {
        setInitialContent(content, false);
    }

    /** As above, optionally splitting giant paragraphs into style segments ({@link InitialDocument#segmented}). */
    public void setInitialContent(String content, boolean segmentLongLines) {
        setInitialContent(
                segmentLongLines ? InitialDocument.prepare(area, content, true) : InitialDocument.ofText(content));
    }

    /** Builds a load's paragraphs for {@link #setInitialContent(InitialDocument)}; safe off the FX thread. */
    public InitialDocument prepareInitialContent(String content, boolean segmentLongLines) {
        return InitialDocument.prepare(area, content, segmentLongLines);
    }

    /** Installs a prepared load: one document swap, with the loaded String shared as baseline and snapshot. */
    public void setInitialContent(InitialDocument loaded) {
        // The area never holds a '\r', so remember the file's line ending here and keep the baseline in the
        // same normalised form — a CRLF baseline could never equal the document again (edit + undo stayed dirty).
        lineEnding = loaded.lineEnding();
        cleanLineEnding = lineEnding;
        String initial = loaded.text();
        widen(); // a fresh document supersedes any narrowing of the old one
        Runnable refilter = logView.suspendFilter(true);
        // Establish the baseline before the change event. Otherwise an async loading shell briefly becomes
        // dirty during replace(), which promotes a disposable preview tab before the method can clear it.
        // A truncated load has no baseline (null): it is never compared, and pins no copy once a log tail moves on.
        cleanText = truncatedLoad ? null : initial;
        forcedDirty = false;
        documentSnapshots.expect(initial); // the dirty check inside the change event must not copy it back out
        if (loaded.document() != null) {
            area.replace(0, area.getLength(), loaded.document());
        } else {
            area.replaceText(initial);
        }
        if (area.getLength() == initial.length()) {
            documentSnapshots.seed(docVersion, initial);
        } else {
            documentSnapshots.expect(null);
            documentSnapshots.invalidate();
        }
        forgetHistoryAtNarrowBoundary(false); // the load is the baseline, not an undo step: undoing it emptied the file
        captureUndoCheckpoint(); // ...and the Undo History baseline is the loaded text, not the loading shell
        refilter.run();
        dirty.set(false);
        gitLines.reset(); // freshly loaded: buffer lines are the disk's lines again
        recomputeRun(); // detect a runnable file on load (drives the Run glyph)
    }

    /**
     * Places the caret at the start of the document and scrolls to the top. Called after a file is
     * loaded so opening a file always lands on the first line ({@code replaceText} leaves the caret
     * at the end, and restoring saved folds moves it to a fold header).
     */
    public void goToStart() {
        area.moveTo(0);
        try {
            area.showParagraphAtTop(0);
        } catch (RuntimeException ignored) {
            // Viewport not laid out yet; it defaults to the top, so there is nothing more to do.
        }
    }

    /**
     * Moves the caret one line down ({@code delta == 1}) or up ({@code delta == -1}) in the focused
     * view, Emacs-style: the target column is the "goal column" — the column the caret was at when the
     * vertical run began — clamped to the target line's length, so passing through short lines does not
     * lose the original column. Any non-vertical caret move resets the goal (see the caret listeners).
     */
    public void moveLine(int delta, SelectionPolicy policy) {
        CodeArea a = focusedArea;
        if (goalColumn < 0) {
            goalColumn = a.getCaretColumn();
        }
        int target = a.getCurrentParagraph() + delta;
        if (target < 0 || target >= a.getParagraphs().size()) {
            return; // already at the first/last line
        }
        int col = Math.min(goalColumn, a.getParagraphLength(target));
        movingByLine = true;
        try {
            a.moveTo(target, col, policy);
        } finally {
            movingByLine = false;
        }
        a.requestFollowCaret();
    }

    /** Clears the goal column unless the caret change came from {@link #moveLine} itself. */
    private void resetGoalColumn() {
        if (!movingByLine) {
            goalColumn = -1;
        }
    }

    /**
     * The <b>whole document</b>, including any part hidden by narrowing. This is what every caller that
     * means "the file" wants — save, autosave, LSP sync, diff, local history, find-in-files, the MCP
     * bridge, the plugin API — and returning the full text here is what makes narrowing safe by
     * construction rather than by each of them remembering to ask. Use {@link #getVisibleContent()} for
     * the accessible portion.
     */
    public String getContent() {
        if (logView.filtered()) {
            return logView.fullText(); // the area shows only the matching lines
        }
        return narrowPrefix == null ? documentTextSnapshot() : narrowPrefix + documentTextSnapshot() + narrowSuffix;
    }

    // --- Narrowing -------------------------------------------------------------------------------

    public boolean isNarrowed() {
        return narrowPrefix != null;
    }

    /** Offset of the accessible region within the whole document (0 when widened). */
    public int narrowStart() {
        return narrowPrefix == null ? 0 : narrowPrefix.length();
    }

    /**
     * Emacs {@code narrow-to-region}: makes only {@code [start, end)} accessible, holding the rest aside.
     * Returns false when the request is not narrowable (an empty region, or a file large enough that
     * swapping the text is not worth it).
     *
     * <p>The document text really is replaced, so anything reading {@code area.getText()} directly sees
     * only the region — which is the point, and is why {@link #getContent()} exists to keep whole-file
     * callers correct.
     *
     * <p><b>Undo history is dropped at the boundary.</b> The swap is itself an edit, and undoing across it
     * would restore the whole document <em>into</em> the narrowed area while the hidden text is still held
     * aside — duplicating the file. Rather than let that be reachable, both narrowing and widening clear
     * the history of both split views and the Undo History checkpoints; edits made while narrowed undo
     * normally.
     */
    public boolean narrowTo(int start, int end) {
        if (largeFile || hugeFile || logView.filtered()) {
            return false;
        }
        // Offsets arrive in *area* coordinates (callers read them from the selection), which while
        // already narrowed are relative to the region — so rebase them onto the document before widening,
        // or re-narrowing would silently measure the new region from the top of the file instead.
        int base = narrowStart();
        if (isNarrowed()) {
            widenInternal(); // re-narrow from the whole document: narrowing does not nest, as in Emacs
        }
        String full = area.getText();
        int s = Math.max(0, Math.min(start + base, full.length()));
        int e = Math.max(0, Math.min(end + base, full.length()));
        if (s >= e) {
            return false;
        }
        int caret = area.getCaretPosition();
        int caret2 = area2 == null ? 0 : area2.getCaretPosition();
        narrowPrefix = full.substring(0, s);
        narrowSuffix = full.substring(e);
        boolean hadHistory = hasUndoHistory();
        LineMarks.narrow(s, e, () -> area.replaceText(full.substring(s, e)), bookmarks, breakpoints, notes, gitLines);
        forgetHistoryAtNarrowBoundary(hadHistory);
        area.moveTo(Math.max(0, Math.min(caret - s, area.getLength())));
        area.requestFollowCaret();
        moveSplitCaret(caret2 - s);
        onNarrowChanged.run();
        return true;
    }

    public void setOnNarrowChanged(Runnable callback) {
        this.onNarrowChanged = callback == null ? () -> {} : callback;
    }

    /** Emacs {@code widen}: restores access to the whole document. No-op when not narrowed. */
    public void widen() {
        if (!isNarrowed()) {
            return;
        }
        widenInternal();
        onNarrowChanged.run();
    }

    private void widenInternal() {
        String prefix = narrowPrefix;
        String visible = area.getText();
        String suffix = narrowSuffix;
        int caret = area.getCaretPosition();
        int caret2 = area2 == null ? 0 : area2.getCaretPosition();
        narrowPrefix = null; // cleared first: replaceText fires the dirty listener, which reads getContent()
        narrowSuffix = null;
        boolean hadHistory = hasUndoHistory();
        LineMarks.widen(() -> area.replaceText(prefix + visible + suffix), bookmarks, breakpoints, notes, gitLines);
        forgetHistoryAtNarrowBoundary(hadHistory);
        area.moveTo(Math.min(prefix.length() + caret, area.getLength()));
        area.requestFollowCaret();
        moveSplitCaret(prefix.length() + caret2);
    }

    /** Puts the split's second caret back after the document was replaced under it (narrowing, widening). */
    private void moveSplitCaret(int to) {
        if (area2 != null) {
            area2.moveTo(Math.clamp(to, 0, area2.getLength()));
            area2.requestFollowCaret();
        }
    }

    /** Something an undo or an Undo History restore could bring back (more than the load's own baseline). */
    private boolean hasUndoHistory() {
        return area.isUndoAvailable() || area.isRedoAvailable() || undoHistory.size() > 1;
    }

    /** Whether a narrow/widen swap cleared a non-empty undo history since the last call: the UI says so. */
    public boolean takeHistoryDropped() {
        boolean dropped = historyDropped;
        historyDropped = false;
        return dropped;
    }

    private boolean historyDropped;

    /** The undo stack and the Undo History checkpoints: neither may be replayed across the boundary. */
    private void forgetHistoryAtNarrowBoundary(boolean hadHistory) {
        historyDropped |= hadHistory;
        area.getUndoManager().forgetHistory();
        undoHistory.clear();
        if (onUndoHistoryChanged != null) {
            onUndoHistoryChanged.run();
        }
    }

    /**
     * Replaces the entire document, widening first. Every caller that computes a replacement from
     * {@link #getContent()} — a find-and-replace across files, a local-history restore, a diff apply, a
     * reload from disk — must come through here or {@link #setContent}: writing whole-document text into a
     * narrowed area would leave the held-aside text alongside it and duplicate the file.
     */
    public void replaceWholeDocument(String text) {
        widen();
        Runnable refilter = logView.suspendFilter(false);
        replaceVisibleText(area, text);
        refilter.run();
    }

    /**
     * Makes the accessible text {@code text} as ONE undo step that records only the span that differs (see
     * {@link WholeDocumentEdit}) — nothing at all when it is already equal. The caret ends at the document
     * end, where replacing everything left it; callers that want it elsewhere move it afterwards.
     */
    public void replaceVisibleText(CodeArea view, String text) {
        WholeDocumentEdit edit = WholeDocumentEdit.between(documentTextSnapshot(), LineEndings.toLf(text));
        if (edit != null) {
            preventUndoMerge();
            view.replaceText(edit.start(), edit.end(), edit.replacement());
            view.moveTo(view.getLength());
            preventUndoMerge();
        }
    }

    /** Keeps a programmatic mutation (or a command's edit) separate from adjacent typing (both views share
     *  one undo history). */
    public void preventUndoMerge() {
        if (!largeFile) {
            area.getUndoManager().preventMerge();
        }
    }

    /** The accessible portion — the narrowed region, or the whole document when not narrowed. */
    public String getVisibleContent() {
        return documentTextSnapshot();
    }

    /** Length of {@link #getContent()} without building it — the per-keystroke dirty-check gate. */
    private int contentLength() {
        if (logView.filtered()) {
            return logView.fullLength();
        }
        return narrowPrefix == null
                ? area.getLength()
                : narrowPrefix.length() + area.getLength() + narrowSuffix.length();
    }

    public BooleanProperty dirtyProperty() {
        return dirty;
    }

    /** Exact: an edit that left a dirty buffer at its saved length is compared here if it has not been yet. */
    public boolean isDirty() {
        resolveDirty();
        return dirtyUnresolved ? differsFromSaved() : dirty.get(); // off the FX thread: answer, publish later
    }

    /** True while {@link #dirty} is carried over an edit that may have restored the saved text. */
    private boolean dirtyUnresolved;

    /**
     * The per-edit dirty check. A length (or line-ending) difference decides without reading the text. At
     * the saved length the text has to be compared, which builds the whole document: a clean buffer does
     * that at once — it must never show as modified when it is not — but an already-dirty one stays dirty
     * and is compared when the edit settles (or when {@link #isDirty()} is asked), so a run of same-length
     * edits (move line up/down, overwrite, transpose) no longer copies the document on every one of them.
     */
    private void dirtyAfterEdit() {
        dirtyUnresolved = dirty.get()
                && !forcedDirty
                && lineEnding.equals(cleanLineEnding)
                && contentLength() == cleanText.length();
        if (!dirtyUnresolved) {
            dirty.set(differsFromSaved());
        }
    }

    private void resolveDirty() {
        if (dirtyUnresolved && Platform.isFxApplicationThread()) { // the property is only set on its thread
            dirtyUnresolved = false;
            dirty.set(differsFromSaved());
        }
    }

    /** Marks the current content as the saved baseline (after load/save); clears the dirty flag. */
    public void markClean() {
        cleanText = getContent(); // the whole document, so narrowing never fakes a dirty flag
        cleanLineEnding = lineEnding;
        forcedDirty = false;
        dirty.set(false);
    }

    /** Whether the buffer differs from what is on disk: its text, its line ending, or a forced flag. */
    private boolean differsFromSaved() {
        return forcedDirty
                || !lineEnding.equals(cleanLineEnding)
                || (cleanText != null
                        && (contentLength() != cleanText.length()
                                || !getContent().equals(cleanText)));
    }

    /** Marks content as not durably saved, even when it still equals its in-memory baseline. */
    public void markUnsaved() {
        forcedDirty = true;
        dirty.set(true);
    }

    /**
     * Acknowledges exactly what an asynchronous save wrote — its text and its line ending — so an edit or a
     * line-ending conversion made while the write was in flight is still unsaved, and undoing either is clean.
     */
    public void acknowledgeSavedContent(String savedContent, String savedLineEnding) {
        cleanText = savedContent == null ? "" : savedContent;
        boolean current = savedLineEnding == null || savedLineEnding.equals(getLineEnding());
        cleanLineEnding = current ? lineEnding : savedLineEnding;
        forcedDirty = !current && eolOverride != null; // a rule that arrived mid-save: no converting back to it
        dirty.set(differsFromSaved());
        gitLines.savedWithPendingEdits(); // a no-op when that left the buffer clean
    }

    public boolean isDisposed() {
        return disposed;
    }

    public String getTitle() {
        if (path != null) {
            return path.getFileName().toString();
        }
        return displayName != null ? displayName : "untitled";
    }

    /** Named definitions (functions, types, sections, tags…) from the last tokenization. */
    public List<TextMateHighlighter.Symbol> symbols() {
        return symbols;
    }

    /**
     * Whether this buffer is syntax-highlighted (a grammar is loaded and it isn't in large-file mode) — the
     * gate for attaching a highlighted-HTML clipboard flavor on copy. The copy path reads the area's
     * <em>already-computed</em> style spans ({@code area.getStyleSpans(from, to)}) rather than re-tokenizing:
     * tm4e grammars are not thread-safe, and tokenizing on the FX thread would race the background
     * highlighters against the shared grammar.
     */
    public boolean hasHighlighting() {
        return grammar != null && !largeFile;
    }

    /** Sets a callback invoked (on the FX thread) whenever {@link #symbols()} changes. */
    public void setOnSymbolsChanged(Runnable callback) {
        this.onSymbolsChanged = callback == null ? () -> {} : callback;
    }

    /** Forces the next {@link #applyHighlighting()} to re-tokenize the whole document (e.g. after a
     *  language/grammar change, where no text change occurred). */
    private void invalidateHighlighting() {
        highlightDirty.invalidate();
        highlightLines = null;
    }

    /** Forgets the per-line tokenizer state and the structure symbols: no grammar pass owns the document. */
    private void dropHighlightState() {
        highlightLines = null;
        if (!symbols.isEmpty()) {
            symbols = List.of();
            onSymbolsChanged.run();
        }
    }

    /** Applies per-column rainbow CSV coloring over the whole document. The scan and span construction run
     *  off the FX thread; only the guarded style application returns to it. */
    private void applyCsvRainbow() {
        dropHighlightState();
        String text = documentTextSnapshot();
        int len = text.length();
        if (len == 0) {
            return;
        }
        styled = true;
        long version = docVersion;
        long gen = ++highlightGen;
        HIGHLIGHT_POOL.execute(() -> {
            char delim = com.editora.csv.CsvParser.detectDelimiter(text);
            StyleSpans<Collection<String>> spans = CsvRainbow.buildSpans(text, delim, CsvRainbow.COLORS);
            Platform.runLater(() -> {
                if (gen != highlightGen || version != docVersion || spans.length() != area.getLength()) {
                    return;
                }
                setStyleSpansPreservingScroll(0, spans);
                scheduleBraceMatch();
            });
        });
    }

    /**
     * Applies style spans without letting the viewport jump.
     *
     * <p>{@code setStyleSpans} re-runs the document update and can drop the virtual flow's scroll to the top:
     * measured as {@code estimatedScrollY} going 184 → 0 on the first highlight pass after a session restore,
     * a couple of pulses after the file had already painted at its saved caret. That is what showed as a
     * restored file jumping to line 1 on open. Restoring the scroll only when it actually collapsed to the
     * top keeps this inert for every normal re-highlight (typing, scrolling), where the flow doesn't reset.
     *
     * <p>What is put back is the first visible <em>line</em>, not the pixel offset: right after a whole-document
     * replace (a reload from disk) the offset is an estimate over unmeasured cells, and re-applying it once the
     * heights are known landed a hundred lines away from where the view had been.
     */
    private void setStyleSpansPreservingScroll(int from, StyleSpans<Collection<String>> spans) {
        Double before = area.estimatedScrollYProperty().getValue();
        int top = before != null && before > 1 ? ScrollAnchor.firstVisibleLine(area) : -1;
        area.setStyleSpans(from, spans);
        Double after = area.estimatedScrollYProperty().getValue();
        if (before != null && before > 1 && (after == null || after <= 1)) {
            ScrollAnchor.restore(area, top, before);
        }
    }

    private void applyHighlighting() {
        if (disposed) {
            return; // a closed buffer must not dispatch new passes (see the disposed field)
        }
        if (largeFile) {
            dropHighlightState(); // large-file mode: leave the document as plain text
            return;
        }
        if (csvRainbow && isCsv() && !heavyFile) {
            applyCsvRainbow();
            return;
        }
        if (grammar == null) {
            // No grammar for this file type, so nothing to compute — and nothing to clear either, except
            // once when a grammar or CSV pass had styled the document, and otherwise in the text typed
            // since the last pause where it took on the style of its neighbour (a matched brace). Clearing
            // the whole document at every pause restyled it for nothing and wiped the brace match.
            dropHighlightState();
            int length = area.getLength();
            int from = styled ? 0 : highlightDirty.isClean() ? length : Math.min(highlightDirty.start(), length);
            int to = styled ? length : Math.min(highlightDirty.end(), length);
            int[] matched = styled ? null : completionActions.braceMatch; // the live pair is not a leftover
            if (styled) {
                styled = false;
                highlightGen++; // a pass still in flight must not style the document after this
            }
            highlightDirty.applied();
            if (from < to && HighlightPass.anyStyled(area.getStyleSpans(from, to), from, matched)) {
                area.clearStyle(from, to);
                scheduleBraceMatch();
            }
            return;
        }
        // Tokenizing is O(lines), so a pass covers only the range edited since the last one applied and
        // stops where the grammar state re-converges (see HighlightPass). The work runs on a background
        // thread — tokenizing, the semantic and bracket overlays, the spliced per-line state — and the
        // result is applied only if no newer pass was dispatched and the text is still the snapshot's.
        styled = true;
        java.util.List<SemanticToken> overlay = semanticOverlay();
        HighlightPass.Request request = new HighlightPass.Request(
                documentTextSnapshot(),
                grammar,
                highlightLines,
                symbols,
                highlightDirty.isClean() ? -1 : highlightDirty.start(),
                highlightDirty.end(),
                bracketColors,
                overlay);
        long version = docVersion;
        long gen = ++highlightGen;
        HighlightPass.submit(HIGHLIGHT_POOL, grammar, () -> {
            HighlightPass.Result r;
            try {
                // The cancel check lets a superseded pass stop at the next line instead of finishing a
                // doomed multi-MB tokenize while holding the shared grammar monitor (see analyzeFrom).
                r = HighlightPass.run(request, () -> gen != highlightGen);
            } catch (Exception | LinkageError e) {
                return; // never let a grammar/engine fault kill the highlighter thread
            }
            if (r == null) {
                return;
            }
            Platform.runLater(() -> {
                // Superseded, or the text changed since the snapshot: what this pass owed stays in
                // highlightDirty, so the next one covers it.
                if (gen != highlightGen || version != docVersion) {
                    return;
                }
                highlightDirty.applied();
                highlightLines = r.lines();
                if (r.spans() != null) {
                    setStyleSpansPreservingScroll(r.fromOffset(), r.spans());
                    scheduleBraceMatch(); // re-apply the match highlight the spans just overwrote
                }
                if (r.symbolsChanged()) {
                    symbols = r.symbols();
                    onSymbolsChanged.run();
                }
                if (semanticActive && semanticStale) {
                    semanticDirty.include(r.fromOffset(), r.fromOffset() + r.length()); // overlay gone here
                } else if (overlay != semanticOverlay() && r.spans() != null) {
                    // Tokens arrived while this pass ran without them: restyle its range with the overlay.
                    highlightDirty.include(r.fromOffset(), r.fromOffset() + r.length());
                    applyHighlighting();
                }
            });
        });
    }
}
