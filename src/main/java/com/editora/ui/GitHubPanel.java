package com.editora.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.editora.github.GitHubItemFilter;
import com.editora.github.GitHubListQuery;
import com.editora.github.IssueListParser.Issue;
import com.editora.github.PrListParser.PullRequest;
import com.editora.github.RunListParser.RunState;
import com.editora.github.RunListParser.WorkflowRun;

import static com.editora.i18n.Messages.tr;

/**
 * The GitHub tool window: a segmented <b>Pull Requests | Issues | Runs</b> list. Selecting a segment asks the
 * controller (via {@link Actions}) to fetch that list lazily; double-click / Enter reviews a PR's diff, opens
 * an issue on GitHub, or — for a <em>failed</em> workflow run — dumps its failure log into the shared Build
 * Output console (where the stack frames are clickable). A row's context menu offers check-out / review-diff
 * for a PR and view-log / rerun / rerun-failed / cancel for a run, plus open-on-GitHub / copy-URL. Purely a
 * view — the controller (through {@code GitHubCoordinator}) runs {@code gh}. Registered default-hidden and
 * available only inside a GitHub repo that has open PRs/issues or workflow runs.
 *
 * <p>All three segments share one filter field and one keyboard flow (the {@link GitLogPanel} shape): typing
 * narrows the visible rows via {@link GitHubItemFilter}, {@code C-n}/{@code C-p} (and bare {@code n}/{@code p},
 * which are free — the list holds no text input) move the selection, and Enter activates.
 *
 * <p>The toolbar names the repository {@code gh} resolved for the folder (with a fork that is the upstream
 * repository, where every row action then runs), and carries the server-side filter: a state (open / closed
 * / merged / all) and "mine". A list longer than its limit ends in a "load more" row instead of stopping
 * silently. A row's menu opens from the keyboard too (Menu key / Shift+F10), and the run actions are palette
 * commands that act on the selected row ({@link #rowAnswer}).
 */
public final class GitHubPanel extends VBox implements ToolWindowContent {

    /** Which segment is showing (a boolean can't hold three states). */
    public enum Mode {
        PRS,
        ISSUES,
        RUNS
    }

    /** Operations the panel asks the controller to perform. */
    public interface Actions {
        void refresh();

        /** Opens the same create-pull-request form as the {@code github.createPr} palette command. */
        void createPr();

        void showPrs();

        void showIssues();

        void showRuns();

        void checkoutPr(int number);

        void reviewPr(int number);

        /** Dumps a failed run's log into the shared Output console. */
        void viewRunLog(long runId, String workflowName);

        /** Re-runs a workflow run; {@code failedOnly} re-runs just the failed jobs. */
        void rerunRun(long runId, boolean failedOnly);

        void cancelRun(long runId);

        void openUrl(String url);

        void copyUrl(String url);
    }

    /**
     * The last row of a list {@code gh} had more of: "showing the first N — load more". Activating it asks
     * for the next page (by raising the limit; {@code gh} has no offset).
     */
    record MoreRow(int shown) {}

    /** A palette command that acts on the selected row. */
    public enum RowAction {
        RERUN,
        RERUN_FAILED,
        CANCEL,
        COPY_URL
    }

    /** Whether a {@link RowAction} can run on the selection, or why not ({@link #messageKey}). */
    public enum RowAnswer {
        RUN(""),
        /** The window is closed, so its selection is not something the user can see: open it and ask. */
        OPEN_AND_ASK("status.github.row.choose"),
        NO_SELECTION("status.github.row.none"),
        NOT_A_RUN("status.github.row.notARun"),
        NOT_FAILED("status.github.row.onlyFailed"),
        NOT_ACTIVE("status.github.row.notActive"),
        STILL_ACTIVE("status.github.row.stillActive"),
        NO_URL("status.github.noUrl");

        private final String messageKey;

        RowAnswer(String messageKey) {
            this.messageKey = messageKey;
        }

        /** The catalog key of the status message for an answer other than {@link #RUN}. */
        public String messageKey() {
            return messageKey;
        }
    }

    /**
     * Decides a row command. It never acts on a selection the user cannot see: with the window closed the
     * answer is to open it. The run rules are the context menu's — only a failed run has failed jobs to
     * re-run, only a finished one can be re-run, only a queued or running one cancelled. Pure.
     */
    static RowAnswer decide(RowAction action, boolean windowOpen, Object selected) {
        if (!windowOpen) {
            return RowAnswer.OPEN_AND_ASK;
        }
        if (selected == null || selected instanceof MoreRow) {
            return RowAnswer.NO_SELECTION;
        }
        if (action == RowAction.COPY_URL) {
            return urlOf(selected).isBlank() ? RowAnswer.NO_URL : RowAnswer.RUN;
        }
        if (!(selected instanceof WorkflowRun run)) {
            return RowAnswer.NOT_A_RUN;
        }
        RunState state = run.state();
        return switch (action) {
            case RERUN -> state.active() ? RowAnswer.STILL_ACTIVE : RowAnswer.RUN;
            case RERUN_FAILED -> state.failed() ? RowAnswer.RUN : RowAnswer.NOT_FAILED;
            case CANCEL -> state.active() ? RowAnswer.RUN : RowAnswer.NOT_ACTIVE;
            case COPY_URL -> RowAnswer.RUN;
        };
    }

    private static String urlOf(Object item) {
        String url =
                switch (item) {
                    case PullRequest pr -> pr.url();
                    case Issue issue -> issue.url();
                    case WorkflowRun run -> run.url();
                    default -> "";
                };
        return url == null ? "" : url;
    }

    private final Actions actions;
    private final ToggleButton prsToggle = new ToggleButton(tr("github.panel.prs"));
    private final ToggleButton issuesToggle = new ToggleButton(tr("github.panel.issues"));
    private final ToggleButton runsToggle = new ToggleButton(tr("github.panel.runs"));
    private final TextField filterField = new TextField();
    /**
     * Unfiltered rows for the showing segment; {@link #list} renders a {@link FilteredList} view over this,
     * so filtering keeps object identity (a selected row survives re-filtering while it still matches).
     */
    private final ObservableList<Object> allItems = FXCollections.observableArrayList();

    private final FilteredList<Object> visibleItems = new FilteredList<>(allItems, i -> true);
    private final ListView<Object> list = new ListView<>();
    private final Label placeholder = new Label(tr("github.panel.noPrs"));
    private final VBox loading = buildLoading();

    /** {@code owner/name} of the repository {@code gh} resolved; empty until known. */
    private final Label repository = new Label();

    private final ChoiceBox<GitHubListQuery.State> stateFilter = new ChoiceBox<>();
    private final ToggleButton mineToggle = new ToggleButton(tr("github.panel.mine"));
    private final ProgressIndicator busy = new ProgressIndicator();

    /** The state filter and the row limit of each segment; "mine" is shared by pull requests and issues. */
    private final Map<Mode, GitHubListQuery.State> states = new EnumMap<>(Mode.class);

    private final Map<Mode, Integer> limits = new EnumMap<>(Mode.class);
    /** Set while the filter controls are being synchronized to a segment, so that is not taken for a choice. */
    private boolean syncingFilter;
    /** After "load more": the index of the first new row, selected when the longer list arrives. */
    private int selectAfterLoad = -1;

    /**
     * The segment whose fetch is in flight. Each {@code gh} call takes seconds, so switching segments twice
     * can land the first response after the second — this drops a result whose segment is no longer wanted.
     */
    private Mode requested = Mode.PRS;

    public GitHubPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("git-log-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);
        setPadding(new Insets(4));

        ToggleGroup group = new ToggleGroup();
        prsToggle.setToggleGroup(group);
        issuesToggle.setToggleGroup(group);
        runsToggle.setToggleGroup(group);
        prsToggle.setSelected(true);
        for (ToggleButton t : List.of(prsToggle, issuesToggle, runsToggle)) {
            t.getStyleClass().add("github-tab");
            // Reachable with Tab (and Space selects): the segments were mouse-only, with a palette command
            // for Runs alone, so Issues could not be reached from the keyboard at all.
            t.setFocusTraversable(true);
        }
        // A toggle group lets a selected button be re-clicked to deselect; keep exactly one selected.
        prsToggle.setOnAction(e -> {
            selectSegment(Mode.PRS);
            actions.showPrs();
        });
        issuesToggle.setOnAction(e -> {
            selectSegment(Mode.ISSUES);
            actions.showIssues();
        });
        runsToggle.setOnAction(e -> {
            selectSegment(Mode.RUNS);
            actions.showRuns();
        });

        Button createPr = iconButton(Icons.newFile(), tr("github.panel.createPrTip"), actions::createPr);
        Button refresh = iconButton(Icons.refresh(), tr("github.panel.refreshTip"), actions::refresh);
        repository.getStyleClass().add("github-repository");
        repository.setMinWidth(0);
        repository.setMaxWidth(Double.MAX_VALUE);
        repository.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
        repository.setTooltip(new Tooltip(tr("github.panel.repositoryTip")));
        HBox.setHgrow(repository, Priority.ALWAYS);
        HBox.setMargin(repository, new Insets(0, 8, 0, 8));

        stateFilter.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(GitHubListQuery.State state) {
                return state == null
                        ? ""
                        : tr("github.panel.state." + state.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public GitHubListQuery.State fromString(String text) {
                return null;
            }
        });
        stateFilter.setAccessibleText(tr("github.panel.stateTip"));
        stateFilter.setTooltip(new Tooltip(tr("github.panel.stateTip")));
        stateFilter.getStyleClass().add("github-state-filter");
        // A fixed width: sized from its items it shrank below its own label when the segment's items changed.
        stateFilter.setPrefWidth(112);
        stateFilter.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        stateFilter.valueProperty().addListener((o, was, now) -> {
            if (!syncingFilter && now != null) {
                states.put(mode(), now);
                filterChanged();
            }
        });
        mineToggle.getStyleClass().add("github-tab");
        mineToggle.setTooltip(new Tooltip(tr("github.panel.mineTip")));
        mineToggle.setOnAction(e -> filterChanged());
        busy.setPrefSize(14, 14);
        busy.setMaxSize(14, 14);
        busy.setVisible(false);
        busy.setManaged(false);
        HBox.setMargin(stateFilter, new Insets(0, 6, 0, 0));
        HBox.setMargin(mineToggle, new Insets(0, 6, 0, 0));
        HBox.setMargin(busy, new Insets(0, 8, 0, 0));
        syncFilterControls(Mode.PRS);

        HBox toolbar = new HBox(
                2, prsToggle, issuesToggle, runsToggle, repository, busy, stateFilter, mineToggle, createPr, refresh);
        toolbar.getStyleClass().add("git-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        // Filter row: narrows the showing segment's rows as you type, with a trailing clear ("✕") button.
        // Typing here can't clash with the list's bare n/p nav — that handler is on the ListView, not the field.
        filterField.setPromptText(tr("github.panel.filterPrompt"));
        filterField.getStyleClass().add("git-log-filter");
        filterField.textProperty().addListener((o, w, n) -> applyFilter(n));
        HBox.setHgrow(filterField, Priority.ALWAYS);
        Button clearFilter = ClearableField.clearButton(filterField);
        HBox filterRow = new HBox(4, filterField, clearFilter);
        filterRow.getStyleClass().add("project-filter-bar");
        filterRow.setAlignment(Pos.CENTER_LEFT);
        FilterFieldNav.install(filterField, list, this::activateSelected);

        list.getStyleClass().add("git-tree");
        list.setItems(visibleItems);
        list.setCellFactory(v -> new ItemCell());
        list.setPlaceholder(placeholder);
        placeholder.getStyleClass().add("tool-window-placeholder");
        placeholder.setWrapText(true);
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                activateSelected();
            }
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                activateSelected();
                e.consume();
            }
        });
        installListNav(list);
        // The rows' menus are set on the cells; the Menu key / Shift+F10 arrive at the list, which owns the focus.
        RowContextMenu.install(list);
        VBox.setVgrow(list, Priority.ALWAYS);

        getChildren().setAll(toolbar, filterRow, list);
    }

    /** Applies the filter box to the showing segment's rows. */
    private void applyFilter(String query) {
        // The "load more" row is not an item to filter: it stays, so a filtered list still says it is partial.
        visibleItems.setPredicate(item -> item instanceof MoreRow || GitHubItemFilter.matches(item, query));
    }

    // --- the server-side filter (state / mine / limit) -------------------------------------------

    private static int defaultLimit(Mode mode) {
        return mode == Mode.RUNS ? GitHubListQuery.DEFAULT_RUN_LIMIT : GitHubListQuery.DEFAULT_LIMIT;
    }

    /** What {@code mode}'s list asks {@code gh} for — the controller passes it to the fetch. */
    public GitHubListQuery query(Mode mode) {
        return new GitHubListQuery(
                states.getOrDefault(mode, GitHubListQuery.State.OPEN),
                mode != Mode.RUNS && mineToggle.isSelected(),
                limits.getOrDefault(mode, defaultLimit(mode)));
    }

    /** Shows the filter controls that apply to {@code mode}: runs have neither a state nor "mine" here. */
    private void syncFilterControls(Mode mode) {
        syncingFilter = true;
        try {
            boolean filterable = mode != Mode.RUNS;
            stateFilter.setVisible(filterable);
            stateFilter.setManaged(filterable);
            mineToggle.setVisible(filterable);
            mineToggle.setManaged(filterable);
            if (filterable) {
                stateFilter
                        .getItems()
                        .setAll(
                                mode == Mode.PRS
                                        ? List.of(GitHubListQuery.State.values())
                                        : List.of(
                                                GitHubListQuery.State.OPEN,
                                                GitHubListQuery.State.CLOSED,
                                                GitHubListQuery.State.ALL));
                stateFilter.setValue(states.getOrDefault(mode, GitHubListQuery.State.OPEN));
            }
        } finally {
            syncingFilter = false;
        }
    }

    /** The state or "mine" was changed: a different list, so back to its first page. */
    private void filterChanged() {
        Mode mode = mode();
        limits.remove(mode);
        refetch(mode);
    }

    /** The "load more" row: the same list, one page longer; its first new row is selected on arrival. */
    private void loadMore(MoreRow row) {
        Mode mode = mode();
        limits.put(mode, limits.getOrDefault(mode, defaultLimit(mode)) + defaultLimit(mode));
        selectAfterLoad = row.shown();
        refetch(mode);
    }

    private void refetch(Mode mode) {
        beginLoading(mode);
        switch (mode) {
            case PRS -> actions.showPrs();
            case ISSUES -> actions.showIssues();
            case RUNS -> actions.showRuns();
        }
    }

    /** The empty-list text of {@code mode} under its current filter. */
    private String emptyText(Mode mode) {
        GitHubListQuery q = query(mode);
        if (mode != Mode.RUNS && (q.state() != GitHubListQuery.State.OPEN || q.mine())) {
            return tr("github.panel.noMatches");
        }
        return tr(
                switch (mode) {
                    case PRS -> "github.panel.noPrs";
                    case ISSUES -> "github.panel.noIssues";
                    case RUNS -> "github.panel.noRuns";
                });
    }

    /** Names the repository {@code gh} resolved ({@code owner/name}; blank while unknown). */
    public void setRepository(String nameWithOwner) {
        repository.setText(nameWithOwner == null ? "" : nameWithOwner);
    }

    /** The repository named in the toolbar. For tests. */
    String repositoryText() {
        return repository.getText();
    }

    /**
     * Shows or hides the toolbar's activity indicator. HOOK for the "gh calls in flight" signal of
     * {@code GitHubService}: the panel only draws it; whoever knows that gh is running calls this.
     */
    public void setBusy(boolean running) {
        busy.setVisible(running);
        busy.setManaged(running);
    }

    // --- palette commands on the selected row ----------------------------------------------------

    /** Whether {@code action} can run on the selected row; {@code windowOpen} is the tool window's state. */
    public RowAnswer rowAnswer(RowAction action, boolean windowOpen) {
        return decide(action, windowOpen, list.getSelectionModel().getSelectedItem());
    }

    /** Runs {@code action} on the selected row, when {@link #rowAnswer} allows it. */
    public void perform(RowAction action) {
        Object sel = list.getSelectionModel().getSelectedItem();
        if (decide(action, true, sel) != RowAnswer.RUN) {
            return;
        }
        if (action == RowAction.COPY_URL) {
            actions.copyUrl(urlOf(sel));
        } else if (sel instanceof WorkflowRun run) {
            switch (action) {
                case RERUN -> actions.rerunRun(run.databaseId(), false);
                case RERUN_FAILED -> actions.rerunRun(run.databaseId(), true);
                case CANCEL -> actions.cancelRun(run.databaseId());
                case COPY_URL -> {}
            }
        }
    }

    /** Selects the row at {@code index} of the visible list. For tests. */
    void selectRow(int index) {
        list.getSelectionModel().clearAndSelect(index);
    }

    /** The visible rows. For tests. */
    List<Object> rows() {
        return List.copyOf(list.getItems());
    }

    // --- times -----------------------------------------------------------------------------------

    /**
     * {@code gh}'s ISO-8601 UTC timestamp as "3 days ago · 4 Oct 2026, 09:15" — relative, then the local date
     * and time — instead of the raw {@code 2026-10-04T07:15:00Z}. An unparsable value is returned as it is.
     */
    static String when(String iso, Instant now, ZoneId zone, Locale locale) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            Instant at = Instant.parse(iso.strip());
            String local = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                    .withLocale(locale)
                    .withZone(zone)
                    .format(at);
            return GitBlameCoordinator.relativeTimeLabel(at.getEpochSecond(), now.getEpochSecond()) + " · " + local;
        } catch (RuntimeException notATimestamp) {
            return iso;
        }
    }

    private static String when(String iso) {
        return when(
                iso, Instant.now(), ZoneId.systemDefault(), Locale.forLanguageTag(com.editora.i18n.Messages.current()));
    }

    /**
     * Emacs-style {@code n}/{@code p} (bare, and with Control) move the selection — the list holds no text
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

    private static VBox buildLoading() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(28, 28);
        spinner.setMaxSize(28, 28);
        Label label = new Label(tr("github.panel.loading"));
        label.getStyleClass().add("tool-window-placeholder");
        VBox box = new VBox(8, spinner, label);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /**
     * Switches segments: selects the toggle, resets the empty-list text, and drops any filter — a query
     * typed against pull requests is meaningless for runs, and leaving it applied would silently hide the
     * rows that just arrived.
     */
    private void selectSegment(Mode mode) {
        switch (mode) {
            case PRS -> prsToggle.setSelected(true);
            case ISSUES -> issuesToggle.setSelected(true);
            case RUNS -> runsToggle.setSelected(true);
        }
        syncFilterControls(mode);
        placeholder.setText(emptyText(mode));
        filterField.clear(); // also resets the predicate, via the text listener
        beginLoading(mode);
    }

    /**
     * Clears the stale list and shows a spinner while {@code gh} runs — a {@code gh pr list} round-trip takes
     * seconds, and leaving the previous segment's rows on screen reads as a frozen window. Keeps the filter:
     * a plain refresh re-fetches what the user is already looking at.
     */
    private void beginLoading(Mode mode) {
        requested = mode;
        allItems.clear();
        list.setPlaceholder(loading);
    }

    /** Applies a fetch result only when its segment is still the one the user wants. */
    private boolean accept(Mode mode) {
        if (requested != mode) {
            return false;
        }
        list.setPlaceholder(placeholder);
        return true;
    }

    /** Which segment is showing — the controller re-fetches that one on refresh. */
    public Mode mode() {
        if (issuesToggle.isSelected()) {
            return Mode.ISSUES;
        }
        return runsToggle.isSelected() ? Mode.RUNS : Mode.PRS;
    }

    /** Selects the Runs segment (used by the {@code github.showRuns} command). */
    public void selectRuns() {
        selectSegment(Mode.RUNS);
    }

    /** Selects {@code mode}'s segment (the {@code github.showPrs} / {@code showIssues} / {@code showRuns} commands). */
    public void select(Mode mode) {
        selectSegment(mode);
    }

    /** Shows the spinner for the segment currently selected — used by a refresh that isn't a segment switch. */
    public void showLoading() {
        beginLoading(mode());
    }

    /**
     * Ends the loading state of {@code mode}'s fetch with {@code message} in place of the rows — a failed
     * {@code gh} call must not read as "no open pull requests", nor leave the spinner up forever.
     */
    public void showError(Mode mode, String message) {
        if (!accept(mode)) {
            return;
        }
        allItems.clear();
        placeholder.setText(message);
    }

    /** The text shown in place of the rows (the empty-list note, or a failure message). For tests. */
    String placeholderText() {
        return list.getPlaceholder() == placeholder ? placeholder.getText() : null;
    }

    /** Replaces the list with pull requests. */
    public void setPrs(List<PullRequest> prs) {
        setPrs(prs, false);
    }

    /** Replaces the list with pull requests; {@code more} adds the "load more" row. */
    public void setPrs(List<PullRequest> prs, boolean more) {
        if (accept(Mode.PRS)) {
            prsToggle.setSelected(true);
            setRows(Mode.PRS, prs, more);
        }
    }

    /** Replaces the list with issues. */
    public void setIssues(List<Issue> issues) {
        setIssues(issues, false);
    }

    /** Replaces the list with issues; {@code more} adds the "load more" row. */
    public void setIssues(List<Issue> issues, boolean more) {
        if (accept(Mode.ISSUES)) {
            issuesToggle.setSelected(true);
            setRows(Mode.ISSUES, issues, more);
        }
    }

    /** Replaces the list with workflow runs. */
    public void setRuns(List<WorkflowRun> runs) {
        setRuns(runs, false);
    }

    /** Replaces the list with workflow runs; {@code more} adds the "load more" row. */
    public void setRuns(List<WorkflowRun> runs, boolean more) {
        if (accept(Mode.RUNS)) {
            runsToggle.setSelected(true);
            setRows(Mode.RUNS, runs, more);
        }
    }

    private void setRows(Mode mode, List<?> rows, boolean more) {
        placeholder.setText(emptyText(mode)); // may still hold an earlier failure's message
        List<Object> all = new java.util.ArrayList<>(rows);
        if (more) {
            all.add(new MoreRow(rows.size()));
        }
        allItems.setAll(all);
        int select = selectAfterLoad;
        selectAfterLoad = -1;
        if (select >= 0 && select < list.getItems().size()) {
            list.getSelectionModel().clearAndSelect(select);
            list.scrollTo(Math.max(0, select - 1));
        }
    }

    private void activateSelected() {
        Object sel = list.getSelectionModel().getSelectedItem();
        if (sel instanceof MoreRow more) {
            loadMore(more);
        } else if (sel instanceof PullRequest pr) {
            actions.reviewPr(pr.number());
        } else if (sel instanceof Issue issue) {
            actions.openUrl(issue.url());
        } else if (sel instanceof WorkflowRun run) {
            // The failure log is the whole point for a failed run; anything else has nothing local to show.
            if (run.state().failed()) {
                actions.viewRunLog(run.databaseId(), run.workflowName());
            } else {
                actions.openUrl(run.url());
            }
        }
    }

    /**
     * Opens with focus in the filter field — the same flow as the Project / Structure / Bookmarks / Notes
     * windows — with row 0 pre-selected so {@code C-n}/Down/Enter act on something straight away.
     */
    @Override
    public void focusFirstItem() {
        if (!list.getItems().isEmpty() && list.getSelectionModel().isEmpty()) {
            list.getSelectionModel().select(0);
            list.scrollTo(0);
        }
        filterField.requestFocus();
    }

    /**
     * One pull request, issue or workflow run, on one line: a key column (number / workflow), then the title,
     * which takes the remaining width and ellipsizes. An {@code HBox} of labels: the {@code TextFlow} these
     * rows used could not ellipsize and made the list as wide as its longest title. {@code prefWidth 0} makes
     * the list size the cell to its viewport. The one-line row height is CSS
     * ({@code .git-log-panel .git-tree .list-cell}).
     */
    private final class ItemCell extends ListCell<Object> {
        ItemCell() {
            setPrefWidth(0);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        /** {@code lead…} at their natural width, then {@code title} growing and ellipsizing. */
        private void setRow(Label title, Label... lead) {
            title.getStyleClass().add("git-log-subject");
            title.setMinWidth(0);
            title.setPrefWidth(0);
            title.setMaxWidth(Double.MAX_VALUE);
            title.setTextOverrun(OverrunStyle.ELLIPSIS);
            HBox.setHgrow(title, Priority.ALWAYS);
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            for (Label l : lead) {
                l.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
                row.getChildren().add(l);
            }
            row.getChildren().add(title);
            setGraphic(row);
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                setContextMenu(null);
                return;
            }
            setText(null);
            setTooltip(null);
            setContextMenu(null);
            if (item instanceof MoreRow more) {
                Label text = new Label(tr("github.panel.more", more.shown()));
                text.getStyleClass().add("github-more-row");
                text.setMaxWidth(Double.MAX_VALUE);
                text.setOnMouseClicked(e -> loadMore(more)); // one click, like the link it reads as
                setGraphic(text);
            } else if (item instanceof PullRequest pr) {
                renderPr(pr);
            } else if (item instanceof Issue issue) {
                renderIssue(issue);
            } else if (item instanceof WorkflowRun run) {
                renderRun(run);
            }
        }

        private void renderRun(WorkflowRun run) {
            RunState state = run.state();
            Label glyph = new Label(state.glyph());
            glyph.getStyleClass().add(state.cssClass());
            Label workflow = new Label(run.workflowName());
            workflow.getStyleClass().add("git-log-hash"); // the "key" column, like #123 for a PR
            setRow(new Label(run.displayTitle()), glyph, workflow);
            setTooltip(new Tooltip(run.event() + " · " + run.headBranch() + "\n" + when(run.createdAt())));

            List<MenuItem> items = new java.util.ArrayList<>();
            if (state.failed()) {
                items.add(item(
                        tr("github.panel.menu.viewLog"),
                        Icons.terminal(),
                        () -> actions.viewRunLog(run.databaseId(), run.workflowName())));
                items.add(item(
                        tr("github.panel.menu.rerunFailed"),
                        Icons.refresh(),
                        () -> actions.rerunRun(run.databaseId(), true)));
            }
            if (!state.active()) {
                items.add(item(
                        tr("github.panel.menu.rerun"),
                        Icons.refresh(),
                        () -> actions.rerunRun(run.databaseId(), false)));
            } else {
                items.add(item(
                        tr("github.panel.menu.cancel"), Icons.stopSquare(), () -> actions.cancelRun(run.databaseId())));
            }
            items.add(item(tr("github.panel.menu.open"), Icons.github(), () -> actions.openUrl(run.url())));
            items.add(item(tr("github.panel.menu.copyUrl"), Icons.copy(), () -> actions.copyUrl(run.url())));
            setContextMenu(new ContextMenu(items.toArray(new MenuItem[0])));
        }

        private void renderPr(PullRequest pr) {
            Label number = new Label("#" + pr.number());
            number.getStyleClass().add("git-log-hash");
            String title = pr.draft() ? pr.title() + "  " + tr("github.draft") : pr.title();
            // With a state filter other than "open" the rows are of mixed states: say which.
            String state = pr.state() == null ? "" : pr.state().toLowerCase(Locale.ROOT);
            if (!state.isEmpty() && !state.equals("open")) {
                title += "  [" + stateWord(state) + "]";
            }
            setRow(new Label(title), number);
            setTooltip(new Tooltip(pr.authorLogin() + " · " + pr.headRefName() + " → " + pr.baseRefName() + "\n"
                    + when(pr.updatedAt())));
            MenuItem checkout =
                    item(tr("github.panel.menu.checkout"), Icons.git(), () -> actions.checkoutPr(pr.number()));
            MenuItem review = item(tr("github.panel.menu.review"), Icons.diff(), () -> actions.reviewPr(pr.number()));
            MenuItem open = item(tr("github.panel.menu.open"), Icons.github(), () -> actions.openUrl(pr.url()));
            MenuItem copy = item(tr("github.panel.menu.copyUrl"), Icons.copy(), () -> actions.copyUrl(pr.url()));
            setContextMenu(new ContextMenu(checkout, review, open, copy));
        }

        private void renderIssue(Issue issue) {
            Label number = new Label("#" + issue.number());
            number.getStyleClass().add("git-log-hash");
            String labels = issue.labels().isEmpty() ? "" : "  [" + String.join(", ", issue.labels()) + "]";
            String state = issue.state() == null ? "" : issue.state().toLowerCase(Locale.ROOT);
            String closed = !state.isEmpty() && !state.equals("open") ? "  [" + stateWord(state) + "]" : "";
            setRow(new Label(issue.title() + labels + closed), number);
            setTooltip(new Tooltip(issue.authorLogin() + " · " + stateWord(state) + "\n" + when(issue.updatedAt())));
            MenuItem open = item(tr("github.panel.menu.open"), Icons.github(), () -> actions.openUrl(issue.url()));
            MenuItem copy = item(tr("github.panel.menu.copyUrl"), Icons.copy(), () -> actions.copyUrl(issue.url()));
            setContextMenu(new ContextMenu(open, copy));
        }
    }

    /** The localized word for {@code gh}'s lower-cased item state; an unknown one is shown as it is. */
    private static String stateWord(String state) {
        return switch (state) {
            case "open", "closed", "merged" -> tr("github.panel.state." + state);
            default -> state;
        };
    }

    private static MenuItem item(String label, javafx.scene.Node icon, Runnable run) {
        MenuItem m = new MenuItem(label);
        if (icon != null) {
            m.setGraphic(icon);
        }
        m.setOnAction(e -> run.run());
        return m;
    }
}
