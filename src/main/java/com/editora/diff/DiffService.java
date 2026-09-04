package com.editora.diff;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.diff.DiffModels.DiffModel;

/**
 * FX-facing façade that runs {@link DiffEngine} off the JavaFX thread (the {@code GitService}/
 * {@code MermaidService} idiom: a single daemon executor + {@link Platform#runLater}). The diff itself
 * is pure and toolkit-free; this only keeps the (potentially non-trivial) line diff off the UI thread
 * and guards against pathologically large inputs.
 */
public final class DiffService {

    /** Above this many lines on either side, skip diffing (post {@code null}) — the caller reports it. */
    private static final int MAX_FULL_LINES = 60_000;

    private static final int MAX_RENDERED_LINES = 120_000;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "diff-service");
        t.setDaemon(true);
        return t;
    });

    /**
     * Computes the diff of {@code leftText} vs {@code rightText} off-thread and posts the
     * {@link DiffModel} on the FX thread — or {@code null} when either side exceeds {@link #MAX_LINES}.
     */
    public void compute(String leftText, String rightText, Consumer<DiffModel> onResult) {
        compute(leftText, rightText, DiffEngine.DiffOptions.DEFAULT, onResult);
    }

    /** As {@link #compute(String, String, Consumer)}, with explicit {@link DiffEngine.DiffOptions}. */
    public void compute(String leftText, String rightText, DiffEngine.DiffOptions opts, Consumer<DiffModel> onResult) {
        exec.submit(() -> {
            List<String> left = DiffEngine.lines(leftText);
            List<String> right = DiffEngine.lines(rightText);
            int largest = Math.max(left.size(), right.size());
            DiffModel model = largest <= MAX_FULL_LINES
                    ? DiffEngine.compute(leftText, rightText, opts)
                    : largest <= MAX_RENDERED_LINES
                            ? DiffEngine.computeCoarse(leftText, rightText)
                            : DiffEngine.metadataOnly(leftText, rightText);
            Platform.runLater(() -> onResult.accept(model));
        });
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
