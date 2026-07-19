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
     *  sides. */
    public record FilePatch(
            String oldPath,
            String newPath,
            List<String> oldLines,
            List<String> newLines,
            int additions,
            int deletions) {}

    private static final class Pending {
        String oldPath = "";
        String newPath = "";
        final List<String> oldLines = new ArrayList<>();
        final List<String> newLines = new ArrayList<>();
        int additions;
        int deletions;

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
        List<FilePatch> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        Pending cur = null;
        int oldRemaining = 0;
        int newRemaining = 0;
        boolean inHunk = false;

        for (String line : text.split("\n", -1)) {
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (inHunk && (oldRemaining > 0 || newRemaining > 0)) {
                if (line.equals(NO_NEWLINE_MARKER)) {
                    continue; // doesn't count against either side's remaining count
                }
                char tag = line.isEmpty() ? ' ' : line.charAt(0);
                String rest = line.isEmpty() || line.length() == 1 ? "" : line.substring(1);
                switch (tag) {
                    case '+' -> {
                        cur.newLines.add(rest);
                        cur.additions++;
                        newRemaining--;
                    }
                    case '-' -> {
                        cur.oldLines.add(rest);
                        cur.deletions++;
                        oldRemaining--;
                    }
                    default -> { // ' ' (context) or any unrecognized tag: treat as common to both sides
                        cur.oldLines.add(rest);
                        cur.newLines.add(rest);
                        oldRemaining--;
                        newRemaining--;
                    }
                }
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
                continue;
            }
            if (line.startsWith("--- ") || line.equals("---")) {
                if (cur != null && cur.hasContent()) {
                    out.add(toFilePatch(cur));
                }
                cur = new Pending();
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
                p.oldPath, p.newPath, List.copyOf(p.oldLines), List.copyOf(p.newLines), p.additions, p.deletions);
    }

    /** Strips a trailing {@code \t<timestamp>} (GNU diff) and a leading git {@code a/}/{@code b/} prefix. */
    private static String parseLabel(String s) {
        String v = s.strip();
        int tab = v.indexOf('\t');
        if (tab >= 0) {
            v = v.substring(0, tab).strip();
        }
        if ((v.startsWith("a/") || v.startsWith("b/")) && v.length() > 2) {
            v = v.substring(2);
        }
        return v;
    }
}
