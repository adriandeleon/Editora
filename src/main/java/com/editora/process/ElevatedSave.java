package com.editora.process;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Pure helpers for saving a file that the user lacks write permission for (e.g. a root-owned file under
 * {@code /etc}) by elevating through the OS's own graphical authentication agent. On Linux that is
 * <strong>{@code pkexec}</strong> (polkit), which pops its own password dialog — Editora never sees or
 * handles the password; it only hands the write off to the elevated helper.
 *
 * <p>The elevated command truncates and rewrites the target <em>in place</em> ({@code cat tmp > target}
 * run as root), so the file keeps its existing owner and permissions — unlike {@code cp}/{@code mv},
 * which would replace the inode and reset ownership/mode.
 */
public final class ElevatedSave {

    /** The polkit privilege-escalation launcher (resolved on PATH, like git/rg/mmdc). */
    public static final String PKEXEC = "pkexec";

    /** A root-owned shell that pkexec accepts as its program (must be an absolute path). */
    private static final String SHELL = "/bin/sh";

    private ElevatedSave() {}

    /** True on Linux, where {@code pkexec}/polkit is the supported elevation path (macOS/Windows: no). */
    public static boolean supportedOnOs(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("linux");
    }

    /**
     * The argv to copy {@code source} → {@code target} as root, preserving the target's owner/mode.
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
}
