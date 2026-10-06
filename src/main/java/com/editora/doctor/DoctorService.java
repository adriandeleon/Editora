package com.editora.doctor;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;

/**
 * Runs a Doctor check list off the FX thread (the {@code GitService} idiom: daemon pool + generation
 * guard + {@link Platform#runLater} delivery). Each {@link CheckSpec}'s probe executes on a small fixed
 * pool so the dozen subprocess probes overlap; every finished check is posted <em>individually</em> on the
 * FX thread, so the screen's rows fill in live. A Refresh mid-run bumps the generation: the superseded
 * run's still-queued probes are skipped without spawning anything, and results of its in-flight ones are
 * dropped.
 */
public final class DoctorService {

    /** Probes run concurrently on this many daemon threads (a run is ~a dozen short subprocesses). */
    private static final int POOL_SIZE = 4;

    /**
     * One check to run: the {@code CHECKING} (or terminal {@code DISABLED}) row shown immediately, and the
     * blocking probe producing the resolved row — {@code null} for a terminal row that needs no probe.
     */
    public record CheckSpec(DoctorCheck placeholder, Supplier<DoctorCheck> probe) {}

    private final ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE, r -> {
        Thread t = new Thread(r, "doctor");
        t.setDaemon(true);
        return t;
    });

    private final AtomicLong generation = new AtomicLong();

    /** Delivers results on the FX thread ({@link Platform#runLater}; a direct executor in tests). */
    private final Executor fxPost;

    public DoctorService() {
        this(Platform::runLater);
    }

    DoctorService(Executor fxPost) {
        this.fxPost = fxPost;
    }

    /**
     * Runs every probing spec, posting each resolved check on the FX thread via {@code onResult} and then
     * {@code onDone} once when the last one lands. Results from a superseded run are dropped.
     */
    public void run(List<CheckSpec> specs, Consumer<DoctorCheck> onResult, Runnable onDone) {
        long gen = generation.incrementAndGet();
        List<CheckSpec> live = specs.stream().filter(s -> s.probe() != null).toList();
        if (live.isEmpty()) {
            fxPost.execute(onDone);
            return;
        }
        AtomicInteger remaining = new AtomicInteger(live.size());
        for (CheckSpec spec : live) {
            pool.submit(() -> {
                if (gen != generation.get()) {
                    return; // superseded while queued: don't spawn a probe nobody will see
                }
                DoctorCheck resolved;
                try {
                    resolved = spec.probe().get();
                } catch (RuntimeException e) {
                    // A probe must never sink the run; surface the failure as a missing row with the reason.
                    resolved = spec.placeholder().missing("doctor.tip.probeFailed", String.valueOf(e.getMessage()));
                }
                if (gen != generation.get()) {
                    return;
                }
                DoctorCheck posted = resolved;
                fxPost.execute(() -> {
                    if (gen != generation.get()) {
                        return;
                    }
                    onResult.accept(posted);
                    if (remaining.decrementAndGet() == 0) {
                        onDone.run();
                    }
                });
            });
        }
    }

    /** Stops the probe pool (window close). */
    public void shutdown() {
        pool.shutdownNow();
    }
}
