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
 *
 * <p>Stopping a command means signalling its process group, so the command must lead one. OpenSSH starts a
 * command without a terminal in a session of its own; dropbear leaves it in the group of its per-connection
 * process, where a group signal could not reach it alone. So a command that finds itself not leading a group
 * re-runs under {@code setsid}, which keeps its pid ({@link #GROUP_GUARD}).
 */
public final class PosixShell {

    /**
     * Starts the line a command writes to stderr before anything else: this marker, the process id, a newline.
     * The reader drops whatever a login shell's startup files printed ahead of it.
     */
    public static final String PID_MARK = "\u001eEDITORA ";

    /**
     * Ends a long-lived child when the connection that started it goes away. An SSH server runs a command
     * without a terminal in a process group the command leads ({@link #GROUP_GUARD}), and exits its per-connection process when the
     * client disappears, but it signals nobody: the command is re-parented and runs on. This subshell waits
     * for that parent ({@code $PPID}) to die, then stops the whole group, politely and then for good. It
     * leaves by itself when the command ends first, and its streams are detached so it never holds the
     * channel open. ({@code kill -s TERM -- -N} is the spelling dash accepts; it rejects {@code kill -TERM --}.)
     */
    private static final String WATCHDOG = """
            ( trap '' TERM
              while kill -0 "$PPID" 2>/dev/null && kill -0 $$ 2>/dev/null; do sleep 2; done
              kill -0 $$ 2>/dev/null && { kill -s TERM -- -$$ || kill -s TERM $$; sleep 3; kill -s KILL -- -$$ || kill -s KILL $$; }
            ) </dev/null >/dev/null 2>&1 &
            """;

    /**
     * Runs the script in {@code $1} with the rest as its arguments, leading a process group of its own: when
     * this shell does not lead one ({@code kill -0 -$$} is the spelling dash, bash and BusyBox all answer
     * correctly), it becomes {@code setsid}'s. {@code setsid} forks only for a process that already leads a
     * group, which this one does not, so the pid stays the one the script prints. Without {@code setsid} the
     * command runs where it is, and a stop reaches only the command itself.
     */
    private static final String GROUP_GUARD = """
            s=$1; shift
            kill -0 -$$ 2>/dev/null || ! command -v setsid >/dev/null 2>&1 || exec setsid /bin/sh -c "$s" editora "$@"
            eval "$s"
            """;

    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private PosixShell() {}

    /** What a terminal script prints first when asked to mark its pid: this, the pid, a newline. */
    public static final String TERMINAL_PID_MARK = "\u001eEDITORA ";

    /**
     * The script an integrated terminal's shell starts with, on any target: optionally print its pid (so a
     * target whose launcher does not end it — a container — can signal it on close), say what terminal this is,
     * start in {@code cwd} (home when it is null or gone), and become the account's login shell — {@code $SHELL},
     * else bash, else sh, since a container often sets none. Run with {@code /bin/sh -c}.
     */
    public static String terminalScript(String cwd, boolean markPid) {
        StringBuilder script = new StringBuilder();
        if (markPid) {
            script.append("printf '\\036EDITORA %s\\n' \"$$\"; ");
        }
        script.append("export TERM=xterm-256color COLORTERM=truecolor TERM_PROGRAM=Editora; ");
        if (cwd != null && !cwd.isBlank()) {
            script.append("cd -- ").append(quote(cwd)).append(" 2>/dev/null || cd; ");
        }
        script.append("s=${SHELL:-}; [ -x \"$s\" ] || s=$(command -v bash || command -v sh); exec \"$s\" -l");
        return script.toString();
    }

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
        return commandLine(spec, cwd, path, userLocale, Map.of());
    }

    /**
     * As above, with {@code profile}: variables the host's login profile sets, exported first so that
     * {@code PATH}, the locale and the caller's own variables still win over them.
     */
    public static String commandLine(
            ExecSpec spec, String cwd, String path, Map<String, String> userLocale, Map<String, String> profile) {
        StringBuilder line = new StringBuilder("exec /bin/sh -c ")
                .append(quote(GROUP_GUARD))
                .append(" editora ")
                .append(quote(script(spec, cwd, path, userLocale, profile)));
        for (String arg : spec.argv()) {
            line.append(' ').append(quote(arg));
        }
        return line.toString();
    }

    /**
     * {@link #commandLine} as arguments, for a launcher that takes them one by one instead of one string for a
     * login shell to parse ({@code docker exec}, {@code wsl.exe --exec}): {@code /bin/sh -c <guard> editora
     * <script> <argv>…}, nothing quoted.
     */
    public static java.util.List<String> argv(
            ExecSpec spec, String cwd, String path, Map<String, String> userLocale, Map<String, String> profile) {
        java.util.List<String> argv = new java.util.ArrayList<>(java.util.List.of(
                "/bin/sh", "-c", GROUP_GUARD, "editora", script(spec, cwd, path, userLocale, profile)));
        argv.addAll(spec.argv());
        return java.util.List.copyOf(argv);
    }

    /**
     * Prints the start time of process {@code $1} (field 22 of {@code /proc/<pid>/stat}: clock ticks since the
     * kernel booted), which with the pid names one process for as long as that kernel runs. Everything before
     * the last {@code ") "} is the pid and the command name, which may hold spaces and parentheses.
     */
    static final String START_TIME = "sed 's/.*) //' \"/proc/$1/stat\" | cut -d' ' -f20";

    /**
     * Kills the group process {@code $1} leads, but only while {@code $1} is still the process that started at
     * {@code $2} ({@link #START_TIME}); exits 3, touching nothing, when it is not (gone, or the pid reused).
     */
    static final String REAP = "s=$(sed 's/.*) //' \"/proc/$1/stat\" 2>/dev/null | cut -d' ' -f20)\n"
            + "[ -n \"$s\" ] && [ \"$s\" = \"$2\" ] || exit 3\n"
            + "kill -s KILL -- \"-$1\" 2>/dev/null || kill -s KILL \"$1\"";

    /** {@link #START_TIME} of {@code pid} as arguments, for a launcher that takes them one by one. */
    public static java.util.List<String> startTimeArgv(long pid) {
        return java.util.List.of("/bin/sh", "-c", START_TIME, "editora", Long.toString(checkedPid(pid)));
    }

    /**
     * {@link #REAP} for {@code pid} started at {@code startTime}, as arguments: what the next Editora runs on a
     * target to stop a program a crashed one left there.
     */
    public static java.util.List<String> reapArgv(long pid, String startTime) {
        if (startTime == null || !startTime.matches("\\d+")) {
            throw new IllegalArgumentException("not a start time: " + startTime);
        }
        return java.util.List.of("/bin/sh", "-c", REAP, "editora", Long.toString(checkedPid(pid)), startTime);
    }

    private static long checkedPid(long pid) {
        if (pid <= 1) {
            throw new IllegalArgumentException("not a process to signal: " + pid);
        }
        return pid;
    }

    /** {@link #killCommandLine} as arguments, for a launcher that takes them one by one. */
    public static java.util.List<String> killArgv(long pid, boolean force) {
        if (pid <= 1) {
            throw new IllegalArgumentException("not a process to signal: " + pid);
        }
        String signal = force ? "KILL" : "TERM";
        return java.util.List.of(
                "/bin/sh", "-c", "kill -s " + signal + " -- -" + pid + " 2>/dev/null || kill -s " + signal + " " + pid);
    }

    /**
     * The command line that signals the process group a {@link #commandLine} child leads: {@code TERM}, or
     * {@code KILL} with {@code force}. Falls back to the one process when it leads no group.
     */
    public static String killCommandLine(long pid, boolean force) {
        if (pid <= 1) {
            throw new IllegalArgumentException("not a process to signal: " + pid);
        }
        String signal = force ? "KILL" : "TERM";
        return "exec /bin/sh -c "
                + quote("kill -s " + signal + " -- -" + pid + " 2>/dev/null || kill -s " + signal + " " + pid);
    }

    /** The {@code sh} script that sets the child up and then becomes it, so the pid it printed stays true. */
    static String script(ExecSpec spec, String cwd, String path, Map<String, String> userLocale) {
        return script(spec, cwd, path, userLocale, Map.of());
    }

    static String script(
            ExecSpec spec, String cwd, String path, Map<String, String> userLocale, Map<String, String> profile) {
        StringBuilder script = new StringBuilder("printf '\\036EDITORA %s\\n' \"$$\" >&2\n");
        if (spec.longLived()) {
            script.append(WATCHDOG);
        }
        if (cwd != null) {
            script.append("cd -- ").append(quote(cwd)).append(" || exit 126\n");
        }
        // The same order as a local child's environment: what the login profile sets, PATH, the locale, then
        // the caller's own variables.
        if (profile != null) {
            profile.forEach((name, value) -> {
                if (name != null && VARIABLE_NAME.matcher(name).matches() && value != null) {
                    export(script, name, value);
                }
            });
        }
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
