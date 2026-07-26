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
import javafx.scene.control.MenuItem;
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
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.github.GitHubItemFilter;
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
            t.setFocusTraversable(false);
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
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(2, prsToggle, issuesToggle, runsToggle, spacer, createPr, refresh);
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
        VBox.setVgrow(list, Priority.ALWAYS);

        getChildren().setAll(toolbar, filterRow, list);
    }

    /** Applies the filter box to the showing segment's rows. */
    private void applyFilter(String query) {
        visibleItems.setPredicate(item -> GitHubItemFilter.matches(item, query));
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
        Button b = new Button();
        b.setGraphic(icon);
        b.getStyleClass().addAll("flat", "git-toolbar-button");
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> action.run());
        return b;
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
            case PRS -> {
                prsToggle.setSelected(true);
                placeholder.setText(tr("github.panel.noPrs"));
            }
            case ISSUES -> {
                issuesToggle.setSelected(true);
                placeholder.setText(tr("github.panel.noIssues"));
            }
            case RUNS -> {
                runsToggle.setSelected(true);
                placeholder.setText(tr("github.panel.noRuns"));
            }
        }
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

    /** Shows the spinner for the segment currently selected — used by a refresh that isn't a segment switch. */
    public void showLoading() {
        beginLoading(mode());
    }

    /** Replaces the list with pull requests. */
    public void setPrs(List<PullRequest> prs) {
        if (!accept(Mode.PRS)) {
            return;
        }
        prsToggle.setSelected(true);
        allItems.setAll(prs);
    }

    /** Replaces the list with issues. */
    public void setIssues(List<Issue> issues) {
        if (!accept(Mode.ISSUES)) {
            return;
        }
        issuesToggle.setSelected(true);
        allItems.setAll(issues);
    }

    /** Replaces the list with workflow runs. */
    public void setRuns(List<WorkflowRun> runs) {
        if (!accept(Mode.RUNS)) {
            return;
        }
        runsToggle.setSelected(true);
        placeholder.setText(tr("github.panel.noRuns"));
        allItems.setAll(runs);
    }

    private void activateSelected() {
        Object sel = list.getSelectionModel().getSelectedItem();
        if (sel instanceof PullRequest pr) {
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

    private final class ItemCell extends ListCell<Object> {
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
            if (item instanceof PullRequest pr) {
                renderPr(pr);
            } else if (item instanceof Issue issue) {
                renderIssue(issue);
            } else if (item instanceof WorkflowRun run) {
                renderRun(run);
            }
        }

        private void renderRun(WorkflowRun run) {
            RunState state = run.state();
            Text glyph = new Text(state.glyph() + " ");
            glyph.getStyleClass().add(state.cssClass());
            Text workflow = new Text(run.workflowName());
            workflow.getStyleClass().add("git-log-hash"); // the "key" column, like #123 for a PR
            Text title = new Text("  " + run.displayTitle());
            title.getStyleClass().add("git-log-subject");
            setGraphic(new TextFlow(glyph, workflow, title));
            setTooltip(new Tooltip(run.event() + " · " + run.headBranch() + "\n" + run.createdAt()));

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
            Text number = new Text("#" + pr.number());
            number.getStyleClass().add("git-log-hash");
            Text title = new Text("  " + pr.title());
            title.getStyleClass().add("git-log-subject");
            TextFlow flow = new TextFlow(number, title);
            if (pr.draft()) {
                Text draft = new Text("  " + tr("github.draft"));
                draft.getStyleClass().add("git-log-subject");
                flow.getChildren().add(draft);
            }
            setGraphic(flow);
            setTooltip(new Tooltip(
                    pr.authorLogin() + " · " + pr.headRefName() + " → " + pr.baseRefName() + "\n" + pr.updatedAt()));
            MenuItem checkout =
                    item(tr("github.panel.menu.checkout"), Icons.git(), () -> actions.checkoutPr(pr.number()));
            MenuItem review = item(tr("github.panel.menu.review"), Icons.diff(), () -> actions.reviewPr(pr.number()));
            MenuItem open = item(tr("github.panel.menu.open"), Icons.github(), () -> actions.openUrl(pr.url()));
            MenuItem copy = item(tr("github.panel.menu.copyUrl"), Icons.copy(), () -> actions.copyUrl(pr.url()));
            setContextMenu(new ContextMenu(checkout, review, open, copy));
        }

        private void renderIssue(Issue issue) {
            Text number = new Text("#" + issue.number());
            number.getStyleClass().add("git-log-hash");
            String labels = issue.labels().isEmpty() ? "" : "  [" + String.join(", ", issue.labels()) + "]";
            Text title = new Text("  " + issue.title() + labels);
            title.getStyleClass().add("git-log-subject");
            setGraphic(new TextFlow(number, title));
            setTooltip(new Tooltip(issue.authorLogin() + " · " + issue.state() + "\n" + issue.updatedAt()));
            MenuItem open = item(tr("github.panel.menu.open"), Icons.github(), () -> actions.openUrl(issue.url()));
            MenuItem copy = item(tr("github.panel.menu.copyUrl"), Icons.copy(), () -> actions.copyUrl(issue.url()));
            setContextMenu(new ContextMenu(open, copy));
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
}
