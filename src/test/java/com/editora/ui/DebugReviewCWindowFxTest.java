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
            set(manager, "enabled", true);
            set(manager, "pythonEnabled", true);
            set(manager, "pythonAvailable", true);
            set(manager, "pythonCommand", fx.configDir.resolve("no-such-python").toString());
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

    // --- C10 / C11: the editor's and the gutter's debugger menus --------------------------------------------

    @Test
    void theEditorMenuOffersBreakpointsAndWhilePausedRunToCursorShowValueAndWatch() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        List<String> idle = FxTestSupport.callOnFx(
                () -> debug.editorMenuItems(b).stream().map(MenuItem::getText).toList());
        assertEquals(
                List.of(
                        DebugPanelText.bareTitle(tr("command.debug.toggleBreakpoint")),
                        DebugPanelText.bareTitle(tr("command.debug.editBreakpoint"))),
                idle);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            stopAt(session, util, 3, "breakpoint");
            FxTestSupport.runOnFx(() -> b.getArea().moveTo(2, 5)); // on "y" of "y = x * 2"
            List<String> paused = FxTestSupport.callOnFx(() ->
                    debug.editorMenuItems(b).stream().map(MenuItem::getText).toList());
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
        List<String> labels = FxTestSupport.callOnFx(
                () -> debug.editorMenuItems(b).stream().map(MenuItem::getText).toList());
        assertEquals(tr("editmenu.debugFile"), labels.getFirst(), labels.toString());
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
                        tr("command.debug.editBreakpoint"),
                        tr("debug.gutter.disable"),
                        tr("debug.gutter.remove"),
                        null,
                        tr("command.debug.breakpoints")),
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
        assertTrue(
                BreakpointsPicker.label(mine.get(0)).startsWith("● util.py:2"), BreakpointsPicker.label(mine.get(0)));

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
}
