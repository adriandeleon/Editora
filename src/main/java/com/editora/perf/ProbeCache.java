package com.editora.perf;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * What a look at the disk answered, kept so the FX thread can ask again without looking (PERF-01, Pauta 6.0.1).
 *
 * <p>For the small facts a window's set-up and every settings apply ask: is Editora's own copy of a server in
 * place, where is the java-debug plugin, is this configured tool a file. The launch {@linkplain #prepare
 * prepares} the answers its first window will ask for on its config-prefetch thread; {@link #get} then answers
 * from memory. A key nobody prepared is looked up where it is asked, once, and kept ({@link BlockingSites} names
 * that look, so a test sees it). {@link #refresh} looks again at every kept key on a thread of its own, for an
 * answer the disk may have changed since; {@link #forget} drops them all (after an install, say).
 *
 * <p>Values are never null: a probe that may find nothing answers an empty {@code Optional} or {@code false}.
 */
public final class ProbeCache<K, V> {

    private final String name;
    private final Function<K, V> probe;
    private final Map<K, V> known = new ConcurrentHashMap<>();

    public ProbeCache(String name, Function<K, V> probe) {
        this.name = name;
        this.probe = probe;
    }

    /** The kept answer for {@code key}, else the probe's, looked up now on this thread and kept. */
    public V get(K key) {
        V value = known.get(key);
        if (value != null) {
            return value;
        }
        return prepare(key);
    }

    /** Looks {@code key} up now, on this thread, and keeps the answer: the launch's warm-up. */
    public V prepare(K key) {
        BlockingSites.enter(name);
        V value = Objects.requireNonNull(probe.apply(key), name);
        known.put(key, value);
        return value;
    }

    /** Whether {@code key} has an answer kept. */
    public boolean knows(K key) {
        return known.containsKey(key);
    }

    /**
     * Looks every kept key up again on a virtual thread of its own; when any answer changed, keeps the new ones
     * and runs {@code onChange} on that thread (a caller that touches the UI hops to the FX thread itself).
     */
    public void refresh(Runnable onChange) {
        List<K> keys = List.copyOf(known.keySet());
        if (keys.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name(name + "-refresh").start(() -> {
            boolean changed = false;
            for (K key : keys) {
                V before = known.get(key);
                V now;
                try {
                    now = Objects.requireNonNull(probe.apply(key), name);
                } catch (RuntimeException e) {
                    continue; // keep what was known; the next refresh tries again
                }
                if (!now.equals(before)) {
                    known.put(key, now);
                    changed = true;
                }
            }
            if (changed) {
                onChange.run();
            }
        });
    }

    /** Drops every kept answer, so the next {@link #get} of each looks again. */
    public void forget() {
        known.clear();
    }
}
