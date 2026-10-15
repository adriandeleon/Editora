package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Platform;

import com.editora.config.HttpResponseHistory;
import com.editora.config.migration.ConfigLoadProblem;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * Keeps each {@code .http} buffer's response panel and its saved history ({@link HttpResponseHistory}) in step:
 * reads the history when the panel is attached, saves it after each run, follows the buffer to a new file on a
 * first Save or a Save As, and forgets a history whose file is gone.
 *
 * <p>Saves go through {@link HttpResponseHistory#saveMerged}, with the keys of every entry this buffer has
 * held: another window showing the same file keeps its entries, and an entry this buffer removed (Clear, the
 * panel's own trimming) stays removed. All reading, merging and deleting runs on one background thread, in
 * the order it was asked for — so a save never overtakes the read it depends on; the FX thread only takes
 * snapshots.
 *
 * <p>FX thread only, except where noted.
 */
final class HttpHistorySync {

    private static final Logger LOG = Logger.getLogger(HttpHistorySync.class.getName());

    /** Reads, merges and deletes histories off the FX thread, one at a time, in order; idles out. */
    private static final ExecutorService WORKER = worker();

    /** Orphaned histories are looked for once per process, the first time a history is read. */
    private static final AtomicBoolean PRUNED = new AtomicBoolean();

    private static ExecutorService worker() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "http-history");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** What this sync knows of one buffer's history. */
    private static final class Tracked {
        /** The file the history belongs to (null while untitled), to notice a first Save / a Save As. */
        Path file;
        /** The keys of every entry this buffer has held (restored or run): see {@link HttpResponseHistory#saveMerged}. */
        final Set<String> seen = new HashSet<>();
        /** When this buffer's history was last cleared (epoch millis; 0 = never). */
        long clearedAt;
    }

    private final CoordinatorHost host;
    private final Supplier<HttpResponseHistory> store;
    private final Function<EditorBuffer, HttpClientPanel> panels;
    private final Map<EditorBuffer, String> environments;
    private final Consumer<EditorBuffer> refreshEnvironments;
    private final Map<EditorBuffer, Tracked> tracked = new IdentityHashMap<>();

    /**
     * @param store the window's history store (may answer null: keep nothing)
     * @param panels each buffer's response panel (null when it has none)
     * @param environments the environment chosen per buffer, which a saved history fills in when there is none
     * @param refreshEnvironments re-reads a buffer's environment list after its history (and choice) arrived
     */
    HttpHistorySync(
            CoordinatorHost host,
            Supplier<HttpResponseHistory> store,
            Function<EditorBuffer, HttpClientPanel> panels,
            Map<EditorBuffer, String> environments,
            Consumer<EditorBuffer> refreshEnvironments) {
        this.host = host;
        this.store = store;
        this.panels = panels;
        this.environments = environments;
        this.refreshEnvironments = refreshEnvironments;
    }

    /** {@code buffer} got its response panel: its saved history (if any) is read and put in it. */
    void attached(EditorBuffer buffer, HttpClientPanel panel) {
        Tracked t = new Tracked();
        t.file = buffer.getPath();
        tracked.put(buffer, t);
        HttpResponseHistory history = store.get();
        if (history == null || t.file == null) {
            return;
        }
        Path file = t.file;
        history.acquire(file);
        if (PRUNED.compareAndSet(false, true)) {
            WORKER.execute(() -> history.pruneOrphans(System.currentTimeMillis()));
        }
        WORKER.execute(() -> {
            HttpResponseHistory.Stored stored;
            try {
                stored = history.load(file);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not read the HTTP history of " + file, e);
                return; // an unreadable history is the same as none
            }
            List<HttpHistoryEntry> entries = entries(stored);
            Platform.runLater(() -> {
                if (panels.apply(buffer) != panel || tracked.get(buffer) != t) {
                    return; // closed, or the feature was switched off, while reading
                }
                report(file, stored);
                stored.responses.forEach(r -> t.seen.add(HttpResponseHistory.entryKey(r)));
                t.clearedAt = Math.max(t.clearedAt, stored.clearedAt);
                if (!stored.file.isEmpty()) {
                    environments.putIfAbsent(buffer, stored.environment); // a file never saved keeps the old choice
                }
                refreshEnvironments.accept(buffer);
                if (panel.history().isEmpty()) {
                    panel.restore(entries);
                } else {
                    panel.addOlder(entries); // a run finished before the read: the saved ones go after it
                }
            });
        });
    }

    /** {@code buffer}'s panel went away (the feature was switched off): its history is no longer followed. */
    void detached(EditorBuffer buffer) {
        Tracked t = tracked.remove(buffer);
        HttpResponseHistory history = store.get();
        if (t != null && t.file != null && history != null) {
            history.release(t.file);
        }
    }

    /**
     * {@code buffer}'s tab closed. When its file no longer exists (it was deleted), the history goes too: it
     * would otherwise stay in the config folder, with its response bodies, for good.
     */
    void closed(EditorBuffer buffer) {
        Tracked t = tracked.remove(buffer);
        HttpResponseHistory history = store.get();
        if (t == null || t.file == null || history == null) {
            return;
        }
        Path file = t.file;
        history.release(file);
        WORKER.execute(() -> {
            if (!Files.exists(file)) {
                history.delete(file);
            }
        });
    }

    /**
     * Whether {@code buffer} now has another file than its history belongs to (a first Save, a Save As, a
     * rename); if so its history follows: what the panel holds is merged with the history saved for the new
     * file and written there, and the old file's history is deleted when that file is gone (a rename or a
     * move) — a Save As keeps it, since the old file is still there.
     */
    boolean followPath(EditorBuffer buffer) {
        Tracked t = tracked.get(buffer);
        Path now = buffer.getPath();
        if (t == null || now == null || now.equals(t.file)) {
            return false;
        }
        Path old = t.file;
        t.file = now;
        HttpResponseHistory history = store.get();
        HttpClientPanel panel = panels.apply(buffer);
        if (history == null || panel == null) {
            refreshEnvironments.accept(buffer);
            return true;
        }
        history.acquire(now);
        if (old != null) {
            history.release(old);
        }
        WORKER.execute(() -> {
            HttpResponseHistory.Stored stored;
            try {
                stored = history.load(now);
            } catch (RuntimeException e) {
                stored = new HttpResponseHistory.Stored();
            }
            List<HttpHistoryEntry> older = entries(stored);
            HttpResponseHistory.Stored found = stored;
            Platform.runLater(() -> {
                if (panels.apply(buffer) != panel || !now.equals(buffer.getPath()) || tracked.get(buffer) != t) {
                    return; // closed, switched off, or saved elsewhere again meanwhile
                }
                report(now, found);
                found.responses.forEach(r -> t.seen.add(HttpResponseHistory.entryKey(r)));
                t.clearedAt = Math.max(t.clearedAt, found.clearedAt);
                if (!found.file.isEmpty()) {
                    environments.putIfAbsent(buffer, found.environment);
                }
                panel.addOlder(older);
                refreshEnvironments.accept(buffer);
                persist(buffer);
                if (old != null) {
                    WORKER.execute(() -> {
                        if (!Files.exists(old)) {
                            history.delete(old); // renamed or moved: the old name's history has nowhere to go
                        }
                    });
                }
            });
        });
        return true;
    }

    /** The user cleared {@code buffer}'s history: no window brings back an entry from before now. */
    void cleared(EditorBuffer buffer) {
        Tracked t = tracked.get(buffer);
        if (t != null) {
            t.clearedAt = System.currentTimeMillis();
        }
        persist(buffer);
    }

    /** Saves {@code buffer}'s environment choice and its last responses (never {@code @no-log} ones). */
    void persist(EditorBuffer buffer) {
        HttpResponseHistory history = store.get();
        HttpClientPanel p = panels.apply(buffer);
        Tracked t = tracked.get(buffer);
        Path file = buffer.getPath();
        if (history == null || p == null || t == null || file == null) {
            return;
        }
        HttpResponseHistory.Stored state = new HttpResponseHistory.Stored();
        String env = environments.get(buffer);
        state.environment = env == null ? p.getSelectedEnvironment() : env;
        state.clearedAt = t.clearedAt;
        for (HttpHistoryEntry e : p.history()) {
            if (!e.noLog()) {
                HttpResponseHistory.Response r = e.toStored();
                state.responses.add(r);
                t.seen.add(HttpResponseHistory.entryKey(r));
            }
        }
        Set<String> seen = Set.copyOf(t.seen);
        WORKER.execute(() -> {
            try {
                history.saveMerged(file, state, seen);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Could not save the HTTP history of " + file, e);
            }
        });
    }

    /** Waits until every read, save and delete asked for so far has run (tests). */
    static void awaitIdleForTest() throws Exception {
        WORKER.submit(() -> {}).get(30, TimeUnit.SECONDS);
    }

    /** The panel entries of a saved history, newest first; a damaged entry is left out. Any thread. */
    private static List<HttpHistoryEntry> entries(HttpResponseHistory.Stored stored) {
        List<HttpHistoryEntry> out = new ArrayList<>();
        for (HttpResponseHistory.Response r : stored.responses) {
            try {
                out.add(HttpHistoryEntry.fromStored(r));
            } catch (RuntimeException ignored) {
                // skip a damaged entry
            }
        }
        return out;
    }

    /** Says in this window what of {@code file}'s history could not be read (it is not repeated elsewhere). */
    private void report(Path file, HttpResponseHistory.Stored stored) {
        for (ConfigLoadProblem problem : stored.problems) {
            host.setError(ConfigLoadMessages.describe(problem, problem.mustNotOverwrite()));
        }
        if (stored.skipped > 0) {
            host.setError(tr(
                    "status.http.historyPartlyUnreadable",
                    stored.skipped,
                    file.getFileName().toString()));
        }
    }
}
