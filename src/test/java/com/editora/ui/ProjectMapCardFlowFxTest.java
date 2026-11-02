package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map card flow: a new card opens past its row's column the way the map flows — right of it, left of it, below or above
 * — as the row's next column would, and the map pans back to make room there; a declaration card opened from a file
 * card's Structure column opens past that column, away from the card, level with its row.
 */
@Tag("fx")
class ProjectMapCardFlowFxTest {

    private static final String APP = String.join(
            "\n",
            "package demo;",
            "",
            "public class App {",
            "    private int count;",
            "",
            "    int add(int a, int b) {",
            "        return a + b;",
            "    }",
            "",
            "    void reset() {",
            "        count = 0;",
            "    }",
            "",
            "    int sub(int a, int b) {",
            "        return a - b;",
            "    }",
            "}",
            "");

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aSpotCostsNothingPastTheColumnTheWayTheMapFlowsAndMostBehindIt() {
        double[] column = {400, 100, 200, 300}; // x 400–600, y 100–400
        double[] right = {1, 0};
        assertEquals(0, MapCards.cardFlowCost(610, 150, 300, 200, column, right), "right of it, flowing right");
        assertEquals(
                MapCards.FLOW_BEHIND, MapCards.cardFlowCost(50, 150, 300, 200, column, right), "left of it: behind");
        assertEquals(
                MapCards.FLOW_BESIDE, MapCards.cardFlowCost(450, 420, 300, 200, column, right), "below it: beside");
        double[] left = {-1, 0};
        assertEquals(0, MapCards.cardFlowCost(50, 150, 340, 200, column, left), "flowing left, left of it");
        assertEquals(MapCards.FLOW_BEHIND, MapCards.cardFlowCost(610, 150, 300, 200, column, left));
        double[] down = {0, 1};
        assertEquals(0, MapCards.cardFlowCost(300, 410, 300, 200, column, down), "flowing down, below it");
        assertEquals(MapCards.FLOW_BEHIND, MapCards.cardFlowCost(300, 0, 300, 90, column, down));
        double[] up = {0, -1};
        assertEquals(0, MapCards.cardFlowCost(300, 0, 300, 95, column, up), "flowing up, above it");
        assertEquals(MapCards.FLOW_BESIDE, MapCards.cardFlowCost(620, 150, 300, 200, column, up), "beside it");
        assertEquals(0, MapCards.cardFlowCost(0, 0, 10, 10, column, null), "a linked card has no way");
    }

    @ParameterizedTest
    @EnumSource(ProjectMapView.FlowDirection.class)
    void aDeclarationsCardOpensPastItsRowsColumnTheWayTheMapFlows(ProjectMapView.FlowDirection flow) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Path app = Files.writeString(root.resolve("App.java"), APP);
        Fixture map = openMap(root, flow);
        try {
            waitFor(() -> nodeBox(map, app) != null, "App.java's row");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    map.view(), "toggleStructure", new Class<?>[] {ProjectMapModel.Entry.class}, entry(map, app)));
            Path add = symbolRow(map, "add");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    map.view(), "togglePreview", new Class<?>[] {ProjectMapModel.Entry.class}, entry(map, add)));
            ProjectMapPreview card = waitForCard(map, add);
            FxTestSupport.runOnFx(() -> {
                MapCards.Site site =
                        (MapCards.Site) FxTestSupport.call(map.surface(), "cardSite", new Class<?>[] {Path.class}, add);
                assertNotNull(site.row(), "the declaration's row is on the map");
                assertPast(flow, MapCards.drawnBounds(card), site.column(), site.row());
            });
        } finally {
            FxTestSupport.runOnFx(map.view()::dispose);
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = ProjectMapView.FlowDirection.class,
            names = {"LEFT_TO_RIGHT", "TOP_TO_BOTTOM"})
    void withNoRoomPastTheColumnTheMapPansBackForTheCard(ProjectMapView.FlowDirection flow) throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Path app = Files.writeString(root.resolve("App.java"), APP);
        Fixture map = openMap(root, flow);
        try {
            waitFor(() -> nodeBox(map, app) != null, "App.java's row");
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    map.view(), "toggleStructure", new Class<?>[] {ProjectMapModel.Entry.class}, entry(map, app)));
            Path add = symbolRow(map, "add");
            // The declaration's column against the panel's far edge: no room past it for a card.
            double[] before = FxTestSupport.callOnFx(() -> {
                MapCards.Site site =
                        (MapCards.Site) FxTestSupport.call(map.surface(), "cardSite", new Class<?>[] {Path.class}, add);
                double[] column = site.column();
                boolean across = flow == ProjectMapView.FlowDirection.LEFT_TO_RIGHT;
                double edge = across ? map.surface().getWidth() : map.surface().getHeight();
                double end = across ? column[0] + column[2] : column[1] + column[3];
                double shift = edge - 20 - end;
                FxTestSupport.call(
                        map.surface(),
                        "panBy",
                        new Class<?>[] {double.class, double.class},
                        across ? shift : 0,
                        across ? 0 : shift);
                return offset(map);
            });
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    map.view(), "togglePreview", new Class<?>[] {ProjectMapModel.Entry.class}, entry(map, add)));
            ProjectMapPreview card = waitForCard(map, add);
            FxTestSupport.runOnFx(() -> {
                assertNotEquals(before[0] + before[1], offset(map)[0] + offset(map)[1], "the map panned back");
                MapCards.Site site =
                        (MapCards.Site) FxTestSupport.call(map.surface(), "cardSite", new Class<?>[] {Path.class}, add);
                double[] column = site.column();
                assertTrue(column[0] >= 0 && column[1] >= 0, "the row's column stays on the panel");
                assertPast(flow, MapCards.drawnBounds(card), column, site.row());
            });
        } finally {
            FxTestSupport.runOnFx(map.view()::dispose);
        }
    }

    @Test
    void aDeclarationCardFromAFileCardsStructureColumnOpensPastTheColumnLevelWithItsRow() throws Exception {
        Path root = Files.createDirectories(temp.resolve("project"));
        Path app = Files.writeString(root.resolve("App.java"), APP);
        Fixture map = openMap(root, ProjectMapView.FlowDirection.LEFT_TO_RIGHT);
        try {
            waitFor(() -> nodeBox(map, app) != null, "App.java's row");
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, app);
                FxTestSupport.call(map.view(), "previewSelection", new Class<?>[] {Path.class}, app);
            });
            ProjectMapPreview file = waitForCard(map, app);
            waitFor(() -> file.editor().getText().contains("return a + b;"), "the file card");
            FxTestSupport.runOnFx(() -> file.setStructureShown(true));
            waitFor(
                    () -> structureOf(map, file) != null
                            && structureOf(map, file).visibleRows().size() > 3,
                    "rows");
            ProjectMapCardStructure column = FxTestSupport.callOnFx(() -> structureOf(map, file));
            FxTestSupport.runOnFx(() -> {
                var tree = column.tree();
                for (int i = 0; i < tree.getExpandedItemCount(); i++) {
                    ProjectMapStructure.Node node =
                            tree.getTreeItem(i).getValue().node();
                    if (node != null && node.symbol().label().startsWith("sub")) {
                        tree.getSelectionModel().select(i);
                    }
                }
                tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, true, false, false, false));
            });
            Path sub = FxTestSupport.callOnFx(() -> column.visibleRows().stream()
                    .map(ProjectMapCardStructure.Row::node)
                    .filter(node -> node != null && node.symbol().label().startsWith("sub"))
                    .findFirst()
                    .orElseThrow()
                    .path());
            ProjectMapPreview declaration = waitForCard(map, sub);
            FxTestSupport.runOnFx(() -> {
                double[] drawn = column.bounds();
                double[] row = column.rowBounds(sub);
                assertNotNull(drawn);
                assertNotNull(row);
                double[] card = MapCards.drawnBounds(declaration);
                boolean right = column.side() == CardAttachment.Side.RIGHT;
                assertTrue(
                        right ? card[0] >= drawn[0] + drawn[2] - 1 : card[0] + card[2] <= drawn[0] + 1,
                        "past the column, away from its card: card " + List.of(card[0], card[2]) + " column "
                                + List.of(drawn[0], drawn[2]) + " on the " + column.side());
                assertTrue(
                        card[1] <= row[1] + row[3] && card[1] + card[3] >= row[1],
                        "level with its row: card " + List.of(card[1], card[3]) + " row " + List.of(row[1], row[3]));
                double[] owner = MapCards.drawnBounds(file);
                assertTrue(owner[0] >= 0, "the file card stays on the panel");
            });
        } finally {
            FxTestSupport.runOnFx(map.view()::dispose);
        }
    }

    // --- helpers ---

    private record Fixture(ProjectMapView view, Scene scene, Region surface) {}

    private static Fixture openMap(Path root, ProjectMapView.FlowDirection flow) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(ProjectMapCardFlowFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            view.resize(1400, 900);
            view.applyCss();
            view.layout();
            view.setRememberedFlow(flow.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {});
            view.setRoot(root);
            return new Fixture(view, scene, FxTestSupport.field(view, "surface"));
        });
    }

    /** Asserts the card stands past the column the way {@code flow} goes, overlapping the row across the flow. */
    private static void assertPast(ProjectMapView.FlowDirection flow, double[] card, double[] column, double[] row) {
        String where = "card " + List.of(card[0], card[1], card[2], card[3]) + " column "
                + List.of(column[0], column[1], column[2], column[3]) + " in " + flow;
        switch (flow) {
            case LEFT_TO_RIGHT -> assertTrue(card[0] >= column[0] + column[2] - 1, where);
            case RIGHT_TO_LEFT -> assertTrue(card[0] + card[2] <= column[0] + 1, where);
            case TOP_TO_BOTTOM -> assertTrue(card[1] >= column[1] + column[3] - 1, where);
            case BOTTOM_TO_TOP -> assertTrue(card[1] + card[3] <= column[1] + 1, where);
        }
        boolean across = flow == ProjectMapView.FlowDirection.LEFT_TO_RIGHT
                || flow == ProjectMapView.FlowDirection.RIGHT_TO_LEFT;
        int a = across ? 1 : 0;
        assertTrue(card[a] <= row[a] + row[a + 2] && card[a] + card[a + 2] >= row[a], "level with its row: " + where);
    }

    private static double[] offset(Fixture map) {
        double x = FxTestSupport.field(map.surface(), "offsetX");
        double y = FxTestSupport.field(map.surface(), "offsetY");
        return new double[] {x, y};
    }

    private static Object nodeBox(Fixture map, Path path) {
        try {
            return FxTestSupport.callOnFx(
                    () -> FxTestSupport.call(map.surface(), "nodeBox", new Class<?>[] {Path.class}, path));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ProjectMapModel.Entry entry(Fixture map, Path path) {
        Object box = FxTestSupport.call(map.surface(), "nodeBox", new Class<?>[] {Path.class}, path);
        return (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
    }

    /** The path of the Map Structure row whose label starts with {@code name}, once it is on the map. */
    private static Path symbolRow(Fixture map, String name) throws Exception {
        Path[] found = new Path[1];
        waitFor(
                () -> {
                    try {
                        return FxTestSupport.callOnFx(() -> {
                            for (Object box : FxTestSupport.<List<?>>field(map.surface(), "boxes")) {
                                ProjectMapModel.Entry e =
                                        (ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0]);
                                if (e.symbol() != null && e.symbol().label().startsWith(name)) {
                                    found[0] = e.path();
                                    return true;
                                }
                            }
                            return false;
                        });
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                name + "'s Structure row");
        return found[0];
    }

    private static ProjectMapCardStructure structureOf(Fixture map, ProjectMapPreview card) {
        Map<ProjectMapPreview, ProjectMapCardStructure> columns = FxTestSupport.field(map.view(), "cardStructures");
        return columns.get(card);
    }

    private static ProjectMapPreview waitForCard(Fixture map, Path key) throws Exception {
        ProjectMapPreview[] card = new ProjectMapPreview[1];
        waitFor(
                () -> {
                    try {
                        return FxTestSupport.callOnFx(() -> {
                            Map<Path, ProjectMapPreview> previews = FxTestSupport.field(map.view(), "previews");
                            card[0] = previews.get(key);
                            return card[0] != null && card[0].getWidth() > 0;
                        });
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                "the card for " + key.getFileName());
        FxTestSupport.drainFx();
        return card[0];
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timed out waiting for " + what);
    }
}
