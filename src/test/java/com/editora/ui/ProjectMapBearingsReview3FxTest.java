package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.ProjectMapState;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import com.editora.ui.ProjectMapView.FlowDirection;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project Map review 3, the bearings group: Back/Forward as a history of places (B5), what the Map keeps across a
 * restart (B6), type-ahead, Home and End (B14), the keys and the crosshair that bring the Map up on the active file
 * (E9), following the code out of a card or a declaration (C12), and the folder menu's Find in Folder… and Git
 * history (E10). Keys go through the scene; the last test goes through a whole window's keymap dispatcher.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectMapBearingsReview3FxTest {

    @TempDir
    Path temp;

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** One call of {@link ProjectMapView.FollowCode#follow}. */
    private record Followed(
            Path file, int line, int column, @Nullable String name, String command) {}

    /** Records what the Map asks to follow; a related file is answered from {@link #related}. */
    private static final class Recorder implements ProjectMapView.FollowCode {
        final List<Followed> followed = new CopyOnWriteArrayList<>();
        final List<Path> histories = new CopyOnWriteArrayList<>();
        final List<Path> asked = new CopyOnWriteArrayList<>();
        List<Path> related = List.of();

        @Override
        public void follow(Path file, int line, int column, @Nullable String name, String commandId) {
            followed.add(new Followed(file, line, column, name, commandId));
        }

        @Override
        public void relatedFiles(Path file, Consumer<List<Path>> found) {
            asked.add(file);
            found.accept(related);
        }

        @Override
        public void fileHistory(Path file) {
            histories.add(file);
        }
    }

    /**
     * {@code project/} holding {@code a/f0.txt … f9.txt}, {@code b/x.txt}, {@code src/App.java},
     * {@code src/AppTest.java} and {@code README.md}, left to right at 100% with {@code a} open and {@code f0.txt}
     * selected.
     */
    private record Harness(
            Stage stage,
            ProjectMapView view,
            Region surface,
            Path project,
            Path a,
            Path b,
            Path app,
            Path appTest,
            Path readme,
            List<Path> opened,
            Recorder recorder)
            implements AutoCloseable {

        Path file(int index) {
            return a.resolve("f" + index + ".txt");
        }

        Scene scene() {
            return stage.getScene();
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

    private static final String APP = """
            package demo;

            public class App {
                public void refresh() {
                    helper();
                }

                void helper() {}
            }
            """;

    private Path project() throws Exception {
        Path project = Files.createTempDirectory(temp, "project").toRealPath();
        Path a = Files.createDirectory(project.resolve("a"));
        for (int i = 0; i < 10; i++) {
            Files.writeString(a.resolve("f" + i + ".txt"), "file " + i);
        }
        Files.writeString(Files.createDirectory(project.resolve("b")).resolve("x.txt"), "x");
        Path src = Files.createDirectory(project.resolve("src"));
        Files.writeString(src.resolve("App.java"), APP);
        Files.writeString(src.resolve("AppTest.java"), "class AppTest {}\n");
        Files.writeString(project.resolve("README.md"), "hello");
        return project;
    }

    private Harness open() throws Exception {
        return open(project(), null, state -> {});
    }

    /**
     * The harness over {@code project}; with {@code remembered}, the view starts hidden, is given that state and is
     * then shown, as the Map window is at startup.
     */
    private Harness open(Path project, @Nullable ProjectMapState remembered, Consumer<ProjectMapState> sink)
            throws Exception {
        List<Path> opened = new CopyOnWriteArrayList<>();
        Recorder recorder = new Recorder();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(opened::add, path -> false, path -> false);
            view.setFollowCode(recorder);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 1000, 760));
            stage.show();
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.call(
                    surface, "setFlowDirection", new Class<?>[] {FlowDirection.class}, FlowDirection.LEFT_TO_RIGHT);
            if (remembered != null) {
                view.setShowing(false);
            }
            view.setRememberedPlace(remembered, sink);
            view.setRoot(project);
            if (remembered != null) {
                view.setShowing(true);
            }
            return new Harness(
                    stage,
                    view,
                    surface,
                    project,
                    project.resolve("a"),
                    project.resolve("b"),
                    project.resolve("src/App.java"),
                    project.resolve("src/AppTest.java"),
                    project.resolve("README.md"),
                    opened,
                    recorder);
        });
        waitForFx(() -> contains(h.surface(), h.readme()));
        if (remembered == null) {
            FxTestSupport.runOnFx(() -> h.view().revealPath(h.file(0)));
            waitForFx(() -> contains(h.surface(), h.file(0)) && h.file(0).equals(selected(h.surface())));
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.invoke(h.surface(), "resetViewport");
                h.scene().getRoot().applyCss();
                h.scene().getRoot().layout();
                h.surface().requestFocus();
            });
        }
        return h;
    }

    // --- B5: a history of places --------------------------------------------------------------------------

    @Test
    void historyKeepsTheFileThatWasOpenedAndFoldsKeyboardStepsIntoWhereTheyLead() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.file(0)); // a pick with the pointer is a place
                List<Path> history = h.history();
                int base = history.size();
                press(h.surface(), KeyCode.DOWN);
                press(h.surface(), KeyCode.DOWN);
                press(h.surface(), KeyCode.ENTER); // opens f2.txt: a place
                assertEquals(List.of(h.file(2)), h.opened());
                press(h.surface(), KeyCode.DOWN);
                press(h.surface(), KeyCode.LEFT); // up to the folder: a parent step folds into the run
                assertEquals(h.a(), selected(h.surface()));
                assertEquals(
                        List.of(h.file(0), h.file(2), h.a()),
                        history.subList(base - 1, history.size()),
                        "the opened file stays on the trail; the steps after it are one entry, where they led");

                pressWith(h.surface(), KeyCode.LEFT, false, true, false);
                assertEquals(h.file(2), selected(h.surface()), "one Back: the file that was opened");
                pressWith(h.surface(), KeyCode.LEFT, false, true, false);
                assertEquals(h.file(0), selected(h.surface()));
            });
        }
    }

    @Test
    void aTypedQuerysLandingsAreOnePlaceAndBackAndForwardListThePlaces() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> select(h.surface(), h.readme()));
            FxTestSupport.runOnFx(() -> {
                h.view().setQuery("f7");
                h.view().setSearchMatches("f7", List.of(h.file(7)));
            });
            waitForFx(() -> h.file(7).equals(selected(h.surface())));
            FxTestSupport.runOnFx(() -> {
                h.view().setQuery("f8");
                h.view().setSearchMatches("f8", List.of(h.file(8)));
            });
            waitForFx(() -> h.file(8).equals(selected(h.surface())));
            FxTestSupport.runOnFx(() -> {
                List<Path> history = h.history();
                assertEquals(h.file(8), history.getLast(), "the search landing is recorded");
                assertEquals(h.readme(), history.get(history.size() - 2), "once: the typed query's last landing");
                assertFalse(history.contains(h.file(7)));

                ContextMenu menu = h.view().historyMenu();
                MenuItem newest = menu.getItems().getFirst();
                assertEquals(tr("project.map.history.entry", "f8.txt", "a"), newest.getText(), "name — folder");
                assertTrue(((javafx.scene.control.CheckMenuItem) newest).isSelected(), "the current place");
                MenuItem readme = menu.getItems().get(1);
                assertEquals("README.md", readme.getText(), "a root file has no folder");
                readme.fire();
                assertEquals(h.readme(), selected(h.surface()), "a pick in the menu goes there");

                // A right-click on Back brings the same list up.
                Button back = FxTestSupport.field(h.view(), "backButton");
                Event.fireEvent(
                        back, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 4, 4, 0, 0, false, null));
                ContextMenu shown = javafx.stage.Window.getWindows().stream()
                        .filter(window -> window instanceof ContextMenu candidate
                                && candidate.isShowing()
                                && candidate.getStyleClass().contains("project-map-history-menu"))
                        .map(ContextMenu.class::cast)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no history menu"));
                assertEquals(h.history().size(), shown.getItems().size(), "one item per place");
                shown.hide();
            });
        }
    }

    // --- B14: type-ahead, Home and End ---------------------------------------------------------------------

    @Test
    void homeEndAndTypeAheadMoveWithinTheSelectedRowsColumnAsOneHistoryStep() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                long[] now = {10_000};
                setClock(h.surface(), () -> now[0]);
                select(h.surface(), h.file(5));
                int before = h.history().size();

                press(h.surface(), KeyCode.END);
                assertEquals(h.file(9), selected(h.surface()), "End: the column's last row");
                press(h.surface(), KeyCode.HOME);
                assertEquals(h.file(0), selected(h.surface()), "Home: the column's first row, not the first column's");

                assertTrue(type(h.surface(), "f").isConsumed());
                assertEquals(h.file(1), selected(h.surface()), "a new initial steps to the next row with it");
                now[0] += 300;
                type(h.surface(), "3");
                assertEquals(h.file(3), selected(h.surface()), "typed quickly, the prefix grows: f3");
                now[0] += 1_500;
                type(h.surface(), "f");
                assertEquals(h.file(4), selected(h.surface()), "after a pause a new prefix starts");
                assertEquals(before + 1, h.history().size(), "the whole run is one Back step");
                now[0] += 200;
                type(h.surface(), "F");
                assertEquals(h.file(5), selected(h.surface()), "the same letter again: the next one");
                assertEquals(before, h.history().size(), "a run back to where it started folds away");

                pressShift(h.surface(), KeyCode.HOME);
                assertEquals(h.a(), selected(h.surface()), "Shift+Home: the first column's first row");
                now[0] += 1_500;
                type(h.surface(), "r");
                assertEquals(h.readme(), selected(h.surface()), "type-ahead in the first column");
            });
        }
    }

    private static void setClock(Region surface, java.util.function.LongSupplier clock) {
        try {
            java.lang.reflect.Field field = surface.getClass().getDeclaredField("keysClock");
            field.setAccessible(true);
            field.set(surface, clock);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // --- E9: the crosshair, the ⋯ item and Show in Map -----------------------------------------------------

    @Test
    void theCrosshairAndTheOptionsItemRevealTheActiveFileAndShowInMapSelectsARow(@TempDir Path dir) throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                AtomicInteger reveals = new AtomicInteger();
                h.view().setOnRevealActiveFile(reveals::incrementAndGet);
                Button crosshair = FxTestSupport.field(h.view(), "revealActiveButton");
                assertEquals(tr("project.map.revealActiveFile"), crosshair.getAccessibleText());
                crosshair.fire();
                FxTestSupport.<MenuItem>field(h.view(), "revealActiveItem").fire();
                assertEquals(2, reveals.get());
            });
        }
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.panel.openMapWindow(true));
            ProjectMapView view = FxTestSupport.field(r.panel, "mapView");
            Region surface = FxTestSupport.field(view, "surface");
            r.await("the map lists the project", () -> contains(surface, r.beta));
            FxTestSupport.runOnFx(() -> {
                r.panel.setActiveFile(r.helper);
                FxTestSupport.<Button>field(view, "revealActiveButton").fire();
            });
            r.await("the crosshair selects the file being edited", () -> r.helper.equals(selected(surface)));
            FxTestSupport.runOnFx(() -> {
                assertTrue(r.panel.showInMap(r.guide), "Search Everywhere's Show in Map on a file row");
                assertFalse(r.panel.showInMap("a command row"));
            });
            r.await("Show in Map selects the row", () -> r.guide.equals(selected(surface)));
        }
    }

    // --- C12: following the code ----------------------------------------------------------------------------

    @Test
    void aCardsCodeCommandsMenuAndADeclarationRowFollowTheCodeFromWhereTheyAre() throws Exception {
        try (Harness h = open()) {
            MapCards cards = FxTestSupport.field(h.view(), "cards");
            ProjectMapPreview card = FxTestSupport.callOnFx(() -> cards.openFileCard(h.app()));
            waitForFx(() -> card.editor().getLength() > 0);
            FxTestSupport.runOnFx(() -> {
                card.editor().moveTo(4, 9); // "helper();" in refresh()
                assertTrue(card.runMarkerCommand("lsp.gotoDefinition"), "F12 in a card is the card's");
                assertEquals(
                        new Followed(h.app(), 4, 9, null, "lsp.gotoDefinition"),
                        h.recorder().followed.getLast());
                assertTrue(card.runMarkerCommand("lsp.findReferences"));
                assertEquals(
                        "lsp.findReferences", h.recorder().followed.getLast().command());
                assertTrue(card.runMarkerCommand("git.fileHistory"));
                assertEquals(List.of(h.app()), h.recorder().histories, "File History is the card's file's");

                // The card menu, opened from the keyboard, acts at the caret.
                card.editor().requestFocus();
                Event.fireEvent(
                        card.editor(),
                        new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 4, 4, 0, 0, true, null));
                ContextMenu menu = FxTestSupport.field(card, "editorContextMenu");
                item(menu.getItems(), tr("project.map.follow.callHierarchy")).fire();
                assertEquals(
                        new Followed(h.app(), 4, 9, null, "lsp.callHierarchy"),
                        h.recorder().followed.getLast());
                menu.hide();

                // A declaration row: at the declaration, the caret on its name.
                ProjectMapModel.Symbol symbol =
                        new ProjectMapModel.Symbol(h.app(), "refresh()", "method", 3, 3, 5, null, false);
                ProjectMapModel.Entry row =
                        ProjectMapModel.Entry.symbol(h.app().resolve("…srefresh~0"), h.app(), 0, symbol);
                ContextMenu rowMenu = (ContextMenu)
                        FxTestSupport.call(h.view(), "symbolMenu", new Class<?>[] {ProjectMapModel.Entry.class}, row);
                item(rowMenu.getItems(), tr("project.map.follow.findReferences"))
                        .fire();
                assertEquals(
                        new Followed(h.app(), 3, -1, "refresh", "lsp.findReferences"),
                        h.recorder().followed.getLast());

                // Related Test / Subject reveals the counterpart on the Map.
                h.recorder().related = List.of(h.appTest());
                assertTrue(card.runMarkerCommand("nav.relatedFile"));
                assertEquals(List.of(h.app()), h.recorder().asked);
            });
            waitForFx(() -> h.appTest().equals(selected(h.surface())));
        }
    }

    @Test
    void f12InACardIsAnsweredOnTheMapNotInTheEditor() throws Exception {
        if (fx == null) {
            fx = FxWindowFixture.create();
        }
        Path project = project();
        // A plain-text file: the command finds no language server to start, and the caret is what is checked.
        Path app = Files.writeString(project.resolve("notes.txt"), "one\ntwo\nthree\nfour\n    five helper();\nsix\n");
        ProjectPanel panel = FxTestSupport.field(fx.controller, "projectPanel");
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        com.editora.command.CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setKeymap("cua");
            fx.windowManager.reloadSharedKeymap();
            if (panel.getScene() == null) {
                registry.run("tool.project");
            }
            panel.mapWindow().closeQuietly();
            panel.setRoot(project);
            panel.openMapWindow(true);
        });
        waitForFx(() -> contains(surface, project.resolve("README.md")));
        MapCards cards = FxTestSupport.field(view, "cards");
        ProjectMapPreview card = FxTestSupport.callOnFx(() -> cards.openFileCard(app));
        waitForFx(() -> card.editor().getLength() > 0);
        FxTestSupport.runOnFx(() -> {
            card.editor().requestFocus();
            card.editor().moveTo(4, 9);
            Event.fireEvent(
                    card.editor(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F12, false, false, false, false));
        });
        // Map code navigation: the card asks for the definition itself, and says what it found in the Map's status
        // line; the file is not opened in the editor (its In the Editor submenu still does that).
        ProjectMapStatusLine status = FxTestSupport.field(panel, "mapStatusLine");
        waitForFx(() -> status.text().contains("helper"));
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[0]);
            assertTrue(buffer == null || !app.equals(buffer.getPath()), "the editor did not open the card's file");
            assertTrue(status.text().contains(tr("project.map.links.definition.none", "helper")), status.text());
        });
    }

    // --- E10: a folder's own exploration --------------------------------------------------------------------

    @Test
    void aFoldersMenuFindsInTheFolderAndShowsItsGitHistory(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            List<String> asked = new CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(() -> {
                r.panel.setFileActions(new StubFileActions() {
                    @Override
                    public void findInFolder(Path folder) {
                        asked.add("find " + folder.getFileName());
                    }

                    @Override
                    public void gitShowFolderHistory(Path folder) {
                        asked.add("history " + folder.getFileName());
                    }
                });
                ContextMenu menu = r.menu(r.item(r.src), true, false);
                MenuItem find = ProjectPanelRig.entry(menu.getItems(), "project.menu.findInFolder");
                MenuItem history = ProjectPanelRig.entry(menu.getItems(), "project.menu.git.folderHistory");
                assertNotNull(find);
                assertNotNull(history, "in the folder's Git submenu");
                find.fire();
                history.fire();
                assertEquals(List.of("find src", "history src"), asked);
                ContextMenu fileMenu = r.menu(r.item(r.alpha), false, false);
                assertEquals(
                        null,
                        ProjectPanelRig.entry(fileMenu.getItems(), "project.menu.findInFolder"),
                        "a file has its own history items");
            });
        }
    }

    /** File actions that do nothing; a folder in the active repository. */
    private static class StubFileActions implements ProjectPanel.FileActions {
        @Override
        public boolean localHistoryEnabled() {
            return true;
        }

        @Override
        public void showLocalHistory(Path file) {}

        @Override
        public GitPathScope gitScope(Path path) {
            return GitPathScope.ACTIVE;
        }

        @Override
        public void gitShowFileHistory(Path file) {}

        @Override
        public void gitCompareWithHead(Path file) {}

        @Override
        public void gitCompareWithBranch(Path file) {}

        @Override
        public void gitCompareWithTag(Path file) {}

        @Override
        public void gitCompareWithRevision(Path file) {}

        @Override
        public void gitAnnotate(Path file) {}

        @Override
        public void gitStage(Path file) {}

        @Override
        public void gitUnstage(Path file) {}

        @Override
        public void gitRevert(Path file) {}

        @Override
        public void gitAddToGitignore(Path file) {}
    }

    // --- B6: what the Map keeps across a restart -----------------------------------------------------------

    @Test
    void theOpenFoldersSelectionPlacesZoomAndCardsComeBackAfterARestartWithoutWhatIsGone() throws Exception {
        Path project = project();
        AtomicReference<ProjectMapState> written = new AtomicReference<>();
        ProjectMapState stored;
        double[] cardAt = new double[2];
        try (Harness h = open(project, null, written::set)) {
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.file(9)); // a place that will be gone
                select(h.surface(), h.b());
                press(h.surface(), KeyCode.ENTER); // opens b
            });
            waitForFx(() -> contains(h.surface(), h.b().resolve("x.txt")));
            MapCards cards = FxTestSupport.field(h.view(), "cards");
            FxTestSupport.runOnFx(() -> {
                select(h.surface(), h.b().resolve("x.txt"));
                ProjectMapPreview card = cards.openFileCard(h.file(3));
                card.setContentZoom(1.21);
                FxTestSupport.call(
                        h.surface(),
                        "setZoom",
                        new Class<?>[] {double.class, double.class, double.class},
                        0.8,
                        100.0,
                        100.0);
            });
            waitForFx(() -> cards.codeCard(h.file(3)) != null
                    && cards.codeCard(h.file(3)).getWidth() > 0
                    && cards.codeCard(h.file(3)).editor().getLength() > 0);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = cards.codeCard(h.file(3));
                card.relocate(220, 140); // where the user dragged it
                cardAt[0] = card.getLayoutX();
                cardAt[1] = card.getLayoutY();
                h.view().bearingsFlush();
            });
            stored = written.get();
            assertNotNull(stored, "the state is handed to the workspace");
            assertEquals(project.toString(), stored.getRoot());
            assertTrue(
                    stored.getExpanded().containsAll(List.of("a", "b")),
                    stored.getExpanded().toString());
            assertEquals("b/x.txt", stored.getSelected());
            assertTrue(
                    stored.getHistory().containsAll(List.of("a/f9.txt", "b", "b/x.txt")),
                    stored.getHistory().toString());
            assertEquals(0.8, stored.getZoom(), 0.0001);
            assertEquals(1, stored.getCards().size());
            assertEquals("a/f3.txt", stored.getCards().getFirst().getPath());
            assertEquals(1.21, stored.getCards().getFirst().getTextZoom(), 0.0001);
        }
        Files.delete(project.resolve("a/f9.txt"));

        try (Harness h = open(project, stored, state -> {})) {
            waitForFx(() -> contains(h.surface(), h.b().resolve("x.txt"))
                    && h.b().resolve("x.txt").equals(selected(h.surface())));
            MapCards cards = FxTestSupport.field(h.view(), "cards");
            waitForFx(() -> cards.codeCard(h.file(3)) != null);
            FxTestSupport.runOnFx(() -> {
                assertTrue(h.view().expandedDirectories().containsAll(List.of(h.a(), h.b())), "the open folders");
                assertEquals(0.8, (double) FxTestSupport.field(h.surface(), "zoom"), 0.0001, "the zoom, not a fit");
                assertFalse(h.history().contains(h.file(9)), "a place that is gone is dropped");
                assertTrue(h.history().containsAll(List.of(h.b(), h.b().resolve("x.txt"))));
                ProjectMapPreview card = cards.codeCard(h.file(3));
                assertEquals(cardAt[0], card.getLayoutX(), 0.5, "the card where it was");
                assertEquals(cardAt[1], card.getLayoutY(), 0.5);
                assertEquals(1.21, card.contentZoom(), 0.0001, "at its text zoom");
                Button back = FxTestSupport.field(h.view(), "backButton");
                assertFalse(back.isDisabled(), "Back works after the restart");
            });
        }
    }

    @Test
    void thePanelKeepsTheMapsStateInTheWorkspaceWhenTheWindowCloses(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            WorkspaceState state = new WorkspaceState();
            AtomicInteger later = new AtomicInteger(); // the coalesced save to disk the exploration asks for
            FxTestSupport.runOnFx(() -> r.panel.setRememberedMapState(() -> state, () -> {}, later::incrementAndGet));
            ProjectMapView view = FxTestSupport.field(r.panel, "mapView");
            Region surface = FxTestSupport.field(view, "surface");
            FxTestSupport.runOnFx(() -> r.panel.openMapWindow(true));
            r.await("the map lists the project", () -> contains(surface, r.beta));
            FxTestSupport.runOnFx(() -> {
                select(surface, r.beta);
                r.panel.mapWindow().closeByUser();
            });
            r.await("the state is in the workspace", () -> state.getProjectMapState() != null);
            FxTestSupport.runOnFx(() -> {
                ProjectMapState kept = state.getProjectMapState();
                assertEquals(dir.toString(), kept.getRoot());
                assertEquals("beta.txt", kept.getSelected());
                assertTrue(later.get() > 0, "and a save to disk was asked for, not left to the session's end");
            });
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private static MenuItem item(List<MenuItem> items, String text) {
        return items.stream()
                .filter(item -> text.equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no menu item " + text));
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

    private static void select(Region surface, Path path) {
        FxTestSupport.call(surface, "select", new Class<?>[] {Path.class}, path);
    }

    private static KeyEvent press(Node target, KeyCode code) {
        return pressWith(target, code, false, false, false);
    }

    private static KeyEvent pressShift(Node target, KeyCode code) {
        KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, true, false, false, false);
        return dispatch(target, event);
    }

    private static KeyEvent pressWith(Node target, KeyCode code, boolean ctrl, boolean alt, boolean meta) {
        return dispatch(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, ctrl, alt, meta));
    }

    /** A typed character at {@code target}, through the scene. */
    private static KeyEvent type(Node target, String character) {
        return dispatch(
                target, new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false));
    }

    /** Dispatches {@code event} at {@code target} and returns an event whose consumed state is the outcome. */
    private static KeyEvent dispatch(Node target, KeyEvent event) {
        boolean[] reachedEnd = {false};
        javafx.event.EventHandler<KeyEvent> probe = seen -> reachedEnd[0] = true;
        javafx.stage.Window window = target.getScene().getWindow();
        window.addEventHandler(event.getEventType(), probe);
        try {
            Event.fireEvent(target, event);
        } finally {
            window.removeEventHandler(event.getEventType(), probe);
        }
        if (!reachedEnd[0]) {
            event.consume();
        }
        return event;
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
