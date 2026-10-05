package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.Breakpoint;
import com.editora.dap.DapClient;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.VariablesArgumentsFilter;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Debug panel and its editor surfaces in a real window, against a scripted adapter: what a stop, a step,
 * an expanded variable, a rejected value and a renamed file do to what the user sees and to what is stored.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DebugUiFlowFxTest {

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

    /** A fresh file per test, so no test sees another's tab, breakpoints or stored state. */
    private Path file(String name, String text) throws Exception {
        Path dir = fx.configDir.resolve("case" + (++files));
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

    /** Puts the window's own manager into a live session with {@code adapter}, as after a launch of {@code file}. */
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

    /** The manager reporting {@code state}, as its own start / end paths do. FX thread. */
    private void state(DapManager.State state) {
        FxTestSupport.call(manager, "setState", new Class<?>[] {DapManager.State.class}, state);
    }

    private void awaitState(DapManager.State wanted) throws Exception {
        await("the session never became " + wanted, () -> manager.state() == wanted);
    }

    /** Waits until {@code condition} holds on the FX thread. */
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

    private void stopAt(FakeDebugAdapter.Session session, Path file, int line1) throws Exception {
        session.framePath = file.toString();
        session.frameLine = line1;
        session.stop(7, "breakpoint");
        awaitState(DapManager.State.SUSPENDED);
    }

    private TreeView<DebugPanel.VarRow> tree() {
        return FxTestSupport.field(panel, "variables");
    }

    private ListView<DapModels.StackFrameInfo> stack() {
        return FxTestSupport.field(panel, "stack");
    }

    /** The row called {@code names…} from the top of the variables tree, or null. FX thread. */
    private TreeItem<DebugPanel.VarRow> row(String... names) {
        TreeItem<DebugPanel.VarRow> at = tree().getRoot();
        for (String name : names) {
            TreeItem<DebugPanel.VarRow> next = null;
            for (TreeItem<DebugPanel.VarRow> child : at.getChildren()) {
                if (child.getValue().name().equals(name)) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            at = next;
        }
        return at;
    }

    private TreeItem<DebugPanel.VarRow> awaitRow(String... names) throws Exception {
        await("no variables row " + List.of(names), () -> row(names) != null);
        return FxTestSupport.callOnFx(() -> row(names));
    }

    private String status() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Label echo = FxTestSupport.field(FxTestSupport.field(fx.controller, "statusBar"), "echo");
            return echo.getText();
        });
    }

    private List<String> statusLog() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            MessageLog log = FxTestSupport.field(FxTestSupport.field(fx.controller, "statusBar"), "messageLog");
            return log.entries().stream().map(MessageLog.Entry::text).toList();
        });
    }

    private List<Tab> tabs() {
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        return area.tabs();
    }

    private void select(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Tab tab = (Tab) FxTestSupport.call(fx.controller, "tabForPath", new Class[] {Path.class}, file);
            EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
            area.select(tab);
        });
        FxTestSupport.drainFx();
    }

    private void close(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Tab tab = (Tab) FxTestSupport.call(fx.controller, "tabForPath", new Class[] {Path.class}, file);
            FxTestSupport.call(fx.controller, "closeTab", new Class[] {Tab.class}, tab);
        });
        FxTestSupport.drainFx();
    }

    private boolean debugWindowOpen() {
        ToolWindowManager windows = FxTestSupport.field(fx.controller, "toolWindows");
        return windows.isOpen(FxTestSupport.<ToolWindow>field(fx.controller, "debugToolWindow"));
    }

    /** The 0-based lines carrying the execution-line highlight. */
    private List<Integer> execLines(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<Integer> out = new ArrayList<>();
            var area = b.getArea();
            for (int i = 0; i < area.getParagraphs().size(); i++) {
                var style = area.getParagraph(i).getParagraphStyle();
                if (style != null && style.contains("exec-line")) {
                    out.add(i);
                }
            }
            return out;
        });
    }

    private Map<String, List<Breakpoint>> stored() {
        return FxTestSupport.<DebugCoordinator.Ops>field(debug, "ops").breakpointMap();
    }

    private List<Integer> storedLines(Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<Breakpoint> list = stored().get(file.toString());
            return list == null ? null : list.stream().map(Breakpoint::line).toList();
        });
    }

    private String console() throws Exception {
        return FxTestSupport.callOnFx(
                () -> FxTestSupport.<CodeArea>field(panel, "console").getText());
    }

    // --- D2-1: large containers ----------------------------------------------------------------------

    /**
     * A {@code byte[65536]} in scope: expanding it asked the adapter for every element and added them to the
     * expanded tree item one by one, freezing the window. With the element count reported, only a page is
     * requested, and the next one only when asked for.
     */
    @Test
    void aLargeArrayIsRequestedAndShownAPageAtATime() throws Exception {
        Path util = file("util.py", UTIL);
        open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            assertEquals(Boolean.TRUE, session.initializeArgs.getSupportsVariablePaging());
            session.variables.put(
                    FakeDebugAdapter.Session.LOCALS,
                    List.of(session.array("buf", 60, 65_536), FakeDebugAdapter.Session.variable("x", "1", 0)));
            stopAt(session, util, 2);

            TreeItem<DebugPanel.VarRow> buf = awaitRow("Locals", "buf");
            FxTestSupport.runOnFx(() -> buf.setExpanded(true));
            await("the first page never arrived", () -> buf.getChildren().size() > 1);

            assertEquals(
                    DebugValues.PAGE_SIZE + 1,
                    FxTestSupport.callOnFx(() -> buf.getChildren().size()));
            VariablesArguments first = session.variableRequests.get(session.variableRequests.size() - 1);
            assertEquals(60, first.getVariablesReference());
            assertEquals(VariablesArgumentsFilter.INDEXED, first.getFilter());
            assertEquals(0, first.getStart());
            assertEquals(DebugValues.PAGE_SIZE, first.getCount());
            TreeItem<DebugPanel.VarRow> more =
                    FxTestSupport.callOnFx(() -> buf.getChildren().get(DebugValues.PAGE_SIZE));
            assertEquals(DebugPanel.Kind.MORE, more.getValue().kind());
            assertEquals(
                    tr("debugpanel.showMore", DebugValues.PAGE_SIZE, 65_536 - DebugValues.PAGE_SIZE),
                    more.getValue().name());

            // Enter on the "show more" row fetches the next page, and only that.
            FxTestSupport.runOnFx(() -> {
                tree().getSelectionModel().select(more);
                tree().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
            });
            await("the second page never arrived", () -> buf.getChildren().size() > DebugValues.PAGE_SIZE + 1);
            assertEquals(
                    2 * DebugValues.PAGE_SIZE + 1,
                    FxTestSupport.callOnFx(() -> buf.getChildren().size()));
            VariablesArguments second = session.variableRequests.get(session.variableRequests.size() - 1);
            assertEquals(DebugValues.PAGE_SIZE, second.getStart());
            assertEquals(DebugValues.PAGE_SIZE, second.getCount());
            assertEquals(
                    "[" + DebugValues.PAGE_SIZE + "]",
                    FxTestSupport.callOnFx(() -> buf.getChildren()
                            .get(DebugValues.PAGE_SIZE)
                            .getValue()
                            .name()));
        }
    }

    /**
     * An adapter that reports no counts, or ignores {@code start}/{@code count}, sends every element. The tree
     * still takes a page of them, not 60,000 rows.
     */
    @Test
    void anAdapterThatSendsEverythingStillFillsTheTreeAPageAtATime() throws Exception {
        Path util = file("util.py", UTIL);
        open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            session.pagingHonoured = false;
            session.arrays.put(61, 60_000); // no count reported for this one
            session.variables.put(
                    FakeDebugAdapter.Session.LOCALS,
                    List.of(FakeDebugAdapter.Session.variable("items", "list", 61), session.array("buf", 62, 30_000)));
            stopAt(session, util, 2);

            for (String name : List.of("items", "buf")) {
                TreeItem<DebugPanel.VarRow> item = awaitRow("Locals", name);
                FxTestSupport.runOnFx(() -> item.setExpanded(true));
                await(name + " never loaded", () -> !item.getChildren().isEmpty());
                assertEquals(
                        DebugValues.PAGE_SIZE + 1,
                        FxTestSupport.callOnFx(() -> item.getChildren().size()),
                        name);
                TreeItem<DebugPanel.VarRow> more =
                        FxTestSupport.callOnFx(() -> item.getChildren().get(DebugValues.PAGE_SIZE));
                FxTestSupport.runOnFx(() -> {
                    tree().getSelectionModel().select(more);
                    tree().fireEvent(new KeyEvent(
                            KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                });
                await(name + ": no second page", () -> item.getChildren().size() == 2 * DebugValues.PAGE_SIZE + 1);
            }
        }
    }

    // --- D2-2 / D2-17: frames without source, and the reveal's status echo ----------------------------

    /**
     * Pausing a thread that sits in JDK code: java-debug names the top frame's source as a {@code jdt:} URI.
     * Opening it flashed a tab open and closed and reported "Failed to open" as an error on every such stop.
     */
    @Test
    void aStopInAFrameWithoutSourceShowsTheNearestFrameThatHasOne() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        String jdt =
                "jdt://contents/java.base/java.lang/Thread.java?=demo/%5C/usr%5C/lib%5C/jvm%3Cjava.lang(Thread.class";
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            session.frames = List.of(
                    FakeDebugAdapter.Session.frame(1, "Thread.sleep", jdt, 509),
                    FakeDebugAdapter.Session.frame(2, "work", util.toString(), 3));
            List<Integer> tabCounts = new ArrayList<>();
            int before = FxTestSupport.callOnFx(() -> tabs().size());
            javafx.animation.AnimationTimer watch = new javafx.animation.AnimationTimer() {
                @Override
                public void handle(long now) {
                    tabCounts.add(tabs().size()); // every pulse: a tab that flashes open is seen
                }
            };
            FxTestSupport.runOnFx(watch::start);
            session.stop(7, "pause");
            awaitState(DapManager.State.SUSPENDED);
            await(
                    "the frame with source was not selected",
                    () -> stack().getSelectionModel().getSelectedIndex() == 1);
            await("no execution line", () -> !execLines(b).isEmpty());
            assertEquals(List.of(2), execLines(b));
            assertEquals(tr("status.debug.stoppedWithoutSource", "Thread.sleep"), status());

            // The frame itself stays selectable: its variables load, nothing is opened, nothing fails.
            int scopes = java.util.Collections.frequency(session.requests, "scopes");
            FxTestSupport.runOnFx(() -> stack().getSelectionModel().select(0));
            await(
                    "the source-less frame's variables were not requested",
                    () -> java.util.Collections.frequency(session.requests, "scopes") > scopes);
            assertEquals(tr("status.debug.noSource", "Thread.sleep"), status());
            assertEquals(List.of(), execLines(b), "the line of another frame is not where this one is");
            Thread.sleep(200);
            FxTestSupport.runOnFx(watch::stop);
            assertEquals(List.of(before), tabCounts.stream().distinct().toList(), "no tab may flash open");
            assertFalse(
                    statusLog().stream().anyMatch(m -> m.contains("jdt:")),
                    "nothing about the URI is reported: " + statusLog());
            assertFalse(
                    statusLog().contains(tr("status.alreadyOpen", "util.py")),
                    statusLog().toString());
        }
    }

    // --- D2-3 / D2-15: adapter errors -----------------------------------------------------------------

    /** A value the adapter refuses was written into the row as if it had been set, and nothing was reported. */
    @Test
    void aRejectedSetValueLeavesTheRowAndReportsTheAdaptersMessage() throws Exception {
        Path util = file("util.py", UTIL);
        open(util);
        OverlayInput.Prompt original = FxTestSupport.field(panel, "prompt");
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            session.variables.put(
                    FakeDebugAdapter.Session.LOCALS, List.of(FakeDebugAdapter.Session.variable("x", "1", 0)));
            stopAt(session, util, 2);
            TreeItem<DebugPanel.VarRow> x = awaitRow("Locals", "x");
            String[] typed = {"not an int"};
            FxTestSupport.runOnFx(() -> panel.setPrompt((title, label, initial, accept) -> accept.accept(typed[0])));

            session.setVariableFailure = "Failed to set variable: incompatible types";
            FxTestSupport.runOnFx(() -> {
                tree().getSelectionModel().select(x);
                panel.setSelectedValue();
            });
            session.awaitRequest("setVariable");
            await("the refusal was not reported", () -> status().contains("incompatible types"));
            assertEquals(tr("status.debug.error", "Failed to set variable: incompatible types"), status());
            assertEquals("1", FxTestSupport.callOnFx(() -> x.getValue().value()), "the debuggee still has 1");

            session.setVariableFailure = null;
            typed[0] = "5";
            FxTestSupport.runOnFx(() -> {
                tree().getSelectionModel().select(x);
                panel.setSelectedValue();
            });
            await("an accepted value must show", () -> "5".equals(x.getValue().value()));

            // The same unwrapping for a failed evaluation: the adapter's words, not the Java exception class.
            TextField eval = FxTestSupport.field(panel, "evalInput");
            FxTestSupport.runOnFx(() -> {
                eval.setText("bad + 1");
                eval.fireEvent(new javafx.event.ActionEvent());
            });
            await("the evaluation error never arrived", () -> console().contains("Cannot evaluate"));
            assertTrue(console().contains("error: Cannot evaluate: bad + 1\n"), console());
            assertFalse(console().contains("Exception"), console());
        } finally {
            FxTestSupport.runOnFx(() -> panel.setPrompt(original));
        }
    }

    // --- D2-4: breakpoints follow a renamed / re-saved file -------------------------------------------

    @Test
    void breakpointsFollowARenameAndASaveAs() throws Exception {
        Path a = file("a.py", UTIL);
        Path c = a.resolveSibling("c.py");
        Path d = a.resolveSibling("d.py");
        EditorBuffer b = open(a);
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(1);
            b.toggleBreakpoint(3);
        });
        assertEquals(List.of(1, 3), storedLines(a));

        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller, "renameFileTo", new Class[] {EditorBuffer.class, Path.class, Path.class}, b, a, c));
        FxTestSupport.drainFx();
        assertEquals(c, FxTestSupport.callOnFx(b::getPath));
        assertEquals(List.of(1, 3), storedLines(c), "the renamed file keeps its breakpoints");
        assertNull(storedLines(a), "and the path that no longer exists is not armed in later sessions");

        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"),
                "applySaveAsTarget",
                new Class[] {EditorBuffer.class, Path.class},
                b,
                d));
        await("Save As never finished", () -> Files.exists(d) && d.equals(b.getPath()) && !b.isDirty());
        assertEquals(List.of(1, 3), storedLines(d), "the copy has them");
        assertEquals(List.of(1, 3), storedLines(c), "and the original, still on disk, keeps its own");

        // They are there for the next open of the file, which is what was lost.
        close(d);
        EditorBuffer reopened = open(d);
        assertEquals(
                List.of(1, 3),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(reopened.getBreakpointManager().lines())));
    }

    // --- D2-5 / D3-14: the panel at the default tool-window height --------------------------------------

    /** Stacked on top of each other, the three areas left the call-stack list 0 px at the default height. */
    @Test
    void theCallStackHasRowsAtTheDefaultToolWindowHeight() throws Exception {
        Path util = file("util.py", UTIL);
        open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            session.frames = List.of(
                    FakeDebugAdapter.Session.frame(1, "work", util.toString(), 3),
                    FakeDebugAdapter.Session.frame(2, "go", util.toString(), 8),
                    FakeDebugAdapter.Session.frame(3, "<module>", util.toString(), 8));
            FxTestSupport.runOnFx(() -> state(DapManager.State.STARTING)); // opens the Debug window, as a launch does
            FxTestSupport.runOnFx(() -> state(DapManager.State.RUNNING));
            session.stop(7, "breakpoint");
            awaitState(DapManager.State.SUSPENDED);
            await("the stack never filled", () -> stack().getItems().size() == 3);
            assertTrue(FxTestSupport.callOnFx(this::debugWindowOpen));
            FxTestSupport.runOnFx(() -> {
                Stage stage = FxTestSupport.field(fx.controller, "stage");
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
            });
            FxTestSupport.drainFx();
            double listHeight = FxTestSupport.callOnFx(() -> stack().getHeight());
            double rowHeight = FxTestSupport.callOnFx(() -> stack().lookupAll(".list-cell").stream()
                    .filter(Node::isVisible)
                    .mapToDouble(n -> n.getBoundsInLocal().getHeight())
                    .max()
                    .orElse(0));
            double panelHeight = FxTestSupport.callOnFx(panel::getHeight);
            assertTrue(panelHeight < 260, "this is about the default strip, not a tall window: " + panelHeight);
            assertTrue(rowHeight > 0 && rowHeight <= 24, "frame rows are dense: " + rowHeight);
            assertTrue(listHeight >= 3 * rowHeight, "all three frames fit: list " + listHeight + ", row " + rowHeight);
            assertTrue(FxTestSupport.callOnFx(() -> tree().getHeight()) >= 60);
            assertTrue(FxTestSupport.callOnFx(() ->
                            FxTestSupport.<CodeArea>field(panel, "console").getHeight())
                    >= 40);
        }
    }

    // --- D2-6 / D2-18: the window and Stop while a session starts or ends ---------------------------------

    @Test
    void theDebugWindowSurvivesATabSwitchWhileASessionStartsAndWhenItEnds() throws Exception {
        Path code = file("a.py", UTIL);
        Path notes = file("notes.txt", "one\ntwo\n");
        open(notes);
        open(code);
        FxTestSupport.runOnFx(() -> state(DapManager.State.STARTING)); // jdtls resolving / building / connecting
        assertTrue(FxTestSupport.callOnFx(this::debugWindowOpen));
        assertFalse(FxTestSupport.callOnFx(manager::isActive), "there is no adapter connection yet");
        assertTrue(FxTestSupport.callOnFx(debug::sessionLive), "but there is a session to stop");
        Chrome.PaletteContext context = FxTestSupport.callOnFx(
                () -> (Chrome.PaletteContext) FxTestSupport.call(fx.controller, "paletteContext", new Class[] {}));
        assertTrue(context.debugActive(), "Stop / Restart are offered in the menu and palette while starting");

        select(notes);
        assertTrue(FxTestSupport.callOnFx(this::debugWindowOpen), "a peek at a text file must not close it");
        select(code);
        select(notes);
        FxTestSupport.runOnFx(() -> state(DapManager.State.INACTIVE)); // the program ended, or crashed
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(this::debugWindowOpen), "its last output stays on screen");
    }

    // --- D2-7: the variables tree across a step ---------------------------------------------------------

    @Test
    void expandedVariablesAndTheSelectionSurviveAStep() throws Exception {
        Path util = file("util.py", UTIL);
        open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            session.variables.put(
                    FakeDebugAdapter.Session.LOCALS,
                    List.of(
                            FakeDebugAdapter.Session.variable("n", "0", 0),
                            FakeDebugAdapter.Session.variable("obj", "<Foo>", 5)));
            session.variables.put(
                    5,
                    List.of(
                            FakeDebugAdapter.Session.variable("a", "1", 0),
                            FakeDebugAdapter.Session.variable("inner", "<Bar>", 6)));
            session.variables.put(6, List.of(FakeDebugAdapter.Session.variable("z", "9", 0)));
            stopAt(session, util, 2);
            TreeItem<DebugPanel.VarRow> obj = awaitRow("Locals", "obj");
            FxTestSupport.runOnFx(() -> obj.setExpanded(true));
            TreeItem<DebugPanel.VarRow> inner = awaitRow("Locals", "obj", "inner");
            FxTestSupport.runOnFx(() -> {
                inner.setExpanded(true);
                tree().getSelectionModel().select(inner);
            });
            awaitRow("Locals", "obj", "inner", "z");

            TreeItem<DebugPanel.VarRow> oldRoot = FxTestSupport.callOnFx(() -> tree().getRoot());
            FxTestSupport.runOnFx(manager::stepOver);
            session.awaitRequest("next");
            stopAt(session, util, 3);
            await("the tree was not rebuilt for the new stop", () -> tree().getRoot() != oldRoot);

            TreeItem<DebugPanel.VarRow> z = awaitRow("Locals", "obj", "inner", "z");
            assertNotEquals(inner, z.getParent(), "the tree was rebuilt for the new stop");
            await(
                    "the selection did not come back",
                    () -> tree().getSelectionModel().getSelectedItem() != null);
            assertEquals(
                    "inner",
                    FxTestSupport.callOnFx(() -> tree().getSelectionModel()
                            .getSelectedItem()
                            .getValue()
                            .name()));
        }
    }

    // --- D2-8: the previous stop's frames while the program runs ------------------------------------------

    @Test
    void aStaleFrameClickedWhileRunningPaintsNoExecutionLine() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            session.frames = List.of(
                    FakeDebugAdapter.Session.frame(1, "work", util.toString(), 3),
                    FakeDebugAdapter.Session.frame(2, "go", util.toString(), 8));
            session.variables.put(
                    FakeDebugAdapter.Session.LOCALS, List.of(FakeDebugAdapter.Session.variable("obj", "<Foo>", 5)));
            session.stop(7, "breakpoint");
            awaitState(DapManager.State.SUSPENDED);
            await("no execution line", () -> execLines(b).equals(List.of(2)));
            TreeItem<DebugPanel.VarRow> obj = awaitRow("Locals", "obj");

            FxTestSupport.runOnFx(manager::resume);
            awaitState(DapManager.State.RUNNING);
            assertEquals(List.of(), execLines(b));
            int scopes = java.util.Collections.frequency(session.requests, "scopes");
            int variables = session.variableRequests.size();
            FxTestSupport.runOnFx(() -> {
                stack().getSelectionModel().select(1);
                obj.setExpanded(true);
            });
            Thread.sleep(150);
            FxTestSupport.drainFx();
            assertEquals(List.of(), execLines(b), "a running program is not 'here'");
            assertEquals(scopes, java.util.Collections.frequency(session.requests, "scopes"));
            assertEquals(variables, session.variableRequests.size(), "a dead reference is not asked for");
        }
    }

    // --- D2-10 / D2-11 / D2-12 / D2-19: breakpoint bookkeeping ---------------------------------------------

    /** The debounce remembered one buffer: of two files shifted within 300 ms, only the last was written. */
    @Test
    void lineShiftsInTwoFilesAreBothPersistedAndATogglePersistsAtOnce() throws Exception {
        Path a = file("a.py", UTIL);
        Path other = file("b.py", UTIL);
        EditorBuffer ab = open(a);
        EditorBuffer bb = open(other);
        FxTestSupport.runOnFx(() -> {
            ab.toggleBreakpoint(1);
            bb.toggleBreakpoint(2);
            assertEquals(
                    List.of(1),
                    stored().get(a.toString()).stream().map(Breakpoint::line).toList());
            assertEquals(
                    List.of(2),
                    stored().get(other.toString()).stream()
                            .map(Breakpoint::line)
                            .toList());
            ab.getArea().insertText(0, "# header\n"); // one pulse: both files' breakpoints move down a line
            bb.getArea().insertText(0, "# header\n");
        });
        await("b.py's shifted breakpoint was not written", () -> List.of(3).equals(storedLines(other)));
        assertEquals(List.of(2), storedLines(a), "the first file's change must not be dropped for the second's");
    }

    @Test
    void editBreakpointOnABareLineLeavesNothingBehindWhenCancelled() throws Exception {
        Path a = file("a.py", UTIL);
        EditorBuffer b = open(a);
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(2, 0);
            registry.run("debug.editBreakpoint");
        });
        FxTestSupport.drainFx();
        assertEquals(
                List.of(),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(b.getBreakpointManager().lines())),
                "looking at the form creates nothing");
        FxTestSupport.runOnFx(() -> {
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            Node focused = stage.getScene().getFocusOwner();
            assertTrue(focused instanceof TextField, "the form is open: " + focused);
            javafx.event.Event.fireEvent(
                    focused, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
        });
        FxTestSupport.drainFx();
        assertEquals(
                List.of(),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(b.getBreakpointManager().lines())));
        assertNull(storedLines(a));

        // Accepted, it creates the breakpoint with what was entered.
        FxTestSupport.runOnFx(() -> registry.run("debug.editBreakpoint"));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            TextField condition = (TextField) stage.getScene().getFocusOwner();
            condition.setText("x > 1");
            javafx.event.Event.fireEvent(
                    condition, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
        await(
                "the accepted breakpoint was not created",
                () -> b.getBreakpointManager().isBreakpoint(2));
        assertEquals(
                "x > 1",
                FxTestSupport.callOnFx(() -> b.getBreakpointManager().get(2).condition()));
    }

    @Test
    void aFileWithoutADebuggerGetsNoInvisibleBreakpoint() throws Exception {
        Path notes = file("notes.txt", "one\ntwo\nthree\n");
        EditorBuffer b = open(notes);
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            assertFalse(b.isBreakpointsEnabled(), "a text file has no breakpoint gutter");
            b.getArea().moveTo(1, 0);
            registry.run("debug.toggleBreakpoint");
        });
        FxTestSupport.drainFx();
        assertEquals(
                List.of(),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(b.getBreakpointManager().lines())));
        assertNull(storedLines(notes));
        assertEquals(tr("status.debug.noBreakpointsHere"), status());
    }

    @Test
    void undoBringsBackTheBreakpointOfADeletedLine() throws Exception {
        Path a = file("a.py", UTIL);
        EditorBuffer b = open(a);
        FxTestSupport.runOnFx(() -> {
            b.getArea().getUndoManager().forgetHistory();
            b.toggleBreakpoint(2);
            b.getBreakpointManager().setCondition(2, "x > 1");
            var area = b.getArea();
            area.deleteText(area.getAbsolutePosition(2, 0), area.getAbsolutePosition(3, 0));
        });
        FxTestSupport.drainFx();
        assertEquals(
                List.of(),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(b.getBreakpointManager().lines())));
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        FxTestSupport.drainFx();
        assertEquals(
                List.of(2),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(b.getBreakpointManager().lines())));
        assertEquals(
                "x > 1",
                FxTestSupport.callOnFx(() -> b.getBreakpointManager().get(2).condition()));
        await("the restored breakpoint was not persisted", () -> List.of(2).equals(storedLines(a)));
    }

    // --- D2-13: a thread that is not suspended -------------------------------------------------------------

    @Test
    void pickingARunningThreadStaysOnTheStoppedOneAndSaysWhy() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer b = open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            session.runningThreads.add(8);
            stopAt(session, util, 2);
            ComboBox<DapModels.ThreadInfo> threads = FxTestSupport.field(panel, "threads");
            await("the threads never listed", () -> threads.getItems().size() == 2);
            await("no execution line", () -> execLines(b).equals(List.of(1)));

            FxTestSupport.runOnFx(() -> threads.setValue(threads.getItems().get(1)));
            await(
                    "nothing said the thread is running",
                    () -> tr("status.debug.threadRunning").equals(status()));
            await(
                    "the dropdown did not return to the stopped thread",
                    () -> threads.getValue().id() == 7);
            await("steps must go to the stopped thread again", () -> manager.currentThreadId() == 7);
            assertEquals(1, FxTestSupport.callOnFx(() -> stack().getItems().size()));
            assertEquals(List.of(1), execLines(b));
        }
    }

    // --- D2-14: the stopped file closed and reopened ---------------------------------------------------------

    @Test
    void reopeningTheStoppedFileShowsTheExecutionLineAgain() throws Exception {
        Path util = file("util.py", UTIL);
        EditorBuffer first = open(util);
        Path other = file("other.py", UTIL);
        open(other);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            stopAt(session, util, 3);
            await("no execution line", () -> execLines(first).equals(List.of(2)));

            close(util);
            EditorBuffer reopened = open(util);
            assertNotEquals(first, reopened);
            await(
                    "the reopened file has no execution line",
                    () -> execLines(reopened).equals(List.of(2)));
        }
    }

    // --- D2-16 / D3-7 / D3-8 / D3-11: the console and the status ---------------------------------------------

    @Test
    void eachLaunchStartsWithAnEmptyConsoleAndColourCodesAreNotShown() throws Exception {
        Path util = file("util.py", UTIL);
        open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            session.output("\u001B[31mred text\u001B[0m plain\n");
            await("the output never arrived", () -> console().contains("plain"));
            assertEquals("red text plain\n", console());
        }
        FxTestSupport.runOnFx(manager::stop);
        FxTestSupport.drainFx();
        assertEquals("red text plain\n", console(), "a finished session's output stays to be read");
        FxTestSupport.runOnFx(() -> state(DapManager.State.STARTING));
        assertEquals("", console(), "the next launch does not append to the last one's output");
    }

    /** The stderr class was applied but every editor theme's {@code .editor-area .text} rule overrode its fill. */
    @Test
    void stderrIsDrawnInItsOwnColour() throws Exception {
        Path util = file("util.py", UTIL);
        open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            FxTestSupport.runOnFx(() -> state(DapManager.State.STARTING));
            FxTestSupport.runOnFx(() -> state(DapManager.State.RUNNING));
            session.output("plain line\n", "stdout");
            session.output("error line\n", "stderr");
            await("the output never arrived", () -> console().contains("error line"));
            FxTestSupport.runOnFx(() -> {
                Stage stage = FxTestSupport.field(fx.controller, "stage");
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
            });
            FxTestSupport.drainFx();
            CodeArea area = FxTestSupport.field(panel, "console");
            List<javafx.scene.paint.Paint> fills = FxTestSupport.callOnFx(() -> {
                area.applyCss();
                area.layout();
                javafx.scene.paint.Paint plain = null;
                javafx.scene.paint.Paint error = null;
                for (Node n : area.lookupAll(".text")) {
                    if (n instanceof Text t && t.getText().contains("line")) {
                        if (t.getStyleClass().contains("run-stderr")) {
                            error = t.getFill();
                        } else {
                            plain = t.getFill();
                        }
                    }
                }
                return java.util.Arrays.asList(plain, error);
            });
            assertTrue(fills.get(0) != null && fills.get(1) != null, "both lines are on screen: " + fills);
            assertNotEquals(fills.get(0), fills.get(1), "stderr must not be drawn in the stdout ink");
        }
    }

    @Test
    void thePanelSaysWhatItCannotDoInsteadOfIgnoringTheUser() throws Exception {
        List<String> evaluated = new ArrayList<>();
        DebugPanel idle = FxTestSupport.callOnFx(() ->
                new DebugPanel((DebugPanel.Actions) java.lang.reflect.Proxy.newProxyInstance(
                        DebugPanel.Actions.class.getClassLoader(),
                        new Class[] {DebugPanel.Actions.class},
                        (p, m, a) -> {
                            if (m.getName().equals("evaluate")) {
                                evaluated.add((String) a[0]);
                            }
                            return null;
                        })));
        FxTestSupport.runOnFx(() -> {
            TreeView<DebugPanel.VarRow> variables = FxTestSupport.field(idle, "variables");
            CodeArea console = FxTestSupport.field(idle, "console");
            Label state = FxTestSupport.field(idle, "status");
            TextField eval = FxTestSupport.field(idle, "evalInput");

            // D2-20: a watch added with no program suspended is in the tree, to be seen, edited and removed.
            idle.setWatches(List.of("x * 2"));
            TreeItem<DebugPanel.VarRow> watches =
                    variables.getRoot().getChildren().get(0);
            assertEquals(
                    List.of(DebugPanel.Kind.WATCH, DebugPanel.Kind.ADD_WATCH),
                    watches.getChildren().stream().map(i -> i.getValue().kind()).toList());
            assertEquals("x * 2", watches.getChildren().get(0).getValue().name());
            idle.setState(DapManager.State.INACTIVE);
            assertEquals(
                    2, variables.getRoot().getChildren().get(0).getChildren().size());

            // D3-8: Enter while the program runs evaluates nothing — and now says so, once.
            idle.setState(DapManager.State.RUNNING);
            eval.setText("hello");
            eval.fireEvent(new javafx.event.ActionEvent());
            eval.fireEvent(new javafx.event.ActionEvent());
            assertEquals(List.of(), evaluated);
            assertEquals(tr("debugpanel.evalNeedsPause") + "\n", console.getText());
            assertEquals("hello", eval.getText());

            // D3-11: an exception stop does not look like a breakpoint hit.
            idle.setState(DapManager.State.SUSPENDED);
            assertEquals(tr("debugpanel.state.suspended"), state.getText());
            idle.setStopReason("exception");
            assertEquals(tr("debugpanel.state.exception"), state.getText());
            idle.setState(DapManager.State.RUNNING);
            idle.setStopReason(null);
            idle.setState(DapManager.State.SUSPENDED);
            assertEquals(tr("debugpanel.state.suspended"), state.getText());
        });
    }

    // --- D2-9: inline values ----------------------------------------------------------------------------------

    /** The frame's values were painted on every visible line naming {@code x} or {@code n} — other functions too. */
    @Test
    void inlineValuesAreScopedToTheStoppedFunction() throws Exception {
        Path util = file(
                "scoped.py",
                "def work(n):\n    x = n + 1\n    return x\n\n\ndef other(items):\n    x = 'unrelated'\n    n = len(items)\n    return x, n\n");
        EditorBuffer b = open(util);
        try (FakeDebugAdapter adapter = new FakeDebugAdapter(false)) {
            FakeDebugAdapter.Session session = connect(adapter, util);
            session.variables.put(
                    FakeDebugAdapter.Session.LOCALS,
                    List.of(
                            FakeDebugAdapter.Session.variable("n", "0", 0),
                            FakeDebugAdapter.Session.variable("x", "1", 0)));
            stopAt(session, util, 2);
            Object overlay = FxTestSupport.field(b, "inlineValues");
            await("the inline values never arrived", () -> FxTestSupport.field(overlay, "values") != null);
            assertEquals(1, (int) FxTestSupport.callOnFx(() -> FxTestSupport.<Integer>field(overlay, "frameLine")));
        }
    }
}
