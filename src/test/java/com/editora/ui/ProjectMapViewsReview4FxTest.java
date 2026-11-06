package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.MapTrails;
import com.editora.config.ProjectMapState;
import com.editora.ui.ProjectMapView.FlowDirection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project Map review 4, the views group: a working set's (and a restart's) zoom and pan survive the reveal of its
 * selection (A1); a trail never leaves behind cards it opened for stops it already left (A2), plays and edits a
 * hand-named file (A3), steps from the keyboard and closes on Escape before the cards (A4); its editor never writes a
 * stale copy over a trail changed on disk (A5); a card the reader pinned outlives its stop (A6); the bar follows its
 * stop's card closing (A7); a note keeps off the map's columns (A8); a stop past its file's end says so (A9); and a
 * trail this build would cut is read-only (A10).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMapViewsReview4FxTest {

    @TempDir
    Path temp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Harness(Stage stage, ProjectMapView view, Region surface, Path project, List<String> said)
            implements AutoCloseable {

        Path file(int index) {
            return project.resolve("a").resolve("f" + index + ".txt");
        }

        MapCards cards() {
            return view.cards;
        }

        ProjectMapTrails trails() {
            return view.trails;
        }

        /** The code cards open now, by their keys. */
        List<Path> codeCards() {
            return cards().openCards().stream()
                    .filter(card -> card.node() instanceof ProjectMapPreview)
                    .map(MapCards.OpenCard::key)
                    .toList();
        }

        @Override
        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage.hide();
            });
        }
    }

    /** {@code a/f0..f9.txt} of three lines each, {@code README.md}, and a ten-folder chain down to {@code deep.txt}. */
    private Path project() throws Exception {
        Path project = Files.createTempDirectory(temp, "project").toRealPath();
        Path a = Files.createDirectory(project.resolve("a"));
        for (int i = 0; i < 10; i++) {
            Files.writeString(a.resolve("f" + i + ".txt"), "file " + i + "\nsecond line\nthird line\n");
        }
        Files.writeString(project.resolve("README.md"), "hello");
        Path deep = project;
        for (int i = 0; i < 10; i++) {
            deep = Files.createDirectory(deep.resolve("level-" + i));
            for (int j = 0; j < 6; j++) {
                Files.writeString(deep.resolve("sibling-" + j + ".txt"), "s");
            }
        }
        Files.writeString(deep.resolve("deep.txt"), "the bottom");
        return project;
    }

    private Path deep(Path project) {
        Path deep = project;
        for (int i = 0; i < 10; i++) {
            deep = deep.resolve("level-" + i);
        }
        return deep.resolve("deep.txt");
    }

    /** A Map over {@code project} in a {@code width × height} window; {@code settle}: the first load is waited for. */
    private Harness open(Path project, boolean settle, double width, double height) throws Exception {
        List<String> said = new CopyOnWriteArrayList<>();
        Queue<String> answers = new ArrayDeque<>();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            view.setOnStatus(said::add);
            view.setViewsPrompt((title, label, initial, onAccept) -> {
                String answer = answers.poll();
                if (answer != null) {
                    onAccept.accept(answer);
                }
            });
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), width, height));
            stage.show();
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.call(
                    surface, "setFlowDirection", new Class<?>[] {FlowDirection.class}, FlowDirection.LEFT_TO_RIGHT);
            view.setRoot(project);
            return new Harness(stage, view, surface, project, said);
        });
        if (settle) {
            waitForFx(() -> contains(h.surface(), project.resolve("README.md")));
            FxTestSupport.runOnFx(() -> h.view().revealPath(h.file(0)));
            waitForFx(() -> contains(h.surface(), h.file(0)));
            FxTestSupport.drainFx();
        }
        return h;
    }

    private Harness open(Path project, boolean settle) throws Exception {
        return open(project, settle, 1100, 780);
    }

    private static MapTrails.Stop stop(String file, int from, int to, String note) {
        return new MapTrails.Stop(file, "", from, to, "", note);
    }

    // --- A1 -----------------------------------------------------------------------------------------------------

    @Test
    void aWorkingSetsZoomAndPanSurviveTheRevealOfItsSelectionOnATreeTooDeepToFit() throws Exception {
        Path project = project();
        Path deep = deep(project);
        List<String> chain = new ArrayList<>();
        Path folder = project;
        for (int i = 0; i < 10; i++) {
            folder = folder.resolve("level-" + i);
            chain.add(project.relativize(folder).toString().replace('\\', '/'));
        }
        try (Harness h = open(project, true, 700, 480)) {
            ProjectMapState set = new ProjectMapState();
            set.setRoot(project.toString());
            set.setExpanded(chain);
            set.setSelected(project.relativize(deep).toString().replace('\\', '/'));
            set.setZoom(0.95);
            set.setOffsetX(-1234.5);
            set.setOffsetY(-210.25);
            FxTestSupport.runOnFx(() -> h.view().viewsApply(set));
            waitForFx(() -> deep.equals(selected(h.surface())) && ((MapSurface) h.surface()).viewsLaidOut());
            FxTestSupport.drainFx();
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertEquals(0.95, (double) FxTestSupport.field(h.surface(), "zoom"), 1e-6, "the set's zoom");
                assertEquals(-1234.5, (double) FxTestSupport.field(h.surface(), "offsetX"), 1e-6, "the set's pan");
                assertEquals(-210.25, (double) FxTestSupport.field(h.surface(), "offsetY"), 1e-6);
            });

            // A selection asked for later is revealed as a place again (zoomed out to show its path).
            FxTestSupport.runOnFx(() -> h.view().revealPath(deep.resolveSibling("sibling-0.txt")));
            waitForFx(() -> deep.resolveSibling("sibling-0.txt").equals(selected(h.surface())));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> assertTrue(
                    (double) FxTestSupport.field(h.surface(), "zoom") < 0.95 - 1e-6,
                    "a reveal still makes room for the path to its row"));
        }

        // A restart: the remembered zoom and pan come back the same way.
        ProjectMapState remembered = new ProjectMapState();
        remembered.setRoot(project.toString());
        remembered.setExpanded(chain);
        remembered.setSelected(project.relativize(deep).toString().replace('\\', '/'));
        remembered.setZoom(0.91);
        remembered.setOffsetX(-987.0);
        remembered.setOffsetY(-123.0);
        Harness restarted = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 700, 480));
            stage.show();
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.call(
                    surface, "setFlowDirection", new Class<?>[] {FlowDirection.class}, FlowDirection.LEFT_TO_RIGHT);
            view.setShowing(false);
            view.setRememberedPlace(remembered, state -> {});
            view.setRoot(project);
            view.setShowing(true);
            return new Harness(stage, view, surface, project, new CopyOnWriteArrayList<>());
        });
        try (Harness h = restarted) {
            waitForFx(() -> deep.equals(selected(h.surface())) && ((MapSurface) h.surface()).viewsLaidOut());
            FxTestSupport.drainFx();
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertEquals(0.91, (double) FxTestSupport.field(h.surface(), "zoom"), 1e-6, "the last session's zoom");
                assertEquals(-987.0, (double) FxTestSupport.field(h.surface(), "offsetX"), 1e-6);
                assertEquals(-123.0, (double) FxTestSupport.field(h.surface(), "offsetY"), 1e-6);
            });
        }
    }

    // --- A2 -----------------------------------------------------------------------------------------------------

    @Test
    void steppingOnOrClosingBeforeAStopsCardLandsLeavesNoCardTheTrailOpened() throws Exception {
        Path project = project();
        MapTrails.save(
                project,
                "Quick",
                new MapTrails.Trail(
                        "Quick",
                        List.of(
                                stop("a/f1.txt", 1, 2, "one"),
                                stop("a/f2.txt", 1, 2, "two"),
                                stop("a/f3.txt", 1, 2, "three"))));
        try (Harness h = open(project, false)) {
            FxTestSupport.runOnFx(() -> {
                assertTrue(h.trails().play("Quick", 0));
                h.trails().step(1);
                h.trails().step(1); // all before the Map's first load has landed: stops 1 and 2 still wait
            });
            waitForFx(() -> h.trails().stopCard() != null);
            FxTestSupport.drainFx();
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertEquals(List.of(h.file(3)), h.codeCards(), "only the shown stop's card");
                assertEquals(2, h.trails().stopShown());
            });
        }
        try (Harness h = open(project, true)) {
            FxTestSupport.runOnFx(() -> {
                assertTrue(h.trails().play("Quick", 0));
                h.trails().close(); // at once: the card was asked for on the next pulse
            });
            FxTestSupport.drainFx();
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertNull(h.trails().playingName());
                assertEquals(List.of(), h.codeCards(), "no card turns up after the trail closed");
            });
        }
    }

    // --- A3 -----------------------------------------------------------------------------------------------------

    @Test
    void aHandNamedTrailFilePlaysAndTakesStopsInItsOwnFile() throws Exception {
        Path project = project();
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        Path hand = Files.writeString(
                folder.resolve("Kubelet tour.json"),
                "{\"title\": \"Kubelet tour\", \"stops\": [{\"file\": \"a/f1.txt\", \"startLine\": 1}]}");
        try (Harness h = open(project, true)) {
            FxTestSupport.runOnFx(() -> {
                h.trails().fill();
                MenuItem play = h.trails().menu().getItems().stream()
                        .filter(item ->
                                tr("project.map.trails.play", "Kubelet tour").equals(item.getText()))
                        .findFirst()
                        .orElseThrow();
                play.fire();
                assertEquals("Kubelet tour", h.trails().playingName(), "it plays");
                h.trails().edit("Kubelet tour");
                assertNotNull(h.trails().editor(), "and opens in the editor");
                assertTrue(h.trails().append("Kubelet tour", stop("README.md", 1, 1, "")));
            });
            assertEquals(2, MapTrails.read(hand).trail().stops().size(), "the stop went into the hand-named file");
            assertFalse(Files.exists(folder.resolve("Kubelet-tour.json")), "no second file");
        }
    }

    // --- A4 -----------------------------------------------------------------------------------------------------

    @Test
    void theKeyboardStepsAPlayingTrailAndEscapeClosesItBeforeTheCards() throws Exception {
        Path project = project();
        MapTrails.save(
                project,
                "Keys",
                new MapTrails.Trail(
                        "Keys",
                        List.of(
                                stop("a/f1.txt", 1, 2, "one"),
                                stop("a/f2.txt", 1, 2, "two"),
                                stop("a/f3.txt", 1, 2, ""))));
        try (Harness h = open(project, true)) {
            FxTestSupport.runOnFx(() -> assertTrue(h.trails().play("Keys", 0)));
            waitForFx(() -> h.trails().stopCard() != null);
            FxTestSupport.runOnFx(() -> {
                Event.fireEvent(h.surface(), key(KeyCode.RIGHT, true, false));
                assertEquals(1, h.trails().stopShown(), "Shortcut+→ on the map: the next stop");
                Event.fireEvent(h.surface(), key(KeyCode.RIGHT, false, false));
                assertEquals(1, h.trails().stopShown(), "a plain arrow still moves on the map only");
                Event.fireEvent(h.trails().bar(), key(KeyCode.LEFT, true, false));
                assertEquals(0, h.trails().stopShown(), "Shortcut+← from the bar: the previous one");
                assertTrue(h.trails().command(1), "the command steps it too");
                assertEquals(1, h.trails().stopShown());
            });
            waitForFx(() -> h.trails().stopCard() != null
                    && h.trails().stopCard().editor().getLength() > 0);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = h.trails().stopCard();
                Event.fireEvent(card.editor(), key(KeyCode.RIGHT, true, false));
                assertEquals(1, h.trails().stopShown(), "in a card's text the keys move by words");

                Event.fireEvent(h.surface(), key(KeyCode.ESCAPE, false, false));
                assertNull(h.trails().playingName(), "Escape closes the trail first");
                assertFalse(h.view()
                        .viewsCanvasHost()
                        .getChildren()
                        .contains(h.trails().bar()));
                assertEquals(List.of(h.file(2)), h.codeCards(), "and leaves the cards");
                Event.fireEvent(h.surface(), key(KeyCode.ESCAPE, false, false));
                assertEquals(List.of(), h.codeCards(), "the next Escape closes them");
                assertFalse(h.trails().command(1), "nothing plays: the command says so");
            });
        }
    }

    @Test
    void theTrailCommandsStepAndCloseTheMapsTrailAndSaySoWhenNonePlays() throws Exception {
        Path project = project();
        MapTrails.save(
                project,
                "Cmd",
                new MapTrails.Trail("Cmd", List.of(stop("a/f1.txt", 1, 1, ""), stop("a/f2.txt", 1, 1, ""))));
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            com.editora.command.CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(
                    () -> registry.get("projectMap.trailNext").orElseThrow().run());
            FxTestSupport.drainFx();
            StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
            assertTrue(FxTestSupport.callOnFx(
                    () -> FxTestSupport.<MessageLog>field(status, "messageLog").entries().stream()
                            .anyMatch(e -> tr("status.projectMap.noTrail").equals(e.text()))));

            ProjectPanel panel = FxTestSupport.field(fx.controller, "projectPanel");
            FxTestSupport.runOnFx(() -> {
                panel.setRoot(project);
                panel.openMapWindow(false);
            });
            ProjectMapView view = FxTestSupport.field(panel, "mapView");
            waitForFx(() -> panel.isMapShowing() && view.trails.play("Cmd", 0));
            FxTestSupport.runOnFx(() -> {
                registry.get("projectMap.trailNext").orElseThrow().run();
                assertEquals(1, view.trails.stopShown());
                registry.get("projectMap.trailPrevious").orElseThrow().run();
                assertEquals(0, view.trails.stopShown());
                registry.get("projectMap.trailClose").orElseThrow().run();
                assertNull(view.trails.playingName());
            });
        } finally {
            fx.dispose();
        }
    }

    @Test
    void shortcutArrowsAreTheTrailKeysOnlyWithoutAltOrShift() {
        assertEquals(1, ProjectMapTrails.keyStep(key(KeyCode.RIGHT, true, false)));
        assertEquals(-1, ProjectMapTrails.keyStep(key(KeyCode.LEFT, true, false)));
        assertEquals(0, ProjectMapTrails.keyStep(key(KeyCode.RIGHT, true, true)), "Shortcut+Shift");
        assertEquals(0, ProjectMapTrails.keyStep(key(KeyCode.UP, true, false)));
        assertEquals(0, ProjectMapTrails.keyStep(key(KeyCode.RIGHT, false, false)));
        KeyEvent alt = new KeyEvent(
                KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, false, !mac(), true, mac()); // Alt: history, not trails
        assertEquals(0, ProjectMapTrails.keyStep(alt));
    }

    // --- A5 -----------------------------------------------------------------------------------------------------

    @Test
    void theEditorRefusesToWriteOverATrailChangedOnDiskAndShowsItAsItIs() throws Exception {
        Path project = project();
        List<MapTrails.Stop> two = List.of(stop("a/f1.txt", 1, 1, ""), stop("a/f2.txt", 1, 1, ""));
        Path file = MapTrails.save(project, "Shared", new MapTrails.Trail("Shared", two));
        try (Harness h = open(project, true)) {
            FxTestSupport.runOnFx(() -> h.trails().edit("Shared"));
            List<MapTrails.Stop> pulled = new ArrayList<>(two);
            pulled.add(new MapTrails.Stop("a/f3.txt", "", 1, 1, "teammate's stop", ""));
            MapTrails.save(project, "Shared", new MapTrails.Trail("Shared", pulled)); // a git pull, meanwhile
            FxTestSupport.runOnFx(() -> {
                ProjectMapTrailEditor editor = h.trails().editor();
                editor.list().getSelectionModel().select(0);
                editor.move(1);
                assertEquals(pulled, MapTrails.read(file).trail().stops(), "the pulled stop is not lost");
                assertEquals(
                        tr("project.map.status.trail.changedOnDisk", "Shared"),
                        h.said().getLast());
                assertEquals(pulled, editor.shownStops(), "the editor shows the file as it is now");
                editor.list().getSelectionModel().select(0);
                editor.move(1); // now from what is on disk: written
                assertEquals(
                        List.of("a/f2.txt", "a/f1.txt", "a/f3.txt"),
                        MapTrails.read(file).trail().stops().stream()
                                .map(MapTrails.Stop::file)
                                .toList());
            });
        }
    }

    // --- A6 and A7 ----------------------------------------------------------------------------------------------

    @Test
    void aPinnedStopCardStaysAndTheBarFollowsItsStopCardClosing() throws Exception {
        Path project = project();
        MapTrails.save(
                project,
                "Pins",
                new MapTrails.Trail("Pins", List.of(stop("a/f1.txt", 1, 2, "one"), stop("a/f2.txt", 1, 2, "two"))));
        try (Harness h = open(project, true)) {
            FxTestSupport.runOnFx(() -> assertTrue(h.trails().play("Pins", 0)));
            waitForFx(() -> h.trails().stopCard() != null);
            FxTestSupport.runOnFx(() -> {
                h.trails().stopCard().setPinned(true); // what its pin button does
                assertTrue(h.cards().pinned(h.trails().stopCard()));
                h.trails().step(1);
            });
            waitForFx(() -> h.trails().stopCard() != null
                    && h.file(2).equals(h.trails().stopCard().path()));
            FxTestSupport.runOnFx(() -> {
                assertTrue(h.codeCards().contains(h.file(1)), "the card pinned on stop 1 stays");

                ProjectMapPreview card = h.trails().stopCard();
                h.cards().close(card); // its own ×
                assertTrue(h.trails().stopCardGone(), "the bar knows its stop's card went");
                Label title = (Label) h.trails().bar().getChildren().get(3);
                assertEquals(tr("project.map.trails.bar.cardClosed", "f2.txt"), title.getText());
                Event.fireEvent(
                        title,
                        new MouseEvent(
                                MouseEvent.MOUSE_CLICKED,
                                1,
                                1,
                                1,
                                1,
                                MouseButton.PRIMARY,
                                1,
                                false,
                                false,
                                false,
                                false,
                                true,
                                false,
                                false,
                                true,
                                false,
                                false,
                                null));
            });
            waitForFx(() -> h.trails().stopCard() != null
                    && h.file(2).equals(h.trails().stopCard().path()));
            FxTestSupport.runOnFx(() -> {
                assertFalse(h.trails().stopCardGone(), "the title brought the stop back");
                ProjectMapState set = new ProjectMapState();
                set.setRoot(project.toString());
                set.setExpanded(List.of("a"));
                set.setSelected("a/f0.txt");
                h.view().viewsApply(set); // a working set: every card closes, and the trail with them
                assertNull(h.trails().playingName());
                assertFalse(h.view()
                        .viewsCanvasHost()
                        .getChildren()
                        .contains(h.trails().bar()));
            });
        }
    }

    // --- A8 -----------------------------------------------------------------------------------------------------

    @Test
    void aNoteTakesTheSideOfItsCardThatCoversLessOfTheColumns() {
        double room = 280;
        double sheet = 260;
        List<double[]> rightColumn = List.<double[]>of(new double[] {820, 0, 220, 900, 1});
        List<double[]> leftColumn = List.<double[]>of(new double[] {100, 0, 240, 900, 1});
        assertEquals(
                CardAttachment.Side.RIGHT,
                ProjectMapTrailNote.viewsSide(400, 800, room, 1400, 100, 120, sheet, List.of()),
                "the right, as before, with nothing in the way");
        assertEquals(
                CardAttachment.Side.LEFT,
                ProjectMapTrailNote.viewsSide(400, 800, room, 1400, 100, 120, sheet, rightColumn),
                "a column on the right: the left");
        assertEquals(
                CardAttachment.Side.RIGHT,
                ProjectMapTrailNote.viewsSide(400, 800, room, 1400, 100, 120, sheet, leftColumn));
        assertEquals(
                CardAttachment.Side.RIGHT,
                ProjectMapTrailNote.viewsSide(200, 800, room, 1400, 100, 120, sheet, rightColumn),
                "no room on the left: the right whatever it covers");
        assertEquals(
                CardAttachment.Side.RIGHT,
                ProjectMapTrailNote.viewsSide(400, 800, room, 1400, 1000, 120, sheet, rightColumn),
                "the column ends above the sheet: nothing covered");
    }

    @Test
    void aCardOpenedCentredWithNoRoomBesideItPansTheMapForItsNote() {
        double room = 256; // the sheet and its offset from the card
        double sheet = 220;
        // A 640-wide card at 242 in an 1100-wide map area, as a stop's card opens centred: 242 on its left, 218 on
        // its right, short of a sheet on both sides — the sheet would stand over the stop's lines.
        assertEquals(
                CardAttachment.Side.OVER,
                ProjectMapTrailNote.viewsSide(242, 882, room, 1100, 100, 120, sheet, List.of()));
        assertEquals(
                new ProjectMapTrailNote.RoomPan(CardAttachment.Side.LEFT, 14),
                ProjectMapTrailNote.viewsRoomPan(242, 882, room, 1100, 100, 120, sheet, List.of()),
                "the shorter pan: 14 right frees the left, where 38 left would free the right");
        List<double[]> leftColumn = List.<double[]>of(new double[] {14, 0, 188, 900, 1});
        assertEquals(
                new ProjectMapTrailNote.RoomPan(CardAttachment.Side.RIGHT, -38),
                ProjectMapTrailNote.viewsRoomPan(242, 882, room, 1100, 100, 120, sheet, leftColumn),
                "the row's column on the left: the right, though the pan is longer");
        assertEquals(
                new ProjectMapTrailNote.RoomPan(CardAttachment.Side.RIGHT, -26),
                ProjectMapTrailNote.viewsRoomPan(230, 870, room, 1100, 100, 120, sheet, List.of()),
                "exactly centred: the right, as a side is chosen");
        assertNull(
                ProjectMapTrailNote.viewsRoomPan(100, 900, room, 1000, 100, 120, sheet, List.of()),
                "the card and its sheet are wider than the map area: no pan, the sheet stands over the card");
    }

    // --- A9 -----------------------------------------------------------------------------------------------------

    @Test
    void aStopWhoseLinesArePastItsFilesEndSaysSo() throws Exception {
        Path project = project();
        MapTrails.save(
                project, "Shrunk", new MapTrails.Trail("Shrunk", List.of(stop("a/f1.txt", 99000, 99010, "a note"))));
        try (Harness h = open(project, true)) {
            FxTestSupport.runOnFx(() -> assertTrue(h.trails().play("Shrunk", 0)));
            String expected = tr("project.map.status.trail.linesPastEnd", 1, 99000, 99010, "a/f1.txt");
            waitForFx(() -> h.said().contains(expected));
            assertTrue(expected.contains("99000"), "no grouping in a line number: " + expected);
        }
    }

    // --- A10 ----------------------------------------------------------------------------------------------------

    @Test
    void aTrailThisBuildWouldCutPlaysButCannotBeEdited() throws Exception {
        Path project = project();
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        Path file = Files.writeString(
                folder.resolve("authored.json"),
                "{\"title\": \"Authored\", \"stops\": [{\"file\": \"a/f1.txt\", \"startLine\": 1, \"author\": \"kim\"}]}");
        String before = Files.readString(file);
        try (Harness h = open(project, true)) {
            FxTestSupport.runOnFx(() -> {
                h.trails().fill();
                Optional<MenuItem> edit = h.trails().menu().getItems().stream()
                        .filter(item -> item instanceof javafx.scene.control.Menu)
                        .flatMap(item -> ((javafx.scene.control.Menu) item).getItems().stream())
                        .findFirst();
                assertTrue(edit.isPresent());
                assertTrue(edit.get().isDisable(), "Edit ▸ Authored is disabled");
                assertEquals(
                        tr("project.map.trails.keptAsIs", "Authored", "unknown field \"author\" in a stop"),
                        edit.get().getText());
                assertTrue(h.trails().play("authored", 0), "it still plays");
                assertFalse(h.trails().append("authored", stop("README.md", 1, 1, "")));
                assertEquals(
                        tr("project.map.status.trail.notWritable", "Authored", "unknown field \"author\" in a stop"),
                        h.said().getLast());
            });
            assertEquals(before, Files.readString(file), "left as it is");
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static boolean mac() {
        return com.editora.command.KeymapManager.isMac();
    }

    /** A key press with the platform's Shortcut ({@code shortcut}) and Shift. */
    private static KeyEvent key(KeyCode code, boolean shortcut, boolean shift) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !mac(), false, shortcut && mac());
    }

    private static boolean contains(Region surface, Path path) {
        return (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, path);
    }

    @SuppressWarnings("unchecked")
    private static Path selected(Region surface) {
        return ((Optional<ProjectMapModel.Entry>) FxTestSupport.call(surface, "selectedEntry", new Class<?>[0]))
                .map(ProjectMapModel.Entry::path)
                .orElse(null);
    }

    private static void waitForFx(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for the map");
    }
}
