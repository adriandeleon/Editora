package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.control.Button;

import com.editora.config.Breakpoint;
import com.editora.config.RunConfiguration;
import com.editora.config.Settings;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.run.StackTraceLinks;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Debug commands as the user reaches them — Debug, Debug Main Class, a saved configuration, Attach,
 * Restart, Run to Cursor, Jump to Line, the panel's buttons — against a fake jdtls and a scripted adapter:
 * what each one starts, and what the status bar says when it starts nothing. Also what happens to stored
 * breakpoints when a file is renamed or another window rewrites them.
 */
@Tag("fx")
class DebugCoordinatorCommandsFxTest {

    private static final String SOURCE = "package demo;\npublic class Args {\n  int a;\n  int b;\n  int c;\n}\n";

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path project;

    @TempDir
    Path loose;

    /** Status-bar messages, debug-state segments and tool-window availability, in order. */
    private final List<String> log = new ArrayList<>();

    private final List<EditorBuffer> buffers = new ArrayList<>();
    private EditorBuffer active;
    private LspManager lspManager;
    private DapManager dap;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private List<FakeLanguageServer> fakes;
    private final Map<String, Object> replies = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> commands = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, List<Breakpoint>> store = new LinkedHashMap<>();
    private final List<String> edited = new ArrayList<>();
    private final List<String> stored = new ArrayList<>();
    private volatile boolean saveOk = true;
    private volatile String promptAnswer;
    private int saves;

    private final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            new ArrayList<>(buffers).forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            record("status:" + message);
        }

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            String answer = promptAnswer;
            if (answer != null) {
                onAccept.accept(answer);
            }
        }
    }

    private final class Ops implements DebugCoordinator.Ops {
        @Override
        public void openToolWindow() {}

        @Override
        public void editConfiguration(String name) {
            edited.add(name);
        }

        @Override
        public void toggleToolWindow() {}

        @Override
        public void setToolWindowAvailable(boolean available) {
            record("available:" + available);
        }

        @Override
        public void setStatusDebug(String text) {
            record("state:" + text);
        }

        @Override
        public void setStatusDebugLoading(boolean loading) {}

        @Override
        public boolean saveBuffer(EditorBuffer buffer) {
            saves++;
            return saveOk;
        }

        @Override
        public String programArgs(Path path) {
            return path.getFileName().toString().equals("Args.java") ? "--from-file-store" : "";
        }

        @Override
        public void openLink(StackTraceLinks.Link link) {}

        @Override
        public void openPath(Path file) {}

        @Override
        public EditorBuffer bufferForPath(Path file) {
            return buffers.stream()
                    .filter(b -> file.equals(b.getPath()))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<String> debugWatches() {
            return List.of();
        }

        @Override
        public void persistDebugWatches(List<String> watches) {}

        @Override
        public Map<String, List<Breakpoint>> breakpointMap() {
            return store;
        }

        @Override
        public void saveBreakpoints() {}

        @Override
        public void breakpointsStored(Map<String, ?> bucket, String fileKey) {
            stored.add(String.valueOf(fileKey));
        }
    }

    private Host host;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        adapter = new FakeDebugAdapter(false);
        replies.put("vscode.java.startDebugSession", adapter.port());
        lspManager = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessions(lspManager);
        lspManager.configure(true, Map.of("java", "jdtls"));
        dap = new DapManager(lspManager);
        FxTestSupport.runOnFx(() -> {
            host = new Host();
            host.settings.setDebugSupport(true);
            host.settings.setLspSupport(true);
            LspCoordinator lsp = new LspCoordinator(host, lspManager, new LspOpsStub());
            lsp.setServerAvailableForTest("java", true);
            dap.configure(true, project.resolve("no-plugin-here").toString());
            dap.setServerProvidesJavaDebug(true);
            debug = new DebugCoordinator(host, dap, lspManager, lsp, new Ops());
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            debug.shutdown();
            dap.shutdown();
        });
        lspManager.shutdownAll();
        adapter.close();
    }

    // --- harness --------------------------------------------------------------------------------------

    private void record(String entry) {
        synchronized (log) {
            log.add(entry);
            log.notifyAll();
        }
    }

    private List<String> statuses() {
        synchronized (log) {
            return log.stream()
                    .filter(e -> e.startsWith("status:"))
                    .map(e -> e.substring("status:".length()))
                    .toList();
        }
    }

    private void clearLog() {
        synchronized (log) {
            log.clear();
        }
    }

    /** Waits on the coordinator's own reports (status bar, debug-state segment) until {@code condition}. */
    private void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        synchronized (log) {
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError(what + " never happened; log=" + log + " commands=" + commands);
                }
                TimeUnit.NANOSECONDS.timedWait(log, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            }
        }
    }

    private void awaitStatus(String message) throws InterruptedException {
        await("status '" + message + "'", () -> log.contains("status:" + message));
    }

    private void awaitState(String key) throws InterruptedException {
        String entry = "state:" + (key == null ? null : tr(key));
        await(entry, () -> !log.isEmpty() && lastState().equals(entry));
    }

    private String lastState() {
        for (int i = log.size() - 1; i >= 0; i--) {
            if (log.get(i).startsWith("state:")) {
                return log.get(i);
            }
        }
        return "";
    }

    private EditorBuffer buffer(Path file, String source) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        EditorBuffer[] made = new EditorBuffer[1];
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(file);
            b.setContent(source);
            buffers.add(b);
            active = b;
            made[0] = b;
        });
        return made[0];
    }

    /** A saved Java tab on the fake jdtls, in front. */
    private Path open(String relative) throws Exception {
        Path file = project.resolve(relative);
        buffer(file, SOURCE);
        lspManager.openDocument(file, project, "java", SOURCE);
        for (FakeLanguageServer fake : fakes) {
            fake.executeCommandHandler = params -> {
                commands.add(params.getCommand());
                return replies.get(params.getCommand());
            };
        }
        return file;
    }

    /** jdtls lists {@code classes} as the project's main classes, each in its own file under demo/. */
    private void mainClasses(String... classes) throws Exception {
        Path out = Files.createDirectories(project.resolve("target/classes"));
        replies.put("vscode.java.resolveClasspath", List.of(List.of(), List.of(out.toString())));
        List<Map<String, Object>> listed = new ArrayList<>();
        for (String cls : classes) {
            String simple = cls.substring(cls.lastIndexOf('.') + 1);
            listed.add(Map.of(
                    "mainClass",
                    cls,
                    "projectName",
                    "proj",
                    "filePath",
                    project.resolve("src/main/java/demo/" + simple + ".java").toString()));
        }
        replies.put("vscode.java.resolveMainClass", listed);
    }

    private FakeDebugAdapter.Session session(String kind) throws Exception {
        FakeDebugAdapter.Session session;
        try {
            session = adapter.awaitSession();
        } catch (AssertionError e) {
            throw new AssertionError("no debug session was started; log=" + log, e);
        }
        session.awaitRequest(kind);
        return session;
    }

    /** Debug on the active file, up to a running session. */
    private FakeDebugAdapter.Session launch() throws Exception {
        FxTestSupport.runOnFx(() -> debug.debugStart());
        FakeDebugAdapter.Session session = session("launch");
        awaitState("debug.state.running");
        return session;
    }

    private void stopAt(FakeDebugAdapter.Session session, Path file, int line1) throws Exception {
        session.framePath = file.toString();
        session.frameLine = line1;
        session.stop(7, "breakpoint");
        awaitState("debug.state.suspended");
    }

    private Button button(String name) {
        return FxTestSupport.field(debug.panel(), name);
    }

    private void press(String name) throws Exception {
        Button b = button(name);
        FxTestSupport.runOnFx(() -> {
            assertFalse(b.isDisabled(), "the " + name + " button is usable in this state");
            b.fire();
        });
    }

    private static RunConfiguration config(String name, String type, String mainClass, String beforeLaunch) {
        return new RunConfiguration(
                name,
                type,
                "",
                mainClass,
                "proj",
                "one 'two three'",
                "-Dmode=test",
                "",
                "GREETING=hi",
                beforeLaunch,
                "");
    }

    // --- Debug (the active file) ------------------------------------------------------------------------

    @Test
    void debugOnNothingDebuggableSaysWhyAndStartsNothing() throws Exception {
        FxTestSupport.runOnFx(() -> debug.debugStart());
        assertEquals(List.of(tr("status.debug.saveFirst")), statuses());

        clearLog();
        buffer(loose.resolve("notes.txt"), "plain text\n");
        FxTestSupport.runOnFx(() -> debug.debugStart());
        assertEquals(List.of(tr("status.debug.unavailable")), statuses());

        clearLog();
        open("src/main/java/demo/Args.java");
        mainClasses("demo.Args");
        saveOk = false;
        FxTestSupport.runOnFx(() -> {
            active.getFocusedArea().insertText(0, "// edited\n");
            assertTrue(active.isDirty());
            debug.debugStart();
        });
        FxTestSupport.drainFx();
        assertEquals(1, saves, "the edited file is saved first");
        assertEquals(List.of(), statuses(), "a cancelled save is its own explanation");
        assertEquals(List.of(), commands, "and nothing was launched");
        assertFalse(FxTestSupport.callOnFx(debug::sessionLive));
    }

    @Test
    void debugAgainContinuesAPausedSessionAndRetargetsARunningOneToAnotherFile() throws Exception {
        Path args = open("src/main/java/demo/Args.java");
        EditorBuffer argsBuffer = active;
        mainClasses("demo.Args", "demo.Other");
        FakeDebugAdapter.Session first = launch();
        assertEquals("demo.Args", first.launchArgs.get("mainClass"));
        assertEquals("--from-file-store", first.launchArgs.get("args"), "the file's remembered program arguments");

        FxTestSupport.runOnFx(() -> debug.debugStart()); // same file, running: nothing to continue or replace
        Path other = open("src/main/java/demo/Other.java");
        FxTestSupport.runOnFx(() -> debug.debugStart());

        assertTrue(first.disconnected.await(10, TimeUnit.SECONDS), "the running session is replaced");
        FakeDebugAdapter.Session second = session("launch");
        assertEquals("demo.Other", second.launchArgs.get("mainClass"));
        assertEquals(2, adapter.sessionCount(), "Debug on the same file started no second session");
        assertEquals(other, FxTestSupport.callOnFx(dap::debugFile));

        awaitState("debug.state.running");
        stopAt(second, other, 3);
        FxTestSupport.runOnFx(() -> {
            active = argsBuffer; // looking at another file while paused
            debug.debugStart();
        });
        second.awaitRequest("continue");
        assertEquals(2, adapter.sessionCount(), "a paused session is continued, never replaced");
        assertEquals(1, second.disconnected.getCount());
        assertEquals(args, argsBuffer.getPath());
    }

    @Test
    void restartRepeatsTheLaunchFromTheTabAndStillWorksOnceTheTabIsClosed() throws Exception {
        open("src/main/java/demo/Args.java");
        mainClasses("demo.Args");
        FakeDebugAdapter.Session first = launch();

        FxTestSupport.runOnFx(() -> {
            active.getFocusedArea().insertText(0, "// edited\n");
            debug.restart();
        });
        assertTrue(first.disconnected.await(10, TimeUnit.SECONDS));
        FakeDebugAdapter.Session second = session("launch");
        assertEquals("demo.Args", second.launchArgs.get("mainClass"));
        assertEquals(1, saves, "the edited file is saved again before it is debugged again");
        awaitState("debug.state.running");

        FxTestSupport.runOnFx(() -> {
            buffers.clear(); // the tab was closed while its program runs
            active = null;
            debug.restart();
        });
        assertTrue(second.disconnected.await(10, TimeUnit.SECONDS));
        assertEquals("demo.Args", session("launch").launchArgs.get("mainClass"));
        assertEquals(1, saves, "there is no buffer left to save");
    }

    // --- Attach -----------------------------------------------------------------------------------------

    @Test
    void attachParsesHostAndPortAndRestartAttachesAgain() throws Exception {
        FxTestSupport.runOnFx(() -> debug.debugAttach());
        assertEquals(List.of(tr("status.debug.saveFirst")), statuses());

        clearLog();
        Path file = open("src/main/java/demo/Args.java");
        promptAnswer = " not-a-port ";
        FxTestSupport.runOnFx(() -> debug.debugAttach());
        assertEquals(List.of(tr("status.debug.badAddress", "not-a-port")), statuses());
        assertEquals(0, adapter.sessionCount());

        promptAnswer = "build.example.test:8000";
        FxTestSupport.runOnFx(() -> debug.debugAttach());
        FakeDebugAdapter.Session first = session("attach");
        assertEquals("build.example.test", first.launchArgs.get("hostName"));
        assertEquals("8000", String.valueOf(first.launchArgs.get("port")).replace(".0", ""));
        assertEquals(file, FxTestSupport.callOnFx(dap::debugFile));
        awaitState("debug.state.running");
        assertTrue(FxTestSupport.callOnFx(debug::restartAvailable), "an attach the user made can be made again");

        FxTestSupport.runOnFx(() -> debug.restart());
        assertTrue(first.disconnected.await(10, TimeUnit.SECONDS));
        FakeDebugAdapter.Session second = session("attach");
        assertEquals("build.example.test", second.launchArgs.get("hostName"));

        promptAnswer = "5005"; // a bare port: this machine
        FxTestSupport.runOnFx(() -> debug.debugAttach());
        assertEquals("localhost", session("attach").launchArgs.get("hostName"));
    }

    @Test
    void attachAndTheOtherCommandsSayWhenDebuggingIsNotAvailable() throws Exception {
        open("src/main/java/demo/Args.java");
        mainClasses("demo.Args");
        FxTestSupport.runOnFx(() -> {
            dap.setServerProvidesJavaDebug(false); // jdtls came up without java-debug
            promptAnswer = "5005";
            debug.debugAttach();
            debug.debugMainClass();
            debug.debugConfig(config("App", "java", "demo.Args", ""));
            debug.attachToPort(active.getPath(), "localhost", 5005);
            debug.debugStart();
        });
        FxTestSupport.drainFx();

        String unavailable = tr("status.debug.unavailable");
        assertEquals(List.of(unavailable, unavailable, unavailable, unavailable, unavailable), statuses());
        assertEquals(0, adapter.sessionCount());

        clearLog();
        FxTestSupport.runOnFx(() -> {
            host.settings.setDebugSupport(false);
            debug.ifDebug(() -> record("status:ran"));
            host.settings.setDebugSupport(true);
            debug.ifDebug(() -> record("status:ran"));
        });
        assertEquals(List.of(tr("statusbar.tip.debugDisabled"), "ran"), statuses());
    }

    @Test
    void aJdtlsThatLosesItsDebugCommandsTakesTheDebugWindowAway() throws Exception {
        open("src/main/java/demo/Args.java");
        FxTestSupport.runOnFx(() -> debug.applyGating());
        await("the window to be offered", () -> log.contains("available:true"));
        assertTrue(FxTestSupport.callOnFx(() -> debug.debugEffectiveFor("java")));

        clearLog();
        FxTestSupport.runOnFx(() -> {
            debug.refreshJavaDebugAvailability(); // the fake jdtls advertises no java-debug command
            debug.refreshJavaDebugAvailability(); // nothing changed the second time: no second re-gate
        });

        assertFalse(FxTestSupport.callOnFx(() -> debug.debugEffectiveFor("java")));
        assertFalse(FxTestSupport.callOnFx(() -> debug.debugEffectiveFor("ruby")));
        assertFalse(FxTestSupport.callOnFx(() -> debug.debugEffectiveFor("javascript")));
        assertFalse(FxTestSupport.callOnFx(() -> debug.debugEffectiveFor(null)));
        synchronized (log) {
            assertEquals(1, log.stream().filter(e -> e.startsWith("available:")).count(), log.toString());
        }
    }

    // --- Debug Main Class -------------------------------------------------------------------------------

    @Test
    void debugMainClassNeedsAJavaFileOfAProjectWithAMainClass() throws Exception {
        buffer(loose.resolve("notes.txt"), "plain text\n");
        FxTestSupport.runOnFx(() -> debug.debugMainClass());
        assertEquals(List.of(tr("status.debug.needJavaFile")), statuses());

        clearLog();
        buffer(loose.resolve("Loose.java"), "public class Loose {}\n");
        FxTestSupport.runOnFx(() -> debug.debugMainClass());
        assertEquals(List.of(tr("status.debug.noProject")), statuses());

        clearLog();
        open("src/main/java/demo/Args.java");
        replies.put("vscode.java.resolveMainClass", List.of());
        FxTestSupport.runOnFx(() -> debug.debugMainClass());
        awaitStatus(tr("status.debug.noMainClass"));

        clearLog();
        mainClasses("demo.Args", "demo.Other");
        FxTestSupport.runOnFx(() -> debug.debugMainClassNamed("demo.Missing"));
        awaitStatus(tr("status.debug.noMainClass"));
        assertEquals(0, adapter.sessionCount());
    }

    @Test
    void debugMainClassLaunchesTheProjectsOnlyMainClassFromAnyOfItsFiles() throws Exception {
        open("src/main/java/demo/Helper.java");
        mainClasses("demo.Args");
        saveOk = true;

        FxTestSupport.runOnFx(() -> {
            active.getFocusedArea().insertText(0, "// edited\n");
            debug.debugMainClass();
        });

        FakeDebugAdapter.Session session = session("launch");
        assertEquals("demo.Args", session.launchArgs.get("mainClass"));
        assertEquals(project.toString(), session.launchArgs.get("cwd"));
        assertEquals(
                "--from-file-store", session.launchArgs.get("args"), "the arguments kept for the class's own file");
        assertEquals(1, saves);
        assertEquals(List.of(), statuses());
        awaitState("debug.state.running");

        FxTestSupport.runOnFx(() -> debug.restart());
        assertTrue(session.disconnected.await(10, TimeUnit.SECONDS));
        assertEquals("demo.Args", session("launch").launchArgs.get("mainClass"), "Restart debugs the same class");
    }

    // --- a saved configuration --------------------------------------------------------------------------

    @Test
    void aConfigurationThatCannotBeDebuggedSaysWhatIsMissing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            debug.debugConfig(config("Script", "python", "", ""));
            debug.debugConfig(config("Blank", "java", " ", ""));
            debug.debugConfig(config("File", "java", "src/main/java/demo/Args.java", ""));
            debug.debugConfig(config("NoTab", "java", "demo.Args", ""));
        });
        assertEquals(
                List.of(
                        tr("status.debug.configTypeUnsupported", "python"),
                        tr("status.run.configNeedsMainClass", "Blank"),
                        tr("status.run.mainClassIsAFile", "src/main/java/demo/Args.java"),
                        tr("status.run.configNeedsJavaFile")),
                statuses());
        assertEquals(List.of("Blank", "File"), edited, "the form is opened at the configuration to put right");

        clearLog();
        buffer(loose.resolve("Loose.java"), "public class Loose {}\n");
        FxTestSupport.runOnFx(() -> debug.debugConfig(config("Loose", "java", "Loose", "")));
        assertEquals(List.of(tr("status.debug.noProject")), statuses());

        clearLog();
        buffers.clear();
        open("src/main/java/demo/Args.java");
        mainClasses("demo.Other");
        FxTestSupport.runOnFx(() -> debug.debugConfig(config("Gone", "java", "demo.Args", "")));
        awaitStatus(tr("status.debug.noMainClass"));
        assertEquals(0, adapter.sessionCount());
    }

    @Test
    void aConfigurationIsDebuggedWithItsArgumentsEnvironmentAndWorkingDirectory() throws Exception {
        open("src/main/java/demo/Helper.java");
        mainClasses("demo.Args", "demo.Other");
        Path work = Files.createDirectories(project.resolve("work"));
        RunConfiguration cfg = new RunConfiguration(
                "App",
                "java",
                "",
                "demo.Other",
                "proj",
                "one 'two three'",
                "-Dmode=test",
                work.toString(),
                "GREETING=hi",
                "",
                "");

        FxTestSupport.runOnFx(() -> debug.debugConfig(cfg));

        FakeDebugAdapter.Session session = session("launch");
        assertEquals("demo.Other", session.launchArgs.get("mainClass"));
        assertEquals(work.toString(), session.launchArgs.get("cwd"));
        assertEquals("-Dmode=test", session.launchArgs.get("vmArgs"));
        assertEquals(Map.of("GREETING", "hi"), session.launchArgs.get("env"));
        assertTrue(String.valueOf(session.launchArgs.get("args")).startsWith("one "), session.launchArgs.toString());
        assertEquals(List.of(), statuses());
        awaitState("debug.state.running");

        FxTestSupport.runOnFx(() -> debug.restart());
        assertTrue(session.disconnected.await(10, TimeUnit.SECONDS));
        assertEquals("demo.Other", session("launch").launchArgs.get("mainClass"), "Restart debugs the configuration");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the before-launch step is a /bin/sh command
    void aBeforeLaunchBuildIsShownInTheConsoleAndGatesTheSession() throws Exception {
        open("src/main/java/demo/Args.java");
        mainClasses("demo.Args");
        CodeArea console = FxTestSupport.field(debug.panel(), "console");

        FxTestSupport.runOnFx(() -> debug.debugConfig(
                config("Built", "java", "demo.Args", "sh -c 'echo compiling; echo \"1 warning\" >&2'")));

        FakeDebugAdapter.Session session = session("launch");
        assertEquals("demo.Args", session.launchArgs.get("mainClass"));
        awaitState("debug.state.running");
        String shown = FxTestSupport.callOnFx(console::getText);
        assertTrue(shown.contains("$ sh -c echo compiling; echo \"1 warning\" >&2\n"), shown);
        assertTrue(shown.contains("compiling\n") && shown.contains("1 warning\n"), shown);
        assertTrue(
                statuses().contains(tr("status.run.beforeLaunch", "Built")),
                statuses().toString());

        // A build that fails is not followed by a session on the previous class files.
        FxTestSupport.runOnFx(() -> debug.stop());
        awaitState(null);
        clearLog();
        FxTestSupport.runOnFx(() -> debug.debugConfig(
                config("Broken", "java", "demo.Args", "sh -c 'echo \"Args.java:3: error\"; exit 3'")));
        awaitStatus(tr("status.run.beforeLaunchFailed", "Broken", "Args.java:3: error"));
        FxTestSupport.drainFx();
        assertEquals(1, adapter.sessionCount(), "no session followed the failed build");
        assertFalse(FxTestSupport.callOnFx(debug::sessionLive));

        // One that cannot even be started says so in the console as well.
        clearLog();
        String missing = project.resolve("no-such-build-tool").toString();
        FxTestSupport.runOnFx(() -> debug.debugConfig(config("Missing", "java", "demo.Args", missing)));
        await(
                "the failure",
                () -> log.stream()
                        .anyMatch(e -> e.startsWith("status:")
                                && !e.equals("status:" + tr("status.run.beforeLaunch", "Missing"))));
        assertEquals(1, adapter.sessionCount());
        assertTrue(FxTestSupport.callOnFx(console::getText).contains("$ " + missing + "\n"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the before-launch step is a /bin/sh command
    void stopEndsABeforeLaunchBuildAndASecondLaunchIsRefusedWhileItRuns() throws Exception {
        open("src/main/java/demo/Args.java");
        mainClasses("demo.Args");
        Button stop = button("stop");

        FxTestSupport.runOnFx(() -> debug.debugConfig(config("Slow", "java", "demo.Args", "sh -c 'exec sleep 300'")));
        awaitStatus(tr("status.run.beforeLaunch", "Slow"));
        assertTrue(FxTestSupport.callOnFx(debug::sessionLive), "a build in progress counts as a live session");

        FxTestSupport.runOnFx(() -> debug.debugConfig(config("Again", "java", "demo.Args", "sh -c 'exit 0'")));
        awaitStatus(tr("status.run.busy"));

        FxTestSupport.runOnFx(() -> {
            assertFalse(stop.isDisabled(), "Stop is usable while the build runs");
            stop.fire();
        });
        awaitStatus(tr("status.run.beforeLaunchStopped", "Slow"));
        FxTestSupport.drainFx();
        assertEquals(0, adapter.sessionCount(), "the launch the build was gating did not happen");
        assertFalse(FxTestSupport.callOnFx(debug::sessionLive));
    }

    // --- controls while a session is live ---------------------------------------------------------------

    @Test
    void thePanelsButtonsDriveTheSession() throws Exception {
        Path file = open("src/main/java/demo/Args.java");
        mainClasses("demo.Args");
        FakeDebugAdapter.Session session = launch();

        press("pause");
        session.awaitRequest("pause");
        stopAt(session, file, 3);

        press("stepOver");
        session.awaitRequest("next");
        awaitState("debug.state.running");
        stopAt(session, file, 4);
        press("stepInto");
        session.awaitRequest("stepIn");
        awaitState("debug.state.running");
        stopAt(session, file, 4);
        press("stepOut");
        session.awaitRequest("stepOut");
        awaitState("debug.state.running");
        stopAt(session, file, 5);

        press("start"); // paused: the green button continues
        session.awaitRequest("continue");
        awaitState("debug.state.running");
        assertEquals(1, adapter.sessionCount());

        press("stop");
        assertTrue(session.disconnected.await(10, TimeUnit.SECONDS));
        awaitState(null);
    }

    @Test
    void runToCursorUsesTheCaretLineOfTheFileInFront() throws Exception {
        Path file = open("src/main/java/demo/Args.java");
        mainClasses("demo.Args");
        FakeDebugAdapter.Session session = launch();

        FxTestSupport.runOnFx(() -> debug.debugRunToCursor()); // running: nothing is paused to run from
        stopAt(session, file, 3);
        assertEquals(0, session.breakpoints.size());

        FxTestSupport.runOnFx(() -> active.getFocusedArea().moveTo(4, 0));
        press("runToCursor");
        session.awaitRequest("setBreakpoints");
        session.awaitRequest("continue");
        assertEquals(file.toString(), session.breakpoints.get(0).getSource().getPath());
        assertEquals(5, session.breakpoints.get(0).getBreakpoints()[0].getLine(), "the caret's line, 1-based");
    }

    @Test
    void jumpToLineIsRefusedWithAReasonWhenTheAdapterCannotOrTheLineHasNoTarget() throws Exception {
        Path file = open("src/main/java/demo/Args.java");
        mainClasses("demo.Args");
        FakeDebugAdapter.Session plain = launch();
        FxTestSupport.runOnFx(() -> debug.debugJumpToLine()); // running: nothing to move
        stopAt(plain, file, 3);
        assertEquals(List.of(), statuses());

        FxTestSupport.runOnFx(() -> debug.debugJumpToLine());
        assertEquals(List.of(tr("status.debug.jumpUnsupported")), statuses());

        FxTestSupport.runOnFx(() -> debug.stop());
        awaitState(null);
        clearLog();
        adapter.gotoTargets = true;
        FakeDebugAdapter.Session session = launch();
        stopAt(session, file, 3);
        FxTestSupport.runOnFx(() -> {
            active.getFocusedArea().moveTo(2, 0);
            debug.debugJumpToLine();
        });
        awaitStatus(tr("status.debug.jumpNoTarget"));
        assertEquals(3, session.gotoTargetRequests.get(0).getLine());

        session.gotoTargetsFailure = "Cannot jump out of the current function";
        FxTestSupport.runOnFx(() -> debug.debugJumpToLine());
        awaitStatus("Cannot jump out of the current function");

        session.gotoTargetsFailure = null;
        session.gotoTargetIds = List.of(9);
        FxTestSupport.runOnFx(() -> debug.debugJumpToLine());
        session.awaitRequest("goto");
        assertEquals(9, session.gotoRequests.get(0).getTargetId());
    }

    @Test
    void uncaughtExceptionBreakpointsAreToggledForTheLiveSessionToo() throws Exception {
        open("src/main/java/demo/Args.java");
        mainClasses("demo.Args");
        FakeDebugAdapter.Session session = launch();
        assertEquals(0, session.exceptionBreakpoints.size(), "none by default");

        FxTestSupport.runOnFx(() -> debug.toggleExceptionBreakpoints());
        session.awaitRequest("setExceptionBreakpoints");
        assertEquals(
                List.of("uncaught"), List.of(session.exceptionBreakpoints.get(0).getFilters()));
        FxTestSupport.runOnFx(() -> debug.toggleExceptionBreakpoints());
        session.awaitRequests("setExceptionBreakpoints", 2);
        assertEquals(List.of(), List.of(session.exceptionBreakpoints.get(1).getFilters()));

        assertEquals(List.of(tr("status.debug.exceptionsOn"), tr("status.debug.exceptionsOff")), statuses());
    }

    @Test
    void endingProgramInputWithNoProgramOfOursSaysSo() throws Exception {
        FxTestSupport.runOnFx(() -> debug.endProgramInput());

        assertEquals(List.of(tr("status.debug.noProgramInput")), statuses());
    }

    @Test
    void anAdapterErrorAndANoticeReachTheStatusBarAndTheConsole() throws Exception {
        open("src/main/java/demo/Args.java");
        mainClasses("demo.Args");
        CodeArea console = FxTestSupport.field(debug.panel(), "console");
        FakeDebugAdapter.Session session = launch();

        session.userNotification("ERROR", "Cannot evaluate the condition 'i =='");
        awaitStatus(tr("status.debug.error", "Cannot evaluate the condition 'i =='"));
        assertTrue(
                FxTestSupport.callOnFx(console::getText).contains("Cannot evaluate the condition 'i =='"),
                "the notice is in the console as well");

        adapter.launchFailure = "The class is not on the classpath";
        FxTestSupport.runOnFx(() -> debug.restart());
        awaitStatus(tr("status.debug.error", "launch failed: The class is not on the classpath"));
        awaitState(null);
    }

    // --- stored breakpoints follow the file -------------------------------------------------------------

    private static Breakpoint at(int line) {
        return new Breakpoint(line, "", "", true, "");
    }

    @Test
    void aRenamedFileTakesItsBreakpointsAlongInTheStoreAndInTheLiveSession() throws Exception {
        Path file = open("src/main/java/demo/Args.java");
        EditorBuffer b = active;
        mainClasses("demo.Args");
        store.put(file.toString(), new ArrayList<>(List.of(at(2))));
        FxTestSupport.runOnFx(() -> {
            debug.wireBuffer(b);
            debug.restoreBreakpoints(b);
            assertEquals(
                    List.of(2),
                    b.getBreakpointManager().snapshot().stream()
                            .map(Breakpoint::line)
                            .toList());
        });
        FakeDebugAdapter.Session session = launch();
        session.awaitRequest("setBreakpoints");
        assertEquals(file.toString(), session.breakpoints.get(0).getSource().getPath());

        Path renamed = file.resolveSibling("Renamed.java");
        Files.move(file, renamed);
        FxTestSupport.runOnFx(() -> {
            b.setPath(renamed);
            debug.bufferPathChanged(b, file);
            debug.bufferPathChanged(b, renamed); // the same path again: nothing to carry over
        });

        assertNull(store.get(file.toString()), "nothing is left under the old name");
        assertEquals(
                List.of(2),
                store.get(renamed.toString()).stream().map(Breakpoint::line).toList());
        session.awaitRequests("setBreakpoints", 3);
        assertEquals(file.toString(), session.breakpoints.get(1).getSource().getPath());
        assertEquals(0, session.breakpoints.get(1).getBreakpoints().length, "the old path is cleared at the adapter");
        assertEquals(renamed.toString(), session.breakpoints.get(2).getSource().getPath());
        assertEquals(3, session.breakpoints.get(2).getBreakpoints()[0].getLine());
    }

    @Test
    void aSaveAsCopyKeepsTheOriginalsBreakpointsWhereTheyWere() throws Exception {
        Path file = open("src/main/java/demo/Args.java");
        EditorBuffer b = active;
        store.put(file.toString(), new ArrayList<>(List.of(at(2))));
        FxTestSupport.runOnFx(() -> {
            debug.wireBuffer(b);
            debug.restoreBreakpoints(b);
        });
        Path copy = file.resolveSibling("Copy.java");
        Files.copy(file, copy);

        FxTestSupport.runOnFx(() -> {
            b.setPath(copy);
            debug.bufferPathChanged(b, file);
        });

        assertEquals(
                List.of(2),
                store.get(file.toString()).stream().map(Breakpoint::line).toList());
        assertEquals(
                List.of(2),
                store.get(copy.toString()).stream().map(Breakpoint::line).toList());
    }

    @Test
    void breakpointsOfClosedFilesFollowARenamedFolder() throws Exception {
        Path oldDir = project.resolve("src/main/java/old");
        Path newDir = project.resolve("src/main/java/fresh");
        store.put(oldDir.resolve("A.java").toString(), new ArrayList<>(List.of(at(4))));
        store.put(project.resolve("src/main/java/keep/B.java").toString(), new ArrayList<>(List.of(at(1))));

        FxTestSupport.runOnFx(() -> {
            debug.pathRenamed(oldDir, newDir);
            debug.pathRenamed(project.resolve("src/main/java/unrelated"), project.resolve("elsewhere"));
        });

        assertEquals(
                List.of(
                        newDir.resolve("A.java").toString(),
                        project.resolve("src/main/java/keep/B.java").toString()),
                store.keySet().stream().sorted().toList());
        assertEquals(List.of("null"), stored, "the other windows are told once, for the rename that moved something");
    }

    @Test
    void breakpointsRewrittenByAnotherWindowAreShownAndSentToTheLiveSession() throws Exception {
        Path file = open("src/main/java/demo/Args.java");
        EditorBuffer b = active;
        mainClasses("demo.Args");
        FxTestSupport.runOnFx(() -> debug.wireBuffer(b));
        FakeDebugAdapter.Session session = launch();

        store.put(file.toString(), new ArrayList<>(List.of(at(1), at(3))));
        FxTestSupport.runOnFx(() -> {
            debug.breakpointsChangedElsewhere(new LinkedHashMap<String, Object>(), file.toString()); // another project
            debug.breakpointsChangedElsewhere(
                    store, project.resolve("Other.java").toString()); // another file
            assertEquals(List.of(), b.getBreakpointManager().snapshot());
            debug.breakpointsChangedElsewhere(store, file.toString());
            debug.breakpointsChangedElsewhere(store, null); // "several files": the same set again
        });

        assertEquals(
                List.of(1, 3),
                FxTestSupport.callOnFx(() -> b.getBreakpointManager().snapshot().stream()
                        .map(Breakpoint::line)
                        .toList()));
        session.awaitRequest("setBreakpoints");
        session.awaitDelivered();
        assertEquals(1, session.breakpoints.size(), "an unchanged set is not sent twice");
        assertEquals(2, session.breakpoints.get(0).getBreakpoints().length);
    }

    @Test
    void closedFilesBreakpointsAreArmedAtLaunchUnlessTheStoreEntryIsUnusable() {
        Path closed = project.resolve("Closed.java");
        Map<String, List<Breakpoint>> map = new LinkedHashMap<>();
        map.put(closed.toString(), List.of(at(4), new Breakpoint(6, "", "", false, "")));
        map.put(project.resolve("Empty.java").toString(), List.of());
        map.put(project.resolve("Null.java").toString(), null);
        map.put("bad\u0000path", List.of(at(1)));
        map.put(project.resolve("Open.java").toString(), List.of(at(2)));

        var armed = DebugCoordinator.closedFileBreakpoints(
                map, java.util.Set.of(project.resolve("Open.java").toString()), p -> true, p -> null);

        assertEquals(1, armed.size());
        assertEquals(closed, armed.get(0).file());
        assertEquals(
                List.of(4),
                armed.get(0).breakpoints().stream().map(lb -> lb.line()).toList());
        assertEquals(List.of(), DebugCoordinator.closedFileBreakpoints(null, null, p -> true, p -> null));
    }
}
