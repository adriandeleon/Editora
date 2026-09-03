package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
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

                @SuppressWarnings("unchecked")
                Function<ProjectMapModel.Entry, ContextMenu> contextMenuFactory =
                        FxTestSupport.field(surface, "contextMenuFactory");
                Path readme = root.resolve("README.md").toAbsolutePath().normalize();
                ContextMenu menu = contextMenuFactory.apply(new ProjectMapModel.Entry(readme, root, 1, false));
                assertTrue(menu.getItems().stream()
                        .anyMatch(item -> tr("project.menu.rename").equals(item.getText())));
                assertTrue(menu.getItems().stream()
                        .anyMatch(item -> tr("project.menu.delete").equals(item.getText())));

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
        Path javaFile = Files.writeString(root.resolve("Main.java"), "class Main {}")
                .toAbsolutePath()
                .normalize();
        List<ProjectMapModel.Entry> entries = ProjectMapModel.loadVisible(root, Set.of(root), false);
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

                Object javaBox = boxFor(surface, javaFile);
                double hoverX = center(javaBox, "x", "width");
                double hoverY = center(javaBox, "y", "height");
                move(surface, hoverX, hoverY);
                Tooltip tooltip = FxTestSupport.field(surface, "nodeTooltip");
                assertTrue(tooltip.getText().contains(javaFile.toString()));
                assertTrue(tooltip.getText().contains("Type: File"));
                assertTrue(tooltip.getText().contains("Size: 13 B"));
                assertTrue(tooltip.getText().contains("Modified:"));
                assertTrue(tooltip.getText().contains("Open in a tab"));
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

                Object sourceBox = boxFor(surface, source);
                double clickX = center(sourceBox, "x", "width");
                double clickY = center(sourceBox, "y", "height");

                click(surface, clickX, clickY);
                assertTrue(mapView.expandedDirectories().contains(source));
                Button back = FxTestSupport.field(mapView, "backButton");
                assertFalse(back.isDisabled());
                HBox breadcrumbs = FxTestSupport.field(mapView, "breadcrumbs");
                assertTrue(breadcrumbs.getChildren().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .anyMatch(button -> "src".equals(button.getText())));
                back.fire();
                @SuppressWarnings("unchecked")
                java.util.Optional<ProjectMapModel.Entry> selected = (java.util.Optional<ProjectMapModel.Entry>)
                        FxTestSupport.call(surface, "selectedEntry", new Class<?>[0]);
                assertEquals(
                        root.toAbsolutePath().normalize(),
                        selected.orElseThrow().path());

                click(surface, clickX, clickY);
                assertFalse(mapView.expandedDirectories().contains(source));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void oneClickShowsAMovableResizableScrollableCodePreview() throws Exception {
        Path file = Files.writeString(root.resolve("Preview.java"), "class Preview {\n    int value = 7;\n}\n")
                .toAbsolutePath()
                .normalize();
        AtomicReference<Path> opened = new AtomicReference<>();
        ProjectMapView mapView = FxTestSupport.callOnFx(() -> new ProjectMapView(
                opened::set,
                path -> false,
                path -> false,
                path -> new ProjectMapPreview.Content("class Preview {\n    int value = 8;\n}\n", false)));
        try {
            FxTestSupport.runOnFx(() -> {
                Scene scene = new Scene(mapView, 900, 620);
                scene.getStylesheets()
                        .add(ProjectMapViewFxTest.class
                                .getResource("/com/editora/styles/app.css")
                                .toExternalForm());
                mapView.resize(900, 620);
                mapView.applyCss();
                mapView.layout();

                Region surface = FxTestSupport.field(mapView, "surface");
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(file, root, 1, false)),
                        Set.of(root));

                Object fileBox = boxFor(surface, file);
                click(surface, center(fileBox, "x", "width"), center(fileBox, "y", "height"));

                ProjectMapPreview preview = FxTestSupport.field(mapView, "preview");
                assertTrue(preview.isVisible());
                assertEquals(file, preview.path());
                assertTrue(preview.editor().getText().contains("value = 8"), "open-buffer content should win");
                assertEquals(640, preview.getWidth(), 0.001);
                assertEquals(420, preview.getHeight(), 0.001);
                javafx.scene.layout.BorderPane frame = FxTestSupport.field(preview, "frame");
                assertTrue(frame.getCenter() instanceof org.fxmisc.flowless.VirtualizedScrollPane<?>);

                preview.relocate(40, 40);
                double beforeWidth = preview.getWidth();
                FxTestSupport.invokeWith(
                        preview,
                        "resizePressed",
                        MouseEvent.class,
                        mouse(preview, MouseEvent.MOUSE_PRESSED, beforeWidth, preview.getHeight()));
                FxTestSupport.invokeWith(
                        preview,
                        "resized",
                        MouseEvent.class,
                        mouse(preview, MouseEvent.MOUSE_DRAGGED, beforeWidth + 70, preview.getHeight() + 45));
                assertTrue(preview.getWidth() > beforeWidth);
                assertTrue(preview.getHeight() > 420);

                Button open = FxTestSupport.field(preview, "open");
                open.fire();
                assertEquals(file, opened.get());

                Button close = FxTestSupport.field(preview, "close");
                close.fire();
                assertFalse(preview.isVisible());
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void rightClickSelectsTheNodeAndRequestsItsSharedContextMenu() throws Exception {
        Path file = Files.writeString(root.resolve("notes.txt"), "hello")
                .toAbsolutePath()
                .normalize();
        AtomicReference<ProjectMapModel.Entry> requested = new AtomicReference<>();
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 500, 300);
                mapView.applyCss();
                mapView.resize(500, 300);
                mapView.layout();

                Region surface = FxTestSupport.field(mapView, "surface");
                surface.resize(500, 240);
                List<ProjectMapModel.Entry> entries = List.of(
                        new ProjectMapModel.Entry(root, null, 0, true),
                        new ProjectMapModel.Entry(file, root, 1, false));
                FxTestSupport.call(
                        surface, "setEntries", new Class<?>[] {List.class, Set.class}, entries, Set.of(root));
                mapView.setContextMenuFactory(entry -> {
                    requested.set(entry);
                    return null;
                });

                Object fileBox = boxFor(surface, file);
                double x = center(fileBox, "x", "width");
                double y = center(fileBox, "y", "height");
                ContextMenuEvent event = new ContextMenuEvent(
                        ContextMenuEvent.CONTEXT_MENU_REQUESTED, x, y, x, y, false, new PickResult(surface, x, y));
                FxTestSupport.invokeWith(surface, "contextMenuRequested", ContextMenuEvent.class, event);

                assertEquals(file, requested.get().path());
                assertTrue(surface.getAccessibleText().contains("notes.txt"));
                assertTrue(event.isConsumed());
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void backspaceEditsAColumnFilterInsteadOfNavigatingTheMap() throws Exception {
        Path source = root.resolve("src").toAbsolutePath().normalize();
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 500, 300);
                mapView.resize(500, 300);
                mapView.layout();
                Region surface = FxTestSupport.field(mapView, "surface");
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(source, root, 1, true)),
                        Set.of(root));
                mapView.applyCss();
                mapView.layout();

                TextField filter = (TextField) surface.lookup(".project-map-column-filter");
                filter.setText("src");
                filter.positionCaret(filter.getLength());
                Event.fireEvent(
                        filter,
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE, false, false, false, false));

                assertEquals("sr", filter.getText());
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void mapContextMenuClosesOnTheNextPressAnywhereInTheWindow() throws Exception {
        Path file = root.resolve("notes.txt").toAbsolutePath().normalize();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<ContextMenu> menuRef = new AtomicReference<>();
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                Stage stage = new Stage();
                stageRef.set(stage);
                StackPane window = new StackPane(mapView);
                Scene scene = new Scene(window, 500, 300);
                stage.setScene(scene);
                stage.show();
                window.applyCss();
                window.layout();

                Region surface = FxTestSupport.field(mapView, "surface");
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(file, root, 1, false)),
                        Set.of(root));
                ContextMenu menu = new ContextMenu(new MenuItem("Open"));
                menu.setAutoHide(false); // exercise the map's explicit fallback, not the native popup grab
                menuRef.set(menu);
                mapView.setContextMenuFactory(entry -> menu);

                Object fileBox = boxFor(surface, file);
                double x = center(fileBox, "x", "width");
                double y = center(fileBox, "y", "height");
                Point2D screen = surface.localToScreen(x, y);
                ContextMenuEvent event = new ContextMenuEvent(
                        ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                        x,
                        y,
                        screen.getX(),
                        screen.getY(),
                        false,
                        new PickResult(surface, x, y));
                FxTestSupport.invokeWith(surface, "contextMenuRequested", ContextMenuEvent.class, event);
            });
            FxTestSupport.runOnFx(() -> {}); // let the deferred owner-scene dismissal filter arm

            assertTrue(FxTestSupport.callOnFx(() -> menuRef.get().isShowing()));
            FxTestSupport.runOnFx(() -> Event.fireEvent(
                    stageRef.get().getScene().getRoot(),
                    mouse((Region) stageRef.get().getScene().getRoot(), MouseEvent.MOUSE_PRESSED, 480, 280)));
            assertFalse(FxTestSupport.callOnFx(() -> menuRef.get().isShowing()));
        } finally {
            FxTestSupport.runOnFx(() -> {
                mapView.dispose();
                if (stageRef.get() != null) {
                    stageRef.get().hide();
                }
            });
        }
    }

    @Test
    void eachColumnHeightFollowsItsVisibleRowsWithBottomPadding() throws Exception {
        Path src = root.resolve("src").toAbsolutePath().normalize();
        Path docs = root.resolve("docs").toAbsolutePath().normalize();
        Path readme = root.resolve("README.md").toAbsolutePath().normalize();
        Path java = src.resolve("App.java");
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 720, 420);
                mapView.resize(720, 420);
                mapView.layout();
                Region surface = FxTestSupport.field(mapView, "surface");
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(src, root, 1, true),
                                new ProjectMapModel.Entry(docs, root, 1, true),
                                new ProjectMapModel.Entry(readme, root, 1, false),
                                new ProjectMapModel.Entry(java, src, 2, false)),
                        Set.of(root, src));

                Object depthOne = columnBoxFor(surface, 1);
                Object depthTwo = columnBoxFor(surface, 2);
                double depthOneHeight = (double) FxTestSupport.call(depthOne, "height", new Class<?>[0]);
                double depthTwoHeight = (double) FxTestSupport.call(depthTwo, "height", new Class<?>[0]);
                assertTrue(depthOneHeight > depthTwoHeight, "a three-row column should be taller than one row");

                double columnBottom = (double) FxTestSupport.call(depthOne, "y", new Class<?>[0]) + depthOneHeight;
                double lastRowBottom = FxTestSupport.<List<?>>field(surface, "boxes").stream()
                        .filter(box -> entryOf(box).depth() == 1)
                        .mapToDouble(box -> (double) FxTestSupport.call(box, "y", new Class<?>[0])
                                + (double) FxTestSupport.call(box, "height", new Class<?>[0]))
                        .max()
                        .orElseThrow();
                double zoom = FxTestSupport.field(surface, "zoom");
                assertEquals(12 * zoom, columnBottom - lastRowBottom, 0.001);
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void columnHiddenCheckboxStartsEnabledAndHidesTheWholeDotFolderBranch() throws Exception {
        Path hidden = root.resolve(".config").toAbsolutePath().normalize();
        Path hiddenChild = hidden.resolve("settings.json");
        Path source = root.resolve("src").toAbsolutePath().normalize();
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 720, 420);
                mapView.resize(720, 420);
                mapView.layout();
                Region surface = FxTestSupport.field(mapView, "surface");
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(hidden, root, 1, true),
                                new ProjectMapModel.Entry(source, root, 1, true),
                                new ProjectMapModel.Entry(hiddenChild, hidden, 2, false)),
                        Set.of(root, hidden));

                Object controls = FxTestSupport.<Map<Integer, ?>>field(surface, "columnControls")
                        .get(1);
                CheckBox showHidden = (CheckBox) FxTestSupport.call(controls, "showHidden", new Class<?>[0]);
                assertTrue(showHidden.isSelected());
                assertTrue(FxTestSupport.<List<?>>field(surface, "boxes").stream()
                        .anyMatch(box -> entryOf(box).path().equals(hidden)));

                showHidden.fire();
                assertFalse(FxTestSupport.<List<?>>field(surface, "boxes").stream()
                        .anyMatch(box -> entryOf(box).path().equals(hidden)));
                assertFalse(FxTestSupport.<List<?>>field(surface, "boxes").stream()
                        .anyMatch(box -> entryOf(box).path().equals(hiddenChild)));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void flowSelectorReorientsColumnsAndRowsInAllFourDirections() throws Exception {
        Path src = root.resolve("src").toAbsolutePath().normalize();
        Path docs = root.resolve("docs").toAbsolutePath().normalize();
        Path java = src.resolve("App.java");
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 900, 600);
                mapView.resize(900, 600);
                mapView.layout();
                Region surface = FxTestSupport.field(mapView, "surface");
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(src, root, 1, true),
                                new ProjectMapModel.Entry(docs, root, 1, true),
                                new ProjectMapModel.Entry(java, src, 2, false)),
                        Set.of(root, src));

                @SuppressWarnings("unchecked")
                ComboBox<ProjectMapView.FlowDirection> flow = FxTestSupport.field(mapView, "flowFilter");
                assertEquals(ProjectMapView.FlowDirection.LEFT_TO_RIGHT, flow.getValue());
                assertTrue(origin(boxFor(surface, java), "x") > origin(boxFor(surface, src), "x"));

                flow.setValue(ProjectMapView.FlowDirection.RIGHT_TO_LEFT);
                assertTrue(origin(boxFor(surface, java), "x") < origin(boxFor(surface, src), "x"));

                flow.setValue(ProjectMapView.FlowDirection.TOP_TO_BOTTOM);
                assertTrue(origin(boxFor(surface, java), "y") > origin(boxFor(surface, src), "y"));
                assertTrue(origin(boxFor(surface, src), "x") > origin(boxFor(surface, docs), "x"));

                flow.setValue(ProjectMapView.FlowDirection.BOTTOM_TO_TOP);
                assertTrue(origin(boxFor(surface, java), "y") < origin(boxFor(surface, src), "y"));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void arrowKeysFollowTheSelectedFlowDirection() throws Exception {
        Path src = root.resolve("src").toAbsolutePath().normalize();
        Path java = src.resolve("App.java");
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 700, 420);
                mapView.resize(700, 420);
                mapView.layout();
                Region surface = FxTestSupport.field(mapView, "surface");
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(src, root, 1, true),
                                new ProjectMapModel.Entry(java, src, 2, false)),
                        Set.of(root, src));

                FxTestSupport.call(surface, "setSelected", new Class<?>[] {Path.class}, src);
                FxTestSupport.call(
                        surface,
                        "setFlowDirection",
                        new Class<?>[] {ProjectMapView.FlowDirection.class},
                        ProjectMapView.FlowDirection.TOP_TO_BOTTOM);
                FxTestSupport.call(surface, "navigateArrow", new Class<?>[] {KeyCode.class}, KeyCode.DOWN);
                assertEquals(java, selectedPath(surface));

                FxTestSupport.call(surface, "navigateArrow", new Class<?>[] {KeyCode.class}, KeyCode.UP);
                assertEquals(src, selectedPath(surface));

                FxTestSupport.call(
                        surface,
                        "setFlowDirection",
                        new Class<?>[] {ProjectMapView.FlowDirection.class},
                        ProjectMapView.FlowDirection.RIGHT_TO_LEFT);
                FxTestSupport.call(surface, "navigateArrow", new Class<?>[] {KeyCode.class}, KeyCode.LEFT);
                assertEquals(java, selectedPath(surface));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void columnFiltersReflowDescendantsAndPinnedColumnsDoNotDrag() throws Exception {
        Path src = root.resolve("src").toAbsolutePath().normalize();
        Path docs = root.resolve("docs").toAbsolutePath().normalize();
        Path java = src.resolve("App.java");
        Path guide = docs.resolve("guide.md");
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 720, 420);
                mapView.applyCss();
                mapView.resize(720, 420);
                mapView.layout();
                Region surface = FxTestSupport.field(mapView, "surface");
                surface.resize(720, 350);
                List<ProjectMapModel.Entry> entries = List.of(
                        new ProjectMapModel.Entry(root, null, 0, true),
                        new ProjectMapModel.Entry(src, root, 1, true),
                        new ProjectMapModel.Entry(docs, root, 1, true),
                        new ProjectMapModel.Entry(java, src, 2, false),
                        new ProjectMapModel.Entry(guide, docs, 2, false));
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        entries,
                        Set.of(root, src, docs));

                Map<Integer, ?> controls = FxTestSupport.field(surface, "columnControls");
                Object depthOne = controls.get(1);
                TextField filter = (TextField) FxTestSupport.call(depthOne, "filter", new Class<?>[0]);
                ToggleButton pin = (ToggleButton) FxTestSupport.call(depthOne, "pin", new Class<?>[0]);
                filter.setText("src");
                List<?> filteredBoxes = FxTestSupport.field(surface, "boxes");
                assertTrue(filteredBoxes.stream()
                        .anyMatch(box -> entryOf(box).path().equals(src)));
                assertTrue(filteredBoxes.stream()
                        .anyMatch(box -> entryOf(box).path().equals(java)));
                assertFalse(filteredBoxes.stream()
                        .anyMatch(box -> entryOf(box).path().equals(docs)));
                assertFalse(filteredBoxes.stream()
                        .anyMatch(box -> entryOf(box).path().equals(guide)));

                Object columnBox = columnBoxFor(surface, 1);
                double x = (double) FxTestSupport.call(columnBox, "x", new Class<?>[0]) + 5;
                double y = (double) FxTestSupport.call(columnBox, "y", new Class<?>[0]) + 5;
                drag(surface, x, y, x + 45, y + 20);
                Object layout = ((Map<?, ?>) FxTestSupport.field(surface, "columnLayouts")).get(1);
                double movedX = FxTestSupport.field(layout, "x");
                assertTrue(movedX > 0);

                pin.fire();
                drag(surface, x + 45, y + 20, x + 100, y + 50);
                assertEquals(movedX, (double) FxTestSupport.field(layout, "x"), 0.001);
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void plainMouseWheelZoomsAroundThePointer() throws Exception {
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 500, 300);
                mapView.applyCss();
                mapView.resize(500, 300);
                mapView.layout();
                Region surface = FxTestSupport.field(mapView, "surface");
                double before = FxTestSupport.field(surface, "zoom");
                ScrollEvent wheel = new ScrollEvent(
                        ScrollEvent.SCROLL,
                        250,
                        150,
                        250,
                        150,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        0,
                        120,
                        0,
                        120,
                        ScrollEvent.HorizontalTextScrollUnits.NONE,
                        0,
                        ScrollEvent.VerticalTextScrollUnits.NONE,
                        0,
                        0,
                        new PickResult(surface, 250, 150));
                FxTestSupport.invokeWith(surface, "scrolled", ScrollEvent.class, wheel);
                assertTrue((double) FxTestSupport.field(surface, "zoom") > before);
                assertTrue(wheel.isConsumed());
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    private static Object boxFor(Region surface, Path path) {
        List<?> boxes = FxTestSupport.field(surface, "boxes");
        return boxes.stream()
                .filter(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]))
                        .path()
                        .equals(path))
                .findFirst()
                .orElseThrow();
    }

    private static ProjectMapModel.Entry entryOf(Object box) {
        return (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
    }

    @SuppressWarnings("unchecked")
    private static Path selectedPath(Region surface) {
        return ((java.util.Optional<ProjectMapModel.Entry>)
                        FxTestSupport.call(surface, "selectedEntry", new Class<?>[0]))
                .orElseThrow()
                .path();
    }

    private static Object columnBoxFor(Region surface, int depth) {
        List<?> boxes = FxTestSupport.field(surface, "columnBoxes");
        return boxes.stream()
                .filter(box -> {
                    ProjectMapModel.Column column =
                            (ProjectMapModel.Column) FxTestSupport.call(box, "column", new Class<?>[0]);
                    return column.depth() == depth;
                })
                .findFirst()
                .orElseThrow();
    }

    private static double center(Object box, String origin, String size) {
        return (double) FxTestSupport.call(box, origin, new Class<?>[0])
                + (double) FxTestSupport.call(box, size, new Class<?>[0]) / 2;
    }

    private static double origin(Object box, String coordinate) {
        return (double) FxTestSupport.call(box, coordinate, new Class<?>[0]);
    }

    private static void move(Region target, double x, double y) {
        MouseEvent event = new MouseEvent(
                MouseEvent.MOUSE_MOVED,
                x,
                y,
                x,
                y,
                MouseButton.NONE,
                0,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                new PickResult(target, x, y));
        FxTestSupport.invokeWith(target, "mouseMoved", MouseEvent.class, event);
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

    private static void drag(Region target, double fromX, double fromY, double toX, double toY) {
        FxTestSupport.invokeWith(
                target, "mousePressed", MouseEvent.class, mouse(target, MouseEvent.MOUSE_PRESSED, fromX, fromY));
        FxTestSupport.invokeWith(
                target, "mouseDragged", MouseEvent.class, mouse(target, MouseEvent.MOUSE_DRAGGED, toX, toY));
        target.fireEvent(mouse(target, MouseEvent.MOUSE_RELEASED, toX, toY));
    }

    private static MouseEvent mouse(Region target, javafx.event.EventType<MouseEvent> type, double x, double y) {
        return new MouseEvent(
                type,
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
                true,
                false,
                false,
                false,
                false,
                false,
                new PickResult(target, x, y));
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
