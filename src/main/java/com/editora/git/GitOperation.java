package com.editora.git;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The multi-step operation a repository is in the middle of — a merge, rebase, cherry-pick or revert that
 * stopped (usually on a conflict) and now waits for the user to continue, skip or abort it — or
 * {@link #NONE}.
 *
 * <p>Git keeps this as files in the work tree's git directory ({@code MERGE_HEAD}, {@code rebase-merge/},
 * {@code CHERRY_PICK_HEAD}, …), and {@link #detect} reads it from there: a handful of {@code stat} calls
 * and no process, so it can ride on every status refresh. The files are per work tree, so the directory
 * must be the one {@code git rev-parse --git-dir} names — for a linked work tree that is
 * {@code <repo>/.git/worktrees/<name>}, not the {@code .git} file in the folder.
 *
 * @param kind    what is in progress
 * @param step    the commit a rebase is at (1-based), or 0 when unknown / not a rebase
 * @param total   how many commits the rebase replays, or 0 when unknown / not a rebase
 * @param message the commit message git prepared for a merge ({@code MERGE_MSG}, comment lines removed),
 *                else {@code ""}
 */
public record GitOperation(Kind kind, int step, int total, String message) {

    /** What is in progress, with the git subcommand that continues, skips or aborts it. */
    public enum Kind {
        NONE(""),
        MERGE("merge"),
        REBASE("rebase"),
        CHERRY_PICK("cherry-pick"),
        REVERT("revert"),
        /** Shown, never driven: a bisect has no continue, and ending it is {@code git bisect reset}. */
        BISECT("bisect");

        private final String subcommand;

        Kind(String subcommand) {
            this.subcommand = subcommand;
        }

        /** The git subcommand of this operation ({@code ""} for {@link #NONE}). */
        public String subcommand() {
            return subcommand;
        }
    }

    /** Nothing in progress. */
    public static final GitOperation NONE = new GitOperation(Kind.NONE, 0, 0, "");

    /** The most of a prepared merge message that is read; it is a few lines in practice. */
    static final int MAX_MESSAGE_BYTES = 64 * 1024;

    public GitOperation {
        kind = kind == null ? Kind.NONE : kind;
        message = message == null ? "" : message;
    }

    public static GitOperation of(Kind kind) {
        return kind == null || kind == Kind.NONE ? NONE : new GitOperation(kind, 0, 0, "");
    }

    public boolean inProgress() {
        return kind != Kind.NONE;
    }

    /** Whether Continue applies: every driven operation has one (a merge's is the commit that concludes it). */
    public boolean canContinue() {
        return kind == Kind.MERGE || kind == Kind.REBASE || kind == Kind.CHERRY_PICK || kind == Kind.REVERT;
    }

    /** Whether Abort applies ({@code git <op> --abort}). */
    public boolean canAbort() {
        return canContinue();
    }

    /** Whether git has a {@code --skip} for this operation: all but a merge, which is a single step. */
    public boolean canSkip() {
        return kind == Kind.REBASE || kind == Kind.CHERRY_PICK || kind == Kind.REVERT;
    }

    /**
     * The command that continues this operation, or an empty array when there is none. A merge has no
     * sequencer: it is concluded by committing, and {@code --no-edit} takes the message git prepared.
     * {@code --cleanup=strip} because that message ends in a {@code # Conflicts:} comment block which only
     * an editor session removes — without it the block became part of the commit message.
     */
    public String[] continueArgs() {
        return switch (kind) {
            case MERGE -> new String[] {"commit", "--no-edit", "--cleanup=strip"};
            case REBASE, CHERRY_PICK, REVERT -> new String[] {kind.subcommand(), "--continue"};
            case NONE, BISECT -> new String[0];
        };
    }

    /** {@code git <op> --abort}, or an empty array when the operation cannot be aborted from here. */
    public String[] abortArgs() {
        return canAbort() ? new String[] {kind.subcommand(), "--abort"} : new String[0];
    }

    /** {@code git <op> --skip}, or an empty array when git has no skip for this operation. */
    public String[] skipArgs() {
        return canSkip() ? new String[] {kind.subcommand(), "--skip"} : new String[0];
    }

    /** What {@link #detect} needs from a git directory; a map in tests, the file system otherwise. */
    public interface Probe {
        /** Whether {@code name} (a path relative to the git directory, {@code /}-separated) exists. */
        boolean exists(String name);

        /** The text of {@code name}, or {@code null} when it cannot be read. */
        String read(String name);
    }

    /**
     * Reads the operation in progress from a git directory, in the order {@code git status} itself uses.
     *
     * <p>A rebase is checked first: while it replays a commit it also leaves {@code CHERRY_PICK_HEAD} (and,
     * with {@code --rebase-merges}, {@code MERGE_HEAD}) behind, and the command that moves on is still
     * {@code rebase --continue}. {@code rebase-apply/applying} is {@code git am}, not a rebase, and is left
     * alone. A cherry-pick or revert of several commits has no {@code *_HEAD} between two of them once the
     * conflicted one is committed; the sequencer's todo list says which of the two is still running. Pure.
     */
    public static GitOperation detect(Probe probe) {
        if (probe == null) {
            return NONE;
        }
        if (probe.exists("rebase-merge")) {
            return new GitOperation(
                    Kind.REBASE, number(probe.read("rebase-merge/msgnum")), number(probe.read("rebase-merge/end")), "");
        }
        if (probe.exists("rebase-apply") && !probe.exists("rebase-apply/applying")) {
            return new GitOperation(
                    Kind.REBASE, number(probe.read("rebase-apply/next")), number(probe.read("rebase-apply/last")), "");
        }
        if (probe.exists("MERGE_HEAD")) {
            return new GitOperation(Kind.MERGE, 0, 0, cleanMessage(probe.read("MERGE_MSG")));
        }
        if (probe.exists("CHERRY_PICK_HEAD")) {
            return of(Kind.CHERRY_PICK);
        }
        if (probe.exists("REVERT_HEAD")) {
            return of(Kind.REVERT);
        }
        if (probe.exists("sequencer/todo")) {
            Kind sequenced = sequencerKind(probe.read("sequencer/todo"));
            if (sequenced != Kind.NONE) {
                return of(sequenced);
            }
        }
        if (probe.exists("BISECT_LOG")) {
            return of(Kind.BISECT);
        }
        return NONE;
    }

    /** {@link #detect(Probe)} over the real directory {@code gitDir}; {@link #NONE} for a null one. */
    public static GitOperation detect(Path gitDir) {
        if (gitDir == null) {
            return NONE;
        }
        return detect(new Probe() {
            @Override
            public boolean exists(String name) {
                return Files.exists(gitDir.resolve(name));
            }

            @Override
            public String read(String name) {
                Path file = gitDir.resolve(name);
                try {
                    if (!Files.isRegularFile(file) || Files.size(file) > MAX_MESSAGE_BYTES) {
                        return null;
                    }
                    return Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException | RuntimeException unreadable) {
                    // Being rewritten by a running git, or not UTF-8: the operation is still known.
                    return null;
                }
            }
        });
    }

    /** Which sequencer command a todo list is for: its first instruction is {@code pick} or {@code revert}. */
    static Kind sequencerKind(String todo) {
        if (todo == null) {
            return Kind.NONE;
        }
        for (String line : todo.split("\\R")) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            String verb = text.split("\\s+", 2)[0];
            if (verb.equals("pick") || verb.equals("p")) {
                return Kind.CHERRY_PICK;
            }
            if (verb.equals("revert") || verb.equals("r")) {
                return Kind.REVERT;
            }
            return Kind.NONE;
        }
        return Kind.NONE;
    }

    /**
     * The message git prepared for a merge, as it should appear in a commit box: git appends the conflicted
     * paths as {@code #} comment lines for an editor to strip, and a message passed with {@code -m} is not
     * stripped, so they are removed here. Pure.
     */
    static String cleanMessage(String raw) {
        if (raw == null) {
            return "";
        }
        List<String> kept = new java.util.ArrayList<>();
        for (String line : raw.split("\\R", -1)) {
            if (!line.startsWith("#")) {
                kept.add(line.stripTrailing());
            }
        }
        return String.join("\n", kept).strip();
    }

    private static int number(String text) {
        if (text == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(text.strip()));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
