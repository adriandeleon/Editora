package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.editora.dap.DapClient;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import org.eclipse.lsp4j.debug.SetBreakpointsArguments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1: editing a file during a session. The program keeps running the code it was started with, so breakpoint
 * lines must reach the adapter in that code's numbering and stops must be painted on the line the user's edited
 * document now has for it. Before, one line typed at the top moved every marker and Editora sent the moved
 * lines: the breakpoints landed on other statements and the next stop was painted one line too high.
 */
@Tag("fx")
class DebugLaunchedLinesFxTest {

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setPythonDebugEnabled(true);
            set(manager, "enabled", true);
            set(manager, "pythonEnabled", true);
            set(manager, "pythonAvailable", true);
        });
        adapter = new FakeDebugAdapter(false);
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(manager::stop);
        adapter.close();
        fx.dispose();
    }

    private static void set(Object target, String name, Object value) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class[] {Path.class}, file));
        await("the file opened", () -> {
            EditorBuffer b = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            return b != null
                    && file.equals(b.getPath())
                    && !FxTestSupport.callOnFx(b::getContent).isEmpty();
        });
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
    }

    private interface Check {
        boolean holds() throws Exception;
    }

    private static void await(String what, Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!check.holds()) {
            assertTrue(System.nanoTime() < deadline, what + " never happened");
            Thread.sleep(10);
        }
        FxTestSupport.drainFx();
    }

    /** A live session of the window's own manager on {@code adapter}, started as a launch starts one. */
    private FakeDebugAdapter.Session connect(Path file) throws Exception {
        long epoch =
                (Long) FxTestSupport.callOnFx(() -> FxTestSupport.call(manager, "beginSession", new Class<?>[] {}));
        FxTestSupport.runOnFx(() -> {
            set(manager, "debugFile", file);
            // STARTING is where the coordinator takes the launched text, as on every launch
            FxTestSupport.call(manager, "setState", new Class<?>[] {DapManager.State.class}, DapManager.State.STARTING);
        });
        DapClient client = new DapClient(
                (DapClient.Host) FxTestSupport.call(manager, "sessionHost", new Class<?>[] {long.class}, epoch));
        client.connect(adapter.port(), "python").get(10, TimeUnit.SECONDS);
        FakeDebugAdapter.Session session = adapter.awaitSession();
        assertTrue(session.configured.await(10, TimeUnit.SECONDS));
        assertTrue((Boolean) FxTestSupport.call(
                manager, "publishClient", new Class<?>[] {long.class, DapClient.class}, epoch, client));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                manager, "setState", new Class<?>[] {DapManager.State.class}, DapManager.State.RUNNING));
        return session;
    }

    /** The 1-based lines of the last {@code setBreakpoints} the adapter got. */
    private static List<Integer> lastSent(FakeDebugAdapter.Session session) {
        List<SetBreakpointsArguments> all = session.breakpoints;
        List<Integer> out = new ArrayList<>();
        if (all.isEmpty()) {
            return out;
        }
        for (var b : all.get(all.size() - 1).getBreakpoints()) {
            out.add(b.getLine());
        }
        return out;
    }

    private int execLine(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            var area = b.getArea();
            for (int i = 0; i < area.getParagraphs().size(); i++) {
                var style = area.getParagraph(i).getParagraphStyle();
                if (style != null && style.contains("exec-line")) {
                    return i;
                }
            }
            return -1;
        });
    }

    @Test
    void editsDuringASessionKeepBreakpointsAndStopsOnTheCodeTheProgramRuns() throws Exception {
        Path file = fx.configDir.resolve("app.py");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            text.append("line").append(i).append("()\n");
        }
        Files.writeString(file, text);
        EditorBuffer b = open(file);
        FxTestSupport.runOnFx(() -> {
            b.toggleBreakpoint(4);
            b.toggleBreakpoint(7);
        });
        FakeDebugAdapter.Session session = connect(file);
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(9)); // a change made live: the file's set goes out
        await("the set was sent", () -> lastSent(session).equals(List.of(5, 8, 10)));
        int sends = session.breakpoints.size();

        // one line typed at the top, not saved
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "added()\n"));
        FxTestSupport.drainFx();
        assertEquals(
                sends,
                session.breakpoints.size(),
                "the markers moved with their text, but the program's lines did not: nothing to re-send; sent "
                        + lastSent(session));

        // a stop on the program's line 8 (1-based) is the document's line 9 now
        session.framePath = file.toString();
        session.frameLine = 8;
        session.stop(1, "breakpoint");
        await("the stop was shown", () -> execLine(b) >= 0);
        assertEquals(8, execLine(b), "painted on line8(), where the program is (0-based 8 after the insert)");
        assertEquals(
                "line7()",
                FxTestSupport.callOnFx(
                        () -> b.getArea().getParagraph(execLine(b)).getText()));
        String changed = com.editora.i18n.Messages.tr("status.debug.changedSinceLaunch", file.getFileName());
        String status = FxTestSupport.callOnFx(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            MessageLog log = FxTestSupport.field(bar, "messageLog");
            return log.entries().stream().map(MessageLog.Entry::text).toList().toString();
        });
        assertTrue(status.contains(changed), status);

        // a breakpoint on the added line has no code in the running program: it is not sent
        FxTestSupport.runOnFx(() -> b.toggleBreakpoint(0));
        FxTestSupport.drainFx();
        assertEquals(List.of(5, 8, 10), lastSent(session));
        assertEquals(
                com.editora.i18n.Messages.tr("debug.breakpoint.addedSinceLaunch"),
                FxTestSupport.callOnFx(() -> b.getBreakpointManager().live(0).tooltip()));

        // after a hot code replace the program runs the edited text: its numbering is used from then on
        FxTestSupport.runOnFx(() -> debug.rebaselineLines(file));
        await("re-sent in the new numbering", () -> lastSent(session).equals(List.of(1, 6, 9, 11)));
    }

    @Test
    void theMappingFollowsInsertionsAndDeletions() {
        var map = LaunchedLines.Mapping.between(List.of("a", "b", "c", "d"), List.of("x", "a", "c", "d", "y"));
        assertTrue(map.changed());
        assertEquals(-1, map.toLaunched(0), "x was added");
        assertEquals(0, map.toLaunched(1));
        assertEquals(2, map.toLaunched(2));
        assertEquals(-1, map.toLaunched(4));
        assertEquals(1, map.toCurrent(0));
        assertEquals(2, map.toCurrent(1), "b was deleted: the next line that survived");
        assertEquals(3, map.toCurrent(3));
        assertEquals(
                5, LaunchedLines.Mapping.between(List.of("a"), List.of("a")).toCurrent(5), "identity");
    }
}
