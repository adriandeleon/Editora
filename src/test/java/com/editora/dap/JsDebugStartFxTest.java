package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.i18n.Messages;
import com.editora.lsp.LspManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starting the JavaScript debugger with a stand-in {@code node} that runs a stand-in js-debug. Attaching to a Node.js
 * waiting on its inspector ({@link DapManager#startJsAttach}): the server's announced port is found, the session
 * attaches through it, and Stop disconnects. Launching a file: the request names the program, its folder and the
 * Node.js that runs it, and the command line opens the Debug console. A js-debug that dies before it listens, a
 * Node.js too old for js-debug, debugging being off and JavaScript debugging being unavailable are each reported.
 */
@Tag("fx")
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the stand-in node is a shell script")
class JsDebugStartFxTest {

    private static final long WAIT = 30;

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
        onFx(() -> Messages.init("en"));
    }

    @TempDir
    Path dir;

    private LspManager lsp;
    private DapManager dap;
    private FakeDebugAdapter adapter;
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<DapManager.State> states = new CopyOnWriteArrayList<>();
    private final List<String> output = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        adapter = new FakeDebugAdapter(false);
        lsp = new LspManager((f, d) -> {}, (t, m) -> {});
        dap = new DapManager(lsp);
        onFx(() -> dap.setListener(new DapManager.Listener() {
            @Override
            public void onState(DapManager.State state) {
                states.add(state);
            }

            @Override
            public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {}

            @Override
            public void onOutput(String text, String category) {
                output.add(text);
            }

            @Override
            public void onError(String message) {
                errors.add(message);
            }
        }));
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(dap::shutdown);
        lsp.shutdownAll();
        adapter.close();
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

    private void awaitError() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT);
        while (errors.isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "no error was reported; states " + states);
            Thread.sleep(20);
        }
    }

    /** A stand-in {@code node}: answers {@code --version}, and otherwise runs {@code body} as the "js-debug". */
    private Path node(String body) throws Exception {
        Path node = dir.resolve("node");
        Files.writeString(node, "#!/bin/sh\ncase \"$1\" in --version) echo v22.11.0; exit 0;; esac\n" + body + "\n");
        Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("rwxr-xr-x"));
        return node;
    }

    /** JavaScript debugging switched on with {@code node} running a stand-in {@code dapDebugServer.js}. */
    private void enableJs(Path node) throws Exception {
        Path server = Files.writeString(dir.resolve("dapDebugServer.js"), "// stand-in\n");
        CompletableFuture<Boolean> detected = new CompletableFuture<>();
        onFx(() -> {
            dap.configure(true, dir.resolve("no-plugin-here").toString(), false, "", true, server.toString());
            dap.setNodePath(node.toString());
            dap.detectJs(detected::complete);
        });
        assertTrue(detected.get(WAIT, TimeUnit.SECONDS), "the stand-in js-debug is found and its node answers");
        assertTrue(dap.isLanguageAvailable("javascript"));
    }

    @Test
    void anAttachFindsTheServersPortAttachesThroughItAndStopDisconnects() throws Exception {
        // js-debug prints where it listens; the session must connect there, not to a guessed port.
        enableJs(node("echo \"Debug server listening at 127.0.0.1:" + adapter.port() + "\"\nexec sleep 300"));

        onFx(() -> dap.startJsAttach(9229, "Debug request", dir, List.of("<node_internals>/**")));
        FakeDebugAdapter.Session session = adapter.awaitSession();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT);
        while (!session.requests.contains("attach")) {
            assertTrue(System.nanoTime() < deadline, "no attach; requests " + session.requests + ", errors " + errors);
            Thread.sleep(20);
        }
        assertEquals(9229, ((Number) session.launchArgs.get("port")).intValue(), session.launchArgs.toString());
        assertEquals("127.0.0.1", session.launchArgs.get("address"), session.launchArgs.toString());
        assertEquals(DapManager.State.STARTING, states.get(0));

        onFx(dap::stop);
        assertTrue(
                session.disconnected.await(WAIT, TimeUnit.SECONDS), "Stop disconnects; requests " + session.requests);
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void aJsDebugThatExitsBeforeItListensIsReportedWithItsNode() throws Exception {
        Path node = node("echo 'cannot find module' >&2\nexit 1");
        enableJs(node);
        onFx(() -> dap.startJsAttach(9229, "Debug request", dir, List.of()));
        awaitError();
        String expected = tr("debug.error.jsAdapterExited", node.toString());
        assertTrue(errors.get(0).startsWith(expected), errors.toString());
        assertTrue(errors.get(0).contains("cannot find module"), "what it wrote to stderr is shown: " + errors);
    }

    @Test
    void debuggingOffOrJavaScriptUnavailableIsSaidAtOnce() throws Exception {
        onFx(() -> {
            dap.configure(false, "");
            dap.startJsAttach(9229, "Debug request", dir, List.of());
        });
        awaitError();
        assertEquals(List.of(tr("debug.error.disabled")), errors);

        errors.clear();
        onFx(() -> {
            dap.configure(true, dir.resolve("no-plugin-here").toString(), false, "", false, "");
            dap.startJsAttach(9229, "Debug request", dir, List.of());
        });
        awaitError();
        assertEquals(List.of(tr("debug.error.jsUnavailable")), errors);
    }

    @Test
    void launchingAFileSendsItsProgramFolderAndNodeAndShowsTheCommandFirst() throws Exception {
        Path node = node("echo \"Debug server listening at 127.0.0.1:" + adapter.port() + "\"\nexec sleep 300");
        enableJs(node);
        Path project = Files.createDirectories(dir.resolve("app"));
        Files.writeString(project.resolve("package.json"), "{\"name\": \"app\"}\n");
        Path file = Files.writeString(project.resolve("main.js"), "console.log('hi');\n");
        onFx(() -> {
            dap.setProgramConsole(true);
            dap.startLaunch(file, "javascript", (options, chosen) -> {});
        });
        FakeDebugAdapter.Session session = adapter.awaitSession();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT);
        while (!session.requests.contains("launch")) {
            assertTrue(System.nanoTime() < deadline, "no launch; requests " + session.requests + ", errors " + errors);
            Thread.sleep(20);
        }
        assertEquals(file.toString(), session.launchArgs.get("program"), session.launchArgs.toString());
        assertEquals(project.toString(), session.launchArgs.get("cwd"), session.launchArgs.toString());
        assertEquals(node.toString(), session.launchArgs.get("runtimeExecutable"), session.launchArgs.toString());
        assertTrue(
                output.stream().anyMatch(line -> line.contains(node.toString()) && line.contains("main.js")),
                "the console opens with what runs: " + output);
        onFx(dap::stop);
        assertTrue(session.disconnected.await(WAIT, TimeUnit.SECONDS));
    }

    @Test
    void aNodeTooOldForJsDebugIsReported() throws Exception {
        Path node = dir.resolve("node");
        Files.writeString(node, "#!/bin/sh\ncase \"$1\" in --version) echo v16.20.2; exit 0;; esac\nexit 1\n");
        Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("rwxr-xr-x"));
        enableJs(node);
        Path file = Files.writeString(dir.resolve("main.js"), "console.log('hi');\n");
        onFx(() -> dap.startLaunch(file, "javascript", (options, chosen) -> {}));
        awaitError();
        assertEquals(
                List.of(tr("debug.error.jsNodeTooOld", "v16.20.2", node.toString(), JsLaunch.MIN_ADAPTER_NODE_MAJOR)),
                errors);
    }
}
