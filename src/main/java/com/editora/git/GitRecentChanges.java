package com.editora.git;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * How often each file and folder of a project changed lately: the commits of the last {@link #DAYS} days that touch
 * it, and who made the newest one (the Project Map's "Show recent changes", Map explore position 3). Read from one
 * streamed {@code git log -M --name-status} ({@link #logArgs}) by {@link Parser}, which is pure and unit-tested. A
 * renamed file keeps its history: the commits from before the move are counted for its newest path (Map review 4,
 * B2), and so for the folders it is in now.
 *
 * <p>The work is capped: at most {@link #MAX_COMMITS} commits and {@link #MAX_PATH_LINES} file lines are read, within
 * {@link #BUDGET}; past any of them the result is the newest part, flagged {@link Result#truncated()}. Merges are left
 * out (their files are their parents' changes). In a shallow clone the boundary commits — grafted, so without parents
 * — are skipped: git lists every file of their tree as added, and one {@code --depth 1} clone would otherwise show
 * every file as changed once.
 */
public final class GitRecentChanges {

    /** The window of "recent", in days. */
    public static final int DAYS = 90;
    /** The most commits read. */
    public static final int MAX_COMMITS = 10_000;
    /** The most file lines read (all commits together); below the process runner's 10 MB line capture. */
    public static final int MAX_PATH_LINES = 150_000;
    /** The longest the log may run. */
    public static final Duration BUDGET = Duration.ofSeconds(20);

    /** Starts a commit's header line ({@code %x01}): a file name never starts with it. */
    static final char HEADER = '\u0001';

    /** One file's or folder's recent history: how many commits, and the newest one's time and author. */
    public record Stat(int commits, long lastEpochSeconds, String lastAuthor) {}

    /**
     * What was read for one HEAD. Paths are {@code /}-separated and relative to the folder the log ran in; {@code ""}
     * is that folder itself. {@code direct} counts, per folder, the commits that touch a file directly in it (not in a
     * subfolder): where the work happens. {@code shallow}: the repository is a shallow clone, its history cut at
     * {@code boundaryEpochSeconds} (the newest grafted commit; 0 when none was within the window). {@code problem} is
     * empty unless git failed.
     */
    public record Result(
            String head,
            boolean shallow,
            int commits,
            boolean truncated,
            Map<String, Stat> files,
            Map<String, Stat> folders,
            Map<String, Stat> direct,
            long boundaryEpochSeconds,
            String problem) {

        /** Not a repository, an unborn branch, or no git: nothing to show. */
        public static final Result NONE = new Result("", false, 0, false, Map.of(), Map.of(), Map.of(), 0, "");

        public Result {
            files = Map.copyOf(files);
            folders = Map.copyOf(folders);
            direct = Map.copyOf(direct);
        }

        static Result failed(String head, String problem) {
            return new Result(head, false, 0, false, Map.of(), Map.of(), Map.of(), 0, problem);
        }

        /**
         * The stat of the file or folder at {@code rel}, or null when it did not change in the window. A folder row
         * that git tracks as one entry (a submodule's pointer, a gitlink) has the stat of that entry (B12).
         */
        public @Nullable Stat stat(String rel, boolean folder) {
            if (!folder) {
                return files.get(rel);
            }
            Stat stat = folders.get(rel);
            return stat != null ? stat : files.get(rel);
        }
    }

    private GitRecentChanges() {}

    /**
     * The {@code git log} arguments (after the global options) for the last {@code days} days below the working
     * directory: no merges, renames detected ({@code -M}: a moved file keeps its count; measured cheaper than the
     * plain read on Editora's history), names relative to the directory.
     */
    public static List<String> logArgs(int days) {
        return List.of(
                "-c",
                "core.quotePath=false",
                "log",
                "--since=" + days + ".days",
                "--no-merges",
                "-M",
                "--no-color",
                "--name-status",
                "--format=%x01%H%x09%P%x09%at%x09%an",
                "--relative",
                "--",
                ".");
    }

    /**
     * Reads the log one line at a time, newest commit first, and adds every commit to its files and to each folder
     * above them (once per commit). A file line is {@code <status>\t<path>}, or {@code R<score>\t<old>\t<new>} for
     * a rename; a bare path (a {@code --name-only} line) is taken as it is. Because the log runs newest first, a
     * rename is seen before the older commits of its old path: those are credited to the new path, through every
     * later rename. Names git quotes (a {@code "}, a {@code \\} or a control character in them) are unquoted.
     * {@link #accept} answers true when a cap is reached and the rest should not be read. Thread-safe: the lines
     * arrive on the process reader's thread.
     */
    public static final class Parser {

        private final boolean shallow;
        private final Map<String, Acc> files = new HashMap<>();
        private final Map<String, Acc> folders = new HashMap<>();
        private final Map<String, Acc> direct = new HashMap<>();
        private final List<String> current = new ArrayList<>();
        /** Old path to the newest path it was renamed to, as far as the log was read. */
        private final Map<String, String> renamed = new HashMap<>();

        private @Nullable String author;
        private long epoch;
        private boolean skipping;
        private int commits;
        private int lines;
        private boolean truncated;
        private long boundary;

        /** {@code shallow}: the repository is a shallow clone, so a commit without parents is its cut, skipped. */
        public Parser(boolean shallow) {
            this.shallow = shallow;
        }

        /** One line of the log; true once a cap is reached (stop reading). */
        public synchronized boolean accept(String line) {
            if (truncated) {
                return true;
            }
            if (!line.isEmpty() && line.charAt(0) == HEADER) {
                flush();
                if (commits >= MAX_COMMITS) {
                    truncated = true;
                    return true;
                }
                String[] fields = line.substring(1).split("\t", 4);
                if (fields.length < 4) {
                    author = null;
                    return false;
                }
                epoch = parseLong(fields[2]);
                author = fields[3];
                skipping = shallow && fields[1].isBlank();
                if (skipping) {
                    boundary = Math.max(boundary, epoch);
                }
                return false;
            }
            if (line.isBlank() || author == null || skipping) {
                return false;
            }
            if (++lines > MAX_PATH_LINES) {
                truncated = true;
                current.clear(); // a commit read only in part is left out, not counted for the files that fit
                return true;
            }
            fileLine(line);
            return false;
        }

        /** One file line of the current commit: its path, as now named, joins the commit's files. */
        private void fileLine(String line) {
            int tab = line.indexOf('\t');
            if (tab < 0) {
                addPath(line); // --name-only
                return;
            }
            String[] fields = line.split("\t", 3);
            if (fields.length == 3 && (fields[0].startsWith("R") || fields[0].startsWith("C"))) {
                String from = unquote(fields[1]);
                String to = unquote(fields[2]);
                if (from == null || to == null) {
                    return;
                }
                String now = renamed.getOrDefault(to, to);
                if (fields[0].startsWith("R") && !from.equals(now)) {
                    renamed.put(from, now); // the old path's older commits are the file's history
                }
                current.add(now);
                return;
            }
            addPath(fields[fields.length - 1]);
        }

        private void addPath(String raw) {
            String path = unquote(raw);
            if (path != null && !path.isEmpty()) {
                current.add(renamed.getOrDefault(path, path));
            }
        }

        /** Marks the result as cut short (the budget ran out before the log ended). */
        public synchronized void cutShort() {
            truncated = true;
        }

        /** The commits read so far, the last one included. */
        public synchronized Result finish(String head) {
            flush();
            return new Result(
                    head, shallow, commits, truncated, frozen(files), frozen(folders), frozen(direct), boundary, "");
        }

        private void flush() {
            String who = author;
            if (who == null || skipping) {
                current.clear();
                return;
            }
            if (current.isEmpty()) {
                author = null; // a commit that changed nothing here
                return;
            }
            commits++;
            Set<String> above = new HashSet<>();
            Set<String> own = new HashSet<>();
            above.add("");
            for (String rel : new java.util.LinkedHashSet<>(current)) { // a path named twice is one change
                add(files, rel, who);
                int slash = rel.lastIndexOf('/');
                own.add(slash < 0 ? "" : rel.substring(0, slash));
                while (slash > 0) {
                    String folder = rel.substring(0, slash);
                    if (!above.add(folder)) {
                        break; // this folder and those above it are counted for this commit already
                    }
                    slash = folder.lastIndexOf('/');
                }
            }
            for (String folder : above) {
                add(folders, folder, who);
            }
            for (String folder : own) {
                add(direct, folder, who);
            }
            current.clear();
            author = null;
        }

        private void add(Map<String, Acc> into, String rel, String who) {
            Acc acc = into.get(rel);
            if (acc == null) {
                // Newest first: the first commit seen for a path is its last change.
                into.put(rel, new Acc(epoch, who));
            } else {
                acc.commits++;
            }
        }

        private static Map<String, Stat> frozen(Map<String, Acc> accs) {
            Map<String, Stat> out = new HashMap<>(accs.size() * 2);
            accs.forEach((rel, acc) -> out.put(rel, new Stat(acc.commits, acc.lastEpoch, acc.lastAuthor)));
            return out;
        }

        /**
         * A path as git wrote it: unchanged, or unquoted when git quoted it ({@code "..."} with C escapes and octal
         * bytes, which are UTF-8). Null when the quoting cannot be read.
         */
        static @Nullable String unquote(String text) {
            if (text.length() < 2 || text.charAt(0) != '"' || text.charAt(text.length() - 1) != '"') {
                return text;
            }
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(text.length());
            for (int i = 1; i < text.length() - 1; i++) {
                char c = text.charAt(i);
                if (c != '\\') {
                    byte[] utf8 = String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if (Character.isHighSurrogate(c) && i + 2 < text.length()) {
                        utf8 = text.substring(i, i + 2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        i++;
                    }
                    bytes.writeBytes(utf8);
                    continue;
                }
                if (++i >= text.length() - 1) {
                    return null;
                }
                char escaped = text.charAt(i);
                switch (escaped) {
                    case 'a' -> bytes.write(7);
                    case 'b' -> bytes.write(8);
                    case 't' -> bytes.write('\t');
                    case 'n' -> bytes.write('\n');
                    case 'v' -> bytes.write(11);
                    case 'f' -> bytes.write(12);
                    case 'r' -> bytes.write('\r');
                    case '"', '\\' -> bytes.write(escaped);
                    default -> {
                        int end = i + 3; // an octal byte: three digits
                        if (escaped < '0' || escaped > '7' || end > text.length() - 1) {
                            return null;
                        }
                        try {
                            bytes.write(Integer.parseInt(text.substring(i, end), 8));
                        } catch (NumberFormatException unreadable) {
                            return null;
                        }
                        i = end - 1;
                    }
                }
            }
            return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        private static long parseLong(String text) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }

    private static final class Acc {
        int commits = 1;
        final long lastEpoch;
        final String lastAuthor;

        Acc(long lastEpoch, String lastAuthor) {
            this.lastEpoch = lastEpoch;
            this.lastAuthor = lastAuthor;
        }
    }
}
