package com.editora.editor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.Parent;

import com.editora.text.ColorSwatch;
import org.fxmisc.richtext.Inlay;
import org.jspecify.annotations.Nullable;

/**
 * The colour swatches drawn before a CSS colour literal (HTML review D9): an inlay — a decoration laid out between
 * the glyphs, never text — per colour the language server reported, merged into each line's other inlays.
 *
 * <p>An inlay carries a style class but no colour, so each colour gets a class of its own
 * ({@code swatch-ff0000ff}) and the buffer a small stylesheet that paints those classes. The sheet only grows
 * while colours are added (typing {@code #abc} one character at a time adds three), so it changes — and the
 * buffer's CSS is re-applied — only when a colour it has never shown appears; past {@link #MAX_CLASSES} it is
 * rebuilt from the colours on screen.
 */
public final class ColorSwatchInlays {

    /** Colours the sheet keeps before it is rebuilt from the current ones only. */
    static final int MAX_CLASSES = 512;

    /** The most swatches one buffer draws: a generated stylesheet with tens of thousands is not worth it. */
    static final int MAX_SWATCHES = 5_000;

    static final String CLASS_PREFIX = "swatch-";

    private Map<Integer, List<Inlay>> byLine = Map.of();
    private final Set<String> painted = new LinkedHashSet<>();
    private @Nullable String sheet;

    /**
     * Replaces the swatches with {@code swatches} (null or empty clears them) and keeps {@code root}'s stylesheet
     * in step. True when what is drawn changed, so the caller refreshes its inlays.
     */
    public boolean set(@Nullable List<ColorSwatch> swatches, Parent root) {
        Map<Integer, List<Inlay>> next = new HashMap<>();
        Set<String> colours = new LinkedHashSet<>();
        if (swatches != null) {
            for (ColorSwatch s : swatches.size() > MAX_SWATCHES ? swatches.subList(0, MAX_SWATCHES) : swatches) {
                String hex = s.hex();
                colours.add(hex);
                next.computeIfAbsent(s.line(), k -> new ArrayList<>(1))
                        .add(new Inlay(s.column(), "", CLASS_PREFIX + hex));
            }
        }
        if (next.equals(byLine)) {
            return false;
        }
        byLine = next.isEmpty() ? Map.of() : next;
        if (!painted.containsAll(colours)) {
            if (painted.size() + colours.size() > MAX_CLASSES) {
                painted.clear();
            }
            painted.addAll(colours);
            String url = sheetUrl(painted);
            if (sheet != null) {
                root.getStylesheets().remove(sheet);
            }
            root.getStylesheets().add(url);
            sheet = url;
        }
        return true;
    }

    /** {@code others} (a line's hints, null for none) with the line's swatches added; null when there are neither. */
    public java.util.@Nullable List<Inlay> merged(int line, java.util.@Nullable List<Inlay> others) {
        List<Inlay> mine = byLine.get(line);
        if (mine == null) {
            return others;
        }
        if (others == null || others.isEmpty()) {
            return mine;
        }
        List<Inlay> all = new ArrayList<>(others.size() + mine.size());
        all.addAll(mine);
        all.addAll(others);
        return all;
    }

    /** Whether any swatch is drawn. */
    boolean isEmpty() {
        return byLine.isEmpty();
    }

    /**
     * A small framed square in the space the inlay takes, the same in every rule: JavaFX CSS has no selector for
     * "every class starting {@code swatch-}". The outer fill is the frame, so a white swatch shows on a white page.
     */
    private static final String GEOMETRY = "-fx-min-width: 0.95em; -fx-pref-width: 0.95em; -fx-max-width: 0.95em;"
            + " -fx-padding: 0; -fx-opacity: 1;"
            + " -fx-background-insets: 0.24em 0.2em 0.16em 0.05em, 0.31em 0.27em 0.23em 0.12em;"
            + " -fx-background-radius: 2, 1;";

    /** The stylesheet that paints each {@code rrggbbaa} of {@code colours}, as a {@code data:} URL. */
    static String sheetUrl(Set<String> colours) {
        StringBuilder css = new StringBuilder(colours.size() * 120);
        for (String hex : colours) {
            css.append(".inlay.")
                    .append(CLASS_PREFIX)
                    .append(hex)
                    .append(" { ")
                    .append(GEOMETRY)
                    .append(" -fx-background-color: -color-fg-muted, #")
                    .append(hex)
                    .append("; }\n");
        }
        return "data:text/css;base64,"
                + Base64.getEncoder().encodeToString(css.toString().getBytes(StandardCharsets.UTF_8));
    }
}
