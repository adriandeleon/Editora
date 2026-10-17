package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import com.editora.config.Breakpoint;

import static com.editora.i18n.Messages.tr;

/**
 * Pure: what a breakpoint is, in words — the gutter glyph's hover text and the breakpoints list's detail. Its
 * condition, log message and hit count were visible only by opening the edit form (review C11).
 */
public final class BreakpointDescription {

    private BreakpointDescription() {}

    /** One line per property that makes {@code bp} more than a plain breakpoint; empty for a plain one. */
    public static List<String> lines(Breakpoint bp) {
        List<String> out = new ArrayList<>();
        if (bp == null) {
            return out;
        }
        if (!bp.enabled()) {
            out.add(tr("debug.breakpoint.disabled"));
        }
        if (bp.isLogpoint()) {
            out.add(tr("debug.breakpoint.logMessage", bp.logMessage()));
        }
        if (!bp.condition().isEmpty()) {
            out.add(tr("debug.breakpoint.condition", bp.condition()));
        }
        if (!bp.hitCondition().isEmpty()) {
            out.add(tr("debug.breakpoint.hitCondition", bp.hitCondition()));
        }
        return out;
    }

    /** {@link #lines} joined into a hover text, followed by what a live session says ({@code live}); null if none. */
    public static String tooltip(Breakpoint bp, String live) {
        List<String> all = lines(bp);
        if (live != null && !live.isBlank()) {
            all.add(live);
        }
        return all.isEmpty() ? null : String.join("\n", all);
    }
}
