package com.editora.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.process.ProcessRegistry;
import com.editora.process.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One <a href="https://agentclientprotocol.com">ACP</a> agent process (e.g. Claude Code via its ACP
 * adapter) driven over stdio — the agent-side analog of {@code lsp.LanguageServerSession}, but with a
 * hand-rolled newline-delimited JSON-RPC loop (ACP is not LSP; no Content-Length framing, no lsp4j).
 *
 * <p>Threading: {@link #start} spawns the process (resolved against the augmented PATH so a
 * Finder-launched app finds an npm-installed agent) and a daemon reader thread; requests return
 * {@link CompletableFuture}s completed on the reader thread; agent→client requests (fs reads/writes,
 * permission asks) are dispatched to a small executor so a blocked handler (a permission dialog) never
 * stalls the read loop. The host marshals to the FX thread itself. The two {@code fs/*} methods are confined
 * to the session folder by {@link AcpFsGuard} before the host is asked at all. {@link #dispose} kills the whole
 * process tree via {@link ProcessRegistry} (an npx wrapper must not orphan the real agent).
 */
public final class AcpClient {

    private static final Logger LOG = Logger.getLogger(AcpClient.class.getName());
    private static final int STDERR_LOG_CAP = 200;

    /** The editor-side services the agent calls back into; see the threading note on {@link AcpClient}. */
    public interface Host {
        /** A parsed {@code session/update} notification (reader thread — marshal to FX yourself). */
        void onUpdate(AcpJson.Update update);

        /** The complete model/mode selector state changed (reader thread). */
        default void onSessionConfig(String sessionId, AcpJson.SessionInfo info) {}

        /**
         * The agent process exited by itself — it quit or crashed (reader thread). Not called for a process
         * this client stopped through {@link AcpClient#dispose()}.
         */
        void onExit(int code);

        /**
         * Serve {@code fs/read_text_file}: the file's live text (open-buffer text wins). May block.
         * {@code path} is always the absolute path {@link AcpFsGuard} resolved inside the session folder.
         */
        String readTextFile(String path, Integer line, Integer limit) throws Exception;

        /**
         * Serve {@code fs/write_text_file}: apply {@code content} to the buffer/file. May block.
         * {@code path} is always the absolute path {@link AcpFsGuard} resolved inside the session folder.
         */
        void writeTextFile(String path, String content) throws Exception;

        /** Serve {@code session/request_permission}: resolve to the chosen optionId, or null = cancelled. */
        CompletableFuture<String> requestPermission(String title, List<AcpJson.PermissionOption> options);

        /** A directory the agent must never write into even when it lies inside the session folder — the
         *  editor's own configuration directory. {@code null} = none. */
        default Path writeProtectedDirectory() {
            return null;
        }
    }

    private final List<String> command;
    private final Path cwd;
    private final Host host;
    private final Map<String, String> environment;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    /** Agent→client requests run here so a slow handler can't stall the read loop. */
    private final ExecutorService requestExec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "acp-agent-request");
        t.setDaemon(true);
        return t;
    });

    private volatile JsonNode sessionConfig;
    /** The folder {@code fs/*} requests are confined to: the latest session's cwd, else the process cwd. */
    private volatile Path fsRoot;

    private volatile Process process;
    /**
     * Set by {@link #dispose()}. The process the editor stopped still ends its output stream, some time
     * later and on another thread; reported as an exit then, it reached a host that had already moved on —
     * a new chat showed "(agent exited)", lost its "working" state mid-turn, and could have the session it
     * had just started taken for the one that ended.
     */
    private volatile boolean disposed;
    /**
     * The ordered writer to the agent's stdin. Requests are issued from the FX thread (a prompt, Stop, a
     * model switch), and a direct pipe write blocks for as long as the agent is not reading — an agent busy
     * with a turn, with a prompt larger than the pipe buffer on its way, froze the editor and Stop with it.
     */
    private volatile com.editora.lsp.AsyncPipeWriter writer;

    public AcpClient(List<String> command, Path cwd, Host host) {
        this(command, cwd, host, Map.of());
    }

    public AcpClient(List<String> command, Path cwd, Host host, Map<String, String> environment) {
        this.command = command;
        this.cwd = cwd;
        this.fsRoot = cwd;
        this.host = host;
        this.environment = Map.copyOf(environment);
    }

    /**
     * The agent process, before it is started: the user's own environment (locale included) plus the
     * augmented PATH, then {@code environment}. The agent is an interactive CLI that reads and writes the
     * user's files and prose — the parse-stable {@code LC_ALL=C} would make it treat their paths as ASCII.
     */
    static ProcessBuilder processBuilder(List<String> command, Path cwd, Map<String, String> environment) {
        ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(command));
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        ProcessRunner.applyUserEnv(pb.environment(), environment);
        return pb;
    }

    /** Spawns the agent + reader/stderr threads. Returns false when the command can't launch. */
    public synchronized boolean start() {
        if (process != null && process.isAlive()) {
            return true;
        }
        try {
            Process started = processBuilder(command, cwd, environment).start();
            process = started;
            ProcessRegistry.track(process); // reaped on JVM exit / next-run startup if we die without dispose()
            // An agent that has stopped reading altogether is killed once the backlog limit is reached; the
            // reader thread then sees the stream end and reports the exit like any other.
            writer = new com.editora.lsp.AsyncPipeWriter(
                    started.getOutputStream(), "acp-agent-writer", () -> ProcessRegistry.killTree(started));
            drainStderr(process);
            startReader(process);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to start ACP agent " + command, e);
            process = null;
            return false;
        }
    }

    public boolean isAlive() {
        Process p = process;
        return p != null && p.isAlive();
    }

    // --- outgoing -----------------------------------------------------------------------------------

    /** {@code initialize} — must be the first request on a fresh process. */
    public CompletableFuture<JsonNode> initialize() {
        return request("initialize", AcpJson.initializeParams(mapper));
    }

    /** {@code session/new} → the new session's id plus its model/mode catalogs. */
    public CompletableFuture<AcpJson.SessionInfo> newSession(Path sessionCwd) {
        fsRoot = sessionCwd;
        return request("session/new", AcpJson.newSessionParams(mapper, sessionCwd.toString()))
                .thenApply(this::rememberSessionConfig);
    }

    /** {@code session/resume} → the reopened session's model/mode catalogs. The response omits the
     *  session id (the caller already knows it), so {@code sessionId} is spliced back into the parsed
     *  {@link AcpJson.SessionInfo}. */
    public CompletableFuture<AcpJson.SessionInfo> resumeSession(String sessionId, Path sessionCwd) {
        fsRoot = sessionCwd;
        return request("session/resume", AcpJson.resumeSessionParams(mapper, sessionId, sessionCwd.toString()))
                .thenApply(result -> {
                    AcpJson.SessionInfo parsed = rememberSessionConfig(result);
                    return new AcpJson.SessionInfo(
                            sessionId,
                            parsed.models(),
                            parsed.currentModelId(),
                            parsed.modes(),
                            parsed.currentModeId());
                });
    }

    /** {@code session/prompt} → the turn's stop reason (e.g. {@code end_turn}/{@code cancelled}). */
    public CompletableFuture<String> prompt(String sessionId, String text) {
        return request("session/prompt", AcpJson.promptParams(mapper, sessionId, text))
                .thenApply(result -> result != null && result.hasNonNull("stopReason")
                        ? result.get("stopReason").asText()
                        : "end_turn");
    }

    /** {@code session/cancel} (fire-and-forget; the in-flight prompt resolves with {@code cancelled}). */
    public void cancel(String sessionId) {
        send(AcpJson.notification(mapper, "session/cancel", AcpJson.cancelParams(mapper, sessionId)));
    }

    /** {@code session/set_model} — switches the running session's active model. Result body is ignored. */
    public CompletableFuture<Void> setModel(String sessionId, String modelId) {
        JsonNode option = AcpJson.configOption(sessionConfig, "model");
        if (option != null) {
            return setConfigOption(sessionId, option.path("id").asText(), modelId);
        }
        return request("session/set_model", AcpJson.setModelParams(mapper, sessionId, modelId))
                .thenApply(result -> null);
    }

    /** {@code session/set_mode} — switches the running session's active mode. Result body is ignored. */
    public CompletableFuture<Void> setMode(String sessionId, String modeId) {
        JsonNode option = AcpJson.configOption(sessionConfig, "mode");
        if (option != null) {
            return setConfigOption(sessionId, option.path("id").asText(), modeId);
        }
        return request("session/set_mode", AcpJson.setModeParams(mapper, sessionId, modeId))
                .thenApply(result -> null);
    }

    private AcpJson.SessionInfo rememberSessionConfig(JsonNode result) {
        sessionConfig = result;
        return AcpJson.parseSessionInfo(result);
    }

    private CompletableFuture<Void> setConfigOption(String sessionId, String configId, String value) {
        return request("session/set_config_option", AcpJson.setConfigOptionParams(mapper, sessionId, configId, value))
                .thenAccept(result -> host.onSessionConfig(sessionId, rememberSessionConfig(result)));
    }

    private CompletableFuture<JsonNode> request(String method, JsonNode params) {
        long id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        if (!send(AcpJson.request(mapper, id, method, params))) {
            pending.remove(id);
            f.completeExceptionally(new IOException("Agent process is not running"));
        }
        return f;
    }

    private boolean send(ObjectNode message) {
        Process p = process;
        OutputStream out = writer;
        if (p == null || out == null || !p.isAlive()) {
            return false;
        }
        try {
            byte[] line = (mapper.writeValueAsString(message) + "\n").getBytes(StandardCharsets.UTF_8);
            // One write is one queue entry, so a message is never interleaved with another and the wire
            // order is the order of these calls; the pipe write itself happens on the writer's thread.
            out.write(line);
            return true;
        } catch (IOException e) {
            LOG.log(Level.FINE, "ACP write failed", e);
            return false;
        }
    }

    // --- incoming -----------------------------------------------------------------------------------

    private void startReader(Process p) {
        Thread t = new Thread(
                () -> {
                    try (BufferedReader r =
                            new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (!line.isBlank()) {
                                handleLine(line);
                            }
                        }
                    } catch (IOException ignored) {
                        // stream closed — the process exited (or we disposed it).
                    }
                    failPending(new IOException("Agent process exited"));
                    if (!disposed) {
                        host.onExit(p.isAlive() ? -1 : p.exitValue());
                    }
                },
                "acp-agent-reader");
        t.setDaemon(true);
        t.start();
    }

    private void handleLine(String line) {
        JsonNode msg;
        try {
            msg = mapper.readTree(line);
        } catch (IOException e) {
            LOG.fine("ACP: unparseable line from agent: " + line);
            return;
        }
        if (msg == null || !msg.isObject()) {
            return;
        }
        if (msg.hasNonNull("method")) {
            if (msg.has("id")) {
                JsonNode id = msg.get("id");
                String method = msg.get("method").asText();
                JsonNode params = msg.get("params");
                requestExec.submit(() -> handleAgentRequest(id, method, params));
            } else {
                handleNotification(msg.get("method").asText(), msg.get("params"));
            }
            return;
        }
        // A response to one of our requests.
        JsonNode id = msg.get("id");
        if (id == null || !id.canConvertToLong()) {
            return;
        }
        CompletableFuture<JsonNode> f = pending.remove(id.asLong());
        if (f == null) {
            return;
        }
        if (msg.hasNonNull("error")) {
            String message = msg.get("error").hasNonNull("message")
                    ? msg.get("error").get("message").asText()
                    : "agent error";
            f.completeExceptionally(new IOException(message));
        } else {
            f.complete(msg.get("result"));
        }
    }

    private void handleNotification(String method, JsonNode params) {
        if ("session/update".equals(method)) {
            try {
                if (params != null
                        && "config_option_update"
                                .equals(params.path("update")
                                        .path("sessionUpdate")
                                        .asText())) {
                    host.onSessionConfig(
                            params.path("sessionId").asText(), rememberSessionConfig(params.path("update")));
                } else {
                    host.onUpdate(AcpJson.parseUpdate(params));
                }
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "ACP update handler failed", e);
            }
        }
        // other notifications are ignored (forward-compatible)
    }

    private void handleAgentRequest(JsonNode id, String method, JsonNode params) {
        try {
            switch (method) {
                case "fs/read_text_file" -> {
                    // Confined to the session folder; throws → error reply. The host gets the path the guard
                    // resolved, never the agent's string: a relative one would be resolved a second time,
                    // against the editor's own working directory.
                    String path =
                            AcpFsGuard.checkRead(fsRoot, textOf(params, "path")).toString();
                    Integer line = intOf(params, "line");
                    Integer limit = intOf(params, "limit");
                    String content = host.readTextFile(path, line, limit);
                    ObjectNode result = mapper.createObjectNode();
                    result.put("content", content == null ? "" : content);
                    send(AcpJson.response(mapper, id, result));
                }
                case "fs/write_text_file" -> {
                    String path = AcpFsGuard.checkWrite(fsRoot, host.writeProtectedDirectory(), textOf(params, "path"))
                            .toString();
                    host.writeTextFile(path, textOf(params, "content"));
                    send(AcpJson.response(mapper, id, null));
                }
                case "session/request_permission" -> {
                    List<AcpJson.PermissionOption> options = AcpJson.parsePermissionOptions(params);
                    host.requestPermission(AcpJson.permissionTitle(params), options)
                            .whenComplete((optionId, error) -> send(AcpJson.response(
                                    mapper, id, AcpJson.permissionOutcome(mapper, error == null ? optionId : null))));
                }
                default ->
                    send(AcpJson.errorResponse(
                            mapper, id, AcpJson.METHOD_NOT_FOUND, "Method not supported: " + method));
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "ACP client-side handler failed: " + method, e);
            send(AcpJson.errorResponse(mapper, id, AcpJson.INTERNAL_ERROR, String.valueOf(e.getMessage())));
        }
    }

    private void failPending(Exception cause) {
        for (Long id : pending.keySet()) {
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null) {
                f.completeExceptionally(cause);
            }
        }
    }

    /** Kills the agent's whole process tree (an npx wrapper must not orphan the real agent). */
    public synchronized void dispose() {
        disposed = true;
        Process p = process;
        process = null;
        if (p != null) {
            ProcessRegistry.killTree(p);
        }
        com.editora.lsp.AsyncPipeWriter out = writer;
        writer = null;
        if (out != null) {
            out.close();
        }
        failPending(new IOException("Agent disposed"));
        requestExec.shutdownNow();
    }

    private void drainStderr(Process p) {
        Thread t = new Thread(
                () -> {
                    int logged = 0;
                    try (BufferedReader r =
                            new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (logged < STDERR_LOG_CAP) {
                                LOG.info("[acp-agent stderr] " + line);
                                if (++logged == STDERR_LOG_CAP) {
                                    LOG.info("[acp-agent stderr] …(further output suppressed)");
                                }
                            }
                            // keep draining past the cap so the pipe never fills and blocks the agent.
                        }
                    } catch (IOException ignored) {
                        // stream closed (agent exited) — nothing more to drain.
                    }
                },
                "acp-agent-stderr");
        t.setDaemon(true);
        t.start();
    }

    private static String textOf(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Integer intOf(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asInt() : null;
    }
}
