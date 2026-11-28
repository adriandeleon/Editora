package com.editora.mcp.ide;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.editora.mcp.McpBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ClaudeIdeServer} with the JDK's own WebSocket client standing in for Claude Code: the lock file it finds, the
 * token it must send, the MCP handshake and the IDE tools, an @-mention, and the lock file gone once it stops.
 */
class ClaudeIdeServerTest {

    private final ObjectMapper m = new ObjectMapper();

    /**
     * A bridge whose abstract methods answer {@code answers} by name (null when absent) and whose default methods do
     * what they do: the IDE tools touch only a few of them.
     */
    private static McpBridge bridge(java.util.Map<String, java.util.function.Function<Object[], Object>> answers) {
        return (McpBridge) java.lang.reflect.Proxy.newProxyInstance(
                McpBridge.class.getClassLoader(), new Class<?>[] {McpBridge.class}, (proxy, method, args) -> {
                    var answer = answers.get(method.getName());
                    if (answer != null) {
                        return answer.apply(args);
                    }
                    if (method.isDefault()) {
                        return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                    }
                    return method.getReturnType() == List.class ? List.of() : null;
                });
    }

    /** A client that queues every message it receives. */
    private static final class Client implements WebSocket.Listener {
        final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        private final StringBuilder part = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            part.append(data);
            if (last) {
                received.add(part.toString());
                part.setLength(0);
            }
            ws.request(1);
            return null;
        }
    }

    private static WebSocket connect(int port, String token, Client client) throws Exception {
        return HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header(ClaudeIdeServer.AUTH_HEADER, token)
                .buildAsync(URI.create("ws://127.0.0.1:" + port), client)
                .get(10, TimeUnit.SECONDS);
    }

    private JsonNode ask(WebSocket ws, Client client, int id, String method, String params) throws Exception {
        ws.sendText(
                        "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params
                                + "}",
                        true)
                .get(10, TimeUnit.SECONDS);
        String answer = client.received.poll(10, TimeUnit.SECONDS);
        assertTrue(answer != null, "an answer to " + method);
        return m.readTree(answer);
    }

    @Test
    void claudeCodeFindsTheWindowConnectsWithTheTokenAndCallsItsTools(@TempDir Path dir) throws Exception {
        Path ide = dir.resolve("ide");
        McpBridge bridge = bridge(java.util.Map.of(
                "listOpenFiles", a -> List.of(new McpBridge.OpenFile("/r/A.java", "A.java", "java", true, true))));
        try (ClaudeIdeServer server = new ClaudeIdeServer(bridge, () -> List.of(Path.of("/r")), ide, "1.0")) {
            JsonNode lock = m.readTree(Files.readString(ide.resolve(server.port() + ".lock")));
            assertEquals("ws", lock.get("transport").asText());
            assertEquals("Editora", lock.get("ideName").asText());
            assertEquals(
                    Path.of("/r").toString(),
                    lock.get("workspaceFolders").get(0).asText()); // \r on Windows
            String token = lock.get("authToken").asText();
            assertTrue(token.matches("[0-9a-f]{32}"), token);

            Client stranger = new Client();
            assertThrows(Exception.class, () -> connect(server.port(), "0".repeat(32), stranger), "a wrong token");

            Client client = new Client();
            WebSocket ws = connect(server.port(), token, client);
            JsonNode init = ask(ws, client, 1, "initialize", "{\"protocolVersion\":\"2025-06-18\"}");
            assertEquals("2025-06-18", init.at("/result/protocolVersion").asText());
            assertEquals("editora", init.at("/result/serverInfo/name").asText());
            ws.sendText("{\"jsonrpc\":\"2.0\",\"method\":\"ide_connected\",\"params\":{\"pid\":1}}", true);

            JsonNode tools = ask(ws, client, 2, "tools/list", "{}");
            List<String> names = new java.util.ArrayList<>();
            tools.at("/result/tools").forEach(t -> names.add(t.get("name").asText()));
            assertTrue(
                    names.containsAll(List.of("openDiff", "openFile", "getDiagnostics", "close_tab")),
                    names.toString());

            JsonNode editors = ask(ws, client, 3, "tools/call", "{\"name\":\"getOpenEditors\",\"arguments\":{}}");
            JsonNode tabs =
                    m.readTree(editors.at("/result/content/0/text").asText()).get("tabs");
            assertEquals("A.java", tabs.get(0).get("label").asText());
            assertTrue(tabs.get(0).get("isDirty").asBoolean());

            JsonNode dirty = ask(
                    ws,
                    client,
                    4,
                    "tools/call",
                    "{\"name\":\"checkDocumentDirty\",\"arguments\":{\"filePath\":\"/r/A.java\"}}");
            assertTrue(m.readTree(dirty.at("/result/content/0/text").asText())
                    .get("isDirty")
                    .asBoolean());

            JsonNode closed =
                    ask(ws, client, 5, "tools/call", "{\"name\":\"close_tab\",\"arguments\":{\"tab_name\":\"x\"}}");
            assertEquals("TAB_CLOSED", closed.at("/result/content/0/text").asText());

            assertEquals(
                    -32601, ask(ws, client, 6, "nope", "{}").at("/error/code").asInt());

            assertTrue(server.atMention(Path.of("/r/A.java"), 3, 5));
            JsonNode mention = m.readTree(client.received.poll(10, TimeUnit.SECONDS));
            assertEquals("at_mentioned", mention.get("method").asText());
            assertEquals(2, mention.at("/params/lineStart").asInt(), "0-based");
            assertEquals(4, mention.at("/params/lineEnd").asInt());
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(10, TimeUnit.SECONDS);
        }
        assertFalse(Files.list(ide).findAny().isPresent(), "the lock file is gone once the server stops");
    }

    @Test
    void openDiffIsAProposalOfTheWholeFile(@TempDir Path dir) throws Exception {
        CompletableFuture<String> proposed = new CompletableFuture<>();
        String[] outcome = {"accepted"};
        int[] saves = {0};
        McpBridge bridge = bridge(java.util.Map.of(
                "listOpenFiles", a -> List.of(new McpBridge.OpenFile("/r/A.java", "A.java", "java", false, true)),
                "reviewProposal",
                        a -> {
                            proposed.complete(a[0] + "|" + a[1] + "|" + a[3]);
                            return new McpBridge.ProposalOutcome(outcome[0], "");
                        },
                "saveBuffer",
                        a -> {
                            saves[0]++;
                            return null;
                        }));
        ClaudeIdeTools tools = new ClaudeIdeTools(m, bridge, List::of);
        var args = m.readTree("{\"old_file_path\":\"/r/A.java\",\"new_file_path\":\"/r/A.java\","
                + "\"new_file_contents\":\"x\\n\",\"tab_name\":\"edit A\"}");
        var saved = tools.call("openDiff", args);
        assertEquals("FILE_SAVED", saved.at("/content/0/text").asText());
        assertEquals("x\n", saved.at("/content/1/text").asText());
        assertEquals("/r/A.java|x\n|Claude Code", proposed.get());
        assertEquals(0, saves[0], "Claude Code writes the file itself: Editora neither applies nor saves");
        outcome[0] = "rejected";
        assertEquals(
                "DIFF_REJECTED",
                tools.call("openDiff", args).at("/content/0/text").asText());
    }

    /**
     * Regression (HN dry run, 2026-11-27): the lock file, which carries the token, was written at the umask's
     * permissions and only then restricted, so another user could read the token for a moment. It is owner-only from
     * the moment it exists, before the token is in it, and a folder this creates is the owner's only.
     */
    @Test
    void theLockFileIsPrivateBeforeTheTokenIsWritten(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                dir.getFileSystem().supportedFileAttributeViews().contains("posix"), "POSIX permissions");
        Path ide = dir.resolve("claude/ide");
        List<String> atCreation = new java.util.concurrent.CopyOnWriteArrayList<>();
        ClaudeIdeServer.lockFileCreatedForTest = tmp -> {
            try {
                atCreation.add(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(tmp))
                        + " " + Files.size(tmp));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
        try (ClaudeIdeServer server = new ClaudeIdeServer(bridge(java.util.Map.of()), List::of, ide, "1.0")) {
            assertEquals(List.of("rw------- 0"), atCreation, "owner-only and still empty when it is created");
            assertEquals(
                    "rw-------",
                    java.nio.file.attribute.PosixFilePermissions.toString(
                            Files.getPosixFilePermissions(server.lockFile())));
            assertEquals(
                    "rwx------",
                    java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(ide)),
                    "the folder this created is the owner's only");
        } finally {
            ClaudeIdeServer.lockFileCreatedForTest = null;
        }
    }

    /** Regression: Claude Code asks for the mcp subprotocol and dropped the connection when the answer named none. */
    @Test
    void theMcpSubprotocolClaudeCodeAsksForIsAgreed(@TempDir Path dir) throws Exception {
        try (ClaudeIdeServer server = new ClaudeIdeServer(bridge(java.util.Map.of()), List::of, dir, "1.0")) {
            String token = m.readTree(Files.readString(server.lockFile()))
                    .get("authToken")
                    .asText();
            WebSocket ws = HttpClient.newHttpClient()
                    .newWebSocketBuilder()
                    .header(ClaudeIdeServer.AUTH_HEADER, token)
                    .subprotocols("mcp")
                    .buildAsync(URI.create("ws://127.0.0.1:" + server.port()), new Client())
                    .get(10, TimeUnit.SECONDS);
            assertEquals("mcp", ws.getSubprotocol());
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(10, TimeUnit.SECONDS);
        }
        assertEquals("mcp", WebSocketServer.subprotocol("chat, mcp"));
        assertEquals(null, WebSocketServer.subprotocol("chat"));
        assertEquals(null, WebSocketServer.subprotocol(null));
    }

    /** Regression: the lock file named no folder when the window learned its project after the server started. */
    @Test
    void theLockFileNamesAFolderTheWindowLearnsLater(@TempDir Path dir) throws Exception {
        List<Path> folders = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (ClaudeIdeServer server = new ClaudeIdeServer(bridge(java.util.Map.of()), () -> folders, dir, "1.0")) {
            assertEquals(
                    0,
                    m.readTree(Files.readString(server.lockFile()))
                            .get("workspaceFolders")
                            .size());
            folders.add(Path.of("/r/jkit"));
            server.refreshLockFile();
            JsonNode lock = m.readTree(Files.readString(server.lockFile()));
            assertEquals(
                    Path.of("/r/jkit").toString(),
                    lock.get("workspaceFolders").get(0).asText()); // \r\jkit on Windows
            assertTrue(lock.get("authToken").asText().matches("[0-9a-f]{32}"), "the same token, written again");
        }
    }

    /** Regression: an Editora that stopped without closing its windows left lock files for Claude Code to offer. */
    @Test
    void lockFilesOfAGoneEditoraAreReapedOthersKept(@TempDir Path dir) throws Exception {
        Process done = new ProcessBuilder("true").start();
        done.waitFor();
        long gone = done.pid();
        Files.writeString(dir.resolve("1.lock"), "{\"pid\":" + gone + ",\"ideName\":\"Editora\"}");
        Files.writeString(dir.resolve("2.lock"), "{\"pid\":" + gone + ",\"ideName\":\"Visual Studio Code\"}");
        Files.writeString(
                dir.resolve("3.lock"), "{\"pid\":" + ProcessHandle.current().pid() + ",\"ideName\":\"Editora\"}");
        Files.writeString(dir.resolve("4.lock"), "not json");
        ClaudeIdeServer.reapStaleLockFiles(dir);
        assertFalse(Files.exists(dir.resolve("1.lock")), "Editora's, process gone");
        assertTrue(Files.exists(dir.resolve("2.lock")), "another IDE's is never touched");
        assertTrue(Files.exists(dir.resolve("3.lock")), "a running Editora's stays");
        assertTrue(Files.exists(dir.resolve("4.lock")), "unreadable: not ours to judge");
        ClaudeIdeServer.reapStaleLockFiles(dir.resolve("missing")); // no folder yet: nothing to do
    }

    @Test
    void theLockFolderFollowsClaudesConfigFolder() {
        assertEquals(Path.of("/c/ide"), ClaudeIdeServer.ideDir("/c", Path.of("/home/u")));
        assertEquals(Path.of("/home/u/.claude/ide"), ClaudeIdeServer.ideDir(null, Path.of("/home/u")));
        assertEquals(
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                WebSocketServer.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="),
                "RFC 6455's example");
    }
}
