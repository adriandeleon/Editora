package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.git.GitService;
import com.editora.vfs.Vfs;

/**
 * Which repository a path picked in the Project tree belongs to, relative to the window's active one.
 *
 * <p>The Git UI follows the active tab's repository, but a project folder can hold others: a nested
 * repository, a submodule, a second worktree. Their files are <em>inside</em> the active repository's folder
 * and yet not part of it, so computing their path against the active root produced a pathspec git rejects —
 * and the tree's status colouring, which is the active repository's, says nothing about them.
 *
 * <p>The answer comes from the nearest {@code .git} entry above the path (a folder for a repository, a file
 * for a submodule or worktree): a few {@code stat} calls, cheap enough for a menu that is about to open.
 */
public enum GitPathScope {
    /** Not inside any repository (or not a local path). */
    NONE,
    /** In the window's active repository: the last status and the tree colouring describe it. */
    ACTIVE,
    /** In some other repository; its status is not known until git is asked. */
    OTHER;

    /** The nearest ancestor of {@code path} (or {@code path} itself) holding a {@code .git} entry, or null. */
    static Path nearestRepository(Path path) {
        if (path == null || !Vfs.isLocal(path)) {
            return null;
        }
        try {
            for (Path dir = path.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
                if (Files.exists(dir.resolve(".git"))) {
                    return dir;
                }
            }
        } catch (RuntimeException unreadable) {
            // an unusable path is simply in no repository
        }
        return null;
    }

    static GitPathScope of(Path path, Path activeRoot) {
        Path nearest = nearestRepository(path);
        if (nearest == null) {
            // No marker found (GIT_DIR, an unusual layout): trust the active repository if the path is in it.
            return activeRoot != null && GitService.repoRelative(activeRoot, path) != null ? ACTIVE : NONE;
        }
        return activeRoot != null
                        && GitService.repoRelative(activeRoot, nearest) != null
                        && GitService.repoRelative(nearest, activeRoot) != null
                ? ACTIVE
                : OTHER;
    }
}
