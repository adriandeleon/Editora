package com.editora.ui;

/**
 * What a window's focus gain does at startup. Pure, so the decision is unit-tested.
 *
 * <p>The focus a window gains by being shown used to run the whole regain fan-out on top of what
 * {@code MainController.init} and the session restore had just done: a second {@code git status} + diff
 * before the first had answered, a second build-tool detection, a clipboard round trip on the FX thread and
 * a re-listing of every expanded Project folder. Nothing happened "while we were away", so that first focus
 * is not a regain. It only computes the repository state (Git status, build-tool markers) when the startup
 * path has not: the tab-switch fan-out does it when a session is restored, and a window that opens on
 * Welcome has no tab switch.
 */
final class StartupFocus {

    /** What the controller does for one focus gain. */
    enum Action {
        /** The show-time focus; the repository state is already known. */
        NONE,
        /** The show-time focus of a window whose repository state nothing has computed yet. */
        STARTUP_REFRESH,
        /** A real regain: re-check what another program may have changed. */
        REGAIN
    }

    private boolean startupFocusPending = true;
    private boolean repositoryStateComputed;

    /** The tab-switch fan-out computed Git status and build-tool markers for this window. */
    void repositoryStateComputed() {
        repositoryStateComputed = true;
    }

    /** Decides the next focus gain, consuming the show-time one. */
    Action onFocusGained() {
        if (!startupFocusPending) {
            return Action.REGAIN;
        }
        startupFocusPending = false;
        if (repositoryStateComputed) {
            return Action.NONE;
        }
        repositoryStateComputed = true;
        return Action.STARTUP_REFRESH;
    }

    /** Back to "just shown, nothing computed" — for a test that replays the startup sequence. */
    void resetForTest() {
        startupFocusPending = true;
        repositoryStateComputed = false;
    }

    boolean startupFocusPending() {
        return startupFocusPending;
    }

    boolean isRepositoryStateComputed() {
        return repositoryStateComputed;
    }
}
