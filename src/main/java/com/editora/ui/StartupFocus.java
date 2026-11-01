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
 * Welcome has no tab switch. Focus that moves between Editora's own windows is not a regain either
 * ({@link #onFocusGained}).
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
    /** The {@link #returns} count this window last refreshed for. */
    private long returnsSeen;

    /**
     * A focus gain this soon after another of Editora's own windows lost focus is focus moving between them
     * (a file opened from the Project Map raises its editor window, Escape leaves the Map, a dialog closes),
     * not a return from another program.
     */
    static final long TRANSFER_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250);

    private static boolean tracking;
    private static boolean anyStageLostFocus;
    private static boolean stageFocused;
    private static boolean lastGainWasTransfer;
    private static long lastStageFocusLostNanos;
    /** How often one of Editora's windows gained focus from another program (FX thread only). */
    private static long returns;

    /** The tab-switch fan-out computed Git status and build-tool markers for this window. */
    void repositoryStateComputed() {
        repositoryStateComputed = true;
    }

    /**
     * Decides the next focus gain, consuming the show-time one. Focus that comes from another Editora window
     * is not a regain (Map review 3 D3: opening a file from the Map used to re-list every open folder) unless
     * Editora came back from another program through one of its other windows since this one last refreshed:
     * a return to the Map window followed by a click into the editor still refreshes, once.
     */
    Action onFocusGained() {
        trackStages();
        boolean transfer = noteStageFocusGained();
        if (!startupFocusPending) {
            if (transfer && returnsSeen == returns) {
                return Action.NONE;
            }
            returnsSeen = returns;
            return Action.REGAIN;
        }
        startupFocusPending = false;
        returnsSeen = returns;
        if (repositoryStateComputed) {
            return Action.NONE;
        }
        repositoryStateComputed = true;
        return Action.STARTUP_REFRESH;
    }

    /**
     * Some stage of Editora's gained focus: whether another one lost it just before (focus moving between
     * Editora's windows); otherwise Editora came back from another program, and {@link #returns} counts it.
     */
    static boolean noteStageFocusGained() {
        if (stageFocused) {
            return lastGainWasTransfer; // the same gain, heard by the window's controller and by the tracker
        }
        stageFocused = true;
        lastGainWasTransfer = anyStageLostFocus && clock.getAsLong() - lastStageFocusLostNanos <= TRANSFER_NANOS;
        if (!lastGainWasTransfer) {
            returns++;
        }
        return lastGainWasTransfer;
    }

    /** Some stage of Editora's lost focus, to another of its windows or to another program. */
    static void noteStageFocusLost() {
        stageFocused = false;
        anyStageLostFocus = true;
        lastStageFocusLostNanos = clock.getAsLong();
    }

    /** The clock focus moves are timed by; a test steps it instead of sleeping. */
    static java.util.function.LongSupplier clock = System::nanoTime;

    /** Forgets every stage's focus history (a test starts from "nothing has lost focus yet"). */
    static void resetStageFocusForTest() {
        stageFocused = false;
        anyStageLostFocus = false;
        lastGainWasTransfer = false;
        clock = System::nanoTime;
    }

    /**
     * Follows the focus of every stage Editora shows (the Map, Settings, dialogs), once, from the first focus
     * gain on the FX thread. A pure test off that thread decides from the editor window's gains alone.
     */
    private static void trackStages() {
        if (tracking || !javafx.application.Platform.isFxApplicationThread()) {
            return;
        }
        tracking = true;
        javafx.collections.ObservableList<javafx.stage.Window> windows = javafx.stage.Window.getWindows();
        windows.forEach(StartupFocus::trackStage);
        windows.addListener((javafx.collections.ListChangeListener<javafx.stage.Window>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(StartupFocus::trackStage);
            }
        });
    }

    private static final Object TRACKED = new Object();

    private static void trackStage(javafx.stage.Window window) {
        if (!(window instanceof javafx.stage.Stage) || window.getProperties().putIfAbsent(TRACKED, TRACKED) != null) {
            return; // a popup (menu, tooltip) never takes focus from its owner
        }
        window.focusedProperty().addListener((obs, was, now) -> {
            if (Boolean.TRUE.equals(now)) {
                noteStageFocusGained();
            } else {
                noteStageFocusLost();
            }
        });
    }

    /** Back to "just shown, nothing computed" — for a test that replays the startup sequence. */
    void resetForTest() {
        startupFocusPending = true;
        repositoryStateComputed = false;
        returnsSeen = 0;
    }

    boolean startupFocusPending() {
        return startupFocusPending;
    }

    boolean isRepositoryStateComputed() {
        return repositoryStateComputed;
    }
}
