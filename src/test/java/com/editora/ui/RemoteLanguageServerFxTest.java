package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.lsp.LspManager;
import com.editora.process.ExecTargets;
import com.editora.vfs.EmbeddedSftpFixture;
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
 * A language server for a remote file runs on the file's host and knows the document by its path there.
 * The "server" is a few lines of Python speaking just enough LSP, started through the in-process SSH server.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class RemoteLanguageServerFxTest {

    /** Answers initialize and hover, and logs what it was told (one JSON message per line). */
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
                elif method == "textDocument/hover":
                    uri = msg["params"]["textDocument"]["uri"]
                    send({"jsonrpc": "2.0", "id": msg["id"], "result": {"contents": "hover for " + uri}})
                elif method == "shutdown":
                    send({"jsonrpc": "2.0", "id": msg["id"], "result": None})
                elif method == "exit":
                    sys.exit(0)
                elif "id" in msg:
                    send({"jsonrpc": "2.0", "id": msg["id"], "result": None})
            """;

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private LspManager manager;
    private Path log;
    private Path remoteApp;

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        Path serverApp = Files.createDirectories(sftp.serverPath("my app"));
        Files.writeString(serverApp.resolve("main.py"), "print('hi')\n");
        Path script = Files.writeString(sftp.serverPath("fake_lsp.py"), SERVER);
        log = sftp.serverPath("lsp.log");
        ExecTargets.setRemoteTools((id, tool) -> tool.equals("lsp.python") ? "python3 " + script + " " + log : null);
        remoteApp = sftp.remotePath("my app");
        manager = new LspManager((file, diagnostics) -> {}, (type, message) -> {});
        // The local setting is a path on this machine: it must not be what runs on the host.
        manager.configure(true, Map.of("python", "/opt/local-only/pyright-langserver --stdio"));
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.shutdownAll();
        sftp.close();
        ExecTargets.setRemoteTools(null);
        ExecTargets.setRemoteEnabled(null);
    }

    private String logText() throws Exception {
        return Files.exists(log) ? Files.readString(log) : "";
    }

    @Test
    void theServerRunsOnTheHostAndKnowsTheFileByItsPathThere() throws Exception {
        Path file = remoteApp.resolve("main.py");
        FxTestSupport.runOnFx(() -> manager.openDocument(file, remoteApp, "python", "print('hi')\n"));

        AtomicReference<String> hover = new AtomicReference<>();
        for (int i = 0; i < 100 && hover.get() == null; i++) {
            CountDownLatch done = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> manager.hover(file, 0, 1, text -> {
                hover.set(text == null || text.isBlank() ? null : text);
                done.countDown();
            }));
            assertTrue(done.await(20, TimeUnit.SECONDS));
            if (hover.get() == null) {
                Thread.sleep(100); // initialize still on its way
            }
        }

        String expectedUri = "file://" + sftp.serverPath("my app").toString().replace(" ", "%20") + "/main.py";
        assertEquals("hover for " + expectedUri, hover.get());
        String told = logText();
        assertTrue(told.contains("\"textDocument/didOpen\"") && told.contains(expectedUri), told);
        assertFalse(told.contains("sftp://"), "the server was shown a key, not its own path: " + told);
        assertTrue(
                told.contains("\"processId\": null") || !told.contains("\"processId\""),
                "a remote server must not be given this machine's pid: " + told);
        assertTrue(
                sftp.commands().stream().anyMatch(line -> line.contains("fake_lsp.py")),
                "the server was not started on the host");
        assertFalse(sftp.commands().stream().anyMatch(line -> line.contains("/opt/local-only")));
    }

    @Test
    void aServerMissingOnTheHostIsSaidToBeMissing() throws Exception {
        ExecTargets.setRemoteTools((id, tool) -> tool.equals("lsp.python") ? "no-such-language-server" : null);
        AtomicReference<Boolean> found = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        FxTestSupport.runOnFx(() -> manager.detectRemote(remoteApp, "python", ok -> {
            found.set(ok);
            done.countDown();
        }));

        assertTrue(done.await(20, TimeUnit.SECONDS));
        assertEquals(Boolean.FALSE, found.get());
        assertEquals(Boolean.FALSE, manager.remoteAvailability(remoteApp, "python"), "remembered for that host");
    }
}
