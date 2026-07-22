package com.editora.ui;

import java.util.List;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

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

        /** Dumps a failed run's log into the shared Build Output console. */
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
            prsToggle.setSelected(true);
            placeholder.setText(tr("github.panel.noPrs"));
            beginLoading(Mode.PRS);
            actions.showPrs();
        });
        issuesToggle.setOnAction(e -> {
            issuesToggle.setSelected(true);
            placeholder.setText(tr("github.panel.noIssues"));
            beginLoading(Mode.ISSUES);
            actions.showIssues();
        });
        runsToggle.setOnAction(e -> {
            runsToggle.setSelected(true);
            placeholder.setText(tr("github.panel.noRuns"));
            beginLoading(Mode.RUNS);
            actions.showRuns();
        });

        Button createPr = iconButton(Icons.newFile(), tr("github.panel.createPrTip"), actions::createPr);
        Button refresh = iconButton(Icons.refresh(), tr("github.panel.refreshTip"), actions::refresh);
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(2, prsToggle, issuesToggle, runsToggle, spacer, createPr, refresh);
        toolbar.getStyleClass().add("git-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        list.getStyleClass().add("git-tree");
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
        VBox.setVgrow(list, Priority.ALWAYS);

        getChildren().setAll(toolbar, list);
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
     * Clears the stale list and shows a spinner while {@code gh} runs — a {@code gh pr list} round-trip takes
     * seconds, and leaving the previous segment's rows on screen reads as a frozen window.
     */
    private void beginLoading(Mode mode) {
        requested = mode;
        list.getItems().clear();
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
        runsToggle.setSelected(true);
        placeholder.setText(tr("github.panel.noRuns"));
        beginLoading(Mode.RUNS);
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
        list.getItems().setAll(prs);
    }

    /** Replaces the list with issues. */
    public void setIssues(List<Issue> issues) {
        if (!accept(Mode.ISSUES)) {
            return;
        }
        issuesToggle.setSelected(true);
        list.getItems().setAll(issues);
    }

    /** Replaces the list with workflow runs. */
    public void setRuns(List<WorkflowRun> runs) {
        if (!accept(Mode.RUNS)) {
            return;
        }
        runsToggle.setSelected(true);
        placeholder.setText(tr("github.panel.noRuns"));
        list.getItems().setAll(runs);
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

    @Override
    public void focusFirstItem() {
        if (!list.getItems().isEmpty() && list.getSelectionModel().isEmpty()) {
            list.getSelectionModel().select(0);
            list.scrollTo(0);
        }
        list.requestFocus();
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
