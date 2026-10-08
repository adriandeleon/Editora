package com.editora.sync;

import java.util.List;

/**
 * What one sync did.
 *
 * @param received entries this machine took from the repository
 * @param sent entries the repository took from this machine
 * @param conflicts entries changed differently on both sides; this machine's version was kept (also in
 *     {@code sent})
 * @param skipped files left alone on both sides
 * @param detail git's message for a failure, or the category for {@link Status#NEEDS_CONFIRMATION}
 */
public record SyncReport(
        Status status,
        List<Change> received,
        List<Change> sent,
        List<Change> conflicts,
        List<Skipped> skipped,
        String detail) {

    public enum Status {
        /** Both sides now hold the merged data. */
        OK,
        /** Git is not installed (or the configured command does not start). */
        NO_GIT,
        /** The repository could not be reached or refused the sign-in; nothing was changed anywhere. */
        FETCH_FAILED,
        /** This machine is up to date, but its own changes could not be pushed. */
        PUSH_FAILED,
        /** A newer Editora wrote the repository; this build must not touch it. */
        NEWER_FORMAT,
        /** The merge would remove most of a category; nothing was changed. */
        NEEDS_CONFIRMATION,
        /** The local files changed while the sync was running; nothing was changed, the next run retries. */
        LOCAL_BUSY,
        /** A local git or file error. */
        FAILED
    }

    /** One entry of one category, named as the user knows it. */
    public record Change(SyncCategory category, String label) {}

    /** A file neither side touched, and why. */
    public record Skipped(String path, SyncMerge.Skip reason) {}

    public boolean ok() {
        return status == Status.OK;
    }

    /** Whether the sync moved any data in either direction. */
    public boolean changedAnything() {
        return !received.isEmpty() || !sent.isEmpty();
    }

    static SyncReport failure(Status status, String detail) {
        return new SyncReport(status, List.of(), List.of(), List.of(), List.of(), detail == null ? "" : detail);
    }
}
