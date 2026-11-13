package com.editora.editor.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.editora.config.Breakpoint;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Pure: what a breakpoint is, in words — the gutter glyph's hover text and the breakpoints list's detail. Its
 * condition, log message and hit count were visible only by opening the edit form (review C11).
 */
public final class BreakpointDescription {

    private BreakpointDescription() {}

    /** One line per property that makes {@code bp} more than a plain breakpoint; empty for a plain one. */
    public static List<String> lines(@Nullable Breakpoint bp) {
        return lines(bp, id -> null);
    }

    /**
     * {@link #lines(Breakpoint)}, naming the breakpoint it waits for by {@code triggers} (a breakpoint id → where it
     * is, "Main.java:12"; null when there is no such breakpoint, so this one waits for none).
     */
    public static List<String> lines(@Nullable Breakpoint bp, Function<String, @Nullable String> triggers) {
        List<String> out = new ArrayList<>();
        if (bp == null) {
            return out;
        }
        if (!bp.enabled()) {
            out.add(tr("debug.breakpoint.disabled"));
        }
        String trigger = bp.isTriggered() ? triggers.apply(bp.triggeredBy()) : null;
        if (trigger != null) {
            out.add(tr("debug.breakpoint.triggeredBy", trigger));
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
        if (!bp.filters().thread().isEmpty()) {
            out.add(tr("debug.breakpoint.threadFilter", bp.filters().thread()));
        }
        if (!bp.filters().caller().isEmpty()) {
            out.add(tr("debug.breakpoint.callerFilter", bp.filters().caller()));
        }
        if (!bp.filters().instance().isEmpty()) {
            out.add(tr("debug.breakpoint.instanceFilter", bp.filters().instance()));
        }
        if (bp.hasLambdas()) {
            String numbers =
                    String.join(", ", bp.lambdas().stream().map(String::valueOf).toList());
            out.add(tr(bp.lambdasOnly() ? "debug.breakpoint.lambdasOnly" : "debug.breakpoint.lambdas", numbers));
        }
        if (bp.temporary() && !bp.isLogpoint()) {
            out.add(tr("debug.breakpoint.temporary"));
        }
        if (bp.records()) {
            out.add(tr("debug.breakpoint.recording"));
        }
        if (!bp.group().isEmpty()) {
            out.add(tr("debug.breakpoint.group", bp.group()));
        }
        return out;
    }

    /** {@link #lines} joined into a hover text, followed by what a live session says ({@code live}); null if none. */
    public static @Nullable String tooltip(@Nullable Breakpoint bp, @Nullable String live) {
        return tooltip(bp, live, id -> null);
    }

    /** {@link #tooltip(Breakpoint, String)}, naming the breakpoint it waits for by {@code triggers}. */
    public static @Nullable String tooltip(
            @Nullable Breakpoint bp, @Nullable String live, Function<String, @Nullable String> triggers) {
        List<String> all = lines(bp, triggers);
        if (live != null && !live.isBlank()) {
            all.add(live);
        }
        return all.isEmpty() ? null : String.join("\n", all);
    }
}
