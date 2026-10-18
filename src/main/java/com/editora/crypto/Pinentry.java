package com.editora.crypto;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import com.editora.process.ProcessRunner;

/**
 * Which passphrase dialog gpg-agent would show, and whether it can show one to an app with no terminal. A
 * GUI-launched Editora has no TTY, so a terminal pinentry ({@code pinentry-curses}, {@code pinentry-tty}) cannot
 * ask, and every decryption would fail; Editora then asks itself. Blocking (runs {@code gpgconf}).
 */
public final class Pinentry {

    private Pinentry() {}

    /** What the check found: the program's file name, and whether it can ask without a terminal. */
    public record Verdict(String program, boolean graphical, boolean found) {}

    /**
     * Finds the pinentry gpg-agent uses — {@code pinentry-program} in {@code gpg-agent.conf} in the GnuPG home
     * {@code gpgconf} reports (beside {@code gpgExecutable}), else {@code pinentry} on PATH — and judges it.
     */
    public static Verdict check(String gpgExecutable, java.util.Map<String, String> env) {
        String gpgconf = gpgconfBeside(gpgExecutable);
        ProcessRunner.Result r =
                ProcessRunner.run(null, Duration.ofSeconds(15), List.of(gpgconf, "--list-dirs", "homedir"), env);
        String home = r.ok() ? GpgStatus.unescapePercent(r.out().strip()) : "";
        String configured = "";
        if (!home.isEmpty()) {
            try {
                Path conf = Path.of(home, "gpg-agent.conf");
                if (Files.isRegularFile(conf)) {
                    configured = configuredProgram(Files.readString(conf));
                }
            } catch (IOException | RuntimeException e) {
                configured = "";
            }
        }
        String program = configured.isEmpty()
                ? ProcessRunner.resolveExecutable(List.of("pinentry")).get(0)
                : configured;
        Path real = realPath(program);
        if (real == null) {
            return new Verdict(program, false, false);
        }
        String name = real.getFileName().toString();
        return new Verdict(name, graphical(name), true);
    }

    /** {@code gpgconf} in the folder {@code gpg} is in, or the bare name. */
    static String gpgconfBeside(String gpgExecutable) {
        boolean windows =
                gpgExecutable != null && gpgExecutable.toLowerCase(Locale.ROOT).endsWith(".exe");
        if (gpgExecutable != null && (gpgExecutable.contains("/") || gpgExecutable.contains("\\"))) {
            Path dir = Path.of(gpgExecutable).getParent();
            if (dir != null) {
                Path beside = dir.resolve(windows ? "gpgconf.exe" : "gpgconf");
                if (Files.isRegularFile(beside)) {
                    return beside.toString();
                }
            }
        }
        return windows ? "gpgconf.exe" : "gpgconf";
    }

    /** The {@code pinentry-program} in a {@code gpg-agent.conf} text, or an empty string when it is not set. */
    static String configuredProgram(String conf) {
        String found = "";
        for (String raw : conf.split("\r?\n")) {
            String line = raw.strip();
            if (line.startsWith("pinentry-program")
                    && line.length() > "pinentry-program".length()
                    && Character.isWhitespace(line.charAt("pinentry-program".length()))) {
                String value = line.substring("pinentry-program".length()).strip();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                found = value; // the last one wins, as gpg-agent reads it
            }
        }
        return found;
    }

    /** Whether a pinentry with this file name opens a window (and so works without a terminal). */
    static boolean graphical(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return !(n.contains("curses") || n.contains("tty"));
    }

    private static Path realPath(String program) {
        try {
            Path p = Path.of(program);
            if (!p.isAbsolute() || !Files.exists(p)) {
                return null;
            }
            return p.toRealPath();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
