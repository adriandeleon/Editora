package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ctrl+Shift+arrows move the keyboard to the nearest card that way on the Project Map (Map code browser gap 13). */
@Tag("fx")
class MapCardSpatialKeysFxTest {

    @TempDir
    Path temp;

    private Path root;
    private final AsyncTestScope async = new AsyncTestScope();
    private Fixture map;
    private final List<String> statuses = new CopyOnWriteArrayList<>();

    MapCardSpatialKeysFxTest() throws Exception {}

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void close() throws Exception {
        if (map != null) {
            FxTestSupport.runOnFx(() -> {
                map.view().dispose();
                map.stage().close();
            });
        }
        async.close();
    }

    @Test
    void theNearestCardThatWayIsTheOneStraightAheadFirst() {
        double[] from = {100, 100, 200, 100};
        double[] ahead = {500, 120, 200, 100}; // right, overlapping across
        double[] nearerAside = {320, 400, 200, 100}; // right and nearer, but far below
        double[] behind = {0, 125, 50, 50}; // level with it: neither above nor below
        List<double[]> boxes = List.of(ahead, nearerAside, behind);
        assertEquals(0, MapCards.nearestToward(from, boxes, MapCards.Toward.RIGHT), "straight ahead beats aside");
        assertEquals(2, MapCards.nearestToward(from, boxes, MapCards.Toward.LEFT));
        assertEquals(1, MapCards.nearestToward(from, boxes, MapCards.Toward.DOWN));
        assertEquals(-1, MapCards.nearestToward(from, boxes, MapCards.Toward.UP), "nothing above");
        assertEquals(-1, MapCards.nearestToward(from, List.of(), MapCards.Toward.RIGHT));
        // Mostly below and a little to the right: below, not right.
        double[] belowRight = {120, 400, 200, 100};
        assertEquals(-1, MapCards.nearestToward(from, List.of(belowRight), MapCards.Toward.RIGHT));
        assertEquals(0, MapCards.nearestToward(from, List.of(belowRight), MapCards.Toward.DOWN));

        // Equal scores: the nearer centre wins.
        double[] up = {150, 0, 100, 50};
        double[] upFar = {100, -200, 200, 250};
        assertEquals(0, MapCards.nearestToward(from, List.of(up, upFar), MapCards.Toward.UP));
        assertEquals(1, MapCards.nearestToward(from, List.of(upFar, up), MapCards.Toward.UP));
        // Overlapping boxes count by their centres.
        assertEquals(
                0, MapCards.nearestToward(from, List.of(new double[] {250, 100, 200, 100}), MapCards.Toward.RIGHT));
    }

    @Test
    void aCardIsBroughtIntoViewOnlyAsFarAsItIsOutOfView() {
        assertEquals(0, MapCards.revealShift(100, 200, 1000, 14), 1e-9, "in view: stays");
        assertEquals(1000 - 14 - 1100, MapCards.revealShift(900, 200, 1000, 14), 1e-9, "past the far edge");
        assertEquals(14 + 50, MapCards.revealShift(-50, 200, 1000, 14), 1e-9, "before the near edge");
        assertEquals(14 - 300, MapCards.revealShift(300, 990, 1000, 14), 1e-9, "longer than the room: its start");
    }

    @Test
    void onlyCtrlShiftWithAnArrowMoves() {
        assertEquals(MapCards.Toward.RIGHT, MapCards.toward(key(KeyCode.RIGHT, true, true, false)));
        assertEquals(MapCards.Toward.LEFT, MapCards.toward(key(KeyCode.LEFT, true, true, false)));
        assertEquals(MapCards.Toward.UP, MapCards.toward(key(KeyCode.UP, true, true, false)));
        assertEquals(MapCards.Toward.DOWN, MapCards.toward(key(KeyCode.DOWN, true, true, false)));
        assertNull(MapCards.toward(key(KeyCode.RIGHT, true, false, false)), "Ctrl alone");
        assertNull(MapCards.toward(key(KeyCode.RIGHT, false, true, false)), "Shift alone");
        assertNull(MapCards.toward(key(KeyCode.RIGHT, true, true, true)), "with Alt");
        assertNull(MapCards.toward(key(KeyCode.A, true, true, false)), "not an arrow");
        assertNull(
                MapCards.toward(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.RIGHT, true, true, false, false)));
        assertNull(
                MapCards.toward(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, true, true, false, true)),
                "with Meta");
    }

    @Test
    void ctrlShiftArrowsMoveTheKeyboardBetweenCardsAndInFromTheMap() throws Exception {
        root = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path a = Files.writeString(root.resolve("A.java"), "class A {\n}\n");
        Path b = Files.writeString(root.resolve("B.java"), "class B {\n}\n");
        Path c = Files.writeString(root.resolve("C.java"), "class C {\n}\n");
        map = openMap();
        ProjectMapPreview cardA = openFileCard(a);
        ProjectMapPreview cardB = openFileCard(b);
        ProjectMapPreview cardC = openFileCard(c);
        FxTestSupport.runOnFx(() -> {
            // A at the left, B right of it, C below A.
            cardA.relocate(300, 60);
            cardB.relocate(800, 80);
            cardC.relocate(320, 500);
            for (ProjectMapPreview card : List.of(cardA, cardB, cardC)) {
                card.resize(380, 300);
            }
            cardA.focusContent();
        });
        await("A has the keyboard", () -> holds(cardA));
        double[] offset = offset();

        press(KeyCode.RIGHT);
        await("B has it", () -> holds(cardB));
        press(KeyCode.LEFT);
        await("back to A", () -> holds(cardA));
        press(KeyCode.DOWN);
        await("C, below A", () -> holds(cardC));
        FxTestSupport.runOnFx(() -> {
            assertEquals(offset[0], offset()[0], 1e-9, "every card in view: the map stays where it was");
            assertEquals(offset[1], offset()[1], 1e-9);
        });

        // Nothing below C: the status says so and the keyboard stays.
        press(KeyCode.DOWN);
        await("told", () -> statuses.contains(tr("project.map.status.noCardToward.down")));
        FxTestSupport.runOnFx(() -> assertTrue(holds(cardC)));

        // A card partly out of view is brought in, just enough.
        FxTestSupport.runOnFx(() -> cardB.relocate(map.view().getWidth() - 100, 80));
        press(KeyCode.UP);
        await("A again", () -> holds(cardA));
        press(KeyCode.RIGHT);
        await("B, brought into view", () -> holds(cardB));
        FxTestSupport.runOnFx(() -> {
            Region host = FxTestSupport.field(map.view(), "canvasHost");
            double[] drawn = MapCards.drawnBounds(cardB);
            assertTrue(drawn[0] + drawn[2] <= host.getWidth() + 0.5, "all of it in view");
            assertTrue(offset()[0] < offset[0], "the map moved left for it");
        });

        // From the map: the selected row's nearest card that way.
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, a);
            map.surface().requestFocus();
        });
        await("the map has the keyboard", () -> map.surface().isFocused());
        FxTestSupport.runOnFx(() -> {
            MapSurface.NodeBox row = map.surface().nodeBox(a);
            List<ProjectMapPreview> cards = List.of(cardA, cardB, cardC);
            int expected = MapCards.nearestToward(
                    new double[] {row.x(), row.y(), row.width(), row.height()},
                    cards.stream().map(MapCards::drawnBounds).toList(),
                    MapCards.Toward.RIGHT);
            assertTrue(expected >= 0, "the cards stand right of the map's column");
            Event.fireEvent(map.surface(), key(KeyCode.RIGHT, true, true, false));
            assertTrue(holds(cards.get(expected)), "the nearest card right of A's row has the keyboard");
        });

        // A node outside the cards and the map: not taken.
        FxTestSupport.runOnFx(() -> {
            MapCards cards = FxTestSupport.field(map.view(), "cards");
            assertFalse(cards.focusToward(new Label(), MapCards.Toward.RIGHT, null));
            // With no row selected, the map starts from its middle.
            FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, (Path) null);
            map.surface().requestFocus();
            Event.fireEvent(map.surface(), key(KeyCode.LEFT, true, true, false));
        });
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private void press(KeyCode code) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Node owner = map.scene().getFocusOwner();
            Event.fireEvent(owner == null ? map.view() : owner, key(code, true, true, false));
        });
    }

    private boolean holds(Region card) {
        for (Node node = map.scene().getFocusOwner(); node != null; node = node.getParent()) {
            if (node == card) {
                return true;
            }
        }
        return false;
    }

    private double[] offset() {
        return new double[] {
            FxTestSupport.<Double>field(map.surface(), "offsetX"), FxTestSupport.<Double>field(map.surface(), "offsetY")
        };
    }

    private static KeyEvent key(KeyCode code, boolean control, boolean shift, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, false);
    }

    private Fixture openMap() throws Exception {
        Path project = root;
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false, path -> null);
            view.setOnStatus(statuses::add);
            Stage stage = new Stage();
            Scene scene = new Scene(view, 1400, 900);
            scene.getStylesheets()
                    .add(MapCardSpatialKeysFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage.setScene(scene);
            stage.show();
            view.applyCss();
            view.layout();
            view.setRememberedFlow(ProjectMapView.FlowDirection.LEFT_TO_RIGHT.name(), ignored -> {});
            view.setRememberedNavigation(true, false, (keep, focus) -> {});
            view.setRoot(project);
            return new Fixture(view, scene, stage, FxTestSupport.field(view, "surface"));
        });
    }

    private ProjectMapPreview openFileCard(Path file) throws Exception {
        await("row " + file.getFileName(), () -> rows().contains(file));
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(map.surface(), "setSelected", new Class<?>[] {Path.class}, file);
            FxTestSupport.call(map.view(), "previewSelection", new Class<?>[] {Path.class}, file);
        });
        await(
                "card " + file.getFileName(),
                () -> previews().containsKey(file)
                        && !previews().get(file).editor().getText().isEmpty());
        return FxTestSupport.callOnFx(() -> previews().get(file));
    }

    private List<Path> rows() {
        List<?> boxes = FxTestSupport.field(map.surface(), "boxes");
        return boxes.stream()
                .map(box -> ((ProjectMapModel.Entry) FxTestSupport.call(box, "entry", new Class<?>[0])).path())
                .toList();
    }

    private Map<Path, ProjectMapPreview> previews() {
        return FxTestSupport.field(map.view(), "previews");
    }

    private void await(String what, BooleanSupplier condition) throws Exception {
        SaveGuardsFxTest.awaitOnFx(async, what, condition);
    }

    private record Fixture(ProjectMapView view, Scene scene, Stage stage, MapSurface surface) {}
}
