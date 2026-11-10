package com.editora.dap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A window's second debug manager shares the first one's settings, and nothing of its session. */
class DapManagerNewSessionTest {

    @Test
    void aNewSessionSharesTheSettingsBothWays() {
        DapManager first = new DapManager(null);
        first.configure(true, "/no/such/java-debug");
        first.setServerProvidesJavaDebug(true);
        first.setExceptionFilters("python", List.of("raised"));

        DapManager second = first.newSession();
        assertNotSame(first, second);
        assertTrue(second.sharesSettingsWith(first));
        assertTrue(second.isAdapterAvailable(), "Java debugging on, as the first manager was told");
        assertEquals(Map.of("python", List.of("raised")), second.exceptionChoices());

        second.setHexValues(true);
        second.setExceptionFilters("javascript", List.of("uncaught"));
        assertTrue(first.hexValues(), "a setting changed through either reaches both");
        assertEquals(List.of("uncaught"), first.exceptionChoices().get("javascript"));

        first.configure(false, "");
        assertFalse(second.isAdapterAvailable(), "debugging switched off for every session");
    }

    @Test
    void aNewSessionStartsWithNoneOfItsOwn() {
        DapManager first = new DapManager(null);
        DapManager second = first.newSession();
        assertEquals(DapManager.State.INACTIVE, second.state());
        assertFalse(second.isActive());
        assertFalse(second.programInputAvailable());
        second.shutdown();
        assertEquals(DapManager.State.INACTIVE, first.state(), "ending one manager leaves the other");
    }

    @Test
    void twoWindowsDoNotShare() {
        DapManager one = new DapManager(null);
        DapManager other = new DapManager(null);
        assertFalse(one.sharesSettingsWith(other));
        one.setHexValues(true);
        assertFalse(other.hexValues());
    }
}
