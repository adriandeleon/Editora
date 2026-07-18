package com.editora.git;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.editora.git.GitStatus.FileEntry;

/**
 * The Git working-tree status of a single file, for coloring it in the Project file tree (IntelliJ-style:
 * added = green, modified = blue, deleted = gray, renamed = violet, untracked = olive). Pure — derived from
 * the porcelain letters {@link StatusParser} already parses; the {@code cssClass()} is themed in
 * {@code app.css} ({@code .project-tree .git-status-*}).
 */
public enum GitFileStatus {
    ADDED("git-status-added"),
    MODIFIED("git-status-modified"),
    DELETED("git-status-deleted"),
    RENAMED("git-status-renamed"),
    UNTRACKED("git-status-untracked");

    private final String cssClass;

    GitFileStatus(String cssClass) {
        this.cssClass = cssClass;
    }

    /** The style class the Project-tree cell adds for this status (see {@code app.css}). */
    public String cssClass() {
        return cssClass;
    }

    /** The single-letter status label (M/A/D/R/U), matching the Commit tool window's convention. */
    public String letter() {
        return switch (this) {
            case ADDED -> "A";
            case MODIFIED -> "M";
            case DELETED -> "D";
            case RENAMED -> "R";
            case UNTRACKED -> "U";
        };
    }

    /**
     * Classifies one changed file from its porcelain-v2 index/worktree letters. Precedence when the two
     * sides disagree: deleted → renamed → added → modified (an untracked file is reported first). Pure.
     */
    public static GitFileStatus of(FileEntry e) {
        if (e.untracked()) {
            return UNTRACKED;
        }
        char i = e.index();
        char w = e.worktree();
        if (i == 'D' || w == 'D') {
            return DELETED;
        }
        if (i == 'R' || w == 'R') {
            return RENAMED;
        }
        if (i == 'A' || w == 'A') {
            return ADDED;
        }
        return MODIFIED; // M, C, T, or a mix
    }

    /**
     * Classifies a {@code git diff-tree --name-status} letter (a commit's changed file): A/M/D/R/C/T. A copy
     * (C) is treated like a rename (both are violet). Anything else is modified. Pure.
     */
    public static GitFileStatus fromLetter(char c) {
        return switch (Character.toUpperCase(c)) {
            case 'A' -> ADDED;
            case 'D' -> DELETED;
            case 'R', 'C' -> RENAMED;
            default -> MODIFIED; // M, T, or anything unexpected
        };
    }

    /**
     * Maps every changed file in {@code status} to its {@link GitFileStatus}, keyed by <b>absolute, normalized
     * path</b> resolved against {@code root} (the repo root Git reported paths relative to). A rename is keyed
     * by its new path. Returns an empty map for a non-repo / null input. Pure — no filesystem I/O.
     */
    public static Map<Path, GitFileStatus> byPath(GitStatus status, Path root) {
        Map<Path, GitFileStatus> out = new LinkedHashMap<>();
        if (status == null || !status.isRepo() || root == null) {
            return out;
        }
        for (FileEntry e : status.files()) {
            Path abs = root.resolve(e.path()).toAbsolutePath().normalize();
            out.put(abs, of(e));
        }
        return out;
    }
}
