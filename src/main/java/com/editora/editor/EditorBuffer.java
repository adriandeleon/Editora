package com.editora.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
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
import javafx.scene.shape.Line;
import javafx.scene.text.Font;

import com.editora.completion.Completion;
import com.editora.completion.CompletionEngine;
import com.editora.completion.CompletionProvider;
import com.editora.diagram.DiagramKind;
import com.editora.editops.AutoClose;
import com.editora.editops.BraceMatcher;
import com.editora.editops.Commenter;
import com.editora.editops.Indenter;
import com.editora.editops.LineIndent;
import com.editora.logviewer.LogFilter;
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
import com.editora.snippet.SnippetSession;
import com.editora.snippet.VariableResolver;
import com.editora.structured.StructuredParser;
import com.editora.structured.XmlParser;
import com.editora.typst.TypstMarkup;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.multi.MultiCaretController;
import org.fxmisc.richtext.util.UndoUtils;
import org.fxmisc.undo.UndoManager;
import org.fxmisc.undo.UndoManagerFactory;
import org.reactfx.Subscription;

import static com.editora.i18n.Messages.tr;

/** A single open document: a RichTextFX {@link CodeArea} plus its backing file, language, and dirty state. */
public class EditorBuffer implements TabContent {

    private final CodeArea area = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(area);
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);
    /** The last saved/loaded content; the buffer is dirty only when the text differs from this. */
    private String cleanText = "";
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
    private Label viewModeNote;
    /** When true, a non-writable-on-disk file offers "Edit as Administrator" instead of a dead-end note. */
    private boolean adminEditAvailable;

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

    private Minimap minimap2;
    private Split split = Split.NONE;

    /** Multiple cursors + Alt+drag column/box selection (RichTextFX fork add-on). Installed on {@link #area}
     *  (and {@link #area2} when split) while {@link #multiCaretEnabled}; transparent with one caret. */
    private boolean multiCaretEnabled;

    private MultiCaretController<?, ?, ?> multiCaret;
    private MultiCaretController<?, ?, ?> multiCaret2;

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
    /** While a log filter is active, the complete unfiltered text (the area shows only matching lines). */
    private String logFullText;

    private boolean logFiltered;
    private LogLevel logMinLevel;
    private java.util.regex.Pattern logRegex;
    /** Inherited level at the end of {@link #logFullText}, so an appended chunk filters with the right carry. */
    private LogLevel logCarry;
    /** While following ({@code tail -f}), each append auto-scrolls to the bottom. */
    private boolean logFollowing;
    /** Max characters kept in a following log buffer before the oldest lines are trimmed (bounds memory). */
    private static final int LOG_FOLLOW_CAP = 12 * 1024 * 1024;
    /** Fired from the debounced edit pulse while this is an HTML buffer (drives HTML live-preview reload). */
    private Runnable htmlPreviewDirtyListener;
    /** Active debounced subscription driving live preview re-render (null when not previewing). */
    private Subscription previewSub;
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
    /** The most recently focused view (primary or secondary); drives "active area" for commands. */
    private CodeArea focusedArea = area;
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
    private SnippetSession snippetSession;
    /** Resolves (language, prefix) → snippet for Tab-expand; injected by the controller (default: none). */
    private java.util.function.BiFunction<String, String, Snippet> snippetProvider = (lang, prefix) -> null;
    /** Resolves completions for the typed prefix; injected by the controller (default: none). */
    private CompletionProvider completionProvider = (s, d, p, sp, prose) -> List.of();
    /** When false the whole autocomplete feature is inert (master Settings toggle). */
    private boolean autocompleteEnabled = true;
    /** Per-source autocomplete toggles (gated by {@link #autocompleteEnabled}). */
    private boolean autocompleteProse = true;

    private boolean autocompleteSnippets = true;
    private boolean autocompleteMermaid = true;
    /** The caret-anchored completion dropdown (lazily created). */
    private CompletionPopup completionPopup;
    /** Injected async LSP completion source (code buffers); generation guard for stale async results. */
    private java.util.function.BiConsumer<int[], java.util.function.Consumer<java.util.List<Completion>>>
            lspCompletionProvider;

    private long completionGen;
    /** The view the completion popup is currently driven by (for click-accept routing). */
    private CodeArea completionArea;

    /** The IntelliJ-style documentation side-popup (lazily created) + its lazy resolver/state. */
    private CompletionDocPopup docPopup;

    private java.util.function.BiConsumer<Object, java.util.function.Consumer<String>> completionDocResolver;
    private boolean completionDocEnabled = true; // Settings: auto-show docs beside the list
    private boolean docPopupActive; // per-open session flag (reset on each popup open; Ctrl+Q toggles)
    private long docGen; // generation guard for the debounced/async doc fetch
    private javafx.animation.PauseTransition docDebounce;
    /**
     * Monotonic count of text changes to this buffer's document — a cheap "has the document moved under me"
     * stamp for the async/deferred completion paths (a caret offset alone can't tell: an edit can land the
     * caret back on the same offset). Bumped for edits made in either view (they share the document).
     */
    private long docVersion;
    /** Per-buffer Emacs mark ring (session-only); shifted through edits by the subscription in the ctor. */
    private final com.editora.editops.MarkRing markRing = new com.editora.editops.MarkRing();
    /**
     * The {@link #docVersion} right after we programmatically accepted a completion, so the auto-trigger
     * that the accept's own edit schedules is suppressed — but a real edit after it (which bumps the
     * version) is not. A plain boolean cleared on the next pulse would be useless here: the debounced
     * trigger it must gate is ~280 ms away.
     */
    private long suppressCompletionAtVersion = -1;
    /**
     * The edit the last completion accept made, measured around it, so the {@code additionalTextEdits} a later
     * {@code completionItem/resolve} returns — positions the server computed against the document as it stood
     * <em>before</em> that accept — can be translated into the document as it stands now. See
     * {@link LspEditShift}.
     */
    private LspEditShift.Change pendingCompletionShift;
    /** Inline "ghost text" suggestion (prose buffers): a single muted suffix drawn after the caret. */
    private Label ghostLabel;

    /** AI inline completion (any buffer): provider + gate + stale-result guard (see ui.AiCoordinator). */
    private AiCompletionProvider aiCompletionProvider;

    private boolean aiCompletionEnabled;
    private long aiCompletionGen;

    private String ghostSuffix;
    private CodeArea ghostArea;
    /** The two document offsets currently carrying the {@code brace-match} style, or null. */
    private int[] braceMatch;
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

    private final Line columnRuler = new Line();
    private final Minimap minimap = new Minimap(area);
    private final WhitespaceOverlay whitespace = new WhitespaceOverlay(area);
    private final SpellCheckOverlay spellOverlay = new SpellCheckOverlay(area);
    private LogHighlightOverlay logOverlay; // lazily attached on first activation — see logOverlay()
    private final InlineValuesOverlay inlineValues = new InlineValuesOverlay(area);
    /** Per-line blame for the IntelliJ-style gutter "Annotate" column; null = blame off. */
    private java.util.List<BlameInfo> blameLines;
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
    /** Whether the Run affordance is enabled at all (gated by the LSP feature setting). */
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
    /** Whether this file is runnable (a Java 25 compact source file, a Python script, or — when the Bash
     *  LSP is enabled — a shell script) — drives the gutter Run glyph + the Run tool window. */
    private boolean runnable;
    /** 0-based line the gutter Run glyph sits on (a compact file's {@code main}, a Python {@code __main__}
     *  guard, else the first line), or -1 when not runnable. */
    private int runLine = -1;
    /** Fired (FX thread) when the runnable status flips, so the controller refreshes the Run button. */
    private Runnable onRunnableChanged = () -> {};

    private SearchHighlightOverlay searchOverlay; // lazily attached — see searchOverlay()
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

    private javafx.scene.control.Tooltip lspTooltip;
    /** Message currently shown by {@link #lspTooltip} — skips a re-{@code show()} (flicker) on each move. */
    private String lspTooltipText;

    private final FoldManager folds = new FoldManager(area);
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
    private final javafx.scene.control.Tooltip noteTip = new javafx.scene.control.Tooltip();

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
    /** Whether this buffer's server advertises whole-document formatting (refreshed when it reports ready). */
    private boolean lspFormatAvailable;
    /** Whether this buffer's server advertises range formatting — enables Tab to re-indent the line. */
    private boolean lspRangeFormatAvailable;
    /** Injected range-formatter (the controller wires it to the LSP manager); null = none. */
    private LspRangeFormatter lspRangeFormatter;
    /** Generation guard so a stale async re-indent result can't clobber a later edit. */
    private long reindentGen;
    /** Chars of context captured before/after a note's selection (for re-anchoring). */
    private static final int CONTEXT_CHARS = 40;
    /** Git gutter change bars: 0-based line → CSS class ({@code git-added}/{@code git-modified}/
     *  {@code git-deleted}); {@code null} when this buffer isn't under Git change tracking. */
    private java.util.Map<Integer, String> changeBars;
    /** Per-line hunk text (the {@code -}/{@code +} diff) shown as a tooltip on the change bar; may be null. */
    private java.util.Map<Integer, String> changeHunks;

    private Path path;
    /** Suggested name for a still-unsaved buffer (e.g. from {@code --new-file=foo.txt}); drives the tab
     *  title and extension-based highlighting while {@link #path} stays null (so Save prompts Save-As). */
    private String displayName;
    /** Last-known on-disk modified time (epoch millis) and size, to detect external changes; -1 = unknown. */
    private long diskModifiedMillis = -1;

    private long diskSize = -1;
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
    private long highlightGen;
    /** Bumped on every language/grammar change (FX thread only); drops a stale deferred grammar load. */
    private long languageGen;
    /** Per-line grammar end-states from the last tokenization (FX-thread confined), so an edit can
     *  re-highlight only from the changed line forward instead of the whole document. */
    private final java.util.ArrayList<org.eclipse.tm4e.core.grammar.IStateStack> lineStates =
            new java.util.ArrayList<>();
    /** Earliest line changed since the last highlight (0 = re-highlight the whole document). */
    private int dirtyFromLine;
    /** Named definitions from the last tokenization (FX-thread confined); drives the Structure view. */
    private List<TextMateHighlighter.Symbol> symbols = List.of();
    /** Notified (on the FX thread) after {@link #symbols} is refreshed. */
    private Runnable onSymbolsChanged = () -> {};

    private String fontFamily = "monospace";
    private int fontSize = 14;
    /** Current-line highlight fill; varies per editor theme (see {@link #setLineHighlightColor}). */
    private Color lineHighlightColor = Color.web("#dfe7f0");
    /** Minimap block + viewport colors; vary per editor theme (see {@link #setMinimapColors}). */
    private Color minimapText = Color.web("#9aa5b1");

    private Color minimapViewport = Color.web("#0969da", 0.14);
    /** Visual tab width (columns); applied to the minimap and persisted via Settings. */
    private int tabSize = 4;
    // EditorConfig overrides (null = no override → fall back to detection/global). See com.editora.editorconfig.
    private Boolean indentInsertSpacesOverride;
    private Integer indentSizeOverride;
    private String eolOverride; // "LF"/"CRLF" — effective line ending (EditorConfig or a manual choice)
    private Integer rulerColumnOverride; // null = default 80; EditorConfigProperties.OFF = hide
    private String detectedCharset = com.editora.editorconfig.EditorConfigCharset.UTF_8;
    private String charsetOverride; // EditorConfig charset to write; null = keep detected
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

    /** Max undo entries kept per view; caps undo memory (RichTextFX defaults to unlimited). */
    private static final int UNDO_HISTORY = 300;

    public EditorBuffer() {
        refreshGutter();
        // Gutter click: route to the injectable handler (the controller adds, or confirms a removal);
        // defaults to a plain toggle so the editor works standalone (and in tests).
        folds.setBookmarkHooks(bookmarks::isBookmarked);
        // Personal-Notes markers are drawn inline at each note's start by noteOverlay (no gutter slot).
        notes.setOnLinesRepaint(lines -> Platform.runLater(() -> lines.forEach(this::refreshGutterLine)));
        // Git change bars: the slot is reserved only while tracking is on (changeBars != null); the
        // per-line hunk text feeds a hover tooltip on the bar.
        folds.setChangeHook(
                () -> changeBars != null,
                line -> changeBars == null ? null : changeBars.get(line),
                line -> changeHunks == null ? null : changeHunks.get(line));
        // Gutter Run glyph: reserved for a runnable file — one entry line for a script, or one per
        // request for a .http file.
        folds.setRunHooks(
                () -> runnable,
                this::isRunGlyphLine,
                this::onRunGlyph,
                line -> testLines.containsKey(line) ? "test-run-marker" : null,
                this::runGlyphTooltip);
        // Gutter breakpoint strip: reserved only while debugging is enabled; click toggles a breakpoint.
        folds.setBreakpointHooks(
                () -> debugEnabled,
                breakpoints::isBreakpoint,
                this::breakpointStyleClass,
                line -> gutterBreakpointClick.accept(this, line));
        // Gutter blame "Annotate" column (leftmost): reserved only while blame is on; the per-line
        // author/date/heatmap come from the controller-supplied list, click shows that line's commit.
        folds.setBlameHooks(
                () -> blameLines != null,
                this::blameInfoAt,
                () -> blameColumnWidth,
                line -> gutterBlameClick.accept(this, line));
        breakpoints.setOnLinesRepaint(lines -> Platform.runLater(() -> lines.forEach(this::refreshGutterLine)));
        addViewModePaging(area); // Space/Backspace = page down/up while in read-only View mode
        addSnippetKeys(area); // Tab expands/cycles snippets (else falls through to indent)
        addAutoClose(area); // auto-close ()[]{} and quotes (before auto-indent so it sees the keystroke first)
        addAutoIndent(area); // Enter auto-indents; closers de-indent (per-language smart indent)
        addCompletionKeys(area); // registered last → runs first, so popup nav/accept beats Tab/Enter
        installCompletionTrigger(area);
        // When an edit shifts bookmarks, repaint the affected lines' gutter markers after the edit's own
        // graphic rebuild settles (deferred to the next pulse), so the moved marker follows its line.
        bookmarks.setOnLinesRepaint(lines -> Platform.runLater(() -> lines.forEach(this::refreshGutterLine)));
        area.getStyleClass().add("editor-area");
        area.setWrapText(false);
        area.setUndoManager(boundedUndoManager(area));
        // Word/line-level undo: end the current undo group after an edit that finishes a word or line, so
        // one C-z undoes a word/line rather than the whole typing burst (the idle break is built into the
        // manager via UndoMerge.PAUSE). Subscribe AFTER setUndoManager so the manager records the change
        // first; both views share the document, so this one subscription covers edits made in either.
        area.plainTextChanges().subscribe(c -> breakUndoGroupIfBoundary(c.getInserted(), c.getRemoved()));
        // Document-change stamp for the deferred/async completion paths. On plainTextChanges (emitted
        // synchronously by the edit) rather than the debounced stream, so a change is visible the instant it
        // happens; both views share the document, so this one subscription covers either. One long++ per
        // edit — off the per-keystroke cost budget.
        area.plainTextChanges().subscribe(c -> docVersion++);
        // Mark ring: shift stored offsets across every edit so a mark still points at its text after
        // typing (one cheap pass over <=16 ints per edit; skipped when the ring is empty, the common case).
        area.plainTextChanges()
                .subscribe(c -> markRing.shift(
                        c.getPosition(),
                        c.getRemoved().length(),
                        c.getInserted().length()));
        area.setLineHighlighterFill(lineHighlightColor);
        // Track the earliest changed line immediately (the debounced stream below drops intermediate
        // emissions, so the dirty start must be accumulated here), then re-highlight after a pause.
        area.multiPlainChanges().subscribe(changes -> {
            for (var change : changes) {
                int line = area.offsetToPosition(
                                change.getPosition(), org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                        .getMajor();
                dirtyFromLine = Math.min(dirtyFromLine, line);
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
        });
        area.multiPlainChanges().successionEnds(Duration.ofMillis(150)).subscribe(ignore -> {
            applyHighlighting();
            recomputeRun(); // re-evaluate the Run glyph when a top-level main / __main__ appears/leaves
        });
        // Live Mermaid linting: debounced maid run for .mmd buffers (only while enabled + maid detected).
        area.multiPlainChanges().successionEnds(Duration.ofMillis(450)).subscribe(ignore -> {
            scheduleMermaidLint();
            scheduleMarkdownLint();
        });
        // TODO/highlight patterns: debounced re-scan for the in-editor highlight (no-op when off / huge file).
        area.multiPlainChanges().successionEnds(Duration.ofMillis(300)).subscribe(ignore -> refreshTodoMarks());
        // Undo History tool window: snapshot the document when editing settles (one checkpoint per burst).
        area.multiPlainChanges().successionEnds(UndoMerge.PAUSE).subscribe(ignore -> captureUndoCheckpoint());
        // LSP document sync: debounced didChange notification (only while the buffer is LSP-managed) +
        // a debounced pull-diagnostics request (no-op unless the server uses the pull model). The pull is
        // here, not in lspChangeListener, so it fires once per debounce — not on every completion keystroke
        // (requestLspCompletion flushes lspChangeListener directly to send fresh text before completing).
        area.multiPlainChanges().successionEnds(Duration.ofMillis(300)).subscribe(ignore -> {
            if (lspActive) {
                sendLspChange();
            }
            if (lspActive && lspDiagnosticsRequester != null) {
                lspDiagnosticsRequester.run();
            }
            if (semanticActive && semanticTokensRequester != null) {
                semanticTokensRequester.run();
            }
        });
        // After scrolling settles, re-request semantic tokens for the now-visible region (debounced so a
        // drag-scroll doesn't fire a request per frame). Inert unless semantic highlighting is active.
        semanticScrollDebounce.setOnFinished(e -> {
            if (semanticActive && semanticTokensRequester != null) {
                semanticTokensRequester.run();
            }
        });
        // HTML live preview: debounced reload pulse for HTML buffers (only while a browser preview is open).
        area.multiPlainChanges().successionEnds(Duration.ofMillis(250)).subscribe(ignore -> {
            if (isHtml() && htmlPreviewDirtyListener != null) {
                htmlPreviewDirtyListener.run();
            }
        });
        // Dirty only when the content differs from the last saved/loaded text, so reverting an edit
        // (undo or manual) clears the marker. Driven off plainTextChanges (not textProperty): subscribing
        // to textProperty would force RichTextFX to materialize the whole document String on every
        // keystroke — O(n) allocation per char on a very large single buffer (e.g. minified JS on one
        // line, past the line-count heavy-file tier). The cheap getLength() check gates the full-text
        // compare so area.getText() is only built in the rare near-clean state, never while typing.
        area.plainTextChanges()
                .subscribe(c -> dirty.set(
                        contentLength() != cleanText.length() || !getContent().equals(cleanText)));
        // Auto-rename tag: mirror a tag-name edit onto the paired open/close tag (html/xml only —
        // the handler's first checks are two cheap boolean/string compares for every other buffer).
        area.plainTextChanges().subscribe(this::maybeMirrorTagRename);
        // Auto-fill: break the line at a word boundary when it grows past the fill column (off by default,
        // so the very first check short-circuits for every buffer that hasn't turned it on).
        area.plainTextChanges().subscribe(this::maybeAutoFill);
        area.caretPositionProperty().addListener((obs, old, now) -> {
            resetGoalColumn();
            scheduleBraceMatch();
        });
        area.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                focusedArea = area;
            }
        });
        installContextMenu();
        installFormatBarListeners(area);
        installSplitScrollSync();
        installOverlays();
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
        });
        a.estimatedScrollYProperty().addListener((obs, old, now) -> {
            scheduleFormatBar();
            scheduleAiActionsBar();
        });
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
        if (formatBarUpdatePending) {
            return;
        }
        formatBarUpdatePending = true;
        Platform.runLater(this::updateFormatBar);
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
        if (aiActionsBarUpdatePending) {
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
        Commenter.Edit edit = Commenter.toggle(
                a.getText(), a.getSelection().getStart(), a.getSelection().getEnd(), Commenter.styleFor(language));
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
        String csv = fromSelection ? sel : Clipboard.getSystemClipboard().getString();
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
            if (!updated.equals(doc)) {
                a.replaceText(updated); // whole-document, undoable
            }
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
        columnRuler.getStyleClass().add("column-ruler");
        columnRuler.setManaged(false);
        columnRuler.setMouseTransparent(true);
        columnRuler.setStartY(0);
        columnRuler.endYProperty().bind(root.heightProperty());
        columnRuler.setVisible(false);
        // Re-measure the ruler whenever the rendered text moves: horizontal scroll, and any layout
        // change (first paint, resize, vertical scroll, gutter widening). Always deferred via
        // runLater so we never query character bounds synchronously inside the layout pass — doing
        // that re-enters layout and blanks the editor.
        area.estimatedScrollXProperty().addListener((obs, old, now) -> scheduleRulerMeasure());
        area.viewportDirtyEvents().subscribe(ignore -> scheduleRulerMeasure());

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
        // The whitespace overlay shares the text rectangle with the scroll pane (and tracks it when
        // the minimap is toggled); it is mouse-transparent so clicks reach the editor.
        AnchorPane.setTopAnchor(whitespace, 0d);
        AnchorPane.setBottomAnchor(whitespace, 0d);
        AnchorPane.setLeftAnchor(whitespace, 0d);
        AnchorPane.setRightAnchor(whitespace, Minimap.WIDTH);
        // The spell-check overlay (red squiggles) shares the same text rectangle, mouse-transparent.
        AnchorPane.setTopAnchor(spellOverlay, 0d);
        AnchorPane.setBottomAnchor(spellOverlay, 0d);
        AnchorPane.setLeftAnchor(spellOverlay, 0d);
        AnchorPane.setRightAnchor(spellOverlay, Minimap.WIDTH);
        spellChecker = new SpellChecker(spellLanguage, spellUserWords);
        spellChecker.setUserWordsEnabled(spellUserWordsEnabled);
        spellChecker.setTechnicalWordsEnabled(spellTechnicalEnabled);
        spellOverlay.setChecker(spellChecker);
        spellOverlay.setProseMode(isProse());
        spellOverlay.setMarkdown(isMarkdown()); // skip fenced ``` code blocks from spell check
        installLintHover(area); // hover reads lintOverlay only while mermaid lint is active (lazily attached)
        AnchorPane.setTopAnchor(mdLintOverlay, 0d);
        AnchorPane.setBottomAnchor(mdLintOverlay, 0d);
        AnchorPane.setLeftAnchor(mdLintOverlay, 0d);
        AnchorPane.setRightAnchor(mdLintOverlay, Minimap.WIDTH);
        installMarkdownLintHover(area);
        installImageDrop(area);
        installLspHover(area); // hover reads lspOverlay only while LSP is active (lazily attached)
        // Inline debugger values share the text rectangle, mouse-transparent (active only while
        // execution is suspended in this file).
        AnchorPane.setTopAnchor(inlineValues, 0d);
        AnchorPane.setBottomAnchor(inlineValues, 0d);
        AnchorPane.setLeftAnchor(inlineValues, 0d);
        AnchorPane.setRightAnchor(inlineValues, Minimap.WIDTH);
        installDebugHover(area);
        AnchorPane.setTopAnchor(todoOverlay, 0d);
        AnchorPane.setBottomAnchor(todoOverlay, 0d);
        AnchorPane.setLeftAnchor(todoOverlay, 0d);
        AnchorPane.setRightAnchor(todoOverlay, Minimap.WIDTH);
        AnchorPane.setTopAnchor(noteOverlay, 0d);
        AnchorPane.setBottomAnchor(noteOverlay, 0d);
        AnchorPane.setLeftAnchor(noteOverlay, 0d);
        AnchorPane.setRightAnchor(noteOverlay, Minimap.WIDTH);
        noteOverlay.setSpans(notes::activeSpans);
        noteOverlay.setActive(true);
        installNoteHover();
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
    /** Supplies extra right-click items (plugin contributions), injected by the controller; null = none. */
    private java.util.function.Supplier<List<MenuItem>> menuContributor;

    private void installContextMenu() {
        contextMenu.getStyleClass().add("editor-context-menu");
        area.setOnContextMenuRequested(e -> {
            List<MenuItem> items = new java.util.ArrayList<>();
            // A JUnit test file runs its tests ("Run Tests", blue ▶ matching the gutter) rather than the
            // generic green "Run File"; anything else runnable (compact Java source / Python script) keeps it.
            com.editora.test.JavaTestScanner.TestTarget classTarget = testClassTarget();
            if (classTarget != null) {
                MenuItem runTests = new MenuItem(tr("editmenu.runTests"));
                runTests.setGraphic(FoldManager.runGlyph("test-run-marker")); // blue, matching the test gutter ▶
                runTests.setOnAction(ev -> testRunHandler.accept(classTarget));
                items.add(runTests);
                items.add(new SeparatorMenuItem());
            } else if (runnable && runHandler != null) {
                MenuItem run = new MenuItem(tr("command.file.run"));
                run.setGraphic(FoldManager.runGlyph()); // green play icon, matching the gutter glyph
                run.setOnAction(ev -> runHandler.run());
                items.add(run);
                items.add(new SeparatorMenuItem());
            }
            // LSP navigation (only when this buffer is served by a language server). Move the caret to the
            // right-clicked position first so go-to-definition/references/hover target that symbol.
            if (lspActive) {
                int clickOffset = clickOffsetAt(e.getX(), e.getY());
                items.addAll(lspMenuItems(clickOffset));
                items.add(new SeparatorMenuItem());
            }
            SpellHit hit = spellHitAt(e.getX(), e.getY());
            if (hit != null) {
                items.addAll(spellMenuItems(hit));
                items.add(new SeparatorMenuItem());
            }
            if (canFormatMarkdown()) {
                items.addAll(markdownMenuItems());
                items.add(new SeparatorMenuItem());
            } else if (isTypst() && isEditable()) {
                items.addAll(typstMenuItems());
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
            // Acts on the right-clicked line, not the caret line, matching the LSP items above.
            if (path != null) {
                items.add(new SeparatorMenuItem());
                int clickedLine = clickLineAt(e.getX(), e.getY());
                boolean marked = bookmarks.isBookmarked(clickedLine);
                MenuItem bookmark = new MenuItem(tr(marked ? "editmenu.removeBookmark" : "editmenu.addBookmark"));
                bookmark.setGraphic(MenuIcons.bookmark());
                bookmark.setOnAction(ev -> bookmarkToggleRequest.accept(this, clickedLine));
                items.add(bookmark);
            }
            if (path != null && notesEnabled) {
                items.add(new SeparatorMenuItem());
                boolean hasSelection = area.getSelection().getLength() > 0;
                MenuItem addNote = new MenuItem(tr(hasSelection ? "editmenu.addNoteSelection" : "editmenu.addNote"));
                addNote.setGraphic(MenuIcons.note());
                addNote.setOnAction(ev -> addNoteHandler.accept(this));
                items.add(addNote);
            }
            contextMenu.getItems().setAll(items);
            contextMenu.show(area, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // A left-click in the editor dismisses an open context menu. RichTextFX consumes the
        // mouse press before the popup's auto-hide fires, so close it explicitly. The event is
        // not consumed, so the click still positions the caret as usual.
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (contextMenu.isShowing() && e.getButton() == MouseButton.PRIMARY) {
                contextMenu.hide();
            }
        });
    }

    /** Injects a supplier of extra right-click menu items (plugin contributions); null clears it. */
    public void setMenuContributor(java.util.function.Supplier<List<MenuItem>> contributor) {
        this.menuContributor = contributor;
    }

    /** The document offset under a context-menu click (for caret-positioning LSP nav); caret if it misses. */
    private int clickOffsetAt(double x, double y) {
        try {
            return area.hit(x, y).getInsertionIndex();
        } catch (RuntimeException ex) {
            return area.getCaretPosition();
        }
    }

    /** The 0-based paragraph under a context-menu click (for the bookmark item); caret line if it misses. */
    private int clickLineAt(double x, double y) {
        try {
            return area.offsetToPosition(clickOffsetAt(x, y), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                    .getMajor();
        } catch (RuntimeException ex) {
            return area.getCurrentParagraph();
        }
    }

    /** Go to Definition / Find References / Show Documentation — each moves the caret to {@code offset}
     *  first so it targets the right-clicked symbol, then runs the controller-supplied action. */
    private List<MenuItem> lspMenuItems(int offset) {
        MenuItem def = new MenuItem(tr("command.lsp.gotoDefinition"));
        def.setGraphic(MenuIcons.gotoDefinition());
        def.setOnAction(e -> {
            area.moveTo(offset);
            lspGotoDefinitionAction.run();
        });
        MenuItem refs = new MenuItem(tr("command.lsp.findReferences"));
        refs.setGraphic(MenuIcons.find());
        refs.setOnAction(e -> {
            area.moveTo(offset);
            lspFindReferencesAction.run();
        });
        MenuItem hover = new MenuItem(tr("command.lsp.hover"));
        hover.setGraphic(MenuIcons.about());
        hover.setOnAction(e -> {
            area.moveTo(offset);
            lspHoverAction.run();
        });
        List<MenuItem> items = new java.util.ArrayList<>(List.of(def, refs, hover));
        if (lspFormatAvailable) {
            MenuItem format = new MenuItem(tr("command.lsp.formatDocument"));
            format.setGraphic(MenuIcons.code());
            format.setOnAction(e -> lspFormatAction.run()); // whole-document — no caret positioning needed
            items.add(format);
        }
        return items;
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
        // copy/paste commands. Without this, area.copy() only grabs the primary caret's row. A box selection
        // makes copy/cut available even when the primary selection is empty (extra carets carry the rest).
        boolean hasMulti = hasMultipleCarets();
        boolean canCopy = area.getSelection().getLength() > 0 || hasMulti;
        boolean editable = isEditable();
        boolean hasClipboardText = Clipboard.getSystemClipboard().hasString();
        MenuItem cut = new MenuItem(tr("editmenu.cut"));
        cut.setGraphic(MenuIcons.cut());
        cut.setOnAction(e -> {
            if (!multiCaretCut()) {
                area.cut();
            }
        });
        cut.setDisable(!canCopy || !editable);
        MenuItem copy = new MenuItem(tr("editmenu.copy"));
        copy.setGraphic(MenuIcons.copy());
        copy.setOnAction(e -> {
            if (!multiCaretCopy()) {
                area.copy();
            }
        });
        copy.setDisable(!canCopy);
        MenuItem paste = new MenuItem(tr("editmenu.paste"));
        paste.setGraphic(MenuIcons.paste());
        paste.setOnAction(e -> {
            if (!multiCaretPaste()) {
                area.paste();
            }
        });
        paste.setDisable(!hasClipboardText || !editable);
        MenuItem undo = new MenuItem(tr("editmenu.undo"));
        undo.setGraphic(MenuIcons.undo());
        undo.setOnAction(e -> area.undo());
        undo.setDisable(!area.isUndoAvailable());
        MenuItem redo = new MenuItem(tr("editmenu.redo"));
        redo.setGraphic(MenuIcons.redo());
        redo.setOnAction(e -> area.redo());
        redo.setDisable(!area.isRedoAvailable());
        MenuItem selectAll = new MenuItem(tr("editmenu.selectAll"));
        selectAll.setGraphic(MenuIcons.selectAll());
        selectAll.setOnAction(e -> area.selectAll());
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

    /** The misspelled word at editor coordinates {@code (x, y)}, or {@code null}. */
    private SpellHit spellHitAt(double x, double y) {
        if (!spellCheckOn || spellChecker == null || !spellChecker.ready() || largeFile) {
            return null;
        }
        int offset;
        try {
            offset = area.hit(x, y).getInsertionIndex();
        } catch (RuntimeException ex) {
            return null;
        }
        if (offset < 0 || offset > area.getLength()) {
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

    // --- Lazily-attached feature overlays --------------------------------------------------------------
    // These overlays are inert for most buffers (LSP off, not a diagram, not a log, never searched/ace-jumped),
    // so they are built + wired only on first activation rather than per buffer. Each is inserted just below a
    // fixed eager sibling to preserve the z-order of the original construction.

    /** Attaches {@code overlay} to the editor pane just below {@code below}, with the standard text-rect anchors. */
    private <T extends javafx.scene.layout.Region> T attachLazyOverlay(T overlay, javafx.scene.Node below) {
        AnchorPane.setTopAnchor(overlay, 0d);
        AnchorPane.setBottomAnchor(overlay, 0d);
        AnchorPane.setLeftAnchor(overlay, 0d);
        AnchorPane.setRightAnchor(overlay, Minimap.WIDTH);
        int idx = root.getChildren().indexOf(below);
        if (idx < 0) {
            idx = root.getChildren().indexOf(minimap); // fallback: just under the minimap
        }
        if (idx < 0) {
            root.getChildren().add(overlay);
        } else {
            root.getChildren().add(idx, overlay);
        }
        return overlay;
    }

    private SearchHighlightOverlay searchOverlay() {
        if (searchOverlay == null) {
            searchOverlay = attachLazyOverlay(new SearchHighlightOverlay(area), todoOverlay);
        }
        return searchOverlay;
    }

    private LogHighlightOverlay logOverlay() {
        if (logOverlay == null) {
            logOverlay = attachLazyOverlay(new LogHighlightOverlay(area), whitespace);
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
     * match). Pass an empty list (or call {@link #clearSearchMatches}) to remove the highlight.
     */
    public void setSearchMatches(java.util.List<int[]> matches, int activeIndex) {
        if (largeFile) {
            return;
        }
        searchOverlay().setMatches(matches, activeIndex);
    }

    /** Clears the find-match highlight overlay. */
    public void clearSearchMatches() {
        if (searchOverlay != null) {
            searchOverlay.setMatches(java.util.List.of(), -1);
        }
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

    /** Re-scans the buffer text and updates the highlight overlay + the scrollbar/minimap overview stripes;
     *  inert when off / no matcher / huge file. */
    private void refreshTodoMarks() {
        if (!todoEnabled || todoMatcher == null || largeFile) {
            todoOverlay.setMarks(java.util.List.of());
            todoStripe.setMarks(java.util.List.of());
            minimap.setTodoMarks(java.util.List.of());
            return;
        }
        java.util.List<TodoMark> marks = todoMatcher.match(area.getText());
        todoOverlay.setMarks(marks);
        todoStripe.setMarks(marks);
        minimap.setTodoMarks(marks);
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
        if (orientation == Split.NONE) {
            focusedArea = area;
        } else {
            this.markdownViewMode = MarkdownViewMode.EDITOR; // a code split supersedes the Markdown preview
            unsubscribePreview();
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

    /** Whether this buffer uses the self-scrolling tree host (structured, XML, crontab, or fstab) — shared surface. */
    private boolean hasTreePreview() {
        return hasStructuredPreview()
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
        mermaidValidator.accept(area.getText(), lintOverlay()::setDiagnostics);
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
        markdownLintValidator.accept(area.getText(), this::applyMarkdownLintDiagnostics);
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
                    webImageDropHandler.accept(img, url);
                    e.setDropCompleted(true);
                    e.consume();
                }
            }
        });
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
     *  Rust, PHP, Ruby, C/C++, HTML, CSS, Kotlin, Lua, Dockerfile, SQL, Terraform, TOML). Hardcoded here
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
            "typst");

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
    private void sendLspChange() {
        if (lspChangeListener == null || docVersion == lastLspSentVersion) {
            return;
        }
        lastLspSentVersion = docVersion;
        lspChangeListener.accept(area.getText());
    }

    /** Injects the debounced pull-diagnostics request (fired on the same pulse as didChange); null disables. */
    public void setLspDiagnosticsRequester(Runnable requester) {
        this.lspDiagnosticsRequester = requester;
    }

    /** Sets the completion trigger characters the server advertised (empty = none / not yet known). */
    public void setLspTriggerChars(java.util.Set<Character> chars) {
        this.lspTriggerChars = chars == null ? java.util.Set.of() : chars;
    }

    /** True if this file can be run (a Java 25 compact source file, a Python script, or a shell script
     *  when the Bash LSP is enabled). */
    public boolean isRunnable() {
        return runnable;
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

    /** Enables/disables the Run affordance (gated by the LSP feature). When off, the gutter Run glyph,
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
        int caret = area.getCurrentParagraph();
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
        com.editora.test.JavaTestScanner.TestTarget t = testLines.get(line);
        if (t == null) {
            return null; // the plain script/Makefile/.http ▶ keeps its untooltipped look
        }
        return t.methodName() == null
                ? tr("testrunner.gutter.runClass", com.editora.test.TestSourceLocator.simpleName(t.className()))
                : tr("testrunner.gutter.runMethod", t.methodName());
    }

    private void onRunGlyph(int line) {
        com.editora.test.JavaTestScanner.TestTarget testTarget = testLines.get(line);
        if (testTarget != null) {
            testRunHandler.accept(testTarget); // a JUnit class/method ▶ (never coincides with a compact-source main)
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

    /** Recomputes runnable status + the gutter entry line(s); refreshes the gutter and fires the callback. */
    private void recomputeRun() {
        maybeApplyShebang(); // an interpreter shebang can set the language before we gate the Run glyph
        String name = path != null ? path.getFileName().toString() : displayName;
        boolean eligible = runFeatureEnabled && !largeFile && area.getLength() <= COMPACT_SCAN_LIMIT;
        boolean httpEligible =
                httpFeatureEnabled && !largeFile && isHttpFile() && area.getLength() <= COMPACT_SCAN_LIMIT;
        // Test gutter: independent of the LSP-gated run feature, so it has its own eligibility (a Java buffer
        // in a JVM project with the Test Runner on). Additive — a test file also keeps any compact-source ▶.
        boolean testEligible =
                testGutterEnabled && !largeFile && "java".equals(language) && area.getLength() <= COMPACT_SCAN_LIMIT;
        // Only materialize the whole document when we actually scan it (compact-source / .http / test detection).
        // Otherwise editing a moderately large file (256 KB–5 MB) would allocate the full text on every
        // 150 ms edit pulse just to discard it as run-ineligible.
        String text = (eligible || httpEligible || testEligible) ? area.getText() : "";
        boolean nowRunnable;
        int nowLine;
        java.util.List<Integer> nowHttpLines = java.util.List.of();
        java.util.Map<Integer, String> nowMakeTargets = java.util.Map.of();
        if (httpEligible) {
            nowHttpLines = com.editora.http.HttpFile.parse(text).stream()
                    .map(com.editora.http.HttpFile.Request::startLine)
                    .toList();
            nowRunnable = !nowHttpLines.isEmpty();
            nowLine = -1; // .http uses the line set, not a single entry line
        } else if (eligible && isMakefile()) {
            // Each rule target gets its own gutter ▶ (running `make <target>`) — the .http multi-glyph model.
            java.util.Map<Integer, String> targets = new java.util.LinkedHashMap<>();
            for (com.editora.run.MakefileTargets.Target t : com.editora.run.MakefileTargets.parse(text)) {
                targets.put(t.line(), t.name());
            }
            nowMakeTargets = targets;
            nowRunnable = !targets.isEmpty();
            nowLine = -1; // Makefile uses the target line map, not a single entry line
        } else if (eligible && "python".equals(language)) {
            nowRunnable = true;
            nowLine = pythonRunLine(text); // the __main__ guard, else the first line
        } else if (eligible && shellRunEnabled && "shell".equals(language)) {
            nowRunnable = true;
            nowLine = 0; // run the whole script from the top (the shebang line, if any)
        } else if (eligible
                && "java".equals(language)
                && (CompactSource.isLaunchable(name, text)
                        || (shebangJavaSource != null && CompactSource.hasTopLevelMain(text)))) {
            // A .java compact source, or an extensionless file with a `java --source N` shebang.
            nowRunnable = true;
            nowLine = CompactSource.mainLine(text);
        } else {
            nowRunnable = false;
            nowLine = -1;
        }
        // JUnit test glyphs are additive to whatever the run type above decided (a test file is usually neither
        // compact-source nor a script), so they OR into runnable and get their own line→target map.
        java.util.Map<Integer, com.editora.test.JavaTestScanner.TestTarget> nowTestLines = java.util.Map.of();
        if (testEligible) {
            java.util.Map<Integer, com.editora.test.JavaTestScanner.TestTarget> tl = new java.util.LinkedHashMap<>();
            for (com.editora.test.JavaTestScanner.TestTarget t : com.editora.test.JavaTestScanner.scan(text)) {
                tl.put(t.line(), t);
            }
            nowTestLines = tl;
            nowRunnable = nowRunnable || !tl.isEmpty();
        }
        boolean changed = nowRunnable != runnable;
        boolean httpLinesChanged = !nowHttpLines.equals(httpRequestLines);
        boolean makeTargetsChanged = !nowMakeTargets.equals(makeTargets);
        boolean testLinesChanged = !nowTestLines.equals(testLines);
        int oldLine = runLine;
        runnable = nowRunnable;
        runLine = nowLine;
        httpRequestLines = nowHttpLines;
        makeTargets = nowMakeTargets;
        testLines = nowTestLines;
        if (changed) {
            onRunnableChanged.run();
            refreshGutter(); // the Run slot appeared/disappeared on every row — rebuild the factory
        } else if (httpLinesChanged || makeTargetsChanged || testLinesChanged) {
            refreshGutter(); // requests/targets/test methods added/removed — relight the glyphs on the new lines
        } else if (nowLine != oldLine) {
            // The entry line moved (edits above it) — repaint just the old and new gutter rows.
            if (oldLine >= 0) {
                refreshGutterLine(oldLine);
            }
            if (nowLine >= 0) {
                refreshGutterLine(nowLine);
            }
        }
    }

    private static final java.util.regex.Pattern PYTHON_MAIN_GUARD =
            java.util.regex.Pattern.compile("^\\s*if\\s+__name__\\s*==\\s*['\"]__main__['\"]\\s*:");

    /** The gutter Run line for a Python script: the {@code if __name__ == "__main__":} guard, else line 0. */
    private static int pythonRunLine(String text) {
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (PYTHON_MAIN_GUARD.matcher(lines[i]).find()) {
                return i;
            }
        }
        return 0;
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

    /** The debounced semantic-tokens request (fired on the 300 ms didChange pulse while active); null = none. */
    public void setSemanticTokensRequester(Runnable requester) {
        this.semanticTokensRequester = requester;
    }

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
     * tokens onto the shifted text (which mis-colored characters/lines until the next response).
     */
    public void setSemanticTokens(java.util.List<SemanticToken> tokens, long requestGen) {
        if (!semanticActive || requestGen != semanticGen) {
            return;
        }
        semanticTokens = tokens == null ? java.util.List.of() : tokens;
        semanticStale = false; // anchored to the current (unchanged since request) text → safe to overlay
        invalidateHighlighting();
        applyHighlighting();
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

    /** Injects the LSP actions surfaced in the right-click menu while {@link #isLspActive()} (Format is
     *  shown only when the server advertises it — see {@link #setLspFormatAvailable}). */
    public void setLspNavActions(Runnable gotoDefinition, Runnable findReferences, Runnable hover, Runnable format) {
        this.lspGotoDefinitionAction = gotoDefinition == null ? () -> {} : gotoDefinition;
        this.lspFindReferencesAction = findReferences == null ? () -> {} : findReferences;
        this.lspHoverAction = hover == null ? () -> {} : hover;
        this.lspFormatAction = format == null ? () -> {} : format;
    }

    /** Whether to offer "Format Document" in the right-click menu (the server's formatting capability). */
    public void setLspFormatAvailable(boolean available) {
        this.lspFormatAvailable = available;
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
        return area.getText();
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

    /** While suspended: the frame's variable name → value map painted as grey end-of-line
     *  annotations on visible lines that mention them; null/empty clears (resume/terminate). */
    public void setInlineValues(java.util.Map<String, String> values) {
        inlineValues.setValues(hugeFile ? null : values);
    }

    /** IntelliJ-style blame "Annotate" gutter column: per-0-based-line annotations (null/empty clears it).
     *  Already formatted + localized by the controller (author/date/tooltip/heatmap), so {@code editor}
     *  stays git-free. Off on huge files. Computes the fixed column width from the widest author+date, then
     *  rebuilds the gutter so the column appears/disappears. */
    public void setBlame(java.util.List<BlameInfo> lines) {
        this.blameLines = (hugeFile || lines == null || lines.isEmpty()) ? null : java.util.List.copyOf(lines);
        this.blameColumnWidth = blameLines == null ? 0 : measureBlameColumnWidth(blameLines);
        refreshGutter();
    }

    /** Per-line annotation for the gutter column (null for a blank/unloaded row). */
    private BlameInfo blameInfoAt(int line) {
        return (blameLines != null && line >= 0 && line < blameLines.size()) ? blameLines.get(line) : null;
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
        return blameLines != null;
    }

    /** The commit hash that last touched {@code line} (for "show this commit"), or null. */
    public String blameHashAt(int line) {
        BlameInfo bi = blameInfoAt(line);
        return bi == null ? null : bi.hash();
    }

    /** The commit hash that last touched the caret line (for "show this commit"), or null. */
    public String blameHashAtCaret() {
        return blameHashAt(area.getCurrentParagraph());
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

    /** Forces (or clears) log-viewer mode on a buffer whose extension isn't {@code .log}; rebuilds the host. */
    public void setLogViewForced(boolean forced) {
        if (this.logViewForced == forced) {
            return;
        }
        this.logViewForced = forced;
        rebuildViewHost();
    }

    /** Overlays the log control (Follow / level / regex) top-right of the code pane; {@code null} removes it. */
    public void setLogControl(Node control) {
        if (logControl != null && logControl != control) {
            removeCornerControl(logControl);
        }
        this.logControl = control;
        rebuildViewHost();
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

    /** Auto-rename-tag: editing an HTML/XML tag name mirrors the rename onto the paired tag. */
    private boolean autoRenameTag;

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

    /** Re-entrancy guard: the mirrored {@code replaceText} must not itself trigger another mirror. */
    private boolean applyingTagRename;

    /** Enables/disables the paired-tag auto-rename (pushed from the controller's view settings). */
    public void setAutoRenameTag(boolean on) {
        autoRenameTag = on;
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
        if (hasActiveSnippet()
                || area.getUndoManager().isPerformingAction()
                || (area2 != null && area2.getUndoManager().isPerformingAction())) {
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
        String prefix = com.editora.editops.Filler.fillPrefix(
                line, com.editora.editops.Commenter.styleFor(getLanguage()).line());
        com.editora.editops.AutoFill.Break brk = com.editora.editops.AutoFill.compute(line, fillColumn, prefix);
        if (brk == null) {
            return;
        }
        int lineStart = a.getAbsolutePosition(par, 0);
        int caret = a.getCaretPosition();
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

    private void maybeMirrorTagRename(org.fxmisc.richtext.model.PlainTextChange c) {
        if (!autoRenameTag || applyingTagRename || largeFile || hugeFile || !isEditable()) {
            return;
        }
        String lang = getLanguage();
        boolean html = "html".equals(lang);
        if (!html && !"xml".equals(lang)) {
            return;
        }
        if (area.getUndoManager().isPerformingAction()
                || (area2 != null && area2.getUndoManager().isPerformingAction())) {
            return;
        }
        com.editora.editops.TagRename.Mirror m = com.editora.editops.TagRename.mirror(
                area.getText(), c.getPosition(), c.getRemoved(), c.getInserted(), html);
        if (m == null) {
            return;
        }
        CodeArea a = getFocusedArea();
        int anchor = a.getAnchor();
        int caret = a.getCaretPosition();
        int delta = m.name().length() - (m.to() - m.from());
        applyingTagRename = true;
        try {
            a.replaceText(m.from(), m.to(), m.name());
            // replaceText moved this view's caret to the mirror — put it back where the user was.
            a.selectRange(anchor >= m.to() ? anchor + delta : anchor, caret >= m.to() ? caret + delta : caret);
        } finally {
            applyingTagRename = false;
        }
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

    /** Whether a level/regex filter is currently narrowing the visible lines. */
    public boolean isLogFiltered() {
        return logFiltered;
    }

    /** Whether the buffer is auto-scrolling as the file grows ({@code tail -f}). */
    public boolean isLogFollowing() {
        return logFollowing;
    }

    public void setLogFollowing(boolean following) {
        this.logFollowing = following;
        if (following) {
            scrollToLogBottom();
        }
    }

    /**
     * Narrows the visible lines to those whose (inherited) level is at least {@code minLevel} and which
     * match {@code regex}; passing {@code null}/{@code null} clears the filter and restores the full text.
     * The full text is retained so a later clear (or an appended tail) re-derives correctly.
     */
    public void applyLogFilter(LogLevel minLevel, java.util.regex.Pattern regex) {
        if (minLevel == null && regex == null) {
            if (logFiltered) {
                String full = logFullText;
                logFiltered = false;
                logFullText = null;
                logMinLevel = null;
                logRegex = null;
                logCarry = null;
                replaceLogText(full);
            }
            return;
        }
        String source = logFiltered ? logFullText : area.getText();
        logFullText = source;
        logFiltered = true;
        logMinLevel = minLevel;
        logRegex = regex;
        logCarry = LogFilter.endCarry(source, null);
        replaceLogText(LogFilter.filter(source, minLevel, regex, null));
    }

    /** The current level floor of the active filter (null when unfiltered or no floor). */
    public LogLevel getLogMinLevel() {
        return logMinLevel;
    }

    /**
     * Appends {@code text} read from the file's tail. While a filter is active the full text is grown and
     * only the matching subset is shown; otherwise the text is appended directly. Auto-scrolls to the
     * bottom while following, and trims the oldest lines past {@link #LOG_FOLLOW_CAP} to bound memory.
     */
    public void appendLogText(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (logFiltered) {
            String add = LogFilter.filter(text, logMinLevel, logRegex, logCarry);
            logCarry = LogFilter.endCarry(text, logCarry);
            logFullText = logFullText + text;
            if (!add.isEmpty()) {
                appendToArea(logFullText, add); // grow the full text; show the matches
            }
        } else {
            appendToArea(null, text);
        }
        if (logFollowing) {
            scrollToLogBottom();
        }
    }

    /** Replaces the whole buffer with {@code fullText} (e.g. on log rotation), keeping any active filter. */
    public void resetLogContent(String fullText) {
        String full = fullText == null ? "" : fullText;
        if (logFiltered) {
            logFullText = full;
            logCarry = LogFilter.endCarry(full, null);
            replaceLogText(LogFilter.filter(full, logMinLevel, logRegex, null));
        } else {
            replaceLogText(full);
        }
        if (logFollowing) {
            scrollToLogBottom();
        }
    }

    /** Programmatically replaces the area text (the buffer is read-only to the user, but code may write). */
    private void replaceLogText(String text) {
        area.replaceText(text == null ? "" : text); // the overlay redraws off the resulting plain-change
        scrollToLogBottom();
    }

    private void appendToArea(String newFullForTrim, String displayAppend) {
        area.appendText(displayAppend);
        trimLogIfOversized();
        if (newFullForTrim != null && newFullForTrim.length() > LOG_FOLLOW_CAP) {
            logFullText = newFullForTrim.substring(newFullForTrim.length() - LOG_FOLLOW_CAP);
        }
    }

    /** Drops the oldest lines once the displayed text exceeds the follow cap (keeps memory bounded). */
    private void trimLogIfOversized() {
        int len = area.getLength();
        if (len <= LOG_FOLLOW_CAP) {
            return;
        }
        int cut = len - LOG_FOLLOW_CAP;
        // Round up to the next line start so we never leave a half line at the top.
        int nl = area.getText().indexOf('\n', cut);
        int end = nl < 0 ? cut : nl + 1;
        area.deleteText(0, Math.min(end, len));
    }

    private void scrollToLogBottom() {
        int total = area.getParagraphs().size();
        if (total > 0) {
            area.showParagraphAtBottom(total - 1);
        }
    }

    /** Injects the debounced HTML-edit listener (fires the live-preview reload); {@code null} disables it. */
    public void setHtmlPreviewDirtyListener(Runnable listener) {
        this.htmlPreviewDirtyListener = listener;
    }

    /** Switches the Markdown view mode, (un)subscribing the live preview and rebuilding the view host. */
    public void setMarkdownViewMode(MarkdownViewMode mode) {
        MarkdownViewMode target = mode == null ? MarkdownViewMode.EDITOR : mode;
        boolean changed = this.markdownViewMode != target;
        this.markdownViewMode = target;
        scheduleFormatBar(); // hide the format bar when entering pure PREVIEW, re-evaluate otherwise
        scheduleAiActionsBar(); // same, for the AI selection-actions bar
        if (target == MarkdownViewMode.EDITOR) {
            unsubscribePreview();
        } else {
            this.split = Split.NONE; // preview supersedes any code split
            ensurePreviewSubscription();
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

    /** Copies the preview text to the clipboard — rendered plain text for Markdown, the source for a diagram. */
    public void copyPreviewToClipboard() {
        String text = isMarkdown() ? MarkdownRenderer.plainText(getContent()) : getContent();
        javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
        cc.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private void showPreviewContextMenu(double screenX, double screenY) {
        if (previewContextMenu == null) {
            MenuItem selectAll = new MenuItem(tr("editmenu.selectAll"));
            selectAll.setGraphic(MenuIcons.selectAll());
            selectAll.setOnAction(ev -> copyPreviewToClipboard());
            MenuItem copy = new MenuItem(tr("editmenu.copy"));
            copy.setGraphic(MenuIcons.copy());
            copy.setOnAction(ev -> copyPreviewToClipboard());
            previewContextMenu = new javafx.scene.control.ContextMenu(selectAll, copy, new SeparatorMenuItem());
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

    private void ensurePreviewSubscription() {
        if (previewSub != null || largeFile) {
            return; // large files render once (no live updates), mirroring the highlight/minimap guard
        }
        previewSub = area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(250))
                .subscribe(ignore -> scheduleRenderPreview());
    }

    private void unsubscribePreview() {
        if (previewSub != null) {
            previewSub.unsubscribe();
            previewSub = null;
        }
    }

    /**
     * Releases this buffer's resources. Must be called by the controller when the tab is
     * <em>actually closed</em> (not on a plain tab switch). The preview/highlight executors are now
     * shared, app-lifetime pools ({@link #PREVIEW_POOL}/{@link #HIGHLIGHT_POOL}), so disposal does not
     * shut them down — it instead bumps {@link #previewGen}/{@link #highlightGen} so any in-flight task
     * submitted by this buffer discards its result (the gen guard) instead of touching the now-dead
     * buffer. The reactfx subscriptions are on the buffer's own {@code area}/{@code area2} and die with
     * it on GC; we drop the preview one eagerly too. Idempotent. Once disposed the buffer must not be reused.
     */
    public void dispose() {
        unsubscribePreview();
        disposeMultiCaret();
        previewGen++; // discard any in-flight preview result for this (now closed) buffer
        highlightGen++; // discard any in-flight highlight result
        languageGen++; // discard any in-flight deferred grammar load
    }

    // --- Multiple cursors + column/box selection (RichTextFX fork) -------------------------------

    /**
     * Enables/disables the multi-caret add-on. When on, installs {@link MultiCaretController} on the
     * primary area (and the secondary split view if present); when off, removes it (collapsing to one
     * caret). Skipped for huge files (editing is already inert there). Idempotent.
     */
    public void setMultiCaretEnabled(boolean enabled) {
        this.multiCaretEnabled = enabled;
        if (enabled && !hugeFile) {
            if (multiCaret == null) {
                multiCaret = MultiCaretController.install(area);
            }
            if (area2 != null && multiCaret2 == null) {
                multiCaret2 = MultiCaretController.install(area2);
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

    /** The multi-caret manager for the area that currently has focus (secondary split view, else primary),
     *  or null when the add-on isn't installed. */
    private org.fxmisc.richtext.multi.MultiCaretManager<?, ?, ?> activeManager() {
        if (multiCaret2 != null && area2 != null && area2.isFocused()) {
            return multiCaret2.getManager();
        }
        return multiCaret == null ? null : multiCaret.getManager();
    }

    /** True while any area of this buffer has more than the primary caret (extra carets / box selection). */
    public boolean hasMultipleCarets() {
        return (multiCaret != null && multiCaret.getManager().hasExtras())
                || (multiCaret2 != null && multiCaret2.getManager().hasExtras());
    }

    /** Selects the next occurrence of the current selection as an additional caret (VS Code Cmd/Ctrl+D). */
    public void addCaretNextOccurrence() {
        var m = activeManager();
        if (m != null) {
            m.addNextOccurrence();
        }
    }

    /** Adds a caret on the line above the (primary) caret. */
    public void addCaretAbove() {
        var m = activeManager();
        if (m != null) {
            m.addCaretLineUpDown(false);
        }
    }

    /** Adds a caret on the line below the (primary) caret. */
    public void addCaretBelow() {
        var m = activeManager();
        if (m != null) {
            m.addCaretLineUpDown(true);
        }
    }

    /** True when {@code a} currently has extra carets, so this buffer's single-caret KEY filters
     *  (auto-indent/close, snippets, completion, view paging) should stand down and let the fork's
     *  multi-caret input map handle the key for every caret. */
    private boolean multiCaretActiveOn(CodeArea a) {
        if (a == area) {
            return multiCaret != null && multiCaret.getManager().hasExtras();
        }
        if (a == area2) {
            return multiCaret2 != null && multiCaret2.getManager().hasExtras();
        }
        return false;
    }

    /** Copies every caret's selection to the clipboard (VS Code one-line-per-caret) when extra carets
     *  exist; returns whether it handled it (so the caller can fall back to the single-caret copy). */
    public boolean multiCaretCopy() {
        var m = activeManager();
        if (m != null && m.hasExtras()) {
            m.copy();
            return true;
        }
        return false;
    }

    /** Cuts every caret's selection (multi-caret aware); returns whether it handled it. */
    public boolean multiCaretCut() {
        var m = activeManager();
        if (m != null && m.hasExtras()) {
            m.cut();
            return true;
        }
        return false;
    }

    /** Pastes at every caret (distributing clipboard lines one per caret); returns whether it handled it. */
    public boolean multiCaretPaste() {
        var m = activeManager();
        if (m != null && m.hasExtras()) {
            m.paste();
            return true;
        }
        return false;
    }

    /** Puts the whole current line (including a trailing newline) on the clipboard — the empty-selection
     *  Copy of VS Code's {@code editor.emptySelectionClipboard}. Leaves the document untouched. */
    public void copyCurrentLine() {
        CodeArea a = focusedArea != null ? focusedArea : area;
        int p = a.getCurrentParagraph();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(a.getParagraph(p).getText() + "\n");
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Cuts the whole current line — copies it (with a trailing newline) then deletes the line as one
     *  undoable edit (the empty-selection Cut of VS Code's {@code editor.emptySelectionClipboard}). */
    public void cutCurrentLine() {
        CodeArea a = focusedArea != null ? focusedArea : area;
        int p = a.getCurrentParagraph();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(a.getParagraph(p).getText() + "\n");
        Clipboard.getSystemClipboard().setContent(content);
        int total = a.getParagraphs().size();
        int start;
        int end;
        if (p < total - 1) { // not the last line: take this line plus its trailing newline
            start = a.getAbsolutePosition(p, 0);
            end = a.getAbsolutePosition(p + 1, 0);
        } else if (total > 1) { // last line: take the preceding newline plus this line
            start = a.getAbsolutePosition(p - 1, a.getParagraph(p - 1).length());
            end = a.getAbsolutePosition(p, a.getParagraph(p).length());
        } else { // only line in the buffer: clear it
            start = 0;
            end = a.getLength();
        }
        a.deleteText(start, end);
    }

    /** Collapses any extra carets / box selection back to a single caret. */
    public void collapseCarets() {
        if (multiCaret != null) {
            multiCaret.getManager().collapseToPrimary();
        }
        if (multiCaret2 != null) {
            multiCaret2.getManager().collapseToPrimary();
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
            String src = area.getText();
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
            String src = area.getText();
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
        if (hasXmlPreview()) {
            // Whole file is one XML doc: parse off-thread (JDK DOM), build the DOM tree on the FX thread into
            // the same self-scrolling host as the JSON/YAML/TOML tree. The previewGen guard drops a stale render.
            String src = area.getText();
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
            String src = area.getText();
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
            String src = area.getText();
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
            String src = area.getText();
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
            String src = area.getText();
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
            String src = area.getText();
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
            String surfaceKey = path != null ? path.toString() : ("mmd@" + System.identityHashCode(this));
            javafx.scene.layout.VBox box =
                    new javafx.scene.layout.VBox(MermaidImages.node(area.getText(), lw -> lw * scale, surfaceKey));
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
                    new javafx.scene.layout.VBox(SvgImages.node(area.getText(), lw -> lw * scale));
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
            javafx.scene.layout.VBox box =
                    new javafx.scene.layout.VBox(DiagramImages.node(dk, area.getText(), lw -> lw * scale));
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
            String retainKey = path != null ? path.toString() : ("untitled@" + System.identityHashCode(this));
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(
                    TypstImages.node(area.getText(), lw -> lw * scale, retainKey, fileDir, root, getDisplayName()));
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
            String src = area.getText();
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
        String md = area.getText();
        Path baseDir = path == null ? null : path.getParent();
        long gen = ++previewGen;
        PREVIEW_POOL.submit(() -> {
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
        });
    }

    /**
     * Rebuilds {@link #viewHost} for the current modes. The floating control is parented to the editor
     * pane ({@link #root}) in Editor/Split so it always sits at the right edge of the <em>code</em> area
     * (at the divider when split); in Preview-only mode there is no code pane, so it overlays the
     * preview instead.
     */
    private void rebuildViewHost() {
        detachViewModeControl();
        Node content;
        if (markdownViewMode == MarkdownViewMode.PREVIEW) {
            StackPane host = previewModeHost();
            if (viewModeControl != null) {
                StackPane.setAlignment(viewModeControl, Pos.TOP_RIGHT);
                StackPane.setMargin(viewModeControl, new Insets(6, 10, 0, 0));
                host.getChildren().add(viewModeControl);
            }
            content = host;
        } else if (markdownViewMode == MarkdownViewMode.SPLIT) {
            SplitPane pane = new SplitPane(root, previewSplitSide());
            pane.setOrientation(Orientation.HORIZONTAL);
            pane.setDividerPositions(0.5);
            attachControlToCodePane();
            content = pane;
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
        viewHost.getChildren().setAll(content);
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

    /** The SPLIT-mode preview side: the bare node (the mode toggle rides the code pane, not the preview). */
    private Node previewSplitSide() {
        if (hasCsvPreview()) {
            return csvPreviewNode;
        }
        if (hasHttpPreview()) {
            return httpPreviewNode;
        }
        if (hasTreePreview()) {
            return structuredContentHolder();
        }
        return previewHost();
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
            treePreviewContextMenu = new javafx.scene.control.ContextMenu(pdf, print);
            treePreviewContextMenu.getStyleClass().add("editor-context-menu");
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
            Button out = zoomButton("−", this::zoomPreviewOut); // − (minus sign)
            Button in = zoomButton("+", this::zoomPreviewIn);
            // Light/dark preview-theme toggle (independent of the app theme). Glyph shows what a click
            // switches TO: a moon while the preview is light, a sun while it's dark.
            previewThemeButton = zoomButton("", () -> previewThemeToggle.run());
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
        previewThemeButton.setTooltip(new javafx.scene.control.Tooltip(
                tr(dark ? "markdown.previewTheme.toLight" : "markdown.previewTheme.toDark")));
    }

    private Button zoomButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().addAll("md-zoom-button", "flat");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
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
        removeCornerControl(logControl);
    }

    private void removeCornerControl(Node control) {
        if (control == null) {
            return;
        }
        root.getChildren().remove(control);
        if (previewHost != null) {
            previewHost.getChildren().remove(control);
        }
        if (csvPreviewHost != null) {
            csvPreviewHost.getChildren().remove(control);
        }
    }

    /** Overlays the corner control(s) at the top-right of the code pane ({@link #root}), clear of its minimap.
     *  A buffer is Markdown <em>or</em> HTML, so at most one of the two controls is non-null. */
    private void attachControlToCodePane() {
        placeCornerControl(viewModeControl);
        placeCornerControl(htmlPreviewControl);
        placeCornerControl(logControl);
    }

    private void placeCornerControl(Node control) {
        if (control == null) {
            return;
        }
        if (!root.getChildren().contains(control)) {
            root.getChildren().add(control);
        }
        AnchorPane.setTopAnchor(control, 6d);
        AnchorPane.setRightAnchor(control, codePaneControlInset());
    }

    private double codePaneControlInset() {
        return (minimapVisible && !largeFile && !heavyFile) ? Minimap.WIDTH + 6 : 10;
    }

    /** Keeps the control clear of the minimap when it toggles (no full view rebuild). */
    private void positionViewModeControl() {
        if (viewModeControl != null && root.getChildren().contains(viewModeControl)) {
            AnchorPane.setRightAnchor(viewModeControl, codePaneControlInset());
        }
    }

    /** Lazily builds the secondary view (scroll pane + its own minimap) sharing this document. */
    private void ensureSecondaryView() {
        if (area2 != null) {
            return;
        }
        area2 = new CodeArea(area.getContent()); // shares the EditableStyledDocument
        area2.getStyleClass().add("editor-area");
        area2.setWrapText(false);
        area2.setUndoManager(largeFile ? UndoUtils.noOpUndoManager() : boundedUndoManager(area2));
        area2.setEditable(area.isEditable());
        addViewModePaging(area2); // same pager keys in the secondary split view
        addSnippetKeys(area2);
        addAutoClose(area2);
        addAutoIndent(area2);
        addCompletionKeys(area2);
        installCompletionTrigger(area2);
        installImageDrop(area2);
        if (multiCaretEnabled && !hugeFile && multiCaret2 == null) {
            multiCaret2 = MultiCaretController.install(area2); // same multi-caret add-on in the split view
        }
        area2.setLineHighlighterFill(lineHighlightColor);
        area2.setParagraphGraphicFactory(LineNumberFactory.get(area2));
        area2.setStyle("-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;");
        area2.caretPositionProperty().addListener((obs, old, now) -> {
            resetGoalColumn();
            scheduleBraceMatch();
        });
        area2.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                focusedArea = area2;
            }
        });
        installFormatBarListeners(area2);
        scrollPane2 = new VirtualizedScrollPane<>(area2);
        // Give the secondary view its own minimap (tracks this pane's viewport), docked like the primary.
        minimap2 = new Minimap(area2);
        minimap2.setTabSize(tabSize);
        minimap2.setColors(minimapText, minimapViewport);
        root2 = new AnchorPane(scrollPane2, minimap2);
        AnchorPane.setTopAnchor(scrollPane2, 0d);
        AnchorPane.setBottomAnchor(scrollPane2, 0d);
        AnchorPane.setLeftAnchor(scrollPane2, 0d);
        AnchorPane.setTopAnchor(minimap2, 0d);
        AnchorPane.setBottomAnchor(minimap2, 0d);
        AnchorPane.setRightAnchor(minimap2, 0d);
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
        this.fontFamily = family;
        this.fontSize = size;
        String style = "-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;";
        area.setStyle(style);
        if (area2 != null) {
            area2.setStyle(style);
        }
        whitespace.setFont(family, size);
        inlineValues.setFont(family, size);
        if (blameLines != null) {
            // The blame annotation column width is font-relative — recompute + rebuild so it stays aligned.
            blameColumnWidth = measureBlameColumnWidth(blameLines);
            refreshGutter();
        }
        scheduleRulerMeasure();
    }

    /** Show/hide the column-80 ruler overlay. */
    public void setColumnRulerVisible(boolean visible) {
        this.rulerVisible = visible;
        if (visible) {
            scheduleRulerMeasure();
        } else {
            columnRuler.setVisible(false);
        }
    }

    /** A fixed-size undo manager so undo history can't grow without bound. */
    private static UndoManager<?> boundedUndoManager(CodeArea a) {
        UndoManagerFactory factory = UndoManagerFactory.fixedSizeHistoryFactory(UNDO_HISTORY);
        // Pass UndoMerge.PAUSE as the preventMergeDelay: edits more than that apart start a new undo
        // group (idle break), giving word/line-level undo together with the token break below.
        return a.isPreserveStyle()
                ? UndoUtils.richTextUndoManager(a, factory, UndoMerge.PAUSE)
                : UndoUtils.plainTextUndoManager(a, factory, UndoMerge.PAUSE);
    }

    /** Ends the current undo group at a word/line boundary (see {@link UndoMerge}); no-op for huge files. */
    private void breakUndoGroupIfBoundary(String inserted, String removed) {
        if (largeFile || !UndoMerge.breakAfter(inserted, removed)) {
            return;
        }
        area.getUndoManager().preventMerge();
        if (area2 != null) {
            area2.getUndoManager().preventMerge(); // the split views share the document + each record it
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
        if (largeFile || area.getLength() > UNDO_HISTORY_MAX_BYTES) {
            return;
        }
        if (undoHistory.add(area.getText(), area.getCaretPosition(), System.currentTimeMillis())
                && onUndoHistoryChanged != null) {
            onUndoHistoryChanged.run();
        }
    }

    /** Restores a checkpoint's document text + caret (a single undoable edit). */
    public void restoreUndoCheckpoint(UndoHistory.Checkpoint c) {
        if (c == null || !isEditable()) {
            return;
        }
        area.replaceText(c.text());
        area.moveTo(UndoHistory.clamp(c.caret(), area.getLength()));
        area.requestFocus();
    }

    /** Toggle the highlight on the line containing the caret. */
    public void setLineHighlightOn(boolean on) {
        area.setLineHighlighterOn(on);
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
        if (minimap2 != null) {
            minimap2.refresh();
        }
    }

    /**
     * Marks whether this buffer is the active (visible) tab. A background tab releases per-buffer
     * GPU-backed caches — currently the minimap snapshot — so retained VRAM doesn't scale with the
     * number of open files; they regenerate when the tab is shown again. Called by the controller on
     * tab selection and when a tab is added in the background.
     */
    public void setRenderingActive(boolean active) {
        minimap.setRenderingActive(active);
        if (minimap2 != null) {
            minimap2.setRenderingActive(active);
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
        if (minimap2 != null) {
            minimap2.setColors(text, viewport);
        }
    }

    /** Show/hide the line-number gutter. The fold-chevron column is always present. */
    public void setLineNumbersVisible(boolean visible) {
        this.lineNumbersVisible = visible;
        refreshGutter();
    }

    /** Show/hide the entire gutter (Simple UI mode removes it completely). */
    public void setGutterVisible(boolean visible) {
        this.gutterVisible = visible;
        refreshGutter();
    }

    /** Rebuilds the gutter graphic factory (line numbers + fold chevrons + markers) from current state, or
     *  removes the gutter entirely (null factory) when {@link #gutterVisible} is off. */
    public void refreshGutter() {
        noteOverlay.refresh();
        area.setParagraphGraphicFactory(gutterVisible ? folds.gutterFactory(lineNumbersVisible) : null);
        applyNoGutterStyle(area);
        if (area2 != null) {
            area2.setParagraphGraphicFactory(gutterVisible ? LineNumberFactory.get(area2) : null);
            applyNoGutterStyle(area2);
        }
    }

    /** With no gutter (Simple UI mode) the text would sit flush against the editor's left edge; the
     *  {@code .no-gutter} class adds a small left padding so it doesn't. The gutter itself supplies that
     *  inset when present, so the class is removed then. */
    private void applyNoGutterStyle(CodeArea a) {
        a.getStyleClass().remove("no-gutter");
        if (!gutterVisible) {
            a.getStyleClass().add("no-gutter");
        }
    }

    public FoldManager getFoldManager() {
        return folds;
    }

    /**
     * Sets the Git gutter change bars (0-based line → CSS class), or {@code null} to disable tracking
     * (no reserved slot). Toggling tracking rebuilds the whole gutter factory (the reserved width
     * changes); otherwise only the lines whose bar changed are repainted — cheap and viewport-safe.
     * Off in large-file mode (the gutter is minimal there).
     */
    public void setChangeBars(java.util.Map<Integer, String> lineClasses) {
        setChangeBars(lineClasses, null);
    }

    /** As {@link #setChangeBars(java.util.Map)} plus a per-line hunk-text map for the change-bar tooltip. */
    public void setChangeBars(java.util.Map<Integer, String> lineClasses, java.util.Map<Integer, String> hunkText) {
        if (largeFile && lineClasses != null) {
            lineClasses = null; // never track in large/huge-file mode
            hunkText = null;
        }
        boolean wasTracked = changeBars != null;
        boolean nowTracked = lineClasses != null;
        java.util.Set<Integer> repaint = new java.util.HashSet<>();
        if (wasTracked) {
            repaint.addAll(changeBars.keySet());
        }
        if (nowTracked) {
            repaint.addAll(lineClasses.keySet());
        }
        changeBars = lineClasses;
        changeHunks = hunkText;
        if (wasTracked != nowTracked) {
            refreshGutter(); // the reserved slot appeared/disappeared — rebuild the factory
        } else {
            repaint.forEach(this::refreshGutterLine);
        }
    }

    /** Whether this buffer currently has Git change tracking on (a reserved change-bar slot). */
    public boolean hasChangeBars() {
        return changeBars != null;
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

    /** 0-based line currently highlighted as the debugger's execution point, or -1 when none. */
    private int executionLine = -1;

    /**
     * Marks {@code line} as the current execution point (a distinct paragraph background) and scrolls/moves
     * the caret there so the built-in current-line highlight reinforces it. Clears any previous mark.
     */
    public void setExecutionLine(int line) {
        clearExecutionLine();
        if (line >= 0 && line < area.getParagraphs().size()) {
            executionLine = line;
            area.setParagraphStyle(line, java.util.List.of("exec-line"));
            jumpToLine(line);
        }
    }

    /** Removes the execution-point highlight (if any). */
    public void clearExecutionLine() {
        if (executionLine >= 0 && executionLine < area.getParagraphs().size()) {
            area.setParagraphStyle(executionLine, java.util.Collections.emptyList());
        }
        executionLine = -1;
    }

    /** The extra glyph CSS-class suffix for the breakpoint on {@code line} (disabled/logpoint/conditional). */
    private String breakpointStyleClass(int line) {
        com.editora.config.Breakpoint bp = breakpoints.get(line);
        if (bp == null) {
            return null;
        }
        if (!bp.enabled()) {
            return "disabled";
        }
        if (bp.isLogpoint()) {
            return "logpoint";
        }
        if (bp.isConditional()) {
            return "conditional";
        }
        return null;
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
        String doc = area.getText();
        var sel = area.getSelection();
        if (sel.getLength() > 0) {
            int start = sel.getStart();
            int end = sel.getEnd();
            var sp = area.offsetToPosition(start, fwd);
            var ep = area.offsetToPosition(end, fwd);
            com.editora.config.NoteScope scope = sp.getMajor() == ep.getMajor()
                    ? com.editora.config.NoteScope.WORD
                    : com.editora.config.NoteScope.RANGE;
            String prefix = doc.substring(Math.max(0, start - CONTEXT_CHARS), start);
            String suffix = doc.substring(end, Math.min(doc.length(), end + CONTEXT_CHARS));
            var anchor = new com.editora.config.TextAnchor(
                    sp.getMajor(), sp.getMinor(), ep.getMajor(), ep.getMinor(), area.getSelectedText(), prefix, suffix);
            return new NoteDraft(scope, anchor);
        }
        int line = area.getCurrentParagraph();
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

    /** Hover popup over a note's span shows its body (updated only when the hovered note changes). */
    private void installNoteHover() {
        area.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (!noteIndicators) {
                hideNoteTip();
                return;
            }
            com.editora.config.PersonalNote n;
            try {
                n = notes.noteAt(area.hit(e.getX(), e.getY()).getInsertionIndex());
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
                noteTip.show(area, e.getScreenX() + 12, e.getScreenY() + 16);
            }
        });
        area.addEventFilter(MouseEvent.MOUSE_EXITED, e -> hideNoteTip());
        // Clicking the inline note marker (the ~7px amber triangle at a note's start) opens the editor —
        // restoring the click-to-edit the gutter glyph used to give. A click elsewhere is untouched.
        area.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (!noteIndicators || e.getButton() != MouseButton.PRIMARY || e.getClickCount() != 1) {
                return;
            }
            for (int[] span : notes.activeSpans()) {
                Bounds scr;
                try {
                    scr = area.getCharacterBoundsOnScreen(span[0], Math.min(area.getLength(), span[0] + 1))
                            .orElse(null);
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

    /** Toggles soft word wrap on the editor surface (and the split view); the 80-column ruler stays visible. */
    public void setWordWrap(boolean wrap) {
        area.setWrapText(wrap);
        if (area2 != null) {
            area2.setWrapText(wrap);
        }
    }

    public void setMinimapVisible(boolean visible) {
        this.minimapVisible = visible;
        boolean effective = minimapEffective(visible, largeFile, heavyFile, markdownViewMode);
        applyMinimap(scrollPane, minimap, effective);
        AnchorPane.setRightAnchor(whitespace, effective ? Minimap.WIDTH : 0d);
        if (minimap2 != null) {
            applyMinimap(scrollPane2, minimap2, effective);
        }
        updateDiagnosticStripe(); // show the scrollbar stripe when the minimap no longer carries marks
        positionViewModeControl(); // keep the floating Markdown control clear of the minimap
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
    private void scheduleRulerMeasure() {
        if (!rulerVisible || rulerMeasurePending) {
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
     * The boundary is found by extrapolating the (monospace) glyph advance from caret positions, so it
     * is exact regardless of which glyphs are present. The ruler is hidden when column 80 falls outside
     * the visible text width (e.g. the window is too narrow, or the text is scrolled past it).
     */
    private void measureAndPlaceRuler() {
        if (!rulerVisible
                || rulerColumnOverride != null
                        && rulerColumnOverride == com.editora.editorconfig.EditorConfigProperties.OFF) {
            columnRuler.setVisible(false);
            return;
        }
        Double x = columnRulerX();
        double viewportWidth = scrollPane.getWidth();
        boolean show = x != null && x >= 0 && x <= viewportWidth;
        columnRuler.setVisible(show);
        if (show) {
            columnRuler.setStartX(x);
            columnRuler.setEndX(x);
        }
    }

    /**
     * Root-local x of column 80, extrapolated from the live layout: the caret x at the start of the
     * longest visible line and at its end give the exact per-column advance (querying caret positions,
     * a {@code from == to} character-bounds call, avoids any dependence on glyph ink widths). Returns
     * {@code null} if no visible line has any text to measure from.
     */
    private Double columnRulerX() {
        int col = rulerColumnOverride != null && rulerColumnOverride > 0 ? rulerColumnOverride : 80;
        try {
            int total = area.getParagraphs().size();
            if (total == 0) {
                return null;
            }
            int first = Math.max(0, area.firstVisibleParToAllParIndex());
            int last = Math.min(total - 1, area.lastVisibleParToAllParIndex());
            int refPar = -1;
            int refLen = 0;
            for (int p = first; p <= last; p++) {
                if (area.isFolded(p)) {
                    continue; // collapsed line: its caret bounds would skew the advance measurement
                }
                int len = area.getParagraphLength(p);
                if (len > refLen) {
                    refLen = len;
                    refPar = p;
                }
            }
            if (refPar < 0) {
                return null; // all visible lines empty; nothing to measure the advance from
            }
            Bounds start = caretBounds(refPar, 0);
            Bounds end = caretBounds(refPar, refLen);
            if (start == null || end == null) {
                return null;
            }
            // When word wrap is on, a long line spans several visual rows, so column 0 and column refLen
            // sit on *different* rows and their x-distance is no longer refLen glyph advances (it collapses,
            // which dropped the ruler near the left edge). Average over the whole line only while it stays on
            // one row; otherwise fall back to a single adjacent-column advance measured on the first row.
            double advance;
            if (Math.abs(end.getMinY() - start.getMinY()) < 1.0) {
                advance = (end.getMinX() - start.getMinX()) / refLen; // unwrapped: precise, rounding averaged out
            } else {
                Bounds next = caretBounds(refPar, 1); // refLen >= 1, so column 1 is on the first visual row
                if (next == null) {
                    return null;
                }
                advance = next.getMinX() - start.getMinX();
            }
            return start.getMinX() + col * advance;
        } catch (RuntimeException ignored) {
            // Viewport mid-layout; a later event will re-measure.
        }
        return null;
    }

    /** Root-local bounds of the caret at {@code column} in paragraph {@code p}, or {@code null}. */
    private Bounds caretBounds(int p, int column) {
        int abs = area.getAbsolutePosition(p, column);
        Bounds screen = area.getCharacterBoundsOnScreen(abs, abs).orElse(null);
        return screen == null ? null : root.screenToLocal(screen);
    }

    public Path getPath() {
        return path;
    }

    /** Records the file's on-disk modified time + size as last loaded/saved, for external-change detection. */
    public void setDiskSnapshot(long modifiedMillis, long size) {
        this.diskModifiedMillis = modifiedMillis;
        this.diskSize = size;
    }

    /** Whether {@code modifiedMillis}/{@code size} differ from the last recorded on-disk snapshot. */
    public boolean diskChangedFrom(long modifiedMillis, long size) {
        return diskModifiedMillis >= 0 && (modifiedMillis != diskModifiedMillis || size != diskSize);
    }

    /** Associates this buffer with a file and selects the grammar and fold language from its extension. */
    public void setPath(Path path) {
        this.path = path;
        // The full path (not just the basename) so location-based rules resolve — e.g. ~/.ssh/config,
        // /etc/hosts, .git/config (see ConfigFileType). The registries reduce it to the basename for
        // ordinary extension lookups.
        String lookup = path == null ? null : path.toString();
        String name = lookup == null ? LanguageRegistry.plaintext() : LanguageRegistry.forFileName(lookup);
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
        recomputeRun(); // a Save-As to a runnable file type can show the gutter Run glyph
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
            IGrammar g = GrammarRegistry.shared().forFileName(displayName);
            applyLanguage(LanguageRegistry.forFileName(displayName), g);
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

    /** Rewrites the document with the chosen line ending; marks the buffer dirty. */
    public void convertLineEndings(boolean crlf) {
        String normalized = area.getText().replace("\r\n", "\n");
        area.replaceText(crlf ? normalized.replace("\n", "\r\n") : normalized);
    }

    /** {@code "CRLF"} if {@code text} contains any Windows line ending, else {@code "LF"}. */
    public static String detectLineEnding(String text) {
        return text != null && text.contains("\r\n") ? "CRLF" : "LF";
    }

    /** The effective line ending: the override (EditorConfig / a manual choice) when set, else detected. */
    public String getLineEnding() {
        return eolOverride != null ? eolOverride : detectLineEnding(area.getText());
    }

    /** Sets the visual tab width used by the minimap (and tracked for future use). */
    public void setTabSize(int tabSize) {
        this.tabSize = tabSize;
        minimap.setTabSize(tabSize);
        if (minimap2 != null) {
            minimap2.setTabSize(tabSize);
        }
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

    /** The effective line ending to write on save ({@code "LF"}/{@code "CRLF"}); null = no override. */
    public void setEolOverride(String eol) {
        this.eolOverride = "CRLF".equals(eol) || "LF".equals(eol) ? eol : null;
    }

    /** The ruler column (EditorConfig {@code max_line_length}); null = default, OFF = hide; re-measures. */
    public void setRulerColumn(Integer column) {
        this.rulerColumnOverride = column;
        measureAndPlaceRuler();
    }

    public void setDetectedCharset(String charset) {
        this.detectedCharset = charset == null ? com.editora.editorconfig.EditorConfigCharset.UTF_8 : charset;
    }

    public void setCharsetOverride(String charset) {
        this.charsetOverride = charset;
    }

    /** The charset to write: the EditorConfig override if set, else the charset detected on open. */
    public String getEffectiveCharset() {
        return charsetOverride != null ? charsetOverride : detectedCharset;
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
     * user's view settings) so very large documents stay responsive. Should be set right after the
     * content is loaded; see {@link #LARGE_FILE_BYTES}.
     */
    public void setLargeFile(boolean large) {
        if (this.largeFile == large) {
            return;
        }
        this.largeFile = large;
        highlightGen++; // discard any in-flight highlight result
        setMinimapVisible(minimapVisible); // re-apply with the large-file guard
        applySpellActive(); // spell checking is off in large-file mode (like highlighting)
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

    /** True when the buffer accepts edits — neither huge-file mode nor user View mode is active. */
    public boolean isEditable() {
        return !hugeFile && !viewMode;
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
            boolean canEdit = path == null || Files.isWritable(path);
            // A non-writable file offers "Edit as Administrator" when elevation is available (Linux/pkexec),
            // instead of a dead-end "read-only on disk" note. Enabling editing routes the eventual Save
            // through the elevated write.
            boolean adminOffer = !canEdit && adminEditAvailable;
            enableEditingButton.setText(tr(adminOffer ? "viewmode.editAsAdmin" : "viewmode.enableEditing"));
            enableEditingButton.setVisible(canEdit || adminOffer);
            enableEditingButton.setManaged(canEdit || adminOffer);
            viewModeNote.setVisible(!canEdit && !adminOffer);
            viewModeNote.setManaged(!canEdit && !adminOffer);
        }
        viewModeBarVisible = show;
        refreshTopBars();
    }

    /** Puts the active top bars into {@code outer.setTop}: the install banner above the view-mode banner
     *  (a {@code VBox} when both show), or {@code null} when neither does. */
    private void refreshTopBars() {
        java.util.List<javafx.scene.Node> bars = new java.util.ArrayList<>(2);
        if (installBarShown && installBar != null) {
            bars.add(installBar);
        }
        if (viewModeBarVisible && viewModeBar != null) {
            bars.add(viewModeBar);
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
        return snippetSession != null && snippetSession.isActive();
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
                    && !completionShowing()) {
                // Plain Tab first tries to expand a snippet prefix; otherwise smart-indent (Shift-Tab
                // always dedents). Only code buffers get smart Tab — prose keeps the default. Skipped
                // while a completion popup/ghost is showing, so Tab accepts the completion instead.
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
     * the file's indent unit. Returns false (leaving the default Tab) for prose / non-code buffers, in
     * read-only/large-file mode.
     */
    private boolean applySmartTab(CodeArea a, boolean shift) {
        if (!isEditable() || hugeFile) {
            return false;
        }
        Indenter.TabEdit edit = Indenter.smartTab(
                a.getText(),
                a.getSelection().getStart(),
                a.getSelection().getEnd(),
                language,
                tabSize,
                shift,
                indentInsertSpacesOverride,
                indentSizeOverride);
        if (edit == null) {
            return false; // PLAIN (prose/plaintext): keep the editor's default Tab behavior
        }
        if (edit.from() != edit.to() || !edit.replacement().isEmpty()) {
            a.replaceText(edit.from(), edit.to(), edit.replacement());
        }
        a.selectRange(edit.selStart(), edit.selEnd());
        return true;
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
            applyLspLineIndent(a, par, line, caret, edits);
        });
        return true;
    }

    /** Adopts the formatter's leading whitespace for the current line, leaving the rest of the line as-is. */
    private void applyLspLineIndent(CodeArea a, int par, String line, int caret, java.util.List<LspTextEdit> edits) {
        String newIndent = LineIndent.formattedIndent(line, edits, par);
        if (newIndent == null) {
            return; // unusable (multi-line) result → leave the line untouched
        }
        String oldIndent = LineIndent.leadingWhitespace(line);
        if (newIndent.equals(oldIndent)) {
            return; // already correctly indented
        }
        int lineStart = a.getAbsolutePosition(par, 0);
        int oldEnd = lineStart + oldIndent.length();
        a.replaceText(lineStart, oldEnd, newIndent);
        int delta = newIndent.length() - oldIndent.length();
        int newCaret = caret <= oldEnd ? lineStart + newIndent.length() : caret + delta;
        newCaret = Math.max(lineStart, Math.min(newCaret, a.getLength()));
        a.moveTo(newCaret);
        // Re-indenting isn't the user typing a word — don't let the edit we just made pop the completion.
        suppressCompletionAtVersion = docVersion;
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
                    || e.isControlDown()
                    || e.isAltDown()
                    || e.isMetaDown()
                    || a.getSelection().getLength() > 0) {
                return;
            }
            applyCloserDedent(a, e.getCharacter().charAt(0));
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
        if (isMarkdown()) {
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
            int markerLen = isTypst() ? TypstMarkup.markerLength(line) : MarkdownLines.markerLength(line);
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
        Indenter.EnterEdit edit = Indenter.enterEdit(
                a.getText(), caret, language, tabSize, indentInsertSpacesOverride, indentSizeOverride);
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
     * When a closing token is typed alone on a line (a {@code )]}} bracket, or a completed closer keyword
     * like {@code end}/{@code fi}), re-aligns the line's indent to its opener. Does <em>not</em> insert the
     * character (the caller does). Shared by the auto-indent key filter and macro replay ({@link #typeChar}).
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
        String currentIndent = leadingIndent(beforeCaret);
        String aligned = Indenter.closerAlignIndent(a.getText(), caret, tabSize);
        if (!aligned.equals(currentIndent)) {
            a.replaceText(lineStart, lineStart + currentIndent.length(), aligned);
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
            if (multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (!isEditable()
                    || hasActiveSnippet()
                    || e.getCharacter().length() != 1
                    || e.isControlDown()
                    || e.isAltDown()
                    || e.isMetaDown()) {
                return;
            }
            char c = e.getCharacter().charAt(0);
            if (applyAutoCloseTyped(a, c)) {
                e.consume();
            }
        });
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
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
        // BraceMatcher never scans beyond DEFAULT_MAX_SCAN chars from the caret, so feed it only that
        // window instead of materializing the whole document on every caret move (the hot path); a big
        // file under the 5 MB brace cap would otherwise allocate the entire text per cursor move.
        int max = BraceMatcher.DEFAULT_MAX_SCAN;
        int winStart = Math.max(0, caret - max - 1);
        int winEnd = Math.min(a.getLength(), caret + max + 1);
        int[] m = BraceMatcher.match(a.getText(winStart, winEnd), caret - winStart, max);
        if (m != null) {
            addBraceClass(m[0] + winStart);
            addBraceClass(m[1] + winStart);
            braceMatch = new int[] {m[0] + winStart, m[1] + winStart};
        }
    }

    private void clearBraceMatch() {
        if (braceMatch != null) {
            removeBraceClass(braceMatch[0]);
            removeBraceClass(braceMatch[1]);
            braceMatch = null;
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
        int caret = a.getCaretPosition();
        String text = a.getText();
        int identStart = caret;
        while (identStart > 0 && isPrefixChar(text.charAt(identStart - 1))) {
            identStart--;
        }
        // Plenty of snippet prefixes aren't identifiers — `#include`/`#ifndef` (c/cpp), `!` (the emmet html
        // skeleton), `?xml`, `---` (yaml), `->` (ruby), `[PSCustomObject]` — so try the whole
        // non-whitespace token first and fall back to the identifier run. Matching only the identifier run
        // left 42 bundled snippets unreachable from the keyboard: at `#inc` the scan stops on the `#` and
        // looks up "inc", which no snippet is registered under.
        int tokenStart = snippetTokenStart(text, caret);
        if (tokenStart < identStart) {
            Snippet wide = snippetProvider.apply(language, text.substring(tokenStart, caret));
            if (wide != null) {
                startSnippet(a, wide, tokenStart, caret);
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
        startSnippet(a, snippet, identStart, caret);
        return true;
    }

    /** Longest snippet prefix we'll look up ({@code [SuppressMessageAttribute]} is the longest bundled one). */
    private static final int MAX_SNIPPET_PREFIX = 40;

    /** Start of the non-whitespace token ending at {@code caret}, bounded to {@link #MAX_SNIPPET_PREFIX}. */
    private static int snippetTokenStart(String text, int caret) {
        int start = caret;
        int limit = Math.max(0, caret - MAX_SNIPPET_PREFIX);
        while (start > limit && !Character.isWhitespace(text.charAt(start - 1))) {
            start--;
        }
        return start;
    }

    /** Parses {@code snippet}, replaces {@code [from,to)} with the expansion, and begins a session. */
    private void startSnippet(CodeArea a, Snippet snippet, int from, int to) {
        if (snippetSession != null) {
            snippetSession.cancel();
            snippetSession = null;
        }
        String fileName = path == null ? "" : path.getFileName().toString();
        String directory = path == null || path.toAbsolutePath().getParent() == null
                ? ""
                : path.toAbsolutePath().getParent().toString();
        String filePath = path == null ? "" : path.toAbsolutePath().toString();
        String clip = javafx.scene.input.Clipboard.getSystemClipboard().hasString()
                ? javafx.scene.input.Clipboard.getSystemClipboard().getString()
                : "";
        int line = a.offsetToPosition(from, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        String currentLine = a.getParagraph(line).getText();
        VariableResolver vars =
                new VariableResolver(fileName, directory, filePath, a.getSelectedText(), clip, line, currentLine);
        ParsedSnippet parsed = SnippetParser.parse(snippet.body(), vars);
        String indent = leadingIndent(currentLine);
        SnippetSession session = new SnippetSession(a, parsed, from, to, indent);
        if (session.isActive()) {
            snippetSession = session;
            session.setOnEnd(() -> snippetSession = null);
        }
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
        if (snippetSession != null) {
            snippetSession.cancel();
            snippetSession = null;
        }
        SnippetSession session = new SnippetSession(area, parsed, 0, area.getLength(), "");
        if (session.isActive()) {
            snippetSession = session;
            session.setOnEnd(() -> snippetSession = null);
        }
        area.requestFocus();
    }

    /** Leading whitespace (spaces/tabs) of a line, used to indent a snippet's continuation lines. */
    private static String leadingIndent(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return line.substring(0, i);
    }

    private static boolean isPrefixChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    // ---- Autocomplete (snippet + dictionary completions; see com.editora.completion) ----

    /** Injects the completion lookup (set by the controller), mirroring {@link #setSnippetProvider}. */
    public void setCompletionProvider(CompletionProvider provider) {
        if (provider != null) {
            this.completionProvider = provider;
        }
    }

    /** Applies the autocomplete settings: the master toggle plus per-source toggles (prose / snippets /
     *  mermaid). The {@code mermaid} flag is the effective value (already gated on the feature + tools). */
    public void setAutocomplete(boolean enabled, boolean prose, boolean snippets, boolean mermaid) {
        this.autocompleteEnabled = enabled;
        this.autocompleteProse = prose;
        this.autocompleteSnippets = snippets;
        this.autocompleteMermaid = mermaid;
        if (!enabled) {
            hideCompletion();
        }
    }

    // ---- AI inline completion (ghost text from an AI provider; see ui.AiCoordinator) ----

    /** Requests a short AI continuation of the text around the caret; the result must be delivered on
     *  the FX thread (null/empty = nothing to show). Kept editor-neutral so {@code editor} stays free
     *  of {@code ai}/{@code ui}. */
    public interface AiCompletionProvider {
        void complete(String language, String prefix, String suffix, java.util.function.Consumer<String> onResult);
    }

    /** Injects the AI completion lookup (set by the controller), mirroring {@link #setCompletionProvider}. */
    public void setAiCompletionProvider(AiCompletionProvider provider) {
        this.aiCompletionProvider = provider;
    }

    /** Applies the effective AI-inline-completion gate (feature on + key present, pushed by the controller). */
    public void setAiCompletionEnabled(boolean enabled) {
        this.aiCompletionEnabled = enabled;
        if (!enabled) {
            aiCompletionGen++; // drop any in-flight request's result
            hideGhost();
        }
    }

    private CompletionPopup completionPopup() {
        if (completionPopup == null) {
            completionPopup = new CompletionPopup();
            completionPopup.setOnAccept(c -> {
                if (completionArea != null) {
                    acceptCompletion(completionArea, c);
                }
            });
            completionPopup.setOnSelect(this::onCompletionSelect); // drive the documentation side-popup
            completionPopup.setOnHidden(this::hideDocPopup); // also tear the doc popup down on auto-hide
        }
        return completionPopup;
    }

    // ---- Completion documentation side-popup (IntelliJ "quick documentation") ----

    /** Injects the lazy doc resolver (opaque token → markdown), set by the controller (LSP-backed). */
    public void setCompletionDocResolver(
            java.util.function.BiConsumer<Object, java.util.function.Consumer<String>> resolver) {
        this.completionDocResolver = resolver;
    }

    /** Whether the documentation popup auto-shows beside the completion list (the Settings toggle). */
    public void setCompletionDocEnabled(boolean enabled) {
        this.completionDocEnabled = enabled;
        if (!enabled) {
            hideDocPopup();
        }
    }

    private CompletionDocPopup docPopup() {
        if (docPopup == null) {
            docPopup = new CompletionDocPopup();
        }
        return docPopup;
    }

    /** Ctrl+Q: toggle the doc popup for the open completion session (show/hide for the current selection). */
    public void toggleCompletionDoc() {
        if (completionPopup == null || !completionPopup.isShowing()) {
            return;
        }
        docPopupActive = !docPopupActive;
        if (docPopupActive) {
            scheduleDoc(completionPopup.selected());
        } else {
            hideDocPopup();
        }
    }

    private void onCompletionSelect(Completion c) {
        if (docPopupActive) {
            scheduleDoc(c);
        }
    }

    /** Debounced per-selection doc fetch (so arrowing quickly doesn't hammer the resolver). */
    private void scheduleDoc(Completion c) {
        docGen++;
        if (c == null || completionDocResolver == null) {
            hideDocPopup();
            return;
        }
        if (docDebounce == null) {
            docDebounce = new javafx.animation.PauseTransition(javafx.util.Duration.millis(180));
        }
        docDebounce.stop();
        long gen = docGen;
        docDebounce.setOnFinished(e -> requestDoc(c, gen));
        docDebounce.playFromStart();
    }

    private void requestDoc(Completion c, long gen) {
        if (gen != docGen || !docPopupActive || completionPopup == null || !completionPopup.isShowing()) {
            return;
        }
        completionDocResolver.accept(c.resolveToken(), doc -> {
            if (gen != docGen || !docPopupActive || completionPopup == null || !completionPopup.isShowing()) {
                return;
            }
            showDoc(c, doc);
        });
    }

    private void showDoc(Completion c, String doc) {
        // The declaration (signature/type) header is shown for every item; the rendered documentation is
        // added only when the server provides any. Nothing to show ⇒ hide (e.g. a plain word/snippet).
        String signature = c.detail() == null ? "" : c.detail().strip();
        javafx.scene.Node rendered = null;
        if (doc != null && !doc.isBlank()) {
            try {
                rendered = MarkdownRenderer.renderDocument(MarkdownRenderer.parseToDocument(doc.strip()), null);
            } catch (RuntimeException ex) {
                rendered = null;
            }
        }
        if (signature.isBlank() && rendered == null) {
            hideDocPopup();
            return;
        }
        Bounds anchor = completionPopup.screenBounds();
        if (anchor == null || completionArea == null || completionArea.getScene() == null) {
            return;
        }
        docPopup().show(completionArea.getScene().getWindow(), anchor, signature, rendered);
    }

    private void hideDocPopup() {
        docGen++;
        if (docDebounce != null) {
            docDebounce.stop();
        }
        if (docPopup != null) {
            docPopup.hide();
        }
    }

    /**
     * Popup navigation/accept/dismiss while it's open. Registered <b>after</b> the snippet/indent filters
     * so it runs first: with the popup open, Tab/Enter accept the selection (instead of expanding a
     * snippet or inserting a newline); ↑/↓ move; Esc closes; caret-moving keys dismiss. With the popup
     * closed it does nothing, so normal Tab/Enter behavior is unaffected.
     */
    private void addCompletionKeys(CodeArea a) {
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            // Inline ghost text (prose): Tab accepts, Esc dismisses; anything else lets the caret move
            // (the caret listener then clears it and the debounce recomputes).
            if (ghostVisible()) {
                switch (e.getCode()) {
                    case TAB -> {
                        if (e.isShiftDown() || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                            hideGhost();
                        } else {
                            acceptGhost();
                            e.consume();
                        }
                    }
                    case ESCAPE -> {
                        hideGhost();
                        e.consume();
                    }
                    default -> {} // typing/Backspace/arrows fall through
                }
                return;
            }
            if (completionPopup == null || !completionPopup.isShowing()) {
                return;
            }
            // Emacs-style C-n / C-p move the selection too (the area owns these keys while the popup is
            // open — see setOwnsKeys — so the global dispatcher leaves them for us).
            if (e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                if (e.getCode() == KeyCode.N) {
                    completionPopup.moveDown();
                    e.consume();
                    return;
                }
                if (e.getCode() == KeyCode.P) {
                    completionPopup.moveUp();
                    e.consume();
                    return;
                }
                if (e.getCode() == KeyCode.G) { // C-g cancels (Emacs); Esc is handled below
                    hideCompletion();
                    e.consume();
                    return;
                }
                if (e.getCode() == KeyCode.Q) { // C-q toggles the documentation popup (IntelliJ quick-doc)
                    toggleCompletionDoc();
                    e.consume();
                    return;
                }
            }
            switch (e.getCode()) {
                case DOWN -> {
                    completionPopup.moveDown();
                    e.consume();
                }
                case UP -> {
                    completionPopup.moveUp();
                    e.consume();
                }
                case PAGE_DOWN -> {
                    completionPopup.pageDown();
                    e.consume();
                }
                case PAGE_UP -> {
                    completionPopup.pageUp();
                    e.consume();
                }
                case ENTER, TAB -> {
                    if (e.isShiftDown() || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                        hideCompletion();
                        return;
                    }
                    Completion sel = completionPopup.selected();
                    if (sel != null) {
                        acceptCompletion(a, sel);
                        e.consume();
                    } else {
                        hideCompletion();
                    }
                }
                case ESCAPE -> {
                    hideCompletion();
                    e.consume();
                }
                case LEFT, RIGHT, HOME, END -> hideCompletion(); // let the caret move
                default -> {} // letters/Backspace fall through; the debounced trigger refreshes the list
            }
        });
        a.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                hideCompletion();
            }
        });
    }

    /** Debounced auto-trigger: completion only appears after typing pauses (~280 ms), so it never
     *  flickers while the user is typing continuously. */
    private void installCompletionTrigger(CodeArea a) {
        a.multiPlainChanges().successionEnds(Duration.ofMillis(280)).subscribe(ignored -> {
            if (a.isFocused()) {
                updateCompletion(a, false);
            }
        });
        // AI inline completion rides a longer pause (~600 ms) so it never races the local popup/ghost;
        // each edit bumps the generation, superseding the in-flight request. Zero work while disabled.
        a.multiPlainChanges().successionEnds(Duration.ofMillis(600)).subscribe(ignored -> {
            if (a.isFocused()) {
                maybeRequestAiCompletion(a);
            }
        });
        // Any caret move or scroll invalidates the inline ghost's position; clear it (the debounce
        // re-shows it after the next pause in typing). The popup manages its own key/caret handling.
        a.caretPositionProperty().addListener((o, ov, nv) -> {
            hideGhost();
            dismissCompletionIfPrefixEmpty(a); // close at once when Backspace deletes the whole typed word
        });
        a.estimatedScrollYProperty().addListener((o, ov, nv) -> hideGhost());
    }

    /**
     * Closes the popup the instant the caret lands where there is no word prefix — e.g. Backspace deleted
     * the whole typed word — so the list doesn't linger over an empty prefix (and isn't left to the 280 ms
     * debounce). Kept open only when the caret is right after an LSP trigger char (member completion).
     */
    private void dismissCompletionIfPrefixEmpty(CodeArea a) {
        if (completionPopup == null || !completionPopup.isShowing()) {
            return;
        }
        int caret = a.getCaretPosition();
        // Only the chars just before the caret matter — read a bounded window instead of materializing the
        // whole document (this fires per caret-move while the popup is open; getText() on a big file is O(n)).
        int from = Math.max(0, caret - PREFIX_LOOKBACK);
        String before = a.getText(from, caret);
        int kept = before.length();
        while (kept > 0 && isPrefixChar(before.charAt(kept - 1))) {
            kept--;
        }
        boolean afterTrigger = lspActive && !isProse() && endsWithLspTrigger(before, before.length());
        if (kept == before.length() && !afterTrigger) {
            hideCompletion();
        }
    }

    /** Lookback window (chars) when checking the prefix before the caret — far longer than any real prefix. */
    private static final int PREFIX_LOOKBACK = 256;

    /** True while the completion popup or the inline ghost is visible. */
    public boolean completionShowing() {
        return ghostVisible() || (completionPopup != null && completionPopup.isShowing());
    }

    /** Dismisses any active completion (popup or ghost) — the {@code edit.cancel} / Escape path. */
    public void cancelCompletion() {
        hideCompletion();
    }

    /** Manual trigger (the {@code edit.completion} command), on the focused view. */
    public void triggerCompletion() {
        CodeArea a = (area2 != null && area2.isFocused()) ? area2 : area;
        updateCompletion(a, true);
    }

    /** Recomputes the word at the caret and shows/refreshes/hides the popup. {@code manual} lowers the
     *  minimum prefix length so an explicit invoke works on a single character. */
    private void updateCompletion(CodeArea a, boolean manual) {
        if (!autocompleteEnabled
                || largeFile // off in large-file mode: a.getText() below would allocate the whole document
                // per trigger (the same per-keystroke cost brace matching is gated to avoid), and there is
                // no LSP/grammar to complete against on a large file anyway. largeFile implies hugeFile.
                || !isEditable()
                || hasActiveSnippet()
                || (!manual && docVersion == suppressCompletionAtVersion) // don't re-offer right after accept
                || a.getSelection().getLength() > 0) {
            hideCompletion();
            return;
        }
        int caret = a.getCaretPosition();
        String text = a.getText();
        int start = caret;
        while (start > 0 && isPrefixChar(text.charAt(start - 1))) {
            start--;
        }
        String prefix = text.substring(start, caret);
        int min = manual ? 1 : CompletionEngine.MIN_PREFIX;
        // LSP "trigger character" (e.g. Java's '.'): fire member completion with no prefix, and keep an
        // open LSP popup updating as the member name is typed (IntelliJ-style) — bypassing the min-prefix.
        // Keeping it open with an empty prefix is only right immediately after a trigger char; once the
        // user backspaces past the typed word (prefix empty, not after a trigger) the popup must close.
        boolean lspTrigger = lspActive
                && !isProse()
                && (endsWithLspTrigger(text, caret) || (completionPopupShowing() && !prefix.isEmpty()));
        if (prefix.length() < min && !lspTrigger) {
            hideCompletion();
            return;
        }
        // Per-source toggle: prose → word/dictionary, mermaid → keywords+snippets, other code → snippets.
        boolean sourceOn = isProse() ? autocompleteProse : (isDiagram() ? autocompleteMermaid : autocompleteSnippets);
        if (!sourceOn) {
            hideCompletion();
            return;
        }
        // Snippets match the whole non-whitespace token (so a non-identifier trigger — `#inc`→`#include`,
        // `?xml`, `[PSCustomObject]` — surfaces in the popup, not only via Tab, #446); words/keywords use the
        // identifier run. When the token is wider than the identifier, stamp a ReplaceStart on the snippet-kind
        // items so accepting one replaces `[wideStart, caret]` (e.g. the whole `#inc`, never `##include`).
        int wideStart = snippetTokenStart(text, caret);
        String wideToken = text.substring(wideStart, caret);
        List<Completion> items =
                completionProvider.complete(language, getSpellLanguage(), prefix, wideToken, isProse());
        if (a.getScene() == null) {
            return;
        }
        if (wideStart < start && !items.isEmpty()) {
            var pos = a.offsetToPosition(wideStart, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward);
            Completion.ReplaceStart rs = new Completion.ReplaceStart(pos.getMajor(), pos.getMinor());
            items = items.stream()
                    .map(c -> c.snippet() != null && c.replaceStart() == null ? c.withReplaceStart(rs) : c)
                    .toList();
        }
        // Prose: a single inline "ghost text" suffix after the caret (only at end-of-line content, so it
        // never overlaps following text). Code: the multi-choice popup. Handle prose BEFORE any
        // empty-items return, so the dictionary-load retry still gets registered on the first keystrokes.
        if (isProse()) {
            int lineEnd = caret;
            while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') {
                lineEnd++;
            }
            String suffix = text.substring(caret, lineEnd).isBlank() ? bestGhostSuffix(items, prefix) : null;
            if (suffix != null && !suffix.isEmpty()) {
                hidePopup();
                showGhost(a, suffix);
                return;
            }
            hideCompletion();
            // The word list loads off-thread; if it isn't ready yet, re-run once it lands so the first
            // suggestion appears without needing another keystroke.
            String dl = getSpellLanguage();
            if (com.editora.completion.DictionaryWords.isAvailable(dl)
                    && !com.editora.completion.DictionaryWords.isReady(dl)) {
                com.editora.completion.DictionaryWords.ensureLoaded(dl, () -> {
                    if (a.isFocused()) {
                        updateCompletion(a, manual);
                    }
                });
            }
            return;
        }
        hideGhost();
        // Code buffer with a language server: fetch LSP completions async and merge with local snippets.
        if (lspActive && lspCompletionProvider != null) {
            requestLspCompletion(a, caret, prefix, items);
            return;
        }
        if (items.isEmpty()) {
            hidePopup();
            return;
        }
        Bounds caretScreen = a.getCharacterBoundsOnScreen(caret, caret).orElse(null);
        if (caretScreen == null) {
            return;
        }
        completionArea = a;
        // Take ownership of editor-context chords so C-n/C-p reach the popup instead of moving the caret.
        a.getProperties().put("editora.ownsKeys", Boolean.TRUE);
        docPopupActive = completionDocEnabled; // arm the doc popup for this session (Ctrl+Q toggles it)
        completionPopup().setQuery(prefix);
        completionPopup().show(a.getScene().getWindow(), caretScreen, items, 0);
    }

    /** Prompt-window sizes for AI inline completion (chars before/after the caret). */
    private static final int AI_COMPLETION_PREFIX_CHARS = 4000;

    private static final int AI_COMPLETION_SUFFIX_CHARS = 1000;

    /**
     * Fires one AI inline-completion request after a typing pause: only in an editable, non-large buffer
     * with the caret at end-of-line content (the prose-ghost convention — the suggestion never overlaps
     * following text) and no completion popup open. The async result shows as ghost text (Tab accepts)
     * only if the caret hasn't moved and no newer request superseded it.
     */
    private void maybeRequestAiCompletion(CodeArea a) {
        if (!aiCompletionEnabled
                || aiCompletionProvider == null
                || largeFile
                || !isEditable()
                || hasActiveSnippet()
                || a.getSelection().getLength() > 0
                || completionPopupShowing()) {
            return;
        }
        int caret = a.getCaretPosition();
        if (caret == 0) {
            return; // nothing to continue yet
        }
        String text = a.getText();
        int lineEnd = caret;
        while (lineEnd < text.length() && text.charAt(lineEnd) != '\n') {
            lineEnd++;
        }
        if (!text.substring(caret, lineEnd).isBlank()) {
            return;
        }
        String prefix = text.substring(Math.max(0, caret - AI_COMPLETION_PREFIX_CHARS), caret);
        String suffix = text.substring(caret, Math.min(text.length(), caret + AI_COMPLETION_SUFFIX_CHARS));
        long gen = ++aiCompletionGen;
        aiCompletionProvider.complete(language, prefix, suffix, result -> {
            if (gen != aiCompletionGen
                    || result == null
                    || result.isBlank()
                    || !a.isFocused()
                    || a.getCaretPosition() != caret
                    || completionPopupShowing()) {
                return;
            }
            String oneLine = result.lines().findFirst().orElse("").stripTrailing();
            if (!oneLine.isEmpty()) {
                showGhost(a, oneLine);
            }
        });
    }

    /** The suffix of the best word completion that continues {@code prefix}, or null if none qualifies. */
    private static String bestGhostSuffix(List<Completion> items, String prefix) {
        for (Completion c : items) {
            if (c.kind() == Completion.Kind.WORD) {
                // The accept path keeps the typed prefix and inserts only this suffix, so the word must be
                // cased compatibly with what was typed (see CompletionEngine.ghostSuffix).
                String suffix = CompletionEngine.ghostSuffix(c.insert(), prefix);
                if (suffix != null) {
                    return suffix;
                }
            }
        }
        return null;
    }

    private Label ghostLabel() {
        if (ghostLabel == null) {
            ghostLabel = new Label();
            ghostLabel.getStyleClass().add("completion-ghost");
            ghostLabel.setMouseTransparent(true);
            ghostLabel.setManaged(false); // free-positioned via layoutX/Y, ignored by AnchorPane layout
            ghostLabel.setFocusTraversable(false);
            ghostLabel.setAlignment(Pos.CENTER_LEFT); // center text in the line-box height (vertical align)
            ghostLabel.setVisible(false);
        }
        return ghostLabel;
    }

    private boolean ghostVisible() {
        return ghostLabel != null && ghostLabel.isVisible();
    }

    /** Draws the ghost suffix as a muted overlay label starting exactly at the caret. */
    private void showGhost(CodeArea a, String suffix) {
        int caret = a.getCaretPosition();
        // At end-of-line there's no glyph *at* the caret, so measure the char *before* it and use its
        // right edge as the start x (getCharacterBoundsOnScreen(caret, caret) would be empty there).
        boolean usePrev = caret > 0;
        Bounds screen = (usePrev
                        ? a.getCharacterBoundsOnScreen(caret - 1, caret)
                        : a.getCharacterBoundsOnScreen(caret, caret + 1))
                .orElse(null);
        if (screen == null) {
            return;
        }
        AnchorPane target = (a == area2 && root2 != null) ? root2 : root;
        Bounds local = target.screenToLocal(screen);
        if (local == null) {
            return;
        }
        Label g = ghostLabel();
        if (g.getParent() != target) {
            if (g.getParent() instanceof AnchorPane ap) {
                ap.getChildren().remove(g);
            }
            target.getChildren().add(g);
        }
        g.setFont(Font.font(fontFamily, fontSize));
        g.setText(suffix);
        // Unmanaged node: AnchorPane won't lay it out, so size + place it ourselves. applyCss() first so
        // the Label's skin exists and prefWidth reflects the text (otherwise it stays 0×0). Use the
        // measured line-box height and let the Label center the text in it, so it aligns with the line.
        g.applyCss();
        double w = Math.ceil(g.prefWidth(-1));
        double h = local.getHeight() > 0 ? local.getHeight() : Math.ceil(g.prefHeight(w));
        g.resizeRelocate(usePrev ? local.getMaxX() : local.getMinX(), local.getMinY(), w, h);
        g.setVisible(true);
        g.toFront();
        ghostSuffix = suffix;
        ghostArea = a;
    }

    /** Inserts the pending ghost suffix at the caret (the Tab-accept path for inline completion). */
    private void acceptGhost() {
        if (ghostSuffix == null || ghostArea == null) {
            return;
        }
        CodeArea a = ghostArea;
        String s = ghostSuffix;
        hideGhost();
        a.insertText(a.getCaretPosition(), s);
        // Stamped AFTER the edit (which bumps docVersion), so the auto-trigger that edit schedules is the
        // one suppressed — the user typing on afterwards bumps the version again and re-enables it.
        suppressCompletionAtVersion = docVersion;
        a.requestFocus();
    }

    private void hideGhost() {
        if (ghostLabel != null) {
            ghostLabel.setVisible(false);
        }
        ghostSuffix = null;
        ghostArea = null;
    }

    private void hidePopup() {
        completionGen++; // invalidate any in-flight async (LSP) completion so a late result won't re-show
        if (completionArea != null) {
            completionArea.getProperties().remove("editora.ownsKeys"); // release the C-n/C-p ownership
        }
        if (completionPopup != null) {
            completionPopup.hide();
        }
        hideDocPopup(); // the doc popup never outlives the completion list
    }

    /** Injected async LSP completion source: {@code accept({line,char}, items->…)}; null = none. */
    public void setLspCompletionProvider(
            java.util.function.BiConsumer<int[], java.util.function.Consumer<java.util.List<Completion>>> provider) {
        this.lspCompletionProvider = provider;
    }

    /** Requests LSP completions async, filters them by the typed {@code prefix} (the server returns the
     *  whole scope and leaves filtering to the client), then shows them merged with the local snippets. */
    private void requestLspCompletion(CodeArea a, int caret, String prefix, java.util.List<Completion> localItems) {
        long gen = ++completionGen;
        long version = docVersion;
        // Flush the current text to the server FIRST: the completion auto-trigger (≈120ms) fires before
        // the debounced didChange (≈300ms), so without this the server still has stale text and member
        // completion after '.' resolves against the old document. JSON-RPC preserves order, so this
        // didChange is applied before the completion request below. (Skipped when the server already has this
        // version — e.g. the debounced pulse just sent it — so it doesn't re-materialize the whole document.)
        sendLspChange();
        lspCompletionProvider.accept(new int[] {a.getCurrentParagraph(), a.getCaretColumn()}, lspItems -> {
            // Drop a response the document has moved past. The caret check alone isn't enough: an edit can
            // put the caret back on the same offset (select the word, retype it), which would show items
            // computed for the old text.
            if (gen != completionGen
                    || docVersion != version
                    || a.getScene() == null
                    || a.getCaretPosition() != caret
                    || hasActiveSnippet()) {
                return;
            }
            // Order LSP items by the server's relevance (preselect, then sortText) — IntelliJ-style —
            // before merging the local snippets in after them.
            java.util.List<Completion> ordered = CompletionEngine.sortLspByRelevance(filterByPrefix(lspItems, prefix));
            java.util.List<Completion> merged = mergeCompletions(ordered, localItems);
            if (merged.isEmpty()) {
                hidePopup();
                return;
            }
            Bounds cs = a.getCharacterBoundsOnScreen(caret, caret).orElse(null);
            if (cs == null) {
                return;
            }
            completionArea = a;
            a.getProperties().put("editora.ownsKeys", Boolean.TRUE);
            docPopupActive = completionDocEnabled; // arm the doc popup for this session (Ctrl+Q toggles it)
            completionPopup().setQuery(prefix);
            completionPopup().show(a.getScene().getWindow(), cs, merged, preselectIndexOf(merged));
        });
    }

    /** Whether the completion popup is currently open (an in-progress LSP/local completion session). */
    private boolean completionPopupShowing() {
        return completionPopup != null && completionPopup.isShowing();
    }

    /** True if the char just before {@code caret} is one of the server's advertised completion trigger
     *  characters (e.g. {@code .} for Java, {@code <} for HTML) — so completion fires there, not just on a
     *  word prefix. The set comes from the server's capabilities via {@link #setLspTriggerChars}.
     *  Whitespace is never treated as a trigger: no language completes on a space, and doing so would keep
     *  the popup open on a blank/indented line after the typed word is deleted. */
    private boolean endsWithLspTrigger(String text, int caret) {
        if (caret <= 0 || caret > text.length()) {
            return false;
        }
        char c = text.charAt(caret - 1);
        return !Character.isWhitespace(c) && lspTriggerChars.contains(c);
    }

    /** Keeps LSP items whose label or insert text starts with {@code prefix} (case-insensitive). The
     *  server returns the full scope; this is the client-side prefix filtering LSP expects. Blank prefix
     *  ⇒ unfiltered. */
    private static java.util.List<Completion> filterByPrefix(java.util.List<Completion> items, String prefix) {
        if (items == null || items.isEmpty() || prefix == null || prefix.isBlank()) {
            return items == null ? java.util.List.of() : items;
        }
        String p = prefix.toLowerCase(java.util.Locale.ROOT);
        java.util.List<Completion> strict = new java.util.ArrayList<>();
        java.util.List<Completion> fuzzy = new java.util.ArrayList<>();
        for (Completion c : items) {
            String label = c.label() == null ? "" : c.label().toLowerCase(java.util.Locale.ROOT);
            String insert = c.insert() == null ? "" : c.insert().toLowerCase(java.util.Locale.ROOT);
            if (label.startsWith(p) || insert.startsWith(p)) {
                strict.add(c);
            } else if (isSubsequence(p, label) || isSubsequence(p, insert)) {
                fuzzy.add(c);
            }
        }
        // Prefer literal-prefix matches (Java/TS return the whole scope, so this narrows it). When there
        // are none, fall back to the server's fuzzy matches — some servers (Pyright) already narrow
        // server-side and return subsequence matches, so strict filtering would empty the popup.
        return strict.isEmpty() ? fuzzy : strict;
    }

    /** True if {@code p} is a subsequence of {@code s} (chars in order, not necessarily contiguous). */
    private static boolean isSubsequence(String p, String s) {
        int i = 0;
        for (int j = 0; j < s.length() && i < p.length(); j++) {
            if (s.charAt(j) == p.charAt(i)) {
                i++;
            }
        }
        return i == p.length();
    }

    /** Index of the first preselected item, or 0 (the popup pre-highlights the server's preselect). */
    private static int preselectIndexOf(java.util.List<Completion> items) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).preselect()) {
                return i;
            }
        }
        return 0;
    }

    /** Merges LSP completions (first) with local snippet items, de-duped by insert text, capped. */
    private static java.util.List<Completion> mergeCompletions(
            java.util.List<Completion> lsp, java.util.List<Completion> local) {
        java.util.LinkedHashMap<String, Completion> byInsert = new java.util.LinkedHashMap<>();
        if (lsp != null) {
            for (Completion c : lsp) {
                byInsert.putIfAbsent(c.insert(), c);
            }
        }
        for (Completion c : local) {
            byInsert.putIfAbsent(c.insert(), c);
        }
        java.util.List<Completion> out = new java.util.ArrayList<>(byInsert.values());
        return out.size() > 50 ? out.subList(0, 50) : out;
    }

    /** Replaces the typed prefix with the accepted completion (a snippet starts a tab-stop session). */
    private void acceptCompletion(CodeArea a, Completion c) {
        int caret = a.getCaretPosition();
        String text = a.getText();
        int start = caret;
        while (start > 0 && isPrefixChar(text.charAt(start - 1))) {
            start--;
        }
        Completion.ReplaceStart rs = c.replaceStart();
        if (rs != null) {
            // The server told us exactly what to replace (LSP textEdit.range) — honor it over the identifier
            // walk. The end stays the current caret so any chars typed since the request are absorbed.
            try {
                start = Math.min(caret, lspOffset(a, rs.line(), rs.character()));
            } catch (RuntimeException ignored) {
                // Range no longer valid (document moved) — fall back to the identifier walk above.
            }
        } else if (start == caret && caret > 0 && c.snippet() == null) {
            // The identifier walk captured nothing: the char before the caret is a non-identifier trigger
            // (e.g. phpactor's `$`, a bash variable sigil). If the insert begins with that overlap, extend the
            // replaced range back over it so accepting `$user` after typing `$` yields `$user`, not `$$user`.
            int overlap = CompletionEngine.prefixOverlap(text.substring(0, caret), c.insert());
            start = caret - overlap;
        }
        hideCompletion();
        // Measured around our own edit so a later completionItem/resolve's additionalTextEdits — positions the
        // server computed against the document as it is right here, before the accept — can be translated onto
        // the document the accept leaves behind (#410). Taken before the edit; completed just after it.
        var preStart = lspPosition(a, start);
        var preEnd = lspPosition(a, caret);
        int preLength = text.length();
        if (c.snippet() != null) {
            startSnippet(a, c.snippet(), start, caret);
        } else {
            a.replaceText(start, caret, c.insert());
        }
        // Where the replaced range's end landed. Derived from the net length change rather than the accepted
        // text, so it holds for the snippet path too (its expansion re-indents and adds tab stops).
        var postEnd = lspPosition(a, caret + (a.getLength() - preLength));
        pendingCompletionShift =
                new LspEditShift.Change(preStart[0], preStart[1], preEnd[0], preEnd[1], postEnd[0], postEnd[1]);
        // Stamped AFTER the edit (which bumps docVersion), so the auto-trigger that edit schedules is the
        // one suppressed — the user typing on afterwards bumps the version again and re-enables it.
        suppressCompletionAtVersion = docVersion;
        if (c.onAccept() != null) {
            c.onAccept().run(); // e.g. resolve + apply a TypeScript auto-import's additionalTextEdits
        }
        a.requestFocus();
    }

    private void hideCompletion() {
        hidePopup();
        hideGhost();
    }

    /**
     * Applies language-server text edits (e.g. a completion's {@code additionalTextEdits} — the
     * {@code import} line a TypeScript auto-import adds). Edits are applied bottom-to-top so earlier
     * offsets stay valid; out-of-range positions are clamped/skipped. Inert when not editable.
     */
    /** Whether this file's detected indentation is spaces rather than tabs — the LSP formatting hint. */
    public boolean detectInsertSpaces(int tabSize) {
        if (indentInsertSpacesOverride != null) {
            return indentInsertSpacesOverride;
        }
        return !Indenter.detectUnit(area.getText(), tabSize).contains("\t");
    }

    /**
     * Monotonic count of edits made to this buffer's document. Callers that hand work to an async round-trip
     * and then apply its result to the document (e.g. resolving a completion's auto-import edits) capture
     * this first and drop the result if it moved — the offsets a server computed against one revision are
     * meaningless against another.
     */
    public long docVersion() {
        return docVersion;
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

    /**
     * Applies the {@code additionalTextEdits} a {@code completionItem/resolve} returned for the completion this
     * buffer last accepted (an auto-import line). Unlike {@link #applyLspEdits}, the positions are first
     * translated across the accept's own insertion, which moved everything below the caret after the server
     * computed them — see {@link LspEditShift}.
     */
    public void applyCompletionAdditionalEdits(java.util.List<LspTextEdit> edits) {
        applyLspEdits(LspEditShift.shift(edits, pendingCompletionShift));
    }

    public void applyLspEdits(java.util.List<LspTextEdit> edits) {
        if (edits == null || edits.isEmpty() || !isEditable()) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        // Resolve each edit to an absolute [start,end] against the current document, keep valid + non-overlapping,
        // sorted ascending. Applying them as ONE MultiChangeBuilder commit makes the whole set a single undo
        // unit — a multi-line Format Document (or an auto-import's additional edits) was previously one
        // replaceText per edit, so it took many Ctrl-Z to revert (#415, the Format-Document sub-item).
        int len = a.getLength();
        java.util.List<int[]> ranges = new java.util.ArrayList<>(); // {start, end}
        java.util.List<String> texts = new java.util.ArrayList<>();
        java.util.List<LspTextEdit> asc = new java.util.ArrayList<>(edits);
        asc.sort((x, y) -> Integer.compare(lspEditOffset(a, x), lspEditOffset(a, y)));
        int last = 0;
        for (LspTextEdit e : asc) {
            try {
                int s = lspOffset(a, e.startLine(), e.startCol());
                int en = lspOffset(a, e.endLine(), e.endCol());
                int from = Math.min(s, en);
                int to = Math.max(s, en);
                if (from < last || to > len) {
                    continue; // overlaps a previous edit or out of range — skip (rare; positions shifted)
                }
                ranges.add(new int[] {from, to});
                texts.add(e.newText() == null ? "" : e.newText());
                last = to;
            } catch (RuntimeException ignored) {
                // Position no longer valid (document changed under us) — skip this edit.
            }
        }
        if (ranges.isEmpty()) {
            return;
        }
        if (ranges.size() == 1) {
            a.replaceText(ranges.get(0)[0], ranges.get(0)[1], texts.get(0));
            return;
        }
        org.fxmisc.richtext.MultiChangeBuilder<?, ?, ?> builder = a.createMultiChange(ranges.size());
        for (int i = 0; i < ranges.size(); i++) {
            builder.replaceTextAbsolutely(ranges.get(i)[0], ranges.get(i)[1], texts.get(i));
        }
        builder.commit(); // one undo unit for the whole edit set
    }

    private static int lspEditOffset(CodeArea a, LspTextEdit e) {
        try {
            return lspOffset(a, e.startLine(), e.startCol());
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /** Absolute offset for a 0-based LSP line/character, clamped to the document/paragraph bounds. */
    private static int lspOffset(CodeArea a, int line, int col) {
        int par = Math.max(0, Math.min(line, a.getParagraphs().size() - 1));
        int len = a.getParagraph(par).length();
        return a.getAbsolutePosition(par, Math.max(0, Math.min(col, len)));
    }

    /**
     * The 0-based LSP {@code {line, character}} of an absolute offset (the inverse of {@link #lspOffset}),
     * clamped to the document. {@code Backward} bias so an offset at a line's end reads as that line's last
     * column rather than the next line's column 0 — the two ends of the accept's change must agree.
     */
    private static int[] lspPosition(CodeArea a, int offset) {
        int clamped = Math.max(0, Math.min(offset, a.getLength()));
        var pos = a.offsetToPosition(clamped, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward);
        return new int[] {pos.getMajor(), pos.getMinor()};
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
        area.setUndoManager(largeFile ? UndoUtils.noOpUndoManager() : boundedUndoManager(area));
        if (area2 != null) {
            area2.setUndoManager(largeFile ? UndoUtils.noOpUndoManager() : boundedUndoManager(area2));
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
        widen(); // a fresh document supersedes any narrowing of the old one
        area.replaceText(content == null ? "" : content);
        markClean();
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
        return narrowPrefix == null ? area.getText() : narrowPrefix + area.getText() + narrowSuffix;
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
     * the history; edits made while narrowed undo normally.
     */
    public boolean narrowTo(int start, int end) {
        if (largeFile || hugeFile) {
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
        narrowPrefix = full.substring(0, s);
        narrowSuffix = full.substring(e);
        area.replaceText(full.substring(s, e));
        area.getUndoManager().forgetHistory();
        area.moveTo(Math.max(0, Math.min(caret - s, area.getLength())));
        area.requestFollowCaret();
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
        narrowPrefix = null; // cleared first: replaceText fires the dirty listener, which reads getContent()
        narrowSuffix = null;
        area.replaceText(prefix + visible + suffix);
        area.getUndoManager().forgetHistory();
        area.moveTo(Math.min(prefix.length() + caret, area.getLength()));
        area.requestFollowCaret();
    }

    /**
     * Replaces the entire document, widening first. Every caller that computes a replacement from
     * {@link #getContent()} — a find-and-replace across files, a local-history restore, a diff apply, a
     * reload from disk — must come through here or {@link #setContent}: writing whole-document text into a
     * narrowed area would leave the held-aside text alongside it and duplicate the file.
     */
    public void replaceWholeDocument(String text) {
        widen();
        area.replaceText(text == null ? "" : text);
    }

    /** The accessible portion — the narrowed region, or the whole document when not narrowed. */
    public String getVisibleContent() {
        return area.getText();
    }

    /** Length of {@link #getContent()} without building it — the per-keystroke dirty-check gate. */
    private int contentLength() {
        return narrowPrefix == null
                ? area.getLength()
                : narrowPrefix.length() + area.getLength() + narrowSuffix.length();
    }

    public BooleanProperty dirtyProperty() {
        return dirty;
    }

    public boolean isDirty() {
        return dirty.get();
    }

    /** Marks the current content as the saved baseline (after load/save); clears the dirty flag. */
    public void markClean() {
        cleanText = getContent(); // the whole document, so narrowing never fakes a dirty flag
        dirty.set(false);
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

    /** Sets a callback invoked (on the FX thread) whenever {@link #symbols()} changes. */
    public void setOnSymbolsChanged(Runnable callback) {
        this.onSymbolsChanged = callback == null ? () -> {} : callback;
    }

    /** Forces the next {@link #applyHighlighting()} to re-tokenize the whole document (e.g. after a
     *  language/grammar change, where no text change occurred). */
    private void invalidateHighlighting() {
        dirtyFromLine = 0;
        lineStates.clear();
    }

    /** Applies per-column rainbow CSV coloring over the whole document (a cheap O(n) FX-thread pass — no
     *  grammar state, so no incremental machinery; runs on the same debounced highlight pulse). */
    private void applyCsvRainbow() {
        lineStates.clear();
        if (!symbols.isEmpty()) {
            symbols = List.of();
            onSymbolsChanged.run();
        }
        String text = area.getText();
        int len = text.length();
        if (len == 0) {
            return;
        }
        char delim = com.editora.csv.CsvParser.detectDelimiter(text);
        StyleSpans<Collection<String>> spans = CsvRainbow.buildSpans(text, delim, CsvRainbow.COLORS);
        if (spans.length() == len) {
            area.setStyleSpans(0, spans);
            scheduleBraceMatch();
        }
    }

    /**
     * Applies style spans without letting the viewport jump.
     *
     * <p>{@code setStyleSpans} re-runs the document update and can drop the virtual flow's scroll to the top:
     * measured as {@code estimatedScrollY} going 184 → 0 on the first highlight pass after a session restore,
     * a couple of pulses after the file had already painted at its saved caret. That is what showed as a
     * restored file jumping to line 1 on open. Restoring the scroll only when it actually collapsed to the
     * top keeps this inert for every normal re-highlight (typing, scrolling), where the flow doesn't reset.
     */
    private void setStyleSpansPreservingScroll(int from, StyleSpans<Collection<String>> spans) {
        Double before = area.estimatedScrollYProperty().getValue();
        area.setStyleSpans(from, spans);
        Double after = area.estimatedScrollYProperty().getValue();
        if (before != null && before > 1 && (after == null || after <= 1)) {
            area.estimatedScrollYProperty().setValue(before);
        }
    }

    private void applyHighlighting() {
        if (largeFile) {
            // Large-file mode: leave the document as plain text and drop any structure symbols/state.
            lineStates.clear();
            if (!symbols.isEmpty()) {
                symbols = List.of();
                onSymbolsChanged.run();
            }
            return;
        }
        if (csvRainbow && isCsv() && !heavyFile) {
            applyCsvRainbow();
            return;
        }
        if (grammar == null) {
            // No grammar for this file type: clear any previously applied styles so none linger.
            // This is cheap (a single span), so do it inline on the FX thread.
            int length = area.getLength();
            if (length > 0) {
                area.setStyleSpans(
                        0,
                        new StyleSpansBuilder<Collection<String>>()
                                .add(Collections.emptyList(), length)
                                .create());
            }
            lineStates.clear();
            if (!symbols.isEmpty()) {
                symbols = List.of();
                onSymbolsChanged.run();
            }
            return;
        }
        // Tokenizing is O(lines): re-highlight only from the first changed line to the end, reusing
        // the stored grammar end-states for the unchanged prefix. The work still runs on a background
        // thread (a generation counter discards stale results) and the result is re-validated against
        // the current document length before applying, since RichTextFX requires the spans to cover
        // their range exactly.
        // The start line is only valid if we have its predecessor's end-state; otherwise re-do all.
        int from = dirtyFromLine;
        if (from > 0 && (from - 1 >= lineStates.size() || lineStates.get(from - 1) == null)) {
            from = 0;
        }
        var startState = from == 0 ? null : lineStates.get(from - 1);
        String text = area.getText();
        IGrammar g = grammar;
        int fromLine = from;
        long gen = ++highlightGen;
        dirtyFromLine = Integer.MAX_VALUE; // captured; reset so new edits set a fresh dirty start
        HIGHLIGHT_POOL.execute(() -> {
            TextMateHighlighter.IncrementalAnalysis a;
            try {
                a = TextMateHighlighter.analyzeFrom(text, g, fromLine, startState);
            } catch (Exception | LinkageError e) {
                return; // never let a grammar/engine fault kill the highlighter thread
            }
            if (a == null || a.spans() == null) {
                return;
            }
            Platform.runLater(() -> {
                if (gen != highlightGen) {
                    return; // a newer edit superseded this pass
                }
                // The spans cover [fromOffset … end of captured text]; bail if the doc has changed.
                if (a.fromOffset() + a.spans().length() != area.getLength()) {
                    return;
                }
                StyleSpans<Collection<String>> spans = a.spans();
                // Overlay the server's semantic tokens on top of the lexical highlight (semantic wins
                // where present). Suppressed while stale (doc edited since the tokens were anchored).
                if (semanticActive && !semanticStale && !semanticTokens.isEmpty()) {
                    StyleSpans<Collection<String>> sem = buildSemanticSpans(a.fromOffset(), spans.length());
                    if (sem != null) {
                        spans = spans.overlay(sem, (lex, s) -> s.isEmpty() ? lex : s);
                    }
                }
                setStyleSpansPreservingScroll(a.fromOffset(), spans);
                scheduleBraceMatch(); // re-apply the match highlight the spans just overwrote
                // Replace per-line end-states from fromLine onward (the prefix is unchanged).
                while (lineStates.size() > fromLine) {
                    lineStates.remove(lineStates.size() - 1);
                }
                lineStates.addAll(a.endStates());
                // Symbols: keep those before fromLine (unchanged), append the freshly tokenized ones.
                List<TextMateHighlighter.Symbol> merged = new java.util.ArrayList<>();
                for (TextMateHighlighter.Symbol s : symbols) {
                    if (s.line() < fromLine) {
                        merged.add(s);
                    }
                }
                merged.addAll(a.symbols());
                symbols = merged;
                onSymbolsChanged.run();
            });
        });
    }

    /**
     * A sparse {@link StyleSpans} of length {@code windowLen} (so it can {@code overlay} the TextMate
     * spans starting at {@code windowStart}) carrying each cached semantic token's CSS class; gaps and
     * out-of-window tokens are empty styles. Returns {@code null} if no token falls in the window.
     *
     * <p>Tokens are sorted by position (the decoder preserves wire order), so a single forward cursor
     * builds the spans; an out-of-order or overlapping token is skipped defensively. Positions map
     * through the live document (the {@code area}) — the {@code editor} package stays {@code lsp}-free.
     */
    private StyleSpans<Collection<String>> buildSemanticSpans(int windowStart, int windowLen) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int cursor = 0; // offset within the window of the next unstyled char
        int paragraphs = area.getParagraphs().size();
        for (SemanticToken t : semanticTokens) {
            if (t.line() < 0 || t.line() >= paragraphs) {
                continue; // line no longer exists (shrunk doc); the stale guard usually precludes this
            }
            int col = Math.min(t.startChar(), area.getParagraphLength(t.line()));
            int off = area.getAbsolutePosition(t.line(), col) - windowStart;
            if (off < cursor || off >= windowLen) {
                continue; // before the cursor (overlap/out-of-order) or past the window end
            }
            int len = Math.min(t.length(), windowLen - off);
            if (len <= 0) {
                continue;
            }
            if (off > cursor) {
                b.add(Collections.emptyList(), off - cursor);
            }
            b.add(List.of(t.cssClasses().split(" ")), len);
            cursor = off + len;
        }
        if (cursor == 0) {
            return null; // no token in this window — skip the overlay entirely
        }
        if (cursor < windowLen) {
            b.add(Collections.emptyList(), windowLen - cursor);
        }
        return b.create();
    }
}
