package com.editora.doctor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorSummaryTest {

    private static DoctorCheck with(DoctorStatus status) {
        DoctorCheck base = DoctorCheck.checking(status.name(), "sec", "L", "");
        return switch (status) {
            case OK -> base.ok("");
            case WARN -> base.warn("", "tip");
            case MISSING -> base.missing("tip");
            case DISABLED -> base.disabled();
            case CHECKING -> base;
        };
    }

    @Test
    void countsEveryStatus() {
        DoctorSummary s = DoctorSummary.of(List.of(
                with(DoctorStatus.OK),
                with(DoctorStatus.OK),
                with(DoctorStatus.WARN),
                with(DoctorStatus.MISSING),
                with(DoctorStatus.DISABLED),
                with(DoctorStatus.CHECKING)));
        assertEquals(2, s.ok());
        assertEquals(1, s.warn());
        assertEquals(1, s.missing());
        assertEquals(1, s.disabled());
        assertEquals(1, s.checking());
        assertEquals(2, s.issues());
        assertTrue(s.pending());
    }

    @Test
    void emptyIsAllZeroAndNotPending() {
        DoctorSummary s = DoctorSummary.of(List.of());
        assertEquals(0, s.ok());
        assertEquals(0, s.issues());
        assertFalse(s.pending());
    }
}
