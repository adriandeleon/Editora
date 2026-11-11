package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.Scene;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.image.WritableImage;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map's dependency overlay (Map code browser round 2): with ⋯ "Show dependencies of the selection" on, the
 * rows the selected file or folder uses and those that use it are tinted, said in their tooltips and on the status
 * line.
 */
@Tag("fx")
class MapDependencyOverlayFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static void write(Path root, String rel, String text) throws Exception {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    void theSelectionsUsesAndUsersAreTintedAroundAFolderAndAFile() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        write(project, "app/Main.java", "package app;\n\nimport core.Service;\nimport core.Repo;\n\nclass Main {}\n");
        write(project, "core/Service.java", "package core;\n\npublic class Service {\n    Repo repo;\n}\n");
        write(project, "core/Repo.java", "package core;\n\nimport db.Db;\n\npublic class Repo {\n    Db db;\n}\n");
        write(project, "db/Db.java", "package db;\n\nimport core.Repo;\n\npublic class Db {}\n");
        write(project, "README.md", "# Project\n");
        List<String> said = new CopyOnWriteArrayList<>();
        List<Boolean> remembered = new CopyOnWriteArrayList<>();
        Stage[] stage = new Stage[1];
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false);
            made.setOnStatus(said::add);
            made.setOnShowDependenciesChanged(remembered::add);
            stage[0] = new Stage();
            Scene scene = new Scene(new StackPane(made), 1200, 800);
            scene.getStylesheets()
                    .add(MapDependencyOverlayFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage[0].setScene(scene);
            stage[0].show();
            made.setRoot(project);
            return made;
        });
        try {
            MapSurface surface = FxTestSupport.callOnFx(() -> FxTestSupport.field(view, "surface"));
            Path core = project.resolve("core");
            Path db = project.resolve("db");
            Path app = project.resolve("app");
            waitFor(() -> surface.contains(core) && surface.contains(db));
            FxTestSupport.runOnFx(() -> {
                assertFalse(view.isShowDependencies());
                surface.select(core); // off: nothing is worked out
                assertSame(MapDependencyOverlay.NONE, surface.dependencyOverlay());
                view.setShowDependencies(true); // on: the imports are read for the selection
                assertEquals(List.of(true), remembered);
            });
            waitFor(() -> core.equals(surface.dependencyOverlay().subject()));
            FxTestSupport.runOnFx(() -> {
                MapDependencyOverlay overlay = surface.dependencyOverlay();
                assertEquals(MapDependencyOverlay.BOTH, overlay.mark(db), "core uses db, db uses core");
                assertEquals(MapDependencyOverlay.USED_BY, overlay.mark(app));
                assertEquals(tr("project.map.deps.overlayStatus", "core", 1, 2), said.getLast());
                String tip = (String) FxTestSupport.call(
                        surface,
                        "tooltipText",
                        new Class<?>[] {ProjectMapModel.Entry.class},
                        new ProjectMapModel.Entry(db, project, 1, true));
                assertTrue(tip.contains(tr("project.map.tooltip.depBoth")), tip);
                String appTip = (String) FxTestSupport.call(
                        surface,
                        "tooltipText",
                        new Class<?>[] {ProjectMapModel.Entry.class},
                        new ProjectMapModel.Entry(app, project, 1, true));
                assertTrue(appTip.contains(tr("project.map.tooltip.depUses")), appTip);
                view.applyCss();
                view.layout();
            });
            // The used row is drawn in its colour: app's border is olive, not the plain border.
            Color border = FxTestSupport.callOnFx(() -> {
                WritableImage shot = surface.snapshot(null, null);
                double[] box = box(surface, app);
                return box == null
                        ? null
                        : shot.getPixelReader()
                                .getColor((int) Math.round(box[0] + box[2] / 2), (int) Math.round(box[1]));
            });
            assertNotNull(border, "app's row is laid out");
            assertTrue(border.getGreen() > border.getBlue(), "olive: " + border);

            // A file: its own folder holds it and is not marked.
            FxTestSupport.runOnFx(() -> view.revealPath(project.resolve("core/Repo.java")));
            waitFor(() -> project.resolve("core/Repo.java")
                    .equals(surface.dependencyOverlay().subject()));
            FxTestSupport.runOnFx(() -> {
                MapDependencyOverlay overlay = surface.dependencyOverlay();
                assertEquals(MapDependencyOverlay.BOTH, overlay.mark(project.resolve("db/Db.java")));
                assertEquals(MapDependencyOverlay.USED_BY, overlay.mark(project.resolve("core/Service.java")));
                assertEquals(0, overlay.mark(core));
                assertEquals(tr("project.map.deps.overlayStatus", "Repo.java", 1, 3), said.getLast());
                // Far out the rows are bars: the marked ones keep their colours.
                FxTestSupport.call(
                        surface, "setZoom", new Class<?>[] {double.class, double.class, double.class}, 0.3, 0.0, 0.0);
                surface.snapshot(null, null);
            });

            // A file whose imports the Map does not read says so.
            FxTestSupport.runOnFx(() -> view.revealPath(project.resolve("README.md")));
            waitFor(() -> project.resolve("README.md")
                    .equals(surface.dependencyOverlay().subject()));
            FxTestSupport.runOnFx(() -> {
                assertEquals(tr("project.map.deps.overlayNotRead", "README.md"), said.getLast());
                CheckMenuItem option = FxTestSupport.field(view, "dependencyOverlayOption");
                option.setSelected(false); // off: the tint goes at once
                assertSame(MapDependencyOverlay.NONE, surface.dependencyOverlay());
                assertEquals(List.of(true, false), remembered);
                option.setSelected(true);
                view.setRoot(db); // a new root: nothing until a selection there
                assertSame(MapDependencyOverlay.NONE, surface.dependencyOverlay());
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage[0].close();
            });
        }
    }

    /** A row as last projected, {@code {x, y, w, h}}, or null when it is not laid out. */
    private static double @org.jspecify.annotations.Nullable [] box(MapSurface surface, Path path) {
        List<?> boxes = FxTestSupport.field(surface, "boxes");
        for (Object box : List.copyOf(boxes)) {
            ProjectMapModel.Entry entry = (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
            if (entry.path().equals(path)) {
                return new double[] {
                    (double) FxTestSupport.call(box, "x", new Class<?>[0]),
                    (double) FxTestSupport.call(box, "y", new Class<?>[0]),
                    (double) FxTestSupport.call(box, "width", new Class<?>[0]),
                    (double) FxTestSupport.call(box, "height", new Class<?>[0])
                };
            }
        }
        return null;
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
