package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
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
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.config.NoteScope;
import com.editora.editor.NoteDraft;
import com.editora.pdf.PdfExportService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
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
        Path readme = Files.writeString(root.resolve("README.md"), "# Test")
                .toAbsolutePath()
                .normalize();
        Set<Path> bookmarked = new HashSet<>(Set.of(readme));
        Set<Path> noted = new HashSet<>(Set.of(readme));
        AtomicInteger bookmarkAdds = new AtomicInteger();
        AtomicInteger noteAdds = new AtomicInteger();

        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel value = new ProjectPanel(path -> {}, (from, to) -> {}, path -> {}, path -> false);
            value.setMarkerActions(new ProjectPanel.MarkerActions() {
                @Override
                public boolean personalNotesEnabled() {
                    return true;
                }

                @Override
                public boolean hasBookmarks(Path file) {
                    return bookmarked.contains(file.toAbsolutePath().normalize());
                }

                @Override
                public boolean hasPersonalNotes(Path file) {
                    return noted.contains(file.toAbsolutePath().normalize());
                }

                @Override
                public void addBookmark(Path file) {
                    bookmarkAdds.incrementAndGet();
                }

                @Override
                public void addPersonalNote(Path file) {
                    noteAdds.incrementAndGet();
                }
            });
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
                ContextMenu menu = contextMenuFactory.apply(new ProjectMapModel.Entry(readme, root, 1, false));
                assertTrue(menu.getItems().stream()
                        .anyMatch(item -> tr("project.menu.rename").equals(item.getText())));
                assertTrue(menu.getItems().stream()
                        .anyMatch(item -> tr("project.menu.delete").equals(item.getText())));
                MenuItem addBookmark = menu.getItems().stream()
                        .filter(item -> tr("project.menu.addBookmark").equals(item.getText()))
                        .findFirst()
                        .orElseThrow();
                MenuItem addNote = menu.getItems().stream()
                        .filter(item -> tr("project.menu.addPersonalNote").equals(item.getText()))
                        .findFirst()
                        .orElseThrow();
                addBookmark.fire();
                addNote.fire();
                assertEquals(1, bookmarkAdds.get());
                assertEquals(1, noteAdds.get());

                @SuppressWarnings("unchecked")
                TreeCell<Path> cell = ((javafx.scene.control.TreeView<Path>) FxTestSupport.field(panel, "tree"))
                        .getCellFactory()
                        .call(FxTestSupport.field(panel, "tree"));
                FxTestSupport.call(cell, "updateItem", new Class<?>[] {Path.class, boolean.class}, readme, false);
                assertTrue(hasStyleClass(cell.getGraphic(), "project-bookmark-indicator"));
                assertTrue(hasStyleClass(cell.getGraphic(), "project-note-indicator"));

                mapView.refreshStates();
                assertTrue(((Set<?>) FxTestSupport.field(surface, "bookmarkedPaths")).contains(readme));
                assertTrue(((Set<?>) FxTestSupport.field(surface, "notedPaths")).contains(readme));

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

    private static boolean hasStyleClass(javafx.scene.Node node, String styleClass) {
        if (node == null) {
            return false;
        }
        if (node.getStyleClass().contains(styleClass)) {
            return true;
        }
        return node instanceof javafx.scene.Parent parent
                && parent.getChildrenUnmodifiable().stream().anyMatch(child -> hasStyleClass(child, styleClass));
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
                mapView.setRememberedFlow("LEFT_TO_RIGHT", ignored -> {});

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
        Path project =
                Files.createDirectory(root.resolve("project")).toAbsolutePath().normalize();
        Path source =
                Files.createDirectory(project.resolve("src")).toAbsolutePath().normalize();
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 500, 300);
                mapView.setRoot(project);
                mapView.applyCss();
                mapView.resize(500, 300);
                mapView.layout();

                Region surface = FxTestSupport.field(mapView, "surface");
                surface.resize(500, 240);
                surface.layout();
                List<ProjectMapModel.Entry> entries = List.of(
                        new ProjectMapModel.Entry(project, null, 0, true),
                        new ProjectMapModel.Entry(source, project, 1, true));
                FxTestSupport.call(
                        surface, "setEntries", new Class<?>[] {List.class, Set.class}, entries, Set.of(project));

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
                assertEquals(project, selected.orElseThrow().path());

                Object currentSourceBox = boxFor(surface, source);
                click(surface, center(currentSourceBox, "x", "width"), center(currentSourceBox, "y", "height"));
                assertFalse(mapView.expandedDirectories().contains(source));
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void newlyOpenedColumnsCenterOnTheClickedDirectoryAndStayBeyondTheParentColumn() throws Exception {
        Path first = root.resolve("first").toAbsolutePath().normalize();
        Path second = root.resolve("second").toAbsolutePath().normalize();
        Path clicked = root.resolve("third").toAbsolutePath().normalize();
        Path childA = clicked.resolve("alpha.txt");
        Path childB = clicked.resolve("beta.txt");
        List<ProjectMapModel.Entry> collapsed = List.of(
                new ProjectMapModel.Entry(root, null, 0, true),
                new ProjectMapModel.Entry(first, root, 1, true),
                new ProjectMapModel.Entry(second, root, 1, true),
                new ProjectMapModel.Entry(clicked, root, 1, true));
        List<ProjectMapModel.Entry> expanded = List.of(
                collapsed.get(0),
                collapsed.get(1),
                collapsed.get(2),
                collapsed.get(3),
                new ProjectMapModel.Entry(childA, clicked, 2, false),
                new ProjectMapModel.Entry(childB, clicked, 2, false));

        for (ProjectMapView.FlowDirection flow : ProjectMapView.FlowDirection.values()) {
            ProjectMapView mapView =
                    FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
            try {
                FxTestSupport.runOnFx(() -> {
                    new Scene(mapView, 900, 600);
                    mapView.resize(900, 600);
                    mapView.layout();
                    Region surface = FxTestSupport.field(mapView, "surface");
                    surface.resize(900, 530);
                    FxTestSupport.call(
                            surface, "setOnActivate", new Class<?>[] {Consumer.class}, (Consumer<ProjectMapModel.Entry>)
                                    ignored -> {});
                    FxTestSupport.call(
                            surface, "setFlowDirection", new Class<?>[] {ProjectMapView.FlowDirection.class}, flow);
                    FxTestSupport.call(
                            surface, "setEntries", new Class<?>[] {List.class, Set.class}, collapsed, Set.of(root));

                    Object clickedBox = boxFor(surface, clicked);
                    double anchorX = center(clickedBox, "x", "width");
                    double anchorY = center(clickedBox, "y", "height");
                    click(surface, anchorX, anchorY);
                    FxTestSupport.call(
                            surface,
                            "setEntries",
                            new Class<?>[] {List.class, Set.class},
                            expanded,
                            Set.of(root, clicked));

                    Object currentAnchor = boxFor(surface, clicked);
                    Object parentColumn = columnBoxFor(surface, 1);
                    Object childColumn = columnBoxFor(surface, 2);
                    switch (flow) {
                        case LEFT_TO_RIGHT -> {
                            assertEquals(
                                    center(currentAnchor, "y", "height"), center(childColumn, "y", "height"), 0.001);
                            assertTrue(
                                    edge(parentColumn, "x", "width") <= origin(childColumn, "x"),
                                    "the child column must be right of its parent");
                        }
                        case RIGHT_TO_LEFT -> {
                            assertEquals(
                                    center(currentAnchor, "y", "height"), center(childColumn, "y", "height"), 0.001);
                            assertTrue(
                                    edge(childColumn, "x", "width") <= origin(parentColumn, "x"),
                                    "the child column must be left of its parent");
                        }
                        case TOP_TO_BOTTOM -> {
                            assertEquals(center(currentAnchor, "x", "width"), center(childColumn, "x", "width"), 0.001);
                            assertTrue(
                                    edge(parentColumn, "y", "height") <= origin(childColumn, "y"),
                                    "the child column must be below its parent");
                        }
                        case BOTTOM_TO_TOP -> {
                            assertEquals(center(currentAnchor, "x", "width"), center(childColumn, "x", "width"), 0.001);
                            assertTrue(
                                    edge(childColumn, "y", "height") <= origin(parentColumn, "y"),
                                    "the child column must be above its parent");
                        }
                    }
                });
            } finally {
                FxTestSupport.runOnFx(mapView::dispose);
            }
        }
    }

    @Test
    void siblingBranchColumnsStayOpenWithoutOverlapAndExposeIndependentCloseButtons() throws Exception {
        Path src = root.resolve("src").toAbsolutePath().normalize();
        Path docs = root.resolve("docs").toAbsolutePath().normalize();
        Path java = src.resolve("App.java");
        Path guide = docs.resolve("guide.md");
        Path packageDirectory = src.resolve("example");
        List<ProjectMapModel.Entry> entries = List.of(
                new ProjectMapModel.Entry(root, null, 0, true),
                new ProjectMapModel.Entry(src, root, 1, true),
                new ProjectMapModel.Entry(docs, root, 1, true),
                new ProjectMapModel.Entry(java, src, 2, false),
                new ProjectMapModel.Entry(guide, docs, 2, false));

        for (ProjectMapView.FlowDirection flow : ProjectMapView.FlowDirection.values()) {
            ProjectMapView mapView =
                    FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
            try {
                FxTestSupport.runOnFx(() -> {
                    new Scene(mapView, 900, 600);
                    mapView.resize(900, 600);
                    mapView.layout();
                    Region surface = FxTestSupport.field(mapView, "surface");
                    surface.resize(900, 530);
                    Set<Path> expanded = FxTestSupport.field(mapView, "expanded");
                    expanded.addAll(Set.of(root.toAbsolutePath().normalize(), src, docs, packageDirectory));
                    FxTestSupport.call(
                            surface, "setFlowDirection", new Class<?>[] {ProjectMapView.FlowDirection.class}, flow);
                    FxTestSupport.call(
                            surface,
                            "setEntries",
                            new Class<?>[] {List.class, Set.class},
                            entries,
                            Set.of(root, src, docs));

                    Object srcColumn = columnBoxForParent(surface, src);
                    Object docsColumn = columnBoxForParent(surface, docs);
                    assertFalse(overlaps(srcColumn, docsColumn), "sibling branch columns must never overlap");

                    Object parentColumn = columnBoxForParent(surface, root);
                    switch (flow) {
                        case LEFT_TO_RIGHT -> {
                            assertTrue(edge(parentColumn, "x", "width") <= origin(srcColumn, "x"));
                            assertTrue(edge(parentColumn, "x", "width") <= origin(docsColumn, "x"));
                        }
                        case RIGHT_TO_LEFT -> {
                            assertTrue(edge(srcColumn, "x", "width") <= origin(parentColumn, "x"));
                            assertTrue(edge(docsColumn, "x", "width") <= origin(parentColumn, "x"));
                        }
                        case TOP_TO_BOTTOM -> {
                            assertTrue(edge(parentColumn, "y", "height") <= origin(srcColumn, "y"));
                            assertTrue(edge(parentColumn, "y", "height") <= origin(docsColumn, "y"));
                        }
                        case BOTTOM_TO_TOP -> {
                            assertTrue(edge(srcColumn, "y", "height") <= origin(parentColumn, "y"));
                            assertTrue(edge(docsColumn, "y", "height") <= origin(parentColumn, "y"));
                        }
                    }

                    Object controls = columnControlsFor(surface, src);
                    Button close = (Button) FxTestSupport.call(controls, "close", new Class<?>[0]);
                    assertEquals(tr("project.map.column.close"), close.getAccessibleText());
                    close.fire();
                    assertEquals(Set.of(root.toAbsolutePath().normalize(), docs), mapView.expandedDirectories());
                });
            } finally {
                FxTestSupport.runOnFx(mapView::dispose);
            }
        }
    }

    @Test
    void printAndPdfActionsSnapshotTheCompleteMapWithoutChangingTheLiveViewport() throws Exception {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<ProjectMapModel.Entry> entries = new ArrayList<>();
        entries.add(new ProjectMapModel.Entry(normalizedRoot, null, 0, true));
        for (int i = 0; i < 14; i++) {
            entries.add(
                    new ProjectMapModel.Entry(normalizedRoot.resolve("File" + i + ".java"), normalizedRoot, 1, false));
        }
        AtomicReference<Image> printed = new AtomicReference<>();
        AtomicReference<Image> exported = new AtomicReference<>();
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 340, 260);
                mapView.resize(340, 260);
                mapView.layout();
                mapView.setOutputActions(printed::set, exported::set);
                Region surface = FxTestSupport.field(mapView, "surface");
                surface.resize(340, 190);
                surface.layout();
                FxTestSupport.call(
                        surface, "setEntries", new Class<?>[] {List.class, Set.class}, entries, Set.of(normalizedRoot));
                FxTestSupport.call(mapView, "setOutputEnabled", new Class<?>[] {boolean.class}, true);

                double liveZoom = FxTestSupport.field(surface, "zoom");
                double liveOffsetX = FxTestSupport.field(surface, "offsetX");
                double liveOffsetY = FxTestSupport.field(surface, "offsetY");
                Canvas canvas = FxTestSupport.field(surface, "canvas");
                double liveCanvasWidth = canvas.getWidth();
                double liveCanvasHeight = canvas.getHeight();

                FxTestSupport.<Button>field(mapView, "printButton").fire();
                FxTestSupport.<Button>field(mapView, "exportPdfButton").fire();

                assertTrue(printed.get().getHeight() > liveCanvasHeight, "print must include rows below the viewport");
                assertTrue(exported.get().getHeight() > liveCanvasHeight, "PDF must include rows below the viewport");
                assertEquals(liveZoom, (double) FxTestSupport.field(surface, "zoom"), 0.001);
                assertEquals(liveOffsetX, (double) FxTestSupport.field(surface, "offsetX"), 0.001);
                assertEquals(liveOffsetY, (double) FxTestSupport.field(surface, "offsetY"), 0.001);
                assertEquals(liveCanvasWidth, canvas.getWidth(), 0.001);
                assertEquals(liveCanvasHeight, canvas.getHeight(), 0.001);
            });

            Path pdf = root.resolve("project-map.pdf");
            CountDownLatch exportedPdf = new CountDownLatch(1);
            AtomicReference<PdfExportService.Result> result = new AtomicReference<>();
            PdfExportService pdfService = new PdfExportService();
            try {
                pdfService.exportFxImages(List.of(exported.get()), "letter", pdf, value -> {
                    result.set(value);
                    exportedPdf.countDown();
                });
                assertTrue(exportedPdf.await(20, TimeUnit.SECONDS), "PDF export must complete");
                assertTrue(result.get().ok(), result.get().message());
                assertTrue(Files.size(pdf) > 0);
                try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                    assertTrue(document.getNumberOfPages() >= 1);
                }
            } finally {
                pdfService.shutdown();
            }
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void pannedColumnControlsAreClippedToTheMapViewport() throws Exception {
        Path source = root.resolve("src").toAbsolutePath().normalize();
        Path child = source.resolve("App.java");
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 720, 500);
                mapView.resize(720, 500);
                mapView.layout();
                Region surface = FxTestSupport.field(mapView, "surface");
                surface.resize(720, 400);
                surface.layout();
                FxTestSupport.call(
                        surface,
                        "setFlowDirection",
                        new Class<?>[] {ProjectMapView.FlowDirection.class},
                        ProjectMapView.FlowDirection.LEFT_TO_RIGHT);
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(source, root, 1, true),
                                new ProjectMapModel.Entry(child, source, 2, false)),
                        Set.of(root, source));

                Object controls = columnControlsFor(surface, root);
                TextField filter = (TextField) FxTestSupport.call(controls, "filter", new Class<?>[0]);
                drag(surface, 690, 370, 690, -130);

                assertTrue(filter.getLayoutY() < 0, "the test must pan a column control above the viewport");
                Rectangle clip = (Rectangle) surface.getClip();
                assertEquals(0, clip.getX(), 0.001);
                assertEquals(0, clip.getY(), 0.001);
                assertEquals(surface.getWidth(), clip.getWidth(), 0.001);
                assertEquals(surface.getHeight(), clip.getHeight(), 0.001);
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void columnControlsHideWhenZoomLeavesTooLittleHeaderSpaceAndReturnWhenZoomedIn() throws Exception {
        Path source = root.resolve("src").toAbsolutePath().normalize();
        Path child = source.resolve("App.java");
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 720, 500);
                mapView.resize(720, 500);
                mapView.layout();
                Region surface = FxTestSupport.field(mapView, "surface");
                surface.resize(720, 400);
                surface.layout();
                FxTestSupport.call(
                        surface,
                        "setFlowDirection",
                        new Class<?>[] {ProjectMapView.FlowDirection.class},
                        ProjectMapView.FlowDirection.LEFT_TO_RIGHT);
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(source, root, 1, true),
                                new ProjectMapModel.Entry(child, source, 2, false)),
                        Set.of(root, source));

                Object controls = columnControlsFor(surface, root);
                TextField filter = (TextField) FxTestSupport.call(controls, "filter", new Class<?>[0]);
                CheckBox showHidden = (CheckBox) FxTestSupport.call(controls, "showHidden", new Class<?>[0]);
                ToggleButton pin = (ToggleButton) FxTestSupport.call(controls, "pin", new Class<?>[0]);
                assertTrue(filter.isVisible());
                assertTrue(showHidden.isVisible());
                assertTrue(pin.isVisible());

                FxTestSupport.call(
                        surface,
                        "setZoom",
                        new Class<?>[] {double.class, double.class, double.class},
                        0.4,
                        360.0,
                        200.0);
                assertFalse(filter.isVisible());
                assertFalse(showHidden.isVisible());
                assertFalse(pin.isVisible());

                FxTestSupport.call(
                        surface,
                        "setZoom",
                        new Class<?>[] {double.class, double.class, double.class},
                        1.0,
                        360.0,
                        200.0);
                assertTrue(filter.isVisible());
                assertTrue(showHidden.isVisible());
                assertTrue(pin.isVisible());
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void fileClickOpensATabAndItsPreviewIconShowsAReadOnlyZoomablePreview() throws Exception {
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
                mapView.setRememberedFlow("LEFT_TO_RIGHT", ignored -> {});

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

                assertEquals(file, opened.get(), "the file row itself opens a normal editor tab");
                assertTrue(previews(mapView).isEmpty());

                double previewX =
                        origin(fileBox, "x") + (double) FxTestSupport.call(fileBox, "width", new Class<?>[0]) - 8;
                click(surface, previewX, center(fileBox, "y", "height"));
                ProjectMapPreview preview = previewFor(mapView, file);
                mapView.applyCss();
                mapView.layout();
                preview.layout();
                assertTrue(preview.isVisible());
                assertEquals(file, preview.path());
                assertTrue(preview.editor().getText().contains("value = 8"), "open-buffer content should win");
                org.fxmisc.flowless.VirtualizedScrollPane<?> editorScroll =
                        FxTestSupport.field(preview, "editorScroll");
                double contentWidth = editorScroll.totalWidthEstimateProperty().getValue();
                assertTrue(
                        contentWidth <= editorScroll.getWidth(),
                        () -> "the preview should fit its content without horizontal scrolling: "
                                + contentWidth
                                + " > "
                                + editorScroll.getWidth());
                assertEquals(420, preview.getHeight(), 0.001);
                javafx.scene.layout.BorderPane frame = FxTestSupport.field(preview, "frame");
                assertTrue(frame.getCenter() instanceof org.fxmisc.flowless.VirtualizedScrollPane<?>);
                javafx.scene.control.Label readOnly = FxTestSupport.field(preview, "readOnly");
                assertEquals(tr("project.map.preview.readOnly"), readOnly.getText());
                Button zoomIn = FxTestSupport.field(preview, "zoomIn");
                zoomIn.fire();
                assertTrue((double) FxTestSupport.field(preview, "contentZoom") > 1.0);

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
    void multipleCodePreviewsRemainOpenAndCloseIndependently() throws Exception {
        Path first = Files.writeString(root.resolve("First.java"), "class First {}\n")
                .toAbsolutePath()
                .normalize();
        Path second = Files.writeString(root.resolve("Second.java"), "class Second {}\n")
                .toAbsolutePath()
                .normalize();
        ProjectMapView mapView = FxTestSupport.callOnFx(() -> new ProjectMapView(
                path -> {},
                path -> false,
                path -> false,
                path -> new ProjectMapPreview.Content("// " + path.getFileName(), false)));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 1400, 900);
                mapView.resize(1400, 900);
                mapView.applyCss();
                mapView.layout();
                mapView.setRememberedFlow("LEFT_TO_RIGHT", ignored -> {});

                Region surface = FxTestSupport.field(mapView, "surface");
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(first, root, 1, false),
                                new ProjectMapModel.Entry(second, root, 1, false)),
                        Set.of(root));

                clickPreviewIcon(surface, first);
                ProjectMapPreview firstPreview = previewFor(mapView, first);
                clickPreviewIcon(surface, second);
                ProjectMapPreview secondPreview = previewFor(mapView, second);

                assertEquals(2, previews(mapView).size());
                assertTrue(firstPreview.isVisible());
                assertTrue(secondPreview.isVisible());
                assertTrue(firstPreview.editor().getText().contains("First.java"));
                assertTrue(secondPreview.editor().getText().contains("Second.java"));
                assertTrue(
                        firstPreview.getLayoutX() != secondPreview.getLayoutX()
                                || firstPreview.getLayoutY() != secondPreview.getLayoutY(),
                        "cards should be cascaded when the viewport cannot fit both without overlap");

                FxTestSupport.<Button>field(firstPreview, "close").fire();
                assertEquals(Set.of(second), previews(mapView).keySet());
                assertTrue(secondPreview.isVisible(), "closing one card must leave its sibling open");

                clickPreviewIcon(surface, second);
                assertEquals(1, previews(mapView).size());
                assertSame(secondPreview, previewFor(mapView, second), "reopening a file must reuse its card");
            });
        } finally {
            FxTestSupport.runOnFx(mapView::dispose);
        }
    }

    @Test
    void previewOpensBeyondItsColumnInEveryFlowAndExpandsForLongLines() throws Exception {
        String longLine = "x".repeat(100);
        for (ProjectMapView.FlowDirection flow : ProjectMapView.FlowDirection.values()) {
            Path file = root.resolve(flow.name() + ".txt").toAbsolutePath().normalize();
            ProjectMapView mapView = FxTestSupport.callOnFx(() -> new ProjectMapView(
                    path -> {}, path -> false, path -> false, path -> new ProjectMapPreview.Content(longLine, false)));
            try {
                FxTestSupport.runOnFx(() -> {
                    Scene scene = new Scene(mapView, 1200, 800);
                    scene.getStylesheets()
                            .add(ProjectMapViewFxTest.class
                                    .getResource("/com/editora/styles/app.css")
                                    .toExternalForm());
                    mapView.resize(1200, 800);
                    mapView.applyCss();
                    mapView.layout();
                    mapView.setRememberedFlow(flow.name(), ignored -> {});

                    Region surface = FxTestSupport.field(mapView, "surface");
                    FxTestSupport.call(
                            surface,
                            "setEntries",
                            new Class<?>[] {List.class, Set.class},
                            List.of(
                                    new ProjectMapModel.Entry(root, null, 0, true),
                                    new ProjectMapModel.Entry(file, root, 1, false)),
                            Set.of(root));
                    surface.layout();

                    Object fileBox = boxFor(surface, file);
                    click(surface, center(fileBox, "x", "width"), center(fileBox, "y", "height"));
                    double previewX =
                            origin(fileBox, "x") + (double) FxTestSupport.call(fileBox, "width", new Class<?>[0]) - 8;
                    click(surface, previewX, center(fileBox, "y", "height"));

                    ProjectMapPreview preview = previewFor(mapView, file);
                    mapView.applyCss();
                    mapView.layout();
                    preview.layout();
                    assertTrue(preview.getWidth() > 640, "long lines should widen the preview");

                    Object column = columnBoxFor(surface, 1);
                    switch (flow) {
                        case LEFT_TO_RIGHT ->
                            assertTrue(
                                    edge(column, "x", "width") <= preview.getLayoutX(),
                                    () -> "the preview must open right of its column: column edge "
                                            + edge(column, "x", "width")
                                            + ", preview x "
                                            + preview.getLayoutX());
                        case RIGHT_TO_LEFT ->
                            assertTrue(
                                    preview.getLayoutX() + preview.getWidth() <= origin(column, "x"),
                                    () -> "the preview must open left of its column: preview edge "
                                            + (preview.getLayoutX() + preview.getWidth())
                                            + ", column x "
                                            + origin(column, "x"));
                        case TOP_TO_BOTTOM ->
                            assertTrue(
                                    edge(column, "y", "height") <= preview.getLayoutY(),
                                    () -> "the preview must open below its column: column edge "
                                            + edge(column, "y", "height")
                                            + ", preview y "
                                            + preview.getLayoutY());
                        case BOTTOM_TO_TOP ->
                            assertTrue(
                                    preview.getLayoutY() + preview.getHeight() <= origin(column, "y"),
                                    () -> "the preview must open above its column: preview edge "
                                            + (preview.getLayoutY() + preview.getHeight())
                                            + ", column y "
                                            + origin(column, "y"));
                    }

                    org.fxmisc.flowless.VirtualizedScrollPane<?> editorScroll =
                            FxTestSupport.field(preview, "editorScroll");
                    double contentWidth =
                            editorScroll.totalWidthEstimateProperty().getValue();
                    assertTrue(
                            contentWidth <= editorScroll.getWidth(),
                            () -> "the initial preview should fit its longest line: "
                                    + contentWidth
                                    + " > "
                                    + editorScroll.getWidth());
                });
            } finally {
                FxTestSupport.runOnFx(mapView::dispose);
            }
        }
    }

    @Test
    void previewContextMenuCopiesCodeAndCreatesAnchoredMarkers() throws Exception {
        Path file = root.resolve("Preview.java").toAbsolutePath().normalize();
        AtomicReference<Path> bookmarkedFile = new AtomicReference<>();
        AtomicInteger bookmarkedLine = new AtomicInteger(-1);
        AtomicReference<Path> notedFile = new AtomicReference<>();
        AtomicReference<NoteDraft> noteDraft = new AtomicReference<>();
        ProjectMapPreview preview = FxTestSupport.callOnFx(() -> new ProjectMapPreview(path -> {}));
        try {
            FxTestSupport.runOnFx(() -> {
                StackPane host = new StackPane(preview);
                new Scene(host, 900, 600);
                host.resize(900, 600);
                host.applyCss();
                host.layout();
                preview.setMarkerActions(new ProjectMapPreview.MarkerActions() {
                    @Override
                    public boolean personalNotesEnabled() {
                        return true;
                    }

                    @Override
                    public void addBookmark(Path selected, int line) {
                        bookmarkedFile.set(selected);
                        bookmarkedLine.set(line);
                    }

                    @Override
                    public void addPersonalNote(Path selected, NoteDraft draft) {
                        notedFile.set(selected);
                        noteDraft.set(draft);
                    }
                });
                preview.showFile(
                        file,
                        new ProjectMapPreview.Content("first\nsecond line\nthird", false),
                        (width, height, parentWidth, parentHeight) ->
                                new ProjectMapPreview.Placement(14, 14, width, height));
                preview.applyCss();
                preview.layout();

                FxTestSupport.invokeWith(preview, "rebuildEditorContextMenu", int.class, 1);
                ContextMenu menu = FxTestSupport.field(preview, "editorContextMenu");
                assertEquals(
                        List.of(
                                tr("editmenu.copy"),
                                tr("editmenu.selectAll"),
                                tr("editmenu.addBookmark"),
                                tr("editmenu.addNote")),
                        menu.getItems().stream()
                                .filter(item -> !(item instanceof javafx.scene.control.SeparatorMenuItem))
                                .map(MenuItem::getText)
                                .toList());
                menuItem(menu, tr("editmenu.addBookmark")).fire();
                assertEquals(file, bookmarkedFile.get());
                assertEquals(1, bookmarkedLine.get());

                preview.editor().selectRange(6, 12);
                FxTestSupport.invokeWith(preview, "rebuildEditorContextMenu", int.class, 1);
                menuItem(menu, tr("editmenu.copy")).fire();
                assertEquals("second", Clipboard.getSystemClipboard().getString());
                menuItem(menu, tr("editmenu.addNote")).fire();
                assertEquals(file, notedFile.get());
                assertEquals(NoteScope.WORD, noteDraft.get().scope());
                assertEquals(1, noteDraft.get().anchor().line());
                assertEquals("second", noteDraft.get().anchor().selectedText());
            });
        } finally {
            FxTestSupport.runOnFx(preview::dispose);
        }
    }

    @Test
    void rightClickSelectsTheNodeAndRequestsItsSharedContextMenu() throws Exception {
        Path project =
                Files.createDirectory(root.resolve("project")).toAbsolutePath().normalize();
        Path file = Files.writeString(project.resolve("notes.txt"), "hello")
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
                        new ProjectMapModel.Entry(project, null, 0, true),
                        new ProjectMapModel.Entry(file, project, 1, false));
                FxTestSupport.call(
                        surface, "setEntries", new Class<?>[] {List.class, Set.class}, entries, Set.of(project));
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
        Path project =
                Files.createDirectory(root.resolve("project")).toAbsolutePath().normalize();
        Path file = project.resolve("notes.txt").toAbsolutePath().normalize();
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
                                new ProjectMapModel.Entry(project, null, 0, true),
                                new ProjectMapModel.Entry(file, project, 1, false)),
                        Set.of(project));
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
    void columnsExpandToShowFullNamesWithoutBreakingDirectionalSpacing() throws Exception {
        String longName = "a_very_long_directory_name_that_must_remain_fully_visible_in_the_canvas";
        Path longFolder = root.resolve(longName).toAbsolutePath().normalize();
        Path shortFile = root.resolve("z.txt").toAbsolutePath().normalize();
        Path child = longFolder.resolve("Child.java");
        ProjectMapView mapView =
                FxTestSupport.callOnFx(() -> new ProjectMapView(path -> {}, path -> false, path -> false));
        try {
            FxTestSupport.runOnFx(() -> {
                new Scene(mapView, 1_000, 600);
                mapView.resize(1_000, 600);
                mapView.layout();
                mapView.setRememberedFlow("LEFT_TO_RIGHT", ignored -> {});
                Region surface = FxTestSupport.field(mapView, "surface");
                FxTestSupport.call(
                        surface,
                        "setEntries",
                        new Class<?>[] {List.class, Set.class},
                        List.of(
                                new ProjectMapModel.Entry(root, null, 0, true),
                                new ProjectMapModel.Entry(longFolder, root, 1, true),
                                new ProjectMapModel.Entry(shortFile, root, 1, false),
                                new ProjectMapModel.Entry(child, longFolder, 2, false)),
                        Set.of(root, longFolder));

                double zoom = FxTestSupport.field(surface, "zoom");
                Object folderBox = boxFor(surface, longFolder);
                double folderWidth = (double) FxTestSupport.call(folderBox, "width", new Class<?>[0]);
                Text measure = new Text(longName);
                measure.setFont(Font.font("System", FontWeight.SEMI_BOLD, 12));
                assertTrue(
                        folderWidth / zoom >= measure.getLayoutBounds().getWidth() + 57,
                        "node width must reserve the full label plus icon and status padding");

                Object childBox = boxFor(surface, child);
                assertEquals(
                        origin(folderBox, "x") + folderWidth + 50 * zoom,
                        origin(childBox, "x"),
                        0.001,
                        "the next column must follow the expanded width");

                FxTestSupport.call(
                        surface,
                        "setFlowDirection",
                        new Class<?>[] {ProjectMapView.FlowDirection.class},
                        ProjectMapView.FlowDirection.TOP_TO_BOTTOM);
                Object verticalLong = boxFor(surface, longFolder);
                Object verticalShort = boxFor(surface, shortFile);
                assertEquals(
                        origin(verticalLong, "x")
                                + (double) FxTestSupport.call(verticalLong, "width", new Class<?>[0])
                                + 9 * zoom,
                        origin(verticalShort, "x"),
                        0.001,
                        "vertical flows must use the content-sized width between siblings");
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

                Object controls = columnControlsFor(surface, root);
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
                assertEquals(ProjectMapView.FlowDirection.RIGHT_TO_LEFT, flow.getValue());
                assertTrue(origin(boxFor(surface, java), "x") < origin(boxFor(surface, src), "x"));

                mapView.setRememberedFlow("LEFT_TO_RIGHT", ignored -> {});
                assertEquals(ProjectMapView.FlowDirection.LEFT_TO_RIGHT, flow.getValue());
                assertTrue(origin(boxFor(surface, java), "x") > origin(boxFor(surface, src), "x"));

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

                Object depthOne = columnControlsFor(surface, root);
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
                Object layout = columnLayoutFor(surface, root);
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

    private static MenuItem menuItem(ContextMenu menu, String text) {
        return menu.getItems().stream()
                .filter(item -> text.equals(item.getText()))
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

    private static Object columnBoxForParent(Region surface, Path parent) {
        Path normalized = parent.toAbsolutePath().normalize();
        List<?> boxes = FxTestSupport.field(surface, "columnBoxes");
        return boxes.stream()
                .filter(box -> {
                    ProjectMapModel.Column column =
                            (ProjectMapModel.Column) FxTestSupport.call(box, "column", new Class<?>[0]);
                    return normalized.equals(column.parent());
                })
                .findFirst()
                .orElseThrow();
    }

    private static Object columnControlsFor(Region surface, Path parent) {
        return keyedColumnValue(surface, "columnControls", parent);
    }

    private static Object columnLayoutFor(Region surface, Path parent) {
        return keyedColumnValue(surface, "columnLayouts", parent);
    }

    private static Object keyedColumnValue(Region surface, String field, Path parent) {
        Path normalized = parent.toAbsolutePath().normalize();
        Map<?, ?> values = FxTestSupport.field(surface, field);
        return values.entrySet().stream()
                .filter(entry -> normalized.equals(FxTestSupport.call(entry.getKey(), "parent", new Class<?>[0])))
                .map(Map.Entry::getValue)
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

    private static double edge(Object box, String origin, String size) {
        return (double) FxTestSupport.call(box, origin, new Class<?>[0])
                + (double) FxTestSupport.call(box, size, new Class<?>[0]);
    }

    private static boolean overlaps(Object first, Object second) {
        return origin(first, "x") < edge(second, "x", "width")
                && edge(first, "x", "width") > origin(second, "x")
                && origin(first, "y") < edge(second, "y", "height")
                && edge(first, "y", "height") > origin(second, "y");
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

    @SuppressWarnings("unchecked")
    private static Map<Path, ProjectMapPreview> previews(ProjectMapView mapView) {
        return FxTestSupport.field(mapView, "previews");
    }

    private static ProjectMapPreview previewFor(ProjectMapView mapView, Path path) {
        return previews(mapView).get(path.toAbsolutePath().normalize());
    }

    private static void clickPreviewIcon(Region surface, Path path) {
        Object box = boxFor(surface, path);
        double x = origin(box, "x") + (double) FxTestSupport.call(box, "width", new Class<?>[0]) - 8;
        click(surface, x, center(box, "y", "height"));
    }
}
