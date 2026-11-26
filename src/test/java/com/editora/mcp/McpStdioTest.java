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
            thread = Thread.ofVirtual().start(() -> relay.run(in, out));
        }

        JsonNode call(String json) throws Exception {
            send(json);
            String line = lines.poll(30, TimeUnit.SECONDS);
            assertNotNull(line, "no answer to " + json);
            return mapper.readTree(line);
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
            assertEquals(
                    McpTools.PROTOCOL_VERSION,
                    init.at("/result/protocolVersion").asText());
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
            JsonNode other = relay.call("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}");
            assertTrue(other.at("/error/message").asText().contains("Editora is not running"), other.toString());
            String err = relay.err.toString(StandardCharsets.UTF_8);
            assertEquals(1, err.lines().count(), "said once on standard error, not for every message: " + err);
        }
    }

    @Test
    void theTokenIsReadAgainOnceWhenRefusedAndARefusalThatStaysIsSaid() throws Exception {
        writeEndpoint("http://127.0.0.1:4242/mcp", "first");
        List<String> tokensSent = new ArrayList<>();
        McpStdio.Transport transport = (endpoint, body) -> {
            tokensSent.add(endpoint.token());
            if (tokensSent.size() == 1) {
                writeEndpoint("http://127.0.0.1:4242/mcp", "second"); // New Token, between the read and the call
                return new McpStdio.Reply(401, new byte[0]);
            }
            if (endpoint.token().equals("second") && tokensSent.size() == 2) {
                return new McpStdio.Reply(200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}".getBytes());
            }
            return new McpStdio.Reply(401, new byte[0]);
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
                (e, b) -> {
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
    }

    private void writeEndpoint(String url, String token) throws java.io.IOException {
        Files.writeString(
                dir.resolve(McpStdio.ENDPOINT_FILE),
                mapper.createObjectNode().put("url", url).put("token", token).toString());
    }
}
