package com.editora.editor;

import java.time.Duration;
import java.util.Collection;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;

import java.nio.file.Path;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/** A single open document: a RichTextFX {@link CodeArea} plus its backing file, language, and dirty state. */
public class EditorBuffer {

    private final CodeArea area = new CodeArea();
    private final BooleanProperty dirty = new SimpleBooleanProperty(false);

    private Path path;
    private LanguageRules rules = LanguageRegistry.plaintext();

    public EditorBuffer() {
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.getStyleClass().add("editor-area");
        area.multiPlainChanges()
                .successionEnds(Duration.ofMillis(150))
                .subscribe(ignore -> applyHighlighting());
        area.textProperty().addListener((obs, old, now) -> dirty.set(true));
    }

    public CodeArea getArea() {
        return area;
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
