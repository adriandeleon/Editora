package com.editora.dap;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 * {@link DapManager} as a session: what the user is told when a launch cannot go ahead, which main class a
 * launch settles on, and what each control (Pause, Run to Cursor, Jump to Line, Restart, the evaluations)
 * sends to the adapter and reports back. jdtls is a fake that answers the java-debug commands; the adapter
 * is {@link FakeDebugAdapter}, speaking real DAP over a loopback socket. Nothing here starts a JDK tool:
 * where the manager compiles a loose file, a {@code /bin/sh} stand-in plays {@code javac}.
 */
@Tag("fx")
class DapManagerSessionFxTest {

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
    private final List<String> errors = new CopyOnWriteArrayList<>();
    /** States, stops, notices and breakpoint answers, in the order the listener saw them. */
    private final List<String> events = new ArrayList<>();

    private final List<List<DapModels.StackFrameInfo>> stops = new CopyOnWriteArrayList<>();
    private final List<ExecuteCommandParams> commands = new CopyOnWriteArrayList<>();
    private final Map<String, Function<ExecuteCommandParams, Object>> replies = new java.util.HashMap<>();
    private volatile List<DapModels.FileBreakpoints> breakpoints = List.of();
    private final List<List<DapManager.MainClassOption>> picked = new CopyOnWriteArrayList<>();

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
            dap.setBreakpointsSupplier(() -> breakpoints);
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {
                    event(state.name());
                }

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                    stops.add(frames);
                    event("stopped:" + threadId + ":" + reason);
                }

                @Override
                public void onOutput(String text, String category) {}

                @Override
                public void onError(String message) {
                    errors.add(message);
                    event("error");
                }

                @Override
                public void onBreakpointStatus(Path f, List<DapModels.BreakpointStatus> statuses, boolean whole) {
                    event("breakpoints:" + f);
                }
            });
        });
        replies.put("vscode.java.startDebugSession", p -> adapter.port());
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(dap::shutdown);
        lsp.shutdownAll();
        adapter.close();
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

    private int count(String event) {
        return java.util.Collections.frequency(events(), event);
    }

    private String lastState() {
        List<String> all = events();
        for (int i = all.size() - 1; i >= 0; i--) {
            String e = all.get(i);
            if (e.equals(e.toUpperCase(java.util.Locale.ROOT)) && !e.contains(":")) {
                return e;
            }
        }
        return null;
    }

    /** Waits, on the listener's own notifications, until {@code condition} holds. */
    private void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        synchronized (events) {
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError(what + " never happened; events=" + events + " errors=" + errors
                            + " commands=" + commandNames());
                }
                TimeUnit.NANOSECONDS.timedWait(events, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            }
        }
    }

    private void awaitEvents(String event, int times) throws InterruptedException {
        await(times + " x " + event, () -> java.util.Collections.frequency(events, event) >= times);
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

    /** Lets every callback already queued on the FX thread run, and the ones those queue. */
    private static void drainFx() throws Exception {
        for (int i = 0; i < 3; i++) {
            onFx(() -> {});
        }
    }

    private void open(Path f, Path projectRoot, String source) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        lsp.openDocument(f, projectRoot, "java", source);
        for (FakeLanguageServer fake : fakes) {
            fake.executeCommandHandler = params -> {
                commands.add(params);
                Function<ExecuteCommandParams, Object> reply = replies.get(params.getCommand());
                return reply == null ? null : reply.apply(params);
            };
        }
    }

    private List<String> commandNames() {
        return commands.stream().map(ExecuteCommandParams::getCommand).toList();
    }

    private static Map<String, Object> mainClass(String mainClass, String project, Path f) {
        return Map.of("mainClass", mainClass, "projectName", project, "filePath", f.toString());
    }

    /** A Maven-shaped project whose one main class jdtls lists and has a class file for. */
    private void project() throws Exception {
        root = dir.resolve("proj");
        file = root.resolve("src/main/java/App.java");
        open(file, root, "public class App {\n  public static void main(String[] a) {\n    int x = 1;\n  }\n}\n");
        Path classes = Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(classes.resolve("App.class"), "compiled");
        replies.put("vscode.java.resolveMainClass", p -> List.of(mainClass("App", "proj", file)));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(classes.toString())));
        replies.put("vscode.java.resolveJavaExecutable", p -> "/opt/jdk/bin/java");
    }

    private final DapManager.MainClassPicker noPicker = (options, chosen) -> {
        errors.add("the main-class picker was shown with " + options.size() + " options");
        chosen.accept(null);
    };

    /** Launches the project's main class and returns the adapter's session once it is running. */
    private FakeDebugAdapter.Session launch() throws Exception {
        int running = count("RUNNING");
        onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertTrue(session.configured.await(10, TimeUnit.SECONDS), "the adapter was configured");
        awaitEvents("RUNNING", running + 1);
        return session;
    }

    private FakeDebugAdapter.Session launchAndStop() throws Exception {
        FakeDebugAdapter.Session session = launch();
        stop(session, 7);
        return session;
    }

    private void stop(FakeDebugAdapter.Session session, int threadId) throws Exception {
        int before = count("stopped:" + threadId + ":breakpoint");
        session.stop(threadId, "breakpoint");
        awaitEvents("stopped:" + threadId + ":breakpoint", before + 1);
    }

    private static List<Integer> lines(org.eclipse.lsp4j.debug.SetBreakpointsArguments args) {
        List<Integer> out = new ArrayList<>();
        for (org.eclipse.lsp4j.debug.SourceBreakpoint b : args.getBreakpoints()) {
            out.add(b.getLine());
        }
        return out;
    }

    // --- a launch that cannot go ahead says why ---------------------------------------------------------

    @Test
    void aLanguageWithNoDebugAdapterIsRefusedWithAMessage() throws Exception {
        onFx(() -> dap.startLaunch(dir.resolve("notes.txt"), "plaintext", noPicker));

        assertEquals(List.of("Debugging is not supported for this file type."), errors);
        assertEquals(
                List.of(), events().stream().filter(e -> !e.equals("error")).toList(), "no session began");
    }

    @Test
    void javaDebuggingThatIsOffOrHasNoFileSaysSoInsteadOfStarting() throws Exception {
        project();
        onFx(() -> dap.startLaunch(null, noPicker));
        assertEquals(List.of("Open a Java file to debug first."), errors);

        errors.clear();
        onFx(() -> {
            dap.configure(false, "");
            dap.startLaunch(file, noPicker, "");
            dap.startAttach(file, "localhost", 5005);
            dap.startLaunchMainClass(file, new DapManager.MainClassOption("App", "proj", null), root);
        });
        String unavailable = "Java debugging is not available (enable it and install the java-debug plugin).";
        assertEquals(List.of(unavailable, unavailable, unavailable), errors);
        assertEquals(List.of(), commandNames(), "jdtls was asked nothing");
        onFx(() -> assertFalse(dap.isActive()));
    }

    @Test
    void theStandaloneAdaptersSayWhichPartIsMissing() throws Exception {
        Path script = dir.resolve("app.py");
        onFx(() -> {
            dap.startLaunch(null, "python", noPicker);
            dap.startLaunch(script, "python", noPicker);
            dap.startLaunch(dir.resolve("app.js"), "javascript", noPicker);
            dap.configure(false, "");
            dap.startLaunch(script, "python", noPicker);
        });

        assertEquals(
                List.of(
                        "Open a file to debug first.",
                        "Python debugging is not available (enable it and install debugpy).",
                        "JavaScript debugging is not available (enable it and install vscode-js-debug).",
                        "Debugging is not enabled (turn it on in Settings → Debugging)."),
                errors);
    }

    @Test
    void aLanguageIsAvailableOnlyOnceItsOwnAdapterIs() throws Exception {
        onFx(() -> {
            assertTrue(dap.isLanguageAvailable("java"));
            assertFalse(dap.isLanguageAvailable("python"), "debugpy was never detected");
            assertFalse(dap.isLanguageAvailable("javascript"));
            assertFalse(dap.isLanguageAvailable("rust"));
            assertFalse(dap.isLanguageAvailable(null));
            dap.setServerProvidesJavaDebug(false);
            assertFalse(dap.isLanguageAvailable("java"), "no bundle and no server support");
            assertFalse(dap.isAdapterAvailable());
        });
    }

    @Test
    void redetectingWithDebuggingOffLeavesNoBundleBehind() throws Exception {
        CountDownLatch detected = new CountDownLatch(1);
        Boolean[] found = new Boolean[1];
        onFx(() -> {
            dap.configure(false, dir.resolve("no-plugin-here").toString());
            dap.detect(ok -> {
                found[0] = ok;
                detected.countDown();
            });
        });

        assertTrue(detected.await(20, TimeUnit.SECONDS), "the result is delivered");
        onFx(() -> {
            assertEquals(List.of(), dap.bundlePaths(), "a located jar is not injected while debugging is off");
            assertFalse(dap.isAdapterAvailable());
        });
        assertTrue(found[0] != null, "found or not, the caller is told");
    }

    @Test
    void aJdtlsThatCannotListMainClassesEndsTheLaunchWithItsError() throws Exception {
        project();
        fakes.forEach(fake -> fake.failEverything = true);

        onFx(() -> dap.startLaunch(file, noPicker));

        await("the failure", () -> !errors.isEmpty());
        assertTrue(errors.get(0).startsWith("resolveMainClass failed: "), errors.toString());
        awaitEvents("INACTIVE", 1);
        assertEquals(List.of("STARTING", "error", "INACTIVE"), events());
        assertEquals(0, adapter.sessionCount());
    }

    @Test
    void aClasspathJdtlsCannotResolveEndsTheLaunchWithItsError() throws Exception {
        project();
        replies.put("vscode.java.resolveMainClass", p -> {
            fakes.forEach(fake -> fake.failEverything = true); // every later command fails
            return List.of(mainClass("App", "proj", file));
        });

        onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));

        await("the failure", () -> !errors.isEmpty());
        assertTrue(errors.get(0).startsWith("Could not resolve the classpath: "), errors.toString());
        awaitEvents("INACTIVE", 1);
        assertEquals(0, adapter.sessionCount());
    }

    @Test
    void anEmptyClasspathIsReportedAsAProjectThatHasNotImported() throws Exception {
        project();
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of()));

        onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));

        await("the failure", () -> !errors.isEmpty());
        assertEquals(
                "Could not resolve the classpath for App — make sure the Java project has finished importing "
                        + "(watch the LSP status), then try again.",
                errors.get(0));
        awaitEvents("INACTIVE", 1);
        assertFalse(commandNames().contains("vscode.java.startDebugSession"));
    }

    @Test
    void aLaunchWithNoMainClassIsRefusedBeforeJdtlsIsAsked() throws Exception {
        project();
        List<DapManager.ResolvedLaunch> resolved = new CopyOnWriteArrayList<>();

        onFx(() -> {
            dap.resolveLaunch(file, new DapManager.MainClassOption(" ", "proj", null), resolved::add);
            dap.resolveLaunch(file, new DapManager.MainClassOption(null, null, null), resolved::add);
        });

        assertEquals(2, resolved.size());
        for (DapManager.ResolvedLaunch r : resolved) {
            assertFalse(r.ok());
            assertEquals("No main class to run — set one on the run configuration.", r.error());
            assertEquals(List.of(), r.classPaths());
        }
        assertEquals(List.of(), commandNames());
    }

    @Test
    void aResolvedLaunchCarriesTheExecutableThePathsAndThePreviewFlag() throws Exception {
        project();
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of("/m/mod.jar"), List.of("/c/classes", 7)));
        replies.put("vscode.java.checkProjectSettings", p -> true);
        List<DapManager.ResolvedLaunch> resolved = new CopyOnWriteArrayList<>();

        onFx(() -> dap.resolveLaunch(file, new DapManager.MainClassOption("App", null, null), r -> {
            resolved.add(r);
            event("resolved");
        }));

        awaitEvents("resolved", 1);
        DapManager.ResolvedLaunch r = resolved.get(0);
        assertTrue(r.ok());
        assertEquals("/opt/jdk/bin/java", r.javaExec());
        assertEquals(List.of("/m/mod.jar"), r.modulePaths());
        assertEquals(List.of("/c/classes", "7"), r.classPaths(), "an entry that is not a string is still kept");
        assertTrue(r.enablePreview());
    }

    @Test
    void theProjectsMainClassesAreListedAndAFailureReadsAsNone() throws Exception {
        project();
        Path other = root.resolve("src/main/java/Tool.java");
        replies.put(
                "vscode.java.resolveMainClass",
                p -> List.of(mainClass("App", "proj", file), mainClass("Tool", "proj", other)));
        List<List<DapManager.MainClassOption>> listed = new CopyOnWriteArrayList<>();

        onFx(() -> dap.resolveMainClasses(file, options -> {
            listed.add(options);
            event("listed");
        }));
        awaitEvents("listed", 1);
        assertEquals(
                List.of("App", "Tool"),
                listed.get(0).stream().map(o -> o.className()).toList());
        assertEquals(other.toString(), listed.get(0).get(1).filePath());

        fakes.forEach(fake -> fake.failEverything = true);
        onFx(() -> dap.resolveMainClasses(file, options -> {
            listed.add(options);
            event("listed");
        }));
        awaitEvents("listed", 2);
        assertEquals(List.of(), listed.get(1));

        onFx(() -> {
            dap.resolveMainClasses(null, listed::add); // no file to route through: answered at once
            dap.configure(false, "");
            dap.resolveMainClasses(file, listed::add);
        });
        assertEquals(List.of(List.of(), List.of()), listed.subList(2, 4));
    }

    @Test
    void mainClassRepliesAreReadFromGsonObjectsToo() {
        com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
        entry.addProperty("mainClass", "demo.App");
        entry.addProperty("projectName", "proj");
        List<Object> mixed = new ArrayList<>();
        mixed.add(entry);
        mixed.add("not an entry");

        List<DapManager.MainClassOption> parsed = FxlessAccess.parseMainClasses(mixed);

        assertEquals(List.of(new DapManager.MainClassOption("demo.App", "proj", null)), parsed);
    }

    // --- which main class a launch settles on -----------------------------------------------------------

    @Test
    void theOnlyMainClassOfTheProjectIsLaunchedFromAFileThatHasNone() throws Exception {
        project();
        Path helper = root.resolve("src/main/java/Helper.java");
        open(helper, root, "class Helper {}\n");

        onFx(() -> dap.startLaunch(helper, "java", noPicker, "", root));

        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertEquals("App", session.launchArgs.get("mainClass"));
        assertEquals(root.toString(), session.launchArgs.get("cwd"));
        assertEquals(List.of(), errors);
        onFx(() -> assertEquals(helper, dap.debugFile()));
    }

    @Test
    void severalMainClassesAreOfferedAndTheChosenOneIsLaunched() throws Exception {
        project();
        Path helper = root.resolve("src/main/java/Helper.java");
        open(helper, root, "class Helper {}\n");
        Path tool = root.resolve("src/main/java/Tool.java");
        Files.writeString(root.resolve("target/classes/Tool.class"), "compiled");
        replies.put(
                "vscode.java.resolveMainClass",
                p -> List.of(mainClass("App", "proj", file), mainClass("Tool", "proj", tool)));

        onFx(() -> dap.startLaunch(
                helper,
                "java",
                (options, chosen) -> {
                    picked.add(options);
                    chosen.accept(options.get(1));
                },
                "/chosen/jdk/bin/java",
                root));

        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertEquals(1, picked.size());
        assertEquals(2, picked.get(0).size());
        assertEquals("Tool", session.launchArgs.get("mainClass"));
        assertEquals("/chosen/jdk/bin/java", session.launchArgs.get("javaExec"), "the selected JDK wins");
        assertEquals(List.of(), errors);
    }

    @Test
    void dismissingTheMainClassChooserEndsTheLaunchQuietly() throws Exception {
        project();
        Path helper = root.resolve("src/main/java/Helper.java");
        open(helper, root, "class Helper {}\n");
        replies.put(
                "vscode.java.resolveMainClass",
                p -> List.of(mainClass("App", "proj", file), mainClass("Tool", "proj", root.resolve("Tool.java"))));

        onFx(() -> dap.startLaunch(helper, "java", (options, chosen) -> chosen.accept(null), "", root));

        awaitEvents("INACTIVE", 1);
        assertEquals(List.of("STARTING", "INACTIVE"), events());
        assertEquals(List.of(), errors);
        assertEquals(0, adapter.sessionCount());
        assertFalse(commandNames().contains("vscode.java.resolveClasspath"));
    }

    @Test
    void stoppingWhileJdtlsIsStillAnsweringAbandonsTheLaunch() throws Exception {
        project();

        // The reply is delivered on a later FX pulse; by then the user has pressed Stop.
        onFx(() -> {
            dap.startLaunch(file, "java", noPicker, "", root);
            dap.stop();
        });
        drainFx();

        assertEquals(List.of("vscode.java.resolveMainClass"), commandNames(), "nothing more was asked");
        assertEquals(List.of(), errors);
        assertEquals("INACTIVE", lastState());
        assertEquals(0, adapter.sessionCount());
    }

    @Test
    void stoppingWhileTheClasspathOrTheSessionIsBeingResolvedAbandonsTheLaunch() throws Exception {
        for (String during : List.of("vscode.java.resolveClasspath", "vscode.java.startDebugSession")) {
            project();
            commands.clear();
            Function<ExecuteCommandParams, Object> answer = replies.get(during);
            replies.put(during, p -> {
                dap.stop(); // the fake answers on the FX thread, where Stop is pressed
                return answer.apply(p);
            });

            onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));
            await(during + " to be asked", () -> commandNames().contains(during));
            drainFx();

            assertEquals(List.of(), errors, during);
            assertEquals("INACTIVE", lastState(), during);
            assertEquals(0, adapter.sessionCount(), during);
            replies.put(during, answer);
        }
    }

    // --- the adapter cannot be reached, or refuses ------------------------------------------------------

    @Test
    void aJdtlsThatCannotStartTheAdapterEndsTheLaunchWithItsError() throws Exception {
        project();
        replies.put("vscode.java.checkProjectSettings", p -> {
            fakes.forEach(fake -> fake.failEverything = true); // startDebugSession is the next command
            return false;
        });

        onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));

        await("the failure", () -> !errors.isEmpty());
        assertTrue(errors.get(0).startsWith("Could not start the debug session: "), errors.toString());
        awaitEvents("INACTIVE", 1);
    }

    @Test
    void anAdapterPortThatIsNotANumberEndsTheLaunch() throws Exception {
        project();
        replies.put("vscode.java.startDebugSession", p -> "soon");

        onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));

        await("the failure", () -> !errors.isEmpty());
        assertEquals(List.of("The debug adapter did not return a port."), errors);
        awaitEvents("INACTIVE", 1);
        onFx(() -> assertFalse(dap.isActive()));
    }

    @Test
    void anAdapterNobodyIsListeningOnEndsTheLaunchWithAConnectError() throws Exception {
        project();
        int deadPort;
        try (ServerSocket closedAgain = new ServerSocket(0)) {
            deadPort = closedAgain.getLocalPort();
        }
        replies.put("vscode.java.startDebugSession", p -> deadPort);

        onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));

        await("the failure", () -> !errors.isEmpty());
        assertEquals(
                "Could not connect to the debug adapter: could not connect to the debug adapter on port " + deadPort,
                errors.get(0));
        awaitEvents("INACTIVE", 1);
        onFx(() -> assertFalse(dap.isActive()));
    }

    @Test
    void aLaunchTheAdapterRefusesIsReportedWithTheAdaptersWords() throws Exception {
        project();
        adapter.launchFailure = "Main class 'App' not found";

        onFx(() -> dap.startLaunch(file, "java", noPicker, "", root));

        await("the failure", () -> !errors.isEmpty());
        assertEquals("launch failed: Main class 'App' not found", errors.get(0));
        await("the session to end", () -> "INACTIVE".equals(lastState()));
        onFx(() -> assertFalse(dap.isActive()));
    }

    @Test
    void anAttachTheAdapterRefusesIsReportedAsAnAttach() throws Exception {
        project();
        adapter.launchFailure = "Connection refused";

        onFx(() -> dap.startAttach(file, "localhost", 5005));

        await("the failure", () -> !errors.isEmpty());
        assertEquals("attach failed: Connection refused", errors.get(0));
        await("the session to end", () -> "INACTIVE".equals(lastState()));
    }

    // --- controls ---------------------------------------------------------------------------------------

    @Test
    void pauseTargetsTheFirstThreadUntilOneHasStoppedAndThenThatOne() throws Exception {
        project();
        FakeDebugAdapter.Session session = launch();

        onFx(dap::pause);
        session.awaitRequest("pause");
        assertEquals(List.of(7), session.pausedThreads, "no thread has stopped yet: the first one listed");

        // Thread 9 stops; the adapter cannot produce its stack, which must not lose the stop.
        session.runningThreads.add(9);
        stop(session, 9);
        assertEquals(List.of(), stops.get(stops.size() - 1));
        onFx(() -> {
            assertEquals(DapManager.State.SUSPENDED, dap.state());
            assertEquals(9, dap.currentThreadId());
            dap.pause(); // suspended already: nothing to pause
            dap.resume();
            dap.pause();
        });
        session.awaitRequests("pause", 2);
        assertEquals(List.of(7, 9), session.pausedThreads, "the thread the user was last looking at");
    }

    @Test
    void pauseDoesNothingWhenTheAdapterCannotListItsThreads() throws Exception {
        project();
        FakeDebugAdapter.Session session = launch();
        session.threadsFailure = "not now";

        onFx(dap::pause);
        session.awaitRequest("threads");
        session.threadsFailure = null;
        onFx(dap::pause);
        session.awaitRequest("pause");

        assertEquals(1, session.pausedThreads.size(), "only the second Pause reached the adapter");
        assertEquals(List.of(), errors);
    }

    @Test
    void runToCursorPlantsATemporaryBreakpointAndRemovesItAtTheNextStop() throws Exception {
        project();
        Path other = root.resolve("src/main/java/Other.java");
        breakpoints = List.of(
                new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(1, null, null))),
                new DapModels.FileBreakpoints(other, List.of(new DapModels.LineBreakpoint(5, null, null))));
        FakeDebugAdapter.Session session = launch();
        session.awaitRequests("setBreakpoints", 2);

        onFx(() -> dap.runToCursor(file, 3)); // running: there is no paused thread to run from
        stop(session, 7);
        assertEquals(2, session.breakpoints.size(), "Run to Cursor while running sent nothing");

        onFx(() -> {
            dap.runToCursor(null, 3); // no file under the caret
            dap.runToCursor(file, 3);
        });
        session.awaitRequests("setBreakpoints", 3);
        session.awaitRequest("continue");
        assertEquals(file.toString(), session.breakpoints.get(2).getSource().getPath());
        assertEquals(List.of(2, 4), lines(session.breakpoints.get(2)), "the real breakpoint plus the cursor line");
        await("the resume", () -> "RUNNING".equals(lastState()));

        stop(session, 7);
        session.awaitRequests("setBreakpoints", 4);
        assertEquals(file.toString(), session.breakpoints.get(3).getSource().getPath());
        assertEquals(List.of(2), lines(session.breakpoints.get(3)), "only the user's own breakpoint is left");

        stop(session, 7);
        session.awaitDelivered();
        drainFx();
        assertEquals(4, session.breakpoints.size(), "a later stop has nothing to clean up");
    }

    @Test
    void jumpToLineMovesTheStoppedThreadToTheAdaptersTarget() throws Exception {
        project();
        adapter.gotoTargets = true;
        FakeDebugAdapter.Session session = launch();
        List<String> jumpErrors = new CopyOnWriteArrayList<>();
        onFx(() -> {
            assertTrue(dap.supportsJumpToLine());
            dap.jumpToLine(file, 2, jumpErrors::add); // running: nothing to move
        });
        stop(session, 7);
        assertEquals(List.of(), session.gotoTargetRequests);

        session.gotoTargetIds = List.of(42, 43);
        onFx(() -> {
            dap.jumpToLine(null, 2, jumpErrors::add);
            dap.jumpToLine(file, 2, jumpErrors::add);
        });
        session.awaitRequest("goto");
        assertEquals(1, session.gotoTargetRequests.size());
        assertEquals(3, session.gotoTargetRequests.get(0).getLine(), "DAP lines are 1-based");
        assertEquals(
                file.toString(), session.gotoTargetRequests.get(0).getSource().getPath());
        assertEquals(7, session.gotoRequests.get(0).getThreadId());
        assertEquals(42, session.gotoRequests.get(0).getTargetId(), "the first target offered");
        assertEquals(List.of(), jumpErrors);
    }

    @Test
    void jumpToLineReportsALineWithNoTargetAndARefusalDifferently() throws Exception {
        project();
        adapter.gotoTargets = true;
        FakeDebugAdapter.Session session = launchAndStop();
        List<String> jumpErrors = new CopyOnWriteArrayList<>();
        Runnable jump = () -> dap.jumpToLine(file, 2, message -> {
            jumpErrors.add(message);
            event("jump-error");
        });

        onFx(jump); // no target on the line
        awaitEvents("jump-error", 1);
        session.gotoTargetsFailure = "Line 3 is outside the current function";
        onFx(jump);
        awaitEvents("jump-error", 2);
        session.gotoTargetsFailure = null;
        session.gotoTargetIds = List.of(42);
        session.gotoFailure = "Cannot jump into a finally block";
        onFx(jump);
        awaitEvents("jump-error", 3);

        assertEquals(
                List.of("", "Line 3 is outside the current function", "Cannot jump into a finally block"), jumpErrors);
        assertEquals(List.of(), errors, "a failed jump is the caller's to show");
        onFx(() -> assertEquals(DapManager.State.SUSPENDED, dap.state()));
    }

    @Test
    void anAdapterWithoutGotoTargetsDoesNotOfferJumpToLine() throws Exception {
        project();
        onFx(() -> assertFalse(dap.supportsJumpToLine(), "no session"));
        launch();
        onFx(() -> assertFalse(dap.supportsJumpToLine(), "java-debug does not advertise it"));
    }

    @Test
    void restartEndsTheSessionAndLaunchesTheSameProgramAgain() throws Exception {
        project();
        onFx(() -> dap.restart()); // nothing has been launched: nothing to restart
        assertEquals(0, adapter.sessionCount());
        FakeDebugAdapter.Session first = launch();

        onFx(dap::restart);

        assertTrue(first.disconnected.await(10, TimeUnit.SECONDS), "the first session is ended");
        FakeDebugAdapter.Session second = adapter.awaitSession();
        second.awaitRequest("launch");
        assertEquals("App", second.launchArgs.get("mainClass"));
        awaitEvents("RUNNING", 2);
        assertEquals(List.of("INACTIVE", "STARTING", "RUNNING", "INACTIVE", "STARTING", "RUNNING"), events());
        assertEquals(List.of(), errors);
    }

    @Test
    void anExceptionFilterChangedMidSessionIsSentToTheAdapter() throws Exception {
        project();
        onFx(() -> dap.setExceptionFilters(List.of("uncaught")));
        FakeDebugAdapter.Session session = launch();
        session.awaitRequest("setExceptionBreakpoints");
        assertEquals(
                List.of("uncaught"), List.of(session.exceptionBreakpoints.get(0).getFilters()));

        onFx(() -> dap.setExceptionFilters(List.of("caught", "uncaught")));
        session.awaitRequests("setExceptionBreakpoints", 2);
        assertEquals(
                List.of("caught", "uncaught"),
                List.of(session.exceptionBreakpoints.get(1).getFilters()));

        onFx(() -> dap.setExceptionFilters(null));
        session.awaitRequests("setExceptionBreakpoints", 3);
        assertEquals(List.of(), List.of(session.exceptionBreakpoints.get(2).getFilters()));
    }

    @Test
    void aContinuedEventForAnotherThreadLeavesTheStopOnScreen() throws Exception {
        project();
        FakeDebugAdapter.Session session = launchAndStop();
        int running = count("RUNNING");

        session.continued(9, false);
        session.awaitDelivered();
        drainFx();
        assertEquals(running, count("RUNNING"), "thread 7 is still stopped");
        onFx(() -> assertEquals(DapManager.State.SUSPENDED, dap.state()));

        session.continued(7, false);
        awaitEvents("RUNNING", running + 1);

        stop(session, 7);
        session.continued(9, true); // every thread, whichever one the event names
        awaitEvents("RUNNING", running + 2);
        onFx(() -> assertEquals(DapManager.State.RUNNING, dap.state()));
    }

    // --- evaluation -------------------------------------------------------------------------------------

    @Test
    void aHoverShowsAValueAndNothingForWhatCannotBeEvaluated() throws Exception {
        project();
        FakeDebugAdapter.Session session = launchAndStop();
        List<String> shown = new ArrayList<>();
        java.util.function.Consumer<String> show = value -> {
            synchronized (shown) {
                shown.add(value);
            }
            event("hover");
        };

        onFx(() -> dap.evaluateHover("count", 1, show));
        awaitEvents("hover", 1);
        onFx(() -> dap.evaluateHover("badName", 1, show));
        awaitEvents("hover", 2);

        assertEquals(java.util.Arrays.asList("val(count)", null), shown, "a keyword or a type name shows nothing");
        assertEquals("hover", session.evaluateRequests.get(0).getContext().toString());
        assertEquals(List.of(), errors);
    }

    @Test
    void anEvaluationTheAdapterRefusesCarriesItsMessage() throws Exception {
        project();
        launchAndStop();
        List<String> text = new CopyOnWriteArrayList<>();
        List<DapModels.EvalResult> full = new CopyOnWriteArrayList<>();

        onFx(() -> {
            dap.evaluate("badExpr", 1, "repl", r -> {
                text.add(r);
                event("evaluated");
            });
            dap.evaluateFull("badWatch", 1, "watch", r -> {
                full.add(r);
                event("evaluated");
            });
        });
        awaitEvents("evaluated", 2);

        assertEquals(List.of("error: Cannot evaluate: badExpr"), text);
        assertTrue(full.get(0).failed());
        assertEquals("Cannot evaluate: badWatch", full.get(0).result());
        assertEquals(0, full.get(0).variablesReference());
    }

    @Test
    void withNoSessionEveryInspectionAnswersEmptyAtOnce() throws Exception {
        List<Object> answers = new ArrayList<>();
        onFx(() -> {
            dap.threads(answers::add);
            dap.stackTrace(7, answers::add);
            dap.scopes(1, answers::add);
            dap.variables(1000, answers::add);
            dap.evaluate("x", 1, "repl", answers::add);
            dap.evaluateFull("x", 1, "watch", r -> answers.add(r.result() + "/" + r.failed()));
            dap.evaluateHover("x", 1, answers::add);
            dap.setVariable(1000, "x", "2", answers::add); // nothing was set: no answer at all
            dap.selectThread(9, answers::add);
            dap.updateBreakpoints(new DapModels.FileBreakpoints(dir.resolve("A.java"), List.of()));
            dap.resume();
            dap.pause();
            dap.stepOver();
            dap.runToCursor(dir.resolve("A.java"), 1);
            dap.jumpToLine(dir.resolve("A.java"), 1, answers::add);
        });

        assertEquals(
                java.util.Arrays.asList(List.of(), List.of(), List.of(), List.of(), "", "/false", null, List.of()),
                answers);
        assertEquals(List.of(), errors);
        assertEquals(List.of(), events(), "no state change was announced");
        onFx(() -> {
            assertEquals(9, dap.currentThreadId(), "the selected thread is remembered for the next session");
            assertFalse(dap.isStepping());
            assertFalse(dap.sendProgramInput("typed"));
            assertFalse(dap.closeProgramInput());
        });
    }

    // --- a loose file is compiled first (a /bin/sh stand-in plays javac) --------------------------------

    /** A JDK folder whose {@code javac} is a script: records its arguments, then exits with {@code exit}. */
    private String standInJdk(String name, String body) throws Exception {
        Path bin = Files.createDirectories(dir.resolve(name).resolve("bin"));
        Path javac = bin.resolve("javac");
        Files.writeString(
                javac, "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + dir.resolve(name + "-args.txt") + "'\n" + body);
        Files.setPosixFilePermissions(javac, PosixFilePermissions.fromString("rwxr-xr-x"));
        return bin.resolve("java").toString(); // the debuggee's java: handed to the adapter, never started here
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the stand-in compiler is a /bin/sh script
    void aFileJdtlsListsNoMainClassForIsCompiledAndLaunchedByItsDeclaredClass() throws Exception {
        String jdkJava = standInJdk("jdk", "exit 0\n");
        Path loose = dir.resolve("loose/Start.java");
        open(loose, dir.resolve("loose"), "package demo;\n\npublic class Launcher {\n  void main() {}\n}\n");
        replies.put("vscode.java.resolveMainClass", p -> List.of());
        replies.put("vscode.java.resolveMainMethod", p -> List.of(Map.of("projectName", "loose_1a2b")));

        onFx(() -> dap.startLaunch(loose, noPicker, jdkJava));

        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertEquals(List.of(), errors);
        assertEquals("demo.Launcher", session.launchArgs.get("mainClass"), "the declared type, not the file name");
        assertEquals("loose_1a2b", session.launchArgs.get("projectName"));
        assertEquals(jdkJava, session.launchArgs.get("javaExec"));
        assertEquals(loose.getParent().toString(), session.launchArgs.get("cwd"));
        List<String> javac = Files.readAllLines(dir.resolve("jdk-args.txt"));
        assertEquals("-g", javac.get(0), "local variables are kept for the debugger");
        assertEquals("-d", javac.get(1));
        assertEquals(loose.toString(), javac.get(3));
        Path classes = Path.of(javac.get(2));
        assertEquals(List.of(classes.toString()), session.launchArgs.get("classPaths"));
        assertTrue(Files.isDirectory(classes), "the classes stay for as long as the session runs");

        awaitEvents("RUNNING", 1);
        onFx(dap::stop);
        await("the temporary classes to be removed", () -> Files.notExists(classes));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the stand-in compiler is a /bin/sh script
    void aFileThatDoesNotCompileEndsTheLaunchWithTheCompilersOutput() throws Exception {
        String jdkJava = standInJdk("jdk", "echo 'Start.java:3: error: ; expected' >&2\nexit 1\n");
        Path loose = dir.resolve("loose/Start.java");
        open(loose, dir.resolve("loose"), "public class Start {\n  void main() {\n    int x\n  }\n}\n");
        replies.put("vscode.java.resolveMainClass", p -> List.of());

        onFx(() -> dap.startCompactSource(loose, jdkJava));

        await("the failure", () -> !errors.isEmpty());
        assertEquals("Compilation failed:\nStart.java:3: error: ; expected", errors.get(0));
        await("the session to end", () -> "INACTIVE".equals(lastState()));
        assertEquals(0, adapter.sessionCount());
        Path classes = Path.of(Files.readAllLines(dir.resolve("jdk-args.txt")).get(2));
        assertTrue(Files.notExists(classes), "nothing is left behind in the temp folder");
    }

    @Test
    void compactSourceDebuggingIsOnlyForJavaFilesOfARecentEnoughRelease() throws Exception {
        Path script = dir.resolve("tool");
        List<String> refused = new ArrayList<>();
        onFx(() -> {
            for (Runnable start : List.<Runnable>of(
                    () -> dap.startCompactSource(script, ""),
                    () -> dap.startCompactSource(null, ""),
                    () -> dap.startCompactShebang(script, 24, ""))) {
                try {
                    start.run();
                    refused.add("started");
                } catch (IllegalArgumentException e) {
                    refused.add(e.getMessage());
                }
            }
        });

        assertEquals(
                List.of(
                        "Compact source debugging needs a .java file",
                        "Compact source debugging needs a .java file",
                        "Compact source debugging requires --source 25 or newer"),
                refused);
        assertEquals(List.of(), events());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the stand-in compiler is a /bin/sh script
    void aShebangScriptIsDebuggedThroughACopyTheUserNeverSees() throws Exception {
        String jdkJava = standInJdk("jdk", "exit 0\n");
        Path script = dir.resolve("scripts/tool");
        String source = "#!/usr/bin/env -S java --source 25\nvoid main() {\n  int answer = 42;\n}\n";
        open(script, dir.resolve("scripts"), source);
        replies.put("vscode.java.resolveMainMethod", p -> List.of(Map.of("projectName", "scripts_9f")));
        breakpoints =
                List.of(new DapModels.FileBreakpoints(script, List.of(new DapModels.LineBreakpoint(2, null, null))));

        onFx(() -> dap.startCompactShebang(script, 25, jdkJava));

        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertEquals(List.of(), errors);
        List<String> javac = Files.readAllLines(dir.resolve("jdk-args.txt"));
        assertEquals(List.of("-g", "--release", "25", "-d"), javac.subList(0, 4));
        Path classes = Path.of(javac.get(4));
        Path copy = Path.of(javac.get(5));
        assertEquals(classes.resolve("tool.java"), copy);
        String compiled = Files.readString(copy);
        assertEquals(source.length(), compiled.length());
        assertTrue(compiled.startsWith(" ".repeat(34) + "\nvoid main()"), "the shebang is blanked, lines kept");
        assertEquals("tool", session.launchArgs.get("mainClass"));
        assertEquals("scripts_9f", session.launchArgs.get("projectName"));

        // The breakpoint is set in the copy the classes were compiled from …
        session.awaitRequest("setBreakpoints");
        assertEquals(copy.toString(), session.breakpoints.get(0).getSource().getPath());
        assertEquals(List.of(3), lines(session.breakpoints.get(0)));
        // … and what the adapter says about it is reported for the script itself.
        await("the adapter's answer", () -> events.contains("breakpoints:" + script));
        assertFalse(events().contains("breakpoints:" + copy));

        // Frames in the copy — or in its class, when the adapter names no source — read as the script.
        session.frames = List.of(
                FakeDebugAdapter.Session.frame(1, "tool.main()", copy.toString(), 3),
                FakeDebugAdapter.Session.frame(2, "tool$Helper.run()", null, 9),
                FakeDebugAdapter.Session.frame(3, "tool.lambda$0()", null, 4),
                FakeDebugAdapter.Session.frame(4, "java.lang.Thread.run()", null, 1),
                FakeDebugAdapter.Session.frame(
                        5, "Other.call()", dir.resolve("Other.java").toString(), 8));
        stop(session, 7);
        List<Path> shown = new ArrayList<>();
        for (DapModels.StackFrameInfo frame : stops.get(0)) {
            shown.add(frame.file());
        }
        assertEquals(java.util.Arrays.asList(script, script, script, null, dir.resolve("Other.java")), shown);

        // A breakpoint toggled mid-session goes to the copy as well.
        onFx(() -> dap.updateBreakpoints(
                new DapModels.FileBreakpoints(script, List.of(new DapModels.LineBreakpoint(1, null, null)))));
        session.awaitRequests("setBreakpoints", 2);
        assertEquals(copy.toString(), session.breakpoints.get(1).getSource().getPath());

        onFx(dap::stop);
        await("the copy and its classes to be removed", () -> Files.notExists(classes));
        assertEquals("INACTIVE", lastState());
    }
}
