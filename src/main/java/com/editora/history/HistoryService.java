package com.editora.history;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;

import com.editora.config.HistoryRevision;
import com.editora.history.HistoryRetention.RetentionPolicy;

/**
 * The UI-facing facade for the Local File History. Mirrors the {@code GitService}/{@code MermaidService}
 * idiom: a single daemon executor does the off-thread work (sha + gzip + blob I/O + pure retention
 * computation) and results are posted back on the JavaFX thread via {@link Platform#runLater}, so the
 * UI thread is never blocked. The bucketed index ({@code HistoryStore}) is owned by the config layer and
 * mutated only on the FX thread by the caller — this service is stateless except for the blob store.
 *
 * <p>The caller captures the buffer's content string on the FX thread before calling {@link #snapshot}
 * (the auto-save precedent), so the executor never touches live editor state.
 */
public final class HistoryService {

    /** A null revision can mean an unchanged successful snapshot; {@code successful} distinguishes I/O failure. */
    public record SnapshotOutcome(HistoryRevision revision, boolean successful) {}

    private static final Logger LOG = Logger.getLogger(HistoryService.class.getName());

    private final HistoryBlobStore blobs;
    private final BooleanSupplier gcAllowed;
    private final Object publicationLock = new Object();
    private int publicationsInFlight;
    private final Set<String> publicationHashes = new LinkedHashSet<>();
    private Set<String> deferredLiveHashes;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "history-service");
        t.setDaemon(true);
        return t;
    });

    /**
     * Minimum spacing of throttled blob collections ({@link #gcIfDue}). A collection lists every shard
     * directory of the store; doing that after each save made every save cost a walk of the whole history.
     */
    static final long GC_MIN_INTERVAL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(10);

    private final LongSupplier nanoClock;
    /** Guarded by {@link #publicationLock}. */
    private boolean gcHasRun;

    private long lastGcNanos;
    private boolean gcRequested;
    /** The policy the index was last swept with; {@code null} until the startup sweep is claimed. */
    private RetentionPolicy sweptPolicy;
    /** The limits the user has agreed to; see {@link #effectivePolicy}. Guarded by {@link #publicationLock}. */
    private RetentionPolicy acknowledgedPolicy;

    public HistoryService(HistoryBlobStore blobs) {
        this(blobs, () -> true, System::nanoTime);
    }

    /**
     * As {@link #HistoryService(HistoryBlobStore)}, with a gate on garbage collection: {@code gcAllowed} is
     * asked on the worker thread immediately before any blob is deleted, and a {@code false} skips that
     * collection (keeping blobs is always the safe outcome). The config layer uses it to stop one Editora
     * process deleting revision bodies that belong to another process sharing the same config directory.
     */
    public HistoryService(HistoryBlobStore blobs, BooleanSupplier gcAllowed) {
        this(blobs, gcAllowed, System::nanoTime);
    }

    /** As above with an injectable monotonic clock (tests drive the GC throttle without waiting). */
    public HistoryService(HistoryBlobStore blobs, LongSupplier nanoClock) {
        this(blobs, () -> true, nanoClock);
    }

    /** The GC gate and the throttle clock together. */
    public HistoryService(HistoryBlobStore blobs, BooleanSupplier gcAllowed, LongSupplier nanoClock) {
        this.blobs = blobs;
        this.gcAllowed = gcAllowed == null ? () -> true : gcAllowed;
        this.nanoClock = nanoClock;
        exec.submit(blobs::hardenExisting);
    }

    /**
     * Records a snapshot off the FX thread — sha, blob write — and delivers the new {@link HistoryRevision}
     * on the FX thread via {@code onRecorded}, for the caller to fold into the index there. Optionally
     * carries a user {@code label} and {@code force}s a revision even when the content is unchanged. {@code force = true} is used for "Put Label" so a label always marks a point
     * in time (the blob is content-addressed, so a same-sha row costs only the index entry). With
     * {@code force = false} an unchanged content is skipped and {@code onUpdated} is <b>not</b> called.
     */
    public void snapshot(
            Path file,
            String content,
            String reason,
            String label,
            boolean force,
            List<HistoryRevision> existing,
            RetentionPolicy policy,
            long now,
            Consumer<HistoryRevision> onRecorded) {
        snapshotWithOutcome(
                file,
                content,
                reason,
                label,
                force,
                existing,
                policy,
                now,
                outcome -> onRecorded.accept(outcome.revision()));
    }

    public void snapshotWithOutcome(
            Path file,
            String content,
            String reason,
            String label,
            boolean force,
            List<HistoryRevision> existing,
            RetentionPolicy policy,
            long now,
            Consumer<SnapshotOutcome> onRecorded) {
        List<HistoryRevision> snapshot = existing == null ? List.of() : new ArrayList<>(existing);
        synchronized (publicationLock) {
            publicationsInFlight++;
        }
        try {
            exec.submit(() -> {
                try {
                    String sha = HistoryBlobStore.sha256(content);
                    // A GC request can become deferred while this publication is in flight. Its live set was
                    // captured before this revision reached the index, so protect the newly written blob until
                    // the next index publication supplies a complete live set. Otherwise the deferred GC can
                    // delete the blob between its write and the durable index callback.
                    synchronized (publicationLock) {
                        publicationHashes.add(sha);
                        if (deferredLiveHashes != null && !deferredLiveHashes.contains(sha)) {
                            var protectedHashes = new LinkedHashSet<>(deferredLiveHashes);
                            protectedHashes.add(sha);
                            deferredLiveHashes = Set.copyOf(protectedHashes);
                        }
                    }
                    if (!force && HistoryRetention.isDuplicate(snapshot, sha)) {
                        // Unchanged since the last revision — skip the blob write. Still report completion: the
                        // caller counts in-flight records to know when it is safe to GC.
                        deliver(new SnapshotOutcome(null, true), onRecorded);
                        return;
                    }
                    blobs.put(content, sha);
                    long size = content.getBytes(StandardCharsets.UTF_8).length;
                    HistoryRevision rev =
                            new HistoryRevision(file.toString(), now, size, sha, reason, label == null ? "" : label);
                    // Deliver just the revision: the caller folds it into the index on the FX thread, against the
                    // list as it is THEN. Building the new list here from the list as it was at submit time meant a
                    // second record for the same file (a label during a slow save; two dirty buffers on one autosave)
                    // overwrote the first one's revision with a list that never contained it.
                    deliver(new SnapshotOutcome(rev, true), onRecorded);
                } catch (Throwable t) {
                    // A failure here (e.g. the blob disk write) MUST still complete the callback: the caller
                    // decrements an in-flight counter in onRecorded and only GCs when it hits zero, so a stranded
                    // callback silently stops local-history GC for the rest of the session (blobs grow unbounded).
                    // The submit() Future is unobserved, so without this the throw is swallowed and never logged.
                    LOG.log(Level.WARNING, "Failed to record a history revision for " + file, t);
                    deliver(new SnapshotOutcome(null, false), onRecorded);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            synchronized (publicationLock) {
                publicationsInFlight--;
                if (publicationsInFlight == 0) {
                    publicationHashes.clear();
                }
            }
            Platform.runLater(() -> onRecorded.accept(new SnapshotOutcome(null, false)));
        }
    }

    private void deliver(SnapshotOutcome outcome, Consumer<SnapshotOutcome> onRecorded) {
        Platform.runLater(() -> {
            try {
                onRecorded.accept(outcome);
            } finally {
                synchronized (publicationLock) {
                    publicationsInFlight--;
                    if (publicationsInFlight == 0 && deferredLiveHashes != null) {
                        Set<String> live = deferredLiveHashes;
                        deferredLiveHashes = null;
                        queueGc(live);
                    }
                    if (publicationsInFlight == 0) {
                        publicationHashes.clear();
                    }
                }
            }
        });
    }

    /** Fetches a revision's body off the FX thread and delivers it (or {@code null}) on the FX thread. */
    public void content(HistoryRevision rev, Consumer<String> onText) {
        try {
            exec.submit(() -> {
                try {
                    String text = rev == null ? null : blobs.get(rev.sha256());
                    Platform.runLater(() -> onText.accept(text));
                } catch (Throwable t) {
                    // Always complete the callback so a diff/preview view doesn't hang "loading" on a read failure.
                    LOG.log(Level.WARNING, "Failed to read a history revision body", t);
                    Platform.runLater(() -> onText.accept(null));
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            Platform.runLater(() -> onText.accept(null));
        }
    }

    /**
     * Garbage-collects blobs no longer referenced by {@code live}.
     *
     * <p>{@code live} is computed on the FX thread, so the caller must only call this when <b>no record is in
     * flight</b>: a blob is written on the executor before its revision reaches the index, so a GC that ran
     * between those two points deleted the blob of a revision that was about to be indexed — leaving a row
     * whose content is gone (and which then renders as an empty file).
     */
    public void gc(Set<String> live) {
        Set<String> snapshot = live == null ? Set.of() : Set.copyOf(live);
        synchronized (publicationLock) {
            if (publicationsInFlight > 0) {
                var protectedHashes = new LinkedHashSet<>(snapshot);
                protectedHashes.addAll(publicationHashes);
                deferredLiveHashes = Set.copyOf(protectedHashes);
                return;
            }
            // Submit before a new publication can increment publicationsInFlight and queue its blob write.
            queueGc(snapshot);
        }
    }

    /**
     * {@link #gc} at most once per {@link #GC_MIN_INTERVAL_NANOS}: the call made after every index
     * publication. A skipped collection deletes nothing, which is always safe — the unreferenced blobs are
     * picked up by the next one that runs, with a live set that is current at that moment (a stale live set
     * is never kept for later). The first call of a session and the first after {@link #requestGc()} always
     * run.
     */
    public void gcIfDue(Set<String> live) {
        long now = nanoClock.getAsLong();
        synchronized (publicationLock) {
            if (gcHasRun && !gcRequested && now - lastGcNanos < GC_MIN_INTERVAL_NANOS) {
                return;
            }
            gcHasRun = true;
            gcRequested = false;
            lastGcNanos = now;
        }
        gc(live);
    }

    /**
     * Makes the next {@link #gcIfDue} run regardless of the throttle. Called when the user purges history:
     * the snapshots they asked to delete must leave the disk now, not at the next scheduled collection.
     */
    public void requestGc() {
        synchronized (publicationLock) {
            gcRequested = true;
        }
    }

    /**
     * The retention limits in force. The first call of a session adopts {@code configured} (what the settings
     * file says is what the user last agreed to). After that a configured policy takes effect at once only
     * where it is <em>looser</em>: a limit that became stricter stays at its previous value until
     * {@link #acknowledge} — whichever way the setting was changed, tightening a limit deletes revisions, and
     * that needs the user's say-so first (see {@link #previewTightening}).
     */
    public RetentionPolicy effectivePolicy(RetentionPolicy configured) {
        synchronized (publicationLock) {
            if (configured == null) {
                return acknowledgedPolicy;
            }
            acknowledgedPolicy = acknowledgedPolicy != null && HistoryRetention.tightens(acknowledgedPolicy, configured)
                    ? HistoryRetention.loosest(acknowledgedPolicy, configured)
                    : configured;
            return acknowledgedPolicy;
        }
    }

    /** The user confirmed {@code policy}, stricter limits included: it is now the one in force. */
    public void acknowledge(RetentionPolicy policy) {
        synchronized (publicationLock) {
            acknowledgedPolicy = policy;
        }
    }

    /**
     * Computes, off the FX thread, what replacing {@code current} with {@code candidate} would delete from
     * {@code snapshot} (a private copy of the index) and delivers it on the FX thread — {@code null} when it
     * could not be computed, which callers must treat as "do not tighten".
     */
    public void previewTightening(
            Map<String, Map<String, List<HistoryRevision>>> snapshot,
            RetentionPolicy current,
            RetentionPolicy candidate,
            long now,
            Consumer<HistoryRetention.Impact> onImpact) {
        try {
            exec.submit(() -> {
                HistoryRetention.Impact impact;
                try {
                    impact = HistoryRetention.tighteningImpact(snapshot, current, candidate, now);
                } catch (RuntimeException failure) {
                    LOG.log(Level.WARNING, "Could not preview a Local History limit change", failure);
                    impact = null;
                }
                HistoryRetention.Impact result = impact;
                Platform.runLater(() -> onImpact.accept(result));
            });
        } catch (RejectedExecutionException shuttingDown) {
            Platform.runLater(() -> onImpact.accept(null));
        }
    }

    /**
     * Whether the caller should sweep the index with {@code policy}: true the first time it is asked (the
     * once-per-start sweep, whichever window gets there first) and again whenever the limits change.
     */
    public boolean claimSweep(RetentionPolicy policy) {
        synchronized (publicationLock) {
            if (policy == null || policy.equals(sweptPolicy)) {
                return false;
            }
            sweptPolicy = policy;
            return true;
        }
    }

    /**
     * Computes a retention {@link HistoryRetention#sweep sweep} of {@code snapshot} off the FX thread and
     * delivers, on the FX thread, the revisions it would drop. {@code snapshot} must be a private copy: the
     * live index belongs to the FX thread and keeps changing while this runs, which is why the result is
     * "what to remove" rather than a replacement index.
     */
    public void sweep(
            Map<String, Map<String, List<HistoryRevision>>> snapshot,
            RetentionPolicy policy,
            long now,
            Consumer<Map<String, Map<String, List<HistoryRevision>>>> onEvicted) {
        try {
            exec.submit(() -> {
                Map<String, Map<String, List<HistoryRevision>>> evicted;
                try {
                    evicted = HistoryRetention.evicted(snapshot, HistoryRetention.sweep(snapshot, policy, now));
                } catch (RuntimeException failure) {
                    LOG.log(Level.WARNING, "Local history retention sweep failed", failure);
                    return;
                }
                Platform.runLater(() -> onEvicted.accept(evicted));
            });
        } catch (RejectedExecutionException shuttingDown) {
            // Nothing to sweep for a closing application; the next start sweeps again.
        }
    }

    private void queueGc(Set<String> snapshot) {
        try {
            exec.submit(() -> {
                if (gcAllowed.getAsBoolean()) {
                    blobs.deleteUnreferenced(snapshot);
                } else {
                    requestGc(); // refused, not done: the throttle must not count it, so the next save collects
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            // Final shutdown owns no future GC work; retaining blobs is the safe failure mode.
        }
    }

    /** How long {@link #shutdown()} waits for the write it interrupted to let go of the history folder. */
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    /**
     * Stops the worker and waits for it. Queued work is dropped and a blob write in flight is interrupted
     * (it cleans up its temp file), but it has not <em>finished</em> when {@code shutdownNow} returns — and
     * the caller's next step assumes it has: the application releases its instance lock ("no more writes
     * from this process"), a test deletes the folder. Returning early left a window in which the worker was
     * still creating and moving files there.
     */
    public void shutdown() {
        exec.shutdownNow();
        try {
            exec.awaitTermination(SHUTDOWN_WAIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
