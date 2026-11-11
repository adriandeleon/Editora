package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Problems on the Project Map (Map code browser round 2): the Problems window's errors and warnings on the rows and
 * added up on folders, the Problems chip, the tooltip, and Next and Previous Problem across the Map.
 */
@Tag("fx")
class MapProblemsFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static LspDiagnostic problem(int line, LspDiagnostic.Severity severity, String message) {
        return new LspDiagnostic(line, 0, line, 1, severity, message, null, "javac", null, false);
    }

    @Test
    void theRowsTheChipTheTooltipAndNextProblem() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path a = Files.writeString(
                Files.createDirectories(project.resolve("src")).resolve("A.java"),
                "class A {\n  int x;\n  int y;\n}\n");
        Path b = Files.writeString(project.resolve("src/B.java"), "class B {}\n");
        Files.writeString(Files.createDirectories(project.resolve("docs")).resolve("notes.md"), "# notes\n");
        List<String> said = new CopyOnWriteArrayList<>();
        Stage[] stage = new Stage[1];
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false);
            made.setOnStatus(said::add);
            stage[0] = new Stage();
            Scene scene = new Scene(new StackPane(made), 1200, 800);
            scene.getStylesheets()
                    .add(MapProblemsFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage[0].setScene(scene);
            stage[0].show();
            made.setRoot(project);
            return made;
        });
        try {
            Region surface = FxTestSupport.callOnFx(() -> FxTestSupport.field(view, "surface"));
            waitFor(() -> (boolean)
                    FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, project.resolve("src")));
            FxTestSupport.runOnFx(() -> {
                view.stepProblem(true);
                assertEquals(tr("project.map.problems.none"), said.getLast(), "nothing yet");

                view.setProblems(Map.of(
                        a,
                        List.of(
                                problem(2, LspDiagnostic.Severity.ERROR, "cannot find symbol"),
                                problem(1, LspDiagnostic.Severity.WARNING, "unused")),
                        b,
                        List.of(problem(0, LspDiagnostic.Severity.WARNING, "raw type"))));
                assertEquals(new ProjectMapProblems.Count(1, 2), view.problems().of(project.resolve("src")));
                view.applyCss();
                view.layout();

                // The tooltip says how many.
                ProjectMapModel.Entry src = new ProjectMapModel.Entry(project.resolve("src"), project, 1, true);
                String tip = (String)
                        FxTestSupport.call(surface, "tooltipText", new Class<?>[] {ProjectMapModel.Entry.class}, src);
                assertTrue(tip.contains(tr("project.map.tooltip.problems", 1, 2)), tip);

                // The Problems chip lights the folders with problems, not the others.
                ToggleButton chip = FxTestSupport.field(view, "problemsFilter");
                chip.fire();
                @SuppressWarnings("unchecked")
                java.util.Set<Path> lit = (java.util.Set<Path>) FxTestSupport.field(surface, "emphasized");
                assertTrue(lit.contains(project.resolve("src")), lit.toString());
                assertTrue(lit.contains(a), "a file in a collapsed folder still lights it");
                assertFalse(lit.contains(project.resolve("docs")));

                // Problems change while the chip is on: the map follows.
                view.setProblems(Map.of(b, List.of(problem(0, LspDiagnostic.Severity.WARNING, "raw type"))));
                @SuppressWarnings("unchecked")
                java.util.Set<Path> now = (java.util.Set<Path>) FxTestSupport.field(surface, "emphasized");
                assertFalse(now.contains(a));
                chip.fire();

                // Next Problem from nowhere: the first, its card opened.
                view.setProblems(Map.of(
                        a,
                        List.of(problem(2, LspDiagnostic.Severity.ERROR, "cannot find symbol")),
                        b,
                        List.of(problem(0, LspDiagnostic.Severity.WARNING, "raw type"))));
                view.stepProblem(true);
                assertEquals(tr("project.map.problems.stepError", 1, 2, "cannot find symbol"), said.getLast());
            });
            waitFor(() -> view.cards.previews.keySet().stream().anyMatch(key -> key.startsWith(a)));
            ProjectMapPreview card = FxTestSupport.callOnFx(() -> view.cards.previews.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(a))
                    .findFirst()
                    .orElseThrow()
                    .getValue());
            waitFor(() -> card.editor().getLength() > 0);
            FxTestSupport.runOnFx(() -> {
                card.editor().requestFocus();
                card.editor().moveTo(0); // the declaration card starts at the problem's line
                view.stepProblem(true); // from the card's caret: on to B
                assertEquals(tr("project.map.problems.stepWarning", 2, 2, "raw type"), said.getLast());
            });
            waitFor(() ->
                    view.focusedCard() != null && b.equals(view.focusedCard().path()));
            FxTestSupport.runOnFx(() -> {
                view.stepProblem(false);
                assertTrue(said.getLast().contains("cannot find symbol"), said.getLast());
                // A new root counts afresh: nothing of this one is in it.
                view.setRoot(project.resolve("docs"));
                assertFalse(view.problems().has(a), "outside the new root");
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage[0].close();
            });
        }
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNotNull(null, "timed out");
    }
}
