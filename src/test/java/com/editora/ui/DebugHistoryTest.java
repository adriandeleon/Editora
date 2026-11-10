package com.editora.ui;

import java.nio.file.Path;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Debug History's stops: what each kept, how many, and a value followed through them. */
class DebugHistoryTest {

    private static final Path FILE = Path.of("Main.java").toAbsolutePath();
    private static final LocalTime T = LocalTime.of(12, 0, 5);

    private static DapModels.StackFrameInfo frame(int id, String name, int line) {
        return new DapModels.StackFrameInfo(id, name, FILE, line, 1);
    }

    /** main called from nowhere; work called from main. */
    private static final List<DapModels.StackFrameInfo> IN_WORK = List.of(frame(2, "work", 9), frame(1, "main", 3));

    private static final List<DapModels.StackFrameInfo> IN_MAIN = List.of(frame(1, "main", 4));

    private static final List<String> TOTAL = List.of("Locals", "total");

    @Test
    void eachStopKeepsWhatItShowedByFrame() {
        DebugHistory h = new DebugHistory();
        DebugHistory.Stop first = h.newStop("breakpoint", T, 1, IN_WORK, 0);
        h.record(0, TOTAL, "4", "int", false);
        h.record(0, List.of("Locals", "items"), "List(2)", "List", true);
        h.record(0, List.of("Locals", "items", "[0]"), "\"pencil\"", "String", false);
        h.record(1, List.of("Locals", "args"), "String[0]", "String[]", true);
        h.record(7, TOTAL, "x", "int", false); // a frame the stop does not have: ignored
        h.recordInline(0, FILE, 9, Map.of("total", "4"));
        h.recordPins(FILE, Map.of(9, "total = 4"));
        h.recordException("IllegalStateException: boom");
        assertEquals(1, first.number);
        assertEquals("breakpoint", first.reason);
        assertEquals(
                List.of("total", "items", "[0]"),
                first.rows(0).stream().map(DebugHistory.Row::name).toList());
        assertTrue(first.rows(0).get(1).expandable());
        assertEquals("4", first.value(0, TOTAL));
        assertTrue(first.hasRows(1));
        assertFalse(first.hasRows(5));
        assertEquals(Map.of("total", "4"), first.inline(0).values());
        assertNull(first.inline(1));
        assertEquals(Map.of(FILE, Map.of(9, "total = 4")), first.pins());
        assertEquals("IllegalStateException: boom", first.exception());
        assertEquals("work", first.top().name());
        h.recordPins(FILE, null);
        assertTrue(first.pins().isEmpty(), "pins cleared from a file");
    }

    @Test
    void theOldestStopsAreForgottenPastTheCapacity() {
        DebugHistory h = new DebugHistory();
        h.setCapacity(2);
        for (int i = 0; i < 4; i++) {
            h.newStop("step", T, 1, IN_MAIN, 0);
        }
        assertEquals(List.of(3, 4), h.stops().stream().map(s -> s.number).toList(), "numbers go on counting");
        assertNull(h.byNumber(1));
        assertSame(h.last(), h.byNumber(4));
        h.setCapacity(1);
        assertEquals(1, h.size());
        h.setCapacity(0);
        assertTrue(h.isEmpty());
        assertNull(h.newStop("step", T, 1, IN_MAIN, 0), "0: nothing is kept");
        h.record(0, TOTAL, "1", "int", false); // nothing to record into
        h.recordInline(0, FILE, 1, Map.of());
        h.recordPins(FILE, Map.of(1, "x"));
        h.recordException("x");
        assertEquals(0, h.capacity());
        h.setCapacity(-3);
        assertEquals(0, h.capacity());
        h.setCapacity(DebugHistory.DEFAULT_CAPACITY);
        h.newStop("step", T, 1, IN_MAIN, 0);
        assertEquals(6, h.last().number);
        h.reset();
        assertTrue(h.isEmpty());
        assertEquals(1, h.newStop("step", T, 1, IN_MAIN, 0).number, "a new session counts from 1");
    }

    @Test
    void aValueIsFollowedThroughTheStopsOfItsFrame() {
        DebugHistory h = new DebugHistory();
        DebugHistory.Stop s1 = h.newStop("breakpoint", T, 1, IN_WORK, 0);
        h.record(0, TOTAL, "0", "int", false);
        DebugHistory.Stop s2 = h.newStop("step", T.plusSeconds(1), 1, IN_MAIN, 0); // main: another frame
        h.record(0, TOTAL, "99", "int", false);
        DebugHistory.Stop s3 = h.newStop("step", T.plusSeconds(2), 1, IN_WORK, 0);
        h.record(0, TOTAL, "0", "int", false);
        DebugHistory.Stop s4 = h.newStop("step", T.plusSeconds(3), 1, IN_WORK, 0);
        h.record(0, TOTAL, "4", "int", false);

        String work = s1.frameKey(0);
        assertEquals(0, s4.frameIndex(work));
        assertEquals(-1, s2.frameIndex(work));
        assertEquals(
                List.of(
                        new DebugHistory.Seen(1, T, "0", false),
                        new DebugHistory.Seen(3, T.plusSeconds(2), "0", false),
                        new DebugHistory.Seen(4, T.plusSeconds(3), "4", true)),
                h.valueHistory(work, TOTAL));
        assertTrue(h.changed(s4, 0, TOTAL, "4"), "0 at stop 3, 4 at stop 4");
        assertFalse(h.changed(s3, 0, TOTAL, "0"), "the same as stop 1 (stop 2 was another frame)");
        assertFalse(h.changed(s1, 0, TOTAL, "0"), "nothing before it");
        assertFalse(h.changed(s2, 0, TOTAL, "99"));
        assertSame(s2, h.before(s3));
        assertSame(s4, h.after(s3));
        assertNull(h.after(s4));
        assertNull(h.before(s1));
        assertNull(h.before(new DebugHistory.Stop(9, "", T, 1, IN_MAIN, 0)), "a stop not kept");
    }

    @Test
    void aStopWithoutFramesHasNoneShown() {
        DebugHistory.Stop empty = new DebugHistory.Stop(1, "pause", T, 1, List.of(), 3);
        assertEquals(-1, empty.shownFrame);
        assertNull(empty.top());
        DebugHistory.Stop clamped = new DebugHistory.Stop(2, "pause", T, 1, IN_WORK, 9);
        assertEquals(1, clamped.shownFrame);
    }
}
