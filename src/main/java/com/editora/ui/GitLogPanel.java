package com.editora.ui;

import java.util.List;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.editora.git.GitFileStatus;
import com.editora.git.GitLog;
import com.editora.git.GitLog.Entry;
import com.editora.git.GitService.CommitFile;
import com.editora.git.RelativeTime;

import static com.editora.i18n.Messages.tr;

/**
 * The Git Log / History tool window: a commit list (the checked-out branch's history, or one file's) over
 * the selected commit's changed files. A commit row is one line — short hash, the branches and tags that
 * point at it, the subject (ellipsized), author and relative date — and a footer says when the list is
 * only the newest part of a longer history. Selecting a commit asks the controller (via {@link Actions}) to
 * fetch its files. In whole-repository mode, double-clicking a file opens its commit-vs-parent diff;
 * in file-history mode it compares that revision with the editable working file instead. A commit's
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

        /** Compare a commit's file with its working-tree copy so selected changes can be applied locally. */
        void compareFileWithWorking(String hash, String repoRelativePath);

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
    private final Label filterLabel = new Label(tr("gitlog.currentBranch"));
    private final Button showAllButton;
    private final TextField filterField = new TextField();
    /** Unfiltered commits; {@link #commits} shows a {@link FilteredList} view over this so filtering keeps object
     * identity (a selected commit survives re-filtering while it still matches). */
    private final ObservableList<Entry> allCommits = FXCollections.observableArrayList();

    private final FilteredList<Entry> filteredCommits = new FilteredList<>(allCommits, c -> true);
    private final ListView<Entry> commits = new ListView<>();
    /** Shown under the commit list when the load hit its limit: the list is not the whole history. */
    private final Label truncatedLabel = new Label();

    private final ListView<CommitFile> files = new ListView<>();
    private final SplitPane split = new SplitPane();
    private final Label placeholder = new Label(tr("gitlog.noCommits"));
    private boolean focusPending; // focusFirstItem() ran before the async log arrived
    private boolean fileHistoryMode;

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
        RowContextMenu.install(commits); // Menu key / Shift+F10 open the selected row's menu (cells are not focusable)
        installListNav(commits);
        commits.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            files.getItems().clear();
            if (now != null) {
                actions.selected(now.hash());
            }
        });

        truncatedLabel.getStyleClass().add("git-log-truncated");
        truncatedLabel.setMaxWidth(Double.MAX_VALUE);
        truncatedLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        truncatedLabel.setVisible(false);
        truncatedLabel.setManaged(false);
        VBox.setVgrow(commits, Priority.ALWAYS);
        VBox commitsBox = new VBox(commits, truncatedLabel);

        files.getStyleClass().add("git-tree");
        files.setCellFactory(v -> new FileCell());
        RowContextMenu.install(files); // the file rows' menu from the Menu key / Shift+F10, as for the commits
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
        split.getItems().setAll(commitsBox, files);
        split.setDividerPositions(0.6);
        VBox.setVgrow(split, Priority.ALWAYS);

        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);

        getChildren().setAll(toolbar, filterRow, split);
    }

    /** Case-insensitive substring filter over each commit's subject, author, short/long hash, date and refs. */
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
                || contains(c.date(), q)
                || c.refs().stream().anyMatch(r -> contains(r.name(), q)));
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
        return Icons.toolbarButton(icon, tip, action, "flat", "git-toolbar-button"); // tooltip + accessible name
    }

    /**
     * Replaces the commit list. {@code fileName} = null ⇒ the history of {@code branch} (the checked-out
     * branch — not every branch of the repository); else the history of that file.
     *
     * <p>A reload that brings the same commits back (the log is reloaded after every Git mutation, most of
     * which — staging, a fetch with nothing new — move nothing) leaves the list, its selection and the
     * changed-files pane alone. When the commits did change, the selected commit stays selected if it is
     * still listed.
     */
    public void setLog(GitLog.Page page, String fileName, String branch) {
        boolean filtered = fileName != null && !fileName.isBlank();
        boolean sameMode = fileHistoryMode == filtered;
        fileHistoryMode = filtered;
        filterLabel.setText(headerText(filtered ? fileName : null, branch));
        showAllButton.setVisible(filtered);
        showAllButton.setManaged(filtered);
        truncatedLabel.setText(
                page.truncated() ? tr("gitlog.truncated", page.entries().size()) : "");
        truncatedLabel.setVisible(page.truncated());
        truncatedLabel.setManaged(page.truncated());
        if (!sameMode || !allCommits.equals(page.entries())) {
            String selected = selectedHash();
            files.getItems().clear();
            // Populate the unfiltered master list; the FilteredList view re-applies the current filter.
            allCommits.setAll(page.entries());
            reselect(selected);
        }
        // A fresh open focuses the panel before the async log arrives — complete that focus now.
        if (focusPending && selectFirstCommit()) {
            focusPending = false;
            commits.requestFocus();
        }
    }

    /** Whole-branch log without a truncation notice — the shape most callers and tests have. */
    public void setLog(List<Entry> log, String fileName) {
        setLog(new GitLog.Page(log, false), fileName, "");
    }

    /** "History: Foo.java" for a file; "Commits on main" for the branch (never "all commits": it is one branch). */
    static String headerText(String fileName, String branch) {
        if (fileName != null) {
            return tr("gitlog.history", fileName);
        }
        return branch == null || branch.isBlank() ? tr("gitlog.currentBranch") : tr("gitlog.branch", branch);
    }

    /** Selects the commit {@code hash} again after the list was replaced, or nothing when it is gone. */
    private void reselect(String hash) {
        if (hash != null) {
            for (int i = 0; i < filteredCommits.size(); i++) {
                if (hash.equals(filteredCommits.get(i).hash())) {
                    commits.getSelectionModel().clearAndSelect(i);
                    return;
                }
            }
        }
        commits.getSelectionModel().clearSelection();
    }

    /** Pushes the selected commit's changed files (called by the controller after {@code commitFiles}). */
    public void setCommitFiles(List<CommitFile> commitFiles) {
        files.getItems().setAll(commitFiles);
    }

    /** The hash of the currently-selected commit, or {@code null} when none is selected (backs the palette commands). */
    public String selectedHash() {
        Entry c = commits.getSelectionModel().getSelectedItem();
        return c == null ? null : c.hash();
    }

    private void openSelectedFile() {
        Entry c = commits.getSelectionModel().getSelectedItem();
        CommitFile f = files.getSelectionModel().getSelectedItem();
        if (c != null && f != null) {
            if (fileHistoryMode) {
                actions.compareFileWithWorking(c.hash(), f.path());
            } else {
                actions.openFileDiff(c.hash(), f.path(), f.origPath());
            }
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

    /** How many ref chips a row shows before the rest collapse into a "+N" chip (all are in the tooltip). */
    static final int MAX_REF_CHIPS = 3;

    /**
     * How many ref chips fit a row {@code rowWidth} px wide: three in the bottom panel, fewer when the log is
     * docked narrow — the subject must keep room, and a chip squeezed to "…" says nothing.
     */
    static int chipBudget(double rowWidth) {
        if (rowWidth >= 640) {
            return MAX_REF_CHIPS;
        }
        return rowWidth >= 440 ? 2 : 1;
    }

    /** Whether a row {@code rowWidth} px wide has room for the author column beside the subject and date. */
    static boolean showsAuthor(double rowWidth) {
        return rowWidth >= 520;
    }

    /** "3 days ago" for a commit time; the short ISO date when git gave no usable timestamp. */
    static String relativeDate(Entry c, long nowSeconds) {
        if (c.epochSeconds() <= 0) {
            return c.date();
        }
        RelativeTime.Span span = RelativeTime.of(c.epochSeconds(), nowSeconds);
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

    /** The chip text of a ref: the name, with {@code HEAD →} in front of the checked-out branch. */
    static String refLabel(GitLog.Ref ref) {
        return ref.current() && ref.kind() != GitLog.RefKind.HEAD ? "HEAD → " + ref.name() : ref.name();
    }

    private static String refCssClass(GitLog.Ref ref) {
        if (ref.current()) {
            return "git-ref-head";
        }
        return switch (ref.kind()) {
            case HEAD -> "git-ref-head";
            case LOCAL -> "git-ref-local";
            case REMOTE -> "git-ref-remote";
            case TAG -> "git-ref-tag";
            case OTHER -> "git-ref-other";
        };
    }

    private static String commitTooltip(Entry c) {
        StringBuilder sb = new StringBuilder(c.subject());
        sb.append('\n').append(c.author()).append(" · ").append(c.date());
        if (c.epochSeconds() > 0) {
            sb.append(" (")
                    .append(relativeDate(c, System.currentTimeMillis() / 1000))
                    .append(')');
        }
        if (!c.refs().isEmpty()) {
            sb.append('\n');
            for (int i = 0; i < c.refs().size(); i++) {
                sb.append(i == 0 ? "" : ", ").append(refLabel(c.refs().get(i)));
            }
        }
        return sb.append('\n').append(c.hash()).toString();
    }

    /**
     * One commit, on one line: {@code hash [refs…] subject ………… author  date}. An {@code HBox} of labels —
     * the {@code TextFlow} this replaced could not ellipsize and made the list as wide as its longest
     * subject. The cell asks for no width of its own ({@code prefWidth 0}), so the list gives it exactly the
     * viewport's; the subject asks for none either and takes whatever the other columns leave, ellipsizing.
     * A narrow row drops the author and all but one or two ref chips ({@link #chipBudget},
     * {@link #showsAuthor}) rather than squeezing every column to an ellipsis. The one-line row height is CSS
     * ({@code .git-log-panel .git-tree .list-cell}).
     */
    private final class CommitCell extends ListCell<Entry> {
        private final Label hash = new Label();
        private final HBox refs = new HBox(4);
        private final Label subject = new Label();
        private final Label author = new Label();
        private final Label date = new Label();
        private final HBox row = new HBox(8, hash, refs, subject, author, date);
        private final Tooltip tooltip = new Tooltip();

        CommitCell() {
            setPrefWidth(0);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("git-log-row");
            // Color the short hash (accent) apart from the subject (default) so the log reads like a git graph.
            hash.getStyleClass().add("git-log-hash");
            hash.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            refs.setAlignment(Pos.CENTER_LEFT);
            refs.setFillHeight(false); // chips keep their own height: a pill, not a full-row block
            refs.setMinWidth(0);
            subject.getStyleClass().add("git-log-subject");
            subject.setMinWidth(60);
            subject.setPrefWidth(0);
            subject.setMaxWidth(Double.MAX_VALUE);
            subject.setTextOverrun(OverrunStyle.ELLIPSIS);
            HBox.setHgrow(subject, Priority.ALWAYS);
            author.getStyleClass().add("git-log-meta");
            author.setMinWidth(0);
            author.setMaxWidth(160);
            author.setTextOverrun(OverrunStyle.ELLIPSIS);
            date.getStyleClass().add("git-log-meta");
            date.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            // Only when a threshold is crossed (two per row at most while a divider is dragged).
            widthProperty().addListener((o, was, now) -> {
                if (!isEmpty()
                        && (chipBudget(was.doubleValue()) != chipBudget(now.doubleValue())
                                || showsAuthor(was.doubleValue()) != showsAuthor(now.doubleValue()))) {
                    fitToWidth(getItem());
                }
            });
        }

        /** Shows as many ref chips, and the author, as the row's current width has room for. */
        private void fitToWidth(Entry c) {
            double width = getWidth() > 0 ? getWidth() : commits.getWidth();
            refs.getChildren().clear();
            int shown = Math.min(c.refs().size(), chipBudget(width));
            for (int i = 0; i < shown; i++) {
                GitLog.Ref ref = c.refs().get(i);
                refs.getChildren().add(chip(refLabel(ref), refCssClass(ref)));
            }
            if (c.refs().size() > shown) {
                refs.getChildren().add(chip("+" + (c.refs().size() - shown), "git-ref-other"));
            }
            refs.setManaged(!c.refs().isEmpty());
            refs.setVisible(!c.refs().isEmpty());
            boolean author = showsAuthor(width);
            this.author.setManaged(author);
            this.author.setVisible(author);
        }

        @Override
        protected void updateItem(Entry c, boolean empty) {
            super.updateItem(c, empty);
            setText(null);
            if (empty || c == null) {
                setGraphic(null);
                setTooltip(null);
                setContextMenu(null);
                return;
            }
            hash.setText(c.shortHash());
            subject.setText(c.subject());
            author.setText(c.author());
            date.setText(relativeDate(c, System.currentTimeMillis() / 1000));
            fitToWidth(c);
            setGraphic(row);
            tooltip.setText(commitTooltip(c));
            setTooltip(tooltip);
            setContextMenu(buildMenu(c));
        }

        private Label chip(String text, String kindClass) {
            Label chip = new Label(text);
            chip.getStyleClass().addAll("git-ref", kindClass);
            chip.setMinWidth(0);
            chip.setMaxWidth(180);
            chip.setTextOverrun(OverrunStyle.ELLIPSIS);
            return chip;
        }

        private ContextMenu buildMenu(Entry c) {
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
                Entry c = commits.getSelectionModel().getSelectedItem();
                if (c != null) {
                    actions.openFileDiff(c.hash(), path, f.origPath());
                }
            });
            MenuItem compareWorking = item(tr("gitlog.menu.compareWorking"), Icons.merge(), () -> {
                Entry c = commits.getSelectionModel().getSelectedItem();
                if (c != null) {
                    actions.compareFileWithWorking(c.hash(), path);
                }
            });
            MenuItem open = item(tr("gitlog.menu.openFile"), Icons.fileSheet(), () -> actions.openFile(path));
            MenuItem history = item(tr("gitlog.menu.fileHistory"), Icons.gitLog(), () -> actions.showFileHistory(path));
            MenuItem copy = item(tr("gitlog.menu.copyPath"), Icons.copy(), () -> actions.copyPath(path));
            // A deleted file has no working-tree copy to open.
            open.setDisable(GitFileStatus.fromLetter(f.status()) == GitFileStatus.DELETED);
            return new ContextMenu(diff, compareWorking, open, history, copy);
        }

        private void clearStatusClasses() {
            for (GitFileStatus s : GitFileStatus.values()) {
                getStyleClass().remove(s.cssClass());
            }
        }
    }
}
