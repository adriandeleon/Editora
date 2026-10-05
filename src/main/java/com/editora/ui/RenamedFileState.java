package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.PathKeys;
import com.editora.config.WorkspaceState;

/**
 * What is stored about a file under its path, carried along when the path changes.
 *
 * <p>Bookmarks, personal notes, breakpoints and the per-file session maps (folds, Markdown mode, spelling
 * language, program arguments …) are all keyed by a path string. A rename used to move only some of them,
 * and only for the one file that was open and renamed from its tab: a rename or move from the Project
 * tree, and every file below a renamed folder, left the rest behind under a path that no longer existed.
 *
 * <p>A <b>rename</b> ({@link #rekey}) moves the entry of {@code old} and of everything stored below it, so
 * one call covers a file and a folder. A <b>Save As</b> ({@link #copy}) leaves the original its own entry:
 * that file is still on disk.
 *
 * <p>Pure apart from {@link #formerCanonicalKey}; the callers own persistence.
 */
final class RenamedFileState {

    private RenamedFileState() {}

    /**
     * Moves the value stored under {@code oldKey}, and under every key below it ({@code oldKey} followed by
     * {@code separator}), to the same place under {@code newKey}. A value already there is replaced: nothing
     * existed at the new path before the rename, so it can only be a leftover.
     *
     * @return whether anything moved
     */
    static <V> boolean rekey(Map<String, V> map, String oldKey, String newKey, String separator) {
        if (map == null || map.isEmpty() || oldKey == null || newKey == null || oldKey.equals(newKey)) {
            return false;
        }
        Map<String, V> moved = null;
        for (var it = map.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, V> e = it.next();
            String renamed = renamed(e.getKey(), oldKey, newKey, separator);
            if (renamed != null) {
                if (moved == null) {
                    moved = new LinkedHashMap<>();
                }
                moved.put(renamed, e.getValue());
                it.remove();
            }
        }
        if (moved == null) {
            return false;
        }
        map.putAll(moved);
        return true;
    }

    /** {@link #rekey(Map, String, String, String)} for a plain list of paths, keeping their order. */
    static boolean rekey(List<String> paths, String oldKey, String newKey, String separator) {
        if (paths == null || oldKey == null || newKey == null || oldKey.equals(newKey)) {
            return false;
        }
        boolean any = false;
        for (int i = 0; i < paths.size(); i++) {
            String renamed = renamed(paths.get(i), oldKey, newKey, separator);
            if (renamed != null) {
                paths.set(i, renamed);
                any = true;
            }
        }
        return any;
    }

    /** {@code key} as it reads after the rename, or null when it is neither {@code oldKey} nor below it. */
    static String renamed(String key, String oldKey, String newKey, String separator) {
        if (key == null) {
            return null;
        }
        if (key.equals(oldKey)) {
            return newKey;
        }
        boolean below = separator != null
                && !separator.isEmpty()
                && key.length() > oldKey.length() + separator.length()
                && key.startsWith(oldKey)
                && key.startsWith(separator, oldKey.length());
        return below ? newKey + key.substring(oldKey.length()) : null;
    }

    /** Copies the (immutable) value under {@code oldKey} to {@code newKey}; returns whether there was one. */
    static <V> boolean copy(Map<String, V> map, String oldKey, String newKey) {
        V value = map == null || oldKey == null || newKey == null || oldKey.equals(newKey) ? null : map.get(oldKey);
        if (value == null) {
            return false;
        }
        map.put(newKey, value);
        return true;
    }

    /** A renamed file or folder ({@code old → target}): every per-file session map follows it. */
    static void rekeyWorkspace(WorkspaceState ws, Path old, Path target) {
        String oldKey = old.toString();
        String newKey = target.toString();
        String sep = old.getFileSystem().getSeparator();
        rekey(ws.getFoldedRegions(), oldKey, newKey, sep);
        rekey(ws.getManualFoldRegions(), oldKey, newKey, sep);
        rekey(ws.getMarkdownViewModes(), oldKey, newKey, sep);
        rekey(ws.getMarkwhenViews(), oldKey, newKey, sep);
        rekey(ws.getSpellLanguages(), oldKey, newKey, sep);
        rekey(ws.getProgramArgs(), oldKey, newKey, sep);
        rekey(ws.getReadOnlyFiles(), oldKey, newKey, sep);
    }

    /**
     * A buffer saved under a new name: the view choices made for it go with it, and the file it came from
     * keeps them too. Folds are not copied here (the window persists them from the buffer itself), nor is
     * the read-only pin, which belongs to the file that was pinned.
     */
    static void copyWorkspace(WorkspaceState ws, Path old, Path target) {
        if (old == null || target == null) {
            return; // an untitled buffer had nothing stored; a rolled-back Save As has nowhere to copy to
        }
        String oldKey = old.toString();
        String newKey = target.toString();
        copy(ws.getMarkdownViewModes(), oldKey, newKey);
        copy(ws.getMarkwhenViews(), oldKey, newKey);
        copy(ws.getSpellLanguages(), oldKey, newKey);
        copy(ws.getProgramArgs(), oldKey, newKey);
    }

    /**
     * The notes-store key ({@link PathKeys#canonicalKey}) a path had before it was renamed away. The
     * canonical form resolves symbolic links, which cannot be asked of a path that is gone — but its folder
     * is still there, and the name inside it did not change. (For a path that still exists — Save As — this
     * is simply its canonical key.)
     */
    static String formerCanonicalKey(Path old) {
        if (old == null) {
            return null;
        }
        try {
            if (Files.exists(old)) {
                return PathKeys.canonicalKey(old);
            }
            Path abs = old.toAbsolutePath().normalize();
            Path parent = abs.getParent();
            return parent == null || abs.getFileName() == null
                    ? abs.toString()
                    : PathKeys.canonical(parent).resolve(abs.getFileName()).toString();
        } catch (RuntimeException e) {
            return old.toString();
        }
    }
}
