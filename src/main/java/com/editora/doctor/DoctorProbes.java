package com.editora.doctor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.editora.process.ProcessRunner;

/**
 * The Doctor's subprocess/PATH probes — <b>blocking</b>, run only on {@link DoctorService}'s daemon pool,
 * never on the FX thread. Each helper is a single fresh probe (no caching): the Doctor's whole point is to
 * reflect the machine's state <em>now</em>, unlike the per-feature services' cached detects. The decision
 * logic is the pure {@link DoctorRules}; command resolution mirrors {@code ProcessRunner.resolveExecutable}
 * (the same augmented-PATH primitive every feature detect funnels through).
 */
public final class DoctorProbes {

    /** Generous per-probe cap — an npx wrapper cold-run can take several seconds. */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** A presence probe's outcome: whether the tool is there, plus its version line when it printed one. */
    public record Presence(boolean present, String version) {
        public static final Presence ABSENT = new Presence(false, "");
    }

    private DoctorProbes() {}

    /**
     * Probes {@code command} with {@code --version} (presence per {@link DoctorRules#presentFrom}); a
     * multi-token wrapper that rejects the flag gets a {@code --help} second chance, mirroring
     * {@code Mermaid.detect}. The version line is captured only when the run actually succeeded.
     */
    public static Presence version(List<String> command) {
        if (command == null || command.isEmpty()) {
            return Presence.ABSENT;
        }
        boolean wrapper = command.size() > 1;
        ProcessRunner.Result r = runSafe(withArg(command, "--version"));
        if (r != null && DoctorRules.presentFrom(r.exit(), wrapper)) {
            return new Presence(true, r.ok() ? DoctorRules.firstLine(r.out(), r.err()) : "");
        }
        if (wrapper) {
            ProcessRunner.Result h = runSafe(withArg(command, "--help"));
            if (h != null && h.ok()) {
                return new Presence(true, "");
            }
        }
        return Presence.ABSENT;
    }

    /** Runs {@code argv} verbatim (e.g. {@code java -version}, which prints to stderr): present = launched. */
    public static Presence raw(List<String> argv) {
        ProcessRunner.Result r = runSafe(argv);
        if (r == null || r.exit() == -1) {
            return Presence.ABSENT;
        }
        return new Presence(true, DoctorRules.firstLine(r.out(), r.err()));
    }

    /** The raw combined output of {@code argv} ({@code err + out}), or {@code ""} — for version parsing. */
    public static String output(List<String> argv) {
        ProcessRunner.Result r = runSafe(argv);
        return r == null ? "" : r.err() + "\n" + r.out();
    }

    /** Whether {@code argv} runs and exits 0 (e.g. {@code gh auth status}). */
    public static boolean succeeds(List<String> argv) {
        ProcessRunner.Result r = runSafe(argv);
        return r != null && r.ok();
    }

    /**
     * PATH-resolution-only presence (no subprocess) — the same rule as {@code LspManager.available}: an
     * executable path, or a bare name the augmented PATH resolves.
     */
    public static boolean onPath(List<String> command) {
        return !resolvedPath(command).isEmpty();
    }

    /**
     * The resolved absolute executable for {@code command}'s first token ({@code ""} when absent): an
     * explicit path is checked with {@code Files.isExecutable}; a bare name is resolved against the
     * augmented PATH via {@code ProcessRunner.resolveExecutable}.
     */
    public static String resolvedPath(List<String> command) {
        if (command == null || command.isEmpty()) {
            return "";
        }
        String exe = command.get(0);
        if (exe.indexOf('/') >= 0 || exe.indexOf('\\') >= 0) {
            try {
                return Files.isExecutable(Path.of(exe)) ? exe : "";
            } catch (RuntimeException e) {
                return "";
            }
        }
        String resolved = ProcessRunner.resolveExecutable(command).get(0);
        return resolved.equals(exe) ? "" : resolved;
    }

    private static List<String> withArg(List<String> base, String arg) {
        List<String> cmd = new ArrayList<>(base);
        cmd.add(arg);
        return cmd;
    }

    private static ProcessRunner.Result runSafe(List<String> argv) {
        try {
            return ProcessRunner.run(null, TIMEOUT, argv);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
