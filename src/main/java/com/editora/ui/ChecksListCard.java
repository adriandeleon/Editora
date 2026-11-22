package com.editora.ui;

import java.util.List;
import java.util.Locale;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.editor.UiZoom;
import com.editora.github.ChecksParser;
import com.editora.github.ChecksParser.CheckRun;
import com.editora.github.ChecksPoll;
import com.editora.github.GitHubService;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The list behind the status bar's pull-request checks segment: one row per check — state glyph, name, its
 * workflow, a link to it on GitHub and, for a failed GitHub Actions job, its failure log — in an in-scene
 * card ({@link OverlayHost}). Failed checks come first, then pending ones: they are why the list was opened.
 * The rows are a virtualized list (PERF-08, Pauta 6.2.0): a pull request can have any number of checks, and only
 * the rows on screen have nodes. The list is one Tab stop: the arrow keys move along the rows and Enter runs the
 * selected row's first action (its failure log, else its page on GitHub); the links of the rows on screen and
 * the buttons under the list are Tab stops as before, and Esc closes the card.
 * Purely a view; {@code GitHubCoordinator} feeds it and runs its actions.
 */
final class ChecksListCard extends VBox {

    /** What a row or the footer asks for. */
    interface Actions {
        void open(String url);

        void viewFailedLog(long runId, String name);

        void refresh();

        void close();
    }

    /** Past this many rows the list scrolls instead of growing the card. */
    private static final int VISIBLE_ROWS = 12;

    private static final double ROW_HEIGHT = 26;

    private final Actions actions;
    private final Label title = new Label();
    private final Label summary = new Label();
    private final ListView<CheckRun> list = new ListView<>();
    private final Button refresh = new Button(tr("github.checks.refresh"));

    ChecksListCard(Actions actions) {
        this.actions = actions;
        getStyleClass().addAll("command-palette", "overlay-form", "github-checks-card");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        UiZoom.spacing(this, 8);
        UiZoom.padding(this, 12);
        UiZoom.minWidth(this, 420);
        UiZoom.maxSize(this, 560, Region.USE_PREF_SIZE);

        title.getStyleClass().add("palette-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMaxWidth(Double.MAX_VALUE);
        summary.getStyleClass().add("overlay-note");

        list.getStyleClass().add("github-checks-list");
        list.setCellFactory(view -> new CheckCell());
        UiZoom.follow(list, "cellSize", () -> list.setFixedCellSize(UiZoom.px(ROW_HEIGHT)));
        list.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            // On the list itself: Enter on a row's own link or button is that control's.
            if (e.getTarget() == list && e.getCode() == KeyCode.ENTER && !e.isShortcutDown() && !e.isAltDown()) {
                CheckRun run = list.getSelectionModel().getSelectedItem();
                if (run != null && activate(run)) {
                    e.consume();
                }
            }
        });
        showRows(0);

        refresh.setOnAction(e -> actions.refresh());
        Button close = new Button(tr("dialog.close"));
        close.setOnAction(e -> actions.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = UiZoom.spacing(new HBox(summary, spacer, refresh, close), 8);
        footer.setAlignment(Pos.CENTER_LEFT);

        getChildren().setAll(title, list, footer);
    }

    /** Gives the list the height of {@code count} rows; past {@link #VISIBLE_ROWS} it scrolls. */
    private void showRows(int count) {
        double height = Math.min(count, VISIBLE_ROWS) * ROW_HEIGHT;
        UiZoom.follow(list, "height", () -> {
            double h = UiZoom.px(height);
            list.setMinHeight(h);
            list.setPrefHeight(h);
            list.setMaxHeight(h);
        });
    }

    /** Runs {@code run}'s first action: its failure log when it has one, else its page; false when it has neither. */
    private boolean activate(CheckRun run) {
        long runId = ChecksPoll.runId(run.link());
        if ("fail".equalsIgnoreCase(run.bucket()) && runId > 0) {
            actions.viewFailedLog(runId, run.name());
            return true;
        }
        if (run.link() != null && !run.link().isBlank()) {
            actions.open(run.link());
            return true;
        }
        return false;
    }

    /** Shows {@code checks} (a poll updates the open card in place); {@code null} empties the list. */
    void update(GitHubService.@Nullable BranchChecks checks) {
        if (checks == null || checks.pr() == null) {
            list.getItems().clear();
            title.setText(tr("github.checks.none"));
            summary.setText("");
            showRows(0);
            return;
        }
        title.setText(
                tr("github.checks.title", checks.pr().number(), checks.pr().title()));
        ChecksParser.ChecksSummary s = ChecksParser.ChecksSummary.of(checks.runs());
        summary.setText(tr("github.checks.summary", s.pass(), s.fail(), s.pending(), s.skipped()));
        // A poll updates the open card in place: the row the keyboard is on stays the one it is on.
        int selected = list.getSelectionModel().getSelectedIndex();
        list.getItems().setAll(ordered(checks.runs()));
        if (selected >= 0 && !list.getItems().isEmpty()) {
            list.getSelectionModel().select(Math.min(selected, list.getItems().size() - 1));
        }
        showRows(checks.runs().size());
    }

    /** A row of the list: the check's own line, built for the rows on screen only. */
    private final class CheckCell extends ListCell<CheckRun> {
        CheckCell() {
            setPrefWidth(0); // never wider than the list: a long name is cut with an ellipsis, as before
        }

        @Override
        protected void updateItem(CheckRun run, boolean empty) {
            super.updateItem(run, empty);
            setText(null);
            if (empty || run == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            setGraphic(row(run));
            setAccessibleText(glyph(run.bucket())[0] + " " + run.name() + " " + run.workflow());
        }
    }

    /** Failed first, then pending, then the rest — each group in {@code gh}'s order. Pure. */
    static List<CheckRun> ordered(List<CheckRun> runs) {
        return runs.stream()
                .sorted(java.util.Comparator.comparingInt(r -> rank(r.bucket())))
                .toList();
    }

    private static int rank(String bucket) {
        return switch (bucket == null ? "" : bucket.toLowerCase(Locale.ROOT)) {
            case "fail", "cancel" -> 0;
            case "pending" -> 1;
            case "pass" -> 2;
            default -> 3;
        };
    }

    /** The state glyph and its colour class for a check's bucket (the run-list palette). Pure. */
    static String[] glyph(String bucket) {
        return switch (bucket == null ? "" : bucket.toLowerCase(Locale.ROOT)) {
            case "pass" -> new String[] {"✓", "github-run-success"};
            case "fail" -> new String[] {"✗", "github-run-failure"};
            case "cancel" -> new String[] {"⊘", "github-run-queued"};
            case "pending" -> new String[] {"○", "github-run-running"};
            default -> new String[] {"–", "github-run-queued"};
        };
    }

    private Node row(CheckRun run) {
        String[] g = glyph(run.bucket());
        Label state = new Label(g[0]);
        state.getStyleClass().add(g[1]);
        UiZoom.minWidth(state, 16);
        Label name = new Label(run.name());
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label workflow = new Label(run.workflow());
        workflow.getStyleClass().add("overlay-note");
        workflow.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = UiZoom.spacing(new HBox(state, name, workflow, spacer), 8);
        box.setAlignment(Pos.CENTER_LEFT);
        UiZoom.minHeight(box, ROW_HEIGHT - 2);
        box.getStyleClass().addAll("git-tree", "github-check-row");
        long runId = ChecksPoll.runId(run.link());
        if ("fail".equalsIgnoreCase(run.bucket()) && runId > 0) {
            Hyperlink log = new Hyperlink(tr("github.checks.viewLog"));
            log.setMinWidth(Region.USE_PREF_SIZE);
            log.setOnAction(e -> actions.viewFailedLog(runId, run.name()));
            box.getChildren().add(log);
        }
        if (run.link() != null && !run.link().isBlank()) {
            Hyperlink open = new Hyperlink(tr("github.checks.open"));
            open.setMinWidth(Region.USE_PREF_SIZE);
            open.setAccessibleText(tr("github.checks.openNamed", run.name()));
            open.setOnAction(e -> actions.open(run.link()));
            box.getChildren().add(open);
        }
        return box;
    }

    /** Puts the focus on the list, on its first row, or on Refresh when there is no row. */
    void focusFirst() {
        if (list.getItems().isEmpty()) {
            refresh.requestFocus();
            return;
        }
        if (list.getSelectionModel().isEmpty()) {
            list.getSelectionModel().select(0);
        }
        list.requestFocus();
    }

    /** The rows on screen, top to bottom, as {@code glyph name}: read from the rows' own labels. For tests. */
    List<String> rowTexts() {
        return list.lookupAll(".github-check-row").stream()
                .filter(n -> n.getParent() instanceof ListCell<?> cell && !cell.isEmpty())
                .sorted(java.util.Comparator.comparingInt(n -> ((ListCell<?>) n.getParent()).getIndex()))
                .map(n -> {
                    HBox box = (HBox) n;
                    return ((Label) box.getChildren().get(0)).getText() + " "
                            + ((Label) box.getChildren().get(1)).getText();
                })
                .toList();
    }

    /** How many rows have nodes right now: the rows on screen, whatever the number of checks. For tests. */
    int rowNodes() {
        return list.lookupAll(".github-check-row").size();
    }
}
