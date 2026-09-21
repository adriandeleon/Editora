package com.editora.mcp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.editora.agent.runtime.*;
import com.editora.config.AgentMcpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Session-owned MCP lifecycle. Discovery may reconnect; uncertain tool calls are never retried. */
public final class AgentMcpManager implements AutoCloseable {
    @FunctionalInterface
    public interface Factory {
        McpConnection connect(AgentMcpServer server, Path cwd) throws Exception;
    }

    public record Health(String server, String state, int tools, String detail, List<String> toolNames) {
        public Health(String server, String state, int tools, String detail) {
            this(server, state, tools, detail, List.of());
        }
    }

    private record Connected(McpConnection connection, Map<String, JsonNode> tools) {}

    private final List<AgentMcpServer> servers;
    private final Path cwd;
    private final Factory factory;
    private final Map<String, Connected> connected = new ConcurrentHashMap<>();
    private final Map<String, Health> health = new ConcurrentHashMap<>();
    private final ObjectMapper json = new ObjectMapper();
    private volatile boolean closed;

    public AgentMcpManager(List<AgentMcpServer> servers, Path cwd) {
        this(
                servers,
                cwd,
                (server, root) -> new StdioMcpConnection(com.editora.run.ProgramArgs.tokenize(server.command()), root));
    }

    public AgentMcpManager(List<AgentMcpServer> servers, Path cwd, Factory factory) {
        this.servers = List.copyOf(servers);
        this.cwd = cwd;
        this.factory = factory;
        if (servers.size() > 8
                || servers.stream().map(AgentMcpServer::id).distinct().count() != servers.size())
            throw new IllegalArgumentException("At most eight distinct MCP servers");
    }

    public List<Health> health() {
        return servers.stream()
                .map(s -> {
                    var entry = connected.get(s.id());
                    var h = health.getOrDefault(
                            s.id(), new Health(s.id(), s.enabled() ? "DISCONNECTED" : "DISABLED", 0, ""));
                    if (entry != null && !entry.connection().alive())
                        return new Health(
                                s.id(),
                                "DISCONNECTED",
                                h.tools(),
                                "Reconnect on the next approved call or start a new session to refresh tools");
                    if (entry != null && entry.connection().catalogChanged())
                        return new Health(s.id(), "CATALOG_CHANGED", h.tools(), "New session required for discovery");
                    return h;
                })
                .toList();
    }

    public void register(AgentTools registry, AgentCancellation c) throws Exception {
        for (var server : servers) {
            if (!server.enabled()) continue;
            c.check();
            try {
                var live = connect(server, c);
                for (var entry : live.tools().entrySet()) {
                    String original = entry.getKey();
                    var definition = entry.getValue();
                    String readable = original.replaceAll("[^a-zA-Z0-9_]", "_");
                    String name =
                            "mcp_" + server.id() + "_" + readable.substring(0, Math.min(12, readable.length())) + "_"
                                    + AgentSessionStore.hash(original.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                                            .substring(0, 16);
                    var external = AgentMcpTools.external(
                            name,
                            "MCP server " + server.id() + ", tool " + original + ": "
                                    + AgentContext.bounded(
                                            definition.path("description").asText(), 500),
                            definition.path("inputSchema"),
                            (ignored, args, scope) -> call(server, original, definition, args, scope));
                    var spec = external.spec();
                    registry.register(new AgentTool(
                            new AgentTool.Spec(
                                    spec.name(),
                                    spec.description(),
                                    spec.inputSchema(),
                                    definition.get("outputSchema"),
                                    AgentTool.Effect.EXTERNAL,
                                    Duration.ofSeconds(60),
                                    true,
                                    "mcp:" + server.id()),
                            external.handler()));
                }
            } catch (java.util.concurrent.CancellationException cancelled) {
                throw cancelled;
            } catch (Exception failure) {
                health.put(server.id(), new Health(server.id(), "FAILED", 0, "Connection or tool discovery failed"));
            }
        }
        var schema = json.createObjectNode().put("type", "object");
        schema.putObject("properties");
        registry.register(new AgentTool(
                new AgentTool.Spec(
                        "mcp_status",
                        "Inspect managed MCP server connection health and discovered tool counts.",
                        schema,
                        null,
                        AgentTool.Effect.READ,
                        Duration.ofSeconds(5),
                        true,
                        "editora"),
                (a, scope) -> {
                    var out = json.createArrayNode();
                    for (var h : health())
                        out.addObject()
                                .put("server", h.server())
                                .put("state", h.state())
                                .put("tools", h.tools())
                                .put("detail", h.detail());
                    return AgentTool.Result.ok(out.toString());
                }));
    }

    private JsonNode call(AgentMcpServer server, String tool, JsonNode expected, JsonNode args, AgentCancellation c)
            throws Exception {
        var live = connected.get(server.id());
        if (live == null || !live.connection().alive() || live.connection().catalogChanged()) live = connect(server, c);
        if (!expected.equals(live.tools().get(tool)))
            throw new IllegalStateException("MCP tool changed; start a new session and review its new capabilities");
        var params = json.createObjectNode().put("name", tool);
        params.set("arguments", args);
        try {
            return live.connection().request("tools/call", params, Duration.ofSeconds(50), c);
        } catch (Exception failure) {
            live.connection().close();
            health.put(
                    server.id(),
                    new Health(server.id(), "DISCONNECTED", live.tools().size(), "Call failed; no automatic replay"));
            // External processes can mutate before disconnecting. The runtime must stop this turn.
            throw new java.util.concurrent.TimeoutException(
                    "MCP call interrupted or failed; external effects are uncertain; inspect state before continuing");
        }
    }

    private Connected connect(AgentMcpServer server, AgentCancellation c) throws Exception {
        if (closed) throw new IllegalStateException("MCP manager closed");
        var old = connected.remove(server.id());
        if (old != null) old.connection().close();
        health.put(server.id(), new Health(server.id(), "CONNECTING", 0, ""));
        var connection = factory.connect(server, cwd);
        try (var hook = c.onCancel(connection::close)) {
            var init = json.createObjectNode().put("protocolVersion", "2025-06-18");
            init.putObject("capabilities");
            init.putObject("clientInfo").put("name", "Editora").put("version", "1");
            var response = connection.request("initialize", init, Duration.ofSeconds(10), c);
            if (!List.of("2024-11-05", "2025-03-26", "2025-06-18")
                            .contains(response.path("protocolVersion").asText())
                    || !response.path("capabilities").isObject())
                throw new IllegalArgumentException("Unsupported MCP handshake");
            connection.notification("notifications/initialized", json.createObjectNode());
            Map<String, JsonNode> tools = new java.util.LinkedHashMap<>();
            if (response.path("capabilities").has("tools")) {
                String cursor = null;
                var cursors = new java.util.HashSet<String>();
                int pages = 0;
                do {
                    if (++pages > 32) throw new IllegalArgumentException("MCP catalog page limit exceeded");
                    var params = json.createObjectNode();
                    if (cursor != null) params.put("cursor", cursor);
                    var page = connection.request("tools/list", params, Duration.ofSeconds(10), c);
                    if (!page.path("tools").isArray()) throw new IllegalArgumentException("Malformed MCP catalog");
                    for (var tool : page.get("tools")) {
                        String name = tool.path("name").asText();
                        if (name.isBlank()
                                || name.length() > 128
                                || !tool.path("inputSchema").isObject()
                                || tools.size() >= 32
                                || tools.putIfAbsent(name, tool.deepCopy()) != null)
                            throw new IllegalArgumentException("Invalid MCP tool catalog");
                    }
                    cursor = page.path("nextCursor").isTextual()
                            ? page.path("nextCursor").asText()
                            : null;
                    if (cursor != null && !cursors.add(cursor))
                        throw new IllegalArgumentException("Repeated MCP cursor");
                } while (cursor != null);
            }
            c.check();
            if (closed) throw new IllegalStateException("MCP manager closed");
            var live = new Connected(
                    connection, java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(tools)));
            synchronized (this) {
                if (closed) throw new IllegalStateException("MCP manager closed");
                connected.put(server.id(), live);
            }
            health.put(
                    server.id(), new Health(server.id(), "CONNECTED", tools.size(), "", List.copyOf(tools.keySet())));
            return live;
        } catch (Exception failure) {
            connection.close();
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        connected.values().forEach(c -> c.connection().close());
        connected.clear();
    }
}
