package com.editora.mcp;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * A tiny embedded <a href="https://modelcontextprotocol.io">Model Context Protocol</a> server,
 * bound to <strong>loopback only</strong> (never the LAN), modeled on {@code web.LivePreviewServer}.
 * It speaks JSON-RPC 2.0 over the MCP "Streamable HTTP" transport's request/response subset (one POST
 * → one {@code application/json} response; no SSE). Every request must carry
 * {@code Authorization: Bearer <token>} — the token is the gate against other local processes driving
 * the editor. On start it writes {@code <configDir>/mcp-endpoint.json} (url + token) for discovery.
 *
 * <p>All editor reads go through {@link McpBridge} (implemented by the controller), which marshals
 * onto the JavaFX thread; this class touches no JavaFX and runs entirely on its HTTP worker threads.
 */
public final class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());
    private static final String ENDPOINT_FILE = "mcp-endpoint.json";

    private final McpBridge bridge;
    private final Path configDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpTools tools;
    private final String token;

    private HttpServer server;
    private ExecutorService pool;

    public McpServer(McpBridge bridge, Path configDir) {
        this.bridge = bridge;
        this.configDir = configDir;
        this.tools = new McpTools(bridge, mapper);
        this.token = randomToken();
    }

    /** Starts the server on an ephemeral loopback port (idempotent); returns the bound port. */
    public synchronized int start() throws IOException {
        if (server != null) {
            return server.getAddress().getPort();
        }
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mcp-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/mcp", this::handle);
        server.start();
        writeEndpointFile();
        LOG.info("MCP server listening on " + url());
        return server.getAddress().getPort();
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public String token() {
        return token;
    }

    /** The loopback endpoint URL, or {@code ""} when not running. */
    public synchronized String url() {
        return server == null ? "" : "http://127.0.0.1:" + port() + "/mcp";
    }

    /** Stops the server + its thread pool and removes the endpoint file (idempotent). */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        try {
            Files.deleteIfExists(configDir.resolve(ENDPOINT_FILE));
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    // --- request handling -------------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendStatus(ex, 405); // GET (SSE) and others are unsupported in this minimal server
                return;
            }
            if (!authorized(ex)) {
                sendStatus(ex, 401);
                return;
            }
            byte[] in = readCapped(ex.getRequestBody());
            JsonNode req;
            try {
                req = mapper.readTree(in);
            } catch (IOException parse) {
                writeJson(ex, 200, JsonRpc.error(mapper, mapper.nullNode(), JsonRpc.PARSE_ERROR, "Parse error"));
                return;
            }
            if (req != null && req.isArray()) {
                // JSON-RPC batching is legal but unsupported here. Without this the array has no "id", so it
                // was taken for a notification and answered 202 with no body — leaving the client waiting
                // forever on a response that will never come. Say so instead.
                writeJson(
                        ex,
                        200,
                        JsonRpc.error(
                                mapper,
                                mapper.nullNode(),
                                JsonRpc.INVALID_REQUEST,
                                "Batch requests are not supported"));
                return;
            }
            JsonNode id = req == null ? null : req.get("id");
            String method =
                    req != null && req.hasNonNull("method") ? req.get("method").asText() : "";
            if (id == null) {
                // A notification (e.g. notifications/initialized): acknowledge, no body.
                sendStatus(ex, 202);
                return;
            }
            ObjectNode response = dispatch(id, method, req.get("params"));
            writeJson(ex, 200, response);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "MCP request failed", e);
            sendStatus(ex, 500);
        } finally {
            ex.close();
        }
    }

    private ObjectNode dispatch(JsonNode id, String method, JsonNode params) {
        try {
            return switch (method) {
                case "initialize" -> JsonRpc.success(mapper, id, tools.initializeResult());
                case "tools/list" -> JsonRpc.success(mapper, id, tools.listToolsResult());
                case "tools/call" -> JsonRpc.success(mapper, id, tools.callTool(params));
                case "ping" -> JsonRpc.success(mapper, id, mapper.createObjectNode());
                default -> JsonRpc.error(mapper, id, JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method);
            };
        } catch (RuntimeException e) {
            return JsonRpc.error(mapper, id, JsonRpc.INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    private boolean authorized(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return false;
        }
        // Constant-time: String.equals short-circuits on the first differing char, which leaks a prefix
        // oracle to anything that can time the loopback response.
        byte[] got = header.getBytes(StandardCharsets.UTF_8);
        byte[] want = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(got, want);
    }

    /**
     * Writes the endpoint + bearer token for a local MCP client to read.
     *
     * <p>The token is the <b>only</b> thing standing between a local process and full control of the editor
     * (the tool surface can run any registered command and read/write any file), so the file must be
     * owner-only. Jackson's default write left it at the umask — 0644 in practice — inside a 0755 config dir,
     * i.e. readable by every other local account (on macOS every standard user's primary group is `staff`,
     * so `~` being 0750 doesn't stop it; on Linux `/home/user` is commonly 0755). The rest of the auth design
     * is sound; this file undid it. Elsewhere the codebase already writes secrets 0600 (InstallService, the
     * elevated-save temp).
     */
    /** Cap on a request body — the app runs with a bounded heap; an unbounded readAllBytes need not oblige. */
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private static byte[] readCapped(java.io.InputStream in) throws IOException {
        return in.readNBytes(MAX_BODY_BYTES);
    }

    private void writeEndpointFile() {
        ObjectNode node = mapper.createObjectNode();
        node.put("url", url());
        node.put("token", token);
        // Stamp this process so a stale file left by a crash can be reaped at the next launch (#464): the
        // recorded pid + start instant let reapStaleEndpoint tell a live server from a dead one, safely
        // against a reused PID (mirrors ProcessRegistry.reapOrphans).
        node.put("pid", ProcessHandle.current().pid());
        currentProcessStartMillis().ifPresent(ms -> node.put("startMillis", ms));
        try {
            Path file = configDir.resolve(ENDPOINT_FILE);
            Files.createDirectories(configDir);
            restrictToOwner(configDir, "rwx------");
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
            restrictToOwner(file, "rw-------");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write " + ENDPOINT_FILE, e);
        }
    }

    /** This JVM process's start instant as epoch millis, if the OS exposes it. */
    private static java.util.Optional<Long> currentProcessStartMillis() {
        return ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli);
    }

    /**
     * Reaps a {@code mcp-endpoint.json} left behind by a crash/SIGKILL — the file is deleted only in
     * {@link #stop()}, so a hard exit leaves it advertising a dead port with a live-looking token (a client
     * then fails, or worse hands the token to whatever later binds that port). Run once at launch, before any
     * server starts. The stamped pid + start instant let {@link #isStaleEndpoint} distinguish a genuinely
     * live server (a concurrent instance) from a stale file, safely against a reused PID (#464).
     */
    public static void reapStaleEndpoint(Path configDir) {
        Path file = configDir.resolve(ENDPOINT_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        Long pid = null;
        Long startMillis = null;
        try {
            JsonNode node = new ObjectMapper().readTree(file.toFile());
            if (node != null && node.hasNonNull("pid")) {
                pid = node.get("pid").asLong();
            }
            if (node != null && node.hasNonNull("startMillis")) {
                startMillis = node.get("startMillis").asLong();
            }
        } catch (IOException e) {
            // Unreadable/corrupt endpoint file — it can't identify a live server, so treat it as stale.
        }
        if (isStaleEndpoint(pid, startMillis, McpServer::processStartMillis)) {
            try {
                Files.deleteIfExists(file);
                LOG.info("Reaped stale " + ENDPOINT_FILE);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not delete stale " + ENDPOINT_FILE, e);
            }
        }
    }

    /** The start instant (epoch millis) of the live process {@code pid}, or empty when it isn't running. */
    private static java.util.Optional<Long> processStartMillis(long pid) {
        return ProcessHandle.of(pid).flatMap(h -> h.info().startInstant()).map(java.time.Instant::toEpochMilli);
    }

    /**
     * Whether an endpoint file's recorded {@code pid}/{@code startMillis} identify a dead (or non-matching)
     * server, so the file should be reaped. {@code liveStart} returns the live start-millis of a pid (empty if
     * not running). Pure; unit-tested. Reap when: no pid (an old/unstamped file at launch is from a previous
     * run), the pid isn't a live process, or it is live but its start time differs (the PID was reused). When
     * the pid is live but we have no recorded start time to compare, keep it (conservative — never delete a
     * possibly-live endpoint).
     */
    static boolean isStaleEndpoint(
            Long pid, Long startMillis, java.util.function.LongFunction<java.util.Optional<Long>> liveStart) {
        if (pid == null) {
            return true;
        }
        java.util.Optional<Long> live = liveStart.apply(pid);
        if (live.isEmpty()) {
            return true;
        }
        if (startMillis == null) {
            return false;
        }
        return !live.get().equals(startMillis);
    }

    /** Best-effort owner-only permissions; a no-op where POSIX views aren't supported (Windows). */
    private static void restrictToOwner(Path path, String perms) {
        try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(perms));
            }
        } catch (IOException | UnsupportedOperationException e) {
            LOG.log(Level.WARNING, "Could not restrict permissions on " + path, e);
        }
    }

    private void writeJson(HttpExchange ex, int status, ObjectNode body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sendStatus(HttpExchange ex, int status) {
        try {
            ex.sendResponseHeaders(status, -1);
        } catch (IOException ignored) {
            // client already gone
        }
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
