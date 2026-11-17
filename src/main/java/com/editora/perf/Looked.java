package com.editora.perf;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * The last answer a look at the disk gave, by key: a look made off the FX thread refreshes it, and the FX thread
 * answers from it (PERF-01, Pauta 6.0.1). A launch's plan looks off the thread first for exactly what its FX part
 * asks next, so the FX part finds it. Bounded: the keys of a session.
 *
 * <p>No memo looks on the FX thread. A key nothing has looked at yet is answered there with the memo's <em>cold
 * answer</em> (what the code did when the fact was unknown, or null, "unknown", for a memo made without one), looked
 * at on a thread of its own, and kept; {@link #onLearned} then hears it on the FX thread. An asker that cannot go on
 * without the real answer checks {@link #allKnown} first and has the asks run off the FX thread, or uses
 * {@link #whenKnown}, which waits off the FX thread.
 */
public final class Looked<K, V> {

    private static final int MAX = 256;

    private final String name;
    private final Function<K, @Nullable V> look;
    private final Map<K, Kept<V>> known = new ConcurrentHashMap<>();

    /** How old an answer the FX thread takes without asking for a fresh one behind it. */
    private static final long FRESH_NANOS = 5_000_000_000L;

    private record Kept<V>(Optional<V> value, long at) {}

    private final @Nullable V coldAnswer;

    /** The keys a look behind is under way for, so a second cold ask starts none. */
    private final Set<K> looking = ConcurrentHashMap.newKeySet();

    private volatile @Nullable Consumer<K> learned;

    /**
     * A memo whose cold key answers null on the FX thread ("unknown": its FX askers check {@link #allKnown} first)
     * and is looked at behind; off the FX thread it is looked at where it is asked.
     */
    public Looked(String name, Function<K, @Nullable V> look) {
        this.name = name;
        this.look = look;
        this.coldAnswer = null;
    }

    /**
     * A memo that never looks on the FX thread: there a cold key answers {@code coldAnswer} (what the code did when
     * the fact was unknown or absent, so nothing is hidden or grayed meanwhile) and is looked at behind.
     */
    public Looked(String name, Function<K, @Nullable V> look, @Nullable V coldAnswer) {
        this.name = name;
        this.look = look;
        this.coldAnswer = coldAnswer;
    }

    /** Runs {@code listener} on the FX thread with each key a look behind a cold FX ask has just kept. */
    public Looked<K, V> onLearned(@Nullable Consumer<K> listener) {
        this.learned = listener;
        return this;
    }

    /**
     * Whether everything {@code asks} would ask of a memo is kept, worked out without looking at anything: while it
     * runs on this thread, a key no look has filled is not looked at (it answers the cold answer, or null) but
     * noted. The way an FX command asks whether it may go on now, or must first have {@code asks} run off the FX
     * thread, where it looks for real (PERF-01, Pauta 6.0.1). {@code asks} must have no other effect, and must not
     * catch {@code RuntimeException} around a memo's ask.
     */
    public static boolean allKnown(Runnable asks) {
        boolean[] before = PROBING.get();
        boolean[] missing = {false};
        PROBING.set(missing);
        try {
            asks.run();
        } catch (NotKnown gap) {
            missing[0] = true;
        } finally {
            if (before == null) {
                PROBING.remove();
            } else {
                PROBING.set(before);
            }
        }
        return !missing[0];
    }

    /** Thrown inside {@link #allKnown} at the first key no look has filled: the rest need not be asked. */
    static final class NotKnown extends RuntimeException {
        NotKnown() {
            super(null, null, false, false);
        }
    }

    /** Set while {@link #allKnown} runs on this thread: what it found missing. */
    static final ThreadLocal<boolean @Nullable []> PROBING = new ThreadLocal<>();

    /** The answer for {@code key}: from memory on the FX thread when there is one, else looked up now and kept. */
    public @Nullable V get(K key) {
        boolean[] probing = PROBING.get();
        if (probing != null) {
            Kept<V> kept = known.get(key);
            if (kept == null) {
                probing[0] = true;
                throw new NotKnown();
            }
            return kept.value().orElse(null);
        }
        if (onFx()) {
            Kept<V> kept = known.get(key);
            if (kept != null) {
                if (System.nanoTime() - kept.at() > FRESH_NANOS) {
                    // Looked at again behind the answer, so the next ask has the disk as it is now.
                    Thread.ofVirtual().name(name + "-refresh").start(() -> lookNow(key));
                }
                return kept.value().orElse(null);
            }
            lookBehind(key);
            Kept<V> landed = known.get(key); // a test that runs OffFx in place has it already
            return landed != null ? landed.value().orElse(null) : coldAnswer;
        }
        return lookNow(key);
    }

    /**
     * Hands {@code then} the answer for {@code key} on the FX thread: at once when it is kept, else after a look off
     * the FX thread. Off the FX thread it looks (or answers from memory) and calls {@code then} in place.
     */
    public void whenKnown(K key, Consumer<@Nullable V> then) {
        if (!onFx()) {
            then.accept(get(key));
            return;
        }
        Kept<V> kept = known.get(key);
        if (kept != null) {
            then.accept(get(key));
            return;
        }
        OffFx.then(
                name + "-look",
                () -> Optional.ofNullable(lookNow(key)),
                a -> then.accept(a.value() != null ? a.value().orElse(null) : coldAnswer));
    }

    /** Looks at {@code key} on a thread of its own when nothing is kept for it, so a later FX ask finds it. */
    public void warm(K key) {
        if (!known.containsKey(key)) {
            if (onFx()) {
                lookBehind(key);
            } else {
                lookNow(key);
            }
        }
    }

    private void lookBehind(K key) {
        if (!looking.add(key)) {
            return;
        }
        OffFx.then(
                name + "-look",
                () -> {
                    try {
                        lookNow(key);
                    } finally {
                        looking.remove(key);
                    }
                    return Boolean.TRUE;
                },
                done -> {
                    Consumer<K> l = learned;
                    if (l != null && done.ok()) {
                        l.accept(key);
                    }
                });
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
        long now = System.nanoTime();
        if (known.size() >= MAX) {
            evict(now);
        }
        known.put(key, new Kept<>(Optional.ofNullable(value), now));
        return value;
    }

    /** How long a key just looked at is kept whatever the bound: a command waiting for it finds it. */
    private static final long KEEP_NANOS = 60_000_000_000L;

    /**
     * Makes room: drops the keys not looked at for {@link #KEEP_NANOS}, never one just looked at — an FX command that
     * found a key missing has it looked at off the thread and then asks again, and must find it — and, if every key
     * is that fresh and the memo has grown to four times its bound, the older half.
     */
    private void evict(long now) {
        known.entrySet().removeIf(e -> now - e.getValue().at() > KEEP_NANOS);
        if (known.size() < 4 * MAX) {
            return;
        }
        List<Map.Entry<K, Kept<V>>> byAge = new java.util.ArrayList<>(known.entrySet());
        byAge.sort(java.util.Comparator.comparingLong(e -> e.getValue().at()));
        for (int i = 0; i < byAge.size() / 2; i++) {
            known.remove(byAge.get(i).getKey(), byAge.get(i).getValue());
        }
    }

    /** How many keys are kept (tests). */
    int size() {
        return known.size();
    }

    /**
     * Set while work that runs off the FX thread in the app runs where it is: a plan or an {@link OffFx} task a test
     * seam runs in place. Its asks look for real, as they would on the app's own thread.
     */
    private static final ThreadLocal<Boolean> OFF_FX_WORK = new ThreadLocal<>();

    /** Runs {@code work} as work the app does off the FX thread (see {@link #OFF_FX_WORK}), wherever it runs. */
    public static void asOffFxWork(Runnable work) {
        Boolean before = OFF_FX_WORK.get();
        OFF_FX_WORK.set(Boolean.TRUE);
        try {
            work.run();
        } finally {
            if (before == null) {
                OFF_FX_WORK.remove();
            } else {
                OFF_FX_WORK.set(before);
            }
        }
    }

    private static boolean onFx() {
        if (OFF_FX_WORK.get() != null) {
            return false;
        }
        try {
            return javafx.application.Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
