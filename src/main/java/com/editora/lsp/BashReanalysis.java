package com.editora.lsp;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Tells the Bash server about shell files that changed on disk while no window had them open (Bash review B7).
 *
 * <p>bash-language-server has no {@code workspace/didChangeWatchedFiles} handler and reads each workspace file once,
 * so after a {@code git pull}, a branch switch or another editor's save a sourced library kept its old functions and
 * line numbers: Go to Definition landed on the wrong line and new functions were missing from completion until a
 * restart. Each such file is opened and closed again on its session ({@link LanguageServerSession#reanalyze}), which
 * makes the server parse it afresh.
 *
 * <p>Changes are gathered for {@link #DEBOUNCE_MS} first, so a burst costs one pass; at most {@link
 * BashShims#MAX_REANALYSED_PER_BURST} files of at most {@link BashShims#MAX_REANALYSED_BYTES} each are read, off the FX
 * thread, and handed to the sessions back on it, where every other document sync is sent from.
 */
final class BashReanalysis {

    /** How long changes are gathered before the files are read. */
    static final long DEBOUNCE_MS = 400;

    private final Executor fx;
    private final Executor later;

    /** Session → document key → file, waiting for the next pass. Guarded by {@code this}. */
    private final Map<LanguageServerSession, Map<String, Path>> pending = new LinkedHashMap<>();

    private boolean scheduled;

    BashReanalysis(Executor fx) {
        this(fx, CompletableFuture.delayedExecutor(DEBOUNCE_MS, TimeUnit.MILLISECONDS));
    }

    /** @param later runs a pass once the burst is over (tests: at once) */
    BashReanalysis(Executor fx, Executor later) {
        this.fx = fx;
        this.later = later;
    }

    /**
     * Notes the shell files among {@code changes} that {@code session} should read again: created or changed, on
     * this machine, under its root, and not open. Any other session, or a server on another machine, is left alone.
     */
    void changed(LanguageServerSession session, List<LspManager.WatchedFile> changes) {
        if (!BashShims.isBash(session.serverId())
                || session.isDisposed()
                || !com.editora.process.ExecTargets.runsHere(session.root())) {
            return;
        }
        Path root = session.root().toAbsolutePath().normalize();
        boolean schedule;
        synchronized (this) {
            for (LspManager.WatchedFile c : changes) {
                if (c == null || c.file() == null || c.kind() == LspManager.WatchedKind.DELETED) {
                    continue;
                }
                Path file = c.file().toAbsolutePath().normalize();
                if (!file.startsWith(root)
                        || file.getFileSystem() != FileSystems.getDefault()
                        || !BashShims.reanalysable(file)) {
                    continue;
                }
                String key = LspManager.uri(file);
                if (!session.isOpen(key)) {
                    pending.computeIfAbsent(session, s -> new LinkedHashMap<>()).put(key, file);
                }
            }
            schedule = !pending.isEmpty() && !scheduled;
            scheduled |= schedule;
        }
        if (schedule) {
            later.execute(this::pass);
        }
    }

    /** Reads the waiting files (off the FX thread) and hands their text to their sessions on it. */
    private void pass() {
        Map<LanguageServerSession, Map<String, Path>> batch;
        synchronized (this) {
            batch = new LinkedHashMap<>(pending);
            pending.clear();
            scheduled = false;
        }
        Map<LanguageServerSession, Map<String, String>> texts = new LinkedHashMap<>();
        int budget = BashShims.MAX_REANALYSED_PER_BURST;
        for (var e : batch.entrySet()) {
            for (var file : e.getValue().entrySet()) {
                if (budget <= 0) {
                    break;
                }
                String text = read(file.getValue());
                if (text != null) {
                    texts.computeIfAbsent(e.getKey(), s -> new LinkedHashMap<>())
                            .put(file.getKey(), text);
                    budget--;
                }
            }
        }
        if (!texts.isEmpty()) {
            fx.execute(() -> texts.forEach((session, files) -> {
                if (!session.isDisposed()) {
                    files.forEach(session::reanalyze);
                }
            }));
        }
    }

    /** The file's UTF-8 text, or null when it is gone, too large or not text the server could read. */
    private static @org.jspecify.annotations.Nullable String read(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > BashShims.MAX_REANALYSED_BYTES) {
                return null;
            }
            return Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
