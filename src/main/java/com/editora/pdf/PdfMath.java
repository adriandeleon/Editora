package com.editora.pdf;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.image.BufferedImage;

import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;
import org.scilab.forge.jlatexmath.TeXIcon;

/**
 * LaTeX formulas for the Markdown PDF: a picture <em>with its metrics</em>. The preview's
 * {@code MathImages} hands out JavaFX nodes (or bare PNG bytes) at screen resolution, which is why a formula
 * in a PDF used to be squeezed into a fixed box and sat on a guessed baseline. Here the formula is rasterised
 * at {@link #SCALE}× and reports its size and depth in points, so it is placed at its real size on the text
 * baseline and stays sharp when the page is zoomed or printed.
 */
final class PdfMath {

    /** Pixels per point: 4× is about 290 dpi on paper. */
    static final float SCALE = 4f;
    /** The x-height of the formula font (Computer Modern), as a fraction of its em. */
    static final float X_HEIGHT = 0.4306f;

    private PdfMath() {}

    /**
     * A rendered formula: its bitmap and, in points, its width, full height and how far it reaches below the
     * baseline ({@code depth}, part of {@code height}).
     */
    record Formula(BufferedImage image, float width, float height, float depth) {}

    /**
     * The em size at which a formula's x-height equals that of body text with x-height {@code bodyXHeight}
     * (a fraction of its em) at {@code bodySize} points. Computer Modern has a much smaller x-height than a
     * screen sans, so a formula set at the body's nominal size looks a size too small beside it.
     */
    static float emFor(float bodySize, float bodyXHeight) {
        float ratio = bodyXHeight > 0f ? bodyXHeight / X_HEIGHT : 1f;
        return bodySize * Math.max(1f, Math.min(ratio, 1.4f));
    }

    /** Renders {@code latex} at {@code em} points in {@code color}; null when it does not parse. */
    static Formula render(String latex, boolean display, float em, Color color) {
        try {
            int style = display ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT;
            TeXIcon icon = new TeXFormula(latex).createTeXIcon(style, em * SCALE);
            // "true" insets: the one-argument form adds 18% of the size on every side, which is why a formula
            // used to float in a wide gap.
            icon.setInsets(new Insets(1, 1, 1, 1), true); // room for antialiasing, nothing more
            int w = icon.getIconWidth();
            int h = icon.getIconHeight();
            if (w < 1 || h < 1) {
                return null;
            }
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                icon.setForeground(color);
                icon.paintIcon(null, g, 0, 0);
            } finally {
                g.dispose();
            }
            return new Formula(image, w / SCALE, h / SCALE, icon.getIconDepth() / SCALE);
        } catch (Exception | LinkageError e) {
            return null; // invalid LaTeX: the caller prints the source instead
        }
    }
}
