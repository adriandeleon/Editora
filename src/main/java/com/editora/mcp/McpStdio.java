package com.editora.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * {@code editora mcp}: Editora's MCP server over standard input and output, for an agent that starts its MCP
 * servers as programs ({@code claude mcp add editora -- editora mcp}). It relays each JSON-RPC message, one per
 * line, to the running editor's loopback endpoint, reading the URL and token from {@code mcp-endpoint.json} in the
 * config folder for every message. The editor's port and token change whenever its server starts or gets a new
 * token; this command doesn't, so an agent set up once keeps working across restarts.
 *
 * <p>Both protocol eras pass through ({@link McpProtocol}); the relay adds the HTTP headers each one asks for and
 * writes an event-stream answer back as one line per message. Notifications outlive the editor: a modern
 * {@code subscriptions/listen} stays open here while Editora restarts (when it is back, the client hears that
 * things may have changed), and after a legacy {@code initialize} the relay holds the editor's GET stream, opening
 * it again and repeating the client's {@code resources/subscribe} calls when the editor comes back.
 *
 * <p>When no editor answers (not running, MCP off, or the token refused), {@code initialize},
 * {@code server/discover} and the lists are answered here from {@link McpToolCatalog}, so the agent still starts
 * and sees the tools, and each {@code tools/call} returns a tool error that says what the user has to do. No
 * window, no JavaFX, no single-instance hand-off; nothing but JSON-RPC is written to standard output.
 */
public final class McpStdio {

    /** The subcommand's name: {@code editora mcp …}. */
    public static final String COMMAND = "mcp";

    static final int OK = 0;
    static final int USAGE = 2;

    static final String ENDPOINT_FILE = "mcp-endpoint.json";

    /** A tool call may wait on the editor (a save, a search across the project); the editor bounds its own work. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** How long the relay waits before looking for the editor again, to reopen a notification stream. */
    static final long RECONNECT_MILLIS = 2_000;

    /** Where the editor listens, as {@code mcp-endpoint.json} says. */
    record Endpoint(URI url, String token) {}

    /** One HTTP answer: its status, its content type and its body, read as it arrives (an event stream may last). */
    record Reply(int status, String contentType, InputStream body) {

        /** A whole JSON answer (or none, for an empty {@code body}). */
        static Reply json(int status, byte[] body) {
            return new Reply(status, "application/json", new java.io.ByteArrayInputStream(body));
        }

        boolean eventStream() {
            return contentType.startsWith("text/event-stream");
        }
    }

    /** Talks to the editor. A seam for tests; {@link #httpTransport} in use. */
    interface Transport {
        /** One JSON-RPC message, with the protocol headers it needs. */
        Reply post(Endpoint endpoint, byte[] body, java.util.Map<String, String> headers)
                throws IOException, InterruptedException;

        /** A legacy client's GET event stream. */
        default Reply get(Endpoint endpoint, java.util.Map<String, String> headers)
                throws IOException, InterruptedException {
            throw new IOException("no event stream");
        }
    }

    /** Why the editor could not answer. */
    enum Unavailable {
        /** No endpoint file, an unusable one, or nothing listening at its address. */
        NOT_RUNNING,
        /** Something listens, but refused the token in the endpoint file. */
        REFUSED
    }

    /** One modern subscription the relay keeps open across editor restarts. */
    private static final class Listen {
        final JsonNode id;
        final McpProtocol.Filters filters;
        final Thread thread = Thread.currentThread();
        volatile boolean cancelled;
        volatile @Nullable InputStream stream;

        Listen(JsonNode id, McpProtocol.Filters filters) {
            this.id = id;
            this.filters = filters;
        }

        void cancel() {
            cancelled = true;
            closeQuietly(stream);
            java.util.concurrent.locks.LockSupport.unpark(thread);
        }
    }

    private final Path configDir;
    private final Transport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrintStream err;
    private final Object outLock = new Object();
    private @Nullable Unavailable lastState;
    private boolean everReported;
    /** {@link #RECONNECT_MILLIS}; shorter in tests. */
    volatile long reconnectMillis = RECONNECT_MILLIS;
    /** Standard input has ended: the streams close and nothing reconnects. */
    private volatile boolean closed;
    /** The revision a legacy {@code initialize} agreed on, sent as {@code MCP-Protocol-Version} from then on. */
    private volatile @Nullable String legacyVersion;
    /** The open modern subscriptions, by request id (as JSON text). */
    private final java.util.Map<String, Listen> listens = new java.util.concurrent.ConcurrentHashMap<>();
    /** What a legacy client subscribed to, repeated to an editor that comes back. */
    private final java.util.Set<String> legacySubscriptions = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** The legacy GET stream's thread, once started, and the stream it reads. */
    private @Nullable Thread legacyThread;

    private volatile @Nullable InputStream legacyStream;
    private final java.util.concurrent.atomic.AtomicLong relayIds = new java.util.concurrent.atomic.AtomicLong();

    McpStdio(Path configDir, Transport transport, PrintStream err) {
        this.configDir = configDir;
        this.transport = transport;
        this.err = err;
    }

    /** Runs the command for {@code args} (the arguments after {@code mcp}) and returns the exit status. */
    public static int main(List<String> args) {
        Options o;
        try {
            o = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("editora mcp: " + e.getMessage());
            System.err.println(usage());
            return USAGE;
        }
        if (o.help()) {
            System.err.println(usage());
            return OK;
        }
        Path dir = com.editora.config.ConfigManager.configDirFor(o.configDir(), o.dev());
        new McpStdio(dir, httpTransport(), System.err).run(System.in, System.out);
        return OK;
    }

    /** The parsed command line. */
    record Options(@Nullable String configDir, boolean dev, boolean help) {}

    static Options parse(List<String> args) {
        String configDir = null;
        boolean dev = false;
        boolean help = false;
        for (int i = 0; i < args.size(); i++) {
            String a = args.get(i);
            if ("--help".equals(a) || "-h".equals(a)) {
                help = true;
            } else if ("--dev".equals(a)) {
                dev = true;
            } else if (a.startsWith("--config-dir=")) {
                configDir = nonBlank(a.substring("--config-dir=".length()));
            } else if ("--config-dir".equals(a)) {
                if (i + 1 >= args.size()) {
                    throw new IllegalArgumentException("--config-dir needs a folder");
                }
                configDir = nonBlank(args.get(++i));
            } else {
                throw new IllegalArgumentException("unknown argument: " + a);
            }
        }
        return new Options(configDir, dev, help);
    }

    private static String nonBlank(String v) {
        if (v.isBlank()) {
            throw new IllegalArgumentException("--config-dir needs a folder");
        }
        return v.trim();
    }

    static String usage() {
        return """
                Usage: editora mcp [--config-dir DIR] [--dev]

                Serves Editora's MCP tools over standard input and output, relaying to the running
                editor. Turn the server on in Editora (Settings > MCP Server), then add it to an agent
                once, for example:

                  claude mcp add --scope user editora -- editora mcp

                  --config-dir DIR  the config folder of the Editora to reach (as given to Editora)
                  --dev             the development instance's config folder
                  -h, --help        show this help""";
    }

    // --- the relay --------------------------------------------------------------------------------

    /** Reads messages from {@code in} until it ends, answering on {@code out}; in-flight calls are finished first. */
    void run(InputStream in, OutputStream out) {
        java.util.function.Consumer<ObjectNode> emit = answer -> write(out, answer);
        ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String message = line;
                // Each message on its own thread: a slow tool call must not hold up a ping or a second call.
                calls.execute(() -> handle(message, emit));
            }
        } catch (IOException e) {
            err.println("editora mcp: standard input failed: " + e.getMessage());
        } finally {
            close();
            calls.shutdown();
            try {
                if (!calls.awaitTermination(REQUEST_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS)) {
                    calls.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Ends the subscriptions and the legacy stream: the client is gone. */
    void close() {
        closed = true;
        listens.values().forEach(Listen::cancel);
        closeQuietly(legacyStream);
        Thread t = legacyThread;
        if (t != null) {
            java.util.concurrent.locks.LockSupport.unpark(t);
        }
    }

    /** The answer to one line, or {@code null} when it needs none (a notification). For tests. */
    @Nullable
    ObjectNode handle(String line) {
        java.util.List<ObjectNode> answers = new java.util.ArrayList<>();
        handle(line, answers::add);
        return answers.isEmpty() ? null : answers.get(answers.size() - 1);
    }

    /** Handles one line, sending what it answers to {@code emit} (several messages for an event stream). */
    void handle(String line, java.util.function.Consumer<ObjectNode> emit) {
        JsonNode msg;
        try {
            msg = mapper.readTree(line);
        } catch (IOException e) {
            emit.accept(JsonRpc.error(mapper, mapper.nullNode(), JsonRpc.PARSE_ERROR, "Parse error"));
            return;
        }
        if (msg == null || !msg.isObject()) {
            String why = msg != null && msg.isArray() ? "Batch requests are not supported" : "Invalid request";
            emit.accept(JsonRpc.error(mapper, mapper.nullNode(), JsonRpc.INVALID_REQUEST, why));
            return;
        }
        JsonNode id = msg.get("id");
        String method = msg.hasNonNull("method") ? msg.get("method").asText() : "";
        if (method.isEmpty()) {
            return; // a response to a request of ours; this server never asks the client anything
        }
        JsonNode params = msg.get("params");
        String modern = McpProtocol.modernVersion(params);
        if (method.equals("notifications/cancelled") && params != null) {
            Listen l = listens.get(params.path("requestId").toString());
            if (l != null) {
                l.cancel(); // on stdio this ends a subscription; over HTTP, closing its stream does
                return;
            }
        }
        if (modern != null && id != null && method.equals("subscriptions/listen")) {
            listen(id, msg, McpProtocol.Filters.of(params), emit);
            return;
        }
        if (modern == null && method.equals("resources/subscribe") && params != null && params.hasNonNull("uri")) {
            legacySubscriptions.add(params.get("uri").asText());
        } else if (modern == null && method.equals("resources/unsubscribe") && params != null) {
            legacySubscriptions.remove(params.path("uri").asText());
        }
        byte[] body = line.getBytes(StandardCharsets.UTF_8);
        java.util.Map<String, String> headers = headers(method, params, modern);
        Unavailable why = Unavailable.NOT_RUNNING;
        for (int attempt = 0; attempt < 2; attempt++) {
            Endpoint endpoint = readEndpoint(configDir, mapper);
            if (endpoint == null) {
                why = Unavailable.NOT_RUNNING;
                break;
            }
            Reply reply;
            try {
                reply = transport.post(endpoint, body, headers);
            } catch (IOException e) {
                why = Unavailable.NOT_RUNNING;
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                why = Unavailable.NOT_RUNNING;
                break;
            }
            if (reply.status() == 401) {
                // The token may have been replaced between reading the file and the call: read it again, once.
                closeQuietly(reply.body());
                why = Unavailable.REFUSED;
                continue;
            }
            if (reply.status() / 100 != 2 && reply.status() != 400 && reply.status() != 404) {
                // 400 and 404 carry the editor's JSON-RPC error (a revision or header refused, an unknown method)
                closeQuietly(reply.body());
                why = Unavailable.NOT_RUNNING;
                break;
            }
            report(null);
            relayAnswer(reply, id, emit);
            if (method.equals("initialize") && modern == null) {
                startLegacyStream(emit);
            }
            return;
        }
        report(why);
        if (method.equals("initialize") && modern == null) {
            legacyVersion = McpProtocol.negotiateLegacy(
                    params == null ? null : params.path("protocolVersion").asText(null));
            startLegacyStream(emit); // so notifications flow once the editor is there
        }
        if (id != null) {
            emit.accept(unavailableAnswer(id, method, params, modern != null, why));
        }
    }

    /** The protocol headers a message needs: the modern revision's, or the legacy one agreed on. */
    java.util.Map<String, String> headers(String method, @Nullable JsonNode params, @Nullable String modern) {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        if (modern != null) {
            h.put("MCP-Protocol-Version", modern);
            h.put("Mcp-Method", headerValue(method));
            String name = McpProtocol.nameOf(method, params);
            if (name != null) {
                h.put("Mcp-Name", headerValue(name));
            }
        } else if (legacyVersion != null && !method.equals("initialize")) {
            h.put("MCP-Protocol-Version", legacyVersion);
        }
        return h;
    }

    /** {@code value} as a header: as it is when plain visible ASCII, else {@code =?base64?…?=}. */
    static String headerValue(String value) {
        boolean plain = !value.isEmpty()
                && value.chars().allMatch(c -> c >= 0x20 && c <= 0x7e)
                && value.charAt(0) != ' '
                && value.charAt(value.length() - 1) != ' ';
        return plain
                ? value
                : "=?base64?" + java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))
                        + "?=";
    }

    /** Writes the editor's answer: one JSON message, or each message of an event stream as it comes. */
    private void relayAnswer(Reply reply, @Nullable JsonNode id, java.util.function.Consumer<ObjectNode> emit) {
        if (reply.eventStream()) {
            pumpEvents(reply.body(), emit::accept);
            return;
        }
        byte[] bytes;
        try (InputStream in = reply.body()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            bytes = new byte[0];
        }
        if (id == null || bytes.length == 0) {
            return;
        }
        try {
            JsonNode answer = mapper.readTree(bytes);
            if (answer instanceof ObjectNode o) {
                if (o.path("result").has("protocolVersion")) {
                    legacyVersion = o.get("result").get("protocolVersion").asText(); // initialize agreed on it
                }
                emit.accept(o);
                return;
            }
        } catch (IOException e) {
            // fall through: an answer we cannot read
        }
        emit.accept(JsonRpc.error(mapper, id, JsonRpc.INTERNAL_ERROR, "Editora sent an answer that is not JSON-RPC"));
    }

    /** Reads an event stream until it ends, handing each event's JSON message to {@code each}. */
    private void pumpEvents(InputStream in, java.util.function.Consumer<ObjectNode> each) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        try {
                            if (mapper.readTree(data.toString()) instanceof ObjectNode o) {
                                each.accept(o);
                            }
                        } catch (IOException e) {
                            // not JSON: not a message
                        }
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.startsWith("data: ") ? line.substring(6) : line.substring(5));
                }
                // ':' comments (keep-alives), event:, id: and retry: lines carry no message
            }
        } catch (IOException e) {
            // the stream broke: the editor stopped, or the subscription was cancelled
        }
    }

    /**
     * A modern subscription, kept open here until the client cancels it or goes: the editor's stream while it runs,
     * this relay's own acknowledgement while it doesn't, and when the editor comes back (a new stream, perhaps a
     * new port), notice that what was watched may have changed. Blocks the calling (virtual) thread.
     */
    private void listen(
            JsonNode id, JsonNode msg, McpProtocol.Filters filters, java.util.function.Consumer<ObjectNode> emit) {
        Listen l = new Listen(id, filters);
        listens.put(id.toString(), l);
        byte[] body = msg.toString().getBytes(StandardCharsets.UTF_8);
        java.util.Map<String, String> headers = headers("subscriptions/listen", msg.get("params"), McpProtocol.MODERN);
        boolean[] acked = {false};
        try {
            while (!l.cancelled && !closed) {
                Endpoint endpoint = readEndpoint(configDir, mapper);
                Reply reply = null;
                if (endpoint != null) {
                    try {
                        reply = transport.post(endpoint, body, headers);
                    } catch (IOException e) {
                        reply = null;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (reply != null && reply.status() == 200 && reply.eventStream()) {
                    report(null);
                    l.stream = reply.body();
                    boolean again = acked[0];
                    pumpEvents(reply.body(), event -> {
                        if (event.path("method").asText().equals("notifications/subscriptions/acknowledged")) {
                            if (!acked[0]) {
                                acked[0] = true;
                                emit.accept(event);
                            }
                            if (again) {
                                changedSince(l, emit); // the editor was away: what was watched may differ
                            }
                        } else if (!event.has("id")) {
                            emit.accept(event); // the final response means the editor stopped: wait for the next
                        }
                    });
                    l.stream = null;
                } else {
                    if (reply != null) {
                        if (reply.status() == 400) {
                            relayAnswer(reply, id, emit); // the editor refused this subscription itself
                            return;
                        }
                        closeQuietly(reply.body());
                    }
                    report(reply == null ? Unavailable.NOT_RUNNING : Unavailable.REFUSED);
                    if (!acked[0]) {
                        acked[0] = true;
                        ObjectNode p = mapper.createObjectNode();
                        p.set("notifications", filters.agreed(mapper));
                        emit.accept(
                                McpProtocol.notification(mapper, "notifications/subscriptions/acknowledged", p, id));
                    }
                }
                if (!l.cancelled && !closed) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(reconnectMillis));
                }
            }
        } finally {
            listens.remove(id.toString());
        }
    }

    /** After the editor was away: the lists and every watched resource may have changed. */
    private void changedSince(Listen l, java.util.function.Consumer<ObjectNode> emit) {
        if (l.filters.toolsListChanged()) {
            emit.accept(McpProtocol.notification(mapper, "notifications/tools/list_changed", null, l.id));
        }
        if (l.filters.resourcesListChanged()) {
            emit.accept(McpProtocol.notification(mapper, "notifications/resources/list_changed", null, l.id));
        }
        for (String uri : l.filters.resourceSubscriptions()) {
            ObjectNode p = mapper.createObjectNode().put("uri", uri);
            emit.accept(McpProtocol.notification(mapper, "notifications/resources/updated", p, l.id));
        }
    }

    /**
     * A legacy client's notifications: the editor's GET stream, held from the first {@code initialize} until the
     * client goes, and opened again when the editor comes back — with the client's subscriptions repeated, since the
     * new server has none of them, and notice that the list and the subscribed resources may have changed.
     */
    private synchronized void startLegacyStream(java.util.function.Consumer<ObjectNode> emit) {
        if (legacyThread != null || closed) {
            return;
        }
        legacyThread = Thread.ofVirtual().name("editora-mcp-legacy-stream").start(() -> {
            boolean away = false;
            while (!closed) {
                Endpoint endpoint = readEndpoint(configDir, mapper);
                Reply reply = null;
                if (endpoint != null) {
                    java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
                    String v = legacyVersion;
                    if (v != null) {
                        h.put("MCP-Protocol-Version", v);
                    }
                    try {
                        reply = transport.get(endpoint, h);
                    } catch (IOException e) {
                        reply = null;
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (reply != null && reply.status() == 200 && reply.eventStream()) {
                    if (away && endpoint != null) {
                        resubscribe(endpoint);
                        emit.accept(
                                McpProtocol.notification(mapper, "notifications/resources/list_changed", null, null));
                        for (String uri : legacySubscriptions) {
                            emit.accept(McpProtocol.notification(
                                    mapper,
                                    "notifications/resources/updated",
                                    mapper.createObjectNode().put("uri", uri),
                                    null));
                        }
                    }
                    legacyStream = reply.body();
                    pumpEvents(reply.body(), emit::accept);
                    legacyStream = null;
                } else if (reply != null) {
                    closeQuietly(reply.body());
                }
                away = true;
                if (!closed) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(reconnectMillis));
                }
            }
        });
    }

    /** Repeats the legacy client's {@code resources/subscribe} calls to an editor that just came back. */
    private void resubscribe(Endpoint endpoint) {
        for (String uri : legacySubscriptions) {
            ObjectNode req = mapper.createObjectNode();
            req.put("jsonrpc", "2.0");
            req.put("id", "editora-mcp-" + relayIds.incrementAndGet());
            req.put("method", "resources/subscribe");
            req.putObject("params").put("uri", uri);
            try {
                closeQuietly(transport
                        .post(
                                endpoint,
                                req.toString().getBytes(StandardCharsets.UTF_8),
                                headers("resources/subscribe", req.get("params"), null))
                        .body());
            } catch (IOException e) {
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** What is answered here when the editor can't answer {@code method}. */
    ObjectNode unavailableAnswer(
            JsonNode id, String method, @Nullable JsonNode params, boolean modern, Unavailable why) {
        McpToolCatalog catalog = new McpToolCatalog(mapper);
        ObjectNode result = switch (method) {
            case "initialize" ->
                catalog.initializeResult(
                        params == null ? null : params.path("protocolVersion").asText(null));
            case "server/discover" -> catalog.discoverResult();
            case "tools/list" -> catalog.listToolsResult();
            case "prompts/list" -> catalog.promptsListResult();
            case "tools/call" -> toolError(message(why));
            case "resources/list" -> {
                ObjectNode r = mapper.createObjectNode();
                r.putArray("resources"); // the open files are the editor's to tell
                yield r;
            }
            case "resources/templates/list" -> {
                ObjectNode r = mapper.createObjectNode();
                r.putArray("resourceTemplates");
                yield r;
            }
            case "ping", "resources/subscribe", "resources/unsubscribe" -> mapper.createObjectNode();
            default -> null;
        };
        if (result == null) {
            int code = method.equals("resources/read")
                    ? (modern ? JsonRpc.INVALID_PARAMS : McpProtocol.LEGACY_RESOURCE_NOT_FOUND)
                    : JsonRpc.INTERNAL_ERROR;
            return JsonRpc.error(mapper, id, code, message(why));
        }
        return JsonRpc.success(
                mapper,
                id,
                modern || method.equals("server/discover") ? McpProtocol.modernResult(mapper, method, result) : result);
    }

    private static void closeQuietly(@Nullable InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // closing to stop reading: nothing to do about a failure
            }
        }
    }

    String message(Unavailable why) {
        return switch (why) {
            case NOT_RUNNING ->
                "Editora is not running, or its MCP server is off. Ask the user to start Editora and turn on"
                        + " Settings > MCP Server (or run View: Toggle MCP Server), then try again.";
            case REFUSED ->
                "Editora refused the token in " + configDir.resolve(ENDPOINT_FILE)
                        + ". Ask the user to turn the MCP server off and on again in Editora (Settings > MCP Server),"
                        + " then try again.";
        };
    }

    private ObjectNode toolError(String text) {
        ObjectNode block = mapper.createObjectNode();
        block.put("type", "text");
        block.put("text", text);
        ObjectNode r = mapper.createObjectNode();
        r.set("content", mapper.createArrayNode().add(block));
        r.put("isError", true);
        return r;
    }

    /** One line on standard error when the editor stops or starts answering; nothing while it stays the same. */
    private synchronized void report(@Nullable Unavailable state) {
        if (everReported && state == lastState) {
            return;
        }
        everReported = true;
        lastState = state;
        err.println(state == null ? "editora mcp: connected to Editora" : "editora mcp: " + message(state));
    }

    private void write(OutputStream out, @Nullable ObjectNode answer) {
        if (answer == null) {
            return;
        }
        byte[] bytes;
        try {
            // Compact, so the message is one line: the stdio transport separates messages by newlines.
            bytes = mapper.writeValueAsBytes(answer);
        } catch (IOException e) {
            return;
        }
        synchronized (outLock) {
            try {
                out.write(bytes);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                err.println("editora mcp: standard output failed: " + e.getMessage());
            }
        }
    }

    // --- the endpoint file ------------------------------------------------------------------------

    /**
     * The endpoint {@code mcp-endpoint.json} names, or {@code null} when there is none, it can't be read, or its
     * URL is not plain HTTP on this computer's loopback address. The token is sent only there.
     */
    static @Nullable Endpoint readEndpoint(Path configDir, ObjectMapper mapper) {
        Path file = configDir.resolve(ENDPOINT_FILE);
        JsonNode node;
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            node = mapper.readTree(file.toFile());
        } catch (IOException e) {
            return null; // being rewritten, or corrupt: the same as no editor this time
        }
        if (node == null || !node.hasNonNull("url") || !node.hasNonNull("token")) {
            return null;
        }
        String token = node.get("token").asText();
        URI url;
        try {
            url = URI.create(node.get("url").asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (token.isBlank() || !isLoopbackHttp(url)) {
            return null;
        }
        return new Endpoint(url, token);
    }

    static boolean isLoopbackHttp(URI url) {
        String host = url.getHost();
        return "http".equals(url.getScheme())
                && url.getPort() > 0
                && url.getUserInfo() == null
                && host != null
                && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"));
    }

    /** The real transport: no proxy, short connect and long request timeouts, bodies read as they arrive. */
    static Transport httpTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new Transport() {
            @Override
            public Reply post(Endpoint endpoint, byte[] body, java.util.Map<String, String> headers)
                    throws IOException, InterruptedException {
                HttpRequest.Builder b = HttpRequest.newBuilder(endpoint.url())
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + endpoint.token())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                headers.forEach(b::header);
                return send(client, b.build());
            }

            @Override
            public Reply get(Endpoint endpoint, java.util.Map<String, String> headers)
                    throws IOException, InterruptedException {
                HttpRequest.Builder b = HttpRequest.newBuilder(endpoint.url())
                        .header("Accept", "text/event-stream")
                        .header("Authorization", "Bearer " + endpoint.token())
                        .GET();
                headers.forEach(b::header);
                return send(client, b.build());
            }
        };
    }

    private static Reply send(HttpClient client, HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        String type = response.headers().firstValue("Content-Type").orElse("application/json");
        return new Reply(response.statusCode(), type, response.body());
    }
}
