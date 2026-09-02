package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMapViewFxTest {

    @TempDir
    Path root;

    @BeforeAll
    static void startToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void mapToggleShowsAndLaysOutTheCanvasNavigator() throws Exception {
        Files.createDirectory(root.resolve("src"));
        Files.writeString(root.resolve("README.md"), "# Test");

        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel value = new ProjectPanel(path -> {}, (from, to) -> {}, path -> {}, path -> false);
            value.setRoot(root);
            return value;
        });
        try {
            FxTestSupport.runOnFx(() -> {
                HBox filterBar = FxTestSupport.field(panel, "filterBar");
                HBox modes = (HBox) filterBar.getChildren().getLast();
                ToggleButton map = (ToggleButton) modes.getChildren().getLast();
                map.fire();

                ProjectMapView mapView = FxTestSupport.field(panel, "mapView");
                assertSame(mapView, panel.getChildren().getLast());

                Scene scene = new Scene(panel, 760, 520);
                scene.getStylesheets()
                        .add(ProjectMapViewFxTest.class
                                .getResource("/com/editora/styles/app.css")
                                .toExternalForm());
                panel.applyCss();
                panel.resize(760, 520);
                panel.layout();

                Region surface = FxTestSupport.field(mapView, "surface");
                assertTrue(surface.getWidth() > 0);
                assertTrue(surface.getHeight() > 0);

                StackPane host = (StackPane) mapView.getChildren().getLast();
                Region zoomBar = (Region) host.lookup(".project-map-zoom");
                assertTrue(zoomBar.getWidth() < host.getWidth() / 2, "zoom controls must not cover the canvas");
                assertTrue(
                        zoomBar.getBoundsInParent().getMaxY() > host.getHeight() / 2,
                        "zoom controls should stay at the bottom of the canvas");
            });
        } finally {
            FxTestSupport.runOnFx(panel::dispose);
        }
    }

    @Test
    void canvasRasterizesTheSharedFolderAndFileTypeGlyphs() throws Exception {
        Set<Path> openFiles = new HashSet<>();
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, openFiles::contains, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                Scene scene = new Scene(mapView, 500, 300);
                scene.getStylesheets()
                        .add(ProjectMapViewFxTest.class
                                .getResource("/com/editora/styles/app.css")
                                .toExternalForm());
                mapView.applyCss();
                mapView.resize(500, 300);
                mapView.layout();

                Region surface = FxTestSupport.field(mapView, "surface");
                Path javaFile = root.resolve("Main.java").toAbsolutePath().normalize();
                List<ProjectMapModel.Entry> entries = List.of(
                        new ProjectMapModel.Entry(root, null, 0, true),
                        new ProjectMapModel.Entry(javaFile, root, 1, false));
                FxTestSupport.call(
                        surface, "setEntries", new Class<?>[] {List.class, Set.class}, entries, Set.of(root));

                Map<?, Image> images = FxTestSupport.field(surface, "iconImages");
                assertTrue(
                        images.keySet().stream().anyMatch(key -> key.toString().contains("kind=folder")));
                assertTrue(
                        images.keySet().stream().anyMatch(key -> key.toString().contains("kind=java")));
                assertTrue(images.values().stream().allMatch(ProjectMapViewFxTest::hasVisiblePixel));

                openFiles.add(javaFile);
                FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, javaFile);
                mapView.refreshStates();
                assertEquals(Set.of(javaFile), FxTestSupport.field(surface, "openPaths"));
                assertTrue(surface.getAccessibleText().contains("open in a tab"));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void oneClickExpandsAndCollapsesFolderNodes() throws Exception {
        Path source =
                Files.createDirectory(root.resolve("src")).toAbsolutePath().normalize();
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 500, 300);
                mapView.setRoot(root);
                mapView.applyCss();
                mapView.resize(500, 300);
                mapView.layout();

                Region surface = FxTestSupport.field(mapView, "surface");
                surface.resize(500, 240);
                surface.layout();
                List<ProjectMapModel.Entry> entries = List.of(
                        new ProjectMapModel.Entry(root, null, 0, true),
                        new ProjectMapModel.Entry(source, root, 1, true));
                FxTestSupport.call(
                        surface, "setEntries", new Class<?>[] {List.class, Set.class}, entries, Set.of(root));

                List<?> boxes = FxTestSupport.field(surface, "boxes");
                Object sourceBox = boxes.stream()
                        .filter(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]))
                                .path()
                                .equals(source))
                        .findFirst()
                        .orElseThrow();
                double clickX = (double) FxTestSupport.call(sourceBox, "x", new Class<?>[0])
                        + (double) FxTestSupport.call(sourceBox, "width", new Class<?>[0]) / 2;
                double clickY = (double) FxTestSupport.call(sourceBox, "y", new Class<?>[0])
                        + (double) FxTestSupport.call(sourceBox, "height", new Class<?>[0]) / 2;

                click(surface, clickX, clickY);
                assertTrue(mapView.expandedDirectories().contains(source));

                click(surface, clickX, clickY);
                assertFalse(mapView.expandedDirectories().contains(source));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    private static void click(Region target, double x, double y) {
        MouseEvent event = new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                new PickResult(target, x, y));
        FxTestSupport.invokeWith(target, "mouseClicked", MouseEvent.class, event);
    }

    private static boolean hasVisiblePixel(Image image) {
        for (int y = 0; y < (int) image.getHeight(); y++) {
            for (int x = 0; x < (int) image.getWidth(); x++) {
                if (image.getPixelReader().getArgb(x, y) >>> 24 != 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
