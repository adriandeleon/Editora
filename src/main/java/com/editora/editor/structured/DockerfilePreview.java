package com.editora.editor.structured;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

import com.editora.dockerfile.Dockerfile;
import com.editora.dockerfile.DockerfileDescribe;
import com.editora.i18n.Messages;

/**
 * Renders a parsed {@link Dockerfile} into the preview: a header (stage count), any global build args, then
 * one block per build stage — its title (base image + name) and the curated "what it does" facts (exposed
 * ports, workdir, user, env, entrypoint/command, health check, build-step count). Self-scrolling; hosts as
 * the Split/Preview side like {@link StructuredTree}. Kept in {@code editor} (no {@code ui} dependency).
 *
 * <p>The live preview is a virtualized list of those rows ({@link PreviewList}, PERF-08, Pauta 6.2.0); {@link
 * #content} builds them all, for an export or a print.
 */
public final class DockerfilePreview {

    private DockerfilePreview() {}

    public static Node build(Dockerfile dockerfile) {
        return PreviewList.of(rows(dockerfile), "dockerfile-preview-list");
    }

    public static VBox content(Dockerfile d, double width) {
        return PreviewList.column(rows(d), width, "dockerfile-preview");
    }

    /** The header, each global build arg, then each stage. */
    private static List<Supplier<Node>> rows(Dockerfile d) {
        List<Supplier<Node>> rows = new ArrayList<>();
        rows.add(() -> {
            Label header =
                    new Label(Messages.tr("dockerfile.summary", d.stages().size()));
            header.getStyleClass().add("dockerfile-header");
            header.setWrapText(true);
            return header;
        });
        for (Dockerfile.Instruction arg : d.globalArgs()) {
            rows.add(() -> {
                Label a = new Label("ARG " + arg.args());
                a.getStyleClass().add("dockerfile-arg");
                a.setWrapText(true);
                return a;
            });
        }
        int last = d.stages().size() - 1;
        for (int i = 0; i < d.stages().size(); i++) {
            Dockerfile.Stage stage = d.stages().get(i);
            boolean isFinal = i == last;
            rows.add(() -> stageNode(stage, isFinal));
        }
        return rows;
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
