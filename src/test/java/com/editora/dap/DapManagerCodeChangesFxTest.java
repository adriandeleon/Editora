package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
 * {@link DapManager}'s code-change and frame controls in a session: Apply Code Changes has jdtls build (it runs
 * without autobuild) and only then asks java-debug to replace classes; a build with errors replaces nothing;
 * a program Editora compiled itself gets a jdtls build to compare with at session start; Restart Frame and
 * Smart Step Into send the frame and the target. jdtls is a fake, the adapter is {@link FakeDebugAdapter}.
 */
@Tag("fx")
class DapManagerCodeChangesFxTest {

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
    private final List<String> events = new ArrayList<>();
    private final Map<String, Function<ExecuteCommandParams, Object>> replies = new java.util.HashMap<>();
    /** What jdtls answers {@code java/buildWorkspace} with (1 = SUCCEED, 2 = WITH_ERROR). */
    private volatile Object buildAnswer = 1;

    private Path file;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new FakeDebugAdapter(false);
        adapter.restartFrame = true;
        adapter.stepInTargets = true;
        lsp = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessionsWithRawRequests(lsp, LspTestHooks.caps());
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
                    event("stopped:" + threadId + ":" + reason);
                }

                @Override
                public void onOutput(String text, String category) {}

                @Override
                public void onError(String message) {
                    errors.add(message);
                    event("error");
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

    private int count(String event) {
        synchronized (events) {
            return java.util.Collections.frequency(events, event);
        }
    }

    private void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        synchronized (events) {
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError(what + " never happened; events=" + events + " errors=" + errors);
                }
                TimeUnit.NANOSECONDS.timedWait(events, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            }
        }
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

    private void open(Path f, Path projectRoot, String source) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, source);
        lsp.openDocument(f, projectRoot, "java", source);
        for (FakeLanguageServer fake : fakes) {
            fake.executeCommandHandler = params -> {
                Function<ExecuteCommandParams, Object> reply = replies.get(params.getCommand());
                return reply == null ? null : reply.apply(params);
            };
            fake.rawHandler = raw -> "java/buildWorkspace".equals(raw.method()) ? buildAnswer : null;
        }
    }

    /** The {@code java/buildWorkspace} requests jdtls received, as their {@code isFullBuild} argument. */
    private List<Object> builds() {
        List<Object> out = new ArrayList<>();
        for (FakeLanguageServer fake : fakes) {
            for (FakeLanguageServer.Raw raw : List.copyOf(fake.rawRequests)) {
                if ("java/buildWorkspace".equals(raw.method())) {
                    out.add(raw.params());
                }
            }
        }
        return out;
    }

    private void project() throws Exception {
        root = dir.resolve("proj");
        file = root.resolve("src/main/java/App.java");
        open(file, root, "public class App {\n  public static void main(String[] a) {\n    int x = 1;\n  }\n}\n");
        Path classes = Files.createDirectories(root.resolve("target/classes"));
        Files.writeString(classes.resolve("App.class"), "compiled");
        replies.put(
                "vscode.java.resolveMainClass",
                p -> List.of(Map.of("mainClass", "App", "projectName", "proj", "filePath", file.toString())));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(classes.toString())));
        replies.put("vscode.java.resolveJavaExecutable", p -> "/opt/jdk/bin/java");
    }

    private FakeDebugAdapter.Session launch() throws Exception {
        onFx(() -> dap.startLaunch(file, "java", (o, c) -> c.accept(null), "", root));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        await("RUNNING", () -> count("RUNNING") >= 1);
        return session;
    }

    private DapManager.CodeReplaceOutcome apply() throws Exception {
        CompletableFuture<DapManager.CodeReplaceOutcome> done = new CompletableFuture<>();
        onFx(() -> dap.applyCodeChanges(file, done::complete));
        return done.get(20, TimeUnit.SECONDS);
    }

    // --- hot code replace ----------------------------------------------------------------------------

    @Test
    void applyingBuildsIncrementallyThenReplacesWhatJdtlsRebuilt() throws Exception {
        project();
        FakeDebugAdapter.Session session = launch();
        session.redefineAnswer = Map.of("changedClasses", List.of("App"));
        int before = builds().size();

        DapManager.CodeReplaceOutcome outcome = apply();

        assertEquals(DapManager.CodeReplaceResult.APPLIED, outcome.result());
        assertEquals(List.of("App"), outcome.classes());
        assertEquals(List.of(false), builds().subList(before, builds().size()), "one incremental build");
        assertTrue(session.requests.contains("redefineClasses"), "then java-debug was asked to replace");
    }

    @Test
    void aBuildWithCompileErrorsReplacesNothing() throws Exception {
        project();
        FakeDebugAdapter.Session session = launch();
        buildAnswer = 2; // BuildWorkspaceStatus.WITH_ERROR

        DapManager.CodeReplaceOutcome outcome = apply();

        assertEquals(DapManager.CodeReplaceResult.COMPILE_ERRORS, outcome.result());
        assertFalse(session.requests.contains("redefineClasses"), "a broken method would throw when it runs");
    }

    @Test
    void aReplaceJavaDebugRefusesSaysWhy() throws Exception {
        project();
        FakeDebugAdapter.Session session = launch();
        session.redefineAnswer = Map.of("changedClasses", List.of(), "errorMessage", "Add method not implemented");

        DapManager.CodeReplaceOutcome outcome = apply();

        assertEquals(DapManager.CodeReplaceResult.FAILED, outcome.result());
        assertEquals("Add method not implemented", outcome.message());
    }

    @Test
    void withNoRunningJavaSessionNothingIsBuilt() throws Exception {
        project();
        DapManager.CodeReplaceOutcome outcome = apply();

        assertEquals(DapManager.CodeReplaceResult.NO_SESSION, outcome.result());
        assertEquals(List.of(), builds());
    }

    @Test
    void theHotCodeReplaceEventsOfTheSessionReachTheirListener() throws Exception {
        project();
        List<String> notices = new CopyOnWriteArrayList<>();
        onFx(() -> dap.setCodeReplaceNotices((type, message) -> {
            notices.add(type + ":" + message);
            event("notice");
        }));
        FakeDebugAdapter.Session session = launch();

        session.hotCodeReplace("WARNING", "JVM contains obsolete methods");

        await("the notice", () -> count("notice") >= 1);
        assertEquals(List.of("WARNING:JVM contains obsolete methods"), notices);
    }

    @Test
    void aProjectLaunchIsNotBuiltAgainAtSessionStart() throws Exception {
        project();
        launch();
        int before = builds().size();

        onFx(dap::primeCodeReplace);

        assertEquals(before, builds().size(), "a build project's output is the user's: it was built before launch");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the stand-in compiler is a /bin/sh script
    void aFileEditoraCompiledItselfGetsOneJdtlsBuildToCompareWith() throws Exception {
        Path bin = Files.createDirectories(dir.resolve("jdk/bin"));
        Path javac = bin.resolve("javac");
        Files.writeString(javac, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(javac, PosixFilePermissions.fromString("rwxr-xr-x"));
        file = dir.resolve("loose/Start.java");
        open(file, file.getParent(), "public class Start {\n  public static void main(String[] a) {}\n}\n");
        replies.put("vscode.java.resolveMainClass", p -> List.of());
        replies.put("vscode.java.resolveMainMethod", p -> List.of(Map.of("projectName", "loose_1a2b")));
        onFx(() -> dap.startLaunch(
                file, (o, c) -> c.accept(null), bin.resolve("java").toString()));
        adapter.awaitSession().awaitRequest("launch");
        await("RUNNING", () -> count("RUNNING") >= 1);

        onFx(dap::primeCodeReplace);
        onFx(dap::primeCodeReplace); // once per session

        await("the build", () -> builds().size() == 1);
        assertEquals(List.of(false), builds(), "an incremental build of jdtls's own output");
    }

    // --- restart frame and smart step into -----------------------------------------------------------

    @Test
    void restartFrameSendsTheFrameAndRunsUntilTheNextStop() throws Exception {
        project();
        FakeDebugAdapter.Session session = launch();
        session.stop(7, "breakpoint");
        await("the stop", () -> count("stopped:7:breakpoint") >= 1);
        onFx(() -> assertTrue(dap.supportsRestartFrame()));

        onFx(() -> dap.restartFrame(1234));

        session.awaitRequest("restartFrame");
        assertEquals(List.of(1234), session.restartedFrames);
        await("running after the restart", () -> count("RUNNING") >= 2);
    }

    @Test
    void smartStepIntoAsksForTheTopFramesTargetsAndStepsIntoThePickedOne() throws Exception {
        project();
        FakeDebugAdapter.Session session = launch();
        session.stepTargetLabels = List.of("add(twice(1),twice(2))", "twice(1)");
        session.stop(7, "breakpoint");
        await("the stop", () -> count("stopped:7:breakpoint") >= 1);

        CompletableFuture<List<DapModels.StepTarget>> targets = new CompletableFuture<>();
        onFx(() -> dap.stepInTargets(targets::complete));
        List<DapModels.StepTarget> listed = targets.get(10, TimeUnit.SECONDS);
        assertEquals(
                List.of("add(twice(1),twice(2))", "twice(1)"),
                listed.stream().map(DapModels.StepTarget::label).toList());

        onFx(() -> dap.stepIntoTarget(listed.get(1).id()));

        session.awaitRequest("stepIn");
        assertEquals(101, session.stepIns.get(0).getTargetId());
        assertEquals(7, session.stepIns.get(0).getThreadId());
    }

    @Test
    void theOutcomeOfAReplaceAnswer() {
        assertEquals(
                DapManager.CodeReplaceResult.APPLIED,
                DapManager.codeReplaceOutcome(new DapModels.CodeReplace(List.of("a.B"), ""), null)
                        .result());
        assertEquals(
                DapManager.CodeReplaceResult.NOTHING_CHANGED,
                DapManager.codeReplaceOutcome(new DapModels.CodeReplace(List.of(), ""), null)
                        .result());
        assertEquals(
                DapManager.CodeReplaceResult.NOTHING_CHANGED,
                DapManager.codeReplaceOutcome(null, null).result());
        DapManager.CodeReplaceOutcome refused = DapManager.codeReplaceOutcome(null, "no redefinition");
        assertEquals(DapManager.CodeReplaceResult.FAILED, refused.result());
        assertEquals("no redefinition", refused.message());
        assertEquals(DapManager.CodeReplaceMode.AUTO, DapManager.CodeReplaceMode.of("bogus"));
        assertEquals(DapManager.CodeReplaceMode.MANUAL, DapManager.CodeReplaceMode.of(" Manual "));
        assertEquals(DapManager.CodeReplaceMode.OFF, DapManager.CodeReplaceMode.of("off"));
    }
}
