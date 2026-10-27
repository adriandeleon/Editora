package com.editora.install;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.process.ProcessRegistry;
import com.editora.process.ProcessRunner;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;

/**
 * The post-install proof that a freshly extracted pom.xml language server can answer a request — not merely
 * start. The lemminx-maven bundle used to pass every install check (it is a zip with jars at the top), answer
 * {@code initialize}, say "Loaded and ready", and then fail every request inside the server with a
 * {@code NoClassDefFoundError} (Maven review C1). A server that only answers the handshake is not installed.
 *
 * <p>Minimal JSON-RPC over stdio, deliberately independent of the editor's session plumbing: {@code initialize},
 * {@code initialized}, {@code didOpen} on a one-line pom in a temp folder, {@code textDocument/hover} on
 * {@code <project}, then {@code shutdown}/{@code exit}. The verdict is the hover answer: a result (even null) is
 * a pass; a JSON-RPC error, an exit before the answer, or no answer within the deadline fails the install with a
 * message that says which. The framing, parsing and verdict are pure and unit-tested; the process part runs the
 * install's own launch command, resolved on the augmented PATH like every other subprocess.
 */
public final class JvmLspSmoke {

    private static final Logger LOG = Logger.getLogger(JvmLspSmoke.class.getName());

    /** How long the whole exchange may take (the Maven server loads Plexus and indexes {@code ~/.m2} at start). */
    public static final Duration DEADLINE = Duration.ofSeconds(60);

    /** The one-line pom the server is asked about. */
    static final String POM = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
            + "<groupId>smoke</groupId><artifactId>smoke</artifactId><version>1</version></project>\n";

    private static final int STDERR_CAP = 20;
    private static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;

    private JvmLspSmoke() {}

    /** Runs the exchange with the default {@link #DEADLINE}. */
    public static Optional<String> hoverCheck(List<String> argv) {
        return hoverCheck(argv, DEADLINE);
    }

    /**
     * Starts {@code argv}, asks it to hover over {@code <project} in a one-line pom, and returns why that failed —
     * or empty when the server answered with a result. Never throws: a failure to even start is a failure text.
     */
    public static Optional<String> hoverCheck(List<String> argv, Duration deadline) {
        return check(argv, deadline, true);
    }

    /**
     * Starts {@code argv} and asks it only to {@code initialize}: whether the program runs here and speaks LSP at
     * all. For a native server whose hash already proves it is the release (lemminx): its question is whether this
     * machine can run it — lemminx needs glibc 2.34 (XML review A4) — not whether its bundle is whole; and its
     * hover on the one-line pom fails inside the server even where it works. Empty when it answered.
     */
    public static Optional<String> initializeCheck(List<String> argv, Duration deadline) {
        return check(argv, deadline, false);
    }

    private static Optional<String> check(List<String> argv, Duration deadline, boolean hover) {
        if (argv == null || argv.isEmpty()) {
            return Optional.of("no launch command");
        }
        Path dir = null;
        Process process = null;
        try {
            dir = Files.createTempDirectory("editora-lsp-smoke-");
            Path pom = dir.resolve("pom.xml");
            Files.writeString(pom, POM);
            ProcessBuilder pb = new ProcessBuilder(ProcessRunner.resolveExecutable(argv));
            pb.directory(dir.toFile());
            ProcessRunner.applyUserEnv(pb);
            process = pb.start();
            ProcessRegistry.track(process);
            return exchange(process, dir, pom, deadline, hover);
        } catch (IOException e) {
            return Optional.of("could not start " + argv.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("interrupted");
        } finally {
            if (process != null) {
                ProcessRegistry.killTree(process);
            }
            if (dir != null) {
                deleteQuietly(dir);
            }
        }
    }

    /** A message read from the server, or the end of its output ({@code eof}, with the exit code once known). */
    private record Incoming(@Nullable JsonObject message, boolean eof) {}

    private static Optional<String> exchange(Process process, Path dir, Path pom, Duration deadline, boolean hover)
            throws IOException, InterruptedException {
        List<String> stderr = new ArrayList<>();
        Thread stderrDrain = drain(process.getErrorStream(), stderr);
        LinkedBlockingQueue<Incoming> inbox = new LinkedBlockingQueue<>();
        Thread reader = new Thread(
                () -> {
                    try (InputStream in = process.getInputStream()) {
                        String body;
                        while ((body = readFrame(in)) != null) {
                            JsonElement parsed = JsonParser.parseString(body);
                            if (parsed.isJsonObject()) {
                                inbox.add(new Incoming(parsed.getAsJsonObject(), false));
                            }
                        }
                    } catch (IOException | RuntimeException e) {
                        LOG.log(Level.FINE, "smoke: reading the server's output ended", e);
                    }
                    inbox.add(new Incoming(null, true));
                },
                "lsp-smoke-reader");
        reader.setDaemon(true);
        reader.start();

        long deadlineNanos = System.nanoTime() + deadline.toNanos();
        OutputStream out = process.getOutputStream();
        String rootUri = dir.toUri().toString();
        String docUri = pom.toUri().toString();
        try {
            send(out, request(1, "initialize", initializeParams(rootUri)));
        } catch (IOException e) {
            // Gone before it read a byte (a native build on too old a C library exits at once): the end of its
            // output, awaited below, says how it ended and what it wrote on stderr — "Stream closed" says neither.
            LOG.log(Level.FINE, "smoke: the server closed its input before initialize", e);
        }
        Optional<String> failure = await(inbox, 1, "initialize", deadlineNanos, deadline, process, stderr, stderrDrain);
        if (failure.isPresent()) {
            return failure;
        }
        send(out, notification("initialized", new JsonObject()));
        if (hover) {
            send(out, notification("textDocument/didOpen", didOpenParams(docUri)));
            send(out, request(2, "textDocument/hover", positionParams(docUri, 0, 2)));
            failure = await(inbox, 2, "hover", deadlineNanos, deadline, process, stderr, stderrDrain);
            if (failure.isPresent()) {
                return failure;
            }
        }
        try {
            send(out, request(3, "shutdown", null));
            send(out, notification("exit", null));
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (IOException e) {
            LOG.log(Level.FINE, "smoke: the server closed its input before shutdown", e);
        }
        return Optional.empty();
    }

    /**
     * Waits for the response to request {@code id} and judges it: empty on a result, else why it failed (the
     * server's error, its early exit with the first stderr lines, or the deadline).
     */
    private static Optional<String> await(
            LinkedBlockingQueue<Incoming> inbox,
            int id,
            String what,
            long deadlineNanos,
            Duration deadline,
            Process process,
            List<String> stderr,
            Thread stderrDrain)
            throws InterruptedException {
        while (true) {
            long left = deadlineNanos - System.nanoTime();
            Incoming in = left <= 0 ? null : inbox.poll(left, TimeUnit.NANOSECONDS);
            if (in == null) {
                return Optional.of("no answer to " + what + " within " + deadline.toSeconds() + " s");
            }
            if (in.eof()) {
                process.waitFor(2, TimeUnit.SECONDS);
                stderrDrain.join(1_000); // its last lines are the reason, when the process is gone
                String exit = process.isAlive() ? "closed its output" : "exited with code " + process.exitValue();
                String tail;
                synchronized (stderr) {
                    tail = String.join(" | ", stderr);
                }
                return Optional.of(
                        "the server " + exit + " before answering " + what + (tail.isBlank() ? "" : ": " + tail));
            }
            JsonObject msg = in.message();
            if (isResponseTo(msg, id)) {
                return verdict(msg).map(reason -> what + " failed inside the server: " + reason);
            }
            // Notifications ($/progress, window/logMessage, publishDiagnostics) and the server's own requests
            // (client/registerCapability, workspace/configuration) are not answered: a one-shot client.
        }
    }

    // --- pure: framing, messages, verdict --------------------------------------------------------------------

    /** The {@code Content-Length}-framed bytes of one JSON message. Pure. */
    public static byte[] frame(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }

    /**
     * Reads one framed message from {@code in}: the headers up to the blank line, then {@code Content-Length}
     * bytes of body. Null at a clean end of stream; an {@link IOException} on a malformed or oversized frame.
     */
    public static @Nullable String readFrame(InputStream in) throws IOException {
        int length = -1;
        boolean any = false;
        while (true) {
            String line = readLine(in);
            if (line == null) {
                return any ? fail("unexpected end of stream inside a header") : null;
            }
            if (line.isEmpty()) {
                if (!any) {
                    continue; // CRLF padding between messages
                }
                break;
            }
            any = true;
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    return fail("bad Content-Length: " + line);
                }
            }
        }
        if (length < 0 || length > MAX_FRAME_BYTES) {
            return fail("missing or oversized Content-Length: " + length);
        }
        byte[] body = in.readNBytes(length);
        if (body.length < length) {
            return fail("end of stream inside a message body");
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    /** One header line without its line ending; null at end of stream before any byte. */
    private static @Nullable String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buf.write(c);
            }
            if (buf.size() > 8192) {
                return fail("header line too long");
            }
        }
        if (c < 0 && buf.size() == 0) {
            return null;
        }
        return buf.toString(StandardCharsets.US_ASCII);
    }

    private static String fail(String why) throws IOException {
        throw new IOException(why);
    }

    /** Whether {@code msg} is the response to request {@code id}. Pure. */
    static boolean isResponseTo(@Nullable JsonObject msg, int id) {
        if (msg == null || !msg.has("id") || msg.has("method")) {
            return false;
        }
        JsonElement idEl = msg.get("id");
        if (!idEl.isJsonPrimitive()) {
            return false;
        }
        return idEl.getAsJsonPrimitive().isNumber()
                ? idEl.getAsInt() == id
                : String.valueOf(id).equals(idEl.getAsString());
    }

    /**
     * Why a response is a failure, or empty for a result: a JSON-RPC {@code error} reads
     * {@code "<code> <message>: <first line of data>"} (the data is where lemminx puts the stack trace, whose
     * first line names the missing class). Pure.
     */
    public static Optional<String> verdict(@Nullable JsonObject response) {
        if (response == null || !response.has("error") || response.get("error").isJsonNull()) {
            return Optional.empty();
        }
        JsonElement err = response.get("error");
        if (!err.isJsonObject()) {
            return Optional.of(String.valueOf(err));
        }
        JsonObject e = err.getAsJsonObject();
        StringBuilder sb = new StringBuilder();
        if (e.has("code") && e.get("code").isJsonPrimitive()) {
            sb.append(e.get("code").getAsString()).append(' ');
        }
        if (e.has("message") && e.get("message").isJsonPrimitive()) {
            sb.append(e.get("message").getAsString());
        }
        String data =
                e.has("data") && e.get("data").isJsonPrimitive() ? e.get("data").getAsString() : "";
        String first = firstLine(data);
        if (!first.isEmpty()) {
            sb.append(": ").append(first);
        }
        return Optional.of(sb.toString().strip());
    }

    /** The first non-blank line of {@code text}, cut to 240 characters. Pure. */
    public static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\R")) {
            String s = line.strip();
            if (!s.isEmpty()) {
                return s.length() > 240 ? s.substring(0, 240) + "…" : s;
            }
        }
        return "";
    }

    static String request(int id, String method, @Nullable JsonElement params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("id", id);
        o.addProperty("method", method);
        if (params != null) {
            o.add("params", params);
        }
        return o.toString();
    }

    static String notification(String method, @Nullable JsonElement params) {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", "2.0");
        o.addProperty("method", method);
        if (params != null) {
            o.add("params", params);
        }
        return o.toString();
    }

    static JsonObject initializeParams(String rootUri) {
        JsonObject p = new JsonObject();
        p.addProperty("processId", ProcessHandle.current().pid());
        p.addProperty("rootUri", rootUri);
        JsonObject caps = new JsonObject();
        JsonObject text = new JsonObject();
        JsonObject hover = new JsonObject();
        hover.addProperty("dynamicRegistration", false);
        text.add("hover", hover);
        caps.add("textDocument", text);
        p.add("capabilities", caps);
        JsonObject init = new JsonObject();
        JsonObject xml = new JsonObject();
        JsonObject maven = new JsonObject();
        JsonObject central = new JsonObject();
        central.addProperty("skip", true); // a smoke must not send completion queries to search.maven.org
        maven.add("central", central);
        xml.add("maven", maven);
        init.add("settings", wrap("xml", xml));
        p.add("initializationOptions", init);
        return p;
    }

    private static JsonObject wrap(String key, JsonElement value) {
        JsonObject o = new JsonObject();
        o.add(key, value);
        return o;
    }

    static JsonObject didOpenParams(String uri) {
        JsonObject doc = new JsonObject();
        doc.addProperty("uri", uri);
        doc.addProperty("languageId", "xml");
        doc.addProperty("version", 1);
        doc.addProperty("text", POM);
        return wrap("textDocument", doc);
    }

    static JsonObject positionParams(String uri, int line, int character) {
        JsonObject p = new JsonObject();
        p.add("textDocument", wrap("uri", new com.google.gson.JsonPrimitive(uri)));
        JsonObject pos = new JsonObject();
        pos.addProperty("line", line);
        pos.addProperty("character", character);
        p.add("position", pos);
        return p;
    }

    // --- process helpers -------------------------------------------------------------------------------------

    private static void send(OutputStream out, String json) throws IOException {
        out.write(frame(json));
        out.flush();
    }

    private static Thread drain(InputStream err, List<String> into) {
        Thread t = new Thread(
                () -> {
                    try (var r =
                            new java.io.BufferedReader(new java.io.InputStreamReader(err, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            synchronized (into) {
                                if (into.size() < STDERR_CAP && !line.isBlank()) {
                                    into.add(line.strip());
                                }
                            }
                        }
                    } catch (IOException ignored) {
                        // the process is gone
                    }
                },
                "lsp-smoke-stderr");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void deleteQuietly(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort: a temp folder
                }
            });
        } catch (IOException ignored) {
            // best effort: a temp folder
        }
    }
}
