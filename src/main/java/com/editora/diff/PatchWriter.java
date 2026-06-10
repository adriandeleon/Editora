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

    private PatchWriter() {
    }

    private static final int CONTEXT = 3;

    /**
     * A unified diff between {@code left} and {@code right} with {@code git}-style {@code a/}, {@code b/}
     * file labels. Returns an empty string when the two are identical (no hunks).
     */
    public static String unifiedDiff(String leftLabel, String rightLabel, String leftText, String rightText) {
        List<String> left = DiffEngine.lines(leftText);
        List<String> right = DiffEngine.lines(rightText);
        Patch<String> patch = DiffUtils.diff(left, right);
        if (patch.getDeltas().isEmpty()) {
            return "";
        }
        List<String> lines = UnifiedDiffUtils.generateUnifiedDiff(
                leftLabel, rightLabel, left, patch, CONTEXT);
        return String.join("\n", lines) + "\n";
    }
}
