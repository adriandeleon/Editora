package com.editora.diff;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
     * {@link DiffModel} on the FX thread — or {@code null} when the comparison failed.
     */
    public void compute(String leftText, String rightText, Consumer<DiffModel> onResult) {
        compute(leftText, rightText, DiffEngine.DiffOptions.DEFAULT, onResult);
    }

    /** As {@link #compute(String, String, Consumer)}, with explicit {@link DiffEngine.DiffOptions}. */
    public void compute(String leftText, String rightText, DiffEngine.DiffOptions opts, Consumer<DiffModel> onResult) {
        try {
            exec.submit(() -> {
                DiffModel computed;
                try {
                    computed = model(leftText, rightText, opts);
                } catch (Throwable failed) { // incl. OutOfMemoryError: the caller must still hear back
                    // A task that dies inside submit() is swallowed by its Future; without this the callback
                    // never fired and the tab stayed "opening" for good. null is the "could not diff" answer.
                    computed = null;
                }
                DiffModel model = computed;
                Platform.runLater(() -> onResult.accept(model));
            });
        } catch (RejectedExecutionException shuttingDown) {
            // Git/blob reads can complete on the FX queue after the owning window has closed. At that point
            // there is no consumer left to update, so a late diff request is expected lifecycle fallout.
            if (!exec.isShutdown()) {
                throw shuttingDown;
            }
        }
    }

    /**
     * Writes the unified diff of two texts off-thread ({@link PatchWriter}) and posts it on the FX thread —
     * {@code null} when it could not be produced. Export ran this on the FX thread, where one large rewritten
     * file froze the window.
     */
    public void patch(
            String leftLabel, String rightLabel, String leftText, String rightText, Consumer<String> onResult) {
        try {
            exec.submit(() -> {
                String computed;
                try {
                    computed = PatchWriter.unifiedDiff(leftLabel, rightLabel, leftText, rightText);
                } catch (Throwable failed) { // as in compute: the caller must still hear back
                    computed = null;
                }
                String patch = computed;
                Platform.runLater(() -> onResult.accept(patch));
            });
        } catch (RejectedExecutionException shuttingDown) {
            if (!exec.isShutdown()) {
                throw shuttingDown;
            }
        }
    }

    static DiffModel model(String leftText, String rightText, DiffEngine.DiffOptions opts) {
        List<String> left = DiffEngine.lines(leftText);
        List<String> right = DiffEngine.lines(rightText);
        int largest = Math.max(left.size(), right.size());
        return largest <= MAX_FULL_LINES
                ? DiffEngine.compute(leftText, rightText, opts)
                : largest <= MAX_RENDERED_LINES
                        ? DiffEngine.computeCoarse(leftText, rightText)
                        : DiffEngine.metadataOnly(leftText, rightText);
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
