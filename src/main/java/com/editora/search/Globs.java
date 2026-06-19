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

    /** Split a raw field (comma-separated globs) into trimmed, non-blank patterns. */
    public static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(",")) {
            String g = part.strip();
            if (!g.isEmpty()) {
                out.add(g);
            }
        }
        return out;
    }

    /**
     * Whether {@code relPath} (root-relative, '/'-separated) passes the filters: included iff there are no
     * include globs or it matches at least one, AND it matches none of the exclude globs.
     */
    public static boolean accept(String relPath, List<String> include, List<String> exclude) {
        if (relPath == null) {
            return false;
        }
        for (String ex : exclude) {
            if (EditorConfigGlob.matches(ex, relPath)) {
                return false;
            }
        }
        if (include.isEmpty()) {
            return true;
        }
        for (String in : include) {
            if (EditorConfigGlob.matches(in, relPath)) {
                return true;
            }
        }
        return false;
    }
}
