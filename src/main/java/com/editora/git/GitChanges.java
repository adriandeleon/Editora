package com.editora.git;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The working tree's changes against HEAD, file by file, as the Project Map's Changes card shows them (Map code browser
 * round 2): each file's hunks with their context lines, numbered as in the old and the new file. Parsed from one {@code
 * git diff --relative -U3 HEAD} of a folder, the untracked files added whole. Pure.
 */
public final class GitChanges {

    /** The most files one reading keeps; it says when there were more. */
    public static final int MAX_FILES = 500;
    /** The most lines a file's diff keeps; its counts stay whole. */
    public static final int MAX_LINES = 5_000;

    /** What a line of a diff is. */
    public enum Kind {
        /** A hunk's {@code @@ … @@} header. */
        HUNK,
        CONTEXT,
        ADDED,
        REMOVED,
        /** Git's "\ No newline at end of file". */
        NOTE
    }

    /**
     * One line of a file's diff: its 1-based line in the old and the new file (0: none), and its text; a hunk's header
     * holds the lines the hunk starts at.
     */
    public record Line(Kind kind, int oldLine, int newLine, String text) {}

    /**
     * One changed file: its path relative to the folder read, {@code status} (M modified, A added, D deleted, U
     * untracked), its diff's lines, where each hunk's header is among them, how many lines it adds and removes, whether
     * it is binary, and whether its lines were cut at {@link #MAX_LINES}.
     */
    public record FileDiff(
            String path,
            char status,
            List<Line> lines,
            List<Integer> hunks,
            int added,
            int removed,
            boolean binary,
            boolean truncated) {
        public FileDiff {
            lines = List.copyOf(lines);
            hunks = List.copyOf(hunks);
        }

        /**
         * The line of the new file hunk {@code index} starts at, 1-based: its first added line, else the line after
         * the removal (at least 1).
         */
        public int hunkLine(int index) {
            int at = hunks.get(index);
            for (int i = at + 1; i < lines.size() && lines.get(i).kind() != Kind.HUNK; i++) {
                Line line = lines.get(i);
                if (line.kind() == Kind.ADDED) {
                    return line.newLine();
                }
                if (line.kind() == Kind.REMOVED) {
                    return nearestNewLine(i);
                }
            }
            return Math.max(1, lines.get(at).newLine());
        }

        /**
         * The 1-based line of the new file the diff's line {@code index} (not a hunk's header) stands for: its own, or
         * for a removed line the next line that is in the new file, else the one after the last before it.
         */
        public int nearestNewLine(int index) {
            Line here = lines.get(index);
            if (here.newLine() > 0) {
                return here.newLine();
            }
            for (int i = index + 1; i < lines.size() && lines.get(i).kind() != Kind.HUNK; i++) {
                if (lines.get(i).newLine() > 0) {
                    return lines.get(i).newLine();
                }
            }
            for (int i = index - 1; ; i--) {
                Line before = lines.get(i);
                if (before.kind() == Kind.HUNK) {
                    return Math.max(1, before.newLine()); // a hunk's header holds where it starts
                }
                if (before.newLine() > 0) {
                    return before.newLine() + 1;
                }
            }
        }
    }

    /**
     * A reading: the changed files in path order, whether there were more than {@link #MAX_FILES} (or more output than
     * could be read), why there are none when Git could not answer ({@code error} not empty), and the commit the
     * working tree was compared with ({@code base}: {@code HEAD}, or a commit's id; Map gaps round 3, item 3).
     */
    public record Result(List<FileDiff> files, boolean truncated, String error, String base) {
        public static final Result UNAVAILABLE = new Result(List.of(), false, "unavailable");

        public Result {
            files = List.copyOf(files);
        }

        public Result(List<FileDiff> files, boolean truncated, String error) {
            this(files, truncated, error, "HEAD");
        }

        /** This reading, compared with {@code commit}. */
        public Result withBase(String commit) {
            return new Result(files, truncated, error, commit);
        }

        public boolean ok() {
            return error.isEmpty();
        }

        /** The file at {@code path} (relative to the folder read), or null. */
        public @Nullable FileDiff file(String path) {
            for (FileDiff file : files) {
                if (file.path().equals(path)) {
                    return file;
                }
            }
            return null;
        }
    }

    private static final Pattern HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

    private GitChanges() {}

    /** Every file of a {@code git diff} patch, in the order the patch has them. */
    public static List<FileDiff> parse(String patch) {
        List<FileDiff> files = new ArrayList<>();
        if (patch.isEmpty()) {
            return files;
        }
        Builder current = null;
        for (String raw : patch.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("diff --git ")) {
                if (current != null) {
                    files.add(current.build());
                }
                current = new Builder(headerPath(line.substring("diff --git ".length())));
                continue;
            }
            if (current == null) {
                continue;
            }
            current.accept(line);
        }
        if (current != null) {
            files.add(current.build());
        }
        return files;
    }

    /** An untracked file at {@code path}, all of {@code text} added; binary when it holds a NUL. */
    public static FileDiff untracked(String path, String text, boolean truncated) {
        if (text.indexOf('\0') >= 0) {
            return new FileDiff(path, 'U', List.of(), List.of(), 0, 0, true, truncated);
        }
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        String[] parts = body.isEmpty() ? new String[0] : body.split("\n", -1);
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(Kind.HUNK, 0, 1, "@@ -0,0 +1," + parts.length + " @@"));
        boolean cut = truncated;
        for (int i = 0; i < parts.length; i++) {
            if (lines.size() > MAX_LINES) {
                cut = true;
                break;
            }
            String part = parts[i].endsWith("\r") ? parts[i].substring(0, parts[i].length() - 1) : parts[i];
            lines.add(new Line(Kind.ADDED, 0, i + 1, part));
        }
        return new FileDiff(
                path,
                'U',
                parts.length == 0 ? List.of() : lines,
                parts.length == 0 ? List.of() : List.of(0),
                parts.length,
                0,
                false,
                cut);
    }

    /** The tracked and untracked files together in path order, at most {@link #MAX_FILES}. */
    public static Result merge(List<FileDiff> tracked, List<FileDiff> untracked, boolean outputTruncated) {
        List<FileDiff> all = new ArrayList<>(tracked);
        all.addAll(untracked);
        all.sort(Comparator.comparing(FileDiff::path));
        boolean more = all.size() > MAX_FILES;
        return new Result(more ? all.subList(0, MAX_FILES) : all, more || outputTruncated, "");
    }

    /** The path of a {@code diff --git a/P b/P} header (renames are off: both sides are the same path). */
    static String headerPath(String rest) {
        if (rest.startsWith("\"")) {
            List<String> parts = quotedParts(rest);
            String first = parts.isEmpty() ? rest : parts.get(0);
            return first.startsWith("a/") ? first.substring(2) : first;
        }
        int length = rest.length();
        int half = (length - 5) / 2;
        if (half > 0 && rest.startsWith("a/") && rest.substring(2 + half).equals(" b/" + rest.substring(2, 2 + half))) {
            return rest.substring(2, 2 + half);
        }
        int split = rest.indexOf(" b/");
        return rest.startsWith("a/") && split > 2 ? rest.substring(2, split) : rest;
    }

    /** The C-quoted strings of {@code text} ({@code "a/x\tz" "b/x\tz"}), unquoted, UTF-8 octal escapes included. */
    static List<String> quotedParts(String text) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '"') {
                i++;
                continue;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            i++;
            while (i < text.length() && text.charAt(i) != '"') {
                char c = text.charAt(i);
                if (c == '\\' && i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (next >= '0' && next <= '7' && i + 3 < text.length()) {
                        bytes.write(Integer.parseInt(text.substring(i + 1, i + 4), 8));
                        i += 4;
                        continue;
                    }
                    bytes.write(
                            switch (next) {
                                case 't' -> '\t';
                                case 'n' -> '\n';
                                case 'r' -> '\r';
                                default -> next;
                            });
                    i += 2;
                    continue;
                }
                byte[] encoded = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(encoded, 0, encoded.length);
                i++;
            }
            parts.add(bytes.toString(StandardCharsets.UTF_8));
            i++;
        }
        return parts;
    }

    /** One file's diff as it is read. */
    private static final class Builder {
        private final String path;
        private char status = 'M';
        private final List<Line> lines = new ArrayList<>();
        private final List<Integer> hunks = new ArrayList<>();
        private int added;
        private int removed;
        private boolean binary;
        private boolean truncated;
        private boolean inHunk;
        private int oldLine;
        private int newLine;

        Builder(String path) {
            this.path = path;
        }

        void accept(String line) {
            if (line.startsWith("@@")) {
                Matcher m = HEADER.matcher(line);
                if (m.find()) {
                    inHunk = true;
                    oldLine = Integer.parseInt(m.group(1));
                    newLine = Integer.parseInt(m.group(3));
                    if (add(new Line(Kind.HUNK, oldLine, newLine, line))) {
                        hunks.add(lines.size() - 1);
                    }
                }
                return;
            }
            if (!inHunk) {
                if (line.startsWith("new file mode")) {
                    status = 'A';
                } else if (line.startsWith("deleted file mode")) {
                    status = 'D';
                } else if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) {
                    binary = true;
                }
                return;
            }
            if (line.isEmpty()) {
                return; // the patch's final newline; a context line keeps its leading space
            }
            switch (line.charAt(0)) {
                case ' ' -> add(new Line(Kind.CONTEXT, oldLine++, newLine++, line.substring(1)));
                case '+' -> {
                    added++;
                    add(new Line(Kind.ADDED, 0, newLine++, line.substring(1)));
                }
                case '-' -> {
                    removed++;
                    add(new Line(Kind.REMOVED, oldLine++, 0, line.substring(1)));
                }
                case '\\' -> add(new Line(Kind.NOTE, 0, 0, line.substring(1).strip()));
                default -> {}
            }
        }

        private boolean add(Line line) {
            if (lines.size() >= MAX_LINES) {
                truncated = true;
                return false;
            }
            lines.add(line);
            return true;
        }

        FileDiff build() {
            return new FileDiff(path, status, lines, hunks, added, removed, binary, truncated);
        }
    }
}
