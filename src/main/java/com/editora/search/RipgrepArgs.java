package com.editora.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure builder mapping a {@link SearchQuery} to ripgrep flags (everything between the {@code rg} command and
 * the trailing search path). Unit-tested. The caller prepends the configured {@code rg} command and appends
 * the search path (e.g. {@code "."}); the pattern is passed via {@code -e} so a leading-dash query isn't
 * mistaken for a flag.
 *
 * <p>Flag mapping: always {@code --json --no-messages --crlf --max-filesize=<bytes>}; case-insensitive → {@code -i},
 * else explicit {@code -s}; literal (non-regex) → {@code -F}; whole-word → {@code -w}; each include glob →
 * {@code -g <glob>} and each exclude glob → {@code -g !<glob>}; and {@code --no-ignore} only when
 * {@code respectIgnore} is false (rg honors {@code .gitignore}/hidden/binary by default), else
 * {@code --no-require-git}.
 *
 * <p>{@code --crlf} makes {@code \r\n} a line terminator, as it is for the Java matcher (which drops a
 * trailing CR): without it {@code $} never matched in a CRLF file, so {@code ;$} found nothing in closed
 * files and one line in the same file once open. {@code --no-require-git} applies {@code .gitignore} in a
 * folder that is not a git repository (an unpacked tarball, a project before {@code git init}), which the
 * built-in walker has always done — without it the two backends searched different file sets.
 */
public final class RipgrepArgs {

    private RipgrepArgs() {}

    public static List<String> build(
            SearchQuery q, List<String> include, List<String> exclude, boolean respectIgnore, long maxFileBytes) {
        List<String> a = new ArrayList<>();
        a.add("--json");
        a.add("--no-messages");
        a.add("--crlf");
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
        a.add(respectIgnore ? "--no-require-git" : "--no-ignore");
        a.add("-e");
        a.add(q.text());
        return a;
    }
}
