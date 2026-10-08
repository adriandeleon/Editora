package com.editora.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import com.editora.git.GitFileStatus;
import com.editora.git.GitFormat;
import com.editora.git.GitGraph;
import com.editora.git.GitLog;
import com.editora.git.GitLog.Entry;
import com.editora.git.GitService.CommitFile;
import com.editora.git.RelativeTime;

import static com.editora.i18n.Messages.tr;

/**
 * The Git Log / History tool window: a commit list — the checked-out branch's history, every branch's, one
 * file's (followed across renames), or the result of a history search — over the selected commit's details
 * and changed files.
 *
 * <p>A commit row is one line: the graph, short hash, the branches and tags that point at the commit, the
 * subject (ellipsized), author and relative date. The list is loaded a page at a time; the next page is
 * asked for when the list is scrolled near its end or from the footer. The graph is laid out by the pure
 * {@link GitGraph} from the parent hashes and continues across pages; it is drawn for a plain history only
 * (a file history, a search or a filtered list shows a subset of commits, which has no graph).
 *
 * <p>The filter box narrows the <em>loaded</em> rows as you type; Enter runs the text as a search over the
 * whole history (see {@link com.editora.git.GitLogQuery}). Selecting a commit asks the controller (via
 * {@link Actions}) for its files and details. In whole-repository mode, double-clicking a file opens its
 * commit-vs-parent diff; in file-history mode it compares that revision with the editable working file
 * instead. Enter or a double-click on a commit opens the whole commit as one multi-file review — in a file
 * history, what the commit changed in that file, which is the question a file history is opened to answer;
 * with two commits selected it compares them. Like {@link GitPanel} it is purely a view — the controller
 * knows the repo root and runs {@code git}.
 *
 * <p>A file history is headed by a chip — "Git history: Foo.java ✕" — whose ✕ (or Escape in the commit
 * list) goes back to the branch's log. The toolbar buttons take keyboard focus.
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

        /** Loads the next page of the listing on screen. */
        void loadMore();

        /** Switches between the checked-out branch and every branch, remote and tag. */
        void toggleAllBranches();

        /** Searches the whole history ({@link com.editora.git.GitLogQuery}); a blank query ends the search. */
        void searchHistory(String query);

        /** Opens every file a commit changed as one review tab. */
        void reviewCommit(String hash);

        /** Opens the files that differ between two commits as one review tab. */
        void compareCommits(String olderHash, String newerHash);

        void newTag(String hash);

        /** Writes the commit as a patch ({@code git format-patch}), to a file or a new buffer. */
        default void createPatch(String hash) {}

        void checkoutTag(String tag);

        void pushTag(String tag);

        void deleteTag(String tag);

        /** A parent link of the details pane names a commit that is not among the loaded rows. */
        void commitNotLoaded(String hash);
    }

    /**
     * What the listing on screen is of — it decides the header, and whether a reload replaced the listing or
     * only refreshed it. {@code fileName} = null ⇒ the history of {@code branch} (or of every branch); else
     * the history of that file. {@code search} is the active history search ("" for none). {@code graph}:
     * the rows are a plain walk of the history, so a graph can be drawn for them.
     */
    public record View(String fileName, String branch, boolean allBranches, String search, boolean graph) {
        public View {
            fileName = fileName == null || fileName.isBlank() ? null : fileName;
            branch = branch == null ? "" : branch;
            search = search == null ? "" : search.strip();
        }

        static final View NONE = new View(null, "", false, "", false);

        boolean fileHistory() {
            return fileName != null;
        }

        /** Whether {@code other} lists the same commits (the branch name is a label, not a listing). */
        boolean sameListing(View other) {
            return other != null
                    && Objects.equals(fileName, other.fileName)
                    && allBranches == other.allBranches
                    && search.equals(other.search)
                    && graph == other.graph;
        }
    }

    /** How close to the end of the loaded rows a scroll must come for the next page to be asked for. */
    static final int LOAD_AHEAD_ROWS = 40;

    /** {@link #LOAD_AHEAD_ROWS}; 0 switches loading on scroll off (a test paging by hand). */
    int loadAheadRows = LOAD_AHEAD_ROWS;

    /** Width of one graph lane, and how many lanes the graph column shows before it is clipped. */
    static final double GRAPH_LANE_WIDTH = 12;

    static final int MAX_GRAPH_LANES = 14;

    private final Actions actions;
    private final Label filterLabel = new Label(tr("gitlog.currentBranch"));
    private final Button showAllButton;
    private final ToggleButton allBranchesButton = new ToggleButton();
    /** "Search: …" with its clear button, in the toolbar while a history search is the listing. */
    private final Label searchLabel = new Label();

    private final HBox searchChip = new HBox(2);
    private final TextField filterField = new TextField();
    /** Unfiltered commits; {@link #commits} shows a {@link FilteredList} view over this so filtering keeps object
     * identity (a selected commit survives re-filtering while it still matches). */
    private final ObservableList<Entry> allCommits = FXCollections.observableArrayList();

    private final FilteredList<Entry> filteredCommits = new FilteredList<>(allCommits, c -> true);
    private final ListView<Entry> commits = new ListView<>();
    /** Shown under the commit list while older history is not loaded yet, beside {@link #loadMoreButton}. */
    private final Label truncatedLabel = new Label();

    private final Button loadMoreButton = new Button(tr("gitlog.loadMore"));
    private final HBox footer = new HBox(8, truncatedLabel, loadMoreButton);
    /** The repository is a shallow clone: where the loaded history ends is where the clone was cut. */
    private boolean shallow;

    private View view = View.NONE;
    private boolean hasMore;
    private boolean loadingMore;
    /** The lane layout of {@link #allCommits}, row for row; null when the listing has no graph. */
    private GitGraph graph;

    private final List<GitGraph.Row> graphRows = new ArrayList<>();
    /** How many lanes the graph column is wide (0: no graph column). The same for every row. */
    private int graphColumns;
    /** In a file history: the file as each loaded commit knew it (its path there), by commit hash. */
    private final Map<String, CommitFile> followed = new HashMap<>();
    /** The commit menu now showing, hidden before another opens. */
    private ContextMenu openMenu;

    private final Label detailsHash = new Label();
    private final HBox detailsParents = new HBox(6);
    private final Label detailsAuthor = new Label();
    private final HBox detailsRefs = new HBox(4);
    /**
     * The commit message. A read-only text area rather than a label, so it can be selected and copied — with
     * the mouse or, focused, from the keyboard; it is as tall as its text ({@link #fitMessageHeight}).
     */
    private final TextArea detailsMessage = new TextArea();

    private final VBox details = new VBox(2);
    private final ScrollPane detailsScroll = new ScrollPane(details);
    /** The commit {@link #details} describes. */
    private String detailsOf;

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
        filterLabel.setMinWidth(0);
        filterLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        // The way back from a file history is a ✕ on its header, as on the search chip: an icon button
        // that looked like the tool window's own icon said nothing about where it led.
        showAllButton = new Button("✕");
        showAllButton.getStyleClass().add("project-filter-clear");
        showAllButton.setMinWidth(Region.USE_PREF_SIZE);
        Icons.name(showAllButton, tr("gitlog.showAllTip"));
        showAllButton.setOnAction(e -> actions.showAll());
        showAllButton.setVisible(false);
        showAllButton.setManaged(false);
        HBox header = new HBox(2, filterLabel, showAllButton);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMinWidth(0);
        HBox.setHgrow(header, Priority.ALWAYS);
        allBranchesButton.setGraphic(Icons.git());
        allBranchesButton.getStyleClass().addAll("flat", "git-toolbar-button");
        Icons.name(allBranchesButton, tr("gitlog.allBranchesTip"));
        // The button shows what is listed, not what was clicked: it follows the next setLog.
        allBranchesButton.setOnAction(e -> {
            allBranchesButton.setSelected(view.allBranches());
            actions.toggleAllBranches();
        });
        searchLabel.getStyleClass().add("git-log-search");
        searchLabel.setMinWidth(0);
        searchLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        Button endSearch = new Button("✕");
        endSearch.getStyleClass().add("project-filter-clear");
        Icons.name(endSearch, tr("gitlog.clearSearch"));
        endSearch.setOnAction(e -> actions.searchHistory(""));
        searchChip.getChildren().setAll(searchLabel, endSearch);
        searchChip.setAlignment(Pos.CENTER_LEFT);
        searchChip.setMinWidth(0);
        searchChip.setVisible(false);
        searchChip.setManaged(false);
        Button refresh = iconButton(Icons.refresh(), tr("gitlog.refreshTip"), actions::refresh);
        // Every toolbar control is a Tab stop: without a mouse there was no way to them at all.
        for (javafx.scene.control.ButtonBase button : List.of(showAllButton, endSearch, allBranchesButton, refresh)) {
            button.setFocusTraversable(true);
        }
        HBox toolbar = new HBox(2, header, searchChip, allBranchesButton, refresh);
        toolbar.getStyleClass().add("git-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Filter row: narrows the loaded commits as you type (subject / author / hash / date / ref), with a
        // trailing clear ("✕") button; Enter (or the search button) runs the text over the whole history.
        // Typing here can't clash with the list's bare n/p nav — that handler is on the commit ListView.
        filterField.setPromptText(tr("gitlog.filterPrompt"));
        filterField.getStyleClass().add("git-log-filter");
        filterField.textProperty().addListener((o, w, n) -> applyCommitFilter(n));
        filterField.setOnAction(e -> searchHistory());
        filterField.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            // Escape steps back once: first the typed filter, then the history search.
            if (e.getCode() == KeyCode.ESCAPE) {
                if (!filterField.getText().isEmpty()) {
                    filterField.clear();
                    e.consume();
                } else if (!view.search().isEmpty()) {
                    actions.searchHistory("");
                    e.consume();
                }
            }
        });
        HBox.setHgrow(filterField, Priority.ALWAYS);
        Button clearFilter = ClearableField.clearButton(filterField);
        Button search = iconButton(Icons.find(), tr("gitlog.searchTip"), this::searchHistory);
        HBox filterRow = new HBox(4, filterField, clearFilter, search);
        filterRow.getStyleClass().add("project-filter-bar");
        filterRow.setAlignment(Pos.CENTER_LEFT);

        commits.getStyleClass().add("git-tree");
        commits.setItems(filteredCommits);
        commits.setPlaceholder(placeholder);
        commits.setCellFactory(v -> new CommitCell());
        // Two rows can be selected to compare them; everything else acts on the row selected last.
        commits.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        RowContextMenu.install(commits); // Menu key / Shift+F10 open the selected row's menu (cells are not focusable)
        installListNav(commits);
        commits.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            files.getItems().clear();
            showDetails(now);
            if (now != null) {
                actions.selected(now.hash());
            }
        });
        commits.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ENTER) {
                openSelectedCommits();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE && view.fileHistory()) {
                actions.showAll(); // out of the file history, back to the branch's log
                e.consume();
            }
        });
        commits.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelectedCommits();
            }
        });

        truncatedLabel.getStyleClass().add("git-log-truncated");
        truncatedLabel.setMinWidth(0);
        truncatedLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        loadMoreButton.getStyleClass().addAll("flat", "git-log-load-more");
        loadMoreButton.setFocusTraversable(false);
        loadMoreButton.setMinWidth(Region.USE_PREF_SIZE);
        loadMoreButton.setOnAction(e -> requestMore());
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.getStyleClass().add("git-log-footer");
        footer.setVisible(false);
        footer.setManaged(false);
        VBox.setVgrow(commits, Priority.ALWAYS);
        VBox commitsBox = new VBox(commits, footer);

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
        // A small preferred height, grown to the space there is: a VBox short of room shrinks all of its
        // children alike, and a list asking for its default 400 px squeezed the details pane to nothing.
        files.setMinHeight(40);
        files.setPrefHeight(60);
        VBox.setVgrow(files, Priority.ALWAYS);

        // The selected commit, above its files: full hash (copyable), parents (links to their rows), author
        // and e-mail, absolute and relative date, refs, and the whole message. It takes the height it needs,
        // up to half of the lower pane, and scrolls beyond that — the file list always keeps room.
        detailsHash.getStyleClass().add("git-log-hash");
        detailsHash.setMinWidth(0);
        Button copyHash = iconButton(Icons.copy(), tr("gitlog.menu.copyHash"), () -> {
            if (detailsOf != null) {
                actions.copyHash(detailsOf);
            }
        });
        detailsParents.setAlignment(Pos.CENTER_LEFT);
        HBox hashRow = new HBox(4, detailsHash, copyHash, detailsParents);
        hashRow.setAlignment(Pos.CENTER_LEFT);
        detailsAuthor.getStyleClass().add("git-log-meta");
        detailsAuthor.setWrapText(true);
        detailsRefs.setAlignment(Pos.CENTER_LEFT);
        detailsMessage.getStyleClass().add("git-log-message");
        detailsMessage.setWrapText(true);
        detailsMessage.setEditable(false);
        detailsMessage.setPrefRowCount(1);
        detailsMessage.setAccessibleText(tr("gitlog.details.message"));
        // The text node exists once the skin does; from then on the area follows its height.
        detailsMessage.skinProperty().addListener((o, was, now) -> {
            javafx.scene.Node text = detailsMessage.lookup(".text");
            if (text != null) {
                text.boundsInLocalProperty().addListener((b, before, after) -> fitMessageHeight());
            }
            fitMessageHeight();
        });
        details.getChildren().setAll(hashRow, detailsAuthor, detailsRefs, detailsMessage);
        details.getStyleClass().add("git-log-details");
        detailsScroll.setFitToWidth(true);
        detailsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        detailsScroll.getStyleClass().add("git-log-details-scroll");
        detailsScroll.setMinHeight(0);
        detailsScroll.setVisible(false);
        detailsScroll.setManaged(false);
        VBox lower = new VBox(detailsScroll, files);
        detailsScroll.maxHeightProperty().bind(lower.heightProperty().multiply(0.5));

        split.setOrientation(javafx.geometry.Orientation.VERTICAL);
        split.getItems().setAll(commitsBox, lower);
        split.setDividerPositions(0.55);
        VBox.setVgrow(split, Priority.ALWAYS);

        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);

        getChildren().setAll(toolbar, filterRow, split);
    }

    /** Runs the filter box's text as a search over the whole history; the box then filters its result. */
    private void searchHistory() {
        String query = filterField.getText().strip();
        if (query.isEmpty()) {
            // Nothing typed: show where to type. (An active search is ended with its ✕ or Escape, not by an
            // Enter in an empty box.)
            filterField.requestFocus();
            return;
        }
        filterField.clear();
        actions.searchHistory(query);
    }

    /** Moves focus to the filter box (the {@code git.log.search} command). */
    public void focusSearch() {
        filterField.requestFocus();
        filterField.selectAll();
    }

    /** Case-insensitive substring filter over each loaded commit's subject, author, hash, date and refs. */
    private void applyCommitFilter(String text) {
        String q = text == null ? "" : text.strip().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            filteredCommits.setPredicate(c -> true);
        } else {
            filteredCommits.setPredicate(c -> contains(c.subject(), q)
                    || contains(c.author(), q)
                    || contains(c.shortHash(), q)
                    || contains(c.hash(), q)
                    || contains(c.date(), q)
                    || c.refs().stream().anyMatch(r -> contains(r.name(), q)));
        }
        updateGraphColumn(); // a filtered list is a subset of the commits: no graph while it is narrowed
        updateFooter();
    }

    private boolean filtering() {
        return !filterField.getText().isBlank();
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
     * Replaces the commit list with the first page (or a reload) of the listing {@code view} describes.
     *
     * <p>A reload that brings the same commits back (the log is reloaded after every Git mutation, most of
     * which — staging, a fetch with nothing new — move nothing) leaves the list, its selection, its scroll
     * position and the details pane alone. When the commits did change, the selected commit stays selected
     * if it is still listed. A different listing (another file, a search, all branches) starts at the top.
     */
    public void setLog(GitLog.Page page, View view) {
        boolean sameListing = view.sameListing(this.view);
        this.view = view;
        filterLabel.setText(headerText(view));
        showAllButton.setVisible(view.fileHistory());
        showAllButton.setManaged(view.fileHistory());
        allBranchesButton.setSelected(view.allBranches());
        boolean searching = !view.search().isEmpty();
        searchLabel.setText(searching ? tr("gitlog.searching", view.search()) : "");
        searchChip.setVisible(searching);
        searchChip.setManaged(searching);
        placeholder.setText(placeholderText(page.error(), view));
        hasMore = page.truncated();
        loadingMore = false;
        if (!sameListing || !allCommits.equals(page.entries())) {
            String selected = selectedHash();
            files.getItems().clear();
            followed.clear();
            followed.putAll(page.followed());
            graph = view.graph() ? new GitGraph() : null;
            graphRows.clear();
            if (graph != null) {
                graphRows.addAll(graph.append(page.entries()));
            }
            updateGraphColumn();
            // Populate the unfiltered master list; the FilteredList view re-applies the current filter.
            allCommits.setAll(page.entries());
            reselect(selected);
            if (!sameListing) {
                commits.scrollTo(0);
            }
        } else {
            followed.putAll(page.followed());
        }
        updateFooter();
        // A fresh open focuses the panel before the async log arrives — complete that focus now.
        if (focusPending && selectFirstCommit()) {
            focusPending = false;
            commits.requestFocus();
        }
    }

    /**
     * What an empty list says: git's error; that the search found nothing; that the file of a file history
     * has never been committed (untracked, ignored or only staged — "No commits." read as if its history
     * were lost); or that the branch has no commits.
     */
    static String placeholderText(String error, View view) {
        if (error != null && !error.isEmpty()) {
            return tr("gitlog.failed", error);
        }
        if (!view.search().isEmpty()) {
            return tr("gitlog.noMatches");
        }
        return view.fileHistory() ? tr("gitlog.noFileCommits", view.fileName()) : tr("gitlog.noCommits");
    }

    /** Whether the repository is a shallow clone; said under the commits once all of them are loaded. */
    public void setShallow(boolean shallow) {
        this.shallow = shallow;
        updateFooter();
    }

    /**
     * Adds the next page under the loaded rows. The selection and the scroll position stay where they are,
     * and the graph's open lanes run on into the new rows.
     */
    public void appendLog(GitLog.Page page) {
        loadingMore = false;
        hasMore = page.truncated();
        followed.putAll(page.followed());
        if (graph != null) {
            graphRows.addAll(graph.append(page.entries())); // before the rows: a cell reads its graph row
            updateGraphColumn();
        }
        allCommits.addAll(page.entries());
        updateFooter();
    }

    /** The branch's history (or a file's) with no search, as most callers and tests have it. */
    public void setLog(GitLog.Page page, String fileName, String branch) {
        setLog(page, new View(fileName, branch, false, "", false));
    }

    /** Whole-branch log without a truncation notice — the shape most callers and tests have. */
    public void setLog(List<Entry> log, String fileName) {
        setLog(new GitLog.Page(log, false), fileName, "");
    }

    /** How many commits are loaded (not: how many the filter box currently lets through). */
    public int loadedCount() {
        return allCommits.size();
    }

    /** The hash of the last loaded commit — where the next page continues — or null when none is loaded. */
    public String lastLoadedHash() {
        return allCommits.isEmpty()
                ? null
                : allCommits.get(allCommits.size() - 1).hash();
    }

    /** Whether older history than the loaded rows exists. */
    public boolean hasMore() {
        return hasMore;
    }

    /** The loaded commit {@code hash}, or null. */
    public Entry entry(String hash) {
        for (Entry c : allCommits) {
            if (c.hash().equals(hash)) {
                return c;
            }
        }
        return null;
    }

    /** In a file history: the file's path in commit {@code hash} (it may have been renamed since), or null. */
    public String followedPath(String hash) {
        CommitFile file = followed.get(hash);
        return file == null ? null : file.path();
    }

    /**
     * In a file history: the path the file was renamed from in commit {@code hash}, when
     * {@code repoRelativePath} is that file there and the commit renamed it; else null.
     */
    public String followedOrigPath(String hash, String repoRelativePath) {
        CommitFile file = followed.get(hash);
        return file != null && file.path().equals(repoRelativePath) ? file.origPath() : null;
    }

    /** Asks for the next page, once: from the footer button, or when the list is scrolled near its end. */
    private void requestMore() {
        if (hasMore && !loadingMore) {
            loadingMore = true;
            updateFooter();
            actions.loadMore();
        }
    }

    /**
     * Whether showing row {@code index} of {@code shown} rows should fetch the next page: only near the end,
     * and never while the filter box narrows the rows — a filtered list is short, so its end is always on
     * screen, and it would pull the whole history in page by page (that is what the history search is for).
     */
    static boolean loadsAhead(int index, int shown, boolean hasMore, boolean filtering, int ahead) {
        return hasMore && !filtering && index >= shown - ahead;
    }

    private void updateFooter() {
        // A shallow clone ends where it was cut, not where the history began: say so under the last row.
        boolean cut = shallow && !hasMore && !allCommits.isEmpty();
        footer.setVisible(hasMore || cut);
        footer.setManaged(hasMore || cut);
        truncatedLabel.setText(hasMore ? tr("gitlog.truncated", allCommits.size()) : cut ? tr("gitlog.shallow") : "");
        // Visible and managed with the footer (GitLogRowsFxTest reads them off the label).
        truncatedLabel.setVisible(hasMore || cut);
        truncatedLabel.setManaged(hasMore || cut);
        loadMoreButton.setVisible(hasMore);
        loadMoreButton.setManaged(hasMore);
        loadMoreButton.setDisable(loadingMore);
        loadMoreButton.setText(tr(loadingMore ? "gitlog.loadingMore" : "gitlog.loadMore"));
    }

    /** The graph column is as wide as the widest loaded row (capped), and absent without a graph. */
    private void updateGraphColumn() {
        int columns = graph == null || filtering() ? 0 : Math.min(graph.width(), MAX_GRAPH_LANES);
        if (columns != graphColumns) {
            graphColumns = columns;
            commits.refresh();
        }
    }

    /**
     * "Git history: Foo.java" for a file; "Commits on main" for the branch (never "all commits": it is one
     * branch) and "Commits on all branches" when it is not.
     */
    static String headerText(View view) {
        if (view.fileHistory()) {
            return tr(view.allBranches() ? "gitlog.historyAllBranches" : "gitlog.history", view.fileName());
        }
        if (view.allBranches()) {
            return tr("gitlog.allBranches");
        }
        return view.branch().isBlank() ? tr("gitlog.currentBranch") : tr("gitlog.branch", view.branch());
    }

    static String headerText(String fileName, String branch) {
        return headerText(new View(fileName, branch, false, "", false));
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

    /**
     * Selects and reveals the loaded commit {@code hash} (a parent link, a command). A row the filter box
     * hides is shown by clearing the box. False when the commit is not loaded.
     */
    public boolean selectCommit(String hash) {
        Entry target = entry(hash);
        if (target == null) {
            return false;
        }
        if (!filteredCommits.contains(target)) {
            filterField.clear();
        }
        int index = filteredCommits.indexOf(target);
        commits.getSelectionModel().clearAndSelect(index);
        commits.scrollTo(Math.max(0, index - 2));
        return true;
    }

    /** Pushes the selected commit's changed files (called by the controller after {@code commitFiles}). */
    public void setCommitFiles(List<CommitFile> commitFiles) {
        files.getItems().setAll(commitFiles);
        // A file history lists every file of the commit: put the selection on the one whose history it is.
        String path = view.fileHistory() ? followedPath(selectedHash()) : null;
        if (path != null) {
            for (int i = 0; i < commitFiles.size(); i++) {
                if (path.equals(commitFiles.get(i).path())) {
                    files.getSelectionModel().clearAndSelect(i);
                    files.scrollTo(i);
                    break;
                }
            }
        }
    }

    /** As {@link #setCommitFiles(List)}, dropped when {@code hash} is no longer the selected commit. */
    public void setCommitFiles(String hash, List<CommitFile> commitFiles) {
        if (hash != null && hash.equals(selectedHash())) {
            setCommitFiles(commitFiles);
        }
    }

    /**
     * Completes the details pane with what only {@code git show} knows — the e-mail, the message body, the
     * committer — once it arrives; dropped when another commit has been selected meanwhile.
     */
    public void setCommitDetails(GitLog.Details d) {
        if (d == null || !d.hash().equals(detailsOf)) {
            return;
        }
        long now = System.currentTimeMillis() / 1000;
        String author = tr(
                "gitlog.details.author",
                d.author(),
                d.authorEmail(),
                absoluteDate(d.authorEpochSeconds()),
                relativeDate(d.authorEpochSeconds(), "", now));
        if (!d.committer().equals(d.author()) || !d.committerEmail().equals(d.authorEmail())) {
            author += "\n"
                    + tr(
                            "gitlog.details.committer",
                            d.committer(),
                            d.committerEmail(),
                            absoluteDate(d.commitEpochSeconds()),
                            relativeDate(d.commitEpochSeconds(), "", now));
        }
        detailsAuthor.setText(author);
        detailsMessage.setText(d.message());
        showParents(d.parents());
        showRefs(d.refs());
    }

    /** Sizes the message area to its text: no inner scroll bar, the details pane scrolls as a whole. */
    private void fitMessageHeight() {
        javafx.scene.Node text = detailsMessage.lookup(".text");
        if (text == null) {
            return;
        }
        Insets insets = detailsMessage.getInsets();
        double height = Math.ceil(text.getBoundsInLocal().getHeight()) + insets.getTop() + insets.getBottom() + 4;
        detailsMessage.setMinHeight(height);
        detailsMessage.setPrefHeight(height);
        detailsMessage.setMaxHeight(height);
    }

    /** Fills the details pane from the row at once; {@link #setCommitDetails} adds the rest. */
    private void showDetails(Entry c) {
        detailsOf = c == null ? null : c.hash();
        detailsScroll.setVisible(c != null);
        detailsScroll.setManaged(c != null);
        if (c == null) {
            return;
        }
        detailsHash.setText(c.hash());
        long now = System.currentTimeMillis() / 1000;
        detailsAuthor.setText(c.author() + " · "
                + (c.epochSeconds() > 0
                        ? absoluteDate(c.epochSeconds()) + " (" + relativeDate(c, now) + ")"
                        : c.date()));
        detailsMessage.setText(c.subject());
        showParents(c.parents());
        showRefs(c.refs());
        detailsScroll.setVvalue(0);
    }

    private void showParents(List<String> parents) {
        detailsParents.getChildren().clear();
        if (parents.isEmpty()) {
            return;
        }
        Label label = new Label(tr(parents.size() > 1 ? "gitlog.details.parents" : "gitlog.details.parent"));
        label.getStyleClass().add("git-log-meta");
        detailsParents.getChildren().add(label);
        for (String parent : parents) {
            Hyperlink link = new Hyperlink(GitFormat.shortHash(parent));
            link.getStyleClass().add("git-log-parent");
            link.setFocusTraversable(false);
            link.setTooltip(new Tooltip(parent));
            link.setOnAction(e -> {
                if (!selectCommit(parent)) {
                    actions.commitNotLoaded(parent);
                }
            });
            detailsParents.getChildren().add(link);
        }
    }

    private void showRefs(List<GitLog.Ref> refs) {
        detailsRefs.getChildren().clear();
        for (GitLog.Ref ref : refs) {
            Label chip = new Label(refLabel(ref));
            chip.getStyleClass().addAll("git-ref", refCssClass(ref));
            detailsRefs.getChildren().add(chip);
        }
        detailsRefs.setVisible(!refs.isEmpty());
        detailsRefs.setManaged(!refs.isEmpty());
    }

    private static final DateTimeFormatter ABSOLUTE_DATE = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM);

    /** A commit time in the user's zone and locale: "Oct 3, 2026, 2:22:10 PM". */
    static String absoluteDate(long epochSeconds) {
        return ABSOLUTE_DATE
                .withLocale(java.util.Locale.getDefault(java.util.Locale.Category.FORMAT))
                .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()));
    }

    /** The hash of the currently-selected commit, or {@code null} when none is selected (backs the palette commands). */
    public String selectedHash() {
        Entry c = commits.getSelectionModel().getSelectedItem();
        return c == null ? null : c.hash();
    }

    /** The hashes of every selected commit, in list order (newest first). */
    public List<String> selectedHashes() {
        List<Integer> indexes = new ArrayList<>(commits.getSelectionModel().getSelectedIndices());
        indexes.sort(null);
        List<String> hashes = new ArrayList<>(indexes.size());
        for (int index : indexes) {
            if (index >= 0 && index < filteredCommits.size()) {
                hashes.add(filteredCommits.get(index).hash());
            }
        }
        return hashes;
    }

    /**
     * Enter / double-click on the commit list: two selected commits are compared, one is opened — as a
     * review of everything it changed, or in a file history as its change to that file (the whole commit
     * is still "Review Commit" in the row's menu).
     */
    private void openSelectedCommits() {
        List<String> selected = selectedHashes();
        String hash = selectedHash();
        if (selected.size() == 2) {
            actions.compareCommits(selected.get(1), selected.get(0)); // the lower row is the older commit
        } else if (hash != null) {
            CommitFile file = view.fileHistory() ? followed.get(hash) : null;
            if (file != null) {
                actions.openFileDiff(hash, file.path(), file.origPath());
            } else {
                actions.reviewCommit(hash);
            }
        }
    }

    private void openSelectedFile() {
        Entry c = commits.getSelectionModel().getSelectedItem();
        CommitFile f = files.getSelectionModel().getSelectedItem();
        if (c != null && f != null) {
            if (view.fileHistory()) {
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
        return relativeDate(c.epochSeconds(), c.date(), nowSeconds);
    }

    static String relativeDate(long epochSeconds, String fallback, long nowSeconds) {
        if (epochSeconds <= 0) {
            return fallback;
        }
        RelativeTime.Span span = RelativeTime.of(epochSeconds, nowSeconds);
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

    /** Lane colours, by column: mid-tones that read on a light and on a dark list alike. */
    private static final Color[] LANE_COLORS = {
        Color.web("#3b82f6"),
        Color.web("#e0603a"),
        Color.web("#2fa46a"),
        Color.web("#b065d8"),
        Color.web("#d4a017"),
        Color.web("#16a3b8"),
        Color.web("#e0559a"),
        Color.web("#8a8f98")
    };

    /**
     * The graph of one row: a canvas as wide as the graph column and exactly as high as the cell, so the
     * lanes of adjacent rows meet. It asks for no height of its own — the row stays one text line — and
     * redraws only when its row or its size changes; drawing allocates nothing.
     */
    private final class GraphStrip extends Region {
        private final Canvas canvas = new Canvas();
        private final ListCell<?> cell;
        private GitGraph.Row row;
        private boolean merge;

        GraphStrip(ListCell<?> cell) {
            this.cell = cell;
            getChildren().add(canvas);
            setMinHeight(0);
            setMaxHeight(Double.MAX_VALUE);
            setMouseTransparent(true);
        }

        void show(GitGraph.Row row, boolean merge) {
            this.row = row;
            this.merge = merge;
            requestLayout();
            draw();
        }

        @Override
        protected double computeMinWidth(double height) {
            return computePrefWidth(height);
        }

        @Override
        protected double computePrefWidth(double height) {
            return graphColumns * GRAPH_LANE_WIDTH;
        }

        @Override
        protected double computePrefHeight(double width) {
            return 0;
        }

        @Override
        protected void layoutChildren() {
            // The canvas covers the whole height of the cell, not just of this node: the cell keeps a pixel
            // of inset around its content, which would otherwise show as a gap in every lane at every row.
            double top = getLayoutY() + (getParent() == null ? 0 : getParent().getLayoutY());
            double height = cell.getHeight() > 0 ? cell.getHeight() : getHeight();
            canvas.setLayoutY(-top);
            if (canvas.getWidth() != getWidth() || canvas.getHeight() != height) {
                canvas.setWidth(getWidth());
                canvas.setHeight(height);
                draw();
            }
        }

        private double x(int lane) {
            return lane * GRAPH_LANE_WIDTH + GRAPH_LANE_WIDTH / 2;
        }

        private void draw() {
            double w = canvas.getWidth();
            double h = canvas.getHeight();
            GraphicsContext g = canvas.getGraphicsContext2D();
            g.clearRect(0, 0, w, h);
            if (row == null || w <= 0 || h <= 0) {
                return;
            }
            double mid = h / 2;
            double nodeX = x(row.column());
            g.setLineWidth(1.5);
            for (int lane : row.through()) {
                g.setStroke(LANE_COLORS[lane % LANE_COLORS.length]);
                g.strokeLine(x(lane), 0, x(lane), h);
            }
            for (int lane : row.in()) {
                g.setStroke(LANE_COLORS[lane % LANE_COLORS.length]);
                edge(g, x(lane), 0, nodeX, mid);
            }
            for (int lane : row.out()) {
                g.setStroke(LANE_COLORS[lane % LANE_COLORS.length]);
                edge(g, nodeX, mid, x(lane), h);
            }
            g.setFill(LANE_COLORS[row.column() % LANE_COLORS.length]);
            if (merge) {
                // A merge is a diamond, apart from the two lanes leaving it.
                g.beginPath();
                g.moveTo(nodeX, mid - 4.5);
                g.lineTo(nodeX + 4.5, mid);
                g.lineTo(nodeX, mid + 4.5);
                g.lineTo(nodeX - 4.5, mid);
                g.closePath();
                g.fill();
            } else {
                g.fillOval(nodeX - 3.2, mid - 3.2, 6.4, 6.4);
            }
        }

        /** A straight line in its own lane; a curve that leaves and arrives vertically between two lanes. */
        private void edge(GraphicsContext g, double x1, double y1, double x2, double y2) {
            if (x1 == x2) {
                g.strokeLine(x1, y1, x2, y2);
                return;
            }
            double bend = (y1 + y2) / 2;
            g.beginPath();
            g.moveTo(x1, y1);
            g.bezierCurveTo(x1, bend, x2, bend, x2, y2);
            g.stroke();
        }
    }

    /**
     * One commit, on one line: {@code graph hash [refs…] subject ………… author  date}. An {@code HBox} of
     * labels — the {@code TextFlow} this replaced could not ellipsize and made the list as wide as its
     * longest subject. The cell asks for no width of its own ({@code prefWidth 0}), so the list gives it
     * exactly the viewport's; the subject asks for none either and takes whatever the other columns leave,
     * ellipsizing. A narrow row drops the author and all but one or two ref chips ({@link #chipBudget},
     * {@link #showsAuthor}) rather than squeezing every column to an ellipsis. The one-line row height is CSS
     * ({@code .git-log-panel .git-tree .list-cell}), whose zero vertical padding is also what lets the graph
     * strips of adjacent rows touch.
     */
    private final class CommitCell extends ListCell<Entry> {
        private final GraphStrip graphStrip = new GraphStrip(this);
        private final Label hash = new Label();
        private final HBox refs = new HBox(4);
        private final Label subject = new Label();
        private final Label author = new Label();
        private final Label date = new Label();
        private final HBox row = new HBox(8, graphStrip, hash, refs, subject, author, date);
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
            // The menu is built when it is asked for (a right-click, or the Menu key through RowContextMenu),
            // not for every row scrolled past, and for the selection as it is then.
            setOnContextMenuRequested(e -> {
                if (isEmpty() || getItem() == null) {
                    return;
                }
                if (openMenu != null) {
                    openMenu.hide();
                }
                openMenu = buildMenu(getItem());
                openMenu.show(this, e.getScreenX(), e.getScreenY());
                e.consume();
            });
        }

        /** Shows as many ref chips, and the author, as the row's current width has room for. */
        private void fitToWidth(Entry c) {
            double width = (getWidth() > 0 ? getWidth() : commits.getWidth()) - graphColumns * GRAPH_LANE_WIDTH;
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
                return;
            }
            // The graph has a row per loaded commit; with it shown nothing is filtered, so the indexes agree.
            int index = getIndex();
            boolean graphed = graphColumns > 0 && index >= 0 && index < graphRows.size();
            graphStrip.setManaged(graphed);
            graphStrip.setVisible(graphed);
            if (graphed) {
                graphStrip.show(graphRows.get(index), c.isMerge());
            }
            hash.setText(c.shortHash());
            subject.setText(c.subject());
            author.setText(c.author());
            date.setText(relativeDate(c, System.currentTimeMillis() / 1000));
            fitToWidth(c);
            setGraphic(row);
            tooltip.setText(commitTooltip(c));
            setTooltip(tooltip);
            if (loadsAhead(index, filteredCommits.size(), hasMore, filtering(), loadAheadRows)) {
                requestMore();
            }
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
            List<String> selected = selectedHashes();
            MenuItem copy = item(tr("gitlog.menu.copyHash"), Icons.copy(), () -> actions.copyHash(h));
            MenuItem review = item(tr("gitlog.menu.review"), Icons.diff(), () -> actions.reviewCommit(h));
            MenuItem compare = item(
                    tr("gitlog.menu.compare"),
                    Icons.diff(),
                    () -> actions.compareCommits(selected.get(1), selected.get(0)));
            compare.setDisable(selected.size() != 2);
            MenuItem checkout = item(tr("gitlog.menu.checkout"), Icons.git(), () -> actions.checkout(h));
            MenuItem newBranch = item(tr("gitlog.menu.newBranch"), Icons.git(), () -> actions.newBranch(h));
            MenuItem newTag = item(tr("gitlog.menu.newTag"), Icons.bookmark(), () -> actions.newTag(h));
            MenuItem revert = item(tr("gitlog.menu.revert"), Icons.refresh(), () -> actions.revert(h));
            MenuItem cherry = item(tr("gitlog.menu.cherryPick"), Icons.stageAll(), () -> actions.cherryPick(h));
            MenuItem patch = item(tr("gitlog.menu.createPatch"), Icons.diff(), () -> actions.createPatch(h));
            Menu reset = new Menu(tr("gitlog.menu.reset"));
            reset.setGraphic(Icons.refresh());
            reset.getItems()
                    .setAll(
                            item(tr("gitlog.menu.resetSoft"), null, () -> actions.reset(h, "soft")),
                            item(tr("gitlog.menu.resetMixed"), null, () -> actions.reset(h, "mixed")),
                            item(tr("gitlog.menu.resetHard"), null, () -> actions.reset(h, "hard")));
            ContextMenu menu = new ContextMenu();
            // In a file history the row is first of all a version of that file.
            CommitFile file = view.fileHistory() ? followed.get(h) : null;
            if (file != null) {
                menu.getItems()
                        .addAll(
                                item(
                                        tr("gitlog.menu.showDiff"),
                                        Icons.diff(),
                                        () -> actions.openFileDiff(h, file.path(), file.origPath())),
                                item(
                                        tr("gitlog.menu.compareWorking"),
                                        Icons.merge(),
                                        () -> actions.compareFileWithWorking(h, file.path())),
                                new SeparatorMenuItem());
            }
            menu.getItems().addAll(copy, review, compare, patch, new SeparatorMenuItem(), checkout, newBranch, newTag);
            // One submenu per tag on the row: which tag is meant is never a guess.
            for (String tag : c.tags()) {
                Menu tagMenu = new Menu(tr("gitlog.menu.tag", tag));
                tagMenu.setGraphic(Icons.bookmark());
                tagMenu.getItems()
                        .setAll(
                                item(tr("gitlog.menu.checkoutTag"), null, () -> actions.checkoutTag(tag)),
                                item(tr("gitlog.menu.pushTag"), null, () -> actions.pushTag(tag)),
                                item(tr("gitlog.menu.deleteTag"), null, () -> actions.deleteTag(tag)));
                menu.getItems().add(tagMenu);
            }
            menu.getItems().addAll(new SeparatorMenuItem(), revert, cherry, reset);
            return menu;
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
