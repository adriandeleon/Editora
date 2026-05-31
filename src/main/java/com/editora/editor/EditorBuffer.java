package com.editora.editor;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.NavigationActions.SelectionPolicy;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.nio.file.Path;

import javafx.application.Platform;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;

/** A single open document: a RichTextFX {@link CodeArea} plus its backing file, language, and dirty state. */
public class EditorBuffer {

    private final CodeArea area = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(area);
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);

    /** Orientation of an optional second, synced view of this document. */
    public enum Split { NONE, SIDE_BY_SIDE, STACKED }

    /** Wraps the scroll pane so we can overlay the column-80 ruler line and dock the minimap. */
    private final AnchorPane root = new AnchorPane();
    /** Tab content: shows either {@link #root} alone or a SplitPane of [root, secondary view]. */
    private final StackPane viewHost = new StackPane(root);
    /** A second editable view sharing this document (created lazily on first split). */
    private CodeArea area2;
    private VirtualizedScrollPane<CodeArea> scrollPane2;
    /** The secondary view's container (scroll pane + its own minimap), mounted in the SplitPane. */
    private AnchorPane root2;
    private Minimap minimap2;
    private Split split = Split.NONE;
    /** Whether the minimap is shown; applied to every split pane's minimap. */
    private boolean minimapVisible = true;
    /** The most recently focused view (primary or secondary); drives "active area" for commands. */
    private CodeArea focusedArea = area;
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
    /** Named definitions from the last tokenization (FX-thread confined); drives the Structure view. */
    private List<TextMateHighlighter.Symbol> symbols = List.of();
    /** Notified (on the FX thread) after {@link #symbols} is refreshed. */
    private Runnable onSymbolsChanged = () -> { };
    private String fontFamily = "monospace";
    private int fontSize = 14;
    /** Visual tab width (columns); applied to the minimap and persisted via Settings. */
    private int tabSize = 4;
    /** Whether the user enabled the 80-column ruler. The line is only actually shown when a visible
     *  line reaches column 80 (see {@link #measureAndPlaceRuler}). */
    private boolean rulerVisible;
    private boolean lineNumbersVisible = true;
    /** Coalesces ruler re-measurement onto a later pulse (see {@link #scheduleRulerMeasure}). */
    private boolean rulerMeasurePending;

    public EditorBuffer() {
        refreshGutter();
        area.getStyleClass().add("editor-area");
        area.setWrapText(false);
        area.setLineHighlighterFill(Color.web("#dfe7f0"));
        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(150))
                .subscribe(ignore -> applyHighlighting());
        area.textProperty().addListener((obs, old, now) -> dirty.set(true));
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

    /** The node to place in the scene: the primary view, or a SplitPane when split. */
    public Region getNode() {
        return viewHost;
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
            viewHost.getChildren().setAll(root);
            return;
        }
        ensureSecondaryView();
        SplitPane pane = new SplitPane(root, root2);
        pane.setOrientation(orientation == Split.SIDE_BY_SIDE ? Orientation.HORIZONTAL : Orientation.VERTICAL);
        pane.setDividerPositions(0.5);
        viewHost.getChildren().setAll(pane);
    }

    /** Lazily builds the secondary view (scroll pane + its own minimap) sharing this document. */
    private void ensureSecondaryView() {
        if (area2 != null) {
            return;
        }
        area2 = new CodeArea(area.getContent()); // shares the EditableStyledDocument
        area2.getStyleClass().add("editor-area");
        area2.setWrapText(false);
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
        root2 = new AnchorPane(scrollPane2, minimap2);
        AnchorPane.setTopAnchor(scrollPane2, 0d);
        AnchorPane.setBottomAnchor(scrollPane2, 0d);
        AnchorPane.setLeftAnchor(scrollPane2, 0d);
        AnchorPane.setTopAnchor(minimap2, 0d);
        AnchorPane.setBottomAnchor(minimap2, 0d);
        AnchorPane.setRightAnchor(minimap2, 0d);
        applyMinimap(scrollPane2, minimap2, minimapVisible);
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

    /** Toggle the highlight on the line containing the caret. */
    public void setLineHighlightOn(boolean on) {
        area.setLineHighlighterOn(on);
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
        applyMinimap(scrollPane, minimap, visible);
        AnchorPane.setRightAnchor(whitespace, visible ? Minimap.WIDTH : 0d);
        if (minimap2 != null) {
            applyMinimap(scrollPane2, minimap2, visible);
        }
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
    public void moveLine(int delta) {
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
            a.moveTo(target, col, SelectionPolicy.CLEAR);
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

    public void markClean() {
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

    private void applyHighlighting() {
        if (grammar == null) {
            // No grammar for this file type: clear any previously applied styles so none linger.
            // This is cheap (a single span), so do it inline on the FX thread.
            int length = area.getLength();
            if (length > 0) {
                area.setStyleSpans(0, new StyleSpansBuilder<Collection<String>>()
                        .add(Collections.emptyList(), length)
                        .create());
            }
            if (!symbols.isEmpty()) {
                symbols = List.of();
                onSymbolsChanged.run();
            }
            return;
        }
        // Tokenizing a whole document is O(document length) — hundreds of milliseconds to over a
        // second on large files. Running it on the FX thread froze scrolling and typing, so we
        // compute the spans on a background thread and apply the result back on the FX thread. A
        // generation counter discards stale results, and we re-check the document length before
        // applying (the user may have edited since), since RichTextFX requires the spans to cover
        // the document exactly.
        String text = area.getText();
        IGrammar g = grammar;
        long gen = ++highlightGen;
        highlightExecutor.execute(() -> {
            TextMateHighlighter.Analysis analysis;
            try {
                analysis = TextMateHighlighter.analyze(text, g);
            } catch (Exception | LinkageError e) {
                return; // never let a grammar/engine fault kill the highlighter thread
            }
            if (analysis == null || analysis.spans() == null) {
                return;
            }
            Platform.runLater(() -> {
                if (gen == highlightGen && analysis.spans().length() == area.getLength()) {
                    area.setStyleSpans(0, analysis.spans());
                    symbols = analysis.symbols();
                    onSymbolsChanged.run();
                }
            });
        });
    }
}
