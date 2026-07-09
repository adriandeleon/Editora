package com.editora.markwhen;

import java.util.List;

/**
 * A node in a parsed Markwhen {@link Timeline}: either an {@link Event} (a dated item) or a {@link Group}
 * (a {@code #}-header section that nests other nodes). Sealed so the renderer can switch exhaustively.
 */
public sealed interface MwNode permits MwNode.Event, MwNode.Group {

    /** The 0-based source line the node was declared on (for click/fold mapping). */
    int line();

    /**
     * A dated event. {@code end} is {@code null} for a single-date (point) event; otherwise the event
     * spans {@code [start, end]} (each endpoint carries its own granularity, so the drawn bar runs from
     * {@code start.startEpochDay} to {@code end.endEpochDayExclusive}). {@code tags} are the {@code #tags}
     * found in the label, in order.
     */
    record Event(MwDate start, MwDate end, String label, List<String> tags, int line) implements MwNode {}

    /**
     * A {@code #}-header section. {@code isSection} is true when a {@code style: section} property makes it
     * a full-width section (vs. the default collapsible group). {@code children} are the events/subgroups
     * declared under it, in document order.
     */
    record Group(String name, boolean isSection, List<MwNode> children, int line) implements MwNode {}
}
