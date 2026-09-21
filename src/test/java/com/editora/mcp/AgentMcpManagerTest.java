package com.editora.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.agent.runtime.*;
import com.editora.config.AgentMcpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AgentMcpManagerTest {
    @TempDir
    Path root;

    private final ObjectMapper json = new ObjectMapper();

    private final class Connection implements McpConnection {
        boolean alive = true, failCall, malformed, endless;
        int calls, pages;

        public JsonNode request(String method, JsonNode args, Duration timeout, AgentCancellation c) throws Exception {
            c.check();
            return switch (method) {
                case "initialize" ->
                    json.readTree("{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}}}");
                case "tools/list" ->
                    endless
                            ? json.createObjectNode()
                                    .put("nextCursor", "page" + (++pages))
                                    .set("tools", json.createArrayNode())
                            : json.readTree(
                                    malformed
                                            ? "{}"
                                            : "{\"tools\":[{\"name\":\"read\",\"description\":\"Read\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}},\"annotations\":{\"readOnlyHint\":true}}]}");
                case "tools/call" -> {
                    calls++;
                    if (failCall) throw new java.io.IOException("disconnect");
                    yield json.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}");
                }
                default -> throw new IllegalArgumentException(method);
            };
        }

        public void notification(String method, JsonNode args) {}

        public boolean alive() {
            return alive;
        }

        public boolean catalogChanged() {
            return false;
        }

        public void close() {
            alive = false;
        }
    }

    @Test
    void discoveryRetainsExternalPolicyAndReconnectNeverReplaysFailedCall() throws Exception {
        AtomicInteger starts = new AtomicInteger();
        var connections = new java.util.ArrayList<Connection>();
        try (var manager = new AgentMcpManager(List.of(new AgentMcpServer("fake", "unused", true)), root, (s, p) -> {
            starts.incrementAndGet();
            var c = new Connection();
            connections.add(c);
            return c;
        })) {
            AgentTools registry = new AgentTools();
            manager.register(registry, new AgentCancellation());
            var spec = registry.specs().stream()
                    .filter(s -> s.origin().equals("mcp:fake"))
                    .findFirst()
                    .orElseThrow();
            var policy = new AgentPolicy();
            for (var trust : AgentPolicy.Trust.values()) {
                policy.setTrust(trust);
                assertTrue(policy.requiresApproval(spec));
            }
            connections.getFirst().failCall = true;
            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> registry.get(spec.name())
                            .handler()
                            .execute(json.createObjectNode(), new AgentCancellation()));
            assertEquals(1, starts.get());
            assertEquals(1, connections.getFirst().calls);
            var result = registry.get(spec.name()).handler().execute(json.createObjectNode(), new AgentCancellation());
            assertTrue(result.text().contains("done"));
            assertTrue(result.changed());
            assertEquals(2, starts.get());
            assertEquals(1, connections.getLast().calls);
        }
        assertFalse(connections.getLast().alive);
    }

    @Test
    void repeatedDisconnectsKeepOneLiveConnectionAndNeverReplay() throws Exception {
        var connections = new java.util.ArrayList<Connection>();
        try (var manager = new AgentMcpManager(List.of(new AgentMcpServer("stress", "unused", true)), root, (s, p) -> {
            var connection = new Connection();
            connections.add(connection);
            return connection;
        })) {
            var registry = new AgentTools();
            manager.register(registry, new AgentCancellation());
            var spec = registry.specs().stream()
                    .filter(s -> s.origin().equals("mcp:stress"))
                    .findFirst()
                    .orElseThrow();
            for (int round = 0; round < 25; round++) {
                var old = connections.getLast();
                old.failCall = true;
                int before = old.calls;
                assertThrows(
                        java.util.concurrent.TimeoutException.class,
                        () -> registry.get(spec.name())
                                .handler()
                                .execute(json.createObjectNode(), new AgentCancellation()));
                assertEquals(before + 1, old.calls);
                assertFalse(old.alive);
                var policy = new AgentPolicy();
                policy.setTrust(AgentPolicy.Trust.AGENT);
                assertTrue(policy.requiresApproval(spec));
                assertFalse(registry.get(spec.name())
                        .handler()
                        .execute(json.createObjectNode(), new AgentCancellation())
                        .error());
                assertEquals(1, connections.getLast().calls);
                assertEquals(1, connections.stream().filter(c -> c.alive).count());
                assertEquals(2, registry.specs().size(), "reconnect must not accumulate duplicate tools");
            }
        }
        assertEquals(26, connections.size());
        assertTrue(connections.stream().noneMatch(c -> c.alive));
    }

    @Test
    void malformedCatalogFailsClosedWithoutLeakingAProcessOrTools() throws Exception {
        var connection = new Connection();
        connection.malformed = true;
        try (var manager =
                new AgentMcpManager(List.of(new AgentMcpServer("bad", "unused", true)), root, (s, p) -> connection)) {
            var registry = new AgentTools();
            manager.register(registry, new AgentCancellation());
            assertEquals(
                    List.of("mcp_status"),
                    registry.specs().stream().map(AgentTool.Spec::name).toList());
            assertFalse(connection.alive);
            assertEquals("FAILED", manager.health().getFirst().state());
        }
    }

    @Test
    void endlessEmptyCatalogPagesStopAtTheDiscoveryLimit() throws Exception {
        var connection = new Connection();
        connection.endless = true;
        try (var manager =
                new AgentMcpManager(List.of(new AgentMcpServer("empty", "unused", true)), root, (s, p) -> connection)) {
            manager.register(new AgentTools(), new AgentCancellation());
            assertEquals(32, connection.pages);
            assertFalse(connection.alive);
            assertEquals("FAILED", manager.health().getFirst().state());
        }
    }

    @Test
    void oversizedOrIncompleteStdioMessagesAreRejected() throws Exception {
        assertThrows(
                java.io.IOException.class,
                () -> StdioMcpConnection.line(
                        new java.io.BufferedReader(new java.io.StringReader("x".repeat(1_000_001)))));
        assertThrows(
                java.io.IOException.class,
                () -> StdioMcpConnection.line(
                        new java.io.BufferedReader(new java.io.StringReader("{\"jsonrpc\":\"2.0\"}"))));
    }
}
