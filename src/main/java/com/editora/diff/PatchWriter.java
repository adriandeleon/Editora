package com.editora.diff;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@.*$");

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
        boolean unterminatedLeft = !leftDoc.finalNewline() && !left.isEmpty();
        boolean unterminatedRight = !rightDoc.finalNewline() && !right.isEmpty();
        if (unterminatedLeft || unterminatedRight) {
            addFinalNewlineHunk(lines, left, right, leftDoc.finalNewline(), rightDoc.finalNewline(), eofDiff);
        }
        return String.join("\n", lines) + "\n";
    }

    private static void addFinalNewlineHunk(
            List<String> out,
            List<String> left,
            List<String> right,
            boolean leftNl,
            boolean rightNl,
            boolean forceFinalHunk) {
        EofPositions eof = eofPositions(out, left.size(), right.size());
        int oldAt = eof.oldAt();
        int newAt = eof.newAt();
        int contextAt = eof.contextAt();
        if (forceFinalHunk && contextAt >= 0) {
            String oldLast = left.get(left.size() - 1);
            String newLast = right.get(right.size() - 1);
            out.remove(contextAt);
            out.add(contextAt, "+" + newLast);
            if (!rightNl) out.add(contextAt + 1, "\\ No newline at end of file");
            out.add(contextAt, "-" + oldLast);
            if (!leftNl) out.add(contextAt + 1, "\\ No newline at end of file");
            return;
        }
        if (!forceFinalHunk && contextAt >= 0 && !leftNl && !rightNl) {
            out.add(contextAt + 1, "\\ No newline at end of file");
            return;
        }
        if (oldAt >= 0 && !leftNl) {
            out.add(oldAt + 1, "\\ No newline at end of file");
            if (newAt > oldAt) newAt++;
        }
        if (newAt >= 0 && !rightNl) {
            out.add(newAt + 1, "\\ No newline at end of file");
        }
        if (forceFinalHunk && !left.isEmpty() && !right.isEmpty() && (oldAt < 0 || newAt < 0)) {
            String oldLast = left.get(left.size() - 1);
            String newLast = right.get(right.size() - 1);
            out.add("@@ -" + left.size() + ",1 +" + right.size() + ",1 @@");
            out.add("-" + oldLast);
            if (!leftNl) out.add("\\ No newline at end of file");
            out.add("+" + newLast);
            if (!rightNl) out.add("\\ No newline at end of file");
        }
    }

    private static EofPositions eofPositions(List<String> lines, int oldSize, int newSize) {
        int oldAt = -1;
        int newAt = -1;
        int contextAt = -1;
        int oldLine = 0;
        int newLine = 0;
        for (int i = 2; i < lines.size(); i++) {
            String line = lines.get(i);
            Matcher header = HUNK_HEADER.matcher(line);
            if (header.matches()) {
                oldLine = Integer.parseInt(header.group(1));
                newLine = Integer.parseInt(header.group(3));
                continue;
            }
            if (line.startsWith("-") && oldLine > 0) {
                if (oldLine == oldSize) oldAt = i;
                oldLine++;
            } else if (line.startsWith("+") && newLine > 0) {
                if (newLine == newSize) newAt = i;
                newLine++;
            } else if (line.startsWith(" ") && oldLine > 0 && newLine > 0) {
                if (oldLine == oldSize && newLine == newSize) contextAt = i;
                oldLine++;
                newLine++;
            }
        }
        return new EofPositions(oldAt, newAt, contextAt);
    }

    private record EofPositions(int oldAt, int newAt, int contextAt) {}
}
