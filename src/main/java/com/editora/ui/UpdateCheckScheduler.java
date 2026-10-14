package com.editora.ui;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javafx.util.Duration;

import com.editora.config.Settings;
import com.editora.perf.AfterFirstPaint;
import com.editora.update.UpdateCheck;

/**
 * When the automatic release check runs: once per app session (a static guard, so several windows do not
 * each hit GitHub), once per day (the persisted timestamp), and {@link #DELAY} after the first painted
 * frame — it is a once-a-day HTTPS request whose TLS and HTTP-client setup has no business competing with
 * the session restore. The throttle timestamp is stamped when the check really starts, so a window closed
 * before then leaves the next launch free to check.
 */
final class UpdateCheckScheduler {

    /** How long after the first painted frame the check starts. */
    static final Duration DELAY = Duration.seconds(5);

    /**
     * System property: {@code off} skips the automatic check (the test suite sets it). Only the window that
     * runs the command sweep switched the setting off, so any other test whose window was still open five
     * seconds after its first frame really asked GitHub — and the sweep, when it happened to be running by
     * then, was the one to report it.
     */
    static final String PROPERTY = "editora.updateCheck";

    private static volatile boolean startedThisSession;

    private UpdateCheckScheduler() {}

    /**
     * Schedules the check when it is enabled, due and not already started this session.
     *
     * @param settings the live preferences (the throttle timestamp is stamped on them when the check starts)
     * @param closed whether the window has closed since (then the check is skipped)
     * @param requestSave persists the stamped timestamp
     * @param check asks the update service; the outcome is the caller's to apply
     */
    static void maybeSchedule(
            Supplier<Settings> settings, BooleanSupplier closed, Runnable requestSave, Runnable check) {
        if ("off".equals(System.getProperty(PROPERTY))) {
            return;
        }
        maybeSchedule(settings, closed, requestSave, check, UpdateCheckScheduler::afterDelay);
    }

    /** As above, with the delay mechanism injected (a test runs without the toolkit). */
    static void maybeSchedule(
            Supplier<Settings> settingsSupplier,
            BooleanSupplier closed,
            Runnable requestSave,
            Runnable check,
            BiConsumer<Duration, Runnable> delay) {
        Settings settings = settingsSupplier.get();
        if (!settings.isUpdateCheck() || startedThisSession) {
            return;
        }
        if (!UpdateCheck.isDue(
                settings.getLastUpdateCheckEpoch(), System.currentTimeMillis(), UpdateCheck.DEFAULT_INTERVAL_MS)) {
            return;
        }
        startedThisSession = true;
        AfterFirstPaint.run(() -> delay.accept(DELAY, () -> {
            if (closed.getAsBoolean()) {
                return;
            }
            settingsSupplier.get().setLastUpdateCheckEpoch(System.currentTimeMillis()); // throttle even on failure
            requestSave.run();
            check.run();
        }));
    }

    private static void afterDelay(Duration wait, Runnable then) {
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(wait);
        pause.setOnFinished(e -> then.run());
        pause.play();
    }

    /** Forgets that a check started — for a test that schedules more than one. */
    static void resetForTest() {
        startedThisSession = false;
    }
}
