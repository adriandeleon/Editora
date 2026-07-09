package com.editora.editor;

import java.util.Locale;

import javafx.scene.paint.Color;

import com.editora.markwhen.MwNode;
import com.editora.markwhen.Timeline;

/** Shared color resolution for the Markwhen timeline + calendar renderers: an event's fill from its first
 *  tag's {@code #Tag: color} declaration, else a stable fallback palette; {@code null} means "no tag →
 *  use the default {@code .markwhen-*} style-class color (theme accent)". */
final class MarkwhenPaint {

    private MarkwhenPaint() {}

    /** Fallback bar/chip colors for tags without an explicit declaration (GitHub-ish hues). */
    private static final Color[] PALETTE = {
        Color.web("#0969da"), Color.web("#1a7f37"), Color.web("#9a6700"), Color.web("#cf222e"),
        Color.web("#8250df"), Color.web("#bf3989"), Color.web("#0550ae"), Color.web("#6e7781")
    };

    /** The event's fill color, or {@code null} when it has no tag (caller uses the default style class). */
    static Color colorFor(MwNode.Event e, Timeline model) {
        if (e.tags().isEmpty()) {
            return null;
        }
        String tag = e.tags().get(0);
        for (Timeline.TagColor tc : model.tagColors()) {
            if (tc.name().equalsIgnoreCase(tag)) {
                Color c = webColor(tc.color());
                if (c != null) {
                    return c;
                }
            }
        }
        return PALETTE[Math.floorMod(tag.toLowerCase(Locale.ROOT).hashCode(), PALETTE.length)];
    }

    static Color webColor(String s) {
        try {
            return Color.web(s.strip());
        } catch (IllegalArgumentException | NullPointerException ex) {
            return null;
        }
    }

    static String toRgba(Color c) {
        return String.format(
                Locale.ROOT,
                "rgba(%d,%d,%d,%.3f)",
                (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255),
                c.getOpacity());
    }
}
