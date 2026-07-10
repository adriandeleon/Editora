package com.editora.editor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import com.editora.cron.Crontab;
import com.editora.i18n.Messages;

/**
 * Renders a parsed {@link Crontab} into the crontab schedule preview: a header (job count + the moment the
 * next-run times are computed from), any {@code NAME=value} environment assignments, then one block per job
 * showing its command, the schedule decoded into English ({@link com.editora.cron.CronExpression#describe()}),
 * and the upcoming fire times. A malformed job renders as a red error row. Self-scrolling (wrapped in a
 * {@code ScrollPane}) so it hosts directly as the Split/Preview side, like {@link StructuredTree}. Kept in
 * {@code editor} (no {@code ui} dependency).
 */
public final class CrontabPreview {

    /** How many upcoming fire times to show per job. */
    private static final int NEXT_RUNS = 3;

    private static final DateTimeFormatter RUN_FMT =
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm", Locale.getDefault());
    private static final DateTimeFormatter HEADER_FMT =
            DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.getDefault());

    private CrontabPreview() {}

    /** The live self-scrolling preview node for the tool host. */
    public static Node build(Crontab crontab, LocalDateTime now) {
        ScrollPane sp = new ScrollPane(content(crontab, now, -1));
        sp.setFitToWidth(true);
        sp.getStyleClass().add("crontab-preview-scroll");
        return sp;
    }

    /** The bare content VBox (used for the light snapshot export); {@code width > 0} pins a layout width. */
    public static VBox content(Crontab crontab, LocalDateTime now, double width) {
        VBox root = new VBox();
        root.getStyleClass().add("crontab-preview");
        if (width > 0) {
            root.setPrefWidth(width);
        }

        Label header = new Label(Messages.tr("crontab.summary", crontab.jobs().size(), now.format(HEADER_FMT)));
        header.getStyleClass().add("crontab-header");
        header.setWrapText(true);
        root.getChildren().add(header);

        for (Crontab.Assignment a : crontab.assignments()) {
            Label assign = new Label(a.name() + " = " + a.value());
            assign.getStyleClass().add("crontab-assign");
            assign.setWrapText(true);
            root.getChildren().add(assign);
        }

        for (Crontab.Job job : crontab.jobs()) {
            root.getChildren().add(jobNode(job, now));
        }
        return root;
    }

    private static Node jobNode(Crontab.Job job, LocalDateTime now) {
        VBox box = new VBox();
        box.getStyleClass().add(job.ok() ? "crontab-job" : "crontab-job-error");

        Label command = new Label(job.command().isEmpty() ? job.rawSchedule() : job.command());
        command.getStyleClass().add("crontab-command");
        command.setWrapText(true);
        box.getChildren().add(command);

        if (!job.ok()) {
            Label err = new Label(Messages.tr("crontab.invalid", job.error()));
            err.getStyleClass().add("crontab-error");
            err.setWrapText(true);
            box.getChildren().add(err);
            return box;
        }

        Label schedule = new Label(job.expr().describe());
        schedule.getStyleClass().add("crontab-schedule");
        schedule.setWrapText(true);
        box.getChildren().add(schedule);

        Label next = new Label(nextRunsText(job, now));
        next.getStyleClass().add("crontab-nextruns");
        next.setWrapText(true);
        box.getChildren().add(next);
        return box;
    }

    private static String nextRunsText(Crontab.Job job, LocalDateTime now) {
        if (job.expr().isReboot()) {
            return Messages.tr("crontab.onReboot");
        }
        List<LocalDateTime> runs = job.expr().nextRuns(now, NEXT_RUNS);
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
