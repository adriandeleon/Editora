package com.editora.ui;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pure decision logic for the multi-window restore set (extracted from {@link WindowManager} so it can be
 * unit-tested without the toolkit). A window is identified by a <em>key</em>:
 *
 * <ul>
 *   <li>{@code ""} — the global, no-project window;
 *   <li>{@code "untitled:<uuid>"} — an extra "New Window" (its session is {@code windows/<uuid>.json});
 *   <li>any other string — a project id (its session lives under {@code projects/}).
 * </ul>
 *
 * The restore/primary/orphan-GC decisions used to live inline in {@code WindowManager.launch} /
 * {@code gcOrphanWindowSessions}; they're bug-prone (a quit-vs-close drain bug lived here), so they're pulled
 * out here and tested directly.
 */
final class WindowKeys {

    /** Prefix marking an "untitled" no-project window key ({@code untitled:<uuid>}). */
    static final String UNTITLED_PREFIX = "untitled:";

    private WindowKeys() {}

    static boolean isUntitled(String key) {
        return key.startsWith(UNTITLED_PREFIX);
    }

    static boolean isGlobal(String key) {
        return key.isEmpty();
    }

    /** A project window: neither the global window nor an untitled one. */
    static boolean isProject(String key) {
        return !isGlobal(key) && !isUntitled(key);
    }

    /** The session file name ({@code <uuid>.json}) for an {@code untitled:<uuid>} key. */
    static String untitledSessionFileName(String key) {
        return key.substring(UNTITLED_PREFIX.length()) + ".json";
    }

    /**
     * The ordered set of window keys to restore at launch. Starts from the saved open set, adds the CLI
     * {@code --project} key when present, drops project windows when Projects are disabled (the global +
     * untitled windows always restore), and falls back to the global window when nothing is left.
     */
    static LinkedHashSet<String> restoreKeys(Collection<String> saved, boolean projectsOn, String cliId) {
        LinkedHashSet<String> toOpen = new LinkedHashSet<>(saved);
        if (cliId != null) {
            toOpen.add(cliId);
        }
        if (!projectsOn) {
            toOpen.removeIf(WindowKeys::isProject);
        }
        if (toOpen.isEmpty()) {
            toOpen.add(""); // nothing remembered ⇒ open the global window
        }
        return toOpen;
    }

    /**
     * The window to focus after restore: the CLI project if given, else the global window if it's open, else
     * the last-active project if it's in the set, else the first restored window. {@code toOpen} must be
     * non-empty (see {@link #restoreKeys}).
     */
    static String primaryKey(String cliId, Collection<String> toOpen, String activeId) {
        if (cliId != null) {
            return cliId;
        }
        if (toOpen.contains("")) {
            return "";
        }
        if (activeId != null && toOpen.contains(activeId)) {
            return activeId;
        }
        return toOpen.iterator().next();
    }

    /**
     * Which {@code windows/*.json} session files to delete: every existing {@code .json} file not backing a
     * currently-open untitled window. (Project sessions live under {@code projects/}, so they're never here.)
     */
    static Set<String> orphanSessionFiles(Collection<String> openKeys, Collection<String> existingFileNames) {
        Set<String> keep = new HashSet<>();
        for (String k : openKeys) {
            if (isUntitled(k)) {
                keep.add(untitledSessionFileName(k));
            }
        }
        Set<String> orphans = new HashSet<>();
        for (String f : existingFileNames) {
            if (f.endsWith(".json") && !keep.contains(f)) {
                orphans.add(f);
            }
        }
        return orphans;
    }
}
