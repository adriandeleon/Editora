package com.editora.editor;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The MS-Word-style "View Mode" banner above a read-only editor: a title, why the file is read-only, and "Enable
 * Editing" (or a "read-only on disk" note in its place). The reason defaults to the general one; a file opened
 * read-only because of its shape (a minified bundle, a very large file) says so instead.
 */
final class ViewModeBar {

    private final HBox node;
    private final Label desc = new Label();
    private final Button enableEditing = new Button(tr("viewmode.enableEditing"));
    private final Label note = new Label(tr("viewmode.note"));

    ViewModeBar(Runnable onEnableEditing) {
        Label title = new Label(tr("viewmode.title"));
        title.getStyleClass().add("view-mode-title");
        desc.getStyleClass().add("view-mode-desc");
        setReason(null);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        enableEditing.getStyleClass().add("accent"); // AtlantaFX accent button — themed, not a hard yellow
        enableEditing.setOnAction(e -> onEnableEditing.run());
        note.getStyleClass().add("view-mode-note");
        node = new HBox(10, title, desc, spacer, note, enableEditing);
        node.getStyleClass().add("view-mode-bar");
        node.setAlignment(Pos.CENTER_LEFT);
    }

    HBox node() {
        return node;
    }

    Button enableEditing() {
        return enableEditing;
    }

    Label note() {
        return note;
    }

    /** Why the file is open for viewing; {@code null} is the general sentence (paging keys). */
    void setReason(@Nullable String reason) {
        desc.setText(reason == null ? tr("viewmode.desc") : reason);
    }

    String reason() {
        return desc.getText();
    }
}
