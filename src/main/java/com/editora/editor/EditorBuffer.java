package com.editora.editor;

import static com.editora.i18n.Messages.tr;

import com.editora.completion.Completion;
import com.editora.completion.CompletionEngine;
import com.editora.completion.CompletionProvider;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.reactfx.Subscription;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import com.editora.snippet.ParsedSnippet;
import com.editora.snippet.Snippet;
import com.editora.snippet.SnippetParser;
import com.editora.snippet.SnippetSession;
import com.editora.snippet.VariableResolver;
import org.fxmisc.richtext.util.UndoUtils;
import org.fxmisc.undo.UndoManager;
import org.fxmisc.undo.UndoManagerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.application.Platform;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.text.Font;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

/** A single open document: a RichTextFX {@link CodeArea} plus its backing file, language, and dirty state. */
public class EditorBuffer implements TabContent {

    private final CodeArea area = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(area);
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);
    /** The last saved/loaded content; the buffer is dirty only when the text differs from this. */
    private String cleanText = "";

    /** Orientation of an optional second, synced view of this document. */
    public enum Split { NONE, SIDE_BY_SIDE, STACKED }

    /** Wraps the scroll pane so we can overlay the column-80 ruler line and dock the minimap. */
    private final AnchorPane root = new AnchorPane();
    /** Tab content: shows either {@link #root} alone or a SplitPane of [root, secondary view]. */
    private final StackPane viewHost = new StackPane(root);
    /** Outermost tab content: a "View Mode" banner (top, read-only only) above {@link #viewHost}. */
    private final javafx.scene.layout.BorderPane outer = new javafx.scene.layout.BorderPane(viewHost);
    /** The MS-Word-style read-only banner (lazy); its "Enable Editing" runs {@link #onEnableEditing}. */
    private HBox viewModeBar;
    private Button enableEditingButton;
    private Label viewModeNote;
    /** Invoked by the banner's "Enable Editing" button; the controller persists + refreshes indicators. */
    private Runnable onEnableEditing = () -> setViewMode(false);
    /** A second editable view sharing this document (created lazily on first split). */
    private CodeArea area2;
    private VirtualizedScrollPane<CodeArea> scrollPane2;
    /** The secondary view's container (scroll pane + its own minimap), mounted in the SplitPane. */
    private AnchorPane root2;
    private Minimap minimap2;
    private Split split = Split.NONE;

    /** IntelliJ-style Markdown preview modes (only meaningful for Markdown files). */
    public enum MarkdownViewMode { EDITOR, SPLIT, PREVIEW }
    private MarkdownViewMode markdownViewMode = MarkdownViewMode.EDITOR;
    /** Rendered-preview pane (lazy); its content is rebuilt by {@link MarkdownRenderer}. */
    private ScrollPane previewPane;
    /** Wraps the preview so the floating control can overlay it in PREVIEW mode (no code pane then). */
    private StackPane previewHost;
    /** The preview's −/+ zoom + light/dark control (overlaid top-left of the preview when previewing). */
    private HBox zoomControl;
    /** The preview light/dark toggle button (a sun/moon glyph); reflects the current effective theme. */
    private Button previewThemeButton;
    /** Markdown preview color theme: "" (follow app), "light", or "dark" — set by the controller. */
    private String previewThemeMode = "";
    /** Whether the app/editor theme is dark (used to resolve "follow app" + the toggle glyph). */
    private boolean previewAppDark;
    /** Runs the controller's global preview-theme toggle (injected, like the snippet/completion providers). */
    private Runnable previewThemeToggle = () -> { };
    /** Preview text zoom factor (1.0 = 100%); scales the rendered preview's base font size. */
    private double previewFontScale = 1.0;
    /** Base preview font size in px (matches {@code .markdown-preview-wrap} in app.css); headings use em. */
    private static final double BASE_PREVIEW_FONT = 15;
    /** The floating Editor/Split/Preview control overlaid top-right (injected for Markdown buffers). */
    private Node viewModeControl;
    /** Active debounced subscription driving live preview re-render (null when not previewing). */
    private Subscription previewSub;
    /** Bumped per preview render request; background results discard if stale. */
    private long previewGen;
    /** Off-thread Markdown parser so a large document never blocks the UI. */
    private final ExecutorService previewExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "markdown-preview");
        t.setDaemon(true);
        return t;
    });
    private Runnable onViewModeChanged = () -> { };
    /** Files at/above this size skip syntax highlighting and the minimap to stay responsive. */
    public static final long LARGE_FILE_BYTES = 5L * 1024 * 1024;
    /** Files at/above this size are opened read-only (and truncated by the loader). */
    public static final long HUGE_FILE_BYTES = 50L * 1024 * 1024;
    /** Whether the minimap is shown; applied to every split pane's minimap. */
    private boolean minimapVisible = true;
    /** Large-file mode: syntax highlighting and the minimap are disabled regardless of settings. */
    private boolean largeFile;
    /** Huge-file mode: implies large-file mode plus read-only (no undo, not editable). */
    private boolean hugeFile;
    /** User "View mode": non-editable but keeps all normal editor features (separate from huge-file). */
    private boolean viewMode;
    /** The most recently focused view (primary or secondary); drives "active area" for commands. */
    private CodeArea focusedArea = area;
    /** Active snippet expansion (Tab cycles its fields), or null when none is in progress. */
    private SnippetSession snippetSession;
    /** Resolves (language, prefix) → snippet for Tab-expand; injected by the controller (default: none). */
    private java.util.function.BiFunction<String, String, Snippet> snippetProvider = (lang, prefix) -> null;
    /** Resolves completions for the typed prefix; injected by the controller (default: none). */
    private CompletionProvider completionProvider = (s, d, p, prose) -> List.of();
    /** When false the whole autocomplete feature is inert (master Settings toggle). */
    private boolean autocompleteEnabled = true;
    /** Per-source autocomplete toggles (gated by {@link #autocompleteEnabled}). */
    private boolean autocompleteProse = true;
    private boolean autocompleteSnippets = true;
    private boolean autocompleteMermaid = true;
    /** The caret-anchored completion dropdown (lazily created). */
    private CompletionPopup completionPopup;
    /** Injected async LSP completion source (code buffers); generation guard for stale async results. */
    private java.util.function.BiConsumer<int[],
            java.util.function.Consumer<java.util.List<Completion>>> lspCompletionProvider;
    private long completionGen;
    /** The view the completion popup is currently driven by (for click-accept routing). */
    private CodeArea completionArea;
    /** Suppresses one auto-trigger pass right after we programmatically accept a completion. */
    private boolean suppressCompletion;
    /** Inline "ghost text" suggestion (prose buffers): a single muted suffix drawn after the caret. */
    private Label ghostLabel;
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
    private final MermaidLintOverlay lintOverlay = new MermaidLintOverlay(area);
    private final LspDiagnosticOverlay lspOverlay = new LspDiagnosticOverlay(area);
    /** Severity stripe over the editor scrollbar (shown whenever LSP is active), so diagnostics stay locatable. */
    private final DiagnosticStripe diagnosticStripe = new DiagnosticStripe(area);
    /** Files above this size are never scanned for compact-source detection (keeps it off the hot path). */
    private static final int COMPACT_SCAN_LIMIT = 256 * 1024;
    /** Whether the Run affordance is enabled at all (gated by the LSP feature setting). */
    private boolean runFeatureEnabled = true;
    /** Whether shell scripts may show the Run glyph (gated by the Bash LSP server toggle, under the LSP
     *  feature) — separate from Java/Python, which only need {@link #runFeatureEnabled}. */
    private boolean shellRunEnabled;
    /** Whether this file is runnable (a Java 25 compact source file, a Python script, or — when the Bash
     *  LSP is enabled — a shell script) — drives the gutter Run glyph + the Run tool window. */
    private boolean runnable;
    /** 0-based line the gutter Run glyph sits on (a compact file's {@code main}, a Python {@code __main__}
     *  guard, else the first line), or -1 when not runnable. */
    private int runLine = -1;
    /** Fired (FX thread) when the runnable status flips, so the controller refreshes the Run button. */
    private Runnable onRunnableChanged = () -> { };
    private final SearchHighlightOverlay searchOverlay = new SearchHighlightOverlay(area);
    private final AceJumpOverlay aceJump = new AceJumpOverlay(area);
    /** Async maid validator (text, callback) injected by the controller; null = no linting. */
    private java.util.function.BiConsumer<String,
            java.util.function.Consumer<java.util.List<com.editora.mermaid.MaidOutput.Diagnostic>>> mermaidValidator;
    private boolean mermaidLintEnabled;
    private javafx.scene.control.Tooltip lintTooltip;
    /** LSP: overlay active (diagnostics + hover), the debounced didChange sink, and the hover tooltip. */
    private boolean lspActive;
    private java.util.function.Consumer<String> lspChangeListener;
    private javafx.scene.control.Tooltip lspTooltip;
    private final FoldManager folds = new FoldManager(area);
    private final BookmarkManager bookmarks = new BookmarkManager(area);
    /** Handles a gutter click on a line: the controller adds, or confirms a removal. Default: toggle. */
    private java.util.function.BiConsumer<EditorBuffer, Integer> gutterBookmarkClick =
            (buffer, line) -> buffer.toggleBookmark(line);
    /** Personal Notes for this buffer (gutter marker + highlight + hover). */
    private final NoteManager notes = new NoteManager(area);
    private final NoteHighlightOverlay noteOverlay = new NoteHighlightOverlay(area);
    /** When false, the Personal Notes feature is disabled for this buffer (no "Add Note" menu items). */
    private boolean notesEnabled = false;
    /** When false, note gutter markers + highlight are hidden (the {@code showNoteIndicators} setting). */
    private boolean noteIndicators = true;
    /** Reused hover tooltip + the id of the note it's currently showing (so we only update on change). */
    private final javafx.scene.control.Tooltip noteTip = new javafx.scene.control.Tooltip();
    private java.util.UUID hoverNoteId;
    /** Handles a gutter note-marker click (the controller opens/edits that line's note). Default: no-op. */
    private java.util.function.BiConsumer<EditorBuffer, Integer> gutterNoteClick = (buffer, line) -> { };
    /** Invoked by the "Add Personal Note" context-menu item (the controller prompts + creates). */
    private java.util.function.Consumer<EditorBuffer> addNoteHandler = b -> { };
    /** "Run File" context-menu handler (compact source files); null hides the item. */
    private Runnable runHandler;
    /** LSP navigation actions (controller-supplied); shown in the context menu only while LSP is active. */
    private Runnable lspGotoDefinitionAction = () -> { };
    private Runnable lspFindReferencesAction = () -> { };
    private Runnable lspHoverAction = () -> { };
    /** Chars of context captured before/after a note's selection (for re-anchoring). */
    private static final int CONTEXT_CHARS = 40;
    /** Git gutter change bars: 0-based line → CSS class ({@code git-added}/{@code git-modified}/
     *  {@code git-deleted}); {@code null} when this buffer isn't under Git change tracking. */
    private java.util.Map<Integer, String> changeBars;

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
    // --- Spell checking (Lucene Hunspell via SpellCheckOverlay); off until enabled by the controller. ---
    private SpellChecker spellChecker;
    private boolean spellCheckOn;
    private String spellLanguage = "en_US";
    private java.util.Set<String> spellUserWords = new java.util.HashSet<>();
    private java.util.function.Consumer<String> onAddToDictionary = w -> { };
    /** Off-thread tokenizer so highlighting a large document never blocks the UI (see applyHighlighting). */
    private final ExecutorService highlightExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "editor-highlighter");
        t.setDaemon(true);
        return t;
    });
    /** Bumped on every highlight request (FX thread only); lets background results discard if stale. */
    private long highlightGen;
    /** Per-line grammar end-states from the last tokenization (FX-thread confined), so an edit can
     *  re-highlight only from the changed line forward instead of the whole document. */
    private final java.util.ArrayList<org.eclipse.tm4e.core.grammar.IStateStack> lineStates =
            new java.util.ArrayList<>();
    /** Earliest line changed since the last highlight (0 = re-highlight the whole document). */
    private int dirtyFromLine;
    /** Named definitions from the last tokenization (FX-thread confined); drives the Structure view. */
    private List<TextMateHighlighter.Symbol> symbols = List.of();
    /** Notified (on the FX thread) after {@link #symbols} is refreshed. */
    private Runnable onSymbolsChanged = () -> { };
    private String fontFamily = "monospace";
    private int fontSize = 14;
    /** Current-line highlight fill; varies per editor theme (see {@link #setLineHighlightColor}). */
    private Color lineHighlightColor = Color.web("#dfe7f0");
    /** Minimap block + viewport colors; vary per editor theme (see {@link #setMinimapColors}). */
    private Color minimapText = Color.web("#9aa5b1");
    private Color minimapViewport = Color.web("#0969da", 0.14);
    /** Visual tab width (columns); applied to the minimap and persisted via Settings. */
    private int tabSize = 4;
    /** Whether the user enabled the 80-column ruler. The line is only actually shown when a visible
     *  line reaches column 80 (see {@link #measureAndPlaceRuler}). */
    private boolean rulerVisible;
    private boolean lineNumbersVisible = true;
    /** Coalesces ruler re-measurement onto a later pulse (see {@link #scheduleRulerMeasure}). */
    private boolean rulerMeasurePending;

    /** Max undo entries kept per view; caps undo memory (RichTextFX defaults to unlimited). */
    private static final int UNDO_HISTORY = 300;

    public EditorBuffer() {
        refreshGutter();
        // Gutter click: route to the injectable handler (the controller adds, or confirms a removal);
        // defaults to a plain toggle so the editor works standalone (and in tests).
        folds.setBookmarkHooks(bookmarks::isBookmarked, line -> gutterBookmarkClick.accept(this, line));
        folds.setNoteHooks(line -> noteIndicators && notes.isNoted(line),
                line -> gutterNoteClick.accept(this, line));
        notes.setOnLinesRepaint(lines -> Platform.runLater(() -> lines.forEach(this::refreshGutterLine)));
        // Git change bars: the slot is reserved only while tracking is on (changeBars != null).
        folds.setChangeHook(() -> changeBars != null,
                line -> changeBars == null ? null : changeBars.get(line));
        // Gutter Run glyph: reserved only for a runnable file, on its entry line.
        folds.setRunHooks(() -> runnable, line -> runnable && line == runLine,
                () -> {
                    if (runHandler != null) {
                        runHandler.run();
                    }
                });
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
        area.setLineHighlighterFill(lineHighlightColor);
        // Track the earliest changed line immediately (the debounced stream below drops intermediate
        // emissions, so the dirty start must be accumulated here), then re-highlight after a pause.
        area.multiPlainChanges().subscribe(changes -> {
            for (var change : changes) {
                int line = area.offsetToPosition(change.getPosition(),
                        org.fxmisc.richtext.model.TwoDimensional.Bias.Backward).getMajor();
                dirtyFromLine = Math.min(dirtyFromLine, line);
            }
        });
        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(150))
                .subscribe(ignore -> {
                    applyHighlighting();
                    recomputeRun(); // re-evaluate the Run glyph when a top-level main / __main__ appears/leaves
                });
        // Live Mermaid linting: debounced maid run for .mmd buffers (only while enabled + maid detected).
        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(450))
                .subscribe(ignore -> scheduleMermaidLint());
        // LSP document sync: debounced didChange notification (only while the buffer is LSP-managed).
        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(300))
                .subscribe(ignore -> {
                    if (lspActive && lspChangeListener != null) {
                        lspChangeListener.accept(area.getText());
                    }
                });
        // Dirty only when the content differs from the last saved/loaded text, so reverting an edit
        // (undo or manual) clears the marker. The length check short-circuits the O(n) compare —
        // it runs only when the length matches the saved length (i.e. near the original state).
        area.textProperty().addListener((obs, old, now) ->
                dirty.set(now.length() != cleanText.length() || !now.equals(cleanText)));
        area.caretPositionProperty().addListener((obs, old, now) -> resetGoalColumn());
        area.caretPositionProperty().addListener((obs, old, now) -> scheduleBraceMatch());
        area.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                focusedArea = area;
            }
        });
        installContextMenu();
        installOverlays();
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
        root.getChildren().addAll(scrollPane, noteOverlay, searchOverlay, whitespace, spellOverlay, lintOverlay, lspOverlay, aceJump, minimap, diagnosticStripe, columnRuler);
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
        spellOverlay.setChecker(spellChecker);
        spellOverlay.setProseMode(isProse());
        AnchorPane.setTopAnchor(lintOverlay, 0d);
        AnchorPane.setBottomAnchor(lintOverlay, 0d);
        AnchorPane.setLeftAnchor(lintOverlay, 0d);
        AnchorPane.setRightAnchor(lintOverlay, Minimap.WIDTH);
        installLintHover(area);
        AnchorPane.setTopAnchor(lspOverlay, 0d);
        AnchorPane.setBottomAnchor(lspOverlay, 0d);
        AnchorPane.setLeftAnchor(lspOverlay, 0d);
        AnchorPane.setRightAnchor(lspOverlay, Minimap.WIDTH);
        installLspHover(area);
        AnchorPane.setTopAnchor(searchOverlay, 0d);
        AnchorPane.setBottomAnchor(searchOverlay, 0d);
        AnchorPane.setLeftAnchor(searchOverlay, 0d);
        AnchorPane.setRightAnchor(searchOverlay, Minimap.WIDTH);
        AnchorPane.setTopAnchor(aceJump, 0d);
        AnchorPane.setBottomAnchor(aceJump, 0d);
        AnchorPane.setLeftAnchor(aceJump, 0d);
        AnchorPane.setRightAnchor(aceJump, Minimap.WIDTH);
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
    private record SpellHit(String word, int start, int end) {
    }

    private final ContextMenu contextMenu = new ContextMenu();

    private void installContextMenu() {
        contextMenu.getStyleClass().add("editor-context-menu");
        area.setOnContextMenuRequested(e -> {
            List<MenuItem> items = new java.util.ArrayList<>();
            // Run a runnable file (compact Java source / Python script).
            if (runnable && runHandler != null) {
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
            items.addAll(standardMenuItems());
            if (path != null && notesEnabled) {
                items.add(new SeparatorMenuItem());
                boolean hasSelection = area.getSelection().getLength() > 0;
                MenuItem addNote = new MenuItem(tr(hasSelection ? "editmenu.addNoteSelection" : "editmenu.addNote"));
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

    /** The document offset under a context-menu click (for caret-positioning LSP nav); caret if it misses. */
    private int clickOffsetAt(double x, double y) {
        try {
            return area.hit(x, y).getInsertionIndex();
        } catch (RuntimeException ex) {
            return area.getCaretPosition();
        }
    }

    /** Go to Definition / Find References / Show Documentation — each moves the caret to {@code offset}
     *  first so it targets the right-clicked symbol, then runs the controller-supplied action. */
    private List<MenuItem> lspMenuItems(int offset) {
        MenuItem def = new MenuItem(tr("command.lsp.gotoDefinition"));
        def.setOnAction(e -> { area.moveTo(offset); lspGotoDefinitionAction.run(); });
        MenuItem refs = new MenuItem(tr("command.lsp.findReferences"));
        refs.setOnAction(e -> { area.moveTo(offset); lspFindReferencesAction.run(); });
        MenuItem hover = new MenuItem(tr("command.lsp.hover"));
        hover.setOnAction(e -> { area.moveTo(offset); lspHoverAction.run(); });
        return List.of(def, refs, hover);
    }

    /** The Cut/Copy/Paste/Undo/Redo/Select All items, with state for the current selection/clipboard. */
    private List<MenuItem> standardMenuItems() {
        boolean hasSelection = area.getSelection().getLength() > 0;
        boolean hasClipboardText = Clipboard.getSystemClipboard().hasString();
        MenuItem cut = new MenuItem(tr("editmenu.cut"));
        cut.setOnAction(e -> area.cut());
        cut.setDisable(!hasSelection);
        MenuItem copy = new MenuItem(tr("editmenu.copy"));
        copy.setOnAction(e -> area.copy());
        copy.setDisable(!hasSelection);
        MenuItem paste = new MenuItem(tr("editmenu.paste"));
        paste.setOnAction(e -> area.paste());
        paste.setDisable(!hasClipboardText);
        MenuItem undo = new MenuItem(tr("editmenu.undo"));
        undo.setOnAction(e -> area.undo());
        undo.setDisable(!area.isUndoAvailable());
        MenuItem redo = new MenuItem(tr("editmenu.redo"));
        redo.setOnAction(e -> area.redo());
        redo.setDisable(!area.isRedoAvailable());
        MenuItem selectAll = new MenuItem(tr("editmenu.selectAll"));
        selectAll.setOnAction(e -> area.selectAll());
        return List.of(cut, copy, paste, new SeparatorMenuItem(), undo, redo,
                new SeparatorMenuItem(), selectAll);
    }

    /** Suggestion items (replace the word) plus "Add to Dictionary"/"Ignore" for a misspelled word. */
    private List<MenuItem> spellMenuItems(SpellHit hit) {
        List<MenuItem> items = new java.util.ArrayList<>();
        List<String> suggestions = spellChecker.suggest(hit.word());
        if (suggestions.isEmpty()) {
            MenuItem none = new MenuItem(tr("editmenu.noSuggestions"));
            none.setDisable(true);
            items.add(none);
        } else {
            for (String s : suggestions) {
                MenuItem mi = new MenuItem(s);
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
        add.setOnAction(e -> addToDictionary(hit.word()));
        MenuItem ignore = new MenuItem(tr("editmenu.ignore"));
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
                if (!spellEligible(absStart)) {
                    return null;
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
        return isProse() ? !style.contains("code") : style.contains("comment") || style.contains("string");
    }

    private void addToDictionary(String word) {
        if (word == null || word.isBlank()) {
            return;
        }
        String lower = word.toLowerCase(java.util.Locale.ROOT);
        spellUserWords.add(lower);
        onAddToDictionary.accept(lower);
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

    /**
     * Highlights all find matches ({@code [start,end)} offset pairs) behind the text, emphasizing the
     * match at {@code activeIndex}. No-op in large-file mode (the find bar still selects the current
     * match). Pass an empty list (or call {@link #clearSearchMatches}) to remove the highlight.
     */
    public void setSearchMatches(java.util.List<int[]> matches, int activeIndex) {
        if (largeFile) {
            return;
        }
        searchOverlay.setMatches(matches, activeIndex);
    }

    /** Clears the find-match highlight overlay. */
    public void clearSearchMatches() {
        searchOverlay.setMatches(java.util.List.of(), -1);
    }

    /** Starts AceJump: the next typed character labels its visible occurrences to jump the caret. */
    public void startAceJump() {
        aceJump.start();
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

    /** Whether this buffer supports the 3-mode preview: Markdown always, Mermaid only while the feature
     *  is enabled (so a .mmd file is plain text with no preview affordance when Mermaid is off). */
    public boolean hasPreview() {
        return isMarkdown() || (isDiagram() && MermaidImages.isEnabled());
    }

    // --- Live Mermaid linting (maid) ----------------------------------------------------------------

    /** Injects the async maid validator: {@code accept(text, diagnostics->…)}; null disables linting. */
    public void setMermaidValidator(java.util.function.BiConsumer<String,
            java.util.function.Consumer<java.util.List<com.editora.mermaid.MaidOutput.Diagnostic>>> validator) {
        this.mermaidValidator = validator;
    }

    /** Turns live linting on/off for this buffer (controller gates on mermaid enabled + maid detected). */
    public void setMermaidLintEnabled(boolean on) {
        this.mermaidLintEnabled = on && isDiagram();
        lintOverlay.setActive(this.mermaidLintEnabled);
        if (this.mermaidLintEnabled) {
            scheduleMermaidLint();
        }
    }

    private void scheduleMermaidLint() {
        if (!mermaidLintEnabled || !isDiagram() || hugeFile || mermaidValidator == null) {
            return;
        }
        mermaidValidator.accept(area.getText(), lintOverlay::setDiagnostics);
    }

    /** Shows the maid message(s) in a tooltip when hovering a squiggled span (overlay is mouse-transparent). */
    private void installLintHover(CodeArea a) {
        a.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (!mermaidLintEnabled || lintOverlay.diagnostics().isEmpty()) {
                if (lintTooltip != null) {
                    lintTooltip.hide();
                }
                return;
            }
            try {
                var hit = a.hit(e.getX(), e.getY());
                var pos = a.offsetToPosition(hit.getInsertionIndex(),
                        org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
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
                if (lintTooltip == null) {
                    lintTooltip = new javafx.scene.control.Tooltip();
                    lintTooltip.getStyleClass().add("mermaid-lint-tooltip");
                    lintTooltip.setWrapText(true);
                    lintTooltip.setMaxWidth(420);
                }
                lintTooltip.setText(sb.toString());
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

    // --- LSP (Language Server Protocol) integration ---------------------------------------------

    /** Language ids that have a language server (Java, the TypeScript server's JS/TS/JSX/TSX, Python,
     *  XML, JSON, and shell). Hardcoded here so {@code editor} need not depend on the {@code lsp} package
     *  (kept in sync with {@code LspServerRegistry}). */
    private static final java.util.Set<String> LSP_LANGUAGES = java.util.Set.of(
            "java", "javascript", "javascriptreact", "typescript", "typescriptreact", "python",
            "xml", "json", "shell");

    /** Whether this buffer's language has a language server. */
    public boolean isLspLanguage() {
        return LSP_LANGUAGES.contains(language);
    }

    /** Injects the debounced didChange sink (text → controller → server); null disables change notices. */
    public void setLspChangeListener(java.util.function.Consumer<String> listener) {
        this.lspChangeListener = listener;
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
        this.onRunnableChanged = callback == null ? () -> { } : callback;
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

    /** Recomputes runnable status + the gutter entry line; refreshes the gutter and fires the callback. */
    private void recomputeRun() {
        String name = path != null ? path.getFileName().toString() : displayName;
        String text = area.getText();
        boolean eligible = runFeatureEnabled && !largeFile && area.getLength() <= COMPACT_SCAN_LIMIT;
        boolean nowRunnable;
        int nowLine;
        if (eligible && "python".equals(language)) {
            nowRunnable = true;
            nowLine = pythonRunLine(text); // the __main__ guard, else the first line
        } else if (eligible && shellRunEnabled && "shell".equals(language)) {
            nowRunnable = true;
            nowLine = 0; // run the whole script from the top (the shebang line, if any)
        } else if (eligible && CompactSource.isLaunchable(name, text)) {
            nowRunnable = true;
            nowLine = CompactSource.mainLine(text);
        } else {
            nowRunnable = false;
            nowLine = -1;
        }
        boolean changed = nowRunnable != runnable;
        int oldLine = runLine;
        runnable = nowRunnable;
        runLine = nowLine;
        if (changed) {
            onRunnableChanged.run();
            refreshGutter(); // the Run slot appeared/disappeared on every row — rebuild the factory
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

    private static final java.util.regex.Pattern PYTHON_MAIN_GUARD = java.util.regex.Pattern.compile(
            "^\\s*if\\s+__name__\\s*==\\s*['\"]__main__['\"]\\s*:");

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
        this.lspActive = on && isLspLanguage() && !hugeFile;
        lspOverlay.setActive(this.lspActive);
        // The minimap stripes only draw while LSP is active for this file, regardless of any stale list.
        minimap.setDiagnosticsEnabled(this.lspActive);
        if (!this.lspActive) {
            lspOverlay.setDiagnostics(java.util.List.of());
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
        lspOverlay.setDiagnostics(diagnostics);
        minimap.setDiagnostics(diagnostics);
        diagnosticStripe.setDiagnostics(diagnostics);
    }

    /** The scrollbar stripe is shown whenever LSP is active for this buffer (minimap on or off). It sits
     *  over the editor's vertical scrollbar: at the far-right edge when the minimap is hidden, else just
     *  inside the minimap (over the editor scrollbar, which ends at the minimap's left edge). */
    private void updateDiagnosticStripe() {
        boolean minimapShown = minimapVisible && !largeFile;
        AnchorPane.setRightAnchor(diagnosticStripe, minimapShown ? Minimap.WIDTH : 0d);
        diagnosticStripe.setActive(lspActive);
    }

    /** Injects the LSP navigation actions surfaced in the right-click menu while {@link #isLspActive()}. */
    public void setLspNavActions(Runnable gotoDefinition, Runnable findReferences, Runnable hover) {
        this.lspGotoDefinitionAction = gotoDefinition == null ? () -> { } : gotoDefinition;
        this.lspFindReferencesAction = findReferences == null ? () -> { } : findReferences;
        this.lspHoverAction = hover == null ? () -> { } : hover;
    }

    /** Current document text (for an initial didOpen). */
    public String text() {
        return area.getText();
    }

    /** Shows the LSP diagnostic message(s) in a tooltip when hovering a squiggled span. */
    private void installLspHover(CodeArea a) {
        a.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (!lspActive || lspOverlay.diagnostics().isEmpty()) {
                if (lspTooltip != null) {
                    lspTooltip.hide();
                }
                return;
            }
            try {
                var hit = a.hit(e.getX(), e.getY());
                var pos = a.offsetToPosition(hit.getInsertionIndex(),
                        org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
                var hits = lspOverlay.at(pos.getMajor(), pos.getMinor());
                if (hits.isEmpty()) {
                    if (lspTooltip != null) {
                        lspTooltip.hide();
                    }
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
                if (lspTooltip == null) {
                    lspTooltip = new javafx.scene.control.Tooltip();
                    lspTooltip.getStyleClass().add("lsp-diagnostic-tooltip");
                    lspTooltip.setWrapText(true);
                    lspTooltip.setMaxWidth(480);
                }
                lspTooltip.setText(sb.toString());
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

    public MarkdownViewMode getMarkdownViewMode() {
        return markdownViewMode;
    }

    public void setOnViewModeChanged(Runnable callback) {
        this.onViewModeChanged = callback == null ? () -> { } : callback;
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

    /** Switches the Markdown view mode, (un)subscribing the live preview and rebuilding the view host. */
    public void setMarkdownViewMode(MarkdownViewMode mode) {
        MarkdownViewMode target = mode == null ? MarkdownViewMode.EDITOR : mode;
        boolean changed = this.markdownViewMode != target;
        this.markdownViewMode = target;
        if (target == MarkdownViewMode.EDITOR) {
            unsubscribePreview();
        } else {
            this.split = Split.NONE; // preview supersedes any code split
            ensurePreviewSubscription();
            scheduleRenderPreview();
        }
        rebuildViewHost();
        if (target == MarkdownViewMode.PREVIEW) {
            // Focus the preview so the paging keys (Space/PageDown/Backspace/PageUp) work without a click.
            Platform.runLater(previewPane()::requestFocus);
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
            previewPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> previewPane.requestFocus());
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
        }
        return previewPane;
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
        boolean previewActive = markdownViewMode == MarkdownViewMode.PREVIEW
                || (previewPane != null && previewPane.isFocusWithin());
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
     * Releases this buffer's OS-level resources. Must be called by the controller when the tab is
     * <em>actually closed</em> (not on a plain tab switch): each buffer owns two daemon executor threads
     * ({@code markdown-preview} + {@code editor-highlighter}) that the JVM keeps alive until shut down,
     * so without this they accumulate one pair per opened file. The reactfx subscriptions are on the
     * buffer's own {@code area}/{@code area2} and die with it on GC, but we drop the preview one eagerly
     * too. Idempotent. Once disposed the buffer must not be reused.
     */
    public void dispose() {
        unsubscribePreview();
        previewExecutor.shutdownNow();
        highlightExecutor.shutdownNow();
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
        if (isDiagram()) {
            // Whole file is one diagram: build the (async-filling) node on the FX thread directly. The
            // preview zoom scales the fit width (not font size, which doesn't affect an ImageView); the
            // content-hash cache makes a zoom re-render a cheap re-fit of the same image. Don't call
            // applyPreviewScale() here — it re-enters this branch for diagrams (would recurse).
            double v = previewPane().getVvalue();
            double scale = previewFontScale;
            javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(
                    MermaidImages.node(area.getText(), lw -> lw * scale));
            box.getStyleClass().add("markdown-preview");
            StackPane wrap = new StackPane(box);
            wrap.getStyleClass().add("markdown-preview-wrap");
            previewPane().setContent(wrap);
            Platform.runLater(() -> previewPane().setVvalue(v));
            return;
        }
        String md = area.getText();
        Path baseDir = path == null ? null : path.getParent();
        long gen = ++previewGen;
        previewExecutor.submit(() -> {
            org.commonmark.node.Node ast = MarkdownRenderer.parseToDocument(md);
            Platform.runLater(() -> {
                if (gen != previewGen) {
                    return; // a newer render superseded this one
                }
                double v = previewPane().getVvalue();
                previewPane().setContent(MarkdownRenderer.renderDocument(ast, baseDir));
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
            StackPane host = previewHost(); // preview + zoom control
            if (viewModeControl != null) {
                StackPane.setAlignment(viewModeControl, Pos.TOP_RIGHT);
                StackPane.setMargin(viewModeControl, new Insets(6, 10, 0, 0));
                host.getChildren().add(viewModeControl);
            }
            content = host;
        } else if (markdownViewMode == MarkdownViewMode.SPLIT) {
            SplitPane pane = new SplitPane(root, previewHost());
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

    private StackPane previewHost() {
        if (previewHost == null) {
            previewHost = new StackPane();
        }
        // preview content + the −/+ zoom control (top-left, clear of the mode toggle at top-right).
        previewHost.getChildren().setAll(previewPane(), zoomControl());
        StackPane.setAlignment(zoomControl(), Pos.TOP_LEFT);
        StackPane.setMargin(zoomControl(), new Insets(6, 0, 0, 6));
        return previewHost;
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
        if (isDiagram()) {
            scheduleRenderPreview(); // re-fit the diagram image to the new zoom (cache hit — cheap)
            return;
        }
        previewPane.getContent().setStyle("-fx-font-size: " + (BASE_PREVIEW_FONT * previewFontScale) + "px;");
    }

    /** Removes the floating control from whichever pane currently hosts it. */
    private void detachViewModeControl() {
        if (viewModeControl == null) {
            return;
        }
        root.getChildren().remove(viewModeControl);
        if (previewHost != null) {
            previewHost.getChildren().remove(viewModeControl);
        }
    }

    /** Overlays the control at the top-right of the code pane ({@link #root}), clear of its minimap. */
    private void attachControlToCodePane() {
        if (viewModeControl == null) {
            return;
        }
        if (!root.getChildren().contains(viewModeControl)) {
            root.getChildren().add(viewModeControl);
        }
        AnchorPane.setTopAnchor(viewModeControl, 6d);
        AnchorPane.setRightAnchor(viewModeControl, codePaneControlInset());
    }

    private double codePaneControlInset() {
        return (minimapVisible && !largeFile) ? Minimap.WIDTH + 6 : 10;
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
        area2.setLineHighlighterFill(lineHighlightColor);
        area2.setParagraphGraphicFactory(LineNumberFactory.get(area2));
        area2.setStyle("-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;");
        area2.caretPositionProperty().addListener((obs, old, now) -> resetGoalColumn());
        area2.caretPositionProperty().addListener((obs, old, now) -> scheduleBraceMatch());
        area2.focusedProperty().addListener((obs, was, now) -> {
            if (now) {
                focusedArea = area2;
            }
        });
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
        applyMinimap(scrollPane2, minimap2, minimapVisible && !largeFile);
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
        return a.isPreserveStyle()
                ? UndoUtils.richTextUndoManager(a, factory)
                : UndoUtils.plainTextUndoManager(a, factory);
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

    /** Rebuilds the gutter graphic factory (line numbers + fold chevrons + markers) from current state. */
    public void refreshGutter() {
        noteOverlay.refresh();
        area.setParagraphGraphicFactory(folds.gutterFactory(lineNumbersVisible));
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
        if (largeFile && lineClasses != null) {
            lineClasses = null; // never track in large/huge-file mode
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

    /** Sets the gutter-click handler ({@code (buffer, line)}): used to add or confirm-remove bookmarks. */
    public void setGutterBookmarkClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        if (handler != null) {
            this.gutterBookmarkClick = handler;
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

    // ---- Personal Notes ----

    public NoteManager getNoteManager() {
        return notes;
    }

    /** Sets the gutter note-marker click handler ({@code (buffer, line)}) — the controller opens the note. */
    public void setGutterNoteClick(java.util.function.BiConsumer<EditorBuffer, Integer> handler) {
        if (handler != null) {
            this.gutterNoteClick = handler;
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
                    ? com.editora.config.NoteScope.WORD : com.editora.config.NoteScope.RANGE;
            String prefix = doc.substring(Math.max(0, start - CONTEXT_CHARS), start);
            String suffix = doc.substring(end, Math.min(doc.length(), end + CONTEXT_CHARS));
            var anchor = new com.editora.config.TextAnchor(sp.getMajor(), sp.getMinor(),
                    ep.getMajor(), ep.getMinor(), area.getSelectedText(), prefix, suffix);
            return new NoteDraft(scope, anchor);
        }
        int line = area.getCurrentParagraph();
        String lineText = area.getParagraph(line).getText();
        int lineLen = lineText.length();
        var anchor = new com.editora.config.TextAnchor(line, 0, line, lineLen, lineText, "", "");
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
            node = MarkdownRenderer.renderDocument(MarkdownRenderer.parseToDocument(body),
                    path != null ? path.getParent() : null);
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
    public void setMinimapVisible(boolean visible) {
        this.minimapVisible = visible;
        // Large files force the minimap off regardless of the user's setting (see setLargeFile).
        boolean effective = visible && !largeFile;
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
        if (!rulerVisible) {
            return;
        }
        Double x = column80X();
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
    private Double column80X() {
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
            double advance = (end.getMinX() - start.getMinX()) / refLen;
            return start.getMinX() + 80 * advance;
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
        String fileName = path == null ? null : path.getFileName().toString();
        String name = fileName == null ? LanguageRegistry.plaintext() : LanguageRegistry.forFileName(fileName);
        IGrammar g = fileName == null ? null : GrammarRegistry.shared().forFileName(fileName);
        applyLanguage(name, g);
        recomputeRun(); // a Save-As to a runnable file type can show the gutter Run glyph
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
        IGrammar g = GrammarRegistry.shared().forLanguageName(resolved);
        applyLanguage(resolved, g);
    }

    /** Applies a language name + grammar: updates fold strategy and re-highlights. */
    private void applyLanguage(String name, IGrammar g) {
        this.language = name;
        this.grammar = g;
        folds.setLanguage(language);
        spellOverlay.setProseMode(isProse()); // prose checks all words; code only comments/strings
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

    /** The detected line ending of the current document ({@code "LF"}/{@code "CRLF"}). */
    public String getLineEnding() {
        return detectLineEnding(area.getText());
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
        spellOverlay.setChecker(spellChecker);
    }

    /** Called when the user picks "Add to Dictionary"; the controller persists the word. */
    public void setOnAddToDictionary(java.util.function.Consumer<String> callback) {
        this.onAddToDictionary = callback == null ? w -> { } : callback;
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
     * Shows/hides the MS-Word-style "View Mode" banner above the editor: visible only in user View mode
     * (not huge-file mode, which can't be made editable). The "Enable Editing" button appears only when
     * the file is writable; otherwise a "read-only on disk" note replaces it.
     */
    private void updateViewModeBar() {
        boolean show = viewMode && !hugeFile;
        if (!show) {
            outer.setTop(null);
            return;
        }
        if (viewModeBar == null) {
            viewModeBar = buildViewModeBar();
        }
        boolean canEdit = path == null || Files.isWritable(path);
        enableEditingButton.setVisible(canEdit);
        enableEditingButton.setManaged(canEdit);
        viewModeNote.setVisible(!canEdit);
        viewModeNote.setManaged(!canEdit);
        outer.setTop(viewModeBar);
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
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
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
            if (e.getCode() == KeyCode.TAB && !e.isShiftDown()
                    && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()
                    && expandPrefixAtCaret(a)) {
                e.consume();
            }
        });
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
            if (e.getCode() != KeyCode.ENTER || e.isShiftDown() || e.isControlDown()
                    || e.isAltDown() || e.isMetaDown()) {
                return;
            }
            if (!isEditable() || hasActiveSnippet()) {
                return;
            }
            if (a.getSelection().getLength() > 0) {
                a.replaceSelection("");
            }
            int caret = a.getCaretPosition();
            Indenter.EnterEdit edit = Indenter.enterEdit(a.getText(), caret, language, tabSize);
            a.replaceText(caret, caret, edit.insert());
            a.moveTo(caret + edit.caretOffset());
            a.requestFollowCaret();
            e.consume(); // we inserted the newline+indent ourselves
        });
        // Smart backspace: when the caret is in a line's leading whitespace, one Backspace clears the
        // whole indent — and on an otherwise-blank (auto-indented) line it also removes the newline, so
        // a single press jumps back to the end of the previous line ("back to where you hit Enter").
        // Only consumes when it removes more than one char, so a normal single-char Backspace still runs
        // everywhere else (and the auto-close empty-pair handler, registered earlier, gets first dibs).
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.BACK_SPACE || viewMode || !isEditable() || hasActiveSnippet()
                    || e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShiftDown()
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
            int del = Indenter.smartBackspaceCount(line.substring(0, col), line.substring(col), par > 0);
            if (del > 1) {
                a.deleteText(caret - del, caret);
                e.consume();
            }
        });
        a.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (!isEditable() || hasActiveSnippet() || e.getCharacter().length() != 1
                    || e.isControlDown() || e.isAltDown() || e.isMetaDown()
                    || a.getSelection().getLength() > 0) {
                return;
            }
            char c = e.getCharacter().charAt(0);
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
        });
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
            if (!isEditable() || hasActiveSnippet() || e.getCharacter().length() != 1
                    || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                return;
            }
            char c = e.getCharacter().charAt(0);
            if (AutoClose.closerFor(c) == 0 && !AutoClose.isCloser(c)) {
                return; // not a bracket or quote
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
                    e.consume();
                }
                case SKIP_OVER -> {
                    a.moveTo(caret + 1);
                    e.consume();
                }
                case WRAP_SELECTION -> {
                    int s = a.getSelection().getStart();
                    String sel = a.getSelectedText();
                    a.replaceText(s, a.getSelection().getEnd(), "" + c + sel + d.closer());
                    a.selectRange(s + 1, s + 1 + sel.length());
                    e.consume();
                }
                case NONE -> { } // normal typing (and the auto-indent closer-dedent may run)
            }
        });
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() != KeyCode.BACK_SPACE || viewMode || !isEditable() || hasActiveSnippet()
                    || e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShiftDown()
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
        int[] m = BraceMatcher.match(a.getText(), a.getCaretPosition(), BraceMatcher.DEFAULT_MAX_SCAN);
        if (m != null) {
            addBraceClass(m[0]);
            addBraceClass(m[1]);
            braceMatch = m;
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

    /** Expands the identifier before the caret if it matches a snippet prefix; returns whether it did. */
    private boolean expandPrefixAtCaret(CodeArea a) {
        if (!isEditable() || a.getSelection().getLength() > 0) {
            return false;
        }
        int caret = a.getCaretPosition();
        String text = a.getText();
        int start = caret;
        while (start > 0 && isPrefixChar(text.charAt(start - 1))) {
            start--;
        }
        if (start == caret) {
            return false;
        }
        Snippet snippet = snippetProvider.apply(language, text.substring(start, caret));
        if (snippet == null) {
            return false;
        }
        startSnippet(a, snippet, start, caret);
        return true;
    }

    /** Parses {@code snippet}, replaces {@code [from,to)} with the expansion, and begins a session. */
    private void startSnippet(CodeArea a, Snippet snippet, int from, int to) {
        if (snippetSession != null) {
            snippetSession.cancel();
            snippetSession = null;
        }
        String fileName = path == null ? "" : path.getFileName().toString();
        String directory = path == null || path.toAbsolutePath().getParent() == null
                ? "" : path.toAbsolutePath().getParent().toString();
        String filePath = path == null ? "" : path.toAbsolutePath().toString();
        String clip = javafx.scene.input.Clipboard.getSystemClipboard().hasString()
                ? javafx.scene.input.Clipboard.getSystemClipboard().getString() : "";
        int line = a.offsetToPosition(from, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
        String currentLine = a.getParagraph(line).getText();
        VariableResolver vars = new VariableResolver(fileName, directory, filePath,
                a.getSelectedText(), clip, line, currentLine);
        ParsedSnippet parsed = SnippetParser.parse(snippet.body(), vars);
        String indent = leadingIndent(currentLine);
        SnippetSession session = new SnippetSession(a, parsed, from, to, indent);
        if (session.isActive()) {
            snippetSession = session;
            session.setOnEnd(() -> snippetSession = null);
        }
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

    private CompletionPopup completionPopup() {
        if (completionPopup == null) {
            completionPopup = new CompletionPopup();
            completionPopup.setOnAccept(c -> {
                if (completionArea != null) {
                    acceptCompletion(completionArea, c);
                }
            });
        }
        return completionPopup;
    }

    /**
     * Popup navigation/accept/dismiss while it's open. Registered <b>after</b> the snippet/indent filters
     * so it runs first: with the popup open, Tab/Enter accept the selection (instead of expanding a
     * snippet or inserting a newline); ↑/↓ move; Esc closes; caret-moving keys dismiss. With the popup
     * closed it does nothing, so normal Tab/Enter behavior is unaffected.
     */
    private void addCompletionKeys(CodeArea a) {
        a.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
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
                    case ESCAPE -> { hideGhost(); e.consume(); }
                    default -> { } // typing/Backspace/arrows fall through
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
            }
            switch (e.getCode()) {
                case DOWN -> { completionPopup.moveDown(); e.consume(); }
                case UP -> { completionPopup.moveUp(); e.consume(); }
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
                case ESCAPE -> { hideCompletion(); e.consume(); }
                case LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN -> hideCompletion(); // let the caret move
                default -> { } // letters/Backspace fall through; the debounced trigger refreshes the list
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
        a.multiPlainChanges()
                .successionEnds(Duration.ofMillis(280))
                .subscribe(ignored -> {
                    if (a.isFocused()) {
                        updateCompletion(a, false);
                    }
                });
        // Any caret move or scroll invalidates the inline ghost's position; clear it (the debounce
        // re-shows it after the next pause in typing). The popup manages its own key/caret handling.
        a.caretPositionProperty().addListener((o, ov, nv) -> hideGhost());
        a.estimatedScrollYProperty().addListener((o, ov, nv) -> hideGhost());
    }

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
        if (!autocompleteEnabled || hugeFile || !isEditable() || hasActiveSnippet()
                || (suppressCompletion && !manual) || a.getSelection().getLength() > 0) {
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
        boolean lspTrigger = lspActive && !isProse()
                && (endsWithLspTrigger(text, caret) || completionPopupShowing());
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
        List<Completion> items = completionProvider.complete(language, getSpellLanguage(), prefix, isProse());
        if (a.getScene() == null) {
            return;
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
        completionPopup().show(a.getScene().getWindow(), caretScreen, items);
    }

    /** The suffix of the best word completion that continues {@code prefix}, or null if none qualifies. */
    private static String bestGhostSuffix(List<Completion> items, String prefix) {
        for (Completion c : items) {
            if (c.kind() == Completion.Kind.WORD && c.insert().length() > prefix.length()
                    && c.insert().regionMatches(true, 0, prefix, 0, prefix.length())) {
                return c.insert().substring(prefix.length());
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
                : a.getCharacterBoundsOnScreen(caret, caret + 1)).orElse(null);
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
        suppressCompletion = true;
        try {
            a.insertText(a.getCaretPosition(), s);
        } finally {
            Platform.runLater(() -> suppressCompletion = false);
        }
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
    }

    /** Injected async LSP completion source: {@code accept({line,char}, items->…)}; null = none. */
    public void setLspCompletionProvider(java.util.function.BiConsumer<int[],
            java.util.function.Consumer<java.util.List<Completion>>> provider) {
        this.lspCompletionProvider = provider;
    }

    /** Requests LSP completions async, filters them by the typed {@code prefix} (the server returns the
     *  whole scope and leaves filtering to the client), then shows them merged with the local snippets. */
    private void requestLspCompletion(CodeArea a, int caret, String prefix,
            java.util.List<Completion> localItems) {
        long gen = ++completionGen;
        // Flush the current text to the server FIRST: the completion auto-trigger (≈120ms) fires before
        // the debounced didChange (≈300ms), so without this the server still has stale text and member
        // completion after '.' resolves against the old document. JSON-RPC preserves order, so this
        // didChange is applied before the completion request below.
        if (lspChangeListener != null) {
            lspChangeListener.accept(a.getText());
        }
        lspCompletionProvider.accept(new int[]{a.getCurrentParagraph(), a.getCaretColumn()}, lspItems -> {
            if (gen != completionGen || a.getScene() == null || a.getCaretPosition() != caret
                    || hasActiveSnippet()) {
                return;
            }
            java.util.List<Completion> merged = mergeCompletions(filterByPrefix(lspItems, prefix), localItems);
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
            completionPopup().show(a.getScene().getWindow(), cs, merged);
        });
    }

    /** Whether the completion popup is currently open (an in-progress LSP/local completion session). */
    private boolean completionPopupShowing() {
        return completionPopup != null && completionPopup.isShowing();
    }

    /** True if the char just before {@code caret} is an LSP completion trigger character (Java's '.'). */
    private static boolean endsWithLspTrigger(String text, int caret) {
        return caret > 0 && caret <= text.length() && text.charAt(caret - 1) == '.';
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

    /** Merges LSP completions (first) with local snippet items, de-duped by insert text, capped. */
    private static java.util.List<Completion> mergeCompletions(java.util.List<Completion> lsp,
            java.util.List<Completion> local) {
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
        hideCompletion();
        suppressCompletion = true;
        try {
            if (c.snippet() != null) {
                startSnippet(a, c.snippet(), start, caret);
            } else {
                a.replaceText(start, caret, c.insert());
            }
        } finally {
            Platform.runLater(() -> suppressCompletion = false);
        }
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
    public void applyLspEdits(java.util.List<LspTextEdit> edits) {
        if (edits == null || edits.isEmpty() || !isEditable()) {
            return;
        }
        CodeArea a = focusedArea != null ? focusedArea : area;
        java.util.List<LspTextEdit> sorted = new java.util.ArrayList<>(edits);
        sorted.sort((x, y) -> Integer.compare(lspEditOffset(a, y), lspEditOffset(a, x)));
        for (LspTextEdit e : sorted) {
            try {
                int s = lspOffset(a, e.startLine(), e.startCol());
                int en = lspOffset(a, e.endLine(), e.endCol());
                a.replaceText(Math.min(s, en), Math.max(s, en), e.newText() == null ? "" : e.newText());
            } catch (RuntimeException ignored) {
                // Position no longer valid (document changed under us) — skip this edit.
            }
        }
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

    public String getContent() {
        return area.getText();
    }

    public BooleanProperty dirtyProperty() {
        return dirty;
    }

    public boolean isDirty() {
        return dirty.get();
    }

    /** Marks the current content as the saved baseline (after load/save); clears the dirty flag. */
    public void markClean() {
        cleanText = area.getText();
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
        this.onSymbolsChanged = callback == null ? () -> { } : callback;
    }

    /** Forces the next {@link #applyHighlighting()} to re-tokenize the whole document (e.g. after a
     *  language/grammar change, where no text change occurred). */
    private void invalidateHighlighting() {
        dirtyFromLine = 0;
        lineStates.clear();
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
        if (grammar == null) {
            // No grammar for this file type: clear any previously applied styles so none linger.
            // This is cheap (a single span), so do it inline on the FX thread.
            int length = area.getLength();
            if (length > 0) {
                area.setStyleSpans(0, new StyleSpansBuilder<Collection<String>>()
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
        highlightExecutor.execute(() -> {
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
                area.setStyleSpans(a.fromOffset(), a.spans());
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
}
