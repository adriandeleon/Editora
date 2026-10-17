package com.editora.dap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lets Editora know the exit code of a Java program java-debug starts itself (the program console off). java-debug
 * 0.53.2 sends {@code exited} with a hard-coded 0 when the VM dies (bytecode of {@code ConfigurationDoneRequestHandler}),
 * and the program is a child of jdtls, so nothing else can see how it ended.
 *
 * <p>The launch's {@code javaExec} names a small POSIX {@code sh} script in a folder of its own instead of
 * {@code java}. java-debug runs it exactly as it would run {@code java} — {@code <javaExec's parent's parent>/bin/
 * <its name>} with the same arguments ({@code DebugUtility.launch}, {@code AdvancedLaunchingConnector}), which is
 * why the script lives in a {@code bin} folder. The script runs the real {@code java} with {@code "$@"} (the
 * arguments are never re-parsed: spaces, quotes, {@code $}, non-ASCII and an {@code @argfile} pass through as
 * they are), forwards the {@code SIGTERM} with which java-debug ends a program it started ({@code Process.destroy}),
 * and writes the program's exit status to a file next to it.
 *
 * <p>POSIX only. On Windows a {@code .cmd} wrapper would be run through {@code cmd.exe}, which re-reads the
 * arguments ({@code %VAR%}, {@code &}, {@code ^}) and so could change the program's command line; a remote host's
 * adapter cannot run a script written here. Both keep java-debug's {@code exited} and say only that the program
 * ended.
 */
final class JavaExitCodeWrapper {

    private static final Logger LOG = Logger.getLogger(JavaExitCodeWrapper.class.getName());

    /** The folder holding {@code bin/java} and the exit file. */
    private final Path directory;

    private final Path script;
    private final Path exitFile;

    private JavaExitCodeWrapper(Path directory, Path script, Path exitFile) {
        this.directory = directory;
        this.script = script;
        this.exitFile = exitFile;
    }

    /** The script java-debug is to run in place of {@code java}. */
    Path script() {
        return script;
    }

    /** The file the program's exit status is written to when it ends. */
    Path exitFile() {
        return exitFile;
    }

    /** Writes a wrapper around {@code realJava} in a new temporary folder; the caller deletes it ({@link #delete}). */
    static JavaExitCodeWrapper create(String realJava) throws IOException {
        Path dir = Files.createTempDirectory("editora-jexit-");
        try {
            Path bin = Files.createDirectory(dir.resolve("bin"));
            Path exit = dir.resolve("exit-code");
            Path script = bin.resolve("java");
            Files.writeString(script, script(realJava, exit), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
            // Should Editora quit before the session ends: deleted in the reverse order of registration.
            for (Path p : java.util.List.of(dir, bin, script, exit.resolveSibling("exit-code.tmp"), exit)) {
                p.toFile().deleteOnExit();
            }
            return new JavaExitCodeWrapper(dir, script, exit);
        } catch (IOException | RuntimeException e) {
            deleteTree(dir);
            throw e;
        }
    }

    /**
     * The wrapper's text. The program's standard input is the wrapper's (an asynchronous list in a
     * non-interactive {@code sh} gets {@code /dev/null} unless given one, hence {@code 3<&0}). Pure.
     */
    static String script(String realJava, Path exitFile) {
        String exit = quote(exitFile.toString());
        return "#!/bin/sh\n"
                + "# Editora: runs the debugged Java program and records how it exited (java-debug reports 0).\n"
                + "exec 3<&0\n"
                + quote(realJava) + " \"$@\" 0<&3 3<&- &\n"
                + "child=$!\n"
                + "exec 3<&-\n"
                + "trap 'kill -TERM \"$child\" 2>/dev/null' TERM\n"
                + "trap 'kill -INT \"$child\" 2>/dev/null' INT\n"
                + "trap 'kill -HUP \"$child\" 2>/dev/null' HUP\n"
                + "wait \"$child\"\n"
                + "code=$?\n"
                + "while kill -0 \"$child\" 2>/dev/null; do\n"
                + "  wait \"$child\"\n"
                + "  code=$?\n"
                + "done\n"
                + "printf '%s\\n' \"$code\" > " + exit + ".tmp && mv -f " + exit + ".tmp " + exit + "\n"
                + "exit \"$code\"\n";
    }

    /** {@code s} as one single-quoted {@code sh} word: nothing in it is expanded. Pure. */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * The exit status the wrapper recorded, waiting up to {@code wait} for it to appear (java-debug reports the
     * VM's death a moment before the process has ended); null when there is none. Blocking: off the FX thread.
     */
    Integer awaitExitCode(Duration wait) {
        long end = System.nanoTime() + wait.toNanos();
        while (true) {
            Integer code = readExitCode(exitFile);
            if (code != null || System.nanoTime() >= end) {
                return code;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /** The status in {@code file} ({@code "3\n"}), or null when it is missing or not a number. */
    static Integer readExitCode(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Integer.parseInt(
                    Files.readString(file, StandardCharsets.UTF_8).strip());
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** Removes the wrapper's folder (best effort). */
    void delete() {
        deleteTree(directory);
    }

    private static void deleteTree(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "Could not remove " + dir, e);
        }
    }
}
