package com.editora.sshconfig;

import java.util.regex.Pattern;

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
        boolean anyPositive = false;
        for (String token : patterns.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            boolean negated = token.startsWith("!");
            String glob = negated ? token.substring(1) : token;
            if (glob.isEmpty()) {
                continue;
            }
            if (globMatches(glob, host)) {
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
        StringBuilder re = new StringBuilder(glob.length() + 8);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.matches(re.toString(), host);
    }
}
