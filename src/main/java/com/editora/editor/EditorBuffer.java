package com.editora.editor;

import java.time.Duration;
import java.util.Collection;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;

import java.nio.file.Path;

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
    private LanguageRules rules = LanguageRegistry.plaintext();
    private String fontFamily = "monospace";
    private int fontSize = 14;
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

    /** Associates this buffer with a file and selects highlighting rules from its extension. */
    public void setPath(Path path) {
        this.path = path;
        this.rules = path == null
                ? LanguageRegistry.plaintext()
                : LanguageRegistry.forFileName(path.getFileName().toString());
        folds.setLanguage(rules.name());
        applyHighlighting();
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
        StyleSpans<Collection<String>> spans = SyntaxHighlighter.compute(area.getText(), rules);
        if (spans != null) {
            area.setStyleSpans(0, spans);
        }
    }
}
