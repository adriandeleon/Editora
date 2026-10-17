package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.process.ExecTargets;
import com.editora.vfs.EmbeddedSftpFixture;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debugging a remote file on its host: the adapter runs there (debugpy over an SSH channel's stdio,
 * vscode-js-debug on a host port reached through a tunnel), is told the program, its folder and the
 * breakpoints by their paths on the host, and the frames it reports come back as files on the connection.
 * The adapter is a Python stand-in speaking just enough DAP, started through the in-process SSH server — as
 * {@code debugpy.adapter} for the stdio case and as {@code dapDebugServer.js} (run by a host-configured
 * "node") for the socket case.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class RemoteDebugFxTest {

    /** Serves DAP on stdio, or on a port when given one (printing the line vscode-js-debug prints). */
    static final String ADAPTER = """
            import json, os, socket, sys
            LOG = os.environ.get("FAKE_DAP_LOG") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "dap.log")
            log = open(LOG, "a", buffering=1)
            def read(rf):
                length = 0
                while True:
                    line = rf.readline()
                    if not line:
                        return None
                    line = line.strip()
                    if not line:
                        break
                    if line.lower().startswith(b"content-length:"):
                        length = int(line.split(b":")[1])
                return json.loads(rf.read(length))
            def serve(rf, wf):
                seq = [0]
                def send(msg):
                    seq[0] += 1
                    msg["seq"] = seq[0]
                    body = json.dumps(msg).encode("utf-8")
                    wf.write(b"Content-Length: %d\\r\\n\\r\\n" % len(body) + body)
                    wf.flush()
                def respond(req, body=None):
                    send({"type": "response", "request_seq": req["seq"], "success": True, "command": req["command"], "body": body or {}})
                def event(name, body=None):
                    send({"type": "event", "event": name, "body": body or {}})
                program = None
                lines = []
                while True:
                    req = read(rf)
                    if req is None:
                        return
                    log.write(json.dumps(req) + "\\n")
                    cmd = req.get("command")
                    args = req.get("arguments") or {}
                    if cmd == "initialize":
                        respond(req, {"supportsConfigurationDoneRequest": True})
                    elif cmd == "launch":
                        program = args.get("program")
                        respond(req)
                        event("initialized")
                    elif cmd == "setBreakpoints":
                        lines = [b["line"] for b in args.get("breakpoints") or []]
                        respond(req, {"breakpoints": [{"verified": True, "line": l} for l in lines]})
                    elif cmd == "configurationDone":
                        respond(req)
                        event("stopped", {"reason": "breakpoint", "threadId": 1, "allThreadsStopped": True})
                    elif cmd == "threads":
                        respond(req, {"threads": [{"id": 1, "name": "main"}]})
                    elif cmd == "stackTrace":
                        frames = [{"id": 1, "name": "main", "source": {"path": program}, "line": lines[0] if lines else 1, "column": 1},
                                  {"id": 2, "name": "boot", "source": {"path": "<frozen importlib._bootstrap>"}, "line": 1, "column": 1}]
                        respond(req, {"stackFrames": frames, "totalFrames": 2})
                    elif cmd in ("disconnect", "terminate"):
                        respond(req)
                        return
                    else:
                        respond(req)
            if len(sys.argv) > 1 and sys.argv[1].isdigit():
                host = sys.argv[2] if len(sys.argv) > 2 else "localhost"
                server = socket.socket()
                server.bind((host, int(sys.argv[1])))
                server.listen(1)
                print("Debug server listening at %s:%d" % (host, server.getsockname()[1]), flush=True)
                conn, _ = server.accept()
                f = conn.makefile("rwb")
                serve(f, f)
            else:
                serve(sys.stdin.buffer, sys.stdout.buffer)
            """;

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private DapManager dap;
    private Path log;
    private final List<List<DapModels.StackFrameInfo>> stops = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        Files.createDirectories(sftp.serverPath("my app"));
        Files.writeString(sftp.serverPath("my app/main.py"), "a = 1\nb = 2\nprint(a + b)\n");
        Files.writeString(sftp.serverPath("my app/main.js"), "let a = 1;\nlet b = 2;\nconsole.log(a + b);\n");
        // The stand-in as a debugpy package, and as a vscode-js-debug install.
        Path adapterDir = Files.createDirectories(sftp.serverPath("site/debugpy/adapter"));
        Files.writeString(sftp.serverPath("site/debugpy/__init__.py"), "");
        Files.writeString(adapterDir.resolve("__init__.py"), "");
        Files.writeString(adapterDir.resolve("__main__.py"), ADAPTER);
        Files.writeString(
                Files.createDirectories(sftp.serverPath("js-debug/src")).resolve("dapDebugServer.js"), ADAPTER);
        log = sftp.serverPath("dap.log");
        dap = new DapManager(null);
        onFx(() -> {
            dap.configure(true, "", true, "/opt/local-only/python3", true, "/opt/local-only/js-debug");
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {}

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                    stops.add(frames);
                }

                @Override
                public void onOutput(String text, String category) {}

                @Override
                public void onError(String message) {
                    errors.add(message);
                }
            });
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(dap::shutdown);
        sftp.close();
        ExecTargets.setRemoteTools(null);
        ExecTargets.setRemoteEnabled(null);
    }

    private static void onFx(Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
    }

    private List<DapModels.StackFrameInfo> awaitStop() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (stops.isEmpty()) {
            assertTrue(errors.isEmpty(), errors.toString());
            assertTrue(
                    System.nanoTime() < deadline,
                    "never stopped at the breakpoint; host ran " + sftp.commands() + "; adapter was told "
                            + (Files.exists(log) ? Files.readString(log) : "nothing"));
            Thread.sleep(50);
        }
        return stops.get(0);
    }

    private JsonObject logged(String command) throws Exception {
        for (String line : Files.readAllLines(log)) {
            JsonObject json = JsonParser.parseString(line).getAsJsonObject();
            if (command.equals(json.get("command").getAsString())) {
                return json.getAsJsonObject("arguments");
            }
        }
        throw new AssertionError("the adapter was never sent " + command);
    }

    private void debug(Path file, String language) throws Exception {
        dap.setBreakpointsSupplier(() -> List.of(
                new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(1, null, null))),
                // A local file of the same path must not reach the host's adapter.
                new DapModels.FileBreakpoints(
                        Path.of(sftp.serverPath("elsewhere.py").toString()),
                        List.of(new DapModels.LineBreakpoint(0, null, null)))));
        onFx(() -> dap.startLaunch(file, language, null));
    }

    private void assertDebuggedOnTheHost(Path file, String folder) throws Exception {
        List<DapModels.StackFrameInfo> frames = awaitStop();
        assertEquals(file, frames.get(0).file(), "the frame is the remote file, on the connection");
        assertEquals(1, frames.get(0).line());
        assertNull(frames.get(1).file(), "a pseudo-source is no file");

        String hostPath = sftp.serverPath(folder).toString();
        assertEquals(hostPath, logged("launch").get("program").getAsString());
        assertEquals(
                sftp.serverPath("my app").toString(),
                logged("launch").get("cwd").getAsString());
        JsonObject breakpoints = logged("setBreakpoints");
        assertEquals(hostPath, breakpoints.getAsJsonObject("source").get("path").getAsString());
        String everything = Files.readString(log);
        assertTrue(
                !everything.contains("elsewhere.py"), "a local file's breakpoint was sent to the host: " + everything);
        assertTrue(!everything.contains("sftp://"), everything);
    }

    @Test
    void pythonIsDebuggedByDebugpyOnTheHost() throws Exception {
        String site = sftp.serverPath("site").toString();
        ExecTargets.setRemoteTools((id, tool) ->
                "dap.python".equals(tool) ? "env FAKE_DAP_LOG=" + log + " PYTHONPATH=" + site + " python3" : null);
        Path file = sftp.remotePath("my app/main.py");

        debug(file, "python");

        assertDebuggedOnTheHost(file, "my app/main.py");
        assertTrue(
                sftp.commands().stream().anyMatch(c -> c.contains("'debugpy.adapter'")),
                "debugpy was not started on the host: " + sftp.commands());
    }

    /**
     * The program runs on the host as Run runs it there: the project's venv on the host (named as the host names
     * it), with the project root on PYTHONPATH — not the host's python3 that runs debugpy.
     */
    @Test
    void pythonOnTheHostRunsUnderTheProjectsVenvThere() throws Exception {
        String site = sftp.serverPath("site").toString();
        ExecTargets.setRemoteTools((id, tool) ->
                "dap.python".equals(tool) ? "env FAKE_DAP_LOG=" + log + " PYTHONPATH=" + site + " python3" : null);
        Files.createDirectories(sftp.serverPath("my app/.venv/bin"));
        Files.writeString(sftp.serverPath("my app/.venv/pyvenv.cfg"), "home = /usr/bin\n");
        Files.writeString(sftp.serverPath("my app/.venv/bin/python"), "");
        Path file = sftp.remotePath("my app/main.py");
        Path root = sftp.remotePath("my app");
        var target = ExecTargets.forPath(file);
        onFx(() -> dap.setPythonPlan(
                com.editora.run.PythonRunPlan.Request.file(
                        file, root, target.toTargetPath(file), List.of(), "", "", ""),
                com.editora.run.PythonRunPlan.Host.elsewhere(target::toTargetPath)));

        debug(file, "python");
        awaitStop();

        JsonObject launch = logged("launch");
        assertEquals(
                sftp.serverPath("my app/.venv/bin/python").toString(),
                launch.get("python").getAsString());
        assertEquals(
                sftp.serverPath("my app").toString(),
                launch.getAsJsonObject("env").get("PYTHONPATH").getAsString());
        assertEquals(sftp.serverPath("my app").toString(), launch.get("cwd").getAsString());
    }

    @Test
    void javascriptIsDebuggedByJsDebugOnTheHostThroughATunnel() throws Exception {
        ExecTargets.setRemoteTools((id, tool) -> switch (tool) {
            case "dap.js" -> sftp.serverPath("js-debug").toString(); // a folder to search
            case "node" -> "python3"; // the stand-in is Python; the host's "node" is configurable
            default -> null;
        });
        log = sftp.serverPath("js-debug/src/dap.log"); // the stand-in logs beside itself here
        Path file = sftp.remotePath("my app/main.js");

        debug(file, "javascript");

        assertDebuggedOnTheHost(file, "my app/main.js");
        assertTrue(
                sftp.commands().stream().anyMatch(c -> c.contains("dapDebugServer.js") && c.contains("'0'")),
                "js-debug was not started on the host: " + sftp.commands());
    }

    @Test
    void aHostWithoutDebugpyIsSaidSo() throws Exception {
        ExecTargets.setRemoteTools((id, tool) -> "dap.python".equals(tool) ? "python3 -S" : null);
        // -S: no site-packages, so no debugpy whatever this machine has installed.

        debug(sftp.remotePath("my app/main.py"), "python");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (errors.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(errors.stream().anyMatch(e -> e.contains("debugpy is not installed")), errors.toString());
    }
}
