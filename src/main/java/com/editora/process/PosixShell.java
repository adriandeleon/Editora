package com.editora.process;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns an {@link ExecSpec} into the one string a POSIX host is asked to run. SSH has no argv: the server
 * hands a single command line to the account's login shell. So the line is built to be parsed exactly once,
 * and only as far as {@code exec /bin/sh -c '<script>' editora '<arg>'…}. From there the arguments are
 * {@code sh}'s positional parameters and reach the program through {@code exec "$@"} without being parsed
 * again, whatever they contain.
 *
 * <p>Everything is single-quoted, which bash, zsh, dash and ksh read the same way. Shells that do not (fish
 * treats a backslash inside single quotes as an escape; csh has its own rules) are not supported targets.
 */
public final class PosixShell {

    /**
     * Starts the line a command writes to stderr before anything else: this marker, the process id, a newline.
     * The reader drops whatever a login shell's startup files printed ahead of it.
     */
    public static final String PID_MARK = "\u001eEDITORA ";

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private PosixShell() {}

    /** {@code text} as one shell word. */
    public static String quote(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    /**
     * The command line for {@code spec}.
     *
     * @param cwd the working directory as the target names it, or {@code null} to stay where the shell starts
     * @param path the target's {@code PATH}, or {@code null} to keep the one the shell starts with
     * @param userLocale the locale variables a {@link ExecSpec.Locale#USER} child gets (a command started
     *     over SSH usually has none of its own); ignored for a parse-stable one
     * @throws IllegalArgumentException if a variable name is not a valid shell identifier
     */
    public static String commandLine(ExecSpec spec, String cwd, String path, Map<String, String> userLocale) {
        StringBuilder line = new StringBuilder("exec /bin/sh -c ")
                .append(quote(script(spec, cwd, path, userLocale)))
                .append(" editora");
        for (String arg : spec.argv()) {
            line.append(' ').append(quote(arg));
        }
        return line.toString();
    }

    /** The {@code sh} script that sets the child up and then becomes it, so the pid it printed stays true. */
    static String script(ExecSpec spec, String cwd, String path, Map<String, String> userLocale) {
        StringBuilder script = new StringBuilder("printf '\\036EDITORA %s\\n' \"$$\" >&2\n");
        if (cwd != null) {
            script.append("cd -- ").append(quote(cwd)).append(" || exit 126\n");
        }
        // The same order as a local child's environment: PATH, the locale, then the caller's own variables.
        if (path != null) {
            export(script, "PATH", path);
        }
        if (spec.locale() == ExecSpec.Locale.PARSE_STABLE) {
            export(script, "LC_ALL", "C");
        } else if (userLocale != null) {
            userLocale.forEach((name, value) -> export(script, name, value));
        }
        spec.env().forEach((name, value) -> export(script, name, value));
        if (spec.discardStdout()) {
            script.append("exec >/dev/null\n");
        }
        if (spec.discardStderr()) {
            script.append("exec 2>/dev/null\n");
        }
        return script.append("exec \"$@\"\n").toString();
    }

    private static void export(StringBuilder script, String name, String value) {
        if (name == null || !VARIABLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("not a shell variable name: " + name);
        }
        if (value == null) {
            script.append("unset ").append(name).append('\n');
        } else {
            script.append(name)
                    .append('=')
                    .append(quote(value))
                    .append("; export ")
                    .append(name)
                    .append('\n');
        }
    }
}
