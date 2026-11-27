package com.editora.ui;

import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.editor.LazyTooltip;
import com.editora.editor.TabContent;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/** A single review tab for every file section in a unified patch. */
final class PatchReviewPane implements TabContent {

    record Entry(@Nullable String label, String status, int additions, int deletions, DiffViewerPane pane) {
        Entry(String label, int additions, int deletions, DiffViewerPane pane) {
            this(label, "", additions, deletions, pane);
        }
    }

    /** Which files of the review are viewed: in memory by default, kept per repository for a Git review. */
    interface Viewed {
        boolean viewed(int index);

        void set(int index, boolean viewed);
    }

    /** Viewed state for this tab only. */
    static final class TabViewed implements Viewed {
        private final java.util.BitSet bits = new java.util.BitSet();

        @Override
        public boolean viewed(int index) {
            return bits.get(index);
        }

        @Override
        public void set(int index, boolean viewed) {
            bits.set(index, viewed);
        }
    }

    private Viewed viewed = new TabViewed();
    private final Label progress = new Label();

    private final String title;
    private final List<Entry> entries;
    private final BorderPane root = new BorderPane();
    private final SplitPane reviewSplit = new SplitPane();
    private final StackPane content = new StackPane();
    private final ListView<Entry> files = new ListView<>();
    private final Label position = new Label();
    private @Nullable HBox applyBar;

    PatchReviewPane(String title, List<Entry> entries) {
        this.title = title;
        // Source first, then tests, configuration, docs, and generated files last (each group as it came).
        List<Integer> order = com.editora.review.ReviewFileGroups.order(
                entries.stream().map(e -> e.label() == null ? "" : e.label()).toList());
        this.entries = order.stream().map(entries::get).toList();
        root.getStyleClass().add("patch-review");
        files.getItems().setAll(this.entries);
        files.getStyleClass().add("patch-file-list");
        files.setAccessibleText(title); // the list of the review's files, named for a screen reader (ACC-01)
        files.setCellFactory(v -> new FileCell());
        progress.getStyleClass().add("diff-summary");
        UiZoom.prefWidth(files, 245);
        UiZoom.minWidth(files, 170);
        files.getSelectionModel().selectedIndexProperty().addListener((o, old, next) -> show(next.intValue()));

        Button previous = button(Icons.arrowUp(), tr("diff.previousFile"), () -> selectRelative(-1));
        Button next = button(Icons.arrowDown(), tr("diff.nextFile"), () -> selectRelative(1));
        FocusCommands.press(root, "diff.previousFile", previous); // the review's file list, from the keyboard
        FocusCommands.press(root, "diff.nextFile", next);
        FocusCommands.bind(root, "diff.toggleViewed", this::toggleViewedOfShown); // the shown file, viewed or not
        position.getStyleClass().add("diff-summary");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox nav = UiZoom.spacing(new HBox(position, gap, progress, previous, next), 3);
        nav.setAlignment(Pos.CENTER_LEFT);
        UiZoom.padding(nav, 4, 6, 4, 6);
        nav.getStyleClass().add("diff-toolbar");
        VBox left = new VBox(nav, new Separator(), files);
        VBox.setVgrow(files, Priority.ALWAYS);
        left.getStyleClass().add("patch-review-sidebar");
        reviewSplit.getItems().addAll(left, content);
        SplitPane.setResizableWithParent(left, false);
        root.setCenter(reviewSplit);
        if (!this.entries.isEmpty()) {
            files.getSelectionModel().select(0);
        }
        refreshProgress();
    }

    /** Where the viewed state is kept (a Git review keeps it per repository, so a reopened review remembers it). */
    void setViewed(Viewed keeper) {
        this.viewed = keeper;
        files.refresh();
        refreshProgress();
    }

    /** Whether file {@code index} is marked viewed. */
    boolean isViewed(int index) {
        return viewed.viewed(index);
    }

    /**
     * Marks file {@code index} viewed or not; a file marked viewed while shown moves the review on to the next file
     * not viewed yet, as on GitHub.
     */
    void markViewed(int index, boolean now) {
        if (index < 0 || index >= entries.size()) {
            return;
        }
        viewed.set(index, now);
        files.refresh();
        refreshProgress();
        if (now && index == files.getSelectionModel().getSelectedIndex()) {
            for (int step = 1; step < entries.size(); step++) {
                int i = (index + step) % entries.size();
                if (!viewed.viewed(i)) {
                    files.getSelectionModel().select(i);
                    files.scrollTo(i);
                    return;
                }
            }
        }
    }

    /** Toggles the viewed mark of the file shown (the {@code diff.toggleViewed} command). */
    void toggleViewedOfShown() {
        int i = files.getSelectionModel().getSelectedIndex();
        markViewed(i, !viewed.viewed(i));
    }

    private void refreshProgress() {
        int n = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (viewed.viewed(i)) {
                n++;
            }
        }
        progress.setText(entries.isEmpty() ? "" : tr("diff.viewedProgress", n, entries.size()));
    }

    /**
     * Adds the "Apply to Working Tree" / "Apply to Index" pair above the review — for a tab that shows a
     * patch <em>file</em>, inside a repository. The other reviews (staged changes, a stash, a folder) show
     * what git already has and get no such bar.
     */
    void setApplyActions(Runnable toWorkingTree, Runnable toIndex) {
        Button worktree = new Button(tr("diff.patch.applyWorktree"));
        worktree.getStyleClass().add("accent");
        worktree.setOnAction(e -> toWorkingTree.run());
        Button index = new Button(tr("diff.patch.applyIndex"));
        index.setOnAction(e -> toIndex.run());
        FocusCommands.press(root, "diff.applyPatchToWorkingTree", worktree);
        FocusCommands.press(root, "diff.applyPatchToIndex", index);
        applyBar = UiZoom.padding(UiZoom.spacing(new HBox(worktree, index), 8), 6, 8, 6, 8);
        applyBar.setAlignment(Pos.CENTER_LEFT);
        applyBar.getStyleClass().addAll("diff-toolbar", "patch-apply-bar");
        root.setTop(applyBar);
    }

    /**
     * One action above the review, offered from the keyboard as {@code commandId} while the review has the focus:
     * Revert Turn on an agent's changes.
     */
    void setAction(String label, String commandId, Runnable action) {
        Button button = new Button(label);
        button.getStyleClass().add("accent");
        button.setOnAction(e -> action.run());
        FocusCommands.press(root, commandId, button);
        applyBar = UiZoom.padding(UiZoom.spacing(new HBox(button), 8), 6, 8, 6, 8);
        applyBar.setAlignment(Pos.CENTER_LEFT);
        applyBar.getStyleClass().addAll("diff-toolbar", "patch-apply-bar");
        root.setTop(applyBar);
    }

    /** The Apply bar's buttons (working tree, then index), or an empty list when there is none. */
    List<Button> applyButtons() {
        return applyBar == null
                ? List.of()
                : applyBar.getChildren().stream().map(Button.class::cast).toList();
    }

    /** The review's files, in the order shown. */
    List<Entry> entries() {
        return entries;
    }

    List<DiffViewerPane> panes() {
        return entries.stream().map(Entry::pane).toList();
    }

    @Nullable
    DiffViewerPane activePane() {
        int selected = files.getSelectionModel().getSelectedIndex();
        return selected >= 0 && selected < entries.size()
                ? entries.get(selected).pane()
                : null;
    }

    private void selectRelative(int delta) {
        if (entries.isEmpty()) {
            return;
        }
        int current = Math.max(0, files.getSelectionModel().getSelectedIndex());
        int next = Math.max(0, Math.min(entries.size() - 1, current + delta));
        files.getSelectionModel().select(next);
        files.scrollTo(next);
    }

    private void show(int index) {
        if (index < 0 || index >= entries.size()) {
            content.getChildren().clear();
            position.setText("");
            return;
        }
        content.getChildren().setAll(entries.get(index).pane().node());
        position.setText(tr("diff.filePosition", index + 1, entries.size()));
    }

    private com.editora.review.ReviewFileGroups.Group groupOf(int index) {
        Entry e = index >= 0 && index < entries.size() ? entries.get(index) : null;
        return com.editora.review.ReviewFileGroups.classify(e == null || e.label() == null ? "" : e.label());
    }

    /** Whether the files are of more than one kind: a review of one kind has no headings. */
    private boolean groupsShown() {
        return !entries.isEmpty() && groupOf(0) != groupOf(entries.size() - 1);
    }

    private static Button button(Node icon, String tip, Runnable action) {
        return Icons.toolbarButton(icon, tip, action, "flat", "diff-toolbar-button"); // tooltip + accessible name
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public Node icon() {
        return Icons.diff();
    }

    @Override
    public boolean hasUnsavedChanges() {
        return entries.stream().anyMatch(entry -> entry.pane().hasUnsavedChanges());
    }

    @Override
    public Object unsavedStateToken() {
        return entries.stream().map(entry -> entry.pane().unsavedStateToken()).toList();
    }

    @Override
    public boolean saveBeforeClose() {
        for (Entry entry : entries) {
            if (entry.pane().hasUnsavedChanges() && !entry.pane().saveBeforeClose()) {
                return false;
            }
        }
        return !hasUnsavedChanges();
    }

    @Override
    public String closeSaveActionKey() {
        return "diff.applyResult";
    }

    private final class FileCell extends ListCell<Entry> {
        @Override
        protected void updateItem(Entry entry, boolean empty) {
            super.updateItem(entry, empty);
            if (empty || entry == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            Label name = new Label(entry.label());
            name.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(name, Priority.ALWAYS);
            Label stat = new Label("+" + entry.additions() + "  −" + entry.deletions());
            stat.getStyleClass().add("diff-summary");
            Label status = new Label(entry.status());
            status.getStyleClass().add("patch-file-status");
            status.setVisible(!entry.status().isBlank());
            status.setManaged(!entry.status().isBlank());
            int index = getIndex();
            javafx.scene.control.CheckBox box = new javafx.scene.control.CheckBox();
            box.getStyleClass().add("patch-file-viewed");
            box.setSelected(viewed.viewed(index));
            box.setAccessibleText(tr("diff.viewedFile", entry.label()));
            box.setOnAction(e -> markViewed(index, box.isSelected()));
            LazyTooltip.install(box, () -> tr("diff.viewed"));
            HBox row = UiZoom.spacing(new HBox(box, status, name, stat), 8);
            if (box.isSelected()) {
                row.getStyleClass().add("patch-file-row-viewed");
            }
            row.setAlignment(Pos.CENTER_LEFT);
            com.editora.review.ReviewFileGroups.Group group = groupOf(index);
            if (group == com.editora.review.ReviewFileGroups.Group.GENERATED) {
                row.getStyleClass().add("patch-file-generated");
            }
            if (index >= 0 && (index == 0 || groupOf(index - 1) != group) && groupsShown()) {
                Label heading = new Label(tr(group.key()));
                heading.getStyleClass().add("patch-file-group");
                setGraphic(new VBox(heading, row)); // the first file of each kind is headed by the kind
            } else {
                setGraphic(row);
            }
            setText(null);
            setAccessibleText(entry.label()
                    + (entry.status().isBlank() ? "" : ", " + entry.status())
                    + ", plus "
                    + entry.additions()
                    + ", minus "
                    + entry.deletions());
        }
    }
}
