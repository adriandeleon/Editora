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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.MapTrails;
import com.editora.config.ProjectMapState;
import com.editora.config.ProjectMapWorkingSet;
import com.editora.ui.ProjectMapView.FlowDirection;
import org.jspecify.annotations.Nullable;
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
 * Trails and working sets past their main paths (Map explore positions 1 and 2): the ⋯ submenus' items acting,
 * refusals (taken, blank, unreadable, newer, full, unwritable), the play bar's buttons and keys, stepping at either
 * end, a trail edited while it plays, a stop whose declaration is gone, the editor's keys, buttons, rows and drag,
 * and the stop's note sheet.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMapTrailsCoverageFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    void setUp() throws Exception {
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

    private record Harness(
            Stage stage, ProjectMapView view, Region surface, Path project, Queue<String> answers, List<String> said)
            implements AutoCloseable {

        Path file(int index) {
            return project.resolve("a/f" + index + ".txt");
        }

        Path app() {
            return project.resolve("src/App.java");
        }

        MapCards cards() {
            return view.cards;
        }

        ProjectMapTrails trails() {
            return view.trails;
        }

        String last() {
            return said.isEmpty() ? "" : said.getLast();
        }

        @Override
        public void close() throws Exception {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage.hide();
            });
        }
    }

    private Path project() throws Exception {
        Path project = Files.createTempDirectory(temp, "project").toRealPath();
        Path a = Files.createDirectory(project.resolve("a"));
        for (int i = 0; i < 5; i++) {
            Files.writeString(a.resolve("f" + i + ".txt"), "file " + i + "\nsecond line\nthird line\nfourth\n");
        }
        Files.writeString(Files.createDirectory(project.resolve("src")).resolve("App.java"), APP);
        Files.writeString(project.resolve("README.md"), "hello");
        return project;
    }

    private Harness open(Path project) throws Exception {
        Queue<String> answers = new ArrayDeque<>();
        List<String> said = new CopyOnWriteArrayList<>();
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
            stage.setScene(new Scene(new StackPane(view), 1100, 780));
            stage.show();
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.call(
                    surface, "setFlowDirection", new Class<?>[] {FlowDirection.class}, FlowDirection.LEFT_TO_RIGHT);
            view.setRoot(project);
            return new Harness(stage, view, surface, project, answers, said);
        });
        waitForFx(() -> contains(h.surface(), project.resolve("README.md")));
        FxTestSupport.drainFx();
        return h;
    }

    private static MapTrails.Stop stop(String file, int from, int to, String title, String note) {
        return new MapTrails.Stop(file, "", from, to, title, note);
    }

    private static void tour(Path project) throws Exception {
        MapTrails.save(
                project,
                "Tour",
                new MapTrails.Trail(
                        "Tour",
                        List.of(
                                stop("a/f1.txt", 2, 3, "One", "first note"),
                                stop("a/f2.txt", 0, 0, "", "a note with no lines"),
                                stop("a/f3.txt", 1, 1, "Three", ""))));
    }

    private static Path newer(Path project) throws Exception {
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        return Files.writeString(
                folder.resolve("future.json"),
                "{\"version\": 9, \"title\": \"Future\", \"stops\": [{\"file\": \"README.md\"}]}");
    }

    private static Path broken(Path project) throws Exception {
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        return Files.writeString(folder.resolve("broken.json"), "{\"stops\": 3}");
    }

    // --- the menu ----------------------------------------------------------------------------------------------

    @Test
    void theTrailsMenuListsWhatTheFolderHoldsAndItsItemsAct() throws Exception {
        Path project = project();
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> {
                h.trails().fill();
                List<MenuItem> items = h.trails().menu().getItems();
                assertFalse(item(items, tr("project.map.trails.new")).isDisable());
                assertTrue(item(items, tr("project.map.trails.addStop")).isDisable(), "no card to add yet");
                assertTrue(item(items, tr("project.map.trails.none")).isDisable());
                assertTrue(items.stream().noneMatch(i -> i instanceof Menu), "nothing to edit: no Edit submenu");
            });
            tour(project);
            newer(project);
            broken(project);
            FxTestSupport.runOnFx(() -> h.cards().openFileCard(h.file(4)));
            waitForFx(() -> h.cards().codeCard(h.file(4)) != null
                    && h.cards().codeCard(h.file(4)).editor().getLength() > 0);
            FxTestSupport.runOnFx(() -> {
                h.trails().fill();
                List<MenuItem> items = h.trails().menu().getItems();
                assertTrue(items.stream()
                        .noneMatch(i -> tr("project.map.trails.none").equals(i.getText())));
                assertTrue(item(items, tr("project.map.trails.unreadable", "broken"))
                        .isDisable());
                Menu edit = (Menu) items.stream()
                        .filter(i -> i instanceof Menu)
                        .findFirst()
                        .orElseThrow();
                assertTrue(item(edit.getItems(), tr("project.map.trails.readOnly", "Future"))
                        .isDisable());
                assertFalse(item(edit.getItems(), "Tour").isDisable());
                MenuItem add = item(items, tr("project.map.trails.addStop"));
                assertFalse(add.isDisable(), "a code card is open: it can be added");

                // Add with no trail chosen: New trail… first, then the note.
                h.answers().addAll(List.of("Fresh", "  added from the menu  "));
                add.fire();
            });
            MapTrails.Trail fresh =
                    MapTrails.read(MapTrails.fileFor(project, "Fresh")).trail();
            assertNotNull(fresh);
            assertEquals(1, fresh.stops().size());
            assertEquals("a/f4.txt", fresh.stops().getFirst().file());
            assertEquals("added from the menu", fresh.stops().getFirst().note());
            FxTestSupport.runOnFx(() -> {
                assertEquals(tr("project.map.status.trail.stopAdded", "Fresh", 1), h.last());
                h.trails().fill();
                item(h.trails().menu().getItems(), tr("project.map.trails.play", "Tour"))
                        .fire();
                assertEquals("Tour", h.trails().playingName());
                assertEquals(0, h.trails().stopShown());
                Menu edit = (Menu) h.trails().menu().getItems().stream()
                        .filter(i -> i instanceof Menu)
                        .findFirst()
                        .orElseThrow();
                item(edit.getItems(), "Tour").fire();
                assertNotNull(h.trails().editor());
                assertEquals("Tour", h.trails().editor().name());

                // The card menu offers the card as a stop; the stop goes to the trail last chosen.
                ProjectMapPreview card = h.cards().codeCard(h.file(4));
                List<MenuItem> cardItems = h.trails().cardMenu(card);
                assertEquals(1, cardItems.size());
                h.answers().add("");
                cardItems.getFirst().fire();
            });
            assertEquals(
                    4,
                    MapTrails.read(MapTrails.fileFor(project, "Tour"))
                            .trail()
                            .stops()
                            .size());
        }
    }

    @Test
    void makingATrailRefusesBlankTakenNewerUnreadableFullAndUnwritableOnes() throws Exception {
        Path project = project();
        tour(project);
        newer(project);
        broken(project);
        MapTrails.Stop one = stop("a/f0.txt", 1, 1, "", "");
        MapTrails.save(
                project, "Full", new MapTrails.Trail("Full", java.util.Collections.nCopies(MapTrails.MAX_STOPS, one)));
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> {
                ProjectMapTrails trails = h.trails();
                assertNull(trails.create(null));
                assertNull(trails.create("  ?? "), "nothing usable in the name");
                assertNull(trails.create(" Tour "));
                assertEquals(tr("project.map.status.trail.exists", "Tour"), h.last());

                assertFalse(trails.append("future", one));
                assertEquals(tr("project.map.status.trail.notWritable", "Future", "version 9"), h.last());
                assertFalse(trails.append("broken", one));
                assertEquals(tr("project.map.status.trail.notWritable", "broken", "\"stops\" is not a list"), h.last());
                assertFalse(trails.append("Full", one));
                assertEquals(tr("project.map.status.trail.full", MapTrails.MAX_STOPS), h.last());

                assertFalse(trails.write("future", new MapTrails.Trail("Future", List.of(one))));
                assertTrue(h.last().contains("version 9"), h.last());

                // New trail… with nothing after: the new, empty trail's editor says how stops are added.
                h.answers().add("Brand new");
                trails.askNew(null);
                assertNotNull(trails.editor());
                assertEquals("Brand-new", trails.editor().name());
                assertTrue(trails.editor().shownStops().isEmpty());
                assertEquals(
                        tr("project.map.status.trail.created", "Brand new", ".editora/map-trails/Brand-new.json"),
                        h.last());

                // Edit refuses what it could not write back.
                trails.edit("future");
                assertEquals(tr("project.map.status.trail.notWritable", "Future", "version 9"), h.last());
                trails.edit("broken");
                assertTrue(h.last().contains("stops"), h.last());
            });
            // A trails folder that is a file: nothing can be written there, and the status says why.
            Path folder = MapTrails.folderFor(project);
            try (var files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    Files.delete(file);
                }
            }
            Files.delete(folder);
            Files.writeString(folder, "not a folder");
            FxTestSupport.runOnFx(() -> {
                assertNull(h.trails().create("Blocked"));
                assertTrue(h.last().startsWith(tr("project.map.status.trail.saveFailed", "Blocked", "")), h.last());
                assertFalse(h.trails().write("Blocked", new MapTrails.Trail("Blocked", List.of())));
            });
        }
    }

    @Test
    void renamingRefusesBlankUnreadableAndTakenNamesAndTheTrailPlayingFollowsIts() throws Exception {
        Path project = project();
        tour(project);
        broken(project);
        MapTrails.save(project, "Other", new MapTrails.Trail("Other", List.of(stop("README.md", 1, 1, "", ""))));
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> {
                ProjectMapTrails trails = h.trails();
                assertNull(trails.rename("Tour", "  "));
                assertNull(trails.rename("broken", "Fixed"));
                assertNull(trails.rename("Tour", "Other"));
                assertEquals(tr("project.map.status.trail.exists", "Other"), h.last());

                assertTrue(trails.play("Tour", 0));
                trails.edit("Tour");
                assertEquals("Grand-tour", trails.rename("Tour", " Grand tour "));
                assertEquals("Grand-tour", trails.playingName(), "the bar plays the renamed file");
                assertEquals("Grand-tour", trails.editor().name(), "the editor follows it too");
                assertTrue(trails.editor().shownStops().size() == 3);
                trails.close();
                assertNull(trails.editor(), "closing the bar closes its editor");
                assertNull(trails.playingName());
            });
        }
    }

    // --- playing ----------------------------------------------------------------------------------------------

    @Test
    void theBarStepsWithinTheTrailAndItsButtonsAndEscapeAct() throws Exception {
        Path project = project();
        tour(project);
        MapTrails.save(project, "Empty", new MapTrails.Trail("Empty", List.of()));
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> {
                ProjectMapTrails trails = h.trails();
                trails.step(1); // nothing playing: nothing to step
                assertEquals(-1, trails.stopShown());
                assertFalse(trails.play("Empty", 0));
                assertEquals(tr("project.map.status.trail.empty", "Empty"), h.last());
                assertFalse(trails.play("missing", 0));

                assertTrue(trails.play("Tour", 99), "an index past the end plays the last stop");
                assertEquals(2, trails.stopShown());
                trails.step(1);
                assertEquals(2, trails.stopShown(), "no stop after the last");
                assertTrue(trails.play("Tour", 0), "playing again keeps one bar");
                assertEquals(
                        1,
                        h.view().viewsCanvasHost().getChildren().stream()
                                .filter(node -> node == trails.bar())
                                .count());
                trails.step(-1);
                assertEquals(0, trails.stopShown(), "no stop before the first");
                assertEquals("One", ((Label) trails.bar().getChildren().get(3)).getText());
                assertTrue(
                        trails.bar().getAccessibleText().contains("Tour"),
                        trails.bar().getAccessibleText());

                button(trails, 2).fire(); // ›
                assertEquals(1, trails.stopShown());
                assertEquals(
                        "f2.txt", ((Label) trails.bar().getChildren().get(3)).getText(), "an untitled stop: its file");
                button(trails, 0).fire(); // ‹
                assertEquals(0, trails.stopShown());
                button(trails, 4).fire(); // Edit
                assertNotNull(trails.editor());
                button(trails, 5).fire(); // ×
                assertNull(trails.playingName());
                assertFalse(h.view().viewsCanvasHost().getChildren().contains(trails.bar()));
                button(trails, 4).fire(); // Edit with nothing playing: nothing
                assertNull(trails.editor());

                assertTrue(trails.play("Tour", 0));
                Event.fireEvent(trails.bar(), key(KeyCode.A, false));
                assertEquals("Tour", trails.playingName(), "another key leaves it playing");
                Event.fireEvent(trails.bar(), key(KeyCode.ESCAPE, false));
                assertNull(trails.playingName(), "Escape closes the bar");
            });
        }
    }

    @Test
    void aStopsCardOpenBeforeTheTrailStaysOpenAndAStopWithoutLinesGetsItsNoteAtTheTop() throws Exception {
        Path project = project();
        tour(project);
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> h.cards().openFileCard(h.file(1)));
            waitForFx(() -> h.cards().codeCard(h.file(1)) != null
                    && h.cards().codeCard(h.file(1)).editor().getLength() > 0);
            FxTestSupport.runOnFx(() -> assertTrue(h.trails().play("Tour", 0)));
            waitForFx(() -> h.trails().stopCard() != null
                    && h.cards().viewsAttachment(h.trails().stopCard()) != null);
            ProjectMapPreview first = FxTestSupport.callOnFx(() -> h.trails().stopCard());
            FxTestSupport.runOnFx(() -> {
                assertTrue(first == h.cards().codeCard(h.file(1)), "the card already open is the stop's");
                h.trails().step(1);
            });
            waitForFx(() -> h.trails().stopCard() != null
                    && h.cards().viewsAttachment(h.trails().stopCard()) != null
                    && h.trails().stopCard().getWidth() > 0);
            FxTestSupport.runOnFx(() -> {
                assertNotNull(h.cards().codeCard(h.file(1)), "a card the trail did not open is left open");
                assertNull(h.cards().viewsAttachment(first), "its note came off");
                ProjectMapPreview card = h.trails().stopCard();
                ProjectMapTrailNote note = (ProjectMapTrailNote) h.cards().viewsAttachment(card);
                assertEquals("a note with no lines", note.text());
                assertEquals(
                        tr("project.map.trails.note.header", 2, 3) + ": a note with no lines",
                        note.getAccessibleText());
                assertTrue(note.isVisible());
                assertTrue(note.card() == card);
                assertEquals(0, note.reserved(CardAttachment.Side.RIGHT));
                assertTrue(note.contains(note.getChildren().getFirst()), "its own header");
                assertFalse(note.contains(card));
                assertFalse(note.contains((Node) null));
                double[] bounds = note.bounds();
                assertNotNull(bounds);
                assertEquals(note.getLayoutX(), bounds[0], 0.01);
                Canvas canvas = new Canvas(1000, 800);
                note.drawConnectors(canvas.getGraphicsContext2D()); // no line: from the card's header

                // Dragged to the card's other side, it stays there as the card moves.
                Node header = note.getChildren().getFirst();
                double startX = note.getLayoutX();
                Event.fireEvent(header, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, 500, 300, false));
                Event.fireEvent(header, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.SECONDARY, 400, 300, false));
                assertEquals(startX, note.getLayoutX(), 0.01, "only the primary button drags");
                Event.fireEvent(header, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 500, 300, true));
                double toX = card.getLayoutX() - ProjectMapTrailNote.WIDTH * card.mapScale() - 60;
                Event.fireEvent(
                        header, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 500 + toX - startX, 340, true));
                assertEquals(toX, note.getLayoutX(), 0.5, "dragged to the left of the card");
                note.drawConnectors(canvas.getGraphicsContext2D());
                card.relocate(card.getLayoutX() + 30, card.getLayoutY());
                assertEquals(toX + 30, note.getLayoutX(), 0.5, "it keeps its side as the card moves");
                note.setAvoid(null);

                card.setVisible(false);
                assertFalse(note.isVisible(), "hidden with its card");
                assertNull(note.bounds());
                note.drawConnectors(canvas.getGraphicsContext2D());
                card.setVisible(true);

                h.trails().step(1); // the card the trail opened closes, and its note with it
                assertNull(note.getParent());
                assertNull(note.bounds());
                note.dispose(); // twice: nothing more
            });
            waitForFx(() -> h.trails().stopCard() != null);
            FxTestSupport.runOnFx(() -> {
                assertNull(h.cards().codeCard(h.file(2)), "the second stop's card closed as the trail moved on");
                assertNull(h.cards().viewsAttachment(h.trails().stopCard()), "a stop with no note gets no sheet");
            });
        }
    }

    @Test
    void aStopsNoteClosesOnItsCrossAndComesBackWhenTheStopIsPlayedAgain() throws Exception {
        Path project = project();
        tour(project);
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> assertTrue(h.trails().play("Tour", 0)));
            waitForFx(() -> h.trails().stopCard() != null
                    && h.cards().viewsAttachment(h.trails().stopCard()) != null);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = h.trails().stopCard();
                ProjectMapTrailNote note = (ProjectMapTrailNote) h.cards().viewsAttachment(card);
                HBox header = (HBox) note.getChildren().getFirst();
                Button close = (Button) header.getChildren().getLast();
                assertEquals("×", close.getText());
                assertEquals(tr("project.map.trails.note.closeHelp"), close.getAccessibleText());
                assertEquals(close.getAccessibleText(), close.getTooltip().getText(), "one name for both");
                close.fire();
                assertNull(note.getParent(), "the sheet is gone");
                assertNull(h.cards().viewsAttachment(card), "and off its card");
                assertTrue(h.trails().stopCard() == card, "the stop's card stays");
                h.trails().play("Tour", 0);
            });
            waitForFx(() -> h.trails().stopCard() != null
                    && h.cards().viewsAttachment(h.trails().stopCard()) != null);
        }
    }

    @Test
    void aStopWhoseDeclarationIsGoneShowsItsFileAndOneOutsideTheProjectShowsTheFile() throws Exception {
        Path project = project();
        MapTrails.save(
                project,
                "Decl",
                new MapTrails.Trail(
                        "Decl",
                        List.of(
                                new MapTrails.Stop("src/App.java", "…sGone~0", 3, 4, "Gone", "where it was"),
                                new MapTrails.Stop("src/App.java", "../../../outside", 2, 2, "Escape", ""))));
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> assertTrue(h.trails().play("Decl", 0)));
            waitForFx(() -> h.cards().codeCard(h.app()) != null
                    && h.cards().viewsAttachment(h.cards().codeCard(h.app())) != null);
            FxTestSupport.runOnFx(() -> {
                assertTrue(
                        h.said().contains(tr("project.map.status.trail.declarationGone", 1)),
                        h.said().toString());
                assertTrue(h.trails().stopCard() == h.cards().codeCard(h.app()), "the file's card instead");
                h.trails().step(1);
            });
            waitForFx(() -> h.trails().stopCard() != null && h.trails().stopShown() == 1);
            FxTestSupport.runOnFx(() -> {
                assertTrue(h.trails().stopCard() == h.cards().codeCard(h.app()), "a declaration outside: the file");
                assertNull(h.cards().viewsAttachment(h.trails().stopCard()));
                h.trails().rootChanged();
                assertNull(h.trails().playingName());
            });
        }
    }

    @Test
    void aTrailEditedWhilePlayingKeepsTheBarOnAStopItHasAndClosesWhenNoneAreLeft() throws Exception {
        Path project = project();
        tour(project);
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> {
                ProjectMapTrails trails = h.trails();
                assertTrue(trails.play("Tour", 2));
                trails.edit("Tour");
                ProjectMapTrailEditor editor = trails.editor();
                editor.list().getSelectionModel().select(2);
                editor.removeSelected();
                assertEquals(1, trails.stopShown(), "the bar moves to the trail's new last stop");
                assertEquals(
                        tr("project.map.trails.bar.position", 2, 2),
                        ((Label) trails.bar().getChildren().get(1)).getText());
                editor.list().getSelectionModel().select(0);
                editor.removeSelected();
                editor.list().getSelectionModel().select(0);
                editor.removeSelected();
                assertNull(trails.playingName(), "no stops left: the bar closes");
                assertNull(trails.editor(), "and its editor with it");
            });
        }
    }

    // --- the editor -------------------------------------------------------------------------------------------

    @Test
    void theEditorsKeysButtonsAndRowsActOnTheSelectedStop() throws Exception {
        Path project = project();
        tour(project);
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> h.trails().edit("Tour"));
            waitForFx(() -> !h.trails().editor().list().lookupAll(".list-cell").isEmpty()
                    && cellTexts(h.trails().editor()).size() >= 3);
            FxTestSupport.runOnFx(() -> {
                ProjectMapTrailEditor editor = h.trails().editor();
                List<String> rows = cellTexts(editor);
                assertEquals(
                        "1. One, " + tr("project.map.trails.stop.lines", "a/f1.txt", "2", "3") + ", first note",
                        rows.get(0));
                assertEquals("2. f2.txt, a/f2.txt, a note with no lines", rows.get(1), "no lines: just the file");
                assertEquals("3. Three, " + tr("project.map.trails.stop.lines", "a/f3.txt", "1", "1"), rows.get(2));

                var list = editor.list();
                list.getSelectionModel().select(0);
                assertTrue(button(editor, "up").isDisable(), "the first stop cannot go up");
                assertFalse(button(editor, "down").isDisable());
                KeyEvent altDown = key(KeyCode.DOWN, true);
                Event.fireEvent(list, altDown);
                assertEquals(List.of("Two?", "One", "Three"), titles(project, "Tour"), "Alt+↓ moves it later");
                assertEquals(1, list.getSelectionModel().getSelectedIndex());
                Event.fireEvent(list, key(KeyCode.UP, true));
                assertEquals(List.of("One", "Two?", "Three"), titles(project, "Tour"), "Alt+↑ back");
                Event.fireEvent(list, key(KeyCode.UP, false));
                assertEquals(
                        List.of("One", "Two?", "Three"), titles(project, "Tour"), "↑ alone only moves the selection");
                list.getSelectionModel().select(2);
                assertTrue(button(editor, "down").isDisable(), "the last stop cannot go down");
                editor.move(1);
                editor.move(-5);
                assertEquals(List.of("One", "Two?", "Three"), titles(project, "Tour"), "out of range: nothing moves");

                button(editor, "up").fire();
                assertEquals(List.of("One", "Three", "Two?"), titles(project, "Tour"));
                button(editor, "down").fire();
                assertEquals(List.of("One", "Two?", "Three"), titles(project, "Tour"));

                list.getSelectionModel().select(1);
                h.answers().add("  Second  ");
                button(editor, "rename").fire();
                h.answers().add("  changed note ");
                button(editor, "note").fire();
                MapTrails.Stop second = read(project, "Tour").stops().get(1);
                assertEquals("Second", second.title());
                assertEquals("changed note", second.note());
                editor.retitle(1, null);
                editor.renote(1, null);
                editor.retitle(7, "out of range");
                editor.renote(-1, "out of range");
                assertEquals("", read(project, "Tour").stops().get(1).title(), "blank: its file's name again");
                assertEquals("", read(project, "Tour").stops().get(1).note());

                button(editor, "play").fire();
                assertEquals(1, h.trails().stopShown(), "Play from Here");
                list.getSelectionModel().select(2);
                Event.fireEvent(list, key(KeyCode.ENTER, false));
                assertEquals(2, h.trails().stopShown(), "Enter plays from the selected stop");
                list.getSelectionModel().select(0);
                Event.fireEvent(list, mouse(MouseEvent.MOUSE_CLICKED, MouseButton.PRIMARY, 10, 10, false, 2));
                assertEquals(0, h.trails().stopShown(), "so does a double-click");
                Event.fireEvent(list, mouse(MouseEvent.MOUSE_CLICKED, MouseButton.PRIMARY, 10, 10, false, 1));
                list.getSelectionModel().select(2);
                Event.fireEvent(list, mouse(MouseEvent.MOUSE_CLICKED, MouseButton.PRIMARY, 10, 10, false, 1));
                assertEquals(0, h.trails().stopShown(), "a single click only selects");

                Event.fireEvent(list, key(KeyCode.DELETE, false));
                assertEquals(List.of("One", "f2.txt?"), titlesOrFiles(project, "Tour"), "Delete removes the stop");
                list.getSelectionModel().select(0);
                button(editor, "remove").fire();
                assertEquals(1, read(project, "Tour").stops().size());

                list.getSelectionModel().clearSelection();
                for (String id : List.of("up", "down", "remove", "rename", "note", "play")) {
                    assertTrue(button(editor, id).isDisable(), id + " needs a selected stop");
                }
                editor.removeSelected();
                editor.move(1);
                button(editor, "rename").fire();
                button(editor, "note").fire();
                button(editor, "play").fire();
                assertEquals(1, read(project, "Tour").stops().size(), "with no stop selected nothing changes");

                h.answers().add("Renamed tour");
                button(editor, "renameTrail").fire();
                assertEquals("Renamed-tour", editor.name());
                assertTrue(Files.exists(MapTrails.fileFor(project, "Renamed tour")));
            });
        }
    }

    @Test
    void theEditorDragsByItsHeaderClosesOnEscapeAndShowsAnUnreadableTrailAsEmpty() throws Exception {
        Path project = project();
        tour(project);
        try (Harness h = open(project)) {
            FxTestSupport.runOnFx(() -> {
                h.trails().edit("Tour");
                ProjectMapTrailEditor editor = h.trails().editor();
                Node header = editor.getChildren().getFirst();
                assertEquals(16, editor.getLayoutX(), 0.01);
                Event.fireEvent(header, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.SECONDARY, 100, 100, false));
                Event.fireEvent(header, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.SECONDARY, 300, 300, false));
                assertEquals(16, editor.getLayoutX(), 0.01, "the secondary button does not drag");
                Event.fireEvent(header, mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY, 100, 100, true));
                Event.fireEvent(header, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, 250, 180, true));
                assertEquals(166, editor.getLayoutX(), 0.01);
                assertEquals(128, editor.getLayoutY(), 0.01);
                Event.fireEvent(header, mouse(MouseEvent.MOUSE_DRAGGED, MouseButton.PRIMARY, -5000, -5000, true));
                assertEquals(0, editor.getLayoutX(), 0.01, "kept inside the map");
                assertEquals(0, editor.getLayoutY(), 0.01);

                Path file = MapTrails.fileFor(project, "Tour");
                editor.show(new MapTrails.Listed("Tour", file, null, "damaged", false));
                assertTrue(editor.shownStops().isEmpty());
                editor.removeSelected();
                editor.retitle(0, "x");
                assertEquals(3, read(project, "Tour").stops().size(), "a trail it cannot read is never written");

                Event.fireEvent(editor, key(KeyCode.B, false));
                assertNotNull(h.trails().editor());
                Event.fireEvent(editor, key(KeyCode.ESCAPE, false));
                assertNull(h.trails().editor(), "Escape closes it");
                assertFalse(h.view().viewsCanvasHost().getChildren().contains(editor));
                h.trails().editorClosed(editor); // closed already: nothing
                assertNull(h.trails().editor());
            });
        }
    }

    // --- working sets ------------------------------------------------------------------------------------------

    @Test
    void workingSetsMenuItemsActAndAFolderHoldsThirtyAtMost() throws Exception {
        Path project = project();
        try (Harness h = open(project)) {
            AtomicReference<List<ProjectMapWorkingSet>> written = new AtomicReference<>(List.of());
            FxTestSupport.runOnFx(() -> {
                ProjectMapWorkingSets sets = h.view().workingSets;
                sets.setRemembered(null, null);
                assertTrue(sets.save("No sink")); // a null sink: saved, told to no one
                sets.setRemembered(List.of(), written::set);
                assertTrue(sets.forRoot().isEmpty());
                sets.fill();
                assertTrue(item(sets.menu().getItems(), tr("project.map.workingSets.none"))
                        .isDisable());
                assertFalse(sets.show("nothing"));
                assertFalse(sets.delete("nothing"));
                assertFalse(sets.save(null));

                for (int i = 0; i < ProjectMapWorkingSets.MAX_SETS; i++) {
                    assertTrue(sets.save("Set " + i));
                }
                assertFalse(sets.save("One too many"));
                assertEquals(tr("project.map.status.workingSet.full", ProjectMapWorkingSets.MAX_SETS), h.last());
                assertTrue(sets.save("set 3"), "a taken name still saves: it replaces that set");
                assertEquals(ProjectMapWorkingSets.MAX_SETS, written.get().size());

                sets.fill();
                item(sets.menu().getItems(), "Set 5").fire();
                assertEquals(tr("project.map.status.workingSet.shown", "Set 5"), h.last());
                Menu delete = (Menu) sets.menu().getItems().stream()
                        .filter(i -> i instanceof Menu)
                        .findFirst()
                        .orElseThrow();
                item(delete.getItems(), "Set 5").fire();
                assertEquals(tr("project.map.status.workingSet.deleted", "Set 5"), h.last());
                assertEquals(ProjectMapWorkingSets.MAX_SETS - 1, written.get().size());

                h.answers().add("Asked");
                sets.askAndSave(); // the current set was deleted: nothing offered
                assertTrue(written.get().stream().anyMatch(set -> set.getName().equals("Asked")));
                assertTrue(sets.delete("Set 0"), "deleting another set keeps the current one");
                h.answers().add("Asked again");
                sets.askAndSave();
                assertTrue(written.get().stream().anyMatch(set -> set.getName().equals("Asked again")));
            });
        }
        // A view with no folder, and one with no prompt: nothing to list, nothing asked.
        FxTestSupport.runOnFx(() -> {
            ProjectMapView bare = new ProjectMapView(path -> {}, path -> false, path -> false);
            try {
                ProjectMapWorkingSets sets = bare.workingSets;
                ProjectMapState elsewhere = new ProjectMapState();
                elsewhere.setRoot("/elsewhere");
                sets.setRemembered(List.of(new ProjectMapWorkingSet("Kept", elsewhere)), null);
                assertTrue(sets.forRoot().isEmpty());
                assertTrue(item(sets.menu().getItems(), tr("project.map.workingSets.local"))
                        .isDisable());
                assertTrue(item(sets.menu().getItems(), tr("project.map.workingSets.save"))
                        .isDisable());
                assertFalse(sets.save("x"), "no folder: nothing to save");
                assertFalse(sets.show("Kept"));
                sets.askAndSave(); // no prompt: nothing happens
                ProjectMapTrails trails = bare.trails;
                trails.fill();
                assertTrue(item(trails.menu().getItems(), tr("project.map.trails.local"))
                        .isDisable());
                assertNull(trails.create("x"));
                assertFalse(trails.append("x", stop("a", 1, 1, "", "")));
                assertFalse(trails.write("x", new MapTrails.Trail("x", List.of())));
                assertNull(trails.rename("x", "y"));
                assertFalse(trails.play("x", 0));
                trails.edit("x");
                assertNull(trails.editor());
                trails.askNew(null);
                trails.ask("h", "l", "", value -> {
                    throw new AssertionError("no prompt to answer");
                });
            } finally {
                bare.dispose();
            }
        });
    }

    @Test
    void onARemoteFolderTrailsAndWorkingSetsAreOfferedForLocalFoldersOnly() throws Exception {
        try (java.nio.file.FileSystem zip = java.nio.file.FileSystems.newFileSystem(
                java.net.URI.create("jar:" + temp.resolve("remote.zip").toUri()), java.util.Map.of("create", "true"))) {
            Path remote = Files.createDirectories(zip.getPath("/project"));
            Files.writeString(remote.resolve("a.txt"), "a");
            List<String> said = new CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(() -> {
                ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
                try {
                    view.setOnStatus(said::add);
                    view.setViewsPrompt((title, label, initial, onAccept) -> onAccept.accept("Asked"));
                    view.setRoot(remote);
                    ProjectMapTrails trails = view.trails;
                    trails.fill();
                    assertTrue(item(trails.menu().getItems(), tr("project.map.trails.local"))
                            .isDisable());
                    assertTrue(item(trails.menu().getItems(), tr("project.map.trails.new"))
                            .isDisable());
                    assertNull(trails.create("Tour"));
                    trails.askNew(null); // the prompt is never shown for a remote folder
                    assertFalse(trails.append("Tour", stop("a.txt", 1, 1, "", "")));
                    assertFalse(trails.write("Tour", new MapTrails.Trail("Tour", List.of())));
                    assertNull(trails.rename("Tour", "Other"));
                    assertFalse(trails.play("Tour", 0));
                    trails.edit("Tour");
                    assertNull(trails.editor());
                    assertFalse(Files.exists(remote.resolve(".editora")), "nothing was written to the remote folder");

                    ProjectMapWorkingSets sets = view.workingSets;
                    sets.fill();
                    assertTrue(item(sets.menu().getItems(), tr("project.map.workingSets.local"))
                            .isDisable());
                    assertTrue(item(sets.menu().getItems(), tr("project.map.workingSets.save"))
                            .isDisable());
                    assertTrue(sets.forRoot().isEmpty());
                    assertTrue(said.isEmpty(), "nothing to report: " + said);
                } finally {
                    view.dispose();
                }
            });
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static MenuItem item(List<MenuItem> items, String text) {
        return items.stream()
                .filter(item -> text.equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(text + " not in "
                        + items.stream().map(MenuItem::getText).toList()));
    }

    private static Button button(ProjectMapTrails trails, int index) {
        return (Button) trails.bar().getChildren().get(index);
    }

    private static Button button(ProjectMapTrailEditor editor, String field) {
        return FxTestSupport.field(editor, field);
    }

    private static List<String> cellTexts(ProjectMapTrailEditor editor) {
        editor.list().applyCss();
        editor.list().layout();
        List<ListCell<?>> cells = new ArrayList<>();
        for (Node node : editor.list().lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && cell.getItem() != null) {
                cells.add(cell);
            }
        }
        cells.sort(java.util.Comparator.comparingInt(ListCell::getIndex));
        return cells.stream().map(Node::getAccessibleText).toList();
    }

    private static KeyEvent key(KeyCode code, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, alt, false);
    }

    private static MouseEvent mouse(
            javafx.event.EventType<MouseEvent> type, MouseButton button, double x, double y, boolean primaryDown) {
        return mouse(type, button, x, y, primaryDown, 1);
    }

    private static MouseEvent mouse(
            javafx.event.EventType<MouseEvent> type,
            MouseButton button,
            double x,
            double y,
            boolean primaryDown,
            int clicks) {
        return new MouseEvent(
                type,
                x,
                y,
                x,
                y,
                button,
                clicks,
                false,
                false,
                false,
                false,
                primaryDown,
                false,
                false,
                false,
                false,
                true,
                null);
    }

    private static MapTrails.Trail read(Path project, String name) {
        return MapTrails.read(MapTrails.fileFor(project, name)).trail();
    }

    /** Titles, with an untitled stop shown as "Two?" (the tour's second stop has none). */
    private static List<String> titles(Path project, String name) {
        return read(project, name).stops().stream()
                .map(stop -> stop.title().isEmpty() ? "Two?" : stop.title())
                .toList();
    }

    private static List<String> titlesOrFiles(Path project, String name) {
        return read(project, name).stops().stream()
                .map(stop -> stop.title().isEmpty() ? ProjectMapTrails.stopTitle(stop) + "?" : stop.title())
                .toList();
    }

    private static boolean contains(Region surface, Path path) {
        return (boolean) FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, path);
    }

    @SuppressWarnings("unused")
    private static @Nullable Path selected(Region surface) {
        @SuppressWarnings("unchecked")
        Optional<ProjectMapModel.Entry> entry =
                (Optional<ProjectMapModel.Entry>) FxTestSupport.call(surface, "selectedEntry", new Class<?>[0]);
        return entry.map(ProjectMapModel.Entry::path).orElse(null);
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
