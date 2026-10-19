package com.editora.run;

import java.util.Locale;

/**
 * Turns "the program is not installed" into a message that says what to do. A launch of a missing {@code npm},
 * {@code pnpm} or {@code node} used to show the JDK's own words — {@code Cannot run program "pnpm" (in
 * directory …): Exec failed, error: 2 (No such file or directory)} — with nothing about why that program was
 * chosen or how to get it. Pure.
 */
public final class MissingProgram {

    private MissingProgram() {}

    /** Whether a launch error means the executable does not exist (ENOENT, or Windows' "cannot find the file"). */
    public static boolean notFound(String error) {
        if (error == null) {
            return false;
        }
        String e = error.toLowerCase(Locale.ROOT);
        return e.contains("error=2,")
                || e.contains("error: 2 ")
                || e.contains("error=2 ")
                || e.contains("no such file or directory")
                || e.contains("cannot find the file");
    }

    /** The program's base name ({@code /usr/bin/pnpm.cmd} → {@code pnpm}). */
    public static String baseName(String executable) {
        if (executable == null) {
            return "";
        }
        String s = executable.replace('\\', '/');
        return s.substring(s.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT).replaceFirst("\\.(exe|cmd|bat)$", "");
    }

    /**
     * The message key and its arguments for a missing {@code executable}, or null when it is not one this knows
     * about (the caller keeps the JDK's message).
     *
     * @param reason what chose a package manager ({@code pnpm-lock.yaml}), or "" / null
     */
    public static Message forProgram(String executable, String reason) {
        String name = baseName(executable);
        return switch (name) {
            case "node", "nodejs" -> new Message("status.run.nodeMissing", name);
            case "npm", "npx" -> new Message("status.run.npmMissing", name);
            case "pnpm", "yarn" ->
                reason == null || reason.isBlank()
                        ? new Message("status.run.managerMissing", name)
                        : new Message("status.run.managerMissingFrom", name, reason);
            case "bun", "bunx" -> new Message("status.run.bunMissing", name);
            default -> null;
        };
    }

    /** A message key and its arguments. */
    public record Message(String key, Object... args) {}
}
