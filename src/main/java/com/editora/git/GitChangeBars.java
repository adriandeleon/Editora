package com.editora.git;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure decision helpers for the editor's Git gutter change bars, extracted from {@code MainController} so the
 * logic is unit-testable ahead of the larger Git-coordinator extraction. java.base + the git models only
 * ({@link #shouldRediff} resolves symlinks, so it touches the file system).
 */
public final class GitChangeBars {

    private GitChangeBars() {}

    /**
     * Maps a diff's {@code line -> ChangeType} to the {@code line -> CSS class} the gutter draws (e.g.
     * {@code git-added}/{@code git-modified}/{@code git-deleted}). An empty input yields an empty map (which
     * still marks a buffer as tracked, reserving the gutter slot).
     */
    public static Map<Integer, String> cssClassesByLine(Map<Integer, ChangeType> changes) {
        Map<Integer, String> classes = new HashMap<>();
        if (changes != null) {
            changes.forEach((line, type) -> classes.put(line, type.cssClass()));
        }
        return classes;
    }

    /**
     * Whether a <em>background</em> open buffer should be re-diffed after a Git mutation (commit/stage/…): it
     * must be a non-active, non-huge, on-disk file located inside the affected repository root. The active
     * buffer is refreshed separately, so it returns {@code false} for it.
     */
    public static boolean shouldRediff(Path bufferPath, Path repoRoot, boolean active, boolean huge) {
        if (bufferPath == null || repoRoot == null || active || huge) {
            return false;
        }
        if (bufferPath.getFileSystem() != repoRoot.getFileSystem()) {
            return false; // a remote (SFTP) buffer is never inside a local repository
        }
        // Not a lexical prefix test: git reports the real root, a buffer keeps its as-opened path, and the
        // two share no prefix when the project is reached through a symlink (see GitService.repoRelative).
        return GitService.repoRelative(repoRoot, bufferPath) != null;
    }
}
