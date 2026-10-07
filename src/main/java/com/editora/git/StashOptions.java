package com.editora.git;

import java.util.ArrayList;
import java.util.List;

/**
 * What {@code git stash push} is asked to take, and the pure rules around it: the argument list, and which
 * combinations and Git versions allow it. Unit-tested.
 *
 * @param message          the stash message; blank for git's own "WIP on …"
 * @param includeUntracked also stash untracked files ({@code --include-untracked})
 * @param stagedOnly       stash only what is staged ({@code --staged}, Git 2.35 and later)
 * @param keepIndex        leave what is staged in place after stashing ({@code --keep-index})
 */
public record StashOptions(String message, boolean includeUntracked, boolean stagedOnly, boolean keepIndex) {

    /** A plain stash of the tracked changes. */
    public static final StashOptions DEFAULT = new StashOptions("", false, false, false);

    public StashOptions {
        message = message == null ? "" : message.strip();
    }

    /**
     * The {@code git} arguments. Git refuses {@code --staged} together with {@code --include-untracked}
     * ("Can't use --staged and --include-untracked or --all at the same time"), and a staged-only stash
     * leaves nothing staged for {@code --keep-index} to keep, so staged-only is passed alone.
     */
    public String[] args() {
        List<String> args = new ArrayList<>(List.of("stash", "push"));
        if (stagedOnly) {
            args.add("--staged");
        } else {
            if (includeUntracked) {
                args.add("--include-untracked");
            }
            if (keepIndex) {
                args.add("--keep-index");
            }
        }
        if (!message.isEmpty()) {
            args.add("-m");
            args.add(message);
        }
        return args.toArray(String[]::new);
    }

    /** Whether the Git that printed {@code versionOutput} has {@code stash push --staged} (2.35 or later). */
    public static boolean stagedSupported(String versionOutput) {
        return GitSafety.versionAtLeast(versionOutput, 2, 35);
    }
}
