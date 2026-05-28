package com.editora.editor;

import java.time.Duration;
import java.util.Collection;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;

import java.nio.file.Path;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.Region;

/** A single open document: a RichTextFX {@link CodeArea} plus its backing file, language, and dirty state. */
public class EditorBuffer {

    private final CodeArea area = new CodeArea();
    private final VirtualizedScrollPane<CodeArea> scrollPane = new VirtualizedScrollPane<>(area);
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);

    private Path path;
    private LanguageRules rules = LanguageRegistry.plaintext();

    public EditorBuffer() {
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.getStyleClass().add("editor-area");
        area.setWrapText(false);
        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(150))
                .subscribe(ignore -> applyHighlighting());
        area.textProperty().addListener((obs, old, now) -> dirty.set(true));
        installContextMenu();
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
    }

    public CodeArea getArea() {
        return area;
    }

    /** The node to place in the scene: the editor wrapped in a scroll pane (vertical + horizontal bars). */
    public Region getNode() {
        return scrollPane;
    }

    /** Applies the editor font, overriding the stylesheet defaults. */
    public void setFont(String family, int size) {
        area.setStyle("-fx-font-family: \"" + family + "\"; -fx-font-size: " + size + "px;");
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
