package com.editora.search;

import java.util.ArrayList;
import java.util.List;

import com.editora.editorconfig.EditorConfigGlob;

/**
 * Pure helpers for the Find-in-Files include/exclude globs: parse the comma-separated panel fields, and
 * decide whether a root-relative path passes the filters. The actual glob→regex matching reuses
 * {@link EditorConfigGlob} (gitignore-style); ripgrep applies its own equivalents via {@code -g}.
 */
public final class Globs {

    private Globs() {}

    /**
     * Split a raw field (comma-separated globs) into trimmed, non-blank patterns. A comma inside a brace
     * alternation belongs to the glob: {@code *.{js,ts}} is one pattern. Splitting it into two halves with
     * unbalanced braces made ripgrep exit 2 and the walker match nothing.
     */
    public static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= raw.length(); i++) {
            char c = i < raw.length() ? raw.charAt(i) : ',';
            if (c == '\\' && i + 1 < raw.length()) {
                i++; // an escaped character is never structure
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            } else if (c == ',' && (depth == 0 || i == raw.length())) {
                String g = raw.substring(start, i).strip();
                if (!g.isEmpty()) {
                    out.add(g);
                }
                start = i + 1;
            }
        }
        return out;
    }

    /**
     * Whether {@code relPath} (root-relative, '/'-separated) passes the filters: included iff there are no
     * include globs or it matches at least one, AND neither it nor a directory above it matches an exclude
     * glob.
     *
     * <p>An exclude names a directory as readily as a file: {@code target} or {@code node_modules} drops
     * everything beneath it, as {@code rg -g '!target'} does. Matching only the path itself excluded a file
     * <em>named</em> {@code target} and nothing else, so the two backends — and the open buffers, which are
     * always matched here — returned different results for the same field.
     */
    public static boolean accept(String relPath, List<String> include, List<String> exclude) {
        if (relPath == null || excluded(relPath, exclude)) {
            return false;
        }
        return included(relPath, include);
    }

    /**
     * As {@link #accept} for a file whose ancestor directories were already tested with
     * {@link #excludesDirectory} (a pruning walk), so they are not tested again for every file under them.
     */
    public static boolean acceptFile(String relPath, List<String> include, List<String> exclude) {
        if (relPath == null) {
            return false;
        }
        for (String ex : exclude) {
            if (!directoryOnly(ex) && EditorConfigGlob.matches(javaGlob(ex), relPath)) {
                return false;
            }
        }
        return included(relPath, include);
    }

    /** Whether the directory {@code relDir} is itself named by an exclude glob, so a walk can skip it whole. */
    public static boolean excludesDirectory(String relDir, List<String> exclude) {
        if (relDir == null || relDir.isEmpty()) {
            return false;
        }
        for (String ex : exclude) {
            if (EditorConfigGlob.matches(javaGlob(directoryOnly(ex) ? ex.substring(0, ex.length() - 1) : ex), relDir)) {
                return true;
            }
        }
        return false;
    }

    private static boolean excluded(String relPath, List<String> exclude) {
        if (exclude.isEmpty()) {
            return false;
        }
        for (int slash = relPath.indexOf('/'); slash >= 0; slash = relPath.indexOf('/', slash + 1)) {
            if (slash > 0 && excludesDirectory(relPath.substring(0, slash), exclude)) {
                return true;
            }
        }
        for (String ex : exclude) {
            if (!directoryOnly(ex) && EditorConfigGlob.matches(javaGlob(ex), relPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code glob} as the Java matcher must be given it. ripgrep reads {@code \x} as an escaped {@code x}
     * (everywhere but Windows, where the backslash is a path separator and stays one here too), while
     * {@link EditorConfigGlob} takes a backslash literally — so {@code we\,ird/*} found the closed file
     * through ripgrep and lost it again once the file was open. An escaped metacharacter becomes a
     * one-character class, anything else the character itself.
     */
    static String javaGlob(String glob) {
        return javaGlob(glob, java.io.File.separatorChar != '\\');
    }

    static String javaGlob(String glob, boolean backslashEscapes) {
        if (!backslashEscapes || glob.indexOf('\\') < 0) {
            return glob;
        }
        StringBuilder out = new StringBuilder(glob.length() + 4);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c != '\\' || i + 1 == glob.length()) {
                out.append(c);
                continue;
            }
            char escaped = glob.charAt(++i);
            if ("*?[{},".indexOf(escaped) >= 0) {
                out.append('[').append(escaped).append(']');
            } else {
                out.append(escaped);
            }
        }
        return out.toString();
    }

    /** A trailing slash ({@code target/}) names a directory only, as in a {@code .gitignore}. */
    private static boolean directoryOnly(String glob) {
        return glob.length() > 1 && glob.endsWith("/");
    }

    private static boolean included(String relPath, List<String> include) {
        if (include.isEmpty()) {
            return true;
        }
        for (String in : include) {
            if (EditorConfigGlob.matches(javaGlob(in), relPath)) {
                return true;
            }
        }
        return false;
    }
}
