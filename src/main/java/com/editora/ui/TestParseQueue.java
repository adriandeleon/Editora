package com.editora.ui;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.editora.test.ParsedSuite;

/**
 * One test run's output parse, off the FX thread (PERF-01, Pauta 6.2.0): a stream that hands its lines over on the
 * FX thread queues each line's parse here, on one thread in arrival order, and the results come back on the FX
 * thread in the same order; {@link #then} runs after every parse queued before it (a run's exit after its last
 * line). For the runs whose output does not come through the build tool's pump (Python's unittest).
 */
final class TestParseQueue {

    private final ExecutorService worker;
    private final Consumer<List<ParsedSuite>> results;

    TestParseQueue(String name, Consumer<List<ParsedSuite>> results) {
        this.worker = Executors.newSingleThreadExecutor(
                r -> Thread.ofVirtual().name(name).unstarted(r));
        this.results = results;
    }

    /** Parses on the queue's thread, then hands the suites to the FX thread. */
    void parse(Supplier<List<ParsedSuite>> parse) {
        try {
            worker.execute(() -> {
                com.editora.perf.BlockingSites.enter("TestParseQueue.parse");
                List<ParsedSuite> found = parse.get();
                if (!found.isEmpty()) {
                    javafx.application.Platform.runLater(() -> results.accept(found));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // the run ended: a late line has nowhere to go
        }
    }

    /** Runs {@code fx} on the FX thread after every parse queued so far has handed over its results, then closes. */
    void then(Runnable fx) {
        try {
            worker.execute(() -> javafx.application.Platform.runLater(fx));
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            javafx.application.Platform.runLater(fx);
        }
        worker.shutdown();
    }
}
