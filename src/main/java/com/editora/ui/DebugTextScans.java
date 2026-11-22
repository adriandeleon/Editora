package com.editora.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

import com.editora.editor.EditorBuffer;
import com.editora.perf.OffFx;
import org.jspecify.annotations.Nullable;

/**
 * The debugger's scans of a document a command decides by (PERF-01, Pauta 6.2.0): whether a breakpoint's line has
 * code, the Rust, JavaScript or pytest test at the caret. Each is worked out off the JavaFX thread on a snapshot of
 * the text and kept for the document's {@linkplain EditorBuffer#docVersion() version}: a command asks, and while the
 * answer is not known it does nothing and is asked again once the answer lands (worked out again when the text moved
 * on meanwhile). FX-confined apart from the work.
 */
final class DebugTextScans {

    /** Where a scan runs: heard off the FX thread (PERF-01). */
    static final String SITE = "DebugTextScans.scan";

    /** An answer, which may itself be null (no test at the caret). */
    record Known<T>(@Nullable T value) {}

    private record Answer(long version, @Nullable Object value) {}

    /** Per buffer: each scan's last answer, and the commands waiting for one in flight; keyed by scan and line. */
    private static final class Memo {
        final Map<String, Answer> answers = new HashMap<>();
        final Map<String, List<Runnable>> waiting = new HashMap<>();
    }

    private final Map<EditorBuffer, Memo> memos = new WeakHashMap<>();

    /**
     * The answer of {@code scan} over {@code b}'s text now, keyed by {@code what} (the scan and its line): known, or
     * null while it is worked out, and then {@code retry} runs on the FX thread once it lands.
     */
    @SuppressWarnings("unchecked")
    <T> @Nullable Known<T> scan(EditorBuffer b, String what, Function<String, @Nullable T> scan, Runnable retry) {
        Memo memo = memos.computeIfAbsent(b, k -> new Memo());
        Answer known = memo.answers.get(what);
        long version = b.docVersion();
        if (known != null && known.version() == version) {
            return new Known<>((T) known.value());
        }
        List<Runnable> waiting = memo.waiting.get(what);
        if (waiting != null) {
            waiting.add(retry); // the same scan is in flight
            return null;
        }
        List<Runnable> mine = new ArrayList<>(List.of(retry));
        memo.waiting.put(what, mine);
        String text = b.getContent();
        OffFx.then(SITE, () -> scan.apply(text), a -> {
            memo.waiting.remove(what, mine);
            if (a.ok()) {
                memo.answers.values().removeIf(old -> old.version() != version); // answers about older texts
                memo.answers.put(what, new Answer(version, a.value()));
            }
            // The commands ask again: they find the answer, or (the text moved on) a new scan of the text now.
            if (a.ok() && !b.isDisposed()) {
                mine.forEach(Runnable::run);
            }
        });
        return null;
    }
}
