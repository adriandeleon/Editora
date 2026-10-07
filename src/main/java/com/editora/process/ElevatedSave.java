package com.editora.process;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Pure helpers for saving a file that the user lacks write permission for (e.g. a root-owned file under
 * {@code /etc}) by elevating through the OS's own graphical authentication agent — the password is never
 * seen or handled by Editora; the OS prompts for it.
 *
 * <ul>
 *   <li><strong>Linux</strong> — {@code pkexec} (polkit) pops its password dialog.</li>
 *   <li><strong>macOS</strong> — {@code osascript} runs AppleScript's
 *       {@code do shell script … with administrator privileges}, which shows the native Touch ID /
 *       password prompt.</li>
 * </ul>
 *
 * <p>In both cases the elevated command truncates and rewrites the target <em>in place</em>
 * ({@code cat source > target} run as root), so the file keeps its existing owner and permissions —
 * unlike {@code cp}/{@code mv}, which would replace the inode and reset ownership/mode.
 *
 * <p>Truncating first means a copy that fails part-way (a full disk, the process killed, a power cut)
 * leaves the target empty or partial. The {@linkplain #SCRIPT elevated script} therefore copies the target
 * aside first ({@code <target>.editora-backup}, same folder, same owner and mode, synced), syncs the new
 * bytes, and puts the previous ones back when the copy fails. The backup is removed once it is no longer
 * the only good copy, and is never overwritten by a later save.
 */
public final class ElevatedSave {

    /** The polkit privilege-escalation launcher on Linux (resolved on PATH, like git/rg/mmdc). */
    public static final String PKEXEC = "pkexec";

    /** The macOS AppleScript runner (always present on macOS). */
    public static final String OSASCRIPT = "osascript";

    /** A root-owned shell that pkexec accepts as its program (must be an absolute path). */
    private static final String SHELL = "/bin/sh";

    /** What the backup of an elevated save's target is called: beside it, so it shares its filesystem. */
    public static final String BACKUP_SUFFIX = ".editora-backup";

    private static final String MARKER = "editora-admin-save:";

    /**
     * The shell script run as root: {@code $1} is the source (the new bytes), {@code $2} the target.
     *
     * <ol>
     *   <li>An existing backup is the previous bytes of a save that did not finish: stop, do not overwrite it.
     *   <li>Copy the target aside with {@code cp -p} and sync it; if that fails the target is not touched.
     *   <li>{@code cat source > target}, then sync. On success the backup is removed.
     *   <li>On failure: an untouched target just loses its backup; otherwise the previous bytes are written
     *       back, and if that fails too the backup is kept and named.
     * </ol>
     *
     * Each outcome other than success prints a marker to stderr and exits with its own code (see
     * {@link Failure}). The script holds no single quote, so AppleScript's {@code quoted form of} passes it
     * through unchanged.
     */
    public static final String SCRIPT = String.join(
            "\n",
            "b=\"$2" + BACKUP_SUFFIX + "\"",
            "flush() { sync \"$1\" 2>/dev/null || sync; }",
            "if [ -e \"$b\" ] || [ -L \"$b\" ]; then echo \"" + MARKER + "stale-backup\" >&2; exit 74; fi",
            "if [ -e \"$2\" ]; then",
            "  if ! { cp -p \"$2\" \"$b\" && flush \"$b\"; }; then",
            "    rm -f \"$b\"; echo \"" + MARKER + "no-backup\" >&2; exit 71",
            "  fi",
            "fi",
            "if cat \"$1\" > \"$2\" && flush \"$2\"; then rm -f \"$b\"; exit 0; fi",
            "if [ ! -e \"$b\" ]; then exit 1; fi",
            "if cmp -s \"$b\" \"$2\"; then rm -f \"$b\"; exit 1; fi",
            "if cat \"$b\" > \"$2\" && flush \"$2\"; then",
            "  rm -f \"$b\"; echo \"" + MARKER + "restored\" >&2; exit 72",
            "fi",
            "echo \"" + MARKER + "backup-kept\" >&2; exit 73");

    /** How an elevated save that did not succeed (and was not cancelled) left the target. */
    public enum Failure {
        /** The copy failed after the target was changed; its previous bytes were written back. */
        RESTORED("restored", 72),
        /** The copy failed and the previous bytes could not be written back: they are in the backup file. */
        BACKUP_KEPT("backup-kept", 73),
        /** The target could not be copied aside first, so it was left alone. */
        NO_BACKUP("no-backup", 71),
        /** A backup from an earlier, unfinished save is still there; nothing was written. */
        STALE_BACKUP("stale-backup", 74),
        /** Anything else: the target is as it was (or was newly created and is incomplete). */
        OTHER(null, -1);

        private final String marker;
        private final int exit;

        Failure(String marker, int exit) {
            this.marker = marker;
            this.exit = exit;
        }
    }

    private ElevatedSave() {}

    /** Where the elevated script keeps {@code target}'s previous bytes while it rewrites it. */
    public static Path backupOf(Path target) {
        return target.resolveSibling(target.getFileName() + BACKUP_SUFFIX);
    }

    /**
     * Reads the script's outcome from its stderr marker — the only thing {@code osascript} passes on, since
     * it exits 1 for every failed shell command — or, without one, from the exit code {@code pkexec} relays.
     */
    public static Failure failureOf(int exit, String stderr) {
        String text = stderr == null ? "" : stderr;
        for (Failure failure : Failure.values()) {
            if (failure.marker != null && text.contains(MARKER + failure.marker)) {
                return failure;
            }
        }
        for (Failure failure : Failure.values()) {
            if (failure.exit == exit) {
                return failure;
            }
        }
        return Failure.OTHER;
    }

    /** {@code stderr} without the script's marker lines: what the failing command itself said. */
    public static String reason(String stderr) {
        if (stderr == null) {
            return "";
        }
        return stderr.lines()
                .filter(line -> !line.contains(MARKER))
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    /** True where a graphical elevation path exists — Linux (pkexec) or macOS (osascript); Windows: no. */
    public static boolean supportedOnOs(String osName) {
        return isLinux(osName) || isMac(osName);
    }

    public static boolean isLinux(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    public static boolean isMac(String osName) {
        if (osName == null) {
            return false;
        }
        String o = osName.toLowerCase(Locale.ROOT);
        return o.contains("mac") || o.contains("darwin");
    }

    /**
     * The elevated argv for {@code osName}: macOS → {@link #osascriptArgv}, Linux → {@link #pkexecArgv},
     * else {@code null} (unsupported OS).
     */
    public static List<String> elevatedArgv(String osName, String pkexec, Path source, Path target) {
        if (isMac(osName)) {
            return osascriptArgv(source, target);
        }
        if (isLinux(osName)) {
            return pkexecArgv(pkexec, source, target);
        }
        return null;
    }

    /**
     * Linux argv to copy {@code source} → {@code target} as root, preserving the target's owner/mode.
     * The shell runs {@link #SCRIPT} with {@code $1} (source) and {@code $2} (target); passing the paths as
     * positional arguments (not interpolated into the script) keeps them safe from shell metacharacters.
     */
    public static List<String> pkexecArgv(String pkexec, Path source, Path target) {
        String exe = pkexec == null || pkexec.isBlank() ? PKEXEC : pkexec;
        return List.of(
                exe,
                SHELL,
                "-c",
                SCRIPT,
                "editora-admin-save", // $0 (a label for the shell), not used by the script
                source.toString(), // $1
                target.toString()); // $2
    }

    /**
     * macOS argv running AppleScript's {@code do shell script … with administrator privileges} (native
     * auth prompt). The script and the two paths are passed as {@code osascript} argv and shell-escaped
     * inside AppleScript via {@code quoted form of}, so they are never interpolated into either the
     * AppleScript or the shell string — safe from metacharacters in the path.
     */
    public static List<String> osascriptArgv(Path source, Path target) {
        return List.of(
                OSASCRIPT,
                "-e",
                "on run argv",
                "-e",
                "do shell script \"" + SHELL + " -c \" & quoted form of (item 1 of argv)"
                        + " & \" editora-admin-save \" & quoted form of (item 2 of argv)"
                        + " & \" \" & quoted form of (item 3 of argv) with administrator privileges",
                "-e",
                "end run",
                SCRIPT,
                source.toString(),
                target.toString());
    }

    /**
     * Whether an elevated-save failure was the user cancelling the auth prompt (vs. a real error): on
     * macOS {@code osascript} exits non-zero with {@code -128}/"User canceled" in stderr; on Linux
     * {@code pkexec} returns 126 (dismissed / not authorized).
     */
    public static boolean isCancellation(String osName, int exit, String stderr) {
        if (isMac(osName)) {
            String e = stderr == null ? "" : stderr.toLowerCase(Locale.ROOT);
            return e.contains("-128") || e.contains("user canceled") || e.contains("user cancelled");
        }
        return exit == 126;
    }
}
