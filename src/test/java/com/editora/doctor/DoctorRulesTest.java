package com.editora.doctor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorRulesTest {

    @Test
    void singleTokenPresenceCountsAnyCleanLaunch() {
        assertTrue(DoctorRules.presentFrom(0, false));
        // A tool that launches but rejects --version is still installed.
        assertTrue(DoctorRules.presentFrom(2, false));
        // -1 is the runner's failed-to-start sentinel: not installed.
        assertFalse(DoctorRules.presentFrom(-1, false));
    }

    @Test
    void wrapperPresenceRequiresSuccess() {
        // npx always launches, so only exit 0 proves the wrapped package (the maid rule).
        assertTrue(DoctorRules.presentFrom(0, true));
        assertFalse(DoctorRules.presentFrom(2, true));
        assertFalse(DoctorRules.presentFrom(-1, true));
    }

    @Test
    void ghStatusDistinguishesMissingUnauthenticatedAndReady() {
        assertEquals(DoctorStatus.MISSING, DoctorRules.ghStatus(false, false));
        assertEquals(DoctorStatus.MISSING, DoctorRules.ghStatus(false, true));
        assertEquals(DoctorStatus.WARN, DoctorRules.ghStatus(true, false));
        assertEquals(DoctorStatus.OK, DoctorRules.ghStatus(true, true));
    }

    @Test
    void javaRunStatusTiers() {
        assertEquals(DoctorStatus.OK, DoctorRules.javaRunStatus(25));
        assertEquals(DoctorStatus.OK, DoctorRules.javaRunStatus(26));
        assertEquals(DoctorStatus.WARN, DoctorRules.javaRunStatus(24));
        assertEquals(DoctorStatus.WARN, DoctorRules.javaRunStatus(8));
        assertEquals(DoctorStatus.WARN, DoctorRules.javaRunStatus(1));
        assertEquals(DoctorStatus.MISSING, DoctorRules.javaRunStatus(0));
        assertEquals(DoctorStatus.MISSING, DoctorRules.javaRunStatus(-1));
    }

    @Test
    void firstLinePrefersStdoutSkipsBlanksAndStrips() {
        assertEquals("git version 2.54.0", DoctorRules.firstLine("git version 2.54.0\nmore", "err"));
        assertEquals("from stderr", DoctorRules.firstLine("", "\n  from stderr  \n"));
        assertEquals("second", DoctorRules.firstLine("\n\n  second \n", ""));
        assertEquals("", DoctorRules.firstLine("", ""));
        assertEquals("", DoctorRules.firstLine(null, null));
    }
}
