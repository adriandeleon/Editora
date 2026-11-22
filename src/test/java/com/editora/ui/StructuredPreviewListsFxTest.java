package com.editora.ui;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.dockerfile.Dockerfile;
import com.editora.editor.UiZoom;
import com.editora.editor.structured.DockerfilePreview;
import com.editora.editor.structured.FstabPreview;
import com.editora.editor.structured.GithubActionsPreview;
import com.editora.editor.structured.OpenApiDoc;
import com.editora.editor.structured.PomPreview;
import com.editora.editor.structured.SshConfigPreview;
import com.editora.editor.structured.SystemdPreview;
import com.editora.fstab.Fstab;
import com.editora.ghactions.Workflow;
import com.editora.maven.PomSummary;
import com.editora.sshconfig.SshConfig;
import com.editora.structured.StructuredParser;
import com.editora.systemd.SystemdUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-08 (Pauta 6.2.0): the live structured previews are virtualized lists. A file of 5,000 entries builds the rows on
 * screen, in file order, with the content the preview always had; the picture an export or a print takes still holds
 * every row. The pom preview, a grid that aligns its columns, shows a named number of rows and its filter finds the
 * others.
 */
@Tag("fx")
class StructuredPreviewListsFxTest {

    private static final int N = 5_000;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Shows {@code live} in a window with the app's style sheet and checks it, then closes the window. */
    private static void inWindow(Node live, Runnable check) {
        Stage stage = new Stage();
        Scene scene = new Scene(new StackPane(live), 700, 500);
        scene.getStylesheets()
                .add(UiZoom.sheet(StructuredPreviewListsFxTest.class
                        .getResource("/com/editora/styles/app.css")
                        .toExternalForm()));
        stage.setScene(scene);
        stage.show();
        try {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            check.run();
        } finally {
            stage.hide();
        }
    }

    /**
     * The common checks: {@code rows} rows in a list that is no Tab stop, a handful of {@code rowClass} nodes on
     * screen, {@code first} among the labels shown and {@code last} not yet; the export's column has every row.
     */
    private static void assertVirtualized(
            Node live, int rows, String rowClass, String first, String last, Supplier<VBox> export) {
        inWindow(live, () -> {
            ListView<?> list = (ListView<?>) live;
            assertEquals(rows, list.getItems().size(), "every row is a row of the list");
            assertFalse(list.isFocusTraversable(), "a read-out, as the column was");
            int built = live.lookupAll("." + rowClass).size();
            assertTrue(built > 0 && built < 60, rowClass + " rows with nodes: " + built);
            List<String> shown = labels(live);
            assertTrue(shown.contains(first), "the first rows are there to read: " + shown);
            assertFalse(shown.contains(last), "a row far down has no node yet");
            list.scrollTo(rows - 1);
            live.getScene().getRoot().layout();
            assertTrue(live.lookupAll("." + rowClass).size() < 60, "scrolled to the end, still the rows on screen");
        });
        VBox whole = export.get();
        assertEquals(rows, whole.getChildren().size(), "the export draws every row once");
    }

    @Test
    void fstabOfFiveThousandEntries() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < N; i++) {
            text.append("/dev/sd").append(i).append(" /mnt/m").append(i).append(" ext4 defaults 0 2\n");
        }
        var entries = Fstab.parse(text.toString());
        FxTestSupport.runOnFx(() -> assertVirtualized(
                FstabPreview.build(entries),
                N + 1,
                "fstab-entry",
                "/mnt/m0",
                "/mnt/m4999",
                () -> FstabPreview.content(entries, 700)));
    }

    @Test
    void sshConfigOfFiveThousandHosts() throws Exception {
        // A host's summary reads the blocks before it (what applies to it), so the export of all of them is
        // quadratic: 1,000 hosts keep the test quick and still fill many screens.
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1_000; i++) {
            text.append("Host h").append(i).append("\n  HostName h").append(i).append(".example.com\n  Port 22\n");
        }
        var blocks = SshConfig.parse(text.toString());
        FxTestSupport.runOnFx(() -> assertVirtualized(
                SshConfigPreview.build(blocks),
                blocks.size() + 1,
                "sshconfig-block",
                "Host h0",
                "Host h999",
                () -> SshConfigPreview.content(blocks, 700)));
    }

    @Test
    void systemdUnitOfFiveThousandDirectives() throws Exception {
        StringBuilder text = new StringBuilder("[Unit]\nDescription=Many\n[Service]\n");
        for (int i = 0; i < N; i++) {
            text.append("Environment=K").append(i).append("=v\n");
        }
        SystemdUnit unit = SystemdUnit.parse(text.toString());
        LocalDateTime now = LocalDateTime.of(2026, 7, 10, 9, 41);
        // The header, two section headings, Description and every Environment line.
        FxTestSupport.runOnFx(() -> assertVirtualized(
                SystemdPreview.build(unit, now),
                N + 4,
                "systemd-item",
                "[Service]",
                "Environment=K4999=v",
                () -> SystemdPreview.content(unit, now, 700)));
    }

    @Test
    void dockerfileOfFiveThousandStages() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < N; i++) {
            text.append("FROM alpine:3.")
                    .append(i)
                    .append(" AS s")
                    .append(i)
                    .append("\nRUN echo ")
                    .append(i)
                    .append('\n');
        }
        Dockerfile d = Dockerfile.parse(text.toString());
        assertEquals(N, d.stages().size());
        FxTestSupport.runOnFx(() -> assertVirtualized(
                DockerfilePreview.build(d),
                N + 1,
                "dockerfile-stage",
                tr("dockerfile.summary", N),
                "never shown",
                () -> DockerfilePreview.content(d, 700)));
    }

    @Test
    void workflowOfFiveThousandJobs() throws Exception {
        StringBuilder text = new StringBuilder("name: Many\non: push\njobs:\n");
        for (int i = 0; i < N; i++) {
            text.append("  job")
                    .append(i)
                    .append(":\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo ")
                    .append(i)
                    .append('\n');
        }
        Workflow wf = Workflow.parse(text.toString());
        assertEquals(N, wf.jobs().size(), String.valueOf(wf.error()));
        // The name, the triggers, the jobs' heading and every job.
        FxTestSupport.runOnFx(() -> assertVirtualized(
                GithubActionsPreview.build(wf),
                N + 3,
                "ghactions-job",
                "job0",
                "job4999",
                () -> GithubActionsPreview.content(wf, 700)));
    }

    @Test
    void openApiDocsOfFiveThousandOperations() throws Exception {
        StringBuilder spec = new StringBuilder(
                "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"Big\",\"version\":\"1\"}," + "\"paths\":{");
        for (int i = 0; i < N; i++) {
            spec.append(i == 0 ? "" : ",")
                    .append("\"/r")
                    .append(i)
                    .append("\":{\"get\":{\"summary\":\"Read ")
                    .append(i)
                    .append("\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}");
        }
        spec.append("}}");
        StructuredParser.Parsed parsed =
                StructuredParser.parse(spec.toString(), StructuredParser.Format.forLanguage("json"));
        assertTrue(parsed.isOpenApi(), String.valueOf(parsed.error()));
        // The title, the endpoints' heading and every operation.
        FxTestSupport.runOnFx(() -> assertVirtualized(
                OpenApiDoc.build(parsed.openApi()),
                N + 2,
                "openapi-op",
                "/r0",
                "/r4999",
                () -> OpenApiDoc.content(parsed.openApi())));
    }

    /**
     * The pom preview builds {@link PomPreview#MAX_ROWS} rows at most and says so; the filter, which reads every
     * row's text, finds a row past them; the export has every row.
     */
    @Test
    void pomOfThreeThousandDependenciesShowsANamedNumberOfRowsAndTheFilterFindsTheOthers() throws Exception {
        int count = 3_000;
        StringBuilder pom = new StringBuilder(
                "<project><groupId>g</groupId><artifactId>big</artifactId><version>1</version><dependencies>");
        for (int i = 0; i < count; i++) {
            pom.append("<dependency><groupId>org.d</groupId><artifactId>dep-")
                    .append(i)
                    .append("</artifactId><version>1.")
                    .append(i)
                    .append("</version></dependency>");
        }
        pom.append("</dependencies></project>");
        PomSummary summary = PomSummary.parse(pom.toString());
        assertEquals(count, summary.dependencies().size());
        FxTestSupport.runOnFx(() -> {
            Node live = PomPreview.build(summary, line -> {});
            inWindow(live, () -> {
                List<String> names = artifacts(live);
                assertEquals(PomPreview.MAX_ROWS, names.size(), "rows with nodes");
                assertEquals("dep-0", names.get(0));
                assertFalse(names.contains("dep-2999"), "a row past the cap has no node");
                Label capped = (Label) live.lookup(".pom-capped");
                assertTrue(capped.isVisible(), "the cap is said");
                assertEquals(tr("pom.capped", PomPreview.MAX_ROWS, count), capped.getText());

                TextField filter = (TextField) live.lookup(".pom-filter");
                filter.setText("dep-2999");
                assertEquals(List.of("dep-2999"), shownArtifacts(live), "the filter finds a row past the cap");
                assertFalse(capped.isVisible(), "one match: nothing is left out");
                filter.setText("dep-1");
                assertEquals(PomPreview.MAX_ROWS, shownArtifacts(live).size(), "a filter that matches many is capped");
                assertTrue(capped.isVisible());
                filter.setText("");
                assertTrue(artifacts(live).size() <= 2 * PomPreview.MAX_ROWS, "rows past the cap are let go again");
                assertEquals(PomPreview.MAX_ROWS, shownArtifacts(live).size());
            });
            VBox whole = PomPreview.content(summary, 700);
            assertEquals(count, artifacts(whole).size(), "the export draws every row");
            assertTrue(whole.lookup(".pom-filter") == null);
        });
    }

    /** The artifactIds of the dependency rows that have nodes. */
    private static List<String> artifacts(Node root) {
        List<String> out = new ArrayList<>();
        for (Node n : root.lookupAll(".pom-artifact")) {
            out.add(((Label) n).getText());
        }
        return out;
    }

    /** The artifactIds of the dependency rows shown. */
    private static List<String> shownArtifacts(Node root) {
        List<String> out = new ArrayList<>();
        for (Node n : root.lookupAll(".pom-artifact")) {
            if (n.getParent() != null && n.getParent().isManaged()) {
                out.add(((Label) n).getText());
            }
        }
        return out;
    }

    private static List<String> labels(Node node) {
        List<String> out = new ArrayList<>();
        collect(node, out);
        return out;
    }

    private static void collect(Node node, List<String> out) {
        if (node instanceof Label l && l.getText() != null && !l.getText().isBlank()) {
            out.add(l.getText());
        }
        if (node instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }
}
