package com.editora.mcp;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpServer} over real loopback HTTP in both protocol eras: the legacy {@code initialize} negotiation and GET
 * stream, the modern {@code server/discover}, {@code _meta} requests, header checks and {@code subscriptions/listen},
 * the Origin check, the tool annotations and the resources.
 */
class McpServerErasTest {

    private static final String META = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{}}";

    private final ObjectMapper m = new ObjectMapper();
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TempDir
    Path dir;

    private McpServerTest.FakeBridge bridge;
    private McpServer server;

    @BeforeEach
    void start() throws Exception {
        bridge = new McpServerTest.FakeBridge();
        server = new McpServer(bridge, dir);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private record Answer(int status, JsonNode body) {}

    private Answer post(String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(server.url()))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + server.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(b::header);
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Answer(r.statusCode(), r.body().isEmpty() ? m.nullNode() : m.readTree(r.body()));
    }

    private Answer modern(int id, String method, String params) throws Exception {
        String p = params.isEmpty() ? "{" + META + "}" : "{" + META + "," + params + "}";
        return post(
                "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + p + "}",
                Map.of("MCP-Protocol-Version", "2026-07-28", "Mcp-Method", method));
    }

    /** An event stream read on its own thread, one JSON message per event. */
    private final class Events implements AutoCloseable {
        final BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        final InputStream body;
        final HttpResponse<InputStream> response;
        final Thread reader;

        Events(HttpRequest request) throws Exception {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            body = response.body();
            reader = Thread.ofVirtual().start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            events.add(m.readTree(line.substring(6)));
                        }
                    }
                } catch (Exception ignored) {
                    // closed by the test or the server
                }
            });
        }

        JsonNode next() throws Exception {
            JsonNode e = events.poll(10, TimeUnit.SECONDS);
            assertNotNull(e, "no event");
            return e;
        }

        @Override
        public void close() throws Exception {
            body.close();
            reader.join(5_000);
        }
    }

    // --- legacy ----------------------------------------------------------------------------------

    @Test
    void initializeAgreesOnTheClientsRevisionOrOffersTheNewestLegacyOne() throws Exception {
        String init =
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"%s\"}}";
        assertEquals(
                "2025-06-18",
                post(init.formatted("2025-06-18"), Map.of())
                        .body()
                        .at("/result/protocolVersion")
                        .asText());
        JsonNode result = post(init.formatted("1999-01-01"), Map.of()).body().get("result");
        assertEquals("2025-11-25", result.get("protocolVersion").asText());
        assertTrue(result.get("instructions").asText().contains("Editora"));
        assertFalse(result.has("resultType"), "a legacy result is not decorated");
        String list = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        assertEquals(
                200, post(list, Map.of("MCP-Protocol-Version", "2025-06-18")).status());
        Answer unknownRevision = post(list, Map.of("MCP-Protocol-Version", "2031-01-01"));
        assertEquals(400, unknownRevision.status());
        assertEquals(-32022, unknownRevision.body().at("/error/code").asInt());
    }

    @Test
    void aLegacyGetStreamCarriesTheUpdatesOfWhatWasSubscribed() throws Exception {
        HttpRequest noStream = HttpRequest.newBuilder(URI.create(server.url()))
                .header("Authorization", "Bearer " + server.token())
                .GET()
                .build();
        assertEquals(
                405,
                client.send(noStream, HttpResponse.BodyHandlers.discarding()).statusCode());

        String uri = McpTools.fileUri("/tmp/a.java");
        post(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"" + uri
                        + "\"}}",
                Map.of());
        try (Events stream = new Events(HttpRequest.newBuilder(URI.create(server.url()))
                .header("Authorization", "Bearer " + server.token())
                .header("Accept", "text/event-stream")
                .GET()
                .build())) {
            assertEquals(200, stream.response.statusCode());
            assertTrue(stream.response
                    .headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .startsWith("text/event-stream"));
            awaitStreams(1);
            server.watchForTest(); // the first look only records what is there
            bridge.stamp.incrementAndGet(); // the buffer is edited
            server.watchForTest();
            JsonNode updated = stream.next();
            assertEquals(
                    "notifications/resources/updated", updated.get("method").asText());
            assertEquals(uri, updated.at("/params/uri").asText());
            assertFalse(updated.at("/params").has("_meta"), "a legacy notification carries no subscription id");
        }
    }

    // --- modern ----------------------------------------------------------------------------------

    @Test
    void discoverSaysWhatTheServerSpeaksAndHowLongThatHolds() throws Exception {
        Answer a = modern(1, "server/discover", "");
        assertEquals(200, a.status());
        JsonNode r = a.body().get("result");
        assertEquals("complete", r.get("resultType").asText());
        List<String> versions = new ArrayList<>();
        r.get("supportedVersions").forEach(v -> versions.add(v.asText()));
        assertEquals(McpProtocol.SUPPORTED, versions);
        assertTrue(r.at("/capabilities/resources/subscribe").asBoolean());
        assertEquals(
                "editora",
                r.at("/_meta/io.modelcontextprotocol~1serverInfo/name").asText());
        assertTrue(r.get("ttlMs").asLong() > 0);
        assertEquals("private", r.get("cacheScope").asText());

        JsonNode tools = modern(2, "tools/list", "").body().get("result");
        assertEquals("complete", tools.get("resultType").asText());
        assertTrue(tools.has("ttlMs"));
        JsonNode call = modern(3, "tools/call", "\"name\":\"list_open_files\",\"arguments\":{}")
                .body();
        assertEquals("complete", call.at("/result/resultType").asText());
        assertFalse(call.at("/result/isError").asBoolean(), call.toString());
    }

    @Test
    void aModernRequestIsRefusedWhenItsRevisionOrHeadersDisagree() throws Exception {
        Answer future = post(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":"
                        + "{\"io.modelcontextprotocol/protocolVersion\":\"2031-01-01\"}}}",
                Map.of());
        assertEquals(400, future.status());
        assertEquals(-32022, future.body().at("/error/code").asInt());
        assertEquals("2031-01-01", future.body().at("/error/data/requested").asText());

        Answer wrongMethod = post(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{" + META + "}}",
                Map.of("Mcp-Method", "tools/call"));
        assertEquals(400, wrongMethod.status());
        assertEquals(-32020, wrongMethod.body().at("/error/code").asInt());

        Answer wrongName = post(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{" + META
                        + ",\"name\":\"list_tabs\",\"arguments\":{}}}",
                Map.of("Mcp-Name", "execute_command"));
        assertEquals(400, wrongName.status(), "the header a proxy checks must be the tool that runs");
        assertEquals(-32020, wrongName.body().at("/error/code").asInt());

        Answer unknown = modern(4, "prompts/list", "");
        assertEquals(404, unknown.status());
        assertEquals(JsonRpc.METHOD_NOT_FOUND, unknown.body().at("/error/code").asInt());
    }

    @Test
    void aWebPageCannotReachTheServerEvenWithTheToken() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertEquals(403, post(ping, Map.of("Origin", "https://evil.example")).status());
        assertEquals(200, post(ping, Map.of("Origin", "http://localhost:5173")).status());
        assertTrue(McpServer.originAllowed(null));
        assertTrue(McpServer.originAllowed("http://127.0.0.1:8080"));
        assertFalse(McpServer.originAllowed("http://127.0.0.1.evil.example"));
        assertFalse(McpServer.originAllowed("null"));
    }

    @Test
    void everyToolSaysWhetherItOnlyReads() throws Exception {
        JsonNode tools = modern(1, "tools/list", "").body().at("/result/tools");
        for (JsonNode t : tools) {
            assertFalse(t.path("title").asText().isEmpty(), t.toString());
            assertTrue(t.at("/annotations/readOnlyHint").isBoolean(), t.toString());
        }
        Map<String, JsonNode> byName = new java.util.HashMap<>();
        tools.forEach(t -> byName.put(t.get("name").asText(), t.get("annotations")));
        assertTrue(byName.get("read_buffer").get("readOnlyHint").asBoolean());
        assertFalse(byName.get("edit_buffer").get("readOnlyHint").asBoolean());
        assertFalse(byName.get("edit_buffer").get("destructiveHint").asBoolean(), "undoable, not on disk");
        assertTrue(byName.get("save_buffer").get("destructiveHint").asBoolean());
        assertTrue(byName.get("execute_command").get("openWorldHint").asBoolean());
    }

    @Test
    void theOpenFilesDiagnosticsAndGitStatusAreResources() throws Exception {
        JsonNode list = modern(1, "resources/list", "").body().get("result");
        assertEquals(0, list.get("ttlMs").asInt());
        List<String> uris = new ArrayList<>();
        list.get("resources").forEach(r -> uris.add(r.get("uri").asText()));
        String file = McpTools.fileUri("/tmp/a.java");
        assertEquals(List.of(file, McpTools.DIAGNOSTICS_URI, McpTools.REVIEWS_URI, McpTools.GIT_STATUS_URI), uris);

        JsonNode read =
                modern(2, "resources/read", "\"uri\":\"" + file + "\"").body().get("result");
        assertEquals("hello", read.at("/contents/0/text").asText());
        assertEquals("complete", read.get("resultType").asText());
        JsonNode git = modern(3, "resources/read", "\"uri\":\"" + McpTools.GIT_STATUS_URI + "\"")
                .body();
        assertEquals("application/json", git.at("/result/contents/0/mimeType").asText());

        String missing = "\"uri\":\"https://example.com/x\"";
        assertEquals(
                -32602,
                modern(4, "resources/read", missing).body().at("/error/code").asInt());
        JsonNode legacy = post(
                        "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\",\"params\":{" + missing + "}}",
                        Map.of())
                .body();
        assertEquals(-32002, legacy.at("/error/code").asInt(), "the legacy code for a missing resource");
    }

    @Test
    void aListenStreamIsAcknowledgedCarriesTaggedUpdatesAndEndsWithAResponse() throws Exception {
        String file = McpTools.fileUri("/tmp/a.java");
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"listen-1\",\"method\":\"subscriptions/listen\",\"params\":{"
                + META + ",\"notifications\":{\"resourcesListChanged\":true,\"promptsListChanged\":true,"
                + "\"resourceSubscriptions\":[\"" + file + "\"]}}}";
        Events stream = new Events(HttpRequest.newBuilder(URI.create(server.url()))
                .header("Authorization", "Bearer " + server.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        try {
            JsonNode ack = stream.next();
            assertEquals(
                    "notifications/subscriptions/acknowledged",
                    ack.get("method").asText());
            assertEquals(
                    "listen-1",
                    ack.at("/params/_meta/io.modelcontextprotocol~1subscriptionId")
                            .asText());
            assertFalse(ack.at("/params/notifications").has("promptsListChanged"), "no prompts: not agreed");
            assertEquals(
                    file,
                    ack.at("/params/notifications/resourceSubscriptions/0").asText());

            server.watchForTest();
            bridge.stamp.incrementAndGet();
            server.watchForTest();
            JsonNode updated = stream.next();
            assertEquals(
                    "notifications/resources/updated", updated.get("method").asText());
            assertEquals(file, updated.at("/params/uri").asText());
            assertEquals(
                    "listen-1",
                    updated.at("/params/_meta/io.modelcontextprotocol~1subscriptionId")
                            .asText());

            server.stop(); // the server says goodbye with the listen's response
            JsonNode last = stream.next();
            assertEquals("listen-1", last.get("id").asText());
            assertEquals("complete", last.at("/result/resultType").asText());
        } finally {
            stream.close();
        }
    }

    @Test
    void closingAListenStreamEndsTheSubscription() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"subscriptions/listen\",\"params\":{" + META
                + ",\"notifications\":{\"resourcesListChanged\":true}}}";
        Events stream = new Events(HttpRequest.newBuilder(URI.create(server.url()))
                .header("Authorization", "Bearer " + server.token())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
        stream.next();
        awaitStreams(1);
        stream.close();
        // The server finds out when a write fails: a notification or the keep-alive. Make some until one does
        // (the first after the close may still land in the socket's buffer).
        com.editora.testkit.Await.until(
                () -> {
                    server.rebind(bridge); // a write, which fails once the stream is gone
                    return server.streamCountForTest() == 0;
                },
                "the server drops the closed stream");
        assertEquals(0, server.streamCountForTest());
    }

    @Test
    void subscribingNeedsAUriAndTheTemplatesListIsEmpty() throws Exception {
        String sub = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/%s\",\"params\":%s}";
        assertEquals(
                JsonRpc.INVALID_PARAMS,
                post(sub.formatted("subscribe", "{}"), Map.of())
                        .body()
                        .at("/error/code")
                        .asInt());
        assertTrue(post(sub.formatted("subscribe", "{\"uri\":\"file:///x\"}"), Map.of())
                .body()
                .get("result")
                .isObject());
        assertTrue(post(sub.formatted("unsubscribe", "{\"uri\":\"file:///x\"}"), Map.of())
                .body()
                .get("result")
                .isObject());
        JsonNode templates = modern(2, "resources/templates/list", "").body().get("result");
        assertEquals(0, templates.get("resourceTemplates").size());
        assertEquals(0, templates.get("ttlMs").asInt());
        HttpRequest put = HttpRequest.newBuilder(URI.create(server.url()))
                .header("Authorization", "Bearer " + server.token())
                .PUT(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        assertEquals(
                405, client.send(put, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertFalse(McpServer.originAllowed("http://[bad"));
        Answer modernHeaderOnly = post(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}", Map.of("MCP-Protocol-Version", "2026-07-28"));
        assertEquals(400, modernHeaderOnly.status(), "the header says modern, the body does not");
        assertEquals(-32020, modernHeaderOnly.body().at("/error/code").asInt());
    }

    private void awaitStreams(int n) throws InterruptedException {
        com.editora.testkit.Await.until(() -> server.streamCountForTest() == n, n + " event streams open");
        assertEquals(n, server.streamCountForTest());
    }
}
