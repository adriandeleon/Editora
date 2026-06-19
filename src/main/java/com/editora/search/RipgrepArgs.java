package com.editora.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure builder mapping a {@link SearchQuery} to ripgrep flags (everything between the {@code rg} command and
 * the trailing search path). Unit-tested. The caller prepends the configured {@code rg} command and appends
 * the search path (e.g. {@code "."}); the pattern is passed via {@code -e} so a leading-dash query isn't
 * mistaken for a flag.
 *
 * <p>Flag mapping: always {@code --json --no-messages --max-filesize=<bytes>}; case-insensitive → {@code -i},
 * else explicit {@code -s}; literal (non-regex) → {@code -F}; whole-word → {@code -w}; each include glob →
 * {@code -g <glob>} and each exclude glob → {@code -g !<glob>}; and {@code --no-ignore} only when
 * {@code respectIgnore} is false (rg honors {@code .gitignore}/hidden/binary by default).
 */
public final class RipgrepArgs {

    private RipgrepArgs() {}

    public static List<String> build(
            SearchQuery q, List<String> include, List<String> exclude, boolean respectIgnore, long maxFileBytes) {
        List<String> a = new ArrayList<>();
        a.add("--json");
        a.add("--no-messages");
        a.add("--max-filesize=" + maxFileBytes);
        a.add(q.caseSensitive() ? "-s" : "-i");
        if (!q.regex()) {
            a.add("-F"); // fixed-strings (literal)
        }
        if (q.wholeWord()) {
            a.add("-w");
        }
        for (String g : include) {
            a.add("-g");
            a.add(g);
        }
        for (String g : exclude) {
            a.add("-g");
            a.add("!" + g);
        }
        if (!respectIgnore) {
            a.add("--no-ignore");
        }
        a.add("-e");
        a.add(q.text());
        return a;
    }
}
