package com.editora.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.config.SharedConfig;
import com.editora.editor.EditorBuffer;
import com.editora.history.HistoryQueries;
import com.editora.history.HistoryRetention;
import com.editora.history.HistoryService;
import com.editora.io.AtomicFileWrite;
import com.editora.io.DocumentWriteSequencer;

import static com.editora.i18n.Messages.tr;

/**
 * Local File History — per-file save/label/delete snapshots stored outside the file, with a tool window to
 * browse / diff / restore revisions. Extracted from {@link MainController} via the {@link CoordinatorHost}
 * pattern. Owns the {@link HistoryService} (off-thread snapshot/blob engine) + the {@link FileHistoryPanel};
 * reuses {@link DiffCoordinator} for the revision diff/restore (passed in). {@code MainController} keeps the
 * {@code ToolWindow} (built with {@link #panel()}) and wires the record hooks (save / autosave / external /
 * pre-delete) + the {@code history.*}/{@code tool.fileHistory} commands + the project-tree "Show Local
 * History" item to this coordinator.
 */
final class HistoryCoordinator {

    @FunctionalInterface
    interface RevisionContentLoader {
        void load(HistoryRevision revision, Consumer<String> completion);
    }

    @FunctionalInterface
    interface RestoreWriter {
        boolean write(Path file, byte[] expectedBytes, byte[] replacementBytes, BooleanSupplier current)
                throws IOException;
    }

    /** Dependencies whose timing or failure determines whether a history restore is safe to commit. */
    record RestoreSupport(
            RevisionContentLoader contentLoader,
            RestoreWriter writer,
            Predicate<Path> confirmOverwrite,
            Function<Path, DocumentWriteSequencer.Ticket> beginDocumentWrite) {

        RestoreSupport {
            Objects.requireNonNull(contentLoader, "contentLoader");
            Objects.requireNonNull(writer, "writer");
            Objects.requireNonNull(beginDocumentWrite, "beginDocumentWrite");
        }
    }

    enum RestoreResult {
        RESTORED,
        CANCELLED,
        INVALID_REQUEST,
        CONTENT_UNAVAILABLE,
        TARGET_CHANGED,
        SUPERSEDED,
        BUFFER_CHANGED,
        APPLY_FAILED,
        WRITE_FAILED
    }

    record DeleteCapture(boolean durable, byte[] expectedBytes) {
        DeleteCapture {
            expectedBytes = expectedBytes == null ? null : expectedBytes.clone();
        }
    }

    private record TargetState(boolean existed, byte[] expectedBytes) {}

    /** Window hooks beyond {@link CoordinatorHost} that the history flows need. */
    interface Ops {
        /** The active project's history bucket: per-file (absolute-path key) → revisions. */
        Map<String, List<HistoryRevision>> historyMap();

        /** Every project's history (project key → file key → revisions), for blob GC. */
        Map<String, Map<String, List<HistoryRevision>>> historyByProject();

        /** Persists {@code history.json}. */
        void saveHistory();

        /** Reports durable publication when a caller must not proceed on an enqueue acknowledgment alone. */
        default void saveHistory(java.util.function.Consumer<Boolean> completion) {
            saveHistory();
            completion.accept(true);
        }

        /** Directory holding the content-addressed history blobs. */
        Path blobsDir();

        /** Shows/hides the File History tool-window stripe button. */
        void setToolWindowAvailable(boolean available);

        /** Opens the File History tool window. */
        void openToolWindow();

        /** Opens (or focuses) the tab for {@code file}. */
        void openPath(Path file);

        /** Re-scans the Project tree (a revision was restored to disk, recreating a file). */
        void refreshProjectTree();

        /** The current text of {@code file}: the open buffer's live text if open, else the on-disk content. */
        String currentTextOf(Path file);
    }

    private final CoordinatorHost host;
    private final DiffCoordinator diff;
    private final Ops ops;
    private final HistoryService historyService;
    private final boolean ownsHistoryService;
    private final RestoreSupport restoreSupport;
    private final ExecutorService restoreExecutor;
    private final FileHistoryPanel panel;

    HistoryCoordinator(CoordinatorHost host, DiffCoordinator diff, Ops ops) {
        this(host, diff, ops, new HistoryService(new com.editora.history.HistoryBlobStore(ops.blobsDir())), true, null);
    }

    HistoryCoordinator(CoordinatorHost host, DiffCoordinator diff, Ops ops, HistoryService historyService) {
        this(host, diff, ops, historyService, false, null);
    }

    HistoryCoordinator(CoordinatorHost host, DiffCoordinator diff, Ops ops, SharedConfig shared) {
        this(
                host,
                diff,
                ops,
                shared.historyService(),
                false,
                defaultRestoreSupport(shared.historyService(), shared.documentWrites()::begin));
    }

    HistoryCoordinator(
            CoordinatorHost host,
            DiffCoordinator diff,
            Ops ops,
            HistoryService historyService,
            Function<Path, DocumentWriteSequencer.Ticket> beginDocumentWrite) {
        this(host, diff, ops, historyService, false, defaultRestoreSupport(historyService, beginDocumentWrite));
    }

    HistoryCoordinator(
            CoordinatorHost host,
            DiffCoordinator diff,
            Ops ops,
            HistoryService historyService,
            RestoreSupport restoreSupport) {
        this(host, diff, ops, historyService, false, restoreSupport);
    }

    private HistoryCoordinator(
            CoordinatorHost host,
            DiffCoordinator diff,
            Ops ops,
            HistoryService historyService,
            boolean ownsHistoryService,
            RestoreSupport restoreSupport) {
        this.host = host;
        this.diff = diff;
        this.ops = ops;
        this.historyService = historyService;
        this.ownsHistoryService = ownsHistoryService;
        this.restoreSupport = restoreSupport == null ? standaloneRestoreSupport(historyService) : restoreSupport;
        this.restoreExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "history-restore");
            thread.setDaemon(true);
            return thread;
        });
        this.panel = new FileHistoryPanel(historyActions());
        this.panel.setDiffSupport(diffSupport());
    }

    private static RestoreSupport standaloneRestoreSupport(HistoryService historyService) {
        DocumentWriteSequencer writes = new DocumentWriteSequencer();
        return defaultRestoreSupport(historyService, writes::begin);
    }

    private static RestoreSupport defaultRestoreSupport(
            HistoryService historyService, Function<Path, DocumentWriteSequencer.Ticket> beginDocumentWrite) {
        return new RestoreSupport(
                historyService::content, HistoryCoordinator::writeRestoredContent, null, beginDocumentWrite);
    }

    /** The right-pane diff machinery for {@link FileHistoryPanel} (revision content, off-thread diff, the
     *  current file text, whole-file revert, and the per-hunk apply/undo/save through {@code DiffCoordinator}). */
    private FileHistoryPanel.DiffSupport diffSupport() {
        return new FileHistoryPanel.DiffSupport() {
            @Override
            public void fetchContent(
                    HistoryRevision revision, java.util.function.Consumer<java.util.Optional<String>> onText) {
                historyService.content(revision, text -> onText.accept(java.util.Optional.ofNullable(text)));
            }

            @Override
            public void computeDiff(
                    String left,
                    String right,
                    com.editora.diff.DiffEngine.DiffOptions opts,
                    java.util.function.Consumer<com.editora.diff.DiffModels.DiffModel> onResult) {
                diff.computeDiff(left, right, opts, onResult);
            }

            @Override
            public String currentText(Path target) {
                return ops.currentTextOf(target);
            }

            @Override
            public void revert(HistoryRevision revision) {
                restoreHistory(revision);
            }

            @Override
            public void applyToLocal(Path target, String newText) {
                diff.applyToLocal(target, newText);
            }

            @Override
            public void undoLocal(Path target) {
                diff.undoLocal(target);
            }

            @Override
            public void saveLocal(Path target) {
                diff.saveLocal(target);
            }

            @Override
            public com.editora.config.Settings settings() {
                return host.settings();
            }
        };
    }

    /** The File History tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    FileHistoryPanel panel() {
        return panel;
    }

    void shutdown() {
        restoreExecutor.shutdownNow();
        if (ownsHistoryService) {
            historyService.shutdown();
        }
    }

    /** Effective Local File History gate: the setting, but off in Simple UI mode (saved setting unchanged). */
    boolean isEnabled() {
        return host.settings().isLocalHistory() && !host.simpleModeActive();
    }

    /**
     * Reconciles the Local File History UI with its setting: updates the tool window's availability for the
     * active file and refreshes its revision list. Runs at startup and on every settings apply.
     */
    void applySupport() {
        refresh();
    }

    /** Sets the history tool window's availability (local file + feature on) and reloads its list. */
    void refresh() {
        EditorBuffer b = host.activeBuffer();
        boolean available = isEnabled() && b != null && b.getPath() != null && host.isLocalBuffer(b);
        ops.setToolWindowAvailable(available);
        if (available) {
            List<HistoryRevision> revs = ops.historyMap().getOrDefault(historyKey(b.getPath()), List.of());
            panel.setRevisions(revs, b.getPath().getFileName().toString(), b.getPath());
        } else {
            panel.setRevisions(List.of(), null, null);
        }
    }

    /** The per-file key used in the history bucket (absolute path string). */
    private static String historyKey(Path file) {
        return PathKeys.normalizedKey(file);
    }

    /**
     * Records a snapshot of {@code buffer}'s content (captured here on the FX thread) off-thread, then folds
     * the pruned result back into the per-project history bucket + persists + GCs stale blobs. A no-op when
     * the feature is off, the buffer is remote/untitled, or the content matches the newest revision.
     */
    void record(EditorBuffer buffer, String reason) {
        if (!isEnabled() || buffer == null || buffer.getPath() == null || !host.isLocalBuffer(buffer)) {
            return;
        }
        recordFor(buffer.getPath(), buffer.getContent(), reason, "", false, null);
    }

    /** Records caller-captured content, used before a closed-file bulk replacement rewrites the file. */
    void record(Path file, String content, String reason) {
        recordFor(file, content, reason, "", false, null);
    }

    /** Records and waits for the index snapshot to become durable before acknowledging the caller. */
    void recordDurably(Path file, String content, String reason, java.util.function.Consumer<Boolean> completion) {
        recordFor(file, content, reason, "", false, completion);
    }

    /**
     * Records a snapshot of arbitrary {@code content} for {@code file} (not necessarily an open buffer — e.g.
     * a manual label, or a file captured at delete time), folding the pruned result into the per-project
     * bucket + persisting + GCing. {@code force} bypasses the unchanged-content skip; {@code label} is the
     * user name ({@code ""} for automatic revisions).
     */
    private void recordFor(Path file, String content, String reason, String label, boolean force) {
        recordFor(file, content, reason, label, force, null);
    }

    private void recordFor(
            Path file,
            String content,
            String reason,
            String label,
            boolean force,
            java.util.function.Consumer<Boolean> durableCompletion) {
        if (!isEnabled() || file == null || content == null || !com.editora.vfs.Vfs.isLocal(file)) {
            if (durableCompletion != null) {
                durableCompletion.accept(true);
            }
            return;
        }
        String key = historyKey(file);
        List<HistoryRevision> existing = ops.historyMap().getOrDefault(key, List.of());
        var s = host.settings();
        long maxAgeMillis = s.getHistoryMaxAgeDays() > 0 ? s.getHistoryMaxAgeDays() * 86_400_000L : 0;
        var policy = new HistoryRetention.RetentionPolicy(
                s.getHistoryMaxPerFile(), maxAgeMillis, (long) Math.max(0, s.getHistoryMaxTotalMb()) * 1024L * 1024L);
        long now = System.currentTimeMillis();
        historyService.snapshotWithOutcome(file, content, reason, label, force, existing, policy, now, outcome -> {
            HistoryRevision rev = outcome.revision();
            if (rev != null) {
                applyRecorded(key, rev, policy, now, durableCompletion);
            } else if (durableCompletion != null) {
                durableCompletion.accept(outcome.successful());
            }
            EditorBuffer active = host.activeBuffer();
            if (rev != null
                    && active != null
                    && active.getPath() != null
                    && historyKey(active.getPath()).equals(key)) {
                refresh();
            }
        });
    }

    /**
     * Folds one recorded revision into the project's index, on the FX thread, against the list <b>as it is
     * now</b> — the executor no longer builds the list, because it could only see the list as it was when the
     * record was submitted.
     */
    private void applyRecorded(
            String key,
            HistoryRevision rev,
            HistoryRetention.RetentionPolicy policy,
            long now,
            java.util.function.Consumer<Boolean> durableCompletion) {
        Map<String, List<HistoryRevision>> bucket = ops.historyMap();
        List<HistoryRevision> current = bucket.getOrDefault(key, List.of());
        List<HistoryRevision> updated = new ArrayList<>(current.size() + 1);
        updated.add(rev); // newest-first
        updated.addAll(current);
        bucket.put(key, HistoryRetention.prune(updated, policy.maxPerFile(), policy.maxAgeMillis(), now));
        // Enforce the per-project byte budget across the whole bucket, then persist.
        var trimmed = HistoryRetention.enforceProjectBudget(bucket, policy.maxTotalBytesPerProject());
        bucket.clear();
        bucket.putAll(trimmed);
        if (durableCompletion == null) {
            ops.saveHistory();
        } else {
            ops.saveHistory(durableCompletion);
        }
    }

    private FileHistoryPanel.Actions historyActions() {
        return new FileHistoryPanel.Actions() {
            @Override
            public void refresh() {
                HistoryCoordinator.this.refresh();
            }

            @Override
            public void restore(HistoryRevision revision) {
                restoreHistory(revision);
            }

            @Override
            public void restoreToDisk(HistoryRevision revision) {
                restoreRevisionToDisk(revision);
            }

            @Override
            public void editLabel(HistoryRevision revision) {
                HistoryCoordinator.this.editLabel(revision);
            }
        };
    }

    /**
     * "Set/Edit Label" on an <b>existing</b> revision: prompts for a name (prefilled with the current label)
     * and rewrites that revision's {@code label} in place so it can be found later by name. An empty answer
     * clears the label. Distinct from {@link #putLabel()}, which records a <i>new</i> labeled snapshot.
     */
    void editLabel(HistoryRevision revision) {
        if (!isEnabled() || revision == null) {
            return;
        }
        String initial = revision.label() == null ? "" : revision.label();
        host.promptText(tr("history.label.title"), tr("history.label.prompt"), initial, name -> {
            String label = name == null ? "" : name.strip();
            if (label.equals(initial)) {
                return; // unchanged
            }
            if (!relabelRevision(revision, label)) {
                return; // the revision was pruned away meanwhile
            }
            ops.saveHistory();
            refresh();
            host.setStatus(label.isEmpty() ? tr("status.history.labelCleared") : tr("status.history.labeled", label));
        });
    }

    /**
     * Replaces {@code revision} (matched by object identity) in its history bucket with a copy carrying the new
     * {@code label}. Records are immutable, and a bucket's list may be immutable, so we swap in a fresh list.
     * Returns false when the revision is no longer present.
     */
    private boolean relabelRevision(HistoryRevision revision, String label) {
        Map<String, List<HistoryRevision>> bucket = ops.historyMap();
        for (Map.Entry<String, List<HistoryRevision>> e : bucket.entrySet()) {
            List<HistoryRevision> list = e.getValue();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) == revision) {
                    HistoryRevision old = list.get(i);
                    HistoryRevision relabeled = new HistoryRevision(
                            old.path(), old.timestamp(), old.sizeBytes(), old.sha256(), old.reason(), label);
                    List<HistoryRevision> copy = new ArrayList<>(list);
                    copy.set(i, relabeled);
                    bucket.put(e.getKey(), copy);
                    return true;
                }
            }
        }
        return false;
    }

    /** Opens the Local File History tool window for the active file. */
    void showActive() {
        if (!isEnabled()) {
            host.setStatus(tr("status.history.disabled"));
            return;
        }
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !host.isLocalBuffer(b)) {
            host.setStatus(tr("status.history.noFile"));
            return;
        }
        refresh();
        ops.openToolWindow();
    }

    /**
     * Project-tree "Show Local History": for a file, opens it (the tool window is keyed to the active buffer)
     * then shows its history; for a folder, shows the folder-history view (files under it + deleted files).
     */
    void showForPath(Path file) {
        if (!isEnabled()) {
            host.setStatus(tr("status.history.disabled"));
            return;
        }
        if (file == null || !com.editora.vfs.Vfs.isLocal(file)) {
            host.setStatus(tr("status.history.noFile"));
            return;
        }
        if (Files.isDirectory(file)) {
            showFolderHistory(file);
            return;
        }
        ops.openPath(file); // makes it the active buffer, which the history tool window tracks
        showActive();
    }

    /** Shows the folder-history view: every file under {@code folder} with recorded revisions (incl. deleted). */
    private void showFolderHistory(Path folder) {
        var folderRevs = HistoryQueries.folderRevisions(ops.historyMap(), historyKey(folder));
        List<FileHistoryPanel.FileGroup> groups = new ArrayList<>();
        for (var e : folderRevs.entrySet()) {
            Path p = Path.of(e.getKey());
            String display = folder.relativize(p).toString();
            boolean deleted = !Files.exists(p);
            groups.add(new FileHistoryPanel.FileGroup(e.getKey(), display, deleted, e.getValue()));
        }
        if (groups.isEmpty()) {
            host.setStatus(tr("status.history.folderEmpty", folder.getFileName()));
            return;
        }
        panel.setFolderHistory(folder.getFileName().toString(), groups);
        ops.setToolWindowAvailable(true);
        ops.openToolWindow();
    }

    /**
     * Folder-history restore: writes {@code revision}'s content back to its own path, recreating a deleted file
     * (parent dirs created); confirms first when the file still exists. Then opens it + refreshes the tree.
     */
    CompletableFuture<RestoreResult> restoreRevisionToDisk(HistoryRevision revision) {
        CompletableFuture<RestoreResult> completion = new CompletableFuture<>();
        if (revision == null || revision.path().isBlank()) {
            completion.complete(RestoreResult.INVALID_REQUEST);
            return completion;
        }

        final Path file;
        try {
            file = Path.of(revision.path());
        } catch (RuntimeException invalidPath) {
            completion.complete(RestoreResult.INVALID_REQUEST);
            return completion;
        }

        submitRestoreWork(completion, () -> {
            final TargetState target;
            try {
                boolean existed = Files.exists(file);
                target = new TargetState(existed, existed ? Files.readAllBytes(file) : null);
            } catch (IOException | RuntimeException failure) {
                finishDiskRestore(file, completion, RestoreResult.WRITE_FAILED);
                return;
            }
            Platform.runLater(() -> startDiskRestore(revision, file, target, completion));
        });
        return completion;
    }

    private void startDiskRestore(
            HistoryRevision revision, Path file, TargetState target, CompletableFuture<RestoreResult> completion) {
        if (completion.isDone()) {
            return;
        }
        Predicate<Path> confirmation = restoreSupport.confirmOverwrite();
        if (target.existed() && !(confirmation == null ? confirmRestoreOverwrite(file) : confirmation.test(file))) {
            completion.complete(RestoreResult.CANCELLED);
            return;
        }

        final DocumentWriteSequencer.Ticket ticket;
        try {
            ticket = restoreSupport.beginDocumentWrite().apply(file);
        } catch (RuntimeException failure) {
            finishDiskRestore(file, completion, RestoreResult.WRITE_FAILED);
            return;
        }
        try {
            restoreSupport
                    .contentLoader()
                    .load(
                            revision,
                            text -> onFx(() -> {
                                if (completion.isDone()) {
                                    ticket.close();
                                    return;
                                }
                                if (text == null) {
                                    ticket.close();
                                    finishDiskRestore(file, completion, RestoreResult.CONTENT_UNAVAILABLE);
                                    return;
                                }
                                byte[] replacement = text.getBytes(StandardCharsets.UTF_8);
                                if (!submitRestoreWork(
                                        completion,
                                        () -> commitDiskRestore(file, target, replacement, ticket, completion))) {
                                    ticket.close();
                                }
                            }));
        } catch (RuntimeException failure) {
            ticket.close();
            finishDiskRestore(file, completion, RestoreResult.CONTENT_UNAVAILABLE);
        }
    }

    private void commitDiskRestore(
            Path file,
            TargetState target,
            byte[] replacement,
            DocumentWriteSequencer.Ticket ticket,
            CompletableFuture<RestoreResult> completion) {
        RestoreResult result;
        try (ticket) {
            DocumentWriteSequencer.Outcome<Boolean> write = ticket.runIfCurrent(
                    () -> restoreSupport.writer().write(file, target.expectedBytes(), replacement, ticket::isCurrent));
            if (!write.executed()) {
                result = RestoreResult.SUPERSEDED;
            } else if (Boolean.TRUE.equals(write.value())) {
                result = RestoreResult.RESTORED;
            } else {
                result = ticket.isCurrent() ? RestoreResult.TARGET_CHANGED : RestoreResult.SUPERSEDED;
            }
        } catch (IOException | RuntimeException failure) {
            result = RestoreResult.WRITE_FAILED;
        }
        finishDiskRestore(file, completion, result);
    }

    private void finishDiskRestore(Path file, CompletableFuture<RestoreResult> completion, RestoreResult result) {
        onFx(() -> {
            if (completion.isDone()) {
                return;
            }
            if (result == RestoreResult.RESTORED) {
                ops.openPath(file);
                ops.refreshProjectTree();
                host.setStatus(tr("status.history.restored", file.getFileName()));
            } else if (result != RestoreResult.CANCELLED) {
                host.setStatus(tr("status.history.restoreFailed", file.getFileName()));
            }
            completion.complete(result);
        });
    }

    private boolean confirmRestoreOverwrite(Path file) {
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("history.restoreOverwrite", file.getFileName()),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("history.menu.restore"));
        confirm.setHeaderText(null);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    static boolean writeRestoredContent(
            Path file, byte[] expectedBytes, byte[] replacementBytes, BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) {
            return false;
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (expectedBytes == null) {
            if (Files.exists(file)) {
                return false;
            }
            return AtomicFileWrite.createNew(file, replacementBytes, current);
        }
        return AtomicFileWrite.replaceIfUnchanged(file, expectedBytes, replacementBytes, current);
    }

    /**
     * Captures a regular file immediately before deletion and acknowledges only after the history body and
     * index are durable. The returned bytes let the caller refuse deletion if the file changes between the
     * capture and the filesystem operation. Binary and oversized files are outside Local History's text
     * contract; they may still be deleted after explicit confirmation, with exact-byte guarding when small.
     */
    void captureBeforeDeleteDurably(Path file, Consumer<DeleteCapture> completion) {
        Objects.requireNonNull(completion, "completion");
        if (file == null || !com.editora.vfs.Vfs.isLocal(file)) {
            completion.accept(new DeleteCapture(false, null));
            return;
        }
        boolean historyEnabled = isEnabled();
        try {
            if (!Files.isRegularFile(file)) {
                completion.accept(new DeleteCapture(false, null));
                return;
            }
            if (Files.size(file) > EditorBuffer.LARGE_FILE_BYTES) {
                completion.accept(new DeleteCapture(true, null));
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            for (byte b : bytes) {
                if (b == 0) {
                    completion.accept(new DeleteCapture(true, bytes));
                    return;
                }
            }
            if (!historyEnabled) {
                completion.accept(new DeleteCapture(true, bytes));
                return;
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            recordFor(
                    file,
                    content,
                    HistoryRevision.REASON_DELETE,
                    "",
                    true,
                    durable -> onFx(() -> completion.accept(new DeleteCapture(durable, bytes))));
        } catch (IOException e) {
            completion.accept(new DeleteCapture(!historyEnabled, null));
        }
    }

    /** Restores {@code revision}'s content into the active file via an undoable whole-file replace. */
    CompletableFuture<RestoreResult> restoreHistory(HistoryRevision revision) {
        CompletableFuture<RestoreResult> completion = new CompletableFuture<>();
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || revision == null) {
            completion.complete(RestoreResult.INVALID_REQUEST);
            return completion;
        }
        Path target = b.getPath();
        long documentVersion = b.docVersion();
        try {
            restoreSupport
                    .contentLoader()
                    .load(
                            revision,
                            text -> onFx(() -> {
                                if (completion.isDone()) {
                                    return;
                                }
                                if (text == null) {
                                    finishBufferRestore(target, completion, RestoreResult.CONTENT_UNAVAILABLE);
                                    return;
                                }
                                if (b.isDisposed()
                                        || b.getPath() == null
                                        || !PathKeys.key(target).equals(PathKeys.key(b.getPath()))
                                        || b.docVersion() != documentVersion) {
                                    finishBufferRestore(target, completion, RestoreResult.BUFFER_CHANGED);
                                    return;
                                }
                                RestoreResult result = diff.applyToLocal(target, text)
                                        ? RestoreResult.RESTORED
                                        : RestoreResult.APPLY_FAILED;
                                finishBufferRestore(target, completion, result);
                            }));
        } catch (RuntimeException failure) {
            finishBufferRestore(target, completion, RestoreResult.CONTENT_UNAVAILABLE);
        }
        return completion;
    }

    private void finishBufferRestore(Path target, CompletableFuture<RestoreResult> completion, RestoreResult result) {
        if (completion.isDone()) {
            return;
        }
        if (result == RestoreResult.RESTORED) {
            host.setStatus(tr("status.history.restored", target.getFileName()));
        } else {
            host.setStatus(tr("status.history.restoreFailed", target.getFileName()));
        }
        completion.complete(result);
    }

    private boolean submitRestoreWork(CompletableFuture<RestoreResult> completion, Runnable task) {
        try {
            restoreExecutor.submit(task);
            return true;
        } catch (RejectedExecutionException shuttingDown) {
            completion.complete(RestoreResult.WRITE_FAILED);
            return false;
        }
    }

    private static void onFx(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /**
     * "Put Label": prompts for a name and records a labeled snapshot of the active file's current content
     * (forced, so a label always marks a point in time even if the content is unchanged).
     */
    void putLabel() {
        if (!isEnabled()) {
            host.setStatus(tr("status.history.disabled"));
            return;
        }
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !host.isLocalBuffer(b)) {
            host.setStatus(tr("status.history.noFile"));
            return;
        }
        Path file = b.getPath();
        String content = b.getContent(); // snapshot the state at the moment the command was invoked
        host.promptText(tr("history.label.title"), tr("history.label.prompt"), "", name -> {
            String label = name == null ? "" : name.strip();
            if (label.isEmpty()) {
                return;
            }
            recordFor(file, content, HistoryRevision.REASON_LABEL, label, true);
            host.setStatus(tr("status.history.labeled", label));
        });
    }

    /** "Recent Changes": a cross-file picker of the active project's most recent revisions, newest-first. */
    void showRecentChanges() {
        if (!isEnabled()) {
            host.setStatus(tr("status.history.disabled"));
            return;
        }
        QuickOpen<HistoryRevision> picker = new QuickOpen<>(
                tr("history.recent.title"),
                tr("history.recent.prompt"),
                () -> HistoryQueries.recent(ops.historyMap(), 200),
                this::recentChangeLabel,
                HistoryRevision::path,
                this::openRecentChange);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Picker row text for a recent revision: {@code fileName · time · label-or-reason}. */
    private String recentChangeLabel(HistoryRevision r) {
        String name = Path.of(r.path()).getFileName().toString();
        String tag = r.label() != null && !r.label().isBlank() ? r.label() : historyReasonLabel(r.reason());
        return name + "  ·  " + historyTime(r.timestamp()) + "  ·  " + tag;
    }

    /** Opens the file behind a recent revision and shows its File History. */
    private void openRecentChange(HistoryRevision r) {
        if (r == null) {
            return;
        }
        ops.openPath(Path.of(r.path()));
        showActive();
    }

    /** Localized capture-reason label (mirrors {@code FileHistoryPanel.reasonLabel} for cross-file pickers). */
    static String historyReasonLabel(String reason) {
        return switch (reason == null ? "" : reason) {
            case HistoryRevision.REASON_AUTOSAVE -> tr("history.reason.autosave");
            case HistoryRevision.REASON_EXTERNAL -> tr("history.reason.external");
            case HistoryRevision.REASON_LABEL -> tr("history.reason.label");
            case HistoryRevision.REASON_DELETE -> tr("history.reason.delete");
            default -> tr("history.reason.save");
        };
    }

    private static String historyTime(long epochMillis) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()));
    }
}
