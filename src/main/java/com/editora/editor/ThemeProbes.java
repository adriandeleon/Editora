package com.editora.editor;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;

import org.jspecify.annotations.Nullable;

/**
 * The theme's colours for a canvas painter, read from the scene's stylesheet. A Canvas cannot be styled, so the
 * painter hosts zero-sized, unmanaged {@link Rectangle}s, one per colour, each with the style class
 * {@code ink-probe-<name>} that {@code app.css} fills with a token ({@code .ink-probe-red { -fx-fill: -state-red; }}),
 * and reads their fills (the Project Map's probes, generalised). A theme change restyles the probes and
 * {@code onChange} runs, so the painter re-reads its colours then and never per paint.
 *
 * <p>Where no stylesheet reaches the probe (a scene without {@code app.css}, or before the first CSS pass) a colour
 * is {@code null} and the painter falls back to something derived from its own ground, never a fixed hue
 * (ICON-08, Pauta 6.2.0).
 */
public final class ThemeProbes {

    /** The state colours, the accent and its text tint. */
    public static final String RED = "red";

    public static final String AMBER = "amber";
    public static final String GREEN = "green";
    public static final String ACCENT = "accent";
    public static final String ACCENT_FG = "accent-fg";
    /** The categorical hues (Git log lanes, a timeline's tags): ladders of the ground, none of them a state's. */
    public static final int LANES = 6;

    private final Map<String, Rectangle> probes = new LinkedHashMap<>();

    /** Probes for {@code names} added to {@code host}; {@code onChange} runs whenever one of their colours changes. */
    public ThemeProbes(ObservableList<Node> host, Runnable onChange, String... names) {
        for (String name : names) {
            Rectangle probe = new Rectangle(0, 0);
            probe.setFill(null); // nothing until a stylesheet fills it: the painter's fallback stands
            probe.getStyleClass().add("ink-probe-" + name);
            probe.setManaged(false);
            probe.setMouseTransparent(true);
            probe.fillProperty().addListener((obs, was, now) -> onChange.run());
            probes.put(name, probe);
            host.add(probe);
        }
    }

    /** The names of the lane probes, {@code lane-0} to {@code lane-5}. */
    public static String[] laneNames() {
        String[] names = new String[LANES];
        for (int i = 0; i < LANES; i++) {
            names[i] = "lane-" + i;
        }
        return names;
    }

    /** The colour {@code name} resolved to, or {@code null} when no stylesheet has filled it. */
    public @Nullable Color get(String name) {
        Rectangle probe = probes.get(name);
        Paint fill = probe == null ? null : probe.getFill();
        return fill instanceof Color color ? color : null;
    }

    /** The colour {@code name} resolved to, or {@code fallback}. */
    public Color get(String name, Color fallback) {
        Color color = get(name);
        return color != null ? color : fallback;
    }
}
