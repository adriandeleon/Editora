package com.editora.sshconfig;

/**
 * SSH client-config {@code Host} pattern matching, matching OpenSSH's rules (verified against {@code ssh -G}):
 * a {@code Host} line carries one or more whitespace-separated patterns, each an optional {@code !}-negation
 * of a glob where {@code *} matches any run of characters (including dots) and {@code ?} matches one. A host
 * matches the line iff <b>no</b> negated pattern matches <b>and</b> at least one positive pattern matches.
 * Matching is <b>case-sensitive</b> (confirmed: {@code Host MyServer} does not match host {@code myserver}).
 * Pure, java.base-only, unit-tested.
 */
public final class SshPattern {

    private SshPattern() {}

    /** Whether {@code host} matches the whitespace-separated {@code patterns} of a {@code Host} line. */
    public static boolean matches(String patterns, String host) {
        if (patterns == null || host == null) {
            return false;
        }
        // No regex: the preview's summaries call this for every block above every host (quadratic in the number
        // of hosts), and a split plus a Pattern compiled per glob kept the FX thread busy for seconds on a
        // 1,000-host config.
        boolean anyPositive = false;
        int n = patterns.length();
        int i = 0;
        while (i < n) {
            while (i < n && isSpace(patterns.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < n && !isSpace(patterns.charAt(i))) {
                i++;
            }
            if (start == i) {
                break;
            }
            boolean negated = patterns.charAt(start) == '!';
            int from = negated ? start + 1 : start;
            if (from == i) {
                continue;
            }
            if (globMatches(patterns, from, i, host)) {
                if (negated) {
                    return false; // a matched negation disqualifies the whole line
                }
                anyPositive = true;
            }
        }
        return anyPositive;
    }

    /** A single glob ({@code *} = any run incl. dots, {@code ?} = one char) against {@code host}, case-sensitive. */
    static boolean globMatches(String glob, String host) {
        return globMatches(glob, 0, glob.length(), host);
    }

    /**
     * {@code glob[from, to)} against the whole of {@code host}: the usual wildcard walk, going back only to the
     * last {@code *} (linear for the patterns ssh configs hold, never exponential).
     */
    private static boolean globMatches(String glob, int from, int to, String host) {
        int g = from;
        int h = 0;
        int star = -1;
        int resume = 0;
        int hn = host.length();
        while (h < hn) {
            if (g < to && glob.charAt(g) == '*') {
                star = g++;
                resume = h;
            } else if (g < to && (glob.charAt(g) == '?' || glob.charAt(g) == host.charAt(h))) {
                g++;
                h++;
            } else if (star >= 0) {
                g = star + 1;
                h = ++resume;
            } else {
                return false;
            }
        }
        while (g < to && glob.charAt(g) == '*') {
            g++;
        }
        return g == to;
    }

    /** The whitespace {@code \s} stands for: space, tab, newline, vertical tab, form feed, carriage return. */
    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
    }
}
