package com.editora.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests for {@link McpServer} over real loopback HTTP, with a canned {@link McpBridge}
 * (no JavaFX). Exercises auth, tools/list, and a tools/call dispatch + the endpoint-file lifecycle.
 */
class McpServerTest {

    /** A bridge returning fixed data so the server can be tested off the FX thread. */
    static final class FakeBridge implements McpBridge {
        volatile String lastExecuted;
        volatile String lastEdit;

        @Override
        public List<OpenFile> listOpenFiles() {
            return List.of(new OpenFile("/tmp/a.java", "a.java", "java", true, true));
        }

        @Override
        public BufferContent readBuffer(String path) {
            return new BufferContent("/tmp/a.java", "a.java", "java", true, "hello");
        }

        @Override
        public List<Diagnostic> getDiagnostics(String path) {
            return List.of();
        }

        @Override
        public List<SearchMatch> findInFiles(String q, boolean cs, boolean rx, boolean ww) {
            return List.of(new SearchMatch("/tmp/a.java", 1, 1, "hello"));
        }

        @Override
        public List<CommandInfo> listCommands() {
            return List.of(new CommandInfo("file.save", "Save", "Save the file"));
        }

        @Override
        public boolean executeCommand(String id) {
            lastExecuted = id;
            return "file.save".equals(id);
        }

        @Override
        public boolean openFile(String path, int line, int col) {
            return "/tmp/a.java".equals(path);
        }

        @Override
        public String editBuffer(String path, String oldText, String newText, boolean replaceAll) {
            lastEdit = oldText + "->" + newText;
            return null;
        }

        @Override
        public String saveBuffer(String path) {
            return null;
        }

        @Override
        public Selection getSelection() {
            return new Selection("/tmp/a.java", "a.java", 1, 6, 1, 1, 1, 6, "hello");
        }

        @Override
        public List<Symbol> documentSymbols(String path) {
            return List.of(new Symbol("A", "", "class", 1, 10, List.of()));
        }

        @Override
        public GitState gitStatus() {
            return new GitState(true, "/tmp", "main", "origin/main", 1, 0, List.of());
        }

        @Override
        public List<TabInfo> listTabs() {
            return List.of();
        }

        @Override
        public List<TodoItem> todoScan() {
            return List.of();
        }
    }

    @Test
    void rejectsMissingToken(@TempDir Path dir) throws Exception {
        McpServer s = new McpServer(new FakeBridge(), dir);
        int port = s.start();
        try {
            HttpResponse<String> resp = post(port, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            assertEquals(401, resp.statusCode());
        } finally {
            s.stop();
        }
    }

    @Test
    void listsToolsWithToken(@TempDir Path dir) throws Exception {
        McpServer s = new McpServer(new FakeBridge(), dir);
        int port = s.start();
        try {
            HttpResponse<String> resp =
                    post(port, s.token(), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
            assertEquals(200, resp.statusCode());
            assertTrue(resp.body().contains("list_open_files"));
            assertTrue(resp.body().contains("execute_command"));
        } finally {
            s.stop();
        }
    }

    @Test
    void callsExecuteCommand(@TempDir Path dir) throws Exception {
        FakeBridge bridge = new FakeBridge();
        McpServer s = new McpServer(bridge, dir);
        int port = s.start();
        try {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"execute_command\",\"arguments\":{\"id\":\"file.save\"}}}";
            HttpResponse<String> resp = post(port, s.token(), body);
            assertEquals(200, resp.statusCode());
            assertEquals("file.save", bridge.lastExecuted);
            assertTrue(resp.body().contains("ran"));
        } finally {
            s.stop();
        }
    }

    @Test
    void writesAndRemovesEndpointFile(@TempDir Path dir) throws Exception {
        McpServer s = new McpServer(new FakeBridge(), dir);
        s.start();
        Path ep = dir.resolve("mcp-endpoint.json");
        assertTrue(Files.exists(ep));
        s.stop();
        assertFalse(Files.exists(ep));
    }

    @Test
    void rebindMovesToolCallsToTheNewBridgeKeepingThePortAndToken(@TempDir Path dir) throws Exception {
        // #463: when the MCP-owning window closes but others remain, the server is re-bound to a survivor
        // instead of stopped — same port/token, tool calls now route to the survivor's bridge.
        FakeBridge first = new FakeBridge();
        FakeBridge second = new FakeBridge();
        McpServer s = new McpServer(first, dir);
        int port = s.start();
        String token = s.token();
        String call = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"execute_command\",\"arguments\":{\"id\":\"file.save\"}}}";
        try {
            assertEquals(200, post(port, token, call).statusCode());
            assertEquals("file.save", first.lastExecuted, "first bridge got the call");

            s.rebind(second); // window that started MCP closed → ownership moves to a survivor
            assertEquals(port, s.port(), "same port after rebind (server not restarted)");
            assertEquals(token, s.token(), "same token after rebind");

            assertEquals(200, post(port, token, call).statusCode());
            assertEquals("file.save", second.lastExecuted, "the call now routes to the survivor's bridge");
        } finally {
            s.stop();
        }
    }

    private static HttpResponse<String> post(int port, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The bearer token is the ONLY thing between a local process and full control of the editor — the tool
     * surface runs any registered command and reads/writes any path. Jackson's plain write left the file at
     * the umask (0644 in practice) inside a 0755 config dir, so every other local account could read it: on
     * macOS every standard user's primary group is `staff`, and on Linux /home/user is commonly world-
     * readable. Elsewhere this codebase already writes secrets 0600.
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void theEndpointFileIsNotReadableByOtherUsers(@TempDir Path dir) throws Exception {
        McpServer s = new McpServer(new FakeBridge(), dir);
        s.start();
        try {
            Path endpoint = dir.resolve("mcp-endpoint.json");
            assertTrue(Files.isRegularFile(endpoint), "the endpoint file is written on start");
            assertTrue(Files.readString(endpoint).contains(s.token()), "…and it does contain the token");
            var perms = Files.getPosixFilePermissions(endpoint);
            assertEquals(
                    java.util.Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
                    perms,
                    "a file holding a bearer token must be owner-only, was: "
                            + java.nio.file.attribute.PosixFilePermissions.toString(perms));
            assertFalse(
                    Files.getPosixFilePermissions(dir)
                            .contains(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE),
                    "the config dir must not be traversable by others either");
        } finally {
            s.stop();
        }
    }

    /** A batch is legal JSON-RPC and unsupported here — but it must SAY so, not answer 202 and hang. */
    @Test
    void aBatchRequestIsRejectedRatherThanSilentlyAcknowledged(@TempDir Path dir) throws Exception {
        McpServer s = new McpServer(new FakeBridge(), dir);
        int port = s.start();
        try {
            HttpResponse<String> resp =
                    post(port, s.token(), "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}]");
            assertEquals(200, resp.statusCode(), "was 202 with no body — the client waits forever");
            assertTrue(resp.body().contains("-32600"), resp.body());
        } finally {
            s.stop();
        }
    }

    @Test
    void isStaleEndpointReapsDeadOrReusedPidsButKeepsALiveMatch() {
        // A live process whose recorded start matches → not stale (a concurrent Editora instance).
        java.util.function.LongFunction<java.util.Optional<Long>> live =
                pid -> pid == 100 ? java.util.Optional.of(5000L) : java.util.Optional.empty();
        assertFalse(McpServer.isStaleEndpoint(100L, 5000L, live), "live pid + matching start → keep");
        // The pid is alive but its start time differs → the PID was reused → stale.
        assertTrue(McpServer.isStaleEndpoint(100L, 4000L, live), "reused pid → reap");
        // No live process with that pid → stale.
        assertTrue(McpServer.isStaleEndpoint(200L, 5000L, live), "dead pid → reap");
        // No pid at all (an old/unstamped file at launch is from a previous run) → stale.
        assertTrue(McpServer.isStaleEndpoint(null, null, live), "no pid → reap");
        // Live pid but no recorded start to compare → conservative: keep (never delete a possibly-live one).
        assertFalse(McpServer.isStaleEndpoint(100L, null, live), "live pid, unknown start → keep");
    }

    @Test
    void reapStaleEndpointDeletesAFileFromADeadProcessButKeepsALiveOne(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("mcp-endpoint.json");

        // A file from a dead process (a pid that isn't running) is reaped.
        Files.writeString(file, "{\"url\":\"http://127.0.0.1:63681/mcp\",\"token\":\"t\",\"pid\":999999999}");
        McpServer.reapStaleEndpoint(dir);
        assertFalse(Files.exists(file), "stale endpoint file (dead pid) should be reaped");

        // A file stamped with THIS live process is kept.
        long pid = ProcessHandle.current().pid();
        Long start = ProcessHandle.current()
                .info()
                .startInstant()
                .map(java.time.Instant::toEpochMilli)
                .orElse(null);
        String json = start == null
                ? "{\"token\":\"t\",\"pid\":" + pid + "}"
                : "{\"token\":\"t\",\"pid\":" + pid + ",\"startMillis\":" + start + "}";
        Files.writeString(file, json);
        McpServer.reapStaleEndpoint(dir);
        assertTrue(Files.exists(file), "a live process's endpoint file must not be reaped");
    }
}
