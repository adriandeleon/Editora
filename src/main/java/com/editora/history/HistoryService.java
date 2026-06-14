package com.editora.history;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

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
     * Records a snapshot off the FX thread, then delivers the updated (pruned) newest-first revision list
     * for {@code file} on the FX thread via {@code onUpdated}. If the content is identical to the newest
     * existing revision ({@link HistoryRetention#isDuplicate}), nothing is written and {@code onUpdated}
     * is <b>not</b> called (a no-op save adds no revision).
     *
     * @param existing the file's current revision list (captured on the FX thread; not mutated)
     */
    public void snapshot(
            Path file,
            String content,
            String reason,
            List<HistoryRevision> existing,
            RetentionPolicy policy,
            long now,
            Consumer<List<HistoryRevision>> onUpdated) {
        List<HistoryRevision> snapshot = existing == null ? List.of() : new ArrayList<>(existing);
        exec.submit(() -> {
            String sha = HistoryBlobStore.sha256(content);
            if (HistoryRetention.isDuplicate(snapshot, sha)) {
                return; // unchanged since the last revision — skip
            }
            blobs.put(content, sha);
            long size = content.getBytes(StandardCharsets.UTF_8).length;
            HistoryRevision rev = new HistoryRevision(file.toString(), now, size, sha, reason);
            List<HistoryRevision> updated = new ArrayList<>(snapshot.size() + 1);
            updated.add(rev); // newest-first
            updated.addAll(snapshot);
            List<HistoryRevision> pruned =
                    HistoryRetention.prune(updated, policy.maxPerFile(), policy.maxAgeMillis(), now);
            Platform.runLater(() -> onUpdated.accept(pruned));
        });
    }

    /** Fetches a revision's body off the FX thread and delivers it (or {@code null}) on the FX thread. */
    public void content(HistoryRevision rev, Consumer<String> onText) {
        exec.submit(() -> {
            String text = rev == null ? null : blobs.get(rev.sha256());
            Platform.runLater(() -> onText.accept(text));
        });
    }

    /** Garbage-collects blobs no longer referenced by {@code live} (computed on the FX thread). */
    public void gc(Set<String> live) {
        exec.submit(() -> blobs.deleteUnreferenced(live));
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
