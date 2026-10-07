package com.editora.diff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a unified diff ({@code .patch}/{@code .diff} text, e.g. {@code git diff}/{@code diff -u} output or
 * {@link PatchWriter}'s own format) back into per-file reconstructed line sequences — the reverse of
 * {@link PatchWriter}. The reconstructed {@code oldLines}/{@code newLines} feed straight into the existing
 * {@link DiffEngine#compute(List, List)} pipeline, so the diff viewer needs no new row type: the patch's own
 * add/remove/context lines become the input to a fresh Myers diff, which reproduces the same change (word-
 * level highlighting included) rather than requiring a hand-rolled hunk-to-row converter.
 *
 * <p>Tolerant of a bare single-file unified diff (starts directly with {@code ---}/{@code +++}), a git-style
 * {@code diff --git a/x b/x} preamble (plus {@code index}/mode/rename lines, all skipped), and several files
 * back to back. A hunk's declared {@code @@ -a,b +c,d @@} line counts gate exactly how many subsequent lines
 * are hunk content — so a content line that happens to start with {@code ---}/{@code +++} (e.g. removing a
 * Markdown front-matter delimiter) is never mistaken for the next file's header. Pure + unit-tested.
 */
public final class PatchParser {

    private PatchParser() {}

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@.*$");
    private static final String NO_NEWLINE_MARKER = "\\ No newline at end of file";

    /** One file's reconstructed diff. {@code oldPath}/{@code newPath} come from the {@code ---}/{@code +++}
     *  header lines, with a git {@code a/}/{@code b/} prefix stripped and any {@code /dev/null} (add/delete)
     *  side left as-is; either may be {@code ""} when the header was missing entirely. {@code additions}/
     *  {@code deletions} count the {@code +}/{@code -} tagged hunk lines — the true diff stat, unlike
     *  {@code oldLines.size()}/{@code newLines.size()} which also include the context lines carried on both
     *  sides. {@code oldLineNumbers}/{@code newLineNumbers} give each reconstructed line the number its hunk
     *  header ({@code @@ -a,b +c,d @@}) places it at in the real file — the lines of every hunk are held back
     *  to back, so their index is not their line number; empty when unknown. */
    public record FilePatch(
            String oldPath,
            String newPath,
            List<String> oldLines,
            List<String> newLines,
            int additions,
            int deletions,
            boolean oldFinalNewline,
            boolean newFinalNewline,
            List<Integer> oldLineNumbers,
            List<Integer> newLineNumbers) {

        public FilePatch {
            oldLineNumbers = List.copyOf(oldLineNumbers == null ? List.of() : oldLineNumbers);
            newLineNumbers = List.copyOf(newLineNumbers == null ? List.of() : newLineNumbers);
        }

        public FilePatch(
                String oldPath,
                String newPath,
                List<String> oldLines,
                List<String> newLines,
                int additions,
                int deletions,
                boolean oldFinalNewline,
                boolean newFinalNewline) {
            this(
                    oldPath,
                    newPath,
                    oldLines,
                    newLines,
                    additions,
                    deletions,
                    oldFinalNewline,
                    newFinalNewline,
                    List.of(),
                    List.of());
        }

        public FilePatch(
                String oldPath,
                String newPath,
                List<String> oldLines,
                List<String> newLines,
                int additions,
                int deletions) {
            this(oldPath, newPath, oldLines, newLines, additions, deletions, true, true);
        }
    }

    private static final class Pending {
        String oldPath = "";
        String newPath = "";
        final List<String> oldLines = new ArrayList<>();
        final List<String> newLines = new ArrayList<>();
        final List<Integer> oldLineNumbers = new ArrayList<>();
        final List<Integer> newLineNumbers = new ArrayList<>();
        int oldLine = 1;
        int newLine = 1;
        int additions;
        int deletions;
        boolean oldFinalNewline = true;
        boolean newFinalNewline = true;
        /** Opened by a {@code diff --git} line whose {@code ---}/{@code +++} pair has not been seen (yet). */
        boolean gitSectionOpen;

        boolean hasContent() {
            return !oldLines.isEmpty() || !newLines.isEmpty() || !oldPath.isEmpty() || !newPath.isEmpty();
        }
    }

    /**
     * Parses {@code text} into one {@link FilePatch} per file section, in order. Lines outside any
     * recognized structure ({@code diff --git}, {@code index}, mode/rename lines, binary-file notices, a
     * blank prelude, …) are skipped. Returns an empty list when nothing resembling a unified diff is found.
     */
    public static List<FilePatch> parse(String text) {
        return parse(text, false);
    }

    /**
     * {@link #parse(String)} that also returns the {@code diff --git} sections with <em>no hunks</em> — a pure
     * rename, a mode-only change, a binary file — as a {@link FilePatch} with no lines, its paths taken from
     * the section's {@code diff --git} / {@code rename from|to} / {@code new|deleted file mode} lines. A review
     * of a change set must list every file it touches, not only the ones with text hunks.
     */
    public static List<FilePatch> parseAllSections(String text) {
        return parse(text, true);
    }

    private static List<FilePatch> parse(String text, boolean hunklessSections) {
        List<FilePatch> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Pending cur = null;
        int oldRemaining = 0;
        int newRemaining = 0;
        boolean inHunk = false;
        char lastTag = 0;

        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.equals(NO_NEWLINE_MARKER)) {
                if (cur != null && lastTag == '-') {
                    cur.oldFinalNewline = false;
                } else if (cur != null && lastTag == '+') {
                    cur.newFinalNewline = false;
                } else if (cur != null && lastTag == ' ') {
                    cur.oldFinalNewline = false;
                    cur.newFinalNewline = false;
                }
                continue;
            }
            if (inHunk && (oldRemaining > 0 || newRemaining > 0)) {
                char tag = line.isEmpty() ? ' ' : line.charAt(0);
                String rest = line.isEmpty() || line.length() == 1 ? "" : line.substring(1);
                switch (tag) {
                    case '+' -> {
                        cur.newLines.add(rest);
                        cur.newLineNumbers.add(cur.newLine++);
                        cur.additions++;
                        newRemaining--;
                    }
                    case '-' -> {
                        cur.oldLines.add(rest);
                        cur.oldLineNumbers.add(cur.oldLine++);
                        cur.deletions++;
                        oldRemaining--;
                    }
                    default -> { // ' ' (context) or any unrecognized tag: treat as common to both sides
                        cur.oldLines.add(rest);
                        cur.newLines.add(rest);
                        cur.oldLineNumbers.add(cur.oldLine++);
                        cur.newLineNumbers.add(cur.newLine++);
                        oldRemaining--;
                        newRemaining--;
                    }
                }
                lastTag = tag;
                if (oldRemaining <= 0 && newRemaining <= 0) {
                    inHunk = false;
                }
                continue;
            }
            inHunk = false;

            Matcher hm = HUNK_HEADER.matcher(line);
            if (hm.matches()) {
                if (cur == null) {
                    cur = new Pending(); // a hunk with no preceding ---/+++ (malformed but salvageable)
                }
                oldRemaining = hm.group(2) != null ? Integer.parseInt(hm.group(2)) : 1;
                newRemaining = hm.group(4) != null ? Integer.parseInt(hm.group(4)) : 1;
                inHunk = oldRemaining > 0 || newRemaining > 0;
                cur.oldLine = Math.max(1, Integer.parseInt(hm.group(1)));
                cur.newLine = Math.max(1, Integer.parseInt(hm.group(3)));
                continue;
            }
            if (hunklessSections && line.startsWith("diff --git ")) {
                if (cur != null && cur.hasContent()) {
                    out.add(toFilePatch(cur));
                }
                cur = new Pending();
                cur.gitSectionOpen = true;
                gitHeaderPaths(line.substring("diff --git ".length()), cur);
                continue;
            }
            if (cur != null && cur.gitSectionOpen && gitExtendedHeader(line, cur)) {
                continue;
            }
            if (line.startsWith("--- ") || line.equals("---")) {
                if (cur != null && cur.gitSectionOpen) {
                    cur.gitSectionOpen = false; // this section's own ---/+++ pair: same file, not a new one
                } else {
                    if (cur != null && cur.hasContent()) {
                        out.add(toFilePatch(cur));
                    }
                    cur = new Pending();
                }
                cur.oldPath = parseLabel(line.length() > 3 ? line.substring(4) : "");
                continue;
            }
            if (line.startsWith("+++ ") || line.equals("+++")) {
                if (cur == null) {
                    cur = new Pending();
                }
                cur.newPath = parseLabel(line.length() > 3 ? line.substring(4) : "");
                continue;
            }
            // Anything else (diff --git, index, mode/rename lines, "Binary files … differ", a blank
            // prelude line, …) carries no diffable content — skip it.
        }
        if (cur != null && cur.hasContent()) {
            out.add(toFilePatch(cur));
        }
        return out;
    }

    private static FilePatch toFilePatch(Pending p) {
        return new FilePatch(
                p.oldPath,
                p.newPath,
                List.copyOf(p.oldLines),
                List.copyOf(p.newLines),
                p.additions,
                p.deletions,
                p.oldFinalNewline,
                p.newFinalNewline,
                p.oldLineNumbers,
                p.newLineNumbers);
    }

    /** The two paths of a {@code diff --git a/<old> b/<new>} line (either may be C-quoted). */
    private static void gitHeaderPaths(String rest, Pending into) {
        String oldLabel;
        String newLabel;
        int split = -1;
        if (rest.startsWith("\"")) {
            for (int i = 1; i < rest.length(); i++) { // the closing quote of the first label
                if (rest.charAt(i) == '\\') {
                    i++;
                } else if (rest.charAt(i) == '"') {
                    split = i + 1;
                    break;
                }
            }
        } else if (rest.length() % 2 == 1 && sameAfterPrefix(rest, rest.length() / 2)) {
            split = rest.length() / 2; // "a/P b/P": the usual case, exact even when P contains spaces
        } else {
            split = rest.indexOf(" b/");
            if (split < 0) {
                split = rest.indexOf(" \"b/");
            }
        }
        if (split <= 0 || split >= rest.length()) {
            return; // unrecognized: the section's later lines (rename from/to, ---/+++) may still name it
        }
        oldLabel = rest.substring(0, split);
        newLabel = rest.substring(split + 1);
        into.oldPath = parseLabel(oldLabel);
        into.newPath = parseLabel(newLabel);
    }

    private static boolean sameAfterPrefix(String rest, int middle) {
        return rest.charAt(middle) == ' '
                && rest.startsWith("a/")
                && rest.startsWith("b/", middle + 1)
                && rest.regionMatches(2, rest, middle + 3, middle - 2);
    }

    /** Reads one git extended-header line of an open section; {@code false} when {@code line} is not one. */
    private static boolean gitExtendedHeader(String line, Pending cur) {
        if (line.startsWith("rename from ") || line.startsWith("copy from ")) {
            cur.oldPath = unquote(line.substring(line.indexOf(" from ") + 6));
        } else if (line.startsWith("rename to ") || line.startsWith("copy to ")) {
            cur.newPath = unquote(line.substring(line.indexOf(" to ") + 4));
        } else if (line.startsWith("new file mode ")) {
            cur.oldPath = "/dev/null";
        } else if (line.startsWith("deleted file mode ")) {
            cur.newPath = "/dev/null";
        } else {
            return false;
        }
        return true;
    }

    /**
     * Decodes git's C-style quoted path ({@code "caf\303\251.txt"} → {@code café.txt}); anything not wrapped in
     * double quotes is returned unchanged. The octal escapes are raw UTF-8 bytes.
     */
    static String unquote(String field) {
        String v = field.strip();
        if (v.length() < 2 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') {
            return v;
        }
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        for (int i = 1; i < v.length() - 1; i++) {
            char c = v.charAt(i);
            if (c != '\\' || i + 2 >= v.length()) {
                bytes.writeBytes(String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                continue;
            }
            char e = v.charAt(++i);
            if (e >= '0' && e <= '7') {
                int value = e - '0';
                for (int k = 0; k < 2 && i + 2 < v.length() && v.charAt(i + 1) >= '0' && v.charAt(i + 1) <= '7'; k++) {
                    value = (value << 3) | (v.charAt(++i) - '0');
                }
                bytes.write(value);
            } else {
                bytes.write(
                        switch (e) {
                            case 'n' -> '\n';
                            case 't' -> '\t';
                            case 'r' -> '\r';
                            case 'a' -> 7;
                            case 'b' -> '\b';
                            case 'f' -> '\f';
                            case 'v' -> 11;
                            default -> e; // \" and \\\\
                        });
            }
        }
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Strips a trailing {@code \t<timestamp>} (GNU diff) and a leading git {@code a/}/{@code b/} prefix, after
     * undoing git's C-quoting of a non-ASCII / special name.
     */
    private static String parseLabel(String s) {
        String v = s.strip();
        int tab = v.indexOf('\t');
        if (tab >= 0) {
            v = v.substring(0, tab).strip();
        }
        v = unquote(v);
        if ((v.startsWith("a/") || v.startsWith("b/")) && v.length() > 2) {
            v = v.substring(2);
        }
        return v;
    }
}
