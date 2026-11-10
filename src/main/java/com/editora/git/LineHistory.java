package com.editora.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The history of some lines of a file — a declaration's, on the Project Map — as {@code git log -L} gives it: each
 * commit that changed them, newest first, with the part of its diff that touched them. Parsed here, without Git,
 * from the log's output ({@link #FORMAT} before each commit's patch).
 */
public final class LineHistory {

    /** Each commit's header: a record separator, then hash, author, author time (seconds) and subject. */
    public static final String FORMAT = "%x1e%H%x1f%an%x1f%at%x1f%s";

    /** Why a history has no commits, or {@link #OK} when it was read. */
    public enum Status {
        /** Read: the commits, possibly none. */
        OK,
        /** Git is off, the file is not in a repository, or not tracked. */
        UNAVAILABLE,
        /** Every line asked about is an uncommitted addition: no commit has them yet. */
        NEW_LINES,
        /** Git took longer than it was given ({@code GitService.LINE_LOG_BUDGET}). */
        TOO_SLOW,
        /** Git failed; {@link Result#problem()} says how. */
        FAILED
    }

    /**
     * One commit that changed the lines: its {@code path} in that commit (and the {@code previousPath} it had before,
     * when it moved), the diff lines that touched them ({@code patch}: hunk headers and context, {@code +} and
     * {@code -} lines), and how many lines it added and removed there.
     */
    public record Commit(
            String hash,
            String author,
            long epochSeconds,
            String subject,
            String path,
            @Nullable String previousPath,
            List<String> patch,
            int added,
            int removed) {

        public Commit {
            patch = List.copyOf(patch);
        }
    }

    /**
     * A history: {@code commits} newest first, {@code truncated} when more were left out, the repository's {@code root}
     * (null when there is none) and, when {@link Status#FAILED}, the {@code problem}.
     */
    public record Result(
            Status status,
            List<Commit> commits,
            boolean truncated,
            @Nullable Path root,
            String problem) {

        public Result {
            commits = List.copyOf(commits);
        }

        public static Result unavailable() {
            return new Result(Status.UNAVAILABLE, List.of(), false, null, "");
        }

        public static Result failed(@Nullable Path root, String problem) {
            return new Result(Status.FAILED, List.of(), false, root, problem);
        }

        /** The different authors of the commits, in order of first appearance (the newest first). */
        public List<String> authors() {
            List<String> out = new ArrayList<>();
            for (Commit commit : commits) {
                if (!out.contains(commit.author())) {
                    out.add(commit.author());
                }
            }
            return out;
        }
    }

    /** How many patch lines one commit keeps: a rewrite of a long declaration is cut there. */
    public static final int MAX_PATCH_LINES = 2000;

    private LineHistory() {}

    /**
     * The commits of a {@code git log -L … --format=}{@link #FORMAT} run, at most {@code max} of them; {@code
     * truncated} when there were more. Pure.
     */
    public static Result parse(@Nullable String out, int max, @Nullable Path root) {
        List<Commit> commits = new ArrayList<>();
        boolean truncated = false;
        String text = out == null ? "" : out;
        for (String record : text.split("\u001e")) {
            Commit commit = commit(record);
            if (commit == null) {
                continue;
            }
            if (commits.size() >= max) {
                truncated = true;
                break;
            }
            commits.add(commit);
        }
        return new Result(Status.OK, commits, truncated, root, "");
    }

    private static @Nullable Commit commit(String record) {
        String[] lines = record.split("\r?\n", -1);
        String[] fields = lines[0].split("\u001f", 4);
        if (fields.length < 4 || fields[0].strip().length() < 7) {
            return null;
        }
        long when;
        try {
            when = Long.parseLong(fields[2].strip());
        } catch (NumberFormatException unreadable) {
            when = 0;
        }
        String oldPath = null;
        String newPath = null;
        List<String> patch = new ArrayList<>();
        int added = 0;
        int removed = 0;
        boolean inHunks = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (!inHunks) {
                if (line.startsWith("--- ")) {
                    oldPath = side(line.substring(4), "a/");
                } else if (line.startsWith("+++ ")) {
                    newPath = side(line.substring(4), "b/");
                } else if (line.startsWith("@@")) {
                    inHunks = true;
                }
                if (!inHunks) {
                    continue;
                }
            }
            if (line.startsWith("diff --git ")) {
                inHunks = false; // a second file's diff: -L follows one file, so this is not expected
                continue;
            }
            if (line.startsWith("+")) {
                added++;
            } else if (line.startsWith("-")) {
                removed++;
            } else if (line.isEmpty() && i == lines.length - 1) {
                continue; // the newline ending the record
            }
            if (patch.size() < MAX_PATCH_LINES) {
                patch.add(line);
            }
        }
        while (!patch.isEmpty() && patch.get(patch.size() - 1).isEmpty()) {
            patch.remove(patch.size() - 1); // the blank line git puts between commits
        }
        String path = newPath != null ? newPath : oldPath != null ? oldPath : "";
        String previous = oldPath != null && newPath != null && !oldPath.equals(newPath) ? oldPath : null;
        return new Commit(
                fields[0].strip(), fields[1].strip(), when, fields[3].strip(), path, previous, patch, added, removed);
    }

    /** A {@code ---}/{@code +++} line's path without its {@code a/}/{@code b/} prefix; null for {@code /dev/null}. */
    private static @Nullable String side(String value, String prefix) {
        String path = value.strip();
        if (path.equals("/dev/null")) {
            return null;
        }
        if (path.length() > 1 && path.startsWith("\"") && path.endsWith("\"")) {
            path = path.substring(1, path.length() - 1); // a quoted name: kept as Git wrote it, without the quotes
        }
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }
}
