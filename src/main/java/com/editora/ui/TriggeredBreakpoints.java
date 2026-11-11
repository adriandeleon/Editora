package com.editora.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.editora.config.Breakpoint;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * Triggered breakpoints — VS Code's triggered breakpoints, IntelliJ's "Disable until hitting the following breakpoint",
 * Visual Studio's "Only enable when the following breakpoint is hit": a breakpoint that names another
 * ({@link Breakpoint#triggeredBy()}, that one's {@link Breakpoint#id()}) waits, in each debug session, until the program
 * stops on that other one; from then on it is sent to the adapter and stops like any breakpoint. The waiting is done
 * here, not by the adapter, so it works with every debugger. A breakpoint whose trigger is gone waits for none.
 *
 * <p>The static methods are pure; the instance finds the window's breakpoints and links one to another.
 */
final class TriggeredBreakpoints {

    private final CoordinatorHost host;
    private final DebugCoordinator.Ops ops;

    TriggeredBreakpoints(CoordinatorHost host, DebugCoordinator.Ops ops) {
        this.host = host;
        this.ops = ops;
    }

    /** Every line breakpoint of the window's files (open tabs as they are now, then closed files' saved ones). */
    List<BreakpointsPicker.Entry> entries() {
        return BreakpointsPicker.lineEntries(host, ops);
    }

    /** Where the breakpoint with id {@code id} is ("Main.java:12"), or null when there is none. */
    @Nullable
    String labelOf(String id) {
        return labels(entries()).apply(id);
    }

    /** The ids of every breakpoint of the window's files. */
    Set<String> ids() {
        return ids(entries());
    }

    /**
     * Makes the breakpoint on (view) {@code line} of {@code b} wait for {@code trigger}'s, giving that one an id if it
     * has none; {@code trigger} null: it waits for none. The breakpoint on {@code line} must exist.
     */
    void link(EditorBuffer b, int line, BreakpointsPicker.@Nullable Entry trigger) {
        var mgr = b.getBreakpointManager();
        if (trigger == null) {
            mgr.setTriggeredBy(line, "");
            b.refreshGutterLine(line);
            return;
        }
        String id = trigger.breakpoint().id();
        if (id.isEmpty()) {
            id = Breakpoint.newId();
            giveId(trigger, id);
        }
        mgr.setTriggeredBy(line, id);
        b.refreshGutterLine(line);
    }

    /** Gives {@code trigger}'s breakpoint the id {@code id}: in its tab, or in the saved breakpoints of its file. */
    private void giveId(BreakpointsPicker.Entry trigger, String id) {
        EditorBuffer tab = trigger.buffer();
        if (tab != null) {
            var mgr = tab.getBreakpointManager();
            mgr.setId(trigger.line() - mgr.regionFirstLine(), id);
            return;
        }
        Map<String, List<Breakpoint>> map = ops.breakpointMap();
        List<Breakpoint> stored = map.get(trigger.key());
        if (stored == null) {
            return;
        }
        List<Breakpoint> next = new ArrayList<>(stored.size());
        for (Breakpoint bp : stored) {
            next.add(bp.line() == trigger.line() ? bp.withId(id) : bp);
        }
        map.put(trigger.key(), next);
        ops.saveBreakpoints();
        ops.breakpointsStored(map, trigger.key());
    }

    // --- pure ---------------------------------------------------------------------------------------------------

    /** Where each breakpoint of {@code entries} that has an id is, by id: "Main.java:12" (null for any other id). */
    static Function<String, @Nullable String> labels(List<BreakpointsPicker.Entry> entries) {
        Map<String, String> byId = new HashMap<>();
        for (BreakpointsPicker.Entry e : entries) {
            String id = e.breakpoint().id();
            if (!id.isEmpty() && e.member() == null) {
                byId.putIfAbsent(id, place(e));
            }
        }
        return byId::get;
    }

    /** "Main.java:12": an entry's file name and 1-based line. */
    static String place(BreakpointsPicker.Entry e) {
        var name = e.file().getFileName();
        return (name == null ? e.file().toString() : name.toString()) + ":" + (e.line() + 1);
    }

    /** The ids of {@code entries}' breakpoints. */
    static Set<String> ids(List<BreakpointsPicker.Entry> entries) {
        Set<String> out = new HashSet<>();
        for (BreakpointsPicker.Entry e : entries) {
            if (!e.breakpoint().id().isEmpty()) {
                out.add(e.breakpoint().id());
            }
        }
        return out;
    }

    /**
     * Whether {@code bp} waits — is not sent to the adapter — in a session where the breakpoints with ids {@code hit}
     * have been hit: it names a trigger that still exists (among {@code ids}) and has not been hit.
     */
    static boolean waits(Breakpoint bp, Set<String> ids, Set<String> hit) {
        return bp.isTriggered() && ids.contains(bp.triggeredBy()) && !hit.contains(bp.triggeredBy());
    }

    /**
     * The breakpoints the one on {@code key}'s whole-file line {@code line} (id {@code selfId}, blank if none) can wait
     * for: every other line breakpoint of {@code entries} but those that wait — directly or through others — for it,
     * which would leave both waiting for ever.
     */
    static List<BreakpointsPicker.Entry> choices(
            String key, int line, String selfId, List<BreakpointsPicker.Entry> entries) {
        Map<String, String> triggerOf = new HashMap<>();
        for (BreakpointsPicker.Entry e : entries) {
            Breakpoint bp = e.breakpoint();
            if (!bp.id().isEmpty() && bp.isTriggered()) {
                triggerOf.put(bp.id(), bp.triggeredBy());
            }
        }
        List<BreakpointsPicker.Entry> out = new ArrayList<>();
        for (BreakpointsPicker.Entry e : entries) {
            if (e.member() != null || (e.key().equals(key) && e.line() == line)) {
                continue;
            }
            if (!selfId.isEmpty() && reaches(e.breakpoint(), selfId, triggerOf)) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    /** Whether following {@code from}'s triggers, one after another, comes to the breakpoint {@code id}. */
    private static boolean reaches(Breakpoint from, String id, Map<String, String> triggerOf) {
        Set<String> seen = new HashSet<>();
        String next = from.triggeredBy();
        while (!next.isEmpty() && seen.add(next)) {
            if (next.equals(id)) {
                return true;
            }
            next = triggerOf.getOrDefault(next, "");
        }
        return false;
    }
}
