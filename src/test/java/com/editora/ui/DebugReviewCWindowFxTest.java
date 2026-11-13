package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.dap.DapClient;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java-debugging review's UI fixes (review C) in a real window against a scripted adapter: where focus goes,
 * how the stopped line looks, the exception row, the editor and gutter menus, the breakpoints list and Mute.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugReviewCWindowFxTest {

    private static final String UTIL =
            "def work(n):\n    x = n + 1\n    y = x * 2\n    return y\n\n\ndef go():\n    return work(0)\n";

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private DebugPanel panel;
    private int files;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        panel = debug.panel();
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setPythonDebugEnabled(true);
            com.editora.dap.DapTestSettings.set(manager, "enabled", true);
            com.editora.dap.DapTestSettings.set(manager, "pythonEnabled", true);
            com.editora.dap.DapTestSettings.set(manager, "pythonAvailable", true);
            com.editora.dap.DapTestSettings.set(
                    manager,
                    "pythonCommand",
                    fx.configDir.resolve("no-such-python").toString());
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            stage.setWidth(1300);
            stage.setHeight(850);
            debug.applyGating();
        });
    }

    @AfterEach
    void endSession() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        FxTestSupport.drainFx();
        if (FxTestSupport.callOnFx(debug::breakpointsMuted)) {
            FxTestSupport.runOnFx(debug::toggleMuteBreakpoints);
        }
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    // --- harness -----------------------------------------------------------------------------------

    private static void set(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private Path file(String name, String text) throws Exception {
        Path dir = fx.configDir.resolve("casec" + (++files));
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    private EditorBuffer active() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
    }

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class[] {Path.class}, file));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            FxTestSupport.drainFx();
            EditorBuffer b = active();
            if (b != null
                    && file.equals(b.getPath())
                    && !FxTestSupport.callOnFx(b::getContent).isEmpty()) {
                FxTestSupport.runOnFx(debug::applyGating);
                return b;
            }
            assertTrue(System.nanoTime() < deadline, "the file never opened: " + file);
            Thread.sleep(20);
        }
    }

    private FakeDebugAdapter.Session connect(FakeDebugAdapter adapter, Path file) throws Exception {
        long epoch =
                (Long) FxTestSupport.callOnFx(() -> FxTestSupport.call(manager, "beginSession", new Class<?>[] {}));
        DapClient client = new DapClient(
                (DapClient.Host) FxTestSupport.call(manager, "sessionHost", new Class<?>[] {long.class}, epoch));
        client.connect(adapter.port(), "python").get(10, TimeUnit.SECONDS);
        assertTrue((Boolean) FxTestSupport.call(
                manager, "publishClient", new Class<?>[] {long.class, DapClient.class}, epoch, client));
        FxTestSupport.runOnFx(() -> {
            set(manager, "debugFile", file);
            state(DapManager.State.RUNNING);
        });
        return adapter.awaitSession();
    }

    private void state(DapManager.State state) {
        FxTestSupport.call(manager, "setState", new Class<?>[] {DapManager.State.class}, state);
    }

    private void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            FxTestSupport.drainFx();
            if (FxTestSupport.callOnFx(condition)) {
                FxTestSupport.drainFx();
                return;
            }
            assertTrue(System.nanoTime() < deadline, what);
            Thread.sleep(15);
        }
    }

    private void stopAt(FakeDebugAdapter.Session session, Path file, int line1, String reason) throws Exception {
        session.framePath = file.toString();
        session.frameLine = line1;
        session.stop(7, reason);
        await("the session never stopped", () -> manager.state() == DapManager.State.SUSPENDED);
        await("the stopped line was never painted", () -> active().getArea().getCurrentParagraph() == line1 - 1);
    }

    private ToolWindowManager toolWindows() {
        return FxTestSupport.field(fx.controller, "toolWindows");
    }

    private ToolWindow debugWindow() {
        return FxTestSupport.field(fx.controller, "debugToolWindow");
    }

    private Node focusOwner() throws Exception {
        return FxTestSupport.callOnFx(() ->
                FxTestSupport.<Stage>field(fx.controller, "stage").getScene().getFocusOwner());
    }

    private static boolean inside(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static Node find(Node root, String styleClass) {
        if (root.getStyleClass().contains(styleClass)) {
            return root;
        }
        if (root instanceof Parent p) {
            for (Node child : p.getChildrenUnmodifiable()) {
                Node found = find(child, styleClass);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // --- C2 / C5: focus stays in the editor; a stop shows the window again ---------------------------------

    /**
     * Review C2: starting a session moved focus into the Debug window, where plain letters are debugger commands
     * — typing "work" restarted and then stopped the program. Review C5: a stop did not show the window again
     * once the user had closed it.
     */
    @Test
    void startingAndStoppingNeverTakeFocusAndAStopReopensTheClosedWindow() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        FxTestSupport.runOnFx(() -> {
            toolWindows().setVisible(debugWindow(), false);
            b.getArea().requestFocus();
        });
        await("the editor never had focus", () -> focusOwner() == b.getArea());

        FxTestSupport.runOnFx(() -> state(DapManager.State.STARTING)); // what every launch reports first
        await(
                "the Debug window did not open for the session",
                () -> toolWindows().isOpen(debugWindow()));
        FxTestSupport.drainFx();
        assertSame(b.getArea(), focusOwner(), "starting left focus in the editor");
        FxTestSupport.runOnFx(() -> state(DapManager.State.INACTIVE));

        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            FxTestSupport.runOnFx(() -> toolWindows().setVisible(debugWindow(), false)); // the user closed it
            assertFalse(FxTestSupport.callOnFx(() -> toolWindows().isOpen(debugWindow())));
            stopAt(session, util, 3, "breakpoint");
            await("the stop did not show the Debug window", () -> toolWindows().isOpen(debugWindow()));
            FxTestSupport.drainFx();
            Node owner = focusOwner();
            assertFalse(inside(owner, panel), "the stop put focus in the Debug window: " + owner);
            assertSame(b.getArea(), owner, "the editor has focus at the stopped line");
        }
    }

    // --- C1: the stopped line shows through the caret line and carries an arrow -----------------------------

    @Test
    void theStoppedLineShowsThroughTheCaretLineAndTheGutterCarriesAnArrow() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        FxTestSupport.runOnFx(() -> b.getArea().setLineHighlighterOn(true));
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            stopAt(session, util, 3, "breakpoint");
            CodeArea area = b.getArea();
            Color normal = FxTestSupport.field(b, "lineHighlightColor");
            assertEquals(
                    Color.TRANSPARENT,
                    FxTestSupport.callOnFx(() -> lineFill(area)),
                    "on the stopped line the caret-line highlight lets the execution colour through");
            Node arrow = FxTestSupport.callOnFx(
                    () -> find(area.getParagraphGraphicFactory().apply(2), "exec-arrow"));
            assertNotNull(arrow, "the gutter marks the stopped line with an arrow");
            assertEquals(
                    null,
                    FxTestSupport.callOnFx(
                            () -> find(area.getParagraphGraphicFactory().apply(1), "exec-arrow")));

            FxTestSupport.runOnFx(() -> area.moveTo(0, 0));
            assertEquals(normal, FxTestSupport.callOnFx(() -> lineFill(area)), "elsewhere the caret line is as always");
            FxTestSupport.runOnFx(() -> area.moveTo(2, 1));
            assertEquals(Color.TRANSPARENT, FxTestSupport.callOnFx(() -> lineFill(area)));

            FxTestSupport.runOnFx(manager::resume);
            await(
                    "the arrow stayed after Continue",
                    () -> find(area.getParagraphGraphicFactory().apply(2), "exec-arrow") == null);
            assertEquals(normal, FxTestSupport.callOnFx(() -> lineFill(area)));
        }
    }

    private static Object lineFill(CodeArea area) {
        try {
            var f = org.fxmisc.richtext.GenericStyledArea.class.getDeclaredField("lineHighlighterFill");
            f.setAccessible(true);
            Object property = f.get(area);
            return property == null ? null : ((javafx.beans.value.ObservableValue<?>) property).getValue();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    // --- C13: the exception of an exception stop ------------------------------------------------------------

    // --- Map gaps round 3, item 2: the Project Map follows the stops ------------------------------------------

    @Test
    void theProjectMapIsToldEachStopAndPicksFramesBack() throws Exception {
        Path util = file("util.py", UTIL);
        open(util);
        List<MapDebugStop> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        FxTestSupport.runOnFx(() -> debug.setOnMapStop(told::add));
        assertEquals(1, told.size(), "told what holds now at once");
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            stopAt(session, util, 3, "breakpoint");
            await("the Map was not told the stop", () -> debug.mapStop().frame() != null);
            MapDebugStop stop = FxTestSupport.callOnFx(debug::mapStop);
            assertEquals("util.py", stop.frame().file().getFileName().toString());
            assertEquals(2, stop.frame().line());
            assertFalse(stop.exception());
            assertFalse(stop.stack().isEmpty(), "with its call stack");
            assertEquals(stop, told.getLast());

            int before = told.size();
            FxTestSupport.runOnFx(() -> debug.selectFrameFromMap(stop.frame())); // listed: shown again
            await("picking a frame on the Map shows it", () -> told.size() > before);
            assertEquals(stop.frame(), told.getLast().frame());
            FxTestSupport.runOnFx(() -> debug.selectFrameFromMap(new com.editora.dap.DapModels.StackFrameInfo(
                    987_654, "gone", null, 0, 0, null, "", null, null))); // not listed: said, nothing shown

            FxTestSupport.runOnFx(manager::resume);
            session.awaitRequest("continue");
            await("the Map was not told it runs", () -> debug.mapStop().frame() == null);
            assertEquals(MapDebugStop.RUNNING, told.getLast());
            int running = told.size();
            FxTestSupport.runOnFx(() -> debug.selectFrameFromMap(stop.frame())); // running: nothing to pick
            assertEquals(running, told.size());
        } finally {
            FxTestSupport.runOnFx(() -> debug.setOnMapStop(ignored -> {}));
        }
    }

    @Test
    void anExceptionStopShowsTheExceptionRowAndARedLine() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            org.eclipse.lsp4j.debug.ExceptionInfoResponse info = new org.eclipse.lsp4j.debug.ExceptionInfoResponse();
            info.setExceptionId("java.lang.IllegalStateException");
            info.setDescription("\"java.lang.IllegalStateException: boom\"");
            adapter.exceptionInfo = info;
            FakeDebugAdapter.Session session = connect(adapter, util);
            stopAt(session, util, 3, "exception");
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(panel, "variables");
            await("no exception row", () -> {
                TreeItem<DebugPanel.VarRow> first = tree.getRoot().getChildren().getFirst();
                return first.getValue().kind() == DebugPanel.Kind.EXCEPTION;
            });
            TreeItem<DebugPanel.VarRow> row =
                    FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().getFirst());
            assertTrue(FxTestSupport.callOnFx(() -> row.getValue().name()).contains("IllegalStateException"));
            assertTrue(
                    FxTestSupport.callOnFx(() ->
                            b.getArea().getParagraph(2).getParagraphStyle().contains("exec-exception")),
                    "the line that threw is drawn red");

            FxTestSupport.runOnFx(manager::resume);
            session.awaitRequest("continue");
            stopAt(session, util, 3, "breakpoint");
            await(
                    "the exception row outlived its stop",
                    () -> tree.getRoot().getChildren().getFirst().getValue().kind() != DebugPanel.Kind.EXCEPTION);
            assertFalse(FxTestSupport.callOnFx(
                    () -> b.getArea().getParagraph(2).getParagraphStyle().contains("exec-exception")));
        }
    }

    @Test
    void anExceptionStopOpensTheExceptionCardUnderTheLineAndTheNextStopClosesIt() throws Exception {
        Path util = file("util.py", UTIL);
        open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            org.eclipse.lsp4j.debug.ExceptionInfoResponse info = new org.eclipse.lsp4j.debug.ExceptionInfoResponse();
            info.setExceptionId("ValueError");
            info.setBreakMode(org.eclipse.lsp4j.debug.ExceptionBreakMode.USER_UNHANDLED);
            org.eclipse.lsp4j.debug.ExceptionDetails details = new org.eclipse.lsp4j.debug.ExceptionDetails();
            details.setFullTypeName("builtins.ValueError");
            details.setMessage("bad value");
            details.setStackTrace("File \"util.py\", line 3");
            info.setDetails(details);
            adapter.exceptionInfo = info;
            FakeDebugAdapter.Session session = connect(adapter, util);
            stopAt(session, util, 3, "exception");
            await(
                    "the exception card",
                    () -> debug.exceptionCard() != null
                            && debug.exceptionCard().shown() != null
                            && "builtins.ValueError"
                                    .equals(debug.exceptionCard().shown().type()));
            com.editora.dap.DapModels.ExceptionInfo shown =
                    FxTestSupport.callOnFx(() -> debug.exceptionCard().shown());
            assertEquals("builtins.ValueError", shown.type());
            assertTrue(shown.uncaught());
            assertEquals("File \"util.py\", line 3", shown.stackTrace());

            // Closed and reopened: Show Exception brings it back for the same stop.
            FxTestSupport.runOnFx(() -> debug.exceptionCard().hide());
            FxTestSupport.runOnFx(debug::showException);
            await("Show Exception reopens it", () -> debug.exceptionCard().isShowing());

            FxTestSupport.runOnFx(manager::resume);
            session.awaitRequest("continue");
            await("Continue closes it", () -> !debug.exceptionCard().isShowing());
            stopAt(session, util, 3, "breakpoint");
            FxTestSupport.drainFx();
            assertFalse(FxTestSupport.callOnFx(() -> debug.exceptionCard().isShowing()), "not for a breakpoint");
        }
    }

    // --- pinned values ----------------------------------------------------------------------------------------

    private static java.util.Map<Integer, String> pinned(EditorBuffer b) {
        Object overlay = FxTestSupport.field(b, "inlineValues");
        return overlay == null ? null : FxTestSupport.field(overlay, "pinned");
    }

    @Test
    void pinnedValuesAreEvaluatedAtEveryStopKeptWithTheProjectAndClearedWhenItRuns() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        PinnedValues pins = debug.pinnedValues();
        FxTestSupport.runOnFx(() -> {
            pins.toggle(b, 1, "x"); // line 2: x = n + 1
            pins.toggle(b, 1, "bad + 1"); // the adapter refuses it
            // From the selection, then from the caret's identifier, through the command.
            b.getFocusedArea().selectRange(2, 8, 2, 13); // "x * 2"
            debug.togglePinAtCaret();
            b.getFocusedArea().moveTo(3, 12); // on "y" of "return y"
            debug.togglePinAtCaret();
        });
        assertEquals(
                List.of("x", "bad + 1"),
                FxTestSupport.callOnFx(() -> b.getPinManager().expressions(1)));
        assertEquals(
                List.of("x * 2"), FxTestSupport.callOnFx(() -> b.getPinManager().expressions(2)));
        assertEquals(
                List.of("y"), FxTestSupport.callOnFx(() -> b.getPinManager().expressions(3)));
        assertEquals(
                3,
                FxTestSupport.callOnFx(() -> fx.controller
                        .config
                        .getPins()
                        .get(DebugCoordinator.key(util))
                        .size()),
                "saved with the project's breakpoints");

        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            stopAt(session, util, 3, "breakpoint");
            await("the pins painted", () -> {
                var shown = pinned(b);
                return shown != null && shown.size() == 3;
            });
            var shown = FxTestSupport.callOnFx(() -> pinned(b));
            assertEquals(
                    "x = val(x)  ·  bad + 1 = " + tr("debug.pin.failed", "Cannot evaluate: bad + 1"), shown.get(1));
            assertEquals("x * 2 = val(x * 2)", shown.get(2));

            // Pinned while paused: it shows at once; Pin to Line in the value popup pins to the popup's line.
            DebugValuePopup.Source source = FxTestSupport.field(debug.valuePopup(), "source");
            FxTestSupport.runOnFx(() -> source.pin(b.getFocusedArea(), 0, "n"));
            await("the popup's pin painted", () -> "n = val(n)".equals(pinned(b).get(0)));

            // Unpin a line; a line with none says so.
            FxTestSupport.runOnFx(() -> {
                b.getFocusedArea().moveTo(2, 0);
                debug.unpinLine();
            });
            await("line 3 unpinned", () -> !pinned(b).containsKey(2));
            assertTrue(FxTestSupport.callOnFx(
                    () -> b.getPinManager().expressions(2).isEmpty()));
            FxTestSupport.runOnFx(debug::unpinLine); // nothing left on line 3

            FxTestSupport.runOnFx(manager::resume);
            session.awaitRequest("continue");
            await("cleared while running", () -> pinned(b) == null);
        }
        // Gone and back: the saved pins come back with the file.
        FxTestSupport.runOnFx(() -> b.getPinManager().clearLine(0));
        assertEquals(List.of("x", "bad + 1"), FxTestSupport.callOnFx(() -> {
            EditorBuffer again = new EditorBuffer();
            again.setContent(UTIL);
            again.getPinManager().restore(fx.controller.config.getPins().get(DebugCoordinator.key(util)));
            return again.getPinManager().expressions(1);
        }));
    }

    @Test
    void thePopupKnowsTheLineItOpenedFor() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        int line = FxTestSupport.callOnFx(() -> {
            var area = b.getFocusedArea();
            area.layout();
            int at = area.getAbsolutePosition(2, 4);
            var bounds = area.getCharacterBoundsOnScreen(at, at + 1).orElseThrow();
            return DebugValuePopup.lineAbove(area, bounds.getMinX(), bounds.getMaxY() + 2);
        });
        assertEquals(2, line, "just below the word: its own line");
        assertEquals(-1, (int)
                FxTestSupport.callOnFx(() -> DebugValuePopup.lineAbove(new org.fxmisc.richtext.CodeArea(), 0, 0)));
    }

    // --- C10 / C11: the editor's and the gutter's debugger menus --------------------------------------------

    @Test
    void theEditorMenuOffersBreakpointsAndWhilePausedRunToCursorShowValueAndWatch() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        List<String> idle = FxTestSupport.callOnFx(() -> labels(debug.editorMenuItems(b)));
        assertEquals(
                List.of(
                        tr("editmenu.breakpoints"),
                        DebugPanelText.bareTitle(tr("command.debug.toggleBreakpoint")),
                        DebugPanelText.bareTitle(tr("command.debug.toggleTemporaryBreakpoint")),
                        DebugPanelText.bareTitle(tr("command.debug.toggleRecordingBreakpoint")),
                        DebugPanelText.bareTitle(tr("command.debug.addLogpoint")),
                        DebugPanelText.bareTitle(tr("command.debug.addTriggeredBreakpoint")),
                        DebugPanelText.bareTitle(tr("command.debug.editBreakpoint")),
                        DebugPanelText.bareTitle(tr("command.debug.moveBreakpointToGroup")),
                        DebugPanelText.bareTitle(tr("command.debug.toggleBreakpointEnabled")),
                        DebugPanelText.bareTitle(tr("command.debug.breakpoints")),
                        DebugPanelText.bareTitle(tr("command.debug.muteBreakpoints"))),
                idle,
                "one Breakpoints submenu, its rows after it");
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            stopAt(session, util, 3, "breakpoint");
            FxTestSupport.runOnFx(() -> b.getArea().moveTo(2, 5)); // on "y" of "y = x * 2"
            List<String> paused = FxTestSupport.callOnFx(() -> labels(debug.editorMenuItems(b)));
            assertTrue(paused.contains(DebugPanelText.bareTitle(tr("command.debug.runToCursor"))), paused.toString());
            assertTrue(paused.contains(DebugPanelText.bareTitle(tr("command.debug.showValue"))), paused.toString());
            assertTrue(paused.contains(tr("debugpanel.addToWatches", "y")), paused.toString());
            // Jump to Line: this adapter has no goto, so the palette greys it and says why.
            String why = FxTestSupport.callOnFx(() -> (String) FxTestSupport.call(
                    fx.controller, "disabledCommandReason", new Class<?>[] {String.class}, "debug.jumpToLine"));
            assertEquals(tr("status.debug.jumpUnsupported"), why);
        }
    }

    /** A classic Java main class in a plain folder had no Debug entry in the editor's menu (review C10). */
    @Test
    void aPlainFolderJavaClassOffersDebugFileInTheEditorMenu() throws Exception {
        Path main = file("Main.java", "public class Main {\n    public static void main(String[] a) {\n    }\n}\n");
        EditorBuffer b = open(main);
        List<String> labels = FxTestSupport.callOnFx(() -> labels(debug.editorMenuItems(b)));
        assertEquals(tr("editmenu.debugFile"), labels.getFirst(), labels.toString());
        // Review 2 C110: not while that file is being debugged — it offered a second launch of it.
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            connect(adapter, main);
            List<String> during = FxTestSupport.callOnFx(() -> labels(debug.editorMenuItems(b)));
            assertFalse(during.contains(tr("editmenu.debugFile")), during.toString());
        }
    }

    /**
     * Add Logpoint: a Shift+click in the gutter (IntelliJ's gesture) or the command asks for the message and makes
     * the line a logpoint; OK waits for a message, a cancelled form leaves no breakpoint behind, a plain click still
     * toggles, and on a line that has a breakpoint it edits that one's message and keeps its condition.
     */
    @Test
    void shiftClickingTheGutterOrAddLogpointMakesTheLineALogpoint() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        var mgr = b.getBreakpointManager();
        Node slot = FxTestSupport.callOnFx(
                () -> find(b.getArea().getParagraphGraphicFactory().apply(1), "breakpoint-slot"));
        assertNotNull(slot);
        FxTestSupport.runOnFx(() -> slot.fireEvent(click(true)));
        FxTestSupport.drainFx();
        assertTrue(OverlayDriver.showing(fx.controller), "the Shift+click asks for the message");
        assertEquals("", FxTestSupport.callOnFx(() -> OverlayDriver.promptText(fx.controller)));
        assertTrue(FxTestSupport.callOnFx(this::okDisabled), "nothing to log yet");
        assertFalse(FxTestSupport.callOnFx(() -> mgr.isBreakpoint(1)), "no breakpoint before OK");
        FxTestSupport.runOnFx(() -> OverlayDriver.dismiss(fx.controller));
        assertFalse(FxTestSupport.callOnFx(() -> mgr.isBreakpoint(1)), "a cancelled form leaves nothing behind");

        FxTestSupport.runOnFx(() -> slot.fireEvent(click(true)));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> OverlayDriver.answer(fx.controller, "  x = {x}  "));
        assertEquals("x = {x}", FxTestSupport.callOnFx(() -> mgr.get(1).logMessage()));
        assertTrue(FxTestSupport.callOnFx(() -> mgr.get(1).isLogpoint()));
        assertTrue(FxTestSupport.callOnFx(() -> mgr.glyphClasses(1)).contains("logpoint"), "drawn as a diamond");

        // A plain click is still Toggle Breakpoint: it removes the logpoint.
        FxTestSupport.runOnFx(() -> slot.fireEvent(click(false)));
        assertFalse(FxTestSupport.callOnFx(() -> mgr.isBreakpoint(1)));

        // The command on a conditional breakpoint: the form opens on its message, and the condition stays.
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(2);
            mgr.setCondition(2, "x > 1");
            b.getArea().moveTo(2, 0);
            debug.addLogpointAtCaret();
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> OverlayDriver.answer(fx.controller, "y = {y}"));
        assertEquals("y = {y}", FxTestSupport.callOnFx(() -> mgr.get(2).logMessage()));
        assertEquals("x > 1", FxTestSupport.callOnFx(() -> mgr.get(2).condition()));
        FxTestSupport.runOnFx(() -> debug.addLogpointAtCaret());
        FxTestSupport.drainFx();
        assertEquals("y = {y}", FxTestSupport.callOnFx(() -> OverlayDriver.promptText(fx.controller)));
        FxTestSupport.runOnFx(() -> OverlayDriver.dismiss(fx.controller));

        // A blank line has no code to log from: no form.
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(4, 0);
            debug.addLogpointAtCaret();
        });
        assertFalse(OverlayDriver.showing(fx.controller));
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(2));
    }

    private static javafx.scene.input.MouseEvent click(boolean shift) {
        return new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                1,
                1,
                100,
                100,
                javafx.scene.input.MouseButton.PRIMARY,
                1,
                shift,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                null);
    }

    /** Whether the overlay card's OK button is disabled. FX thread. */
    private boolean okDisabled() {
        return OverlayDriver.card(fx.controller).lookupAll(".button").stream()
                .filter(n -> n instanceof javafx.scene.control.Button btn
                        && tr("dialog.ok").equals(btn.getText()))
                .findFirst()
                .orElseThrow()
                .isDisabled();
    }

    @Test
    void rightClickingABreakpointOffersEditDisableRemove() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(1));
        FxTestSupport.drainFx();
        Node slot = FxTestSupport.callOnFx(() -> {
            Node graphic = b.getArea().getParagraphGraphicFactory().apply(1);
            return find(graphic, "breakpoint-slot");
        });
        assertNotNull(slot);
        ContextMenu menu = FxTestSupport.field(debug, "gutterMenu");
        FxTestSupport.runOnFx(() -> slot.fireEvent(
                new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1, 1, 100, 100, false, null)));
        FxTestSupport.drainFx();
        List<String> labels = FxTestSupport.callOnFx(
                () -> menu.getItems().stream().map(MenuItem::getText).toList());
        assertEquals(
                java.util.Arrays.asList(
                        DebugPanelText.bareTitle(tr("command.debug.editBreakpoint")), // bare, as its neighbours (C110)
                        tr("debug.gutter.disable"),
                        tr("debug.gutter.remove"),
                        DebugPanelText.bareTitle(tr("command.debug.addTriggeredBreakpoint")),
                        DebugPanelText.bareTitle(tr("command.debug.moveBreakpointToGroup")),
                        null,
                        DebugPanelText.bareTitle(tr("command.debug.breakpoints"))),
                labels);
        FxTestSupport.runOnFx(() -> {
            menu.hide();
            menu.getItems().get(1).fire(); // Disable
        });
        assertFalse(FxTestSupport.callOnFx(() -> b.getBreakpointManager().get(1).enabled()));
        assertTrue(
                FxTestSupport.callOnFx(() -> b.getBreakpointManager().tooltip(1))
                        .contains(tr("debug.breakpoint.disabled")),
                "the glyph's hover says it is disabled (C11)");
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(1));
    }

    // --- gap: lambda breakpoints ------------------------------------------------------------------------------

    private static final String LAMBDAS = "import java.util.List;\n\npublic class Main {\n"
            + "    public static void main(String[] a) {\n"
            + "        List.of(1, 2).forEach(x -> System.out.println(x * 2));\n"
            + "        Runnable r = () -> {}; Runnable s = () -> {};\n"
            + "    }\n}\n";

    @Test
    void lambdaBreakpointsComeFromTheCaretAndTheGutterAndReachTheAdapterAsColumns() throws Exception {
        Path main = file("Main.java", LAMBDAS);
        EditorBuffer b = open(main);
        var mgr = FxTestSupport.callOnFx(b::getBreakpointManager);
        String forEach = LAMBDAS.lines().toList().get(4);
        String runnables = LAMBDAS.lines().toList().get(5);
        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().moveTo(4, 2); // before the lambda: the line's first one
            debug.toggleLambdaBreakpointAtCaret();
            b.getFocusedArea().moveTo(5, runnables.lastIndexOf("{}"));
            debug.toggleLambdaBreakpointAtCaret(); // the second Runnable
            b.getFocusedArea().moveTo(0, 3);
            debug.toggleLambdaBreakpointAtCaret(); // "import" starts no lambda
        });
        assertEquals(List.of(1), FxTestSupport.callOnFx(() -> mgr.get(4).lambdas()));
        assertTrue(FxTestSupport.callOnFx(() -> mgr.get(4).lambdasOnly()));
        assertEquals(List.of(2), FxTestSupport.callOnFx(() -> mgr.get(5).lambdas()));
        assertFalse(FxTestSupport.callOnFx(() -> mgr.isBreakpoint(0)));
        assertTrue(FxTestSupport.callOnFx(() -> mgr.styleClasses(4)).contains("lambda"), "the dot carries a λ");
        assertTrue(FxTestSupport.callOnFx(() -> mgr.tooltip(4)).contains(tr("debug.breakpoint.lambdasOnly", "1")));
        String title = DebugPanelText.bareTitle(tr("command.debug.toggleLambdaBreakpoint"));
        assertFalse(
                FxTestSupport.callOnFx(() -> labels(debug.editorMenuItems(b)).contains(title)),
                "the caret's line starts no lambda");
        FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(4, 2));
        assertTrue(FxTestSupport.callOnFx(() -> labels(debug.editorMenuItems(b)).contains(title)));

        // The gutter menu of line 6: on the line too, and the first lambda as well.
        FxTestSupport.drainFx();
        Node slot = FxTestSupport.callOnFx(
                () -> find(b.getArea().getParagraphGraphicFactory().apply(5), "breakpoint-slot"));
        ContextMenu menu = FxTestSupport.field(debug, "gutterMenu");
        FxTestSupport.runOnFx(() -> slot.fireEvent(
                new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1, 1, 100, 100, false, null)));
        FxTestSupport.drainFx();
        List<String> labels = FxTestSupport.callOnFx(
                () -> menu.getItems().stream().map(MenuItem::getText).toList());
        assertEquals(tr("debug.gutter.stopOnLine"), labels.get(6), labels.toString());
        assertEquals(tr("debug.gutter.stopInLambda", 1, "() -> {}; Runnable s = () -> {};"), labels.get(7));
        assertEquals(tr("debug.gutter.stopInLambda", 2, "() -> {};"), labels.get(8));
        var onLine = (javafx.scene.control.CheckMenuItem)
                FxTestSupport.callOnFx(() -> menu.getItems().get(6));
        assertFalse(FxTestSupport.callOnFx(onLine::isSelected));
        FxTestSupport.runOnFx(() -> {
            menu.hide();
            onLine.setSelected(true);
            onLine.fire();
        });
        FxTestSupport.runOnFx(() -> slot.fireEvent(
                new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1, 1, 100, 100, false, null)));
        var firstAgain = (javafx.scene.control.CheckMenuItem)
                FxTestSupport.callOnFx(() -> menu.getItems().get(7));
        FxTestSupport.runOnFx(() -> {
            menu.hide();
            firstAgain.setSelected(true); // as a click ticks it before its action runs
            firstAgain.fire();
        });
        assertEquals(List.of(1, 2), FxTestSupport.callOnFx(() -> mgr.get(5).lambdas()));
        assertFalse(FxTestSupport.callOnFx(() -> mgr.get(5).lambdasOnly()));
        assertTrue(
                FxTestSupport.callOnFx(() ->
                        fx.controller.config.getBreakpoints().get(DebugCoordinator.key(main)).stream()
                                .anyMatch(bp -> bp.line() == 5 && bp.lambdas().equals(List.of(1, 2)))),
                "kept with the project's breakpoints");

        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            // Every line breakpoint bound; of line 6's lambdas, the second one not (yet).
            adapter.sessionBreakpointAnswer = args -> java.util.Arrays.stream(args.getBreakpoints())
                    .map(sb -> FakeDebugAdapter.Session.breakpoint(
                            null,
                            sb.getColumn() == null || sb.getLine() == 5 || sb.getColumn() < 40,
                            sb.getLine(),
                            sb.getColumn() != null && sb.getColumn() >= 40 ? "not loaded yet" : null))
                    .toList();
            FakeDebugAdapter.Session session = connect(adapter, main);
            FxTestSupport.runOnFx(debug::toggleMuteBreakpoints); // muted and back: the breakpoints are sent again
            FxTestSupport.runOnFx(debug::toggleMuteBreakpoints);
            await(
                    "the file's breakpoints sent",
                    () -> session.breakpoints.stream()
                            .anyMatch(a ->
                                    a.getSource().getPath().endsWith("Main.java") && a.getBreakpoints().length == 4));
            var sent = session.breakpoints.stream()
                    .filter(a -> a.getSource().getPath().endsWith("Main.java"))
                    .reduce((x, y) -> y)
                    .orElseThrow()
                    .getBreakpoints();
            List<String> asked = java.util.Arrays.stream(sent)
                    .map(sb -> sb.getLine() + ":" + sb.getColumn())
                    .toList();
            assertEquals(
                    List.of(
                            "5:" + (forEach.indexOf("x ->") + 1),
                            "6:null",
                            "6:" + (runnables.indexOf("() ->") + 1),
                            "6:" + (runnables.lastIndexOf("() ->") + 1)),
                    asked);
            // One glyph per line: bound when the line and all its lambdas are.
            var bound = com.editora.editor.debug.BreakpointManager.LiveState.VERIFIED;
            await("line 5 bound", () -> mgr.live(4) != null && mgr.live(4).state() == bound);
            await(
                    "line 6 waiting on its second lambda",
                    () -> mgr.live(5) != null
                            && mgr.live(5).state() == com.editora.editor.debug.BreakpointManager.LiveState.PENDING);
            assertTrue(FxTestSupport.callOnFx(() -> mgr.tooltip(5)).contains("not loaded yet"));
        }

        // The command on line 5's only lambda again: the breakpoint stopped nowhere else, so it goes.
        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().moveTo(4, forEach.indexOf("System"));
            debug.toggleLambdaBreakpointAtCaret();
        });
        assertFalse(FxTestSupport.callOnFx(() -> mgr.isBreakpoint(4)));
        // Taking a lambda from a breakpoint that also stops on its line keeps it.
        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().moveTo(5, runnables.lastIndexOf("{}"));
            debug.toggleLambdaBreakpointAtCaret();
        });
        assertEquals(List.of(1), FxTestSupport.callOnFx(() -> mgr.get(5).lambdas()));
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(5));
    }

    @Test
    void lambdaBreakpointsAreForJava() throws Exception {
        Path util = file("util.py", "f = lambda x: x\ng = [y for y in range(3)]\n");
        EditorBuffer b = open(util);
        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().moveTo(0, 4);
            debug.toggleLambdaBreakpointAtCaret();
        });
        assertFalse(FxTestSupport.callOnFx(() -> b.getBreakpointManager().isBreakpoint(0)));
        assertFalse(FxTestSupport.callOnFx(() -> labels(debug.editorMenuItems(b))
                .contains(DebugPanelText.bareTitle(tr("command.debug.toggleLambdaBreakpoint")))));
    }

    // --- gap: method breakpoints and field watchpoints in the gutter ----------------------------------------

    @Test
    void aMethodBreakpointsMarkShowsInTheGutterAClickRemovesItAndItHasItsOwnMenu() throws Exception {
        Path order = file(
                "Order.java",
                "package shop;\n\npublic class Order {\n    int count;\n    long total() {\n        return count;\n"
                        + "    }\n}\n");
        EditorBuffer b = open(order);
        var mgr = FxTestSupport.callOnFx(b::getBreakpointManager);
        com.editora.config.MemberBreakpoint total = com.editora.config.MemberBreakpoint.method("shop.Order", "total");
        com.editora.config.MemberBreakpoint count = com.editora.config.MemberBreakpoint.field(
                        "shop.Order", "count", com.editora.config.MemberBreakpoint.WRITE)
                .withCondition("count > 2");
        FxTestSupport.runOnFx(() -> {
            debug.memberBreakpoints().add(total);
            debug.memberBreakpoints().add(count);
            // No language server here to read the outline: the marks go where it would put them.
            mgr.setMemberMarks(java.util.Map.of(
                    4,
                            new com.editora.editor.debug.BreakpointManager.MemberMark(
                                    List.of(total), MemberBreakpointMarks.tooltip(List.of(total))),
                    3, new com.editora.editor.debug.BreakpointManager.MemberMark(List.of(count), "")));
        });
        FxTestSupport.drainFx();
        assertEquals("method", FxTestSupport.callOnFx(() -> mgr.glyphClasses(4)));
        assertEquals("field conditional", FxTestSupport.callOnFx(() -> mgr.glyphClasses(3)));
        assertTrue(FxTestSupport.callOnFx(() -> mgr.glyphTooltip(4)).startsWith("Order.total()"));
        Node method = FxTestSupport.callOnFx(
                () -> find(b.getArea().getParagraphGraphicFactory().apply(4), "breakpoint-method"));
        assertNotNull(method, "the dot with an entry arrow");
        assertFalse(FxTestSupport.callOnFx(() -> mgr.isBreakpoint(4)), "not a line breakpoint");

        // Right-click: the watchpoint's own menu.
        Node slot = FxTestSupport.callOnFx(
                () -> find(b.getArea().getParagraphGraphicFactory().apply(3), "breakpoint-slot"));
        ContextMenu menu = FxTestSupport.field(debug, "gutterMenu");
        FxTestSupport.runOnFx(() -> slot.fireEvent(
                new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1, 1, 100, 100, false, null)));
        FxTestSupport.drainFx();
        assertEquals(
                java.util.Arrays.asList(
                        tr("debug.gutter.disable"),
                        tr("debug.gutter.removeFieldWatchpoint"),
                        null,
                        DebugPanelText.bareTitle(tr("command.debug.breakpoints"))),
                FxTestSupport.callOnFx(
                        () -> menu.getItems().stream().map(MenuItem::getText).toList()));
        FxTestSupport.runOnFx(() -> {
            menu.hide();
            menu.getItems().getFirst().fire(); // Disable
        });
        assertFalse(FxTestSupport.callOnFx(
                () -> debug.memberBreakpoints().all().get(1).enabled()));
        assertEquals("field disabled", FxTestSupport.callOnFx(() -> mgr.glyphClasses(3)), "greyed at once");

        // Muted: faded like every glyph.
        FxTestSupport.runOnFx(debug::toggleMuteBreakpoints);
        assertEquals("method muted", FxTestSupport.callOnFx(() -> mgr.glyphClasses(4)));
        FxTestSupport.runOnFx(debug::toggleMuteBreakpoints);

        // A click on the method's mark removes the method breakpoint (a line breakpoint is not set).
        java.util.function.BiConsumer<EditorBuffer, Integer> click = FxTestSupport.field(b, "gutterBreakpointClick");
        FxTestSupport.runOnFx(() -> click.accept(b, 4));
        assertEquals(
                List.of(count.withEnabled(false)),
                FxTestSupport.callOnFx(() -> debug.memberBreakpoints().all()));
        assertFalse(FxTestSupport.callOnFx(() -> mgr.isBreakpoint(4)));
        assertNull(FxTestSupport.callOnFx(() -> mgr.memberMark(4)), "its mark went with it");

        // The watchpoint's menu Remove; the line's plain click then sets a line breakpoint as before.
        FxTestSupport.runOnFx(() -> slot.fireEvent(
                new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1, 1, 100, 100, false, null)));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            menu.hide();
            menu.getItems().get(1).fire(); // Remove Field Watchpoint
        });
        assertTrue(FxTestSupport.callOnFx(() -> debug.memberBreakpoints().all().isEmpty()));
        assertTrue(FxTestSupport.callOnFx(() -> mgr.memberMarks().isEmpty()));
        FxTestSupport.runOnFx(() -> click.accept(b, 4));
        assertTrue(FxTestSupport.callOnFx(() -> mgr.isBreakpoint(4)));
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(4));
    }

    // --- an external adapter: its own program, reached as its transport says ---------------------------------

    @Test
    void anExternalAdapterIsReachedOverItsStreamsOnAPortItAnnouncesOrOnAPortItIsGiven() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(1));
        String jvm = ProcessHandle.current().info().command().orElse("java");
        String classPath = System.getProperty("java.class.path");
        for (com.editora.dap.ExternalAdapter.Transport transport : com.editora.dap.ExternalAdapter.Transport.values()) {
            try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
                String target = String.valueOf(adapter.port());
                List<String> mode = switch (transport) {
                    case STDIO -> List.of("stdio", target);
                    case ANNOUNCED_PORT -> List.of("announce", target);
                    case PORT_ARGUMENT -> List.of("listen", com.editora.dap.ExternalAdapter.PORT, target);
                };
                List<String> command = new java.util.ArrayList<>(
                        List.of("java-from-the-profile", "-cp", classPath, "com.editora.dap.RelayAdapterMain"));
                command.addAll(mode);
                com.editora.dap.DebugLanguage go = new com.editora.dap.DebugLanguage(
                        "go",
                        "Go",
                        java.util.Set.of("go"),
                        com.editora.dap.DapServerRegistry.Kind.EXTERNAL,
                        "go",
                        "go",
                        "",
                        List.of(),
                        null,
                        com.editora.dap.DebugLanguage.Availability.LOCATED,
                        List.of(),
                        null,
                        null,
                        false,
                        "status.debug.unavailable",
                        new com.editora.dap.ExternalAdapter(
                                command,
                                transport,
                                transport == com.editora.dap.ExternalAdapter.Transport.ANNOUNCED_PORT
                                        ? java.util.regex.Pattern.compile("DAP server listening at: \\S*:(\\d+)")
                                        : null));
                // Detection finds the command (here the JVM running this test): it is what starts the adapter.
                java.util.concurrent.CompletableFuture<Boolean> found = new java.util.concurrent.CompletableFuture<>();
                manager.configureExternal(go, true, jvm, found::complete);
                assertTrue(found.get(10, TimeUnit.SECONDS), "the JVM is found as " + jvm);
                FxTestSupport.runOnFx(() -> manager.startExternal(
                        util,
                        go,
                        com.editora.dap.ExternalAdapter.launchRequest(
                                "go",
                                util.toString(),
                                List.of("-v"),
                                util.getParent().toString(),
                                java.util.Map.of(),
                                false,
                                java.util.Map.of("mode", "debug"))));
                FakeDebugAdapter.Session session = adapter.awaitSession();
                assertNotNull(session, transport + ": the relay reached the adapter");
                session.awaitRequest("launch");
                assertEquals("go", session.initializeArgs.getAdapterID(), transport.toString());
                assertEquals("debug", session.launchArgs.get("mode"), "the language's own launch fields");
                assertEquals(util.toString(), session.launchArgs.get("program"));
                // After the adapter's `initialized` event, which may come after `launch`.
                await(
                        transport + ": the breakpoints were sent",
                        () -> session.breakpoints.stream()
                                .anyMatch(a -> a.getSource().getPath().endsWith("util.py")));
                await(transport + ": running", () -> manager.state() == DapManager.State.RUNNING);
                FxTestSupport.runOnFx(manager::stop);
                await(transport + ": stopped", () -> !manager.isActive());
                await(
                        transport + ": Stop ended the adapter's process",
                        () -> ProcessHandle.current()
                                .descendants()
                                .noneMatch(p -> p.isAlive()
                                        && p.info().commandLine().orElse("").contains("RelayAdapterMain")));
            }
        }
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(1));
    }

    // --- gap: the breakpoints list and Mute ----------------------------------------------------------------

    @Test
    void theBreakpointsListEnablesRemovesAndMuteTellsTheSessionNothing() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(1);
            b.toggleBreakpoint(2);
        });
        BreakpointsPicker picker = FxTestSupport.callOnFx(() -> new BreakpointsPicker(
                FxTestSupport.field(debug, "host"), FxTestSupport.field(debug, "ops"), manager, () -> {}, () -> false));
        List<BreakpointsPicker.Entry> entries = FxTestSupport.callOnFx(picker::entries);
        List<BreakpointsPicker.Entry> mine =
                entries.stream().filter(e -> e.file().equals(util)).toList();
        assertEquals(
                List.of(1, 2), mine.stream().map(BreakpointsPicker.Entry::line).toList());
        assertTrue(BreakpointsPicker.label(mine.get(0)).startsWith("util.py:2"), BreakpointsPicker.label(mine.get(0)));
        assertEquals("●", BreakpointsPicker.mark(mine.get(0).breakpoint())); // the kind is the row's glyph now

        FxTestSupport.runOnFx(() -> picker.toggleEnabled(mine.get(0)));
        assertFalse(FxTestSupport.callOnFx(() -> b.getBreakpointManager().get(1).enabled()));
        FxTestSupport.runOnFx(() -> picker.remove(mine.get(1)));
        assertFalse(FxTestSupport.callOnFx(() -> b.getBreakpointManager().isBreakpoint(2)));
        FxTestSupport.runOnFx(() -> b.getBreakpointManager().setEnabled(1, true));

        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            FxTestSupport.runOnFx(debug::toggleMuteBreakpoints);
            await(
                    "Mute did not tell the adapter",
                    () -> !session.breakpoints.isEmpty()
                            && session.breakpoints.getLast().getBreakpoints().length == 0);
            int sent = session.breakpoints.size();
            FxTestSupport.runOnFx(debug::toggleMuteBreakpoints);
            await(
                    "unmuting did not re-send",
                    () -> session.breakpoints.size() > sent
                            && session.breakpoints.getLast().getBreakpoints().length == 1);
        }
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(1));
    }

    // --- C4: the Debug window opens tall enough to use ------------------------------------------------------

    @Test
    void theDebugWindowFirstOpensAtTwoFifthsOfTheHeight() throws Exception {
        assertEquals(0.42, debugWindow().firstOpenShare(), 1e-9);
        double divider = FxTestSupport.callOnFx(() -> (Double) FxTestSupport.call(
                toolWindows(),
                "dividerFor",
                new Class<?>[] {ToolWindow.class, ToolWindow.Side.class},
                debugWindow(),
                ToolWindow.Side.BOTTOM));
        // A window the user has not sized yet: its first open leaves at most 58% to the editor (the side's
        // default left it 28% — one variable row in a 1000×650 window).
        if (!FxTestSupport.<com.editora.config.ConfigManager>field(fx.controller, "config")
                .getWorkspaceState()
                .getToolWindowSizes()
                .containsKey("debug")) {
            assertTrue(divider <= 0.58 + 1e-9, "divider " + divider);
        }
        SplitPane areas = FxTestSupport.field(panel, "areas");
        double[] at = FxTestSupport.callOnFx(areas::getDividerPositions);
        assertTrue(at[1] - at[0] > at[0] && at[1] - at[0] > 1 - at[1], "Variables is the widest column");
        assertNotEquals(0, at.length);
    }

    // --- follow-ups 5 and 6: completion in expression fields, the expandable value popup ---------------

    /** The text fields showing in the window's overlay card that complete expressions. FX thread. */
    private List<javafx.scene.control.TextField> completingFields() {
        List<javafx.scene.control.TextField> out = new java.util.ArrayList<>();
        for (Node n : FxTestSupport.<Stage>field(fx.controller, "stage")
                .getScene()
                .getRoot()
                .lookupAll(".text-field")) {
            OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
            if (n instanceof javafx.scene.control.TextField f
                    && overlay.contains(f)
                    && f.isVisible()
                    && DebugCompletion.of(f) != null) {
                out.add(f);
            }
        }
        return out;
    }

    /** The overlay card around the breakpoint form's fields. FX thread. */
    private Node breakpointCard() {
        Node card = completingFields().getFirst();
        while (!card.getStyleClass().contains("overlay-form")) {
            card = card.getParent();
        }
        return card;
    }

    /**
     * The breakpoint form hugs its fields: its wrapping hint used to ask for its whole text on one line, which
     * stretched the card across the window and squeezed the field labels to an ellipsis.
     */
    @Test
    void theBreakpointFormHugsItsFieldsAndWrapsItsHint() throws Exception {
        EditorBuffer b = open(file("util.py", UTIL));
        OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(1, 0);
            debug.editBreakpointAtCaret();
        });
        // The card is laid out on a later pulse (macOS CI measured it before any: 0 px wide).
        await(
                "the breakpoint form to be laid out",
                () -> breakpointCard().getLayoutBounds().getWidth() > 0);
        FxTestSupport.runOnFx(() -> {
            Node card = breakpointCard();
            double width = card.getLayoutBounds().getWidth();
            assertTrue(width > 0 && width < 700, "the card is " + width + " px wide");
            double height = card.getLayoutBounds().getHeight();
            // A hint left on one line made the card ~600 px tall; six rows of fields and a wrapped hint are ~405.
            assertTrue(height < 450, "the card is " + height + " px tall: the hint wraps where it is laid out");
            assertLabelsWhole(card);
            // The filters, folded away until their link opens them: then their labels are whole too.
            javafx.scene.control.Hyperlink filters =
                    (javafx.scene.control.Hyperlink) card.lookup(".debug-breakpoint-filters-toggle");
            filters.fire();
            javafx.scene.Parent root = card.getScene().getRoot();
            root.applyCss();
            root.layout(); // the overlay sizes the card again, as the next pulse does
            assertLabelsWhole(card);
            assertTrue(
                    card.lookupAll(".label").stream()
                            .anyMatch(n -> n instanceof javafx.scene.control.Label l
                                    && l.getText().equals(tr("dialog.debug.threadFilterLabel"))
                                    && l.getWidth() > 0),
                    "the thread filter is shown");
        });
        FxTestSupport.runOnFx(overlay::hide);
    }

    /**
     * Follow-up 5: the watch prompt and a breakpoint's condition and log message complete in the stopped frame
     * (a log message only inside its braces), and the editor's value popup is the expandable one (follow-up 6).
     */
    @Test
    void theWatchPromptAndTheBreakpointFormCompleteAndTheHoverUnfolds() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
        FxTestSupport.runOnFx(() -> panel.addWatchPrompt("na"));
        FxTestSupport.drainFx();
        assertEquals(1, FxTestSupport.callOnFx(this::completingFields).size(), "the watch prompt's field completes");
        FxTestSupport.runOnFx(overlay::hide);
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(1, 0);
            debug.editBreakpointAtCaret();
        });
        FxTestSupport.drainFx();
        assertEquals(2, FxTestSupport.callOnFx(this::completingFields).size(), "the condition and the log message");
        FxTestSupport.runOnFx(overlay::hide);
        assertSame(
                debug.valuePopup(),
                FxTestSupport.callOnFx(() -> FxTestSupport.field(FxTestSupport.field(b, "debugHover"), "presenter")));

        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            org.eclipse.lsp4j.debug.CompletionItem names = new org.eclipse.lsp4j.debug.CompletionItem();
            names.setLabel("names");
            adapter.completions = List.of(names);
            FakeDebugAdapter.Session session = connect(adapter, util);
            stopAt(session, util, 3, "breakpoint");
            List<List<com.editora.dap.DapModels.CompletionTarget>> got = new java.util.ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                assertTrue(debug.completionSource(false).paused());
                debug.completionSource(true).complete("n={na", 5, got::add);
            });
            await("no completions came back", () -> !got.isEmpty());
            var asked = session.completionRequests.getFirst();
            assertEquals("na", asked.getText(), "only the braced expression of a log message is code");
            assertEquals(3, asked.getColumn());
            assertEquals("names", got.getFirst().getFirst().label());
        }
    }

    /** The menu's labels, each submenu followed by its own rows (separators dropped). FX thread. */
    private static List<String> labels(List<MenuItem> items) {
        List<String> out = new java.util.ArrayList<>();
        for (MenuItem item : items) {
            if (item instanceof javafx.scene.control.SeparatorMenuItem) {
                continue;
            }
            out.add(item.getText());
            if (item instanceof javafx.scene.control.Menu sub) {
                out.addAll(labels(sub.getItems()));
            }
        }
        return out;
    }

    /** Every label of the card's form that is shown is whole; the form's hint is not clipped. */
    private static void assertLabelsWhole(Node card) {
        for (Node n : card.lookupAll(".label")) {
            if (n instanceof javafx.scene.control.Label l
                    && l.getParent() instanceof javafx.scene.layout.GridPane grid
                    && grid.isVisible()) {
                double w = l.getWidth();
                if (l.getStyleClass().contains("settings-note")) {
                    assertTrue(l.getHeight() >= l.prefHeight(w) - 0.5, "the hint is not clipped");
                } else {
                    assertTrue(w >= l.prefWidth(-1) - 0.5, l.getText() + " is shown whole");
                }
            }
        }
    }
}
