package com.editora.git;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses {@code git diff --numstat -z}: per changed path, the lines added and deleted. Pure.
 *
 * <p>With {@code -z} a record is {@code <added>TAB<deleted>TAB<path>NUL}; a rename or copy leaves the path
 * empty and follows with {@code <old>NUL<new>NUL}. A binary file has {@code -} for both counts.
 */
public final class GitNumstat {

    private GitNumstat() {}

    /** Lines added and deleted in one file; {@code binary} files have no line counts. */
    public record Counts(int added, int deleted, boolean binary) {}

    /** The line counts of the staged and of the unstaged change of each path (new path for a rename). */
    public record Changes(Map<String, Counts> staged, Map<String, Counts> unstaged) {
        public static final Changes NONE = new Changes(Map.of(), Map.of());

        public Changes {
            staged = Map.copyOf(staged);
            unstaged = Map.copyOf(unstaged);
        }
    }

    /** Path (the new one for a rename) → counts, in git's order. Malformed records are skipped. */
    public static Map<String, Counts> parse(String out) {
        Map<String, Counts> counts = new LinkedHashMap<>();
        if (out == null || out.isEmpty()) {
            return counts;
        }
        String[] tokens = out.split("\0", -1);
        for (int i = 0; i < tokens.length; i++) {
            String record = tokens[i];
            int first = record.indexOf('\t');
            int second = first < 0 ? -1 : record.indexOf('\t', first + 1);
            if (second < 0) {
                continue;
            }
            String added = record.substring(0, first);
            String deleted = record.substring(first + 1, second);
            String path = record.substring(second + 1);
            if (path.isEmpty()) { // a rename: the two paths follow as their own tokens
                if (i + 2 >= tokens.length) {
                    break;
                }
                path = tokens[i + 2];
                i += 2;
            }
            Counts parsed = counts(added, deleted);
            if (parsed != null && !path.isEmpty()) {
                counts.put(path, parsed);
            }
        }
        return counts;
    }

    private static Counts counts(String added, String deleted) {
        if (added.equals("-") && deleted.equals("-")) {
            return new Counts(0, 0, true);
        }
        try {
            return new Counts(Integer.parseInt(added), Integer.parseInt(deleted), false);
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
}
