package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The window's register of long-running background work, so the status bar can say what is happening (#770).
 *
 * <p>Editora starts plenty of work the user cannot see: a find-in-files sweep, a build, a language-server
 * install, a Gradle task enumeration that takes a minute and a half. Each reported itself with a transient
 * status message and then went quiet, which is indistinguishable from having hung — and the only visible
 * progress anywhere was two indeterminate bars hard-wired to LSP startup and debugging.
 *
 * <p>Plain Java, no JavaFX: a registry of what is running plus a change callback. That keeps it unit-testable
 * — the interesting behaviour is bookkeeping (a task ending while two others run, the same task ended twice)
 * rather than anything visual. FX-thread-confined by convention, like the rest of the UI layer.
 */
final class BackgroundTasks {

    /** One running operation. {@code cancel} is null when the work cannot be stopped. */
    record Task(long id, String label, Runnable cancel) {
        boolean cancellable() {
            return cancel != null;
        }
    }

    /**
     * A started task. Callers hold one and call {@link #done()} when the work finishes — including when it
     * fails, which is why it is a handle rather than a "remove by label": two searches can be in flight, and
     * a failed one that never removed itself would leave the indicator claiming work that stopped long ago.
     */
    interface Handle {
        void done();
    }

    /** Insertion-ordered, so the indicator shows the oldest running task rather than flickering between them. */
    private final Map<Long, Task> running = new LinkedHashMap<>();

    private final List<Runnable> listeners = new ArrayList<>();
    private long nextId = 1;

    /** Registers a change observer, called after every start and finish. */
    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /** Starts an uncancellable task. */
    Handle start(String label) {
        return start(label, null);
    }

    /**
     * Starts a task shown until its handle is closed.
     *
     * @param label what to show the user, e.g. "Searching…"
     * @param cancel how to stop it, or null when it cannot be stopped
     */
    Handle start(String label, Runnable cancel) {
        long id = nextId++;
        running.put(id, new Task(id, label == null ? "" : label, cancel));
        notifyListeners();
        return () -> {
            // Idempotent: a service that both completes and errors, or retries its own cleanup, must not
            // remove some *other* task by reusing a stale id — ids are never reused, so a second call is a
            // no-op rather than a mix-up.
            if (running.remove(id) != null) {
                notifyListeners();
            }
        };
    }

    /** Everything currently running, oldest first. */
    List<Task> running() {
        return List.copyOf(running.values());
    }

    int count() {
        return running.size();
    }

    boolean isIdle() {
        return running.isEmpty();
    }

    /** The oldest running task, or null when idle — what the status bar names. */
    Task current() {
        for (Task t : running.values()) {
            return t;
        }
        return null;
    }

    /** Cancels every cancellable task. The rest keep running and stay listed. */
    void cancelAll() {
        for (Task t : List.copyOf(running.values())) {
            if (t.cancellable()) {
                t.cancel().run();
            }
        }
    }

    private void notifyListeners() {
        for (Runnable l : List.copyOf(listeners)) {
            l.run();
        }
    }
}
