package com.editora.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.editora.config.PathKeys;

/**
 * App-wide ordering for writes to one document path. A newer ticket makes an older queued write obsolete;
 * an already-running write finishes before the newer ticket can enter its per-path critical section.
 */
public final class DocumentWriteSequencer {

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }

    public record Outcome<T>(boolean executed, T value) {}

    private static final class State {
        final AtomicLong generation = new AtomicLong();
        int users;
    }

    public final class Ticket implements AutoCloseable {
        private final String key;
        private final State state;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Ticket(String key, State state, long generation) {
            this.key = key;
            this.state = state;
            this.generation = generation;
        }

        public boolean isCurrent() {
            return state.generation.get() == generation;
        }

        public <T> Outcome<T> runIfCurrent(IoSupplier<T> action) throws IOException {
            synchronized (state) {
                if (!isCurrent()) {
                    return new Outcome<>(false, null);
                }
                return new Outcome<>(true, action.get());
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (states) {
                state.users--;
                if (state.users == 0) {
                    states.remove(key, state);
                }
            }
        }
    }

    private final Map<String, State> states = new HashMap<>();

    public Ticket begin(Path path) {
        String key = PathKeys.key(path);
        synchronized (states) {
            State state = states.computeIfAbsent(key, ignored -> new State());
            state.users++;
            return new Ticket(key, state, state.generation.incrementAndGet());
        }
    }

    /** Makes every outstanding ticket for {@code path} obsolete without scheduling another write. */
    public void supersede(Path path) {
        if (path == null) {
            return;
        }
        String key = PathKeys.key(path);
        synchronized (states) {
            State state = states.get(key);
            if (state != null) {
                state.generation.incrementAndGet();
            }
        }
    }
}
