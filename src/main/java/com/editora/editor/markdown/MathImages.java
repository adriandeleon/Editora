package com.editora.editor.markdown;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import org.jspecify.annotations.Nullable;
import org.scilab.forge.jlatexmath.TeXConstants;
import org.scilab.forge.jlatexmath.TeXFormula;

/**
 * Renders LaTeX math (the {@code MarkdownMath} nodes of the parse) to JavaFX nodes via JLaTeXMath. The LaTeX parse and
 * the rasterizing never run on the FX thread (PERF-01, Pauta 6.2.0): the threads that parse a document for the preview,
 * a popup or the agent panel {@linkplain #warm warm} its formulas into a bounded LRU cache keyed by {@code (theme,
 * style, size, latex)}, and a node asked for on the FX thread answers from that cache. A formula the cache does not
 * hold yet shows as an empty image of the same style, rasterized off the thread ({@link #SITE}) and put in when it
 * lands; print resolves its formulas with its other assets ({@link #rendered}). Rasterization uses headless Java2D
 * (a {@link BufferedImage}) → a JavaFX {@link Image} (the same {@link PreviewImageLoader#toFxImage} path the SVG badge
 * loader uses — no {@code javafx.swing}). Bounded so it can't grow the Prism texture pool unbounded.
 */
public final class MathImages {

    /** Where a formula is parsed and rasterized: heard off the FX thread (PERF-01). */
    public static final String SITE = "MathImages.render";

    private static final int MAX_CACHED = 64;

    /** JLaTeXMath keeps static state (its fonts, its symbol tables): one formula at a time, never on the FX thread. */
    private static final Object RENDER_LOCK = new Object();

    /** Keys whose LaTeX did not parse: asked again, they answer the error at once. */
    private static final Map<String, Boolean> FAILED =
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > MAX_CACHED;
                }
            });

    private static volatile boolean warmedFonts;
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
            EPOCH.incrementAndGet();
            CACHE.clear();
            FAILED.clear();
        }
        enabled = on;
        dark = darkTheme;
        if (on && !warmedFonts) {
            warmedFonts = true;
            // JLaTeXMath reads its bundled fonts and tables on first use: off the FX thread, once.
            Thread.ofVirtual().name("math-warm").start(() -> {
                com.editora.perf.BlockingSites.enter(SITE);
                try {
                    rasterize("x", false, 16, false);
                } catch (RuntimeException | LinkageError ignored) {
                    // a formula asked for later reports its own error
                }
            });
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    private static final java.util.concurrent.atomic.AtomicLong EPOCH = new java.util.concurrent.atomic.AtomicLong();

    /** Changes when the feature switch or the app theme did: a formula node built before is out of date. */
    static long epoch() {
        return EPOCH.get();
    }

    public static Node inlineNode(String latex, double fontSize) {
        return node(latex, false, fontSize, dark);
    }

    public static Node blockNode(String latex, double fontSize) {
        return node(latex, true, fontSize, dark);
    }

    /** As {@link #inlineNode(String, double)} with an explicit glyph theme — print is light whatever the app is. */
    public static Node inlineNode(String latex, double fontSize, boolean darkTheme) {
        return node(latex, false, fontSize, darkTheme);
    }

    /** As {@link #blockNode(String, double)} with an explicit glyph theme. */
    public static Node blockNode(String latex, double fontSize, boolean darkTheme) {
        return node(latex, true, fontSize, darkTheme);
    }

    private static Node node(String latex, boolean display, double fontSize, boolean dark) {
        String key = key(latex, display, fontSize, dark);
        Image cached = CACHE.get(key);
        if (cached != null) {
            return view(cached, display);
        }
        if (FAILED.containsKey(key)) {
            return error(latex, display);
        }
        if (!onFxThread()) {
            Rendered r = rendered(latex, display, fontSize, dark); // a worker (print, export): in place
            return r.image() == null ? error(latex, display) : view(r.image(), display);
        }
        // On the FX thread: an empty image of the formula's style now, its pixels when they land (PERF-01).
        ImageView pending = new ImageView();
        pending.getStyleClass().add(display ? "md-math-block" : "md-math-inline");
        pending.setPreserveRatio(true);
        com.editora.perf.OffFx.then(SITE, () -> rendered(latex, display, fontSize, dark), a -> {
            Image img = a.value() == null ? null : a.value().image();
            if (img != null) {
                pending.setImage(img);
                PreviewImageLoader.fit(pending, img.getWidth() / RENDER_SCALE);
            } else if (pending.getParent() instanceof javafx.scene.layout.Pane parent) {
                int at = parent.getChildren().indexOf(pending);
                if (at >= 0) {
                    parent.getChildren().set(at, error(latex, display));
                }
            }
        });
        return pending;
    }

    private static boolean onFxThread() {
        try {
            return javafx.application.Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** The node for a formula already rasterized (or, for {@code image} null, one that did not parse). */
    static Node nodeOf(@Nullable Rendered rendered, String latex, boolean display) {
        return rendered == null || rendered.image() == null ? error(latex, display) : view(rendered.image(), display);
    }

    private static Node view(Image img, boolean display) {
        ImageView v = new ImageView(img);
        v.getStyleClass().add(display ? "md-math-block" : "md-math-inline");
        v.setPreserveRatio(true);
        PreviewImageLoader.fit(v, img.getWidth() / RENDER_SCALE);
        return v;
    }

    /** Invalid LaTeX (ParseException, etc.): the raw source, so the error is visible, not lost. */
    private static Node error(String latex, boolean display) {
        Label err = new Label(display ? "$$" + latex + "$$" : "$" + latex + "$");
        err.getStyleClass().add("md-math-error");
        return err;
    }

    /** A formula's image, or null when its LaTeX did not parse. */
    public record Rendered(@Nullable Image image) {}

    /**
     * Rasterizes a formula for the app theme ({@code darkTheme} null) or the one given, into the cache. Off the FX
     * thread only: the threads that parse a document call it for its formulas before the FX thread builds them.
     */
    public static void warm(String latex, boolean display, double fontSize, @Nullable Boolean darkTheme) {
        if (enabled && latex != null) {
            rendered(latex, display, fontSize, darkTheme == null ? dark : darkTheme);
        }
    }

    /** The formula's image, from the cache or rasterized now. Off the FX thread only. */
    static Rendered rendered(String latex, boolean display, double fontSize, boolean dark) {
        String key = key(latex, display, fontSize, dark);
        Image cached = CACHE.get(key);
        if (cached != null) {
            return new Rendered(cached);
        }
        if (FAILED.containsKey(key)) {
            return new Rendered(null);
        }
        com.editora.perf.BlockingSites.enter(SITE);
        try {
            Image img = PreviewImageLoader.toFxImage(rasterize(latex, display, fontSize, dark));
            CACHE.put(key, img);
            synchronized (CACHE) {
                ImageCacheBudget.trim(CACHE, ImageCacheBudget::footprint, ImageCacheBudget.MATH_BUDGET_BYTES);
            }
            return new Rendered(img);
        } catch (RuntimeException ex) {
            FAILED.put(key, Boolean.TRUE);
            return new Rendered(null);
        }
    }

    static String key(String latex, boolean display, double fontSize, boolean dark) {
        return (dark ? "d" : "l") + (display ? "D" : "I") + (int) Math.round(fontSize) + ":" + latex;
    }

    /** Rasterizes a formula to PNG bytes (for PDF/HTML export); null on a LaTeX parse error. */
    public static byte @Nullable [] renderPng(
            @Nullable String latex, boolean display, float pointSize, boolean darkTheme) {
        try {
            java.awt.Color fg = darkTheme ? new java.awt.Color(0xD0, 0xD7, 0xDE) : new java.awt.Color(0x1F, 0x23, 0x28);
            int style = display ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT;
            BufferedImage bi;
            synchronized (RENDER_LOCK) {
                bi = (BufferedImage) new TeXFormula(latex).createBufferedImage(style, pointSize, fg, null);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(bi, "png", out);
            return out.toByteArray();
        } catch (Exception ex) {
            return null;
        }
    }

    private static BufferedImage rasterize(String latex, boolean display, double fontSize, boolean dark) {
        float size = (float) (fontSize * RENDER_SCALE);
        java.awt.Color fg = dark ? new java.awt.Color(0xD0, 0xD7, 0xDE) : new java.awt.Color(0x1F, 0x23, 0x28);
        int style = display ? TeXConstants.STYLE_DISPLAY : TeXConstants.STYLE_TEXT;
        synchronized (RENDER_LOCK) {
            return (BufferedImage) new TeXFormula(latex).createBufferedImage(style, size, fg, null);
        }
    }
}
