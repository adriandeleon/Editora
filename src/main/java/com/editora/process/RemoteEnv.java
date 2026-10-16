package com.editora.process;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a host's <em>login shell</em> sets up, learned once per connection. A command started over SSH gets a
 * bare environment: no version-manager directories on {@code PATH}, usually no locale at all. The same gap a
 * GUI-launched app has on this machine, closed the same way: ask the login shell once and hand its answers
 * to every child.
 *
 * @param path the login shell's {@code PATH}, or {@code null} when the probe failed (children then keep
 *     whatever the server gives them)
 * @param home the account's home directory, or {@code null}
 * @param os {@code uname -s} ({@code Linux}, {@code Darwin}), or {@code null}
 * @param locale the locale variables the login shell has set, for a child that must read and print the
 *     user's own text
 * @param profile the other variables the login profile sets ({@code JAVA_HOME}, {@code MAVEN_OPTS},
 *     {@code GOPATH}, …) that a plain command over SSH would not have
 */
public record RemoteEnv(String path, String home, String os, Map<String, String> locale, Map<String, String> profile) {

    public static final RemoteEnv UNKNOWN = new RemoteEnv(null, null, null, Map.of(), Map.of());

    RemoteEnv(String path, String home, String os, Map<String, String> locale) {
        this(path, home, os, locale, Map.of());
    }

    private static final String BASE = "__EDITORA_BASE_ENV__";
    private static final String BASE_END = "__EDITORA_BASE_ENV_END__";
    private static final String LOGIN = "__EDITORA_LOGIN_ENV__";
    private static final String LOGIN_END = "__EDITORA_LOGIN_ENV_END__";

    /**
     * Exported variables that describe the login session rather than the account's setup, or that Editora
     * sets itself: never carried over to a command.
     */
    private static final java.util.Set<String> SESSION = java.util.Set.of(
            "PATH",
            "HOME",
            "USER",
            "LOGNAME",
            "SHELL",
            "PWD",
            "OLDPWD",
            "SHLVL",
            "_",
            "PS1",
            "PS2",
            "PS3",
            "PS4",
            "PROMPT_COMMAND",
            "TERM",
            "TERMCAP",
            "COLORTERM",
            "COLUMNS",
            "LINES",
            "DISPLAY",
            "WAYLAND_DISPLAY",
            "XAUTHORITY",
            "DBUS_SESSION_BUS_ADDRESS",
            "MAIL",
            "MAILCHECK",
            "TMUX",
            "TMUX_PANE",
            "STY",
            "WINDOWID",
            "GPG_TTY",
            "LS_COLORS",
            "LSCOLORS",
            "LANG",
            "LANGUAGE",
            "XDG_SESSION_ID",
            "XDG_SESSION_TYPE",
            "XDG_SESSION_CLASS",
            "XDG_VTNR",
            "MOTD_SHOWN");

    private static final java.util.List<String> SESSION_PREFIXES =
            java.util.List.of("SSH_", "BASH_", "ZSH_", "HIST", "LC_", "EDITORA_");

    /** At most this many variables, each no longer than this, are carried: a command line has its limits. */
    static final int MAX_VARIABLES = 200;

    static final int MAX_VALUE = 8 * 1024;
    static final int MAX_TOTAL = 64 * 1024;

    private static final String BEGIN = "__EDITORA_ENV_BEGIN__";
    private static final String END = "__EDITORA_ENV_END__";
    private static final List<String> LOCALE_VARIABLES = List.of("LANG", "LC_ALL", "LC_CTYPE");

    /**
     * The command line that prints the environment between two marker lines, one value per line. It runs the
     * account's shell as an interactive login shell so the profile files that set up version managers are
     * read; the markers fence the values off from whatever those files print.
     */
    public static String probeCommandLine() {
        StringBuilder printf = new StringBuilder("printf '%s\\n' ").append(BEGIN);
        printf.append(" \"$PATH\" \"$HOME\" \"$(uname -s)\"");
        LOCALE_VARIABLES.forEach(name -> printf.append(" \"$").append(name).append('"'));
        printf.append(' ').append(END);
        // Then the login shell's whole environment, to be compared with the one a plain command starts with
        // (printed first, before the login shell replaces this one): what the profile adds is carried over.
        printf.append("; printf '%s\\n' ")
                .append(LOGIN)
                .append("; env; printf '%s\\n' ")
                .append(LOGIN_END);
        return "printf '%s\\n' " + BASE + "; env; printf '%s\\n' " + BASE_END + "; "
                + "exec \"${SHELL:-/bin/sh}\" -l -i -c " + PosixShell.quote(printf.toString())
                + " </dev/null 2>/dev/null";
    }

    /** Reads {@link #probeCommandLine}'s output; {@link #UNKNOWN} when the markers are not both there. */
    public static RemoteEnv parse(String output) {
        if (output == null) {
            return UNKNOWN;
        }
        List<String> lines = output.lines().toList();
        int begin = lines.lastIndexOf(BEGIN);
        int end = begin + 4 + LOCALE_VARIABLES.size(); // PATH, HOME and the OS name, then the locale
        if (begin < 0 || lines.size() <= end || !END.equals(lines.get(end))) {
            return UNKNOWN;
        }
        String path = lines.get(begin + 1);
        Map<String, String> locale = new LinkedHashMap<>();
        for (int i = 0; i < LOCALE_VARIABLES.size(); i++) {
            String value = lines.get(begin + 4 + i);
            if (!value.isBlank()) {
                locale.put(LOCALE_VARIABLES.get(i), value);
            }
        }
        return new RemoteEnv(
                path.isBlank() ? null : path,
                blankToNull(lines.get(begin + 2)),
                blankToNull(lines.get(begin + 3)),
                Map.copyOf(locale),
                profile(section(lines, BASE, BASE_END), section(lines, LOGIN, LOGIN_END)));
    }

    /**
     * The variables between {@code start} and {@code end} ({@code env}'s {@code NAME=value} lines); a value
     * that runs over several lines is dropped, since its lines cannot be told from others. Empty when the
     * section is missing.
     */
    static Map<String, String> section(List<String> lines, String start, String end) {
        int from = lines.lastIndexOf(start);
        int to = from < 0 ? -1 : lines.subList(from, lines.size()).indexOf(end);
        Map<String, String> out = new LinkedHashMap<>();
        if (from < 0 || to < 0) {
            return out;
        }
        java.util.Set<String> broken = new java.util.HashSet<>();
        String last = null;
        for (String line : lines.subList(from + 1, from + to)) {
            int eq = line.indexOf('=');
            String name = eq > 0 ? line.substring(0, eq) : null;
            if (name != null && name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                out.put(name, line.substring(eq + 1));
                last = name;
            } else if (last != null) {
                broken.add(last); // a continuation line: the value before it is not whole
            }
        }
        broken.forEach(out::remove);
        return out;
    }

    /**
     * What the login profile adds to a plain command's environment ({@code base}): the variables {@code login}
     * has with a different value, without the session's own and the ones Editora sets itself ({@code PATH},
     * the locale), within the size limits. Pure.
     */
    static Map<String, String> profile(Map<String, String> base, Map<String, String> login) {
        Map<String, String> out = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(login).entrySet()) {
            String name = e.getKey();
            String value = e.getValue();
            if (value.equals(base.get(name))
                    || SESSION.contains(name)
                    || SESSION_PREFIXES.stream().anyMatch(name::startsWith)
                    || value.length() > MAX_VALUE
                    || out.size() >= MAX_VARIABLES
                    || total + name.length() + value.length() > MAX_TOTAL) {
                continue;
            }
            out.put(name, value);
            total += name.length() + value.length();
        }
        return Map.copyOf(out);
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
