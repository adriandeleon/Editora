package com.editora.doctor;

/**
 * Severity of one Doctor row. {@code MISSING} means the feature is enabled, something in this window needs the
 * tool, and it is absent (red); {@code WARN} is degraded-but-working (amber — gh unauthenticated, an old JDK, a
 * project whose build tool is absent); {@code OPTIONAL} is a tool that is not installed but that nothing open
 * needs (a language server for a language never opened, an accelerator such as ripgrep) — a neutral fact, not
 * counted as a problem; {@code DISABLED} is an informational gray row for a feature that is switched off (its
 * tools are deliberately not probed); {@code CHECKING} is the transient state while a probe is in flight.
 */
public enum DoctorStatus {
    OK,
    WARN,
    MISSING,
    OPTIONAL,
    DISABLED,
    CHECKING;

    /** Whether the row needs the user's attention (amber or red). */
    public boolean needsAttention() {
        return this == WARN || this == MISSING;
    }
}
