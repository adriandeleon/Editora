package com.editora.doctor;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

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
        return version(command, "--version");
    }

    /**
     * As {@link #version(List)}, asking with {@code versionArg} — for the tools that reject {@code --version} and
     * print their version only for their own flag ({@code dot -V}, {@code plantuml -version}).
     */
    public static Presence version(List<String> command, String versionArg) {
        if (command == null || command.isEmpty()) {
            return Presence.ABSENT;
        }
        boolean wrapper = command.size() > 1;
        ProcessRunner.Result r = runSafe(withArg(command, versionArg));
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
        return succeeds(argv, Map.of());
    }

    /** As {@link #succeeds(List)} with {@code extraEnv} merged into the child's environment. */
    public static boolean succeeds(List<String> argv, Map<String, String> extraEnv) {
        ProcessRunner.Result r = runSafe(argv, extraEnv);
        return r != null && r.ok();
    }

    /**
     * The environment that puts {@code dir} ahead of any inherited {@code PYTHONPATH} — what the debugger
     * does with a located debugpy bundle ({@code AdapterDetection.prependPythonPath}); empty for a null dir.
     */
    public static Map<String, String> pythonPathWith(Path dir) {
        if (dir == null) {
            return Map.of();
        }
        String existing = System.getenv().getOrDefault("PYTHONPATH", "");
        return Map.of("PYTHONPATH", existing.isEmpty() ? dir.toString() : dir + File.pathSeparator + existing);
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
            return Boolean.TRUE.equals(EXECUTABLE.get(exe)) ? exe : "";
        }
        String resolved = ProcessRunner.resolveExecutable(command).get(0);
        return resolved.equals(exe) ? "" : resolved;
    }

    /**
     * Whether a configured path is an executable file: looked at off the FX thread where a status row's check asks
     * ({@link #lookAt}), answered from memory on it (PERF-01, Pauta 6.2.0).
     */
    private static final com.editora.perf.Looked<String, Boolean> EXECUTABLE = new com.editora.perf.Looked<>(
            "DoctorProbes.resolvedPath",
            exe -> {
                try {
                    return Files.isExecutable(Path.of(exe));
                } catch (RuntimeException e) {
                    return false;
                }
            },
            Boolean.TRUE); // a path never looked at counts as there until it has been: no offer to install meanwhile

    /** Whether {@link #resolvedPath} of {@code command} answers on the FX thread from a look made off it. */
    public static boolean known(List<String> command) {
        if (command == null || command.isEmpty()) {
            return true;
        }
        String exe = command.get(0);
        return exe.indexOf('/') >= 0 || exe.indexOf('\\') >= 0 ? EXECUTABLE.knows(exe) : ProcessRunner.onPathKnown(exe);
    }
    /**
     * Looks at what {@link #resolvedPath} asks for {@code command} now, on this thread (off the FX thread), so the
     * FX thread's next ask answers from memory.
     */
    public static String lookAt(List<String> command) {
        return resolvedPath(command); // off the FX thread, Looked and the PATH lookup look now and keep the answer
    }

    private static List<String> withArg(List<String> base, String arg) {
        List<String> cmd = new ArrayList<>(base);
        cmd.add(arg);
        return cmd;
    }

    private static ProcessRunner.@Nullable Result runSafe(List<String> argv) {
        return runSafe(argv, Map.of());
    }

    private static ProcessRunner.@Nullable Result runSafe(List<String> argv, Map<String, String> extraEnv) {
        try {
            return ProcessRunner.run(null, TIMEOUT, argv, extraEnv);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
