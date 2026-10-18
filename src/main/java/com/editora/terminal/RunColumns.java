package com.editora.terminal;

/**
 * The display column a style run starts at.
 *
 * <p>JediTerm hands the renderer an {@code x} that indexes the line's {@code char[]}, not its
 * columns, and the two disagree exactly where {@link CellWidth} says they do: a character outside
 * the Basic Multilingual Plane is two array slots and one column. Drawing a run at {@code x}
 * therefore starts it one column late for every astral character earlier in the line — and a Nerd
 * Font icon before each name in an {@code ls} listing is one such character, in the middle of a
 * layout whose whole purpose is that the columns line up.
 *
 * <p>The drift accumulates, which is what makes it read as a rendering fault rather than an
 * off-by-one: a row with two icons ends up two columns right of a row with one, so a listing
 * diverges further the further right you look.
 *
 * <p>Stateful across a row, because a run only knows where it starts, not what came before it.
 * Runs arrive left to right; a gap between them (the padding past the last written column) is
 * plain cells, one column each, so it can be carried across verbatim.
 */
final class RunColumns {

    private int row = -1;
    private int slots;
    private int cells;

    /**
     * @param row the row being drawn, so the running totals reset at its start
     * @param x JediTerm's slot index for this run
     * @param run the run's characters
     * @return the column to draw it at
     */
    int columnOf(int row, int x, CharSequence run) {
        if (row != this.row) {
            this.row = row;
            slots = 0;
            cells = 0;
        }
        // Anything skipped between the last run and this one is ordinary single-slot padding, so
        // it contributes the same number of columns as slots.
        int column = cells + (x - slots);
        // A run that starts before where the last one ended means the assumption above is wrong
        // for this line. Falling back to x is what the renderer did before and is never worse.
        if (column < 0) column = x;
        slots = x + run.length();
        cells = column + CellWidth.cells(run.toString());
        return column;
    }
}
