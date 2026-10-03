package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the real vscode-js-debug adapter and Node: a breakpoint must hit, the call stack
 * must name the original source line, stepping must advance it, and the session must end with the program.
 * Run with {@code ./mvnw test -Dtest=JsDebugProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 *
 * <p>js-debug debugs the program on a second connection it asks for with {@code startDebugging}; until
 * that reverse request was implemented a JavaScript session started, reported RUNNING, and never stopped
 * anywhere. {@code FakeDebugAdapter} covers the handshake in the default suite; this is the real thing.
 */
@Tag("probe")
@Tag("fx")
class JsDebugProbeFxTest {

    private record Stop(String reason, List<DapModels.StackFrameInfo> frames) {}

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aNodeBreakpointHitsStepsAndTheSessionEndsWithTheProgram(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path home = Path.of(System.getProperty("user.home"));
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", home).isPresent(), "needs vscode-js-debug");
        Path script = dir.resolve("app.js");
        Files.writeString(script, "const a = 20;\nconst b = a + 22;\nconsole.log('answer', b);\n");
        Path real = script.toRealPath();

        DapManager manager = new DapManager(null);
        BlockingQueue<Stop> stops = new LinkedBlockingQueue<>();
        List<DapManager.State> states = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        StringBuffer output = new StringBuffer();
        CountDownLatch ended = new CountDownLatch(1);
        manager.setListener(new DapManager.Listener() {
            private boolean started;

            @Override
            public void onState(DapManager.State state) {
                states.add(state);
                started |= state == DapManager.State.RUNNING;
                if (started && state == DapManager.State.INACTIVE) {
                    ended.countDown();
                }
            }

            @Override
            public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                stops.add(new Stop(reason, frames));
            }

            @Override
            public void onOutput(String text, String category) {
                output.append(text);
            }

            @Override
            public void onError(String message) {
                errors.add(message);
            }
        });
        // A breakpoint on the second line (0-based line 1).
        manager.setBreakpointsSupplier(() ->
                List.of(new DapModels.FileBreakpoints(real, List.of(new DapModels.LineBreakpoint(1, null, null)))));
        CompletableFuture<Boolean> detected = new CompletableFuture<>();
        FxTestSupport.runOnFx(() -> {
            manager.configure(true, "", false, "", true, "");
            manager.detectJs(detected::complete);
        });
        assumeTrue(detected.get(60, TimeUnit.SECONDS), "needs node on the PATH");
        try {
            FxTestSupport.runOnFx(() -> manager.startLaunch(real, "javascript", null));

            Stop atBreakpoint = stops.poll(60, TimeUnit.SECONDS);
            assertNotNull(atBreakpoint, "the breakpoint never hit; errors: " + errors + " states: " + states);
            assertEquals(1, atBreakpoint.frames().get(0).line(), "stopped on the breakpoint's line");
            assertEquals(real, atBreakpoint.frames().get(0).file().toRealPath());
            assertEquals(DapManager.State.SUSPENDED, FxTestSupport.callOnFx(manager::state));

            FxTestSupport.runOnFx(manager::stepOver);
            assertEquals(DapManager.State.RUNNING, FxTestSupport.callOnFx(manager::state));
            Stop afterStep = stops.poll(60, TimeUnit.SECONDS);
            assertNotNull(afterStep, "the step never completed; errors: " + errors);
            assertEquals(2, afterStep.frames().get(0).line(), "Step Over advances one line");

            FxTestSupport.runOnFx(manager::resume);
            assertTrue(ended.await(60, TimeUnit.SECONDS), "the session must end when the program does: " + states);
            assertTrue(output.toString().contains("answer 42"), "program output: " + output);
        } finally {
            FxTestSupport.runOnFx(manager::shutdown);
        }
    }
}
