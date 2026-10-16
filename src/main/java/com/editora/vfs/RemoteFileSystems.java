package com.editora.vfs;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.ExecTarget;
import com.editora.process.ExecTargets;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.client.fs.SftpFileSystem;

/**
 * Owns the SFTP connections (one {@link SftpFileSystem} per {@code user@host:port}) and reconstructs remote
 * paths for {@link Vfs}. A connected filesystem makes {@code Files.read/write/list/walk} work over SFTP, so
 * the rest of the app keeps using {@link Path} unchanged. Connect/auth run off the FX thread (the
 * {@code GitService} idiom: a daemon executor + {@link Platform#runLater} callbacks).
 */
public final class RemoteFileSystems implements Vfs.RemoteProvider, ExecTargets.Resolver {

    /**
     * The result of a connect attempt: {@code root} is the remote start directory (the connection's last path,
     * else the SFTP home) when {@code ok}, else {@code failure} says what went wrong and {@code error} is its
     * underlying message.
     */
    public record Result(boolean ok, Path root, String error, ConnectFailure failure) {}

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration AUTH_TIMEOUT = Duration.ofSeconds(20);
    /** How often the connect wait re-checks the (prompt-excluded) deadline while blocking. */
    private static final long POLL_MILLIS = 200;

    private final SshClient client;
    /** This engine's host-key check: its own {@code known_hosts}, its own window's prompt. */
    private final ServerKeyVerifier serverKeyVerifier;

    private final Duration connectTimeout;
    private final Duration authTimeout;
    /**
     * Time spent answering during the <em>current</em> connect — the host-key question, a password, a 2FA code —
     * which the auth deadline leaves out (#486). Reset per connect; safe because {@link #exec} serializes them.
     */
    private final PromptClock promptClock = new PromptClock();

    /** How this window asks for passwords, passphrases and the server's questions during a login. */
    private final LoginQuestions loginQuestions;

    /** Where this machine's SSH setup is: ~/.ssh's config, known_hosts and default keys. */
    private final SshEnvironment ssh;

    private final SshConfigFile hostConfig;
    private final KeyIdentityProvider defaultKeys;

    private final java.util.concurrent.atomic.AtomicBoolean shutDown = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sftp-connect");
        t.setDaemon(true);
        return t;
    });
    private final List<BiConsumer<String, Boolean>> connectionListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, SftpFileSystem> byAuthority = new ConcurrentHashMap<>();
    /** Where commands for each open connection's paths run; dropped with the connection. */
    private final Map<FileSystem, SshExecTarget> execTargets = new ConcurrentHashMap<>();
    /** Authority identity for closed/replaced filesystems that are still retained by an open buffer Path. */
    private final Map<FileSystem, String> knownAuthorities = Collections.synchronizedMap(new WeakHashMap<>());

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
        this(null, (LoginPrompt) null);
    }

    /**
     * @param prompt asks about a host key not yet in {@code known_hosts}; null rejects unknown hosts outright.
     * @param login asks for what a login needs while it runs; null asks nothing (only a secret given to {@link
     *     #connect} is tried).
     */
    public RemoteFileSystems(HostKeyPrompt prompt, LoginPrompt login) {
        this(prompt, login, SshEnvironment.standard());
    }

    /** As above, with the SSH setup read from {@code ssh} (a test's own folder, in a test). */
    public RemoteFileSystems(HostKeyPrompt prompt, LoginPrompt login, SshEnvironment ssh) {
        this(prompt, login, ssh, null, CONNECT_TIMEOUT, AUTH_TIMEOUT);
    }

    /**
     * As {@link #RemoteFileSystems(HostKeyPrompt, LoginPrompt)} with no login questions, and an explicit {@code
     * known_hosts} (tests). Its folder stands in for {@code ~/.ssh}, so a test's engine never reads the {@code
     * config} or the keys of whoever runs the tests.
     */
    RemoteFileSystems(HostKeyPrompt prompt, Path knownHosts) {
        this(prompt, null, knownHosts, CONNECT_TIMEOUT, AUTH_TIMEOUT);
    }

    /** As above, with explicit connect/auth timeouts — the seam tests use to exercise the deadline quickly. */
    RemoteFileSystems(HostKeyPrompt prompt, Path knownHosts, Duration connectTimeout, Duration authTimeout) {
        this(prompt, null, knownHosts, connectTimeout, authTimeout);
    }

    /** As above, with login questions (tests). */
    RemoteFileSystems(
            HostKeyPrompt prompt, LoginPrompt login, Path knownHosts, Duration connectTimeout, Duration authTimeout) {
        this(
                prompt,
                login,
                SshEnvironment.at(knownHosts.toAbsolutePath().getParent()),
                knownHosts,
                connectTimeout,
                authTimeout);
    }

    /**
     * Every part explicit (tests).
     *
     * @param knownHosts the {@code known_hosts} to check against; null for the one in {@code ssh}
     */
    RemoteFileSystems(
            HostKeyPrompt prompt,
            LoginPrompt login,
            SshEnvironment ssh,
            Path knownHosts,
            Duration connectTimeout,
            Duration authTimeout) {
        this.connectTimeout = connectTimeout;
        this.authTimeout = authTimeout;
        this.ssh = ssh;
        this.loginQuestions = new LoginQuestions(login, promptClock);
        // ~/.ssh/config, as ssh reads it: HostName, Port, User, IdentityFile, IdentitiesOnly, ProxyJump.
        this.hostConfig = new SshConfigFile(ssh.config());
        // The keys ssh tries when nothing names one, for a login that may use them (DEFAULT_KEYS).
        this.defaultKeys = session -> {
            List<Path> keys = this.ssh.defaultKeys();
            if (keys.isEmpty()) {
                return List.of();
            }
            FileKeyPairProvider provider = new FileKeyPairProvider(keys.toArray(Path[]::new));
            provider.setPasswordFinder(Passphrases.INSTANCE);
            return provider.loadKeys(session);
        };
        if (knownHosts == null) {
            knownHosts = ssh.knownHosts();
        }
        // Verify the server's host key against ~/.ssh/known_hosts — the same file ssh itself uses, so a host
        // already accepted at the terminal connects without a prompt, and an entry accepted here is honoured
        // by ssh afterwards. MINA's verifier gives the three outcomes SSH requires: a known host whose key
        // matches connects silently; an UNKNOWN host goes to the trust-on-first-use delegate below and is
        // written to the file only once the user accepts; and a known host presenting a DIFFERENT key is
        // refused outright, without asking. That last case is the man-in-the-middle, and it is the point.
        KnownHostsServerKeyVerifier verifier = new KnownHostsServerKeyVerifier(unknownHostVerifier(prompt), knownHosts);
        verifier.setModifiedServerKeyAcceptor((session, address, entry, expected, actual) -> {
            session.setAttribute(HOST_KEY_REFUSED, ConnectFailure.Kind.HOST_KEY_CHANGED);
            return false; // the man-in-the-middle case: never accepted, never asked about
        });
        serverKeyVerifier = verifier;
        // The client is shared by every window (SshClients), so a logged-in session can move to another one.
        client = SshClients.acquire();
        // Register this window's engine in the Vfs provider registry (one per window). resolve()/storable() only
        // answer for authorities/filesystems this instance owns, so windows never clobber each other (#436).
        Vfs.registerRemoteProvider(this);
        ExecTargets.register(this);
    }

    /**
     * The host that runs commands for {@code path}, when it is on a connection this engine holds open. A path
     * left over from a closed connection has no target: nothing can run for it until it is reconnected.
     */
    @Override
    public ExecTarget forPath(Path path) {
        return path == null ? null : execTargets.get(path.getFileSystem());
    }

    /**
     * Has every session ask the server "are you there?" each {@code interval} ({@code keepalive@openssh.com},
     * what {@code ssh}'s ServerAliveInterval sends), and close it after {@link #HEARTBEAT_MISSES} go
     * unanswered. Two reasons: a connection that only carries a quiet language server is otherwise idle long
     * enough for a NAT or firewall to forget it, and a peer that has vanished is otherwise noticed only when
     * something next tries to use it.
     */
    static void keepAlive(SshClient client, Duration interval) {
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, interval);
        CoreModuleProperties.HEARTBEAT_REQUEST.set(client, "keepalive@openssh.com");
        // And give up on a server that stops answering, as ssh's ServerAliveCountMax does: without a limit a
        // silently dead connection (a network that drops packets rather than refusing them) was never noticed
        // — the window kept a dead folder, and nothing was ended until TCP itself timed out, hours later.
        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(client, HEARTBEAT_MISSES);
    }

    /** Unanswered keep-alives before a connection is taken as dead: about a minute and a half at 30 s. */
    static final int HEARTBEAT_MISSES = 3;

    /** Why this connect's host key was refused, read when the login then fails. */
    private static final org.apache.sshd.common.AttributeRepository.AttributeKey<ConnectFailure.Kind> HOST_KEY_REFUSED =
            new org.apache.sshd.common.AttributeRepository.AttributeKey<>();

    /** How this engine's logins ask their questions (see {@link LoginQuestions#of}). */
    LoginQuestions loginQuestions() {
        return loginQuestions;
    }

    /** The shared client's reading of {@code ~/.ssh/config}, for a connect this engine makes. */
    org.apache.sshd.client.config.hosts.HostConfigEntry resolveHost(
            String host,
            int port,
            java.net.SocketAddress local,
            String user,
            String proxyJump,
            AttributeRepository context)
            throws IOException {
        return hostConfig.resolveEffectiveHost(host, port, local, user, proxyJump, context);
    }

    /** The shared client's default keys, for a session this engine owns. */
    KeyIdentityProvider defaultKeys() {
        return defaultKeys;
    }

    /** Where this engine's SSH setup is (the agent is found through its environment). */
    SshEnvironment sshEnvironment() {
        return ssh;
    }

    /** This engine's answering clock (an agent's signature may wait for a person too). */
    PromptClock promptClock() {
        return promptClock;
    }

    /** How a session logs in: only an Automatic login ({@code DEFAULT_KEYS}) asks the SSH agent. */
    static final AttributeRepository.AttributeKey<RemoteConnection.AuthMethod> AUTH =
            new AttributeRepository.AttributeKey<>();

    /** The shared client's host-key check, for a session this engine owns (see {@link SshClients}). */
    boolean verifyServerKey(ClientSession session, java.net.SocketAddress address, java.security.PublicKey key) {
        return serverKeyVerifier.verifyServerKey(session, address, key);
    }

    /**
     * Registers {@code listener} to be told, on the FX thread, when a connection comes up ({@code true}) and
     * when its session ends ({@code false}) — closed here, dropped by the network, or ended by the server.
     */
    public void addConnectionListener(BiConsumer<String, Boolean> listener) {
        if (listener != null) {
            connectionListeners.add(listener);
        }
    }

    private void notifyConnection(String authority, boolean up) {
        Platform.runLater(() -> connectionListeners.forEach(l -> l.accept(authority, up)));
    }

    /**
     * The delegate {@link KnownHostsServerKeyVerifier} consults for a host it has no entry for. Accepting here
     * is what makes MINA write the entry, so this is the only place trust is established.
     */
    private ServerKeyVerifier unknownHostVerifier(HostKeyPrompt prompt) {
        return (session, address, key) -> {
            if (prompt == null) {
                session.setAttribute(HOST_KEY_REFUSED, ConnectFailure.Kind.HOST_KEY_DECLINED);
                return false; // nothing to ask with — refuse rather than trust silently
            }
            String host = address instanceof InetSocketAddress a ? a.getHostString() : String.valueOf(address);
            int port = address instanceof InetSocketAddress a ? a.getPort() : 22;
            // Timed so the auth deadline can exclude it (#486): this runs on a MINA I/O thread while the FX
            // dialog blocks it.
            boolean accepted = promptClock.timed(() ->
                    prompt.confirmUnknownHost(host, port, KeyUtils.getKeyType(key), KeyUtils.getFingerPrint(key)));
            if (!accepted) {
                session.setAttribute(HOST_KEY_REFUSED, ConnectFailure.Kind.HOST_KEY_DECLINED);
            }
            return accepted;
        };
    }

    /**
     * The {@code sftp://user@host:port/path} string for a Path on a connection <b>this</b> engine owns, else
     * {@code null} (the {@link Vfs.RemoteProvider} contract — the caller tries the next provider). Matching by
     * {@code FileSystem} identity is exact, so in a multi-window session a path is stored by its owning window.
     */
    @Override
    public String storable(Path path) {
        for (Map.Entry<String, SftpFileSystem> e : byAuthority.entrySet()) {
            if (path.getFileSystem() == e.getValue()) {
                return "sftp://" + e.getKey() + remotePath(path);
            }
        }
        String remembered = knownAuthorities.get(path.getFileSystem());
        if (remembered != null) {
            return "sftp://" + remembered + remotePath(path);
        }
        return null; // not one of this engine's filesystems
    }

    private static String remotePath(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        return value.startsWith("/") ? value : "/" + value;
    }

    /** Connects (off-thread) and posts the {@link Result} on the FX thread; {@code secret} (a password or
     *  key passphrase) is wiped after use. */
    public void connect(RemoteConnection conn, char[] secret, Consumer<Result> onResult) {
        exec.submit(() -> {
            Result result;
            try {
                SftpFileSystem fs = open(conn, secret);
                String authority = conn.id();
                fs.getSession().addSessionListener(new SessionListener() {
                    @Override
                    public void sessionClosed(Session session) {
                        // Told to whichever engine holds the session now: it may have moved to another window.
                        RemoteFileSystems owner = SshClients.engineOf(session);
                        if (owner != null) {
                            owner.sessionClosed(authority, fs);
                        }
                    }
                });
                hold(authority, fs, new SshExecTarget(authority, fs));
                String start = conn.lastPath() != null && !conn.lastPath().isBlank() ? conn.lastPath() : ".";
                // Resolved to an absolute path now, while the session is live: "." is the SFTP home directory,
                // and since MINA SSHD 2.17 a relative path can no longer be made absolute once its
                // filesystem has been closed (a reconnect closes the previous one).
                result = new Result(true, fs.getPath(start).toAbsolutePath().normalize(), null, null);
            } catch (Exception e) {
                ConnectFailure failure = ConnectFailure.of(e);
                result = new Result(false, null, failure.detail(), failure);
            } finally {
                if (secret != null) {
                    Arrays.fill(secret, '\0');
                }
            }
            Result posted = result;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** Takes {@code fs} on as this engine's connection to {@code authority}, replacing any earlier one. */
    private void hold(String authority, SftpFileSystem fs, SshExecTarget target) {
        knownAuthorities.put(fs, authority);
        execTargets.put(fs, target);
        notifyConnection(authority, true);
        SftpFileSystem prev = byAuthority.put(authority, fs);
        if (prev != null && prev != fs) {
            execTargets.remove(prev);
            close(prev); // reconnecting the same site: don't leak the previous filesystem or its session
        }
    }

    /** The session under {@code fs} ended — closed here, dropped by the network, or ended by the server. */
    private void sessionClosed(String authority, SftpFileSystem fs) {
        execTargets.remove(fs);
        // A reconnect closes the session it replaces: that is not the connection going down.
        SftpFileSystem current = byAuthority.get(authority);
        if (current == null || current == fs) {
            notifyConnection(authority, false);
        }
    }

    /**
     * A live, logged-in connection on its way from one window's engine to another's: what {@link #handOff}
     * gives up and {@link #adopt} takes on. {@code root} is the folder the receiving window opens.
     */
    public static final class LiveConnection {
        private final String authority;
        private final SftpFileSystem fs;
        private final SshExecTarget target;
        private final Path root;

        private LiveConnection(String authority, SftpFileSystem fs, SshExecTarget target, Path root) {
            this.authority = authority;
            this.fs = fs;
            this.target = target;
            this.root = root;
        }

        /** The connection's {@code user@host:port}. */
        public String authority() {
            return authority;
        }

        /** Ends the connection: for a handover that found nobody to take it. */
        public void close() {
            RemoteFileSystems.close(fs);
        }
    }

    /**
     * Gives up this engine's connection to {@code authority} without closing it, so another window's engine can
     * {@link #adopt} it: the connect's one login then serves the project's own window, where a second login
     * would ask for another password or 2FA code. Null when not connected (or the session has already gone).
     * Listeners hear the connection go down here — this window no longer has it.
     *
     * @param root the folder the receiving window opens, a path on this connection
     */
    public LiveConnection handOff(String authority, Path root) {
        SftpFileSystem fs = byAuthority.remove(authority);
        if (fs == null) {
            return null;
        }
        SshExecTarget target = execTargets.remove(fs);
        knownAuthorities.remove(fs);
        notifyConnection(authority, false);
        ClientSession session = fs.getSession();
        if (target == null || session == null || !session.isOpen()) {
            close(fs);
            return null;
        }
        return new LiveConnection(authority, fs, target, root);
    }

    /**
     * Takes on a connection another window's engine {@linkplain #handOff handed off}: from now on its host-key
     * checks, its listeners and its commands belong to this window. Returns the folder to open; a connection
     * that ended on the way is closed and gives null.
     */
    public Path adopt(LiveConnection live) {
        ClientSession session = live.fs.getSession();
        if (session == null || !session.isOpen()) {
            live.close();
            return null;
        }
        session.setAttribute(SshClients.ENGINE, this);
        hold(live.authority, live.fs, live.target);
        if (!session.isOpen()) { // ended while changing hands, before its close could be told to this engine
            sessionClosed(live.authority, live.fs);
            return null;
        }
        return live.root;
    }

    private SftpFileSystem open(RemoteConnection conn, char[] secret) throws IOException {
        // Reset the answering clock: the trust-on-first-use prompt runs during the key exchange, which MINA
        // completes as part of the *auth* wait (the connect future is fulfilled at the transport level, before
        // KEX), so the verifier's time accumulates here and the auth wait below discounts it (#486), as it does
        // the login's own questions.
        promptClock.reset();
        loginQuestions.reset();
        ClientSession session = client.connect(
                        conn.user(),
                        conn.host(),
                        conn.port(),
                        AttributeRepository.ofKeyValuePair(SshClients.ENGINE, this),
                        null)
                .verify(connectTimeout)
                .getSession();
        try {
            authenticate(session, conn, secret, authTimeout, promptClock::elapsedNanos);
            // On success the SftpFileSystem owns the session (closing the FS closes it).
            return SftpClientFactory.instance().createSftpFileSystem(session);
        } catch (IOException | RuntimeException e) {
            ConnectFailure.Kind refused = session.getAttribute(HOST_KEY_REFUSED); // read first: close clears it
            session.close(true); // failed auth / FS creation: close the session so it isn't leaked
            if (refused != null) {
                throw new ConnectFailure.Signal(refused, message(e), e);
            }
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
            ClientSession session,
            AuthFuture auth,
            Duration timeout,
            java.util.function.LongSupplier promptElapsedNanos)
            throws IOException {
        long start = System.nanoTime();
        while (!auth.await(POLL_MILLIS)) {
            if (handshakeExpired(System.nanoTime() - start, promptElapsedNanos.getAsLong(), timeout)) {
                throw new SocketTimeoutException("Authentication timed out after " + timeout.toSeconds() + "s");
            }
        }
        if (!auth.isSuccess()) {
            Throwable t = auth.getException();
            if (LoginQuestions.cancelled(session)) {
                throw new ConnectFailure.Signal(ConnectFailure.Kind.CANCELLED, "Login cancelled", t);
            }
            if (t instanceof IOException io) {
                throw io;
            }
            throw new IOException(t == null ? "Authentication failed" : t.getMessage(), t);
        }
    }

    /**
     * Whether a handshake that has been waiting {@code elapsedNanos} has exceeded {@code timeout}, discounting the
     * {@code promptNanos} a host-key dialog was up (#486). Pure/unit-tested.
     */
    static boolean handshakeExpired(long elapsedNanos, long promptNanos, Duration timeout) {
        return elapsedNanos - promptNanos >= timeout.toNanos();
    }

    /**
     * Runs the auth handshake on {@code session} for {@code conn}, returning once authenticated. {@code secret},
     * when given, is tried first as the password or the key's passphrase; anything else the login needs is
     * asked for while it runs, through the owning engine's {@link LoginPrompt}.
     *
     * <p>What each method may use, so that choosing one means what it says:
     * <ul>
     *   <li>{@code PASSWORD}: the password and the server's own questions (keyboard-interactive), no keys —
     *       otherwise MINA's default keys were tried first, silently.
     *   <li>{@code KEY}: that key file. A server that wants a 2FA code after the key still gets its questions.
     *   <li>{@code DEFAULT_KEYS}: the default keys in {@code ~/.ssh}, then, as {@code ssh} does, a password or the
     *       server's questions if the server offers those.
     * </ul>
     *
     * <p>A given password is <b>removed from the session as soon as the handshake finishes</b>. MINA's client API
     * is String-only ({@code addPasswordIdentity(String)} / a String-returning {@code PasswordIdentityProvider}),
     * so a String copy that the caller's {@code char[]} wipe cannot reach is unavoidable at this boundary — but
     * {@code addPasswordIdentity} otherwise keeps that String on the session's identity list for the <em>entire
     * connected session</em>, which can be hours. A password is only needed for the initial handshake (SSH
     * re-keys against the host key, not the password), so removing it in a {@code finally} shrinks its lifetime
     * from "until you disconnect" to "the handshake", after which it is unreachable and eligible for GC.
     * Package-visible so it can be tested against a real SSH server.
     */
    static void authenticate(ClientSession session, RemoteConnection conn, char[] secret, Duration timeout)
            throws IOException {
        authenticate(session, conn, secret, timeout, () -> 0L);
    }

    /**
     * As above, but discounts {@code promptElapsedNanos} — the time a person spent answering, the host-key
     * question included — from the auth deadline, so a careful answer doesn't time the handshake out (#486).
     */
    static void authenticate(
            ClientSession session,
            RemoteConnection conn,
            char[] secret,
            Duration timeout,
            java.util.function.LongSupplier promptElapsedNanos)
            throws IOException {
        session.setAttribute(LoginQuestions.SUBJECT, conn.id());
        session.setAttribute(AUTH, conn.auth());
        String passwordIdentity = null;
        try {
            switch (conn.auth()) {
                case PASSWORD -> {
                    session.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
                    if (secret != null && secret.length > 0) {
                        passwordIdentity = new String(secret);
                        session.addPasswordIdentity(passwordIdentity);
                        LoginQuestions.next(session, LoginQuestions.PASSWORDS); // so a prompt after it says "again"
                    }
                }
                case KEY -> {
                    givePassphrase(session, secret);
                    FileKeyPairProvider key = new FileKeyPairProvider(Path.of(conn.keyPath()));
                    key.setPasswordFinder(Passphrases.INSTANCE);
                    session.setKeyIdentityProvider(key);
                }
                // The SSH agent's keys first (AgentFactory), then what MINA put together for the session:
                // ~/.ssh/config's IdentityFile keys, then the default keys (the shared client's, from this
                // engine's ~/.ssh) unless IdentitiesOnly says no — ssh's order.
                case DEFAULT_KEYS -> givePassphrase(session, secret);
            }
            awaitAuthExcludingPrompt(session, session.auth(), timeout, promptElapsedNanos);
        } finally {
            if (passwordIdentity != null) {
                session.removePasswordIdentity(passwordIdentity); // don't retain it past the handshake
            }
            // Nor a passphrase, nor the keys: they are needed only to log in.
            session.removeAttribute(Passphrases.GIVEN);
            session.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        }
    }

    private static void givePassphrase(ClientSession session, char[] secret) {
        if (secret != null && secret.length > 0) {
            session.setAttribute(Passphrases.GIVEN, new String(secret));
        }
    }

    /**
     * What {@code ~/.ssh/config} makes of a host, as a connect to it would: the address it really goes to, its
     * port and user, the jump host on the way, and the keys it names. For the connect form, which shows it.
     */
    public record ConfiguredHost(String hostName, int port, String user, String proxyJump, List<String> identities) {
        public ConfiguredHost {
            identities = List.copyOf(identities);
        }
    }

    /** The single host names {@code ~/.ssh/config} has a {@code Host} block for, in file order (no patterns). */
    public List<String> configuredHosts() {
        String text = hostConfig.text(); // includes in place; empty with no config (the usual case)
        Set<String> names = new java.util.LinkedHashSet<>();
        for (com.editora.sshconfig.SshConfig.Block block : com.editora.sshconfig.SshConfig.parse(text)) {
            if ("Host".equals(block.type())) {
                for (String token : block.argument().strip().split("\\s+")) {
                    if (com.editora.sshconfig.SshConfig.isConcreteHost(token)) {
                        names.add(token);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * What {@code ~/.ssh/config} says about {@code host}, read the way a connect reads it (MINA's resolver, the
     * same path), or null when it says nothing beyond the host itself.
     */
    public ConfiguredHost configured(String host) {
        if (host == null || host.isBlank()) {
            return null;
        }
        org.apache.sshd.client.config.hosts.HostConfigEntry entry;
        try {
            entry = hostConfig.resolveEffectiveHost(host.strip(), 0, null, null, null, null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (entry == null) {
            return null;
        }
        String hostName = entry.getHostName() == null ? host.strip() : entry.getHostName();
        int port = entry.getPort() > 0 ? entry.getPort() : 22;
        List<String> identities = entry.getIdentities() == null ? List.of() : List.copyOf(entry.getIdentities());
        // MINA fills in this machine's user name when the config gives none: the config's own User, from the
        // editor's parser of the same text (first value wins, as ssh), is what the form should fill in.
        String user = com.editora.sshconfig.SshConfig.effective(
                com.editora.sshconfig.SshConfig.parse(hostConfig.text()), host.strip(), "User");
        String jump = entry.getProxyJump() == null || entry.getProxyJump().isBlank() ? null : entry.getProxyJump();
        boolean says =
                !hostName.equals(host.strip()) || port != 22 || user != null || jump != null || !identities.isEmpty();
        return says ? new ConfiguredHost(hostName, port, user, jump, identities) : null;
    }

    /** A live {@link Path} for a remote URI, or {@code null} if its connection is not open. */
    public Path pathFor(SftpUri uri) {
        SftpFileSystem fs = uri == null ? null : byAuthority.get(uri.authority());
        return fs == null ? null : fs.getPath(uri.path());
    }

    /**
     * A live {@link Path} for a remote URI whose authority this engine has connected, else {@code null} (the
     * {@link Vfs.RemoteProvider} contract — an unowned authority falls through to the next registered provider).
     */
    @Override
    public Path resolve(String storable) {
        return pathFor(SftpUri.parse(storable));
    }

    public boolean isConnected(String authority) {
        return byAuthority.containsKey(authority);
    }

    /** Every connection this engine currently holds open ({@code user@host:port}). */
    public Set<String> connectedAuthorities() {
        return Set.copyOf(byAuthority.keySet());
    }

    /**
     * True when {@code path} is on an SFTP filesystem that can no longer be used: it was disconnected, or
     * its SSH session has gone. Asked before a save so a dead connection is reported as one, rather than as
     * whichever exception the first request on it happens to raise.
     */
    public static boolean isDisconnected(Path path) {
        if (path == null || !(path.getFileSystem() instanceof SftpFileSystem fs)) {
            return false;
        }
        try {
            return !fs.isOpen() || fs.getSession() == null || !fs.getSession().isOpen();
        } catch (RuntimeException unknown) {
            return true;
        }
    }

    public void disconnect(String authority) {
        SftpFileSystem fs = byAuthority.remove(authority);
        if (fs != null) {
            execTargets.remove(fs);
        }
        close(fs);
    }

    /**
     * Closes every connection this engine holds, and lets go of the shared client. Called when the owning window closes (and on app exit).
     *
     * <p>Also deregisters this engine from the app-wide {@link Vfs} provider registry — it is {@code static}, so
     * without this a closed window's instance (its SSH client, NIO threads, and every open SFTP session) stays
     * strongly reachable for the life of the process and can never be collected. Only <em>this</em> provider is
     * removed, so every other window's stays live (a closed window no longer strands another's remote paths).
     */
    public void shutdown() {
        if (!shutDown.compareAndSet(false, true)) {
            return; // once: a second release would stop the shared client under another window
        }
        Vfs.unregisterRemoteProvider(this);
        ExecTargets.unregister(this);
        execTargets.clear();
        byAuthority.values().forEach(RemoteFileSystems::close);
        byAuthority.clear();
        SshClients.release(); // the last window's engine stops the shared client
        exec.shutdownNow();
    }

    private static void close(SftpFileSystem fs) {
        if (fs != null) {
            ClientSession session = null;
            try {
                session = fs.getSession();
                fs.close();
            } catch (IOException | RuntimeException ignore) {
                // best-effort on disconnect/shutdown
            }
            // Closing the filesystem ends only its SFTP channel. The SSH session under it carries the commands
            // and forwarded ports as well, and is ours to end: left open, it kept the connection (and
            // anything running over it) alive after "Disconnect".
            if (session != null) {
                session.close(true);
            }
        }
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
    }
}
