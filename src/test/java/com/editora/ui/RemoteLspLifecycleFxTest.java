package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import com.editora.lsp.LspManager;
import com.editora.process.ExecTargets;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.Vfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A language server on a remote host across the life of the connection: a file outside the project on the
 * same host is served by the project's server, losing the connection is not a crash to restart from, and
 * connecting again brings the servers back. The server is a few lines of Python started through the
 * in-process SSH server.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class RemoteLspLifecycleFxTest {

    /** Answers initialize, logs every message, and exits (as a crash) when asked to hover in crash.py. */
    private static final String SERVER = """
            import json, sys
            log = open(sys.argv[1], "a", buffering=1)
            def read():
                length = 0
                while True:
                    line = sys.stdin.buffer.readline()
                    if not line:
                        sys.exit(0)
                    line = line.strip()
                    if not line:
                        break
                    if line.lower().startswith(b"content-length:"):
                        length = int(line.split(b":")[1])
                return json.loads(sys.stdin.buffer.read(length))
            def send(msg):
                body = json.dumps(msg).encode("utf-8")
                sys.stdout.buffer.write(b"Content-Length: %d\\r\\n\\r\\n" % len(body) + body)
                sys.stdout.buffer.flush()
            while True:
                msg = read()
                log.write(json.dumps(msg) + "\\n")
                method = msg.get("method")
                if method == "initialize":
                    send({"jsonrpc": "2.0", "id": msg["id"], "result": {"capabilities": {"textDocumentSync": 1, "hoverProvider": True}}})
                elif method == "textDocument/didOpen" and msg["params"]["textDocument"]["uri"].endswith("/crash.py"):
                    sys.exit(3)
                elif method == "exit":
                    sys.exit(0)
                elif "id" in msg:
                    send({"jsonrpc": "2.0", "id": msg["id"], "result": None})
            """;

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private LspManager manager;
    private LspCoordinator coordinator;
    private final LspCoordinatorFixture.Host host = new LspCoordinatorFixture.Host();
    private final LspCoordinatorFixture.Ops ops = new LspCoordinatorFixture.Ops();
    private Path log;

    @BeforeEach
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        Path app = Files.createDirectories(sftp.serverPath("my app"));
        Files.writeString(app.resolve("main.py"), "print('hi')\n");
        Files.writeString(app.resolve("crash.py"), "boom\n");
        Files.writeString(Files.createDirectories(sftp.serverPath("lib")).resolve("helper.py"), "def f(): pass\n");
        Path script = Files.writeString(sftp.serverPath("fake_lsp.py"), SERVER);
        log = sftp.serverPath("lsp.log");
        ExecTargets.setRemoteTools((id, tool) -> tool.equals("lsp.python") ? "python3 " + script + " " + log : null);
        host.settings.setPythonLspEnabled(true);
        ops.projectRoot = sftp.remotePath("my app");
        manager = new LspManager((file, diagnostics) -> {}, (type, message) -> {});
        FxTestSupport.runOnFx(() -> {
            coordinator = new LspCoordinator(host, manager, ops);
            coordinator.applySupport();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> host.buffers.forEach(EditorBuffer::dispose));
        manager.shutdownAll();
        sftp.close();
        ExecTargets.setRemoteTools(null);
        ExecTargets.setRemoteEnabled(null);
    }

    private EditorBuffer open(Path file) throws Exception {
        String text = Files.readString(file);
        EditorBuffer buffer = FxTestSupport.callOnFx(EditorBuffer::new);
        FxTestSupport.runOnFx(() -> {
            buffer.setPath(file);
            buffer.setContent(text);
            host.buffers.add(buffer);
            host.active = buffer;
            coordinator.syncBuffer(buffer);
        });
        return buffer;
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting until " + what);
            Thread.sleep(50);
        }
    }

    private boolean managed(EditorBuffer buffer) {
        try {
            return FxTestSupport.callOnFx(() -> manager.isManaged(buffer.getPath()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private long launches() {
        return sftp.commands().stream()
                .filter(line -> line.contains("fake_lsp.py"))
                .count();
    }

    private String logText() throws Exception {
        return Files.exists(log) ? Files.readString(log) : "";
    }

    private String pythonLabel() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (String) FxTestSupport.call(coordinator, "serverLabel", new Class<?>[] {String.class}, "python"));
    }

    private static long count(String text, String needle) {
        return text.lines().filter(line -> line.contains(needle)).count();
    }

    @Test
    void aFileOutsideTheProjectOnTheSameHostJoinsTheProjectsServer() throws Exception {
        EditorBuffer main = open(sftp.remotePath("my app/main.py"));
        await("main.py is on the server", () -> managed(main));
        EditorBuffer helper = open(sftp.remotePath("lib/helper.py"));
        await("helper.py is on the server", () -> managed(helper));
        await("the server was told about helper.py", () -> {
            try {
                return logText().contains("/lib/helper.py");
            } catch (Exception e) {
                return false;
            }
        });

        assertEquals(1, launches(), "one server on the host, not one per library folder");
        assertEquals(1, count(logText(), "\"initialize\""), logText());
    }

    @Test
    void aLostConnectionIsNotRestartedAndReconnectingBringsTheServerBack() throws Exception {
        EditorBuffer main = open(sftp.remotePath("my app/main.py"));
        await("main.py is on the server", () -> managed(main));
        // Managed as soon as the session exists, before the start executor has even run the SSH command. A
        // connection dropped that early fails the start itself (a different path), so wait for a running server.
        await("the server is up", () -> {
            try {
                return logText().contains("\"initialize\"");
            } catch (Exception e) {
                return false;
            }
        });
        assertEquals(1, launches());

        sftp.dropConnection();
        await("the dead server let go of main.py", () -> !managed(main));
        await("main.py went quiet", () -> {
            try {
                return !FxTestSupport.callOnFx(main::isLspActive);
            } catch (Exception e) {
                return false;
            }
        });
        Thread.sleep(1500); // past the settle delay: a restart, if one were coming, would have been tried
        FxTestSupport.drainFx();
        assertEquals(1, launches(), "nothing is started against a dead connection");
        String crashed = tr("status.lsp.crashed", pythonLabel());
        String looping = tr("status.lsp.crashLoop", pythonLabel());
        assertFalse(
                host.statuses.stream().anyMatch(s -> s.equals(crashed) || s.equals(looping)),
                "a lost connection is not reported as a crash: " + host.statuses);

        sftp.reconnect();
        String authority = sftp.connection().id();
        FxTestSupport.runOnFx(() -> {
            // What the window does on reconnect: its tabs move to the new connection's filesystem.
            main.setPath(Vfs.parseStorable(Vfs.toStorableString(main.getPath())));
            coordinator.remoteHostConnected(authority);
        });
        await("main.py is back on a server", () -> managed(main));
        await("the server is started again on the new connection", () -> launches() == 2);
    }

    @Test
    void aConnectionLostWhileTheServerStartsLeavesTheTabQuietUntilReconnected() throws Exception {
        sftp.dropConnectionOnCommand("fake_lsp.py");
        EditorBuffer main = open(sftp.remotePath("my app/main.py"));
        await("the server's start was attempted", () -> launches() == 1);
        // The start itself fails, which is not a crash: no restart, but the tab must not look served either.
        await("main.py went quiet", () -> {
            try {
                return !FxTestSupport.callOnFx(main::isLspActive);
            } catch (Exception e) {
                return false;
            }
        });
        assertFalse(managed(main));
        Thread.sleep(1500); // past the settle delay: a restart, if one were coming, would have been tried
        FxTestSupport.drainFx();
        assertEquals(1, launches(), "nothing is started against a dead connection");

        sftp.reconnect();
        String authority = sftp.connection().id();
        FxTestSupport.runOnFx(() -> {
            main.setPath(Vfs.parseStorable(Vfs.toStorableString(main.getPath())));
            coordinator.remoteHostConnected(authority);
        });
        await("main.py is back on a server", () -> managed(main));
        assertTrue(FxTestSupport.callOnFx(main::isLspActive));
        await("the server is started on the new connection", () -> launches() == 2);
    }

    @Test
    void aServerThatDiesWhileTheHostIsReachableIsRestartedLikeALocalOne() throws Exception {
        EditorBuffer crash = open(sftp.remotePath("my app/crash.py"));
        String crashed = tr("status.lsp.crashed", pythonLabel());
        await("the crash is reported", () -> host.statuses.contains(crashed));
        await("a fresh server is started", () -> launches() >= 2);
        assertTrue(FxTestSupport.callOnFx(() -> crash.getPath() != null));
    }
}
