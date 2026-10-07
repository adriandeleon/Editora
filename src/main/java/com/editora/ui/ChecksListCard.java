package com.editora.ui;

import java.util.List;
import java.util.Locale;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.github.ChecksParser;
import com.editora.github.ChecksParser.CheckRun;
import com.editora.github.ChecksPoll;
import com.editora.github.GitHubService;

import static com.editora.i18n.Messages.tr;

/**
 * The list behind the status bar's pull-request checks segment: one row per check — state glyph, name, its
 * workflow, a link to it on GitHub and, for a failed GitHub Actions job, its failure log — in an in-scene
 * card ({@link OverlayHost}). Failed checks come first, then pending ones: they are why the list was opened.
 * Every action is a focusable link or button, so the card is usable from the keyboard (Tab, Enter, Esc).
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
    private final VBox rows = new VBox(2);
    private final ScrollPane scroll = new ScrollPane(rows);
    private final Button refresh = new Button(tr("github.checks.refresh"));

    ChecksListCard(Actions actions) {
        this.actions = actions;
        getStyleClass().addAll("command-palette", "overlay-form", "github-checks-card");
        getProperties().put("editora.ownsKeys", Boolean.TRUE);
        setSpacing(8);
        setPadding(new Insets(12));
        setMinWidth(420);
        setMaxSize(560, Region.USE_PREF_SIZE);

        title.getStyleClass().add("palette-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        title.setMaxWidth(Double.MAX_VALUE);
        summary.getStyleClass().add("overlay-note");

        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("github-checks-scroll");

        refresh.setOnAction(e -> actions.refresh());
        Button close = new Button(tr("dialog.close"));
        close.setOnAction(e -> actions.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(8, summary, spacer, refresh, close);
        footer.setAlignment(Pos.CENTER_LEFT);

        getChildren().setAll(title, scroll, footer);
    }

    /** Shows {@code checks} (a poll updates the open card in place); {@code null} empties the list. */
    void update(GitHubService.BranchChecks checks) {
        rows.getChildren().clear();
        if (checks == null || checks.pr() == null) {
            title.setText(tr("github.checks.none"));
            summary.setText("");
            scroll.setPrefViewportHeight(0);
            return;
        }
        title.setText(
                tr("github.checks.title", checks.pr().number(), checks.pr().title()));
        ChecksParser.ChecksSummary s = ChecksParser.ChecksSummary.of(checks.runs());
        summary.setText(tr("github.checks.summary", s.pass(), s.fail(), s.pending(), s.skipped()));
        for (CheckRun run : ordered(checks.runs())) {
            rows.getChildren().add(row(run));
        }
        scroll.setPrefViewportHeight(Math.min(checks.runs().size(), VISIBLE_ROWS) * ROW_HEIGHT);
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
        state.setMinWidth(16);
        Label name = new Label(run.name());
        name.setMinWidth(0);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label workflow = new Label(run.workflow());
        workflow.getStyleClass().add("overlay-note");
        workflow.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(8, state, name, workflow, spacer);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMinHeight(ROW_HEIGHT - 2);
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

    /** Puts the focus on the first row's first link, or on Refresh when no row has one. */
    void focusFirst() {
        Node target = OverlayHost.edgeFocusable(rows, false);
        (target != null ? target : refresh).requestFocus();
    }

    /** The rows on screen, as {@code glyph name}. For tests. */
    List<String> rowTexts() {
        return rows.getChildren().stream()
                .map(n -> {
                    HBox box = (HBox) n;
                    return ((Label) box.getChildren().get(0)).getText() + " "
                            + ((Label) box.getChildren().get(1)).getText();
                })
                .toList();
    }
}
