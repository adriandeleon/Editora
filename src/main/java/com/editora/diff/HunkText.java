package com.editora.diff;

import java.util.ArrayList;
import java.util.List;

import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;

/**
 * The text a Git index side should hold after a hunk (rows {@code [start, end)} of a diff) is staged into
 * it or unstaged from it. Pure and unit-tested.
 *
 * <p>A local apply deliberately keeps the edited file's own end-of-file state and offers a separate
 * final-newline action. The index has no such action, and the side being written may be empty or absent
 * (a new file, a deleted one), so composing through the target's own {@link DiffText} produced a blob that
 * matched neither side: a new file was staged without its final newline, a fully unstaged one was left as a
 * single newline. Here the end of the result follows the side that supplies its last line.
 */
public final class HunkText {

    private HunkText() {}

    /**
     * True when {@code [start, end)} holds every difference between the two sides, so taking those rows from
     * the other side makes the target identical to it. Rows shown as equal while spelled differently (a
     * whitespace-insensitive comparison) count as differences that the range does not cover.
     */
    public static boolean coversEveryChange(List<Row> rows, int start, int end) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean inBlock = i >= start && i < end;
            if (row.type() == RowType.EQUAL ? !java.util.Objects.equals(row.left(), row.right()) : !inBlock) {
                return false;
            }
        }
        return true;
    }

    /**
     * The target side's text after rows {@code [start, end)} take the other side's content.
     *
     * @param rightTarget whether the right side is the one being rewritten
     * @param target the side being rewritten, as currently shown
     * @param other the side the rows are taken from
     */
    public static String apply(
            List<Row> rows, int start, int end, boolean rightTarget, DiffText target, DiffText other) {
        List<String> out = new ArrayList<>();
        boolean lastFromOther = false;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean inBlock = i >= start && i < end;
            String text = inBlock == rightTarget ? row.left() : row.right();
            if (text != null) {
                out.add(text);
                lastFromOther = inBlock;
            }
        }
        if (out.isEmpty()) {
            return ""; // nothing is left: an empty file, not a lone terminator
        }
        // The last line's terminator belongs to the side that line came from.
        boolean finalNewline = lastFromOther ? other.finalNewline() : target.finalNewline();
        String separator = target.lines().isEmpty() ? other.lineSeparator() : target.lineSeparator();
        return new DiffText(out, separator, finalNewline).compose(out);
    }
}
