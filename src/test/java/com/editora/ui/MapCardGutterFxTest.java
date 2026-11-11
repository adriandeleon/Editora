package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.run.MainMethodScanner;
import com.editora.test.JavaTestScanner;
import com.editora.ui.ProjectMapPreview.Content;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map gaps round 3, item 1: a card's gutter shows what the editor's gutter shows beside its line numbers —
 * breakpoints (a click sets or removes one), bookmarks, a ▶ for a JUnit test or a {@code main} method, and the Git
 * change bars — each column only when it has something to show, a declaration card counting from its first line.
 */
@Tag("fx")
class MapCardGutterFxTest {

    private static final String TEXT = String.join(
            "\n",
            "package demo;", // 0
            "import org.junit.jupiter.api.Test;", // 1
            "class AppTest {", // 2
            "    @Test", // 3
            "    void adds() {", // 4
            "        int x = 1;", // 5
            "    }", // 6
            "    public static void main(String[] args) {}", // 7
            "}", // 8
            "");

    @TempDir
    Path dir;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A source whose answers the test sets, recording what the card asks of it. */
    private static final class Stub implements MapCardGutter.Source {
        MapCardGutter.Marks marks = MapCardGutter.Marks.NONE;
        MapCardGutter.@Nullable Changes changes;
        final List<String> calls = new ArrayList<>();
        /** Answers held back, to be given in the test's own order (null: answered at once). */
        @Nullable
        List<Consumer<MapCardGutter.@Nullable Changes>> held;

        @Override
        public MapCardGutter.Marks marks(Path file) {
            return marks;
        }

        @Override
        public void changes(Path file, Consumer<MapCardGutter.@Nullable Changes> found) {
            calls.add("changes " + file.getFileName());
            if (held != null) {
                held.add(found);
            } else {
                found.accept(changes);
            }
        }

        @Override
        public void toggleBreakpoint(Path file, int line, String text) {
            calls.add("toggle " + file.getFileName() + ":" + line + (text.equals(TEXT) ? "" : " (other text)"));
        }

        @Override
        public void run(Path file, MapCardGutter.Run run) {
            calls.add(
                    "run " + (run.main() != null ? run.main().fqn() : run.test().methodName()));
        }
    }

    @Test
    void theRunLinesAreTheTestsAndTheMainsAsTheEditorFindsThem() throws Exception {
        Map<Integer, MapCardGutter.Run> runs = MapCardGutter.runs(TEXT, true, true);
        Set<Integer> expected = new java.util.HashSet<>();
        JavaTestScanner.scan(TEXT).forEach(t -> expected.add(t.line()));
        MainMethodScanner.scan(TEXT).forEach(m -> expected.add(m.line()));
        assertEquals(expected, runs.keySet());
        assertTrue(runs.containsKey(7), "main");
        assertEquals("demo.AppTest", runs.get(7).main().fqn());
        assertEquals(Map.of(), MapCardGutter.runs(TEXT, false, false));
        assertEquals(Set.of(7), MapCardGutter.runs(TEXT, false, true).keySet(), "mains only");

        FxTestSupport.runOnFx(() -> {
            MapCardGutter.Run main = runs.get(7);
            assertEquals(tr("editmenu.runMainTooltip", "AppTest"), main.tooltip());
            MapCardGutter.Run method = runs.values().stream()
                    .filter(r -> r.test() != null && r.test().methodName() != null)
                    .findFirst()
                    .orElseThrow();
            assertEquals(tr("testrunner.gutter.runMethod", "adds"), method.tooltip());
            MapCardGutter.Run type = runs.values().stream()
                    .filter(r -> r.test() != null && r.test().methodName() == null)
                    .findFirst()
                    .orElseThrow();
            assertTrue(type.tooltip().contains("AppTest"));
            assertEquals("", new MapCardGutter.Run(null, null).tooltip());
            assertEquals("", MapCardGutter.numberOf(new Pane()));
            assertEquals("12", MapCardGutter.numberOf(new Label(" 12")));
            assertEquals("", MapCardGutter.numberOf(new HBox(new Label("x"))), "a label that is not the number");
        });
    }

    @Test
    void aCardsGutterShowsTheMarksAndItsClicksSetBreakpointsAndRun() throws Exception {
        Path file = java.nio.file.Files.writeString(dir.resolve("AppTest.java"), TEXT);
        Stub stub = new Stub();
        stub.marks = new MapCardGutter.Marks(
                true,
                Map.of(
                        5,
                        new MapCardGutter.Breakpoint("conditional", "x > 0"),
                        8,
                        new MapCardGutter.Breakpoint(null, "")),
                Set.of(2),
                true,
                true);
        stub.changes = new MapCardGutter.Changes(
                Map.of(5, "git-modified", 7, "git-deleted"), Map.of(5, "@@ -6 +6 @@\n-int x;\n+int x = 1;"));
        ProjectMapPreview[] card = new ProjectMapPreview[1];
        Node[] late = new Node[2];
        Stage[] stage = new Stage[1];
        try {
            FxTestSupport.runOnFx(() -> {
                card[0] = new ProjectMapPreview(ignored -> {});
                card[0].setGutterSource(stub);
                Pane host = new Pane(card[0]);
                stage[0] = new Stage();
                stage[0].setScene(new Scene(host, 900, 700));
                stage[0].show();
                card[0].showFile(
                        file,
                        new Content(TEXT, false),
                        (w, h, pw, ph) -> new ProjectMapPreview.Placement(0, 0, 600, 400));
            });
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview c = card[0];
                assertEquals(TEXT, c.editor().getText());
                assertEquals(stub.marks, c.gutterMarks());
                assertEquals(stub.changes, c.gutterChanges());
                assertTrue(stub.calls.contains("changes AppTest.java"));
                assertEquals(
                        MapCardGutter.runs(TEXT, true, true).keySet(),
                        c.gutterRuns().keySet());

                HBox row = row(c, 5);
                assertTrue(row.getStyleClass().contains("fold-gutter"), "it looks like the editor's gutter");
                assertEquals("6", MapCardGutter.numberOf(row));
                // breakpoint strip, bookmark column, ▶ column, number, bar
                assertEquals(5, row.getChildren().size());
                StackPane strip = (StackPane) row.getChildren().getFirst();
                assertEquals(tr("project.map.gutter.breakpoint", 6), strip.getAccessibleText());
                assertEquals(1, strip.getChildren().size(), "the breakpoint's glyph");
                assertTrue(row.getChildren().getLast().getStyleClass().contains("git-modified"));
                assertEquals(
                        MapCardGutter.BAR_HIT_WIDTH,
                        ((StackPane) row.getChildren().getLast()).getPrefWidth());

                HBox bookmarked = row(c, 2);
                assertTrue(bookmarked.getChildren().get(1) instanceof StackPane, "the bookmark");
                assertFalse(row(c, 3).getChildren().get(1) instanceof StackPane, "an empty bookmark slot");
                StackPane deleted = (StackPane) row(c, 7).getChildren().getLast();
                assertTrue(deleted.getStyleClass().contains("git-deleted"));
                assertEquals(1, deleted.getChildren().size(), "the wedge of lines removed above");
                assertEquals(
                        0,
                        ((StackPane) row(c, 0).getChildren().getFirst())
                                .getChildren()
                                .size());

                // A click in the strip, on a line with no breakpoint: the source sets one there.
                StackPane empty = (StackPane) row(c, 6).getChildren().getFirst();
                click(empty, MouseButton.PRIMARY);
                click(empty, MouseButton.SECONDARY);
                assertTrue(stub.calls.contains("toggle AppTest.java:6"));
                assertEquals(
                        1,
                        stub.calls.stream().filter(s -> s.startsWith("toggle")).count(),
                        "the left button only");

                // The ▶ of main runs it.
                StackPane runSlot = (StackPane) row(c, 7).getChildren().get(2);
                Node glyph = runSlot.getChildren().getFirst();
                assertTrue(glyph instanceof Group);
                click(glyph, MouseButton.PRIMARY);
                click(glyph, MouseButton.SECONDARY);
                assertTrue(stub.calls.contains("run demo.AppTest"));
                assertEquals(
                        1, stub.calls.stream().filter(s -> s.startsWith("run")).count());
                MouseEvent press = mouse(MouseEvent.MOUSE_PRESSED, MouseButton.PRIMARY);
                javafx.event.Event.fireEvent(empty, press);

                // A declaration card numbers and marks the file's lines from its first.
                c.showRange(
                        file,
                        new ProjectMapPreview.Range("adds()", 4, 3, 6),
                        new Content(TEXT, false),
                        (w, h, pw, ph) -> new ProjectMapPreview.Placement(0, 0, 600, 400));
            });
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview c = card[0];
                assertEquals("4", MapCardGutter.numberOf(row(c, 0)));
                HBox marked = row(c, 2);
                assertEquals("6", MapCardGutter.numberOf(marked));
                assertEquals(
                        1,
                        ((StackPane) marked.getChildren().getFirst())
                                .getChildren()
                                .size());
                click((StackPane) row(c, 3).getChildren().getFirst(), MouseButton.PRIMARY);
                assertEquals(
                        2,
                        stub.calls.stream()
                                .filter("toggle AppTest.java:6"::equals)
                                .count(),
                        "the file's line, not the card's");

                // A later ask for the bars wins: an answer to an earlier one is dropped.
                stub.held = new ArrayList<>();
                c.refreshGutter();
                c.refreshGutter();
                MapCardGutter.Changes before = c.gutterChanges();
                stub.held.getFirst().accept(null);
                assertEquals(before, c.gutterChanges(), "an old answer");
                stub.held.getLast().accept(null);
                assertNull(c.gutterChanges());
                stub.held = null;
                c.refreshGutter();

                // The pointer on a shown strip: a press is the strip's, and gives the card focus; a move is not.
                c.editor().applyCss();
                c.editor().layout();
                Node shown = c.editor().lookup(".breakpoint-slot");
                assertTrue(shown != null, "a strip on screen");
                for (var type : List.of(
                        MouseEvent.MOUSE_PRESSED,
                        MouseEvent.MOUSE_DRAGGED,
                        MouseEvent.MOUSE_RELEASED,
                        MouseEvent.DRAG_DETECTED,
                        MouseEvent.MOUSE_MOVED)) {
                    MouseEvent event = mouse(type, MouseButton.PRIMARY);
                    javafx.event.Event.fireEvent(shown, event);
                }
                late[0] = (StackPane) row(c, 4).getChildren().getFirst();
                late[1] = (StackPane) row(c, 4).getChildren().get(2); // main, line 8 of the file

                // Nothing to show: numbers only.
                stub.marks = MapCardGutter.Marks.NONE;
                stub.changes = null;
                c.refreshGutter();
                assertEquals(1, row(c, 0).getChildren().size());
                c.setGutterSource(null);
                assertEquals(MapCardGutter.Marks.NONE, c.gutterMarks());
                assertNull(c.gutterChanges());
                assertEquals(1, row(c, 0).getChildren().size());
                click((Node) row(c, 0).getChildren().getFirst(), MouseButton.PRIMARY); // a number: nothing
                int calls = stub.calls.size();
                click(late[0], MouseButton.PRIMARY);
                click(((StackPane) late[1]).getChildren().getFirst(), MouseButton.PRIMARY);
                assertEquals(calls, stub.calls.size(), "a strip drawn before the source went does nothing");
                c.setGutterSource(stub);
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                card[0].dispose();
                stage[0].close();
                int calls = stub.calls.size();
                click(late[0], MouseButton.PRIMARY);
                click(((StackPane) late[1]).getChildren().getFirst(), MouseButton.PRIMARY);
                card[0].refreshGutter();
                assertEquals(calls, stub.calls.size(), "a closed card does nothing");
            });
        }
    }

    @Test
    void anOpenTabsBarsAreReadAsItsGutterHasThem() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            try {
                b.setContent("a\nb\nc\n");
                assertNull(MapCardGutterMarks.liveBars(b.gitGutter(), 4), "not tracked");
                b.setChangeBars(Map.of());
                assertNull(MapCardGutterMarks.liveBars(b.gitGutter(), 4), "tracked, nothing changed");
                b.setChangeBars(Map.of(1, "git-added", 2, "git-modified"), Map.of(2, "hunk"));
                MapCardGutter.Changes live = MapCardGutterMarks.liveBars(b.gitGutter(), 4);
                assertEquals(Map.of(1, "git-added", 2, "git-modified"), live.bars());
                assertEquals(Map.of(2, "hunk"), live.tooltips());
            } finally {
                b.dispose();
            }
        });
    }

    private static HBox row(ProjectMapPreview card, int paragraph) {
        return (HBox) card.editor().getParagraphGraphicFactory().apply(paragraph);
    }

    private static void click(Node target, MouseButton button) {
        javafx.event.Event.fireEvent(target, mouse(MouseEvent.MOUSE_CLICKED, button));
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, MouseButton button) {
        return new MouseEvent(
                type,
                1,
                1,
                1,
                1,
                button,
                1,
                false,
                false,
                false,
                false,
                button == MouseButton.PRIMARY,
                false,
                button == MouseButton.SECONDARY,
                true,
                false,
                true,
                null);
    }
}
