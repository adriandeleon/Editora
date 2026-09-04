package com.editora.diff;

import java.util.List;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

/**
 * Generates a unified diff ({@code .patch}) between two texts via java-diff-utils, for the diff viewer's
 * "Export patch" action (SDD Phase 4). Pure and unit-tested.
 */
public final class PatchWriter {

    private PatchWriter() {}

    private static final int CONTEXT = 3;

    /**
     * A unified diff between {@code left} and {@code right} with {@code git}-style {@code a/}, {@code b/}
     * file labels. Returns an empty string when the two are identical (no hunks).
     */
    public static String unifiedDiff(String leftLabel, String rightLabel, String leftText, String rightText) {
        DiffText leftDoc = DiffText.parse(leftText);
        DiffText rightDoc = DiffText.parse(rightText);
        List<String> left = leftDoc.lines();
        List<String> right = rightDoc.lines();
        Patch<String> patch = DiffUtils.diff(left, right);
        boolean eofDiff = leftDoc.finalNewline() != rightDoc.finalNewline();
        if (patch.getDeltas().isEmpty() && !eofDiff) {
            return "";
        }
        List<String> lines = new java.util.ArrayList<>(
                UnifiedDiffUtils.generateUnifiedDiff(leftLabel, rightLabel, left, patch, CONTEXT));
        if (lines.isEmpty()) {
            lines.add("--- " + leftLabel);
            lines.add("+++ " + rightLabel);
        }
        if (eofDiff && !left.isEmpty() && !right.isEmpty()) {
            addFinalNewlineHunk(lines, left, right, leftDoc.finalNewline(), rightDoc.finalNewline());
        }
        return String.join("\n", lines) + "\n";
    }

    private static void addFinalNewlineHunk(
            List<String> out, List<String> left, List<String> right, boolean leftNl, boolean rightNl) {
        String oldLast = left.get(left.size() - 1);
        String newLast = right.get(right.size() - 1);
        int oldAt = lastIndexOf(out, "-" + oldLast);
        int newAt = lastIndexOf(out, "+" + newLast);
        int contextAt = oldLast.equals(newLast) ? lastIndexOf(out, " " + oldLast) : -1;
        if (contextAt >= 0 && contextAt > 1) {
            out.remove(contextAt);
            out.add(contextAt, "+" + newLast);
            if (!rightNl) out.add(contextAt + 1, "\\ No newline at end of file");
            out.add(contextAt, "-" + oldLast);
            if (!leftNl) out.add(contextAt + 1, "\\ No newline at end of file");
            return;
        }
        if (oldAt >= 0 && !leftNl) {
            out.add(oldAt + 1, "\\ No newline at end of file");
            if (newAt > oldAt) newAt++;
        }
        if (newAt >= 0 && !rightNl) {
            out.add(newAt + 1, "\\ No newline at end of file");
        }
        if (oldAt < 0 || newAt < 0) {
            out.add("@@ -" + left.size() + ",1 +" + right.size() + ",1 @@");
            out.add("-" + oldLast);
            if (!leftNl) out.add("\\ No newline at end of file");
            out.add("+" + newLast);
            if (!rightNl) out.add("\\ No newline at end of file");
        }
    }

    private static int lastIndexOf(List<String> lines, String value) {
        for (int i = lines.size() - 1; i >= 2; i--) {
            if (lines.get(i).equals(value)) return i;
        }
        return -1;
    }
}
