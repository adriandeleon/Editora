package com.editora.lsp;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;

/**
 * The JDKs a remote host has, for the jdtls that runs there: the same answer {@link JavaRuntimes#discover}
 * gives for this machine, found with one {@code sh} command on the host instead of a directory walk over
 * SFTP (dozens of round trips). It looks where {@code JavaRuntimes} looks on a POSIX machine, plus the
 * host's configured {@code jdk.home}, its {@code JAVA_HOME} and the {@code javac} on its {@code PATH}; each
 * candidate must have a compiler and a {@code release} file, and is reported by its real path.
 */
final class RemoteJavaRuntimes {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * Prints {@code <real path>\t<JAVA_VERSION line>} per JDK. {@code $1} is the host's configured
     * {@code jdk.home} (may be empty).
     */
    static final String SCRIPT = """
            onpath=
            if j=$(command -v javac 2>/dev/null); then
              j=$(readlink -f "$j" 2>/dev/null || printf '%s' "$j")
              onpath=${j%/bin/javac}
            fi
            for d in "$1" "$JAVA_HOME" "$onpath" \\
                "$HOME"/.sdkman/candidates/java/* "$HOME"/.asdf/installs/java/* \\
                "$HOME"/.local/share/mise/installs/java/* "$HOME"/.jabba/jdk/* "$HOME"/.jdks/* \\
                /usr/lib/jvm/* /usr/java/* /Library/Java/JavaVirtualMachines/*/Contents/Home \\
                /opt/homebrew/opt/*/libexec/openjdk.jdk/Contents/Home /usr/local/opt/*/libexec/openjdk.jdk/Contents/Home
            do
              [ -n "$d" ] && [ -f "$d/bin/javac" ] && [ -f "$d/release" ] || continue
              real=$(cd "$d" 2>/dev/null && pwd -P) || continue
              version=$(grep '^JAVA_VERSION=' "$d/release" 2>/dev/null | head -n 1)
              [ -n "$version" ] && printf '%s\\t%s\\n' "$real" "$version"
            done
            exit 0
            """;

    private RemoteJavaRuntimes() {}

    /** The JDKs on the host of remote {@code root}; empty when it cannot be asked. Runs a command: off the FX thread. */
    static List<JavaRuntimes.Jdk> discover(Path root) {
        String jdkHome = ExecTargets.remoteTool(root, "jdk.home");
        ProcessRunner.Result result = ProcessRunner.run(
                root, TIMEOUT, List.of("sh", "-c", SCRIPT, "sh", jdkHome == null ? "" : jdkHome.strip()));
        return result.ok() ? parse(result.out()) : List.of();
    }

    /** Reads {@link #SCRIPT}'s output; a line it cannot read is skipped. Pure. */
    static List<JavaRuntimes.Jdk> parse(String output) {
        List<JavaRuntimes.Jdk> out = new ArrayList<>();
        if (output == null) {
            return out;
        }
        for (String line : output.split("\n")) {
            int tab = line.indexOf('\t');
            if (tab <= 0) {
                continue;
            }
            String path = line.substring(0, tab);
            int major = JavaRuntimes.majorFromRelease(line.substring(tab + 1));
            if (major > 0 && path.startsWith("/")) {
                out.add(new JavaRuntimes.Jdk(major, path));
            }
        }
        return List.copyOf(out);
    }
}
