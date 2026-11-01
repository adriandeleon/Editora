package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.git.GitFileStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless checks for the data fixes of Project Map review 3 (paint, Git push, controls, icons, limit, help). */
@Tag("fx")
class ProjectMapDataReview3FxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Harness(Stage stage, ProjectMapView view, Region surface, Path project, List<String> statuses)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage.hide();
            });
        }
    }

    private Harness open(Path project) throws Exception {
        List<String> statuses = new CopyOnWriteArrayList<>();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            view.setOnStatus(statuses::add);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 900, 700));
            stage.show();
            view.setRoot(project);
            return new Harness(stage, view, FxTestSupport.field(view, "surface"), project, statuses);
        });
        waitForFx(() -> !rows(h.surface()).isEmpty() && idle(h));
        return h;
    }

    // --- D9 / A12 --------------------------------------------------------------------------------------

    @Test
    void aHiddenMapDoesNotPaintOnAGitPushAndPaintsOnceWhenShownAgain() throws Exception {
        Path project = project();
        Path readme = Files.writeString(project.resolve("README.md"), "# hi\n");
        try (Harness h = open(project)) {
            settle(h);
            FxTestSupport.runOnFx(() -> h.view().setShowing(false));
            awaitPulses(2); // a paint asked for before the window closed may still land
            long before = paints(h);
            FxTestSupport.runOnFx(() -> h.view().setGitStatus(Map.of(readme, GitFileStatus.MODIFIED)));
            awaitPulses(3);
            assertEquals(before, paints(h), "nobody sees the hidden map: the Git push draws nothing");

            FxTestSupport.runOnFx(() -> h.view().setShowing(true));
            waitForFx(() -> paints(h) > before);
            assertEquals(
                    GitFileStatus.MODIFIED,
                    FxTestSupport.callOnFx(() -> FxTestSupport.<Map<Path, GitFileStatus>>field(h.surface(), "gitState")
                            .get(readme)),
                    "and the status it missed is drawn once it shows");
        }
    }

    @Test
    void anUnchangedGitStatusIsNotAppliedAgain() throws Exception {
        Path project = project();
        Path deep = Files.createDirectories(project.resolve("a/b"));
        Path file = Files.writeString(deep.resolve("x.txt"), "x");
        try (Harness h = open(project)) {
            Map<Path, GitFileStatus> status = Map.of(file, GitFileStatus.MODIFIED);
            FxTestSupport.runOnFx(() -> h.view().setGitStatus(status));
            Object applied = FxTestSupport.callOnFx(() -> FxTestSupport.field(h.view(), "gitChangedDirectories"));
            assertTrue(((java.util.Set<?>) applied).containsAll(List.of(deep, deep.getParent(), project)));
            FxTestSupport.runOnFx(() -> h.view().setGitStatus(Map.copyOf(status))); // an equal map, a new object
            assertSame(
                    applied,
                    FxTestSupport.callOnFx(() -> FxTestSupport.field(h.view(), "gitChangedDirectories")),
                    "the same status is not walked or pushed again");
        }
    }

    @Test
    void theProjectPanelSkipsAnUnchangedGitStatus(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            Map<Path, GitFileStatus> status = Map.of(r.helper, GitFileStatus.MODIFIED);
            FxTestSupport.runOnFx(() -> r.panel.setGitStatus(status));
            Object dirs = FxTestSupport.callOnFx(() -> FxTestSupport.field(r.panel, "gitChangedDirs"));
            FxTestSupport.runOnFx(() -> r.panel.setGitStatus(Map.copyOf(status)));
            assertSame(dirs, FxTestSupport.callOnFx(() -> FxTestSupport.field(r.panel, "gitChangedDirs")));
            FxTestSupport.runOnFx(() -> r.panel.setGitStatus(Map.of()));
            assertTrue(FxTestSupport.callOnFx(() -> FxTestSupport.<java.util.Set<?>>field(r.panel, "gitChangedDirs")
                    .isEmpty()));
        }
    }

    // --- A8 ----------------------------------------------------------------------------------------------

    @Test
    void aColumnGetsItsControlsOnlyOnceItIsShownAtAZoomThatShowsThem() throws Exception {
        Path project = project();
        Path a = Files.createDirectory(project.resolve("a"));
        Path one = Files.writeString(a.resolve("one.txt"), "1");
        Path b = Files.createDirectory(project.resolve("b"));
        Path two = Files.writeString(b.resolve("two.txt"), "2");
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(one));
            waitForFx(() ->
                    rows(h.surface()).stream().anyMatch(entry -> entry.path().equals(one)));
            settle(h);
            ProjectMapModel.ColumnId columnA = new ProjectMapModel.ColumnId(2, a);
            assertTrue(FxTestSupport.callOnFx(() -> controls(h.surface()).containsKey(columnA)), "shown: made");

            // Zoomed far out (a big search's overview), a column that opens gets no native controls.
            FxTestSupport.runOnFx(() -> setField(h.surface(), "zoom", 0.3));
            FxTestSupport.runOnFx(() -> h.view().revealPath(two));
            waitForFx(() ->
                    rows(h.surface()).stream().anyMatch(entry -> entry.path().equals(two)));
            settle(h);
            ProjectMapModel.ColumnId columnB = new ProjectMapModel.ColumnId(2, b);
            FxTestSupport.runOnFx(() -> {
                setField(h.surface(), "zoom", 0.3);
                FxTestSupport.call(h.surface(), "repaint", new Class<?>[0]);
            });
            assertFalse(FxTestSupport.callOnFx(() -> controls(h.surface()).containsKey(columnB)), "zoomed out: none");

            FxTestSupport.runOnFx(() -> {
                setField(h.surface(), "zoom", 1.0);
                setField(h.surface(), "offsetX", 0.0);
                setField(h.surface(), "offsetY", 0.0);
                FxTestSupport.call(h.surface(), "repaint", new Class<?>[0]);
            });
            assertTrue(
                    FxTestSupport.callOnFx(() -> controls(h.surface()).containsKey(columnB)),
                    "back at full size, near the viewport: made");
        }
    }

    private static void setField(Object target, String name, double value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setDouble(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void theFilterKeyMakesTheControlsOfAColumnThatHasNone() throws Exception {
        Path project = project();
        Path a = Files.createDirectory(project.resolve("a"));
        Path one = Files.writeString(a.resolve("one.txt"), "1");
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(one));
            waitForFx(() -> one.equals(FxTestSupport.call(h.surface(), "selectedPath", new Class<?>[0])));
            settle(h);
            ProjectMapModel.ColumnId column = new ProjectMapModel.ColumnId(2, a);
            FxTestSupport.runOnFx(() -> {
                Map<ProjectMapModel.ColumnId, ?> controls = controls(h.surface());
                Object made = controls.remove(column); // as if it had never been on screen
                if (made != null) {
                    for (String part : List.of("filter", "showHidden", "pin", "close")) {
                        Node control = (Node) FxTestSupport.call(made, part, new Class<?>[0]);
                        control.setVisible(false); // an orphan now: nothing lays it out again
                    }
                }
                FxTestSupport.call(h.surface(), "focusSelectedColumnFilter", new Class<?>[0]);
            });
            assertTrue(FxTestSupport.callOnFx(() -> controls(h.surface()).containsKey(column)));
            assertFalse(h.statuses().contains(tr("project.map.status.rootColumnNoFilter")));
        }
    }

    @Test
    void theIconSetIsRasterisedAheadOfTheRowsThatNeedIt() throws Exception {
        Path project = project();
        Files.writeString(project.resolve("README.md"), "# hi\n");
        try (Harness h = open(project)) {
            int kinds = FileIcons.iconKeys().size();
            waitForFx(() -> iconKinds(h.surface()).size() >= kinds);
            assertTrue(
                    iconKinds(h.surface()).containsAll(List.of("folder", "java", "python")),
                    "kinds no row on the map has shown yet are ready: " + iconKinds(h.surface()));
        }
    }

    // --- A10 ---------------------------------------------------------------------------------------------

    @Test
    void atTheLimitWithOnlyItsOwnFoldersOpenTheMapPointsToTheColumnFilter() throws Exception {
        Path project = project();
        Path big = Files.createDirectories(project.resolve("testdata/corpus"));
        for (int index = 0; index < 1_400; index++) {
            Files.writeString(big.resolve(String.format("c%05d", index)), "");
        }
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(big.resolve("c00000")));
            waitForFx(() ->
                    rows(h.surface()).stream().anyMatch(entry -> entry.path().equals(big.resolve("c00000"))));
            for (int more = 0; more < 4; more++) {
                FxTestSupport.runOnFx(() -> FxTestSupport.call(h.view(), "loadMore", new Class<?>[] {Path.class}, big));
                settle(h);
            }
            String expected = tr("project.map.status.limitUseFilter", ProjectMapModel.MAX_VISIBLE_ITEMS, "corpus");
            waitForFx(() -> h.statuses().contains(expected));
            assertFalse(
                    h.statuses().contains(tr("project.map.status.limit", ProjectMapModel.MAX_VISIBLE_ITEMS)),
                    "not the advice to close a column, which would close the folder itself: " + h.statuses());
        }
    }

    // --- D12 ---------------------------------------------------------------------------------------------

    @Test
    void theHelpPopoverListsTheCardStructureButton() throws Exception {
        Node help = FxTestSupport.callOnFx(() -> {
            java.lang.reflect.Method build = ProjectMapView.class.getDeclaredMethod("buildHelp");
            build.setAccessible(true);
            return (Node) build.invoke(null);
        });
        List<String> texts = FxTestSupport.callOnFx(() -> labels(help));
        assertTrue(texts.contains(tr("project.map.help.cardStructure.gesture")), texts.toString());
        assertTrue(texts.contains(tr("project.map.help.cardStructure")));
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private Path project() throws Exception {
        return Files.createTempDirectory(temp, "project").toRealPath();
    }

    private static List<ProjectMapModel.Entry> rows(Region surface) {
        try {
            return FxTestSupport.callOnFx(() -> FxTestSupport.<List<ProjectMapModel.Entry>>field(surface, "entries"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<ProjectMapModel.ColumnId, ?> controls(Region surface) {
        return FxTestSupport.field(surface, "columnControls");
    }

    private static List<String> iconKinds(Region surface) {
        try {
            return FxTestSupport.callOnFx(() -> {
                Map<?, ?> images = FxTestSupport.field(surface, "iconImages");
                List<String> kinds = new ArrayList<>();
                for (Object key : images.keySet()) {
                    kinds.add((String) FxTestSupport.call(key, "kind", new Class<?>[0]));
                }
                return kinds;
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long paints(Harness h) {
        try {
            return FxTestSupport.callOnFx(() -> FxTestSupport.<Long>field(h.surface(), "completedPaints"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean idle(Harness h) {
        return !FxTestSupport.<Boolean>field(h.view(), "loadInFlight");
    }

    private static void settle(Harness h) throws Exception {
        FxTestSupport.drainFx();
        waitForFx(() -> idle(h));
        awaitPulses(2);
        FxTestSupport.drainFx();
    }

    private static List<String> labels(Node node) {
        List<String> texts = new ArrayList<>();
        if (node instanceof Label label) {
            texts.add(label.getText());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                texts.addAll(labels(child));
            }
        }
        return texts;
    }

    private static void awaitPulses(int count) throws Exception {
        java.util.concurrent.CountDownLatch pulses = new java.util.concurrent.CountDownLatch(count);
        javafx.animation.AnimationTimer counter = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                pulses.countDown();
            }
        };
        FxTestSupport.runOnFx(counter::start);
        try {
            assertTrue(pulses.await(10, TimeUnit.SECONDS), "pulses ran");
        } finally {
            FxTestSupport.runOnFx(counter::stop);
        }
    }

    private static void waitForFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out");
    }
}
