package com.editora.recovery;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The process-wide owner of the recovery directory: one worker thread that does every read, write and delete
 * in {@link RecoveryStore}, so no caller ever waits on the disk.
 *
 * <p>Writes are <em>coalesced per buffer</em>: only the newest text handed in for a buffer is written, and a
 * removal that arrives while a write is waiting replaces it. Operations on one buffer therefore reach the
 * disk in the order they were requested, and a burst of snapshots of a large buffer costs one write.
 *
 * <p>Nothing here starts until it is needed: the thread on the first request, this process's session
 * directory on the first write, and the shutdown hook with it. A launch that never has an unsaved buffer and
 * finds nothing to recover leaves no trace.
 *
 * <p>The windows drive it: each registers a <em>ticker</em> while it has unsaved buffers (called about once a
 * second on the UI executor, where it decides what to snapshot) and a <em>finalizer</em> (called from the
 * shutdown hook for a last snapshot when the process is told to stop without the windows being closed).
 */
public final class RecoveryService implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(RecoveryService.class.getName());

    /** How often a window with unsaved buffers is asked whether one of them needs a new snapshot. */
    public static final long TICK_MILLIS = 1000;

    /** How long the shutdown hook waits for the UI thread's last snapshot, then for the disk. */
    private static final long FINAL_SNAPSHOT_MILLIS = 1500;

    private static final long FINAL_DRAIN_MILLIS = 5000;

    private final RecoveryStore store;
    private final Executor ui;

    private final Object lock = new Object();
    /** Newest request per buffer id; an empty value is a removal. Guarded by {@link #lock}. */
    private final Map<String, Optional<RecoveryRecord>> pending = new LinkedHashMap<>();

    private boolean drainQueued;
    private ScheduledExecutorService worker;
    private ScheduledFuture<?> tickTask;
    private Thread shutdownHook;
    private boolean closed;
    private CompletableFuture<RecoveryStore.Orphans> orphans;
    private boolean offerClaimed;

    /** Buffer ids a write was requested for and not since removed: removing any other id is a no-op. */
    private final Set<String> written = ConcurrentHashMap.newKeySet();
    /** Buffer ids whose latest write reached the disk (worker thread writes, anyone reads). */
    private final Set<String> durable = ConcurrentHashMap.newKeySet();

    private final List<Runnable> tickers = new CopyOnWriteArrayList<>();
    private final List<Runnable> finalizers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean tickQueued = new AtomicBoolean();
    private final AtomicBoolean writeFailing = new AtomicBoolean();
    private final List<Consumer<IOException>> writeErrorListeners = new CopyOnWriteArrayList<>();

    /** Orphaned records not yet restored or discarded. UI thread only. */
    private final List<RecoveryStore.Entry> unresolved = new ArrayList<>();
    /** Record files that could not be read. UI thread only. */
    private final List<Path> unreadable = new ArrayList<>();

    /**
     * @param configDir the Editora config directory
     * @param ui runs a task on the UI thread ({@code Platform::runLater})
     */
    public RecoveryService(Path configDir, Executor ui) {
        this.store = new RecoveryStore(configDir);
        this.ui = ui;
    }

    public RecoveryStore store() {
        return store;
    }

    /** {@code listener} is called (on the worker thread) when a record could not be written, once per run of failures. */
    public void addWriteErrorListener(Consumer<IOException> listener) {
        writeErrorListeners.add(listener);
    }

    public void removeWriteErrorListener(Consumer<IOException> listener) {
        writeErrorListeners.remove(listener);
    }

    // --- this session's records ---

    /** Queues {@code record} to be written, superseding anything queued for the same buffer. */
    public void save(RecoveryRecord record) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            written.add(record.bufferId());
            pending.put(record.bufferId(), Optional.of(record));
            queueDrain();
            installShutdownHook();
        }
    }

    /** Queues the removal of the buffer's record. Free when the buffer never had one. */
    public void remove(String bufferId) {
        if (!written.remove(bufferId)) {
            return;
        }
        synchronized (lock) {
            if (closed) {
                return;
            }
            pending.put(bufferId, Optional.empty());
            queueDrain();
        }
    }

    private void queueDrain() {
        if (!drainQueued) {
            drainQueued = true;
            submit(this::drain);
        }
    }

    private void drain() {
        while (true) {
            String id;
            Optional<RecoveryRecord> request;
            synchronized (lock) {
                var first = pending.entrySet().iterator();
                if (!first.hasNext()) {
                    drainQueued = false;
                    return;
                }
                var entry = first.next();
                id = entry.getKey();
                request = entry.getValue();
                first.remove();
            }
            try {
                if (request.isPresent()) {
                    durable.remove(id);
                    store.write(request.get());
                    durable.add(id);
                    writeFailing.set(false);
                } else {
                    durable.remove(id);
                    store.remove(id);
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Recovery record " + id + " could not be updated", e);
                if (request.isPresent() && writeFailing.compareAndSet(false, true)) {
                    writeErrorListeners.forEach(listener -> listener.accept(e));
                }
            } catch (RuntimeException | OutOfMemoryError e) {
                LOG.log(Level.WARNING, "Recovery record " + id + " could not be updated", e);
            }
        }
    }

    // --- records left by a dead session ---

    /** True for the first caller only: the window that gets to show the offer at start-up. */
    public boolean claimOffer() {
        synchronized (lock) {
            boolean first = !offerClaimed;
            offerClaimed = true;
            return first;
        }
    }

    /** Finds, once, what dead sessions left behind. Completes on the worker thread. */
    public CompletableFuture<RecoveryStore.Orphans> orphans() {
        synchronized (lock) {
            if (orphans == null) {
                CompletableFuture<RecoveryStore.Orphans> result = new CompletableFuture<>();
                orphans = result;
                if (!submit(() -> {
                    try {
                        result.complete(store.claimOrphans());
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "Could not look for recovery records", e);
                        result.complete(RecoveryStore.Orphans.NONE);
                    }
                })) {
                    result.complete(RecoveryStore.Orphans.NONE);
                }
            }
            return orphans;
        }
    }

    /** The orphaned records the user has not decided about yet. UI thread only; callers remove what they resolve. */
    public List<RecoveryStore.Entry> unresolved() {
        return unresolved;
    }

    /** Orphaned record files that could not be read and were left in place. UI thread only. */
    public List<Path> unreadable() {
        return unreadable;
    }

    /** Reads an orphaned record with its text; fails with the {@link IOException} when it cannot be read. */
    public CompletableFuture<RecoveryRecord> read(RecoveryStore.Entry entry) {
        CompletableFuture<RecoveryRecord> result = new CompletableFuture<>();
        if (!submit(() -> {
            try {
                result.complete(store.read(entry));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                result.completeExceptionally(e);
            }
        })) {
            result.completeExceptionally(new IOException("recovery is shut down"));
        }
        return result;
    }

    /** Deletes an orphaned record: only ever on the user's explicit choice. */
    public CompletableFuture<Boolean> discard(RecoveryStore.Entry entry) {
        return discardIf(entry, null);
    }

    /**
     * Deletes an orphaned record that was restored into the buffer {@code bufferId} — but only once that
     * buffer's own record is on disk, so the text is never without a durable copy in between.
     */
    public CompletableFuture<Boolean> discardOnceSaved(RecoveryStore.Entry entry, String bufferId) {
        return discardIf(entry, bufferId);
    }

    private CompletableFuture<Boolean> discardIf(RecoveryStore.Entry entry, String durableBufferId) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!submit(() -> {
            try {
                if (durableBufferId != null && !durable.contains(durableBufferId)) {
                    result.complete(false); // its replacement did not reach the disk: keep the original
                    return;
                }
                store.discard(entry);
                result.complete(true);
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "Could not delete the recovery record " + entry.file(), e);
                result.complete(false);
            }
        })) {
            result.complete(false);
        }
        return result;
    }

    // --- timing ---

    /** Starts calling {@code ticker} on the UI executor about every {@link #TICK_MILLIS}. */
    public void addTicker(Runnable ticker) {
        synchronized (lock) {
            if (closed || tickers.contains(ticker)) {
                return;
            }
            tickers.add(ticker);
            if (tickTask == null) {
                tickTask = worker().scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
            }
        }
    }

    public void removeTicker(Runnable ticker) {
        synchronized (lock) {
            tickers.remove(ticker);
            if (tickers.isEmpty() && tickTask != null) {
                tickTask.cancel(false);
                tickTask = null;
            }
        }
    }

    private void tick() {
        if (tickers.isEmpty() || !tickQueued.compareAndSet(false, true)) {
            return; // the UI thread has not run the previous tick yet: do not queue behind it
        }
        try {
            ui.execute(() -> {
                tickQueued.set(false);
                for (Runnable ticker : tickers) {
                    try {
                        ticker.run();
                    } catch (RuntimeException e) {
                        LOG.log(Level.WARNING, "Recovery snapshot pass failed", e);
                    }
                }
            });
        } catch (RuntimeException uiGone) {
            tickQueued.set(false);
        }
    }

    /** Registers a UI-thread task the shutdown hook runs for a last snapshot. */
    public void addFinalizer(Runnable finalizer) {
        finalizers.add(finalizer);
    }

    public void removeFinalizer(Runnable finalizer) {
        finalizers.remove(finalizer);
    }

    // --- lifetime ---

    /** Waits until everything requested so far has been done. Returns false on timeout. */
    public boolean awaitIdle(long timeoutMillis) {
        CompletableFuture<Void> marker = new CompletableFuture<>();
        synchronized (lock) {
            if (worker == null) {
                return true;
            }
        }
        if (!submit(() -> marker.complete(null))) {
            return true;
        }
        try {
            marker.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    private void installShutdownHook() {
        if (shutdownHook == null) {
            shutdownHook = new Thread(this::onShutdown, "recovery-flush");
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (IllegalStateException alreadyShuttingDown) {
                shutdownHook = null;
            }
        }
    }

    /**
     * The process is ending. After a normal quit every window has already dropped its records, so this only
     * lets the queued deletes finish and removes the empty session directory. After a signal (a plain
     * {@code kill}, a desktop logout) the windows are still open: ask the UI thread for one last snapshot —
     * best-effort, it may already be gone — and get it onto the disk.
     */
    void onShutdown() {
        if (!finalizers.isEmpty()) {
            CountDownLatch done = new CountDownLatch(1);
            try {
                ui.execute(() -> {
                    try {
                        finalizers.forEach(Runnable::run);
                    } catch (RuntimeException e) {
                        LOG.log(Level.FINE, "Final recovery snapshot failed", e);
                    } finally {
                        done.countDown();
                    }
                });
                done.await(FINAL_SNAPSHOT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RuntimeException uiGone) {
                // The toolkit has exited: there is no UI thread to ask.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        awaitIdle(FINAL_DRAIN_MILLIS);
        store.close();
    }

    /** Ends recovery in the ordinary way: finishes queued work, releases the locks, stops the thread. */
    @Override
    public void close() {
        stop(store::close);
    }

    /**
     * Stops as a killed process would have: queued work is finished (so a test knows what is on disk), then
     * every lock is dropped and nothing is cleaned up.
     */
    public void abandon() {
        stop(store::abandon);
    }

    private void stop(Runnable release) {
        ScheduledExecutorService stopping;
        Thread hook;
        synchronized (lock) {
            if (closed) {
                return;
            }
            stopping = worker;
            hook = shutdownHook;
            shutdownHook = null;
            if (tickTask != null) {
                tickTask.cancel(false);
                tickTask = null;
            }
        }
        awaitIdle(FINAL_DRAIN_MILLIS);
        synchronized (lock) {
            closed = true;
            tickers.clear();
            finalizers.clear();
            writeErrorListeners.clear();
        }
        if (stopping != null) {
            stopping.shutdown();
            try {
                stopping.awaitTermination(FINAL_DRAIN_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        release.run();
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException shuttingDown) {
                // the hook is running or about to: it only repeats what was just done
            }
        }
    }

    private ScheduledExecutorService worker() {
        if (worker == null) {
            ScheduledThreadPoolExecutor pool = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "editora-recovery");
                t.setDaemon(true);
                return t;
            });
            pool.setRemoveOnCancelPolicy(true);
            worker = pool;
        }
        return worker;
    }

    /** Runs {@code task} on the worker thread, after everything already queued. False once closed. */
    boolean submit(Runnable task) {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            try {
                worker().execute(task);
                return true;
            } catch (RejectedExecutionException shutDown) {
                return false;
            }
        }
    }
}
