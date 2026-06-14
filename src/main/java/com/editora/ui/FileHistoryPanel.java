package com.editora.ui;

import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.editora.config.HistoryRevision;
import com.editora.git.RelativeTime;

import static com.editora.i18n.Messages.tr;

/**
 * The Local File History tool window: a newest-first list of recorded versions of the active file.
 * Double-clicking (or the context menu) opens a read-only diff of that snapshot vs the current file;
 * the context menu also restores a snapshot (an undoable whole-file replace). Like {@link GitLogPanel}
 * it is purely a view — the controller (via {@link Actions}) owns the history service and the buffers.
 */
public final class FileHistoryPanel extends VBox implements ToolWindowContent {

    /** Operations the panel asks the controller to perform. */
    public interface Actions {
        void refresh();

        void openDiff(HistoryRevision revision);

        void restore(HistoryRevision revision);
    }

    private final Actions actions;
    private final Label fileLabel = new Label(tr("history.noFile"));
    private final ListView<HistoryRevision> revisions = new ListView<>();
    private final Label placeholder = new Label(tr("history.noRevisions"));

    public FileHistoryPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("history-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);
        setPadding(new Insets(4));

        fileLabel.getStyleClass().add("git-branch-label");
        fileLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fileLabel, Priority.ALWAYS);
        Button refresh = iconButton(Icons.refresh(), tr("history.refreshTip"), actions::refresh);
        HBox toolbar = new HBox(2, fileLabel, refresh);
        toolbar.getStyleClass().add("git-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        revisions.getStyleClass().add("git-tree");
        revisions.setCellFactory(v -> new RevisionCell());
        revisions.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);
        revisions.setPlaceholder(placeholder);
        VBox.setVgrow(revisions, Priority.ALWAYS);

        getChildren().setAll(toolbar, revisions);
    }

    private static Button iconButton(javafx.scene.Node icon, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon);
        b.getStyleClass().addAll("flat", "git-toolbar-button");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    /** Replaces the revision list. {@code fileName} = null/blank ⇒ "no file"; the list may be empty. */
    public void setRevisions(List<HistoryRevision> list, String fileName) {
        fileLabel.setText(
                fileName == null || fileName.isBlank() ? tr("history.noFile") : tr("history.forFile", fileName));
        revisions.getItems().setAll(list);
    }

    private void openSelected() {
        HistoryRevision r = revisions.getSelectionModel().getSelectedItem();
        if (r != null) {
            actions.openDiff(r);
        }
    }

    @Override
    public void focusFirstItem() {
        if (!revisions.getItems().isEmpty() && revisions.getSelectionModel().isEmpty()) {
            revisions.getSelectionModel().select(0);
            revisions.scrollTo(0);
        }
        revisions.requestFocus();
    }

    private final class RevisionCell extends ListCell<HistoryRevision> {
        @Override
        protected void updateItem(HistoryRevision r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                setContextMenu(null);
                return;
            }
            setGraphic(Icons.history());
            // Newest-first list ⇒ index 0 is the current (latest) version; tag it.
            String prefix = getIndex() == 0 ? tr("history.current") + "  ·  " : "";
            setText(prefix + absoluteTime(r.timestamp()) + "  ·  " + reasonLabel(r.reason()) + "  ·  "
                    + humanSize(r.sizeBytes()));
            setTooltip(new Tooltip(timeLabel(r.timestamp()) + "\n" + reasonLabel(r.reason())));
            MenuItem diff = item(tr("history.menu.diff"), Icons.diff(), () -> actions.openDiff(r));
            MenuItem restore = item(tr("history.menu.restore"), Icons.undo(), () -> actions.restore(r));
            setContextMenu(new ContextMenu(diff, restore));
        }
    }

    private static MenuItem item(String label, javafx.scene.Node icon, Runnable run) {
        MenuItem m = new MenuItem(label);
        if (icon != null) {
            m.setGraphic(icon);
        }
        m.setOnAction(e -> run.run());
        return m;
    }

    /** Localized "N minutes ago"-style label (reuses the blame relative-time bucketing + i18n keys). */
    private static String timeLabel(long epochMillis) {
        RelativeTime.Span span = RelativeTime.of(epochMillis / 1000, System.currentTimeMillis() / 1000);
        long v = span.value();
        return switch (span.unit()) {
            case NOW -> tr("blame.now");
            case MINUTES -> tr("blame.minutesAgo", v);
            case HOURS -> tr("blame.hoursAgo", v);
            case DAYS -> tr("blame.daysAgo", v);
            case WEEKS -> tr("blame.weeksAgo", v);
            case MONTHS -> tr("blame.monthsAgo", v);
            case YEARS -> tr("blame.yearsAgo", v);
        };
    }

    private static String absoluteTime(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault()));
    }

    private static String reasonLabel(String reason) {
        return switch (reason == null ? "" : reason) {
            case HistoryRevision.REASON_AUTOSAVE -> tr("history.reason.autosave");
            case HistoryRevision.REASON_EXTERNAL -> tr("history.reason.external");
            default -> tr("history.reason.save");
        };
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
