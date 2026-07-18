package com.editora.history;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
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

    private static final Logger LOG = Logger.getLogger(HistoryService.class.getName());

    private final HistoryBlobStore blobs;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "history-service");
        t.setDaemon(true);
        return t;
    });

    public HistoryService(HistoryBlobStore blobs) {
        this.blobs = blobs;
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
        List<HistoryRevision> snapshot = existing == null ? List.of() : new ArrayList<>(existing);
        exec.submit(() -> {
            try {
                String sha = HistoryBlobStore.sha256(content);
                if (!force && HistoryRetention.isDuplicate(snapshot, sha)) {
                    // Unchanged since the last revision — skip the blob write. Still report completion: the
                    // caller counts in-flight records to know when it is safe to GC.
                    Platform.runLater(() -> onRecorded.accept(null));
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
                Platform.runLater(() -> onRecorded.accept(rev));
            } catch (Throwable t) {
                // A failure here (e.g. the blob disk write) MUST still complete the callback: the caller
                // decrements an in-flight counter in onRecorded and only GCs when it hits zero, so a stranded
                // callback silently stops local-history GC for the rest of the session (blobs grow unbounded).
                // The submit() Future is unobserved, so without this the throw is swallowed and never logged.
                LOG.log(Level.WARNING, "Failed to record a history revision for " + file, t);
                Platform.runLater(() -> onRecorded.accept(null));
            }
        });
    }

    /** Fetches a revision's body off the FX thread and delivers it (or {@code null}) on the FX thread. */
    public void content(HistoryRevision rev, Consumer<String> onText) {
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
        exec.submit(() -> blobs.deleteUnreferenced(live));
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
