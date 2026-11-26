package com.editora.mcp;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link McpStdio}, the {@code editora mcp} relay: end to end against a real {@link McpServer} that is stopped and
 * started again (a new port and token) while the relay keeps running, and with a scripted transport for the
 * answers it gives by itself.
 */
class McpStdioTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path dir;

    /** The relay on its own thread, fed and read one line at a time, as an agent does. */
    private final class Relay implements AutoCloseable {
        final PipedOutputStream toRelay = new PipedOutputStream();
        final BlockingQueue<String> lines = new ArrayBlockingQueue<>(64);
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final Thread thread;

        Relay(McpStdio.Transport transport) throws Exception {
            PipedInputStream in = new PipedInputStream(toRelay, 1 << 16);
            // Not a pipe: a PipedInputStream fails once the thread that wrote last has ended, and the relay
            // writes each answer from the short-lived thread that handled it.
            OutputStream out = new OutputStream() {
                private final ByteArrayOutputStream line = new ByteArrayOutputStream();

                @Override
                public synchronized void write(int b) {
                    if (b == '\n') {
                        lines.add(line.toString(StandardCharsets.UTF_8));
                        line.reset();
                    } else {
                        line.write(b);
                    }
                }
            };
            McpStdio relay = new McpStdio(dir, transport, new PrintStream(err, true, StandardCharsets.UTF_8));
            relay.reconnectMillis = 100;
            thread = Thread.ofVirtual().start(() -> relay.run(in, out));
        }

        JsonNode call(String json) throws Exception {
            send(json);
            String line = lines.poll(30, TimeUnit.SECONDS);
            assertNotNull(line, "no answer to " + json);
            return mapper.readTree(line);
        }

        /** The next line the relay writes, or null when none comes within {@code millis}. */
        JsonNode nextOrNull(long millis) throws Exception {
            String line = lines.poll(millis, TimeUnit.MILLISECONDS);
            return line == null ? null : mapper.readTree(line);
        }

        JsonNode next() throws Exception {
            JsonNode n = nextOrNull(30_000);
            assertNotNull(n, "nothing from the relay");
            return n;
        }

        void send(String json) throws Exception {
            toRelay.write((json + "\n").getBytes(StandardCharsets.UTF_8));
            toRelay.flush();
        }

        @Override
        public void close() throws Exception {
            toRelay.close();
            thread.join(30_000);
        }
    }

    private static String toolCall(int id, String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{}}}";
    }

    @Test
    void oneSetUpRelayKeepsWorkingWhenTheServerRestartsWithANewPortAndToken() throws Exception {
        McpServer server = new McpServer(new McpServerTest.FakeBridge(), dir);
        server.start();
        McpServer.awaitEndpointFileForTest();
        try (Relay relay = new Relay(McpStdio.httpTransport())) {
            JsonNode init = relay.call(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\"}}");
            assertEquals("2024-11-05", init.at("/result/protocolVersion").asText());
            relay.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"); // answered with nothing
            JsonNode files = relay.call(toolCall(2, "list_open_files"));
            assertEquals(2, files.get("id").asInt(), "the answer to the notification would have come first");
            assertFalse(files.at("/result/isError").asBoolean());
            assertTrue(files.at("/result/content/0/text").asText().contains("/tmp/a.java"), files.toString());

            int oldPort = server.port();
            String oldToken = server.token();
            server.stop();
            McpServer.awaitEndpointFileForTest();
            JsonNode off = relay.call(toolCall(3, "list_open_files"));
            assertTrue(off.at("/result/isError").asBoolean(), off.toString());
            assertTrue(off.at("/result/content/0/text").asText().contains("Editora is not running"), off.toString());

            server = new McpServer(new McpServerTest.FakeBridge(), dir);
            server.start();
            McpServer.awaitEndpointFileForTest();
            assertFalse(server.token().equals(oldToken), "a new token");
            JsonNode again = relay.call(toolCall(4, "list_open_files"));
            assertFalse(again.at("/result/isError").asBoolean(), again.toString());
            assertTrue(again.at("/result/content/0/text").asText().contains("/tmp/a.java"));

            server.newToken();
            McpServer.awaitEndpointFileForTest();
            JsonNode rotated = relay.call(toolCall(5, "list_open_files"));
            assertFalse(rotated.at("/result/isError").asBoolean(), "a new token needs no new set-up: " + rotated);
            assertTrue(server.port() > 0 && oldPort > 0);
        } finally {
            server.stop();
        }
    }

    @Test
    void anAgentStartedBeforeEditoraStillStartsAndSeesTheTools() throws Exception {
        try (Relay relay = new Relay(McpStdio.httpTransport())) {
            JsonNode init = relay.call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
            assertEquals("editora", init.at("/result/serverInfo/name").asText());
            JsonNode list = relay.call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
            List<String> names = new ArrayList<>();
            list.at("/result/tools").forEach(t -> names.add(t.get("name").asText()));
            assertTrue(names.containsAll(List.of("read_buffer", "edit_buffer", "save_buffer")), names.toString());
            assertEquals(
                    new McpToolCatalog(mapper).listToolsResult().get("tools").size(),
                    names.size(),
                    "the catalog the server serves");
            JsonNode ping = relay.call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}");
            assertTrue(ping.get("result").isObject());
            JsonNode other = relay.call("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"prompts/list\"}");
            assertTrue(other.at("/error/message").asText().contains("Editora is not running"), other.toString());
            String err = relay.err.toString(StandardCharsets.UTF_8);
            assertEquals(1, err.lines().count(), "said once on standard error, not for every message: " + err);
        }
    }

    @Test
    void theTokenIsReadAgainOnceWhenRefusedAndARefusalThatStaysIsSaid() throws Exception {
        writeEndpoint("http://127.0.0.1:4242/mcp", "first");
        List<String> tokensSent = new ArrayList<>();
        McpStdio.Transport transport = (endpoint, body, headers) -> {
            tokensSent.add(endpoint.token());
            if (tokensSent.size() == 1) {
                writeEndpoint("http://127.0.0.1:4242/mcp", "second"); // New Token, between the read and the call
                return McpStdio.Reply.json(401, new byte[0]);
            }
            if (endpoint.token().equals("second") && tokensSent.size() == 2) {
                return McpStdio.Reply.json(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}".getBytes());
            }
            return McpStdio.Reply.json(401, new byte[0]);
        };
        McpStdio relay = new McpStdio(dir, transport, new PrintStream(OutputStream.nullOutputStream()));
        assertEquals(
                "{}",
                relay.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")
                        .get("result")
                        .toString());
        assertEquals(List.of("first", "second"), tokensSent);

        JsonNode refused = relay.handle(toolCall(2, "list_tabs"));
        assertTrue(refused.at("/result/isError").asBoolean());
        assertTrue(refused.at("/result/content/0/text").asText().contains("refused the token"), refused.toString());
        assertEquals(4, tokensSent.size(), "one read again, no more");
    }

    @Test
    void theTokenGoesOnlyToALoopbackHttpAddress() throws Exception {
        for (String url : List.of(
                "https://127.0.0.1:4242/mcp",
                "http://example.com:4242/mcp",
                "http://10.0.0.5:4242/mcp",
                "http://127.0.0.1/mcp",
                "http://user@127.0.0.1:4242/mcp",
                "not a url")) {
            writeEndpoint(url, "secret");
            assertNull(McpStdio.readEndpoint(dir, mapper), url);
        }
        writeEndpoint("http://127.0.0.1:4242/mcp", " ");
        assertNull(McpStdio.readEndpoint(dir, mapper), "a blank token");
        Files.writeString(dir.resolve(McpStdio.ENDPOINT_FILE), "{ half written");
        assertNull(McpStdio.readEndpoint(dir, mapper));
        writeEndpoint("http://127.0.0.1:4242/mcp", "secret");
        assertEquals(
                new McpStdio.Endpoint(URI.create("http://127.0.0.1:4242/mcp"), "secret"),
                McpStdio.readEndpoint(dir, mapper));
        assertTrue(McpStdio.isLoopbackHttp(URI.create("http://localhost:9/mcp")));
        assertTrue(McpStdio.isLoopbackHttp(URI.create("http://[::1]:9/mcp")));
    }

    @Test
    void whatIsNotAMessageIsAnsweredAsJsonRpcSays() throws Exception {
        McpStdio relay = new McpStdio(
                dir,
                (e, b, h) -> {
                    throw new AssertionError("nothing is sent");
                },
                new PrintStream(OutputStream.nullOutputStream()));
        assertEquals(
                JsonRpc.PARSE_ERROR, relay.handle("{nope").at("/error/code").asInt());
        JsonNode batch = relay.handle("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]");
        assertEquals(JsonRpc.INVALID_REQUEST, batch.at("/error/code").asInt());
        assertTrue(batch.at("/id").isNull());
        assertNull(relay.handle("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}"), "a response is not answered");
        assertNull(
                relay.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                "a notification is not answered, sent or not");
    }

    @Test
    void theCommandLineTakesTheConfigFolderAndDevAndNothingElse() {
        assertEquals(new McpStdio.Options(null, false, false), McpStdio.parse(List.of()));
        assertEquals(new McpStdio.Options("/x", false, false), McpStdio.parse(List.of("--config-dir", "/x")));
        assertEquals(new McpStdio.Options("/y", true, false), McpStdio.parse(List.of("--config-dir=/y", "--dev")));
        assertEquals(new McpStdio.Options(null, false, true), McpStdio.parse(List.of("-h")));
        assertThrows(IllegalArgumentException.class, () -> McpStdio.parse(List.of("--config-dir")));
        assertThrows(IllegalArgumentException.class, () -> McpStdio.parse(List.of("--config-dir=")));
        assertThrows(IllegalArgumentException.class, () -> McpStdio.parse(List.of("--port", "1")));
        assertTrue(McpStdio.usage().contains("claude mcp add --scope user editora -- editora mcp"));
        // The entry point: help and a usage error print to standard error and return at once (no relay starts).
        assertEquals(McpStdio.OK, McpStdio.main(List.of("--help")));
        assertEquals(McpStdio.USAGE, McpStdio.main(List.of("--bogus")));
    }

    private static final String META = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\","
            + "\"io.modelcontextprotocol/clientCapabilities\":{}}";

    private static String listen(String id, String uri) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"method\":\"subscriptions/listen\",\"params\":{" + META
                + ",\"notifications\":{\"resourcesListChanged\":true,\"resourceSubscriptions\":[\"" + uri + "\"]}}}";
    }

    private static String subscriptionOf(JsonNode notification) {
        return notification
                .at("/params/_meta/io.modelcontextprotocol~1subscriptionId")
                .asText();
    }

    @Test
    void theRelaySendsTheHeadersEachEraAsksFor() throws Exception {
        writeEndpoint("http://127.0.0.1:4242/mcp", "t");
        List<java.util.Map<String, String>> sent = new ArrayList<>();
        McpStdio.Transport transport = (endpoint, body, headers) -> {
            sent.add(headers);
            String method = mapper.readTree(body).get("method").asText();
            String result = method.equals("initialize") ? "{\"protocolVersion\":\"2025-06-18\"}" : "{}";
            return McpStdio.Reply.json(200, ("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}").getBytes());
        };
        McpStdio relay = new McpStdio(dir, transport, new PrintStream(OutputStream.nullOutputStream()));
        relay.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{" + META
                + ",\"name\":\"read_buffer\",\"arguments\":{}}}");
        assertEquals(
                java.util.Map.of(
                        "MCP-Protocol-Version", "2026-07-28", "Mcp-Method", "tools/call", "Mcp-Name", "read_buffer"),
                sent.get(0));
        relay.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
        assertEquals(java.util.Map.of(), sent.get(1), "the handshake comes before any revision is agreed");
        relay.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}");
        assertEquals(java.util.Map.of("MCP-Protocol-Version", "2025-06-18"), sent.get(2), "then the one agreed");
        relay.close();
    }

    @Test
    void aSubscriptionThroughTheRelayOutlivesAnEditorRestart() throws Exception {
        McpServerTest.FakeBridge bridge = new McpServerTest.FakeBridge();
        McpServer server = new McpServer(bridge, dir);
        server.start();
        McpServer.awaitEndpointFileForTest();
        String file = McpTools.fileUri("/tmp/a.java");
        try (Relay relay = new Relay(McpStdio.httpTransport())) {
            relay.send(listen("sub-1", file));
            JsonNode ack = relay.next();
            assertEquals(
                    "notifications/subscriptions/acknowledged",
                    ack.get("method").asText());
            assertEquals("sub-1", subscriptionOf(ack));
            awaitStreams(server, 1);
            server.watchForTest();
            bridge.stamp.incrementAndGet();
            server.watchForTest();
            JsonNode updated = relay.next();
            assertEquals(
                    "notifications/resources/updated", updated.get("method").asText());
            assertEquals("sub-1", subscriptionOf(updated));

            server.stop(); // its goodbye is not passed on: the relay keeps the subscription
            McpServer.awaitEndpointFileForTest();
            assertEquals(null, relay.nextOrNull(500), "the client is not told the subscription ended");

            McpServerTest.FakeBridge bridge2 = new McpServerTest.FakeBridge();
            server = new McpServer(bridge2, dir); // a new port and token
            server.start();
            McpServer.awaitEndpointFileForTest();
            List<String> after = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                JsonNode n = relay.next();
                assertEquals("sub-1", subscriptionOf(n), n.toString());
                after.add(n.get("method").asText());
            }
            assertEquals(
                    List.of("notifications/resources/list_changed", "notifications/resources/updated"),
                    after,
                    "back: the list and the watched file may have changed while it was away");
            awaitStreams(server, 1);
            server.watchForTest();
            bridge2.stamp.incrementAndGet();
            server.watchForTest();
            assertEquals(
                    "notifications/resources/updated",
                    relay.next().get("method").asText());

            relay.send(
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":\"sub-1\"}}");
            // The relay closes its stream; the server notices at a write that fails, so give it some.
            McpServer restarted = server;
            com.editora.testkit.Await.until(
                    () -> {
                        restarted.rebind(bridge2); // a write, which fails once the stream is gone
                        return restarted.streamCountForTest() == 0;
                    },
                    "the server drops the closed stream");
            assertEquals(0, server.streamCountForTest(), "cancelled: the relay no longer listens");
            assertEquals(null, relay.nextOrNull(300), "and nothing more reaches the client");
        } finally {
            server.stop();
        }
    }

    @Test
    void aSubscriptionOpenedBeforeEditoraIsAcknowledgedAndPicksUpWhenItStarts() throws Exception {
        String file = McpTools.fileUri("/tmp/a.java");
        McpServer server = null;
        try (Relay relay = new Relay(McpStdio.httpTransport())) {
            JsonNode discover = relay.call(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\",\"params\":{" + META + "}}");
            assertEquals("complete", discover.at("/result/resultType").asText(), "answered here, as Editora would");
            assertTrue(discover.at("/result/ttlMs").isNumber());
            relay.send(listen("early", file));
            JsonNode ack = relay.next();
            assertEquals(
                    "notifications/subscriptions/acknowledged",
                    ack.get("method").asText());
            assertEquals(
                    file,
                    ack.at("/params/notifications/resourceSubscriptions/0").asText());

            server = new McpServer(new McpServerTest.FakeBridge(), dir);
            server.start();
            McpServer.awaitEndpointFileForTest();
            assertEquals(
                    "notifications/resources/list_changed",
                    relay.next().get("method").asText());
            assertEquals(
                    "notifications/resources/updated",
                    relay.next().get("method").asText());
            awaitStreams(server, 1);
            assertEquals(null, relay.nextOrNull(300), "and only one acknowledgement");
        } finally {
            if (server != null) {
                server.stop();
            }
        }
    }

    @Test
    void aLegacyClientGetsItsNotificationsThroughTheRelay() throws Exception {
        McpServerTest.FakeBridge bridge = new McpServerTest.FakeBridge();
        McpServer server = new McpServer(bridge, dir);
        server.start();
        McpServer.awaitEndpointFileForTest();
        String file = McpTools.fileUri("/tmp/a.java");
        try (Relay relay = new Relay(McpStdio.httpTransport())) {
            JsonNode init = relay.call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                    + "{\"protocolVersion\":\"2025-11-25\"}}");
            assertEquals("2025-11-25", init.at("/result/protocolVersion").asText());
            assertTrue(relay.call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\",\"params\":"
                            + "{\"uri\":\"" + file + "\"}}")
                    .get("result")
                    .isObject());
            awaitStreams(server, 1); // the relay's GET stream
            server.watchForTest();
            bridge.stamp.incrementAndGet();
            server.watchForTest();
            JsonNode updated = relay.next();
            assertEquals(
                    "notifications/resources/updated", updated.get("method").asText());
            assertEquals(file, updated.at("/params/uri").asText());
        } finally {
            server.stop();
        }
    }

    @Test
    void withNoEditorEachMethodGetsTheAnswerItsEraExpects() throws Exception {
        McpStdio relay = new McpStdio(
                dir,
                (e, b, h) -> {
                    throw new AssertionError("no endpoint file: nothing is sent");
                },
                new PrintStream(OutputStream.nullOutputStream()));
        assertEquals(
                0,
                relay.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\",\"params\":{" + META + "}}")
                        .at("/result/resources")
                        .size());
        JsonNode modern = relay.handle(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/templates/list\",\"params\":{" + META + "}}");
        assertEquals("complete", modern.at("/result/resultType").asText());
        assertTrue(modern.at("/result/resourceTemplates").isArray());
        for (String method : List.of("ping", "resources/subscribe", "resources/unsubscribe")) {
            JsonNode a = relay.handle(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"" + method + "\",\"params\":{\"uri\":\"file:///x\"}}");
            assertTrue(a.get("result").isObject(), method);
            assertFalse(a.get("result").has("resultType"), "a legacy answer stays legacy: " + method);
        }
        assertEquals(
                McpProtocol.LEGACY_RESOURCE_NOT_FOUND,
                relay.handle(
                                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/read\",\"params\":{\"uri\":\"file:///x\"}}")
                        .at("/error/code")
                        .asInt());
        assertEquals(
                JsonRpc.INVALID_PARAMS,
                relay.handle("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\",\"params\":{" + META
                                + ",\"uri\":\"file:///x\"}}")
                        .at("/error/code")
                        .asInt(),
                "the modern code for a missing resource");
        relay.close();
    }

    @Test
    void anEditorThatFailsOrAnswersGarbageIsSaidSo() throws Exception {
        writeEndpoint("http://127.0.0.1:4242/mcp", "t");
        String[] mode = {"io"};
        McpStdio.Transport transport = (endpoint, body, headers) -> switch (mode[0]) {
            case "io" -> throw new java.io.IOException("connection refused");
            case "500" -> McpStdio.Reply.json(500, new byte[0]);
            case "garbage" -> McpStdio.Reply.json(200, "<html>".getBytes());
            case "events" ->
                new McpStdio.Reply(
                        200,
                        "text/event-stream",
                        new java.io.ByteArrayInputStream(
                                ("data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
                                                + ": keep-alive\n\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n")
                                        .getBytes()));
            default -> throw new AssertionError(mode[0]);
        };
        McpStdio relay = new McpStdio(dir, transport, new PrintStream(OutputStream.nullOutputStream()));
        for (String m : List.of("io", "500")) {
            mode[0] = m;
            JsonNode a = relay.handle(toolCall(1, "list_tabs"));
            assertTrue(a.at("/result/content/0/text").asText().contains("not running"), m + ": " + a);
        }
        mode[0] = "garbage";
        assertEquals(
                JsonRpc.INTERNAL_ERROR,
                relay.handle(toolCall(1, "list_tabs")).at("/error/code").asInt());
        mode[0] = "events";
        List<ObjectNode> answers = new ArrayList<>();
        relay.handle(toolCall(1, "list_tabs"), answers::add);
        assertEquals(2, answers.size(), "each event of a streamed answer, in order: " + answers);
        assertEquals("notifications/progress", answers.get(0).get("method").asText());
        assertEquals(1, answers.get(1).get("id").asInt());
        relay.close();
    }

    @Test
    void aSubscriptionTheEditorRefusesIsAnsweredWithItsError() throws Exception {
        writeEndpoint("http://127.0.0.1:4242/mcp", "t");
        McpStdio relay = new McpStdio(
                dir,
                (e, b, h) -> McpStdio.Reply.json(
                        400,
                        "{\"jsonrpc\":\"2.0\",\"id\":\"s\",\"error\":{\"code\":-32020,\"message\":\"no\"}}".getBytes()),
                new PrintStream(OutputStream.nullOutputStream()));
        JsonNode a = relay.handle(listen("s", "file:///x"));
        assertEquals(-32020, a.at("/error/code").asInt(), a.toString());
        relay.close();
    }

    @Test
    void aLegacyClientsSubscriptionsAreRepeatedWhenTheEditorComesBack() throws Exception {
        McpServerTest.FakeBridge bridge = new McpServerTest.FakeBridge();
        McpServer server = new McpServer(bridge, dir);
        server.start();
        McpServer.awaitEndpointFileForTest();
        String file = McpTools.fileUri("/tmp/a.java");
        try (Relay relay = new Relay(McpStdio.httpTransport())) {
            relay.call(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
            relay.call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"" + file
                    + "\"}}");
            relay.call(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/subscribe\",\"params\":{\"uri\":\"file:///gone\"}}");
            relay.call(
                    "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/unsubscribe\",\"params\":{\"uri\":\"file:///gone\"}}");
            awaitStreams(server, 1);
            server.stop();
            McpServer.awaitEndpointFileForTest();

            McpServerTest.FakeBridge bridge2 = new McpServerTest.FakeBridge();
            server = new McpServer(bridge2, dir);
            server.start();
            McpServer.awaitEndpointFileForTest();
            assertEquals(
                    "notifications/resources/list_changed",
                    relay.next().get("method").asText());
            JsonNode updated = relay.next();
            assertEquals(
                    "notifications/resources/updated", updated.get("method").asText());
            assertEquals(file, updated.at("/params/uri").asText(), "only what is still subscribed");
            awaitStreams(server, 1);
            server.watchForTest();
            bridge2.stamp.incrementAndGet();
            server.watchForTest();
            JsonNode fromNewServer = relay.next();
            assertEquals(file, fromNewServer.at("/params/uri").asText(), "the subscription was repeated to it");
        } finally {
            server.stop();
        }
    }

    private static void awaitStreams(McpServer server, int n) throws InterruptedException {
        com.editora.testkit.Await.until(() -> server.streamCountForTest() == n, n + " event streams open");
        assertEquals(n, server.streamCountForTest());
    }

    private void writeEndpoint(String url, String token) throws java.io.IOException {
        Files.writeString(
                dir.resolve(McpStdio.ENDPOINT_FILE),
                mapper.createObjectNode().put("url", url).put("token", token).toString());
    }
}
