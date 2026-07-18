package com.editora.ui;

import java.util.List;

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
import javafx.scene.control.Tooltip;
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
    }

    private final Actions actions;
    private final Label filterLabel = new Label(tr("gitlog.all"));
    private final Button showAllButton;
    private final ListView<Commit> commits = new ListView<>();
    private final ListView<CommitFile> files = new ListView<>();
    private final SplitPane split = new SplitPane();
    private final Label placeholder = new Label(tr("gitlog.noCommits"));

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

        commits.getStyleClass().add("git-tree");
        commits.setCellFactory(v -> new CommitCell());
        commits.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            files.getItems().clear();
            if (now != null) {
                actions.selected(now.hash());
            }
        });

        files.getStyleClass().add("git-tree");
        files.setCellFactory(v -> new FileCell());
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

        getChildren().setAll(toolbar, split);
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
        commits.getItems().setAll(log);
        if (log.isEmpty()) {
            commits.setPlaceholder(placeholder);
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
        if (!commits.getItems().isEmpty() && commits.getSelectionModel().isEmpty()) {
            commits.getSelectionModel().select(0);
            commits.scrollTo(0);
        }
        commits.requestFocus();
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
                return;
            }
            // Color the row by its change status (matching the Commit tool window + Project tree), and use the
            // real file-type icon (FileIcons) instead of a generic sheet — the .git-tree .git-status-* CSS also
            // tints the icon (a .toolbar-icon).
            getStyleClass().add(GitFileStatus.fromLetter(f.status()).cssClass());
            setText(f.status() + "  " + f.path());
            setGraphic(FileIcons.forFileName(f.path()));
            setTooltip(new Tooltip(f.origPath() != null ? f.origPath() + " → " + f.path() : f.path()));
        }

        private void clearStatusClasses() {
            for (GitFileStatus s : GitFileStatus.values()) {
                getStyleClass().remove(s.cssClass());
            }
        }
    }
}
