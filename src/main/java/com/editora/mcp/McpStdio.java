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
 * <p>When no editor answers (not running, MCP off, or the token refused), {@code initialize} and
 * {@code tools/list} are answered here from {@link McpToolCatalog}, so the agent still starts and sees the tools,
 * and each {@code tools/call} returns a tool error that says what the user has to do. No window, no JavaFX, no
 * single-instance hand-off; nothing but JSON-RPC is written to standard output.
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

    /** Where the editor listens, as {@code mcp-endpoint.json} says. */
    record Endpoint(URI url, String token) {}

    /** One HTTP answer: its status and its body (empty for none). */
    record Reply(int status, byte[] body) {}

    /** Sends one JSON-RPC message to the editor. A seam for tests; {@link #httpTransport} in use. */
    interface Transport {
        Reply post(Endpoint endpoint, byte[] body) throws IOException, InterruptedException;
    }

    /** Why the editor could not answer. */
    enum Unavailable {
        /** No endpoint file, an unusable one, or nothing listening at its address. */
        NOT_RUNNING,
        /** Something listens, but refused the token in the endpoint file. */
        REFUSED
    }

    private final Path configDir;
    private final Transport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PrintStream err;
    private final Object outLock = new Object();
    private @Nullable Unavailable lastState;
    private boolean everReported;

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
        ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String message = line;
                // Each message on its own thread: a slow tool call must not hold up a ping or a second call.
                calls.execute(() -> write(out, handle(message)));
            }
        } catch (IOException e) {
            err.println("editora mcp: standard input failed: " + e.getMessage());
        } finally {
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

    /** The answer to one line, or {@code null} when it needs none (a notification). */
    @Nullable
    ObjectNode handle(String line) {
        JsonNode msg;
        try {
            msg = mapper.readTree(line);
        } catch (IOException e) {
            return JsonRpc.error(mapper, mapper.nullNode(), JsonRpc.PARSE_ERROR, "Parse error");
        }
        if (msg == null || !msg.isObject()) {
            String why = msg != null && msg.isArray() ? "Batch requests are not supported" : "Invalid request";
            return JsonRpc.error(mapper, mapper.nullNode(), JsonRpc.INVALID_REQUEST, why);
        }
        JsonNode id = msg.get("id");
        String method = msg.hasNonNull("method") ? msg.get("method").asText() : "";
        if (method.isEmpty()) {
            // A response to a request of ours; this server never asks the client anything.
            return null;
        }
        byte[] body = line.getBytes(StandardCharsets.UTF_8);
        Unavailable why = Unavailable.NOT_RUNNING;
        for (int attempt = 0; attempt < 2; attempt++) {
            Endpoint endpoint = readEndpoint(configDir, mapper);
            if (endpoint == null) {
                why = Unavailable.NOT_RUNNING;
                break;
            }
            Reply reply;
            try {
                reply = transport.post(endpoint, body);
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
                why = Unavailable.REFUSED;
                continue;
            }
            if (reply.status() / 100 != 2) {
                why = Unavailable.NOT_RUNNING;
                break;
            }
            report(null);
            if (id == null || reply.body().length == 0) {
                return null;
            }
            try {
                JsonNode answer = mapper.readTree(reply.body());
                if (answer instanceof ObjectNode o) {
                    return o;
                }
            } catch (IOException e) {
                // fall through: an answer we cannot read
            }
            return JsonRpc.error(mapper, id, JsonRpc.INTERNAL_ERROR, "Editora sent an answer that is not JSON-RPC");
        }
        report(why);
        return id == null ? null : unavailableAnswer(id, method, why);
    }

    /** What is answered here when the editor can't answer {@code method}. */
    ObjectNode unavailableAnswer(JsonNode id, String method, Unavailable why) {
        McpToolCatalog catalog = new McpToolCatalog(mapper);
        return switch (method) {
            case "initialize" -> JsonRpc.success(mapper, id, catalog.initializeResult());
            case "tools/list" -> JsonRpc.success(mapper, id, catalog.listToolsResult());
            case "ping" -> JsonRpc.success(mapper, id, mapper.createObjectNode());
            case "tools/call" -> JsonRpc.success(mapper, id, toolError(message(why)));
            default -> JsonRpc.error(mapper, id, JsonRpc.INTERNAL_ERROR, message(why));
        };
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

    /** The real transport: one POST per message, no proxy, short connect and long request timeouts. */
    static Transport httpTransport() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .proxy(HttpClient.Builder.NO_PROXY)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return (endpoint, body) -> {
            HttpRequest request = HttpRequest.newBuilder(endpoint.url())
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("Authorization", "Bearer " + endpoint.token())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.body());
        };
    }
}
