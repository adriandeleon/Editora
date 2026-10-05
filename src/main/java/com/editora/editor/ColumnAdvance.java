package com.editora.editor;

import javafx.scene.text.Font;
import javafx.scene.text.Text;

/**
 * The width of one text column in the editor font, from the font's own metrics.
 *
 * <p>The column ruler used to derive this from whatever text was on screen: the distance between the first
 * and last character of the longest visible line, divided by its length. That is only right when every
 * character is one cell wide and the line is long — a tab (several cells) or a double-width CJK glyph
 * inflated it, the couple of pixels of padding on a character's bounds were spread over the line (a third
 * of the ruler's offset on a two-character line), and a measure taken before the area's first CSS pass saw
 * the default font. The metrics of the configured font depend on none of that.
 */
final class ColumnAdvance {

    /** Enough digits that sub-pixel rounding of the run's width does not show in one column. */
    private static final int SAMPLE = 64;

    private String family;
    private double size = -1;
    private double advance;

    /** The advance of one column of {@code family} at {@code size} px; cached until either changes. */
    double of(String family, double size) {
        if (size != this.size || !java.util.Objects.equals(family, this.family)) {
            this.family = family;
            this.size = size;
            Text sample = new Text("0".repeat(SAMPLE));
            sample.setFont(family == null ? Font.font("monospace", size) : Font.font(family, size));
            advance = sample.getLayoutBounds().getWidth() / SAMPLE;
        }
        return advance;
    }
}
