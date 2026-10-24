package com.editora.editor.structured;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import com.editora.dockerfile.Dockerfile;
import com.editora.editor.EditorFx;
import com.editora.i18n.Messages;
import com.editora.maven.PomSummary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the pom and Dockerfile summaries that only some files have: a pom's parent read from disk, its
 * URL and SCM, its repositories and plugin repositories, a profile that activates on a condition, and the tag
 * column that never clips a tag; a Dockerfile's {@code ARG}s before its first {@code FROM}.
 */
@Tag("fx")
class SummaryPreviewDetailsFxTest {

    private static final String PARENT = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>org.demo</groupId>
              <artifactId>demo-parent</artifactId>
              <version>2.0</version>
              <packaging>pom</packaging>
            </project>
            """;

    private static final String CHILD = """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>org.demo</groupId>
                <artifactId>demo-parent</artifactId>
                <version>2.0</version>
              </parent>
              <artifactId>demo-app</artifactId>
              <name>Demo application</name>
              <description>The app the demo ships.</description>
              <url>https://demo.example.org</url>
              <scm><url>https://git.example.org/demo</url></scm>
              <dependencies>
                <dependency>
                  <groupId>org.junit.jupiter</groupId>
                  <artifactId>junit-jupiter</artifactId>
                  <version>5.11.0</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
              <repositories>
                <repository><id>internal</id><url>https://repo.example.org/maven</url></repository>
                <repository><url>https://repo.example.org/anonymous</url></repository>
              </repositories>
              <pluginRepositories>
                <pluginRepository><id>plugins</id><url>https://plugins.example.org</url></pluginRepository>
              </pluginRepositories>
              <profiles>
                <profile>
                  <id>modern</id>
                  <activation><jdk>[25,)</jdk></activation>
                  <properties><release>25</release></properties>
                </profile>
              </profiles>
            </project>
            """;

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static List<String> texts(Node root) {
        List<String> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    private static void walk(Node node, List<String> out) {
        if (node instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (node instanceof ScrollPane sp && sp.getContent() != null) {
            walk(sp.getContent(), out); // no skin without a scene: the content is not a child yet
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                walk(child, out);
            }
        }
    }

    private static List<FlowPane> flows(Node node) {
        List<FlowPane> out = new ArrayList<>();
        collectFlows(node, out);
        return out;
    }

    private static void collectFlows(Node node, List<FlowPane> out) {
        if (node instanceof FlowPane f && f.getStyleClass().contains("pom-tags")) {
            out.add(f);
        }
        if (node instanceof ScrollPane sp && sp.getContent() != null) {
            collectFlows(sp.getContent(), out);
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collectFlows(child, out);
            }
        }
    }

    @Test
    void aPomShowsItsParentLinksRepositoriesAndConditionalProfile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), PARENT);
        Path child = Files.createDirectories(dir.resolve("app")).resolve("pom.xml");
        Files.writeString(child, CHILD);
        PomSummary summary = PomSummary.parse(CHILD, child);

        EditorFx.onFx(() -> {
            VBox preview = PomPreview.content(summary, 700);
            List<String> texts = texts(preview);
            assertTrue(texts.contains("Demo application"), texts.toString());
            assertTrue(texts.contains("The app the demo ships."), texts.toString());
            assertTrue(
                    texts.stream()
                            .anyMatch(t -> t.startsWith(Messages.tr("pom.parent", "org.demo:demo-parent:2.0"))
                                    && t.contains(" · ")),
                    "the parent line says it was read from disk: " + texts);
            assertTrue(texts.contains(Messages.tr("pom.url", "https://demo.example.org")), texts.toString());
            assertTrue(texts.contains(Messages.tr("pom.scm", "https://git.example.org/demo")), texts.toString());

            assertTrue(texts.contains(Messages.tr("pom.repositories", 3)), texts.toString());
            assertTrue(texts.contains("internal") && texts.contains("plugins"), texts.toString());
            assertTrue(texts.contains(Messages.tr("pom.untitled")), "a repository without an id: " + texts);
            assertTrue(texts.contains(Messages.tr("pom.pluginRepository")), texts.toString());
            assertTrue(texts.contains(Messages.tr("pom.notCentral")), texts.toString());

            assertTrue(texts.contains(Messages.tr("pom.profile", "modern")), texts.toString());
            assertTrue(texts.contains(Messages.tr("pom.activatesWhen", "jdk [25,)")), texts.toString());

            // Laid out, a tag column is never narrower than its widest tag.
            Scene scene = new Scene(preview, 700, 900);
            preview.applyCss();
            preview.layout();
            List<FlowPane> tags = flows(preview);
            assertFalse(tags.isEmpty(), "the dependency and repository rows carry tags");
            for (FlowPane f : tags) {
                double widest = f.getChildren().stream()
                        .filter(Node::isManaged)
                        .mapToDouble(n -> n.prefWidth(-1))
                        .max()
                        .orElse(0);
                assertTrue(f.minWidth(-1) >= widest, "min " + f.minWidth(-1) + " < widest tag " + widest);
            }
            assertTrue(scene.getRoot() == preview);
        });
    }

    @Test
    void aDockerfileListsTheArgsBeforeItsFirstStage() throws Exception {
        EditorFx.onFx(() -> {
            VBox preview = DockerfilePreview.content(
                    Dockerfile.parse("ARG BASE=alpine:3\nARG USER=app\nFROM ${BASE}\nRUN adduser -D ${USER}\n"), 600);
            List<String> args = preview.getChildren().stream()
                    .filter(n -> n.getStyleClass().contains("dockerfile-arg"))
                    .map(n -> ((Label) n).getText())
                    .toList();
            assertEquals(List.of("ARG BASE=alpine:3", "ARG USER=app"), args);
            assertEquals(600, preview.getPrefWidth());
        });
    }
}
