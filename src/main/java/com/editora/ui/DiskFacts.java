package com.editora.ui;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * Small facts about the disk that a handler asks again and again on the JavaFX thread (is this a folder, which pom
 * is above it, does this file exist), looked at off the thread (PERF-01, Pauta 6.2.0).
 *
 * <p>{@link #get} answers from memory: the last answer for the key, or {@code null} while it has none, in which case
 * a look starts on a virtual thread named for this memory ({@link com.editora.perf.OffFx}, which names it to
 * {@link com.editora.perf.BlockingSites}). An answer older than {@link #FRESH_NANOS} is looked at again behind it, so
 * a change on disk is seen at the next ask. When a look lands with an answer that differs from what was kept, the
 * {@linkplain #onLearned listener} runs on the FX thread, so the asker can ask again (revalidate a form, rebuild a
 * row). The caller decides what "not known yet" means; it must be what the code did when the fact was absent, never a
 * guess that hides or disables something.
 *
 * <p>Confined to the FX thread. Bounded: the memory is dropped when it holds {@link #MAX} keys.
 */
final class DiskFacts<K, V> {

    static final long FRESH_NANOS = 2_000_000_000L;
    private static final int MAX = 512;

    private record Kept<V>(V value, long at) {}

    private final String name;
    private final Function<K, V> look;
    private final Map<K, Kept<V>> known = new HashMap<>();
    private final Set<K> pending = new HashSet<>();
    private Runnable onLearned = () -> {};
    private long freshNanos = FRESH_NANOS;
    private java.util.function.LongSupplier clock = System::nanoTime;
    private @Nullable V assumed;
    private int asking;

    /** @param look runs off the FX thread; answers non-null (an absent fact is {@code false} or empty) */
    DiskFacts(String name, Function<K, V> look) {
        this.name = name;
        this.look = look;
    }

    /**
     * What the asker assumes while a key is not known: a first answer equal to it does not call the listener (a
     * rebuild that would change nothing).
     */
    DiskFacts<K, V> assuming(V unknown) {
        this.assumed = unknown;
        return this;
    }

    /**
     * How old a kept answer may be before an ask looks again behind it ({@link #FRESH_NANOS} by default; 0 looks
     * again at every ask, still answering what is kept meanwhile: a form checked as the user types).
     */
    DiskFacts<K, V> freshFor(long nanos) {
        this.freshNanos = nanos;
        return this;
    }

    /**
     * TEST SEAM — the clock an answer's age is read on ({@link System#nanoTime} by default), so a test can age an
     * answer past {@link #FRESH_NANOS} without waiting that long.
     */
    DiskFacts<K, V> clockForTest(java.util.function.LongSupplier nanos) {
        this.clock = nanos;
        return this;
    }

    /** Runs on the FX thread when a look lands with an answer that differs from the kept one. */
    DiskFacts<K, V> onLearned(Runnable listener) {
        this.onLearned = listener;
        return this;
    }

    /** The kept answer for {@code key}, or null while there is none; starts a look when it has none or is old. */
    @Nullable
    V get(K key) {
        Kept<V> kept = known.get(key);
        if (kept == null || clock.getAsLong() - kept.at() >= freshNanos) {
            asking++;
            try {
                ask(key);
            } finally {
                asking--;
            }
            kept = known.get(key); // a look run in place (a test's runner) has already answered
        }
        return kept == null ? null : kept.value();
    }

    /** The kept answer for {@code key}, or null; never looks. */
    @Nullable
    V known(K key) {
        Kept<V> kept = known.get(key);
        return kept == null ? null : kept.value();
    }

    /** Whether an answer is kept for {@code key}. */
    boolean knows(K key) {
        return known.containsKey(key);
    }

    /** Forgets every answer (the next ask looks again). */
    void forget() {
        known.clear();
    }

    private void ask(K key) {
        if (!pending.add(key)) {
            return;
        }
        com.editora.perf.OffFx.then(name, () -> Objects.requireNonNull(look.apply(key), name), answer -> {
            pending.remove(key);
            V value = answer.value();
            if (!answer.ok() || value == null) {
                return; // nothing learned; the next ask tries again
            }
            if (known.size() >= MAX) {
                known.clear();
            }
            Kept<V> before = known.put(key, new Kept<>(value, clock.getAsLong()));
            V was = before != null ? before.value() : assumed;
            if (asking == 0 && !value.equals(was)) {
                onLearned.run();
            }
        });
    }
}
