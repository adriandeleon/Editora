package com.editora.editor.structured;

import java.util.Locale;

import javafx.scene.paint.Color;

import com.editora.markwhen.MwNode;
import com.editora.markwhen.Timeline;
import org.jspecify.annotations.Nullable;

/** Shared color resolution for the Markwhen timeline + calendar renderers: an event's fill from its first
 *  tag's {@code #Tag: color} declaration (the document's own colour), else one of the theme's six lane hues
 *  ({@code .markwhen-lane-N}, {@code -lane-N} in app.css: none amber, red or green, ICON-18); an event with no tag
 *  takes the default {@code .markwhen-*} style-class colour (the theme's accent). */
final class MarkwhenPaint {

    private MarkwhenPaint() {}

    /** The theme's lane hues a tag without a declared colour cycles through ({@code .markwhen-lane-0} to 5). */
    static final int LANES = 6;

    /**
     * The lane style class for an event whose first tag declares no colour, or {@code null} when it has no tag or
     * its tag declares one.
     */
    static @Nullable String laneClass(MwNode.Event e, Timeline model) {
        if (e.tags().isEmpty() || colorFor(e, model) != null) {
            return null;
        }
        return "markwhen-lane-"
                + Math.floorMod(e.tags().get(0).toLowerCase(Locale.ROOT).hashCode(), LANES);
    }

    /** The event's fill color as its document declares it, or {@code null} (caller uses a style class). */
    static @Nullable Color colorFor(MwNode.Event e, Timeline model) {
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
        return null;
    }

    static @Nullable Color webColor(String s) {
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
