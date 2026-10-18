package com.editora.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.editora.process.ProcessRunner;

/**
 * A throwaway GnuPG home for tests: its own keyring and gpg-agent, keys without passphrases, loopback allowed.
 * Made under {@code /tmp} where there is one — gpg-agent's socket path has a length limit that a macOS temporary
 * folder exceeds. {@link #close} stops the agent and deletes the home.
 */
public final class GpgTestHome implements AutoCloseable {

    public final Path home;
    public final String gpg;

    private GpgTestHome(Path home, String gpg) {
        this.home = home;
        this.gpg = gpg;
    }

    /** Whether a gpg runs here at all; tests that need one assume it. */
    public static boolean available() {
        return new Gpg(Gpg.resolve(""), Map.of()).version() != null;
    }

    public static GpgTestHome create() throws IOException {
        Path tmp = Path.of("/tmp");
        Path home = Files.isDirectory(tmp) && Files.isWritable(tmp)
                ? Files.createTempDirectory(tmp, "eg")
                : Files.createTempDirectory("eg");
        try {
            Files.setPosixFilePermissions(home, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException windows) {
            // no POSIX permissions: gpg only warns
        }
        Files.writeString(home.resolve("gpg-agent.conf"), "allow-loopback-pinentry\n");
        return new GpgTestHome(home, Gpg.resolve(""));
    }

    public Map<String, String> env() {
        return Map.of("GNUPGHOME", home.toString());
    }

    public Gpg gpg() {
        return new Gpg(gpg, env());
    }

    /** Generates a key for {@code uid} with no passphrase; returns its fingerprint. */
    public String newKey(String uid) {
        run("--pinentry-mode", "loopback", "--passphrase", "", "--quick-gen-key", uid, "default", "default", "never");
        for (GpgKey k : gpg().keys()) {
            if (k.primaryUserId().equals(uid)) {
                return k.fingerprint();
            }
        }
        throw new IllegalStateException("no key made for " + uid);
    }

    /** Deletes the secret half of {@code fingerprint}, leaving its public key: someone else's key. */
    public void dropSecretKey(String fingerprint) {
        run("--yes", "--delete-secret-keys", fingerprint);
    }

    /** The armored public key of {@code fingerprint}. */
    public byte[] exportKey(String fingerprint) {
        return ProcessRunner.runBytesWithInput(
                        null,
                        Duration.ofSeconds(30),
                        List.of(gpg, "--batch", "--armor", "--export", fingerprint),
                        env(),
                        null,
                        false,
                        null)
                .out();
    }

    private void run(String... args) {
        List<String> cmd = new ArrayList<>(List.of(gpg, "--batch"));
        cmd.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(null, Duration.ofSeconds(60), cmd, env());
        if (!r.ok()) {
            throw new IllegalStateException("gpg " + String.join(" ", args) + ": " + r.message());
        }
    }

    @Override
    public void close() throws IOException {
        String gpgconf = Pinentry.gpgconfBeside(gpg);
        ProcessRunner.run(null, Duration.ofSeconds(15), List.of(gpgconf, "--kill", "all"), env());
        try (Stream<Path> walk = Files.walk(home)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
            // a socket the agent was still removing: the next run's /tmp clean-up gets it
        }
    }
}
