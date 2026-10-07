package com.editora.diff;

import java.util.ArrayList;
import java.util.List;

import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.ChangeDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.DeleteDelta;
import com.github.difflib.patch.InsertDelta;
import com.github.difflib.patch.Patch;

/**
 * Generates a unified diff ({@code .patch}) between two texts via java-diff-utils, for the diff viewer's
 * "Export patch" action (SDD Phase 4). Pure and unit-tested.
 *
 * <p>Lines are split on {@code \n} alone, so carriage returns are line content and survive into the patch.
 *
 * <p>A final line with no terminator is a different line from the same text <em>with</em> one, and a patch
 * must say so with a {@code \ No newline at end of file} marker after exactly that line. Rather than patch
 * markers into the generated hunks afterwards, the unterminated last line of each side is given a distinct
 * identity <em>before</em> diffing (a trailing {@code \n}, which a split line can never contain). The diff
 * engine then produces the right shape for every case by itself: the line is context only when both sides
 * end with it unterminated, and otherwise becomes a {@code -line}/{@code +line} pair inside the hunk that
 * already covers it — never a second, overlapping hunk.
 */
public final class PatchWriter {

    private PatchWriter() {}

    private static final int CONTEXT = 3;
    /** Cannot occur inside a line (lines are split on it), so it marks "this last line is unterminated". */
    private static final String UNTERMINATED = "\n";

    /**
     * Above this many line edits the Myers search is abandoned for one whole-middle hunk, as in
     * {@link DiffEngine}: unbounded, exporting a re-indented 40,000-line file took 27 seconds.
     */
    static final int MAX_LINE_EDITS = DiffEngine.MAX_LINE_EDITS;

    private static final String NO_NEWLINE = "\\ No newline at end of file";

    /**
     * A unified diff between {@code left} and {@code right} with {@code git}-style {@code a/}, {@code b/}
     * file labels. Returns an empty string when the two are identical (no hunks).
     */
    public static String unifiedDiff(String leftLabel, String rightLabel, String leftText, String rightText) {
        return unifiedDiff(leftLabel, rightLabel, leftText, rightText, MAX_LINE_EDITS);
    }

    static String unifiedDiff(String leftLabel, String rightLabel, String leftText, String rightText, int maxEdits) {
        List<String> left = eofAwareLines(leftText);
        List<String> right = eofAwareLines(rightText);
        Patch<String> patch = BoundedDiff.diff(left, right, maxEdits);
        if (patch == null) {
            patch = changedMiddle(left, right);
        }
        if (patch.getDeltas().isEmpty()) {
            return "";
        }
        List<String> generated = UnifiedDiffUtils.generateUnifiedDiff(leftLabel, rightLabel, left, patch, CONTEXT);
        List<String> lines = new ArrayList<>(generated.size() + 2);
        for (String line : generated) {
            if (line.endsWith(UNTERMINATED)) {
                lines.add(line.substring(0, line.length() - UNTERMINATED.length()));
                lines.add(NO_NEWLINE);
            } else {
                lines.add(line);
            }
        }
        return String.join("\n", lines) + "\n";
    }

    /**
     * One delta replacing everything between the two sides' common prefix and common suffix: a valid, if
     * coarse, patch for texts too far apart to search (see {@link #MAX_LINE_EDITS}).
     */
    private static Patch<String> changedMiddle(List<String> left, List<String> right) {
        int max = Math.min(left.size(), right.size());
        int prefix = 0;
        while (prefix < max && left.get(prefix).equals(right.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < max - prefix
                && left.get(left.size() - 1 - suffix).equals(right.get(right.size() - 1 - suffix))) {
            suffix++;
        }
        Chunk<String> source = new Chunk<>(prefix, new ArrayList<>(left.subList(prefix, left.size() - suffix)));
        Chunk<String> target = new Chunk<>(prefix, new ArrayList<>(right.subList(prefix, right.size() - suffix)));
        Patch<String> patch = new Patch<>();
        if (source.size() > 0 && target.size() > 0) {
            patch.addDelta(new ChangeDelta<>(source, target));
        } else if (source.size() > 0) {
            patch.addDelta(new DeleteDelta<>(source, target));
        } else if (target.size() > 0) {
            patch.addDelta(new InsertDelta<>(source, target));
        }
        return patch;
    }

    /**
     * The document's lines as Git sees them, with an unterminated final line made distinct from its
     * terminated twin. Only {@code \n} ends a line: the {@code \r} of a CRLF file stays at the end of its
     * line (and a lone {@code \r} inside it), exactly as {@code git diff} writes them, so the exported patch
     * applies to CRLF content. Splitting with {@link DiffText} dropped every {@code \r} and Git then
     * refused the patch.
     */
    private static List<String> eofAwareLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int nl = text.indexOf('\n'); nl >= 0; nl = text.indexOf('\n', start)) {
            lines.add(text.substring(start, nl));
            start = nl + 1;
        }
        if (start < text.length()) {
            lines.add(text.substring(start) + UNTERMINATED);
        }
        return lines;
    }
}
