package com.editora.vfs;

import java.io.IOException;
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
import java.util.function.Consumer;
import javafx.application.Platform;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.FilePasswordProvider;
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

    private final SshClient client;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sftp-connect");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, SftpFileSystem> byAuthority = new ConcurrentHashMap<>();

    public RemoteFileSystems() {
        client = SshClient.setUpDefaultClient();
        // Trust the server key on first connect (the editor has no known_hosts/TOFU prompt UI yet); a
        // proper known_hosts check is a deferred hardening. Without this, MINA rejects unknown host keys.
        client.setServerKeyVerifier(org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        Vfs.setRemoteResolver(this::resolve); // remote sftp:// strings → live Paths
        Vfs.setRemoteStorable(this::storableFor); // remote Path → sftp:// string (Path.toUri() throws for SFTP)
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
                byAuthority.put(conn.id(), fs);
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
        ClientSession session = client.connect(conn.user(), conn.host(), conn.port())
                .verify(CONNECT_TIMEOUT)
                .getSession();
        switch (conn.auth()) {
            case PASSWORD -> {
                if (secret != null) {
                    session.addPasswordIdentity(new String(secret));
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
        session.auth().verify(AUTH_TIMEOUT);
        return SftpClientFactory.instance().createSftpFileSystem(session);
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

    /** Closes every connection + the client (called on app exit). */
    public void shutdown() {
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
