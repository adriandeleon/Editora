package com.editora.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One colour literal a language server found ({@code textDocument/documentColor}, HTML review D9): where it starts
 * (0-based line and column) and the colour, each channel 0–1. Kept free of lsp4j so the editor can draw it.
 */
public record ColorSwatch(int line, int column, double red, double green, double blue, double alpha) {

    /** {@code rrggbbaa} in lower-case hex — the colour as a CSS name and a style-class suffix. */
    public String hex() {
        return String.format(
                Locale.ROOT, "%02x%02x%02x%02x", channel(red), channel(green), channel(blue), channel(alpha));
    }

    private static int channel(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, Double.isNaN(v) ? 0 : v)) * 255);
    }

    /**
     * {@code swatches} moved by one edit at {@code line}:{@code column} that replaced {@code removed} with {@code
     * inserted}, so they stay before their literals until the server's next answer (HTML review 2 C111/B108): a
     * swatch after the edit moves with the text, one inside the replaced text is dropped, one before it stays. The
     * same list when none moves.
     */
    public static List<ColorSwatch> shifted(
            List<ColorSwatch> swatches, int line, int column, CharSequence removed, CharSequence inserted) {
        int removedBreaks = breaks(removed);
        int insertedBreaks = breaks(inserted);
        int oldEndLine = line + removedBreaks;
        int oldEndColumn = removedBreaks == 0 ? column + removed.length() : tail(removed);
        int newEndLine = line + insertedBreaks;
        int newEndColumn = insertedBreaks == 0 ? column + inserted.length() : tail(inserted);
        List<ColorSwatch> out = null;
        for (int i = 0; i < swatches.size(); i++) {
            ColorSwatch s = swatches.get(i);
            ColorSwatch moved;
            if (before(s, line, column) || (removed.isEmpty() && inserted.isEmpty())) {
                moved = s;
            } else if (before(s, oldEndLine, oldEndColumn)) {
                moved = null; // its literal was replaced
            } else if (s.line() == oldEndLine) {
                moved = s.at(newEndLine, newEndColumn + s.column() - oldEndColumn);
            } else {
                moved = s.at(s.line() + insertedBreaks - removedBreaks, s.column());
            }
            if (moved != s && out == null) {
                out = new ArrayList<>(swatches.subList(0, i));
            }
            if (out != null && moved != null) {
                out.add(moved);
            }
        }
        return out == null ? swatches : out;
    }

    private ColorSwatch at(int line, int column) {
        return line == this.line && column == this.column
                ? this
                : new ColorSwatch(line, column, red, green, blue, alpha);
    }

    private static boolean before(ColorSwatch s, int line, int column) {
        return s.line() < line || (s.line() == line && s.column() < column);
    }

    private static int breaks(CharSequence text) {
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /** The length of {@code text} after its last line break. */
    private static int tail(CharSequence text) {
        int i = text.length();
        while (i > 0 && text.charAt(i - 1) != '\n') {
            i--;
        }
        return text.length() - i;
    }
}
