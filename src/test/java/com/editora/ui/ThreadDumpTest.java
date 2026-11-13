package com.editora.ui;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadDumpTest {

    private static DapModels.StackFrameInfo frame(String name, String file, int line) {
        return new DapModels.StackFrameInfo(1, name, Path.of("/src/" + file), line, 0);
    }

    private static DapModels.ThreadStack thread(
            int id, String name, boolean paused, String reason, boolean shown, DapModels.StackFrameInfo... frames) {
        return new DapModels.ThreadStack(new DapModels.ThreadStatus(id, name, paused, reason, shown), List.of(frames));
    }

    private static final DapModels.StackFrameInfo PARK = frame("Unsafe.park", "Unsafe.java", 9);
    private static final DapModels.StackFrameInfo TAKE = frame("Queue.take", "Queue.java", 41);

    private static ThreadDump dump(boolean pausedForIt, DapModels.ThreadStack... threads) {
        return new ThreadDump("Main.java", LocalTime.of(14, 3, 22), pausedForIt, List.of(threads));
    }

    @Test
    void theShownThreadComesFirstThenTheAdaptersOrder() {
        ThreadDump d = dump(
                false,
                thread(2, "pool-1", true, "pause", false, PARK),
                thread(1, "main", true, "breakpoint", true, TAKE),
                thread(3, "pool-2", true, "pause", false, PARK));
        assertEquals(
                List.of("main", "pool-1", "pool-2"),
                d.threads().stream().map(ThreadDump::name).toList());
    }

    @Test
    void identicalStacksInOneStateMerge() {
        ThreadDump d = dump(
                false,
                thread(1, "main", true, "breakpoint", true, TAKE, PARK),
                thread(2, "pool-1", true, "pause", false, PARK, TAKE),
                thread(3, "pool-2", true, "pause", false, PARK, TAKE),
                thread(4, "pool-3", true, "pause", false, PARK, TAKE),
                thread(5, "other", true, "pause", false, PARK),
                thread(6, "idle-a", false, "", false),
                thread(7, "idle-b", false, "", false));
        List<ThreadDump.Group> merged = d.groups(true);
        assertEquals(
                List.of(1, 3, 1, 1, 1),
                merged.stream().map(g -> g.threads().size()).toList(),
                "the three pool threads merge; two threads without a stack never do");
        assertEquals("pool-1", ThreadDump.name(merged.get(1).first()), "placed where the first of them is");
        assertEquals(7, d.groups(false).size());
    }

    @Test
    void theSameStackInAnotherStateDoesNotMerge() {
        ThreadDump d = dump(
                false, thread(1, "a", true, "breakpoint", false, PARK), thread(2, "b", true, "pause", false, PARK));
        assertEquals(2, d.groups(true).size());
    }

    @Test
    void stateSaysWhatTheThreadWasDoing() {
        ThreadDump d = dump(false);
        assertEquals(tr("debugpanel.thread.breakpoint"), d.stateText(thread(1, "m", true, "breakpoint", true, PARK)));
        assertEquals(tr("debugpanel.thread.running"), d.stateText(thread(2, "w", false, "", false)));
        assertEquals(
                tr("debugpanel.thread.suspended"),
                d.stateText(thread(3, "w", false, "", false, PARK)),
                "walked, so it was stopped, whatever the adapter reported");
        ThreadDump paused = dump(true);
        assertEquals(
                tr("debugpanel.thread.running"),
                paused.stateText(thread(1, "m", true, "pause", true, PARK)),
                "paused for the dump: it was running");
    }

    @Test
    void runningThreadsWithoutAStackAreCounted() {
        ThreadDump d = dump(
                false,
                thread(1, "m", true, "breakpoint", true, PARK),
                thread(2, "a", false, "", false),
                thread(3, "b", false, "", false),
                thread(4, "c", true, "pause", false));
        assertEquals(2, d.runningWithoutStack(), "a suspended thread the adapter would not walk is not running");
    }

    @Test
    void theFilterMatchesANameOrAFrameIgnoringCase() {
        ThreadDump d = dump(false, thread(1, "Worker-7", true, "pause", false, PARK, TAKE));
        ThreadDump.Group g = d.groups(true).getFirst();
        assertTrue(ThreadDump.matches(g, ""));
        assertTrue(ThreadDump.matches(g, "  "));
        assertTrue(ThreadDump.matches(g, "worker"));
        assertTrue(ThreadDump.matches(g, "queue.take"));
        assertTrue(ThreadDump.matches(g, "Queue.java:42"), "the frame's text, 1-based line");
        assertFalse(ThreadDump.matches(g, "sleep"));
    }

    @Test
    void theJvmsOwnThreadsComeLast() {
        ThreadDump d = dump(
                false,
                thread(2, "Thread [Reference Handler]", false, "", false),
                thread(9, "Thread [worker-0]", true, "pause", false, PARK),
                thread(1, "Thread [main]", true, "breakpoint", true, TAKE),
                thread(3, "Thread [Finalizer]", false, "", false));
        assertEquals(
                List.of("main", "worker-0", "Reference Handler", "Finalizer"),
                d.threads().stream().map(ThreadDump::name).toList());
    }

    @Test
    void aThreadWithoutANameIsNamedByItsId() {
        assertEquals("#12", ThreadDump.name(thread(12, " ", true, "", false)));
        assertEquals("main", ThreadDump.name(thread(1, " main ", true, "", false)));
    }

    @Test
    void theTextReadsLikeJstack() {
        ThreadDump d = dump(
                false,
                thread(1, "main", true, "breakpoint", true, TAKE),
                thread(2, "pool-1", true, "pause", false, PARK),
                thread(3, "pool-2", true, "pause", false, PARK),
                thread(4, "io", false, "", false));
        String text = d.text(true);
        assertEquals(
                tr("threaddump.heading", "Main.java", "14:03:22", 4) + "\n"
                        + "\n"
                        + tr("threaddump.row", "\"main\" #1", tr("debugpanel.thread.breakpoint")) + "\n"
                        + "    at Queue.take(Queue.java:42)\n"
                        + "\n"
                        + tr("threaddump.rowMerged", "\"pool-1\", \"pool-2\"", 2, tr("debugpanel.thread.pause"))
                        + "\n"
                        + "    at Unsafe.park(Unsafe.java:10)\n"
                        + "\n"
                        + tr("threaddump.row", "\"io\" #4", tr("debugpanel.thread.running")) + "\n"
                        + "    " + tr("threaddump.noStack") + "\n",
                text);
        assertTrue(d.text(false).contains(tr("threaddump.row", "\"pool-2\" #3", tr("debugpanel.thread.pause"))));
        assertTrue(dump(true, thread(1, "m", true, "pause", true, PARK))
                .text(true)
                .contains(tr("threaddump.pausedForIt")));
    }

    @Test
    void aMergedRowNamesTheFirstFewThreads() {
        ThreadDump d = dump(
                false,
                thread(1, "a", true, "pause", false, PARK),
                thread(2, "b", true, "pause", false, PARK),
                thread(3, "c", true, "pause", false, PARK),
                thread(4, "d", true, "pause", false, PARK),
                thread(5, "e", true, "pause", false, PARK));
        assertEquals(
                tr("threaddump.rowMerged", "\"a\", \"b\", \"c\", …", 5, tr("debugpanel.thread.pause")),
                d.rowHeading(d.groups(true).getFirst()));
    }
}
