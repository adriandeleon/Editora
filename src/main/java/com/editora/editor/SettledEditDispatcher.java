package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import javafx.animation.PauseTransition;

/**
 * Runs differently-timed "editing settled" work from one JavaFX timer.
 *
 * <p>Each RichTextFX {@code successionEnds} pipeline owns independent subscription/timer state. An editor
 * has several consumers with deliberately different delays, but they all reset from the same document
 * change. This dispatcher preserves those milestones while resetting one {@link PauseTransition}; disabled
 * milestones are skipped without allocating a per-edit task list.
 */
final class SettledEditDispatcher {

    private record Task(long delayMillis, BooleanSupplier enabled, Runnable action) {}

    private final PauseTransition timer = new PauseTransition();
    private final List<Task> tasks = new ArrayList<>();
    private long generation;
    private long editStartedNanos;
    private long cursorMillis;
    private long armedMillis = -1;
    private boolean disposed;

    SettledEditDispatcher() {
        timer.setOnFinished(ignored -> fireMilestone());
    }

    void at(Duration delay, BooleanSupplier enabled, Runnable action) {
        if (disposed) {
            throw new IllegalStateException("dispatcher is disposed");
        }
        long millis = Math.max(1, Objects.requireNonNull(delay, "delay").toMillis());
        tasks.add(
                new Task(millis, Objects.requireNonNull(enabled, "enabled"), Objects.requireNonNull(action, "action")));
        tasks.sort(Comparator.comparingLong(Task::delayMillis));
    }

    /** Restarts every enabled milestone from this edit, matching {@code successionEnds(delay)} semantics. */
    void changed() {
        if (disposed) {
            return;
        }
        generation++;
        timer.stop();
        editStartedNanos = System.nanoTime();
        cursorMillis = 0;
        armedMillis = -1;
        armNext(generation);
    }

    void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        generation++;
        timer.stop();
        tasks.clear();
        armedMillis = -1;
    }

    int taskCount() {
        return tasks.size();
    }

    int enabledMilestoneCount() {
        long previous = Long.MIN_VALUE;
        int count = 0;
        for (Task task : tasks) {
            if (task.enabled().getAsBoolean() && task.delayMillis() != previous) {
                count++;
                previous = task.delayMillis();
            }
        }
        return count;
    }

    private void fireMilestone() {
        if (disposed || armedMillis < 0) {
            return;
        }
        long expectedGeneration = generation;
        long milestone = armedMillis;
        cursorMillis = milestone;
        armedMillis = -1;
        for (Task task : tasks) {
            if (task.delayMillis() == milestone && task.enabled().getAsBoolean()) {
                task.action().run();
                if (disposed || generation != expectedGeneration) {
                    return; // an action edited the document and changed() already armed the new sequence
                }
            }
        }
        armNext(expectedGeneration);
    }

    private void armNext(long expectedGeneration) {
        if (disposed || generation != expectedGeneration) {
            return;
        }
        long next = Long.MAX_VALUE;
        for (Task task : tasks) {
            if (task.delayMillis() > cursorMillis && task.enabled().getAsBoolean()) {
                next = Math.min(next, task.delayMillis());
            }
        }
        if (next == Long.MAX_VALUE) {
            return;
        }
        armedMillis = next;
        // Base every milestone on the original edit time. If the FX thread was busy when an earlier timer
        // became due, later milestones catch up instead of accumulating that delay on each hop.
        long elapsedMillis = Math.max(0, (System.nanoTime() - editStartedNanos) / 1_000_000);
        timer.setDuration(javafx.util.Duration.millis(Math.max(1, next - elapsedMillis)));
        timer.playFromStart();
    }
}
