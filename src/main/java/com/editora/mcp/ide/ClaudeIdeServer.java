package com.editora.mcp.ide;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.mcp.McpBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * An experimental trial of Claude Code's IDE connection (the one its VS Code and JetBrains plugins use, reverse
 * engineered in coder/claudecode.nvim's PROTOCOL.md; not documented by Anthropic): a WebSocket MCP server on the
 * loopback interface, found by Claude Code through a lock file {@code <port>.lock} in {@code ~/.claude/ide} (or
 * {@code $CLAUDE_CONFIG_DIR/ide}) that names the window's folders and carries a random token the client must send as
 * {@code x-claude-code-ide-authorization}. Its tools are the IDE ones (openDiff, openFile, getDiagnostics…), answered
 * through the window's {@link McpBridge}; it tells a connected Claude Code what you select ({@code selection_changed})
 * and what you send it ({@code at_mentioned}).
 */
public final class ClaudeIdeServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ClaudeIdeServer.class.getName());
    static final String AUTH_HEADER = "x-claude-code-ide-authorization";
    static final String PROTOCOL = "2025-03-26";

    private final ObjectMapper m = new ObjectMapper();
    private final McpBridge bridge;
    private final Supplier<List<Path>> folders;
    private final String token;
    private final Path lockFile;
    private final WebSocketServer ws;
    private final String version;

    /**
     * Starts the server and writes its lock file in {@code ideDir}. {@code folders} are the window's workspace
     * folders, read when Claude Code asks and when the lock file is written.
     */
    public ClaudeIdeServer(McpBridge bridge, Supplier<List<Path>> folders, Path ideDir, String version)
            throws IOException {
        this.bridge = bridge;
        this.folders = folders;
        this.version = version;
        byte[] t = new byte[16];
        new SecureRandom().nextBytes(t);
        this.token = HexFormat.of().formatHex(t); // 32 lowercase hex characters, as the plugins write it
        this.ws = new WebSocketServer(0, this::authorized, new WebSocketServer.Handler() {
            @Override
            public void opened(WebSocketServer.Connection c) {}

            @Override
            public void message(WebSocketServer.Connection c, String text) {
                handle(c, text);
            }

            @Override
            public void closed(WebSocketServer.Connection c) {}
        });
        this.lockFile = ideDir.resolve(ws.port() + ".lock");
        writeLockFile(ideDir);
    }

    /** Where Claude Code looks for lock files: {@code $CLAUDE_CONFIG_DIR/ide}, else {@code ~/.claude/ide}. Pure. */
    public static Path ideDir(@Nullable String claudeConfigDir, Path home) {
        return claudeConfigDir != null && !claudeConfigDir.isBlank()
                ? Path.of(claudeConfigDir).resolve("ide")
                : home.resolve(".claude").resolve("ide");
    }

    public int port() {
        return ws.port();
    }

    public Path lockFile() {
        return lockFile;
    }

    /** The token, for a terminal Editora starts (it is in the lock file anyway). */
    String token() {
        return token;
    }

    /** Whether a Claude Code is connected. */
    public boolean connected() {
        return !ws.connections().isEmpty();
    }

    private boolean authorized(Map<String, String> headers) {
        String sent = headers.get(AUTH_HEADER);
        return sent != null
                && MessageDigest.isEqual(sent.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private void writeLockFile(Path ideDir) throws IOException {
        Files.createDirectories(ideDir);
        ObjectNode lock = m.createObjectNode();
        lock.put("pid", ProcessHandle.current().pid());
        ArrayNode ws = lock.putArray("workspaceFolders");
        for (Path p : folders.get()) {
            ws.add(p.toString());
        }
        lock.put("ideName", "Editora");
        lock.put("transport", "ws");
        lock.put("authToken", token);
        Path tmp = ideDir.resolve(lockFile.getFileName() + ".tmp");
        Files.writeString(tmp, m.writeValueAsString(lock));
        try {
            Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows: the user's profile folder is private already
        }
        Files.move(
                tmp,
                lockFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    // --- notifications to Claude Code ------------------------------------------------------------------

    /** Tells every connected Claude Code what is selected: 0-based, the end exclusive, as the plugins send it. */
    public void selectionChanged(McpBridge.Selection s) {
        if (s.path() == null) {
            return;
        }
        notifyAll("selection_changed", selectionJson(s));
    }

    /** Sends {@code path}'s 1-based lines {@code first}–{@code last} to Claude Code as an @-mention. */
    public boolean atMention(Path path, int first, int last) {
        ObjectNode p = m.createObjectNode();
        p.put("filePath", path.toAbsolutePath().toString());
        p.put("lineStart", first - 1);
        p.put("lineEnd", last - 1);
        return notifyAll("at_mentioned", p);
    }

    private boolean notifyAll(String method, ObjectNode params) {
        ObjectNode n = m.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.put("method", method);
        n.set("params", params);
        boolean sent = false;
        for (WebSocketServer.Connection c : ws.connections()) {
            sent |= c.send(n.toString());
        }
        return sent;
    }

    private ObjectNode selectionJson(McpBridge.Selection s) {
        ObjectNode o = m.createObjectNode();
        o.put("text", s.selectedText());
        o.put("filePath", s.path());
        o.put("fileUrl", Path.of(s.path()).toUri().toString());
        ObjectNode sel = o.putObject("selection");
        sel.putObject("start").put("line", s.selStartLine() - 1).put("character", s.selStartCol() - 1);
        sel.putObject("end").put("line", s.selEndLine() - 1).put("character", s.selEndCol() - 1);
        sel.put("isEmpty", s.selectedText().isEmpty());
        return o;
    }

    // --- requests from Claude Code ---------------------------------------------------------------------

    private void handle(WebSocketServer.Connection c, String text) {
        JsonNode req;
        try {
            req = m.readTree(text);
        } catch (IOException e) {
            c.send(error(null, -32700, "Parse error"));
            return;
        }
        if (req == null || !req.has("id") || req.get("id").isNull()) {
            return; // a notification (notifications/initialized, ide_connected): nothing to answer
        }
        JsonNode id = req.get("id");
        String method = req.path("method").asText("");
        JsonNode params = req.path("params");
        // openDiff waits for the user: each request on its own thread keeps the connection reading.
        Thread t = new Thread(
                () -> {
                    String answer;
                    try {
                        answer = answer(id, method, params);
                    } catch (RuntimeException e) {
                        LOG.log(Level.FINE, "IDE request failed", e);
                        answer = error(id, -32603, String.valueOf(e.getMessage()));
                    }
                    c.send(answer);
                },
                "claude-ide-request");
        t.setDaemon(true);
        t.start();
    }

    /** The JSON-RPC answer to one request. Package-private for tests. */
    String answer(JsonNode id, String method, JsonNode params) {
        return switch (method) {
            case "initialize" -> {
                ObjectNode r = m.createObjectNode();
                String asked = params.path("protocolVersion").asText(PROTOCOL);
                r.put("protocolVersion", asked.isBlank() ? PROTOCOL : asked);
                r.putObject("capabilities").putObject("tools").put("listChanged", false);
                r.putObject("serverInfo").put("name", "editora").put("version", version);
                yield success(id, r);
            }
            case "ping" -> success(id, m.createObjectNode());
            case "tools/list" -> success(id, ClaudeIdeTools.list(m));
            case "tools/call" -> success(id, call(params.path("name").asText(""), params.path("arguments")));
            case "resources/list" -> success(id, m.createObjectNode().set("resources", m.createArrayNode()));
            case "prompts/list" -> success(id, m.createObjectNode().set("prompts", m.createArrayNode()));
            default -> error(id, -32601, "Method not found: " + method);
        };
    }

    private ObjectNode call(String tool, JsonNode args) {
        return new ClaudeIdeTools(m, bridge, folders).call(tool, args);
    }

    private String success(JsonNode id, JsonNode result) {
        ObjectNode r = m.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        r.set("result", result);
        return r.toString();
    }

    private String error(@Nullable JsonNode id, int code, String message) {
        ObjectNode r = m.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? m.nullNode() : id);
        r.putObject("error").put("code", code).put("message", message);
        return r.toString();
    }

    /** Stops the server and removes its lock file, so Claude Code no longer offers this window. */
    @Override
    public void close() {
        ws.close();
        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException e) {
            LOG.log(Level.FINE, "could not remove " + lockFile, e);
        }
    }
}
