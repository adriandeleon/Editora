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
 */
public final class ElevatedSave {

    /** The polkit privilege-escalation launcher on Linux (resolved on PATH, like git/rg/mmdc). */
    public static final String PKEXEC = "pkexec";

    /** The macOS AppleScript runner (always present on macOS). */
    public static final String OSASCRIPT = "osascript";

    /** A root-owned shell that pkexec accepts as its program (must be an absolute path). */
    private static final String SHELL = "/bin/sh";

    private ElevatedSave() {}

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
     * The shell reads {@code $1} (source) and redirects into {@code $2} (target); passing the paths as
     * positional arguments (not interpolated into the script) keeps them safe from shell metacharacters.
     */
    public static List<String> pkexecArgv(String pkexec, Path source, Path target) {
        String exe = pkexec == null || pkexec.isBlank() ? PKEXEC : pkexec;
        return List.of(
                exe,
                SHELL,
                "-c",
                "cat \"$1\" > \"$2\"",
                "editora-admin-save", // $0 (a label for the shell), not used by the script
                source.toString(), // $1
                target.toString()); // $2
    }

    /**
     * macOS argv running AppleScript's {@code do shell script … with administrator privileges} (native
     * auth prompt). The two paths are passed as {@code osascript} argv and shell-escaped inside AppleScript
     * via {@code quoted form of}, so they are never interpolated into either the AppleScript or the shell
     * string — safe from metacharacters in the path.
     */
    public static List<String> osascriptArgv(Path source, Path target) {
        return List.of(
                OSASCRIPT,
                "-e",
                "on run argv",
                "-e",
                "do shell script \"cat \" & quoted form of (item 1 of argv) & \" > \""
                        + " & quoted form of (item 2 of argv) with administrator privileges",
                "-e",
                "end run",
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
