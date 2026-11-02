package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

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
 * Map review 4, canvas group: Print/PDF/SVG of a whole folder (D1), the PNG export's origin (D2), path lookups on a
 * long folder (D3) and the label elision's cache (D5).
 */
@Tag("fx")
class ProjectMapCanvasReview4FxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Harness(Stage stage, ProjectMapView view, Region surface, List<String> statuses)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage.hide();
            });
        }
    }

    private Harness open(Path project, double width, double height) throws Exception {
        return open(project, width, height, false);
    }

    /** With {@code themed} the scene has the light theme's stylesheet, so the map is painted in its real colours. */
    private Harness open(Path project, double width, double height, boolean themed) throws Exception {
        List<String> statuses = new CopyOnWriteArrayList<>();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            view.setOnStatus(statuses::add);
            Stage stage = new Stage();
            Scene scene = new Scene(new StackPane(view), width, height);
            if (themed) {
                scene.getStylesheets().add(Themes.stylesheetFor("Editora Light"));
                scene.getStylesheets()
                        .add(ProjectMapCanvasReview4FxTest.class
                                .getResource("/com/editora/styles/app.css")
                                .toExternalForm());
            }
            stage.setScene(scene);
            stage.show();
            view.setRoot(project);
            return new Harness(stage, view, FxTestSupport.field(view, "surface"), statuses);
        });
        waitForFx(() -> !rows(h).isEmpty() && idle(h));
        return h;
    }

    private Path project() throws Exception {
        return Files.createTempDirectory(temp, "project").toRealPath();
    }

    private static Path folder(Path parent, String name, int files) throws Exception {
        Path folder = Files.createDirectory(parent.resolve(name));
        for (int i = 0; i < files; i++) {
            Files.createFile(folder.resolve(String.format("f%05d.txt", i)));
        }
        return folder;
    }

    // --- D1: output lists a whole folder by its first rows ---------------------------------------------------

    @Test
    void printPdfAndSvgShowAWholeFolderByItsFirstRowsAndPlusNMore() throws Exception {
        Path project = project();
        Path five = folder(project, "five", 5_000);
        Path last = five.resolve("f04999.txt");
        try (Harness h = open(project, 1000, 700)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(last));
            waitForFx(() -> idle(h) && last.equals(selected(h)));
            settle(h);
            assertEquals(
                    5_000,
                    rows(h).stream().filter(row -> five.equals(row.parent())).count(),
                    "listed whole");

            long started = System.nanoTime();
            ProjectMapOutput.Rendered rendered =
                    FxTestSupport.callOnFx(() -> (ProjectMapOutput.Rendered) FxTestSupport.call(
                            h.surface(), "renderOutput", new Class<?>[] {double.class, double.class}, 540.0, 720.0));
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertNotNull(rendered);
            System.out.printf(
                    "[canvas5] PDF of a 5,000-file folder: %d pages in %d ms%n",
                    rendered.pages().size(), millis);
            // 300 rows and "+4,700 more" fit a few readable pages; the whole folder took 162 unreadable ones.
            assertTrue(
                    rendered.pages().size() <= 12, "pages: " + rendered.pages().size());
            assertFalse(rendered.plan().detailReduced(), "the labels keep their resolution");
            List<String> names = new ArrayList<>();
            rendered.labels().forEach(page -> page.forEach(label -> names.add(label.text())));
            assertTrue(names.contains("f00000.txt") && names.contains("f00299.txt"), "the first chunk is printed");
            assertFalse(names.contains("f00300.txt") || names.contains("f04999.txt"), "the rest is behind +N more");

            String svg = FxTestSupport.callOnFx(
                    () -> h.surface() instanceof MapSurface surface ? surface.canvasSvg("map") : null);
            assertNotNull(svg);
            assertTrue(svg.contains(ProjectMapSvg.escape(tr("project.map.row.more", 4_700))), "+4,700 more");
            assertFalse(svg.contains("f04999.txt"));

            // The live map still lists the folder whole once the output is done.
            FxTestSupport.runOnFx(
                    () -> assertNotNull(FxTestSupport.call(h.surface(), "nodeBox", new Class<?>[] {Path.class}, last)));
        }
    }

    // --- D2: the PNG is the map area, not the view from its top --------------------------------------------

    @Test
    void thePngShowsTheMapAreaWhereTheWindowShowsIt() throws Exception {
        Path project = project();
        Path src = Files.createDirectory(project.resolve("src"));
        for (int i = 0; i < 12; i++) {
            Files.writeString(src.resolve(String.format("Main%02d.java", i)), "class M {}");
        }
        Path main = src.resolve("Main06.java");
        try (Harness h = open(project, 900, 640, true)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(main));
            waitForFx(() -> idle(h) && main.equals(selected(h)));
            settle(h);
            FxTestSupport.runOnFx(() -> {
                Region host = FxTestSupport.field(h.view(), "canvasHost");
                double shift = host.localToParent(0, 0).getY();
                assertTrue(shift > 20, "the map area starts below the filter row: " + shift);
                Object box = FxTestSupport.call(h.surface(), "nodeBox", new Class<?>[] {Path.class}, main);
                assertNotNull(box);
                double x = (double) FxTestSupport.call(box, "x", new Class<?>[0]);
                double y = (double) FxTestSupport.call(box, "y", new Class<?>[0]);
                double height = (double) FxTestSupport.call(box, "height", new Class<?>[0]);
                int sampleX = (int) Math.round(x + 4);
                int sampleY = (int) Math.round(y + height / 2);
                assertTrue(sampleY - shift >= 0, "the selected row is low enough to tell a shift apart");

                // The map area as the window draws it: the host's own snapshot, at one pixel per pixel.
                WritableImage live = host.snapshot(null, null);
                Color row = live.getPixelReader().getColor(sampleX, sampleY);
                Color above = live.getPixelReader().getColor(sampleX, (int) Math.round(sampleY - shift));
                assertTrue(distance(row, above) > 0.05, "the selected row's fill " + row + " vs " + above);

                WritableImage png = h.view().canvasViewImage();
                assertNotNull(png);
                double scale = png.getWidth() / host.getWidth();
                PixelReader pixels = png.getPixelReader();
                Color exported = pixels.getColor(
                        (int) Math.round((sampleX + 0.5) * scale), (int) Math.round((sampleY + 0.5) * scale));
                assertTrue(
                        distance(row, exported) < 0.05,
                        "the PNG has the selected row where the window has it: " + row + " vs " + exported);
            });
        }
    }

    private static double distance(Color a, Color b) {
        return Math.max(
                Math.abs(a.getRed() - b.getRed()),
                Math.max(Math.abs(a.getGreen() - b.getGreen()), Math.abs(a.getBlue() - b.getBlue())));
    }

    // --- D3: a row's box is looked up, not searched for --------------------------------------------------------

    @Test
    void aLongFoldersRowsAreFoundWithoutAScanOfEveryRow() throws Exception {
        Path project = project();
        Path long_ = folder(project, "long", 20_000);
        Path last = long_.resolve("f19999.txt");
        try (Harness h = open(project, 1000, 700)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(last));
            waitForFx(() -> idle(h) && last.equals(selected(h)));
            settle(h);
            assertEquals(
                    20_000,
                    rows(h).stream().filter(row -> long_.equals(row.parent())).count());
            double[] costs = FxTestSupport.callOnFx(() -> {
                Region surface = h.surface();
                long t0 = System.nanoTime();
                for (int i = 0; i < 1_000; i++) {
                    assertNotNull(FxTestSupport.call(surface, "nodeBox", new Class<?>[] {Path.class}, last));
                }
                long lookups = System.nanoTime() - t0;
                FxTestSupport.invoke(surface, "paintNow"); // warm
                long t1 = System.nanoTime();
                for (int i = 0; i < 10; i++) {
                    FxTestSupport.invoke(surface, "paintNow");
                }
                return new double[] {lookups / 1e6, (System.nanoTime() - t1) / 10 / 1e6};
            });
            System.out.printf(
                    "[canvas5] 20,000 rows: 1,000 lookups of the last row %.2f ms; paint %.2f ms%n",
                    costs[0], costs[1]);
            // A scan of 20,000 rows per lookup made this a few hundred ms; a lookup in the index takes microseconds.
            assertTrue(costs[0] < 40, "1,000 lookups took " + costs[0] + " ms");
        }
    }

    // --- D5: elision falls back without a nested computeIfAbsent ----------------------------------------------

    @Test
    void aNameNoAffixCutFitsIsCutInTheMiddleWithoutThrowing() throws Exception {
        Path project = project();
        Path names = Files.createDirectory(project.resolve("names"));
        Path longName = Files.writeString(names.resolve("x".repeat(200) + ".txt"), "");
        Files.writeString(names.resolve("a&b<c>.txt"), "");
        Files.writeString(names.resolve("über Ω.txt"), "");
        Files.writeString(names.resolve("emoji😀.txt"), "");
        try (Harness h = open(project, 1000, 700)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(longName));
            waitForFx(() -> idle(h) && longName.equals(selected(h)));
            settle(h);
            FxTestSupport.runOnFx(() -> {
                Map<String, String> cache = FxTestSupport.field(h.surface(), "elidedTitles");
                cache.clear(); // the first paint of the row: the affix cut finds nothing and cuts the middle
                FxTestSupport.invoke(h.surface(), "paintNow"); // threw ConcurrentModificationException
                assertTrue(
                        cache.values().stream().anyMatch(value -> value.startsWith("xx") && value.contains("…")),
                        cache.toString());
            });
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static List<ProjectMapModel.Entry> rows(Harness h) {
        try {
            return FxTestSupport.callOnFx(
                    () -> FxTestSupport.<List<ProjectMapModel.Entry>>field(h.surface(), "entries"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Path selected(Harness h) {
        return (Path) FxTestSupport.call(h.surface(), "selectedPath", new Class<?>[0]);
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out");
    }
}
