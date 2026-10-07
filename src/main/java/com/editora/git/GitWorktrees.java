package com.editora.git;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure parser for {@code git worktree list --porcelain}: one stanza per work tree, separated by a blank
 * line, each a {@code worktree <path>} line followed by {@code HEAD <hash>}, {@code branch <ref>} or
 * {@code detached}, and the optional {@code bare}, {@code locked [reason]} and {@code prunable [reason]}
 * attributes. The first stanza is the main work tree. Toolkit-free and unit-tested.
 */
public final class GitWorktrees {

    private GitWorktrees() {}

    /**
     * One work tree. {@code branch} is the short branch name ({@code ""} when detached or bare);
     * {@code main} marks the repository's main work tree, which cannot be removed.
     */
    public record Worktree(
            String path,
            String head,
            String branch,
            boolean main,
            boolean bare,
            boolean detached,
            boolean locked,
            boolean prunable) {}

    public static List<Worktree> parse(String porcelain) {
        List<Worktree> out = new ArrayList<>();
        if (porcelain == null) {
            return out;
        }
        String path = null;
        String head = "";
        String branch = "";
        boolean bare = false;
        boolean detached = false;
        boolean locked = false;
        boolean prunable = false;
        // A trailing blank line closes the last stanza; one is appended so a missing one does too.
        for (String raw : (porcelain + "\n\n").split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty()) {
                if (path != null) {
                    out.add(new Worktree(path, head, branch, out.isEmpty(), bare, detached, locked, prunable));
                }
                path = null;
                head = "";
                branch = "";
                bare = false;
                detached = false;
                locked = false;
                prunable = false;
                continue;
            }
            int space = line.indexOf(' ');
            String key = space < 0 ? line : line.substring(0, space);
            String value = space < 0 ? "" : line.substring(space + 1);
            switch (key) {
                case "worktree" -> path = value;
                case "HEAD" -> head = value;
                case "branch" ->
                    branch = value.startsWith("refs/heads/") ? value.substring("refs/heads/".length()) : value;
                case "bare" -> bare = true;
                case "detached" -> detached = true;
                case "locked" -> locked = true;
                case "prunable" -> prunable = true;
                default -> {
                    // an attribute added by a newer git: nothing here depends on it
                }
            }
        }
        return out;
    }
}
