package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.util.Duration;

import com.editora.config.Settings;
import com.editora.perf.AfterFirstPaint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The automatic release check: once per session, once per day, a few seconds after the first paint. */
class UpdateCheckSchedulerTest {

    private final List<Runnable> delayed = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private boolean closed;

    @BeforeEach
    void fresh() {
        UpdateCheckScheduler.resetForTest();
    }

    @AfterEach
    void letGo() {
        AfterFirstPaint.release();
        UpdateCheckScheduler.resetForTest();
    }

    private void schedule(Settings settings) {
        UpdateCheckScheduler.maybeSchedule(
                () -> settings,
                () -> closed,
                () -> events.add("save"),
                () -> events.add("check"),
                (Duration wait, Runnable then) -> {
                    assertEquals(UpdateCheckScheduler.DELAY, wait);
                    delayed.add(then);
                });
    }

    @Test
    void waitsForTheFirstPaintThenTheDelayThenStampsAndChecks() {
        Settings settings = new Settings();
        settings.setLastUpdateCheckEpoch(0);
        AfterFirstPaint.hold();
        schedule(settings);
        assertTrue(delayed.isEmpty(), "nothing before the first painted frame");
        AfterFirstPaint.release();
        assertEquals(1, delayed.size(), "then the delay starts");
        assertEquals(0, settings.getLastUpdateCheckEpoch(), "the throttle is stamped when the check runs, not before");
        delayed.get(0).run();
        assertTrue(settings.getLastUpdateCheckEpoch() > 0);
        assertEquals(List.of("save", "check"), events);
    }

    @Test
    void onlyOnceASessionAndNotWhenOffOrNotDue() {
        Settings settings = new Settings();
        settings.setLastUpdateCheckEpoch(0);
        schedule(settings);
        schedule(settings); // a second window
        assertEquals(1, delayed.size(), "one check per session");

        UpdateCheckScheduler.resetForTest();
        delayed.clear();
        settings.setLastUpdateCheckEpoch(System.currentTimeMillis()); // checked a moment ago
        schedule(settings);
        assertTrue(delayed.isEmpty(), "not due");

        settings.setLastUpdateCheckEpoch(0);
        settings.setUpdateCheck(false);
        schedule(settings);
        assertTrue(delayed.isEmpty(), "switched off");
    }

    @Test
    void aWindowClosedBeforeTheDelayEndsDoesNotCheck() {
        Settings settings = new Settings();
        settings.setLastUpdateCheckEpoch(0);
        schedule(settings);
        closed = true;
        delayed.get(0).run();
        assertTrue(events.isEmpty());
        assertEquals(0, settings.getLastUpdateCheckEpoch(), "nothing stamped either");
    }
}
