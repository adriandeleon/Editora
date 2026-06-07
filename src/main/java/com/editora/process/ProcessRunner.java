package com.editora.process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external command via {@link ProcessBuilder}, capturing stdout, stderr, and the exit code
 * with a hard timeout. Both streams are drained concurrently so a chatty command can't deadlock on a
 * full pipe buffer.
 *
 * <p>This is the single place Editora shells out (used by {@code git} integration and the Mermaid
 * CLI). It does no threading itself beyond the stderr drainer, so callers must run it off the JavaFX
 * thread (see {@code GitService} / {@code MermaidService}). {@code LC_ALL=C} is always set so output
 * parses the same regardless of the user's locale; feature-specific env vars go through the
 * {@link #run(Path, Duration, List, Map)} overload.
 */
public final class ProcessRunner {

    /** Outcome of one command: process {@code exit} code plus its captured {@code out}/{@code err}. */
    public record Result(int exit, String out, String err) {
        public boolean ok() {
            return exit == 0;
        }

        /** A human-readable error: stderr if present, else stdout, trimmed. */
        public String message() {
            String e = err == null ? "" : err.strip();
            return e.isEmpty() ? (out == null ? "" : out.strip()) : e;
        }
    }

    private ProcessRunner() {
    }

    /**
     * Runs {@code command} in {@code workingDir} (may be {@code null} for the JVM's cwd), waiting at
     * most {@code timeout}. On timeout the process is destroyed and a non-zero {@link Result} returned.
     */
    public static Result run(Path workingDir, Duration timeout, List<String> command) {
        return run(workingDir, timeout, command, Map.of());
    }

    /**
     * As {@link #run(Path, Duration, List)} but with {@code extraEnv} merged into the child process's
     * environment (on top of the inherited environment + {@code LC_ALL=C}).
     */
    public static Result run(Path workingDir, Duration timeout, List<String> command,
            Map<String, String> extraEnv) {
        // Resolve a bare command name to an absolute path against the augmented PATH: on Unix
        // ProcessBuilder searches the JVM's (stripped, GUI-launched) PATH for the executable, not the
        // child env we set below — so without this, mmdc/npx still wouldn't be found.
        ProcessBuilder pb = new ProcessBuilder(resolveExecutable(command));
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        applyStandardEnv(pb);
        pb.environment().putAll(extraEnv);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Result(-1, "", e.getMessage() == null ? "failed to start" : e.getMessage());
        }
        // Drain stderr on a side thread while we read stdout, so neither pipe can fill and stall.
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread errReader = new Thread(() -> drain(process.getErrorStream(), errBuf), "proc-stderr");
        errReader.setDaemon(true);
        errReader.start();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        drain(process.getInputStream(), outBuf);

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, outBuf.toString(StandardCharsets.UTF_8), "command timed out");
            }
            errReader.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, "", "interrupted");
        }
        return new Result(process.exitValue(),
                outBuf.toString(StandardCharsets.UTF_8),
                errBuf.toString(StandardCharsets.UTF_8));
    }

    /**
     * Applies Editora's standard child-process environment to {@code pb}: {@code LC_ALL=C} for stable
     * parsing plus the {@link #augmentedPath() augmented PATH} so GUI-launched apps (whose inherited PATH
     * omits Homebrew/Node/user-local dirs) can still find tools. Exposed so long-lived processes that
     * can't use {@link #run} — e.g. a stdio language server driven by LSP4J — share the same env logic.
     */
    public static void applyStandardEnv(ProcessBuilder pb) {
        pb.environment().put("LC_ALL", "C");
        pb.environment().put(pathKey(pb), augmentedPath());
    }

    /** Common bin dirs a GUI-launched app's PATH usually lacks (Homebrew, Node installers, user-local). */
    private static final List<String> EXTRA_PATH_DIRS = List.of(
            "/opt/homebrew/bin", "/opt/homebrew/sbin",   // Apple Silicon Homebrew
            "/usr/local/bin", "/usr/local/sbin",          // Intel Homebrew / general
            "/opt/local/bin",                             // MacPorts
            System.getProperty("user.home") + "/.local/bin",
            System.getProperty("user.home") + "/bin",
            System.getProperty("user.home") + "/.volta/bin",
            System.getProperty("user.home") + "/.npm-global/bin");

    private static volatile String cachedPath;

    /** The inherited PATH plus any {@link #EXTRA_PATH_DIRS} that exist and aren't already present. */
    public static String augmentedPath() {
        String cached = cachedPath;
        if (cached != null) {
            return cached;
        }
        List<String> dirs = new ArrayList<>();
        String existing = System.getenv("PATH");
        if (existing != null && !existing.isBlank()) {
            for (String d : existing.split(File.pathSeparator)) {
                if (!d.isBlank()) {
                    dirs.add(d);
                }
            }
        }
        for (String d : EXTRA_PATH_DIRS) {
            if (!dirs.contains(d) && Files.isDirectory(Path.of(d))) {
                dirs.add(d);
            }
        }
        String built = String.join(File.pathSeparator, dirs);
        cachedPath = built;
        return built;
    }

    /** Rewrites a bare command name (e.g. {@code mmdc}) to its absolute path on the augmented PATH, so it
     *  resolves even when the JVM's own PATH (a GUI-launched .app) lacks Homebrew/Node dirs. Leaves the
     *  command unchanged if it's already a path or can't be found (ProcessBuilder may still resolve it). */
    public static List<String> resolveExecutable(List<String> command) {
        if (command.isEmpty()) {
            return command;
        }
        String exe = command.get(0);
        if (exe.isEmpty() || exe.indexOf('/') >= 0 || exe.indexOf('\\') >= 0) {
            return command; // already an absolute/relative path
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        for (String dir : augmentedPath().split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = Path.of(dir, exe);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return rewriteFirst(command, candidate.toString());
            }
            if (windows) {
                for (String ext : List.of(".exe", ".cmd", ".bat")) {
                    Path w = Path.of(dir, exe + ext);
                    if (Files.isRegularFile(w)) {
                        return rewriteFirst(command, w.toString());
                    }
                }
            }
        }
        return command;
    }

    private static List<String> rewriteFirst(List<String> command, String resolved) {
        List<String> out = new ArrayList<>(command);
        out.set(0, resolved);
        return out;
    }

    /** The PATH env key — case-insensitive on Windows, so reuse the existing key if the JVM has one. */
    private static String pathKey(ProcessBuilder pb) {
        for (String key : pb.environment().keySet()) {
            if (key.equalsIgnoreCase("PATH")) {
                return key;
            }
        }
        return "PATH";
    }

    private static void drain(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // Stream closed early (process exited); whatever we captured is good enough.
        }
    }
}
