package com.editora.markwhen;

import java.util.List;
import java.util.Map;

/**
 * A parsed Markwhen document: the header {@code title}, the {@code #Tag: color} declarations, the top-level
 * {@link MwNode}s (events + {@code #}-header groups) in document order, and any other header {@code key:
 * value} {@code settings} (kept for completeness; the v1 renderer ignores unknown keys).
 */
public record Timeline(String title, List<TagColor> tagColors, List<MwNode> nodes, Map<String, String> settings) {

    /** A header color declaration, e.g. {@code #Travel: blue} → {@code TagColor("Travel", "blue")}. The
     *  {@code color} is the raw token (a CSS name or {@code #rrggbb}); the renderer resolves it. */
    public record TagColor(String name, String color) {}
}
