package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.collections.ObservableList;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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
 * The canvas group of the Map explore round: Collapse All and Close Other Branches (position 13), the rows on
 * screen as accessible tree items (14), a long folder listed whole (15), PNG / SVG export and the PDF's text
 * (16), the quieter column lock (17) and Reveal in Map from the Project tree (18).
 */
@Tag("fx")
class ProjectMapCanvasExploreFxTest {

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

    private Harness open(Path project, double width, double height) throws Exception {
        List<String> statuses = new CopyOnWriteArrayList<>();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            view.setOnStatus(statuses::add);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), width, height));
            stage.show();
            view.setRoot(project);
            return new Harness(stage, view, FxTestSupport.field(view, "surface"), project, statuses);
        });
        waitForFx(() -> !rows(h.surface()).isEmpty() && idle(h));
        return h;
    }

    // --- 13: Collapse All, Close Other Branches --------------------------------------------------------

    @Test
    void collapseAllKeepsTheWayToTheSelectionAndCloseOtherBranchesKeepsTheRowsOwnColumn() throws Exception {
        Path project = project();
        Path deepA = Files.createDirectories(project.resolve("a/x"));
        Path f = Files.writeString(deepA.resolve("f.txt"), "f");
        Path deepB = Files.createDirectories(project.resolve("b/y"));
        Path g = Files.writeString(deepB.resolve("g.txt"), "g");
        Files.writeString(project.resolve("a/note.txt"), "n"); // no folder chains: each folder is opened by hand
        Files.writeString(project.resolve("b/note.txt"), "n");
        Path c = Files.createDirectories(project.resolve("c"));
        Path h1 = Files.writeString(c.resolve("h.txt"), "h");
        try (Harness h = open(project, 1200, 800)) {
            for (Path file : List.of(g, h1, f)) {
                FxTestSupport.runOnFx(() -> h.view().revealPath(file));
                waitForFx(() ->
                        rows(h.surface()).stream().anyMatch(row -> row.path().equals(file)) && idle(h));
            }
            waitForFx(() -> f.equals(selected(h)));
            assertEquals(Set.of(project, project.resolve("a"), deepA, project.resolve("b"), deepB, c), expanded(h));

            // A row's menu offers Close Other Branches while something off its way is open.
            ContextMenu rowMenu = FxTestSupport.callOnFx(() -> {
                h.view().setContextMenuFactory(entry -> new ContextMenu());
                ProjectMapModel.Entry entryB = entry(h, project.resolve("b"));
                return (ContextMenu) FxTestSupport.call(
                        h.view(),
                        "menuWithMapItems",
                        new Class<?>[] {ProjectMapModel.Entry.class, ContextMenu.class},
                        entryB,
                        new ContextMenu());
            });
            MenuItem closeOthers = ProjectPanelRig.entry(rowMenu.getItems(), "project.map.menu.closeOtherBranches");
            assertNotNull(closeOthers, "the row menu has Close Other Branches");
            FxTestSupport.runOnFx(closeOthers::fire);
            try {
                waitForFx(() -> idle(h) && expanded(h).equals(Set.of(project, project.resolve("b"))));
            } catch (AssertionError e) {
                throw new AssertionError("open: " + expanded(h) + " statuses " + h.statuses(), e);
            }
            assertEquals(project.resolve("b"), selected(h), "the selection was in a closed branch: it goes to the row");

            // Collapse All: the first column and the way to the selection.
            FxTestSupport.runOnFx(() -> h.view().revealPath(h1));
            waitForFx(() -> h1.equals(selected(h)) && idle(h));
            FxTestSupport.runOnFx(() -> h.view().revealPath(g));
            waitForFx(() -> g.equals(selected(h)) && idle(h));
            FxTestSupport.runOnFx(() -> h.view().canvasCloseOthers(null, false));
            try {
                waitForFx(() -> idle(h) && expanded(h).equals(Set.of(project, project.resolve("b"), deepB)));
            } catch (AssertionError e) {
                throw new AssertionError("open: " + expanded(h) + " selected " + selected(h), e);
            }
            assertEquals(g, selected(h));

            FxTestSupport.runOnFx(() -> h.view().canvasCloseOthers(null, false));
            assertEquals(
                    tr("project.map.status.nothingToCollapse"), h.statuses().getLast());
        }
    }

    // --- 14: the rows on screen as accessible tree items ---------------------------------------------------

    @Test
    void theRowsOnScreenAreTreeItemsAndTheSelectedOneIsTheFocusItem() throws Exception {
        Path project = project();
        Path docs = Files.createDirectory(project.resolve("docs"));
        Files.writeString(docs.resolve("guide.md"), "# g");
        for (int i = 0; i < 80; i++) {
            Files.writeString(project.resolve(String.format("file-%03d.txt", i)), "x");
        }
        try (Harness h = open(project, 900, 500)) {
            settle(h);
            FxTestSupport.runOnFx(() -> {
                Region surface = h.surface();
                assertEquals(AccessibleRole.PARENT, surface.getAccessibleRole(), "the surface keeps its own summary");
                ObservableList<?> children =
                        (ObservableList<?>) surface.queryAccessibleAttribute(AccessibleAttribute.CHILDREN);
                MapAccessibleRows tree = children.stream()
                        .filter(MapAccessibleRows.class::isInstance)
                        .map(MapAccessibleRows.class::cast)
                        .findFirst()
                        .orElseThrow();
                assertEquals(AccessibleRole.TREE_VIEW, tree.getAccessibleRole());
                int count = (int) tree.queryAccessibleAttribute(AccessibleAttribute.ROW_COUNT);
                assertTrue(count > 3, "rows on screen: " + count);
                assertTrue(count < 81, "only the rows in the viewport get an item: " + count);

                MapAccessibleRows.Row first = tree.rows().getFirst();
                assertEquals(AccessibleRole.TREE_ITEM, first.queryAccessibleAttribute(AccessibleAttribute.ROLE));
                String name = (String) first.queryAccessibleAttribute(AccessibleAttribute.TEXT);
                assertEquals(tr("project.map.accessible.row.folderCollapsed", "docs", 1, 81), name);
                assertEquals(false, first.queryAccessibleAttribute(AccessibleAttribute.EXPANDED));
                assertEquals(false, first.queryAccessibleAttribute(AccessibleAttribute.LEAF));
                assertSame(tree.rows().get(1), tree.queryAccessibleAttribute(AccessibleAttribute.ROW_AT_INDEX, 1));
                assertNotNull(first.queryAccessibleAttribute(AccessibleAttribute.BOUNDS));

                // A reader moving to a row selects it; that row is then the focus item.
                MapAccessibleRows.Row third = tree.rows().get(2);
                Path thirdPath = third.spec().path();
                third.executeAccessibleAction(AccessibleAction.REQUEST_FOCUS);
                assertEquals(thirdPath, FxTestSupport.call(surface, "selectedPath", new Class<?>[0]));
                FxTestSupport.invoke(surface, "paintNow");
                Object focus = surface.queryAccessibleAttribute(AccessibleAttribute.FOCUS_ITEM);
                assertTrue(focus instanceof MapAccessibleRows.Row row
                        && row.spec().path().equals(thirdPath));
                assertSame(focus, third, "a row on screen keeps its node across paints");
                assertEquals(true, third.queryAccessibleAttribute(AccessibleAttribute.SELECTED));

                // Expand opens the folder's column.
                tree.rows().getFirst().executeAccessibleAction(AccessibleAction.EXPAND);
            });
            waitForFx(() -> idle(h) && expanded(h).contains(docs));
            settle(h);
            FxTestSupport.runOnFx(() -> {
                MapAccessibleRows tree = FxTestSupport.call(h.surface(), "canvasAccessibleRows", new Class<?>[0])
                                instanceof MapAccessibleRows rows
                        ? rows
                        : null;
                assertNotNull(tree);
                MapAccessibleRows.Row docsRow = tree.row(docs);
                assertNotNull(docsRow);
                assertEquals(true, docsRow.queryAccessibleAttribute(AccessibleAttribute.EXPANDED));
                assertEquals(1, docsRow.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_COUNT));
                MapAccessibleRows.Row guide = (MapAccessibleRows.Row)
                        docsRow.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_AT_INDEX, 0);
                assertSame(docsRow, guide.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_PARENT));
                assertEquals(1, guide.queryAccessibleAttribute(AccessibleAttribute.DISCLOSURE_LEVEL));
            });
        }
    }

    @Test
    void aBigMapBuildsItemsForTheScreenOnly() throws Exception {
        Path project = project();
        Path big = Files.createDirectory(project.resolve("big"));
        for (int i = 0; i < 1_200; i++) {
            Files.writeString(big.resolve(String.format("f%04d.txt", i)), "");
        }
        try (Harness h = open(project, 900, 600)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(big.resolve("f0600.txt")));
            waitForFx(() -> idle(h) && rows(h.surface()).size() > 1_200);
            settle(h);
            long[] micros = new long[1];
            int count = FxTestSupport.callOnFx(() -> {
                for (int warm = 0; warm < 20; warm++) {
                    FxTestSupport.invoke(h.surface(), "canvasA11yRebuild");
                }
                long started = System.nanoTime();
                for (int run = 0; run < 50; run++) {
                    FxTestSupport.invoke(h.surface(), "canvasA11yRebuild");
                }
                micros[0] = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started) / 50;
                MapAccessibleRows tree =
                        (MapAccessibleRows) FxTestSupport.call(h.surface(), "canvasAccessibleRows", new Class<?>[0]);
                return tree.rows().size();
            });
            assertTrue(count > 5 && count < 120, "a screen of rows, not 1,200: " + count);
            System.out.println(
                    "[canvas] accessible rebuild over 1,201 rows: " + count + " items in " + micros[0] + " µs");
            assertTrue(micros[0] < 2_000, "a rebuild is a small share of a pulse: " + micros[0] + " µs");
        }
    }

    // --- 15: a long folder end to end ------------------------------------------------------------------------

    @Test
    void aFiveThousandFileFolderIsListedWholeAndScrollsToItsLastRowWithNoMore() throws Exception {
        Path project = project();
        Path big = Files.createDirectory(project.resolve("big"));
        for (int i = 0; i < 5_000; i++) {
            Files.writeString(big.resolve(String.format("case-%05d.bin", i)), "");
        }
        Path other = Files.createDirectory(project.resolve("other"));
        for (int i = 0; i < 900; i++) {
            Files.writeString(other.resolve(String.format("o%03d.txt", i)), "");
        }
        Path last = big.resolve("case-04999.bin");
        try (Harness h = open(project, 1000, 700)) {
            long started = System.nanoTime();
            FxTestSupport.runOnFx(() -> h.view().revealPath(big.resolve("case-00000.bin")));
            waitForFx(() -> idle(h)
                    && rows(h.surface()).stream().anyMatch(row -> row.path().equals(last)));
            long listedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertFalse(
                    rows(h.surface()).stream().anyMatch(ProjectMapModel.Entry::isMore), "no +N more in a whole folder");

            // Another long folder opens beside it: the budget counts what each can show, so both stay whole.
            FxTestSupport.runOnFx(() -> h.view().revealPath(other.resolve("o899.txt")));
            waitForFx(() -> idle(h)
                    && rows(h.surface()).stream().anyMatch(row -> row.path().equals(other.resolve("o000.txt"))));
            List<ProjectMapModel.Entry> rows = rows(h.surface());
            assertEquals(
                    5_000, rows.stream().filter(row -> big.equals(row.parent())).count());
            assertEquals(
                    900, rows.stream().filter(row -> other.equals(row.parent())).count());
            assertFalse(rows.stream().anyMatch(ProjectMapModel.Entry::isPlaceholder), "no stub, no +N more");

            // End to end: the last row is reached, drawn and selected.
            FxTestSupport.runOnFx(() -> h.view().revealPath(last));
            waitForFx(() -> last.equals(selected(h)));
            settle(h);
            FxTestSupport.runOnFx(() -> {
                Object box = FxTestSupport.call(h.surface(), "nodeBox", new Class<?>[] {Path.class}, last);
                assertNotNull(box);
                double y = (double) FxTestSupport.call(box, "y", new Class<?>[0]);
                assertTrue(y >= 0 && y < h.surface().getHeight(), "the last row is on screen at " + y);
            });

            // A row listed by name only gets its size and time when its tooltip asks.
            String tooltip = FxTestSupport.callOnFx(() -> (String) FxTestSupport.call(
                    h.surface(), "tooltipText", new Class<?>[] {ProjectMapModel.Entry.class}, entry(h, last)));
            assertTrue(tooltip.contains(tr("project.map.tooltip.size", "0 B")), tooltip);

            // The FX cost: a full paint, and pans across the long column, with 5,900 rows laid out.
            double[] costs = FxTestSupport.callOnFx(() -> {
                Region surface = h.surface();
                long t0 = System.nanoTime();
                FxTestSupport.invoke(surface, "paintNow");
                long paint = System.nanoTime() - t0;
                long pans = 0;
                for (int i = 0; i < 40; i++) {
                    long t = System.nanoTime();
                    FxTestSupport.call(surface, "panBy", new Class<?>[] {double.class, double.class}, 0.0, 400.0);
                    FxTestSupport.invoke(surface, "paintNow");
                    pans += System.nanoTime() - t;
                }
                return new double[] {paint / 1e6, pans / 40 / 1e6};
            });
            System.out.printf(
                    "[canvas] 5,000 + 900 rows: listed and shown in %d ms; paint %.2f ms; pan+paint %.2f ms%n",
                    listedMillis, costs[0], costs[1]);
            assertTrue(costs[1] < 40, "a pan over the long folder stays well inside a frame budget: " + costs[1]);
        }
    }

    @Test
    void aRemoteLikeRootKeepsPlusNMore() throws Exception {
        Path project = project();
        Path big = Files.createDirectory(project.resolve("big"));
        for (int i = 0; i < 700; i++) {
            Files.writeString(big.resolve(String.format("f%03d.txt", i)), "");
        }
        try (Harness h = open(project, 900, 600)) {
            FxTestSupport.runOnFx(() -> {
                MapLoader loader = FxTestSupport.field(h.view(), "mapLoader");
                loader.canvasWholeFolders = root -> false; // what a remote root does
                h.view().revealPath(big.resolve("f000.txt"));
            });
            waitForFx(() -> idle(h) && rows(h.surface()).stream().anyMatch(ProjectMapModel.Entry::isMore));
            assertEquals(
                    ProjectMapModel.DIRECTORY_CHUNK,
                    rows(h.surface()).stream()
                            .filter(row -> big.equals(row.parent()) && !row.isPlaceholder())
                            .count());
        }
    }

    // --- 16: PNG, SVG and the PDF's text -------------------------------------------------------------------

    @Test
    void pngIsTheViewAtTwiceItsSizeAndSvgHoldsEveryNameAsText() throws Exception {
        Path project = project();
        Path src = Files.createDirectory(project.resolve("src"));
        Path main = Files.writeString(src.resolve("Main & <Co>.java"), "class Main {}");
        Files.writeString(project.resolve("README.md"), "# r");
        Path png = temp.resolve("out/map.png");
        Path svg = temp.resolve("out/map.svg");
        Files.createDirectories(png.getParent());
        try (Harness h = open(project, 800, 600)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(main));
            waitForFx(() -> idle(h) && main.equals(selected(h)));
            settle(h);
            List<String> asked = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                h.view().canvasExportChooser = (extension, base) -> {
                    asked.add(base + "." + extension);
                    return (extension.equals("png") ? png : svg).toFile();
                };
                h.view().canvasExportWriter = Runnable::run;
                h.view().canvasExportForTest(true);
                h.view().canvasExportForTest(false);
            });
            String base = project.getFileName() + "-map";
            assertEquals(List.of(base + ".png", base + ".svg"), asked);
            waitForFx(() -> Files.exists(png) && Files.exists(svg));

            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(png.toFile());
            double hostWidth = FxTestSupport.callOnFx(
                    () -> FxTestSupport.<Region>field(h.view(), "canvasHost").getWidth());
            assertEquals((int) Math.round(hostWidth * ProjectMapView.CANVAS_PNG_SCALE), image.getWidth(), 1);

            String text = Files.readString(svg);
            org.w3c.dom.Document document = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(svg.toFile());
            assertEquals("svg", document.getDocumentElement().getNodeName());
            org.w3c.dom.NodeList texts = document.getElementsByTagName("text");
            List<String> words = new ArrayList<>();
            for (int i = 0; i < texts.getLength(); i++) {
                words.add(texts.item(i).getTextContent());
            }
            assertTrue(words.contains("Main & <Co>.java"), words.toString());
            assertTrue(words.contains("README.md"), words.toString());
            assertTrue(words.contains("src"), words.toString());
            assertTrue(text.contains("<path d=\"M"), "the connectors are drawn");
            assertTrue(
                    h.statuses().contains(tr("project.map.export.done", "map.svg")),
                    h.statuses().toString());
        }
    }

    @Test
    void thePrintedPagesCarryTheirNamesForTheSearchablePdf() throws Exception {
        Path project = project();
        Path src = Files.createDirectory(project.resolve("src"));
        Path main = Files.writeString(src.resolve("Main.java"), "class Main {}");
        try (Harness h = open(project, 800, 600)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(main));
            waitForFx(() -> idle(h) && main.equals(selected(h)));
            settle(h);
            ProjectMapOutput.Rendered rendered =
                    FxTestSupport.callOnFx(() -> (ProjectMapOutput.Rendered) FxTestSupport.call(
                            h.surface(), "renderOutput", new Class<?>[] {double.class, double.class}, 540.0, 720.0));
            assertNotNull(rendered);
            assertEquals(rendered.pages().size(), rendered.labels().size());
            List<String> names = rendered.labels().getFirst().stream()
                    .map(ProjectMapOutput.Label::text)
                    .toList();
            assertTrue(names.contains("Main.java") && names.contains("src"), names.toString());
            javafx.scene.image.Image page = rendered.pages().getFirst();
            for (ProjectMapOutput.Label label : rendered.labels().getFirst()) {
                assertTrue(label.x() >= 0
                        && label.y() >= 0
                        && label.x() < page.getWidth()
                        && label.y() < page.getHeight());
            }

            Path pdf = temp.resolve("map.pdf");
            java.util.List<java.awt.image.BufferedImage> images = new ArrayList<>();
            for (javafx.scene.image.Image image : rendered.pages()) {
                images.add(javafx.embed.swing.SwingFXUtils.fromFXImage(image, null));
            }
            com.editora.pdf.ImagePdfWriter.writePages(
                    images, rendered.pointsPerPixel(), "A4", false, ExportCoordinator.projectMapTexts(rendered), pdf);
            try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
                String extracted = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                assertTrue(extracted.contains("Main.java"), extracted);
                assertTrue(extracted.contains("src"), extracted);
            }
        }
    }

    // --- 17: the column lock shows while the column is in use -----------------------------------------------

    @Test
    void theColumnLockShowsOnlyWhileTheColumnIsHoveredFocusedOrLocked() throws Exception {
        Path project = project();
        Path src = Files.createDirectory(project.resolve("src"));
        Files.writeString(src.resolve("A.java"), "class A {}");
        try (Harness h = open(project, 900, 600)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(src.resolve("A.java")));
            waitForFx(() -> idle(h) && rows(h.surface()).stream().anyMatch(row -> src.equals(row.parent())));
            settle(h);
            FxTestSupport.runOnFx(() -> {
                Region surface = h.surface();
                ProjectMapModel.ColumnId column = new ProjectMapModel.ColumnId(2, src);
                Map<ProjectMapModel.ColumnId, ?> controls = FxTestSupport.field(surface, "columnControls");
                Object made = controls.get(column);
                assertNotNull(made, "the src column has its controls");
                ToggleButton pin = (ToggleButton) FxTestSupport.call(made, "pin", new Class<?>[0]);
                Node filter = (Node) FxTestSupport.call(made, "filter", new Class<?>[0]);
                FxTestSupport.call(
                        surface, "layoutHover", new Class<?>[] {ProjectMapModel.ColumnId.class}, (Object) null);
                assertTrue(filter.isVisible(), "the filter stays");
                assertFalse(pin.isVisible(), "the lock waits for the column to be in use");

                FxTestSupport.call(surface, "layoutHover", new Class<?>[] {ProjectMapModel.ColumnId.class}, column);
                assertTrue(pin.isVisible(), "hovered: the lock shows");
                pin.setSelected(true);
                FxTestSupport.call(
                        surface, "layoutHover", new Class<?>[] {ProjectMapModel.ColumnId.class}, (Object) null);
                FxTestSupport.invoke(surface, "paintNow");
                assertTrue(pin.isVisible(), "locked: the lock stays");
                pin.setSelected(false);
                FxTestSupport.invoke(surface, "paintNow");
                assertFalse(pin.isVisible());
                filter.requestFocus();
            });
            FxTestSupport.runOnFx(() -> {
                Map<ProjectMapModel.ColumnId, ?> controls = FxTestSupport.field(h.surface(), "columnControls");
                ToggleButton pin = (ToggleButton)
                        FxTestSupport.call(controls.get(new ProjectMapModel.ColumnId(2, src)), "pin", new Class<?>[0]);
                assertTrue(pin.isVisible(), "its filter has the focus: the lock shows");
                assertTrue(pin.isFocusTraversable(), "and Tab reaches it");
            });
        }
    }

    // --- 18: Reveal in Map from the Project tree -----------------------------------------------------------

    @Test
    void theProjectTreesFileMenuRevealsTheFileInTheMap(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> {
                ContextMenu menu = r.menu(r.item(r.alpha), false, false);
                MenuItem reveal = ProjectPanelRig.entry(menu.getItems(), "project.map.menu.revealInMap");
                assertNotNull(reveal, "the tree's file menu has Reveal in Map");
                reveal.fire();
            });
            r.await("the Map window to show the file", () -> {
                ProjectMapView map = FxTestSupport.field(r.panel, "mapView");
                Region surface = FxTestSupport.field(map, "surface");
                return r.panel.isMapShowing()
                        && r.alpha.equals(FxTestSupport.call(surface, "selectedPath", new Class<?>[0]));
            });
            FxTestSupport.runOnFx(() -> {
                ProjectMapView map = FxTestSupport.field(r.panel, "mapView");
                ContextMenu mapRow = (ContextMenu) FxTestSupport.call(
                        r.panel,
                        "contextMenuFor",
                        new Class<?>[] {javafx.scene.control.TreeItem.class, boolean.class, boolean.class},
                        mapRowItem(r.alpha),
                        false,
                        false);
                assertEquals(
                        null,
                        ProjectPanelRig.entry(mapRow.getItems(), "project.map.menu.revealInMap"),
                        "a Map row's own menu does not offer the Map");
                assertNotNull(map);
                r.panel.mapWindow().closeByUser();
            });
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------

    /** The tree item the panel builds a Map row's menu for (a private class of the panel). */
    private static javafx.scene.control.TreeItem<?> mapRowItem(Path path) {
        try {
            var constructor =
                    Class.forName("com.editora.ui.ProjectPanel$MapRowItem").getDeclaredConstructor(Path.class);
            constructor.setAccessible(true);
            return (javafx.scene.control.TreeItem<?>) constructor.newInstance(path);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

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

    private static ProjectMapModel.Entry entry(Harness h, Path path) {
        Map<Path, ProjectMapModel.Entry> byPath = FxTestSupport.field(h.surface(), "entryByPath");
        return byPath.get(path);
    }

    private static Set<Path> expanded(Harness h) {
        try {
            return FxTestSupport.callOnFx(() -> {
                MapLoader loader = FxTestSupport.field(h.view(), "mapLoader");
                return loader.expandedDirectories();
            });
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out");
    }
}
