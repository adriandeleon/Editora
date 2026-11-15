package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.MapTrails;
import com.editora.editor.MenuChords;
import com.editora.i18n.Messages;
import com.editora.ui.ProjectMapView.FlowDirection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map's rows and buttons as commands (KBD-01 and MENU-05, Pauta 6.0.1): each {@link MapMenuCommands}
 * command does what its row or button does to what the Map's keyboard is on, and says why when it cannot. The commands
 * run on a Map on its own in a shown window; a status line, a prompt and a picker are recorded.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MapMenuCommandsFxTest {

    private static final String APP =
            "package demo;\n\nclass App {\n    void run() {\n        go();\n    }\n\n" + "    void go() {}\n}\n";

    @TempDir
    Path temp;

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** What the Map's picker was asked to show. */
    private record Picked(String title, List<String> rows, int current, IntConsumer chosen) {}

    private record Harness(
            Stage stage,
            ProjectMapView view,
            MapSurface surface,
            Path project,
            Queue<String> answers,
            List<String> said,
            List<Path> opened,
            List<Picked> picks)
            implements AutoCloseable {

        Path file(int index) {
            return project.resolve("a").resolve("f" + index + ".txt");
        }

        String last() {
            return said.isEmpty() ? "" : said.getLast();
        }

        /** Runs command {@code id} as the registry does while the Map is open. */
        void run(String id) {
            MapMenuCommands.run(view, id);
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
            Files.writeString(a.resolve("f" + i + ".txt"), "file " + i + "\nsecond line\nthird line\n");
        }
        Files.writeString(Files.createDirectory(project.resolve("src")).resolve("App.java"), APP);
        Files.writeString(project.resolve("README.md"), "hello");
        return project;
    }

    private Harness open() throws Exception {
        Path project = project();
        Queue<String> answers = new ArrayDeque<>();
        List<String> said = new CopyOnWriteArrayList<>();
        List<Path> opened = new CopyOnWriteArrayList<>();
        List<Picked> picks = new CopyOnWriteArrayList<>();
        Harness h = FxTestSupport.callOnFx(() -> {
            ProjectMapView view = new ProjectMapView(opened::add, path -> false, path -> false);
            view.setOnStatus(said::add);
            view.setViewsPrompt((title, label, initial, onAccept) -> {
                String answer = answers.poll();
                if (answer != null) {
                    onAccept.accept(answer);
                }
            });
            view.setPicker((title, rows, current, chosen) -> picks.add(new Picked(title, rows, current, chosen)));
            view.setContextMenuFactory(entry -> new ContextMenu()); // the map's own rows only
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(view), 1100, 780));
            stage.show();
            MapSurface surface = FxTestSupport.field(view, "surface");
            FxTestSupport.call(
                    surface, "setFlowDirection", new Class<?>[] {FlowDirection.class}, FlowDirection.LEFT_TO_RIGHT);
            view.setRoot(project);
            return new Harness(stage, view, surface, project, answers, said, opened, picks);
        });
        waitFor(() -> contains(h.surface(), project.resolve("README.md")), "the first column");
        FxTestSupport.drainFx();
        return h;
    }

    /** Reveals {@code path} on the map, selected, with the keyboard on the map's rows. */
    private static void selectRow(Harness h, Path path) throws Exception {
        FxTestSupport.runOnFx(() -> h.view().revealPath(path));
        waitFor(() -> path.equals(h.surface().selectedPath()), "the row " + path);
        FxTestSupport.runOnFx(() -> h.surface().requestFocus());
        FxTestSupport.drainFx();
    }

    private static ProjectMapPreview openCard(Harness h, Path file) throws Exception {
        ProjectMapPreview card = FxTestSupport.callOnFx(() -> h.view().cards.openFileCard(file));
        waitFor(() -> card.editor().getLength() > 0, "the card's text");
        FxTestSupport.runOnFx(() -> card.editor().requestFocus());
        FxTestSupport.drainFx();
        return card;
    }

    private static boolean contains(MapSurface surface, Path path) {
        List<ProjectMapModel.Entry> entries = FxTestSupport.field(surface, "entries");
        return entries.stream().anyMatch(entry -> entry.path().equals(path));
    }

    private static boolean onCanvas(Harness h, Class<?> type) {
        return h.view().viewsCanvasHost().getChildren().stream().anyMatch(type::isInstance);
    }

    private static void waitFor(BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static String notOffered(String id) {
        return tr("status.projectMap.notOffered", CommandTips.title(id));
    }

    private static String unavailable(String id) {
        return tr("status.projectMap.rowUnavailable", CommandTips.title(id));
    }

    // --- registration ---------------------------------------------------------------------------------------------

    @Test
    void everyCommandIsRegisteredTitledAndSaysSoWhileTheMapIsClosed() throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            FxTestSupport.runOnFx(() -> {
                StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
                for (String id : MapMenuCommands.IDS) {
                    assertTrue(fx.controller.registry.get(id).isPresent(), id + " is registered");
                    assertTrue(Messages.keys().contains("command." + id), id + " has a title");
                    assertTrue(Messages.keys().contains("command." + id + ".desc"), id + " has a description");
                    fx.controller.setStatus("");
                    fx.controller.registry.run(id);
                    assertEquals(
                            tr("status.projectMap.notShowing"),
                            FxTestSupport.<javafx.scene.control.Label>field(statusBar, "echo")
                                    .getText(),
                            id + " acts on no Map behind the user's back");
                }
            });
        } finally {
            fx.dispose();
        }
    }

    // --- the options menu ---------------------------------------------------------------------------------------

    @Test
    void theOptionsRowsAreCommandsThatTurnTheirOptionsOver() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                ProjectMapView view = h.view();
                CheckMenuItem hideNotes = FxTestSupport.field(view, "hideOpenNotes");
                Map<String, BooleanSupplier> options = new java.util.LinkedHashMap<>();
                options.put("projectMap.toggleKeepZoom", view::isKeepZoom);
                options.put("projectMap.toggleFocusNewColumn", view::isFocusNewColumn);
                options.put("projectMap.toggleSingleClickOpens", view::isSingleClickOpens);
                options.put("projectMap.toggleCompactChains", view::isCompactChains);
                options.put("projectMap.toggleCompactRows", view::isCompactRows);
                options.put("projectMap.toggleHideIgnored", view::isHideIgnored);
                options.put("projectMap.toggleRecentChanges", view::isShowRecentChanges);
                options.put("projectMap.toggleDependencyOverlay", view::isShowDependencies);
                options.put("projectMap.toggleFollowDebugger", view::isFollowDebugger);
                options.put("projectMap.toggleHideNotes", hideNotes::isSelected);
                options.put("projectMap.toggleFollowActiveFile", view::isFollowActiveFile);
                for (Map.Entry<String, BooleanSupplier> option : options.entrySet()) {
                    String id = option.getKey();
                    boolean before = option.getValue().getAsBoolean();
                    h.run(id);
                    assertEquals(!before, option.getValue().getAsBoolean(), id + " turns its option over");
                    h.run(id);
                    assertEquals(before, option.getValue().getAsBoolean(), id + " and back");
                }
                // The single-click option reaches the map itself, as a click on the row does.
                h.run("projectMap.toggleSingleClickOpens");
                assertFalse(h.surface().singleClickOpens);
                h.run("projectMap.toggleSingleClickOpens");
                assertTrue(h.surface().singleClickOpens);

                // Every row of the menu is its command's: it shows that command's chord.
                List<MenuItem> rows = view.optionsMenuNow();
                for (String id : options.keySet()) {
                    MapButtonCommands.Found found = MapButtonCommands.rowIn(rows, id, false);
                    assertNotNull(found, id + " has its row");
                    assertNotNull(found.action(), id + "'s row is enabled");
                }
                assertEquals(
                        "projectMap.toggleKeepZoom",
                        MenuChords.commandOf(FxTestSupport.<CheckMenuItem>field(view, "keepZoomOnOpen")));

                // Flow: one command per direction, each choosing its radio row.
                for (FlowDirection flow : FlowDirection.values()) {
                    h.run(ProjectMapView.flowCommand(flow));
                    assertEquals(flow, view.canvasFlow(), ProjectMapView.flowCommand(flow));
                }
                h.run("projectMap.flowLeftToRight");
                assertEquals(FlowDirection.LEFT_TO_RIGHT, view.canvasFlow());
                assertTrue(h.said().isEmpty(), "nothing was refused: " + h.said());
            });
        }
    }

    @Test
    void theOptionsActionsOpenTheirCardsAndARowTheMenuDisablesIsRefused() throws Exception {
        try (Harness h = open()) {
            selectRow(h, h.file(1));
            FxTestSupport.runOnFx(() -> h.run("projectMap.overview"));
            waitFor(() -> onCanvas(h, ProjectMapOverviewCard.class), "the Overview card"); // its facts read first
            FxTestSupport.runOnFx(() -> {
                h.run("projectMap.projectDependencies");
                assertTrue(onCanvas(h, ProjectMapDependencyCard.class), "the Dependencies card");
                h.run("projectMap.projectMetrics");
                assertTrue(onCanvas(h, ProjectMapMetricsCard.class), "the Code Metrics card");
                h.run("projectMap.reviewChanges");
                assertTrue(onCanvas(h, ProjectMapChangesCard.class), "the Changes card");

                // Export draws the map to a file a native chooser asks for: while the menu disables it, refused.
                Menu export = FxTestSupport.field(h.view(), "canvasExportMenu");
                export.setDisable(true);
                h.run("projectMap.exportPng");
                assertEquals(unavailable("projectMap.exportPng"), h.last());
                h.run("projectMap.exportSvg");
                assertEquals(unavailable("projectMap.exportSvg"), h.last());

                // No card switcher on a Map on its own: the command says there is no card to switch to.
                h.run("projectMap.switchCard");
                assertEquals(tr("project.map.switch.none"), h.last());
            });
            // Collapse All closes every column off the way to the selection: with README.md selected, a/'s.
            assertTrue(FxTestSupport.callOnFx(() -> contains(h.surface(), h.file(1))));
            selectRow(h, h.project().resolve("README.md"));
            FxTestSupport.runOnFx(() -> h.run("projectMap.collapseAll"));
            waitFor(() -> !contains(h.surface(), h.file(1)), "a/ closed");
        }
    }

    @Test
    void theWorkingSetsAndTrailsAreAPickAway() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                // Save current as… asks for a name in the Map's prompt, as its row does.
                h.answers().add("Alpha");
                h.run("projectMap.saveWorkingSet");
                assertEquals(tr("project.map.status.workingSet.saved", "Alpha"), h.last());

                h.run("projectMap.showWorkingSet");
                Picked shown = h.picks().getLast();
                assertEquals(CommandTips.title("projectMap.showWorkingSet"), shown.title());
                assertEquals(List.of("Alpha"), shown.rows());
                shown.chosen().accept(0);
                assertEquals(tr("project.map.status.workingSet.shown", "Alpha"), h.last());

                // The pick is asked about first, as the menu's row is: Cancel keeps the set.
                h.run("projectMap.deleteWorkingSet");
                Picked deleted = h.picks().getLast();
                assertEquals(List.of("Alpha"), deleted.rows());
                try (ConfirmAnswers questions = ConfirmAnswers.cancelling()) {
                    deleted.chosen().accept(0);
                    assertEquals(List.of(ProjectMapWorkingSets.deleteQuestion("Alpha")), questions.asked());
                    assertEquals(tr("project.map.status.workingSet.shown", "Alpha"), h.last(), "Cancel: kept");
                    questions.answer(true);
                    deleted.chosen().accept(0);
                    assertEquals(2, questions.asked().size());
                }
                assertEquals(tr("project.map.status.workingSet.deleted", "Alpha"), h.last());

                // Nothing left: no picker, the status line says so.
                int before = h.picks().size();
                h.run("projectMap.showWorkingSet");
                assertEquals(before, h.picks().size());
                assertEquals(
                        tr("status.projectMap.nothingToPick", CommandTips.title("projectMap.showWorkingSet")),
                        h.last());
                h.run("projectMap.playTrail");
                assertEquals(
                        tr("status.projectMap.nothingToPick", CommandTips.title("projectMap.playTrail")), h.last());
                // No trail plays: the bar's ✎ and title have nothing to act on.
                h.run("projectMap.trailEditPlaying");
                assertEquals(unavailable("projectMap.trailEditPlaying"), h.last());
                h.run("projectMap.trailShowStop");
                assertEquals(unavailable("projectMap.trailShowStop"), h.last());
            });

            MapTrails.save(
                    h.project(),
                    "Tour",
                    new MapTrails.Trail("Tour", List.of(new MapTrails.Stop("a/f1.txt", "", 0, 0, "One", ""))));
            FxTestSupport.runOnFx(() -> {
                h.run("projectMap.playTrail");
                Picked play = h.picks().getLast();
                assertEquals(List.of("Tour"), play.rows());
                play.chosen().accept(0);
                assertEquals("Tour", h.view().trails.playingName());

                h.run("projectMap.editTrail");
                Picked edit = h.picks().getLast();
                assertEquals(List.of("Tour"), edit.rows());
                edit.chosen().accept(0);
                assertNotNull(h.view().trails.editor(), "the trail editor opened");
                assertEquals("Tour", h.view().trails.editor().name());

                // New Trail… asks for a name in the prompt.
                h.answers().add("Second");
                h.run("projectMap.newTrail");
            });
            assertNotNull(
                    MapTrails.read(MapTrails.fileFor(h.project(), "Second")).trail());
            FxTestSupport.runOnFx(() -> {
                // The playing trail's ✎ is a command too: the editor shows it.
                h.run("projectMap.trailEditPlaying");
                assertEquals("Tour", h.view().trails.editor().name());
            });
        }
    }

    // --- the filters, the breadcrumb and the zoom bar -----------------------------------------------------------

    @Test
    void theFiltersTheBreadcrumbAndTheZoomBarAreCommands() throws Exception {
        try (Harness h = open()) {
            selectRow(h, h.file(2));
            FxTestSupport.runOnFx(() -> {
                ProjectMapView view = h.view();
                Map<String, String> chips = Map.of(
                        "projectMap.filterOpen", "openFilter",
                        "projectMap.filterUnsaved", "modifiedFilter",
                        "projectMap.filterGitChanged", "gitFilter",
                        "projectMap.filterBookmarks", "bookmarksFilter",
                        "projectMap.filterPersonalNotes", "personalNotesFilter",
                        "projectMap.filterProblems", "problemsFilter");
                for (Map.Entry<String, String> chip : chips.entrySet()) {
                    ToggleButton button = FxTestSupport.field(view, chip.getValue());
                    h.run(chip.getKey());
                    assertTrue(button.isSelected(), chip.getKey() + " turns its chip on");
                    h.run(chip.getKey());
                    assertFalse(button.isSelected(), chip.getKey() + " and off");
                }
                CheckBox hideUnmatched = FxTestSupport.field(view, "hideUnmatched");
                h.run("projectMap.toggleHideUnmatched");
                assertTrue(hideUnmatched.isSelected());
                h.run("projectMap.toggleHideUnmatched");
                assertFalse(hideUnmatched.isSelected());

                // Clear Filters: what Clear does.
                h.run("projectMap.filterGitChanged");
                h.run("projectMap.filterBookmarks");
                h.run("projectMap.clearFilters");
                for (String field : chips.values()) {
                    assertFalse(FxTestSupport.<ToggleButton>field(view, field).isSelected(), field);
                }

                // Filter by Type… opens the selector's list.
                ComboBox<?> type = FxTestSupport.field(view, "typeFilter");
                h.run("projectMap.filterType");
                assertTrue(type.isShowing(), "the Type list is open");
                type.hide();

                // The breadcrumb's places, one pick away: the folder picked is selected.
                h.run("projectMap.breadcrumb");
                Picked crumbs = h.picks().getLast();
                assertEquals(List.of(h.project().getFileName().toString(), "a", "f2.txt"), crumbs.rows());
                assertEquals(2, crumbs.current(), "it starts on the selection");
                crumbs.chosen().accept(1);
            });
            waitFor(() -> h.project().resolve("a").equals(h.surface().selectedPath()), "a/ selected");

            FxTestSupport.runOnFx(() -> {
                String start = h.surface().zoomPercent();
                h.run("projectMap.zoomIn");
                String in = h.surface().zoomPercent();
                assertNotEquals(start, in, "zoomed in");
                h.run("projectMap.zoomOut");
                h.run("projectMap.zoomOut");
                assertNotEquals(in, h.surface().zoomPercent(), "zoomed out");
                h.run("projectMap.resetView");
                assertEquals("100%", h.surface().zoomPercent());
                h.run("projectMap.fitView");
                h.run("projectMap.centerSelection");
                assertTrue(h.said().isEmpty(), "nothing refused: " + h.said());
            });
        }
    }

    @Test
    void theResultsStripsCommandsAreRefusedWhileItShowsNoMatches() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                ProjectMapResultsStrip strip = new ProjectMapResultsStrip(h.view(), () -> "");
                assertFalse(strip.isVisible());
                for (String id : List.of("projectMap.previousMatch", "projectMap.nextMatch", "projectMap.goToMatch")) {
                    h.run(id);
                    assertEquals(unavailable(id), h.last(), id);
                }
            });
        }
    }

    // --- the map's rows -----------------------------------------------------------------------------------------

    @Test
    void theRowCommandsFireTheSelectedRowsMenuRows() throws Exception {
        try (Harness h = open()) {
            selectRow(h, h.file(3));
            FxTestSupport.runOnFx(() -> {
                // Preview: the selected file's card.
                h.run("projectMap.preview");
                assertTrue(h.view().hasFileCard(h.file(3)), "the row's card opened");
                // A plain text file has no structure and no notes: their rows are not on its menu.
                h.run("projectMap.rowStructure");
                assertEquals(notOffered("projectMap.rowStructure"), h.last());
                h.run("projectMap.personalNotes");
                assertEquals(notOffered("projectMap.personalNotes"), h.last());
                h.surface().requestFocus();
            });

            // The column's header buttons, pressed for the selected row's column.
            FxTestSupport.runOnFx(() -> {
                h.surface().requestFocus();
                int hiddenBefore = columnControls(h, true);
                int pinnedBefore = columnControls(h, false);
                h.run("projectMap.columnPin");
                assertEquals(pinnedBefore + 1, columnControls(h, false), "the column locked");
                h.run("projectMap.columnPin");
                assertEquals(pinnedBefore, columnControls(h, false), "and unlocked");
                h.run("projectMap.columnShowHidden");
                assertNotEquals(hiddenBefore, columnControls(h, true), "Show hidden turned over");
                h.surface().requestFocus();
                h.run("projectMap.columnShowHidden");
                assertEquals(hiddenBefore, columnControls(h, true), "and back");
            });
            // The first column has no header buttons, and no Close Column.
            selectRow(h, h.project().resolve("README.md"));
            FxTestSupport.runOnFx(() -> {
                h.run("projectMap.columnShowHidden");
                assertEquals(unavailable("projectMap.columnShowHidden"), h.last());
                h.run("projectMap.columnPin");
                assertEquals(unavailable("projectMap.columnPin"), h.last());
                h.run("projectMap.closeColumn");
                assertEquals(notOffered("projectMap.closeColumn"), h.last());
            });
            // A folder's menu: its metrics and dependencies as cards.
            selectRow(h, h.project().resolve("a"));
            FxTestSupport.runOnFx(() -> {
                h.run("projectMap.metrics");
                assertTrue(onCanvas(h, ProjectMapMetricsCard.class), "the folder's Code Metrics card");
                h.surface().requestFocus();
                h.run("projectMap.dependencies");
                assertTrue(onCanvas(h, ProjectMapDependencyCard.class), "the folder's Dependencies card");
            });
            // Close Column on a row of a/: the column goes.
            selectRow(h, h.file(0));
            FxTestSupport.runOnFx(() -> h.run("projectMap.closeColumn"));
            waitFor(() -> !contains(h.surface(), h.file(0)), "a/ closed");

            // With the keyboard outside the map's rows and cards, a row command finds no row to act on.
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.TextField elsewhere = new javafx.scene.control.TextField();
                ((StackPane) h.stage().getScene().getRoot()).getChildren().add(elsewhere);
                elsewhere.requestFocus();
                h.run("projectMap.preview");
                assertEquals(notOffered("projectMap.preview"), h.last());
                ((StackPane) h.stage().getScene().getRoot()).getChildren().remove(elsewhere);
            });
        }
    }

    /** How many built column headers have their "Show hidden" ({@code hidden}) or their lock on. */
    private static int columnControls(Harness h, boolean hidden) {
        Map<?, ?> controls = FxTestSupport.field(h.surface(), "columnControls");
        int on = 0;
        for (Object made : controls.values()) {
            boolean selected = hidden
                    ? ((CheckBox) FxTestSupport.call(made, "showHidden", new Class<?>[0])).isSelected()
                    : ((ToggleButton) FxTestSupport.call(made, "pin", new Class<?>[0])).isSelected();
            on += selected ? 1 : 0;
        }
        return on;
    }

    // --- a card -------------------------------------------------------------------------------------------------

    @Test
    void theCardCommandsActOnTheCardTheKeyboardIsIn() throws Exception {
        try (Harness h = open()) {
            Path app = h.project().resolve("src").resolve("App.java");
            ProjectMapPreview card = openCard(h, app);
            FxTestSupport.runOnFx(() -> {
                // Copy with nothing selected copies the card's whole text, as the row says.
                Clipboard.getSystemClipboard().clear();
                h.run("projectMap.cardCopy");
                assertEquals(APP, Clipboard.getSystemClipboard().getString());

                // Open: the card's file in the editor.
                card.editor().requestFocus();
                h.run("projectMap.openInEditor");
                assertEquals(List.of(app), h.opened());

                // A file card's menu has no Show in Structure, Open Narrowed or Declaration History.
                card.editor().requestFocus();
                for (String id : List.of(
                        "projectMap.showInStructure", "projectMap.openNarrowed", "projectMap.declarationHistory")) {
                    h.run(id);
                    assertEquals(notOffered(id), h.last(), id);
                }

                // The find bar's buttons: refused while it is closed, pressed while it is open.
                for (String id : List.of(
                        "projectMap.cardFindNext",
                        "projectMap.cardFindPrevious",
                        "projectMap.cardFindMatchCase",
                        "projectMap.cardFindClose")) {
                    h.run(id);
                    assertEquals(unavailable(id), h.last(), id);
                }
                card.showFind();
                card.findField().setText("go");
                h.run("projectMap.cardFindNext");
                assertEquals("2 of 2", card.findCountText().replace(' ', ' '));
                h.run("projectMap.cardFindPrevious");
                assertEquals("1 of 2", card.findCountText().replace(' ', ' '));
                ToggleButton matchCase = FxTestSupport.field(card, "findCase");
                h.run("projectMap.cardFindMatchCase");
                assertTrue(matchCase.isSelected());
                h.run("projectMap.cardFindClose");
                assertFalse(card.findShown());

                // Go to Line's ×: refused while the bar is closed, and closes it while it is open.
                card.editor().requestFocus();
                h.run("projectMap.cardGoToLineClose");
                assertEquals(unavailable("projectMap.cardGoToLineClose"), h.last());
                MapCardMoves moves = FxTestSupport.field(card, "moves");
                moves.showLineBar();
                h.run("projectMap.cardGoToLineClose");
                assertFalse(moves.lineBarShown());

                // No gutter marks on a card of a Map on its own.
                card.editor().requestFocus();
                for (String id : List.of("projectMap.cardToggleBreakpoint", "projectMap.cardRun")) {
                    h.run(id);
                    assertEquals(unavailable(id), h.last(), id);
                }
                // Nothing bound them on this card: its text shown, not its file's facts; no answer with a "+N"
                // chip; no notes stuck on it.
                for (String id :
                        List.of("projectMap.revealInFileManager", "projectMap.allPlaces", "projectMap.allNotes")) {
                    h.run(id);
                    assertEquals(notOffered(id), h.last(), id);
                }

                // The card's menu rows that are cards' commands already show those commands' chords.
                card.editor().requestFocus();
                h.run("projectMap.moveToFreeSpace");
                assertNotEquals(notOffered("projectMap.moveToFreeSpace"), h.last());
            });
            // Pin from the menu: the card's own command, which the row shows.
            FxTestSupport.runOnFx(() -> {
                card.editor().requestFocus();
                boolean pinned = card.pinned();
                MapButtonCommands.Found pin = MapButtonCommands.find(card.editor(), "projectMap.cardPin");
                assertNotNull(pin);
                assertNotNull(pin.action());
                pin.action().getAsBoolean();
                assertEquals(!pinned, card.pinned());
            });
        }
    }

    @Test
    void aDeclarationRowAndACardsStructureColumnFireTheirOwnMenusRows() throws Exception {
        try (Harness h = open()) {
            Path app = h.project().resolve("src").resolve("App.java");
            selectRow(h, app);
            // Show Structure from the row's menu: the file's declarations become rows.
            FxTestSupport.runOnFx(() -> h.run("projectMap.rowStructure"));
            waitFor(() -> declaration(h, app) != null, "App.java's declarations on the map");
            ProjectMapModel.Entry declaration = FxTestSupport.callOnFx(() -> declaration(h, app));
            selectRow(h, declaration.path());
            FxTestSupport.runOnFx(() -> {
                int before = h.view().cards.openCards().size();
                h.run("projectMap.preview");
                assertEquals(before + 1, h.view().cards.openCards().size(), "the declaration's card");
                // Without code navigation (a Map on its own), the declaration's menu has no history row.
                h.surface().requestFocus();
                h.run("projectMap.declarationHistory");
                assertEquals(notOffered("projectMap.declarationHistory"), h.last());
            });

            // A card's Structure column: the selected declaration's menu, as in the map's Structure column.
            ProjectMapPreview card = openCard(h, app);
            FxTestSupport.runOnFx(() -> card.setStructureShown(true));
            waitFor(
                    () -> h.view().cards.cardStructures.get(card) != null
                            && h.view().cards.cardStructures.get(card).tree().getExpandedItemCount() > 0,
                    "the card's Structure column");
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.TreeView<?> tree =
                        h.view().cards.cardStructures.get(card).tree();
                tree.getSelectionModel().clearSelection();
                tree.requestFocus();
                h.run("projectMap.preview");
                assertEquals(notOffered("projectMap.preview"), h.last(), "nothing selected in it: no menu");
                tree.getSelectionModel().select(tree.getExpandedItemCount() - 1); // go(), whose card is not open
                tree.requestFocus();
                int before = h.view().cards.openCards().size();
                int said = h.said().size();
                h.run("projectMap.preview");
                assertEquals(said, h.said().size(), "nothing refused: " + h.said());
                assertTrue(h.view().cards.openCards().size() > before, "the selected declaration's card");
            });
        }
    }

    /** A declaration row of {@code file} on the map, or null. */
    private static ProjectMapModel.@org.jspecify.annotations.Nullable Entry declaration(Harness h, Path file) {
        List<ProjectMapModel.Entry> entries = FxTestSupport.field(h.surface(), "entries");
        return entries.stream()
                .filter(entry -> entry.isSymbol() && entry.path().startsWith(file))
                .findFirst()
                .orElse(null);
    }

    @Test
    void aCardsGutterMarksAndFileFactsAreCommandsOfTheCaretsLine() throws Exception {
        try (Harness h = open()) {
            Path main = Files.writeString(
                    h.project().resolve("src").resolve("Main.java"),
                    "package demo;\n\npublic class Main {\n    public static void main(String[] args) {\n    }\n}\n");
            List<Integer> breakpoints = new CopyOnWriteArrayList<>();
            List<MapCardGutter.Run> runs = new CopyOnWriteArrayList<>();
            ProjectMapPreview card = openCard(h, main);
            FxTestSupport.runOnFx(() -> card.setGutterSource(new MapCardGutter.Source() {
                @Override
                public MapCardGutter.Marks marks(Path file) {
                    return new MapCardGutter.Marks(true, Map.of(), java.util.Set.of(), false, true);
                }

                @Override
                public void toggleBreakpoint(Path file, int line, String text) {
                    breakpoints.add(line);
                }

                @Override
                public void run(Path file, MapCardGutter.Run run) {
                    runs.add(run);
                }
            }));
            waitFor(() -> !card.gutterRuns().isEmpty(), "the ▶ of main");
            FxTestSupport.runOnFx(() -> {
                int mainLine = card.gutterRuns().keySet().iterator().next();
                card.editor().moveTo(0, 0);
                card.editor().requestFocus();
                h.run("projectMap.cardRun");
                assertEquals(unavailable("projectMap.cardRun"), h.last(), "no ▶ on the first line");
                h.run("projectMap.cardToggleBreakpoint");
                assertEquals(List.of(0), breakpoints, "a breakpoint on the caret's line");
                card.editor().moveTo(mainLine, 0);
                h.run("projectMap.cardRun");
                assertEquals(1, runs.size(), "the ▶ of the caret's line ran");
            });

            // A file with no text: the card shows its facts, and its Reveal button is a command.
            Path data = Files.write(h.project().resolve("data.bin"), new byte[] {0, 1, 2, 0, 3, 0, 0, 4});
            ProjectMapPreview facts =
                    FxTestSupport.callOnFx(() -> h.view().cards.openFileCard(data));
            List<Path> revealed = new CopyOnWriteArrayList<>();
            waitFor(() -> MapButtonCommands.find(facts, "projectMap.revealInFileManager") != null, "the card's facts");
            FxTestSupport.runOnFx(() -> {
                facts.setOnReveal(revealed::add);
                facts.requestFocus();
                assertTrue(MapButtonCommands.find(facts, "projectMap.revealInFileManager")
                        .action()
                        .getAsBoolean());
                assertEquals(List.of(data), revealed);
            });
        }
    }

    @Test
    void theResultsStripStepsThroughTheMatchesAndListsThem() throws Exception {
        try (Harness h = open()) {
            List<Path> matches = List.of(h.file(1), h.file(3));
            ProjectMapResultsStrip strip = FxTestSupport.callOnFx(() -> {
                ProjectMapResultsStrip made = new ProjectMapResultsStrip(h.view(), () -> "file");
                ((StackPane) h.stage().getScene().getRoot()).getChildren().add(made);
                h.view().setQuery("file");
                h.view().setSearchMatches("file", matches);
                h.view().setOnResultsChanged(made::refresh);
                made.refresh();
                return made;
            });
            waitFor(() -> strip.isVisible() && !strip.list().getItems().isEmpty(), "the strip's matches");
            FxTestSupport.runOnFx(() -> {
                int first = h.view().resultsCursor();
                h.run("projectMap.nextMatch");
                assertNotEquals(first, h.view().resultsCursor(), "the next match");
                h.run("projectMap.previousMatch");
                assertEquals(first, h.view().resultsCursor(), "and back");
                h.run("projectMap.goToMatch");
                assertTrue(strip.list().isShowing(), "the list of every match is open");
                strip.list().hide();
                ((StackPane) h.stage().getScene().getRoot()).getChildren().remove(strip);
            });
        }
    }

    @Test
    void theProjectPanelGivesTheMapItsPickerAndShowCalleesAsksTheMenuFirst() throws Exception {
        Path root = project();
        ProjectPanel panel = FxTestSupport.callOnFx(() -> {
            ProjectPanel p =
                    DisposedPanels.track(new ProjectPanel(path -> {}, (x, y) -> {}, x -> {}, x -> false, x -> false));
            p.setRoot(root);
            assertFalse(p.mapNavigationCommand("projectMap.showCallees"), "the Map is not open");
            p.openMapWindow(false);
            return p;
        });
        try {
            ProjectMapView view = panel.mapView();
            MapSurface surface = FxTestSupport.field(view, "surface");
            waitFor(() -> contains(surface, root.resolve("README.md")), "the first column");
            FxTestSupport.runOnFx(() -> view.revealPath(root.resolve("README.md")));
            waitFor(() -> root.resolve("README.md").equals(surface.selectedPath()), "README.md selected");
            FxTestSupport.runOnFx(() -> {
                surface.requestFocus();
                // A file row's menu has no Callees row, and no card or declaration has the keyboard: not run.
                assertFalse(panel.mapNavigationCommand("projectMap.showCallees"));
                MapMenuCommands.run(view, "projectMap.breadcrumb");
                assertTrue(panel.mapWindow().overlayHost().isShowing(), "the picker over the Map window");
                panel.mapWindow().overlayHost().hide();
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                Stage stage = panel.mapWindow().stage();
                if (stage != null) {
                    stage.hide();
                }
            });
        }
    }

    @Test
    void theMoreNotesSheetIsACommandOfItsCard() throws Exception {
        try (Harness h = open()) {
            Path file = Files.writeString(h.project().resolve("notes.txt"), "line\n".repeat(60));
            List<com.editora.config.PersonalNote> notes = new java.util.ArrayList<>();
            for (int i = 0; i < ProjectMapStickyNotes.MAX_NOTES + 6; i++) {
                notes.add(new com.editora.config.PersonalNote(
                        java.util.UUID.randomUUID(),
                        null,
                        com.editora.config.NoteScope.LINE,
                        new com.editora.config.TextAnchor(i, 0, i, 0, "", "", ""),
                        "note " + i,
                        List.of(),
                        com.editora.config.NoteStatus.ACTIVE,
                        i,
                        i));
            }
            FxTestSupport.runOnFx(() -> h.view().setNotePreviewActions(new ProjectPanel.MarkerActions() {
                @Override
                public boolean personalNotesEnabled() {
                    return true;
                }

                @Override
                public boolean hasBookmarks(Path path) {
                    return false;
                }

                @Override
                public boolean hasPersonalNotes(Path path) {
                    return path.equals(file);
                }

                @Override
                public void addBookmark(Path path) {}

                @Override
                public void addPersonalNote(Path path) {}

                @Override
                public List<com.editora.config.PersonalNote> personalNotes(Path path) {
                    return path.equals(file) ? notes : List.of();
                }
            }));
            ProjectMapPreview card = openCard(h, file);
            FxTestSupport.runOnFx(() -> {
                h.run("projectMap.allNotes");
                assertEquals(notOffered("projectMap.allNotes"), h.last(), "no notes stuck on it yet");
                card.setNotesShown(true);
            });
            waitFor(() -> MapButtonCommands.find(card, "projectMap.allNotes") != null, "the card's notes");
            FxTestSupport.runOnFx(() -> {
                card.editor().requestFocus();
                h.run("projectMap.allNotes");
                Map<Path, ProjectMapNotePreview> noteCards = FxTestSupport.field(h.view(), "notePreviews");
                assertNotNull(noteCards.get(file.toAbsolutePath().normalize()), "the file's note card lists them all");
                // Hidden by "Hide all open Personal Notes": no sheet to press, so refused.
                h.run("projectMap.toggleHideNotes");
                card.editor().requestFocus();
                h.run("projectMap.allNotes");
                assertEquals(unavailable("projectMap.allNotes"), h.last());
            });
        }
    }

    @Test
    void aMapOnItsOwnRefusesWhatItHasNothingFor() throws Exception {
        try (Harness h = open()) {
            FxTestSupport.runOnFx(() -> {
                // Nothing selected yet: no column to press the header buttons of.
                h.surface().requestFocus();
                if (h.surface().selectedPath() == null) {
                    h.run("projectMap.columnPin");
                    assertEquals(unavailable("projectMap.columnPin"), h.last());
                }
                // No menu factory: the map's rows have no menu to fire a row of.
                h.view().setContextMenuFactory(null);
                h.run("projectMap.preview");
                assertEquals(notOffered("projectMap.preview"), h.last());
                h.view().setPicker(null); // a picker that shows nothing: the commands still run
                h.view().pick("projectMap.breadcrumb", List.of("x"), 0, index -> {});
            });
            // A view in no window and with no folder: the Type list cannot open, and there are no sets to pick.
            FxTestSupport.runOnFx(() -> {
                ProjectMapView loose = new ProjectMapView(path -> {}, path -> false, path -> false);
                List<String> said = new java.util.ArrayList<>();
                loose.setOnStatus(said::add);
                MapMenuCommands.run(loose, "projectMap.filterType");
                ComboBox<?> type = FxTestSupport.field(loose, "typeFilter");
                assertFalse(type.isShowing());
                MapMenuCommands.run(loose, "projectMap.showWorkingSet");
                assertEquals(
                        List.of(tr("status.projectMap.nothingToPick", CommandTips.title("projectMap.showWorkingSet"))),
                        said);
                loose.dispose();
            });
            // A card whose gutter shows no breakpoint strip: Toggle Breakpoint is refused there.
            ProjectMapPreview card = openCard(h, h.file(0));
            FxTestSupport.runOnFx(() -> {
                card.setGutterSource(file -> MapCardGutter.Marks.NONE);
                card.editor().requestFocus();
                h.run("projectMap.cardToggleBreakpoint");
                assertEquals(unavailable("projectMap.cardToggleBreakpoint"), h.last());
            });
        }
    }

    @Test
    void theChangesCardsAgainstOpensItsListOnScreen() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ProjectMapChangesCard changes = new ProjectMapChangesCard();
            Stage stage = new Stage();
            try {
                stage.setScene(new Scene(new StackPane(changes), 700, 500));
                stage.show();
                javafx.scene.control.MenuButton against = FxTestSupport.field(changes, "against");
                assertTrue(run(changes, "projectMap.changesAgainst"));
                assertTrue(against.isShowing(), "Against's list is open");
                against.hide();
                against.setDisable(true);
                assertFalse(run(changes, "projectMap.changesAgainst"), "disabled: refused");
            } finally {
                stage.hide();
            }
        });
    }

    // --- the bars and menus of the other cards ------------------------------------------------------------------

    @Test
    void theOtherCardsBarsAreCommandsThatPressTheirButtons() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ProjectMapDependencyCard deps = new ProjectMapDependencyCard();
            int[] refreshed = {0};
            deps.setOnRefresh(() -> refreshed[0]++);
            assertTrue(run(deps, "projectMap.depsInside"));
            assertTrue(FxTestSupport.<ToggleButton>field(deps, "insideButton").isSelected());
            assertTrue(run(deps, "projectMap.depsAround"));
            assertTrue(FxTestSupport.<ToggleButton>field(deps, "aroundButton").isSelected());
            assertTrue(run(deps, "projectMap.cardFolders"));
            assertTrue(FxTestSupport.<ToggleButton>field(deps, "foldersButton").isSelected());
            assertTrue(run(deps, "projectMap.cardFiles"));
            assertTrue(FxTestSupport.<ToggleButton>field(deps, "filesButton").isSelected());
            assertTrue(run(deps, "projectMap.cardRefresh"));
            assertEquals(1, refreshed[0]);
            assertFalse(run(deps, "projectMap.cardBack"), "nowhere to go back to: refused, as the button is disabled");

            ProjectMapMetricsCard metrics = new ProjectMapMetricsCard();
            metrics.setOnRefresh(() -> refreshed[0]++);
            assertTrue(run(metrics, "projectMap.cardParts"));
            assertTrue(FxTestSupport.<ToggleButton>field(metrics, "partsButton").isSelected());
            assertTrue(run(metrics, "projectMap.cardFiles"));
            assertTrue(FxTestSupport.<ToggleButton>field(metrics, "filesButton").isSelected());
            assertTrue(run(metrics, "projectMap.cardRefresh"));
            assertEquals(2, refreshed[0]);
            assertFalse(run(metrics, "projectMap.cardBack"));
            // No row selected in its table: the table's row commands find no menu.
            assertNull(MapButtonCommands.find(metrics.table(), "projectMap.showOnMap"));

            ProjectMapSymbolGraphCard graph = new ProjectMapSymbolGraphCard();
            assertFalse(run(graph, "projectMap.cardBack"));

            ProjectMapHistoryCard history = new ProjectMapHistoryCard();
            assertTrue(run(history, "projectMap.historyBlame"));
            assertTrue(FxTestSupport.<ToggleButton>field(history, "blameButton").isSelected());
            assertTrue(run(history, "projectMap.historyChanges"));
            assertTrue(
                    FxTestSupport.<ToggleButton>field(history, "changesButton").isSelected());

            ProjectMapChangesCard changes = new ProjectMapChangesCard();
            changes.setOnRefresh(() -> refreshed[0]++);
            assertTrue(run(changes, "projectMap.cardRefresh"));
            assertEquals(3, refreshed[0]);
            assertFalse(run(changes, "projectMap.changesNext"), "no change to step to");
            assertFalse(run(changes, "projectMap.changesPrevious"));
            assertFalse(run(changes, "projectMap.changesAgainst"), "not on screen: its list cannot open");
        });
    }

    @Test
    void aPlacesMenuOnACardIsACommandOfThePlaceTheKeyboardIsOn() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ProjectMapDependencyCard deps = new ProjectMapDependencyCard();
            List<Path> revealed = new CopyOnWriteArrayList<>();
            List<Path> openedCards = new CopyOnWriteArrayList<>();
            deps.setOnReveal(revealed::add);
            deps.setOnOpen((path, line) -> openedCards.add(path));
            javafx.scene.control.Button file = new javafx.scene.control.Button("x");
            file.setContextMenu((ContextMenu) FxTestSupport.call(
                    deps, "placeMenu", new Class<?>[] {String.class, boolean.class}, "src/App.java", false));
            MapButtonCommands.ownMenu(file);
            assertNull(MapButtonCommands.find(file, "projectMap.lookInside"), "a file has no inside");
            assertTrue(MapButtonCommands.find(file, "projectMap.showOnMap")
                    .action()
                    .getAsBoolean());
            assertTrue(
                    MapButtonCommands.find(file, "projectMap.openCard").action().getAsBoolean());
            assertEquals(1, revealed.size());
            assertEquals(1, openedCards.size());
            assertTrue(revealed.getFirst().endsWith(Path.of("src", "App.java")), revealed.toString());
        });
    }

    // --- the plumbing ---------------------------------------------------------------------------------------------

    @Test
    void aRowIsFoundByItsCommandAndChosenAsAClickChoosesIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            // row(): the chord of the command, unless the row shows another command's, which it keeps.
            MenuItem plain = MapButtonCommands.row(new MenuItem("plain"), "projectMap.preview");
            assertEquals("projectMap.preview", MenuChords.commandOf(plain));
            MenuItem tagged =
                    MapButtonCommands.row(MenuChords.tag(new MenuItem("t"), "edit.copy"), "projectMap.cardCopy");
            assertEquals("edit.copy", MenuChords.commandOf(tagged));
            MenuItem quiet = MapButtonCommands.also(new MenuItem("q"), "projectMap.showCallees");
            assertNull(MenuChords.commandOf(quiet), "no chord shown");

            CheckMenuItem check = MapButtonCommands.row(new CheckMenuItem("check"), "projectMap.toggleCompactRows");
            javafx.scene.control.RadioMenuItem radio = MapButtonCommands.row(
                    new javafx.scene.control.RadioMenuItem("radio"), "projectMap.flowTopToBottom");
            javafx.scene.control.ToggleGroup group = new javafx.scene.control.ToggleGroup();
            radio.setToggleGroup(group);
            MenuItem hidden = MapButtonCommands.row(new MenuItem("hidden"), "projectMap.metrics");
            hidden.setVisible(false);
            MenuItem off = MapButtonCommands.row(new MenuItem("off"), "projectMap.changes");
            off.setDisable(true);
            Menu sub = new Menu("sub");
            MenuItem inside = MapButtonCommands.row(new MenuItem("inside"), "projectMap.dependencies");
            sub.getItems().add(inside);
            sub.setDisable(true);
            int[] fired = {0};
            for (MenuItem item : List.of(plain, tagged, quiet, check, radio, inside)) {
                item.setOnAction(event -> fired[0]++);
            }
            List<MenuItem> items = List.of(plain, tagged, quiet, check, radio, hidden, off, sub);

            assertTrue(MapButtonCommands.rowIn(items, "projectMap.cardCopy", false)
                    .action()
                    .getAsBoolean());
            assertTrue(MapButtonCommands.rowIn(items, "projectMap.showCallees", false)
                    .action()
                    .getAsBoolean());
            assertTrue(MapButtonCommands.rowIn(items, "projectMap.toggleCompactRows", false)
                    .action()
                    .getAsBoolean());
            assertTrue(check.isSelected(), "a check row turns over");
            assertTrue(MapButtonCommands.rowIn(items, "projectMap.flowTopToBottom", false)
                    .action()
                    .getAsBoolean());
            assertTrue(radio.isSelected(), "a radio row is chosen");
            assertEquals(4, fired[0], "and each fired");
            assertNull(
                    MapButtonCommands.rowIn(items, "projectMap.metrics", false).action(), "a hidden row");
            assertNull(
                    MapButtonCommands.rowIn(items, "projectMap.changes", false).action(), "a disabled row");
            assertNull(
                    MapButtonCommands.rowIn(items, "projectMap.dependencies", false)
                            .action(),
                    "under a disabled menu");
            assertNull(MapButtonCommands.rowIn(items, "projectMap.annotate", false), "no such row");

            // find(): a binding first, then the menu, a column passing on to its card; bindIf may decline.
            StackPane card = new StackPane();
            StackPane column = new StackPane();
            Node inColumn = new StackPane();
            column.getChildren().add(inColumn);
            MapButtonCommands.stuckOn(column, card);
            MapButtonCommands.menu(card, () -> items);
            MapButtonCommands.bindIf(card, "projectMap.cardRefresh", () -> false);
            MapButtonCommands.menu(column, () -> null); // nothing selected in it: no menu
            assertNotNull(MapButtonCommands.find(inColumn, "projectMap.preview"), "the card's menu row");
            assertFalse(MapButtonCommands.find(inColumn, "projectMap.cardRefresh")
                    .action()
                    .getAsBoolean());
            assertNull(MapButtonCommands.actionAt(inColumn, "projectMap.changes"), "disabled: nothing to run");
            assertNull(MapButtonCommands.find(null, "projectMap.preview"));
            assertNull(MapButtonCommands.find(
                    new StackPane(new StackPane()).getChildren().getFirst(), "x.y"));
            assertNull(MapButtonCommands.rowIn(List.of("not a row", plain), "projectMap.cardFiles", false));

            // press(): refused while its button is hidden or disabled, which a click could not press either.
            javafx.scene.control.Button button = new javafx.scene.control.Button("b");
            int[] pressed = {0};
            button.setOnAction(event -> pressed[0]++);
            StackPane owner = new StackPane();
            MapButtonCommands.press(owner, "projectMap.cardRefresh", button);
            button.setVisible(false);
            assertFalse(MapButtonCommands.find(owner, "projectMap.cardRefresh")
                    .action()
                    .getAsBoolean());
            button.setVisible(true);
            button.setDisable(true);
            assertFalse(MapButtonCommands.find(owner, "projectMap.cardRefresh")
                    .action()
                    .getAsBoolean());
            button.setDisable(false);
            assertTrue(MapButtonCommands.find(owner, "projectMap.cardRefresh")
                    .action()
                    .getAsBoolean());
            assertEquals(1, pressed[0]);
            // runs(): the button and the command, one action.
            javafx.scene.control.Button words = new javafx.scene.control.Button("Open");
            MapButtonCommands.runs(owner, words, "projectMap.openInEditor", () -> pressed[0]++);
            words.fire();
            assertTrue(MapButtonCommands.find(owner, "projectMap.openInEditor")
                    .action()
                    .getAsBoolean());
            assertEquals(3, pressed[0]);
            assertEquals("Open", words.getText(), "it keeps its words");
            // ownMenu(): a control with no menu offers none.
            javafx.scene.control.Button bare = new javafx.scene.control.Button("bare");
            MapButtonCommands.ownMenu(bare);
            assertNull(MapButtonCommands.find(bare, "projectMap.showOnMap"));
            // A radio row with no group turns over as a check row does.
            javafx.scene.control.RadioMenuItem loose = new javafx.scene.control.RadioMenuItem("loose");
            MapButtonCommands.choose(loose);
            assertTrue(loose.isSelected());
        });
    }

    @Test
    void theMenuRowCommandsOfALinkKindAndAnEditorRowAreOnePerRow() {
        for (MapCardLinks.Kind kind : MapCardLinks.Kind.values()) {
            String id = ProjectMapPreview.linkRowCommand(kind);
            assertTrue(MapMenuCommands.IDS.contains(id) || MapCardLinks.CALLEES_COMMAND.equals(id), kind + ": " + id);
        }
        assertEquals("projectMap.showCallHierarchy", ProjectMapPreview.hierarchyRowCommand(MapCardLinks.Kind.CALLERS));
        assertEquals("projectMap.showCallHierarchy", ProjectMapPreview.hierarchyRowCommand(MapCardLinks.Kind.CALLEES));
        assertEquals("projectMap.showTypeHierarchy", ProjectMapPreview.hierarchyRowCommand(MapCardLinks.Kind.SUBTYPES));
        List<String> editor = ProjectMapPreview.FOLLOW_COMMANDS.stream()
                .map(ProjectMapPreview::editorRowCommand)
                .toList();
        assertEquals(
                List.of(
                        "projectMap.editorDefinition",
                        "projectMap.editorReferences",
                        "projectMap.editorImplementation",
                        "projectMap.editorTypeHierarchy",
                        "projectMap.editorCallHierarchy"),
                editor);
        assertTrue(MapMenuCommands.IDS.containsAll(editor));
    }

    private static boolean run(Node owner, String id) {
        MapButtonCommands.Found found = MapButtonCommands.find(owner, id);
        assertNotNull(found, id + " is bound on " + owner.getClass().getSimpleName());
        return found.action() != null && found.action().getAsBoolean();
    }
}
