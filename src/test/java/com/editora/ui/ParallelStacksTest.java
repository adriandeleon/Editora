package com.editora.ui;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.editora.dap.DapModels;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelStacksTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static final Path APP = Path.of("/app/Pool.java");

    private static int ids;

    /** A frame of {@code name} on (1-based) {@code line} of the app's file. */
    private static DapModels.StackFrameInfo f(String name, int line) {
        return new DapModels.StackFrameInfo(++ids, name, APP, line - 1, 0, null, "", null, null);
    }

    /** A thread whose stack is {@code frames}, outermost first (as one reads a stack upward). */
    private static DapModels.ThreadStack thread(
            int id, String name, boolean shown, DapModels.StackFrameInfo... frames) {
        List<DapModels.StackFrameInfo> topFirst = new ArrayList<>(List.of(frames));
        java.util.Collections.reverse(topFirst);
        return new DapModels.ThreadStack(new DapModels.ThreadStatus(id, name, true, "", shown), topFirst);
    }

    /**
     * main runs work() from loop(); two workers sleep in wait() (on two lines); a server thread handles a request; a
     * fifth thread was running and has no stack.
     */
    private static ParallelStacks pool() {
        List<DapModels.ThreadStack> threads = List.of(
                thread(1, "main", true, f("Thread.run", 1), f("run", 2), f("loop", 3), f("work", 4)),
                thread(2, "worker-1", false, f("Thread.run", 1), f("run", 2), f("wait", 7), f("sleep", 10)),
                thread(3, "worker-2", false, f("Thread.run", 1), f("run", 2), f("wait", 7), f("sleep", 12)),
                thread(4, "server", false, f("Thread.run", 1), f("serve", 20), f("handle", 21)),
                new DapModels.ThreadStack(new DapModels.ThreadStatus(5, "busy", false, "", false), List.of()));
        return new ParallelStacks(new ThreadDump("app", LocalTime.of(12, 0), false, threads));
    }

    private static List<String> frames(ParallelStacks.Box b) {
        return b.frames().stream().map(ParallelStacks.Frame::text).toList();
    }

    @Test
    void threadsShareABoxUntilTheirStacksPart() {
        ParallelStacks s = pool();
        assertEquals(1, s.withoutStack(), "the running thread is not drawn");
        assertEquals(1, s.roots().size(), "every thread starts in Thread.run");
        ParallelStacks.Box root = s.roots().getFirst();
        assertEquals(List.of("Thread.run:1"), frames(root));
        assertEquals(4, root.threads().size());

        ParallelStacks.Box run = root.above().get(0);
        ParallelStacks.Box server = root.above().get(1);
        assertEquals(List.of("run:2"), frames(run), "where main and the workers part");
        assertEquals(List.of("serve:20", "handle:21"), frames(server), "one thread: one box to its top");
        assertEquals("server", ParallelStacks.heading(server));

        ParallelStacks.Box main = run.above().get(0);
        ParallelStacks.Box workers = run.above().get(1);
        assertEquals(List.of("loop:3", "work:4"), frames(main));
        assertEquals(List.of("wait:7", "sleep (several lines)"), frames(workers), "sleep is on two lines");
        assertEquals("2 threads: worker-1, …", ParallelStacks.heading(workers));
        assertTrue(main.hasShownThread() && run.hasShownThread() && root.hasShownThread());
        assertFalse(workers.hasShownThread());
        assertEquals(3, main.top().opened().line(), "the shown thread's own frame (0-based)");
        assertEquals(5, s.boxes().size(), "root, run, server, main, workers");
    }

    @Test
    void aStackThatEndsWhereOthersGoOnEndsItsBox() {
        List<DapModels.ThreadStack> threads = List.of(
                thread(1, "a", false, f("main", 1), f("start", 2)),
                thread(2, "b", false, f("main", 1), f("start", 2), f("deeper", 3)));
        ParallelStacks s = new ParallelStacks(new ThreadDump("app", LocalTime.NOON, false, threads));
        ParallelStacks.Box root = s.roots().getFirst();
        assertEquals(List.of("main:1", "start:2"), frames(root));
        assertEquals(List.of("deeper:3"), frames(root.above().getFirst()), "only b goes on");
        assertEquals(1, root.above().getFirst().threads().size());
    }

    @Test
    void framesAreTheSameFunctionInTheSameFile() {
        DapModels.StackFrameInfo here = f("run", 1);
        DapModels.StackFrameInfo elsewhere =
                new DapModels.StackFrameInfo(99, "run", Path.of("/lib/Other.java"), 1, 0, null, "", null, null);
        assertEquals(ParallelStacks.key(here), ParallelStacks.key(f("run", 9)), "the line does not matter");
        assertFalse(ParallelStacks.key(here).equals(ParallelStacks.key(elsewhere)));
        List<DapModels.ThreadStack> threads = List.of(
                thread(1, "a", false, here),
                new DapModels.ThreadStack(new DapModels.ThreadStatus(2, "b", true, "", false), List.of(elsewhere)));
        assertEquals(
                2,
                new ParallelStacks(new ThreadDump("app", LocalTime.NOON, false, threads))
                        .roots()
                        .size());
    }

    @Test
    void theTreeIsLaidOutUpwardEachBoxCentredOverThoseAboveIt() {
        ParallelStacks s = pool();
        Map<ParallelStacks.Box, ParallelStacks.Place> places = s.layout(100, 20, 10, 8, 5);
        ParallelStacks.Box root = s.roots().getFirst();
        ParallelStacks.Box run = root.above().get(0);
        ParallelStacks.Box server = root.above().get(1);
        ParallelStacks.Place r = places.get(root);
        assertEquals(30, r.height(), "a heading and one frame");
        assertEquals(
                places.values().stream()
                        .mapToDouble(ParallelStacks.Place::bottom)
                        .max()
                        .orElseThrow(),
                r.bottom(),
                "the root is at the bottom");
        assertEquals(
                5,
                places.values().stream()
                        .mapToDouble(ParallelStacks.Place::y)
                        .min()
                        .orElseThrow(),
                1e-9);
        for (ParallelStacks.Box b : s.boxes()) {
            for (ParallelStacks.Box a : b.above()) {
                assertEquals(places.get(b).y() - 8, places.get(a).bottom(), 1e-9, "one gap above its box");
            }
        }
        // run's pile is two boxes wide (main, workers) and server one: side by side, not overlapping
        assertTrue(places.get(run).x() + 100 <= places.get(server).x());
        double pileLeft = places.get(run.above().get(0)).x();
        double pileRight = places.get(run.above().get(1)).x() + 100;
        assertEquals((pileLeft + pileRight) / 2, places.get(run).centerX(), 1e-9, "centred over its pile");
    }

    @Test
    void theTreeAsText() {
        String text = pool().text();
        assertTrue(text.contains("\n4 threads: main, …\n    Thread.run:1\n"), text);
        assertTrue(
                text.contains(
                        "\n        2 threads: worker-1, …\n            sleep (several lines)\n            wait:7\n"),
                text);
    }
}
