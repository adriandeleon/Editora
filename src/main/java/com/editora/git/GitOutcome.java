package com.editora.git;

import java.util.Locale;

import com.editora.process.ProcessRunner;

/**
 * Pure classification of a finished git command from its exit code and output, for the cases where a
 * non-zero exit is not "the command failed": a merge or rebase that stopped for the user to resolve
 * conflicts, a push the remote rejected because it has commits the branch lacks, a {@code branch -d} that
 * refused an unmerged branch, a {@code worktree remove} that refused a dirty tree. Each has its own next step,
 * and an error dialog titled "Git command failed" names none of them.
 *
 * <p>Git's messages are read in English: user commands run with {@code LC_MESSAGES=C}
 * ({@link GitSafety#userEnv}). Toolkit-free and unit-tested.
 */
public enum GitOutcome {
    /** Exit 0. */
    OK,
    /** The user stopped the command. */
    CANCELLED,
    /** A merge, rebase, cherry-pick or pull stopped on conflicts; the operation is still in progress. */
    CONFLICT,
    /** A push rejected because the remote branch has commits this one does not (pull or force). */
    NON_FAST_FORWARD,
    /** {@code branch -d} refused: the branch has commits that are not merged. */
    NOT_FULLY_MERGED,
    /** {@code worktree remove} refused: the work tree has modified or untracked files. */
    DIRTY_WORKTREE,
    /** Anything else that exited non-zero. */
    FAILED;

    public static GitOutcome of(ProcessRunner.Result result) {
        if (result == null) {
            return FAILED;
        }
        return result.cancelled() ? CANCELLED : of(result.exit(), result.out(), result.err());
    }

    public static GitOutcome of(int exit, String out, String err) {
        if (exit == 0) {
            return OK;
        }
        if (exit < 0) {
            return FAILED; // not started, timed out or interrupted: git said nothing
        }
        String text = ((out == null ? "" : out) + "\n" + (err == null ? "" : err)).toLowerCase(Locale.ROOT);
        if (text.contains("[rejected]") && (text.contains("(non-fast-forward)") || text.contains("(fetch first)"))) {
            return NON_FAST_FORWARD;
        }
        if (text.contains("conflict (")
                || text.contains("automatic merge failed")
                || text.contains("could not apply ")
                || text.contains("resolve all conflicts manually")) {
            return CONFLICT;
        }
        if (text.contains("is not fully merged")) {
            return NOT_FULLY_MERGED;
        }
        if (text.contains("contains modified or untracked files")) {
            return DIRTY_WORKTREE;
        }
        return FAILED;
    }

    /**
     * What git printed, for showing to the user: stdout then stderr, each trimmed, without git's {@code hint:}
     * lines (they name terminal commands; the editor offers its own next step).
     */
    public static String transcript(ProcessRunner.Result result) {
        if (result == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {result.out(), result.err()}) {
            if (part == null) {
                continue;
            }
            for (String line : part.split("\\R")) {
                if (line.isBlank() || line.startsWith("hint:")) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line.stripTrailing());
            }
        }
        return sb.toString();
    }
}
