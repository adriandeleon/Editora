package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.editora.ui.ProjectPanel.FsChange;
import com.editora.ui.ProjectPanel.FsKind;

/**
 * What the project watcher saw between two debounce ticks, kept by directory so the panel can re-list only
 * the folders whose entries came or went instead of rebuilding the tree from its root for any event anywhere.
 * Pure: the panel adds batches on the FX thread and drains one {@link Plan} per tick.
 */
final class ProjectWatchChanges {

    /** Beyond this many distinct paths the batch stops naming files: "something changed, look again". */
    static final int MAX_NAMED = 2_000;

    /**
     * One tick's work.
     *
     * @param relistAll an event queue overflowed (or nothing was recorded): no directory can be trusted
     * @param relistDirs directories in which an entry was created or deleted by someone else
     * @param localWrites files Editora itself just wrote that the watcher reported as created — the panel
     *     re-lists their folder only when the tree does not already show them (a first save of a new file)
     * @param external the changes that were not Editora's own, in arrival order
     * @param unknown the batch could not name everything that changed (overflow, an empty batch, too many)
     */
    record Plan(
            boolean relistAll, Set<Path> relistDirs, List<Path> localWrites, List<FsChange> external, boolean unknown) {

        /** Whether anything outside Editora changed, i.e. whether Git, diffs and the index have work to do. */
        boolean hasExternal() {
            return unknown || !external.isEmpty();
        }

        /** Whether an entry came or went: a modified file changes no tree and can wait for the throttle. */
        boolean structural() {
            return relistAll || !relistDirs.isEmpty();
        }
    }

    private final Set<FsChange> changes = new LinkedHashSet<>();
    private final Set<Path> unknownDirs = new LinkedHashSet<>();
    private boolean overflow;
    private boolean truncated;
    private boolean recorded;

    /**
     * Whether {@code name} is a file Editora creates and removes for its own purposes: the typst render input
     * (#465) and the sibling temporary/backup files of an atomic save. They are never worth a refresh.
     */
    static boolean isInternalName(String name) {
        return name != null
                && (name.startsWith(".editora-typst-")
                        || name.endsWith(".editora-tmp")
                        || name.endsWith(".editora-backup"));
    }

    /**
     * Records one watch-key batch for {@code dir}. {@code named} holds the non-internal events; an empty list
     * without {@code overflow} is a key that fired with nothing to say, which is treated as "this directory
     * changed somehow".
     */
    void add(Path dir, List<FsChange> named, boolean overflow) {
        recorded = true;
        if (overflow) {
            this.overflow = true;
        }
        if (named.isEmpty() && !overflow && dir != null) {
            unknownDirs.add(dir);
        }
        for (FsChange change : named) {
            if (changes.size() >= MAX_NAMED) {
                truncated = true;
                Path parent = change.path().getParent();
                if (parent != null && change.kind() != FsKind.CHANGED) {
                    unknownDirs.add(parent);
                }
                continue;
            }
            changes.add(change);
        }
    }

    void clear() {
        changes.clear();
        unknownDirs.clear();
        overflow = false;
        truncated = false;
        recorded = false;
    }

    /**
     * Empties the accumulator into a plan. {@code locallyWritten} answers whether Editora itself wrote that
     * path a moment ago: an atomic save renames a temporary file over the target, which the watcher reports as
     * the target being created, and that must not read as somebody else touching the project.
     */
    Plan drain(Predicate<Path> locallyWritten) {
        if (!recorded) {
            return new Plan(true, Set.of(), List.of(), List.of(), true); // fired with no batch: assume anything
        }
        Set<Path> dirs = new LinkedHashSet<>(unknownDirs);
        List<Path> local = new ArrayList<>();
        List<FsChange> external = new ArrayList<>();
        // Where replacing a file is reported as "deleted, then created" (Windows), the delete half of our own
        // save is ours too. A delete with no create beside it is someone removing the file just saved.
        Set<Path> replacedLocally = new HashSet<>();
        for (FsChange change : changes) {
            if (change.kind() == FsKind.CREATED && locallyWritten.test(change.path())) {
                replacedLocally.add(change.path());
            }
        }
        for (FsChange change : changes) {
            if (change.kind() == FsKind.DELETED
                    ? replacedLocally.contains(change.path())
                    : locallyWritten.test(change.path())) {
                if (change.kind() == FsKind.CREATED) {
                    local.add(change.path());
                }
                continue;
            }
            external.add(change);
            Path parent = change.path().getParent();
            if (change.kind() != FsKind.CHANGED && parent != null) {
                dirs.add(parent);
            }
        }
        Plan plan = new Plan(
                overflow,
                dirs,
                List.copyOf(local),
                List.copyOf(external),
                overflow || truncated || !unknownDirs.isEmpty());
        clear();
        return plan;
    }
}
