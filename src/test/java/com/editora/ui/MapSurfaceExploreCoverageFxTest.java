package com.editora.ui;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.xml.parsers.DocumentBuilderFactory;

import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.ui.ProjectMapView.FlowDirection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The surface's explore-round paths on a live Map (Map explore positions 14 and 16): the SVG export in each of the
 * four flows (connectors leave the parent on the flow's side), with long names cut and "+N more" rows drawn without a
 * mark; and the accessible rows' Expand and Collapse on folders, a file's Structure column and a declaration with
 * members, with each row named for its states.
 */
@Tag("fx")
class MapSurfaceExploreCoverageFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final String APP = """
            package demo;

            public class App {
                public void refresh() {
                    helper();
                }

                void helper() {}
            }
            """;

    private record Harness(Stage stage, ProjectMapView view, MapSurface surface, Path project, List<String> said)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage.hide();
            });
        }
    }

    private Harness open(Path project, Set<Path> open, Set<Path> modified, boolean wholeFolders) throws Exception {
        List<String> said = new CopyOnWriteArrayList<>();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, open::contains, modified::contains);
            view.setOnStatus(said::add);
            if (!wholeFolders) {
                MapLoader loader = FxTestSupport.field(view, "mapLoader");
                loader.canvasWholeFolders = folder -> false; // as on a remote root: "+N more"
            }
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 1300, 800));
            stage.show();
            view.setRoot(project);
            return new Harness(stage, view, FxTestSupport.field(view, "surface"), project, said);
        });
        waitForFx(() -> !rows(h).isEmpty() && idle(h));
        return h;
    }

    private Path project() throws Exception {
        Path project = Files.createTempDirectory(temp, "project").toRealPath();
        Path src = Files.createDirectory(project.resolve("src"));
        Files.writeString(src.resolve("App.java"), APP);
        Files.writeString(src.resolve("notes.txt"), "n");
        Files.writeString(
                src.resolve("a-very-long-file-name-that-will-never-fit-in-its-row-on-the-map-whatever-the-zoom.txt"),
                "x");
        Files.writeString(project.resolve("README.md"), "# r");
        return project;
    }

    // --- 16: the SVG in every flow ---------------------------------------------------------------------------

    @Test
    void theSvgConnectorsLeaveTheParentOnTheFlowsSideInEveryFlow() throws Exception {
        Path project = project();
        Path many = Files.createDirectory(project.resolve("many"));
        for (int i = 0; i < 700; i++) {
            Files.writeString(many.resolve(String.format("m%03d.txt", i)), "");
        }
        Path app = project.resolve("src/App.java");
        try (Harness h = open(project, Set.of(), Set.of(), false)) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(app));
            waitForFx(() -> idle(h) && app.equals(selected(h)));
            FxTestSupport.runOnFx(() -> h.view().revealPath(many.resolve("m000.txt")));
            waitForFx(() -> idle(h) && rows(h).stream().anyMatch(ProjectMapModel.Entry::isPlaceholder));
            for (FlowDirection flow : FlowDirection.values()) {
                FxTestSupport.runOnFx(() -> FxTestSupport.call(
                        h.surface(), "setFlowDirection", new Class<?>[] {FlowDirection.class}, flow));
                settle(h);
                String svg = FxTestSupport.callOnFx(() -> h.surface().canvasSvg("Map of " + flow));
                assertNotNull(svg, flow.toString());
                Document doc = parse(svg);
                List<double[]> links = new ArrayList<>();
                for (Element path : elements(group(doc, "links"), "path")) {
                    String[] parts = path.getAttribute("d")
                            .replace("M", "")
                            .replace("C", "")
                            .trim()
                            .split("\\s+");
                    links.add(new double[] {
                        Double.parseDouble(parts[0]),
                        Double.parseDouble(parts[1]),
                        Double.parseDouble(parts[6]),
                        Double.parseDouble(parts[7])
                    });
                }
                assertFalse(links.isEmpty(), flow + ": connectors drawn");
                boolean vertical = flow == FlowDirection.TOP_TO_BOTTOM || flow == FlowDirection.BOTTOM_TO_TOP;
                boolean reverse = flow == FlowDirection.RIGHT_TO_LEFT || flow == FlowDirection.BOTTOM_TO_TOP;
                for (double[] link : links) {
                    double along = vertical ? link[3] - link[1] : link[2] - link[0];
                    assertTrue(reverse ? along < 0 : along > 0, flow + ": each link runs along the flow");
                }
                List<Element> columns = elements(group(doc, "columns"), "g");
                if (vertical) {
                    assertEquals(columns.size() - 1, links.size(), flow + ": one link per opened column");
                } else {
                    assertTrue(links.size() > columns.size(), flow + ": one link per row of an opened column");
                }

                // The long name is cut to fit its row, its whole name kept as the row's title.
                boolean cut = false;
                boolean placeholderWithoutMark = false;
                for (Element row : elements(group(doc, "rows"), "g")) {
                    String name = elements(row, "title").getFirst().getTextContent();
                    String shown = elements(row, "text").getFirst().getTextContent();
                    if (name.startsWith("a-very-long-file-name")) {
                        cut = !shown.equals(name) && shown.length() < name.length();
                    }
                    if (name.startsWith("+") && elements(row, "rect").size() == 1) {
                        placeholderWithoutMark = true;
                    }
                }
                assertTrue(cut, flow + ": the long name is cut");
                assertTrue(placeholderWithoutMark, flow + ": a \"+N more\" row has no kind mark");
            }
        }
    }

    // --- 14: Expand and Collapse from a screen reader ------------------------------------------------------------

    @Test
    void expandAndCollapseOpenAndCloseFoldersStructureColumnsAndDeclarations() throws Exception {
        Path project = project();
        Path src = project.resolve("src");
        Path app = src.resolve("App.java");
        Path notes = src.resolve("notes.txt");
        Path readme = project.resolve("README.md");
        try (Harness h = open(project, Set.of(readme), Set.of(readme), true)) {
            FxTestSupport.runOnFx(() -> {
                assertSame(
                        h.surface(),
                        h.surface().queryAccessibleAttribute(AccessibleAttribute.FOCUS_ITEM),
                        "nothing selected on screen: the surface itself has the focus");
                MapAccessibleRows tree = tree(h);
                MapAccessibleRows.Row readmeRow = tree.row(readme);
                assertNotNull(readmeRow);
                String name = (String) readmeRow.queryAccessibleAttribute(AccessibleAttribute.TEXT);
                assertTrue(name.startsWith(tr("project.map.accessible.row.fileOpen", "README.md", 2, 2)), name);
                assertTrue(name.contains(tr("project.map.tooltip.unsaved")), "an unsaved file says so: " + name);
                assertEquals(false, readmeRow.queryAccessibleAttribute(AccessibleAttribute.LEAF), "its headings");

                MapAccessibleRows.Row srcRow = tree.row(src);
                srcRow.executeAccessibleAction(AccessibleAction.COLLAPSE); // already closed: nothing
                assertFalse(expanded(h).contains(src));
                srcRow.executeAccessibleAction(AccessibleAction.EXPAND);
            });
            waitForFx(
                    () -> idle(h) && rows(h).stream().anyMatch(row -> row.path().equals(app)));
            settle(h);
            waitForFx(() -> tree(h).row(src) != null
                    && Boolean.TRUE.equals(tree(h).row(src).queryAccessibleAttribute(AccessibleAttribute.EXPANDED)));
            FxTestSupport.runOnFx(() -> {
                assertTrue(expanded(h).contains(src));
                MapAccessibleRows tree = tree(h);
                tree.row(src).executeAccessibleAction(AccessibleAction.EXPAND); // already open: nothing
                assertEquals(true, tree.row(src).queryAccessibleAttribute(AccessibleAttribute.EXPANDED));
                MapAccessibleRows.Row appRow = tree.row(app);
                assertEquals(false, appRow.queryAccessibleAttribute(AccessibleAttribute.LEAF), "it has a Structure");
                assertEquals(false, appRow.queryAccessibleAttribute(AccessibleAttribute.EXPANDED));
                tree.row(notes).executeAccessibleAction(AccessibleAction.EXPAND); // a text file opens nothing
                assertEquals(notes, selected(h), "but the reader's row is selected");
                FxTestSupport.call(
                        h.surface(),
                        "canvasExpand",
                        new Class<?>[] {Path.class, boolean.class},
                        src.resolve("x"),
                        true);
                appRow.executeAccessibleAction(AccessibleAction.EXPAND);
            });
            ProjectMapStructure structure = FxTestSupport.field(h.view(), "structure");
            waitForFx(() -> structure.isOpen(app)
                    && rows(h).stream()
                            .anyMatch(row -> row.isSymbol() && row.name().startsWith("App")));
            settle(h);
            Path type = FxTestSupport.callOnFx(() -> rows(h).stream()
                    .filter(row -> row.isSymbol() && row.name().startsWith("App"))
                    .findFirst()
                    .orElseThrow()
                    .path());
            FxTestSupport.runOnFx(() -> {
                MapAccessibleRows tree = tree(h);
                assertEquals(true, tree.row(app).queryAccessibleAttribute(AccessibleAttribute.EXPANDED));
                MapAccessibleRows.Row typeRow = tree.row(type);
                assertNotNull(typeRow, "the declaration is a row of its own");
                String name = (String) typeRow.queryAccessibleAttribute(AccessibleAttribute.TEXT);
                assertTrue(name.startsWith("App"), name);
                assertNotEquals(name, tr("project.map.accessible.row.file", "App", 1, 1));
                assertEquals(false, typeRow.queryAccessibleAttribute(AccessibleAttribute.LEAF), "it has members");
                typeRow.executeAccessibleAction(AccessibleAction.EXPAND);
            });
            waitForFx(() -> rows(h).stream()
                    .anyMatch(row -> row.isSymbol() && row.name().startsWith("helper")));
            settle(h);
            FxTestSupport.runOnFx(() -> {
                MapAccessibleRows tree = tree(h);
                assertEquals(true, tree.row(type).queryAccessibleAttribute(AccessibleAttribute.EXPANDED));
                Path helper = rows(h).stream()
                        .filter(row -> row.isSymbol() && row.name().startsWith("helper"))
                        .findFirst()
                        .orElseThrow()
                        .path();
                assertEquals(true, tree.row(helper).queryAccessibleAttribute(AccessibleAttribute.LEAF));
                tree.row(app).executeAccessibleAction(AccessibleAction.COLLAPSE);
            });
            waitForFx(() -> !structure.isOpen(app));
            settle(h);
            FxTestSupport.runOnFx(() -> {
                MapAccessibleRows tree = tree(h);
                assertNull(tree.row(type), "the Structure column closed with its rows");
                tree.row(src).executeAccessibleAction(AccessibleAction.COLLAPSE);
            });
            waitForFx(() -> idle(h) && !expanded(h).contains(src));
        }
    }

    // --- a row's tooltip --------------------------------------------------------------------------------------

    @Test
    void aRowsTooltipSaysWhatItIsWhereItIsAndEachOfItsStates() throws Exception {
        Path project = project();
        Path src = project.resolve("src");
        Path app = src.resolve("App.java");
        Path notes = src.resolve("notes.txt");
        Path link = Files.createSymbolicLink(project.resolve("link.txt"), project.resolve("README.md"));
        Path many = Files.createDirectory(project.resolve("many"));
        for (int i = 0; i < 320; i++) {
            Files.writeString(many.resolve(String.format("m%03d.txt", i)), "");
        }
        try (Harness h = open(project, Set.of(notes), Set.of(notes), false)) {
            FxTestSupport.runOnFx(() ->
                    h.surface().setMarkerSuppliers(notes::equals, path -> path.equals(notes) || path.equals(src)));
            FxTestSupport.runOnFx(() -> h.view().revealPath(notes));
            waitForFx(() -> idle(h) && notes.equals(selected(h)));
            FxTestSupport.runOnFx(() -> h.view().revealPath(many.resolve("m000.txt")));
            waitForFx(() -> idle(h) && rows(h).stream().anyMatch(ProjectMapModel.Entry::isPlaceholder));
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    h.surface(), "canvasExpand", new Class<?>[] {Path.class, boolean.class}, app, true));
            waitForFx(() -> rows(h).stream()
                    .anyMatch(row -> row.isSymbol() && row.name().startsWith("App")));
            settle(h);
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "snapshotStates");
                String file = tooltip(h, entry(h, notes));
                assertTrue(file.startsWith(notes.toString()), "a file: its full path first\n" + file);
                assertTrue(file.contains(tr("project.map.tooltip.type", tr("project.map.tooltip.file"))), file);
                assertTrue(
                        file.contains(tr(
                                "project.map.tooltip.status",
                                String.join(
                                        ", ",
                                        tr("project.map.tooltip.open"),
                                        tr("project.map.tooltip.unsaved"),
                                        tr("project.map.tooltip.bookmarked"),
                                        tr("project.map.tooltip.personalNotes")))),
                        "every state, in order\n" + file);

                String folder = tooltip(h, entry(h, src));
                assertTrue(folder.startsWith("src/"), "a folder: where it is in the project\n" + folder);
                assertTrue(folder.contains(tr("project.map.tooltip.type", tr("project.map.tooltip.folder"))));
                assertTrue(folder.contains(tr("project.map.tooltip.personalNotes")), "a folder carries notes too");
                assertFalse(folder.contains(tr("project.map.tooltip.unsaved")));

                String linked = tooltip(h, entry(h, link));
                assertTrue(linked.contains(tr("project.map.tooltip.type", tr("project.map.tooltip.symbolicLink"))));
                assertFalse(linked.contains(tr("project.map.tooltip.status", "")), "no state: no status line");

                ProjectMapModel.Entry more = rows(h).stream()
                        .filter(ProjectMapModel.Entry::isPlaceholder)
                        .findFirst()
                        .orElseThrow();
                assertEquals(more.name(), tooltip(h, more), "a \"+N more\" row says only what it is");

                ProjectMapModel.Entry type = rows(h).stream()
                        .filter(row -> row.isSymbol() && row.name().startsWith("App"))
                        .findFirst()
                        .orElseThrow();
                String declaration = tooltip(h, type);
                assertTrue(declaration.contains(type.name()), declaration);
                assertTrue(
                        declaration.contains(
                                tr("project.map.tooltip.type", type.symbol().kind())),
                        declaration);
                assertTrue(declaration.contains(app.toString()), "its file");
            });
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static Document parse(String svg) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Element> elements(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static Element group(Document doc, String className) {
        return elements(doc.getDocumentElement(), "g").stream()
                .filter(g -> className.equals(g.getAttribute("class")))
                .findFirst()
                .orElseThrow();
    }

    /** The accessible rows as of the map drawn now (rebuilt, so no paint has to come first). */
    private static MapAccessibleRows tree(Harness h) {
        FxTestSupport.invoke(h.surface(), "canvasA11yRebuild");
        return h.surface().canvasAccessibleRows();
    }

    private static String tooltip(Harness h, ProjectMapModel.Entry entry) {
        return (String)
                FxTestSupport.call(h.surface(), "tooltipText", new Class<?>[] {ProjectMapModel.Entry.class}, entry);
    }

    private static ProjectMapModel.Entry entry(Harness h, Path path) {
        return rows(h).stream()
                .filter(row -> row.path().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError(path + " is not on the map"));
    }

    private static List<ProjectMapModel.Entry> rows(Harness h) {
        return FxTestSupport.field(h.surface(), "entries");
    }

    private static Set<Path> expanded(Harness h) {
        MapLoader loader = FxTestSupport.field(h.view(), "mapLoader");
        return loader.expandedDirectories();
    }

    private static Path selected(Harness h) {
        return (Path) FxTestSupport.call(h.surface(), "selectedPath", new Class<?>[0]);
    }

    private static boolean idle(Harness h) {
        return !FxTestSupport.<Boolean>field(h.view(), "loadInFlight");
    }

    @SuppressWarnings("unused")
    private static Map<Path, ProjectMapModel.Entry> byPath(Harness h) {
        return FxTestSupport.field(h.surface(), "entryByPath");
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
