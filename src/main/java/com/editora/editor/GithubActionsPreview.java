package com.editora.editor;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import com.editora.ghactions.Workflow;
import com.editora.i18n.Messages;

/**
 * Renders a parsed GitHub Actions {@link Workflow} into the preview: the workflow name, a "Triggered by"
 * line (each {@code on:} event in plain English, a {@code schedule:} cron decoded), then one block per job
 * — its runner, dependencies/condition, and the ordered step list. Self-scrolling; hosts as the Split/Preview
 * side like {@link StructuredTree}. Kept in {@code editor} (no {@code ui} dependency).
 */
public final class GithubActionsPreview {

    private GithubActionsPreview() {}

    public static Node build(Workflow wf) {
        ScrollPane sp = new ScrollPane(content(wf, -1));
        sp.setFitToWidth(true);
        sp.getStyleClass().add("ghactions-preview-scroll");
        return sp;
    }

    public static VBox content(Workflow wf, double width) {
        VBox root = new VBox();
        root.getStyleClass().add("ghactions-preview");
        if (width > 0) {
            root.setPrefWidth(width);
        }

        Label header =
                new Label(wf.name() != null && !wf.name().isBlank() ? wf.name() : Messages.tr("ghactions.untitled"));
        header.getStyleClass().add("ghactions-header");
        header.setWrapText(true);
        root.getChildren().add(header);

        if (!wf.ok()) {
            Label err = new Label(Messages.tr("ghactions.invalid", wf.error()));
            err.getStyleClass().add("ghactions-error");
            err.setWrapText(true);
            root.getChildren().add(err);
            return root;
        }

        String triggers =
                wf.triggers().isEmpty() ? Messages.tr("ghactions.noTriggers") : String.join(", ", wf.triggers());
        Label trig = new Label(Messages.tr("ghactions.triggeredBy", triggers));
        trig.getStyleClass().add("ghactions-triggers");
        trig.setWrapText(true);
        root.getChildren().add(trig);

        Label jobsHeader = new Label(Messages.tr("ghactions.jobs", wf.jobs().size()));
        jobsHeader.getStyleClass().add("ghactions-jobs-header");
        root.getChildren().add(jobsHeader);

        for (Workflow.Job job : wf.jobs()) {
            root.getChildren().add(jobNode(job));
        }
        return root;
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
