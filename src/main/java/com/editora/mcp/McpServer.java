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
import org.jspecify.annotations.Nullable;

/**
 * A tiny embedded <a href="https://modelcontextprotocol.io">Model Context Protocol</a> server,
 * bound to <strong>loopback only</strong> (never the LAN), modeled on {@code web.LivePreviewServer}.
 * It speaks JSON-RPC 2.0 over MCP's Streamable HTTP transport, in both eras ({@link McpProtocol}): a legacy client
 * ({@code initialize}, 2024-11-05 to 2025-11-25) gets one JSON answer per POST and may hold a GET event stream for
 * notifications; a modern one (2026-07-28: every request names its revision in {@code _meta}) also gets
 * {@code server/discover} and holds a {@code subscriptions/listen} response stream. Every request must carry
 * {@code Authorization: Bearer <token>} — the token is the gate against other local processes driving
 * the editor — and a request from a web page (an {@code Origin} that is not this computer) is refused. On start it
 * writes {@code <configDir>/mcp-endpoint.json} (url + token) for discovery.
 *
 * <p>All editor reads go through {@link McpBridge} (implemented by the controller), which marshals
 * onto the JavaFX thread; this class touches no JavaFX and runs entirely on its HTTP worker threads.
 */
public final class McpServer {

    private static final Logger LOG = Logger.getLogger(McpServer.class.getName());
    private static final String ENDPOINT_FILE = "mcp-endpoint.json";

    private final Path configDir;
    private final ObjectMapper mapper = new ObjectMapper();
    /** Volatile + reassignable so ownership can move to a surviving window on close ({@link #rebind}) without
     *  restarting the server (keeping the same port/token/endpoint file, so a connected agent stays connected). */
    private volatile McpTools tools;

    /** The event streams agents hold open, and the watch behind them. */
    private final McpNotifier notifier;

    /** Replaced by {@link #newToken()}; read on every request's worker thread. */
    private volatile String token;

    private volatile @Nullable HttpServer server;
    private @Nullable ExecutorService pool;

    public McpServer(McpBridge bridge, Path configDir) {
        this.configDir = configDir;
        this.tools = new McpTools(bridge, mapper);
        this.token = randomToken();
        this.notifier = new McpNotifier(mapper, () -> this.tools);
    }

    /**
     * Re-points the server at a new bridge (a different window's {@code MainController}). Used when the window
     * that started MCP is closed while others remain: ownership moves to a survivor instead of stopping the
     * server, so a connected agent isn't dropped (#463). Keeps the same port/token/endpoint file.
     */
    public void rebind(McpBridge bridge) {
        this.tools = new McpTools(bridge, mapper);
        notifier.resourcesChanged(); // another window: other open files
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

    public boolean isRunning() {
        return server != null;
    }

    public int port() {
        HttpServer s = server;
        return s == null ? -1 : s.getAddress().getPort();
    }

    public String token() {
        return token;
    }

    /**
     * Replaces the bearer token (Settings → MCP "New Token"), keeping the port: an agent set up with the old
     * token is refused from now on and must be given the new endpoint command. Rewrites the endpoint file.
     */
    public synchronized void newToken() {
        token = randomToken();
        if (server != null) {
            writeEndpointFile();
        }
    }

    /** The loopback endpoint URL, or {@code ""} when not running. */
    public String url() {
        int port = port();
        return port < 0 ? "" : "http://127.0.0.1:" + port + "/mcp";
    }

    /** Stops the server + its thread pool and removes the endpoint file (idempotent). */
    public synchronized void stop() {
        notifier.closeAll(); // each stream says goodbye (a modern one with its final response) before the socket goes
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1);
        while (notifier.streamCount() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait(); // the pumps are writing their last event; bounded, and only with streams open
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        Path file = configDir.resolve(ENDPOINT_FILE);
        endpointFile(() -> {
            com.editora.perf.BlockingSites.enter("McpServer.stop");
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // best-effort cleanup; a file left behind is reaped at the next launch (its pid is gone)
            }
        });
    }

    /**
     * The endpoint file's writes and its delete, in the order asked, on a thread of their own (PERF-01, Pauta 6.2.0:
     * Start, New Token and Stop are asked on the FX thread). One daemon thread, made at the first use.
     */
    private static final java.util.concurrent.ExecutorService ENDPOINT_IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcp-endpoint");
        t.setDaemon(true);
        return t;
    });

    /**
     * Runs {@code work} on {@code mcp-endpoint}: queued when asked on the FX thread, waited for on any other (so a
     * caller there finds the file as it left it, as before).
     */
    private static void endpointFile(Runnable work) {
        java.util.concurrent.Future<?> done;
        try {
            done = ENDPOINT_IO.submit(work);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            work.run();
            return;
        }
        if (javafx.application.Platform.isFxApplicationThread()) {
            return;
        }
        try {
            done.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException e) {
            LOG.log(Level.WARNING, "Could not update " + ENDPOINT_FILE, e.getCause());
        }
    }

    /** TEST SEAM — waits until the endpoint file's queued writes and deletes are done. */
    static void awaitEndpointFileForTest() throws Exception {
        ENDPOINT_IO.submit(() -> {}).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    // --- request handling -------------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        boolean streaming = false;
        try {
            if (!originAllowed(ex.getRequestHeaders().getFirst("Origin"))) {
                sendStatus(ex, 403); // a web page in a browser on this computer: DNS rebinding, not an agent
                return;
            }
            if (!authorized(ex)) {
                sendStatus(ex, 401);
                return;
            }
            if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                streaming = legacyStream(ex);
                return;
            }
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendStatus(ex, 405);
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
            JsonNode params = req == null ? null : req.get("params");
            String modern = McpProtocol.modernVersion(params);
            ObjectNode refused = refusal(ex, id, method, params, modern);
            if (refused != null) {
                writeJson(ex, 400, refused);
                return;
            }
            if (id == null) {
                // A notification (notifications/initialized, notifications/cancelled, …): acknowledged, no body.
                sendStatus(ex, 202);
                return;
            }
            if (modern != null && method.equals("subscriptions/listen")) {
                streaming = listen(ex, id, params);
                return;
            }
            ObjectNode response = dispatch(id, method, params, modern != null);
            boolean unknown = response.path("error").path("code").asInt() == JsonRpc.METHOD_NOT_FOUND;
            writeJson(ex, modern != null && unknown ? 404 : 200, response);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "MCP request failed", e);
            if (!streaming) {
                sendStatus(ex, 500);
            }
        } finally {
            ex.close();
        }
    }

    /**
     * Why a request is answered 400 before it runs, or null: a modern request naming a revision this server does not
     * speak, headers that disagree with the body, or a legacy request whose {@code MCP-Protocol-Version} header names
     * a revision that is not ours.
     */
    private @Nullable ObjectNode refusal(
            HttpExchange ex, @Nullable JsonNode id, String method, @Nullable JsonNode params, @Nullable String modern) {
        JsonNode rid = id == null ? mapper.nullNode() : id;
        String versionHeader = ex.getRequestHeaders().getFirst("MCP-Protocol-Version");
        if (modern != null) {
            if (!modern.equals(McpProtocol.MODERN)) {
                return McpProtocol.unsupportedVersion(mapper, rid, modern);
            }
            String mismatch = McpProtocol.headerMismatch(
                    versionHeader,
                    ex.getRequestHeaders().getFirst("Mcp-Method"),
                    ex.getRequestHeaders().getFirst("Mcp-Name"),
                    modern,
                    method,
                    McpProtocol.nameOf(method, params));
            return mismatch == null ? null : JsonRpc.error(mapper, rid, McpProtocol.HEADER_MISMATCH, mismatch);
        }
        if (versionHeader == null || McpProtocol.LEGACY.contains(versionHeader)) {
            return null;
        }
        if (versionHeader.equals(McpProtocol.MODERN)) {
            return JsonRpc.error(
                    mapper,
                    rid,
                    McpProtocol.HEADER_MISMATCH,
                    "Header mismatch: MCP-Protocol-Version is " + McpProtocol.MODERN
                            + " but the request names no revision in _meta");
        }
        return McpProtocol.unsupportedVersion(mapper, rid, versionHeader);
    }

    private ObjectNode dispatch(JsonNode id, String method, @Nullable JsonNode params, boolean modern) {
        McpTools t = tools;
        try {
            ObjectNode result = switch (method) {
                case "initialize" -> t.initializeResult(text(params, "protocolVersion"));
                case "server/discover" -> t.discoverResult();
                case "tools/list" -> t.listToolsResult();
                case "tools/call" -> t.callTool(params);
                case "resources/list" -> t.listResourcesResult();
                case "resources/read" -> t.readResourceResult(text(params, "uri"));
                case "resources/templates/list" -> {
                    ObjectNode r = mapper.createObjectNode();
                    r.putArray("resourceTemplates");
                    yield r;
                }
                case "resources/subscribe", "resources/unsubscribe" -> {
                    String uri = text(params, "uri");
                    if (uri == null) {
                        yield null;
                    }
                    if (method.endsWith("/subscribe")) {
                        notifier.subscribeLegacy(uri);
                    } else {
                        notifier.unsubscribeLegacy(uri);
                    }
                    yield mapper.createObjectNode();
                }
                case "prompts/list" -> t.promptsListResult();
                case "prompts/get" -> t.getPromptResult(params);
                case "ping" -> mapper.createObjectNode();
                default -> null;
            };
            if (result == null) {
                return method.startsWith("resources/")
                        ? JsonRpc.error(mapper, id, JsonRpc.INVALID_PARAMS, "Missing resource uri")
                        : JsonRpc.error(mapper, id, JsonRpc.METHOD_NOT_FOUND, "Method not found: " + method);
            }
            return JsonRpc.success(mapper, id, modern ? McpProtocol.modernResult(mapper, method, result) : result);
        } catch (McpTools.PromptUnavailable e) {
            return JsonRpc.error(mapper, id, JsonRpc.INVALID_PARAMS, String.valueOf(e.getMessage()));
        } catch (McpTools.ResourceNotFound e) {
            int code = modern ? JsonRpc.INVALID_PARAMS : McpProtocol.LEGACY_RESOURCE_NOT_FOUND;
            return JsonRpc.error(mapper, id, code, String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            return JsonRpc.error(mapper, id, JsonRpc.INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    private static @Nullable String text(@Nullable JsonNode params, String field) {
        return params != null && params.hasNonNull(field) ? params.get(field).asText() : null;
    }

    /**
     * A legacy client's GET event stream: list changes, and updates of what it subscribed to with
     * {@code resources/subscribe}. 405 for a GET that does not ask for an event stream. Blocks until the client goes
     * or the server stops; true once the stream's headers are sent.
     */
    private boolean legacyStream(HttpExchange ex) throws IOException {
        String accept = ex.getRequestHeaders().getFirst("Accept");
        if (accept == null || !accept.contains("text/event-stream")) {
            sendStatus(ex, 405);
            return false;
        }
        McpNotifier.Stream stream = notifier.open(null, true, null);
        startEventStream(ex);
        stream.pump(ex.getResponseBody(), null, () -> null);
        return true;
    }

    /**
     * A modern {@code subscriptions/listen}: the response is an event stream that starts with the acknowledgement
     * (the filters this server honours), carries the notifications tagged with the request's id, and ends with the
     * final response when the server stops. A client closing the stream ends the subscription.
     */
    private boolean listen(HttpExchange ex, JsonNode id, @Nullable JsonNode params) throws IOException {
        McpProtocol.Filters filters = McpProtocol.Filters.of(params);
        java.util.Set<String> uris = java.util.concurrent.ConcurrentHashMap.newKeySet();
        uris.addAll(filters.resourceSubscriptions());
        ObjectNode agreed = filters.agreed(mapper);
        McpNotifier.Stream stream = notifier.open(id, filters.resourcesListChanged(), uris);
        ObjectNode ackParams = mapper.createObjectNode();
        ackParams.set("notifications", agreed);
        ObjectNode ack = notifier.notification(stream, "notifications/subscriptions/acknowledged", ackParams);
        startEventStream(ex);
        stream.pump(ex.getResponseBody(), ack, () -> {
            ObjectNode result = mapper.createObjectNode();
            result.putObject("_meta").set(McpProtocol.META_SUBSCRIPTION, id);
            return JsonRpc.success(mapper, id, McpProtocol.modernResult(mapper, "subscriptions/listen", result));
        });
        return true;
    }

    private static void startEventStream(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0); // chunked: open until one side closes it
        // The JDK server holds the headers back until the body is written: without this a client would wait for
        // the first notification (or the keep-alive) just to learn that the stream is open.
        OutputStream body = ex.getResponseBody();
        body.write(": open\n\n".getBytes(StandardCharsets.UTF_8));
        body.flush();
    }

    /** TEST SEAM — how many event streams are open. */
    int streamCountForTest() {
        return notifier.streamCount();
    }

    /** TEST SEAM — runs the resource watch once, now. */
    void watchForTest() {
        notifier.tick();
    }

    /**
     * Whether a request may come from {@code origin}: none (an agent, not a browser), or a page on this computer's
     * loopback address. Anything else is a web page trying to reach the editor through the user's browser.
     */
    static boolean originAllowed(@Nullable String origin) {
        if (origin == null || origin.isEmpty()) {
            return true;
        }
        try {
            java.net.URI u = java.net.URI.create(origin);
            String host = u.getHost();
            return ("http".equals(u.getScheme()) || "https".equals(u.getScheme()))
                    && host != null
                    && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"));
        } catch (IllegalArgumentException e) {
            return false;
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

    /** Cap on a request body — the app runs with a bounded heap; an unbounded readAllBytes need not oblige. */
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private static byte[] readCapped(java.io.InputStream in) throws IOException {
        return in.readNBytes(MAX_BODY_BYTES);
    }

    /**
     * Writes the endpoint + bearer token for a local MCP client to read.
     *
     * <p>The token is the <b>only</b> thing standing between a local process and full control of the editor
     * (the tool surface can run any registered command and read and write the open and project files), so the
     * file must be
     * owner-only. Jackson's default write left it at the umask — 0644 in practice — inside a 0755 config dir,
     * i.e. readable by every other local account (on macOS every standard user's primary group is `staff`,
     * so `~` being 0750 doesn't stop it; on Linux `/home/user` is commonly 0755). The rest of the auth design
     * is sound; this file undid it. Elsewhere the codebase already writes secrets 0600 (InstallService, the
     * elevated-save temp).
     */
    private void writeEndpointFile() {
        ObjectNode node = mapper.createObjectNode();
        node.put("url", url());
        node.put("token", token);
        // Stamp this process so a stale file left by a crash can be reaped at the next launch (#464): the
        // recorded pid + start instant let reapStaleEndpoint tell a live server from a dead one, safely
        // against a reused PID (mirrors ProcessRegistry.reapOrphans).
        node.put("pid", ProcessHandle.current().pid());
        Path dir = configDir;
        endpointFile(() -> {
            com.editora.perf.BlockingSites.enter("McpServer.writeEndpointFile");
            currentProcessStartMillis().ifPresent(ms -> node.put("startMillis", ms));
            try {
                Path file = dir.resolve(ENDPOINT_FILE);
                Files.createDirectories(dir);
                restrictToOwner(dir, "rwx------");
                mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), node);
                restrictToOwner(file, "rw-------");
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Could not write " + ENDPOINT_FILE, e);
            }
        });
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
        com.editora.perf.BlockingSites.enter("McpServer.reapStaleEndpoint");
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
            @Nullable Long pid,
            @Nullable Long startMillis,
            java.util.function.LongFunction<java.util.Optional<Long>> liveStart) {

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
