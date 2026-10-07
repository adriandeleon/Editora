package com.editora.git;

import java.util.ArrayList;
import java.util.List;

/**
 * The row model of the Git Log: one {@link Entry} per commit with the ref names that point at it, and a
 * {@link Page} that says whether the list was cut off at its limit.
 *
 * <p>Pure parsing of one {@code git log} invocation ({@link #FORMAT} with {@code --decorate=full}), so it is
 * unit-tested without a repository. The decorations are asked for with full ref names because the short
 * form cannot tell a local branch called {@code origin/x} from the remote-tracking branch {@code origin/x}.
 */
public final class GitLog {

    private GitLog() {}

    /**
     * {@code hash, short hash, author, author time (epoch seconds), author date (short), decorations,
     * subject}, tab-separated. The subject is last because it is the only field that may itself hold a tab.
     */
    public static final String FORMAT = "--pretty=format:%H%x09%h%x09%an%x09%at%x09%ad%x09%D%x09%s";

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

    /** One commit of the log. {@code date} is git's short ISO date; {@code epochSeconds} backs "3 days ago". */
    public record Entry(
            String hash,
            String shortHash,
            String subject,
            String author,
            long epochSeconds,
            String date,
            List<Ref> refs) {

        public Entry {
            refs = List.copyOf(refs);
        }

        /** A row with no decorations and no timestamp — for callers that only know the classic five fields. */
        public Entry(String hash, String shortHash, String subject, String author, String date) {
            this(hash, shortHash, subject, author, 0L, date, List.of());
        }
    }

    /**
     * The commits of one load. {@code truncated} is true when the history holds more commits than were asked
     * for — the list is then the newest {@code entries.size()} only, and the view must say so.
     */
    public record Page(List<Entry> entries, boolean truncated) {
        public static final Page EMPTY = new Page(List.of(), false);

        public Page {
            entries = List.copyOf(entries);
        }
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
                String[] f = line.split("\t", 7);
                if (f.length < 7) {
                    continue;
                }
                if (entries.size() >= max) {
                    truncated = true;
                    break;
                }
                entries.add(new Entry(
                        f[0].strip(), f[1].strip(), f[6], f[2], parseEpoch(f[3]), f[4].strip(), parseRefs(f[5])));
            }
        }
        return new Page(entries, truncated);
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
