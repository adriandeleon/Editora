package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.config.HistoryRevision;
import com.editora.config.Settings;
import com.editora.diff.DiffEngine;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.git.RelativeTime;
import com.editora.history.HistoryQueries;

import static com.editora.i18n.Messages.tr;

/**
 * The Local File History tool window: a newest-first list of recorded versions of the active file on the
 * left, and a <b>live side-by-side diff</b> of the selected revision (the "before") against the current
 * file on the right — reusing {@link DiffViewerPane}, with ignore-whitespace / highlight-words toggles, a
 * "N differences" count, per-hunk apply chevrons (IntelliJ-style selective restore), and a whole-file
 * Revert. Everything lives in this one docked tool window (there is no separate history window). Like
 * {@link GitLogPanel} it is purely a view — the controller (via {@link Actions}/{@link DiffSupport}) owns
 * the history service and the buffers.
 */
public final class FileHistoryPanel extends VBox implements ToolWindowContent {

    /** Operations the panel asks the controller to perform. */
    public interface Actions {
        void refresh();

        void restore(HistoryRevision revision);

        /** Folder-history restore: write a revision's content back to its own path (recreating a deleted file). */
        void restoreToDisk(HistoryRevision revision);

        /** Prompt for and set (or clear) a label on this existing revision, so it can be found later by name. */
        void editLabel(HistoryRevision revision);
    }

    /**
     * The diff machinery the right pane needs, injected once by the coordinator (delegates to
     * {@code DiffCoordinator}/{@code HistoryService}). Kept separate from {@link Actions} so the panel stays
     * a view. {@code applyToLocal}/{@code undoLocal}/{@code saveLocal} back the per-hunk chevrons.
     */
    public interface DiffSupport {
        void fetchContent(HistoryRevision revision, Consumer<String> onText);

        void computeDiff(String left, String right, DiffEngine.DiffOptions opts, Consumer<DiffModel> onResult);

        String currentText(Path target);

        void revert(HistoryRevision revision);

        void applyToLocal(Path target, String newText);

        void undoLocal(Path target);

        void saveLocal(Path target);

        Settings settings();
    }

    /** One file in the folder-history view: its absolute path, a display name, deleted flag, and revisions. */
    public record FileGroup(String path, String display, boolean deleted, List<HistoryRevision> revisions) {}

    private final Actions actions;
    private DiffSupport support; // injected after construction

    // --- Left: revision list (single-file) / folder tree ---
    private final Label fileLabel = new Label(tr("history.noFile"));
    private final TextField filter = new TextField();
    private final ListView<HistoryRevision> revisions = new ListView<>();
    private final Label placeholder = new Label(tr("history.noRevisions"));
    private final List<HistoryRevision> allRevisions = new ArrayList<>();
    private final TreeView<Object> folderTree = new TreeView<>();

    // --- Right: the live diff of the selected revision vs the current file ---
    private final BorderPane rightPane = new BorderPane();
    private final Label headerInfo = new Label();
    private final Label diffCount = new Label();
    private final ToggleButton ignoreWs = new ToggleButton(tr("history.window.ignoreWhitespace"));
    private final ToggleButton wordLevel = new ToggleButton(tr("history.window.highlightWords"));
    private final Label diffPlaceholder = new Label(tr("history.window.selectPrompt"));

    private Path target; // the active file the revisions belong to (null in folder mode / no file)
    private DiffViewerPane pane; // built on the first diff result for the current file
    private String snapshotText = "";
    private String baseText = "";
    private int gen; // stale-guard for async re-diffs (selection / toggle can overlap)

    public FileHistoryPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("history-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(0);
        setPadding(Insets.EMPTY);

        // Left side --------------------------------------------------------------------------------------
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
        revisions.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                showRevision(b);
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

        VBox left = new VBox(4, toolbar, filter, revisions, folderTree);
        left.setPadding(new Insets(4));
        left.getStyleClass().add("history-window-left");
        left.setMinWidth(200);

        // Right side (diff) ------------------------------------------------------------------------------
        headerInfo.getStyleClass().add("history-window-header");
        diffCount.getStyleClass().add("history-window-count");
        ignoreWs.setTooltip(new Tooltip(tr("history.window.ignoreWhitespace")));
        wordLevel.setTooltip(new Tooltip(tr("history.window.highlightWords")));
        wordLevel.setSelected(true); // word-level emphasis on by default (matches the editor diff)
        ignoreWs.setOnAction(e -> recompute());
        wordLevel.setOnAction(e -> recompute());
        Button revert = new Button(tr("history.window.revert"));
        revert.setTooltip(new Tooltip(tr("history.window.revertTip")));
        revert.setOnAction(e -> revertSelected());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox diffToolbar = new HBox(8, headerInfo, spacer, diffCount, ignoreWs, wordLevel, revert);
        diffToolbar.setAlignment(Pos.CENTER_LEFT);
        diffToolbar.getStyleClass().add("history-window-toolbar");
        rightPane.setTop(diffToolbar);
        diffPlaceholder.getStyleClass().add("tool-window-placeholder");
        rightPane.setCenter(new StackPane(diffPlaceholder));

        SplitPane split = new SplitPane(left, rightPane);
        split.getStyleClass().add("history-window-split");
        Platform.runLater(() -> split.setDividerPositions(0.28));
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().setAll(split);
    }

    /** Injects the diff machinery (the coordinator calls this once, after construction). */
    public void setDiffSupport(DiffSupport support) {
        this.support = support;
    }

    /** Toggles between single-file list mode and folder-tree mode. */
    private void setFolderMode(boolean folder) {
        filter.setVisible(!folder);
        filter.setManaged(!folder);
        revisions.setVisible(!folder);
        revisions.setManaged(!folder);
        folderTree.setVisible(folder);
        folderTree.setManaged(folder);
        // Folder mode has no single-file diff (its revisions belong to different files) — show the prompt.
        rightPane.getTop().setDisable(folder);
        if (folder) {
            resetDiff();
        }
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
    public void setRevisions(List<HistoryRevision> list, String fileName, Path target) {
        setFolderMode(false);
        this.target = target;
        fileLabel.setText(
                fileName == null || fileName.isBlank() ? tr("history.noFile") : tr("history.forFile", fileName));
        allRevisions.clear();
        allRevisions.addAll(list);
        resetDiff(); // the previously-shown diff was for the old file (or old list) — clear it
        applyFilter();
    }

    /** Selects {@code revision} in the list (which shows its diff on the right); used by external entry points. */
    public void selectRevision(HistoryRevision revision) {
        if (revision != null && revisions.getItems().contains(revision)) {
            revisions.getSelectionModel().select(revision);
            revisions.scrollTo(revision);
        }
    }

    /** Clears the right diff pane back to the "select a revision" prompt (a fresh pane is built on next diff). */
    private void resetDiff() {
        gen++; // drop any in-flight async diff
        pane = null;
        headerInfo.setText("");
        diffCount.setText("");
        rightPane.setCenter(new StackPane(diffPlaceholder));
    }

    /** Shows folder-history mode: a file → revisions tree (deleted files badged), restorable via the menu. */
    public void setFolderHistory(String folderName, List<FileGroup> groups) {
        setFolderMode(true);
        this.target = null;
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

    // --- Diff (right pane) ------------------------------------------------------------------------------

    /** Loads the selected revision's snapshot + the current file text, then re-diffs. */
    private void showRevision(HistoryRevision rev) {
        if (support == null || target == null) {
            return;
        }
        headerInfo.setText(tr("history.window.header", absoluteTime(rev.timestamp())));
        baseText = support.currentText(target);
        support.fetchContent(rev, text -> {
            snapshotText = text == null ? "" : text;
            recompute();
        });
    }

    /** Re-diffs {@link #snapshotText} vs {@link #baseText} with the current toggle options (off-thread). */
    private void recompute() {
        if (support == null) {
            return;
        }
        int g = ++gen;
        DiffEngine.DiffOptions opts = new DiffEngine.DiffOptions(ignoreWs.isSelected(), wordLevel.isSelected());
        support.computeDiff(snapshotText, baseText, opts, model -> {
            if (g != gen) {
                return; // a newer selection/toggle superseded this result
            }
            if (model == null) {
                diffCount.setText(tr("history.window.tooLarge"));
                return;
            }
            diffCount.setText(
                    tr("history.window.differences", model.changeBlockStarts().size()));
            Settings s = support.settings();
            String fileName = target == null ? "" : target.getFileName().toString();
            if (pane == null) {
                pane = new DiffViewerPane(
                        fileName,
                        tr("history.window.beforeHeader"),
                        tr("history.window.currentHeader"),
                        fileName,
                        fileName,
                        snapshotText,
                        baseText,
                        model,
                        s.getFontFamily(),
                        s.getFontSize(),
                        s.isShowLineNumbers(),
                        target == null ? null : target.toString());
                // Per-hunk "apply change" chevrons on the current-file (right) side — IntelliJ-style
                // selective restore. Each apply writes the whole-file result through the undoable buffer,
                // then we re-diff so the remaining changes (and chevrons) update.
                Path t = target;
                pane.setEditable(
                        DiffViewerPane.EditableSide.RIGHT,
                        newText -> {
                            support.applyToLocal(t, newText);
                            reDiffAfterEdit();
                        },
                        () -> {
                            support.undoLocal(t);
                            reDiffAfterEdit();
                        },
                        () -> support.saveLocal(t));
                rightPane.setCenter(pane.node());
            } else {
                pane.updateContent(snapshotText, baseText, model);
            }
        });
    }

    /** After a per-hunk apply/undo changed the current file, re-baseline + re-diff (the editable side
     *  persists, so the remaining chevrons update). */
    private void reDiffAfterEdit() {
        Platform.runLater(() -> {
            if (target != null && support != null) {
                baseText = support.currentText(target);
                recompute();
            }
        });
    }

    private void revertSelected() {
        HistoryRevision sel = revisions.getSelectionModel().getSelectedItem();
        if (sel == null || support == null) {
            return;
        }
        support.revert(sel);
        // The editor buffer changed underneath → re-baseline and re-diff (now identical).
        Platform.runLater(() -> {
            if (target != null) {
                baseText = support.currentText(target);
                recompute();
            }
        });
    }

    // --- Filtering + focus ------------------------------------------------------------------------------

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
            MenuItem restore = item(tr("history.menu.restore"), Icons.undo(), () -> actions.restore(r));
            String labelText = r.label() == null || r.label().isBlank()
                    ? tr("history.menu.setLabel")
                    : tr("history.menu.editLabel");
            MenuItem editLabel = item(labelText, Icons.edit(), () -> actions.editLabel(r));
            setContextMenu(new ContextMenu(restore, editLabel));
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
