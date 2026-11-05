package com.editora.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.editora.dap.DapModels;

/**
 * Orders Smart Step Into's calls as they run (review E108): java-debug lists the calls of a line in an order that
 * changes from one session to the next ({@code add(twice(1),twice(2))} first one time, last the next). A call runs
 * when its closing parenthesis is reached, so the calls are sorted by where each one ends in the line's text —
 * {@code twice(1)}, {@code twice(2)}, then {@code add(…)}. java-debug writes a label without the source's spaces,
 * so the line is compared without them too. A label the line does not hold keeps its place after the others.
 */
public final class StepTargetOrder {

    private StepTargetOrder() {}

    /** {@code targets} in the order their calls run on {@code lineText} (null or blank: unchanged). */
    public static List<DapModels.StepTarget> sort(List<DapModels.StepTarget> targets, String lineText) {
        if (lineText == null || lineText.isBlank() || targets.size() < 2) {
            return targets;
        }
        String line = lineText.replaceAll("\\s+", "");
        List<DapModels.StepTarget> out = new ArrayList<>(targets);
        out.sort(Comparator.comparingInt(t -> end(line, t.label())));
        return out;
    }

    private static int end(String line, String label) {
        String compact = label == null ? "" : label.replaceAll("\\s+", "");
        int at = compact.isEmpty() ? -1 : line.indexOf(compact);
        return at < 0 ? Integer.MAX_VALUE : at + compact.length();
    }
}
