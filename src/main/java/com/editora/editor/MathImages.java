package com.editora.editor;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;

/**
 * Renders LaTeX math (from {@link MathSpans}) to JavaFX nodes via JLaTeXMath. JLaTeXMath is pure-Java
 * and fast, so rendering is synchronous on the FX thread; results are cached in a bounded LRU map keyed
 * by {@code (theme, style, size, latex)} so the debounced whole-document re-render never re-rasterizes an
 * unchanged formula. Rasterization uses headless Java2D (a {@link BufferedImage}) → a JavaFX
 * {@link Image} (the same {@link PreviewImageLoader#toFxImage} path the SVG badge loader uses — no
 * {@code javafx.swing}). Bounded so it can't grow the Prism texture pool unbounded.
 */
public final class MathImages {

    private static final int MAX_CACHED = 64;
    /** Rasterize at 2× and display at logical size, so formulas stay crisp on HiDPI. */
    private static final float RENDER_SCALE = 2f;

    private static volatile boolean enabled;
    private static volatile boolean dark;

    private static final Map<String, Image> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<String, Image>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
                    return size() > MAX_CACHED;
                }
            });

    private MathImages() {}

    /** Enables math rendering and sets the glyph color theme; clears the cache when either changes. */
    public static void configure(boolean on, boolean darkTheme) {
        if (enabled != on || dark != darkTheme) {
            CACHE.clear();
        }
        enabled = on;
        dark = darkTheme;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static Node inlineNode(String latex, double fontSize) {
        return node(latex, false, fontSize);
    }

    public static Node blockNode(String latex, double fontSize) {
        return node(latex, true, fontSize);
    }

    private static Node node(String latex, boolean display, double fontSize) {
        try {
            Image img = render(latex, display, fontSize);
            ImageView v = new ImageView(img);
            v.getStyleClass().add(display ? "md-math-block" : "md-math-inline");
            v.setPreserveRatio(true);
            v.setFitWidth(img.getWidth() / RENDER_SCALE);
            return v;
        } catch (RuntimeException ex) {
            // Invalid LaTeX (ParseException, etc.): show the raw source so the error is visible, not lost.
            Label err = new Label(display ? "$$" + latex + "$$" : "$" + latex + "$");
            err.getStyleClass().add("md-math-error");
            return err;
        }
    }

    /** Rasterizes a formula to PNG bytes (for PDF/HTML export); null on a LaTeX parse error. */
    public static byte[] renderPng(String latex, boolean display, float pointSize, boolean darkTheme) {
        try {
            java.awt.Color fg = darkTheme ? new java.awt.Color(0xD0, 0xD7, 0xDE) : new java.awt.Color(0x1F, 0x23, 0x28);
            int style = display ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT;
            BufferedImage bi = (BufferedImage) new TeXFormula(latex).createBufferedImage(style, pointSize, fg, null);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(bi, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    private static Image render(String latex, boolean display, double fontSize) {
        int px = (int) Math.round(fontSize);
        String key = (dark ? "d" : "l") + (display ? "D" : "I") + px + ":" + latex;
        Image cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        float size = (float) (fontSize * RENDER_SCALE);
        java.awt.Color fg = dark ? new java.awt.Color(0xD0, 0xD7, 0xDE) : new java.awt.Color(0x1F, 0x23, 0x28);
        TeXFormula formula = new TeXFormula(latex);
        int style = display ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT;
        BufferedImage bi = (BufferedImage) formula.createBufferedImage(style, size, fg, null);
        Image img = PreviewImageLoader.toFxImage(bi);
        CACHE.put(key, img);
        return img;
    }
}
