package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.editora.config.Breakpoint;
import com.editora.config.MemberBreakpoint;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules of {@link TriggeredBreakpoints}: when a breakpoint waits, what it can wait for, how it is named. */
class TriggeredBreakpointsTest {

    private static BreakpointsPicker.Entry entry(String file, int line, Breakpoint bp) {
        return new BreakpointsPicker.Entry(Path.of(file), file, line, bp.withLine(line), null);
    }

    @Test
    void aBreakpointWaitsUntilItsTriggerIsHitAndOnlyWhileThatExists() {
        Breakpoint waiting = Breakpoint.plain(3, "x").withTriggeredBy("aa");
        assertTrue(TriggeredBreakpoints.waits(waiting, Set.of("aa"), Set.of()));
        assertFalse(TriggeredBreakpoints.waits(waiting, Set.of("aa"), Set.of("aa")), "its trigger was hit");
        assertFalse(TriggeredBreakpoints.waits(waiting, Set.of("bb"), Set.of()), "its trigger is gone");
        assertFalse(TriggeredBreakpoints.waits(Breakpoint.plain(3, "x"), Set.of("aa"), Set.of()), "it waits for none");
    }

    @Test
    void aBreakpointCanWaitForAnyOtherButOneThatWaitsForIt() {
        BreakpointsPicker.Entry self =
                entry("/p/A.java", 1, Breakpoint.plain(0, "a").withId("s"));
        BreakpointsPicker.Entry other = entry("/p/A.java", 5, Breakpoint.plain(0, "b"));
        BreakpointsPicker.Entry direct =
                entry("/p/B.java", 2, Breakpoint.plain(0, "c").withId("d").withTriggeredBy("s"));
        BreakpointsPicker.Entry indirect =
                entry("/p/B.java", 7, Breakpoint.plain(0, "e").withId("i").withTriggeredBy("d"));
        BreakpointsPicker.Entry member = BreakpointsPicker.Entry.of(
                new MemberBreakpoint(MemberBreakpoint.Kind.METHOD, "p.A", "run", "", "", "", true));
        List<BreakpointsPicker.Entry> all = List.of(self, other, direct, indirect, member);

        assertEquals(List.of(other), TriggeredBreakpoints.choices("/p/A.java", 1, "s", all));
        // With no id nothing can wait for it yet: every other line breakpoint is a choice.
        assertEquals(List.of(other, direct, indirect), TriggeredBreakpoints.choices("/p/A.java", 1, "", all));
        // A chain that loops on itself elsewhere does not hang the walk.
        BreakpointsPicker.Entry loopA =
                entry("/p/C.java", 1, Breakpoint.plain(0, "f").withId("la").withTriggeredBy("lb"));
        BreakpointsPicker.Entry loopB =
                entry("/p/C.java", 2, Breakpoint.plain(0, "g").withId("lb").withTriggeredBy("la"));
        assertEquals(
                List.of(loopA, loopB), TriggeredBreakpoints.choices("/p/A.java", 1, "s", List.of(self, loopA, loopB)));
    }

    @Test
    void breakpointsAreNamedByFileAndLine() {
        List<BreakpointsPicker.Entry> all = List.of(
                entry("/p/A.java", 11, Breakpoint.plain(0, "a").withId("x1")),
                entry("/p/B.java", 0, Breakpoint.plain(0, "b")));
        var labels = TriggeredBreakpoints.labels(all);
        assertEquals("A.java:12", labels.apply("x1"));
        assertNull(labels.apply("missing"));
        assertEquals(Set.of("x1"), TriggeredBreakpoints.ids(all));
    }

    @Test
    void aClosedFilesWaitingBreakpointIsNotSent() {
        String path = "/proj/App.java";
        Map<String, List<Breakpoint>> map = Map.of(
                path,
                List.of(
                        Breakpoint.plain(0, "start();").withId("t"),
                        Breakpoint.plain(2, "later();").withTriggeredBy("t")));
        List<String> text = List.of("start();", "", "later();");
        List<DapModels.FileBreakpoints> waiting = ClosedFileBreakpoints.of(
                map, Set.of(), p -> true, p -> text, bp -> !TriggeredBreakpoints.waits(bp, Set.of("t"), Set.of()));
        assertEquals(
                List.of(0),
                waiting.getFirst().breakpoints().stream()
                        .map(DapModels.LineBreakpoint::line)
                        .toList());
        List<DapModels.FileBreakpoints> armed = ClosedFileBreakpoints.of(
                map, Set.of(), p -> true, p -> text, bp -> !TriggeredBreakpoints.waits(bp, Set.of("t"), Set.of("t")));
        assertEquals(
                List.of(0, 2),
                armed.getFirst().breakpoints().stream()
                        .map(DapModels.LineBreakpoint::line)
                        .toList());
    }
}
