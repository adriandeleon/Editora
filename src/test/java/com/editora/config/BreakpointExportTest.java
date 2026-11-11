package com.editora.config;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A breakpoints file written and read back, and the ids an import takes. */
class BreakpointExportTest {

    @Test
    void everythingABreakpointIsComesBack() throws IOException {
        Breakpoint bp = new Breakpoint(4, "i > 2", "", false, "total += i", "3", true, List.of(1), false, true)
                .withId("aa")
                .withTriggeredBy("bb")
                .withGroup("Loop");
        MemberBreakpoint m = MemberBreakpoint.field("p.A", "count", "read").withGroup("Loop");
        String text =
                BreakpointExport.write(List.of(new BreakpointExport.FileBreakpoint("src/Main.java", bp)), List.of(m));
        assertTrue(text.contains("\"format\" : \"editora-breakpoints\""), text);
        BreakpointExport.Document back = BreakpointExport.read(text);
        assertEquals(List.of(new BreakpointExport.FileBreakpoint("src/Main.java", bp)), back.breakpoints());
        assertEquals(List.of(m), back.memberBreakpoints());
    }

    @Test
    void anythingElseIsRefusedAndBrokenEntriesAreLeftOut() throws IOException {
        assertThrows(IOException.class, () -> BreakpointExport.read("{\"byProject\":{}}"));
        assertThrows(IOException.class, () -> BreakpointExport.read("not json"));
        assertThrows(
                IOException.class,
                () -> BreakpointExport.read("{\"format\":\"editora-breakpoints\",\"version\":2}"),
                "a later version");
        BreakpointExport.Document d = BreakpointExport.read("""
                {"format":"editora-breakpoints","version":1,
                 "breakpoints":[{"file":"","breakpoint":{"line":1}},{"file":"a.py"},
                                {"file":"a.py","breakpoint":{"line":-1}},{"file":"a.py","breakpoint":{"line":2}}],
                 "memberBreakpoints":[{"kind":"METHOD","className":"","member":"x"},
                                      {"kind":"METHOD","className":"p.A","member":"run"}]}
                """);
        assertEquals(
                List.of(2),
                d.breakpoints().stream().map(f -> f.breakpoint().line()).toList());
        assertEquals(
                List.of("run"),
                d.memberBreakpoints().stream().map(MemberBreakpoint::member).toList());
    }

    @Test
    void anIdSetElsewhereIsReplacedAndWhatWaitsForItFollows() {
        BreakpointExport.FileBreakpoint trigger = new BreakpointExport.FileBreakpoint(
                "a.py", Breakpoint.plain(1, "x").withId("t"));
        BreakpointExport.FileBreakpoint waiting = new BreakpointExport.FileBreakpoint(
                "a.py", Breakpoint.plain(5, "y").withTriggeredBy("t"));
        List<BreakpointExport.FileBreakpoint> both = List.of(trigger, waiting);
        // The same breakpoint, imported again: its id stays.
        assertSame(
                both,
                BreakpointExport.freshIds(
                        both,
                        Map.of("t", "a.py:1"),
                        fb -> fb.file() + ":" + fb.breakpoint().line()));
        // Another breakpoint has it: a new one, and the waiting one follows.
        List<BreakpointExport.FileBreakpoint> out = BreakpointExport.freshIds(
                both,
                Map.of("t", "b.py:9"),
                fb -> fb.file() + ":" + fb.breakpoint().line());
        String id = out.get(0).breakpoint().id();
        assertNotEquals("t", id);
        assertEquals(id, out.get(1).breakpoint().triggeredBy());
    }
}
