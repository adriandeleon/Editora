package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.editora.config.HistoryRevision;
import com.editora.git.RelativeTime;
import com.editora.history.HistoryQueries;

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

        /** Folder-history restore: write a revision's content back to its own path (recreating a deleted file). */
        void restoreToDisk(HistoryRevision revision);
    }

    /** One file in the folder-history view: its absolute path, a display name, deleted flag, and revisions. */
    public record FileGroup(String path, String display, boolean deleted, List<HistoryRevision> revisions) {}

    private final Actions actions;
    private final Label fileLabel = new Label(tr("history.noFile"));
    private final TextField filter = new TextField();
    private final ListView<HistoryRevision> revisions = new ListView<>();
    private final Label placeholder = new Label(tr("history.noRevisions"));
    /** The full unfiltered revision list for the active file (the {@code revisions} list is the filtered view). */
    private final List<HistoryRevision> allRevisions = new ArrayList<>();
    /** Folder-history mode: a file → revisions tree (shown instead of the single-file list). */
    private final TreeView<Object> folderTree = new TreeView<>();

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

        filter.setPromptText(tr("history.filterPrompt"));
        filter.getStyleClass().add("history-filter");
        filter.setMaxWidth(Double.MAX_VALUE);
        filter.textProperty().addListener((o, a, b) -> applyFilter());

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

        folderTree.getStyleClass().add("git-tree");
        folderTree.setShowRoot(false);
        folderTree.setCellFactory(t -> new FolderCell());
        VBox.setVgrow(folderTree, Priority.ALWAYS);
        folderTree.setVisible(false);
        folderTree.setManaged(false);

        getChildren().setAll(toolbar, filter, revisions, folderTree);
    }

    /** Toggles between single-file list mode and folder-tree mode. */
    private void setFolderMode(boolean folder) {
        filter.setVisible(!folder);
        filter.setManaged(!folder);
        revisions.setVisible(!folder);
        revisions.setManaged(!folder);
        folderTree.setVisible(folder);
        folderTree.setManaged(folder);
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

    /** Replaces the revision list (single-file mode). {@code fileName} = null/blank ⇒ "no file". */
    public void setRevisions(List<HistoryRevision> list, String fileName) {
        setFolderMode(false);
        fileLabel.setText(
                fileName == null || fileName.isBlank() ? tr("history.noFile") : tr("history.forFile", fileName));
        allRevisions.clear();
        allRevisions.addAll(list);
        applyFilter();
    }

    /** Shows folder-history mode: a file → revisions tree (deleted files badged), restorable via the menu. */
    public void setFolderHistory(String folderName, List<FileGroup> groups) {
        setFolderMode(true);
        fileLabel.setText(tr("history.forFolder", folderName == null ? "" : folderName));
        TreeItem<Object> root = new TreeItem<>();
        for (FileGroup g : groups) {
            TreeItem<Object> fileNode = new TreeItem<>(g);
            for (HistoryRevision r : g.revisions()) {
                fileNode.getChildren().add(new TreeItem<>(r));
            }
            root.getChildren().add(fileNode);
        }
        folderTree.setRoot(root);
    }

    /** Re-applies the filter field over {@link #allRevisions}: matches label/reason ({@code HistoryQueries})
     *  or the formatted timestamp; blank query shows everything. */
    private void applyFilter() {
        String q = filter.getText();
        if (q == null || q.isBlank()) {
            revisions.getItems().setAll(allRevisions);
            return;
        }
        String lower = q.toLowerCase(Locale.ROOT).strip();
        List<HistoryRevision> shown = new ArrayList<>();
        for (HistoryRevision r : allRevisions) {
            if (HistoryQueries.matches(r, q)
                    || absoluteTime(r.timestamp()).toLowerCase(Locale.ROOT).contains(lower)) {
                shown.add(r);
            }
        }
        revisions.getItems().setAll(shown);
    }

    private void openSelected() {
        HistoryRevision r = revisions.getSelectionModel().getSelectedItem();
        if (r != null) {
            actions.openDiff(r);
        }
    }

    @Override
    public void focusFirstItem() {
        if (folderTree.isManaged()) {
            folderTree.requestFocus();
            return;
        }
        if (!revisions.getItems().isEmpty() && revisions.getSelectionModel().isEmpty()) {
            revisions.getSelectionModel().select(0);
            revisions.scrollTo(0);
        }
        revisions.requestFocus();
    }

    /** Tree cell for folder mode: a file row (deleted = strikethrough + badge) or a revision row. */
    private final class FolderCell extends TreeCell<Object> {
        @Override
        protected void updateItem(Object value, boolean empty) {
            super.updateItem(value, empty);
            getStyleClass().remove("history-deleted-file");
            if (empty || value == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            if (value instanceof FileGroup g) {
                setGraphic(FileIcons.forFileName(Path.of(g.path()).getFileName().toString()));
                setText(g.deleted() ? g.display() + "  ·  " + tr("history.deleted") : g.display());
                if (g.deleted()) {
                    getStyleClass().add("history-deleted-file");
                }
                setContextMenu(null);
            } else if (value instanceof HistoryRevision r) {
                setGraphic(Icons.history());
                String tag = r.label() != null && !r.label().isBlank() ? r.label() : reasonLabel(r.reason());
                setText(absoluteTime(r.timestamp()) + "  ·  " + tag + "  ·  " + humanSize(r.sizeBytes()));
                MenuItem restore = item(tr("history.menu.restore"), Icons.undo(), () -> actions.restoreToDisk(r));
                setContextMenu(new ContextMenu(restore));
            }
        }
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
            // Tag the actual newest revision (allRevisions[0]) as "Current" — robust under filtering, where
            // the filtered-view index 0 may not be the latest.
            boolean current = !allRevisions.isEmpty() && allRevisions.get(0) == r;
            String prefix = current ? tr("history.current") + "  ·  " : "";
            HBox box = new HBox(4);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getChildren().add(Icons.history());
            if (r.label() != null && !r.label().isBlank()) {
                Label lbl = new Label(r.label());
                lbl.getStyleClass().add("history-label");
                box.getChildren().add(lbl);
            }
            setGraphic(box);
            setText(prefix + absoluteTime(r.timestamp()) + "  ·  " + reasonLabel(r.reason()) + "  ·  "
                    + humanSize(r.sizeBytes()));
            setTooltip(new Tooltip(timeLabel(r.timestamp()) + "\n" + reasonLabel(r.reason())
                    + (r.label() != null && !r.label().isBlank() ? "\n" + r.label() : "")));
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

    static String absoluteTime(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(epochMillis), java.time.ZoneId.systemDefault()));
    }

    static String reasonLabel(String reason) {
        return switch (reason == null ? "" : reason) {
            case HistoryRevision.REASON_AUTOSAVE -> tr("history.reason.autosave");
            case HistoryRevision.REASON_EXTERNAL -> tr("history.reason.external");
            case HistoryRevision.REASON_LABEL -> tr("history.reason.label");
            case HistoryRevision.REASON_DELETE -> tr("history.reason.delete");
            default -> tr("history.reason.save");
        };
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
