package com.editora.editor.structured;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.ghactions.Workflow;
import com.editora.i18n.Messages;

/**
 * Renders a parsed GitHub Actions {@link Workflow} into the preview: the workflow name, a "Triggered by"
 * line (each {@code on:} event in plain English, a {@code schedule:} cron decoded), then one block per job
 * — its runner, dependencies/condition, and the ordered step list. Self-scrolling; hosts as the Split/Preview
 * side like {@link StructuredTree}. Kept in {@code editor} (no {@code ui} dependency).
 *
 * <p>The live preview is a virtualized list of those rows ({@link PreviewList}, PERF-08, Pauta 6.0.1); {@link
 * #content} builds them all, for an export or a print.
 */
public final class GithubActionsPreview {

    private GithubActionsPreview() {}

    public static Node build(Workflow wf) {
        return PreviewList.of(rows(wf), "ghactions-preview-list");
    }

    public static VBox content(Workflow wf, double width) {
        return PreviewList.column(rows(wf), width, "ghactions-preview");
    }

    /** The name, then the error, or the triggers, the jobs' heading and each job. */
    private static List<Supplier<Node>> rows(Workflow wf) {
        List<Supplier<Node>> rows = new ArrayList<>();
        rows.add(() -> {
            Label header = new Label(
                    wf.name() != null && !wf.name().isBlank() ? wf.name() : Messages.tr("ghactions.untitled"));
            header.getStyleClass().add("ghactions-header");
            header.setWrapText(true);
            return header;
        });

        if (!wf.ok()) {
            rows.add(() -> {
                Label err = new Label(Messages.tr("ghactions.invalid", wf.error()));
                err.getStyleClass().add("ghactions-error");
                err.setWrapText(true);
                return err;
            });
            return rows;
        }

        String triggers =
                wf.triggers().isEmpty() ? Messages.tr("ghactions.noTriggers") : String.join(", ", wf.triggers());
        rows.add(() -> {
            Label trig = new Label(Messages.tr("ghactions.triggeredBy", triggers));
            trig.getStyleClass().add("ghactions-triggers");
            trig.setWrapText(true);
            return trig;
        });
        rows.add(() -> {
            Label jobsHeader = new Label(Messages.tr("ghactions.jobs", wf.jobs().size()));
            jobsHeader.getStyleClass().add("ghactions-jobs-header");
            return jobsHeader;
        });
        for (Workflow.Job job : wf.jobs()) {
            rows.add(() -> jobNode(job));
        }
        return rows;
    }

    private static Node jobNode(Workflow.Job job) {
        VBox box = new VBox();
        box.getStyleClass().add("ghactions-job");

        Label title = new Label(job.name() != null && !job.name().isBlank() ? job.name() : job.id());
        title.getStyleClass().add("ghactions-job-title");
        title.setWrapText(true);
        box.getChildren().add(title);

        StringBuilder meta = new StringBuilder();
        if (job.runsOn() != null) {
            meta.append(Messages.tr("ghactions.runsOn", job.runsOn()));
        }
        if (!job.needs().isEmpty()) {
            meta.append(meta.length() > 0 ? " · " : "")
                    .append(Messages.tr("ghactions.needs", String.join(", ", job.needs())));
        }
        if (job.matrix()) {
            meta.append(meta.length() > 0 ? " · " : "").append(Messages.tr("ghactions.matrix"));
        }
        if (meta.length() > 0) {
            Label m = new Label(meta.toString());
            m.getStyleClass().add("ghactions-job-meta");
            m.setWrapText(true);
            box.getChildren().add(m);
        }
        if (job.ifCond() != null && !job.ifCond().isBlank()) {
            Label c = new Label(Messages.tr("ghactions.onlyIf", job.ifCond()));
            c.getStyleClass().add("ghactions-job-if");
            c.setWrapText(true);
            box.getChildren().add(c);
        }

        List<Workflow.Step> steps = job.steps();
        for (int i = 0; i < steps.size(); i++) {
            Label s = new Label((i + 1) + ". " + steps.get(i).label());
            s.getStyleClass().add("ghactions-step");
            s.setWrapText(true);
            box.getChildren().add(s);
        }
        return box;
    }
}
