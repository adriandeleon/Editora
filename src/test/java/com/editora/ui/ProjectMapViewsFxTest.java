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

import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
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
 * Map explore, the views group: working sets — named views of the Map kept per workspace, shown again through the
 * restart's path (position 1) — and trails — guided tours kept in the project's {@code .editora/map-trails/}, made
 * from cards and played stop by stop, with a stop's note stuck on its card (position 2). A trail is played on a Map
 * that has only just been given its folder, as after a restart.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMapViewsFxTest {

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
                    helper();
                }

                void helper() {}
            }
            """;

    /** The view over {@code project}, its answers to prompts (in order) and what it said in its status line. */
    private record Harness(
            Stage stage, ProjectMapView view, Region surface, Path project, Queue<String> answers, List<String> said)
            implements AutoCloseable {

        Path a() {
            return project.resolve("a");
        }

        Path b() {
            return project.resolve("b");
        }

        Path file(int index) {
            return a().resolve("f" + index + ".txt");
        }

        Path app() {
            return project.resolve("src/App.java");
        }

        Path readme() {
            return project.resolve("README.md");
        }

        MapCards cards() {
            return view.cards;
        }

        List<Path> history() {
            return view.historyPlaces();
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
        for (int i = 0; i < 10; i++) {
            Files.writeString(a.resolve("f" + i + ".txt"), "file " + i + "\nsecond line\nthird line\n");
        }
        Files.writeString(Files.createDirectory(project.resolve("b")).resolve("x.txt"), "x");
        Files.writeString(Files.createDirectory(project.resolve("src")).resolve("App.java"), APP);
        Files.writeString(project.resolve("README.md"), "hello");
        return project;
    }

    /**
     * A Map over {@code project}, left to right. {@code settle}: the first load is waited for and {@code a/f0.txt}
     * revealed; otherwise the view has just been given its folder, as the Map window is at startup.
     */
    private Harness open(Path project, boolean settle) throws Exception {
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
        if (settle) {
            waitForFx(() -> contains(h.surface(), h.readme()));
            FxTestSupport.runOnFx(() -> h.view().revealPath(h.file(0)));
            waitForFx(() -> contains(h.surface(), h.file(0)) && h.file(0).equals(selected(h.surface())));
            FxTestSupport.drainFx();
        }
        return h;
    }

    // --- position 1: working sets ------------------------------------------------------------------------------

    @Test
    void aWorkingSetComesBackAsARestartBringsItsFoldersSelectionZoomAndCards() throws Exception {
        Path project = project();
        AtomicReference<List<ProjectMapWorkingSet>> written = new AtomicReference<>();
        double[] cardAt = new double[2];
        try (Harness h = open(project, true)) {
            ProjectMapState elsewhere = new ProjectMapState();
            elsewhere.setRoot("/another/folder");
            FxTestSupport.runOnFx(() -> {
                h.view()
                        .setRememberedWorkingSets(
                                List.of(new ProjectMapWorkingSet("Elsewhere", elsewhere)), written::set);
                h.view().revealPath(h.b().resolve("x.txt"));
            });
            waitForFx(() -> h.b().resolve("x.txt").equals(selected(h.surface())));
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = h.cards().openFileCard(h.file(3));
                card.setContentZoom(1.21);
                setZoom(h.surface(), 0.8);
            });
            waitForFx(() -> h.cards().codeCard(h.file(3)) != null
                    && h.cards().codeCard(h.file(3)).getWidth() > 0
                    && h.cards().codeCard(h.file(3)).editor().getLength() > 0);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = h.cards().codeCard(h.file(3));
                card.relocate(220, 140);
                cardAt[0] = card.getLayoutX();
                cardAt[1] = card.getLayoutY();
                h.answers().add("Reading");
                h.view().workingSets.askAndSave();
            });
            FxTestSupport.runOnFx(() -> {
                assertEquals(
                        List.of("Elsewhere", "Reading"),
                        written.get().stream()
                                .map(ProjectMapWorkingSet::getName)
                                .toList(),
                        "another folder's set is kept, and the new one is added after it");
                ProjectMapState saved = written.get().get(1).getState();
                assertEquals(project.toString(), saved.getRoot());
                assertEquals("b/x.txt", saved.getSelected());
                assertTrue(
                        saved.getExpanded().containsAll(List.of("a", "b")),
                        saved.getExpanded().toString());
                assertTrue(saved.getHistory().isEmpty(), "a set keeps no Back/Forward places");
                assertEquals(1, saved.getCards().size());
                assertEquals(
                        tr("project.map.status.workingSet.saved", "Reading"),
                        h.said().getLast());

                h.view().workingSets.fill();
                List<String> labels = h.view().workingSets.menu().getItems().stream()
                        .map(MenuItem::getText)
                        .toList();
                assertTrue(labels.contains("Reading"), labels.toString());
                assertFalse(labels.contains("Elsewhere"), "only this folder's sets are listed");

                // Somewhere else: another selection, no cards, another zoom, b closed.
                h.cards().closeAll();
                FxTestSupport.call(h.surface(), "select", new Class<?>[] {Path.class}, h.readme());
                setZoom(h.surface(), 1.3);
            });
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.call(h.view(), "closeColumn", new Class<?>[] {Path.class}, h.b()));
            waitForFx(() -> !contains(h.surface(), h.b().resolve("x.txt")));
            FxTestSupport.runOnFx(() -> assertTrue(h.view().workingSets.show("reading"), "names ignore case"));
            waitForFx(() -> h.b().resolve("x.txt").equals(selected(h.surface()))
                    && h.cards().codeCard(h.file(3)) != null);
            FxTestSupport.runOnFx(() -> {
                assertTrue(h.view().expandedDirectories().containsAll(List.of(h.a(), h.b())), "the set's folders");
                assertEquals(0.8, (double) FxTestSupport.field(h.surface(), "zoom"), 0.0001, "the set's zoom");
                ProjectMapPreview card = h.cards().codeCard(h.file(3));
                assertEquals(cardAt[0], card.getLayoutX(), 0.5, "its card where it was");
                assertEquals(cardAt[1], card.getLayoutY(), 0.5);
                assertEquals(1.21, card.contentZoom(), 0.0001);
                assertEquals(h.b().resolve("x.txt"), h.history().getLast(), "the switch is one more place");
                assertEquals(
                        tr("project.map.status.workingSet.shown", "Reading"),
                        h.said().getLast());

                assertTrue(h.view().workingSets.delete("Reading"));
                assertEquals(
                        List.of("Elsewhere"),
                        written.get().stream()
                                .map(ProjectMapWorkingSet::getName)
                                .toList());
                assertTrue(h.view().workingSets.forRoot().isEmpty());
            });
        }
    }

    @Test
    void savingUnderATakenNameReplacesThatSetWhereItStands() throws Exception {
        try (Harness h = open(project(), true)) {
            AtomicReference<List<ProjectMapWorkingSet>> written = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                h.view().setRememberedWorkingSets(List.of(), written::set);
                assertTrue(h.view().workingSets.save("One"));
                assertTrue(h.view().workingSets.save("Two"));
                FxTestSupport.call(h.surface(), "select", new Class<?>[] {Path.class}, h.readme());
                assertTrue(h.view().workingSets.save(" one "));
                assertEquals(
                        List.of("One", "Two"),
                        written.get().stream()
                                .map(ProjectMapWorkingSet::getName)
                                .toList());
                assertEquals("README.md", written.get().getFirst().getState().getSelected());
                assertEquals(
                        tr("project.map.status.workingSet.updated", "One"),
                        h.said().getLast());
                assertFalse(h.view().workingSets.save("  "), "a blank name saves nothing");
            });
        }
    }

    // --- position 2: trails ---------------------------------------------------------------------------------------

    @Test
    void aTrailIsMadeFromCardsAndPlaysOnAFreshMapThroughTheRestartsPath() throws Exception {
        Path project = project();
        Path trailFile = MapTrails.fileFor(project, "Tour");
        try (Harness h = open(project, true)) {
            FxTestSupport.runOnFx(() -> h.cards().openFileCard(h.app()));
            waitForFx(() -> h.cards().codeCard(h.app()) != null
                    && h.cards().codeCard(h.app()).editor().getLength() > 0);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = h.cards().codeCard(h.app());
                assertTrue(card.revealRange(new ProjectMapPreview.Range("", 3, 3, 5)), "lines 4–6 selected");
                h.answers().addAll(List.of("Tour", "Start at refresh"));
                h.view().trails.addStop(card); // no trail yet: New trail… first, then the stop's note
            });
            assertTrue(Files.exists(trailFile), "kept in the project, to be committed");
            MapTrails.Stop first = MapTrails.read(trailFile).trail().stops().getFirst();
            assertEquals(new MapTrails.Stop("src/App.java", "", 4, 6, "App.java", "Start at refresh"), first);

            // A declaration card is a stop of its own: its declaration and its lines.
            ProjectMapStructure structure = FxTestSupport.field(h.view(), "structure");
            FxTestSupport.runOnFx(() -> structure.ensure(h.app()));
            waitForFx(() -> !structure.view(h.app()).roots().isEmpty());
            Path helper = FxTestSupport.callOnFx(
                    () -> findDeclaration(structure.view(h.app()).roots(), "helper"));
            assertNotNull(helper, "helper() is in App.java's outline");
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = h.cards().viewsOpenCard(helper);
                assertNotNull(card);
                List<MenuItem> menu = cardMenu(card);
                assertTrue(
                        menu.stream()
                                .anyMatch(
                                        item -> tr("project.map.trails.addStop").equals(item.getText())),
                        "the card menu adds the card as a stop");
                h.answers().add("Called twice");
                menu.stream()
                        .filter(item -> tr("project.map.trails.addStop").equals(item.getText()))
                        .findFirst()
                        .orElseThrow()
                        .fire();
            });
            List<MapTrails.Stop> stops = MapTrails.read(trailFile).trail().stops();
            assertEquals(2, stops.size());
            MapTrails.Stop second = stops.get(1);
            assertEquals("src/App.java", second.file());
            assertEquals(h.app().relativize(helper).toString().replace('\\', '/'), second.declaration());
            assertEquals(9, second.startLine(), "helper's line, 1-based");
            assertEquals("Called twice", second.note());
            assertEquals(
                    tr("project.map.status.trail.stopAdded", "Tour", 2),
                    h.said().getLast());
        }

        // A Map that has only just been given its folder: nothing listed, nothing laid out, no outline.
        try (Harness h = open(project, false)) {
            FxTestSupport.runOnFx(() -> assertTrue(h.view().trails.play("Tour", 0)));
            waitForFx(() -> h.cards().codeCard(h.app()) != null
                    && h.cards().codeCard(h.app()).editor().getLength() > 0
                    && h.cards().viewsAttachment(h.cards().codeCard(h.app())) != null);
            waitForFx(() -> h.cards().codeCard(h.app()).editor().getSelection().getLength() > 0);
            FxTestSupport.runOnFx(() -> {
                assertEquals(h.app(), selected(h.surface()), "the stop's file revealed on the map");
                ProjectMapPreview card = h.cards().codeCard(h.app());
                var editor = card.editor();
                int from = editor.offsetToPosition(
                                editor.getSelection().getStart(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                        .getMajor();
                int to = editor.offsetToPosition(
                                editor.getSelection().getEnd(), org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                        .getMajor();
                assertEquals(3, from, "the card at the stop's lines");
                assertEquals(5, to);
                ProjectMapTrailNote note = (ProjectMapTrailNote) h.cards().viewsAttachment(card);
                assertEquals("Start at refresh", note.text());
                assertTrue(note.isVisible());
                assertTrue(
                        note.getLayoutX() >= card.getLayoutX() + card.getWidth() * card.mapScale() - 1
                                || note.getLayoutX() + ProjectMapTrailNote.WIDTH <= card.getLayoutX() + 1,
                        "beside the card");
                assertTrue(h.cards().attachments(card).contains(note), "stuck on the card like its other sheets");
                assertBar(h, 1, 2, "App.java");
                h.view().trails.step(1);
            });
            waitForFx(() -> h.view().trails.stopCard() != null
                    && h.view().trails.stopCard().range() != null
                    && h.cards().viewsAttachment(h.view().trails.stopCard()) != null);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = h.view().trails.stopCard();
                assertEquals(
                        "helper", card.range().symbol().replaceAll("\\(.*", "").strip());
                assertNull(h.cards().codeCard(h.app()), "the card the trail opened for the last stop closed");
                assertEquals("Called twice", ((ProjectMapTrailNote) h.cards().viewsAttachment(card)).text());
                assertBar(h, 2, 2, "helper");
                h.view().trails.close();
                assertFalse(h.view()
                        .viewsCanvasHost()
                        .getChildren()
                        .contains(h.view().trails.bar()));
                assertNull(h.cards().viewsAttachment(card), "the note comes off; the card stays");
                assertTrue(h.cards().openCards().stream().anyMatch(open -> open.node() == card));
            });
        }
    }

    @Test
    void aRestartsDeclarationCardWaitsForItsOutlineInsteadOfBeingDropped() throws Exception {
        // The path a trail's declaration stop takes is the restart's: a declaration card asked for while its file's
        // outline is only being loaded opens when the outline arrives (it used to be dropped as "gone").
        Path project = project();
        Path helper;
        try (Harness h = open(project, true)) {
            ProjectMapStructure structure = FxTestSupport.field(h.view(), "structure");
            FxTestSupport.runOnFx(() -> structure.ensure(h.app()));
            waitForFx(() -> !structure.view(h.app()).roots().isEmpty());
            helper = FxTestSupport.callOnFx(
                    () -> findDeclaration(structure.view(h.app()).roots(), "helper"));
        }
        assertNotNull(helper);
        ProjectMapState stored = new ProjectMapState();
        stored.setRoot(project.toString());
        stored.setExpanded(List.of("src"));
        stored.setSelected("src/App.java");
        stored.setCards(List.of(new ProjectMapState.Card(
                project.relativize(helper).toString().replace('\\', '/'), 300, 120, 480, 220, 1.0, false)));
        try (Harness restarted = openRemembering(project, stored)) {
            waitForFx(() -> restarted.cards().codeCard(helper) != null);
            FxTestSupport.runOnFx(
                    () -> assertEquals(300, restarted.cards().codeCard(helper).getLayoutX(), 0.5, "where it was"));
        }
    }

    /** The harness given {@code remembered} while hidden and then shown, as the Map window is at startup. */
    private Harness openRemembering(Path project, ProjectMapState remembered) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(path -> {}, path -> false, path -> false);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 1100, 780));
            stage.show();
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.call(
                    surface, "setFlowDirection", new Class<?>[] {FlowDirection.class}, FlowDirection.LEFT_TO_RIGHT);
            view.setShowing(false);
            view.setRememberedPlace(remembered, state -> {});
            view.setRoot(project);
            view.setShowing(true);
            return new Harness(stage, view, surface, project, new ArrayDeque<>(), new CopyOnWriteArrayList<>());
        });
    }

    @Test
    void aMalformedTrailIsListedAsUnreadableAndAStopWhoseFileIsGoneSaysSo() throws Exception {
        Path project = project();
        Path folder = Files.createDirectories(MapTrails.folderFor(project));
        Files.writeString(folder.resolve("broken.json"), "{\"stops\": [ {\"file\": ");
        MapTrails.save(
                project,
                "gone",
                new MapTrails.Trail(
                        "Gone",
                        List.of(
                                new MapTrails.Stop("src/Removed.java", "", 1, 2, "", ""),
                                new MapTrails.Stop("README.md", "", 1, 1, "", "the readme"))));
        try (Harness h = open(project, true)) {
            FxTestSupport.runOnFx(() -> {
                h.view().trails.fill();
                List<MenuItem> items = h.view().trails.menu().getItems();
                MenuItem broken = items.stream()
                        .filter(item ->
                                tr("project.map.trails.unreadable", "broken").equals(item.getText()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError(labels(items)));
                assertTrue(broken.isDisable(), "listed, not playable");
                assertTrue(
                        items.stream()
                                .anyMatch(item ->
                                        tr("project.map.trails.play", "Gone").equals(item.getText())),
                        labels(items));

                assertFalse(h.view().trails.play("broken", 0));
                assertTrue(
                        h.said()
                                .getLast()
                                .startsWith(tr("project.map.status.trail.unreadable", "broken.json", "")
                                        .strip()),
                        h.said().getLast());
                assertTrue(h.view().trails.play("gone", 0));
                assertEquals(
                        tr("project.map.status.trail.stopGone", 1, "src/Removed.java"),
                        h.said().getLast());
                assertBar(h, 1, 2, "Removed.java");
                h.view().trails.step(1);
            });
            waitForFx(() -> h.cards().codeCard(h.readme()) != null
                    && h.cards().viewsAttachment(h.cards().codeCard(h.readme())) != null);
            assertEquals("{\"stops\": [ {\"file\": ", Files.readString(folder.resolve("broken.json")), "left alone");
        }
    }

    @Test
    void theEditorReordersRemovesAndRenamesStopsInTheTrailsFile() throws Exception {
        Path project = project();
        List<MapTrails.Stop> three = List.of(
                new MapTrails.Stop("a/f1.txt", "", 1, 2, "One", ""),
                new MapTrails.Stop("a/f2.txt", "", 1, 2, "Two", ""),
                new MapTrails.Stop("a/f3.txt", "", 1, 2, "Three", "a note"));
        MapTrails.save(project, "edit-me", new MapTrails.Trail("Edit me", three));
        try (Harness h = open(project, true)) {
            FxTestSupport.runOnFx(() -> {
                h.view().trails.edit("edit-me");
                ProjectMapTrailEditor editor = h.view().trails.editor();
                assertNotNull(editor);
                assertTrue(h.view().viewsCanvasHost().getChildren().contains(editor), "a card over the map");
                assertEquals(three, editor.shownStops());

                editor.list().getSelectionModel().select(2);
                editor.move(-1);
                assertEquals(List.of("One", "Three", "Two"), titles(project, "edit-me"), "written at once");
                assertEquals(1, editor.list().getSelectionModel().getSelectedIndex(), "the moved stop stays selected");
                editor.retitle(0, "Start here");
                editor.renote(0, "  first  ");
                editor.list().getSelectionModel().select(2);
                editor.removeSelected();
                assertEquals(List.of("Start here", "Three"), titles(project, "edit-me"));
                assertEquals(
                        "first", read(project, "edit-me").stops().getFirst().note());

                assertEquals("Onboarding", h.view().trails.rename("edit-me", "Onboarding"));
                assertFalse(Files.exists(MapTrails.fileFor(project, "edit-me")), "the file follows the name");
                assertEquals("Onboarding", read(project, "Onboarding").title());
                assertEquals("Onboarding", editor.name());
                assertEquals(List.of("Start here", "Three"), titles(project, "Onboarding"));
                editor.dispose();
                assertNull(h.view().trails.editor());
            });
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private static void assertBar(Harness h, int stop, int of, String title) {
        Label position = (Label) h.view().trails.bar().getChildren().get(1);
        Label name = (Label) h.view().trails.bar().getChildren().get(3);
        Button previous = (Button) h.view().trails.bar().getChildren().getFirst();
        Button next = (Button) h.view().trails.bar().getChildren().get(2);
        assertTrue(
                h.view()
                        .viewsCanvasHost()
                        .getChildren()
                        .contains(h.view().trails.bar()),
                "the bar is up");
        assertEquals(tr("project.map.trails.bar.position", stop, of), position.getText());
        assertTrue(name.getText().startsWith(title), name.getText());
        assertEquals(stop == 1, previous.isDisable());
        assertEquals(stop == of, next.isDisable());
    }

    @SuppressWarnings("unchecked")
    private static List<MenuItem> cardMenu(ProjectMapPreview card) {
        FxTestSupport.call(card, "rebuildEditorContextMenu", new Class<?>[] {int.class}, 0);
        ContextMenu menu = FxTestSupport.field(card, "editorContextMenu");
        return new ArrayList<>(menu.getItems());
    }

    private static @Nullable Path findDeclaration(List<ProjectMapStructure.Node> nodes, String name) {
        for (ProjectMapStructure.Node node : nodes) {
            if (node.symbol().label().contains(name)) {
                return node.path();
            }
            Path found = findDeclaration(node.children(), name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static MapTrails.Trail read(Path project, String name) {
        return MapTrails.read(MapTrails.fileFor(project, name)).trail();
    }

    private static List<String> titles(Path project, String name) {
        return read(project, name).stops().stream().map(MapTrails.Stop::title).toList();
    }

    private static String labels(List<MenuItem> items) {
        return items.stream().map(MenuItem::getText).toList().toString();
    }

    private static void setZoom(Region surface, double zoom) {
        FxTestSupport.call(
                surface, "setZoom", new Class<?>[] {double.class, double.class, double.class}, zoom, 100.0, 100.0);
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
