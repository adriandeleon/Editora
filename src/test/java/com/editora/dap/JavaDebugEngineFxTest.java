package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import javafx.application.Platform;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixer B's session-engine fixes, against {@link FakeDebugAdapter} over a real socket and a fake jdtls: the
 * state while java-debug starts the program (D3), a lost adapter ending the program at once and saying so
 * (B2, B12), and controls held while a console evaluation runs (B11).
 */
@Tag("fx")
class JavaDebugEngineFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        com.editora.i18n.Messages.init("en");
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private LspManager lsp;
    private DapManager dap;
    private FakeDebugAdapter adapter;
    private List<FakeLanguageServer> fakes;
    private final List<String> events = new ArrayList<>();
    private final List<ExecuteCommandParams> commands = new CopyOnWriteArrayList<>();
    private final Map<String, Function<ExecuteCommandParams, Object>> replies = new java.util.HashMap<>();
    private final List<Process> spawned = new CopyOnWriteArrayList<>();
    private Path file;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new FakeDebugAdapter(false);
        lsp = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessions(lsp);
        lsp.configure(true, Map.of("java", "jdtls"));
        dap = new DapManager(lsp);
        onFx(() -> {
            dap.configure(true, dir.resolve("no-plugin-here").toString());
            dap.setServerProvidesJavaDebug(true);
            dap.setBreakpointsSupplier(List::of);
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {
                    event(state.name());
                }

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                    event("stopped:" + threadId);
                }

                @Override
                public void onOutput(String text, String category) {}

                @Override
                public void onError(String message) {
                    event("error:" + message);
                }

                @Override
                public void onAdapterLost() {
                    event("adapterLost");
                }

                @Override
                public void onStatus(String message) {
                    event("status:" + message);
                }
            });
        });
        replies.put("vscode.java.startDebugSession", p -> adapter.port());
        root = dir.resolve("proj");
        file = root.resolve("src/main/java/App.java");
        String source = "public class App {\n  public static void main(String[] a) {\n    int x = 1;\n  }\n}\n";
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        lsp.openDocument(file, root, "java", source);
        for (FakeLanguageServer fake : fakes) {
            fake.executeCommandHandler = params -> {
                commands.add(params);
                Function<ExecuteCommandParams, Object> reply = replies.get(params.getCommand());
                return reply == null ? null : reply.apply(params);
            };
        }
        Path classes = Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(classes.resolve("App.class"), "compiled");
        replies.put(
                "vscode.java.resolveMainClass",
                p -> List.of(Map.of("mainClass", "App", "projectName", "proj", "filePath", file.toString())));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(classes.toString())));
        replies.put("vscode.java.resolveJavaExecutable", p -> "/opt/jdk/bin/java");
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(dap::shutdown);
        lsp.shutdownAll();
        adapter.close();
        for (Process p : spawned) {
            p.destroyForcibly();
        }
    }

    // --- harness --------------------------------------------------------------------------------------

    private void event(String event) {
        synchronized (events) {
            events.add(event);
            events.notifyAll();
        }
    }

    private List<String> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    private void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        synchronized (events) {
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError(what + " never happened; events=" + events);
                }
                TimeUnit.NANOSECONDS.timedWait(events, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            }
        }
    }

    private void awaitEvent(String event) throws InterruptedException {
        await(event, () -> events.contains(event));
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = new Throwable[1];
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread ran the action");
        if (failure[0] != null) {
            throw new AssertionError("the action failed on the FX thread", failure[0]);
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        Object[] result = new Object[1];
        onFx(() -> {
            try {
                result[0] = action.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked")
        T value = (T) result[0];
        return value;
    }

    private final DapManager.MainClassPicker noPicker = (options, chosen) -> chosen.accept(null);

    private FakeDebugAdapter.Session launch() throws Exception {
        onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertTrue(session.configured.await(10, TimeUnit.SECONDS), "the adapter was configured");
        awaitEvent("RUNNING");
        return session;
    }

    private Process sleeper() throws Exception {
        Process p = new ProcessBuilder("sleep", "60").start();
        spawned.add(p);
        return p;
    }

    // --- D3: Running only once java-debug has the program ----------------------------------------------

    @Test
    void theSessionSaysRunningOnlyWhenTheAdapterHasAnsweredTheLaunch() throws Exception {
        adapter.launchGate = new CompletableFuture<>();
        onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        onFx(() -> {}); // whatever the request queued on the FX thread has run

        assertEquals(DapManager.State.STARTING, onFx(dap::state), "still starting while the JVM is launched");

        adapter.launchGate.complete(null);
        awaitEvent("RUNNING");
    }

    // --- B4 / B9: java-debug is told the step filters and statics before each session ---------------------

    @Test
    void javaDebugGetsItsSettingsBeforeTheSessionStarts() throws Exception {
        launch();
        List<String> names =
                commands.stream().map(ExecuteCommandParams::getCommand).toList();
        int settings = names.indexOf(JavaDebugSettings.COMMAND);
        assertTrue(settings >= 0, "the settings were sent: " + names);
        assertTrue(settings < names.indexOf("vscode.java.startDebugSession"), names.toString());
        String json = String.valueOf(commands.get(settings).getArguments().get(0));
        assertTrue(json.contains("\"showStaticVariables\":true"), json);
        assertTrue(json.contains("\"java.*\""), json);
    }

    @Test
    void stepFiltersAndStaticsFollowTheSettings() throws Exception {
        onFx(() -> dap.setJavaDebugSettings(new JavaDebugSettings(false, false, List.of())));
        launch();
        String json = commands.stream()
                .filter(c -> c.getCommand().equals(JavaDebugSettings.COMMAND))
                .map(c -> String.valueOf(c.getArguments().get(0)))
                .findFirst()
                .orElseThrow();
        assertTrue(json.contains("\"showStaticVariables\":false"), json);
        assertFalse(json.contains("java.*"), json);
    }

    @Test
    void aJdtlsThatRefusesTheSettingsStillDebugs() throws Exception {
        replies.put(JavaDebugSettings.COMMAND, p -> {
            throw new IllegalStateException("unknown command");
        });
        launch(); // reaches Running all the same
    }

    // --- B2: the program java-debug launched ends with a lost adapter ---------------------------------

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aProgramTheAdapterLaunchedIsEndedWhenTheAdapterVanishes() throws Exception {
        FakeDebugAdapter.Session session = launch();
        Process program = sleeper();
        session.processId(program.pid());
        onFx(() -> {}); // the processid event has been read
        session.stop(1, "breakpoint");
        awaitEvent("stopped:1");

        session.dropConnection(); // jdtls was killed: no terminated event

        awaitEvent("INACTIVE");
        assertTrue(program.waitFor(10, TimeUnit.SECONDS), "the program was ended, not left running");
        assertTrue(events().contains("adapterLost"), "the user is told why the session ended: " + events());
    }

    // --- B12: a program Editora started ends at once, with a reason --------------------------------------

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLostAdapterEndsEditorasOwnProgramAtOnceAndSaysWhy() throws Exception {
        onFx(() -> dap.setProgramConsole(true));
        FakeDebugAdapter.Session session = launch();
        Integer pid = session.runInTerminal(dir.toString(), List.of("sleep", "60"), Map.of())
                .get(10, TimeUnit.SECONDS)
                .getProcessId();
        ProcessHandle program = ProcessHandle.of(pid).orElseThrow();

        session.dropConnection();

        awaitEvent("adapterLost");
        awaitEvent("INACTIVE");
        assertTrue(program.onExit().get(10, TimeUnit.SECONDS) != null);
        assertFalse(program.isAlive());
    }

    @Test
    void anOrdinaryEndIsNotReportedAsALostAdapter() throws Exception {
        FakeDebugAdapter.Session session = launch();
        session.terminate();
        awaitEvent("INACTIVE");
        session.dropConnection();
        onFx(() -> {});
        assertFalse(events().contains("adapterLost"), events().toString());
    }

    // --- B11: controls wait for a console evaluation -----------------------------------------------------

    @Test
    void stepAndContinueWaitForAConsoleEvaluationAndSayWhy() throws Exception {
        FakeDebugAdapter.Session session = launch();
        session.stop(1, "breakpoint");
        awaitEvent("stopped:1");
        CompletableFuture<org.eclipse.lsp4j.debug.EvaluateResponse> slow = new CompletableFuture<>();
        adapter.evaluateGate = slow;
        List<String> results = new CopyOnWriteArrayList<>();
        onFx(() -> dap.evaluate("slow()", 1, "repl", results::add));
        session.awaitRequest("evaluate");

        onFx(() -> {
            dap.stepOver();
            dap.resume();
        });

        assertFalse(session.requests.contains("next"), "no step was queued behind the evaluation");
        assertFalse(session.requests.contains("continue"));
        assertTrue(events().stream().anyMatch(e -> e.startsWith("status:")), events().toString());

        org.eclipse.lsp4j.debug.EvaluateResponse done = new org.eclipse.lsp4j.debug.EvaluateResponse();
        done.setResult("1");
        slow.complete(done);
        await("the evaluation answered", () -> !results.isEmpty());
        onFx(dap::stepOver);
        session.awaitRequest("next");
    }

    @Test
    void aTimeoutSaysTheExpressionIsStillRunningNotAJavaClassName() {
        String text = DapManager.msg(
                new java.util.concurrent.CompletionException(new java.util.concurrent.TimeoutException()));
        assertFalse(text.contains("TimeoutException"), text);
        assertTrue(text.contains(String.valueOf(DapClient.requestTimeoutSeconds())), text);
        assertFalse(DapManager.stillEvaluating().contains("Exception"));
    }
}
