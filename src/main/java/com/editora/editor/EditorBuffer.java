package com.editora.editor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;

import com.editora.completion.Completion;
import com.editora.completion.CompletionProvider;
import com.editora.diagram.DiagramKind;
import com.editora.editops.Indenter;
import com.editora.editops.LineEndings;
import com.editora.editops.WholeDocumentEdit;
import com.editora.editor.completion.BufferCompletion;
import com.editora.editor.completion.CompletionEditTracker;
import com.editora.editor.crypto.BufferEncryption;
import com.editora.editor.debug.BreakpointGutterMenu;
import com.editora.editor.debug.BreakpointManager;
import com.editora.editor.debug.DebugHover;
import com.editora.editor.debug.DebugValuePresenter;
import com.editora.editor.debug.ExecutionLine;
import com.editora.editor.debug.InlineValuesOverlay;
import com.editora.editor.fold.FoldManager;
import com.editora.editor.fold.NarrowPresentation;
import com.editora.editor.fold.StickyScroll;
import com.editora.editor.fold.StickyScrollBar;
import com.editora.editor.git.BlameInfo;
import com.editora.editor.git.GitGutterLines;
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
import com.editora.editor.lsp.OccurrenceHighlightOverlay;
import com.editora.editor.markdown.DiagramImages;
import com.editora.editor.markdown.MarkdownCompletions;
import com.editora.editor.markdown.MarkdownEditing;
import com.editora.editor.markdown.MarkdownLintOverlay;
import com.editora.editor.markdown.MarkdownLintStripe;
import com.editora.editor.markdown.MermaidLintOverlay;
import com.editora.editor.markdown.PreviewFind;
import com.editora.editor.markdown.PreviewLayout;
import com.editora.editor.markdown.PreviewMenu;
import com.editora.editor.markdown.PreviewScrollSync;
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
import com.editora.editor.undo.CompletionUndoManager;
import com.editora.editor.undo.UndoHistory;
import com.editora.logviewer.LogLevel;
import com.editora.logviewer.LogQuery;
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
import com.editora.text.OccurrenceSpan;
import com.editora.text.SearchMatches;
import com.editora.text.SemanticToken;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.fxmisc.undo.UndoManager;
import org.jspecify.annotations.Nullable;
import org.reactfx.Subscription;

/** A single open document: a RichTextFX {@link CodeArea} plus its backing file, language, and dirty state. */
public class EditorBuffer implements TabContent {

    /** The completion popup, its keys and the typing hooks around it; told about the buffer by {@link BufferCompletionHost}. */
    final BufferCompletion completionActions = new BufferCompletion(new BufferCompletionHost(this));

    /** Auto-rename-tag: mirrors an HTML/XML tag-name edit onto the paired tag once the edit has committed. */
    final TagRenameMirror tagRename = new TagRenameMirror(
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
    /** Toggle Comment and the Typst insert commands ({@link BufferMarkup}). */
    final BufferMarkup markup = new BufferMarkup(this);
    /** The log viewer's mode, control, highlighting and filter ({@link BufferLog}). */
    final BufferLog logViewer = new BufferLog(this);
    /** A {@code .http} file's Run glyphs, results and variable checks ({@link BufferHttp}). */
    final BufferHttp httpRequests = new BufferHttp(this);
    /** Which previews this buffer's file offers, and their feature gates ({@link BufferPreviewKinds}). */
    final BufferPreviewKinds previewKinds = new BufferPreviewKinds(this);
    /** Loading, reloading and rewriting the whole text; line moves ({@link BufferDocument}). */
    final BufferDocument document = new BufferDocument(this);
    /** Large-file, heavy-file, read-only, View and loading modes ({@link BufferModes}). */
    final BufferModes modes = new BufferModes(this);
    /** The language and grammar from the path, a shebang or the user ({@link BufferLanguage}). */
    final BufferLanguage languageState = new BufferLanguage(this);
    /** Line endings, tab size, charset and EditorConfig overrides ({@link BufferFileFormat}). */
    final BufferFileFormat fileFormat = new BufferFileFormat(this);
    /** The split's second view, fonts, wrap, minimap and background-tab rendering ({@link BufferViews}). */
    final BufferViews views = new BufferViews(this);
    /** Undo grouping, the bounded undo manager and Local History checkpoints ({@link BufferUndo}). */
    final BufferUndo undo = new BufferUndo(this);
    /** Bracket-pair matching at the caret ({@link BufferBraces}). */
    final BufferBraces braces = new BufferBraces(this);
    /** Whether the text differs from the last saved or loaded text ({@link BufferDirty}). */
    final BufferDirty dirtyState = new BufferDirty(this);
    /** Live Mermaid and Markdown linting ({@link BufferLint}). */
    final BufferLint lint = new BufferLint(this);
    /** Personal Notes: applying saved notes and capturing a new one's anchor ({@link BufferNotes}). */
    final BufferNotes noteMarks = new BufferNotes(this);
    /** The gutter: line numbers, change bars, blame, bookmarks, breakpoints and the execution line ({@link BufferGutter}). */
    final BufferGutter gutter = new BufferGutter(this);
    /** A {@code .http} document's script lines, parsed off the FX thread ({@link BufferHttpScripts}). */
    final BufferHttpScripts httpScriptLines = new BufferHttpScripts(this);
    /** The language-server surface: activation, diagnostics, didChange and server edits ({@link BufferLsp}). */
    final BufferLsp lsp = new BufferLsp(this);
    /** What a Ctrl/Cmd-hover underlines as a link ({@link BufferDefinitionLinks}). */
    final BufferDefinitionLinks definitionLinking = new BufferDefinitionLinks(this);
    /** Sticky scroll: when the pinned scope headers update ({@link BufferStickyScroll}). */
    final BufferStickyScroll stickyHeaders = new BufferStickyScroll(this);
    /** The lazily attached overlays and stripes over the text, and their placement ({@link BufferOverlays}). */
    final BufferOverlays overlays = new BufferOverlays(this);
    /** The constructor's listener wiring: gutter hooks, edit subscriptions, settled-edit milestones ({@link BufferWiring}). */
    final BufferWiring wiring = new BufferWiring(this);

    final BooleanProperty dirty = new SimpleBooleanProperty(false);
    /**
     * The last saved/loaded content — its length and fingerprint, and the text itself only while it is still the
     * snapshot ({@link SavedBaseline}); the buffer is dirty only when the text differs from this. Null for a
     * truncated load, which has no baseline: it is never compared, and pins no copy once a log tail moves on.
     */
    @Nullable
    SavedBaseline baseline = SavedBaseline.of("");
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
            new BufferEncryption(outer, viewHost, modes::applyEditable, this::refreshTopBars);
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
    /** Resolves the typst {@code --root} for a saved .typ file (project root for a multi-file doc); injected
     *  from MainController since the editor package can't reach project state. Null ⇒ use the file's folder. */
    java.util.function.@Nullable UnaryOperator<java.nio.file.Path> typstRootResolver;
    /** Per-buffer, session-only: this pom is showing the generic XML tree instead of its summary. */
    boolean pomShowXml;
    /** Filter, follow and trim state of the log viewer; a filtered view is read-only (see {@link LogView}). */
    final LogView logView = new LogView(area, modes::applyEditable);
    /** Fired from the debounced edit pulse while an HTML preview is served (its site reloads on any file's edit). */
    @Nullable
    Runnable htmlPreviewDirtyListener;
    /** One document subscription and timer sequence for all differently-timed settled-edit work. */
    final SettledEditDispatcher settledEdits = new SettledEditDispatcher();

    @Nullable
    Subscription settledEditSub;

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
    /**
     * Files of at least this many lines open in large-file mode (no highlighting, minimap, undo, …; see
     * {@link #setLargeFile}). Measured on 2026-11-22: typing and scrolling stay smooth with everything on even at
     * 400,000 lines, so what this bounds is memory (highlighting keeps about 450 bytes a line) and the first
     * highlighting pass (about 6 s per 100,000 lines of Java, while every other tab of the language waits for its
     * colours). Lines, not bytes, set both: 5 MB was 115,000 lines of Java but 315,000 of pretty-printed JSON.
     */
    public static final int LARGE_FILE_LINES = 200_000;
    /**
     * Files of at least this size open in large-file mode whatever their line count — a backstop for long-lined
     * files, whose undo memory for a bulk edit (Replace All keeps the text it replaced) has not been measured.
     * Was the only test, at 5 MB, until 2026-11-22.
     */
    public static final long LARGE_FILE_BYTES = 16L * 1024 * 1024;

    /** Whether a document of {@code chars} characters in {@code lines} lines opens in large-file mode. */
    public static boolean largeFileMode(long chars, int lines) {
        return lines >= LARGE_FILE_LINES || chars >= LARGE_FILE_BYTES;
    }
    /** Files at/above this size are opened read-only (and truncated by the loader). */
    public static final long HUGE_FILE_BYTES = 50L * 1024 * 1024;
    /** Whether the minimap is shown; applied to every split pane's minimap. */
    boolean minimapVisible = true;
    /** Whether this buffer is the visible tab (see {@link #setRenderingActive}); starts true because a
     *  freshly built buffer measures itself before the controller marks background tabs inactive. */
    boolean renderingActive = true;
    /** Large-file mode: syntax highlighting and the minimap are disabled regardless of settings. */
    boolean largeFile;
    /** Intermediate "large source file" tier (below large-file mode): the language server is not started —
     *  highlighting, the minimap and editing stay. Triggered by line count at load (see {@code setHeavyFile});
     *  toggleable per-buffer. {@code largeFile} implies the same LSP suppression. */
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

    final ColumnRuler columnRuler = new ColumnRuler(area);
    final Minimap minimap = new Minimap(area);
    final JumpReveal jumps = new JumpReveal(
            () -> stickyHeaders.stickyScrollEnabled,
            a -> a == focusedArea,
            () -> this.folds.regions(),
            this::stickyLinesFor,
            () -> this.stickyLines.size());
    /** Lazily attached when "show whitespace" first turns on — see {@link #whitespace()}. */
    @Nullable
    WhitespaceOverlay whitespace;
    /** Lazily attached when indent guides first turn on — see {@link #indentGuides()}. */
    @Nullable
    IndentGuideOverlay indentGuides;
    /** Eager: spell checking is on by default for prose and for the comments and strings of code. */
    final SpellCheckOverlay spellOverlay = new SpellCheckOverlay(area, this::documentText);

    @Nullable
    LogHighlightOverlay logOverlay; // lazily attached on first activation — see logOverlay()

    @Nullable
    InlineValuesOverlay inlineValues; // lazily attached when a debug session first suspends here
    /** Git change bars + blame column, kept on the right lines through unsaved edits. */
    final GitGutterLines gitLines = new GitGutterLines(area, dirty, this::refreshGutter, minimap);
    /** Fixed annotation-column width in px, computed from the widest author+date when blame is set, so
     *  line numbers stay aligned regardless of which row's gutter is (re)built. */
    double blameColumnWidth;

    @Nullable
    MermaidLintOverlay lintOverlay; // lazily attached — see lintOverlay()

    @Nullable
    MarkdownLintOverlay mdLintOverlay; // lazily attached when Markdown linting turns on — see mdLintOverlay()
    /** Squiggles under unresolvable {{variables}} of a .http buffer (on the same overlay as Markdown lint). */
    final HttpVariableMarks httpVariableMarks = new HttpVariableMarks(
            this::mdLintOverlay,
            this::documentTextSnapshot,
            () -> this.httpFeatureEnabled && isHttpFile() && !this.hugeFile,
            () -> this.markdownLintEnabled);

    @Nullable
    LspDiagnosticOverlay lspOverlay; // lazily attached — see lspOverlay()
    /** Severity stripe over the editor scrollbar (shown whenever LSP is active), so diagnostics stay locatable;
     *  lazily attached when LSP first activates for this buffer — see {@link #diagnosticStripe()}. */
    @Nullable
    DiagnosticStripe diagnosticStripe;

    /** TODO/highlight overview stripe over the scrollbar (beside the diagnostic stripe); lazily attached with
     *  the first non-empty scan result — see {@link #todoStripe()}. */
    @Nullable
    TodoStripe todoStripe;
    /** Markdown-lint overview stripe over the scrollbar (beside the TODO/diagnostic stripes); lazily attached
     *  with the Markdown-lint overlay — see {@link #mdLintStripe()}. */
    @Nullable
    MarkdownLintStripe mdLintStripe;
    /** Whether the HTTP Client feature is enabled for this buffer (gated by {@code Settings.httpClientSupport},
     *  Simple UI mode and a local file) — a {@code .http} file then shows a Run glyph on every request line. */
    boolean httpFeatureEnabled;
    /** A {@code .http} buffer's request ▶s, the running ■ and the injected {@link HttpHooks}. */
    final HttpGutter httpGutter = new HttpGutter();
    /** A {@code .http} buffer's last results after its request lines, and the ▶ colours they give (U11). */
    final HttpResultMarks httpResults = new HttpResultMarks();

    @Nullable
    SearchHighlightOverlay searchOverlay; // lazily attached — see searchOverlay()

    @Nullable
    OccurrenceHighlightOverlay occurrenceOverlay; // lazily attached — see occurrenceOverlay() (#675)

    /** Highlights configured TODO/FIXME-style patterns (per-pattern color), behind the text; lazily attached
     *  with the first non-empty scan result (TODO highlighting is on by default, but most files have no
     *  marks) — see {@link #todoOverlay()}. */
    @Nullable
    TodoHighlightOverlay todoOverlay;

    @Nullable
    AceJumpOverlay aceJump; // lazily attached — see aceJump()

    boolean mermaidLintEnabled;
    boolean markdownLintEnabled;
    /** Image files and browser images dropped onto a Markdown or Typst buffer; see {@link ImageDrop}. */
    final ImageDrop imageDrop =
            new ImageDrop(() -> (isMarkdown() || isTypst()) && isEditable(), this::isMarkdown, this::caretToDrop);
    /** LSP: overlay active (diagnostics + hover), the debounced didChange sink, and the hover tooltip. */
    boolean lspActive;

    final java.util.List<CompletionEditTracker> completionEditTrackers = new java.util.ArrayList<>();

    /** Debounced pull-diagnostics request (servers that answer textDocument/diagnostic); null = none. */
    @Nullable
    Runnable lspDiagnosticsRequester;
    /** Debounced semantic-tokens request (servers advertising range semantic tokens); null = none. */
    @Nullable
    Runnable semanticTokensRequester;
    /** Debounces a viewport semantic-tokens re-request after scrolling settles (so a new region gets
     *  tokens without firing a server request per scroll frame). */
    final javafx.animation.PauseTransition semanticScrollDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
    /** Completion trigger characters the server advertised (e.g. {@code .} for Java, {@code <} for HTML). */
    java.util.Set<Character> lspTriggerChars = java.util.Set.of();
    /** Signature-help trigger chars (usually '(' and ',') — see setLspSignatureTriggerChars (#674). */
    java.util.Set<Character> lspSignatureTriggerChars = java.util.Set.of();
    /** Fired (deferred a pulse) when a signature trigger char is typed; null = disabled. */
    java.util.function.@Nullable Consumer<Character> signatureHelpRequester;

    final FoldManager folds = new FoldManager(area, this::documentText);

    /** The gutter numbers, {@code narrowed} style and banner of a narrowed buffer (see {@link NarrowPresentation}). */
    final NarrowPresentation narrowView = new NarrowPresentation(this, area, logView, folds);

    /** Pinned enclosing-scope headers over the top of the code pane; see {@link StickyScroll}. */
    final StickyScrollBar stickyScroll = new StickyScrollBar();

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
    boolean debugEnabled = false;
    /** Handles a breakpoint-strip click; default toggles. The controller overrides to persist + re-send. */
    java.util.function.BiConsumer<EditorBuffer, Integer> gutterBreakpointClick =
            (buffer, line) -> buffer.toggleBreakpoint(line);
    /** Handles a Shift+click on the breakpoint strip; default does what a plain click does. The controller asks
     *  for a log message and makes the line a logpoint. */
    java.util.function.BiConsumer<EditorBuffer, Integer> gutterLogpointClick =
            (buffer, line) -> buffer.gutterBreakpointClick.accept(buffer, line);
    /** A right-click on a breakpoint's gutter glyph (the controller shows Edit / Disable / Remove). */
    BreakpointGutterMenu gutterBreakpointMenu = (buffer, line, e) -> {};

    /**
     * IntelliJ's Run to Cursor in the gutter, which the controller answers: whether a line's number offers it now (the
     * debugger paused), what its arrow says on hover, and the run to that line ({@code force}: ignoring breakpoints).
     */
    public interface GutterRunToHere {
        boolean offeredAt(EditorBuffer buffer, int line);

        String tooltip(EditorBuffer buffer, int line);

        void run(EditorBuffer buffer, int line, boolean force);
    }

    @Nullable
    GutterRunToHere gutterRunToHere;
    /** Personal Notes for this buffer (inline marker + highlight + hover). */
    final NoteManager notes = new NoteManager(area);

    final NoteHighlightOverlay noteOverlay = new NoteHighlightOverlay(area);
    /** When false, the Personal Notes feature is disabled for this buffer (no "Add Note" menu items). */
    boolean notesEnabled = false;
    /** The popup over a note's span and the click on its start marker ({@link NoteHover}). */
    final NoteHover noteHover = new NoteHover(
            notes,
            () -> noteMarks.noteIndicators,
            n -> noteMarks.noteMarkerClick.accept(this, n),
            () -> this.fontFamily,
            () -> this.fontSize,
            () -> this.path != null ? this.path.getParent() : null);

    /** Handles a click on a line's blame annotation (the controller shows that line's commit). Default: no-op. */
    java.util.function.BiConsumer<EditorBuffer, Integer> gutterBlameClick = (buffer, line) -> {};
    /** Invoked by the "Add Personal Note" context-menu item (the controller prompts + creates). */
    java.util.function.Consumer<EditorBuffer> addNoteHandler = b -> {};

    /** "Add Review Comment" ({@code false}) and "Suggest Change" ({@code true}) from the context menu; null: no rows. */
    java.util.function.@Nullable BiConsumer<EditorBuffer, Boolean> reviewCommentHandler;
    /** LSP navigation actions (controller-supplied); shown in the context menu only while LSP is active. */
    Runnable lspGotoDefinitionAction = () -> {};

    Runnable lspCodeActionsAction = () -> {};
    /** Whether this buffer's server advertises code actions (quick fixes) — gates the menu item (#670). */
    boolean lspCodeActionsAvailable;

    /** The code-action lightbulb on the caret line (the coordinator gives it its probe). */
    final CodeActionBulb bulb = new CodeActionBulb(
            focusedView.getReadOnlyProperty(),
            () -> lspActive && lspCodeActionsAvailable && !largeFile && isEditable(),
            this::docVersion,
            this::refreshGutterLine);

    /** The file's problem counts at the top right of the editor. */
    final ProblemCounterSlot problemCounter = new ProblemCounterSlot(
            () -> lspActive && !largeFile, viewLayout::placeCornerControl, viewLayout::removeCornerControl);

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
    /** Spell checking (Lucene Hunspell, drawn by {@link #spellOverlay}); off until the controller enables it. */
    final BufferSpell spell = new BufferSpell(
            area, spellOverlay, this::getFocusedArea, () -> language, this::isEditable, () -> largeFile);
    /** Named definitions from the last tokenization (FX-thread confined); drives the Structure view. */
    List<TextMateHighlighter.Symbol> symbols = List.of();
    /** Notified (on the FX thread) after {@link #symbols} is refreshed. */
    Runnable onSymbolsChanged = () -> {};

    String fontFamily = "monospace";
    int fontSize = 14;
    /** Whether {@link #setFont} has run at least once. The fields above are only *assumed* defaults — the
     *  area's inline style and the overlays' fonts are set by that call, so the first one must always
     *  apply even when it happens to match them. */
    boolean fontApplied;
    /**
     * Current-line highlight fill; varies per editor theme (see {@link #setLineHighlightColor}). Until the theme
     * sets it, a faint neutral wash, no theme's colour (ICON-08).
     */
    Color lineHighlightColor = Color.gray(0.5, 0.12);
    /** Visual tab width (columns); applied to the minimap and persisted via Settings. */
    int tabSize = 4;
    // EditorConfig overrides (null = no override → fall back to detection/global). See com.editora.editorconfig.
    /** One full-text materialization shared by every consumer of the current {@link #docVersion}. */
    final DocumentSnapshots documentSnapshots = new DocumentSnapshots();

    /** Per-buffer Emacs mark ring (session-only); shifted through edits by the subscription in the ctor. */
    final com.editora.editops.MarkRing markRing = new com.editora.editops.MarkRing();

    @Nullable
    Boolean indentInsertSpacesOverride;

    @Nullable
    Integer indentSizeOverride;

    @Nullable
    String eolOverride; // "LF"/"CRLF"/"CR" forced by EditorConfig end_of_line; null = none
    /** The file's own line ending: detected on load or chosen by a conversion. The document holds bare LF. */
    String lineEnding = LineEndings.LF;

    String detectedCharset = com.editora.editorconfig.EditorConfigCharset.UTF_8;
    /** The encoding the user chose (Reopen / Save with Encoding); outranks EditorConfig. Null = none. */
    @Nullable
    String userCharset;

    boolean lineNumbersVisible = true;
    /** When false (Simple UI mode), the entire gutter is removed (null paragraph-graphic factory) — no
     *  line numbers, fold chevrons, bookmark/note/run/breakpoint slots, or git change bars. */
    boolean gutterVisible = true;

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
        wiring.install();
        scrollSync = new PreviewScrollSync(
                area, () -> markdownViewMode, () -> previewPanes.previewPane, () -> previewPanes.markdownPreview);
        wiring.installOverlays();
    }

    /** A Markdown document up to this long keeps its live preview even in large-file mode (see below). */
    public static final int PREVIEW_LIVE_MAX_CHARS = 2_000_000;

    /**
     * Whether an edit refreshes the preview. Large-file mode stops it — except for a Markdown document that
     * is "large" only for one very long line (a pasted {@code data:} image can pass the long-line limit at
     * once): its preview used to render once and then silently stay as it was.
     */
    boolean previewLive() {
        return !largeFile || (isMarkdown() && area.getLength() <= PREVIEW_LIVE_MAX_CHARS);
    }

    /** The site an HTML page's link under a Ctrl/Cmd-hover is looked for on (the page is parsed whole). */
    public static final String HTML_LINK_HOVER_SITE = "EditorBuffer.htmlLinkHover";

    /** The site an HTML page's link under a Ctrl/Cmd-click is looked for on, without a language server. */
    public static final String HTML_LINK_CLICK_SITE = "EditorBuffer.htmlLinkClick";

    /** Asks the language server whether the symbol at a 0-based line and UTF-16 column has a definition. */
    @FunctionalInterface
    public interface DefinitionProbe {
        void probe(int line, int column, java.util.function.Consumer<Boolean> found);
    }

    @Nullable
    DefinitionLinkOverlay definitionLinkOverlay;
    /** The primary view's Ctrl/Cmd-hover links (the split's second view has its own). */
    @Nullable
    DefinitionLinks definitionLinks;

    DefinitionLinkOverlay definitionLinkOverlay() {
        return overlays.definitionLinkOverlay();
    }

    @Nullable
    DefinitionLinkOverlay definitionLinkOverlayForTest() {
        return overlays.definitionLinkOverlayForTest();
    }

    DefinitionLinks definitionLinksForTest() {
        return definitionLinking.definitionLinksForTest();
    }

    public void setDefinitionProbe(@Nullable DefinitionProbe probe) {
        definitionLinking.setDefinitionProbe(probe);
    }

    void probeDefinitionLink(
            CodeArea a, int offset, java.util.function.Consumer<DefinitionLinks.@Nullable Span> answer) {
        definitionLinking.probeDefinitionLink(a, offset, answer);
    }

    /** Injects the external-URL opener (controller's HostServices) used by Ctrl/Cmd-click + open-link. */
    public void setOpenUrlHandler(java.util.function.Consumer<String> handler) {
        completionActions.setLinkOpener(this.openUrlHandler = handler == null ? u -> {} : handler);
    }

    /** The external-URL opener injected by {@link #setOpenUrlHandler} (a no-op until then). */
    public java.util.function.Consumer<String> openUrlHandler() {
        return openUrlHandler;
    }

    /**
     * Opens the link under the caret externally once its destination, worked out off the FX thread, lands;
     * {@code done} hears false when the caret is not on a link.
     */
    public void openLinkUnderCaret(java.util.function.Consumer<Boolean> done) {
        markdownEditing.linkUnderCaret(url -> {
            if (url != null) {
                openUrlHandler.accept(url);
            }
            done.accept(url != null);
        });
    }

    public void setFormatBarEnabled(boolean enabled) {
        selectionBars.setFormatBarEnabled(enabled);
    }

    void scheduleStickyScroll() {
        stickyHeaders.scheduleStickyScroll();
    }

    public void setStickyScrollEnabled(boolean enabled) {
        stickyHeaders.setStickyScrollEnabled(enabled);
    }

    public boolean isStickyScrollEnabled() {
        return stickyHeaders.isStickyScrollEnabled();
    }

    /** Shows a jump's target line in one of this buffer's views, clear of the sticky-scroll rows. */
    public JumpReveal jumps() {
        return jumps;
    }

    java.util.List<Integer> stickyScrollLines() {
        return stickyHeaders.stickyScrollLines();
    }

    javafx.scene.Node stickyScrollNode() {
        return stickyHeaders.stickyScrollNode();
    }

    java.util.List<Integer> stickyLines = java.util.List.of();

    void stickyLinesFor(int firstVisible) {
        stickyHeaders.stickyLinesFor(firstVisible);
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

    public boolean canFormatMarkdown() {
        return markup.canFormatMarkdown();
    }

    public boolean canFormatMarkup() {
        return markup.canFormatMarkup();
    }

    public boolean supportsComments() {
        return markup.supportsComments();
    }

    public boolean toggleComment() {
        return markup.toggleComment();
    }

    /** {@link com.editora.perf.BlockingSites} name of Toggle Comment's work, done off the FX thread. */
    public static final String COMMENT_SITE = "EditorBuffer.toggleComment";

    public boolean toggleComment(
            java.util.function.Consumer<@Nullable String> done, java.util.function.Function<String, String> whyNot) {
        return markup.toggleComment(done, whyNot);
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

    /** Inserts or regenerates the table of contents; {@code done} hears whether there was one to write. */
    public void insertOrUpdateToc(java.util.function.Consumer<Boolean> done) {
        markdownEditing.insertOrUpdateToc(done);
    }

    public void insertAtCaret(String text) {
        markup.insertAtCaret(text);
    }

    public void insertTypstTableInteractive() {
        markup.insertTypstTableInteractive();
    }

    public void setInsertTypstTableHandler(Runnable handler) {
        markup.setInsertTypstTableHandler(handler);
    }

    public void setTypstImageHandler(Runnable handler) {
        markup.setTypstImageHandler(handler);
    }

    public void insertTypstTable(int rows, int cols) {
        markup.insertTypstTable(rows, cols);
    }

    public void insertTypstOutline() {
        markup.insertTypstOutline();
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

    Runnable selectionChanged = () -> {};

    /** Runs {@code listener} when the selection of either view changes, or the other view comes into use. */
    public void onSelectionChanged(Runnable listener) {
        selectionChanged = listener;
        focusedView.addListener((obs, was, now) -> listener.run());
    }

    // --- Lazily-attached feature overlays --------------------------------------------------------------
    // These overlays are inert for most buffers (LSP off, not a diagram, not a log, never searched/ace-jumped),
    // so they are built + wired only on first activation rather than per buffer. Each is inserted just below a
    // fixed eager sibling to preserve the z-order of the original construction. They are built in BufferOverlays.

    void anchorOverText(Node overlay) {
        overlays.anchorOverText(overlay);
    }

    <T extends javafx.scene.layout.Region> T attachLazyOverlay(T overlay, int layer) {
        return overlays.attachLazyOverlay(overlay, layer);
    }

    private SearchHighlightOverlay searchOverlay() {
        return overlays.searchOverlay();
    }

    private OccurrenceHighlightOverlay occurrenceOverlay() {
        return overlays.occurrenceOverlay();
    }

    TodoHighlightOverlay todoOverlay() {
        return overlays.todoOverlay();
    }

    IndentGuideOverlay indentGuides() {
        return overlays.indentGuides();
    }

    public void setIndentGuidesVisible(boolean visible) {
        overlays.setIndentGuidesVisible(visible);
    }

    WhitespaceOverlay whitespace() {
        return overlays.whitespace();
    }

    MarkdownLintOverlay mdLintOverlay() {
        return overlays.mdLintOverlay();
    }

    InlineValuesOverlay inlineValues() {
        return overlays.inlineValues();
    }

    DiagnosticStripe diagnosticStripe() {
        return overlays.diagnosticStripe();
    }

    MarkdownLintStripe mdLintStripe() {
        return overlays.mdLintStripe();
    }

    TodoStripe todoStripe() {
        return overlays.todoStripe();
    }

    LogHighlightOverlay logOverlay() {
        return overlays.logOverlay();
    }

    public com.editora.editor.logviewer.@Nullable LogStripe logStripeIfAttached() {
        return overlays.logStripeIfAttached();
    }

    MermaidLintOverlay lintOverlay() {
        return overlays.lintOverlay();
    }

    LspDiagnosticOverlay lspOverlay() {
        return overlays.lspOverlay();
    }

    private AceJumpOverlay aceJump() {
        return overlays.aceJump();
    }

    public void setSearchMatches(SearchMatches matches, int activeIndex) {
        overlays.setSearchMatches(matches, activeIndex);
    }

    public void clearSearchMatches() {
        overlays.clearSearchMatches();
    }

    public void setOccurrenceSpans(java.util.List<OccurrenceSpan> spans) {
        overlays.setOccurrenceSpans(spans);
    }

    public void clearOccurrenceSpans() {
        overlays.clearOccurrenceSpans();
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

    /**
     * A review thread's marker: drawn after the thread's last line ({@code line}, 0-based) like a code lens, kept on
     * its line through edits, and clickable. {@code threadId} names the thread for the click handler.
     */
    public record ReviewLens(int line, String label, String threadId) {}

    /** Sets the review markers; null or empty clears them. */
    public void setReviewLenses(java.util.@Nullable List<ReviewLens> lenses) {
        inlayDecor.setReviewLenses(lenses);
    }

    public void setReviewLensHandler(java.util.function.BiConsumer<Integer, java.util.List<ReviewLens>> handler) {
        inlayDecor.setReviewLensHandler(handler);
    }

    /** The review markers on {@code line} (0-based), as they are now (edits move them). */
    public java.util.List<ReviewLens> reviewLensesOn(int line) {
        return inlayDecor.reviewLensesOn(line);
    }

    /** Every line that has a review marker now, in order. */
    public java.util.NavigableSet<Integer> reviewLensLines() {
        return inlayDecor.reviewLensLines();
    }

    public void activateReviewLens(int line) {
        inlayDecor.activateReviewLens(line);
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

    public void startAceJump() {
        overlays.startAceJump();
    }

    public void startAceJumpLine() {
        overlays.startAceJumpLine();
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

    public void setMarkwhenView(MarkwhenView view) {
        previewKinds.setMarkwhenView(view);
    }

    public void toggleMarkwhenView() {
        previewKinds.toggleMarkwhenView();
    }

    public void setOnMarkwhenViewChanged(Runnable callback) {
        previewKinds.setOnMarkwhenViewChanged(callback);
    }

    /** An HTML file (.html/.htm/.xhtml) — eligible for the HTML Live Preview "open in browser" control. */
    public boolean isHtml() {
        return "html".equals(language);
    }

    /** A delimiter-separated-values file (.csv/.tsv) — drives the status-bar column readout + CSV commands. */
    public boolean isCsv() {
        return "csv".equals(language);
    }

    public boolean hasPreview() {
        return previewKinds.hasPreview();
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

    public boolean hasSvgPreview() {
        return previewKinds.hasSvgPreview();
    }

    public void setSvgPreviewEnabled(boolean enabled) {
        previewKinds.setSvgPreviewEnabled(enabled);
    }

    /** A Typst document buffer (.typ). The whole buffer renders to a multi-page image preview via the typst
     *  CLI (see {@link TypstImages}). */
    public boolean isTypst() {
        return "typst".equals(language);
    }

    public boolean hasTypstPreview() {
        return previewKinds.hasTypstPreview();
    }

    public void setTypstPreviewEnabled(boolean enabled) {
        previewKinds.setTypstPreviewEnabled(enabled);
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

    public boolean hasStructuredPreview() {
        return previewKinds.hasStructuredPreview();
    }

    /** An XML document: not an {@code .svg}, which renders as an image, nor a DTD, which has no tree (C11). */
    public boolean isXml() {
        String name = path != null ? String.valueOf(path.getFileName()) : displayName;
        return "xml".equals(language) && !isSvg() && !com.editora.structured.DtdDeclarations.isDtdFileName(name);
    }

    public boolean hasXmlPreview() {
        return previewKinds.hasXmlPreview();
    }

    /** A crontab buffer (language {@code crontab}: a crontab / *.cron / cron.d file — see ConfigFileType). */
    public boolean isCrontab() {
        return "crontab".equals(language);
    }

    public boolean hasCrontabPreview() {
        return previewKinds.hasCrontabPreview();
    }

    public void setCrontabPreviewEnabled(boolean enabled) {
        previewKinds.setCrontabPreviewEnabled(enabled);
    }

    /** An {@code /etc/fstab} buffer (language {@code fstab} — see ConfigFileType). */
    public boolean isFstab() {
        return "fstab".equals(language);
    }

    public boolean hasFstabPreview() {
        return previewKinds.hasFstabPreview();
    }

    public void setFstabPreviewEnabled(boolean enabled) {
        previewKinds.setFstabPreviewEnabled(enabled);
    }

    /** A systemd unit buffer (language {@code systemd}: .service/.timer/.socket/… — see LanguageRegistry). */
    public boolean isSystemd() {
        return "systemd".equals(language);
    }

    public boolean hasSystemdPreview() {
        return previewKinds.hasSystemdPreview();
    }

    public void setSystemdPreviewEnabled(boolean enabled) {
        previewKinds.setSystemdPreviewEnabled(enabled);
    }

    /** An SSH client-config buffer (language {@code ssh-config} — see ConfigFileType). */
    public boolean isSshConfig() {
        return "ssh-config".equals(language);
    }

    public boolean hasSshConfigPreview() {
        return previewKinds.hasSshConfigPreview();
    }

    public void setSshConfigPreviewEnabled(boolean enabled) {
        previewKinds.setSshConfigPreviewEnabled(enabled);
    }

    /** A Dockerfile buffer (language {@code dockerfile} — see ConfigFileType). */
    public boolean isDockerfile() {
        return "dockerfile".equals(language);
    }

    public boolean hasDockerfilePreview() {
        return previewKinds.hasDockerfilePreview();
    }

    public void setDockerfilePreviewEnabled(boolean enabled) {
        previewKinds.setDockerfilePreviewEnabled(enabled);
    }

    public boolean isGithubActions() {
        return previewKinds.isGithubActions();
    }

    public boolean hasGithubActionsPreview() {
        return previewKinds.hasGithubActionsPreview();
    }

    public void setGithubActionsPreviewEnabled(boolean enabled) {
        previewKinds.setGithubActionsPreviewEnabled(enabled);
    }

    public boolean isPom() {
        return previewKinds.isPom();
    }

    public boolean hasPomPreview() {
        return previewKinds.hasPomPreview();
    }

    public void setPomPreviewEnabled(boolean enabled) {
        previewKinds.setPomPreviewEnabled(enabled);
    }

    /** The language whose snippets this buffer gets: {@code maven-pom} for a pom, else {@code language} (its own). */
    public String snippetLanguage(String language) {
        return isPom() ? PomFiles.SNIPPET_LANGUAGE : language;
    }

    /** Whether this pom buffer is currently showing the generic XML tree instead of the pom summary. */
    public boolean isPomShowingXml() {
        return pomShowXml;
    }

    public boolean togglePomView() {
        return previewKinds.togglePomView();
    }

    boolean hasTreePreview() {
        return previewKinds.hasTreePreview();
    }

    public void setStructuredPreviewEnabled(boolean enabled) {
        previewKinds.setStructuredPreviewEnabled(enabled);
    }

    public boolean isStructuredOpenApi() {
        return previewPanes.isStructuredOpenApi();
    }

    public void toggleStructuredView() {
        previewPanes.toggleStructuredView();
    }

    public void setMermaidValidator(
            java.util.function.BiConsumer<
                            String,
                            java.util.function.Consumer<java.util.List<com.editora.mermaid.MaidOutput.Diagnostic>>>
                    validator) {
        lint.setMermaidValidator(validator);
    }

    public void setMermaidLintEnabled(boolean on) {
        lint.setMermaidLintEnabled(on);
    }

    void scheduleMermaidLint() {
        lint.scheduleMermaidLint();
    }

    // --- Live Markdown linting -----------------------------------------------------------------------

    public void setMarkdownLintValidator(
            java.util.function.BiConsumer<String, java.util.function.Consumer<java.util.List<MarkdownLint.Diagnostic>>>
                    validator) {
        lint.setMarkdownLintValidator(validator);
    }

    public void setMarkdownLintEnabled(boolean on) {
        lint.setMarkdownLintEnabled(on);
    }

    void scheduleMarkdownLint() {
        lint.scheduleMarkdownLint();
    }

    public java.util.List<MarkdownLint.Diagnostic> markdownLintDiagnostics() {
        return lint.markdownLintDiagnostics();
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

    public boolean isLspLanguage() {
        return lsp.isLspLanguage();
    }

    public void setLspChangeListener(java.util.function.Consumer<String> listener) {
        lsp.setLspChangeListener(listener);
    }

    public void sendLspChange() {
        lsp.sendLspChange();
    }

    public boolean sendsLspChanges() {
        return lsp.sendsLspChanges();
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

    public int[] lineStartOffsets() {
        return lsp.lineStartOffsets();
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

    public void setHttpEnabled(boolean enabled) {
        httpRequests.setHttpEnabled(enabled);
    }

    public void setHttpDebugHandler(java.util.function.IntConsumer handler) {
        runGutter.setHttpDebugHandler(handler);
    }

    public void setHttpHooks(HttpHooks hooks) {
        httpRequests.setHttpHooks(hooks);
    }

    /** The hooks injected by {@link #setHttpHooks} ({@link HttpHooks#NONE} until then). */
    public HttpHooks httpHooks() {
        return httpGutter.hooks();
    }

    /** Checks the {@code {{variables}}} again (the environment or the env files changed). */
    public void refreshHttpVariableLint() {
        httpVariableMarks.update();
    }

    public void addMarkupCheck(HttpVariableMarks.MarkupCheck check) {
        httpRequests.addMarkupCheck(check);
    }

    /** The quick fixes for the unresolved-variable marks at {@code offset} of {@code view} ("Use environment dev"). */
    public java.util.List<CodeAction> httpVariableFixesAt(@Nullable CodeArea view, int offset) {
        return HttpVariableFixes.at(httpVariableMarks, httpGutter.hooks(), view, offset);
    }

    public boolean showHttpVariableFixes() {
        return httpRequests.showHttpVariableFixes();
    }

    /** The unresolved-variable marks shown in this {@code .http} buffer (empty while off). */
    public java.util.List<MarkdownLint.Diagnostic> httpVariableDiagnostics() {
        return httpVariableMarks.diagnostics();
    }

    public void setHttpRunning(int index) {
        httpRequests.setHttpRunning(index);
    }

    public void setHttpRunning(int index, String requestLine) {
        httpRequests.setHttpRunning(index, requestLine);
    }

    public void setHttpResults(java.util.List<HttpLineResult> results) {
        httpRequests.setHttpResults(results);
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
        httpRequests.repaintHttpResults(lines);
    }

    /** Whether {@code line} is folded away; false for a line that no longer exists (stale request lines). */
    boolean isFoldedLine(int line) {
        return line >= 0 && line < area.getParagraphs().size() && area.isFolded(line);
    }

    @Nullable
    String paragraphTextOrNull(int line) {
        return httpRequests.paragraphTextOrNull(line);
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

    /** Whether this file belongs to a Maven or Gradle project; Run File then never runs a class of it as one file. */
    public void setJvmBuildProject(boolean inBuild) {
        runGutter.setJvmBuildProject(inBuild);
    }

    /** The JDK release Run File's single-file Java program needs: 25 (compact source), 11 (classic class), else 0. */
    public int javaRunRelease() {
        return runGutter.javaRelease();
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

    /**
     * Runs {@code then} once the Run scan of the text has landed: at once when none is in flight. The scan runs off
     * the FX thread (PERF-01, Pauta 6.2.0), so a Run or Debug command decides from its result by waiting here.
     */
    public void whenRunScanned(Runnable then) {
        runGutter.whenRunScanned(then);
    }

    /**
     * TEST SEAM — whether a settled-edit milestone (highlighting, lint, the preview's reload, LSP sync, …) is still
     * to fire for the last edit. A test waits for it to turn false instead of sleeping past the slowest one.
     */
    public boolean settledEditsPendingForTest() {
        return settledEdits.pendingForTest();
    }

    /** TEST SEAM — lands a Run scan still in flight now, scanning in place; the app uses {@link #whenRunScanned}. */
    public void settleRunScanForTest() {
        runGutter.settleRunScanForTest();
    }

    /** Whether a Run scan of the text is in flight off the FX thread (what {@link #whenRunScanned} waits for). */
    public boolean runScanPending() {
        return runGutter.runScanPending();
    }

    public void setHeavyFileLspAllowed(boolean allowed) {
        lsp.setHeavyFileLspAllowed(allowed);
    }

    public void setLspActive(boolean on) {
        lsp.setLspActive(on);
    }

    public boolean isLspActive() {
        return lspActive;
    }

    public void setLspDiagnostics(java.util.List<LspDiagnostic> diagnostics) {
        lsp.setLspDiagnostics(diagnostics);
    }

    void suppressStaleDiagnostics() {
        lsp.suppressStaleDiagnostics();
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

    boolean inlayHintsActive;

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

    public int[] visibleLineWindow() {
        return lsp.visibleLineWindow();
    }

    void updateDiagnosticStripe() {
        overlays.updateDiagnosticStripe();
    }

    void updateMarkdownLintStripe() {
        overlays.updateMarkdownLintStripe();
    }

    void updateTodoStripe() {
        overlays.updateTodoStripe();
    }

    public void setLspNavActions(
            Runnable gotoDefinition,
            Runnable findReferences,
            Runnable hover,
            Runnable format,
            Runnable codeActions,
            Runnable rename,
            Runnable gotoImplementation,
            Runnable gotoTypeDefinition) {
        lsp.setLspNavActions(
                gotoDefinition,
                findReferences,
                hover,
                format,
                codeActions,
                rename,
                gotoImplementation,
                gotoTypeDefinition);
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

    public void setLspCodeActionsAvailable(boolean available) {
        lsp.setLspCodeActionsAvailable(available);
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

    public com.editora.http.HttpScriptLines.@Nullable Map httpScriptMap() {
        return httpScriptLines.httpScriptMap();
    }

    public void whenHttpScriptMap(Runnable then) {
        httpScriptLines.whenHttpScriptMap(then);
    }

    com.editora.http.HttpScriptLines.@Nullable Map httpScripts;
    /** Current document text (for an initial didOpen). */
    public String text() {
        return documentTextSnapshot();
    }

    public void setInlineValues(java.util.@Nullable Map<String, String> values, int frameLine) {
        overlays.setInlineValues(values, frameLine);
    }

    public void setPinnedValues(java.util.@Nullable Map<Integer, String> shown) {
        overlays.setPinnedValues(shown);
    }

    public void setStepTime(int line, @Nullable String text) {
        overlays.setStepTime(line, text);
    }

    /** The values pinned to this buffer's lines. */
    public com.editora.editor.debug.PinManager getPinManager() {
        return pins;
    }

    public void setBlame(java.util.@Nullable List<BlameInfo> lines) {
        gutter.setBlame(lines);
    }

    static java.util.Set<String> distinctBlameLabels(java.util.List<BlameInfo> lines) {
        return BufferGutter.distinctBlameLabels(lines);
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

    /** The on-disk line (index into the blame list) that area line {@code line} shows, or -1 when none. */
    public int blameDiskLineAt(int line) {
        return gitLines.diskLineAt(line);
    }

    /** The commit hash that last touched the caret line (for "show this commit"), or null. */
    public @Nullable String blameHashAtCaret() {
        return blameHashAt(focusedArea.getCurrentParagraph());
    }

    /** The debugger's value popup (hover and Show Value at Caret) — see {@link DebugHover}. */
    final DebugHover debugHover = new DebugHover();

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

    /** Debug: Quick Evaluate at Caret, the keyboard's Alt+click ({@link DebugHover#quickEvaluateAtCaret}). */
    public boolean quickEvaluateAtCaret() {
        return debugHover.quickEvaluateAtCaret(focusedArea);
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

    public void revealHttpPreview() {
        httpRequests.revealHttpPreview();
    }

    // --- Log viewer ----------------------------------------------------------------------------------

    /** Whether this is a log buffer: a {@code .log} file (language {@code "log"}) or forced "View as Log". */
    public boolean isLog() {
        return "log".equals(language) || logViewer.logViewForced;
    }

    public boolean isLogViewForced() {
        return logViewer.isLogViewForced();
    }

    public void setLogViewForced(boolean forced) {
        logViewer.setLogViewForced(forced);
    }

    public void setLogControl(@Nullable Node control) {
        logViewer.setLogControl(control);
    }

    /** Whether the floating log control is currently attached. */
    public boolean hasLogControl() {
        return logControl != null;
    }

    public void setLogHighlightEnabled(boolean on) {
        logViewer.setLogHighlightEnabled(on);
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

    public void applyLogFilter(@Nullable LogLevel minLevel, LogQuery text) {
        logViewer.applyLogFilter(minLevel, text);
    }

    /** Whether {@code minLevel}/{@code text} is the filter already showing. */
    public boolean showsLogFilter(@Nullable LogLevel minLevel, LogQuery text) {
        return logView.showsFilter(minLevel, text);
    }

    public String logFilterSource() {
        return logViewer.logFilterSource();
    }

    public int logFilterEpoch() {
        return logView.epoch();
    }

    /** Shows a filter computed from {@link #logFilterSource()}; false (and no change) when it is stale. */
    public boolean installLogFilter(
            com.editora.logviewer.LogFilter.Run run, int epoch, @Nullable LogLevel minLevel, LogQuery text) {
        return logView.install(run, epoch, minLevel, text);
    }

    /** The current level floor of the active filter (null when unfiltered or no floor). */
    public @Nullable LogLevel getLogMinLevel() {
        return logView.minLevel();
    }

    /** The text filter of the active filter as typed ({@link LogQuery#NONE} when unfiltered). */
    public LogQuery getLogQuery() {
        return logView.query();
    }

    /** Whether a pattern of the active filter took too long on some line (judged as not matching). */
    public boolean isLogPatternSlow() {
        return logView.slowPattern();
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

    public void setLogSources(com.editora.logviewer.@Nullable LogSources sources) {
        logViewer.setLogSources(sources);
    }

    public com.editora.logviewer.@Nullable LogSources getLogSources() {
        return logViewer.getLogSources();
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

    public void dispose() {
        wiring.dispose();
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

    /** {@link com.editora.perf.BlockingSites} name of Select All Occurrences' search, made off the FX thread. */
    public static final String OCCURRENCES_SITE = BufferCarets.OCCURRENCES_SITE;

    /**
     * Select All Occurrences: searched off the FX thread, then {@code placed} hears the carets placed (on the FX
     * thread, only if nothing moved meanwhile).
     */
    public void selectAllOccurrences(java.util.function.IntConsumer placed) {
        caretOps.selectAllOccurrences(placed);
    }

    /** Split into Lines / Add Cursors to Line Ends: worked out off the FX thread; {@code placed} hears the carets. */
    public void splitSelectionIntoLines(boolean atLineEnds, java.util.function.IntConsumer placed) {
        caretOps.splitSelectionIntoLines(atLineEnds, placed);
    }

    /** Skip Occurrence: searched off the FX thread; {@code moved} hears, on the FX thread, whether it moved. */
    public void skipOccurrence(java.util.function.Consumer<Boolean> moved) {
        caretOps.skipOccurrence(moved);
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

    public boolean isPreviewOnly() {
        return previewPanes.isPreviewOnly();
    }

    public @Nullable PreviewFind previewFind() {
        return previewPanes.previewFind();
    }

    public boolean scrollPreviewToAnchor(String slug) {
        return previewPanes.scrollPreviewToAnchor(slug);
    }

    public void snapshotPreviewChunks(
            String lightUaStylesheet,
            java.util.function.BiConsumer<Integer, Integer> progress,
            java.util.function.Consumer<PreviewSnapshots.Result> done) {
        previewPanes.snapshotPreviewChunks(lightUaStylesheet, progress, done);
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

    void ensureSecondaryView() {
        views.ensureSecondaryView();
    }

    public void setFont(String family, int size) {
        views.setFont(family, size);
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

    UndoManager<?> boundedUndoManager() {
        return undo.boundedUndoManager();
    }

    void breakUndoGroupIfBoundary(String inserted, String removed) {
        undo.breakUndoGroupIfBoundary(inserted, removed);
    }

    // --- Undo History (the Undo History tool window) ------------------------------------------------

    /** Above this document size the Undo History is disabled (full-text snapshots would cost too much). */
    static final int UNDO_HISTORY_MAX_BYTES = 1_000_000;

    final UndoHistory undoHistory = new UndoHistory();

    public UndoHistory getUndoHistory() {
        return undoHistory;
    }

    public void setOnUndoHistoryChanged(@Nullable Runnable r) {
        undo.setOnUndoHistoryChanged(r);
    }

    public void captureUndoCheckpoint() {
        undo.captureUndoCheckpoint();
    }

    public void restoreUndoCheckpoint(UndoHistory.Checkpoint c) {
        undo.restoreUndoCheckpoint(c);
    }

    public void setLineHighlightOn(boolean on) {
        views.setLineHighlightOn(on);
    }

    public void setLineHighlightColor(Color color) {
        views.setLineHighlightColor(color);
    }

    /** Colors the collapsed-region hover preview to match the editor theme. */
    public void setFoldPreviewColors(Color background, Color foreground) {
        folds.setPreviewColors(background, foreground);
    }

    /** Forces the minimap(s) to re-render (after layout/theme settle; the first render may run early). */
    public void refreshMinimap() {
        minimap.refresh();
    }

    public void setRenderingActive(boolean active) {
        views.setRenderingActive(active);
    }

    /**
     * How long a tab stays in the background before {@link #releaseHiddenCaches} runs. The textures go at
     * once (above); these caches are rebuilt on the FX thread in O(document) when next asked for, and a tab
     * switched away from is often switched back to within seconds, so they are kept for a while first.
     */
    static final Duration HIDDEN_RELEASE_DELAY = Duration.ofMinutes(3);

    void releaseHiddenCaches() {
        views.releaseHiddenCaches();
    }

    public void setHighlightDeferred(boolean deferred) {
        highlighter.setHighlightDeferred(deferred);
    }

    public boolean isHighlightDeferred() {
        return highlighter.isHighlightDeferred();
    }

    public void onTabShown() {
        views.onTabShown();
    }

    public void setMinimapColors(Color text, Color viewport) {
        views.setMinimapColors(text, viewport);
    }

    public void setLineNumbersVisible(boolean visible) {
        gutter.setLineNumbersVisible(visible);
    }

    public void setGutterVisible(boolean visible) {
        gutter.setGutterVisible(visible);
    }

    public void refreshGutter() {
        gutter.refreshGutter();
    }

    public FoldManager getFoldManager() {
        return folds;
    }

    public void setChangeBars(java.util.@Nullable Map<Integer, String> lineClasses) {
        gutter.setChangeBars(lineClasses);
    }

    public void setChangeBars(
            java.util.@Nullable Map<Integer, String> lineClasses, java.util.@Nullable Map<Integer, String> hunkText) {
        gutter.setChangeBars(lineClasses, hunkText);
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

    public void setBookmarkToggleRequest(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        gutter.setBookmarkToggleRequest(handler);
    }

    /** Callback fired after any bookmark change (for persistence + the global Bookmarks panel). */
    public void setOnBookmarksChanged(Runnable callback) {
        bookmarks.setOnChanged(callback);
    }

    public void refreshGutterLine(int line) {
        gutter.refreshGutterLine(line);
    }

    public boolean toggleBookmark(int line) {
        return gutter.toggleBookmark(line);
    }

    public void removeBookmark(int line) {
        gutter.removeBookmark(line);
    }

    public boolean applyBookmarks(List<com.editora.config.Bookmark> saved) {
        return gutter.applyBookmarks(saved);
    }

    // ---- Breakpoints (debugging) ----

    public BreakpointManager getBreakpointManager() {
        return breakpoints;
    }

    public void setBreakpointsEnabled(boolean enabled) {
        gutter.setBreakpointsEnabled(enabled);
    }

    public boolean isBreakpointsEnabled() {
        return debugEnabled;
    }

    public void setGutterBreakpointClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        gutter.setGutterBreakpointClick(handler);
    }

    public void setGutterLogpointClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        gutter.setGutterLogpointClick(handler);
    }

    public void setGutterBreakpointMenu(BreakpointGutterMenu menu) {
        gutterBreakpointMenu = menu == null ? (buffer, line, e) -> {} : menu;
    }

    /** Callback fired after any breakpoint change (persist + re-send to a live DAP session). */
    public void setOnBreakpointsChanged(Runnable callback) {
        breakpoints.setOnChanged(callback);
    }

    public boolean toggleBreakpoint(int line) {
        return gutter.toggleBreakpoint(line);
    }

    public void setOnBreakpointRemoved(
            java.util.function.BiConsumer<EditorBuffer, com.editora.config.Breakpoint> listener) {
        gutter.setOnBreakpointRemoved(listener);
    }

    public void dropBreakpoint(int line) {
        gutter.dropBreakpoint(line);
    }

    public boolean restoreBreakpoint(com.editora.config.Breakpoint breakpoint) {
        return gutter.restoreBreakpoint(breakpoint);
    }

    public boolean applyBreakpoints(List<com.editora.config.Breakpoint> saved) {
        return gutter.applyBreakpoints(saved);
    }

    /** The debugger's execution-point highlight (see {@link ExecutionLine}). */
    final ExecutionLine executionLine = new ExecutionLine();

    public void setExecutionLine(int line) {
        gutter.setExecutionLine(line);
    }

    /** Draws the execution line as an exception stop (red) or a plain stop. */
    public void setExecutionLineException(boolean exception) {
        executionLine.setException(area, exception);
    }

    /** Draws the execution line as an earlier stop's (Debug History: grey) or the live one's. */
    public void setExecutionLineHistory(boolean history) {
        executionLine.setHistory(area, history);
    }

    /** Sets who answers the gutter's Run to Here arrow (see {@link GutterRunToHere}); {@code null}: no arrow. */
    public void setGutterRunToHere(@Nullable GutterRunToHere runToHere) {
        this.gutterRunToHere = runToHere;
    }

    public void clearExecutionLine() {
        gutter.clearExecutionLine();
    }

    // ---- Personal Notes ----

    public NoteManager getNoteManager() {
        return notes;
    }

    public void setGutterBlameClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        gutter.setGutterBlameClick(handler);
    }

    /** Sets the "Add Personal Note" context-menu handler (the controller prompts for the body + creates). */
    public void setAddNoteHandler(java.util.function.Consumer<EditorBuffer> handler) {
        if (handler != null) {
            this.addNoteHandler = handler;
        }
    }

    /** Sets what the context menu's review rows do; until then the menu has none. */
    public void setReviewCommentHandler(java.util.function.BiConsumer<EditorBuffer, Boolean> handler) {
        this.reviewCommentHandler = handler;
    }

    public void setRunHandler(Runnable handler) {
        runGutter.setRunHandler(handler);
    }

    public void setDebugFileHandler(Runnable handler, java.util.function.BooleanSupplier shown) {
        runGutter.setDebugFileHandler(handler, shown);
    }

    /** Whether the editor menu offers Debug File beside Run File for this file now. */
    public boolean debugFileOffered() {
        return runGutter.debugFileHandler != null && runGutter.debugFileShown.getAsBoolean();
    }

    /** Callback fired after any note change (for persistence + the Notes panel). */
    public void setOnNotesChanged(Runnable callback) {
        notes.setOnChanged(callback);
    }

    public void applyNotes(List<com.editora.config.PersonalNote> saved) {
        noteMarks.applyNotes(saved);
    }

    public void applyNotes(
            List<com.editora.config.PersonalNote> saved, java.util.function.Consumer<Boolean> movedThen) {
        noteMarks.applyNotes(saved, movedThen);
    }

    public NoteDraft captureNoteDraft() {
        return noteMarks.captureNoteDraft();
    }

    public NoteDraft captureLineNoteDraft(int requestedLine) {
        return noteMarks.captureLineNoteDraft(requestedLine);
    }

    /** Content-hash file identity of this buffer's file (for notes); {@code null} when the buffer is unsaved. */
    public com.editora.config.@Nullable FileIdentity fileIdentity() {
        return path == null ? null : com.editora.config.FileIdentity.of(path);
    }

    /** Enables/disables the Personal Notes feature for this buffer (gates the "Add Note" menu items). */
    public void setNotesEnabled(boolean on) {
        this.notesEnabled = on;
    }

    public void setNoteIndicatorsVisible(boolean on) {
        noteMarks.setNoteIndicatorsVisible(on);
    }

    public void setNoteMarkerClick(
            java.util.function.BiConsumer<EditorBuffer, com.editora.config.PersonalNote> handler) {
        noteMarks.setNoteMarkerClick(handler);
    }

    public boolean jumpToNextTodo() {
        return todoMarker.jumpToNextTodo();
    }

    public boolean jumpToPreviousTodo() {
        return todoMarker.jumpToPreviousTodo();
    }

    public void clearBookmarks() {
        gutter.clearBookmarks();
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
    static boolean minimapEffective(boolean visible, boolean largeFile, MarkdownViewMode mode) {
        return visible && !largeFile && mode != MarkdownViewMode.PREVIEW;
    }

    final WordWrap wrap = new WordWrap(this::setWordWrap);

    /** This buffer's word-wrap preference (per kind of file) and its long-line hold; see {@link WordWrap}. */
    public WordWrap wrap() {
        return wrap;
    }

    public void setWordWrap(boolean wrap) {
        views.setWordWrap(wrap);
    }

    public void setMinimapVisible(boolean visible) {
        views.setMinimapVisible(visible);
    }

    public void setWhitespaceVisible(boolean visible) {
        views.setWhitespaceVisible(visible);
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

    public void setPath(@Nullable Path path) {
        languageState.setPath(path);
    }

    public void setDisplayName(@Nullable String name) {
        languageState.setDisplayName(name);
    }

    /** The suggested name for an unsaved buffer, or null (used as the Save-As default). */
    public @Nullable String getDisplayName() {
        return displayName;
    }

    /** The current language name (see {@link LanguageRegistry}); drives fold strategy and the status bar. */
    public String getLanguage() {
        return language;
    }

    public void setLanguageOverride(String name) {
        languageState.setLanguageOverride(name);
    }

    public void addLanguageListener(Runnable listener) {
        languageState.addLanguageListener(listener);
    }

    void maybeApplyShebang() {
        languageState.maybeApplyShebang();
    }

    /** The {@code --source N} version if this is a Java compact-source shebang file, else {@code null}. */
    public @Nullable Integer getShebangJavaSource() {
        return shebangJavaSource;
    }

    public void convertLineEndings(boolean crlf) {
        fileFormat.convertLineEndings(crlf);
    }

    /** {@code "CRLF"} if {@code text} contains any Windows line ending, else {@code "LF"}. */
    public static String detectLineEnding(String text) {
        return text != null && text.contains("\r\n") ? "CRLF" : "LF";
    }

    /** The line ending a save writes: the EditorConfig override when set, else the file's own. */
    public String getLineEnding() {
        return eolOverride != null ? eolOverride : lineEnding;
    }

    /** The file's own line ending (or a manual conversion), whatever {@code .editorconfig}'s end_of_line says. */
    public String getBaseLineEnding() {
        return lineEnding;
    }

    public boolean isLineEndingForced() {
        return fileFormat.isLineEndingForced();
    }

    public void setTabSize(int tabSize) {
        fileFormat.setTabSize(tabSize);
    }

    public int getTabSize() {
        return tabSize;
    }

    // --- EditorConfig overrides ------------------------------------------------------------------

    public void setIndentOverride(@Nullable Boolean insertSpaces, @Nullable Integer size) {
        fileFormat.setIndentOverride(insertSpaces, size);
    }

    public void setEolOverride(@Nullable String eol) {
        fileFormat.setEolOverride(eol);
    }

    public void setRulerColumn(@Nullable Integer column) {
        rulerPlacement.setRulerColumn(column);
    }

    public void setDetectedCharset(String charset) {
        fileFormat.setDetectedCharset(charset);
    }

    public void setDetectedCharset(String charset, boolean assumed) {
        fileFormat.setDetectedCharset(charset, assumed);
    }

    public String getDetectedCharset() {
        return fileFormat.getDetectedCharset();
    }

    public void setCharsetOverride(@Nullable String charset) {
        fileFormat.setCharsetOverride(charset);
    }

    public boolean isCharsetAssumed() {
        return fileFormat.isCharsetAssumed();
    }

    public String getEffectiveCharset() {
        return fileFormat.getEffectiveCharset();
    }

    public @Nullable String getUserCharset() {
        return fileFormat.getUserCharset();
    }

    public void setUserCharset(@Nullable String charset) {
        fileFormat.setUserCharset(charset);
    }

    public void charsetSaved(String charset) {
        fileFormat.charsetSaved(charset);
    }

    public void setEditorConfigProps(com.editora.editorconfig.EditorConfigProperties props) {
        fileFormat.setEditorConfigProps(props);
    }

    public com.editora.editorconfig.EditorConfigProperties getEditorConfigProps() {
        return fileFormat.getEditorConfigProps();
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

    public void setLargeFile(boolean large) {
        modes.setLargeFile(large);
    }

    public void setHeavyFile(boolean heavy) {
        modes.setHeavyFile(heavy);
    }

    public boolean isHeavyFile() {
        return heavyFile;
    }

    /** The document's line (paragraph) count — used to decide the large-file tier at load. */
    public int lineCount() {
        return area.getParagraphs().size();
    }

    public int lastLineLength() {
        return document.lastLineLength();
    }

    public String lineText(int line) {
        return document.lineText(line);
    }

    public void setReadOnly(boolean readOnly) {
        modes.setReadOnly(readOnly);
    }

    public void setTruncatedLoad(boolean truncated) {
        modes.setTruncatedLoad(truncated);
    }

    /** True when this buffer holds only a slice of its file — see {@link #setTruncatedLoad}. Never save it. */
    public boolean isTruncatedLoad() {
        return truncatedLoad;
    }

    public void setViewMode(boolean viewMode) {
        modes.setViewMode(viewMode);
    }

    public void setViewMode(boolean viewMode, @Nullable String reason) {
        modes.setViewMode(viewMode, reason);
    }

    public boolean isViewMode() {
        return viewMode;
    }

    public void setLoading(boolean loading) {
        modes.setLoading(loading);
    }

    public boolean settlingForTest() {
        return languageState.settlingForTest();
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

    public void setOnEnableEditing(Runnable onEnableEditing) {
        banners.setOnEnableEditing(onEnableEditing);
    }

    public void setAdminEditAvailable(boolean available) {
        banners.setAdminEditAvailable(available);
    }

    /** Injects how the View mode banner asks whether a local file may be written (see {@code BufferBanners}). */
    public static void setWritableCheck(java.util.function.Predicate<java.nio.file.Path> check) {
        BufferBanners.writableCheck = check;
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

    /** As above, with a link after the message ({@code detailsLabel}) that runs {@code onDetails}. */
    public void showFileNotice(
            String message,
            String detailsLabel,
            Runnable onDetails,
            String actionLabel,
            String dismissLabel,
            Runnable onAction) {
        banners.showFileNotice(message, detailsLabel, onDetails, actionLabel, dismissLabel, onAction);
    }

    /** TEST SEAM: fires the file notice's action, or ({@code details}) its details link. */
    public void fireFileNoticeForTest(boolean details) {
        banners.fireFileNoticeForTest(details);
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

    /** How Tab learns whether a language's snippets are loaded, and waits for them (see BufferSnippets). */
    public void setSnippetLoading(SnippetLoading loading) {
        snippetKeys.setSnippetLoading(loading);
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

    public void typeString(String text) {
        document.typeString(text);
    }

    public boolean ownsKeyTarget(javafx.event.EventTarget target) {
        return document.ownsKeyTarget(target);
    }

    public void pressKey(String macroKeyToken) {
        typingAids.pressKey(macroKeyToken);
    }

    public void typeChar(char c) {
        typingAids.typeChar(c);
    }

    void scheduleBraceMatch() {
        braces.scheduleBraceMatch();
    }

    /**
     * The most the FX thread reads each way from the caret for a bracket's partner, and only within the lines on
     * screen ({@link BufferBraces#nearBraceWindow}): painting a pair the user sees is reading the screen, not searching the
     * document. A partner further off is looked for off the FX thread (PERF-01, Pauta 6.2.0).
     */
    static final int NEAR_BRACE_SCAN = 16_384;

    /** {@link com.editora.perf.BlockingSites} name of the far brace scan, made off the FX thread. */
    static final String BRACE_SCAN_SITE = "EditorBuffer.braceMatch";

    void clearBraceMatch() {
        braces.clearBraceMatch();
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
     * RichTextFX's {@code getText()}; repeated consumers of the same version receive the same String — also the
     * one a worker built from {@link #documentText()} for this version, if it got there first.
     */
    String documentTextSnapshot() {
        return documentText().text();
    }

    /**
     * The current version of the document, taken in O(1) on the FX thread, whose text a consumer that works off
     * the FX thread reads on its worker ({@link DocumentText#text()}, built once per version and shared with
     * {@link #documentTextSnapshot()}): a typing pause no longer copies the document on the FX thread (perf
     * review 2026-11-23 E2).
     */
    public DocumentText documentText() {
        return documentSnapshots.get(docVersion, () -> DocumentText.of(area)::text);
    }

    /** Number of whole-document strings this buffer has materialized through the shared cache (tests). */
    long documentSnapshotMaterializations() {
        return documentSnapshots.materializations();
    }

    /** As {@link #documentSnapshotMaterializations()}, counting only the strings built on the FX thread (tests). */
    long documentSnapshotFxMaterializations() {
        return documentSnapshots.fxMaterializations();
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

    public java.util.function.Consumer<java.util.List<LspTextEdit>> trackCompletionAdditionalEdits() {
        return lsp.trackCompletionAdditionalEdits();
    }

    public void applyCompletionAdditionalEdits(java.util.List<LspTextEdit> edits) {
        lsp.applyCompletionAdditionalEdits(edits);
    }

    public void applyLspEdits(java.util.List<LspTextEdit> edits) {
        lsp.applyLspEdits(edits);
    }

    public boolean applyLspEditsAtomically(java.util.List<LspTextEdit> edits) {
        return lsp.applyLspEditsAtomically(edits);
    }

    public boolean canPlaceLspEdits(java.util.List<LspTextEdit> edits) {
        return lsp.canPlaceLspEdits(edits);
    }

    static void toggleStyleClass(Node node, String styleClass, boolean on) {
        if (on) {
            if (!node.getStyleClass().contains(styleClass)) {
                node.getStyleClass().add(styleClass);
            }
        } else {
            node.getStyleClass().remove(styleClass);
        }
    }

    public boolean isReadOnly() {
        return hugeFile;
    }

    public boolean isLargeFile() {
        return largeFile;
    }

    public void setContent(String content) {
        document.setContent(content);
    }

    public void setInitialContent(String content) {
        document.setInitialContent(content);
    }

    public void setInitialContent(String content, boolean segmentLongLines) {
        document.setInitialContent(content, segmentLongLines);
    }

    /** {@link com.editora.perf.BlockingSites} name of text given to a buffer, made ready off the FX thread. */
    public static final String PREPARE_TEXT_SITE = "EditorBuffer.prepareText";

    public void setContentLater(String content, Runnable then) {
        document.setContentLater(content, then);
    }

    public void setInitialContentLater(String content, boolean segmentLongLines, Runnable then) {
        document.setInitialContentLater(content, segmentLongLines, then);
    }

    public void whenTextInstalled(Runnable action) {
        document.whenTextInstalled(action);
    }

    public InitialDocument prepareInitialContent(String content, boolean segmentLongLines) {
        return document.prepareInitialContent(content, segmentLongLines);
    }

    public void setInitialContent(InitialDocument loaded) {
        document.setInitialContent(loaded);
    }

    public void reloadContent(InitialDocument loaded) {
        document.reloadContent(loaded);
    }

    public void reloadContent(InitialDocument loaded, @Nullable PreparedEdit prepared) {
        document.reloadContent(loaded, prepared);
    }

    public void goToStart() {
        document.goToStart();
    }

    public void moveLine(int delta, SelectionPolicy policy) {
        document.moveLine(delta, policy);
    }

    void resetGoalColumn() {
        document.resetGoalColumn();
    }

    public String getContent() {
        return document.getContent();
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

    void forgetHistoryAtNarrowBoundary(boolean hadHistory) {
        narrowing.forgetHistoryAtNarrowBoundary(hadHistory);
    }

    public void replaceWholeDocument(String text) {
        document.replaceWholeDocument(text);
    }

    public void replaceVisibleText(CodeArea view, String text) {
        document.replaceVisibleText(view, text);
    }

    /** {@link com.editora.perf.BlockingSites} name of a whole-document rewrite's diff, worked out off the FX thread. */
    public static final String WHOLE_EDIT_SITE = "EditorBuffer.wholeDocumentEdit";

    /**
     * A whole-document rewrite worked out off the FX thread: the span of the accessible text at document version
     * {@code version} that differs from what it becomes ({@code edit} null when they are equal).
     */
    public record PreparedEdit(long version, @Nullable WholeDocumentEdit edit) {

        /** Off the FX thread: the edit from {@code current} (the accessible text at {@code version}) to {@code next}. */
        public static PreparedEdit between(long version, String current, String next) {
            return new PreparedEdit(version, WholeDocumentEdit.between(current, LineEndings.toLf(next)));
        }

        /** The accessible text at {@code version}: what a worker prepares an edit against. */
        public record Base(long version, String text) {

            /** Off the FX thread: the edit that makes this text {@code next}. */
            public PreparedEdit to(String next) {
                return between(version, text, next);
            }
        }
    }

    public PreparedEdit.Base baseForWholeEdit() {
        return document.baseForWholeEdit();
    }

    public boolean applyPreparedWholeEdit(PreparedEdit prepared) {
        return document.applyPreparedWholeEdit(prepared);
    }

    public boolean applyPreparedVisibleEdit(CodeArea view, PreparedEdit prepared) {
        return document.applyPreparedVisibleEdit(view, prepared);
    }

    public boolean applyPreparedWholeText(PreparedEdit prepared, String wholeText) {
        return document.applyPreparedWholeText(prepared, wholeText);
    }

    public void replaceWholeDocumentLater(String text, java.util.function.Consumer<Boolean> done) {
        document.replaceWholeDocumentLater(text, done);
    }

    public void replaceVisibleTextLater(CodeArea view, String text, java.util.function.Consumer<Boolean> done) {
        document.replaceVisibleTextLater(view, text, done);
    }

    public void preventUndoMerge() {
        undo.preventUndoMerge();
    }

    public Runnable beginUndoSpan() {
        return undo.beginUndoSpan();
    }

    /** The accessible portion — the narrowed region, or the whole document when not narrowed. */
    public String getVisibleContent() {
        return documentTextSnapshot();
    }

    public BooleanProperty dirtyProperty() {
        return dirty;
    }

    public boolean isDirty() {
        return dirtyState.isDirty();
    }

    void dirtyAfterEdit(boolean sameLength) {
        dirtyState.dirtyAfterEdit(sameLength);
    }

    void resolveDirty() {
        dirtyState.resolveDirty();
    }

    void sealBaselineIfDirty() {
        dirtyState.sealBaselineIfDirty();
    }

    /** {@link BlockingSites} name of the dirty check's hashes, made off the FX thread. */
    static final String DIRTY_HASH_SITE = "EditorBuffer.dirtyHash";

    /** The version and baseline a hash is under way for, so a second ask for them starts none. */
    long hashingVersion = -1;

    boolean baselineHoldsText() {
        return dirtyState.baselineHoldsText();
    }

    public void markClean() {
        dirtyState.markClean();
    }

    public void markUnsaved() {
        dirtyState.markUnsaved();
    }

    public void acknowledgeSavedContent(String savedContent, String savedLineEnding) {
        dirtyState.acknowledgeSavedContent(savedContent, savedLineEnding);
    }

    public void acknowledgeSavedContent(
            long savedVersion, int savedLength, String savedTextFingerprint, String savedLineEnding) {
        dirtyState.acknowledgeSavedContent(savedVersion, savedLength, savedTextFingerprint, savedLineEnding);
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
