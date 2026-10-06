package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.application.Platform;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A debugged Java program that reads {@code System.in} used to wait forever: java-debug's
 * {@code internalConsole} gives it no standard input. With {@link DapManager#setProgramConsole} the launch
 * asks for {@code console: integratedTerminal}, the adapter sends {@code runInTerminal}, and Editora starts
 * the program itself. These drive {@link DapManager} against a scripted adapter that plays java-debug's side
 * of that exchange (recorded from 0.53.2: an argv array, the cwd, the launch's own env; no {@code output}
 * events; {@code disconnect} does not end the process) and a <em>real</em> child process.
 */
@Tag("fx")
class DebugProgramConsoleFxTest {

    /** Echoes two lines, writes one to stderr, reports end of input, and exits with 3. */
    private static final String ECHO = """
            public class Echo {
                public static void main(String[] args) throws Exception {
                    var in = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                    System.out.println("cwd=" + System.getProperty("user.dir"));
                    System.out.println("env=" + System.getenv("EDITORA_TEST_SET") + "/" + System.getenv("EDITORA_TEST_UNSET"));
                    System.out.print("first? ");
                    System.out.flush();
                    String one = in.readLine();
                    System.out.println("one <" + one + ">");
                    System.err.println("warn: between");
                    String two = in.readLine();
                    System.out.println("two <" + two + ">");
                    System.out.println("eof=" + (in.readLine() == null));
                    System.exit(3);
                }
            }
            """;

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private LspManager lsp;
    private DapManager dap;
    private FakeDebugAdapter adapter;
    private List<FakeLanguageServer> fakes;
    private Path file;
    private Path echo;
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<String[]> output = new CopyOnWriteArrayList<>();
    private final List<Long> started = new ArrayList<>();

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
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {
                    events.add(state.name());
                }

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {}

                @Override
                public void onOutput(String text, String category) {
                    output.add(new String[] {category, text});
                }

                @Override
                public void onError(String message) {
                    errors.add(message);
                }

                @Override
                public void onProgramInput(boolean available) {
                    events.add("input=" + available);
                }

                @Override
                public void onProgramExit(int code) {
                    events.add("exit=" + code);
                }
            });
        });
        // A project file whose class jdtls has: the launch goes straight to the adapter.
        file = dir.resolve("proj/src/main/java/Echo.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, ECHO);
        lsp.openDocument(file, dir.resolve("proj"), "java", ECHO);
        Path classes = Files.createDirectories(dir.resolve("proj/target/classes"));
        Files.writeString(classes.resolve("Echo.class"), "compiled");
        Map<String, Object> replies = Map.of(
                "vscode.java.startDebugSession", adapter.port(),
                "vscode.java.resolveMainClass",
                        List.of(Map.of("mainClass", "Echo", "projectName", "proj", "filePath", file.toString())),
                "vscode.java.resolveClasspath", List.of(List.of(), List.of(classes.toString())),
                "vscode.java.resolveJavaExecutable", javaExec());
        for (FakeLanguageServer fake : fakes) {
            fake.executeCommandHandler = params -> replies.get(params.getCommand());
        }
        // What the "adapter" has the client run: the real program, through the JDK's source launcher.
        echo = Files.writeString(Files.createDirectories(dir.resolve("work")).resolve("Echo.java"), ECHO);
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(dap::shutdown);
        lsp.shutdownAll();
        adapter.close();
        for (long pid : started) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread ran the action");
    }

    private static String javaExec() {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                .toString();
    }

    private void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(
                        what + " never happened; events=" + events + " errors=" + errors + " output=" + text(null));
            }
            Thread.sleep(20);
        }
    }

    /** Everything written so far in {@code category} (null: any), in arrival order. */
    private String text(String category) {
        StringBuilder all = new StringBuilder();
        for (String[] chunk : output) {
            if (category == null || category.equals(chunk[0])) {
                all.append(chunk[1]);
            }
        }
        return all.toString();
    }

    /** Launches with the program console on and returns the adapter's session once it has the launch. */
    private FakeDebugAdapter.Session launch() throws Exception {
        onFx(() -> {
            dap.setProgramConsole(true);
            dap.startLaunch(file, "java", (options, chosen) -> chosen.accept(null), javaExec(), dir.resolve("proj"));
        });
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        return session;
    }

    /** java-debug's answer to that launch: "run this yourself". Returns the process the client started. */
    private ProcessHandle runInTerminal(FakeDebugAdapter.Session session) throws Exception {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("EDITORA_TEST_SET", "yes");
        env.put("EDITORA_TEST_UNSET", null);
        Integer pid = session.runInTerminal(dir.resolve("work").toString(), List.of(javaExec(), echo.toString()), env)
                .get(20, TimeUnit.SECONDS)
                .getProcessId();
        started.add(pid.longValue());
        return ProcessHandle.of(pid).orElseThrow(() -> new AssertionError("no process " + pid));
    }

    @Test
    void theLaunchAsksForTheProgramToBeHandedBackAndAnAttachDoesNot() throws Exception {
        FakeDebugAdapter.Session session = launch();
        assertEquals("integratedTerminal", session.launchArgs.get("console"));
        assertEquals(Boolean.TRUE, session.initializeArgs.getSupportsRunInTerminalRequest());
        assertEquals(List.of(), errors);

        onFx(() -> dap.startAttach(file, "localhost", 5005));
        FakeDebugAdapter.Session attached = adapter.awaitSession();
        attached.awaitRequest("attach");
        assertNotEquals(Boolean.TRUE, attached.initializeArgs.getSupportsRunInTerminalRequest());
        assertFalse(attached.launchArgs.containsKey("console"));
        // An attach has no program of its own: asked anyway, it answers with an error, as every session did.
        assertThrows(
                ExecutionException.class,
                () -> attached.runInTerminal(dir.toString(), List.of(javaExec(), "-version"), Map.of())
                        .get(10, TimeUnit.SECONDS));
    }

    @Test
    void withTheSettingOffTheAdapterStartsTheProgramAsBefore() throws Exception {
        onFx(() -> dap.startLaunch(
                file, "java", (options, chosen) -> chosen.accept(null), javaExec(), dir.resolve("proj")));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertEquals("internalConsole", session.launchArgs.get("console"));
        assertNotEquals(Boolean.TRUE, session.initializeArgs.getSupportsRunInTerminalRequest());
        assertThrows(
                ExecutionException.class,
                () -> session.runInTerminal(dir.toString(), List.of(javaExec(), "-version"), Map.of())
                        .get(10, TimeUnit.SECONDS));
        assertFalse(dap.programInputAvailable());
    }

    @Test
    void typedLinesReachTheProgramItsOutputIsShownAndItsExitEndsTheSession() throws Exception {
        FakeDebugAdapter.Session session = launch();
        ProcessHandle program = runInTerminal(session);
        assertEquals(
                ProcessHandle.current().pid(),
                program.parent().orElseThrow().pid(),
                "Editora's own child, not the adapter's");

        await("the prompt", () -> text("stdout").endsWith("first? "));
        assertTrue(text("stdout").contains("cwd=" + dir.resolve("work").toRealPath()), text("stdout"));
        assertTrue(
                text("stdout").contains("env=yes/null"), "the request's variables, set and unset: " + text("stdout"));
        assertTrue(events.contains("input=true"), events.toString());

        boolean[] sent = new boolean[2];
        onFx(() -> sent[0] = dap.sendProgramInput("hello"));
        await("the first echo", () -> text("stdout").contains("one <hello>\n"));
        await("stderr", () -> text("stderr").contains("warn: between\n"));
        onFx(() -> sent[1] = dap.sendProgramInput(""));
        await("the second echo", () -> text("stdout").contains("two <>\n"));
        assertTrue(sent[0] && sent[1]);
        assertTrue(program.isAlive(), "still reading");

        onFx(() -> assertTrue(dap.closeProgramInput()));
        await("the end of the session", () -> events.contains("INACTIVE"));
        assertTrue(text("stdout").endsWith("eof=true\n"), "the last line is shown before the session ends");
        assertTrue(
                events.indexOf("exit=3") < events.lastIndexOf("INACTIVE") && events.contains("exit=3"),
                events.toString());
        assertEquals(List.of(), errors);
        assertTrue(session.disconnected.await(10, TimeUnit.SECONDS), "the adapter is told the session is over");
        onFx(() -> {
            assertFalse(dap.programInputAvailable());
            assertFalse(dap.sendProgramInput("late"));
        });
    }

    @Test
    void stopKillsAProgramThatIsWaitingForInput() throws Exception {
        FakeDebugAdapter.Session session = launch();
        ProcessHandle program = runInTerminal(session);
        await("the prompt", () -> text("stdout").endsWith("first? "));

        onFx(dap::stop);

        await("the program's death", () -> !program.isAlive());
        assertFalse(
                events.stream().anyMatch(e -> e.startsWith("exit=")), "a stopped program is not reported as finished");
        assertEquals("INACTIVE", events.get(events.size() - 1));
    }

    @Test
    void aNewLaunchAndTheWindowClosingBothEndTheProgramOfTheSessionBefore() throws Exception {
        ProcessHandle first = runInTerminal(launch());
        await("the prompt", () -> text("stdout").endsWith("first? "));

        output.clear();
        ProcessHandle second = runInTerminal(launch()); // no Stop in between
        await("the first program's death", () -> !first.isAlive());
        await("the second prompt", () -> text("stdout").endsWith("first? "));
        assertTrue(second.isAlive());
        onFx(() -> assertTrue(dap.sendProgramInput("again")));
        await("the echo", () -> text("stdout").contains("one <again>\n"));

        onFx(dap::shutdown);
        await("the second program's death", () -> !second.isAlive());
    }

    @Test
    void anAdapterThatEndsTheSessionFirstStillShowsTheProgramsLastOutput() throws Exception {
        FakeDebugAdapter.Session session = launch();
        ProcessHandle program = runInTerminal(session);
        await("the prompt", () -> text("stdout").endsWith("first? "));
        onFx(() -> dap.sendProgramInput("a"));
        onFx(() -> dap.sendProgramInput("b"));
        await("the echo", () -> text("stdout").contains("two <b>\n"));

        session.terminate(); // the debugger is done; the program is still there, waiting on its input
        session.awaitDelivered();
        onFx(() -> {});
        assertTrue(program.isAlive());
        assertFalse(events.contains("INACTIVE"), "the session waits for its program: " + events);

        await("the program being ended", () -> !program.isAlive()); // after the grace period
        await("the end of the session", () -> events.contains("INACTIVE"));
        assertTrue(text("stdout").contains("two <b>\n"));
    }

    @Test
    void aProgramThatCannotBeStartedFailsTheRequestAndTheLaunch() throws Exception {
        FakeDebugAdapter.Session session = launch();
        ExecutionException refused = assertThrows(
                ExecutionException.class,
                () -> session.runInTerminal(
                                dir.toString(),
                                List.of(dir.resolve("no-such-java").toString(), "Echo"),
                                Map.of())
                        .get(20, TimeUnit.SECONDS));
        assertTrue(String.valueOf(refused.getCause().getMessage()).contains("no-such-java"), refused.toString());
        onFx(() -> assertFalse(dap.programInputAvailable()));
    }
}
