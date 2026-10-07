package com.editora.github;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GhVersionTest {

    /** G19: {@code pr checks --json} arrived in gh 2.50, not the "2.4" Settings used to state. */
    @Test
    void theChecksRollUpNeedsGh250() {
        assertTrue(GhVersion.supportsChecksJson("gh version 2.96.0 (2026-07-02)"));
        assertTrue(GhVersion.supportsChecksJson("gh version 2.50.0 (2024-05-29)"));
        assertTrue(GhVersion.supportsChecksJson("gh version 3.0.0"));
        assertFalse(GhVersion.supportsChecksJson("gh version 2.49.2 (2024-05-13)"));
        assertFalse(GhVersion.supportsChecksJson("gh version 2.4.0 (2021-12-21)"));
        assertFalse(GhVersion.supportsChecksJson("gh version 1.14.0"));
    }

    @Test
    void anUnreadableVersionDisablesNothing() {
        assertTrue(GhVersion.supportsChecksJson(""));
        assertTrue(GhVersion.supportsChecksJson(null));
        assertTrue(GhVersion.supportsChecksJson("gh (development build)"));
    }

    @Test
    void extractsTheNumber() {
        assertEquals("2.49.2", GhVersion.number("gh version 2.49.2 (2024-05-13)"));
        assertEquals("wrapper", GhVersion.number(" wrapper "));
        assertEquals("2.50", GhVersion.MINIMUM);
    }
}
