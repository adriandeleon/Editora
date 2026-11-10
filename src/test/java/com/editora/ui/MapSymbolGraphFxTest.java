package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Pane;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Project Map's Symbol Graph card (Map code browser round 2): a declaration with its callers, callees, supertypes
 * and subtypes around it, walked with Enter and Back.
 */
@Tag("fx")
class MapSymbolGraphFxTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A question the card asked, and where its answer goes. */
    private record Asked(MapCardLinks.Request request, Consumer<MapHierarchyTree.Level> answer) {}

    private static MapCardLinks.Group group(Path file, String label, int line, int count) {
        return new MapCardLinks.Group(file, null, label, line, count, label.replace("()", ""));
    }

    private static MapHierarchyTree.Level level(MapCardLinks.Group... groups) {
        return new MapHierarchyTree.Level(List.of(groups), MapCardLinks.Via.SERVER, null, false, null);
    }

    @Test
    void theFourSidesAreAskedDrawnAndWalked() throws Exception {
        Path file = temp.resolve("Shapes.java");
        List<Asked> asked = new ArrayList<>();
        List<String> opened = new ArrayList<>();
        Stage[] stage = new Stage[1];
        ProjectMapSymbolGraphCard card = FxTestSupport.callOnFx(() -> {
            ProjectMapSymbolGraphCard made = new ProjectMapSymbolGraphCard();
            made.setAsker((request, answer) -> asked.add(new Asked(request, answer)));
            made.setOpener((f, line, name) -> opened.add(f.getFileName() + ":" + line + ":" + name));
            Pane host = new Pane(made);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(host, 1000, 800));
            stage[0].show();
            made.place(new ProjectMapPreview.Placement(10, 10, 760, 560));
            return made;
        });
        try {
            FxTestSupport.runOnFx(() -> {
                assertNull(card.subject());
                card.show(new ProjectMapSymbolGraphCard.Subject(file, 3, 6, null, null));
                assertEquals(
                        List.of(
                                MapCardLinks.Kind.CALLERS,
                                MapCardLinks.Kind.CALLEES,
                                MapCardLinks.Kind.SUPERTYPES,
                                MapCardLinks.Kind.SUBTYPES),
                        asked.stream().map(a -> a.request().kind()).toList());
                assertEquals(3, asked.get(0).request().line());
                assertEquals(6, asked.get(0).request().column());
                assertTrue(card.summaryText().endsWith(tr("project.map.graph.asking")), card.summaryText());

                // Callers: one of them is area() itself (a recursive call); the name comes with the answer.
                asked.get(0)
                        .answer()
                        .accept(new MapHierarchyTree.Level(
                                List.of(group(file, "total()", 20, 2), group(file, "area()", 3, 1)),
                                MapCardLinks.Via.SERVER,
                                null,
                                false,
                                "area"));
                asked.get(1).answer().accept(level(group(file, "width()", 30, 1)));
                asked.get(2).answer().accept(level());
                asked.get(3).answer().accept(level());
                assertEquals("area", card.subject().name());
                assertEquals(tr("project.map.graph.title", "area"), card.title());
                assertEquals(List.of("total()", "area()"), card.drawn(ProjectMapSymbolGraphCard.Side.CALLERS));
                assertEquals(List.of("width()"), card.drawn(ProjectMapSymbolGraphCard.Side.CALLEES));
                assertEquals(List.of(), card.drawn(ProjectMapSymbolGraphCard.Side.SUPERTYPES), "a method: no types");
                assertTrue(card.isSelf(ProjectMapSymbolGraphCard.Side.CALLERS, 1));
                assertFalse(card.isSelf(ProjectMapSymbolGraphCard.Side.CALLERS, 0));
                assertTrue(card.summaryText().endsWith(tr("project.map.graph.counts", 2, 1, 0, 0)), card.summaryText());
                card.applyCss();
                card.layout();

                // A press says where it is; Enter walks onto it; its old answers no longer count.
                card.press(ProjectMapSymbolGraphCard.Side.CALLEES, 0);
                assertEquals(MapHierarchyTree.rowText(group(file, "width()", 30, 1)), card.detailText());
                card.enter(ProjectMapSymbolGraphCard.Side.CALLERS, 1); // itself: no walk
                assertEquals(4, asked.size());
                card.enter(ProjectMapSymbolGraphCard.Side.CALLEES, 0);
                assertEquals(8, asked.size());
                assertEquals(30, card.subject().line());
                assertEquals("width", card.subject().name());
                asked.get(0).answer().accept(level(group(file, "stale()", 1, 1)));
                assertTrue(card.drawn(ProjectMapSymbolGraphCard.Side.CALLERS).isEmpty(), "the old question's answer");

                // Back: area() again, asked afresh.
                Event.fireEvent(
                        card,
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.BACK_SPACE, false, false, false, false));
                assertEquals(3, card.subject().line());
                assertEquals(12, asked.size());
                assertFalse(card.goBack(), "nothing before area()");

                // Opening: the middle, and a declaration's menu.
                asked.get(8).answer().accept(level(group(file, "total()", 20, 2), group(file, "area()", 3, 1)));
                card.pressMiddle();
                assertEquals(List.of("Shapes.java:3:area"), opened);
                List<MenuItem> self =
                        card.menuOf(ProjectMapSymbolGraphCard.Side.CALLERS, 1).getItems();
                assertTrue(self.get(0).isDisable(), "no graph of itself again");
                self.get(1).fire();
                assertEquals("Shapes.java:3:area", opened.get(1));
                card.menuOf(ProjectMapSymbolGraphCard.Side.CALLERS, 0)
                        .getItems()
                        .get(0)
                        .fire();
                assertEquals(20, card.subject().line());
                card.focusContent();

                // A double-click walks too; a partial answer says so.
                asked.get(13)
                        .answer()
                        .accept(new MapHierarchyTree.Level(
                                List.of(group(file, "far()", 40, 1)), MapCardLinks.Via.SERVER, null, true, null));
                card.applyCss();
                card.layout();
                card.doubleClick(ProjectMapSymbolGraphCard.Side.CALLEES, 0);
                assertEquals(40, card.subject().line());
                assertTrue(card.goBack());
                assertEquals(20, card.subject().line());
                card.show(card.subject()); // the same place: nothing new on the Back list
                assertTrue(card.goBack());
                assertEquals(3, card.subject().line());
            });
        } finally {
            FxTestSupport.runOnFx(() -> stage[0].close());
        }
    }

    @Test
    void typesManyAndNothingAreSaid() throws Exception {
        Path file = temp.resolve("Shape.java");
        List<Asked> asked = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            ProjectMapSymbolGraphCard card = new ProjectMapSymbolGraphCard();
            card.setAsker((request, answer) -> asked.add(new Asked(request, answer)));
            Stage stage = new Stage();
            stage.setScene(new Scene(new Pane(card), 900, 700));
            stage.show();
            card.place(new ProjectMapPreview.Placement(0, 0, 760, 600));
            card.show(new ProjectMapSymbolGraphCard.Subject(file, 0, 6, "Shape", null));
            List<MapCardLinks.Group> many = new ArrayList<>();
            for (int i = 0; i < ProjectMapSymbolGraphCard.MAX_NODES + 4; i++) {
                many.add(group(file, "Sub" + i, 100 + i, 1));
            }
            asked.get(0).answer().accept(level());
            asked.get(1).answer().accept(level());
            asked.get(2).answer().accept(level(group(file, "Base", 50, 1)));
            asked.get(3).answer().accept(new MapHierarchyTree.Level(many, MapCardLinks.Via.SERVER, null, true, null));
            assertEquals(List.of("Base"), card.drawn(ProjectMapSymbolGraphCard.Side.SUPERTYPES));
            assertEquals(
                    ProjectMapSymbolGraphCard.MAX_NODES,
                    card.drawn(ProjectMapSymbolGraphCard.Side.SUBTYPES).size());
            card.applyCss();
            card.layout();

            // Nothing at all: the server's note says why.
            card.show(new ProjectMapSymbolGraphCard.Subject(file, 9, 0, null, null));
            MapHierarchyTree.Level none =
                    new MapHierarchyTree.Level(List.of(), MapCardLinks.Via.NAME, "No server ran.", false, null);
            for (int i = 4; i < 8; i++) {
                asked.get(i).answer().accept(none);
            }
            assertTrue(card.summaryText().endsWith("No server ran."), card.summaryText());
            assertEquals(tr("project.map.graph.title", tr("project.map.graph.at", "Shape.java", 10)), card.title());
            card.show(new ProjectMapSymbolGraphCard.Subject(file, 11, 0, "x", null));
            MapHierarchyTree.Level empty = level();
            for (int i = 8; i < 12; i++) {
                asked.get(i).answer().accept(empty);
            }
            assertTrue(card.summaryText().endsWith(tr("project.map.graph.none")), card.summaryText());
            card.applyCss();
            card.layout();
            stage.close();
        });
        assertNull(
                ProjectMapSymbolGraphCard.subjectOf(
                        new MapCardLinks.Group(file, null, "use", 3, 1, null),
                        ProjectMapSymbolGraphCard.Side.CALLERS,
                        MapCardLinks.Via.NAME),
                "a place found by name is no declaration to ask about");
    }

    // --- through the Map --------------------------------------------------------------------------------------

    /** A source whose answers the test gives, in the order the questions came. */
    private static final class Held implements MapCardLinks.Source {
        final List<MapCardLinks.Request> asked = new CopyOnWriteArrayList<>();
        final List<Consumer<MapCardLinks.Answer>> pending = new CopyOnWriteArrayList<>();

        @Override
        public void find(Path root, MapCardLinks.Request request, Consumer<MapCardLinks.Answer> answer) {
            asked.add(request);
            pending.add(answer);
        }
    }

    @Test
    void aCardsAndARowsMenuOpenTheGraphBesideIt() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path app = Files.writeString(
                Files.createDirectories(project.resolve("src")).resolve("App.java"),
                "class App {\n    void go() {\n        stop();\n    }\n\n    void stop() {}\n}\n");
        Held source = new Held();
        Stage[] stage = new Stage[1];
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new StackPane(made), 1300, 860));
            stage[0].show();
            made.setRoot(project);
            made.setLinkSource(source);
            return made;
        });
        try {
            Region surface = FxTestSupport.callOnFx(() -> FxTestSupport.field(view, "surface"));
            waitFor(() -> (boolean)
                    FxTestSupport.call(surface, "contains", new Class<?>[] {Path.class}, project.resolve("src")));
            ProjectMapPreview card = FxTestSupport.callOnFx(() -> view.cards.openFileCard(app));
            waitFor(() -> card.editor().getLength() > 0 && card.getWidth() > 0);
            FxTestSupport.runOnFx(() -> {
                @SuppressWarnings("unchecked")
                List<MenuItem> items = (List<MenuItem>) FxTestSupport.call(card, "linkMenuItems", new Class<?>[0]);
                MenuItem graph = items.stream()
                        .filter(item -> tr("project.map.graph.menu").equals(item.getText()))
                        .findFirst()
                        .orElseThrow();
                FxTestSupport.call(card, "moveCaretTo", new Class<?>[] {int.class, int.class}, 1, 10);
                view.cards.showSymbolGraph(card, 1, 10, true);
                assertNotNull(graph);
            });
            waitFor(() -> source.asked.size() == 4);
            ProjectMapSymbolGraphCard graph = FxTestSupport.callOnFx(() -> view.cards.symbolGraphCard());
            FxTestSupport.runOnFx(() -> {
                assertNotNull(graph);
                assertTrue(graph.isVisible());
                assertEquals(app, graph.subject().file());
                assertEquals(1, source.asked.get(0).line());
                assertTrue(view.cards.openCards().stream().anyMatch(open -> open.node() == graph));
                // The callees: stop() at line 5, found by the server.
                source.pending
                        .get(1)
                        .accept(new MapCardLinks.Answer(
                                List.of(new MapCardLinks.Hit(app, 5, 9, 1, "stop")),
                                MapCardLinks.Via.SERVER,
                                false,
                                null,
                                "go"));
                for (int i : new int[] {0, 2, 3}) {
                    source.pending
                            .get(i)
                            .accept(new MapCardLinks.Answer(List.of(), MapCardLinks.Via.SERVER, false, null, "go"));
                }
            });
            waitFor(() -> !graph.drawn(ProjectMapSymbolGraphCard.Side.CALLEES).isEmpty());
            FxTestSupport.runOnFx(() -> {
                assertSame(graph, view.cards.symbolGraphCard());
                // Asked again from the card: the same graph moves.
                view.cards.showSymbolGraph(card, 5, 10, true);
                assertSame(graph, view.cards.symbolGraphCard());
                assertEquals(5, graph.subject().line());
                assertTrue(graph.goBack());
                assertNotNull(ProjectMapView.switcherIcon(new MapCards.OpenCard(app, graph, "g", false)));
                graph.pressMiddle(); // go()'s card
                Event.fireEvent(
                        graph, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
                assertNull(view.cards.symbolGraphCard(), "Escape closes it");
                view.cards.showSymbolGraph(card, 1, 10, true);
                ProjectMapSymbolGraphCard again = view.cards.symbolGraphCard();
                assertNotNull(again);
                view.cards.close(again); // as the Map's Close does
                assertNull(view.cards.symbolGraphCard());
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage[0].close();
            });
        }
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNotNull(null, "timed out");
    }
}
