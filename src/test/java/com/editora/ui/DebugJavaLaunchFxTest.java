package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.editora.config.Breakpoint;
import com.editora.config.Settings;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.run.StackTraceLinks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java debug commands as the window runs them — {@link DebugCoordinator} over the real {@link DapManager}
 * and {@link LspManager}, with a jdtls and a debug adapter that answer in-process — down to the request the
 * adapter receives.
 */
@Tag("fx")
class DebugJavaLaunchFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path project;

    private final List<String> statuses = new CopyOnWriteArrayList<>();
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private EditorBuffer active;
    private LspManager lspManager;
    private DapManager dap;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private List<FakeLanguageServer> fakes;
    private final Map<String, Object> replies = new java.util.concurrent.ConcurrentHashMap<>();

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
            statuses.add(message);
        }
    }

    private final class Ops implements DebugCoordinator.Ops {
        @Override
        public void openToolWindow() {}

        @Override
        public void editConfiguration(String name) {}

        @Override
        public void toggleToolWindow() {}

        @Override
        public void setToolWindowAvailable(boolean available) {}

        @Override
        public void setStatusDebug(String text) {}

        @Override
        public void setStatusDebugLoading(boolean loading) {}

        @Override
        public boolean saveBuffer(EditorBuffer buffer) {
            return true;
        }

        @Override
        public String programArgs(Path path) {
            return "";
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
            return Map.of();
        }

        @Override
        public void saveBreakpoints() {}
    }

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
            Host host = new Host();
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

    /** A saved Java tab on the fake jdtls, in front. */
    private Path open(String relative, String source) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(file);
            b.setContent(source);
            buffers.add(b);
            active = b;
        });
        lspManager.openDocument(file, project, "java", source);
        for (FakeLanguageServer fake : fakes) {
            fake.executeCommandHandler = params -> replies.get(params.getCommand());
        }
        return file;
    }

    private Map<String, Object> request(String kind) throws Exception {
        FakeDebugAdapter.Session session;
        try {
            session = adapter.awaitSession();
        } catch (AssertionError e) {
            throw new AssertionError("no debug session was started; statuses=" + statuses, e);
        }
        session.awaitRequest(kind);
        return session.launchArgs;
    }

    private void classpath() throws Exception {
        Path classes = Files.createDirectories(project.resolve("target/classes"));
        replies.put("vscode.java.resolveClasspath", List.of(List.of(), List.of(classes.toString())));
    }

    /**
     * jdtls names a main class of a named module {@code <module>/<class>}; the gutter ▶ passes the plain
     * class name. Compared whole, they never matched and the status bar said "No main class was found".
     */
    @Test
    void theGutterDebugsAMainClassOfANamedModule() throws Exception {
        Path file = open("src/main/java/app/core/ModMain.java", "package app.core;\npublic class ModMain {\n}\n");
        classpath();
        replies.put(
                "vscode.java.resolveMainClass",
                List.of(Map.of(
                        "mainClass",
                        "app.core/app.core.ModMain",
                        "projectName",
                        "modproj",
                        "filePath",
                        file.toString())));

        FxTestSupport.runOnFx(() -> debug.debugMainClassNamed("app.core.ModMain"));

        Map<String, Object> launch = request("launch");
        assertEquals(
                "app.core/app.core.ModMain", launch.get("mainClass"), "the launch keeps the module-qualified name");
        assertEquals(project.toString(), launch.get("cwd"));
        assertEquals(List.of(), statuses);
    }

    /** Debug on the active file of a project runs where Run and the gutter do: the project root. */
    @Test
    void debugStartOnAProjectFileRunsInTheProjectRoot() throws Exception {
        Path file = open("src/main/java/demo/Args.java", "package demo;\npublic class Args {\n}\n");
        classpath();
        replies.put(
                "vscode.java.resolveMainClass",
                List.of(Map.of("mainClass", "demo.Args", "projectName", "proj", "filePath", file.toString())));

        FxTestSupport.runOnFx(() -> debug.debugStart());

        assertEquals(project.toString(), request("launch").get("cwd"));
    }

    /**
     * A debugger attached to a JVM that a test or build run started cannot be restarted by re-attaching: the
     * JVM stops listening once resumed, and the session was left showing "Running" with no debuggee. Restart
     * now leaves the session alone and says why.
     */
    @Test
    void restartLeavesASessionAttachedToATestRunAloneAndSaysWhy() throws Exception {
        Path file = open("src/test/java/demo/LoopTest.java", "package demo;\npublic class LoopTest {\n}\n");

        FxTestSupport.runOnFx(() -> debug.attachToPort(file, "localhost", 5005));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("attach");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (FxTestSupport.callOnFx(dap::state) != DapManager.State.RUNNING) {
            assertTrue(System.nanoTime() < deadline, "the attach never reached RUNNING");
            Thread.sleep(10);
        }
        statuses.clear();

        FxTestSupport.runOnFx(() -> debug.restart());
        FxTestSupport.drainFx();
        Thread.sleep(300); // a re-attach would have reached the adapter by now
        FxTestSupport.drainFx();

        assertEquals(List.of(tr("status.debug.cannotRestartAttached")), statuses);
        assertEquals(DapManager.State.RUNNING, FxTestSupport.callOnFx(dap::state), "the session is untouched");
        assertEquals(1, adapter.sessionCount(), "no second attach was made");
        assertEquals(1, session.disconnected.getCount(), "and the first was not disconnected");
    }

    /**
     * … and the control no longer invites the attempt: the panel's Restart button is disabled for such a
     * session, with the reason on hover (on the button's holder — a disabled button gets no mouse events), and
     * the palette grays the command with the same sentence. Pressing R in the panel still says why.
     */
    @Test
    void restartIsDisabledWithItsReasonWhileAttachedToATestRun() throws Exception {
        Path file = open("src/test/java/demo/LoopTest.java", "package demo;\npublic class LoopTest {\n}\n");
        DebugPanel panel = debug.panel();
        javafx.scene.control.Button restart = FxTestSupport.field(panel, "restart");
        javafx.scene.Node holder = FxTestSupport.field(panel, "restartHolder");

        FxTestSupport.runOnFx(() -> debug.attachToPort(file, "localhost", 5005));
        adapter.awaitSession().awaitRequest("attach");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (FxTestSupport.callOnFx(dap::state) != DapManager.State.RUNNING) {
            assertTrue(System.nanoTime() < deadline, "the attach never reached RUNNING");
            Thread.sleep(10);
        }
        FxTestSupport.drainFx();

        assertTrue(FxTestSupport.callOnFx(restart::isDisabled), "Restart cannot do anything for this session");
        javafx.scene.control.Tooltip why = FxTestSupport.callOnFx(
                () -> (javafx.scene.control.Tooltip) holder.getProperties().get("javafx.scene.control.Tooltip"));
        assertEquals(tr("status.debug.cannotRestartAttached"), why == null ? null : why.getText());
        assertFalse(FxTestSupport.callOnFx(debug::restartAvailable));
        javafx.scene.control.Button stop = FxTestSupport.field(panel, "stop");
        assertFalse(FxTestSupport.callOnFx(stop::isDisabled), "the session can still be stopped");

        statuses.clear();
        FxTestSupport.runOnFx(() -> panel.fireEvent(new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED,
                "",
                "",
                javafx.scene.input.KeyCode.R,
                false,
                false,
                false,
                false)));
        FxTestSupport.drainFx();
        assertEquals(List.of(tr("status.debug.cannotRestartAttached")), statuses, "the key path still explains");

        // The session that follows, started the ordinary way, can be restarted again.
        FxTestSupport.runOnFx(dap::stop);
        FxTestSupport.drainFx();
        Path main = open("src/main/java/demo/Args.java", "package demo;\npublic class Args {\n}\n");
        classpath();
        replies.put(
                "vscode.java.resolveMainClass",
                List.of(Map.of("mainClass", "demo.Args", "projectName", "proj", "filePath", main.toString())));
        FxTestSupport.runOnFx(() -> debug.debugStart());
        adapter.awaitSession().awaitRequest("launch");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (FxTestSupport.callOnFx(restart::isDisabled)) {
            assertTrue(System.nanoTime() < deadline, "Restart stayed disabled for a launched session");
            Thread.sleep(10);
        }
        assertTrue(FxTestSupport.callOnFx(debug::restartAvailable));
        assertNull(FxTestSupport.callOnFx(() -> holder.getProperties().get("javafx.scene.control.Tooltip")));
    }
}
