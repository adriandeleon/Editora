package com.editora.install;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionFloorTest {

    @Test
    void theTableHoldsEveryFloorEditoraChecks() {
        assertEquals("25", VersionFloor.JAVA_RUN.minimum());
        assertEquals("2.50", VersionFloor.GH.minimum());
        assertEquals("18", VersionFloor.NODE_JS_DEBUG.minimum());
        assertEquals("20", VersionFloor.NODE_HTTP_SCRIPTS.minimum());
        assertEquals("22.13", VersionFloor.NODE_HTTP_PERMISSION.minimum());
    }

    @Test
    void majorAndMinorAreReadFromTheMinimum() {
        assertEquals(2, VersionFloor.GH.major());
        assertEquals(50, VersionFloor.GH.minor());
        assertEquals(25, VersionFloor.JAVA_RUN.major());
        assertEquals(0, VersionFloor.JAVA_RUN.minor());
    }

    @Test
    void aVersionLineMeetsTheFloorFromItsFirstNumber() {
        assertTrue(VersionFloor.GH.metBy("gh version 2.50.0 (2024-05-29)"));
        assertTrue(VersionFloor.GH.metBy("gh version 2.96.0 (2026-07-02)"));
        assertFalse(VersionFloor.GH.metBy("gh version 2.49.2 (2024-05-13)"));
        assertFalse(VersionFloor.NODE_JS_DEBUG.metBy("v16.20.2"));
        assertTrue(VersionFloor.NODE_JS_DEBUG.metBy("v18.0.0"));
    }

    @Test
    void anUnreadableVersionIsNewEnough() {
        assertTrue(VersionFloor.GH.metBy("gh version DEV"));
        assertTrue(VersionFloor.GH.metBy((String) null));
        assertTrue(VersionFloor.NODE_HTTP_SCRIPTS.metBy((int[]) null));
    }

    @Test
    void anArrayVersionComparesPartByPart() {
        assertTrue(VersionFloor.NODE_HTTP_PERMISSION.metBy(new int[] {22, 13, 0}));
        assertFalse(VersionFloor.NODE_HTTP_PERMISSION.metBy(new int[] {22, 12, 9}));
        assertTrue(VersionFloor.NODE_HTTP_PERMISSION.metBy(new int[] {23, 0, 0}));
        assertFalse(VersionFloor.NODE_HTTP_SCRIPTS.metBy(new int[] {19, 9, 0}));
    }
}
