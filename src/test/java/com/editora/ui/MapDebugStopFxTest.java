package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Map gaps round 3, item 2: the Project Map follows the debugger. Each stop marks its line on the card of the
 * declaration holding it, a preview the next step's card replaces where it stood, with the call stack beside it, whose
 * frames go back to the debugger; a resume takes the mark away, and "Follow the Debugger" turns it all off.
 */
@Tag("fx")
class MapDebugStopFxTest {

    private static final String CALC = String.join(
            "\n",
            "package app;", // 0
            "", // 1
            "public class Calc {", // 2
            "    int add(int a, int b) {", // 3
            "        int sum = a + b;", // 4
            "        return sum;", // 5
            "    }", // 6
            "", // 7
            "    int twice(int a) {", // 8
            "        return add(a, a);", // 9
            "    }", // 10
            "}", // 11
            "");

    @TempDir
    Path temp;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static DapModels.StackFrameInfo frame(int id, String name, Path file, int line) {
        return new DapModels.StackFrameInfo(id, name, file, line, 0, null, "", null, null);
    }

    @Test
    void theMapFollowsTheDebuggersStopsAndItsCallStackPicksFrames() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project")).toRealPath();
        Path calc = project.resolve("app/Calc.java");
        Files.createDirectories(calc.getParent());
        Files.writeString(calc, CALC);
        DapModels.StackFrameInfo add = frame(1, "Calc.add", calc, 4);
        DapModels.StackFrameInfo twice = frame(2, "Calc.twice", calc, 9);
        DapModels.StackFrameInfo await =
                new DapModels.StackFrameInfo(3, "await", null, 0, 0, null, DapModels.HINT_LABEL, null, null);
        DapModels.StackFrameInfo library =
                new DapModels.StackFrameInfo(4, "Thread.run", null, 0, 0, null, "", null, null);
        DapModels.StackFrameInfo eval = new DapModels.StackFrameInfo(
                5, "<eval>", null, 2, 0, null, "", null, new DapModels.LoadedSource("eval", "", null, 7, null));
        List<DapModels.StackFrameInfo> stack = List.of(add, twice, await, library, eval);
        List<DapModels.StackFrameInfo> picked = new CopyOnWriteArrayList<>();
        List<Boolean> remembered = new CopyOnWriteArrayList<>();
        Stage[] stage = new Stage[1];
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false);
            made.setOnDebugFramePicked(picked::add);
            made.setOnFollowDebuggerChanged(remembered::add);
            stage[0] = new Stage();
            Scene scene = new Scene(new StackPane(made), 1400, 900);
            scene.getStylesheets()
                    .add(MapDebugStopFxTest.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            stage[0].setScene(scene);
            stage[0].show();
            made.setRoot(project);
            return made;
        });
        try {
            assertTrue(FxTestSupport.callOnFx(view::isFollowDebugger), "on by default");

            // A stop: the card of add() marks line 5, a preview, with the call stack beside it.
            FxTestSupport.runOnFx(() -> view.setDebugStop(new MapDebugStop(add, stack, false)));
            waitFor(() -> view.debugStopCard() != null && view.debugStopCard().executionLine() == 4);
            ProjectMapPreview first = FxTestSupport.callOnFx(view::debugStopCard);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview.Range range = first.range();
                assertNotNull(range, "the declaration's card");
                assertTrue(range.symbol().startsWith("add"), range.symbol());
                assertTrue(first.getStyleClass().contains(ProjectMapPreview.DEBUG_STOP_STYLE));
                int paragraph = first.executionParagraph();
                assertEquals(4 - range.startLine(), paragraph);
                assertEquals(
                        List.of("exec-line"),
                        List.copyOf(first.editor().getParagraph(paragraph).getParagraphStyle()));
                HBox row = (HBox) first.editor().getParagraphGraphicFactory().apply(paragraph);
                StackPane strip = (StackPane) row.getChildren().getFirst();
                assertTrue(strip.getChildren().stream().anyMatch(Group.class::isInstance), "the arrow");

                MapCallStack column = view.debugCallStack();
                assertNotNull(column);
                assertEquals(stack, column.frames());
                assertEquals(0, column.list().getSelectionModel().getSelectedIndex());
                assertEquals(tr("project.map.debug.stackTitleCount", 5), column.titleText());
                assertEquals("Calc.java:10", MapCallStack.where(twice));
                assertEquals(tr("project.map.debug.stackNoSource"), MapCallStack.where(library));
                assertEquals(tr("project.map.debug.stackNoFile"), MapCallStack.where(eval));

                // Picking a frame hands it to the debugger; a label or nothing is not a frame.
                column.pick(twice);
                column.pick(await);
                column.pick(null);
                assertEquals(List.of(twice), picked);
                column.list().getSelectionModel().select(3);
                KeyEvent enter = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false);
                javafx.event.Event.fireEvent(column.list(), enter);
                assertEquals(List.of(twice, library), picked);
                column.list().applyCss();
                column.list().layout();
                Node cell = column.list().lookup(".list-cell");
                assertNotNull(cell);
                javafx.event.Event.fireEvent(
                        cell,
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
                                true,
                                null));
                assertEquals(add, picked.getLast(), "a click on the first row");
            });

            // The debugger shows the caller: its card takes the stop card's place, the call stack follows.
            FxTestSupport.runOnFx(() -> view.setDebugStop(new MapDebugStop(twice, stack, true)));
            waitFor(() -> view.debugStopCard() != first && view.debugStopCard() != null);
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview second = view.debugStopCard();
                assertNotSame(first, second);
                assertNull(first.getParent(), "the preview it replaced is closed");
                assertEquals(9, second.executionLine());
                assertEquals(
                        List.of("exec-line", "exec-exception"),
                        List.copyOf(second.editor()
                                .getParagraph(second.executionParagraph())
                                .getParagraphStyle()));
                assertEquals(1, view.debugCallStack().list().getSelectionModel().getSelectedIndex());
            });

            // A frame with no file: the card is not marked, the call stack stays.
            FxTestSupport.runOnFx(() -> view.setDebugStop(new MapDebugStop(library, stack, false)));
            waitFor(() -> view.debugStopCard().executionLine() < 0);
            FxTestSupport.runOnFx(() -> {
                assertEquals(3, view.debugCallStack().list().getSelectionModel().getSelectedIndex());
                assertFalse(view.debugStopCard().getStyleClass().contains(ProjectMapPreview.DEBUG_STOP_STYLE));
            });

            // Running: no mark and no call stack; the next stop marks the card again.
            FxTestSupport.runOnFx(() -> view.setDebugStop(new MapDebugStop(twice, stack, false)));
            waitFor(() -> view.debugStopCard().executionLine() == 9);
            FxTestSupport.runOnFx(() -> view.setDebugStop(MapDebugStop.RUNNING));
            waitFor(() -> view.debugStopCard().executionLine() < 0);
            FxTestSupport.runOnFx(() -> assertNull(view.debugCallStack()));

            // Escape on the call stack takes it away until the next stop.
            FxTestSupport.runOnFx(() -> view.setDebugStop(new MapDebugStop(twice, stack, false)));
            waitFor(() -> view.debugCallStack() != null);
            FxTestSupport.runOnFx(() -> {
                MapCallStack column = view.debugCallStack();
                KeyEvent escape =
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false);
                javafx.event.Event.fireEvent(column.list(), escape);
                assertNull(view.debugCallStack());
                view.setDebugStop(new MapDebugStop(twice, stack, false));
            });
            waitFor(() -> view.debugCallStack() != null);

            // "Follow the Debugger" off: the mark and the call stack go, and stops leave the Map alone.
            FxTestSupport.runOnFx(() -> view.setFollowDebugger(false));
            waitFor(() -> view.debugCallStack() == null);
            FxTestSupport.runOnFx(() -> {
                assertEquals(List.of(false), remembered);
                assertTrue(view.debugStopCard() == null || view.debugStopCard().executionLine() < 0);
                view.setDebugStop(new MapDebugStop(add, stack, false));
            });
            settle();
            FxTestSupport.runOnFx(() -> assertNull(view.debugCallStack()));
            FxTestSupport.runOnFx(() -> view.setFollowDebugger(true));
            waitFor(() -> view.debugCallStack() != null && view.debugStopCard().executionLine() == 4);

            // A hidden Map shows the stop when it shows again.
            FxTestSupport.runOnFx(() -> {
                view.setShowing(false);
                view.setDebugStop(new MapDebugStop(twice, stack, false));
            });
            settle();
            FxTestSupport.runOnFx(() -> assertEquals(4, view.debugStopCard().executionLine(), "not while hidden"));
            FxTestSupport.runOnFx(() -> view.setShowing(true));
            waitFor(() -> view.debugStopCard() != null && view.debugStopCard().executionLine() == 9);
            // Showing reloads the Map, and app/ is not open on it: the check for cards whose rows went away leaves the
            // card the debugger is paused on (it used to close it, a pulse after the reload landed).
            ProjectMapPreview shown = FxTestSupport.callOnFx(view::debugStopCard);
            awaitOrphanCheck(view);
            FxTestSupport.runOnFx(() -> assertSame(shown, view.debugStopCard(), "the stop card stays"));

            // A root with nothing paused, and a stop with no stack: the card marks the line, no call stack.
            FxTestSupport.runOnFx(() -> view.setDebugStop(new MapDebugStop(add, List.of(), false)));
            waitFor(() -> view.debugCallStack() == null && view.debugStopCard().executionLine() == 4);
            ProjectMapPreview last = FxTestSupport.callOnFx(view::debugStopCard);
            awaitOrphanCheck(view);
            FxTestSupport.runOnFx(() -> {
                assertSame(last, view.debugStopCard(), "still paused there: the card stays");
                last.clearExecutionLine();
                last.clearExecutionLine(); // nothing marked: nothing to do
                assertSame(last, view.debugStopCard());
            });

            // A resume lets it go: the next check closes it, its folder not open on the Map.
            FxTestSupport.runOnFx(() -> view.setDebugStop(MapDebugStop.RUNNING));
            FxTestSupport.drainFx();
            awaitOrphanCheck(view);
            FxTestSupport.runOnFx(() -> {
                assertNull(view.debugStopCard());
                assertNull(last.getParent(), "closed");
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage[0].close();
            });
        }
    }

    @Test
    void aKeptCardStaysAFileWithNoOutlineGetsItsCardAndAClosedMapDoesNothing() throws Exception {
        Path project = Files.createDirectories(temp.resolve("kept")).toRealPath();
        Path calc = project.resolve("Calc.java");
        Files.writeString(calc, CALC);
        Path notes = project.resolve("notes.txt");
        Files.writeString(notes, "one\ntwo\nthree\n");
        DapModels.StackFrameInfo add = frame(1, "Calc.add", calc, 4);
        DapModels.StackFrameInfo twice = frame(2, "Calc.twice", calc, 9);
        DapModels.StackFrameInfo text = frame(3, "notes", notes, 1);
        DapModels.StackFrameInfo fold =
                new DapModels.StackFrameInfo(4, "3 frames", null, 3, 0, null, DapModels.HINT_FOLD, null, null);
        Stage[] stage = new Stage[1];
        ProjectMapView view = FxTestSupport.callOnFx(() -> {
            ProjectMapView made = new ProjectMapView(path -> {}, path -> false, path -> false);
            made.setOnDebugFramePicked(null);
            made.setOnFollowDebuggerChanged(null);
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new StackPane(made), 1400, 900));
            stage[0].show();
            // No folder yet: a stop shows nothing.
            made.setDebugStop(new MapDebugStop(add, List.of(add), false));
            return made;
        });
        try {
            settle();
            FxTestSupport.runOnFx(() -> {
                assertNull(view.debugStopCard());
                view.setFollowDebugger(false); // told no one
                view.setFollowDebugger(true);
                view.setRoot(project);
            });

            // A stop, then another before the first file's outline is read: only the second opens.
            FxTestSupport.runOnFx(() -> view.setDebugStop(new MapDebugStop(add, List.of(add, twice), false)));
            FxTestSupport.runOnFx(() -> view.setDebugStop(new MapDebugStop(text, List.of(text, fold), false)));
            waitFor(() -> view.debugStopCard() != null && view.debugStopCard().executionLine() == 1);
            ProjectMapPreview notesCard = FxTestSupport.callOnFx(view::debugStopCard);
            FxTestSupport.runOnFx(() -> {
                assertNull(notesCard.range(), "a file with no outline: its whole card");
                assertEquals(1, notesCard.executionParagraph());
                MapCallStack column = view.debugCallStack();
                column.pick(fold);
                column.show(List.of(text, fold), 99); // a frame not listed: nothing selected
                assertEquals(-1, column.list().getSelectionModel().getSelectedIndex());
                column.list().applyCss();
                column.list().layout();
                for (Node cell : column.list().lookupAll(".list-cell")) {
                    javafx.event.Event.fireEvent(
                            cell,
                            new MouseEvent(
                                    MouseEvent.MOUSE_CLICKED,
                                    1,
                                    1,
                                    1,
                                    1,
                                    MouseButton.SECONDARY,
                                    1,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    true,
                                    true,
                                    false,
                                    true,
                                    null));
                }
                javafx.event.Event.fireEvent(
                        column.list(),
                        new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false));
                column.list().getItems().clear(); // every cell empty
                column.list().layout();

                // Pinned, the card is kept: the next stop opens its own card and the pinned one loses its mark.
                javafx.scene.control.ToggleButton pin = FxTestSupport.field(notesCard, "pin");
                pin.fire();
            });
            FxTestSupport.runOnFx(() -> view.setDebugStop(new MapDebugStop(add, List.of(add), false)));
            waitFor(() ->
                    view.debugStopCard() != notesCard && view.debugStopCard().executionLine() == 4);
            FxTestSupport.runOnFx(() -> {
                assertNotNull(notesCard.getParent(), "a pinned card stays");
                assertEquals(-1, notesCard.executionLine());
                assertEquals(-1, notesCard.executionParagraph());
                view.setShowing(false);
                view.setShowing(true); // nothing new: the stop is shown again as it is
            });
            settle();
            FxTestSupport.runOnFx(() -> assertEquals(4, view.debugStopCard().executionLine()));
            FxTestSupport.runOnFx(() -> {
                ProjectMapPreview card = view.debugStopCard();
                view.dispose();
                view.setDebugStop(new MapDebugStop(twice, List.of(twice), false)); // closed: nothing happens
                view.applyDebugStop();
                card.setExecutionLine(2, false); // a closed card is not marked
                assertEquals(4, card.executionLine());
            });
        } finally {
            FxTestSupport.runOnFx(() -> {
                view.dispose();
                stage[0].close();
            });
        }
    }

    /** Lets the pulses that apply a stop pass. */
    /**
     * Runs the Map's check for cards whose rows went away now, as the first connector draw after a reload does, and
     * waits for it (it closes cards a pulse later, not from inside the draw).
     */
    private static void awaitOrphanCheck(ProjectMapView view) throws Exception {
        MapCards cards = FxTestSupport.field(view, "cards");
        FxTestSupport.runOnFx(() -> {
            try {
                java.lang.reflect.Field checked = MapCards.class.getDeclaredField("cardsCheckedAgainst");
                checked.setAccessible(true);
                checked.set(cards, null);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            FxTestSupport.invoke(cards, "repaintPreviewConnectors");
        });
        FxTestSupport.drainFx();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 5; i++) {
            FxTestSupport.runOnFx(() -> {});
            TimeUnit.MILLISECONDS.sleep(20);
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
