package com.editora.editor.structured;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import javafx.beans.binding.DoubleBinding;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.cron.CronExpression;
import com.editora.cron.Crontab;
import com.editora.editor.UiZoom;
import com.editora.i18n.Messages;

/**
 * Renders a parsed {@link Crontab} into the crontab schedule preview: a header (job count + the moment the
 * next-run times are computed from), any {@code NAME=value} environment assignments, then one block per job
 * showing its command, the schedule decoded into English ({@link com.editora.cron.CronExpression#describe()}),
 * and the upcoming fire times. A malformed job renders as a red error row. Self-scrolling, so it hosts directly
 * as the Split/Preview side, like {@link StructuredTree}. Kept in {@code editor} (no {@code ui} dependency).
 *
 * <p>The live preview is a virtualized list (PERF-08, Pauta 6.2.0): a crontab can hold any number of jobs, and
 * only the rows on screen have nodes. {@link #content} still makes a node for every row: it is the picture an
 * export or a print takes, which has to hold them all.
 */
public final class CrontabPreview {

    /** How many upcoming fire times to show per job. */
    private static final int NEXT_RUNS = 3;

    private static final DateTimeFormatter RUN_FMT =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm", Locale.getDefault());
    private static final DateTimeFormatter HEADER_FMT =
            DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.getDefault());

    private CrontabPreview() {}

    /** One row of the live preview: the header, an environment assignment or a job. */
    private sealed interface Row {}

    private record Header(String text) implements Row {}

    private record Assign(Crontab.Assignment assignment) implements Row {}

    private record JobRow(Crontab.Job job) implements Row {}

    /** What the list's own padding and its scroll bar take from a row's width. */
    private static final double ROW_CHROME = 36;

    /** The left and right padding of {@code .crontab-job-error} in {@code app.css}, together. */
    private static final double ERROR_BOX_PADDING = 16;

    /** The live self-scrolling preview node for the tool host: the rows {@link #content} shows, in a list. */
    public static Node build(Crontab crontab, LocalDateTime now) {
        ListView<Row> list = new ListView<>();
        list.getStyleClass().add("crontab-preview-list");
        // A read-out, as the column of labels was: it takes no Tab stop and shows no selected row.
        list.setFocusTraversable(false);
        list.getItems().add(new Header(summary(crontab, now)));
        for (Crontab.Assignment a : crontab.assignments()) {
            list.getItems().add(new Assign(a));
        }
        for (Crontab.Job job : crontab.jobs()) {
            list.getItems().add(new JobRow(job));
        }
        // A wrapping label needs a definite width to say how tall it is: the list's, less its own chrome.
        DoubleBinding rowWidth = javafx.beans.binding.Bindings.createDoubleBinding(
                () -> Math.max(UiZoom.px(60), list.getWidth() - UiZoom.px(ROW_CHROME)),
                list.widthProperty(),
                UiZoom.factorProperty());
        list.setCellFactory(view -> new ListCell<>() {
            {
                setPrefWidth(0); // never wider than the list: the text wraps, it does not scroll sideways
            }

            @Override
            protected void updateItem(Row row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                Region node = switch (row) {
                    case Header h -> headerNode(h.text());
                    case Assign a -> assignNode(a.assignment());
                    case JobRow j -> jobNode(j.job(), now);
                };
                node.prefWidthProperty().bind(rowWidth);
                node.maxWidthProperty().bind(rowWidth);
                node.setMinHeight(Region.USE_PREF_SIZE);
                if (row instanceof JobRow j && node instanceof VBox box) {
                    // The job's lines wrap inside the box, which pads them when it is the red error box.
                    double padding = j.job().ok() ? 0 : ERROR_BOX_PADDING;
                    for (Node line : box.getChildren()) {
                        if (line instanceof Label label) {
                            label.maxWidthProperty().bind(rowWidth.subtract(padding));
                            label.setMinHeight(Region.USE_PREF_SIZE);
                        }
                    }
                }
                setGraphic(node);
            }
        });
        // A row's height follows its wrapped text, so a new width asks the rows on screen for their height again.
        list.widthProperty().addListener((o, was, now2) -> list.refresh());
        return list;
    }

    private static String summary(Crontab crontab, LocalDateTime now) {
        return Messages.tr("crontab.summary", crontab.jobs().size(), now.format(HEADER_FMT));
    }

    private static Label headerNode(String text) {
        Label header = new Label(text);
        header.getStyleClass().add("crontab-header");
        header.setWrapText(true);
        return header;
    }

    private static Label assignNode(Crontab.Assignment a) {
        Label assign = new Label(a.name() + " = " + a.value());
        assign.getStyleClass().add("crontab-assign");
        assign.setWrapText(true);
        return assign;
    }

    /** The bare content VBox (used for the light snapshot export); {@code width > 0} pins a layout width. */
    public static VBox content(Crontab crontab, LocalDateTime now, double width) {
        VBox root = new VBox();
        root.getStyleClass().add("crontab-preview");
        if (width > 0) {
            root.setPrefWidth(width);
        }

        root.getChildren().add(headerNode(summary(crontab, now)));

        for (Crontab.Assignment a : crontab.assignments()) {
            root.getChildren().add(assignNode(a));
        }

        for (Crontab.Job job : crontab.jobs()) {
            root.getChildren().add(jobNode(job, now));
        }
        return root;
    }

    private static VBox jobNode(Crontab.Job job, LocalDateTime now) {
        VBox box = new VBox();
        box.getStyleClass().add(job.ok() ? "crontab-job" : "crontab-job-error");

        Label command = new Label(job.command().isEmpty() ? job.rawSchedule() : job.command());
        command.getStyleClass().add("crontab-command");
        command.setWrapText(true);
        box.getChildren().add(command);

        if (!job.ok() || !(job.expr() instanceof CronExpression expr)) {
            Label err = new Label(Messages.tr("crontab.invalid", job.error()));
            err.getStyleClass().add("crontab-error");
            err.setWrapText(true);
            box.getChildren().add(err);
            return box;
        }

        Label schedule = new Label(expr.describe());
        schedule.getStyleClass().add("crontab-schedule");
        schedule.setWrapText(true);
        box.getChildren().add(schedule);

        Label next = new Label(nextRunsText(expr, now));
        next.getStyleClass().add("crontab-nextruns");
        next.setWrapText(true);
        box.getChildren().add(next);
        return box;
    }

    private static String nextRunsText(CronExpression expr, LocalDateTime now) {
        if (expr.isReboot()) {
            return Messages.tr("crontab.onReboot");
        }
        List<LocalDateTime> runs = expr.nextRuns(now, NEXT_RUNS);
        if (runs.isEmpty()) {
            return Messages.tr("crontab.noRuns");
        }
        StringJoiner sj = new StringJoiner(", ");
        for (LocalDateTime r : runs) {
            sj.add(r.format(RUN_FMT));
        }
        return "→ " + sj;
    }
}
