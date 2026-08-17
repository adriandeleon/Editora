package com.editora.ipc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.editora.config.ConfigWriter;

/**
 * Routes a second launch into the already-running Editora instead of starting a second editor.
 *
 * <p>On Linux and Windows a file manager's "Open With" delivers the path as <b>argv</b>, which by definition
 * means a new process — so clicking a file while Editora was open paid a full cold start <em>and</em> left a
 * second JVM resident (measured: 670 MB + 1707 MB for two windows' worth of one editor). macOS never had this
 * problem because Finder delivers an AppKit {@code openFiles} Apple Event, which reaches the running app;
 * this is the cross-platform equivalent of that path, and it hands off to the same
 * {@code WindowManager.openExternalFiles} the Apple Event does.
 *
 * <p><b>Scope is the config directory</b>, not the machine: the endpoint file lives in it, so {@code --dev}
 * and any {@code --config-dir} instance are separate instances by construction and can never hand off to each
 * other.
 *
 * <p><b>No Jackson here, deliberately.</b> This runs in {@code main} on <em>every</em> launch, including the
 * one that goes on to become the primary. Parsing the endpoint with the app's {@code ObjectMapper} would make
 * every launch pay Jackson's ~60 ms class-loading warm-up before the window even starts — more than this
 * whole feature saves. A {@link Properties} file costs nothing beyond {@code java.base}.
 */
public final class SingleInstance implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(SingleInstance.class.getName());

    /** Endpoint file, in the config dir so the instance is scoped to it (see the class doc). */
    static final String ENDPOINT_FILE = "instance.properties";

    /** Protocol banner. Sent both ways so we never mistake an unrelated listener for an Editora. */
    static final String MAGIC = "EDITORA-INSTANCE-1";

    private static final String ACK_OK = MAGIC + " OK";

    /** How long to wait for the running instance: generous enough for a busy FX thread, short enough that a
     *  dead endpoint doesn't visibly stall the launch we're about to do ourselves instead. */
    private static final int CONNECT_TIMEOUT_MS = 700;

    private static final int READ_TIMEOUT_MS = 2000;

    /** A launch carrying more than this is not a file-manager click; refuse rather than buffer it. */
    private static final int MAX_REQUEST_BYTES = 64 * 1024;

    /** Claim attempts, to settle a race between two launches starting in the same instant. */
    private static final int CLAIM_ATTEMPTS = 3;

    /** What {@link #start} decided this process is. */
    public enum Role {
        /** This process owns the endpoint; it must serve forwarded launches. */
        PRIMARY,
        /** The launch was delivered to a running instance — the caller should exit immediately. */
        FORWARDED,
        /** No handoff (opted out, nothing to forward, or the endpoint could not be used). Start normally. */
        STANDALONE
    }

    /** Receives a forwarded launch's raw arguments, on whatever thread the accept loop uses. */
    public interface Listener {
        void onLaunch(List<String> args);
    }

    private final Path endpoint;
    private final List<List<String>> pending = new ArrayList<>();
    private Listener listener;
    private volatile boolean closed;
    /** Set by {@link #claimAsync}'s thread once the port is bound; null until then, and if the claim lost. */
    private volatile ServerSocket server;

    private volatile String token;
    /** Counted down when the claim has finished, either way — so a test can wait for it deterministically. */
    private final java.util.concurrent.CountDownLatch claimed = new java.util.concurrent.CountDownLatch(1);

    private SingleInstance(Path endpoint) {
        this.endpoint = endpoint;
    }

    /** Runs the claim off the startup path; see the note in {@link #start}. */
    private void claimAsync() {
        Thread t = new Thread(
                () -> {
                    try {
                        claim();
                    } finally {
                        claimed.countDown();
                    }
                },
                "editora-single-instance-claim");
        t.setDaemon(true);
        t.start();
    }

    /** Blocks until the asynchronous claim has settled. Test seam — never called on the startup path. */
    boolean awaitClaim(long millis) throws InterruptedException {
        return claimed.await(millis, TimeUnit.MILLISECONDS);
    }

    /** True when this process owns the endpoint and is serving forwarded launches. */
    public boolean serving() {
        return server != null && !closed;
    }

    /** The outcome of {@link #start}: a role, plus the live instance when this process became PRIMARY. */
    public record Result(Role role, SingleInstance instance) {
        public boolean forwarded() {
            return role == Role.FORWARDED;
        }
    }

    /**
     * Decides this process's role: forward {@code args} to a running instance, or become the one that serves
     * them.
     *
     * <p>{@code allowForward} is the caller's policy (see {@code App.shouldForwardLaunch}) — when false this
     * only ever claims, never delivers, so an explicitly-new instance can still serve later launches.
     *
     * <p>Never throws: every failure degrades to {@link Role#STANDALONE}, i.e. exactly the behaviour before
     * this existed. Starting a second editor is a far better outcome than failing to start one.
     */
    public static Result start(Path configDir, List<String> args, boolean allowForward) {
        if (configDir == null) {
            return new Result(Role.STANDALONE, null);
        }
        Path endpoint = configDir.resolve(ENDPOINT_FILE);
        Endpoint existing = read(endpoint);
        if (existing != null) {
            if (allowForward && forward(existing, args)) {
                return new Result(Role.FORWARDED, null);
            }
            // Either we may not forward, or nobody answered — a crash leaves the file advertising a dead
            // port. Only treat it as ours to replace when it is genuinely unreachable, so a live instance is
            // never evicted by a launch that merely chose not to talk to it.
            if (reachable(existing)) {
                return new Result(Role.STANDALONE, null); // alive, but this launch wants its own process
            }
        }
        // Becoming the primary is deliberately asynchronous. Everything up to here is one file read (plus, at
        // most, a connect to an endpoint that already existed), but binding a socket, seeding a token and
        // publishing the file drags the java.net and security classes onto the critical path — measured at
        // ~38 ms of time-to-first-paint for ~4 ms of actual work, i.e. almost entirely class loading. Nothing
        // needs the endpoint to exist before this process has a window: a launch arriving in that gap simply
        // starts its own editor, exactly as it did before this feature existed.
        SingleInstance instance = new SingleInstance(endpoint);
        instance.claimAsync();
        return new Result(Role.PRIMARY, instance);
    }

    /**
     * Binds a loopback port and publishes it, atomically. The endpoint is written to a temp file and then
     * {@code move}d into place <b>without</b> REPLACE_EXISTING: that both settles the race (exactly one
     * launch can create the name) and guarantees a reader never sees a half-written file — a plain
     * create-then-write would leave a window where the endpoint exists but names no port yet.
     */
    private void claim() {
        ServerSocket socket = null;
        Path tmp = endpoint.resolveSibling(
                ENDPOINT_FILE + "." + ProcessHandle.current().pid() + ".tmp");
        try {
            // A leftover that names no port must be removed, not merely ignored: the move below deliberately
            // refuses to replace an existing name (that is what settles the race), so a corrupt file — a
            // truncated write, a foreign file — would otherwise block every future claim and disable handoff
            // permanently. Safe to delete precisely because a live primary publishes atomically, so a reader
            // never sees a partial file: unparseable really does mean nobody's. An endpoint that parses but
            // does not answer was already established as dead by start().
            if (Files.exists(endpoint)) {
                deleteStale(endpoint, "it named no reachable instance");
            }
            // First run: the config dir doesn't exist yet (ConfigManager creates it later, in start()), and
            // without this the very first launch could never claim and so could never be handed a file.
            Files.createDirectories(endpoint.getParent());
            socket = new ServerSocket();
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            String newToken = newToken();
            Properties props = new Properties();
            props.setProperty("magic", MAGIC);
            props.setProperty("port", String.valueOf(socket.getLocalPort()));
            props.setProperty("token", newToken);
            props.setProperty("pid", String.valueOf(ProcessHandle.current().pid()));
            // The token is a credential for "make this editor open files", so it gets the same owner-only
            // treatment as everything else derived from the config dir.
            ConfigWriter.createOwnerOnly(tmp);
            try (var out = Files.newOutputStream(tmp)) {
                props.store(out, "Editora single-instance endpoint; recreated on every launch");
            }
            Files.move(tmp, endpoint); // no REPLACE_EXISTING: fails iff someone else already claimed
            if (closed) { // the app exited while we were still claiming
                closeQuietly(socket);
                deleteQuietly(endpoint);
                return;
            }
            this.token = newToken;
            this.server = socket;
            startAcceptLoop();
        } catch (FileAlreadyExistsException raced) {
            // Another launch claimed in the same instant. It serves from now on; this process simply runs
            // without receiving forwards, which is exactly the behaviour before this feature existed.
            LOG.fine("Another instance claimed the endpoint first; not serving forwarded launches");
            closeQuietly(socket);
            deleteQuietly(tmp);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not claim the single-instance endpoint", e);
            closeQuietly(socket);
            deleteQuietly(tmp);
        }
    }

    private void startAcceptLoop() {
        ServerSocket bound = server;
        Thread t = new Thread(
                () -> {
                    while (!closed) {
                        try (Socket client = bound.accept()) {
                            client.setSoTimeout(READ_TIMEOUT_MS);
                            serve(client);
                        } catch (IOException e) {
                            if (!closed) {
                                LOG.log(Level.FINE, "single-instance accept failed", e);
                            }
                        } catch (RuntimeException e) {
                            LOG.log(Level.FINE, "single-instance request failed", e);
                        }
                    }
                },
                "editora-single-instance");
        t.setDaemon(true); // must never hold the JVM open
        t.start();
    }

    /** Reads one request line, authenticates it, and acks. The wire format is one line: MAGIC TOKEN ARGS… */
    private void serve(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out =
                new BufferedWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));
        String line = readBounded(in);
        List<String> args = parseRequest(line, token);
        if (args == null) {
            out.write(MAGIC + " ERR");
            out.newLine();
            out.flush();
            return;
        }
        out.write(ACK_OK);
        out.newLine();
        out.flush();
        deliver(args);
    }

    /** Buffers until the UI is ready — a launch can arrive while this process is still building its window. */
    private void deliver(List<String> args) {
        Listener target;
        synchronized (this) {
            if (listener == null) {
                pending.add(args);
                return;
            }
            target = listener;
        }
        target.onLaunch(args);
    }

    /** Installs the handler and drains anything that arrived before the UI existed. */
    public void setListener(Listener listener) {
        List<List<String>> queued;
        synchronized (this) {
            this.listener = listener;
            queued = List.copyOf(pending);
            pending.clear();
        }
        if (listener != null) {
            queued.forEach(listener::onLaunch);
        }
    }

    /**
     * Parses and authenticates a request line, returning its arguments — or {@code null} if it is not a
     * well-formed, correctly-tokened Editora request. Pure, so the wire format is unit-tested without sockets.
     *
     * <p>Arguments are encoded one per field, hex-encoded, so a path containing spaces, newlines or a
     * non-UTF-8-safe byte sequence survives the round trip intact — a filename is user data and must not be
     * re-split on the far side.
     */
    static List<String> parseRequest(String line, String expectedToken) {
        if (line == null || expectedToken == null || expectedToken.isBlank()) {
            return null;
        }
        String[] parts = line.trim().split(" ");
        if (parts.length < 2 || !MAGIC.equals(parts[0])) {
            return null;
        }
        // Constant-time-ish compare: this is a local token, but there is no reason to leak its prefix.
        if (!java.security.MessageDigest.isEqual(
                parts[1].getBytes(StandardCharsets.UTF_8), expectedToken.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        List<String> args = new ArrayList<>();
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            try {
                args.add(new String(HexFormat.of().parseHex(parts[i]), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return args;
    }

    /** Builds the request line for {@code args}. Pure counterpart of {@link #parseRequest}. */
    static String buildRequest(String token, List<String> args) {
        StringBuilder sb = new StringBuilder(MAGIC).append(' ').append(token);
        for (String a : args) {
            if (a != null) {
                sb.append(' ').append(HexFormat.of().formatHex(a.getBytes(StandardCharsets.UTF_8)));
            }
        }
        return sb.toString();
    }

    /** Sends {@code args} to the endpoint; true only when it acknowledged as an Editora. */
    private static boolean forward(Endpoint endpoint, List<String> args) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            var out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            out.write(buildRequest(endpoint.token, args));
            out.newLine();
            out.flush();
            var in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return ACK_OK.equals(readBounded(in));
        } catch (IOException e) {
            return false;
        }
    }

    /** Whether something is listening on the endpoint at all (used to tell "busy" from "crashed"). */
    private static boolean reachable(Endpoint endpoint) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoint.port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String readBounded(BufferedReader in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (sb.length() >= MAX_REQUEST_BYTES) {
                return null;
            }
            sb.append((char) c);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** The endpoint file's contents; {@code null} from {@link #read} when absent or unusable. */
    private record Endpoint(int port, String token) {}

    private static Endpoint read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        Properties props = new Properties();
        try (var in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        if (!MAGIC.equals(props.getProperty("magic"))) {
            return null;
        }
        String token = props.getProperty("token");
        try {
            int port = Integer.parseInt(props.getProperty("port", "").trim());
            if (port <= 0 || port > 65535 || token == null || token.isBlank()) {
                return null;
            }
            return new Endpoint(port, token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static void deleteStale(Path endpoint, String why) {
        try {
            Files.deleteIfExists(endpoint);
            LOG.info("Reaped a stale " + ENDPOINT_FILE + " (" + why + ")");
        } catch (IOException e) {
            LOG.log(Level.FINE, "Could not delete a stale " + ENDPOINT_FILE, e);
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Stops serving and removes the endpoint so the next launch becomes primary rather than waiting on us. */
    @Override
    public void close() {
        closed = true;
        // May be null when the claim is still in flight or lost the race; the claim thread re-checks `closed`
        // after publishing, so a claim that lands after this tears itself down rather than leaking a socket
        // and a stale endpoint file.
        closeQuietly(server);
        if (server != null) {
            deleteQuietly(endpoint);
        }
    }

    /** The loopback port being served, or -1 when this process never acquired the endpoint. */
    public int port() {
        ServerSocket bound = server;
        return bound == null ? -1 : bound.getLocalPort();
    }
}
