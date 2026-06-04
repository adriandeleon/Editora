package com.editora.config;

/**
 * Lifecycle of a {@link PersonalNote}: {@code ACTIVE} (attached), {@code RESOLVED} (kept but marked done),
 * {@code ORPHANED} (its anchor could no longer be relocated — kept for recovery, never silently deleted).
 */
public enum NoteStatus {
    ACTIVE,
    RESOLVED,
    ORPHANED
}
