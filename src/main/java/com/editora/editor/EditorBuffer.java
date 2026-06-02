package com.editora.editor;

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
public class EditorBuffer {

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
    /** The preview's −/+ zoom control (overlaid top-left of the preview when previewing). */
    private HBox zoomControl;
    /** Preview text zoom factor (1.0 = 100%); scales the rendered preview's base font size. */
    private double previewFontScale = 1.0;
    /** Base preview font size in px (matches {@code .markdown-preview} in app.css); headings use em. */
    private static final double BASE_PREVIEW_FONT = 14;
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
    private final FoldManager folds = new FoldManager(area);
    private final BookmarkManager bookmarks = new BookmarkManager(area);
    /** Handles a gutter click on a line: the controller adds, or confirms a removal. Default: toggle. */
    private java.util.function.BiConsumer<EditorBuffer, Integer> gutterBookmarkClick =
            (buffer, line) -> buffer.toggleBookmark(line);

    private Path path;
    /** Language name for the current file (drives fold strategy); see {@link LanguageRegistry}. */
    private String language = LanguageRegistry.plaintext();
    /** TextMate grammar for the current file, or {@code null} when no grammar is bundled. */
    private IGrammar grammar;
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
        addViewModePaging(area); // Space/Backspace = page down/up while in read-only View mode
        addSnippetKeys(area); // Tab expands/cycles snippets (else falls through to indent)
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
                .subscribe(ignore -> applyHighlighting());
        // Dirty only when the content differs from the last saved/loaded text, so reverting an edit
        // (undo or manual) clears the marker. The length check short-circuits the O(n) compare —
        // it runs only when the length matches the saved length (i.e. near the original state).
        area.textProperty().addListener((obs, old, now) ->
                dirty.set(now.length() != cleanText.length() || !now.equals(cleanText)));
        area.caretPositionProperty().addListener((obs, old, now) -> resetGoalColumn());
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
        root.getChildren().addAll(scrollPane, whitespace, minimap, columnRuler);
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
        AnchorPane.setTopAnchor(minimap, 0d);
        AnchorPane.setBottomAnchor(minimap, 0d);
        AnchorPane.setRightAnchor(minimap, 0d);
    }

    private void installContextMenu() {
        MenuItem cut = new MenuItem("Cut");
        cut.setOnAction(e -> area.cut());
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(e -> area.copy());
        MenuItem paste = new MenuItem("Paste");
        paste.setOnAction(e -> area.paste());
        MenuItem undo = new MenuItem("Undo");
        undo.setOnAction(e -> area.undo());
        MenuItem redo = new MenuItem("Redo");
        redo.setOnAction(e -> area.redo());
        MenuItem selectAll = new MenuItem("Select All");
        selectAll.setOnAction(e -> area.selectAll());

        ContextMenu menu = new ContextMenu(
                cut, copy, paste,
                new SeparatorMenuItem(),
                undo, redo,
                new SeparatorMenuItem(),
                selectAll);
        menu.getStyleClass().add("editor-context-menu");

        menu.setOnShowing(e -> {
            boolean hasSelection = area.getSelection().getLength() > 0;
            boolean hasClipboardText = Clipboard.getSystemClipboard().hasString();
            cut.setDisable(!hasSelection);
            copy.setDisable(!hasSelection);
            paste.setDisable(!hasClipboardText);
            undo.setDisable(!area.isUndoAvailable());
            redo.setDisable(!area.isRedoAvailable());
        });

        area.setOnContextMenuRequested(e -> {
            menu.show(area, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // A left-click in the editor dismisses an open context menu. RichTextFX consumes the
        // mouse press before the popup's auto-hide fires, so close it explicitly. The event is
        // not consumed, so the click still positions the caret as usual.
        area.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (menu.isShowing() && e.getButton() == MouseButton.PRIMARY) {
                menu.hide();
            }
        });
    }

    /** The primary view. Tool windows, overlays, folding and highlighting all bind to this one. */
    public CodeArea getArea() {
        return area;
    }

    /** The view that currently has focus (primary or the split's secondary); for caret/edit commands. */
    public CodeArea getFocusedArea() {
        return focusedArea;
    }

    /** The node to place in the scene: the read-only banner (when shown) above the editor view. */
    public Region getNode() {
        return outer;
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

    public MarkdownViewMode getMarkdownViewMode() {
        return markdownViewMode;
    }

    public void setOnViewModeChanged(Runnable callback) {
        this.onViewModeChanged = callback == null ? () -> { } : callback;
    }

    /** Overlays the Editor/Split/Preview control top-right of this buffer's view (Markdown buffers only). */
    public void setViewModeControl(Node control) {
        this.viewModeControl = control;
        rebuildViewHost();
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
        if (changed) {
            onViewModeChanged.run();
        }
    }

    private ScrollPane previewPane() {
        if (previewPane == null) {
            previewPane = new ScrollPane();
            previewPane.setFitToWidth(true);
            previewPane.getStyleClass().add("markdown-preview-pane");
        }
        return previewPane;
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

    private void scheduleRenderPreview() {
        if (markdownViewMode == MarkdownViewMode.EDITOR) {
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
            zoomControl = new HBox(out, in);
            zoomControl.getStyleClass().add("md-zoom");
            zoomControl.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            zoomControl.setPickOnBounds(false);
        }
        return zoomControl;
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

    /** Applies the current zoom by overriding the rendered preview root's base font size (headings use em). */
    private void applyPreviewScale() {
        if (previewPane != null && previewPane.getContent() != null) {
            previewPane.getContent().setStyle("-fx-font-size: " + (BASE_PREVIEW_FONT * previewFontScale) + "px;");
        }
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
        area2.setLineHighlighterFill(lineHighlightColor);
        area2.setParagraphGraphicFactory(LineNumberFactory.get(area2));
        area2.setStyle("-fx-font-family: \"" + fontFamily + "\"; -fx-font-size: " + fontSize + "px;");
        area2.caretPositionProperty().addListener((obs, old, now) -> resetGoalColumn());
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

    /** Rebuilds the gutter graphic factory (line numbers + fold chevrons) from current state. */
    private void refreshGutter() {
        area.setParagraphGraphicFactory(folds.gutterFactory(lineNumbersVisible));
    }

    public FoldManager getFoldManager() {
        return folds;
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

    /** Associates this buffer with a file and selects the grammar and fold language from its extension. */
    public void setPath(Path path) {
        this.path = path;
        String fileName = path == null ? null : path.getFileName().toString();
        String name = fileName == null ? LanguageRegistry.plaintext() : LanguageRegistry.forFileName(fileName);
        IGrammar g = fileName == null ? null : GrammarRegistry.shared().forFileName(fileName);
        applyLanguage(name, g);
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
        Label title = new Label("View Mode");
        title.getStyleClass().add("view-mode-title");
        Label desc = new Label(
                "This file is open for viewing. Space pages down, Backspace pages up.");
        desc.getStyleClass().add("view-mode-desc");
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        enableEditingButton = new Button("Enable Editing");
        enableEditingButton.getStyleClass().add("accent"); // AtlantaFX accent button — themed, not a hard yellow
        enableEditingButton.setOnAction(e -> onEnableEditing.run());
        viewModeNote = new Label("Read-only on disk (no write permission)");
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
        return path == null ? "untitled" : path.getFileName().toString();
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
