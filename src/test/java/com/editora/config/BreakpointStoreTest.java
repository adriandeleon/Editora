package com.editora.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for {@link BreakpointStore}: bucketing + order-preserving merge. */
class BreakpointStoreTest {

    @Test
    void bucketCreatesAndReusesPerProject() {
        BreakpointStore s = new BreakpointStore();
        assertTrue(s.bucket("p1").isEmpty());
        s.bucket("p1").put("/a.java", List.of(Breakpoint.plain(1, "a")));
        assertEquals(1, s.bucket("p1").size());
        // Same key returns the same map instance; a null key maps to the global "" bucket.
        assertSame(s.bucket("p1"), s.bucket("p1"));
        assertSame(s.bucket(""), s.bucket(null));
    }

    @Test
    void setByProjectNullResetsToEmpty() {
        BreakpointStore s = new BreakpointStore();
        s.setByProject(null);
        assertNotNull(s.getByProject());
        assertTrue(s.getByProject().isEmpty());
        assertEquals(BreakpointStore.SCHEMA_VERSION, s.getSchemaVersion());
        s.setSchemaVersion(9);
        assertEquals(9, s.getSchemaVersion());
    }

    @Test
    void mergeWithNoPreviousOrderReturnsCurrent() {
        List<Breakpoint> cur = List.of(Breakpoint.plain(2, "b"), Breakpoint.plain(1, "a"));
        assertEquals(cur, BreakpointStore.mergePreservingOrder(null, cur));
        assertEquals(cur, BreakpointStore.mergePreservingOrder(List.of(), cur));
        assertTrue(BreakpointStore.mergePreservingOrder(null, null).isEmpty());
    }

    @Test
    void mergeKeepsPreviousOrderMatchingByLine() {
        List<Breakpoint> prev = List.of(Breakpoint.plain(5, "five"), Breakpoint.plain(1, "one"));
        List<Breakpoint> cur = List.of(Breakpoint.plain(1, "one"), Breakpoint.plain(5, "five"));
        // Result follows prev's order (5 then 1), not the snapshot's (1 then 5).
        List<Breakpoint> merged = BreakpointStore.mergePreservingOrder(prev, cur);
        assertEquals(5, merged.get(0).line());
        assertEquals(1, merged.get(1).line());
    }

    @Test
    void mergeMatchesByLineTextWhenLineShiftedThenAppendsNew() {
        List<Breakpoint> prev = List.of(Breakpoint.plain(10, "shifted"));
        // Same content, new line number (an external edit moved it), plus a brand-new breakpoint.
        List<Breakpoint> cur = List.of(Breakpoint.plain(99, "brand-new"), Breakpoint.plain(12, "shifted"));
        List<Breakpoint> merged = BreakpointStore.mergePreservingOrder(prev, cur);
        assertEquals("shifted", merged.get(0).lineText()); // matched by lineText, emitted first
        assertEquals(12, merged.get(0).line());
        assertEquals("brand-new", merged.get(1).lineText()); // unmatched snapshot entry appended
        assertEquals(2, merged.size());
    }
}
