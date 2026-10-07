package com.editora.git;

import java.util.ArrayList;
import java.util.List;

import com.editora.git.GitStatus.FileEntry;
import com.editora.process.ProcessRunner;

/**
 * Decisions about unmerged (conflicted) paths: which commands take one side of a conflict, and whether a
 * failed command stopped <em>on</em> a conflict rather than failing outright. Pure.
 */
public final class GitConflicts {

    private GitConflicts() {}

    /** The unmerged entries of {@code status}, in git's order; empty for a null or non-repository status. */
    public static List<FileEntry> unmerged(GitStatus status) {
        if (status == null || !status.isRepo()) {
            return List.of();
        }
        return status.files().stream().filter(FileEntry::unmerged).toList();
    }

    /**
     * The commands that resolve {@code entries} by taking one whole side — {@code ours} (the branch that was
     * checked out; during a rebase, the branch being rebased <em>onto</em>) or theirs — and staging the
     * result.
     *
     * <p>{@code git checkout --ours/--theirs} only works for a path that side still has. The porcelain letters
     * say which sides exist ({@code X} is ours, {@code Y} theirs; {@code D} = that side deleted the file,
     * {@code A} = only that side added it, {@code U} = modified): taking a side that deleted the path, or
     * never had it, is {@code git rm}. So the paths are split into a checkout-then-add list and an rm list,
     * each run once over all its paths.
     */
    public static List<String[]> acceptSide(List<FileEntry> entries, boolean ours) {
        List<String> take = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        for (FileEntry entry : entries) {
            if (!entry.unmerged()) {
                continue;
            }
            (sideHasFile(entry, ours) ? take : remove).add(entry.path());
        }
        List<String[]> commands = new ArrayList<>(3);
        if (!take.isEmpty()) {
            commands.add(argv(take, "checkout", ours ? "--ours" : "--theirs", "--"));
            commands.add(argv(take, "add", "--"));
        }
        if (!remove.isEmpty()) {
            // --ignore-unmatch: a path both sides deleted is already gone from the work tree and the index
            // stages may be all that is left of it.
            commands.add(argv(remove, "rm", "-q", "--ignore-unmatch", "--"));
        }
        return commands;
    }

    /** Whether the chosen side of an unmerged entry still has the file (see {@link #acceptSide}). */
    static boolean sideHasFile(FileEntry entry, boolean ours) {
        char own = ours ? entry.index() : entry.worktree();
        char other = ours ? entry.worktree() : entry.index();
        if (own == 'D') {
            return false; // this side deleted it (DU / UD / DD)
        }
        // AU / UA: "added by us/them" — the side marked U there has no version of the path at all.
        return !(own == 'U' && other == 'A');
    }

    private static String[] argv(List<String> paths, String... prefix) {
        String[] out = new String[1 + prefix.length + paths.size()];
        out[0] = GitSafety.LITERAL_PATHSPECS;
        System.arraycopy(prefix, 0, out, 1, prefix.length);
        for (int i = 0; i < paths.size(); i++) {
            out[1 + prefix.length + i] = paths.get(i);
        }
        return out;
    }

    /**
     * Whether a failed merge, pull, rebase, cherry-pick, revert or stash pop/apply stopped because of
     * conflicts — the repository is now waiting for them to be resolved — rather than refusing to start.
     * The first is a state to show (the Conflicts group, the operation banner); only the second is an error
     * to put in a dialog. Read from git's English output, which every command Editora runs is pinned to:
     * each conflicted path is announced as {@code CONFLICT (content): …}, and the sequencer adds "could not
     * apply" / "after resolving the conflicts". Pure.
     */
    public static boolean stoppedOnConflict(ProcessRunner.Result result) {
        if (result == null || result.ok() || result.cancelled() || result.timedOut()) {
            return false;
        }
        String text = (result.out() == null ? "" : result.out()) + '\n' + (result.err() == null ? "" : result.err());
        return text.contains("CONFLICT (")
                || text.contains("Automatic merge failed")
                || text.contains("after resolving the conflicts")
                || text.contains("Resolve all conflicts manually");
    }
}
