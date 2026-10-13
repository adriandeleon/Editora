package com.editora.vfs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.process.PosixShell;

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
 */
record RemoteEnv(String path, String home, String os, Map<String, String> locale) {

    static final RemoteEnv UNKNOWN = new RemoteEnv(null, null, null, Map.of());

    private static final String BEGIN = "__EDITORA_ENV_BEGIN__";
    private static final String END = "__EDITORA_ENV_END__";
    private static final List<String> LOCALE_VARIABLES = List.of("LANG", "LC_ALL", "LC_CTYPE");

    /**
     * The command line that prints the environment between two marker lines, one value per line. It runs the
     * account's shell as an interactive login shell so the profile files that set up version managers are
     * read; the markers fence the values off from whatever those files print.
     */
    static String probeCommandLine() {
        StringBuilder printf = new StringBuilder("printf '%s\\n' ").append(BEGIN);
        printf.append(" \"$PATH\" \"$HOME\" \"$(uname -s)\"");
        LOCALE_VARIABLES.forEach(name -> printf.append(" \"$").append(name).append('"'));
        printf.append(' ').append(END);
        return "exec \"${SHELL:-/bin/sh}\" -l -i -c " + PosixShell.quote(printf.toString()) + " </dev/null 2>/dev/null";
    }

    /** Reads {@link #probeCommandLine}'s output; {@link #UNKNOWN} when the markers are not both there. */
    static RemoteEnv parse(String output) {
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
                Map.copyOf(locale));
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
