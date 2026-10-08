package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.input.DragEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

/** The tab strip and what hangs off it: dragging tabs, closing with unsaved text, recent files, background tabs. */
@Tag("fx")
class MainControllerTabsFxTest {

    @TempDir
    Path dir;

    private AsyncTestScope async;
    private FxWindowFixture fx;
    private MainController controller;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        async = new AsyncTestScope();
        fx = async.own(FxWindowFixture.create());
        controller = fx.controller;
    }

    @AfterEach
    void tearDown() throws Exception {
        async.close();
    }

    private String echo() {
        StatusBar status = FxTestSupport.field(controller, "statusBar");
        return FxTestSupport.<Label>field(status, "echo").getText();
    }

    private EditorBuffer active() {
        return (EditorBuffer) FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {});
    }

    private EditorBuffer addBuffer(String name, String content) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setDisplayName(name);
        buffer.setContent(content);
        FxTestSupport.call(controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
        return buffer;
    }

    private EditorArea area() {
        return FxTestSupport.field(controller, "editorArea");
    }

    private Tab tabOf(EditorBuffer buffer) {
        return area().tabs().stream()
                .filter(t -> t.getUserData() == buffer)
                .findFirst()
                .orElseThrow();
    }

    private List<Object> order() {
        List<Object> out = new ArrayList<>();
        area().tabs().forEach(t -> {
            if (t.getUserData() instanceof EditorBuffer) {
                out.add(t.getUserData());
            }
        });
        return out;
    }

    private void setDragged(Tab tab) {
        try {
            java.lang.reflect.Field f = MainController.class.getDeclaredField("draggedTab");
            f.setAccessible(true);
            f.set(controller, tab);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A drag event over {@code header}: on its right half when {@code rightHalf}. */
    private static DragEvent drag(javafx.event.EventType<DragEvent> type, Node header, boolean rightHalf) {
        Bounds inScene = header.localToScene(header.getBoundsInLocal());
        double x = rightHalf ? inScene.getMaxX() + 1 : inScene.getMinX() - 1;
        double y = inScene.getMinY() + inScene.getHeight() / 2;
        return new DragEvent(null, header, type, null, x, y, x, y, TransferMode.MOVE, null, header, null);
    }

    /**
     * A drag cannot be started without a pointer, so the window is put where a drag-detected leaves it (the
     * dragged tab remembered) and sent the drag events that follow.
     */
    @Test
    void aTabDroppedOnAnotherLandsBeforeOrAfterItAndTheMarkersAreCleared() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer a = addBuffer("a", "a\n");
            EditorBuffer b = addBuffer("b", "b\n");
            EditorBuffer c = addBuffer("c", "c\n");
            assertEquals(List.of(a, b, c), order());
            Node headerA = tabOf(a).getGraphic();
            Node headerC = tabOf(c).getGraphic();

            setDragged(tabOf(a));
            Event.fireEvent(headerC, drag(DragEvent.DRAG_OVER, headerC, true));
            assertTrue(headerC.getStyleClass().contains("tab-drop-after"), "the line shows where it would land");
            assertFalse(headerC.getStyleClass().contains("tab-drop-before"));
            Event.fireEvent(headerC, drag(DragEvent.DRAG_OVER, headerC, false));
            assertTrue(headerC.getStyleClass().contains("tab-drop-before"));
            assertFalse(headerC.getStyleClass().contains("tab-drop-after"));
            Event.fireEvent(headerC, drag(DragEvent.DRAG_EXITED, headerC, false));
            assertFalse(headerC.getStyleClass().contains("tab-drop-before"));

            Event.fireEvent(headerA, drag(DragEvent.DRAG_OVER, headerA, true)); // over itself: nowhere to go
            assertFalse(headerA.getStyleClass().contains("tab-drop-after"));
            Event.fireEvent(headerA, drag(DragEvent.DRAG_DROPPED, headerA, true));
            assertEquals(List.of(a, b, c), order(), "dropped on itself: nothing moves");

            Event.fireEvent(headerC, drag(DragEvent.DRAG_DROPPED, headerC, true));
            assertEquals(List.of(b, c, a), order(), "on the right half: after it");

            Node headerB = tabOf(b).getGraphic();
            Node movedHeader = tabOf(a).getGraphic();
            setDragged(tabOf(a));
            movedHeader.getStyleClass().add("tab-dragging");
            Event.fireEvent(headerB, drag(DragEvent.DRAG_DROPPED, headerB, false));
            assertEquals(List.of(a, b, c), order(), "on the left half: before it");

            Event.fireEvent(movedHeader, drag(DragEvent.DRAG_DONE, movedHeader, false));
            assertFalse(movedHeader.getStyleClass().contains("tab-dragging"));
            assertNull(FxTestSupport.field(controller, "draggedTab"), "the gesture is over");

            // With nothing being dragged, a stray drag event over a tab moves nothing.
            Event.fireEvent(headerC, drag(DragEvent.DRAG_OVER, headerC, true));
            Event.fireEvent(headerC, drag(DragEvent.DRAG_DROPPED, headerC, true));
            assertEquals(List.of(a, b, c), order());
            assertFalse(headerC.getStyleClass().contains("tab-drop-after"));
        });
    }

    @Test
    void closingTheWindowWithUnsavedTextCanBeCancelledAndTheWindowStays() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer dirty = addBuffer("draft", "");
            dirty.getArea().appendText("typed and not saved\n");
            assertTrue(dirty.isDirty());
            Stage stage = FxTestSupport.field(controller, "stage");
            WindowEvent request = new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST);

            List<SettingsRig.Shown> asked = SettingsRig.answering(
                    ButtonBar.ButtonData.CANCEL_CLOSE,
                    () -> stage.getOnCloseRequest().handle(request));

            assertEquals(1, asked.size(), "the unsaved buffer is asked about");
            assertTrue(request.isConsumed(), "Cancel keeps the window open");
            assertEquals(
                    1, FxTestSupport.<List<?>>field(fx.windowManager, "windows").size());
            assertTrue(area().contains(tabOf(dirty)));
            assertEquals("typed and not saved\n", dirty.getContent());
        });
    }

    @Test
    void closingTheWelcomeTabNeedsNoQuestionAndADirtyTabsCloseCanBeCancelled() throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invoke(controller, "showWelcome");
            Tab welcome = FxTestSupport.field(controller, "welcomeTab");
            Event welcomeClose = new Event(Tab.TAB_CLOSE_REQUEST_EVENT);
            assertEquals(
                    List.of(),
                    SettingsRig.answering(
                            ButtonBar.ButtonData.CANCEL_CLOSE,
                            () -> welcome.getOnCloseRequest().handle(welcomeClose)));
            assertFalse(welcomeClose.isConsumed(), "nothing to lose: it just closes");

            EditorBuffer dirty = addBuffer("draft", "");
            dirty.getArea().appendText("unsaved\n");
            Tab tab = tabOf(dirty);
            Event close = new Event(Tab.TAB_CLOSE_REQUEST_EVENT);
            List<SettingsRig.Shown> asked = SettingsRig.answering(
                    ButtonBar.ButtonData.CANCEL_CLOSE,
                    () -> tab.getOnCloseRequest().handle(close));
            assertEquals(1, asked.size());
            assertTrue(close.isConsumed(), "Cancel keeps the tab");
            assertTrue(area().contains(tab));
        });
    }

    @Test
    void theRecentMenuListsOpenedFilesRemovesOneAtItsButtonAndClearsAfterAsking() throws Exception {
        Path one = Files.writeString(dir.resolve("one.txt"), "1\n");
        Path two = Files.writeString(dir.resolve("two.txt"), "2\n");
        SaveDecisionsFxTest.open(async, fx, one);
        SaveDecisionsFxTest.open(async, fx, two);
        FxTestSupport.runOnFx(() -> {
            MenuButton recent = FxTestSupport.field(controller, "recentButton");
            FxTestSupport.invoke(controller, "rebuildRecentMenu");
            List<CustomMenuItem> entries = recent.getItems().stream()
                    .filter(CustomMenuItem.class::isInstance)
                    .map(CustomMenuItem.class::cast)
                    .toList();
            assertEquals(
                    List.of("two.txt", "one.txt"),
                    entries.stream()
                            .map(e -> SettingsRig.texts(e.getContent()))
                            .filter(t -> !t.isEmpty())
                            .map(t -> t.get(0))
                            .toList(),
                    "most recent first: " + recent.getItems());

            Button remove =
                    SettingsRig.all(entries.get(0).getContent(), Button.class).get(0);
            Event.fireEvent(
                    remove,
                    new MouseEvent(
                            MouseEvent.MOUSE_PRESSED,
                            0,
                            0,
                            0,
                            0,
                            MouseButton.PRIMARY,
                            1,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            false,
                            false,
                            false,
                            false,
                            null));
            assertEquals(List.of(one), List.copyOf(fx.shared.recentFiles().getList()), "only that entry goes");

            MenuItem clear = recent.getItems().stream()
                    .filter(i -> tr("tooltip.clearRecent").equals(i.getText()))
                    .findFirst()
                    .orElseThrow();
            List<SettingsRig.Shown> asked = SettingsRig.answering(ButtonBar.ButtonData.CANCEL_CLOSE, clear::fire);
            assertEquals(1, asked.size());
            assertEquals(tr("dialog.clearRecent.header"), asked.get(0).header());
            assertEquals(1, fx.shared.recentFiles().getList().size(), "declined");

            SettingsRig.inDialog(
                    clear::fire,
                    dialog -> ((Button) SettingsRig.button(dialog, tr("dialog.clearRecent.clear"))).fire());
            assertTrue(fx.shared.recentFiles().getList().isEmpty());
            assertEquals(tr("status.recentCleared"), echo());

            FxTestSupport.invoke(controller, "rebuildRecentMenu");
            assertEquals(tr("menu.noRecentFiles"), recent.getItems().get(0).getText());
            assertTrue(recent.getItems().get(0).isDisable());
            assertEquals(
                    List.of(),
                    SettingsRig.answering(
                            ButtonBar.ButtonData.CANCEL_CLOSE, () -> FxTestSupport.invoke(controller, "onClearRecent")),
                    "nothing to clear, nothing to ask");
        });
    }

    @Test
    void aFileOpenedInTheBackgroundBecomesATabWithoutTakingTheSelection() throws Exception {
        Path text = Files.writeString(dir.resolve("background.txt"), "in the background\n");
        Path missing = dir.resolve("not-there.txt");
        List<EditorBuffer> results = new ArrayList<>();
        EditorBuffer[] front = new EditorBuffer[1];
        FxTestSupport.runOnFx(() -> {
            front[0] = addBuffer("front", "front\n");
            openInBackground(text, results);
        });
        SettingsRig.awaitFx("the background load", () -> results.size() == 1);
        FxTestSupport.runOnFx(() -> {
            EditorBuffer opened = results.get(0);
            assertNotNull(opened);
            assertEquals(text, opened.getPath());
            assertEquals("in the background\n", opened.getContent());
            assertSame(front[0], active(), "the user's tab stays in front");
            assertTrue(area().contains(tabOf(opened)));

            openInBackground(text, results); // already open: handed back as it is, at once
            assertEquals(2, results.size());
            assertSame(opened, results.get(1));

            openInBackground(missing, results);
        });
        SettingsRig.awaitFx("the refusal", () -> results.size() == 3);
        FxTestSupport.runOnFx(() -> {
            assertNull(results.get(2), "a file that is not there is no buffer");
            assertEquals(2, order().size());

            // The synchronous form, for a caller that already knows the file is small.
            Path second = dir.resolve("second.txt");
            try {
                Files.writeString(second, "second\n");
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            EditorBuffer sync =
                    (EditorBuffer) FxTestSupport.invokeWith(controller, "openBackgroundBuffer", Path.class, second);
            assertEquals("second\n", sync.getContent());
            assertSame(front[0], active());
            assertNull(FxTestSupport.invokeWith(controller, "openBackgroundBuffer", Path.class, missing));
        });
    }

    private void openInBackground(Path file, List<EditorBuffer> results) {
        FxTestSupport.call(
                controller,
                "openBackgroundBufferAsync",
                new Class<?>[] {Path.class, java.util.function.Consumer.class},
                file,
                (java.util.function.Consumer<EditorBuffer>) results::add);
    }

    @Test
    void theFindCommandsOpenTheBarFirstAndThenStepThroughTheMatches() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = addBuffer("text", "cat dog cat bird cat\n");
            FindReplaceBar find = FxTestSupport.field(controller, "findBar");

            FxTestSupport.invoke(controller, "findNextMatch");
            assertTrue(find.isShown(), "with the bar closed, Find Next opens it");
            javafx.scene.control.TextField query = FxTestSupport.field(find, "findField");
            query.setText("cat");
            FxTestSupport.invoke(find, "recompute");
            buffer.getArea().moveTo(0);

            FxTestSupport.invoke(controller, "findNextMatch");
            int first = buffer.getArea().getSelection().getStart();
            FxTestSupport.invoke(controller, "findNextMatch");
            int second = buffer.getArea().getSelection().getStart();
            assertTrue(second > first, "Find Next moves forward: " + first + " then " + second);
            assertEquals("cat", buffer.getArea().getSelectedText());
            FxTestSupport.invoke(controller, "findPreviousMatch");
            assertEquals(first, buffer.getArea().getSelection().getStart(), "and Find Previous back");

            javafx.scene.control.TextField replacement = FxTestSupport.field(find, "replaceField");
            replacement.setText("cow");
            FxTestSupport.invoke(controller, "findReplaceCurrentMatch");
            assertEquals(1, count(buffer.getContent(), "cow"), "one match replaced");
            FxTestSupport.invoke(controller, "findReplaceAllMatches");
            assertEquals("cow dog cow bird cow\n", buffer.getContent());

            find.hideBar();
            FxTestSupport.invoke(controller, "findPreviousMatch");
            assertTrue(find.isShown(), "with the bar closed, Find Previous opens it too");
            find.hideBar();
            FxTestSupport.invoke(controller, "findReplaceCurrentMatch");
            assertTrue(find.isShown());
            find.hideBar();
            FxTestSupport.invoke(controller, "findReplaceAllMatches");
            assertTrue(find.isShown());
            assertEquals("cow dog cow bird cow\n", buffer.getContent(), "opening the bar replaces nothing");
        });
    }

    private static int count(String text, String word) {
        int n = 0;
        for (int i = text.indexOf(word); i >= 0; i = text.indexOf(word, i + 1)) {
            n++;
        }
        return n;
    }
}
