package com.editora.vfs;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.application.Platform;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;

/**
 * Owns the SFTP connections (one {@link SftpFileSystem} per {@code user@host:port}) and reconstructs remote
 * paths for {@link Vfs}. A connected filesystem makes {@code Files.read/write/list/walk} work over SFTP, so
 * the rest of the app keeps using {@link Path} unchanged. Connect/auth run off the FX thread (the
 * {@code GitService} idiom: a daemon executor + {@link Platform#runLater} callbacks).
 */
public final class RemoteFileSystems {

    /** The result of a connect attempt: {@code root} is the remote start directory (the connection's last
     *  path, else the SFTP home) when {@code ok}, else {@code error} explains the failure. */
    public record Result(boolean ok, Path root, String error) {}

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(20);
    /** How often the connect wait re-checks the (prompt-excluded) deadline while blocking. */
    private static final long POLL_MILLIS = 200;

    private final SshClient client;
    private final Duration connectTimeout;
    private final Duration authTimeout;
    /**
     * Host-key-dialog time during the <em>current</em> connect, so the auth deadline can discount it (#486): the
     * trust-on-first-use prompt blocks the key exchange that gates auth, and a careful "compare this fingerprint"
     * answer must not time the handshake out. {@code promptAccumulatedNanos} is completed prompt time;
     * {@code promptStartNanos} marks a prompt still on screen (0 when none) so {@link #livePromptElapsedNanos()}
     * counts the in-progress wait too — the deadline is checked <em>while</em> the dialog is up, before the
     * verifier's {@code finally} has folded it in. The verifier (a MINA I/O thread) writes both; the auth wait
     * (the serialized {@code sftp-connect} thread) reads them. Reset per connect; safe because {@link #exec}
     * serializes connects.
     */
    private final AtomicLong promptAccumulatedNanos = new AtomicLong();

    private final AtomicLong promptStartNanos = new AtomicLong();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sftp-connect");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, SftpFileSystem> byAuthority = new ConcurrentHashMap<>();

    /**
     * Asked to approve a host key that {@code known_hosts} has never seen — the trust-on-first-use decision,
     * the same one {@code ssh} puts to you at the terminal.
     *
     * <p>Called on an SSH I/O thread and <b>must block</b> until the user decides; {@code false} aborts the
     * connection. A null prompt means "no UI to ask with", which rejects unknown hosts rather than trusting
     * them.
     */
    @FunctionalInterface
    public interface HostKeyPrompt {
        boolean confirmUnknownHost(String host, int port, String keyType, String fingerprint);
    }

    public RemoteFileSystems() {
        this(null);
    }

    /**
     * @param prompt asks about a host key not yet in {@code known_hosts}; null rejects unknown hosts outright.
     */
    public RemoteFileSystems(HostKeyPrompt prompt) {
        this(prompt, knownHostsFile());
    }

    /** As {@link #RemoteFileSystems(HostKeyPrompt)}, with an explicit {@code known_hosts} (tests). */
    RemoteFileSystems(HostKeyPrompt prompt, Path knownHosts) {
        this(prompt, knownHosts, CONNECT_TIMEOUT, AUTH_TIMEOUT);
    }

    /** As above, with explicit connect/auth timeouts — the seam tests use to exercise the deadline quickly. */
    RemoteFileSystems(HostKeyPrompt prompt, Path knownHosts, Duration connectTimeout, Duration authTimeout) {
        this.connectTimeout = connectTimeout;
        this.authTimeout = authTimeout;
        client = SshClient.setUpDefaultClient();
        // Verify the server's host key against ~/.ssh/known_hosts — the same file ssh itself uses, so a host
        // already accepted at the terminal connects without a prompt, and an entry accepted here is honoured
        // by ssh afterwards. MINA's verifier gives the three outcomes SSH requires: a known host whose key
        // matches connects silently; an UNKNOWN host goes to the trust-on-first-use delegate below and is
        // written to the file only once the user accepts; and a known host presenting a DIFFERENT key is
        // refused outright, without asking. That last case is the man-in-the-middle, and it is the point.
        client.setServerKeyVerifier(new KnownHostsServerKeyVerifier(unknownHostVerifier(prompt), knownHosts));
        client.start();
        Vfs.setRemoteResolver(this::resolve); // remote sftp:// strings → live Paths
        Vfs.setRemoteStorable(this::storableFor); // remote Path → sftp:// string (Path.toUri() throws for SFTP)
        Vfs.setRemoteOwner(this); // so shutdown() can un-pin these statics (see Vfs.clearRemoteHooksIf)
    }

    /** {@code ~/.ssh/known_hosts} — shared with OpenSSH deliberately (see the constructor). */
    static Path knownHostsFile() {
        return Path.of(System.getProperty("user.home", "."), ".ssh", "known_hosts");
    }

    /**
     * The delegate {@link KnownHostsServerKeyVerifier} consults for a host it has no entry for. Accepting here
     * is what makes MINA write the entry, so this is the only place trust is established.
     */
    private ServerKeyVerifier unknownHostVerifier(HostKeyPrompt prompt) {
        return (session, address, key) -> {
            if (prompt == null) {
                return false; // nothing to ask with — refuse rather than trust silently
            }
            String host = address instanceof InetSocketAddress a ? a.getHostString() : String.valueOf(address);
            int port = address instanceof InetSocketAddress a ? a.getPort() : 22;
            // Time how long the human takes so the auth deadline can exclude it (#486): this runs on a MINA I/O
            // thread while the FX dialog blocks it. Mark the start so the deadline check counts the in-progress
            // wait, then fold the total in and clear the marker when the dialog closes.
            long t0 = System.nanoTime();
            promptStartNanos.set(t0);
            try {
                return prompt.confirmUnknownHost(host, port, KeyUtils.getKeyType(key), KeyUtils.getFingerPrint(key));
            } finally {
                promptAccumulatedNanos.addAndGet(System.nanoTime() - t0);
                promptStartNanos.set(0);
            }
        };
    }

    /** The {@code sftp://user@host:port/path} string for a remote Path, by finding its owning connection. */
    private String storableFor(Path path) {
        for (Map.Entry<String, SftpFileSystem> e : byAuthority.entrySet()) {
            if (path.getFileSystem() == e.getValue()) {
                return "sftp://" + e.getKey() + path; // key is user@host:port; path starts with '/'
            }
        }
        return path.toString(); // unknown filesystem (disconnected) — best-effort
    }

    /** Connects (off-thread) and posts the {@link Result} on the FX thread; {@code secret} (a password or
     *  key passphrase) is wiped after use. */
    public void connect(RemoteConnection conn, char[] secret, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                SftpFileSystem fs = open(conn, secret);
                SftpFileSystem prev = byAuthority.put(conn.id(), fs);
                if (prev != null && prev != fs) {
                    closeQuietly(prev); // reconnecting the same site: don't leak the previous filesystem
                }
                String start = conn.lastPath() != null && !conn.lastPath().isBlank() ? conn.lastPath() : ".";
                result = new Result(true, fs.getPath(start), null); // "." resolves to the SFTP home dir
            } catch (Exception e) {
                result = new Result(false, null, message(e));
            } finally {
                if (secret != null) {
                    Arrays.fill(secret, '\0');
                }
            }
            Result posted = result;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    private SftpFileSystem open(RemoteConnection conn, char[] secret) throws IOException {
        // Reset the host-key-dialog clock: the trust-on-first-use prompt runs during the key exchange, which MINA
        // completes as part of the *auth* wait (the connect future is fulfilled at the transport level, before
        // KEX), so the verifier's time accumulates here and the auth wait below discounts it (#486).
        promptAccumulatedNanos.set(0);
        promptStartNanos.set(0);
        ClientSession session = client.connect(conn.user(), conn.host(), conn.port())
                .verify(connectTimeout)
                .getSession();
        try {
            authenticate(session, conn, secret, authTimeout, this::livePromptElapsedNanos);
            // On success the SftpFileSystem owns the session (closing the FS closes it).
            return SftpClientFactory.instance().createSftpFileSystem(session);
        } catch (IOException | RuntimeException e) {
            session.close(true); // failed auth / FS creation: close the session so it isn't leaked
            throw e;
        }
    }

    /**
     * Blocks on {@code auth} up to {@code timeout}, but <b>excludes</b> the time a host-key dialog was on screen
     * (#486): the trust-on-first-use prompt blocks the key exchange that gates authentication, so a slow "compare
     * this fingerprint" answer lands inside this wait and must not count against the deadline. Polls every {@link
     * #POLL_MILLIS} and re-checks the prompt-adjusted deadline; a genuinely stuck server still fails at {@code
     * timeout} because {@code promptElapsedNanos} stays 0 when nobody is asked.
     */
    private static void awaitAuthExcludingPrompt(
            AuthFuture auth, Duration timeout, java.util.function.LongSupplier promptElapsedNanos) throws IOException {
        long start = System.nanoTime();
        while (!auth.await(POLL_MILLIS)) {
            if (handshakeExpired(System.nanoTime() - start, promptElapsedNanos.getAsLong(), timeout)) {
                throw new SocketTimeoutException("Authentication timed out after " + timeout.toSeconds() + "s");
            }
        }
        if (!auth.isSuccess()) {
            Throwable t = auth.getException();
            if (t instanceof IOException io) {
                throw io;
            }
            throw new IOException(t == null ? "Authentication failed" : t.getMessage(), t);
        }
    }

    /** Host-key-dialog time so far this connect — completed prompts plus any dialog still on screen (#486). */
    private long livePromptElapsedNanos() {
        long start = promptStartNanos.get();
        long acc = promptAccumulatedNanos.get();
        return start == 0 ? acc : acc + Math.max(0, System.nanoTime() - start);
    }

    /**
     * Whether a handshake that has been waiting {@code elapsedNanos} has exceeded {@code timeout}, discounting the
     * {@code promptNanos} a host-key dialog was up (#486). Pure/unit-tested.
     */
    static boolean handshakeExpired(long elapsedNanos, long promptNanos, Duration timeout) {
        return elapsedNanos - promptNanos >= timeout.toNanos();
    }

    /**
     * Runs the auth handshake on {@code session} for {@code conn}, using {@code secret} as the password or key
     * passphrase, returning once authenticated.
     *
     * <p>For password auth the password is <b>removed from the session as soon as the handshake finishes</b>.
     * MINA's client API is String-only ({@code addPasswordIdentity(String)} / a String-returning
     * {@code PasswordIdentityProvider}), so a String copy that the caller's {@code char[]} wipe cannot reach
     * is unavoidable at this boundary — but {@code addPasswordIdentity} otherwise keeps that String on the
     * session's identity list for the <em>entire connected session</em>, which can be hours. A password is
     * only needed for the initial handshake (SSH re-keys against the host key, not the password), so removing
     * it in a {@code finally} shrinks its lifetime from "until you disconnect" to "the handshake", after which
     * it is unreachable and eligible for GC. Package-visible so it can be tested against a real SSH server.
     */
    static void authenticate(ClientSession session, RemoteConnection conn, char[] secret, Duration timeout)
            throws IOException {
        authenticate(session, conn, secret, timeout, () -> 0L);
    }

    /**
     * As above, but discounts {@code promptElapsedNanos} — the time a host-key dialog was on screen — from the
     * auth deadline, so a careful "compare this fingerprint" answer doesn't time the handshake out (#486).
     */
    static void authenticate(
            ClientSession session,
            RemoteConnection conn,
            char[] secret,
            Duration timeout,
            java.util.function.LongSupplier promptElapsedNanos)
            throws IOException {
        String passwordIdentity = null;
        try {
            switch (conn.auth()) {
                case PASSWORD -> {
                    if (secret != null) {
                        passwordIdentity = new String(secret);
                        session.addPasswordIdentity(passwordIdentity);
                    }
                }
                case KEY -> session.setKeyIdentityProvider(keyProvider(List.of(Path.of(conn.keyPath())), secret));
                case DEFAULT_KEYS -> {
                    List<Path> keys = defaultKeyFiles();
                    if (!keys.isEmpty()) {
                        session.setKeyIdentityProvider(keyProvider(keys, secret));
                    }
                }
            }
            awaitAuthExcludingPrompt(session.auth(), timeout, promptElapsedNanos);
        } finally {
            if (passwordIdentity != null) {
                session.removePasswordIdentity(passwordIdentity); // don't retain it past the handshake
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static KeyIdentityProvider keyProvider(List<Path> files, char[] passphrase) {
        FileKeyPairProvider provider = new FileKeyPairProvider(files.toArray(Path[]::new));
        if (passphrase != null && passphrase.length > 0) {
            provider.setPasswordFinder(FilePasswordProvider.of(new String(passphrase)));
        }
        return provider;
    }

    /** The user's default private keys in {@code ~/.ssh}, most-modern-first, that actually exist. */
    private static List<Path> defaultKeyFiles() {
        Path ssh = Path.of(System.getProperty("user.home", "."), ".ssh");
        List<Path> out = new ArrayList<>();
        for (String name : List.of("id_ed25519", "id_ecdsa", "id_rsa")) {
            Path key = ssh.resolve(name);
            if (Files.isRegularFile(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** A live {@link Path} for a remote URI, or {@code null} if its connection is not open. */
    public Path pathFor(SftpUri uri) {
        SftpFileSystem fs = uri == null ? null : byAuthority.get(uri.authority());
        return fs == null ? null : fs.getPath(uri.path());
    }

    private Path resolve(String storable) {
        return pathFor(SftpUri.parse(storable));
    }

    public boolean isConnected(String authority) {
        return byAuthority.containsKey(authority);
    }

    public void disconnect(String authority) {
        SftpFileSystem fs = byAuthority.remove(authority);
        close(fs);
    }

    /**
     * Closes every connection + the client. Called when the owning window closes (and on app exit).
     *
     * <p>Also clears the app-wide {@link Vfs} resolver hooks this instance installed in its constructor — they
     * are {@code static}, so without this a closed window's instance (its SSH client, NIO threads, and every
     * open SFTP session) stays strongly reachable for the life of the process and can never be collected.
     * Only clears them if they still point at <em>this</em> instance, so a newer window's hooks aren't
     * unhooked from under it.
     */
    public void shutdown() {
        Vfs.clearRemoteHooksIf(this);
        byAuthority.values().forEach(RemoteFileSystems::close);
        byAuthority.clear();
        client.stop();
        exec.shutdownNow();
    }

    private static void close(SftpFileSystem fs) {
        if (fs != null) {
            try {
                fs.close();
            } catch (IOException ignore) {
                // best-effort on disconnect/shutdown
            }
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
