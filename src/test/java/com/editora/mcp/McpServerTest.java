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

    private static HttpResponse<String> post(int port, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
