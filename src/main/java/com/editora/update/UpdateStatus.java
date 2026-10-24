package com.editora.update;

import org.jspecify.annotations.Nullable;

/**
 * What the last update check that got an answer found: the newest published release ({@code latestVersion}
 * blank when no check has answered yet), its release page, and when the answer came. Pure, toolkit-free; it
 * decides what the About panel and the status bar say about updates until the next check.
 */
public record UpdateStatus(String latestVersion, String latestUrl, long checkedAtMs) {

    /** What the status means for a given running version. */
    public enum Verdict {
        /** No check has answered yet, so there is nothing to claim either way. */
        NOT_CHECKED,
        /** The newest release found is not newer than the running one. */
        UP_TO_DATE,
        /** A newer release was found. */
        UPDATE_AVAILABLE
    }

    public static final UpdateStatus NONE = new UpdateStatus("", "", 0);

    public UpdateStatus {
        latestVersion = latestVersion == null ? "" : latestVersion.strip();
        latestUrl = latestUrl == null ? "" : latestUrl.strip();
    }

    public Verdict verdict(String runningVersion) {
        if (latestVersion.isEmpty()) {
            return Verdict.NOT_CHECKED;
        }
        return UpdateCheck.isNewer(runningVersion, latestVersion) ? Verdict.UPDATE_AVAILABLE : Verdict.UP_TO_DATE;
    }

    /** The newer release to offer, or {@code null} when there is none (up to date, or not checked). */
    public @Nullable ReleaseInfo update(String runningVersion) {
        return verdict(runningVersion) == Verdict.UPDATE_AVAILABLE
                ? new ReleaseInfo(latestVersion, latestUrl, "")
                : null;
    }
}
