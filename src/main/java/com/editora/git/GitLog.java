package com.editora.git;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The row model of the Git Log: one {@link Entry} per commit with its parents and the ref names that point
 * at it, a {@link Page} that says whether more history follows, the {@link Request} a page is asked for with
 * (and its argv, {@link #logArgs}), and the {@link Details} of one selected commit.
 *
 * <p>Pure parsing of one {@code git log} invocation ({@link #FORMAT} with {@code --decorate=full}), so it is
 * unit-tested without a repository. The decorations are asked for with full ref names because the short
 * form cannot tell a local branch called {@code origin/x} from the remote-tracking branch {@code origin/x}.
 */
public final class GitLog {

    private GitLog() {}

    /**
     * {@code hash, short hash, author, author time (epoch seconds), author date (short), parent hashes,
     * decorations, subject}, tab-separated. The subject is last because it is the only field that may itself
     * hold a tab.
     */
    public static final String FORMAT = "--pretty=format:" + row();

    /** {@link #FORMAT} with each commit introduced by {@link #RECORD_START}, for a file history. */
    static final String FOLLOW_FORMAT = "--pretty=tformat:%x01" + row();

    private static String row() {
        return "%H%x09%h%x09%an%x09%at%x09%ad%x09%P%x09%D%x09%s";
    }

    /** Starts every commit of a {@link #parseFollow file history} listing, whose records hold NULs and newlines. */
    static final char RECORD_START = '\u0001';

    private static final int FIELDS = 8;

    /** What kind of name decorates a commit. */
    public enum RefKind {
        /** The checked-out position itself ({@code HEAD}); alone on a row it means a detached HEAD. */
        HEAD,
        /** A local branch ({@code refs/heads/…}). */
        LOCAL,
        /** A remote-tracking branch ({@code refs/remotes/…}), named with its remote: {@code origin/main}. */
        REMOTE,
        /** A tag ({@code refs/tags/…}). */
        TAG,
        /** Anything else git chose to decorate with ({@code refs/stash}, a replace ref…). */
        OTHER
    }

    /**
     * A name pointing at a commit. {@code current} marks the branch {@code HEAD} is attached to
     * ({@code HEAD -> main}).
     */
    public record Ref(RefKind kind, String name, boolean current) {}

    /**
     * One commit of the log. {@code date} is git's short ISO date; {@code epochSeconds} backs "3 days ago";
     * {@code parents} are full hashes, first parent first (two or more make it a merge).
     */
    public record Entry(
            String hash,
            String shortHash,
            String subject,
            String author,
            long epochSeconds,
            String date,
            List<String> parents,
            List<Ref> refs) {

        public Entry {
            parents = List.copyOf(parents);
            refs = List.copyOf(refs);
        }

        /** A row whose parents are not known (they only matter to the graph and to reverting a merge). */
        public Entry(
                String hash,
                String shortHash,
                String subject,
                String author,
                long epochSeconds,
                String date,
                List<Ref> refs) {
            this(hash, shortHash, subject, author, epochSeconds, date, List.of(), refs);
        }

        public boolean isMerge() {
            return parents.size() > 1;
        }

        /** The names of the tags pointing at this commit. */
        public List<String> tags() {
            List<String> tags = new ArrayList<>();
            for (Ref ref : refs) {
                if (ref.kind() == RefKind.TAG) {
                    tags.add(ref.name());
                }
            }
            return tags;
        }

        /** A row with no decorations and no timestamp — for callers that only know the classic five fields. */
        public Entry(String hash, String shortHash, String subject, String author, String date) {
            this(hash, shortHash, subject, author, 0L, date, List.of(), List.of());
        }
    }

    /**
     * The commits of one load. {@code truncated} is true when the history holds more commits than were asked
     * for — more can be loaded. {@code followed} is filled for a file history: the file as each commit knew
     * it (its path there, and the path it was renamed from in the commit that renamed it), keyed by commit
     * hash. {@code error} is git's message when the listing failed ({@code ""} otherwise) — a search that
     * timed out is not "no commits".
     */
    public record Page(
            List<Entry> entries, boolean truncated, Map<String, GitService.CommitFile> followed, String error) {
        public static final Page EMPTY = new Page(List.of(), false);

        public Page {
            entries = List.copyOf(entries);
            followed = Map.copyOf(followed);
            error = error == null ? "" : error;
        }

        public Page(List<Entry> entries, boolean truncated) {
            this(entries, truncated, Map.of(), "");
        }

        public static Page failed(String error) {
            return new Page(List.of(), false, Map.of(), error == null || error.isBlank() ? "git log failed" : error);
        }

        /** This page without its first {@code n} commits (the overlap a continuation page is anchored with). */
        public Page drop(int n) {
            return new Page(entries.subList(Math.min(n, entries.size()), entries.size()), truncated, followed, error);
        }
    }

    /**
     * What one page of the log is asked for: every branch, remote and tag ({@code allBranches}) or the
     * checked-out branch; one file's history, followed across renames ({@code file}, absolute; null for the
     * repository); a history search ({@code query}, never null); and the window {@code skip … skip + max}.
     */
    public record Request(boolean allBranches, Path file, GitLogQuery query, int skip, int max) {
        public Request {
            query = query == null ? GitLogQuery.NONE : query;
            skip = Math.max(0, skip);
            max = Math.max(1, max);
        }

        /** Whether the rows are parsed with {@link #parseFollow} rather than {@link #parse}. */
        public boolean follows() {
            return file != null;
        }

        /**
         * Whether the listing is a plain walk of the history — every commit with its real parents — which is
         * what a graph can be drawn for. A file history or a search lists a subset.
         */
        public boolean graphable() {
            return file == null && query.isEmpty();
        }
    }

    /**
     * The {@code git log} arguments (after the hardening prefix) of {@code request}. Pure.
     *
     * <p><b>Order.</b> {@code --date-order}: no commit before all of its children — the one property the
     * graph needs, since a lane is opened by a child and closed by its parent — and otherwise by commit date,
     * so the list still reads newest-first across branches in the all-branches view. {@code --topo-order}
     * also satisfies the graph but lists a whole side branch before returning to the mainline, which puts
     * last week's commits above yesterday's; the default order is not graphable at all (a parent can be shown
     * before a late child).
     *
     * <p><b>Safety.</b> Every piece of user text is attached to its option ({@code --grep=…},
     * {@code --author=…}, {@code -S…}, {@code --since=…}) or comes after {@code --}, so none of it can be
     * read as an option; search patterns are fixed strings, not regular expressions. A history file is a
     * literal pathspec ({@link GitSafety#LITERAL_PATHSPECS}); a {@code path:} pattern is a glob anchored at
     * the repository root with no further pathspec magic.
     */
    public static List<String> logArgs(Request request) {
        GitLogQuery query = request.query();
        List<String> args = new ArrayList<>();
        if (request.file() != null) {
            args.add(GitSafety.LITERAL_PATHSPECS);
        }
        args.addAll(List.of("log", "--no-color", "--decorate=full", "--date=short", "--date-order"));
        if (request.follows()) {
            // The file's own name-status line per commit is how its path at that commit is known.
            args.addAll(List.of("--follow", "--name-status", "-z", FOLLOW_FORMAT));
        } else {
            args.add(FORMAT);
        }
        args.add("-n");
        args.add(String.valueOf(request.max() + 1));
        if (request.skip() > 0) {
            args.add("--skip=" + request.skip());
        }
        if (request.allBranches()) {
            args.add("--all");
        }
        args.addAll(query.options());
        if (request.file() != null) {
            args.add("--");
            args.add(request.file().toAbsolutePath().toString());
        } else if (!query.paths().isEmpty()) {
            args.add("--");
            args.addAll(query.pathspecs());
        }
        return args;
    }

    /**
     * Parses {@link #FORMAT} output. The caller asks git for {@code max + 1} commits: an extra row proves the
     * history goes on, and is dropped from the page.
     */
    public static Page parse(String out, int max) {
        List<Entry> entries = new ArrayList<>();
        boolean truncated = false;
        if (out != null) {
            for (String line : out.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                Entry entry = parseRow(line);
                if (entry == null) {
                    continue;
                }
                if (entries.size() >= max) {
                    truncated = true;
                    break;
                }
                entries.add(entry);
            }
        }
        return new Page(entries, truncated);
    }

    private static Entry parseRow(String line) {
        String[] f = line.split("\t", FIELDS);
        if (f.length < FIELDS) {
            return null;
        }
        String parents = f[5].strip();
        return new Entry(
                f[0].strip(),
                f[1].strip(),
                f[7],
                f[2],
                parseEpoch(f[3]),
                f[4].strip(),
                parents.isEmpty() ? List.of() : List.of(parents.split(" +")),
                parseRefs(f[6]));
    }

    /**
     * Parses a file history: {@code git log --follow --name-status -z} with each commit introduced by
     * {@link #RECORD_START}. A record is the {@link #FORMAT} row, a separator, and the followed file's
     * NUL-separated name-status entry ({@code M path} or {@code R100 old new}) — NUL-separated because that
     * is the only form in which git prints an unusual file name verbatim.
     */
    public static Page parseFollow(String out, int max) {
        List<Entry> entries = new ArrayList<>();
        Map<String, GitService.CommitFile> followed = new LinkedHashMap<>();
        boolean truncated = false;
        if (out != null) {
            for (String record : out.split(String.valueOf(RECORD_START))) {
                // The row ends at the first NUL or newline (a subject holds neither): git puts a NUL, a
                // newline or both between the row and the name-status entry, depending on the format kind.
                int end = 0;
                while (end < record.length() && record.charAt(end) != '\0' && record.charAt(end) != '\n') {
                    end++;
                }
                Entry entry = parseRow(record.substring(0, end));
                if (entry == null) {
                    continue;
                }
                if (entries.size() >= max) {
                    truncated = true;
                    break;
                }
                entries.add(entry);
                while (end < record.length() && (record.charAt(end) == '\0' || record.charAt(end) == '\n')) {
                    end++;
                }
                List<GitService.CommitFile> files = GitService.parseNameStatusZ(record.substring(end));
                if (!files.isEmpty()) {
                    followed.put(entry.hash(), files.get(0));
                }
            }
        }
        return new Page(entries, truncated, followed, "");
    }

    /**
     * Everything the details pane shows of one commit. {@code message} is the whole commit message (subject,
     * blank line, body) without trailing blank lines.
     */
    public record Details(
            String hash,
            List<String> parents,
            String author,
            String authorEmail,
            long authorEpochSeconds,
            String committer,
            String committerEmail,
            long commitEpochSeconds,
            List<Ref> refs,
            String message) {

        public Details {
            parents = List.copyOf(parents);
            refs = List.copyOf(refs);
        }
    }

    /** {@code git show -s} format of {@link #parseDetails}: NUL-separated, the free-form message last. */
    public static final String DETAILS_FORMAT =
            "--format=%H%x00%P%x00%an%x00%ae%x00%at%x00%cn%x00%ce%x00%ct%x00%D%x00%B";

    /** Parses {@link #DETAILS_FORMAT} output; null when it is not one. */
    public static Details parseDetails(String out) {
        if (out == null) {
            return null;
        }
        String[] f = out.split("\0", 10);
        if (f.length < 10 || f[0].isBlank()) {
            return null;
        }
        String parents = f[1].strip();
        return new Details(
                f[0].strip(),
                parents.isEmpty() ? List.of() : List.of(parents.split(" +")),
                f[2],
                f[3],
                parseEpoch(f[4]),
                f[5],
                f[6],
                parseEpoch(f[7]),
                parseRefs(f[8]),
                f[9].stripTrailing());
    }

    private static long parseEpoch(String field) {
        try {
            return Long.parseLong(field.strip());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Parses a {@code %D} decoration list written with full ref names, e.g.
     * {@code HEAD -> refs/heads/main, tag: refs/tags/v1.0, refs/remotes/origin/main}. The symbolic
     * {@code refs/remotes/<remote>/HEAD} is dropped: it only repeats the remote's default branch.
     */
    public static List<Ref> parseRefs(String decorations) {
        if (decorations == null || decorations.isBlank()) {
            return List.of();
        }
        List<Ref> refs = new ArrayList<>();
        for (String raw : decorations.split(", ")) {
            String name = raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            boolean current = false;
            if (name.startsWith("HEAD -> ")) {
                name = name.substring("HEAD -> ".length()).strip();
                current = true;
            } else if (name.equals("HEAD")) {
                refs.add(new Ref(RefKind.HEAD, "HEAD", true));
                continue;
            }
            boolean tag = name.startsWith("tag: ");
            if (tag) {
                name = name.substring("tag: ".length()).strip();
            }
            if (name.startsWith("refs/heads/")) {
                refs.add(new Ref(RefKind.LOCAL, name.substring("refs/heads/".length()), current));
            } else if (name.startsWith("refs/remotes/")) {
                String shortName = name.substring("refs/remotes/".length());
                if (!shortName.endsWith("/HEAD")) {
                    refs.add(new Ref(RefKind.REMOTE, shortName, false));
                }
            } else if (name.startsWith("refs/tags/")) {
                refs.add(new Ref(RefKind.TAG, name.substring("refs/tags/".length()), false));
            } else {
                refs.add(new Ref(
                        tag ? RefKind.TAG : RefKind.OTHER,
                        name.startsWith("refs/") ? name.substring(5) : name,
                        current));
            }
        }
        return refs;
    }
}
