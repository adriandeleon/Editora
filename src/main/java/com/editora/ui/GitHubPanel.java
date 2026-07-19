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

import static com.editora.i18n.Messages.tr;

/**
 * The GitHub tool window: a segmented <b>Pull Requests | Issues</b> list. Selecting a mode asks the controller
 * (via {@link Actions}) to fetch the list; double-click / Enter reviews a PR's diff (or opens an issue on
 * GitHub); a row's context menu offers check-out / review-diff / open-on-GitHub / copy-URL. Purely a view —
 * the controller (through {@code GitHubCoordinator}) runs {@code gh}. Registered default-hidden and available
 * only inside a GitHub repo.
 */
public final class GitHubPanel extends VBox implements ToolWindowContent {

    /** Operations the panel asks the controller to perform. */
    public interface Actions {
        void refresh();

        void showPrs();

        void showIssues();

        void checkoutPr(int number);

        void reviewPr(int number);

        void openUrl(String url);

        void copyUrl(String url);
    }

    private final Actions actions;
    private final ToggleButton prsToggle = new ToggleButton(tr("github.panel.prs"));
    private final ToggleButton issuesToggle = new ToggleButton(tr("github.panel.issues"));
    private final ListView<Object> list = new ListView<>();
    private final Label placeholder = new Label(tr("github.panel.noPrs"));

    public GitHubPanel(Actions actions) {
        this.actions = actions;
        getStyleClass().add("git-log-panel");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(4);
        setPadding(new Insets(4));

        ToggleGroup group = new ToggleGroup();
        prsToggle.setToggleGroup(group);
        issuesToggle.setToggleGroup(group);
        prsToggle.setSelected(true);
        prsToggle.getStyleClass().add("github-tab");
        issuesToggle.getStyleClass().add("github-tab");
        prsToggle.setFocusTraversable(false);
        issuesToggle.setFocusTraversable(false);
        // A toggle group lets a selected button be re-clicked to deselect; keep exactly one selected.
        prsToggle.setOnAction(e -> {
            prsToggle.setSelected(true);
            placeholder.setText(tr("github.panel.noPrs"));
            actions.showPrs();
        });
        issuesToggle.setOnAction(e -> {
            issuesToggle.setSelected(true);
            placeholder.setText(tr("github.panel.noIssues"));
            actions.showIssues();
        });

        Button refresh = iconButton(Icons.refresh(), tr("github.panel.refreshTip"), actions::refresh);
        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(2, prsToggle, issuesToggle, spacer, refresh);
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

    /** Whether the panel is currently showing pull requests (vs. issues) — the controller re-fetches this on refresh. */
    public boolean showingPrs() {
        return prsToggle.isSelected();
    }

    /** Replaces the list with pull requests. */
    public void setPrs(List<PullRequest> prs) {
        prsToggle.setSelected(true);
        list.getItems().setAll(prs);
    }

    /** Replaces the list with issues. */
    public void setIssues(List<Issue> issues) {
        issuesToggle.setSelected(true);
        list.getItems().setAll(issues);
    }

    private void activateSelected() {
        Object sel = list.getSelectionModel().getSelectedItem();
        if (sel instanceof PullRequest pr) {
            actions.reviewPr(pr.number());
        } else if (sel instanceof Issue issue) {
            actions.openUrl(issue.url());
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
            }
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
