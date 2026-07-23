package com.editora.ui;

import java.util.List;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.git.GitFileStatus;
import com.editora.git.GitService.Commit;
import com.editora.git.GitService.CommitFile;

import static com.editora.i18n.Messages.tr;

/**
 * The Git Log / History tool window: a commit list (whole-repo or filtered to one file) over the
 * selected commit's changed files. Selecting a commit asks the controller (via {@link Actions}) to
 * fetch its files; double-clicking a file opens a read-only diff (commit vs parent). A commit's
 * context menu offers Copy Hash / Checkout / Reset / Revert / Cherry-Pick / New Branch. Like
 * {@link GitPanel} it is purely a view — the controller knows the repo root and runs {@code git}.
 */
public final class GitLogPanel extends VBox implements ToolWindowContent {

    /** Operations the panel asks the controller to perform (all by full commit hash). */
    public interface Actions {
        void refresh();

        void showAll();

        void selected(String hash);

        /** Diff a commit's file against its parent; {@code origRepoRelativePath} is the pre-rename path (or null). */
        void openFileDiff(String hash, String repoRelativePath, String origRepoRelativePath);

        void copyHash(String hash);

        void checkout(String hash);

        void reset(String hash, String mode); // "soft" | "mixed" | "hard"

        void revert(String hash);

        void cherryPick(String hash);

        void newBranch(String hash);

        /** Opens the working-tree copy of a commit's file in the editor. */
        void openFile(String repoRelativePath);

        /** Filters the log to that file's history. */
        void showFileHistory(String repoRelativePath);

        /** Copies the file's repo-relative path to the clipboard. */
        void copyPath(String repoRelativePath);
    }

    private final Actions actions;
    private final Label filterLabel = new Label(tr("gitlog.all"));
    private final Button showAllButton;
    private final TextField filterField = new TextField();
    /** Unfiltered commits; {@link #commits} shows a {@link FilteredList} view over this so filtering keeps object
     * identity (a selected commit survives re-filtering while it still matches). */
    private final ObservableList<Commit> allCommits = FXCollections.observableArrayList();

    private final FilteredList<Commit> filteredCommits = new FilteredList<>(allCommits, c -> true);
    private final ListView<Commit> commits = new ListView<>();
    private final ListView<CommitFile> files = new ListView<>();
    private final SplitPane split = new SplitPane();
    private final Label placeholder = new Label(tr("gitlog.noCommits"));
    private boolean focusPending; // focusFirstItem() ran before the async log arrived

    public GitLogPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("git-log-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);
        setPadding(new Insets(4));

        filterLabel.getStyleClass().add("git-branch-label");
        filterLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(filterLabel, Priority.ALWAYS);
        showAllButton = iconButton(Icons.gitLog(), tr("gitlog.showAllTip"), actions::showAll);
        showAllButton.setVisible(false);
        showAllButton.setManaged(false);
        Button refresh = iconButton(Icons.refresh(), tr("gitlog.refreshTip"), actions::refresh);
        HBox toolbar = new HBox(2, filterLabel, showAllButton, refresh);
        toolbar.getStyleClass().add("git-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Filter row: narrows the commit list as you type (subject / author / hash / date), with a trailing
        // clear ("✕") button. Typing here can't clash with the list's bare n/p nav — that handler is on the
        // commit ListView, not the field.
        filterField.setPromptText(tr("gitlog.filterPrompt"));
        filterField.getStyleClass().add("git-log-filter");
        filterField.textProperty().addListener((o, w, n) -> applyCommitFilter(n));
        HBox.setHgrow(filterField, Priority.ALWAYS);
        Button clearFilter = ClearableField.clearButton(filterField);
        HBox filterRow = new HBox(4, filterField, clearFilter);
        filterRow.getStyleClass().add("project-filter-bar");
        filterRow.setAlignment(Pos.CENTER_LEFT);

        commits.getStyleClass().add("git-tree");
        commits.setItems(filteredCommits);
        commits.setPlaceholder(placeholder);
        commits.setCellFactory(v -> new CommitCell());
        installListNav(commits);
        commits.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            files.getItems().clear();
            if (now != null) {
                actions.selected(now.hash());
            }
        });

        files.getStyleClass().add("git-tree");
        files.setCellFactory(v -> new FileCell());
        installListNav(files);
        files.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelectedFile();
                e.consume();
            }
        });
        files.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelectedFile();
            }
        });

        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.getItems().setAll(commits, files);
        split.setDividerPositions(0.6);
        VBox.setVgrow(split, Priority.ALWAYS);

        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);

        getChildren().setAll(toolbar, filterRow, split);
    }

    /** Case-insensitive substring filter over each commit's subject, author, short/long hash and date. */
    private void applyCommitFilter(String text) {
        String q = text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            filteredCommits.setPredicate(c -> true);
            return;
        }
        filteredCommits.setPredicate(c -> contains(c.subject(), q)
                || contains(c.author(), q)
                || contains(c.shortHash(), q)
                || contains(c.hash(), q)
                || contains(c.date(), q));
    }

    private static boolean contains(String s, String lowerQuery) {
        return s != null && s.toLowerCase(java.util.Locale.ROOT).contains(lowerQuery);
    }

    /**
     * Emacs-style {@code n}/{@code p} (bare, and with Control) move the selection — the lists hold no text
     * input, so the bare letters are free. Arrow keys keep working via the ListView's own behavior.
     */
    private static void installListNav(ListView<?> list) {
        list.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.isAltDown() || e.isMetaDown() || e.isShiftDown()) {
                return;
            }
            int delta =
                    switch (e.getCode()) {
                        case N -> 1;
                        case P -> -1;
                        default -> 0;
                    };
            if (delta == 0) {
                return;
            }
            int size = list.getItems().size();
            if (size > 0) {
                int i = Math.clamp(list.getSelectionModel().getSelectedIndex() + delta, 0, size - 1);
                list.getSelectionModel().clearAndSelect(i);
                list.scrollTo(i);
            }
            e.consume();
        });
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

    /** Replaces the commit list. {@code fileName} = null ⇒ whole-repo; else the filtered file's name. */
    public void setLog(List<Commit> log, String fileName) {
        boolean filtered = fileName != null && !fileName.isBlank();
        filterLabel.setText(filtered ? tr("gitlog.history", fileName) : tr("gitlog.all"));
        showAllButton.setVisible(filtered);
        showAllButton.setManaged(filtered);
        files.getItems().clear();
        // Populate the unfiltered master list; the FilteredList view re-applies the current filter automatically.
        allCommits.setAll(log);
        // A fresh open focuses the panel before the async log arrives — complete that focus now.
        if (focusPending && selectFirstCommit()) {
            focusPending = false;
            commits.requestFocus();
        }
    }

    /** Pushes the selected commit's changed files (called by the controller after {@code commitFiles}). */
    public void setCommitFiles(List<CommitFile> commitFiles) {
        files.getItems().setAll(commitFiles);
    }

    /** The hash of the currently-selected commit, or {@code null} when none is selected (backs the palette commands). */
    public String selectedHash() {
        Commit c = commits.getSelectionModel().getSelectedItem();
        return c == null ? null : c.hash();
    }

    private void openSelectedFile() {
        Commit c = commits.getSelectionModel().getSelectedItem();
        CommitFile f = files.getSelectionModel().getSelectedItem();
        if (c != null && f != null) {
            actions.openFileDiff(c.hash(), f.path(), f.origPath());
        }
    }

    @Override
    public void focusFirstItem() {
        // The log loads asynchronously, so on a fresh open the list is still empty here — remember that focus
        // was wanted and let setLog() finish the job when the commits arrive.
        focusPending = !selectFirstCommit();
        commits.requestFocus();
    }

    /** Selects the first commit when there is one and nothing is selected yet; false when the list is empty. */
    private boolean selectFirstCommit() {
        if (commits.getItems().isEmpty()) {
            return false;
        }
        if (commits.getSelectionModel().isEmpty()) {
            commits.getSelectionModel().select(0);
            commits.scrollTo(0);
        }
        return true;
    }

    private final class CommitCell extends ListCell<Commit> {
        @Override
        protected void updateItem(Commit c, boolean empty) {
            super.updateItem(c, empty);
            if (empty || c == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                setContextMenu(null);
                return;
            }
            // Color the short hash (accent) apart from the subject (default) so the log reads like a git graph.
            setText(null);
            Text hash = new Text(c.shortHash());
            hash.getStyleClass().add("git-log-hash");
            Text subject = new Text("  " + c.subject());
            subject.getStyleClass().add("git-log-subject");
            setGraphic(new TextFlow(hash, subject));
            setTooltip(new Tooltip(c.subject() + "\n" + c.author() + " · " + c.date() + "\n" + c.hash()));
            setContextMenu(buildMenu(c));
        }

        private ContextMenu buildMenu(Commit c) {
            String h = c.hash();
            MenuItem copy = item(tr("gitlog.menu.copyHash"), Icons.copy(), () -> actions.copyHash(h));
            MenuItem checkout = item(tr("gitlog.menu.checkout"), Icons.git(), () -> actions.checkout(h));
            MenuItem newBranch = item(tr("gitlog.menu.newBranch"), Icons.git(), () -> actions.newBranch(h));
            MenuItem revert = item(tr("gitlog.menu.revert"), Icons.refresh(), () -> actions.revert(h));
            MenuItem cherry = item(tr("gitlog.menu.cherryPick"), Icons.stageAll(), () -> actions.cherryPick(h));
            Menu reset = new Menu(tr("gitlog.menu.reset"));
            reset.setGraphic(Icons.refresh());
            reset.getItems()
                    .setAll(
                            item(tr("gitlog.menu.resetSoft"), null, () -> actions.reset(h, "soft")),
                            item(tr("gitlog.menu.resetMixed"), null, () -> actions.reset(h, "mixed")),
                            item(tr("gitlog.menu.resetHard"), null, () -> actions.reset(h, "hard")));
            return new ContextMenu(copy, checkout, newBranch, revert, cherry, reset);
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

    private final class FileCell extends ListCell<CommitFile> {
        @Override
        protected void updateItem(CommitFile f, boolean empty) {
            super.updateItem(f, empty);
            clearStatusClasses();
            if (empty || f == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                setContextMenu(null);
                return;
            }
            // Color the row by its change status (matching the Commit tool window + Project tree), and use the
            // real file-type icon (FileIcons) instead of a generic sheet — the .git-tree .git-status-* CSS also
            // tints the icon (a .toolbar-icon).
            getStyleClass().add(GitFileStatus.fromLetter(f.status()).cssClass());
            setText(f.status() + "  " + f.path());
            setGraphic(FileIcons.forFileName(f.path()));
            setTooltip(new Tooltip(f.origPath() != null ? f.origPath() + " → " + f.path() : f.path()));
            setContextMenu(buildMenu(f));
        }

        /** Show Diff / Open File / Show File History / Copy Path for the right-clicked file. */
        private ContextMenu buildMenu(CommitFile f) {
            String path = f.path();
            MenuItem diff = item(tr("gitlog.menu.showDiff"), Icons.diff(), () -> {
                Commit c = commits.getSelectionModel().getSelectedItem();
                if (c != null) {
                    actions.openFileDiff(c.hash(), path, f.origPath());
                }
            });
            MenuItem open = item(tr("gitlog.menu.openFile"), Icons.fileSheet(), () -> actions.openFile(path));
            MenuItem history = item(tr("gitlog.menu.fileHistory"), Icons.gitLog(), () -> actions.showFileHistory(path));
            MenuItem copy = item(tr("gitlog.menu.copyPath"), Icons.copy(), () -> actions.copyPath(path));
            // A deleted file has no working-tree copy to open.
            open.setDisable(GitFileStatus.fromLetter(f.status()) == GitFileStatus.DELETED);
            return new ContextMenu(diff, open, history, copy);
        }

        private void clearStatusClasses() {
            for (GitFileStatus s : GitFileStatus.values()) {
                getStyleClass().remove(s.cssClass());
            }
        }
    }
}
