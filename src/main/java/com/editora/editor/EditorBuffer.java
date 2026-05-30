package com.editora.editor;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.nio.file.Path;

import javafx.application.Platform;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

/** A single open document: a RichTextFX {@link CodeArea} plus its backing file, language, and dirty state. */
public class EditorBuffer {

    private final CodeArea area = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(area);
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);

    /** Wraps the scroll pane so we can overlay the column-80 ruler line and dock the minimap. */
    private final AnchorPane root = new AnchorPane();
    private final Line columnRuler = new Line();
    private final Minimap minimap = new Minimap(area);
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
    private String fontFamily = "monospace";
    private int fontSize = 14;
    /** Visual tab width (columns); applied to the minimap and persisted via Settings. */
    private int tabSize = 4;
    private boolean rulerVisible;
    private boolean lineNumbersVisible = true;
    /** Document x of the column-80 ruler (before horizontal scroll is applied). */
    private double rulerBaseX;

    public EditorBuffer() {
        refreshGutter();
        area.getStyleClass().add("editor-area");
        area.setWrapText(false);
        area.setLineHighlighterFill(Color.web("#dfe7f0"));
        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(150))
                .subscribe(ignore -> applyHighlighting());
        area.textProperty().addListener((obs, old, now) -> dirty.set(true));
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
        // The ruler is pinned to the overlay pane, so re-place it as the text scrolls horizontally.
        area.estimatedScrollXProperty().addListener((obs, old, now) -> positionColumnRuler());

        // Editor scroll pane fills the area, leaving room on the right for the minimap; the minimap
        // is docked to the right edge; the column ruler floats on top of everything.
        root.getChildren().addAll(scrollPane, minimap, columnRuler);
        AnchorPane.setTopAnchor(scrollPane, 0d);
        AnchorPane.setBottomAnchor(scrollPane, 0d);
        AnchorPane.setLeftAnchor(scrollPane, 0d);
        AnchorPane.setRightAnchor(scrollPane, Minimap.WIDTH);
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

    public CodeArea getArea() {
        return area;
    }

    /** The node to place in the scene: scroll pane + column-ruler overlay. */
    public Region getNode() {
        return root;
    }

    /** Applies the editor font, overriding the stylesheet defaults. */
    public void setFont(String family, int size) {
        this.fontFamily = family;
        this.fontSize = size;
        area.setStyle("-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;");
        updateColumnRulerPosition();
    }

    /** Show/hide the column-80 ruler overlay. */
    public void setColumnRulerVisible(boolean visible) {
        this.rulerVisible = visible;
        columnRuler.setVisible(visible);
        if (visible) {
            updateColumnRulerPosition();
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

    /** Show/hide the minimap overview; reclaims its width for the editor when hidden. */
    public void setMinimapVisible(boolean visible) {
        minimap.setVisible(visible);
        minimap.setManaged(visible);
        AnchorPane.setRightAnchor(scrollPane, visible ? Minimap.WIDTH : 0d);
    }

    private void updateColumnRulerPosition() {
        if (!rulerVisible) {
            return;
        }
        Text probe = new Text("M");
        probe.setFont(Font.font(fontFamily, fontSize));
        double charWidth = probe.getLayoutBounds().getWidth();
        if (charWidth <= 0) {
            return;
        }
        rulerBaseX = charWidth * 80;
        positionColumnRuler();
    }

    /** Places the ruler at column 80, offset by the current horizontal scroll so it tracks the text. */
    private void positionColumnRuler() {
        if (!rulerVisible) {
            return;
        }
        Double scrollX = area.estimatedScrollXProperty().getValue();
        double x = rulerBaseX - (scrollX == null || scrollX.isNaN() ? 0 : scrollX);
        columnRuler.setStartX(x);
        columnRuler.setEndX(x);
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
    }

    public int getTabSize() {
        return tabSize;
    }

    /** Replaces the document content (e.g. after loading a file) and resets the dirty flag. */
    public void setContent(String content) {
        area.replaceText(content == null ? "" : content);
        markClean();
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
            StyleSpans<Collection<String>> spans;
            try {
                spans = TextMateHighlighter.compute(text, g);
            } catch (Exception | LinkageError e) {
                return; // never let a grammar/engine fault kill the highlighter thread
            }
            if (spans == null) {
                return;
            }
            Platform.runLater(() -> {
                if (gen == highlightGen && spans.length() == area.getLength()) {
                    area.setStyleSpans(0, spans);
                }
            });
        });
    }
}
