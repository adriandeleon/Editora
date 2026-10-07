package com.editora.diff;

import java.util.ArrayList;
import java.util.List;

import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.UnifiedRow;

/**
 * Gives the rows of a diff computed from a patch's reconstructed lines the line numbers the patch itself
 * states. {@link PatchParser} holds every hunk's lines back to back, so the engine numbers them 1, 2, 3, …:
 * a hunk at line 500 was shown as line 1, and nothing marked where one hunk ended and the next began. With
 * the real numbers the jump between hunks is visible in the gutter. Pure and unit-tested.
 */
public final class PatchLineNumbers {

    private PatchLineNumbers() {}

    /**
     * {@code model} with each side's sequential line number {@code n} replaced by {@code numbers.get(n - 1)}.
     * A side whose list is empty, or a line beyond its list, keeps the number it had.
     */
    public static DiffModel renumber(DiffModel model, List<Integer> leftNumbers, List<Integer> rightNumbers) {
        if (model == null || (leftNumbers.isEmpty() && rightNumbers.isEmpty())) {
            return model;
        }
        List<Row> rows = new ArrayList<>(model.rows().size());
        for (Row row : model.rows()) {
            rows.add(new Row(
                    row.type(),
                    row.left(),
                    map(row.leftLine(), leftNumbers),
                    row.right(),
                    map(row.rightLine(), rightNumbers),
                    row.leftWordRanges(),
                    row.rightWordRanges()));
        }
        List<UnifiedRow> unified = new ArrayList<>(model.unified().size());
        for (UnifiedRow row : model.unified()) {
            unified.add(new UnifiedRow(
                    row.type(),
                    row.text(),
                    map(row.leftLine(), leftNumbers),
                    map(row.rightLine(), rightNumbers),
                    row.wordRanges()));
        }
        return new DiffModel(
                rows,
                unified,
                model.added(),
                model.removed(),
                model.changeBlockStarts(),
                model.leftFinalNewline(),
                model.rightFinalNewline(),
                model.quality());
    }

    private static int map(int line, List<Integer> numbers) {
        return line >= 1 && line <= numbers.size() ? numbers.get(line - 1) : line;
    }
}
