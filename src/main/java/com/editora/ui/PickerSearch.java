package com.editora.ui;

import com.editora.perf.OffFx;

/**
 * Where a picker's filter runs as the user types: off the JavaFX thread (PERF-01, Pauta 6.0.1: no searching of any
 * size on it). Each picker owns a {@link LatestOnly} made by {@link #latest}: a keystroke supersedes the filter in
 * flight (interrupted, its answer dropped), and the answer of the latest one comes back on the FX thread. The FX
 * thread takes a copy of what is filtered (the rows, the strings they are matched by) and keeps the rows shown until
 * the answer lands; Enter pressed meanwhile waits for it.
 *
 * <p>The work runs where {@link OffFx} runs a command's work: a virtual thread per filter (so one picker's long filter
 * never queues another's), or in place for a test that runs {@code OffFx} in place ({@code OffFxInPlace}), when the
 * answer lands before the keystroke returns.
 */
final class PickerSearch {

    private PickerSearch() {}

    /** The name of the threads filters run on. */
    static final String THREAD = "picker-search";

    /** A latest-only filter for one picker: its work runs off the FX thread, its answer on it. */
    static LatestOnly latest() {
        return new LatestOnly(task -> OffFx.start(THREAD, task), OffFx::onFx);
    }
}
