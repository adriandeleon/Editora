package com.editora.ui;

import java.util.List;

import com.editora.config.Breakpoint;
import com.editora.config.StopFilters;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StopFilterCheckTest {

    @Test
    void globsIncludeAndExcludeIgnoringCase() {
        assertTrue(StopFilterCheck.matches("", List.of("anything")));
        assertTrue(StopFilterCheck.matches("worker-*", List.of("worker-3")));
        assertTrue(StopFilterCheck.matches("WORKER-?", List.of("worker-3")));
        assertFalse(StopFilterCheck.matches("worker-?", List.of("worker-12")));
        assertTrue(StopFilterCheck.matches("main, worker-*", List.of("worker-1")));
        assertFalse(StopFilterCheck.matches("worker-*", List.of("main")));
        assertFalse(StopFilterCheck.matches("!main", List.of("main")));
        assertTrue(StopFilterCheck.matches("!main", List.of("worker-1")), "only an exclusion: the rest pass");
        assertFalse(StopFilterCheck.matches("worker-*, !worker-2", List.of("worker-2")));
        assertTrue(StopFilterCheck.matches("a.b(c)", List.of("a.b(c)")), "no regex characters");
    }

    @Test
    void aCallerIsNamedAsShownWithoutItsArgumentsAndByItsFunction() {
        assertEquals(
                List.of("OrderService.save(Order)", "OrderService.save", "save"),
                StopFilterCheck.callerNames("OrderService.save(Order)"));
        assertEquals(List.of("handle"), StopFilterCheck.callerNames("handle"), "Python's");
        assertEquals(List.of("main.(*Service).Save", "Save"), StopFilterCheck.callerNames("main.(*Service).Save"));
        assertEquals(List.of("shapes::area", "area"), StopFilterCheck.callerNames("shapes::area"));
        assertEquals(List.of(""), StopFilterCheck.callerNames(null), "no caller");
        assertTrue(StopFilterCheck.matches("*.save", StopFilterCheck.callerNames("OrderService.save(Order)")));
        assertTrue(StopFilterCheck.matches("save", StopFilterCheck.callerNames("OrderService.save(Order)")));
        assertFalse(StopFilterCheck.matches("load", StopFilterCheck.callerNames("OrderService.save(Order)")));
    }

    @Test
    void theHitCountCountsOnlyHitsThatPassed() {
        assertTrue(StopFilterCheck.hitMatches("", 1));
        assertTrue(StopFilterCheck.hitMatches("3", 3));
        assertFalse(StopFilterCheck.hitMatches("3", 2));
        assertTrue(StopFilterCheck.hitMatches("== 3", 3));
        assertTrue(StopFilterCheck.hitMatches(">= 3", 4));
        assertFalse(StopFilterCheck.hitMatches("> 3", 3));
        assertTrue(StopFilterCheck.hitMatches("< 3", 2));
        assertTrue(StopFilterCheck.hitMatches("<=3", 3));
        assertTrue(StopFilterCheck.hitMatches("%2", 4));
        assertFalse(StopFilterCheck.hitMatches("% 2", 3));
        assertTrue(StopFilterCheck.hitMatches("whatever", 1), "a form not known: every hit");
    }

    @Test
    void theValueAMethodRunsOnIsThisOrSelf() {
        assertEquals("this", StopFilterCheck.selfName("java"));
        assertEquals("self", StopFilterCheck.selfName("python"));
        assertEquals("self", StopFilterCheck.selfName("ruby"));
        assertEquals("$this", StopFilterCheck.selfName("php"));
        assertEquals("this", StopFilterCheck.selfName(null));
    }

    @Test
    void filtersAreStoredAndAnOldBreakpointHasNone() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Breakpoint old = json.readValue("{\"line\":3,\"condition\":\"x > 1\",\"enabled\":true}", Breakpoint.class);
        assertTrue(old.filters().none());
        assertFalse(old.isFiltered());

        Breakpoint filtered = Breakpoint.plain(3, "x = 1").withFilters(new StopFilters(" worker-* ", "save", "mine"));
        assertEquals(new StopFilters("worker-*", "save", "mine"), filtered.filters(), "stripped");
        assertTrue(filtered.isConditional(), "drawn as a conditional breakpoint");
        Breakpoint back = json.readValue(json.writeValueAsString(filtered), Breakpoint.class);
        assertEquals(filtered, back);
        assertFalse(json.writeValueAsString(filtered).contains("\"none\""), "not a stored property");
        assertEquals(
                filtered.filters(),
                filtered.withLine(9).withEnabled(false).withGroup("g").filters(),
                "kept");
    }
}
