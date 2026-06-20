package com.editora.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.editor.EditorBuffer;
import com.editora.editor.UndoHistory;

import static com.editora.i18n.Messages.tr;

/**
 * Tool window content listing the active buffer's in-session undo checkpoints (newest first). A checkpoint
 * is a document snapshot captured when editing settles; double-click / Enter restores it (a single undoable
 * edit). Session-only and finer-grained than save-based Local History.
 */
public class UndoHistoryPanel extends VBox implements ToolWindowContent {

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ListView<UndoHistory.Checkpoint> list = new ListView<>();
    private EditorBuffer buffer;

    public UndoHistoryPanel() {
        getStyleClass().add("undo-history-panel");
        list.getStyleClass().add("undo-history-list");
        list.setPlaceholder(new Label(tr("undoHistory.empty")));
        list.setCellFactory(lv -> new CheckpointCell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                restoreSelected();
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                restoreSelected();
                e.consume();
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().add(list);
    }

    /** Attaches to a buffer (or {@code null}); seeds a baseline checkpoint and refreshes live on edits. */
    public void attach(EditorBuffer newBuffer) {
        if (buffer != null) {
            buffer.setOnUndoHistoryChanged(null);
        }
        this.buffer = newBuffer;
        if (buffer != null) {
            buffer.setOnUndoHistoryChanged(this::refresh);
            if (buffer.getUndoHistory().isEmpty()) {
                buffer.captureUndoCheckpoint(); // baseline = the current (as-opened) state
            }
        }
        refresh();
    }

    private void refresh() {
        var sel = list.getSelectionModel().getSelectedItem();
        list.getItems()
                .setAll(
                        buffer == null
                                ? java.util.List.of()
                                : buffer.getUndoHistory().entriesNewestFirst());
        if (sel != null) {
            list.getItems().stream()
                    .filter(c -> c.seq() == sel.seq())
                    .findFirst()
                    .ifPresent(c -> list.getSelectionModel().select(c));
        }
    }

    private void restoreSelected() {
        UndoHistory.Checkpoint c = list.getSelectionModel().getSelectedItem();
        if (c != null && buffer != null) {
            buffer.restoreUndoCheckpoint(c);
        }
    }

    @Override
    public void focusFirstItem() {
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
        }
        list.requestFocus();
    }

    private static final class CheckpointCell extends ListCell<UndoHistory.Checkpoint> {
        @Override
        protected void updateItem(UndoHistory.Checkpoint c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            Label time = new Label(TIME.format(Instant.ofEpochMilli(c.epochMillis())));
            time.getStyleClass().add("undo-history-time");
            Label preview = new Label(c.linePreview().isEmpty() ? tr("undoHistory.blankLine") : c.linePreview());
            preview.getStyleClass().add("undo-history-preview");
            Region spacer = new Region();
            HBox.setHgrow(preview, Priority.ALWAYS);
            HBox row = new HBox(8, time, preview, spacer);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }
}
