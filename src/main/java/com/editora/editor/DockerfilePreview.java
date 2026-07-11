package com.editora.editor;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

import com.editora.dockerfile.Dockerfile;
import com.editora.dockerfile.DockerfileDescribe;
import com.editora.i18n.Messages;

/**
 * Renders a parsed {@link Dockerfile} into the preview: a header (stage count), any global build args, then
 * one block per build stage — its title (base image + name) and the curated "what it does" facts (exposed
 * ports, workdir, user, env, entrypoint/command, health check, build-step count). Self-scrolling; hosts as
 * the Split/Preview side like {@link StructuredTree}. Kept in {@code editor} (no {@code ui} dependency).
 */
public final class DockerfilePreview {

    private DockerfilePreview() {}

    public static Node build(Dockerfile dockerfile) {
        ScrollPane sp = new ScrollPane(content(dockerfile, -1));
        sp.setFitToWidth(true);
        sp.getStyleClass().add("dockerfile-preview-scroll");
        return sp;
    }

    public static VBox content(Dockerfile d, double width) {
        VBox root = new VBox();
        root.getStyleClass().add("dockerfile-preview");
        if (width > 0) {
            root.setPrefWidth(width);
        }

        Label header = new Label(Messages.tr("dockerfile.summary", d.stages().size()));
        header.getStyleClass().add("dockerfile-header");
        header.setWrapText(true);
        root.getChildren().add(header);

        for (Dockerfile.Instruction arg : d.globalArgs()) {
            Label a = new Label("ARG " + arg.args());
            a.getStyleClass().add("dockerfile-arg");
            a.setWrapText(true);
            root.getChildren().add(a);
        }

        int last = d.stages().size() - 1;
        for (int i = 0; i < d.stages().size(); i++) {
            root.getChildren().add(stageNode(d.stages().get(i), i == last));
        }
        return root;
    }

    private static Node stageNode(Dockerfile.Stage stage, boolean isFinal) {
        VBox box = new VBox();
        box.getStyleClass().add("dockerfile-stage");

        Label title = new Label(DockerfileDescribe.title(stage, isFinal));
        title.getStyleClass().add("dockerfile-title");
        title.setWrapText(true);
        box.getChildren().add(title);

        for (String fact : DockerfileDescribe.summaryLines(stage)) {
            Label f = new Label(fact);
            f.getStyleClass().add("dockerfile-fact");
            f.setWrapText(true);
            box.getChildren().add(f);
        }
        return box;
    }
}
