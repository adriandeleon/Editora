package com.editora.perf;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * The last answer a look at the disk gave, by key: a look made off the FX thread refreshes it, and the FX thread
 * answers from it (PERF-01, Pauta 6.0.1). A launch's plan looks off the thread first for exactly what its FX part
 * asks next, so the FX part finds it; the FX thread looks itself only for a key no plan has looked at, and
 * {@link BlockingSites} names that look. Bounded: the keys of a session.
 */
public final class Looked<K, V> {

    private static final int MAX = 256;

    private final String name;
    private final Function<K, @Nullable V> look;
    private final Map<K, Kept<V>> known = new ConcurrentHashMap<>();

    /** How old an answer the FX thread takes without asking for a fresh one behind it. */
    private static final long FRESH_NANOS = 5_000_000_000L;

    private record Kept<V>(Optional<V> value, long at) {}

    public Looked(String name, Function<K, @Nullable V> look) {
        this.name = name;
        this.look = look;
    }

    /** The answer for {@code key}: from memory on the FX thread when there is one, else looked up now and kept. */
    public @Nullable V get(K key) {
        if (onFx()) {
            Kept<V> kept = known.get(key);
            if (kept != null) {
                if (System.nanoTime() - kept.at() > FRESH_NANOS) {
                    // Looked at again behind the answer, so the next ask has the disk as it is now.
                    Thread.ofVirtual().name(name + "-refresh").start(() -> lookNow(key));
                }
                return kept.value().orElse(null);
            }
        }
        return lookNow(key);
    }

    /** Looks at {@code key} now, on this thread, whatever is kept, and keeps the answer: a look off the FX thread. */
    public @Nullable V lookAgain(K key) {
        return lookNow(key);
    }

    /** Whether there is an answer kept for {@code key} (which {@link #get} on the FX thread then answers from). */
    public boolean knows(K key) {
        return known.containsKey(key);
    }

    private @Nullable V lookNow(K key) {
        BlockingSites.enter(name);
        V value = look.apply(key);
        if (known.size() >= MAX) {
            known.clear();
        }
        known.put(key, new Kept<>(Optional.ofNullable(value), System.nanoTime()));
        return value;
    }

    private static boolean onFx() {
        try {
            return javafx.application.Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
