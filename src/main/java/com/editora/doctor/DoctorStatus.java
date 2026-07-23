package com.editora.doctor;

/**
 * Severity of one Doctor row. {@code MISSING} means the feature is enabled but its tool is absent (red);
 * {@code WARN} is degraded-but-working (amber — an optional accelerator absent, gh unauthenticated, an old
 * JDK); {@code DISABLED} is an informational gray row for a feature that is switched off (its tools are
 * deliberately not probed); {@code CHECKING} is the transient state while a probe is in flight.
 */
public enum DoctorStatus {
    OK,
    WARN,
    MISSING,
    DISABLED,
    CHECKING
}
