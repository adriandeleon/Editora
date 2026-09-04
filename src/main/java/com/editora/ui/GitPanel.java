package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.git.GitFileStatus;
import com.editora.git.GitStatus;
import com.editora.git.GitStatus.FileEntry;

import static com.editora.i18n.Messages.tr;

/**
 * The Git (Commit) tool window: the active repository's changes grouped into <em>Staged</em>,
 * <em>Changes</em> (unstaged), and <em>Untracked</em>, with stage/unstage/discard actions and a
 * commit message box. Mirrors {@link BookmarksPanel}'s structure (a {@link TreeView} of rows that
 * route mutations back through an {@link Actions} callback so the controller — which knows the repo
 * root and which files are open — performs the actual {@code git} calls off-thread).
 *
 * <p>It is purely a view: it never shells out itself. The controller pushes a {@link GitStatus} via
 * {@link #setStatus} after each refresh.
 */
public final class GitPanel extends VBox implements ToolWindowContent {

    /**
     * Mutations the panel asks the controller to perform (all by repo-relative path). The
     * stage/unstage/discard operations take a <em>list</em> because the tree is multi-select: the
     * controller runs one {@code git} invocation over every path (and therefore one refresh) rather
     * than N of them.
     */
    public interface Actions {
        void open(String repoRelativePath);

        void stage(List<String> paths);

        void unstage(List<String> paths);

        /**
         * Reverts local changes: {@code tracked} paths are checked out from the index/HEAD and
         * {@code untracked} ones are deleted. Either list may be empty; the controller confirms once
         * for the whole set.
         */
        void discard(List<String> tracked, List<String> untracked);

        void stageAll();

        void commit(String message);

        void push();

        void refresh();

        /** Review all files on one side: staged changes or unstaged/untracked working changes. */
        void review(boolean staged);

        /** Show a diff for the row: {@code staged} → index↔HEAD, else worktree↔index. */
        void diff(String repoRelativePath, boolean staged);
    }

    /** Which group a file row sits under. */
    private enum Group {
        STAGED("gitpanel.group.staged"),
        MODIFIED("gitpanel.group.modified"),
        UNTRACKED("gitpanel.group.untracked");
        final String key;

        Group(String key) {
            this.key = key;
        }
    }

    private sealed interface Row permits GroupRow, FileRow {}

    private record GroupRow(Group group, int count) implements Row {}

    private record FileRow(Group group, FileEntry entry) implements Row {}

    private final Actions actions;
    private final TreeView<Row> tree = new TreeView<>();
    private final TextField filterField = new TextField();
    private final HBox filterBar;
    private final TextArea message = new TextArea();
    private final Button commitButton = new Button(tr("gitpanel.commit"));
    private final Label branchLabel = new Label();
    /** Push indicator: "↑N" (commits to push), "↑ publish" (no upstream), or "✓ pushed". */
    private final Label aheadLabel = new Label();

    private Button pushButton;
    private final MenuButton reviewButton;
    private final MenuItem reviewWorkingItem;
    private final MenuItem reviewStagedItem;
    /** The menu currently on screen, hidden before showing the next one (each is built per right-click,
     *  since its contents depend on the live selection). */
    private ContextMenu openMenu;

    private Runnable onGenerateCommitMessage = () -> {};
    /** Shown only while {@link #setAiAvailable} says AI Actions is enabled + reachable; sits in its own
     *  thin toolbar row directly above the commit message box (not the repo-wide header). */
    private final Button aiCommitButton =
            iconButton(Icons.aiGenerate(), tr("gitpanel.aiCommitTip"), () -> onGenerateCommitMessage.run());

    private final HBox messageToolbar = new HBox(aiCommitButton);

    /** The status last pushed by the controller, so a filter change can re-render without a fresh {@code
     *  git status} (filtering is a view, not a refresh). */
    private GitStatus lastStatus;

    private final StackPane placeholderPane;
    private final Label placeholder = new Label(tr("gitpanel.placeholder"));
    private final Button cloneButton = new Button(tr("gitpanel.clone"));
    private Runnable onClone = () -> {};

    public GitPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("git-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);
        setPadding(new Insets(4));

        // Header: current branch (ellipsized, takes remaining width) + compact icon buttons on the right.
        branchLabel.getStyleClass().add("git-branch-label");
        branchLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(branchLabel, Priority.ALWAYS);
        aheadLabel.getStyleClass().add("git-ahead");
        Button stageAll = iconButton(Icons.stageAll(), tr("gitpanel.stageAllTip"), actions::stageAll);
        reviewWorkingItem = new MenuItem(tr("gitpanel.reviewWorking"));
        reviewWorkingItem.setOnAction(e -> actions.review(false));
        reviewStagedItem = new MenuItem(tr("gitpanel.reviewStaged"));
        reviewStagedItem.setOnAction(e -> actions.review(true));
        reviewButton = new MenuButton();
        reviewButton.setGraphic(Icons.diff());
        reviewButton.getItems().setAll(reviewWorkingItem, reviewStagedItem);
        reviewButton.getStyleClass().addAll("flat", "git-toolbar-button");
        reviewButton.setFocusTraversable(false);
        reviewButton.setAccessibleText(tr("gitpanel.reviewTip"));
        reviewButton.setTooltip(new Tooltip(tr("gitpanel.reviewTip")));
        reviewButton.setDisable(true);
        pushButton = iconButton(Icons.gitPush(), tr("gitpanel.pushTip"), actions::push);
        Button refresh = iconButton(Icons.refresh(), tr("gitpanel.refreshTip"), actions::refresh);
        HBox header = new HBox(2, branchLabel, aheadLabel, reviewButton, stageAll, pushButton, refresh);
        header.getStyleClass().add("git-toolbar");
        header.setAlignment(Pos.CENTER_LEFT);

        // Filter/search row, mirroring the Bookmarks / Personal Notes tool windows (same style classes, the
        // same Down/Enter + C-n/C-p hand-off into the results via FilterFieldNav).
        filterField.setPromptText(tr("gitpanel.filterPrompt"));
        filterField.getStyleClass().add("bookmarks-filter");
        filterField.textProperty().addListener((o, w, n) -> renderFiles());
        FilterFieldNav.install(filterField, tree, this::openSelected);
        HBox.setHgrow(filterField, Priority.ALWAYS);
        Button clearFilter = new Button("✕");
        clearFilter.getStyleClass().add("project-filter-clear");
        clearFilter.setFocusTraversable(false);
        clearFilter.setTooltip(new Tooltip(tr("project.filterClear")));
        clearFilter.setOnAction(e -> {
            filterField.clear();
            filterField.requestFocus();
        });
        clearFilter.visibleProperty().bind(filterField.textProperty().isEmpty().not());
        clearFilter.managedProperty().bind(clearFilter.visibleProperty());
        filterBar = new HBox(6, filterField, clearFilter);
        filterBar.getStyleClass().add("project-filter-bar");
        filterBar.setAlignment(Pos.CENTER_LEFT);

        tree.setShowRoot(false);
        tree.getStyleClass().add("git-tree");
        tree.setCellFactory(t -> new GitCell());
        tree.addEventFilter(KeyEvent.KEY_PRESSED, this::onTreeKey);
        // Multi-select: Shift+Up/Down extends from the keyboard, Shift/Ctrl+click from the mouse, and the
        // context menu then acts on the whole selection (see actionTargets).
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        // Keyboard menu key / Shift+F10: the event targets the focused *TreeView* (cells aren't focusable),
        // so a cell handler never sees it — open the menu for the focused row instead. Mouse requests are
        // consumed by the cell, so they never reach here.
        tree.setOnContextMenuRequested(e -> {
            TreeItem<Row> focused =
                    tree.getFocusModel() == null ? null : tree.getFocusModel().getFocusedItem();
            if (focused != null && focused.getValue() instanceof FileRow) {
                showMenu(tree, focused, e.getScreenX(), e.getScreenY());
                e.consume();
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        aiCommitButton.setVisible(false); // hidden until setAiAvailable(true) — off by default
        aiCommitButton.setManaged(false);
        messageToolbar.setVisible(false);
        messageToolbar.setManaged(false);
        messageToolbar.setAlignment(Pos.CENTER_RIGHT);

        message.setPromptText(tr("gitpanel.commitPrompt"));
        message.getStyleClass().add("git-commit-message");
        message.setWrapText(true);
        message.setPrefRowCount(3);
        // Ctrl/Cmd+Enter commits, like most Git UIs.
        message.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER && (e.isControlDown() || e.isMetaDown())) {
                doCommit();
                e.consume();
            }
        });
        commitButton.setMaxWidth(Double.MAX_VALUE);
        commitButton.setDefaultButton(false);
        commitButton.setOnAction(e -> doCommit());

        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);
        cloneButton.getStyleClass().add("flat");
        cloneButton.setOnAction(e -> onClone.run());
        VBox emptyBox = new VBox(8, placeholder, cloneButton);
        emptyBox.setAlignment(Pos.CENTER);
        placeholderPane = new StackPane(emptyBox);
        VBox.setVgrow(placeholderPane, Priority.ALWAYS);

        getChildren().setAll(placeholderPane);
        // Start with no repo until the controller pushes a status.
        getProperties().put("git.header", header);
    }

    /** A compact, legible icon button for the panel toolbar (graphic + tooltip, no truncated text). */
    private static Button iconButton(javafx.scene.Node icon, String tip, Runnable action) {
        Button b = new Button();
        b.setGraphic(icon);
        b.getStyleClass().addAll("flat", "git-toolbar-button");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
    }

    private void doCommit() {
        String msg = message.getText() == null ? "" : message.getText().strip();
        if (!msg.isEmpty()) {
            actions.commit(msg);
        }
    }

    /** Clears the commit message (called by the controller after a successful commit). */
    public void clearMessage() {
        message.clear();
    }

    /** Sets the action run by the "Clone Repository…" button shown when there's no repo. */
    public void setOnClone(Runnable onClone) {
        this.onClone = onClone == null ? () -> {} : onClone;
    }

    /** Sets the action run by the header's "Generate Commit Message" (AI) button. */
    public void setOnGenerateCommitMessage(Runnable onGenerateCommitMessage) {
        this.onGenerateCommitMessage = onGenerateCommitMessage == null ? () -> {} : onGenerateCommitMessage;
    }

    /** Shows/hides the "Generate Commit Message" (AI) toolbar row above the message box — the effective
     *  gate (AI Actions enabled + a cached connectivity probe), pushed from the controller; never
     *  toggled per-selection/keystroke. */
    public void setAiAvailable(boolean available) {
        aiCommitButton.setVisible(available);
        aiCommitButton.setManaged(available);
        messageToolbar.setVisible(available);
        messageToolbar.setManaged(available);
    }

    /**
     * Rebuilds the panel from {@code status}. A {@code null} or non-repo status shows the
     * "Not a Git repository" placeholder and hides the commit UI.
     */
    public void setStatus(GitStatus status) {
        if (status == null || !status.isRepo()) {
            lastStatus = null;
            reviewButton.setDisable(true);
            reviewWorkingItem.setDisable(true);
            reviewStagedItem.setDisable(true);
            getChildren().setAll(placeholderPane);
            return;
        }
        lastStatus = status;
        branchLabel.setText("⎇ " + (status.branch().isBlank() ? "(detached)" : status.branch()));
        updatePushIndicator(status);

        // The commit affordances read the FULL status, never the filtered view: hiding a staged file behind
        // a filter must not disable Commit.
        boolean hasStaged = status.files().stream().anyMatch(FileEntry::staged);
        boolean hasWorking = status.files().stream().anyMatch(file -> file.unstaged() || file.untracked());
        reviewStagedItem.setDisable(!hasStaged);
        reviewWorkingItem.setDisable(!hasWorking);
        reviewButton.setDisable(!hasStaged && !hasWorking);
        commitButton.setDisable(!hasStaged);
        commitButton.setText(tr("gitpanel.commit"));
        // Nothing to summarize without a staged diff — grey it out instead of silently no-op'ing on click.
        aiCommitButton.setDisable(!hasStaged);

        renderFiles();
    }

    /**
     * (Re)builds the file tree from {@link #lastStatus} through the current filter, and swaps in the right
     * body — the tree, the "nothing to commit" note, or a "no files match" note when a filter is what
     * emptied it. Runs on each status push and on every filter keystroke; the filter never re-runs
     * {@code git status}.
     */
    private void renderFiles() {
        if (lastStatus == null) {
            return;
        }
        HBox header = (HBox) getProperties().get("git.header");
        String query = filterQuery();
        TreeItem<Row> root = new TreeItem<>();
        addGroup(root, Group.STAGED, matching(lastStatus.files().stream().filter(FileEntry::staged), query));
        addGroup(root, Group.MODIFIED, matching(lastStatus.files().stream().filter(FileEntry::unstaged), query));
        addGroup(root, Group.UNTRACKED, matching(lastStatus.files().stream().filter(FileEntry::untracked), query));
        tree.setRoot(root);

        if (root.getChildren().isEmpty()) {
            boolean filteredOut = !query.isEmpty() && !lastStatus.files().isEmpty();
            Label note = new Label(filteredOut ? tr("gitpanel.noMatches") : tr("gitpanel.clean"));
            note.getStyleClass().add("tool-window-placeholder");
            note.setWrapText(true);
            StackPane notePane = new StackPane(note);
            VBox.setVgrow(notePane, Priority.ALWAYS);
            // The filter bar stays even with nothing to show, or a filter that matches nothing would remove
            // the only control that can clear it.
            getChildren().setAll(header, filterBar, notePane, messageToolbar, message, commitButton);
        } else {
            getChildren().setAll(header, filterBar, tree, messageToolbar, message, commitButton);
        }
    }

    /** The filter text, normalized (lower-cased + stripped); empty when nothing is being filtered. */
    private String filterQuery() {
        String text = filterField.getText();
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * {@code files} narrowed to those whose repo-relative path contains {@code query} (case-insensitive).
     * A query that names a group ("staged", "untracked") is <em>not</em> special-cased here — the group's
     * own title is matched by {@link #addGroup}, which keeps that whole group.
     */
    private static List<FileEntry> matching(Stream<FileEntry> files, String query) {
        List<FileEntry> out = new ArrayList<>();
        files.forEach(f -> {
            if (query.isEmpty() || f.path().toLowerCase(Locale.ROOT).contains(query)) {
                out.add(f);
            }
        });
        return out;
    }

    /**
     * Updates the header push indicator + Push button emphasis from the branch's ahead/behind state:
     * {@code ↑N} when there are commits to push, {@code ↑ publish} when the branch has no upstream yet
     * (everything is unpushed), or {@code ✓ pushed} when up to date.
     */
    private void updatePushIndicator(GitStatus status) {
        boolean noUpstream = status.upstream() == null || status.upstream().isBlank();
        int ahead = status.ahead();
        int behind = status.behind();
        boolean needsPush = noUpstream || ahead > 0;

        String text;
        String tip;
        if (noUpstream) {
            text = tr("gitpanel.publish");
            tip = tr("gitpanel.publishTip");
        } else if (ahead > 0 && behind > 0) {
            text = "↑" + ahead + " ↓" + behind;
            tip = tr("gitpanel.pushPull", ahead, behind);
        } else if (ahead > 0) {
            text = "↑" + ahead;
            tip = tr(ahead == 1 ? "gitpanel.toPush.one" : "gitpanel.toPush.many", ahead);
        } else if (behind > 0) {
            text = "↓" + behind;
            tip = tr(behind == 1 ? "gitpanel.toPull.one" : "gitpanel.toPull.many", behind);
        } else {
            text = tr("gitpanel.pushed");
            tip = tr("gitpanel.upToDate", status.upstream());
        }
        aheadLabel.setText(text);
        aheadLabel.setTooltip(new Tooltip(tip));
        aheadLabel.getStyleClass().removeAll("git-ahead-active", "git-ahead-clean");
        aheadLabel.getStyleClass().add(needsPush ? "git-ahead-active" : "git-ahead-clean");
        pushButton.getStyleClass().remove("git-needs-push");
        if (needsPush) {
            pushButton.getStyleClass().add("git-needs-push");
        }
    }

    private void addGroup(TreeItem<Row> root, Group group, List<FileEntry> files) {
        // A query naming the group itself ("untracked") lists that whole group, even when no path matches.
        String query = filterQuery();
        if (!query.isEmpty()
                && files.isEmpty()
                && lastStatus != null
                && tr(group.key).toLowerCase(Locale.ROOT).contains(query)) {
            files = allIn(group);
        }
        if (files.isEmpty()) {
            return;
        }
        TreeItem<Row> node = new TreeItem<>(new GroupRow(group, files.size()));
        node.setExpanded(true);
        for (FileEntry f : files) {
            node.getChildren().add(new TreeItem<>(new FileRow(group, f)));
        }
        root.getChildren().add(node);
    }

    /** Every file of {@code group} in the last status, ignoring the filter (the group-title match). */
    private List<FileEntry> allIn(Group group) {
        return switch (group) {
            case STAGED -> lastStatus.files().stream().filter(FileEntry::staged).toList();
            case MODIFIED ->
                lastStatus.files().stream().filter(FileEntry::unstaged).toList();
            case UNTRACKED ->
                lastStatus.files().stream().filter(FileEntry::untracked).toList();
        };
    }

    private void openSelected() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && item.getValue() instanceof FileRow f) {
            actions.open(f.entry().path());
        }
    }

    // --- keyboard navigation (mirrors BookmarksPanel) ---

    /**
     * Emacs navigation inside the tree: {@code C-n}/{@code C-p} move, {@code C-f}/{@code C-b} expand a group
     * or step out of it, {@code Enter}/{@code C-m} opens the selected file. The panel marks itself
     * {@code editora.ownsKeys}, so the global dispatcher leaves these chords to it — with no handler here
     * they were simply swallowed. Plain arrows (including Shift+arrow, which extends the selection) stay
     * with the {@link TreeView}.
     */
    private void onTreeKey(KeyEvent e) {
        if (e.getCode() == KeyCode.ENTER) {
            openSelected();
            e.consume();
            return;
        }
        if (!e.isControlDown()) {
            return;
        }
        switch (e.getCode()) {
            case N -> {
                move(1);
                e.consume();
            }
            case P -> {
                move(-1);
                e.consume();
            }
            case F -> {
                expandOrDescend();
                e.consume();
            }
            case B -> {
                collapseOrAscend();
                e.consume();
            }
            case M -> {
                openSelected();
                e.consume();
            }
            default -> {}
        }
    }

    /**
     * Moves the selection by {@code delta} rows, wrapping. {@code clearAndSelect}, not {@code select} — the
     * tree is multi-select, where plain {@code select} <em>adds</em> a row, so each press would grow the
     * selection instead of moving it.
     */
    private void move(int delta) {
        int rows = tree.getExpandedItemCount();
        if (rows == 0) {
            return;
        }
        int idx = tree.getSelectionModel().getSelectedIndex();
        int next = idx < 0 ? (delta > 0 ? 0 : rows - 1) : Math.floorMod(idx + delta, rows);
        tree.getSelectionModel().clearAndSelect(next);
        tree.scrollTo(next);
    }

    private void expandOrDescend() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && !item.isLeaf() && !item.isExpanded()) {
            item.setExpanded(true);
        } else {
            move(1);
        }
    }

    private void collapseOrAscend() {
        TreeItem<Row> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            move(-1);
            return;
        }
        if (!item.isLeaf() && item.isExpanded()) {
            item.setExpanded(false);
        } else if (item.getParent() != null && item.getParent() != tree.getRoot()) {
            tree.getSelectionModel().clearAndSelect(tree.getRow(item.getParent()));
            tree.scrollTo(tree.getSelectionModel().getSelectedIndex());
        } else {
            move(-1);
        }
    }

    // --- multi-selection -------------------------------------------------------------------------

    /**
     * The rows a context-menu action applies to: the whole selection when the right-clicked row is part
     * of a multi-selection (so a Shift-selected run stages together), else just the clicked row — which
     * is then made the selection, the standard "right-click outside the selection re-selects" behavior.
     * Group rows are filtered out, so a Shift-range crossing a group header is harmless.
     */
    private List<FileRow> actionTargets(TreeItem<Row> clicked) {
        List<TreeItem<Row>> selected = tree.getSelectionModel().getSelectedItems();
        boolean clickedInSelection = clicked != null && selected.contains(clicked);
        if (clickedInSelection) {
            List<FileRow> rows = fileRows(selected);
            if (rows.size() > 1) {
                return rows;
            }
        }
        if (clicked == null || !(clicked.getValue() instanceof FileRow f)) {
            return List.of();
        }
        tree.getSelectionModel().clearSelection();
        tree.getSelectionModel().select(clicked);
        return List.of(f);
    }

    /** The file rows among {@code items} (group rows and nulls dropped), in selection order. */
    private static List<FileRow> fileRows(List<TreeItem<Row>> items) {
        List<FileRow> rows = new ArrayList<>();
        for (TreeItem<Row> item : items) {
            if (item != null && item.getValue() instanceof FileRow f) {
                rows.add(f);
            }
        }
        return rows;
    }

    /** The currently selected file rows (used by the palette {@code git.stageSelected}/{@code unstageSelected}). */
    private List<FileRow> selectedFileRows() {
        return fileRows(tree.getSelectionModel().getSelectedItems());
    }

    /**
     * Stages every selected row that isn't already staged. Returns false (and does nothing) when the
     * selection holds no such row, so the caller can echo why.
     */
    public boolean stageSelected() {
        List<String> paths = paths(selectedFileRows(), false);
        if (paths.isEmpty()) {
            return false;
        }
        actions.stage(paths);
        return true;
    }

    /** Unstages every selected staged row; mirrors {@link #stageSelected}. */
    public boolean unstageSelected() {
        List<String> paths = paths(selectedFileRows(), true);
        if (paths.isEmpty()) {
            return false;
        }
        actions.unstage(paths);
        return true;
    }

    /** The distinct paths of the rows on the given side of the staged divide (a file can be in both). */
    private static List<String> paths(List<FileRow> rows, boolean staged) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (FileRow r : rows) {
            if ((r.group() == Group.STAGED) == staged) {
                out.add(r.entry().path());
            }
        }
        return List.copyOf(out);
    }

    /** Builds and shows the context menu for {@code clicked}'s action targets at the given screen point. */
    private void showMenu(Node anchor, TreeItem<Row> clicked, double screenX, double screenY) {
        List<FileRow> targets = actionTargets(clicked);
        if (targets.isEmpty()) {
            return;
        }
        if (openMenu != null) {
            openMenu.hide();
        }
        openMenu = buildMenu(targets);
        openMenu.show(anchor, screenX, screenY);
    }

    /**
     * The context menu for {@code targets}. Open/Show Diff are single-row actions; stage, unstage and
     * discard each appear when at least one target is on their side of the staged divide, so a mixed
     * selection offers both Stage and Unstage — each acting only on the rows it applies to.
     */
    private ContextMenu buildMenu(List<FileRow> targets) {
        ContextMenu menu = new ContextMenu();
        if (targets.size() == 1) {
            FileRow only = targets.get(0);
            FileEntry e = only.entry();
            MenuItem open = new MenuItem(tr("gitpanel.menu.open"));
            open.setGraphic(Icons.fileSheet());
            open.setOnAction(a -> actions.open(e.path()));
            menu.getItems().add(open);
            if (!e.untracked()) { // an untracked file has no committed/index version to diff against
                MenuItem showDiff = new MenuItem(tr("gitpanel.menu.showDiff"));
                showDiff.setGraphic(Icons.diff());
                boolean staged = only.group() == Group.STAGED;
                showDiff.setOnAction(a -> actions.diff(e.path(), staged));
                menu.getItems().add(showDiff);
            }
        }
        List<String> toStage = paths(targets, false);
        if (!toStage.isEmpty()) {
            MenuItem stage = new MenuItem(
                    toStage.size() == 1 ? tr("gitpanel.menu.stage") : tr("gitpanel.menu.stageMany", toStage.size()));
            stage.setGraphic(Icons.stageAll());
            stage.setOnAction(a -> actions.stage(toStage));
            menu.getItems().add(stage);
        }
        List<String> toUnstage = paths(targets, true);
        if (!toUnstage.isEmpty()) {
            MenuItem unstage = new MenuItem(
                    toUnstage.size() == 1
                            ? tr("gitpanel.menu.unstage")
                            : tr("gitpanel.menu.unstageMany", toUnstage.size()));
            unstage.setGraphic(Icons.remove());
            unstage.setOnAction(a -> actions.unstage(toUnstage));
            menu.getItems().add(unstage);
        }
        addDiscardItem(menu, targets);
        return menu;
    }

    /** Adds the Discard/Delete item for the non-staged targets (nothing when there are none). */
    private void addDiscardItem(ContextMenu menu, List<FileRow> targets) {
        LinkedHashSet<String> tracked = new LinkedHashSet<>();
        LinkedHashSet<String> untracked = new LinkedHashSet<>();
        for (FileRow r : targets) {
            if (r.group() == Group.STAGED) {
                continue;
            }
            (r.entry().untracked() ? untracked : tracked).add(r.entry().path());
        }
        int total = tracked.size() + untracked.size();
        if (total == 0) {
            return;
        }
        String label;
        if (total == 1) {
            label = tracked.isEmpty() ? tr("gitpanel.menu.deleteUntracked") : tr("gitpanel.menu.discard");
        } else {
            label = tracked.isEmpty()
                    ? tr("gitpanel.menu.deleteUntrackedMany", total)
                    : tr("gitpanel.menu.discardMany", total);
        }
        List<String> trackedPaths = List.copyOf(tracked);
        List<String> untrackedPaths = List.copyOf(untracked);
        MenuItem discard = new MenuItem(label);
        discard.setGraphic(Icons.trash());
        discard.setOnAction(a -> actions.discard(trackedPaths, untrackedPaths));
        menu.getItems().add(discard);
    }

    // --- ToolWindowContent ---

    public void focusContent() {
        if (tree.getRoot() != null && !tree.getRoot().getChildren().isEmpty()) {
            filterField.requestFocus();
        } else {
            message.requestFocus();
        }
    }

    @Override
    public void focusFirstItem() {
        // Land on the filter field so a file can be typed for immediately; Down/Enter and C-n/C-p move into
        // and through the results (FilterFieldNav), as in the Project / Bookmarks / Notes windows.
        if (tree.getExpandedItemCount() > 0 && tree.getSelectionModel().isEmpty()) {
            tree.getSelectionModel().select(0);
            tree.scrollTo(0);
        }
        focusContent();
    }

    /** Moves keyboard focus to the commit message box (for the commit command). */
    public void focusCommitMessage() {
        message.requestFocus();
    }

    /** Replaces the commit message box's content (AI-generated message; the user edits before committing). */
    public void setCommitMessage(String text) {
        message.setText(text == null ? "" : text);
        message.positionCaret(message.getLength());
    }

    /** The row's status letter — derived from the same {@link GitFileStatus} that colors the row, so the two
     *  can't disagree (picking the letter off one porcelain side alone rendered e.g. an index-modified,
     *  worktree-deleted file as "M" in the deleted/grey color). */
    private static String statusLetter(FileEntry e) {
        return GitFileStatus.of(e).letter();
    }

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** Style classes a Git cell may carry, cleared before each render (the group-row marker + the per-status
     *  color classes shared with the Project tree). */
    private static final String[] STATUS_CLASSES = {
        "git-group-row",
        "git-status-added",
        "git-status-modified",
        "git-status-deleted",
        "git-status-renamed",
        "git-status-untracked"
    };

    private final class GitCell extends TreeCell<Row> {

        GitCell() {
            // Built per request rather than stored via setContextMenu: the items depend on the current
            // selection, which changes long after updateItem last ran for this cell.
            setOnContextMenuRequested(e -> {
                if (getItem() instanceof FileRow) {
                    showMenu(this, getTreeItem(), e.getScreenX(), e.getScreenY());
                    e.consume();
                }
            });
        }

        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(STATUS_CLASSES); // includes "git-group-row" + the per-status colors
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (item instanceof GroupRow g) {
                setText(tr(g.group().key) + " (" + g.count() + ")");
                getStyleClass().add("git-group-row");
                setGraphic(null);
            } else if (item instanceof FileRow f) {
                FileEntry e = f.entry();
                // The status letter rides in the graphic, not the text, so it can be bold on its own.
                setText(f.entry().path());
                setGraphic(FileIcons.withStatusLetter(Icons.fileSheet(), statusLetter(e)));
                // Color the row by status (same palette as the Project tree) so the two windows match.
                getStyleClass().add(GitFileStatus.of(e).cssClass());
                setTooltip(new Tooltip(e.path()));
            }
        }
    }
}
