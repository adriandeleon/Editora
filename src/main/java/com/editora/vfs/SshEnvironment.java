package com.editora.vfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where this machine's SSH setup lives: the {@code ~/.ssh} folder ({@code config}, {@code known_hosts}, the
 * default keys) and the environment an agent is found through. One value, injected into {@link
 * RemoteFileSystems}, so tests point it at a folder of their own rather than swapping {@code user.home}.
 */
public record SshEnvironment(Path sshDir, Map<String, String> env, boolean windows) {

    public SshEnvironment {
        env = Map.copyOf(env);
    }

    /** This user's: {@code ~/.ssh} and the process environment. */
    public static SshEnvironment standard() {
        return new SshEnvironment(
                Path.of(System.getProperty("user.home", "."), ".ssh"),
                System.getenv(),
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"));
    }

    /** A folder of a test's own, with no environment. */
    public static SshEnvironment at(Path sshDir) {
        return new SshEnvironment(sshDir, Map.of(), false);
    }

    /** {@code known_hosts} — shared with OpenSSH deliberately (see {@link RemoteFileSystems}). */
    public Path knownHosts() {
        return sshDir.resolve("known_hosts");
    }

    /** {@code ~/.ssh/config}, which may not exist. */
    public Path config() {
        return sshDir.resolve("config");
    }

    /** The default private keys that exist, most modern first — those {@code ssh} tries without an IdentityFile. */
    public List<Path> defaultKeys() {
        List<Path> out = new ArrayList<>();
        for (String name : List.of("id_ed25519", "id_ecdsa", "id_rsa")) {
            Path key = sshDir.resolve(name);
            if (Files.isRegularFile(key)) {
                out.add(key);
            }
        }
        return out;
    }
}
