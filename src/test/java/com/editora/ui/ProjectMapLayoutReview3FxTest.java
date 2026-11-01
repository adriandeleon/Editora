package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.control.TextField;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map review 3 layout fixes in a live panel and Map: columns never overlap however wide the branches of
 * a search are.
 */
@ExtendWith(DisposedPanels.class)
class ProjectMapLayoutReview3FxTest {

    @TempDir
    Path temp;

    private Path root;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void makeRoot() throws Exception {
        root = Files.createDirectories(temp.resolve("spring")).toRealPath();
    }

    /**
     * A Spring-like tree: modules whose sources hang from compacted {@code java / org / springframework} chains, with
     * names of very different widths in different branches. A search that opens many of them used to lay a wide card
     * of one level over the next level's cards of another branch (B3).
     */
    @Test
    void aMultiBranchSearchInASpringLikeTreeLaysNoColumnOverAnother() throws Exception {
        String[] modules = {"spring-tx", "spring-web", "spring-context", "spring-test", "spring-orm", "spring-jdbc"};
        String[] packages = {"annotation", "interceptor", "support", "config", "testfixture/server", "event"};
        for (int m = 0; m < modules.length; m++) {
            for (String set : List.of("main", "test", "testFixtures")) {
                for (int p = 0; p < packages.length; p++) {
                    if ((m + p) % 2 == 0 && !set.equals("main")) {
                        continue;
                    }
                    Path folder = Files.createDirectories(root.resolve(modules[m] + "/src/" + set
                            + "/java/org/springframework/" + modules[m].substring(7) + "/" + packages[p]));
                    // Short names in some branches, very long ones in others: the widths of a level differ a lot.
                    String stem = (m + p) % 3 == 0 ? "Tx" : "AbstractRefreshableConfigApplicationListenerMethodAdapter";
                    Files.writeString(folder.resolve(stem + "Transactional" + p + ".java"), "class X {}\n");
                    Files.writeString(folder.resolve("Other" + p + ".java"), "class Y {}\n");
                }
            }
        }
        // The case that overlapped every time: a deep compacted chain is one level from the first column but is
        // listed last (the listing is breadth-first on disk), so its wide card came after a shallow branch's next
        // level had been placed against the narrower band.
        Path deep = Files.createDirectories(root.resolve("aaa/src/main/java/org/springframework/deep"));
        for (int index = 0; index < 6; index++) {
            Files.writeString(
                    deep.resolve("AbstractRefreshableConfigApplicationContextTransactional" + index + ".java"), "x\n");
        }
        Path shallow = Files.createDirectories(root.resolve("bbb/c"));
        Files.writeString(root.resolve("bbb/notes.txt"), "x\n"); // so bbb's column is not folded into c's
        for (int index = 0; index < 80; index++) {
            Files.writeString(shallow.resolve("Transactional" + index + ".java"), "x\n");
        }
        Files.writeString(root.resolve("build.gradle"), "// root\n");
        ProjectPanel panel = openPanel();
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");

        search(panel, "Transactional");
        waitFor(() -> view.searchResult().matches().size() > 20, "the search finds every branch");
        waitFor(() -> columnBoxes(surface).size() > 40, "the map opens the branches of the matches");
        for (ProjectMapView.FlowDirection flow : ProjectMapView.FlowDirection.values()) {
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(
                        surface, "setFlowDirection", new Class<?>[] {ProjectMapView.FlowDirection.class}, flow);
                FxTestSupport.invoke(surface, "paintNow");
            });
            List<double[]> boxes = FxTestSupport.callOnFx(() -> columnBoxes(surface));
            List<String> overlaps = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) {
                for (int j = i + 1; j < boxes.size(); j++) {
                    if (intersect(boxes.get(i), boxes.get(j))) {
                        overlaps.add(i + "×" + j);
                    }
                }
            }
            assertTrue(overlaps.isEmpty(), flow + ": " + boxes.size() + " columns, overlapping pairs: " + overlaps);
        }
    }

    // --- helpers ------------------------------------------------------------------------------------

    private static boolean intersect(double[] a, double[] b) {
        return a[0] < b[0] + b[2] - 0.5
                && b[0] < a[0] + a[2] - 0.5
                && a[1] < b[1] + b[3] - 0.5
                && b[1] < a[1] + a[3] - 0.5;
    }

    /** The columns as last projected, {@code {x, y, w, h}} on screen. */
    static List<double[]> columnBoxes(Region surface) {
        List<?> boxes = FxTestSupport.field(surface, "columnBoxes");
        List<double[]> out = new ArrayList<>();
        for (Object box : List.copyOf(boxes)) {
            out.add(new double[] {
                (double) FxTestSupport.call(box, "x", new Class<?>[0]),
                (double) FxTestSupport.call(box, "y", new Class<?>[0]),
                (double) FxTestSupport.call(box, "width", new Class<?>[0]),
                (double) FxTestSupport.call(box, "height", new Class<?>[0])
            });
        }
        return out;
    }

    private ProjectPanel openPanel() throws Exception {
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p =
                    DisposedPanels.track(new ProjectPanel(x -> {}, (a, b) -> {}, x -> {}, x -> false, x -> false));
            p.setRoot(root);
            p.openMapWindow(false);
            return p;
        });
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        waitFor(() -> !rows(surface).isEmpty(), "the first column");
        return panel;
    }

    private static void search(ProjectPanel panel, String query) throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.<TextField>field(panel, "mapFilterField").setText(query);
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
            FxTestSupport.invoke(panel, "searchMap");
        });
    }

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        return FxTestSupport.field(surface, "entries");
    }

    static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNotNull(null, "timed out: " + message);
    }
}
