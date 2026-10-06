package com.editora.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

/**
 * Runs an action on the FX thread at the pace {@link ThrottlePolicy} decides: at once for the first request
 * of a burst, then at most once per interval while requests keep arriving. FX thread only.
 */
final class FxThrottle {

    private final ThrottlePolicy policy;
    private final boolean deferImmediate;
    private final Runnable action;
    private final PauseTransition timer = new PauseTransition();

    /**
     * @param deferImmediate whether a run that is due at once is posted to the end of the FX queue instead
     *     of run inside {@link #request} — so that everything already queued (a burst that arrived together)
     *     is in place before the action looks at it
     */
    FxThrottle(int intervalMillis, boolean deferImmediate, Runnable action) {
        this.policy = new ThrottlePolicy(intervalMillis * 1_000_000L);
        this.deferImmediate = deferImmediate;
        this.action = action;
        timer.setOnFinished(e -> runIfScheduled());
    }

    /** Asks for the action to run; cheap when a run is already due. */
    void request() {
        long delay = policy.request(System.nanoTime());
        if (delay == ThrottlePolicy.ALREADY_SCHEDULED) {
            return;
        }
        if (delay > 0) {
            timer.setDuration(Duration.millis(Math.max(1, delay / 1_000_000.0)));
            timer.playFromStart();
        } else if (deferImmediate) {
            Platform.runLater(this::runIfScheduled);
        } else {
            runIfScheduled();
        }
    }

    /** Runs a scheduled action now instead of when it is due; nothing happens when none is scheduled. */
    void flush() {
        timer.stop();
        runIfScheduled();
    }

    /** Drops a scheduled run. */
    void cancel() {
        timer.stop();
        policy.cancel();
    }

    private void runIfScheduled() {
        if (!policy.scheduled()) {
            return; // flushed or cancelled since it was scheduled
        }
        policy.ran(System.nanoTime());
        action.run();
    }
}
