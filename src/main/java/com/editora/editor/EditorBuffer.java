package com.editora.editor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import com.editora.completion.Completion;
import com.editora.completion.CompletionProvider;
import com.editora.diagram.DiagramKind;
import com.editora.editops.BraceMatcher;
import com.editora.editops.Commenter;
import com.editora.editops.HtmlLinks;
import com.editora.editops.Indenter;
import com.editora.editops.LineEndings;
import com.editora.editops.TextFingerprint;
import com.editora.editops.WholeDocumentEdit;
import com.editora.editor.completion.BufferCompletion;
import com.editora.editor.completion.CompletionEditTracker;
import com.editora.editor.completion.SnippetTyping;
import com.editora.editor.crypto.BufferEncryption;
import com.editora.editor.debug.BreakpointGutterMenu;
import com.editora.editor.debug.BreakpointManager;
import com.editora.editor.debug.DebugHover;
import com.editora.editor.debug.DebugValuePresenter;
import com.editora.editor.debug.ExecutionLine;
import com.editora.editor.debug.InlineValuesOverlay;
import com.editora.editor.fold.FoldManager;
import com.editora.editor.fold.NarrowCarry;
import com.editora.editor.fold.NarrowPresentation;
import com.editora.editor.fold.StickyScroll;
import com.editora.editor.fold.StickyScrollBar;
import com.editora.editor.git.BlameInfo;
import com.editora.editor.git.GitGutterLines;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.HighlightDirty;
import com.editora.editor.highlight.JsxContext;
import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.editor.http.HttpGutter;
import com.editora.editor.http.HttpHooks;
import com.editora.editor.http.HttpLineResult;
import com.editora.editor.http.HttpResultMarks;
import com.editora.editor.http.HttpVariableFixes;
import com.editora.editor.http.HttpVariableMarks;
import com.editora.editor.logviewer.LogHighlightOverlay;
import com.editora.editor.logviewer.LogView;
import com.editora.editor.lsp.CodeAction;
import com.editora.editor.lsp.LspDiagnosticOverlay;
import com.editora.editor.lsp.LspEditPlacement;
import com.editora.editor.lsp.LspEditShift;
import com.editora.editor.lsp.OccurrenceHighlightOverlay;
import com.editora.editor.markdown.DiagramImages;
import com.editora.editor.markdown.MarkdownCompletions;
import com.editora.editor.markdown.MarkdownEditing;
import com.editora.editor.markdown.MarkdownLintHover;
import com.editora.editor.markdown.MarkdownLintOverlay;
import com.editora.editor.markdown.MarkdownLintStripe;
import com.editora.editor.markdown.MermaidImages;
import com.editora.editor.markdown.MermaidLintHover;
import com.editora.editor.markdown.MermaidLintOverlay;
import com.editora.editor.markdown.PreviewFind;
import com.editora.editor.markdown.PreviewLayout;
import com.editora.editor.markdown.PreviewMenu;
import com.editora.editor.markdown.PreviewScrollSync;
import com.editora.editor.markdown.PreviewSurfaces;
import com.editora.editor.markdown.TypstImages;
import com.editora.editor.minimap.Minimap;
import com.editora.editor.notes.NoteDraft;
import com.editora.editor.notes.NoteHighlightOverlay;
import com.editora.editor.notes.NoteHover;
import com.editora.editor.notes.NoteManager;
import com.editora.editor.search.SearchHighlightOverlay;
import com.editora.editor.spell.BufferSpell;
import com.editora.editor.spell.SpellCheckOverlay;
import com.editora.editor.structured.PreviewSnapshots;
import com.editora.editor.todo.TodoHighlightOverlay;
import com.editora.editor.todo.TodoMark;
import com.editora.editor.todo.TodoMatcher;
import com.editora.editor.todo.TodoStripe;
import com.editora.editor.undo.CompletionUndoFactory;
import com.editora.editor.undo.CompletionUndoManager;
import com.editora.editor.undo.UndoHistory;
import com.editora.editor.undo.UndoMerge;
import com.editora.logviewer.LogLevel;
import com.editora.markdown.MarkdownInline;
import com.editora.markdown.MarkdownLint;
import com.editora.markdown.MarkdownTable;
import com.editora.maven.PomFiles;
import com.editora.snippet.ParsedSnippet;
import com.editora.snippet.Snippet;
import com.editora.snippet.SnippetSessions;
import com.editora.structured.StructuredParser;
import com.editora.text.FoldRegions;
import com.editora.text.LanguageRegistry;
import com.editora.text.LspDiagnostic;
import com.editora.text.LspTextEdit;
import com.editora.text.Modeline;
import com.editora.text.OccurrenceSpan;
import com.editora.text.SearchMatches;
import com.editora.text.SemanticToken;
import com.editora.text.Shebang;
import com.editora.typst.TypstMarkup;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.fxmisc.richtext.util.UndoUtils;
import org.fxmisc.undo.UndoManager;
import org.jspecify.annotations.Nullable;
import org.reactfx.Subscription;

/** A single open document: a RichTextFX {@link CodeArea} plus its backing file, language, and dirty state. */
public class EditorBuffer implements TabContent {

    final BufferCompletion completionActions = new BufferCompletion(new BufferCompletion.Host() {
        @Override
        public CodeArea area() {
            return area;
        }

        @Override
        public AnchorPane root() {
            return root;
        }

        @Override
        public @Nullable CodeArea area2() {
            return area2;
        }

        @Override
        public @Nullable AnchorPane root2() {
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
        public java.util.function.@Nullable Consumer<Character> signatureHelpRequester() {
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
            return EditorBuffer.this.caretOps.multiCaretActiveOn(a);
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
            EditorBuffer.this.snippetKeys.startSnippet(a, snippet, from, to, reindent);
        }

        @Override
        public com.editora.snippet.TabExpansion.@Nullable Match snippetTrigger(CodeArea a, boolean tabOnly) {
            if (tabOnly && !snippetKeys.snippetTabExpansion) {
                return null;
            }
            return SnippetTyping.triggerAtCaret(
                    a,
                    language,
                    isProse(),
                    tabOnly ? snippetKeys.snippetProvider : snippetKeys.snippetOfferProvider,
                    !tabOnly);
        }

        @Override
        public com.editora.editops.Abbrev.@Nullable Edit abbrevAtCaret(CodeArea a) {
            if (!snippetKeys.abbrevMode
                    || snippetKeys.applyingAbbrev
                    || hugeFile
                    || !isEditable()
                    || snippetKeys.abbrevTable.isEmpty()) {
                return null;
            }
            if (hasActiveSnippet()
                    || caretOps.multiCaretActiveOn(a)
                    || a.getSelection().getLength() > 0) {
                return null;
            }
            return snippetKeys.abbrevBefore(a, a.getCaretPosition());
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

    final CodeArea area = tagRename.newArea(null);
    final IndentKeys indentKeys = new IndentKeys(area, () -> this.language);
    final VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(area);
    /** The preview pane, its hosts and the rendering of every preview format ({@link BufferPreview}). */
    final BufferPreview previewPanes = new BufferPreview(this);
    /** The tab's view composition and its floating corner controls ({@link BufferLayout}). */
    final BufferLayout viewLayout = new BufferLayout(this);
    /** Auto-indent, auto-close, smart Tab and the other typing assists ({@link BufferTyping}). */
    final BufferTyping typingAids = new BufferTyping(this);
    /** Snippet keys and expansion, and abbrevs ({@link BufferSnippets}). */
    final BufferSnippets snippetKeys = new BufferSnippets(this);
    /** The right-click menu ({@link BufferContextMenu}). */
    final BufferContextMenu editorMenu = new BufferContextMenu(this);
    /** The gutter Run glyphs and their handlers ({@link BufferRunGutter}). */
    final BufferRunGutter runGutter = new BufferRunGutter(this);
    /** Inlay hints, colour swatches, code lenses and {@code .http} results drawn inline ({@link BufferInlays}). */
    final BufferInlays inlayDecor = new BufferInlays(this);
    /** The column rulers' columns and their deferred placement ({@link BufferRuler}). */
    final BufferRuler rulerPlacement = new BufferRuler(this);
    /** Syntax, semantic-token, CSV and bracket highlighting ({@link BufferHighlighter}). */
    final BufferHighlighter highlighter = new BufferHighlighter(this);
    /** The format bar and the AI actions bar over a selection ({@link SelectionBars}). */
    final SelectionBars selectionBars = new SelectionBars(this);
    /** Multiple cursors and column selection ({@link BufferCarets}). */
    final BufferCarets caretOps = new BufferCarets(this);
    /** TODO/FIXME highlights and jumps ({@link BufferTodos}). */
    final BufferTodos todoMarker = new BufferTodos(this);
    /** The diagnostic tooltip, document-highlight trigger and paste auto-import ({@link BufferLspAssists}). */
    final BufferLspAssists lspAssists = new BufferLspAssists(this);
    /** Emacs narrowing ({@link BufferNarrowing}). */
    final BufferNarrowing narrowing = new BufferNarrowing(this);
    /** The notice, install and View Mode bars above the text ({@link BufferBanners}). */
    final BufferBanners banners = new BufferBanners(this);

    final BooleanProperty dirty = new SimpleBooleanProperty(false);
    /**
     * The last saved/loaded content — its length and fingerprint, and the text itself only while it is still the
     * snapshot ({@link SavedBaseline}); the buffer is dirty only when the text differs from this. Null for a
     * truncated load, which has no baseline: it is never compared, and pins no copy once a log tail moves on.
     */
    private @Nullable SavedBaseline baseline = SavedBaseline.of("");
    /** True when the current content has no durable identity even if it equals the in-memory baseline. */
    private boolean forcedDirty;
    /** The line ending the file had when loaded or last saved; a different one is an unsaved change. */
    private String cleanLineEnding = LineEndings.LF;
    /** Orientation of an optional second, synced view of this document. */
    public enum Split {
        NONE,
        SIDE_BY_SIDE,
        STACKED
    }

    /** Wraps the scroll pane so we can overlay the column-80 ruler line and dock the minimap. */
    final AnchorPane root = new AnchorPane();
    /** Tab content: shows either {@link #root} alone or a SplitPane of [root, secondary view]. */
    final StackPane viewHost = new StackPane(root);
    /** Outermost tab content: a "View Mode" banner (top, read-only only) above {@link #viewHost}. */
    final javafx.scene.layout.BorderPane outer = new javafx.scene.layout.BorderPane(viewHost);
    /** The file's encryption and the views that stand in for its text ({@link BufferEncryption}). */
    final BufferEncryption encryption =
            new BufferEncryption(outer, viewHost, this::applyEditable, this::refreshTopBars);
    /** A second editable view sharing this document (created lazily on first split). */
    @Nullable
    CodeArea area2;

    @Nullable
    VirtualizedScrollPane<CodeArea> scrollPane2;
    /** The secondary view's container (scroll pane + its own minimap), mounted in the SplitPane. */
    @Nullable
    AnchorPane root2;

    @Nullable
    SecondaryPane pane2;

    private @Nullable Minimap minimap2;
    Split split = Split.NONE;

    /** IntelliJ-style Markdown preview modes (only meaningful for Markdown files). */
    public enum MarkdownViewMode {
        EDITOR,
        SPLIT,
        PREVIEW
    }

    MarkdownViewMode markdownViewMode = MarkdownViewMode.EDITOR;

    /** Which renderer a Markwhen preview uses (toggled per file, persisted like the view mode). */
    public enum MarkwhenView {
        TIMELINE,
        CALENDAR
    }

    MarkwhenView markwhenView = MarkwhenView.TIMELINE;
    /** Fired (FX thread) when the Markwhen view flips, so the controller persists it. */
    private Runnable onMarkwhenViewChanged = () -> {};
    /** Split scroll sync and position keeping between the editor and the preview ({@link PreviewScrollSync}). */
    PreviewScrollSync scrollSync;
    /** The floating log-viewer control overlaid top-right (Follow / level / regex; injected for log buffers). */
    @Nullable
    Node logControl;
    /** The CSV/TSV grid preview node (a ui-layer TableView panel), injected for CSV buffers when the feature
     *  is on; non-null doubles as the CSV-preview enablement gate (mirrors {@link BufferLayout#htmlPreviewControl}). */
    @Nullable
    Node csvPreviewNode;
    /** The HTTP response panel (a ui-layer {@code HttpClientPanel}), injected for {@code .http} buffers when
     *  the feature is on; non-null doubles as the HTTP-preview enablement gate (mirrors {@link
     *  #csvPreviewNode}). Unlike every other preview it is <b>not</b> derived from the buffer text — it shows
     *  the result of running a request — so the debounced pulse deliberately never re-renders it. */
    @Nullable
    Node httpPreviewNode;
    /** Structured-data (JSON/YAML/TOML) preview: on when the feature is enabled (pushed from settings). */
    private boolean structuredPreviewEnabled;
    /** SVG image preview for .svg files: on when the feature is enabled (pushed from settings). */
    private boolean svgPreviewEnabled;
    /** Typst document preview for .typ files: on when the feature is enabled (pushed from settings). */
    private boolean typstPreviewEnabled;
    /** Resolves the typst {@code --root} for a saved .typ file (project root for a multi-file doc); injected
     *  from MainController since the editor package can't reach project state. Null ⇒ use the file's folder. */
    java.util.function.@Nullable UnaryOperator<java.nio.file.Path> typstRootResolver;
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
    boolean pomShowXml;
    /** Forces log-viewer mode on a buffer whose extension isn't {@code .log} ("View as Log"). */
    private boolean logViewForced;
    /** Filter, follow and trim state of the log viewer; a filtered view is read-only (see {@link LogView}). */
    final LogView logView = new LogView(area, this::applyEditable);
    /** Fired from the debounced edit pulse while an HTML preview is served (its site reloads on any file's edit). */
    @Nullable
    Runnable htmlPreviewDirtyListener;
    /** One document subscription and timer sequence for all differently-timed settled-edit work. */
    private final SettledEditDispatcher settledEdits = new SettledEditDispatcher();

    private @Nullable Subscription settledEditSub;
    static final ExecutorService HIGHLIGHT_POOL = Executors.newFixedThreadPool(
            Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2)),
            daemonFactory("editor-highlighter"));

    /** A daemon {@link java.util.concurrent.ThreadFactory} naming threads {@code <name>-N}. */
    static java.util.concurrent.ThreadFactory daemonFactory(String name) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    Runnable onViewModeChanged = () -> {};
    /** Files at/above this size skip syntax highlighting and the minimap to stay responsive. */
    public static final long LARGE_FILE_BYTES = 5L * 1024 * 1024;
    /** Files at/above this size are opened read-only (and truncated by the loader). */
    public static final long HUGE_FILE_BYTES = 50L * 1024 * 1024;
    /** Whether the minimap is shown; applied to every split pane's minimap. */
    boolean minimapVisible = true;
    /** Whether this buffer is the visible tab (see {@link #setRenderingActive}); starts true because a
     *  freshly built buffer measures itself before the controller marks background tabs inactive. */
    boolean renderingActive = true;
    /** Large-file mode: syntax highlighting and the minimap are disabled regardless of settings. */
    boolean largeFile;
    /** Intermediate "large source file" tier (below the 5 MB hard mode): the minimap and LSP are
     *  disabled — but syntax highlighting and editing stay — so a very long single file (e.g. a
     *  13k-line source) stays responsive. Triggered by line count at load (see {@code setHeavyFile});
     *  toggleable per-buffer. {@code largeFile} implies the same minimap/LSP suppression. */
    boolean heavyFile;
    /** Huge-file mode: implies large-file mode plus read-only (no undo, not editable). */
    boolean hugeFile;
    /** The loader could only read part of this file (huge-file cap / log tail) — saving would truncate it. */
    boolean truncatedLoad;
    /** User "View mode": non-editable but keeps all normal editor features (separate from huge-file). */
    boolean viewMode;
    /** Temporary non-editable shell while file content is loaded; never presented as user View mode. */
    boolean loading;
    /** The most recently focused view (primary or secondary); drives "active area" for commands. */
    CodeArea focusedArea = area;
    /** {@link #focusedArea} as a value to observe, for what shows the caret of the view the user is in. */
    final javafx.beans.property.ReadOnlyObjectWrapper<CodeArea> focusedView =
            new javafx.beans.property.ReadOnlyObjectWrapper<>(area);
    /** Opens a URL externally (injected from the controller's HostServices); Ctrl/Cmd-click a link. */
    java.util.function.Consumer<String> openUrlHandler = u -> {};
    /** Injected by the controller: opens the table-size picker, then inserts a table. */
    @Nullable
    Runnable insertTypstTableHandler;

    @Nullable
    Runnable typstImagePasteHandler;
    /** The preview's right-click menu: its export/print actions and what the pointer was on ({@link PreviewMenu}). */
    final PreviewMenu previewMenu = new PreviewMenu(this, () -> previewPanes.markdownPreview);
    /** Active snippet expansion (Tab cycles its fields), or null when none is in progress. */
    final SnippetSessions snippetSession = new SnippetSessions(CompletionUndoManager::joinLast);
    /**
     * Monotonic count of text changes to this buffer's document — a cheap "has the document moved under me"
     * stamp for the async/deferred completion paths (a caret offset alone can't tell: an edit can land the
     * caret back on the same offset). Bumped for edits made in either view (they share the document).
     */
    long docVersion;

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

    final ColumnRuler columnRuler = new ColumnRuler(area);
    final Minimap minimap = new Minimap(area);
    final JumpReveal jumps = new JumpReveal(
            () -> this.stickyScrollEnabled,
            a -> a == focusedArea,
            () -> this.folds.regions(),
            this::stickyLinesFor,
            () -> this.stickyLines.size());
    /** Lazily attached when "show whitespace" first turns on — see {@link #whitespace()}. */
    private @Nullable WhitespaceOverlay whitespace;
    /** Lazily attached when indent guides first turn on — see {@link #indentGuides()}. */
    private @Nullable IndentGuideOverlay indentGuides;
    /** Eager: spell checking is on by default for prose and for the comments and strings of code. */
    private final SpellCheckOverlay spellOverlay = new SpellCheckOverlay(area, this::documentTextSnapshot);

    private @Nullable LogHighlightOverlay logOverlay; // lazily attached on first activation — see logOverlay()
    private @Nullable InlineValuesOverlay inlineValues; // lazily attached when a debug session first suspends here
    /** Git change bars + blame column, kept on the right lines through unsaved edits. */
    final GitGutterLines gitLines = new GitGutterLines(area, dirty, this::refreshGutter, minimap);
    /** Fixed annotation-column width in px, computed from the widest author+date when blame is set, so
     *  line numbers stay aligned regardless of which row's gutter is (re)built. */
    private double blameColumnWidth;
    /** Render size (px) of the blame annotation text — must match {@code .blame-author}/{@code .blame-date}
     *  in {@code app.css} so the measured column width matches what's actually drawn (not the editor font). */
    private static final double BLAME_FONT_SIZE = 10;

    private @Nullable MermaidLintOverlay lintOverlay; // lazily attached — see lintOverlay()
    private @Nullable MarkdownLintOverlay
            mdLintOverlay; // lazily attached when Markdown linting turns on — see mdLintOverlay()
    /** Squiggles under unresolvable {{variables}} of a .http buffer (on the same overlay as Markdown lint). */
    private final HttpVariableMarks httpVariableMarks = new HttpVariableMarks(
            this::mdLintOverlay,
            this::documentTextSnapshot,
            () -> this.httpFeatureEnabled && isHttpFile() && !this.hugeFile,
            () -> this.markdownLintEnabled);

    @Nullable
    LspDiagnosticOverlay lspOverlay; // lazily attached — see lspOverlay()
    /** Severity stripe over the editor scrollbar (shown whenever LSP is active), so diagnostics stay locatable;
     *  lazily attached when LSP first activates for this buffer — see {@link #diagnosticStripe()}. */
    private @Nullable DiagnosticStripe diagnosticStripe;

    /** TODO/highlight overview stripe over the scrollbar (beside the diagnostic stripe); lazily attached with
     *  the first non-empty scan result — see {@link #todoStripe()}. */
    @Nullable
    TodoStripe todoStripe;
    /** Markdown-lint overview stripe over the scrollbar (beside the TODO/diagnostic stripes); lazily attached
     *  with the Markdown-lint overlay — see {@link #mdLintStripe()}. */
    private @Nullable MarkdownLintStripe mdLintStripe;
    /** Whether the HTTP Client feature is enabled for this buffer (gated by {@code Settings.httpClientSupport},
     *  Simple UI mode and a local file) — a {@code .http} file then shows a Run glyph on every request line. */
    boolean httpFeatureEnabled;
    /** A {@code .http} buffer's request ▶s, the running ■ and the injected {@link HttpHooks}. */
    final HttpGutter httpGutter = new HttpGutter();
    /** A {@code .http} buffer's last results after its request lines, and the ▶ colours they give (U11). */
    final HttpResultMarks httpResults = new HttpResultMarks();

    @Nullable
    SearchHighlightOverlay searchOverlay; // lazily attached — see searchOverlay()

    private @Nullable OccurrenceHighlightOverlay occurrenceOverlay; // lazily attached — see occurrenceOverlay() (#675)

    /** Highlights configured TODO/FIXME-style patterns (per-pattern color), behind the text; lazily attached
     *  with the first non-empty scan result (TODO highlighting is on by default, but most files have no
     *  marks) — see {@link #todoOverlay()}. */
    @Nullable
    TodoHighlightOverlay todoOverlay;

    private @Nullable AceJumpOverlay aceJump; // lazily attached — see aceJump()
    /** Async maid validator (text, callback) injected by the controller; null = no linting. */
    private java.util.function.@Nullable BiConsumer<
                    String, java.util.function.Consumer<java.util.List<com.editora.mermaid.MaidOutput.Diagnostic>>>
            mermaidValidator;

    private boolean mermaidLintEnabled;
    /** Async Markdown linter (text, callback) injected by the controller; null = no linting. */
    private java.util.function.@Nullable BiConsumer<
                    String, java.util.function.Consumer<java.util.List<MarkdownLint.Diagnostic>>>
            markdownLintValidator;

    private boolean markdownLintEnabled;
    /** What the controller last asked for Markdown lint (the setting), re-applied when the language changes. */
    private boolean markdownLintWanted;
    /** Image files and browser images dropped onto a Markdown or Typst buffer; see {@link ImageDrop}. */
    private final ImageDrop imageDrop =
            new ImageDrop(() -> (isMarkdown() || isTypst()) && isEditable(), this::isMarkdown, this::caretToDrop);
    /** LSP: overlay active (diagnostics + hover), the debounced didChange sink, and the hover tooltip. */
    boolean lspActive;

    final java.util.List<CompletionEditTracker> completionEditTrackers = new java.util.ArrayList<>();

    private java.util.function.@Nullable Consumer<String> lspChangeListener;
    /** {@link #docVersion} of the document text last sent to the server, so a send whose content hasn't changed
     *  since (the completion flush and the debounced pulse both fire for one edit) skips the whole-document
     *  {@code getText()} + {@code didChange}. Reset to -1 on LSP (de)activation so a re-attach always re-sends. */
    private long lastLspSentVersion = -1;
    /** Debounced pull-diagnostics request (servers that answer textDocument/diagnostic); null = none. */
    private @Nullable Runnable lspDiagnosticsRequester;
    /** Debounced semantic-tokens request (servers advertising range semantic tokens); null = none. */
    private @Nullable Runnable semanticTokensRequester;
    /** Debounces a viewport semantic-tokens re-request after scrolling settles (so a new region gets
     *  tokens without firing a server request per scroll frame). */
    private final javafx.animation.PauseTransition semanticScrollDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
    /** Completion trigger characters the server advertised (e.g. {@code .} for Java, {@code <} for HTML). */
    private java.util.Set<Character> lspTriggerChars = java.util.Set.of();
    /** Signature-help trigger chars (usually '(' and ',') — see setLspSignatureTriggerChars (#674). */
    private java.util.Set<Character> lspSignatureTriggerChars = java.util.Set.of();
    /** Fired (deferred a pulse) when a signature trigger char is typed; null = disabled. */
    private java.util.function.@Nullable Consumer<Character> signatureHelpRequester;

    final FoldManager folds = new FoldManager(area, this::documentTextSnapshot);

    /** The gutter numbers, {@code narrowed} style and banner of a narrowed buffer (see {@link NarrowPresentation}). */
    final NarrowPresentation narrowView = new NarrowPresentation(this, area, logView, folds);

    /** Pinned enclosing-scope headers over the top of the code pane; see {@link StickyScroll}. */
    final StickyScrollBar stickyScroll = new StickyScrollBar();

    private boolean stickyScrollEnabled;
    private boolean stickyScrollPending;
    final BookmarkManager bookmarks = new BookmarkManager(area);
    /** Handles a bookmark add/remove request for a line (from the right-click menu): the controller adds,
     *  or confirms a removal. Default: toggle. */
    java.util.function.BiConsumer<EditorBuffer, Integer> bookmarkToggleRequest =
            (buffer, line) -> buffer.toggleBookmark(line);
    /** Breakpoints for this buffer (gutter strip + persistence + sent to a live DAP session). */
    final BreakpointManager breakpoints = new BreakpointManager(area);
    /** The values pinned to this buffer's lines while debugging (painted at each line's end while paused). */
    final com.editora.editor.debug.PinManager pins = new com.editora.editor.debug.PinManager(area);
    /** When true, the leftmost breakpoint strip is reserved + clickable (debugging is enabled). */
    private boolean debugEnabled = false;
    /** Handles a breakpoint-strip click; default toggles. The controller overrides to persist + re-send. */
    private java.util.function.BiConsumer<EditorBuffer, Integer> gutterBreakpointClick =
            (buffer, line) -> buffer.toggleBreakpoint(line);
    /** Handles a Shift+click on the breakpoint strip; default does what a plain click does. The controller asks
     *  for a log message and makes the line a logpoint. */
    private java.util.function.BiConsumer<EditorBuffer, Integer> gutterLogpointClick =
            (buffer, line) -> buffer.gutterBreakpointClick.accept(buffer, line);
    /** A right-click on a breakpoint's gutter glyph (the controller shows Edit / Disable / Remove). */
    private BreakpointGutterMenu gutterBreakpointMenu = (buffer, line, e) -> {};
    /** Personal Notes for this buffer (inline marker + highlight + hover). */
    final NoteManager notes = new NoteManager(area);

    private final NoteHighlightOverlay noteOverlay = new NoteHighlightOverlay(area);
    /** When false, the Personal Notes feature is disabled for this buffer (no "Add Note" menu items). */
    boolean notesEnabled = false;
    /** When false, the note inline marker + highlight are hidden (the {@code showNoteIndicators} setting). */
    private boolean noteIndicators = true;
    /** Invoked when the user clicks a note's inline start marker — the controller opens the note editor. */
    private java.util.function.BiConsumer<EditorBuffer, com.editora.config.PersonalNote> noteMarkerClick = (b, n) -> {};
    /** The popup over a note's span and the click on its start marker ({@link NoteHover}). */
    private final NoteHover noteHover = new NoteHover(
            notes,
            () -> noteIndicators,
            n -> noteMarkerClick.accept(this, n),
            () -> this.fontFamily,
            () -> this.fontSize,
            () -> this.path != null ? this.path.getParent() : null);

    /** Handles a click on a line's blame annotation (the controller shows that line's commit). Default: no-op. */
    private java.util.function.BiConsumer<EditorBuffer, Integer> gutterBlameClick = (buffer, line) -> {};
    /** Invoked by the "Add Personal Note" context-menu item (the controller prompts + creates). */
    java.util.function.Consumer<EditorBuffer> addNoteHandler = b -> {};
    /** LSP navigation actions (controller-supplied); shown in the context menu only while LSP is active. */
    Runnable lspGotoDefinitionAction = () -> {};

    Runnable lspCodeActionsAction = () -> {};
    /** Whether this buffer's server advertises code actions (quick fixes) — gates the menu item (#670). */
    boolean lspCodeActionsAvailable;

    /** The code-action lightbulb on the caret line (the coordinator gives it its probe). */
    private final CodeActionBulb bulb = new CodeActionBulb(
            focusedView.getReadOnlyProperty(),
            () -> lspActive && lspCodeActionsAvailable && !largeFile && isEditable(),
            this::docVersion,
            this::refreshGutterLine);

    /** The file's problem counts at the top right of the editor. */
    private final ProblemCounterSlot problemCounter = new ProblemCounterSlot(
            () -> lspActive && !largeFile, viewLayout::placeCornerControl, viewLayout::removeCornerControl);
    /** Chars of context captured before/after a note's selection (for re-anchoring). */
    private static final int CONTEXT_CHARS = 40;

    @Nullable
    Path path;
    /** Suggested name for a still-unsaved buffer (e.g. from {@code --new-file=foo.txt}); drives the tab
     *  title and extension-based highlighting while {@link #path} stays null (so Save prompts Save-As). */
    @Nullable
    String displayName;
    /** Last-known on-disk identity used to detect external changes; modified time -1 means unknown. */
    private long diskModifiedMillis = -1;

    private long diskSize = -1;
    /** SHA-256 of the exact bytes last loaded/saved; used for remote save conflict detection. */
    private @Nullable String diskFingerprint;
    /** Language name for the current file (drives fold strategy); see {@link LanguageRegistry}. */
    String language = LanguageRegistry.plaintext();
    /** TextMate grammar for the current file, or {@code null} when no grammar is bundled. */
    @Nullable
    IGrammar grammar;
    /** {@code --source N} version from a Java compact-source shebang (for the run command), else null. */
    @Nullable
    Integer shebangJavaSource;
    /** True once the user explicitly picked a language (status bar), so shebang detection won't fight it. */
    private boolean languageUserOverride;
    /** Spell checking (Lucene Hunspell, drawn by {@link #spellOverlay}); off until the controller enables it. */
    final BufferSpell spell = new BufferSpell(
            area, spellOverlay, this::getFocusedArea, () -> language, this::isEditable, () -> largeFile);
    /** Bumped on every language/grammar change (FX thread only); drops a stale deferred grammar load. */
    private long languageGen;
    /** Named definitions from the last tokenization (FX-thread confined); drives the Structure view. */
    List<TextMateHighlighter.Symbol> symbols = List.of();
    /** Notified (on the FX thread) after {@link #symbols} is refreshed. */
    Runnable onSymbolsChanged = () -> {};

    String fontFamily = "monospace";
    int fontSize = 14;
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
    int tabSize = 4;
    // EditorConfig overrides (null = no override → fall back to detection/global). See com.editora.editorconfig.
    /** One full-text materialization shared by every consumer of the current {@link #docVersion}. */
    private final DocumentSnapshots documentSnapshots = new DocumentSnapshots();

    /** Per-buffer Emacs mark ring (session-only); shifted through edits by the subscription in the ctor. */
    private final com.editora.editops.MarkRing markRing = new com.editora.editops.MarkRing();

    private @Nullable Boolean indentInsertSpacesOverride;
    private @Nullable Integer indentSizeOverride;
    private @Nullable String eolOverride; // "LF"/"CRLF"/"CR" forced by EditorConfig end_of_line; null = none
    /** The file's own line ending: detected on load or chosen by a conversion. The document holds bare LF. */
    private String lineEnding = LineEndings.LF;

    private String detectedCharset = com.editora.editorconfig.EditorConfigCharset.UTF_8;
    private @Nullable String charsetOverride; // EditorConfig charset to write; null = keep detected
    /** The declared charset could not decode the file, so {@link #detectedCharset} is a lossless stand-in. */
    private boolean charsetAssumed;
    /** The encoding the user chose (Reopen / Save with Encoding); outranks EditorConfig. Null = none. */
    private @Nullable String userCharset;

    private com.editora.editorconfig.EditorConfigProperties editorConfigProps =
            com.editora.editorconfig.EditorConfigProperties.EMPTY;
    private boolean lineNumbersVisible = true;
    /** When false (Simple UI mode), the entire gutter is removed (null paragraph-graphic factory) — no
     *  line numbers, fold chevrons, bookmark/note/run/breakpoint slots, or git change bars. */
    private boolean gutterVisible = true;
    /** Max undo entries kept per view; caps undo memory (RichTextFX defaults to unlimited). */
    private static final int UNDO_HISTORY = 300;

    public void setCompletionProvider(CompletionProvider provider) {
        completionActions.setCompletionProvider(MarkdownCompletions.provider(provider, this::getPath));
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

    /**
     * Shows {@code actions} at the caret and calls {@code onAccept} with the chosen one.
     *
     * <p>Focus stays in the editor — the popup is focus-less and this buffer's key filter drives it — which
     * is why the caret keeps blinking where the fix will land while the list is open.
     */
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

    /** Opens the completion list with only what {@code filter} keeps of the server's items (Smart Type). */
    public void triggerCompletion(java.util.function.UnaryOperator<List<com.editora.completion.Completion>> filter) {
        completionActions.triggerCompletion(filter);
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
        folds.setBulbHooks(
                bulb::kindAt,
                line -> bulb.click(line, lspCodeActionsAction), // Alt+Enter's list on the bulb's line
                bulb::tooltip);
        folds.setSplitViews(() -> focusedArea, () -> area2);
        // Personal-Notes markers are drawn inline at each note's start by noteOverlay (no gutter slot): a change
        // to the note set repaints the overlay, never the gutter (the overlay follows edits on its own).
        notes.setOnSpansChanged(noteOverlay::refresh);
        gitLines.attach(folds); // change bars: slot reserved only while tracking is on; hunk text on hover
        // Gutter Run glyph: reserved for a runnable file — one entry line for a script, or one per
        // request for a .http file.
        folds.setRunHooks(
                () -> runGutter.runnable || !runGutter.mainLines.isEmpty(),
                runGutter::isRunGlyphLine,
                runGutter::onRunGlyph,
                line -> runGutter.testLines.containsKey(line)
                        ? "test-run-marker"
                        : line == httpGutter.runningGlyphLine(this::isFoldedLine)
                                ? FoldManager.HTTP_RUNNING_CLASS
                                : httpResults.glyphClass(line), // the ▶ of a request that ran: its outcome
                runGutter::runGlyphTooltip);
        // Gutter breakpoint strip: reserved only while debugging is enabled; click toggles a breakpoint.
        folds.setBreakpointHooks(
                () -> debugEnabled,
                breakpoints::hasGlyph, // a line breakpoint, or a method breakpoint's / field watchpoint's mark
                breakpoints::glyphClasses,
                line -> gutterBreakpointClick.accept(this, line));
        folds.setBreakpointShiftClick(line -> gutterLogpointClick.accept(this, line)); // IntelliJ's logpoint gesture
        folds.setBreakpointTooltip(breakpoints::glyphTooltip);
        folds.setDebugGutterHooks(
                i -> executionLine.isMarked(area, i), (l, e) -> gutterBreakpointMenu.show(this, l, e));
        // Gutter blame "Annotate" column (leftmost): reserved only while blame is on; the per-line
        // author/date/heatmap come from the controller-supplied list, click shows that line's commit.
        folds.setBlameHooks(
                this::isBlameOn,
                gitLines::blameAt,
                () -> blameColumnWidth,
                line -> gutterBlameClick.accept(this, line));
        breakpoints.setOnLinesRepaint(lines -> Platform.runLater(() -> lines.forEach(this::refreshGutterLine)));
        typingAids.addViewModePaging(area); // Space/Backspace = page down/up while in read-only View mode
        completionActions.addCompletionKeys(area); // popup owns Enter/Tab before snippet and indentation filters
        completionActions.installCommitCharacters(area);
        snippetKeys.addSnippetKeys(area); // Tab expands/cycles snippets (else falls through to indent)
        snippetSession.setOnChanged(snippetKeys::snippetSessionChanged);
        focusedView.addListener((o, was, now) -> snippetSession.focusMovedTo(now)); // the other split view
        // Auto-close ()[]{} and quotes: before auto-indent, so it sees the keystroke first.
        typingAids.addAutoClose(area);
        typingAids.addAutoIndent(area); // Enter auto-indents; closers de-indent (per-language smart indent)
        completionActions.installCompletionTrigger(area);
        lspAssists.installOccurrenceTrigger(area); // LSP document highlight (#675)
        inlayDecor.installCodeLensClick(area);
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
                int[] before = LspEditPlacement.endAfter(start, c.getRemoved());
                int[] after = LspEditPlacement.endAfter(start, c.getInserted());
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
            inlayDecor.shiftCodeLenses(changes);
            if (httpResults.shift(changes, area)) {
                inlayDecor.refreshInlayAreas(); // a request's result follows it when lines are added above
            }
            gitLines.edited(changes, this::refreshGutterLine, area2); // bars + blame follow inserted/removed lines
            for (var change : changes) {
                int removed = change.getRemoved().length();
                int inserted = change.getInserted().length();
                highlighter.highlightDirty.edited(change.getPosition(), removed, inserted);
                if (highlighter.semanticActive) {
                    highlighter.semanticDirty.edited(change.getPosition(), removed, inserted);
                }
                // The matched pair moves with the text, so the next re-match clears it where it now is: a
                // pass no longer restyles everything below an edit, which used to hide a stale position.
                int[] pair = completionActions.braceMatch();
                if (pair != null) {
                    pair[0] = HighlightDirty.moved(pair[0], change.getPosition(), removed, inserted);
                    pair[1] = HighlightDirty.moved(pair[1], change.getPosition(), removed, inserted);
                }
            }
            // The cached semantic tokens now point at shifted offsets; suppress the overlay until the
            // next response re-anchors them (one boolean write — off the per-char path's cost budget). The
            // generation bump invalidates any in-flight request so its (now-stale) response is dropped.
            if (highlighter.semanticActive) {
                highlighter.semanticGen++;
                highlighter.semanticStale = true;
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
            if ((highlighter.semanticActive || inlayHintsActive) && semanticTokensRequester != null) {
                semanticTokensRequester.run(); // drives semantic tokens AND inlay hints (#681)
            }
        });
        // Dirty only when the content differs from the last saved/loaded text, so reverting an edit
        // (undo or manual) clears the marker. Driven off plainTextChanges (not textProperty): subscribing
        // to textProperty would force RichTextFX to materialize the whole document String on every
        // keystroke — O(n) allocation per char on a very large single buffer (e.g. minified JS on one
        // line, past the line-count heavy-file tier). See dirtyAfterEdit for when the text is compared.
        // A log filter or followed append rewrites the area without being a user edit: dirty is carried over.
        area.plainTextChanges()
                .filter(c -> !logView.adjusting())
                .subscribe(c -> dirtyAfterEdit(
                        c.getRemoved().length() == c.getInserted().length()));
        area.plainTextChanges().subscribe(c -> wrap.inserted(c.getInserted())); // a giant token holds wrap off
        // Auto-fill: break the line at a word boundary when it grows past the fill column (off by default,
        // so the very first check short-circuits for every buffer that hasn't turned it on).
        area.plainTextChanges().subscribe(typingAids::maybeAutoFill);
        // Abbrev auto-expand: when abbrev-mode is on, typing a word terminator expands the word before it.
        area.plainTextChanges().subscribe(snippetKeys::maybeExpandAbbrev);
        area.caretPositionProperty().addListener((obs, old, now) -> {
            resetGoalColumn();
            scheduleBraceMatch();
            bulb.schedule();
        });
        area.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                focusedArea = area;
                focusedView.set(area);
            }
        });
        editorMenu.installContextMenu(area);
        installFormatBarListeners(area);
        scrollSync = new PreviewScrollSync(
                area, () -> markdownViewMode, () -> previewPanes.previewPane, () -> previewPanes.markdownPreview);
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
            sealBaselineIfDirty();
            highlighter.applyHighlighting();
            runGutter.recomputeRun(true); // re-evaluate the Run glyph when a top-level main / __main__ appears/leaves
        });
        settledEdits.at(
                Duration.ofMillis(250),
                () -> htmlPreviewDirtyListener != null
                        || (previewLive() && markdownViewMode != MarkdownViewMode.EDITOR),
                () -> {
                    if (htmlPreviewDirtyListener != null) {
                        htmlPreviewDirtyListener.run();
                    }
                    if (previewLive() && markdownViewMode != MarkdownViewMode.EDITOR) {
                        previewPanes.scheduleRenderPreview();
                    }
                });
        settledEdits.at(
                Duration.ofMillis(90),
                () -> lspActive
                        && completionActions.autocompleteEnabled()
                        && focusedArea != null
                        && focusedArea.isFocused(),
                () -> completionActions.updateCompletion(focusedArea, false));
        settledEdits.at(
                Duration.ofMillis(280),
                () -> !lspActive
                        && completionActions.autocompleteEnabled()
                        && focusedArea != null
                        && focusedArea.isFocused(),
                () -> completionActions.updateCompletion(focusedArea, false));
        settledEdits.at(
                Duration.ofMillis(300),
                () -> todoMarker.todoEnabled || lspActive || highlighter.semanticActive || inlayHintsActive,
                () -> {
                    if (todoMarker.todoEnabled) {
                        todoMarker.refreshTodoMarks();
                    }
                    if (lspActive) {
                        sendLspChange();
                    }
                    if (lspActive && lspDiagnosticsRequester != null) {
                        lspDiagnosticsRequester.run();
                    }
                    if ((highlighter.semanticActive || inlayHintsActive) && semanticTokensRequester != null) {
                        semanticTokensRequester.run(); // drives semantic tokens AND inlay hints (#681)
                    }
                });
        settledEdits.at(
                UndoMerge.PAUSE,
                () -> !disposed && !largeFile && area.getLength() <= UNDO_HISTORY_MAX_BYTES,
                this::captureUndoCheckpoint);
        settledEdits.at(
                Duration.ofMillis(450),
                () -> mermaidLintEnabled || markdownLintEnabled || httpVariableMarks.enabled(),
                () -> {
                    scheduleMermaidLint();
                    scheduleMarkdownLint();
                    httpVariableMarks.schedule();
                });
        settledEdits.at(
                Duration.ofMillis(600),
                () -> completionActions.aiCompletionEnabled() && focusedArea != null && focusedArea.isFocused(),
                () -> completionActions.maybeRequestAiCompletion(focusedArea));
    }

    /** A Markdown document up to this long keeps its live preview even in large-file mode (see below). */
    public static final int PREVIEW_LIVE_MAX_CHARS = 2_000_000;

    /**
     * Whether an edit refreshes the preview. Large-file mode stops it — except for a Markdown document that
     * is "large" only for one very long line (a pasted {@code data:} image passes the 64K-character line
     * limit at once): its preview used to render once and then silently stay as it was.
     */
    boolean previewLive() {
        return !largeFile || (isMarkdown() && area.getLength() <= PREVIEW_LIVE_MAX_CHARS);
    }

    /** Show/reposition the Markdown format bar + the AI selection-actions bar as the selection or scroll
     *  changes (each coalesced per pulse; {@link SelectionBars#updateFormatBar()} always runs first so the AI bar can
     *  stack above it when both apply to the same selection). */
    private void installFormatBarListeners(CodeArea a) {
        markdownEditing.attach(a, selectionBars::scheduleFormatBar);
        a.selectionProperty().addListener((obs, old, now) -> {
            selectionBars.scheduleFormatBar();
            selectionBars.scheduleAiActionsBar();
            selectionChanged.run();
        });
        // One vertical-scroll listener for the four per-scroll features (each gated on its own flag and
        // coalesced per pulse), in the order the former separate listeners ran: RichTextFX notifies every
        // listener on every scroll step, so three of them cost three dispatches for one boolean each.
        a.estimatedScrollYProperty().addListener((obs, old, now) -> {
            selectionBars.scheduleFormatBar();
            selectionBars.scheduleAiActionsBar();
            scheduleStickyScroll();
            if (highlighter.semanticActive) {
                semanticScrollDebounce.playFromStart(); // re-request tokens for the scrolled-to region
            }
        });
        a.estimatedScrollXProperty().addListener((obs, old, now) -> {
            selectionBars.scheduleFormatBar();
            selectionBars.scheduleAiActionsBar();
        });
        a.focusedProperty().addListener((obs, was, now) -> {
            if (!now) {
                selectionBars.hideFormatBar();
                selectionBars.hideAiActionsBar();
            }
        });
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (caretOps.multiCaretActiveOn(a)) { // suspend single-caret assists while multiple carets exist
                return;
            }
            if (e.getCode() == KeyCode.ESCAPE) {
                selectionBars.hideFormatBar(); // don't consume — let other Escape behavior run
                selectionBars.hideAiActionsBar();
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
                if (isLspActive() || HtmlLinks.isHtml(language) && HtmlLinks.at(a.getText(), off) != null) {
                    // Move the caret to the clicked symbol (an HTML link: B6) + focus this area, then go-to-definition
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
        completionActions.setLinkOpener(this.openUrlHandler = handler == null ? u -> {} : handler);
    }

    /** The external-URL opener injected by {@link #setOpenUrlHandler} (a no-op until then). */
    public java.util.function.Consumer<String> openUrlHandler() {
        return openUrlHandler;
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

    public void setFormatBarEnabled(boolean enabled) {
        selectionBars.setFormatBarEnabled(enabled);
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

    /** Shows a jump's target line in one of this buffer's views, clear of the sticky-scroll rows. */
    public JumpReveal jumps() {
        return jumps;
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
        viewLayout.placeStickyScroll();
        stickyLines = stickyScroll.pinnedLines(folds.regions(), firstVisible);
        stickyScroll.update(focusedArea, stickyLines, getTabSize(), this::jumpToLine);
    }

    public void setAiActionHandlers(Runnable onExplainSelection, Runnable onRewriteSelection) {
        selectionBars.setAiActionHandlers(onExplainSelection, onRewriteSelection);
    }

    void requestExplainSelection() {
        selectionBars.requestExplainSelection();
    }

    void requestRewriteSelection() {
        selectionBars.requestRewriteSelection();
    }

    public void setAiActionsEnabled(boolean enabled) {
        selectionBars.setAiActionsEnabled(enabled);
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
        int line = a.offsetToPosition(span[0], org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        Commenter.Edit edit = JsxContext.lineInChildren(
                        grammar,
                        highlighter.highlightLines,
                        line,
                        a.getParagraph(line).getText())
                ? Commenter.toggle(a.getText(), span[0], span[1], JsxContext.COMMENT) // {/* */} in markup (A103)
                : Commenter.toggle(a.getText(), span[0], span[1], language, getPath());
        if (edit == null) {
            return false;
        }
        a.replaceText(edit.from(), edit.to(), edit.replacement());
        a.selectRange(edit.selStart(), edit.selEnd());
        a.requestFocus();
        return true;
    }

    final MarkdownEditing markdownEditing = new MarkdownEditing(this);

    /** The markup editing commands of this buffer (inline markers, headings, lists, tables, TOC). */
    public MarkdownEditing markdown() {
        return markdownEditing;
    }

    public void formatInline(String marker) {
        markdownEditing.formatInline(marker);
    }

    public void formatLink(String url) {
        markdownEditing.formatLink(url);
    }

    public void formatLinkFromClipboard() {
        markdownEditing.formatLinkFromClipboard();
    }

    public boolean trySmartLinkPaste() {
        return markdownEditing.trySmartLinkPaste();
    }

    public void formatHeading(int delta) {
        markdownEditing.formatHeading(delta);
    }

    public void setHeadingLevel(int level) {
        markdownEditing.setHeadingLevel(level);
    }

    public void formatBulletList() {
        markdownEditing.formatBulletList();
    }

    public void formatTaskList() {
        markdownEditing.formatTaskList();
    }

    public boolean reflowTable() {
        return markdownEditing.reflowTable();
    }

    public void insertTable(int rowsTotal, int cols) {
        markdownEditing.insertTable(rowsTotal, cols);
    }

    public void insertTableInteractive() {
        markdownEditing.insertTableInteractive();
    }

    public void setInsertTableHandler(Runnable handler) {
        markdownEditing.setInsertTableHandler(handler);
    }

    public boolean tableAddRow() {
        return markdownEditing.tableAddRow();
    }

    public boolean tableDeleteRow() {
        return markdownEditing.tableDeleteRow();
    }

    public boolean tableAddColumn() {
        return markdownEditing.tableAddColumn();
    }

    public boolean tableDeleteColumn() {
        return markdownEditing.tableDeleteColumn();
    }

    public boolean tableSetAlignment(MarkdownTable.Align align) {
        return markdownEditing.tableSetAlignment(align);
    }

    public boolean tableFromCsv() {
        return markdownEditing.tableFromCsv();
    }

    public boolean tableToCsv() {
        return markdownEditing.tableToCsv();
    }

    public void setTableFileExporter(java.util.function.BiConsumer<String, String> exporter) {
        markdownEditing.setTableFileExporter(exporter);
    }

    public boolean exportTableFile(String format) {
        return markdownEditing.exportTableFile(format);
    }

    public boolean insertOrUpdateToc() {
        return markdownEditing.insertOrUpdateToc();
    }

    /** Inserts {@code text} at the focused area's caret (replacing any selection), then focuses it. */
    public void insertAtCaret(String text) {
        CodeArea a = focusedArea != null ? focusedArea : area;
        a.replaceSelection(text);
        a.requestFocus();
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

    /** The URL of the link under the caret, or {@code null} — for the "open link" command / Ctrl-click. */
    public @Nullable String linkUnderCaret() {
        CodeArea a = focusedArea != null ? focusedArea : area;
        return MarkdownInline.linkAround(a.getText(), a.getCaretPosition());
    }

    private void installOverlays() {
        columnRuler.heightProperty().bind(root.heightProperty());
        // A bottom tool window opening (Problems, Run) shrinks the editor from below and the caret line could
        // drop out of view; keep it in view when it was in view before (Java review A20).
        area.heightProperty().addListener((obs, old, now) -> {
            if (now.doubleValue() < old.doubleValue() && JumpReveal.caretParagraphVisible(area)) {
                javafx.application.Platform.runLater(area::requestFollowCaret);
            }
        });
        // Re-measure the ruler whenever the rendered text moves: horizontal scroll, and any layout
        // change (first paint, resize, vertical scroll, gutter widening). Always deferred via
        // runLater so we never query character bounds synchronously inside the layout pass — doing
        // that re-enters layout and blanks the editor.
        area.estimatedScrollXProperty().addListener((obs, old, now) -> rulerPlacement.scheduleRulerMeasure());
        // viewportDirtyEvents fires for a vertical scroll and for every edit, neither of which can move the
        // ruler: its x is (glyph advance, gutter width, horizontal scroll), and the visibility test needs the
        // viewport width. Each measure costs TWO forced VirtualFlow layouts plus character-bounds queries, so
        // measuring on every dirty event was ~6-7 full measures per chrome toggle and a needless one per
        // keystroke. Horizontal scroll has its own listener above; the rest arrive as a width change or via
        // markRulerInputsDirty().
        area.viewportDirtyEvents().subscribe(ignore -> {
            double w = area.getWidth();
            if (rulerPlacement.rulerInputsDirty || w != rulerPlacement.lastRulerViewportWidth) {
                rulerPlacement.lastRulerViewportWidth = w;
                rulerPlacement.scheduleRulerMeasure();
            }
        });

        // Editor scroll pane fills the area, leaving room on the right for the minimap; the minimap
        // is docked to the right edge; the column ruler floats on top of everything. Only what every
        // buffer uses is built here: the note and spell overlays are on by default, and the ruler is one
        // Line. Every other overlay and stripe is attached on first activation (see their accessors and
        // attachLazyOverlay / attachLazyStripe) at the Layer that keeps this stacking order, so an
        // off-feature buffer never builds their Canvas, subscriptions or listeners.
        root.getChildren()
                .addAll(
                        OverlayLayer.layered(scrollPane, OverlayLayer.TEXT),
                        OverlayLayer.layered(noteOverlay, OverlayLayer.NOTE),
                        OverlayLayer.layered(spellOverlay, OverlayLayer.SPELL),
                        OverlayLayer.layered(minimap, OverlayLayer.MINIMAP),
                        OverlayLayer.layered(columnRuler, OverlayLayer.RULER));
        AnchorPane.setTopAnchor(scrollPane, 0d);
        AnchorPane.setBottomAnchor(scrollPane, 0d);
        AnchorPane.setLeftAnchor(scrollPane, 0d);
        AnchorPane.setRightAnchor(scrollPane, Minimap.WIDTH);
        // The overlays share the text rectangle with the scroll pane (and track it when the minimap is
        // toggled — see anchorOverText); they are mouse-transparent so clicks reach the editor.
        anchorOverText(spellOverlay); // red squiggles
        imageDrop.install(area);
        anchorOverText(noteOverlay);
        noteOverlay.setSpans(notes::activeSpans, notes::hasActiveSpans); // inert until a note is positioned
        noteOverlay.setActive(true);
        installPointerHovers(area);
        AnchorPane.setTopAnchor(minimap, 0d);
        AnchorPane.setBottomAnchor(minimap, 0d);
        AnchorPane.setRightAnchor(minimap, 0d);
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

    public void setMenuContributor(java.util.function.Supplier<List<MenuItem>> contributor) {
        editorMenu.setMenuContributor(contributor);
    }

    public void setDebugMenuContributor(java.util.function.Supplier<List<MenuItem>> contributor) {
        editorMenu.setDebugMenuContributor(contributor);
    }

    public void setSimpleMenu(boolean simple) {
        editorMenu.setSimpleMenu(simple);
    }

    public boolean isSimpleMenu() {
        return editorMenu.isSimpleMenu();
    }

    public void setBuildMenuContributor(java.util.function.Supplier<List<MenuItem>> contributor) {
        editorMenu.setBuildMenuContributor(contributor);
    }

    /** This buffer's spell checking: its settings, the word at the caret, and what can be done with it. */
    public BufferSpell spell() {
        return spell;
    }

    /** Drops this buffer's memoized spell verdicts and repaints. Call after the shared user-word set changes:
     *  the set is shared, but each buffer's overlay memoizes its own per-word results, so without this the
     *  other tabs keep squiggling a word that was just added to the dictionary. */
    public void refreshSpell() {
        spell.refresh();
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
    public @Nullable CodeArea getSplitView() {
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

    /**
     * Attaches {@code overlay} to the editor pane at {@code layer} (see {@link Layer}), with the standard
     * text-rect anchors. The split's second view, if there is one, gets its twin at once; a split opened
     * later finds it with the rest (see {@link SecondaryPane#follow}).
     */
    <T extends javafx.scene.layout.Region> T attachLazyOverlay(T overlay, int layer) {
        anchorOverText(overlay);
        return attachLazy(overlay, layer);
    }

    /** Attaches {@code stripe} docked at the right edge, top to bottom; the caller sets its right inset. */
    private <T extends javafx.scene.layout.Region> T attachLazyStripe(T stripe, int layer) {
        AnchorPane.setTopAnchor(stripe, 0d);
        AnchorPane.setBottomAnchor(stripe, 0d);
        AnchorPane.setRightAnchor(stripe, 0d);
        return attachLazy(stripe, layer);
    }

    private <T extends Node> T attachLazy(T node, int layer) {
        if (node instanceof TabSurface surface) {
            surface.setRenderingActive(renderingActive); // first activated while its tab is in the background
        }
        OverlayLayer.layered(node, layer);
        List<Node> children = root.getChildren();
        int idx = children.size();
        for (int i = 0; i < children.size(); i++) {
            if (OverlayLayer.of(children.get(i)) > layer) {
                idx = i;
                break;
            }
        }
        children.add(idx, node);
        if (pane2 != null) {
            pane2.follow(children); // the split's second view gets its twin
        }
        return node;
    }

    private SearchHighlightOverlay searchOverlay() {
        if (searchOverlay == null) {
            searchOverlay = attachLazyOverlay(new SearchHighlightOverlay(area), OverlayLayer.SEARCH);
        }
        return searchOverlay;
    }

    private OccurrenceHighlightOverlay occurrenceOverlay() {
        if (occurrenceOverlay == null) {
            occurrenceOverlay = attachLazyOverlay(new OccurrenceHighlightOverlay(area), OverlayLayer.OCCURRENCE);
        }
        return occurrenceOverlay;
    }

    TodoHighlightOverlay todoOverlay() {
        if (todoOverlay == null) {
            todoOverlay = attachLazyOverlay(new TodoHighlightOverlay(area), OverlayLayer.TODO);
        }
        return todoOverlay;
    }

    private IndentGuideOverlay indentGuides() {
        if (indentGuides == null) {
            IndentGuideOverlay made = new IndentGuideOverlay(
                    area,
                    () -> "\t".equals(indentUnit()) ? tabSize : indentUnit().length(),
                    () -> tabSize);
            if (fontApplied) {
                made.setFont(fontFamily, fontSize);
            }
            made.setSuppressed(largeFile);
            indentGuides = attachLazyOverlay(made, OverlayLayer.INDENT_GUIDE);
        }
        return indentGuides;
    }

    /** Shows or hides the indent guides (VS Code's vertical lines at each indentation level). */
    public void setIndentGuidesVisible(boolean visible) {
        if (visible || indentGuides != null) { // turning them off on a buffer that never had them builds nothing
            indentGuides().setActive(visible);
        }
    }

    private WhitespaceOverlay whitespace() {
        if (whitespace == null) {
            WhitespaceOverlay made = new WhitespaceOverlay(area);
            if (fontApplied) {
                made.setFont(fontFamily, fontSize);
            }
            made.setSuppressed(largeFile);
            whitespace = attachLazyOverlay(made, OverlayLayer.WHITESPACE);
        }
        return whitespace;
    }

    private MarkdownLintOverlay mdLintOverlay() {
        if (mdLintOverlay == null) {
            mdLintOverlay = attachLazyOverlay(new MarkdownLintOverlay(area), OverlayLayer.MARKDOWN_LINT);
        }
        return mdLintOverlay;
    }

    private InlineValuesOverlay inlineValues() {
        if (inlineValues == null) {
            InlineValuesOverlay made = new InlineValuesOverlay(area);
            if (fontApplied) {
                made.setFont(fontFamily, fontSize);
            }
            inlineValues = attachLazyOverlay(made, OverlayLayer.INLINE_VALUES);
        }
        return inlineValues;
    }

    private DiagnosticStripe diagnosticStripe() {
        if (diagnosticStripe == null) {
            diagnosticStripe = attachLazyStripe(new DiagnosticStripe(area), OverlayLayer.DIAGNOSTIC_STRIPE);
            diagnosticStripe.setOnActivateIn(jumps::revealLine);
            updateDiagnosticStripe(); // its inset and on/off state, as if it had been there all along
        }
        return diagnosticStripe;
    }

    private MarkdownLintStripe mdLintStripe() {
        if (mdLintStripe == null) {
            mdLintStripe = attachLazyStripe(new MarkdownLintStripe(area), OverlayLayer.MARKDOWN_LINT_STRIPE);
            mdLintStripe.setOnActivateIn(jumps::revealLine);
            updateMarkdownLintStripe(); // its inset and on/off state, as if it had been there all along
        }
        return mdLintStripe;
    }

    TodoStripe todoStripe() {
        if (todoStripe == null) {
            todoStripe = attachLazyStripe(new TodoStripe(area), OverlayLayer.TODO_STRIPE);
            todoStripe.setOnActivateIn(jumps::revealLine);
            updateTodoStripe(); // its inset and on/off state, as if it had been there all along
        }
        return todoStripe;
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
                        public @Nullable LogLevel levelAt(int paragraph) {
                            return logView.levelAt(paragraph);
                        }
                    }),
                    OverlayLayer.LOG);
        }
        return logOverlay;
    }

    private MermaidLintOverlay lintOverlay() {
        if (lintOverlay == null) {
            lintOverlay = attachLazyOverlay(new MermaidLintOverlay(area), OverlayLayer.MERMAID_LINT);
        }
        return lintOverlay;
    }

    LspDiagnosticOverlay lspOverlay() {
        if (lspOverlay == null) {
            lspOverlay = attachLazyOverlay(new LspDiagnosticOverlay(area), OverlayLayer.LSP);
        }
        return lspOverlay;
    }

    private AceJumpOverlay aceJump() {
        if (aceJump == null) {
            aceJump = attachLazyOverlay(new AceJumpOverlay(area), OverlayLayer.ACE_JUMP);
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

    public void setInlayHints(java.util.@Nullable List<InlayHint> hints) {
        inlayDecor.setInlayHints(hints);
    }

    public void setColorSwatches(java.util.@Nullable List<com.editora.text.ColorSwatch> swatches) {
        inlayDecor.setColorSwatches(swatches);
    }

    /** One code lens: its 0-based line, its text ({@code 3 references}) and the click handler's own token. */
    public record CodeLens(int line, String label, Object token) {}

    public void setCodeLenses(java.util.@Nullable List<CodeLens> lenses) {
        inlayDecor.setCodeLenses(lenses);
    }

    public void setCodeLensHandler(java.util.function.BiConsumer<Integer, java.util.List<CodeLens>> handler) {
        inlayDecor.setCodeLensHandler(handler);
    }

    public java.util.List<CodeLens> codeLensesOn(int line) {
        return inlayDecor.codeLensesOn(line);
    }

    public void activateCodeLens(int line) {
        inlayDecor.activateCodeLens(line);
    }

    public void setOccurrenceRequester(Runnable requester) {
        lspAssists.setOccurrenceRequester(requester);
    }

    public void setTodoHighlightEnabled(boolean enabled) {
        todoMarker.setTodoHighlightEnabled(enabled);
    }

    public void setTodoMatcher(TodoMatcher matcher) {
        todoMarker.setTodoMatcher(matcher);
    }

    public java.util.List<TodoMark> todoMarks() {
        return todoMarker.todoMarks();
    }

    public void setOnTodoMarksChanged(Runnable callback) {
        todoMarker.setOnTodoMarksChanged(callback);
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
        viewLayout.rebuildViewHost();
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
    public @Nullable DiagramKind diagramKind() {
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
                previewPanes.scheduleRenderPreview();
            }
        }
    }

    /** Flips timeline ⇄ calendar, re-renders the preview, and notifies the controller to persist. */
    public void toggleMarkwhenView() {
        markwhenView = markwhenView == MarkwhenView.TIMELINE ? MarkwhenView.CALENDAR : MarkwhenView.TIMELINE;
        if (markdownViewMode != MarkdownViewMode.EDITOR) {
            previewPanes.scheduleRenderPreview();
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

    /**
     * Leaves the preview when — and only when — this buffer has no preview left. Called after any
     * {@code set*PreviewEnabled} flip.
     *
     * <p>Each setter used to ask "is this file my format?" instead, which is wrong both ways. One setting can
     * back several formats (an XML file's DOM tree rides {@code structuredPreview}, but {@code isStructured()}
     * is false for XML) — so the buffer was left stranded in a preview whose feature is off: every branch of
     * {@link BufferPreview#scheduleRenderPreview} then misses and falls through to the Markdown tail, rendering the source
     * as Markdown, while {@code hasPreview()} being false takes away the toggle needed to escape. And one file
     * can have two previews (a workflow is also YAML) — so turning off the unrelated one clobbered a
     * per-file view mode that is persisted in {@code WorkspaceState.markdownViewModes}.
     */
    private void reconcilePreviewMode() {
        if (!hasPreview() && markdownViewMode != MarkdownViewMode.EDITOR) {
            setMarkdownViewMode(MarkdownViewMode.EDITOR);
        }
    }

    /** Whether this buffer supports the 3-mode preview: Markdown always, Mermaid only while the feature
     *  is enabled (so a .mmd file is plain text with no preview affordance when Mermaid is off), and CSV
     *  only once the grid preview node has been injected (the feature-on gate — see {@link #hasCsvPreview}). */
    public boolean hasPreview() {
        return isMarkdown()
                || isMarkwhen()
                || (isDiagram() && MermaidImages.isEnabled() && !encryption.isEncrypted()) // renders via temp files
                || (isRenderedDiagram() && DiagramImages.isEnabled() && !encryption.isEncrypted())
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
                || (hasTypstPreview() && !encryption.isEncrypted());
    }

    /** A CSV buffer whose grid preview node has been injected (i.e. the CSV preview feature is on). The
     *  injected node doubles as the enablement gate, mirroring {@link BufferLayout#htmlPreviewControl}. */
    public boolean hasCsvPreview() {
        return isCsv() && csvPreviewNode != null;
    }

    /** A {@code .http} buffer whose response panel has been injected (i.e. the HTTP Client feature is on).
     *  The injected node doubles as the enablement gate, mirroring {@link #hasCsvPreview()}. */
    public boolean hasHttpPreview() {
        return isHttpFile() && httpPreviewNode != null;
    }

    /** A preview that Export to PDF / Print can put on a page: every one but the {@code .http} response panel
     *  (the CSV grid goes out as a table). Decided by kind alone, so it is cheap enough to gate a menu item. */
    public boolean hasExportablePreview() {
        return hasPreview() && !hasHttpPreview();
    }

    public boolean previewShowsError() {
        return previewPanes.previewShowsError();
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
    public StructuredParser.@Nullable Format structuredFormat() {
        return StructuredParser.Format.forLanguage(language);
    }

    /** Whether the structured tree/OpenAPI preview should be offered (feature on, and not a huge file). */
    public boolean hasStructuredPreview() {
        return structuredPreviewEnabled && isStructured() && !hugeFile;
    }

    /** An XML document: not an {@code .svg}, which renders as an image, nor a DTD, which has no tree (C11). */
    public boolean isXml() {
        String name = path != null ? String.valueOf(path.getFileName()) : displayName;
        return "xml".equals(language) && !isSvg() && !com.editora.structured.DtdDeclarations.isDtdFileName(name);
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
        return PomFiles.isPom(fileName, area.getText(0, Math.min(area.getLength(), PomFiles.SNIFF_CHARS)));
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

    /** The language whose snippets this buffer gets: {@code maven-pom} for a pom, else {@code language} (its own). */
    public String snippetLanguage(String language) {
        return isPom() ? PomFiles.SNIPPET_LANGUAGE : language;
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
            previewPanes.scheduleRenderPreview();
        }
        return true;
    }

    /** Whether this buffer uses the self-scrolling tree host (structured, XML, crontab, or fstab) — shared surface. */
    boolean hasTreePreview() {
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

    public boolean isStructuredOpenApi() {
        return previewPanes.isStructuredOpenApi();
    }

    public void toggleStructuredView() {
        previewPanes.toggleStructuredView();
    }

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

    // --- Live Markdown linting -----------------------------------------------------------------------

    /** Injects the async Markdown linter: {@code accept(text, diagnostics->…)}; null disables linting. */
    public void setMarkdownLintValidator(
            java.util.function.BiConsumer<String, java.util.function.Consumer<java.util.List<MarkdownLint.Diagnostic>>>
                    validator) {
        this.markdownLintValidator = validator;
    }

    /** Turns Markdown linting on/off for this buffer (controller gates on the setting; Markdown buffers only). */
    public void setMarkdownLintEnabled(boolean on) {
        this.markdownLintWanted = on;
        this.markdownLintEnabled = on && isMarkdown() && !hugeFile;
        if (this.markdownLintEnabled || mdLintOverlay != null) {
            // The overlay is shared with a .http buffer's {{variable}} marks: keep it on while they are.
            mdLintOverlay().setActive(this.markdownLintEnabled || httpVariableMarks.enabled());
        }
        minimap.setLintEnabled(this.markdownLintEnabled);
        updateMarkdownLintStripe();
        if (this.markdownLintEnabled) {
            scheduleMarkdownLint();
        } else {
            if (mdLintStripe != null) {
                mdLintStripe.setDiagnostics(java.util.List.of());
            }
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
        if (!markdownLintEnabled) {
            return; // a result that arrived after linting was turned off (the overlay may not even exist)
        }
        mdLintOverlay().setDiagnostics(diags);
        mdLintStripe().setDiagnostics(diags);
        minimap.setLintMarks(diags);
    }

    /** The diagnostics currently underlined in this buffer (empty while linting is off). */
    public java.util.List<MarkdownLint.Diagnostic> markdownLintDiagnostics() {
        return markdownLintEnabled && mdLintOverlay != null ? mdLintOverlay.diagnostics() : java.util.List.of();
    }

    /** Injects the handler for image files dropped onto a Markdown buffer; null disables the drop affordance. */
    public void setImageDropHandler(java.util.function.Consumer<java.util.List<java.io.File>> handler) {
        imageDrop.setImageDropHandler(handler);
    }

    /**
     * Injects the handler for a raw image (or image URL) dragged from a web browser onto a Markdown buffer;
     * the controller downloads/encodes it into {@code assets/} and inserts a link. Args: the dragged
     * {@code Image} (or null) and the image URL/data-URI (or null) — at least one is non-null.
     */
    public void setWebImageDropHandler(java.util.function.BiConsumer<javafx.scene.image.Image, String> handler) {
        imageDrop.setWebImageDropHandler(handler);
    }

    /** A drop lands under the pointer, in the view it was dropped on — a window dragged into has no focus yet. */
    private void caretToDrop(CodeArea a, javafx.scene.input.DragEvent e) {
        focusedArea = a;
        focusedView.set(a);
        EditorMouse.moveCaretToDrop(a, e.getX(), e.getY());
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
            "groovy",
            "scala",
            "clojure",
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
        return LSP_LANGUAGES.contains(language) || com.editora.text.HtmlElements.HANDLEBARS_LANGUAGE.equals(language);
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

    public void setLspOnTypeFormatter(LspOnTypeFormatter formatter) {
        typingAids.setLspOnTypeFormatter(formatter);
    }

    public void setLspOnTypeTriggers(java.util.Set<Character> chars) {
        typingAids.setLspOnTypeTriggers(chars);
    }

    public void setOnTypeFormattingEnabled(boolean enabled) {
        typingAids.setOnTypeFormattingEnabled(enabled);
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
    public void setSignatureHelpRequester(java.util.function.@Nullable Consumer<Character> requester) {
        this.signatureHelpRequester = requester;
    }

    public boolean isRunnable() {
        return runGutter.isRunnable();
    }

    public boolean isCompactSource() {
        return runGutter.isCompactSource();
    }

    /** True specifically for Python (the controller picks {@code python}, vs {@code java}, as the runner). */
    public boolean isPython() {
        return "python".equals(language);
    }

    /** True specifically for a shell script (the controller picks {@code bash} as the runner). */
    public boolean isShell() {
        return "shell".equals(language);
    }

    private final List<Runnable> languageListeners = new java.util.ArrayList<>(2); // see addLanguageListener

    public void setOnRunnableChanged(Runnable callback) {
        runGutter.setOnRunnableChanged(callback);
    }

    public void setRunEnabled(boolean enabled) {
        runGutter.setRunEnabled(enabled);
    }

    /** True for a {@code .http}/{@code .rest} buffer (drives the HTTP Client run glyphs + tool window). */
    public boolean isHttpFile() {
        return "http".equals(language);
    }

    /** Whether the HTTP Client is enabled for this buffer (its request ▶s show). */
    public boolean isHttpEnabled() {
        return httpFeatureEnabled;
    }

    /** Enables/disables the HTTP Client Run glyphs for a {@code .http} buffer (gated by the feature, Simple UI
     *  mode and a local file). */
    public void setHttpEnabled(boolean enabled) {
        if (enabled != httpFeatureEnabled) {
            httpFeatureEnabled = enabled;
            runGutter.recomputeRun();
        }
        httpVariableMarks.update();
    }

    public void setHttpDebugHandler(java.util.function.IntConsumer handler) {
        runGutter.setHttpDebugHandler(handler);
    }

    /**
     * Injects what this {@code .http} buffer asks of the window ({@link HttpHooks}): running, Run All, cancel, the ▶
     * tooltip, the {@code {{variable}}} check and the {@code {{} completion context. Null clears them.
     */
    public void setHttpHooks(HttpHooks hooks) {
        httpGutter.setHooks(hooks);
        HttpHooks set = httpGutter.hooks();
        httpVariableMarks.setValidator(set.checksVariables() ? set.variableLint() : null);
        completionActions.setHttpVariables(set.variableContext());
    }

    /** The hooks injected by {@link #setHttpHooks} ({@link HttpHooks#NONE} until then). */
    public HttpHooks httpHooks() {
        return httpGutter.hooks();
    }

    /** Checks the {@code {{variables}}} again (the environment or the env files changed). */
    public void refreshHttpVariableLint() {
        httpVariableMarks.update();
    }

    /**
     * A language's own check on the same squiggle overlay: HTML markup while no server serves the page (HTML review
     * D5), JSON Lines' one-value-per-line check.
     */
    public void addMarkupCheck(HttpVariableMarks.MarkupCheck check) {
        httpVariableMarks.addMarkupCheck(check);
    }

    /** The quick fixes for the unresolved-variable marks at {@code offset} of {@code view} ("Use environment dev"). */
    public java.util.List<CodeAction> httpVariableFixesAt(@Nullable CodeArea view, int offset) {
        return HttpVariableFixes.at(httpVariableMarks, httpGutter.hooks(), view, offset);
    }

    /** Offers the variable fixes at the caret in the code-action popup (Code Actions); false when there are none. */
    public boolean showHttpVariableFixes() {
        CodeArea view = getFocusedArea();
        java.util.List<CodeAction> fixes = httpVariableFixesAt(view, view.getCaretPosition());
        if (!fixes.isEmpty()) {
            showCodeActions(fixes, HttpVariableFixes::apply);
        }
        return !fixes.isEmpty();
    }

    /** The unresolved-variable marks shown in this {@code .http} buffer (empty while off). */
    public java.util.List<MarkdownLint.Diagnostic> httpVariableDiagnostics() {
        return httpVariableMarks.diagnostics();
    }

    /**
     * Shows request {@code index} (file order; -1 = none) as running: its ▶ becomes a ■ that cancels the run.
     * Cleared by the caller when the run ends, and here when the requests move (the mark would be stale).
     */
    public void setHttpRunning(int index) {
        httpGutter.setRunning(index, this::paragraphTextOrNull, this::isFoldedLine, this::refreshGutterLine);
    }

    /**
     * As {@link #setHttpRunning(int)} for request {@code index} of the text a run started with, whose method/URL
     * line read {@code requestLine}: when the requests moved since, the one with that line is marked.
     */
    public void setHttpRunning(int index, String requestLine) {
        setHttpRunning(httpGutter.follow(index, requestLine, this::paragraphTextOrNull));
    }

    /** Each request's last result after its line, and its ▶ coloured by it ({@link HttpResultMarks}); empty: none. */
    public void setHttpResults(java.util.List<HttpLineResult> results) {
        if (!httpResults.isEmpty() || (results != null && !results.isEmpty())) {
            repaintHttpResults(httpResults.set(results, this::paragraphTextOrNull));
        }
    }

    /** The results shown after the request lines, by line. */
    public java.util.List<HttpLineResult> httpResults() {
        return httpResults.all();
    }

    /** Runs the click action of the result after {@code line}, as a click on it does; false when it has none. */
    public boolean activateHttpResult(int line) {
        return httpResults.activate(line);
    }

    void repaintHttpResults(java.util.@Nullable Set<Integer> lines) {
        if (lines != null) {
            inlayDecor.ensureInlayFactory();
            inlayDecor.refreshInlayAreas();
            lines.forEach(this::refreshGutterLine);
        }
    }

    /** Whether {@code line} is folded away; false for a line that no longer exists (stale request lines). */
    boolean isFoldedLine(int line) {
        return line >= 0 && line < area.getParagraphs().size() && area.isFolded(line);
    }

    @Nullable
    String paragraphTextOrNull(int line) {
        return line >= 0 && line < area.getParagraphs().size()
                ? area.getParagraph(line).getText()
                : null;
    }

    /** The line of the running request's glyph, or -1. */
    public int httpRunningLine() {
        return httpGutter.runningLine();
    }

    /** True for a GNU Makefile buffer — each rule target line then shows a Run glyph ({@code make <target>}),
     *  and the generic "Run File" command runs the default goal ({@code make}). */
    public boolean isMakefile() {
        return "makefile".equals(language);
    }

    public void setMakeRunHandler(java.util.function.Consumer<String> handler) {
        runGutter.setMakeRunHandler(handler);
    }

    public void setTestGutterEnabled(boolean enabled) {
        runGutter.setTestGutterEnabled(enabled);
    }

    public void setTestRunHandler(java.util.function.Consumer<com.editora.test.JavaTestScanner.TestTarget> handler) {
        runGutter.setTestRunHandler(handler);
    }

    public void setTestDebugHandler(java.util.function.Consumer<com.editora.test.JavaTestScanner.TestTarget> handler) {
        runGutter.setTestDebugHandler(handler);
    }

    public void setMainGutterEnabled(boolean enabled) {
        runGutter.setMainGutterEnabled(enabled);
    }

    public void setMainRunHandler(java.util.function.Consumer<com.editora.run.MainMethodScanner.MainMethod> handler) {
        runGutter.setMainRunHandler(handler);
    }

    public void setMainDebugHandler(java.util.function.Consumer<com.editora.run.MainMethodScanner.MainMethod> handler) {
        runGutter.setMainDebugHandler(handler);
    }

    public com.editora.run.MainMethodScanner.@Nullable MainMethod mainTargetAtCaret() {
        return runGutter.mainTargetAtCaret();
    }

    public com.editora.test.JavaTestScanner.@Nullable TestTarget testClassTarget() {
        return runGutter.testClassTarget();
    }

    public com.editora.test.JavaTestScanner.@Nullable TestTarget testTargetAtCaret(boolean classLevel) {
        return runGutter.testTargetAtCaret(classLevel);
    }

    public void settleRunScan() {
        runGutter.settleRunScan();
    }

    /** A language whose server copes with this file despite the large-source tier (Python, review B10). */
    private boolean heavyFileLspAllowed;

    public void setHeavyFileLspAllowed(boolean allowed) {
        heavyFileLspAllowed = allowed;
    }

    /** Turns LSP rendering (diagnostics overlay + hover) on/off for this buffer. The controller drives
     *  document open/close + requests; this only gates the editor surface. */
    public void setLspActive(boolean on) {
        boolean was = lspActive;
        // Large-file mode disables LSP just like syntax highlighting and the minimap: a 5–50 MB document
        // would flood the FX thread with a full-text didOpen and tens/hundreds of thousands of diagnostics
        // to map + render (the Problems tree, minimap + scrollbar stripes), freezing the editor. largeFile
        // is implied by hugeFile (see setReadOnly), so this single guard covers both.
        this.lspActive = on && isLspLanguage() && !largeFile && (!heavyFile || heavyFileLspAllowed);
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
            if (diagnosticStripe != null) {
                diagnosticStripe.setDiagnostics(java.util.List.of());
            }
        }
        updateDiagnosticStripe();
        problemCounter.update(was != lspActive); // on or off a server: nothing checked yet (fifth review CV1)
        bulb.schedule();
        httpVariableMarks.update(); // an HTML page's own markup marks yield to its server's diagnostics (D5)
        if (!this.lspActive) setColorSwatches(null); // they came from the server
    }

    public boolean isLspActive() {
        return lspActive;
    }

    /** Pushes the latest diagnostics for this buffer into the overlay + minimap/scrollbar stripes. */
    public void setLspDiagnostics(java.util.List<LspDiagnostic> diagnostics) {
        if (lspActive || lspOverlay != null) {
            lspOverlay().setDiagnostics(diagnostics);
        }
        if (lspActive) {
            problemCounter.show(diagnostics);
        }
        bulb.schedule(); // a problem that arrived on the caret line may bring a fix
        minimap.setDiagnostics(diagnostics);
        if (lspActive || diagnosticStripe != null) {
            diagnosticStripe().setDiagnostics(diagnostics);
        }
    }

    /** Clears the diagnostic overlay/stripe/minimap on an edit so their line/col-anchored marks don't paint on
     *  shifted lines until the server re-pushes (#417). A no-op cost beyond the repaint the edit already does. */
    private void suppressStaleDiagnostics() {
        if (lspOverlay != null) {
            lspOverlay.setDiagnostics(java.util.List.of());
        }
        minimap.setDiagnostics(java.util.List.of());
        if (diagnosticStripe != null) {
            diagnosticStripe.setDiagnostics(java.util.List.of());
        }
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

    public void setSemanticActive(boolean on) {
        highlighter.setSemanticActive(on);
    }

    public boolean isSemanticActive() {
        return highlighter.isSemanticActive();
    }

    public long semanticGen() {
        return highlighter.semanticGen();
    }

    public void setSemanticTokens(java.util.List<SemanticToken> tokens) {
        highlighter.setSemanticTokens(tokens);
    }

    public void setSemanticTokens(java.util.List<SemanticToken> tokens, long requestGen) {
        highlighter.setSemanticTokens(tokens, requestGen);
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
        if (lspActive || diagnosticStripe != null) {
            DiagnosticStripe stripe = diagnosticStripe();
            AnchorPane.setRightAnchor(stripe, minimapShown ? Minimap.WIDTH : 0d);
            stripe.setActive(lspActive);
        }
        updateMarkdownLintStripe();
    }

    /** Positions the Markdown-lint overview stripe beside the diagnostic stripe (shifted left by its width
     *  when LSP is also active — never the case for a Markdown buffer, but kept general). */
    private void updateMarkdownLintStripe() {
        boolean minimapShown = minimapVisible && !largeFile && !heavyFile;
        double base = minimapShown ? Minimap.WIDTH : 0d;
        boolean on = markdownLintEnabled && !largeFile && !minimapShown; // the minimap carries the same marks
        if (on || mdLintStripe != null) {
            MarkdownLintStripe stripe = mdLintStripe();
            AnchorPane.setRightAnchor(stripe, base + (lspActive ? DiagnosticStripe.WIDTH : 0d));
            stripe.setActive(on);
        }
        updateTodoStripe();
    }

    /** Positions the TODO overview stripe over the scrollbar: at the edge (inside the minimap when shown),
     *  shifted left by the diagnostic + lint stripes' widths when those are active, so they sit side by side. */
    void updateTodoStripe() {
        boolean minimapShown = minimapVisible && !largeFile && !heavyFile;
        double base = minimapShown ? Minimap.WIDTH : 0d;
        double offset = (lspActive ? DiagnosticStripe.WIDTH : 0d)
                + (markdownLintEnabled && !largeFile && !minimapShown ? MarkdownLintStripe.WIDTH : 0d);
        if (todoStripe != null) { // attached with the first marks (todoStripe()), positioned from then on
            AnchorPane.setRightAnchor(todoStripe, base + offset);
            todoStripe.setActive(todoMarker.todoEnabled && !largeFile);
        }
    }

    /** Injects the LSP actions surfaced in the right-click menu while {@link #isLspActive()} (Format and
     *  Code Actions are shown only when the server advertises them — see {@link BufferContextMenu#setLspFormatAvailable}/
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
        editorMenu.lspFindReferencesAction = findReferences == null ? () -> {} : findReferences;
        editorMenu.lspHoverAction = hover == null ? () -> {} : hover;
        editorMenu.lspFormatAction = format == null ? () -> {} : format;
        this.lspCodeActionsAction = codeActions == null ? () -> {} : codeActions;
        editorMenu.lspRenameAction = rename == null ? () -> {} : rename;
        editorMenu.lspGotoImplementationAction = gotoImplementation == null ? () -> {} : gotoImplementation;
        editorMenu.lspGotoTypeDefinitionAction = gotoTypeDefinition == null ? () -> {} : gotoTypeDefinition;
    }

    public void setLspCommandRunner(java.util.function.Consumer<String> run) {
        editorMenu.setLspCommandRunner(run);
    }

    public void setLspImplementationAvailable(boolean available) {
        editorMenu.setLspImplementationAvailable(available);
    }

    public void setLspTypeDefinitionAvailable(boolean available) {
        editorMenu.setLspTypeDefinitionAvailable(available);
    }

    public void setLspFormatAvailable(boolean available) {
        editorMenu.setLspFormatAvailable(available);
    }

    /** Whether to offer "Code Actions" in the right-click menu (the server's codeAction capability, #670). */
    public void setLspCodeActionsAvailable(boolean available) {
        this.lspCodeActionsAvailable = available;
        bulb.schedule();
    }

    /** The code-action lightbulb shown in the gutter on the caret line. */
    public CodeActionBulb codeActionBulb() {
        return bulb;
    }

    /** The problem counter at the top right of the editor. */
    public ProblemCounterSlot problemCounter() {
        return problemCounter;
    }

    public void setLspRenameAvailable(boolean available) {
        editorMenu.setLspRenameAvailable(available);
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

    public void setLspPasteImportsRequester(LspPasteImportsRequester requester) {
        lspAssists.setLspPasteImportsRequester(requester);
    }

    public void setLspPasteImportsEnabled(boolean enabled) {
        lspAssists.setLspPasteImportsEnabled(enabled);
    }

    public void requestLspPasteImports(int start, int end) {
        lspAssists.requestLspPasteImports(start, end);
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

    public void setLspRangeFormatter(LspRangeFormatter formatter) {
        typingAids.setLspRangeFormatter(formatter);
    }

    public void setLspRangeFormatAvailable(boolean available) {
        typingAids.setLspRangeFormatAvailable(available);
    }

    /** Current document text (for an initial didOpen). */
    public String text() {
        return documentTextSnapshot();
    }

    /** While suspended: the frame's variable name → value map painted as grey end-of-line annotations on the
     *  visible lines of the function stopped at {@code frameLine}; null/empty clears (resume/terminate). */
    public void setInlineValues(java.util.@Nullable Map<String, String> values, int frameLine) {
        java.util.Map<String, String> shown = hugeFile ? null : values;
        if ((shown != null && !shown.isEmpty()) || inlineValues != null) {
            // In a .http file only its script lines are the script's; a request body's "user" is not that variable.
            java.util.BitSet script = isHttpFile() ? com.editora.http.HttpScriptLines.of(getContent()) : null;
            inlineValues().setValues(shown, frameLine, script == null ? null : script::get);
        }
    }

    /**
     * While suspended: the pinned values to paint, by line ({@code expr = value} text), after the line's own inline
     * values; null or empty clears them.
     */
    public void setPinnedValues(java.util.@Nullable Map<Integer, String> shown) {
        java.util.Map<Integer, String> pinned = hugeFile ? null : shown;
        if ((pinned != null && !pinned.isEmpty()) || inlineValues != null) {
            inlineValues().setPinned(pinned);
        }
    }

    /** The values pinned to this buffer's lines. */
    public com.editora.editor.debug.PinManager getPinManager() {
        return pins;
    }

    /** IntelliJ-style blame "Annotate" gutter column: per-0-based-line annotations (null/empty clears it).
     *  Already formatted + localized by the controller (author/date/tooltip/heatmap), so {@code editor}
     *  stays git-free. Off on huge files. Computes the fixed column width from the widest author+date, then
     *  rebuilds the gutter so the column appears/disappears. */
    public void setBlame(java.util.@Nullable List<BlameInfo> lines) {
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
    public @Nullable String blameHashAt(int line) {
        BlameInfo bi = gitLines.blameAt(line);
        return bi == null ? null : bi.hash();
    }

    /** The commit hash that last touched the caret line (for "show this commit"), or null. */
    public @Nullable String blameHashAtCaret() {
        return blameHashAt(focusedArea.getCurrentParagraph());
    }

    /** The debugger's value popup (hover and Show Value at Caret) — see {@link DebugHover}. */
    private final DebugHover debugHover = new DebugHover();

    /** The DAP {@code evaluate} (context "hover") the value popup asks: expression → value (null/blank: none). */
    public void setDebugHoverEvaluator(
            java.util.function.BiConsumer<String, java.util.function.Consumer<String>> evaluator) {
        debugHover.setEvaluator(evaluator);
    }

    /** Flipped by the controller on suspend/resume so hovering costs nothing outside a pause. */
    public void setDebugHoverActive(boolean on) {
        debugHover.setActive(on && !hugeFile);
    }

    /** Shows debugger values as an expandable popup (null: a one-line tooltip) — see {@link DebugValuePresenter}. */
    public void setDebugValuePresenter(DebugValuePresenter presenter) {
        debugHover.setPresenter(presenter);
    }

    /** Show Value at Caret: the value of the name at the caret, in the value popup; false when there is none. */
    public boolean showDebugValueAtCaret() {
        return debugHover.showAtCaret(focusedArea);
    }

    public MarkdownViewMode getMarkdownViewMode() {
        return markdownViewMode;
    }

    public void setOnViewModeChanged(Runnable callback) {
        this.onViewModeChanged = callback == null ? () -> {} : callback;
    }

    public void setViewModeControl(@Nullable Node control) {
        viewLayout.setViewModeControl(control);
    }

    public boolean hasViewModeControl() {
        return viewLayout.hasViewModeControl();
    }

    public void setHtmlPreviewControl(@Nullable Node control) {
        viewLayout.setHtmlPreviewControl(control);
    }

    public boolean hasHtmlPreviewControl() {
        return viewLayout.hasHtmlPreviewControl();
    }

    public void setHttpEnvControl(@Nullable Node control) {
        viewLayout.setHttpEnvControl(control);
    }

    public boolean hasHttpEnvControl() {
        return viewLayout.hasHttpEnvControl();
    }

    public void setCsvPreviewNode(@Nullable Node node) {
        previewPanes.setCsvPreviewNode(node);
    }

    public void setCsvPreviewRefresh(Runnable refresh) {
        previewPanes.setCsvPreviewRefresh(refresh);
    }

    public void setHttpPreviewNode(@Nullable Node node) {
        previewPanes.setHttpPreviewNode(node);
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
        viewLayout.rebuildViewHost();
    }

    /**
     * Docks the log control (Follow / level / pattern) above the text; {@code null} removes it. It is a bar of
     * its own rather than a control floating in the corner: log lines are the longest lines the editor shows,
     * and a floating control sat on top of the end of the first two.
     */
    public void setLogControl(@Nullable Node control) {
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

    public void setAutoCloseTags(boolean on) {
        typingAids.setAutoCloseTags(on);
    }

    /** Enables/disables the paired-tag auto-rename (pushed from the controller's view settings). */
    public void setAutoRenameTag(boolean on) {
        tagRename.setEnabled(on);
    }

    public void setAutoFillEnabled(boolean on) {
        typingAids.setAutoFillEnabled(on);
    }

    public void setFillColumn(int column) {
        typingAids.setFillColumn(column);
    }

    public void setAbbrevs(java.util.Map<String, String> table, boolean autoExpand) {
        snippetKeys.setAbbrevs(table, autoExpand);
    }

    public boolean expandAbbrevAtCaret() {
        return snippetKeys.expandAbbrevAtCaret();
    }

    final DeferredFixes caretFixes = new DeferredFixes();

    /** Applies pending caret fix-ups now (see {@link DeferredFixes}); a macro replay calls it after each key. */
    public void flushDeferredCaretFixes() {
        caretFixes.flush();
    }

    public void setCsvRainbowEnabled(boolean on) {
        highlighter.setCsvRainbowEnabled(on);
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

    public void setBracketColorsEnabled(boolean on) {
        highlighter.setBracketColorsEnabled(on);
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
    public void applyLogFilter(@Nullable LogLevel minLevel, @Nullable String pattern) {
        widen(); // the filter is derived from the area, which must therefore be the whole document
        logView.applyFilter(minLevel, pattern);
    }

    /** Whether {@code minLevel}/{@code pattern} is the filter already showing. */
    public boolean showsLogFilter(@Nullable LogLevel minLevel, @Nullable String pattern) {
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
            com.editora.logviewer.LogFilter.Run run, int epoch, @Nullable LogLevel minLevel, @Nullable String pattern) {
        return logView.install(run, epoch, minLevel, pattern);
    }

    /** The current level floor of the active filter (null when unfiltered or no floor). */
    public @Nullable LogLevel getLogMinLevel() {
        return logView.minLevel();
    }

    /** The pattern of the active filter as typed (null when unfiltered or no pattern). */
    public @Nullable String getLogPattern() {
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

    /** Injects the debounced edit listener (fires the live-preview reload); {@code null} disables it. */
    public void setHtmlPreviewDirtyListener(@Nullable Runnable listener) {
        this.htmlPreviewDirtyListener = listener;
    }

    public void setMarkdownViewMode(MarkdownViewMode mode) {
        previewPanes.setMarkdownViewMode(mode);
    }

    /** The preview's right-click menu, where the window injects its export and print actions. */
    public PreviewMenu previewMenu() {
        return previewMenu;
    }

    public void setPomViewToggleHandler(Runnable handler) {
        previewPanes.setPomViewToggleHandler(handler);
    }

    /** Copies the preview's selection when it has one, else all of it ({@link PreviewMenu#copy}). */
    public void copyPreviewToClipboard() {
        previewMenu.copy();
    }

    /** Copies the preview's HTML <em>markup</em> as plain text ({@link PreviewMenu#copyHtmlSource}). */
    public boolean copyPreviewHtmlSource() {
        return previewMenu.copyHtmlSource();
    }

    public boolean previewEditCommand(boolean selectAll) {
        return previewPanes.previewEditCommand(selectAll);
    }

    /**
     * Pages the Markdown preview when it's the active scroll target (full PREVIEW mode, or a SPLIT whose
     * preview has focus). Lets {@code nav.pageDown}/{@code nav.pageUp} (C-v / M-v) scroll the preview
     * instead of the hidden/unfocused editor. Returns whether it handled the request.
     */
    public boolean pagePreview(boolean down) {
        return scrollPreview(down ? 0.9 : -0.9);
    }

    public boolean scrollPreview(double pages) {
        return previewPanes.scrollPreview(pages);
    }

    /**
     * Releases this buffer's resources. Must be called by the controller when the tab is
     * <em>actually closed</em> (not on a plain tab switch). The preview/highlight executors are now
     * shared, app-lifetime pools ({@link BufferPreview#PREVIEW_POOL}/{@link #HIGHLIGHT_POOL}), so disposal does not
     * shut them down — it instead bumps {@link BufferPreview#previewGen}/{@link BufferHighlighter#highlightGen} so any in-flight task
     * submitted by this buffer discards its result (the gen guard) instead of touching the now-dead
     * buffer. The reactfx subscriptions are on the buffer's own {@code area}/{@code area2} and die with
     * it on GC; the shared settled-edit subscription is dropped eagerly too. Idempotent. Once disposed the
     * buffer must not be reused.
     */
    public void dispose() {
        encryption.wipe(); // a passphrase-only file's passphrase leaves with the tab
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
        // The undo queue too: its removed-and-inserted text counts against the app-wide UndoQueueBudget until
        // it is forgotten, and a closed tab should give the budget back now, not when the queue is collected.
        area.getUndoManager().forgetHistory();
        documentSnapshots.invalidate();
        hiddenRelease.cancel(); // (a pending wait holds this buffer weakly, so it is collectable either way)
        PreviewSurfaces.release(path, this); // rendered Mermaid/Typst pages only this document could show
        minimap.dispose(); // its retained content pixels (MinimapRetention) and its split follower's
        if (settledEditSub != null) {
            settledEditSub.unsubscribe();
            settledEditSub = null;
        }
        settledEdits.dispose();
        caretOps.disposeMultiCaret();
        previewPanes.previewGen++; // discard any in-flight preview result for this (now closed) buffer
        highlighter.highlightGen++; // discard any in-flight highlight result
        todoMarker.todoGen++; // discard any in-flight TODO scan result
        runGutter.runScanGen++; // discard any in-flight run scan result
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
     * Set on {@link #dispose()}; {@link BufferHighlighter#applyHighlighting()} and the TODO scan refuse to dispatch once
     * true. The gen bumps above only invalidate work already in flight — the still-subscribed debounced
     * edit pulse fires once more ~300 ms after the last change, and before this flag it would re-dispatch
     * a fresh full tokenize of the closed buffer whose generation was then CURRENT, so nothing cancelled
     * it. For a large file that ghost pass held the shared per-grammar monitor for however long the whole
     * document takes (minutes on a slow machine), starving every open buffer of the same language — found
     * as a CI-only suite wedge, but it is an app defect too: closing a big tab right after an edit did
     * exactly this.
     */
    volatile boolean disposed;

    // --- Multiple cursors + column/box selection (RichTextFX fork) -------------------------------

    public void setMultiCaretEnabled(boolean enabled) {
        caretOps.setMultiCaretEnabled(enabled);
    }

    public boolean hasMultipleCarets() {
        return caretOps.hasMultipleCarets();
    }

    public boolean anyCaretSelection() {
        return caretOps.anyCaretSelection();
    }

    public void undoOrRedo(CodeArea a, boolean redo) {
        caretOps.undoOrRedo(a, redo);
    }

    public void addCaretNextOccurrence() {
        caretOps.addCaretNextOccurrence();
    }

    /** Cap on carets a single "select all" places, so a common word can't create hundreds of thousands. */
    public static final int MAX_OCCURRENCE_CARETS = 10_000;

    public int placeOccurrenceCarets(List<int[]> ranges, int anchorStart) {
        return caretOps.placeOccurrenceCarets(ranges, anchorStart);
    }

    public int selectAllOccurrences() {
        return caretOps.selectAllOccurrences();
    }

    public int splitSelectionIntoLines(boolean atLineEnds) {
        return caretOps.splitSelectionIntoLines(atLineEnds);
    }

    public boolean skipOccurrence() {
        return caretOps.skipOccurrence();
    }

    public boolean undoLastCaret() {
        return caretOps.undoLastCaret();
    }

    /** The VS Code line commands ({@link com.editora.editops.LineCommands}). */
    public enum LineCommand {
        INSERT_BELOW,
        INSERT_ABOVE,
        DELETE,
        SELECT,
        INDENT,
        OUTDENT
    }

    public boolean lineCommand(LineCommand command) {
        return caretOps.lineCommand(command);
    }

    public boolean completeStatement() {
        return caretOps.completeStatement();
    }

    /** One caret's edit: its change and its selection afterwards, given the text and the caret's selection. */
    @FunctionalInterface
    public interface CaretEdit {
        Indenter.@Nullable TabEdit edit(String text, int selStart, int selEnd);
    }

    public boolean atEachCaret(CaretEdit edit) {
        return caretOps.atEachCaret(edit);
    }

    public void addCaretAbove() {
        caretOps.addCaretAbove();
    }

    public void addCaretBelow() {
        caretOps.addCaretBelow();
    }

    public boolean multiMoveHorizontal(int amount, boolean byWord, boolean select) {
        return caretOps.multiMoveHorizontal(amount, byWord, select);
    }

    public boolean multiMoveVertical(boolean down, boolean select) {
        return caretOps.multiMoveVertical(down, select);
    }

    public boolean multiMoveLineBoundary(boolean toEnd, boolean select) {
        return caretOps.multiMoveLineBoundary(toEnd, select);
    }

    public boolean multiCaretCopy() {
        return caretOps.multiCaretCopy();
    }

    public boolean multiCaretCut() {
        return caretOps.multiCaretCut();
    }

    public boolean multiCaretPaste() {
        return caretOps.multiCaretPaste();
    }

    public void copyCurrentLine() {
        caretOps.copyCurrentLine();
    }

    public void cutCurrentLine() {
        caretOps.cutCurrentLine();
    }

    public void collapseCarets() {
        caretOps.collapseCarets();
    }

    public void refreshPreview() {
        previewPanes.refreshPreview();
    }

    /**
     * Whether only a rendering of this buffer is on screen (Preview mode; not the CSV grid, which edits). Its
     * text is then out of sight, so editing commands are refused rather than applied at an invisible caret.
     */
    public boolean isPreviewOnly() {
        return markdownViewMode == MarkdownViewMode.PREVIEW && !hasCsvPreview();
    }

    public @Nullable PreviewFind previewFind() {
        return previewPanes.previewFind();
    }

    public boolean scrollPreviewToAnchor(String slug) {
        return previewPanes.scrollPreviewToAnchor(slug);
    }

    /**
     * Renders the current image/tree preview to PNG images for print and PDF export (a full snapshot of the
     * whole tree/timeline, not just the visible viewport), in steps that keep the window responsive. Hands
     * {@code done} the result, or {@code null} for a buffer whose preview isn't snapshot-based
     * (Markdown/CSV/Mermaid/diagram export semantically / via their CLI instead) or does not parse. FX
     * thread only; {@code progress} gets (chunks done, chunks in all).
     */
    public void snapshotPreviewChunks(
            String lightUaStylesheet,
            java.util.function.BiConsumer<Integer, Integer> progress,
            java.util.function.Consumer<PreviewSnapshots.Result> done) {
        PreviewSnapshots.capture(this, lightUaStylesheet, progress, done);
    }

    public boolean showsApiDocs() {
        return previewPanes.showsApiDocs();
    }

    public void setPreviewLoading(boolean loading, @Nullable String message) {
        previewPanes.setPreviewLoading(loading, message);
    }

    public void setPreviewThemeToggle(Runnable toggle) {
        previewPanes.setPreviewThemeToggle(toggle);
    }

    public void applyPreviewTheme(String mode, boolean appDark) {
        previewPanes.applyPreviewTheme(mode, appDark);
    }

    public void zoomPreviewIn() {
        previewPanes.zoomPreviewIn();
    }

    public void zoomPreviewOut() {
        previewPanes.zoomPreviewOut();
    }

    public void resetPreviewZoom() {
        previewPanes.resetPreviewZoom();
    }

    public PreviewLayout getPreviewLayout() {
        return previewPanes.getPreviewLayout();
    }

    public void restorePreviewLayout(@Nullable PreviewLayout layout, Runnable onChange) {
        previewPanes.restorePreviewLayout(layout, onChange);
    }

    public void setExpertExitControl(@Nullable Node control) {
        viewLayout.setExpertExitControl(control);
    }

    /** Lazily builds the secondary view (scroll pane + its own minimap) sharing this document. */
    void ensureSecondaryView() {
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
        typingAids.addViewModePaging(area2); // same pager keys in the secondary split view
        completionActions.addCompletionKeys(area2);
        completionActions.installCommitCharacters(area2);
        snippetKeys.addSnippetKeys(area2);
        typingAids.addAutoClose(area2);
        typingAids.addAutoIndent(area2);
        completionActions.installCompletionTrigger(area2);
        lspAssists.installOccurrenceTrigger(area2); // LSP document highlight (#675)
        inlayDecor.installCodeLensClick(area2);
        if (inlayDecor.inlayFactoryInstalled) {
            area2.setInlayFactory(inlayDecor::inlaysOn); // hints, lenses and results set before the split opened
        }
        imageDrop.install(area2);
        if (caretOps.multiCaretEnabled && !hugeFile && caretOps.multiCaret2 == null) {
            caretOps.multiCaret2 =
                    MultiCarets.install(area2, typingAids::tabEdit); // same multi-caret add-on in the split view
        }
        area2.setLineHighlighterFill(lineHighlightColor);
        refreshGutter();
        area2.setStyle("-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;");
        area2.caretPositionProperty().addListener((obs, old, now) -> {
            resetGoalColumn();
            scheduleBraceMatch();
            bulb.schedule();
        });
        area2.focusedProperty().addListener((obs, was, now) -> {
            CodeArea split = area2;
            if (now && split != null) {
                focusedArea = split;
                focusedView.set(split);
            }
        });
        installFormatBarListeners(area2);
        editorMenu.installContextMenu(area2);
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
    private static void applyMinimap(@Nullable Region scroll, Minimap mm, boolean visible) {
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
        if (whitespace != null) {
            whitespace.setFont(family, size); // an overlay attached later takes the font from the fields
        }
        if (indentGuides != null) {
            indentGuides.setFont(family, size);
        }
        spell.geometryChanged();
        if (inlineValues != null) {
            inlineValues.setFont(family, size);
        }
        stickyScroll.setFont(family, size);
        if (gitLines.blame() != null) {
            // The blame annotation column width is font-relative — recompute + rebuild so it stays aligned.
            blameColumnWidth = measureBlameColumnWidth(gitLines.blame());
            refreshGutter();
        }
        rulerPlacement.markRulerInputsDirty(); // the glyph advance changed
    }

    public void setColumnRulerVisible(boolean visible) {
        rulerPlacement.setColumnRulerVisible(visible);
    }

    public void setRulerColumns(List<Integer> global, java.util.Map<String, List<Integer>> perLanguage) {
        rulerPlacement.setRulerColumns(global, perLanguage);
    }

    List<Integer> rulerColumnsInForce() {
        return rulerPlacement.rulerColumnsInForce();
    }

    boolean rulerMeasurePending() {
        return rulerPlacement.rulerMeasurePending();
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

    final UndoHistory undoHistory = new UndoHistory();
    private @Nullable Runnable onUndoHistoryChanged;

    public UndoHistory getUndoHistory() {
        return undoHistory;
    }

    /** Notified after a checkpoint is recorded, so the Undo History panel can refresh (active buffer only). */
    public void setOnUndoHistoryChanged(@Nullable Runnable r) {
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
        // Not in large-file mode: it lays out the caret's whole line, ~270 ms a keystroke on a 2 MB line.
        LineHighlighter.set(area, on, largeFile || loading);
        if (area2 != null) {
            LineHighlighter.set(area2, on, largeFile || loading);
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
     * GPU-backed caches at once — the minimap snapshot and every {@link TabSurface} overlay's canvas — so
     * retained VRAM doesn't scale with the number of open files; they regenerate when the tab is shown
     * again. What it holds on the heap only for drawing goes later, if it stays hidden (see
     * {@link #releaseHiddenCaches}). Called by the controller on tab selection and when a tab is added in
     * the background.
     */
    public void setRenderingActive(boolean active) {
        boolean wasInactive = !renderingActive;
        renderingActive = active;
        if (!active) snippetSession.cancel(); // a background tab: Tab must not come back to its fields
        if (active && wasInactive) {
            rulerPlacement.scheduleRulerMeasure(); // catch up on measures skipped while the tab was hidden
        }
        minimap.setRenderingActive(active);
        // A full-viewport overlay keeps a Canvas + RTTexture alive for a tab the user cannot see. The
        // stripes are narrow (small textures); every overlay that can hold a viewport-sized one lets go.
        for (Node child : root.getChildren()) {
            if (child instanceof TabSurface surface) {
                surface.setRenderingActive(active);
            }
        }
        if (active) {
            hiddenRelease.cancel();
        } else {
            hiddenRelease.start(); // heap-side caches go later, if the tab stays hidden (releaseHiddenCaches)
        }
    }

    /**
     * How long a tab stays in the background before {@link #releaseHiddenCaches} runs. The textures go at
     * once (above); these caches are rebuilt on the FX thread in O(document) when next asked for, and a tab
     * switched away from is often switched back to within seconds, so they are kept for a while first.
     */
    static final Duration HIDDEN_RELEASE_DELAY = Duration.ofMinutes(3);

    private final HiddenTabTimer hiddenRelease = new HiddenTabTimer(HIDDEN_RELEASE_DELAY, this::releaseHiddenCaches);

    /**
     * What a tab hidden for {@link #HIDDEN_RELEASE_DELAY} gives back — only what is cheaply rebuilt on
     * demand: the cached whole-document String when it is a second copy (a dirty buffer's; a clean buffer's
     * snapshot <em>is</em> its saved baseline, so dropping it would free nothing), and the spell overlay's
     * per-line spans and squiggle geometry. Undo entries and Undo History checkpoints are user data and stay.
     * Package-private so a test can run it without waiting.
     */
    void releaseHiddenCaches() {
        if (renderingActive || disposed) {
            return;
        }
        documentSnapshots.releaseUnless(baseline == null ? null : baseline.heldText());
        spellOverlay.releaseHiddenCaches();
    }

    public void setHighlightDeferred(boolean deferred) {
        highlighter.setHighlightDeferred(deferred);
    }

    public boolean isHighlightDeferred() {
        return highlighter.isHighlightDeferred();
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
        rulerPlacement.markRulerInputsDirty(); // the gutter's width is where column 0 starts
        noteOverlay.refresh();
        if (indentGuides != null) {
            indentGuides.refresh(); // and where the guides start: the new gutter is laid out in the next pulse
        }
        area.setParagraphGraphicFactory(gutterVisible ? folds.gutterFactory(lineNumbersVisible) : null);
        applyNoGutterStyle(area);
        if (area2 != null) { // the same gutter: fold chevrons, bookmark, breakpoint and run markers
            area2.setParagraphGraphicFactory(gutterVisible ? folds.gutterFactory(lineNumbersVisible, true) : null);
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
    public void setChangeBars(java.util.@Nullable Map<Integer, String> lineClasses) {
        setChangeBars(lineClasses, null);
    }

    /** As {@link #setChangeBars(java.util.Map)} plus a per-line hunk-text map for the change-bar tooltip. */
    public void setChangeBars(
            java.util.@Nullable Map<Integer, String> lineClasses, java.util.@Nullable Map<Integer, String> hunkText) {
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

    /** Sets the breakpoint-strip Shift+click handler ({@code (buffer, line)}) — the controller adds a logpoint. */
    public void setGutterLogpointClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        if (handler != null) {
            this.gutterLogpointClick = handler;
        }
    }

    public void setGutterBreakpointMenu(BreakpointGutterMenu menu) {
        gutterBreakpointMenu = menu == null ? (buffer, line, e) -> {} : menu;
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
     * Marks {@code line} as the current execution point (a distinct background and a gutter arrow), puts the
     * caret there and scrolls it into view clear of the sticky-scroll headers (review B6). Clears any previous mark.
     */
    public void setExecutionLine(int line) {
        if (executionLine.set(area, folds, line, () -> lineHighlightColor, this::refreshGutterLine)) {
            area.moveTo(line, 0);
            area.requestFocus();
            executionLine.reveal(
                    area,
                    line,
                    stickyScrollEnabled ? stickyLines.size() : 0,
                    t -> stickyScrollEnabled ? StickyScroll.navigationFirstVisible(folds.regions(), t) : t);
        }
    }

    /** Draws the execution line as an exception stop (red) or a plain stop. */
    public void setExecutionLineException(boolean exception) {
        executionLine.setException(area, exception);
    }

    /** Draws the execution line as an earlier stop's (Debug History: grey) or the live one's. */
    public void setExecutionLineHistory(boolean history) {
        executionLine.setHistory(area, history);
    }

    /** Removes the execution-point highlight (if any). */
    public void clearExecutionLine() {
        executionLine.clear(area, this::refreshGutterLine);
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

    public void setRunHandler(Runnable handler) {
        runGutter.setRunHandler(handler);
    }

    public void setDebugFileHandler(Runnable handler, java.util.function.BooleanSupplier shown) {
        runGutter.setDebugFileHandler(handler, shown);
    }

    /** Callback fired after any note change (for persistence + the Notes panel). */
    public void setOnNotesChanged(Runnable callback) {
        notes.setOnChanged(callback);
    }

    /** Replaces this buffer's notes from persisted state and repaints their highlight. Returns true if any note
     *  was re-anchored or (un)orphaned, so the caller can persist the self-healed state. */
    public boolean applyNotes(List<com.editora.config.PersonalNote> saved) {
        return notes.restore(saved); // repaints the note overlay; the gutter shows no notes, so it is left alone
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
        String doc =
                documentTextSnapshot(); // the area's text (the narrowed region when narrowed), one copy per version
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
    public com.editora.config.@Nullable FileIdentity fileIdentity() {
        return path == null ? null : com.editora.config.FileIdentity.of(path);
    }

    /** Enables/disables the Personal Notes feature for this buffer (gates the "Add Note" menu items). */
    public void setNotesEnabled(boolean on) {
        this.notesEnabled = on;
    }

    /** Shows/hides the notes' inline markers + highlight (the {@code showNoteIndicators} setting). */
    public void setNoteIndicatorsVisible(boolean on) {
        if (noteIndicators == on) {
            return;
        }
        noteIndicators = on;
        noteOverlay.setActive(on);
        if (!on) {
            noteHover.hide();
        }
    }

    /** The popups that follow the pointer over {@code area} (either pane), each shown at the pointer. */
    private void installPointerHovers(CodeArea a) {
        MermaidLintHover.install(a, () -> mermaidLintEnabled ? lintOverlay : null); // lazily attached
        MarkdownLintHover.install(
                a,
                () -> markdownLintEnabled || httpVariableMarks.enabled() ? mdLintOverlay : null,
                hits -> HttpVariableFixes.hint(httpVariableMarks, httpGutter.hooks(), hits));
        lspAssists.installLspHover(a); // reads lspOverlay only while LSP is active (lazily attached)
        debugHover.install(a);
        noteHover.install(a);
    }

    /** Sets the handler invoked when the user clicks a note's inline start marker (controller edits it). */
    public void setNoteMarkerClick(
            java.util.function.BiConsumer<EditorBuffer, com.editora.config.PersonalNote> handler) {
        if (handler != null) {
            this.noteMarkerClick = handler;
        }
    }

    public boolean jumpToNextTodo() {
        return todoMarker.jumpToNextTodo();
    }

    public boolean jumpToPreviousTodo() {
        return todoMarker.jumpToPreviousTodo();
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

    /**
     * Whether the minimap should actually show: the user's setting, but forced off for large/heavy files
     * (too costly) and in full {@link MarkdownViewMode#PREVIEW} (no editor surface to map). It stays on in
     * {@link MarkdownViewMode#SPLIT} — the editor is still visible there. Pure, so it's unit-tested.
     */
    static boolean minimapEffective(boolean visible, boolean largeFile, boolean heavyFile, MarkdownViewMode mode) {
        return visible && !largeFile && !heavyFile && mode != MarkdownViewMode.PREVIEW;
    }

    private final WordWrap wrap = new WordWrap(this::setWordWrap);

    /** This buffer's word-wrap preference (per kind of file) and its long-line hold; see {@link WordWrap}. */
    public WordWrap wrap() {
        return wrap;
    }

    /** Toggles soft word wrap on the editor surface (and the split view); the 80-column ruler stays visible. */
    public void setWordWrap(boolean wrap) {
        boolean changed = wrap != area.isWrapText();
        if (changed) {
            rulerPlacement.markRulerInputsDirty(); // wrapping re-flows the text and removes the horizontal scroll
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

    private javafx.animation.@Nullable AnimationTimer wrapRemeasure;

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

    /** Show/hide the minimap overview (on every split pane); reclaims its width for the editor when hidden. */
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
        viewLayout.positionCornerControls(); // keep floating editor controls clear of the minimap
    }

    /** Show/hide the "hidden characters" markers (spaces, tabs, line ends). */
    public void setWhitespaceVisible(boolean visible) {
        if (visible || whitespace != null) { // turning it off on a buffer that never had it builds nothing
            whitespace().setActive(visible);
        }
    }

    public @Nullable Path getPath() {
        return path;
    }

    /** Records file metadata as last loaded/saved, for external-change detection. */
    public void setDiskSnapshot(long modifiedMillis, long size) {
        setDiskSnapshot(modifiedMillis, size, null);
    }

    /** Records metadata plus the exact-content identity used by save-time remote conflict checks. */
    public void setDiskSnapshot(long modifiedMillis, long size, @Nullable String fingerprint) {
        this.diskModifiedMillis = modifiedMillis;
        this.diskSize = size;
        this.diskFingerprint = fingerprint;
    }

    /** Whether {@code modifiedMillis}/{@code size} differ from the last recorded on-disk snapshot. */
    public boolean diskChangedFrom(long modifiedMillis, long size) {
        return diskModifiedMillis >= 0 && (modifiedMillis != diskModifiedMillis || size != diskSize);
    }

    /** Immutable copy of the last loaded/saved disk identity, captured on the FX thread for background I/O. */
    public record DiskSnapshot(
            long modifiedMillis, long size, @Nullable String fingerprint) {
        public boolean differsFrom(long currentModifiedMillis, long currentSize) {
            return modifiedMillis >= 0 && (modifiedMillis != currentModifiedMillis || size != currentSize);
        }

        /** Compares exact bytes when both snapshots have them, otherwise falls back to metadata. */
        public boolean differsFrom(long currentModifiedMillis, long currentSize, @Nullable String currentFingerprint) {
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
    public void setPath(@Nullable Path path) {
        this.path = path;
        banners.writableOnDisk = path == null || path.getFileSystem() != java.nio.file.FileSystems.getDefault()
                ? banners.writableOnDisk
                : null;
        // The full path (not just the basename) so location-based rules resolve — e.g. ~/.ssh/config,
        // /etc/hosts, .git/config (see ConfigFileType). The registries reduce it to the basename for
        // ordinary extension lookups.
        String lookup = path == null ? null : path.toString();
        applyLanguageFor(lookup == null ? LanguageRegistry.plaintext() : LanguageRegistry.forFileName(lookup), lookup);
        runGutter.recomputeRun(); // a Save-As to a runnable file type can show the gutter Run glyph
    }

    /** Applies language {@code name} with the grammar {@code lookup} (a file name or path, or null) maps to. */
    private void applyLanguageFor(String name, @Nullable String lookup) {
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
                highlighter.invalidateHighlighting();
                highlighter.applyHighlighting();
            });
        });
    }

    /**
     * Gives a still-unsaved buffer a suggested file name: it becomes the tab title and selects the
     * grammar/fold language by extension, while {@link #path} stays null so the first Save prompts for a
     * location (Save-As). No-op once the buffer has a real path.
     */
    public void setDisplayName(@Nullable String name) {
        this.displayName = name == null || name.isBlank() ? null : name;
        if (path == null && displayName != null) {
            applyLanguageFor(LanguageRegistry.forFileName(displayName), displayName);
        }
    }

    /** The suggested name for an unsaved buffer, or null (used as the Save-As default). */
    public @Nullable String getDisplayName() {
        return displayName;
    }

    /** The current language name (see {@link LanguageRegistry}); drives fold strategy and the status bar. */
    public String getLanguage() {
        return language;
    }

    /**
     * Overrides the language/grammar regardless of the file's name (the status-bar picker); plain text turns
     * highlighting off. The Run glyph is re-decided here; the controller hears it via {@link #addLanguageListener}.
     */
    public void setLanguageOverride(String name) {
        String resolved = name == null ? LanguageRegistry.plaintext() : name;
        languageUserOverride = true; // the user's pick wins; don't re-detect a shebang over it
        applyLanguage(resolved, GrammarRegistry.shared().forLanguageName(resolved));
        runGutter.recomputeRun(); // a script picked as Shell (or Python) gets its Run glyph now, not on the next edit
    }

    /** Runs {@code listener} on the FX thread each time the language changes after it is added (a picked
     *  language, a typed or loaded shebang, a Save As to another type): the status bar and LSP follow it. */
    public void addLanguageListener(Runnable listener) {
        languageListeners.add(listener);
    }

    /**
     * Promotes a plain-text buffer to the language of its first-line shebang ({@code #!/usr/bin/env python3},
     * or {@code java --source N} for a compact source), via a synthetic {@code "shebang.<ext>"} name so it
     * behaves exactly like a file of that type. Never overrides a real extension or the user's pick.
     */
    void maybeApplyShebang() {
        int len = area.getLength(); // a shebang, else a vim/emacs modeline in the first or last lines
        String sniffed = languageUserOverride || path == null // a .mod/.sch/… is XML only if it reads so (C13)
                ? null
                : LanguageRegistry.sniffedXmlLanguage(path, language, () -> area.getText(0, Math.min(len, 1024)));
        if (sniffed != null && path != null) {
            applyLanguageFor(sniffed, "xml".equals(sniffed) ? path.toString() : null);
        }
        if (languageUserOverride || !LanguageRegistry.plaintext().equals(language)) {
            return; // a real extension or the user's pick already decided the language
        }
        Shebang.Result r =
                Modeline.detect(area.getText(0, Math.min(len, 1024)), area.getText(Math.max(0, len - 1024), len), path);
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
    public @Nullable Integer getShebangJavaSource() {
        return shebangJavaSource;
    }

    /** Applies a language name + grammar: updates fold strategy and re-highlights. */
    private void applyLanguage(String name, @Nullable IGrammar g) {
        languageGen++; // supersede any in-flight deferred grammar load (see applyLanguageDeferred)
        boolean changed = !name.equals(language);
        this.language = name;
        this.grammar = g;
        folds.setLanguage(language);
        wrap.languageChanged(language); // prose wraps by one preference, code by another
        if (!rulerPlacement.languageRulers.isEmpty()) {
            rulerPlacement.markRulerInputsDirty(); // the language may have rulers of its own
        }
        // Which words are checked, which lines are code, and whether this language is checked at all all
        // follow the language. Must be pushed here, not only at construction: the constructor runs before
        // the language is known, which once left every Markdown buffer checking its ``` fenced code blocks.
        spell.languageChanged();
        // A Save As (or a language override) can turn a .http buffer into Markdown and back: the overlay they
        // share follows the new language — the {{variable}} marks stop, Markdown lint starts, or the reverse.
        setMarkdownLintEnabled(markdownLintWanted);
        httpVariableMarks.update();
        highlighter.invalidateHighlighting(); // grammar changed with no text edit — re-tokenize the whole document
        highlighter.applyHighlighting();
        if (changed) {
            languageListeners.forEach(Runnable::run);
        }
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
        spell.geometryChanged();
        if (indentGuides != null) {
            indentGuides.refresh(); // where a tab's guides fall moved
        }
    }

    public int getTabSize() {
        return tabSize;
    }

    // --- EditorConfig overrides ------------------------------------------------------------------

    /** Forces the indent unit (EditorConfig {@code indent_style}/{@code indent_size}); null = auto-detect. */
    public void setIndentOverride(@Nullable Boolean insertSpaces, @Nullable Integer size) {
        this.indentInsertSpacesOverride = insertSpaces;
        this.indentSizeOverride = size;
    }

    /** The EditorConfig line ending to write ({@code "LF"}/{@code "CRLF"}/{@code "CR"}); null = no override. */
    public void setEolOverride(@Nullable String eol) {
        this.eolOverride = LineEndings.isLabel(eol) ? eol : null;
    }

    public void setRulerColumn(@Nullable Integer column) {
        rulerPlacement.setRulerColumn(column);
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

    public void setCharsetOverride(@Nullable String charset) {
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
        if (userCharset != null) {
            return userCharset;
        }
        return charsetOverride != null && !charsetAssumed ? charsetOverride : detectedCharset;
    }

    /** The encoding the user chose for this file, or null when it follows EditorConfig and detection. */
    public @Nullable String getUserCharset() {
        return userCharset;
    }

    /**
     * Chooses the encoding this buffer is written in, whatever EditorConfig says; null returns it to the rules.
     * A choice other than the encoding on disk is an unsaved change, like a line-ending conversion.
     */
    public void setUserCharset(@Nullable String charset) {
        this.userCharset = charset;
        dirty.set(differsFromSaved());
    }

    /** A save wrote the file in {@code charset}: that is what is on disk now, and no longer a pending change. */
    public void charsetSaved(String charset) {
        setDetectedCharset(charset);
        dirty.set(differsFromSaved());
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

    /** Enables/disables spell checking for this buffer (the master switch; see {@link BufferSpell#apply}). */
    public void setSpellCheckEnabled(boolean on) {
        spell.setEnabled(on);
    }

    public boolean isSpellCheckEnabled() {
        return spell.isEnabled();
    }

    public void setSpellLanguage(String langId) {
        spell.setLanguage(langId);
    }

    public String getSpellLanguage() {
        return spell.getLanguage();
    }

    /** Large-file mode (see {@link #LARGE_FILE_BYTES}): no highlighting, minimap, spell check, whitespace marks, indent
     *  guides, current-line highlight or undo, so huge or long-lined documents stay responsive. Set before the text. */
    public void setLargeFile(boolean large) {
        if (this.largeFile == large) {
            return;
        }
        this.largeFile = large;
        highlighter.highlightGen++; // discard any in-flight highlight result
        folds.setHeuristicEnabled(!large); // never schedule a whole-document fold scan for a large file
        setMinimapVisible(minimapVisible); // re-apply with the large-file guard
        spell.applyActive(); // spell checking is off in large-file mode (like highlighting)
        if (whitespace != null) {
            whitespace.setSuppressed(large); // so are the whitespace markers, whatever the setting says
        }
        if (indentGuides != null) {
            indentGuides.setSuppressed(large); // and the indent guides
        }
        setLineHighlightOn(LineHighlighter.wanted(area)); // and the current-line highlight
        applyUndoMode(); // nor undo history: a large file shouldn't pay its memory
        if (!large) {
            highlighter.applyHighlighting(); // re-enable highlighting if we ever leave large-file mode
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
        banners.viewModeReason = viewMode ? banners.viewModeReason : null;
        applyEditable();
    }

    /** View mode, with the banner saying why ({@code reason}) instead of the general sentence. */
    public void setViewMode(boolean viewMode, @Nullable String reason) {
        banners.viewModeReason = reason;
        setViewMode(viewMode);
    }

    public boolean isViewMode() {
        return viewMode;
    }

    /** Keeps an empty loading shell non-editable without showing the user-facing View Mode banner. */
    public void setLoading(boolean loading) {
        this.loading = loading;
        applyEditable();
        setLineHighlightOn(LineHighlighter.wanted(area)); // held back while loading (see LineHighlighter)
    }

    /** True while this is a shell whose document has not arrived: its (empty) text is not the file. */
    public boolean isLoading() {
        return loading;
    }

    /** True when the buffer accepts edits — no huge-file, View mode, loading shell or log filter is active. */
    public boolean isEditable() {
        return !hugeFile && !viewMode && !loading && !logView.filtered() && !encryption.isLocked();
    }

    public BufferEncryption encryption() {
        return encryption;
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
        banners.updateViewModeBar();
    }

    public void setOnEnableEditing(Runnable onEnableEditing) {
        banners.setOnEnableEditing(onEnableEditing);
    }

    public void setAdminEditAvailable(boolean available) {
        banners.setAdminEditAvailable(available);
    }

    public void setWritableOnDisk(@Nullable Boolean writable) {
        banners.setWritableOnDisk(writable);
    }

    public void refreshTopBars() {
        banners.refreshTopBars();
    }

    public void setInstallPrompt(String message, String actionLabel, Runnable onInstall, Runnable onDismiss) {
        banners.setInstallPrompt(message, actionLabel, onInstall, onDismiss);
    }

    public void showInstallBar(boolean show) {
        banners.showInstallBar(show);
    }

    public boolean isInstallBarShown() {
        return banners.isInstallBarShown();
    }

    public void setInstallBarBusy(boolean busy) {
        banners.setInstallBarBusy(busy);
    }

    public void showFileNotice(String message, String actionLabel, String dismissLabel, Runnable onAction) {
        banners.showFileNotice(message, actionLabel, dismissLabel, onAction);
    }

    public void hideFileNotice() {
        banners.hideFileNotice();
    }

    public @Nullable String fileNoticeText() {
        return banners.fileNoticeText();
    }

    public void setSnippetProvider(java.util.function.BiFunction<String, String, Snippet> provider) {
        snippetKeys.setSnippetProvider(provider);
    }

    public void setSnippetOfferProvider(java.util.function.BiFunction<String, String, Snippet> provider) {
        snippetKeys.setSnippetOfferProvider(provider);
    }

    /** True while a snippet's tab stops are being navigated (Tab cycles fields). */
    public boolean hasActiveSnippet() {
        return snippetSession.isActive();
    }

    public void setSnippetTabExpansion(boolean on) {
        snippetKeys.setSnippetTabExpansion(on);
    }

    public void setSnippetHooks(
            java.util.function.UnaryOperator<@Nullable Path> root, java.util.function.Consumer<int[]> progress) {
        snippetKeys.setSnippetHooks(root, progress);
    }

    /** Leaves the running snippet session where the caret is (what Escape does). */
    public void endSnippetSession() {
        snippetSession.cancel();
    }

    /** The indent unit for this buffer: the EditorConfig override, else the document's own (cached). */
    public String indentUnit() {
        return indentKeys.unit(tabSize, indentInsertSpacesOverride, indentSizeOverride);
    }

    /** Async smart-semicolon lookup; delivers the target {@code {line, character}} or null on the FX thread. */
    public interface LspSmartSemicolonRequester {
        void request(int line, int character, java.util.function.Consumer<int[]> callback);
    }

    public void setLspSmartSemicolonRequester(LspSmartSemicolonRequester requester) {
        typingAids.setLspSmartSemicolonRequester(requester);
    }

    public void setSmartSemicolonEnabled(boolean enabled) {
        typingAids.setSmartSemicolonEnabled(enabled);
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

    public void pressKey(String macroKeyToken) {
        typingAids.pressKey(macroKeyToken);
    }

    public void typeChar(char c) {
        typingAids.typeChar(c);
    }

    /** Coalesces a matching-bracket recompute to the next pulse (caret moves rapidly while typing). */
    void scheduleBraceMatch() {
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
            completionActions.setBraceMatch(new int[] {m[0] + winStart, m[1] + winStart});
        }
    }

    void clearBraceMatch() {
        if (completionActions.braceMatch() != null) {
            removeBraceClass(completionActions.braceMatch()[0]);
            removeBraceClass(completionActions.braceMatch()[1]);
            completionActions.setBraceMatch(null);
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

    public void insertSnippet(Snippet snippet) {
        snippetKeys.insertSnippet(snippet);
    }

    public void applyTemplate(ParsedSnippet parsed) {
        snippetKeys.applyTemplate(parsed);
    }

    /** Requests a short AI continuation of the text around the caret; the result must be delivered on
     *  the FX thread (null/empty = nothing to show). Kept editor-neutral so {@code editor} stays free
     *  of {@code ai}/{@code ui}. */
    public interface AiCompletionProvider {
        void complete(String language, String prefix, String suffix, java.util.function.Consumer<String> onResult);
    }

    // ---- Completion documentation side-popup (IntelliJ "quick documentation") ----

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
    String documentTextSnapshot() {
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

    /** Capture the acceptance independently of future completions and track safe typing until resolve lands. */
    public java.util.function.Consumer<java.util.List<LspTextEdit>> trackCompletionAdditionalEdits() {
        var shift = completionActions.pendingCompletionShift();
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
                completionActions.suppressCompletionAt(docVersion);
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
        applyLspEdits(LspEditShift.shift(edits, completionActions.pendingCompletionShift()), true);
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

    /**
     * Builds a load's paragraphs for {@link #setInitialContent(InitialDocument)}; safe off the FX thread. Meant
     * for the read worker, so the text's fingerprint — what the saved baseline keeps once the buffer is edited —
     * is computed here too, and never on the FX thread.
     */
    public InitialDocument prepareInitialContent(String content, boolean segmentLongLines) {
        return InitialDocument.prepare(area, content, segmentLongLines).fingerprinted();
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
        baseline = truncatedLoad ? null : SavedBaseline.of(initial, loaded.textFingerprint());
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
        runGutter.recomputeRun(
                true); // detect a runnable file on load (drives the Run glyph), scanning off the FX thread
    }

    /**
     * Installs a re-read of the file into a buffer that already shows it. Unlike {@link #setInitialContent},
     * which swaps the whole document, only the span that differs is rewritten ({@link WholeDocumentEdit}):
     * the paragraphs, styles and folds outside it survive and nothing is split again on the FX thread. Like a
     * load, it is not an undo step — the history is forgotten, so undo cannot resurrect text the disk no
     * longer has — and the loaded text becomes the snapshot, the Undo History baseline and, through the
     * caller's {@link #markClean}, the saved baseline. Not for a load that needs the segmented or capped
     * profile ({@code longLine}, truncated): those install through {@link #setInitialContent}.
     */
    public void reloadContent(InitialDocument loaded) {
        lineEnding = loaded.lineEnding();
        String text = loaded.text();
        widen(); // the disk's text is the whole document; a narrowed view would duplicate the held-aside part
        Runnable refilter = logView.suspendFilter(true);
        replaceVisibleText(area, text);
        if (area.getLength() == text.length()) {
            documentSnapshots.seed(docVersion, text); // the rewrite left the document equal to the loaded String
        }
        forgetHistoryAtNarrowBoundary(false);
        captureUndoCheckpoint();
        refilter.run();
        gitLines.reset(); // the disk's lines again
        runGutter.recomputeRun(true);
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
        return NarrowCarry.wholeText(narrowing.narrowHeld, documentTextSnapshot());
    }

    // --- Narrowing -------------------------------------------------------------------------------

    public boolean isNarrowed() {
        return narrowing.isNarrowed();
    }

    public int narrowStart() {
        return narrowing.narrowStart();
    }

    public boolean narrowTo(int start, int end) {
        return narrowing.narrowTo(start, end);
    }

    public void setOnNarrowChanged(Runnable callback) {
        narrowing.setOnNarrowChanged(callback);
    }

    public int narrowFirstLine() {
        return narrowing.narrowFirstLine();
    }

    public boolean canNarrow() {
        return narrowing.canNarrow();
    }

    public void widen() {
        narrowing.widen();
    }

    public boolean takeHistoryDropped() {
        return narrowing.takeHistoryDropped();
    }

    /** The undo stack and the Undo History checkpoints: neither may be replayed across the boundary. */
    void forgetHistoryAtNarrowBoundary(boolean hadHistory) {
        narrowing.historyDropped |= hadHistory;
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

    /** Makes every edit until the returned action runs ONE undo/redo step (a macro replay), in both views. */
    public Runnable beginUndoSpan() {
        return largeFile ? () -> {} : CompletionUndoManager.beginSpan(area, area2);
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
        return area.getLength() + NarrowCarry.heldLength(narrowing.narrowHeld);
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
     * that at once — it must never show as modified when it is not — and so does a dirty buffer that a
     * length-changing edit (a deletion, an undo of an insert) brought back to the saved length: that is how
     * a user lands on the saved text, and the tab marker, the Save button and the Git gutter's reset
     * ({@code GitGutterLines.cleaned}) expect the flag to clear then, not 150 ms later. A dirty buffer edited
     * with a {@code sameLength} change stays dirty and is compared when the edit settles (or when
     * {@link #isDirty()} is asked), so a run of same-length edits (move line up/down, overwrite, transpose)
     * no longer copies the document on every one of them — those runs, not a single landing, were the cost.
     */
    private void dirtyAfterEdit(boolean sameLength) {
        dirtyUnresolved = sameLength
                && dirty.get()
                && !forcedDirty
                && lineEnding.equals(cleanLineEnding)
                && baseline != null
                && contentLength() == baseline.length();
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

    /**
     * Once the edits of a dirty buffer have settled, the baseline's text is no longer the snapshot and would be
     * a second document-sized String for as long as the tab stays modified: keep its fingerprint only.
     */
    private void sealBaselineIfDirty() {
        if (baseline != null && dirty.get() && !dirtyUnresolved) {
            baseline.seal();
        }
    }

    /** Whether the saved baseline still holds its text rather than a fingerprint alone (tests). */
    boolean baselineHoldsText() {
        return baseline != null && baseline.holdsText();
    }

    /** Marks the current content as the saved baseline (after load/save); clears the dirty flag. */
    public void markClean() {
        // The whole document, so narrowing never fakes a dirty flag. Widened, this is the snapshot String
        // itself — shared, not copied — and it stays shared until the next edit settles.
        baseline = SavedBaseline.of(getContent());
        cleanLineEnding = lineEnding;
        forcedDirty = false;
        dirty.set(false);
    }

    /** Whether the buffer differs from what is on disk: its text, line ending or encoding, or a forced flag. */
    private boolean differsFromSaved() {
        return forcedDirty
                || !lineEnding.equals(cleanLineEnding)
                || (userCharset != null && !userCharset.equals(detectedCharset))
                || (baseline != null && (contentLength() != baseline.length() || !baseline.matches(getContent())));
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
        acknowledgeSavedBaseline(SavedBaseline.of(savedContent == null ? "" : savedContent), savedLineEnding);
    }

    /**
     * As above, from what the save worker kept of the text it wrote: its {@link #docVersion() document version},
     * length and {@link TextFingerprint fingerprint}, never the text. When the document has not changed since
     * the save was captured, the current snapshot <em>is</em> the saved text and is shared as the baseline;
     * otherwise the baseline starts sealed and the comparison is by hash.
     */
    public void acknowledgeSavedContent(
            long savedVersion, int savedLength, String savedTextFingerprint, String savedLineEnding) {
        SavedBaseline saved = savedVersion == docVersion
                        && narrowing.narrowHeld == null
                        && !logView.filtered()
                        && area.getLength() == savedLength
                ? SavedBaseline.of(documentTextSnapshot(), savedTextFingerprint)
                : SavedBaseline.sealed(savedLength, savedTextFingerprint);
        acknowledgeSavedBaseline(saved, savedLineEnding);
    }

    private void acknowledgeSavedBaseline(SavedBaseline saved, String savedLineEnding) {
        baseline = saved;
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
}
