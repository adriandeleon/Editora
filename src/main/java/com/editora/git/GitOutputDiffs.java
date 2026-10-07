package com.editora.git;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The change graph of a pull's diffstat ({@code src/Main.java | 4 ++--}) as a link to that file's diff
 * between the two commits the pull moved between, which Git names on the line above the stat:
 * {@code Updating 1a2b3c4..5d6e7f8}. Pure text, like {@link GitOutputLinks}: the transcript itself is the
 * only record of which commits a finished pull was about.
 */
public final class GitOutputDiffs {

    /**
     * The clickable span {@code [start, end)} — a stat row's count and graph — and what it compares:
     * {@code oldPath} at {@code oldRev} against {@code newPath} at {@code newRev}. The paths differ only for
     * a rename.
     */
    public record Target(int start, int end, String oldRev, String newRev, String oldPath, String newPath) {}

    /** Git's --stat row with a graph: a path, a pipe, the change count, then {@code +}/{@code -} marks. */
    private static final Pattern DIFF_STAT = Pattern.compile("^\\s*(.+?)\\s+\\|\\s+(\\d+\\s+[+\\-]+)\\s*$");

    private static final Pattern UPDATING = Pattern.compile("^Updating ([0-9a-f]{4,64})\\.\\.([0-9a-f]{4,64})\\s*$");

    /** {@code dir/{old => new}/file}: the part of a renamed path that changed, in braces. */
    private static final Pattern BRACE_RENAME = Pattern.compile("^(.*)\\{(.*) => (.*)}(.*)$");

    private static final Pattern PLAIN_RENAME = Pattern.compile("^(.+) => (.+)$");

    private GitOutputDiffs() {}

    /**
     * The span {@code [start, end)} of {@code line}'s count and change graph, or {@code null} when it is not a
     * stat row with one (a binary file, a mode-only change). Says nothing about whether a diff can be opened
     * from it — that takes the transcript ({@link #at}).
     */
    public static int[] graph(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        Matcher stat = DIFF_STAT.matcher(line);
        return stat.matches() ? new int[] {stat.start(2), stat.end(2)} : null;
    }

    /**
     * The diff the character at {@code offset} of the transcript {@code text} opens, or {@code null}: it is
     * not on a change graph, or the rows it belongs to are not under an {@code Updating a..b} line of the
     * same command (a merge commit's stat, a stash).
     */
    public static Target at(String text, int offset) {
        if (text == null || offset < 0 || offset >= text.length()) {
            return null;
        }
        int lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int lineEnd = text.indexOf('\n', offset);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        Matcher stat = DIFF_STAT.matcher(text.substring(lineStart, lineEnd));
        if (!stat.matches() || offset < lineStart + stat.start(2) || offset >= lineStart + stat.end(2)) {
            return null;
        }
        String[] paths = renamedPaths(stat.group(1));
        // Walk up to the "Updating a..b" this stat belongs to; an echoed command line is the edge of it.
        int end = lineStart - 1;
        while (end > 0) {
            int start = text.lastIndexOf('\n', end - 1) + 1;
            String line = text.substring(start, end);
            if (line.startsWith("$ ")) {
                return null;
            }
            Matcher updating = UPDATING.matcher(line);
            if (updating.matches()) {
                return new Target(
                        lineStart + stat.start(2),
                        lineStart + stat.end(2),
                        updating.group(1),
                        updating.group(2),
                        paths[0],
                        paths[1]);
            }
            end = start - 1;
        }
        return null;
    }

    /** A stat row's path as {@code {old, new}}: the same twice unless it is one of Git's rename forms. */
    static String[] renamedPaths(String path) {
        Matcher brace = BRACE_RENAME.matcher(path);
        if (brace.matches()) {
            return new String[] {
                join(brace.group(1), brace.group(2), brace.group(4)),
                join(brace.group(1), brace.group(3), brace.group(4))
            };
        }
        Matcher plain = PLAIN_RENAME.matcher(path);
        if (plain.matches()) {
            return new String[] {plain.group(1), plain.group(2)};
        }
        return new String[] {path, path};
    }

    /** {@code a/{ => b}/c} is {@code a/c} on its empty side, not {@code a//c}. */
    private static String join(String prefix, String middle, String suffix) {
        return (prefix + middle + suffix).replace("//", "/");
    }
}
