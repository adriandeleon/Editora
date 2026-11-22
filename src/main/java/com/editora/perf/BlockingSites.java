package com.editora.perf;

import java.util.function.BiConsumer;

import org.jspecify.annotations.Nullable;

/**
 * Names the places start-up and a window's set-up touch the disk, so a test can say on which thread each ran
 * (PERF-01, Pauta 6.2.0: before the first window is shown, the FX thread may wait once for the config read and
 * read the bundled resources that window is built from; nothing else).
 *
 * <p>Each such place calls {@link #enter} with its name just before its file work. In a running app nobody
 * listens and the call is one volatile read. A test {@linkplain #listenForTest listens}, builds a window the way
 * the app does, and checks that no name arrived on the FX thread.
 */
public final class BlockingSites {

    private static volatile @Nullable BiConsumer<String, Thread> listener;

    private BlockingSites() {}

    /** {@code site} is about to read, write, list or stat a file on the current thread. */
    public static void enter(String site) {
        BiConsumer<String, Thread> l = listener;
        if (l != null) {
            l.accept(site, Thread.currentThread());
        }
    }

    /** TEST SEAM — receives every {@link #enter} with its thread; {@code null} stops listening. */
    public static void listenForTest(@Nullable BiConsumer<String, Thread> newListener) {
        listener = newListener;
    }
}
