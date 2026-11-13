package com.editora.ui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.editora.dap.DapModels;

import static com.editora.i18n.Messages.tr;

/**
 * Pure: a thread dump — every thread of a debug session with its call stack, taken at one moment — and what the
 * Thread Dump window does with it: threads whose stacks are the same frame for frame grouped as one (IntelliJ's
 * "merge identical stacks": twenty pool workers parked in one place are one row), a filter over thread names and
 * frames, and the dump as text to paste into a bug report, as {@code jstack} prints one.
 *
 * @param session the session's name
 * @param at when it was taken
 * @param pausedForIt the program was running, and Editora paused it for the dump (it went on after)
 * @param threads every thread, in the adapter's order: the one the Debug window shows first, the JVM's own last
 */
record ThreadDump(String session, LocalTime at, boolean pausedForIt, List<DapModels.ThreadStack> threads) {

    ThreadDump {
        threads = shownFirst(threads);
    }

    private static List<DapModels.ThreadStack> shownFirst(List<DapModels.ThreadStack> threads) {
        List<DapModels.ThreadStack> out = new ArrayList<>();
        List<DapModels.ThreadStack> housekeeping = new ArrayList<>();
        for (DapModels.ThreadStack t : threads) {
            if (t.thread().shown()) {
                out.addFirst(t);
            } else if (DebugPanelText.isJvmThread(
                    new DapModels.ThreadInfo(t.thread().id(), t.thread().name()))) {
                housekeeping.add(t); // the JVM's own (Reference Handler, Finalizer…): after the program's
            } else {
                out.add(t);
            }
        }
        out.addAll(housekeeping);
        return List.copyOf(out);
    }

    /** Threads with the same stack: one row of the window, {@code threads} in dump order. */
    record Group(List<DapModels.ThreadStack> threads) {

        DapModels.ThreadStack first() {
            return threads.getFirst();
        }

        List<DapModels.StackFrameInfo> frames() {
            return first().frames();
        }
    }

    /**
     * The dump's threads as rows: with {@code merge}, threads whose stacks match frame for frame (and that are in the
     * same state) share one, placed where the first of them is; a thread without a stack is never merged — its
     * stack is unknown, not the same as another's. Without, one row per thread.
     */
    List<Group> groups(boolean merge) {
        Map<Object, List<DapModels.ThreadStack>> rows = new LinkedHashMap<>();
        for (DapModels.ThreadStack t : threads) {
            Object key = merge && !t.frames().isEmpty()
                    ? List.of(
                            stateText(t),
                            t.frames().stream().map(ThreadDump::frameKey).toList())
                    : t; // a row of its own
            rows.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
        return rows.values().stream().map(Group::new).toList();
    }

    /** A frame as two stacks compare it: its text, which names the function, the file and the line. */
    private static String frameKey(DapModels.StackFrameInfo f) {
        return DebugPanelText.frameText(f);
    }

    /**
     * Whether {@code group} matches the window's filter: a thread's name, or a frame's text, contains it (case
     * ignored). A blank filter matches all.
     */
    static boolean matches(Group group, String filter) {
        String f = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
        if (f.isEmpty()) {
            return true;
        }
        for (DapModels.ThreadStack t : group.threads()) {
            if (name(t).toLowerCase(Locale.ROOT).contains(f)) {
                return true;
            }
        }
        for (DapModels.StackFrameInfo frame : group.frames()) {
            if (DebugPanelText.frameText(frame).toLowerCase(Locale.ROOT).contains(f)) {
                return true;
            }
        }
        return false;
    }

    /** A thread's name ({@code main}, not java-debug's {@code Thread [main]}), or its id when the adapter gave none. */
    static String name(DapModels.ThreadStack t) {
        String name = DebugPanelText.threadName(t.thread().name()).strip();
        return name.isEmpty() ? "#" + t.thread().id() : name;
    }

    /**
     * What a thread was doing: suspended and why (as the Threads view says it), or running. A thread the adapter
     * walked was stopped, whatever it reported. With the program paused for the dump, every thread was running.
     */
    String stateText(DapModels.ThreadStack t) {
        if (pausedForIt) {
            return tr("debugpanel.thread.running");
        }
        DapModels.ThreadStatus status = t.thread();
        if (!status.paused() && !t.frames().isEmpty()) {
            status = new DapModels.ThreadStatus(status.id(), status.name(), true, "", status.shown());
        }
        return tr(DebugPanelText.threadStateKey(status));
    }

    /** How many threads were running with no stack to show: they need the program suspended first. */
    long runningWithoutStack() {
        return threads.stream()
                .filter(t -> t.frames().isEmpty() && !t.thread().paused())
                .count();
    }

    /** The window's title line: the session, the time, how many threads. */
    String title() {
        return tr("threaddump.heading", session, at.format(TIME), threads.size());
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /**
     * The dump as text, the rows of {@link #groups(boolean)}: a heading, then per row its thread names quoted, id and
     * state, and one {@code at} line per frame — as {@code jstack} and a Java stack trace print them.
     */
    String text(boolean merge) {
        StringBuilder out = new StringBuilder(title()).append('\n');
        if (pausedForIt) {
            out.append(tr("threaddump.pausedForIt")).append('\n');
        }
        for (Group g : groups(merge)) {
            out.append('\n').append(rowHeading(g)).append('\n');
            if (g.frames().isEmpty()) {
                out.append("    ").append(tr("threaddump.noStack")).append('\n');
            }
            for (DapModels.StackFrameInfo f : g.frames()) {
                out.append("    at ").append(DebugPanelText.frameText(f)).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * A row's heading: {@code "main" #1 — Suspended (breakpoint)}, or for merged threads their names (the first few)
     * and how many.
     */
    String rowHeading(Group g) {
        StringBuilder names = new StringBuilder();
        int shown = Math.min(g.threads().size(), MAX_NAMES);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                names.append(", ");
            }
            DapModels.ThreadStack t = g.threads().get(i);
            names.append('"').append(name(t)).append('"');
            if (g.threads().size() == 1) {
                names.append(" #").append(t.thread().id());
            }
        }
        if (g.threads().size() > shown) {
            names.append(", …");
        }
        String state = stateText(g.first());
        return g.threads().size() == 1
                ? tr("threaddump.row", names, state)
                : tr("threaddump.rowMerged", names, g.threads().size(), state);
    }

    /** How many names a merged row's heading lists before the rest are "…". */
    static final int MAX_NAMES = 3;
}
