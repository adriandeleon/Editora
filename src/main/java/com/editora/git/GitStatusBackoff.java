package com.editora.git;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How long to leave a repository alone after {@code git status} timed out in it. A status that takes longer
 * than the background-read limit (a huge work tree, a cold network mount, another git holding it busy) will
 * take that long again a moment later; every save, tab switch and focus gain used to start another one, each
 * holding the git lane for the full timeout. Pure apart from the clock value passed in.
 */
final class GitStatusBackoff {

    static final Duration FIRST = Duration.ofSeconds(30);
    static final Duration LONGEST = Duration.ofMinutes(5);

    private record Wait(int failures, long untilNanos) {}

    private final Map<Path, Wait> waits = new ConcurrentHashMap<>();

    /** Whether {@code root} timed out recently enough that asking again now would only time out again. */
    boolean waiting(Path root, long nowNanos) {
        Wait wait = waits.get(root);
        return wait != null && nowNanos - wait.untilNanos() < 0;
    }

    /** Records a timeout: the wait doubles with each one in a row, up to {@link #LONGEST}. */
    void timedOut(Path root, long nowNanos) {
        waits.compute(root, (key, wait) -> {
            int failures = wait == null ? 1 : wait.failures() + 1;
            return new Wait(failures, nowNanos + delay(failures).toNanos());
        });
    }

    /** A status came back: the repository is answering again. */
    void succeeded(Path root) {
        waits.remove(root);
    }

    void clear() {
        waits.clear();
    }

    static Duration delay(int failuresInARow) {
        long seconds = FIRST.toSeconds() << Math.min(Math.max(failuresInARow, 1) - 1, 10);
        return seconds >= LONGEST.toSeconds() ? LONGEST : Duration.ofSeconds(seconds);
    }
}
