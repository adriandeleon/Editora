package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

import com.editora.config.HistoryPurgeHold;
import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.config.SharedConfig;
import com.editora.editops.LineEndings;
import com.editora.editor.EditorBuffer;
import com.editora.editorconfig.EditorConfigCharset;
import com.editora.history.HistoryMoves;
import com.editora.history.HistoryQueries;
import com.editora.history.HistoryRetention;
import com.editora.history.HistoryService;
import com.editora.io.AtomicFileWrite;
import com.editora.io.DocumentWriteSequencer;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

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

    /** Files larger than this are not captured into local history when a save, delete or overwrite replaces them. */
    static final long MAX_CAPTURE_BYTES = 5L * 1024 * 1024;

    @FunctionalInterface
    interface RevisionContentLoader {
        void load(HistoryRevision revision, Consumer<String> completion);
    }

    @FunctionalInterface
    interface RestoreWriter {
        boolean write(Path file, byte @Nullable [] expectedBytes, byte[] replacementBytes, BooleanSupplier current)
                throws IOException;
    }

    /** Dependencies whose timing or failure determines whether a history restore is safe to commit. */
    record RestoreSupport(
            RevisionContentLoader contentLoader,
            RestoreWriter writer,
            @Nullable Predicate<Path> confirmOverwrite,
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
        WRITE_FAILED,
        /** The file as it is now could not be kept in history first, so it was not replaced. */
        NOT_PRESERVED
    }

    /**
     * The status-bar message for a restore that ended as {@code result}, or {@code null} when there is
     * nothing to say (the user cancelled, or the request named no revision). One message for every failure
     * — "could not read the snapshot" — sent the user looking for a damaged history when the snapshot had
     * been read and the file was read-only, or had changed while it was being restored.
     */
    static @Nullable String restoreMessageKey(RestoreResult result) {
        return switch (result) {
            case RESTORED -> "status.history.restored";
            case CANCELLED, INVALID_REQUEST -> null;
            case CONTENT_UNAVAILABLE -> "status.history.restoreFailed";
            case TARGET_CHANGED, SUPERSEDED, BUFFER_CHANGED -> "status.history.restoreChanged";
            case APPLY_FAILED -> "status.history.restoreReadOnly";
            case WRITE_FAILED -> "status.history.restoreWriteFailed";
            case NOT_PRESERVED -> "status.history.restoreNotPreserved";
        };
    }

    private void reportRestore(Path file, RestoreResult result) {
        String key = restoreMessageKey(result);
        if (key == null || disposed) {
            return;
        }
        if (result == RestoreResult.RESTORED) {
            host.setStatus(tr(key, file.getFileName()));
        } else {
            host.setError(tr(key, file.getFileName()));
        }
    }

    record DeleteCapture(boolean durable, byte @Nullable [] expectedBytes) {
        DeleteCapture {
            expectedBytes = expectedBytes == null ? null : expectedBytes.clone();
        }
    }

    /** The file a disk restore replaces, looked at off the FX thread: its bytes and its EditorConfig charset. */
    private record TargetState(
            boolean existed,
            byte @Nullable [] expectedBytes,
            @Nullable String charsetRule) {}

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

        /** The buffer this window has open for {@code file}, or {@code null}. */
        default @Nullable EditorBuffer openBufferFor(Path file) {
            return null;
        }

        /** The index changed here: the other windows' panels must not go on showing what it was. */
        default void historyChanged() {}

        /** The window's project root, for showing a path relative to it; {@code null} without a project. */
        default @Nullable Path projectRoot() {
            return null;
        }

        /**
         * Whether revision bodies nothing refers to any more can be deleted from disk now — not while another
         * running Editora shares the configuration folder, or while the index cannot be trusted.
         */
        default boolean canCollectNow() {
            return true;
        }

        /**
         * What keeps the bodies {@code sha256s} on disk once a purge removed their rows, or
         * {@link HistoryPurgeHold#NONE} when the collection that follows deletes them.
         */
        default HistoryPurgeHold purgeHold(java.util.Collection<String> sha256s) {
            return canCollectNow() ? HistoryPurgeHold.NONE : HistoryPurgeHold.OTHER_PROCESS;
        }

        /**
         * The buffer open for {@code file} in this window or, failing that, in another one, or {@code null}. A
         * restore of an open file goes into its buffer, whichever window has it (B4).
         */
        default @Nullable EditorBuffer openBufferAnywhere(Path file) {
            return openBufferFor(file);
        }

        /** Brings {@code buffer}'s tab forward in the window that holds it. */
        default void revealBuffer(EditorBuffer buffer) {}

        /** Whether the Local History tool window is open now. */
        default boolean toolWindowOpen() {
            return false;
        }

        /** Opens the Local History tool window again without taking the keyboard from the editor. */
        default void reopenToolWindow() {
            openToolWindow();
        }
    }

    /** The text a save replaced the first time this window saved the file: its state before the session. */
    static final String REASON_BASELINE = "BASELINE";

    /** The text of a closed file as Replace in Files found it, recorded before it was rewritten. */
    static final String REASON_BEFORE_REPLACE = "BEFORE_REPLACE";

    private final CoordinatorHost host;
    private final DiffCoordinator diff;
    private final Ops ops;
    private final HistoryService historyService;
    private final boolean ownsHistoryService;
    private final RestoreSupport restoreSupport;
    private final ExecutorService restoreExecutor;
    /** The File History panel, built on its first use (the tool window's first open, or a jump into it). */
    private @Nullable FileHistoryPanel panel;

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
            @Nullable RestoreSupport restoreSupport) {
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
            public void currentTextLater(Path target, java.util.function.Consumer<String> then) {
                EditorBuffer open = ops.openBufferFor(target);
                if (open != null) {
                    then.accept(open.getContent());
                    return;
                }
                // Read as the editor reads it — the file's charset, line feeds only, no byte-order mark — so a
                // Latin-1 file is not "empty" and a CRLF one does not differ on every line (R17).
                com.editora.perf.OffFx.then(
                        "HistoryCoordinator.currentText",
                        () -> closedFileText(target, charsetRuleFor(target)),
                        read -> then.accept(read.or("")));
            }

            @Override
            public void revert(HistoryRevision revision, Runnable done) {
                restoreHistory(revision).whenComplete((result, failure) -> onFx(done));
            }

            @Override
            public void applyToLocalIfUnchanged(
                    Path target, @Nullable String expectedText, String newText, Consumer<Boolean> done) {
                diff.applyToLocalIfUnchangedAsync(target, expectedText, newText, applied -> {
                    if (!applied) {
                        host.setStatus(tr("status.diff.localStale"));
                    }
                    done.accept(applied);
                });
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

            @Override
            public boolean hasUnsavedEdits(Path target) {
                EditorBuffer b = host.activeBuffer();
                return b != null
                        && b.getPath() != null
                        && PathKeys.key(target).equals(PathKeys.key(b.getPath()))
                        && b.isDirty();
            }
        };
    }

    /** A closed file's text in the editor's form, as the revisions hold text. Off the FX thread. */
    static String closedFileText(Path file, @Nullable String charsetRule) throws IOException {
        String text = editorForm(decodeCaptured(Files.readAllBytes(file), charsetRule));
        return text == null ? "" : text;
    }

    /** Test seam: answers the "replace unsaved edits?" question instead of the dialog. */
    @Nullable
    Predicate<Path> confirmUnsavedRestore;

    private @Nullable EditorBuffer watchedBuffer; // the buffer whose edits are forwarded to the panel
    private org.reactfx.@Nullable Subscription watchedText;

    /** The File History tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    FileHistoryPanel panel() {
        if (panel == null) {
            panel = new FileHistoryPanel(historyActions());
            panel.setDiffSupport(diffSupport());
            refresh(); // show the active file's revisions: the refreshes before now had no panel to fill
        }
        return panel;
    }

    /**
     * Set by {@link #shutdown()}. A record submitted before the window closed still reports back afterwards —
     * at the latest from {@code HistoryService.shutdown()}, inside {@code Application.stop()} — and its
     * revision still belongs in the index; the window it would have refreshed is gone.
     */
    private boolean disposed;

    void shutdown() {
        disposed = true;
        watchEditorText(null);
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
        sweepIfDue();
        offerPendingLimits();
    }

    /** The services whose held-back limits are being previewed or asked about right now (FX thread). */
    private static final java.util.Set<HistoryService> OFFERING_LIMITS =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** Per service: the stricter limits the user was shown and declined, so no window asks about them again. */
    private static final Map<HistoryService, HistoryRetention.RetentionPolicy> DECLINED_LIMITS =
            new java.util.WeakHashMap<>();

    /** Test seam: answers the held-back-limits confirmation instead of the dialog. */
    @Nullable
    Predicate<HistoryRetention.Impact> confirmPendingLimits;

    /**
     * The settings hold a limit stricter than the one in force — it arrived without the confirmation the
     * Settings spinners and the {@code history.setMax*} commands ask for (a settings sync, an edited
     * {@code settings.json}). It stays held back, across restarts too; but held back in silence, the user
     * sees a number in Settings that is not the one being applied, and nothing ever asks. So ask, once: with
     * the same count of what would be deleted, after the window is up (the preview is computed off the FX
     * thread and the question is posted, never asked from inside startup). Declined, the previous limits stay
     * in force and the question is not repeated for these values.
     */
    void offerPendingLimits() {
        var s = host.settings();
        HistoryRetention.RetentionPolicy configured =
                policyOf(s.getHistoryMaxPerFile(), s.getHistoryMaxAgeDays(), s.getHistoryMaxTotalMb());
        if (disposed
                || !isEnabled()
                || !historyService.awaitsConfirmation(configured)
                || configured.equals(DECLINED_LIMITS.get(historyService))
                || !OFFERING_LIMITS.add(historyService)) {
            return;
        }
        HistoryRetention.RetentionPolicy inForce = retentionPolicy();
        historyService.previewTightening(indexSnapshot(), inForce, configured, System.currentTimeMillis(), impact -> {
            if (impact == null || disposed || !historyService.awaitsConfirmation(configured)) {
                OFFERING_LIMITS.remove(historyService);
                return;
            }
            if (impact.revisions() == 0) {
                OFFERING_LIMITS.remove(historyService);
                historyService.acknowledge(configured); // nothing to delete: nothing to ask
                sweepIfDue();
                return;
            }
            whenWindowShowing(() -> {
                OFFERING_LIMITS.remove(historyService);
                if (disposed || !historyService.awaitsConfirmation(configured)) {
                    return;
                }
                Predicate<HistoryRetention.Impact> asked = confirmPendingLimits;
                boolean apply = asked == null ? confirmTightening(impact, host.window(), true) : asked.test(impact);
                if (apply) {
                    historyService.acknowledge(configured);
                    sweepIfDue();
                } else {
                    DECLINED_LIMITS.put(historyService, configured);
                    host.setStatus(tr("status.history.limitsPending"));
                }
            });
        });
    }

    /** Runs {@code action} in a later FX turn, once this window is on screen (a dialog needs an owner that is). */
    private void whenWindowShowing(Runnable action) {
        javafx.stage.Window window = host.window();
        if (window == null || window.isShowing() || confirmPendingLimits != null) {
            Platform.runLater(action);
            return;
        }
        window.showingProperty().addListener(new javafx.beans.value.ChangeListener<>() {
            @Override
            public void changed(
                    javafx.beans.value.ObservableValue<? extends Boolean> property, Boolean was, Boolean showing) {
                if (Boolean.TRUE.equals(showing)) {
                    window.showingProperty().removeListener(this);
                    Platform.runLater(action);
                }
            }
        });
    }

    /**
     * The limits in force: the configured ones, except that a limit which became stricter without the user
     * confirming what it deletes stays at its previous value (see {@link HistoryService#effectivePolicy}).
     */
    private HistoryRetention.RetentionPolicy retentionPolicy() {
        var s = host.settings();
        HistoryRetention.RetentionPolicy configured =
                policyOf(s.getHistoryMaxPerFile(), s.getHistoryMaxAgeDays(), s.getHistoryMaxTotalMb());
        HistoryRetention.RetentionPolicy effective = historyService.effectivePolicy(configured);
        return effective != null ? effective : configured; // null only for a null configured policy
    }

    private static HistoryRetention.RetentionPolicy policyOf(int maxPerFile, int maxAgeDays, int maxTotalMb) {
        long maxAgeMillis = maxAgeDays > 0 ? maxAgeDays * 86_400_000L : 0;
        return new HistoryRetention.RetentionPolicy(
                maxPerFile, maxAgeMillis, (long) Math.max(0, maxTotalMb) * 1024L * 1024L);
    }

    /** The newest {@link #changeLimits} request; an older one whose preview arrives late is dropped. */
    private int limitRequests;

    /**
     * Sets the three retention limits — the one way the Settings spinners and the {@code history.setMax*}
     * commands change them. Looser limits are written at once. Stricter ones delete revisions, in every
     * project, the moment they are applied, and stepping a spinner back does not bring them back: so the
     * settings are left alone until it is known what would go, and when that is anything at all the user is
     * told how many revisions from how many files and must confirm. {@code onDone} gets {@code true} once the
     * settings hold the new values (the caller then saves and applies them, which runs the sweep) and
     * {@code false} when the change was declined and nothing was written.
     */
    void changeLimits(
            int maxPerFile, int maxAgeDays, int maxTotalMb, javafx.stage.Window owner, Consumer<Boolean> onDone) {
        int request = ++limitRequests;
        HistoryRetention.RetentionPolicy current = retentionPolicy();
        HistoryRetention.RetentionPolicy candidate = policyOf(maxPerFile, maxAgeDays, maxTotalMb);
        Runnable commit = () -> {
            var s = host.settings();
            s.setHistoryMaxPerFile(maxPerFile);
            s.setHistoryMaxAgeDays(maxAgeDays);
            s.setHistoryMaxTotalMb(maxTotalMb);
            historyService.acknowledge(candidate);
            onDone.accept(true);
        };
        if (!HistoryRetention.tightens(current, candidate)) {
            commit.run();
            return;
        }
        historyService.previewTightening(indexSnapshot(), current, candidate, System.currentTimeMillis(), impact -> {
            if (request != limitRequests) {
                return; // the control moved on while this was computed; its latest value is being checked
            }
            if (impact != null && (impact.revisions() == 0 || confirmTightening(impact, owner))) {
                commit.run();
            } else {
                onDone.accept(false);
            }
        });
    }

    /** One of the three retention limits, for {@link #changeLimit}. */
    enum Limit {
        MAX_PER_FILE,
        MAX_AGE_DAYS,
        MAX_TOTAL_MB
    }

    /**
     * A {@code history.setMax*} command: sets one limit through {@link #changeLimits}, then saves, applies and
     * reports it as {@code title} — or says that nothing changed when the user declined the deletion.
     */
    void changeLimit(String title, Limit limit, int value) {
        var s = host.settings();
        changeLimits(
                limit == Limit.MAX_PER_FILE ? value : s.getHistoryMaxPerFile(),
                limit == Limit.MAX_AGE_DAYS ? value : s.getHistoryMaxAgeDays(),
                limit == Limit.MAX_TOTAL_MB ? value : s.getHistoryMaxTotalMb(),
                host.window(),
                applied -> {
                    if (!applied) {
                        host.setStatus(tr("status.history.limitsUnchanged"));
                        return;
                    }
                    host.requestSave();
                    applySupport();
                    host.syncSettingsWindow();
                    host.setStatus(tr("status.settingChanged", title, Integer.toString(value)));
                });
    }

    private boolean confirmTightening(HistoryRetention.Impact impact, javafx.stage.Window owner) {
        return confirmTightening(impact, owner, false);
    }

    private boolean confirmTightening(HistoryRetention.Impact impact, javafx.stage.Window owner, boolean pending) {
        return Confirm.ask(
                owner != null && owner.isShowing() ? owner : host.window(),
                limitsQuestion(impact.revisions(), impact.files(), pending));
    }

    /**
     * The words of the question before tighter history limits delete {@code revisions} revisions of {@code files}
     * files; {@code pending} when the limits were set earlier and are asked about now, which it says first.
     */
    static Confirm.Question limitsQuestion(int revisions, int files, boolean pending) {
        return new Confirm.Question(
                tr("dialog.history.limits.title"),
                null,
                (pending ? tr("dialog.history.limits.pending") + "\n\n" : "")
                        + tr("dialog.history.limits.confirm", revisions, files),
                tr("dialog.history.purge.button"));
    }

    /**
     * The words of the question before every revision of one file is deleted: how many, the file's name, and how
     * many other files ({@code sharing}) keep text they share with it.
     */
    static Confirm.Question purgeFileQuestion(int count, String fileName, int sharing) {
        String question = tr("dialog.history.purgeFile.confirm", count, fileName);
        if (sharing > 0) {
            question += "\n\n" + tr("dialog.history.purgeFile.shared", sharing);
        }
        return purgeQuestion(question);
    }

    /** The words of the question before every revision of the project's history is deleted. */
    static Confirm.Question purgeProjectQuestion(int count, int files) {
        return purgeQuestion(tr("dialog.history.purgeProject.confirm", count, files));
    }

    private static Confirm.Question purgeQuestion(String message) {
        return new Confirm.Question(tr("dialog.history.purge.title"), null, message, tr("dialog.history.purge.button"));
    }

    /** The words of the question before a restore replaces the existing file {@code fileName}. */
    static Confirm.Question restoreOverwriteQuestion(String fileName) {
        return new Confirm.Question(
                tr("history.menu.restore"),
                null,
                tr("history.restoreOverwrite", fileName),
                tr("confirm.action.overwrite"));
    }

    /** A private copy of the whole index, for work on the history worker. */
    private Map<String, Map<String, List<HistoryRevision>>> indexSnapshot() {
        Map<String, Map<String, List<HistoryRevision>>> snapshot = new LinkedHashMap<>();
        ops.historyByProject().forEach((project, files) -> {
            Map<String, List<HistoryRevision>> copy = new LinkedHashMap<>();
            files.forEach((file, revisions) -> copy.put(file, List.copyOf(revisions)));
            snapshot.put(project, copy);
        });
        return snapshot;
    }

    /**
     * Applies the retention limits to the <em>whole</em> index once per application start (and again when
     * the limits change). Recording a revision only prunes that one file, so without this a file that is
     * never saved again kept its history — and its content on disk — indefinitely. The policy is evaluated
     * on the history worker over a private copy of the index; only the removal of what it evicted happens
     * here on the FX thread, against the index as it is by then.
     */
    void sweepIfDue() {
        HistoryRetention.RetentionPolicy policy = retentionPolicy();
        if (!isEnabled() || !historyService.claimSweep(policy)) {
            return;
        }
        historyService.sweep(indexSnapshot(), policy, System.currentTimeMillis(), this::removeEvicted);
    }

    /** Subtracts swept-out revisions from the live index, drops emptied files, persists, and refreshes. */
    private void removeEvicted(Map<String, Map<String, List<HistoryRevision>>> evicted) {
        boolean changed = false;
        for (var project : evicted.entrySet()) {
            Map<String, List<HistoryRevision>> bucket = ops.historyByProject().get(project.getKey());
            if (bucket == null) {
                continue;
            }
            for (var file : project.getValue().entrySet()) {
                List<HistoryRevision> current = bucket.get(file.getKey());
                if (current == null) {
                    continue;
                }
                List<HistoryRevision> kept = new ArrayList<>(current);
                for (HistoryRevision gone : file.getValue()) {
                    changed |= kept.remove(gone);
                }
                if (kept.isEmpty()) {
                    bucket.remove(file.getKey());
                } else {
                    bucket.put(file.getKey(), kept);
                }
            }
        }
        if (changed) {
            publish();
            refresh();
        }
    }

    // --- purge ---------------------------------------------------------------------------------------

    /**
     * Deletes every recorded revision of the active file after a danger-styled confirmation. Local History
     * keeps copies of what was saved — including a secret pasted into a file and then removed — so there has
     * to be a way to make it forget. Works while the feature is switched off: turning history off must not
     * strand what it already stored.
     */
    void purgeActiveFile() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !host.isLocalBuffer(b)) {
            host.setStatus(tr("status.history.noFile"));
            return;
        }
        purgeFile(historyKey(b.getPath()));
    }

    /**
     * Deletes every recorded revision of the file at {@code key} — also one that no longer exists, from its
     * row in the folder view: the copy taken when a file is deleted is kept for months, and without this the
     * only way to make Local History forget a deleted file was to purge its whole project.
     */
    void purgeFile(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        int count = revisionsInEveryProject(key);
        if (count == 0) {
            host.setStatus(tr("status.history.nothingToPurge"));
            return;
        }
        Path name = Path.of(key).getFileName();
        String fileName = name == null ? key : name.toString();
        // Save As gives the copy the history of the file it came from, and bodies are stored once by content:
        // deleting this file's rows does not take the text away from those. The question says so, and offers to
        // take it from them too (B10) — someone purging a pasted secret wants it gone everywhere.
        int sharing = filesSharingSnapshots(key);
        boolean everywhere;
        if (sharing == 0) {
            Confirm.Question question = purgeFileQuestion(count, fileName, 0);
            if (!(confirmPurgeAnswer == null ? confirmPurge(question) : confirmPurgeAnswer.test(question.message()))) {
                return;
            }
            everywhere = false;
        } else {
            int answer = askSharedPurge(purgeSharedFileChoice(count, fileName, sharing));
            if (answer < 0) {
                return;
            }
            everywhere = answer == 1;
        }
        // Re-read after the modal dialog: revisions recorded while it was open are purged too. Every
        // project's bucket, not only this window's: the same file recorded from a No-Project window or an
        // overlapping project kept its revisions (and its content on disk) while the status said "purged".
        java.util.Set<String> bodies = new java.util.HashSet<>();
        revisionsOf(key).forEach(r -> bodies.add(r.sha256()));
        int removed = 0;
        for (Map<String, List<HistoryRevision>> bucket : everyBucket()) {
            List<HistoryRevision> gone = bucket.remove(key);
            removed += gone == null ? 0 : gone.size();
            if (everywhere) {
                removed += removeRowsWithBodies(bucket, bodies);
            }
        }
        finishPurge(removed, bodies);
    }

    /** Removes from {@code bucket} every row whose body is one of {@code bodies}; drops emptied files. */
    private static int removeRowsWithBodies(Map<String, List<HistoryRevision>> bucket, java.util.Set<String> bodies) {
        int removed = 0;
        for (var it = bucket.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<HistoryRevision>> file = it.next();
            List<HistoryRevision> kept = file.getValue().stream()
                    .filter(r -> !bodies.contains(r.sha256()))
                    .toList();
            int dropped = file.getValue().size() - kept.size();
            if (dropped == 0) {
                continue;
            }
            removed += dropped;
            if (kept.isEmpty()) {
                it.remove();
            } else {
                file.setValue(new ArrayList<>(kept));
            }
        }
        return removed;
    }

    /**
     * The question before one file's history is deleted when other files share some of its snapshots: its two
     * answers are this file only (0) and those snapshots everywhere (1).
     */
    static Confirm.Choice purgeSharedFileChoice(int count, String fileName, int sharing) {
        return Confirm.choice(
                tr("dialog.history.purge.title"),
                null,
                tr("dialog.history.purgeFile.confirm", count, fileName) + "\n\n"
                        + tr("dialog.history.purgeFile.sharedChoice", sharing),
                tr("dialog.history.purge.button"),
                tr("dialog.history.purge.everywhere"));
    }

    /** Test seam: answers {@link #purgeSharedFileChoice} (given its text) — 0, 1, or -1 for Cancel. */
    java.util.function.@Nullable ToIntFunction<String> sharedPurgeAnswer;

    private int askSharedPurge(Confirm.Choice choice) {
        if (sharedPurgeAnswer != null) {
            return sharedPurgeAnswer.applyAsInt(String.valueOf(choice.message()));
        }
        if (confirmPurgeAnswer != null) { // a test that answers yes or no: this file only
            return confirmPurgeAnswer.test(String.valueOf(choice.message())) ? 0 : -1;
        }
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(host.window());
        alert.setTitle(choice.title());
        alert.setHeaderText(choice.header());
        alert.setContentText(choice.message());
        ButtonType thisFile = new ButtonType(choice.answer(0), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType everywhere = new ButtonType(choice.answer(1), javafx.scene.control.ButtonBar.ButtonData.OTHER);
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(thisFile, everywhere, cancel);
        Dialogs.destructive(alert, thisFile);
        Dialogs.destructive(alert, everywhere);
        Dialogs.defaultTo(alert, cancel);
        ButtonType chosen = Dialogs.showAndWait(alert).orElse(cancel);
        return chosen == thisFile ? 0 : chosen == everywhere ? 1 : -1;
    }

    /** Test seam: answers the purge confirmation (given its text) instead of the dialog. */
    @Nullable
    Predicate<String> confirmPurgeAnswer;

    /** How many <em>other</em> files, in any project, have a revision with a body one of {@code key}'s has. */
    private int filesSharingSnapshots(String key) {
        java.util.Set<String> bodies = new java.util.HashSet<>();
        for (HistoryRevision revision : revisionsOf(key)) {
            bodies.add(revision.sha256());
        }
        java.util.Set<String> sharing = new java.util.HashSet<>();
        for (Map<String, List<HistoryRevision>> bucket : everyBucket()) {
            for (Map.Entry<String, List<HistoryRevision>> file : bucket.entrySet()) {
                if (!file.getKey().equals(key) && file.getValue().stream().anyMatch(r -> bodies.contains(r.sha256()))) {
                    sharing.add(file.getKey());
                }
            }
        }
        return sharing.size();
    }

    /** How many revisions of {@code key} are recorded, in this window's bucket and every other project's. */
    private int revisionsInEveryProject(String key) {
        Map<String, List<HistoryRevision>> own = ops.historyMap();
        int count = own.getOrDefault(key, List.of()).size();
        for (Map<String, List<HistoryRevision>> bucket : ops.historyByProject().values()) {
            if (bucket != own) {
                count += bucket.getOrDefault(key, List.of()).size();
            }
        }
        return count;
    }

    /**
     * The files "Delete History of Project" deletes: every file of this window's bucket and, with a project,
     * every file at or under its root in any bucket — reads merge the buckets, so a file of the project recorded
     * from a No-Project or overlapping-project window would otherwise stay listed after the purge (R8).
     */
    private java.util.Set<String> projectKeys() {
        java.util.Set<String> keys =
                new java.util.LinkedHashSet<>(ops.historyMap().keySet());
        Path root = ops.projectRoot();
        if (root != null && Vfs.isLocal(root)) {
            String rootKey = historyKey(root);
            String separator = root.getFileSystem().getSeparator();
            for (Map<String, List<HistoryRevision>> bucket : everyBucket()) {
                for (String key : bucket.keySet()) {
                    if (key.equals(rootKey) || HistoryMoves.renamed(key, rootKey, rootKey, separator) != null) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    /** Deletes every recorded revision of every file in the active project's history, after confirmation. */
    void purgeProject() {
        java.util.Set<String> keys = projectKeys();
        int count = 0;
        for (String key : keys) {
            count += revisionsInEveryProject(key);
        }
        if (count == 0) {
            host.setStatus(tr("status.history.nothingToPurge"));
            return;
        }
        Confirm.Question question = purgeProjectQuestion(count, keys.size());
        if (!(confirmPurgeAnswer == null ? confirmPurge(question) : confirmPurgeAnswer.test(question.message()))) {
            return;
        }
        // Re-read after the modal dialog, as purgeFile does.
        keys = projectKeys();
        java.util.Set<String> bodies = new java.util.HashSet<>();
        int removed = 0;
        for (Map<String, List<HistoryRevision>> bucket : everyBucket()) {
            for (String key : keys) {
                List<HistoryRevision> gone = bucket.remove(key);
                if (gone != null) {
                    removed += gone.size();
                    gone.forEach(r -> bodies.add(r.sha256()));
                }
            }
        }
        finishPurge(removed, bodies);
    }

    private void finishPurge(int removed, java.util.Set<String> purgedBodies) {
        // The content must leave the disk now, not at the next throttled collection.
        historyService.requestGc();
        publish();
        refresh();
        // The rows are gone from the index either way. A body another row still uses stays, as the question said;
        // the rest leave the disk with the collection that follows — unless something holds them, which the user
        // purging a pasted secret has to be told, with the real reason (E4).
        java.util.Set<String> orphaned = new java.util.HashSet<>(purgedBodies);
        for (Map<String, List<HistoryRevision>> bucket : everyBucket()) {
            for (List<HistoryRevision> rows : bucket.values()) {
                rows.forEach(r -> orphaned.remove(r.sha256()));
            }
        }
        HistoryPurgeHold hold = orphaned.isEmpty() ? HistoryPurgeHold.NONE : ops.purgeHold(orphaned);
        host.setStatus(tr(purgedStatusKey(hold), removed));
    }

    /** The catalog key of the status after a purge whose bodies are held by {@code hold}. */
    static String purgedStatusKey(HistoryPurgeHold hold) {
        return switch (hold) {
            case NONE -> "status.history.purged";
            case OTHER_PROCESS -> "status.history.purgedHeld.otherProcess";
            case BACKUP -> "status.history.purgedHeld.backup";
            case INDEX_UNTRUSTED -> "status.history.purgedHeld.indexUntrusted";
        };
    }

    private boolean confirmPurge(Confirm.Question question) {
        return Confirm.ask(host.window(), question);
    }

    /** Sets the history tool window's availability (local file + feature on) and reloads its list. */
    void refresh() {
        EditorBuffer b = host.activeBuffer();
        Path activeFile = b == null ? null : b.getPath();
        boolean available = isEnabled() && b != null && activeFile != null && host.isLocalBuffer(b);
        FileHistoryPanel shownPanel = panel;
        Path folder = isEnabled() && shownPanel != null ? shownPanel.folderShown() : null;
        if (shownPanel != null && folder != null) {
            // The folder view is not "the active file's history": restoring one deleted file opens it, a save
            // is recorded, a tab is clicked — and the listing the user was working through must still be
            // there, with what just changed in it. It is left with the panel's back button.
            List<FileHistoryPanel.FileGroup> groups = folderGroups(folder);
            if (!groups.isEmpty()) {
                ops.setToolWindowAvailable(true);
                watchEditorText(available ? b : null);
                if (!groups.equals(folderGroupsShown)) {
                    folderGroupsShown = groups;
                    shownPanel.setFolderHistory(folder, groups);
                }
                return;
            }
        }
        folderGroupsShown = null;
        boolean reopen = false;
        if (!available) {
            // Closed by a tab with no local file (a new untitled tab, a diff, Welcome), not by the user: it comes
            // back with the next file (U4). Turning the feature off is not that.
            reopenOnNextFile = isEnabled() && (reopenOnNextFile || ops.toolWindowOpen());
        } else if (reopenOnNextFile) {
            reopenOnNextFile = false;
            reopen = !ops.toolWindowOpen();
        }
        ops.setToolWindowAvailable(available);
        if (reopen) {
            ops.reopenToolWindow();
        }
        watchEditorText(available ? b : null);
        if (panel == null) {
            return; // nothing to fill yet; panel() refreshes when it builds
        }
        if (available && activeFile != null) {
            panel.setRevisions(
                    revisionsOf(historyKey(activeFile)),
                    activeFile.getFileName().toString(),
                    activeFile);
        } else {
            panel.setRevisions(List.of(), null, null);
        }
    }

    /** Set when the tool window closed because the active tab has no local file; it reopens on the next file. */
    private boolean reopenOnNextFile;

    /** What the folder view lists now; a refresh that finds the same leaves the tree (and its selection) alone. */
    private @Nullable List<FileHistoryPanel.FileGroup> folderGroupsShown;

    /** This window's bucket first, then every other project's: the order in which they are read and merged. */
    private List<Map<String, List<HistoryRevision>>> everyBucket() {
        Map<String, List<HistoryRevision>> own = ops.historyMap();
        List<Map<String, List<HistoryRevision>>> all = new ArrayList<>();
        all.add(own);
        for (Map<String, List<HistoryRevision>> bucket : ops.historyByProject().values()) {
            if (bucket != own && bucket != null) {
                all.add(bucket);
            }
        }
        return all;
    }

    /**
     * Every recorded revision of the file at {@code key}, newest first, whichever window recorded it. A
     * revision is filed under the project of the window that saved, so a file saved from a No-Project window
     * and opened in its project's window used to show no history at all there.
     */
    private List<HistoryRevision> revisionsOf(String key) {
        return mergedRevisions(everyBucket(), key);
    }

    /** {@link #revisionsOf} over {@code buckets}: one list, newest first, a row present in several listed once. */
    static List<HistoryRevision> mergedRevisions(List<Map<String, List<HistoryRevision>>> buckets, String key) {
        List<HistoryRevision> only = null;
        java.util.Set<HistoryRevision> all = null;
        for (Map<String, List<HistoryRevision>> bucket : buckets) {
            List<HistoryRevision> list = bucket.get(key);
            if (list == null || list.isEmpty()) {
                continue;
            }
            if (only == null && all == null) {
                only = list; // the usual case: one bucket knows the file, and its list is handed on as it is
                continue;
            }
            if (all == null) {
                all = new java.util.LinkedHashSet<>(only);
            }
            all.addAll(list);
        }
        if (all == null) {
            return only == null ? List.of() : only;
        }
        List<HistoryRevision> merged = new ArrayList<>(all);
        merged.sort(
                java.util.Comparator.comparingLong(HistoryRevision::timestamp).reversed()); // stable
        return merged;
    }

    /** Every file with history, each with its revisions from every bucket (see {@link #revisionsOf}). */
    private Map<String, List<HistoryRevision>> mergedIndex() {
        List<Map<String, List<HistoryRevision>>> buckets = everyBucket();
        if (buckets.size() == 1) {
            return buckets.get(0);
        }
        Map<String, List<HistoryRevision>> merged = new LinkedHashMap<>();
        for (Map<String, List<HistoryRevision>> bucket : buckets) {
            for (String key : bucket.keySet()) {
                merged.computeIfAbsent(key, file -> mergedRevisions(buckets, file));
            }
        }
        return merged;
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
        if (!isEnabled()
                || buffer == null
                || buffer.getPath() == null
                || !host.isLocalBuffer(buffer)
                || buffer.encryption().isEncrypted()
                || holdsPart(buffer)) {
            return;
        }
        recordFor(buffer.getPath(), buffer.getContent(), reason, "", false, null);
    }

    /**
     * Whether {@code buffer} holds only part of its file — a truncated load, or a followed log whose head was
     * trimmed. Its text is not a revision of the file: restored, it would write the slice over the whole (R4).
     */
    static boolean holdsPart(EditorBuffer buffer) {
        return buffer.isTruncatedLoad() || buffer.isLogTrimmed();
    }

    /** Records caller-captured content, used before a closed-file bulk replacement rewrites the file. */
    void record(@Nullable Path file, String content, String reason) {
        recordFor(file, content, reason, "", false, null);
    }

    /**
     * Records what a save wrote, with how it was written ({@code encoding}: charset, byte-order mark, line
     * ending): a file deleted outside Editora comes back from such a row as the bytes it was, not as UTF-8 with
     * line feeds (R14).
     */
    void recordSaved(Path file, String content, String reason, @Nullable Encoding encoding) {
        recordFor(file, content, reason, "", false, encoding, false, null);
    }

    /** Records and waits for the index snapshot to become durable before acknowledging the caller. */
    void recordDurably(
            Path file, @Nullable String content, String reason, java.util.function.Consumer<Boolean> completion) {
        recordFor(file, content, reason, "", false, completion);
    }

    /** How long {@link #recordSafetyCopy} lets the FX thread wait for the previous text to reach the disk. */
    static final long SAFETY_COPY_TIMEOUT_MILLIS = 15_000;

    /** Test seam: the wait {@link #recordSafetyCopy} allows. */
    long safetyCopyTimeoutMillis = SAFETY_COPY_TIMEOUT_MILLIS;

    /**
     * Stores {@code buffer}'s current text — unsaved edits included — as a labelled (and therefore
     * retention-protected) revision, and returns only once its content is on disk and the revision is in the
     * index. This is the recovery copy {@link NoUndoGuard} requires before a bulk edit in a buffer that has no
     * undo; anything other than {@link NoUndoGuard.Copy#STORED} means there is none and the edit must not happen.
     * The FX thread waits for the body's write, bounded, on purpose: the edit comes after it, and a copy that
     * was only queued let the edit go ahead when the write then failed (R1).
     */
    NoUndoGuard.Copy recordSafetyCopy(EditorBuffer buffer, String label) {
        if (!isEnabled()) {
            return NoUndoGuard.Copy.HISTORY_OFF;
        }
        Path file = buffer == null ? null : buffer.getPath();
        if (buffer == null
                || file == null
                || !host.isLocalBuffer(buffer)
                || buffer.encryption().isEncrypted()) {
            return NoUndoGuard.Copy.NO_LOCAL_FILE; // an encrypted file's text never goes into Local History
        }
        if (holdsPart(buffer)) {
            return NoUndoGuard.Copy.FAILED; // a slice of the file is no copy of it
        }
        String key = historyKey(file);
        Map<String, List<HistoryRevision>> bucket = ops.historyMap();
        var policy = retentionPolicy();
        long now = System.currentTimeMillis();
        boolean[] stored = {false};
        historyService.snapshotBlocking(
                file,
                buffer.getContent(),
                HistoryRevision.REASON_LABEL,
                label,
                now,
                safetyCopyTimeoutMillis,
                outcome -> {
                    if (outcome.successful() && outcome.revision() != null) {
                        applyRecorded(bucket, key, HistoryMoves.at(key, outcome.revision()), policy, now, null);
                        stored[0] = true;
                    }
                });
        if (stored[0]) {
            refresh();
        }
        return stored[0] ? NoUndoGuard.Copy.STORED : NoUndoGuard.Copy.FAILED;
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
            @Nullable Path file,
            @Nullable String content,
            String reason,
            String label,
            boolean force,
            java.util.function.@Nullable Consumer<Boolean> durableCompletion) {
        recordFor(file, content, reason, label, force, null, false, durableCompletion);
    }

    /** How a captured file was written, kept on its pre-delete revision (see {@link HistoryRevision}). */
    record Encoding(String charset, boolean bom, String lineEnding) {

        /** The encoding of {@code bytes}, read the way the editor reads a file under {@code editorConfigCharset}. */
        static Encoding of(byte[] bytes, @Nullable String editorConfigCharset) {
            return of(bytes, DiffSideText.decodeRaw(bytes, editorConfigCharset, null));
        }

        private static Encoding of(byte[] bytes, EditorConfigCharset.Decoded decoded) {
            return new Encoding(
                    decoded.charset(),
                    EditorConfigCharset.detectByBom(bytes) != null,
                    LineEndings.dominant(decoded.text()));
        }

        HistoryRevision on(HistoryRevision revision) {
            return revision.withEncoding(charset, bom, lineEnding);
        }
    }

    private void recordFor(
            @Nullable Path file,
            @Nullable String content,
            String reason,
            String label,
            boolean force,
            @Nullable Encoding encoding,
            boolean evenWhenOff,
            java.util.function.@Nullable Consumer<Boolean> durableCompletion) {
        // An encrypted file has no Local History (ADR 0017): its revisions would be its text, unencrypted. Nothing
        // is kept then by contract, as with the feature off; the callers proceed on that.
        if (file == null
                || content == null
                || !com.editora.vfs.Vfs.isLocal(file)
                || !(evenWhenOff || isEnabled())
                || encrypted(file, content)) {
            if (durableCompletion != null) {
                durableCompletion.accept(true);
            }
            return;
        }
        String submittedKey = historyKey(file);
        // The bucket of the project this window has now: a project switch before the revision lands must not
        // file it under the next project (E-L1).
        Map<String, List<HistoryRevision>> bucket = ops.historyMap();
        List<HistoryRevision> existing = bucket.getOrDefault(submittedKey, List.of());
        var policy = retentionPolicy();
        long now = System.currentTimeMillis();
        int renamesSeen = renames.size();
        historyService.snapshotWithOutcome(file, content, reason, label, force, existing, policy, now, outcome -> {
            // The file may have been renamed while its content was hashed and stored: the revision belongs
            // to the file, so it lands under the name the file has now, not the one it had when submitted.
            String key = keyAfterRenamesSince(renamesSeen, submittedKey);
            boolean adopted = outcome.successful() && adoptSaveAsOrigin(key);
            HistoryRevision moved = outcome.revision() == null ? null : HistoryMoves.at(key, outcome.revision());
            HistoryRevision rev = moved == null || encoding == null ? moved : encoding.on(moved);
            boolean listed = rev != null && applyRecorded(bucket, key, rev, policy, now, durableCompletion);
            if (!listed) {
                if (adopted) {
                    publish();
                }
                if (rev == null && durableCompletion != null) {
                    durableCompletion.accept(outcome.successful());
                }
            }
            if (!outcome.successful()) {
                warnRecordFailed(file);
            }
            if (disposed) {
                return; // the window is gone: the index has the revision, there is no panel to tell
            }
            EditorBuffer active = host.activeBuffer();
            if ((listed || adopted)
                    && ((panel != null && panel.folderShown() != null)
                            || (active != null
                                    && active.getPath() != null
                                    && historyKey(active.getPath()).equals(key)))) {
                refresh();
            }
        });
    }

    /**
     * Whether {@code content} of {@code file} is an encrypted file's, which Local History never keeps (ADR 0017).
     * Decided by what the file is, not its name alone (R2): a {@code .asc} file is as often a public key, a
     * signature or AsciiDoc, which open as ordinary text and are recorded like any other. A name without an
     * encryption extension is never encrypted; with one, an open buffer says whether it was decrypted, and a
     * closed file's content whether it is an OpenPGP message.
     */
    private boolean encrypted(Path file, String content) {
        String name = String.valueOf(file.getFileName());
        if (com.editora.text.EncryptedNames.extensionOf(name) == null) {
            return false;
        }
        EditorBuffer open = ops.openBufferAnywhere(file);
        if (open != null && open.encryption().isEncrypted()) {
            return true;
        }
        String head = content.substring(0, Math.min(content.length(), 1024));
        com.editora.crypto.EncryptedFiles.Kind kind =
                com.editora.crypto.EncryptedFiles.sniff(head.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        return kind == com.editora.crypto.EncryptedFiles.Kind.ARMORED_MESSAGE
                || kind == com.editora.crypto.EncryptedFiles.Kind.BINARY_MESSAGE
                || (open == null && kind == com.editora.crypto.EncryptedFiles.Kind.NONE && !looksLikeText(head));
    }

    /** No control characters other than whitespace: what a decoded OpenPGP binary packet never is. */
    private static boolean looksLikeText(String head) {
        for (int i = 0; i < head.length(); i++) {
            char c = head.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' && c != '\f') {
                return false;
            }
        }
        return true;
    }

    /** Whether this window has already said that Local History could not record something. */
    private boolean recordFailureReported;

    /**
     * A revision could not be stored (a full disk, a folder that cannot be written). A save that follows goes
     * through all the same, so without a word here Local History stopped for the rest of the session while
     * the status bar went on saying "Saved". Said once per window: the cause rarely goes away by itself, and
     * with auto-save on it would otherwise be repeated every few seconds. The log has every occurrence.
     */
    private void warnRecordFailed(Path file) {
        if (recordFailureReported || disposed) {
            return;
        }
        recordFailureReported = true;
        host.setError(tr("status.history.recordFailed", file.getFileName()));
    }

    /** Persists the index and tells the other windows it changed. */
    private void publish() {
        ops.saveHistory();
        ops.historyChanged();
    }

    private void publish(java.util.function.@Nullable Consumer<Boolean> completion) {
        if (completion == null) {
            publish();
            return;
        }
        ops.saveHistory(completion);
        ops.historyChanged();
    }

    /**
     * Per file: the length and CRC-32 of what this window's last save wrote there. Read and written on the
     * save worker, so a later save can tell "the bytes I am replacing are my own" without hashing them twice.
     */
    private final Map<String, Long> lastSavedStamps = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The last tab of {@code file} closed: its stamp goes, or the map grows by one entry per path ever saved in
     * the window. If the file is opened and saved again, that save records what it replaces as a baseline
     * revision — which the history worker drops when it equals the newest revision, as our own last save does.
     */
    void pathClosed(Path file) {
        if (file != null) {
            lastSavedStamps.remove(historyKey(file));
        }
    }

    /** Paths with a recorded last-save stamp (tests). */
    int savedStampCount() {
        return lastSavedStamps.size();
    }

    private static long stamp(byte[] bytes) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 0, bytes.length);
        return ((long) bytes.length << 32) | crc.getValue();
    }

    /**
     * A save has just replaced {@code replaced} (the bytes that were on disk; {@code null} for a new file)
     * with {@code written}. Called from the save worker, right after the write committed and before the save
     * is acknowledged on the FX thread.
     *
     * <p>Local History records what a save <em>wrote</em>. What the first save of a session <em>replaced</em>
     * — the file as it was opened, or as Git, a formatter or another editor left it — was in no revision, so
     * once the tab's undo history was gone it could not be brought back. Those bytes are recorded here, as an
     * {@link HistoryRevision#REASON_EXTERNAL external} revision ordered before the save's own, when they are
     * not this window's previous save of the file: the first save of a path in a session, and any later one
     * that finds the file changed underneath. The usual save — replacing our own bytes — costs one CRC of
     * each side and records nothing; the decode and hash of a capture happen once, and a body equal to the
     * newest revision is dropped by the history worker.
     *
     * <p>The bytes were already read by the save for its conflict check: nothing is read from disk here.
     */
    void saveReplaced(@Nullable Path target, byte @Nullable [] replaced, byte[] written) {
        if (target == null || written == null || !Vfs.isLocal(target)) {
            return;
        }
        Long previous = lastSavedStamps.put(historyKey(target), stamp(written));
        // No size cap (R5): the save has the bytes in hand already, and from 16 MB the buffer has no undo, so what
        // the first save replaces would otherwise be in neither history nor undo.
        if (replaced == null
                || replaced.length == 0 // an empty file has no text to bring back
                || (previous != null && previous >>> 32 == replaced.length && previous == stamp(replaced))) {
            return;
        }
        // What the first save of a session replaces is the file as it was before the session; what a later
        // one replaces, when it is not this window's own save, was written by something else meanwhile.
        String reason = previous == null ? REASON_BASELINE : HistoryRevision.REASON_EXTERNAL;
        // Decoded here, on the save worker: the charset rules are looked at on this thread (on the FX thread a file
        // nothing has looked at yet answers "no rules"), and a large file's decode costs the FX thread nothing.
        String charsetRule = charsetRuleFor(target);
        if (isBinary(replaced, charsetRule)) {
            return;
        }
        String text = LineEndings.toLf(decodeCaptured(replaced, charsetRule));
        Platform.runLater(() -> recordReplaced(target, text, reason));
    }

    /** FX half of {@link #saveReplaced}: the same text contract as a pre-delete capture, in the editor's form. */
    private void recordReplaced(Path file, String text, String reason) {
        if (isEnabled()) {
            recordFor(file, text, reason, "", false, null);
        }
    }

    /**
     * Records a closed file as Replace in Files found it, before it is rewritten, and reports once that is
     * durable. The text arrives as it was read from disk; the index holds the editor's form of a text
     * ({@code \n} only, no byte-order mark), so that an equal save is recognised as the same content.
     */
    void recordBeforeReplace(Path file, String content, java.util.function.Consumer<Boolean> completion) {
        recordBeforeReplace(file, content, REASON_BEFORE_REPLACE, completion);
    }

    /**
     * As {@link #recordBeforeReplace(Path, String, java.util.function.Consumer)}, under {@code reason} — a
     * Markdown link update records {@link HistoryRevision#REASON_LINK_UPDATE}, so its row says what rewrote the
     * file (F3).
     */
    void recordBeforeReplace(
            Path file, String content, String reason, java.util.function.Consumer<Boolean> completion) {
        recordFor(file, editorForm(content), reason, "", false, completion);
    }

    /** {@code text} as the editor holds it: line feeds only, without a leading byte-order mark. */
    static @Nullable String editorForm(String text) {
        if (text == null) {
            return null;
        }
        String lf = LineEndings.toLf(text);
        return lf.startsWith("\uFEFF") ? lf.substring(1) : lf;
    }

    /** A NUL byte marks a binary file — except in UTF-16 text, where every ASCII character has one. */
    private static boolean isBinary(byte[] bytes, @Nullable String charsetRule) {
        if (EditorConfigCharset.resolveName(bytes, charsetRule).startsWith("utf-16")) {
            return false;
        }
        for (byte b : bytes) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }

    /** One rename this window was told about; {@link #renames} keeps them in order. */
    private record Rename(String oldKey, String newKey, String separator) {}

    /** Renames seen so far. A record in flight replays the ones that happened after it was submitted. */
    private final List<Rename> renames = new ArrayList<>();

    /** Save As targets whose first save has not been recorded yet → the file they were saved from. */
    private final Map<String, String> saveAsOrigins = new java.util.HashMap<>();

    private String keyAfterRenamesSince(int seen, String key) {
        for (int i = Math.min(seen, renames.size()); i < renames.size(); i++) {
            Rename rename = renames.get(i);
            String renamed = HistoryMoves.renamed(key, rename.oldKey(), rename.newKey(), rename.separator());
            key = renamed == null ? key : renamed;
        }
        return key;
    }

    /**
     * A file or folder was renamed or moved ({@code old → target}) inside Editora: the history recorded
     * under the old path follows it, in every project's bucket — the index is keyed by path, so it would
     * otherwise be orphaned and the file would start again with none. Runs whether or not the feature is
     * on (the index must not go stale while it is off). The move is one step on the FX thread and drops no
     * revision, so a blob collection can only ever see an index that references every body.
     */
    void pathRenamed(Path old, Path target) {
        if (old == null || target == null || !Vfs.isLocal(old) || !Vfs.isLocal(target)) {
            return;
        }
        Rename rename = new Rename(
                historyKey(old), historyKey(target), old.getFileSystem().getSeparator());
        if (rename.oldKey().equals(rename.newKey())) {
            return;
        }
        renames.add(rename);
        if (HistoryMoves.rename(ops.historyByProject(), rename.oldKey(), rename.newKey(), rename.separator())) {
            publish();
            refresh();
        }
    }

    /**
     * Save As re-pointed {@code buffer} from {@code oldPath}. Once the new file has actually been written
     * (its first recorded save), it is given the history of the file it was saved from, which keeps its
     * own. A Save As that is rolled back re-points the buffer to where it was, and nothing is copied.
     */
    void bufferPathChanged(EditorBuffer buffer, @Nullable Path oldPath) {
        Path now = buffer.getPath();
        if (oldPath == null || now == null || !Vfs.isLocal(oldPath) || !Vfs.isLocal(now)) {
            return;
        }
        String from = historyKey(oldPath);
        String to = historyKey(now);
        if (to.equals(saveAsOrigins.get(from))) {
            saveAsOrigins.remove(from); // rolled back
        } else if (!from.equals(to)) {
            saveAsOrigins.put(to, from);
        }
    }

    private boolean adoptSaveAsOrigin(String key) {
        String origin = saveAsOrigins.remove(key);
        if (origin == null) {
            return false;
        }
        // The origin's history from every bucket, as the panel lists it, not only this window's (R15).
        List<HistoryRevision> past = revisionsOf(origin);
        if (past.isEmpty()) {
            return false;
        }
        Map<String, List<HistoryRevision>> bucket = ops.historyMap();
        Map<String, List<HistoryRevision>> both = new java.util.HashMap<>();
        both.put(origin, past);
        List<HistoryRevision> own = bucket.get(key);
        if (own != null) {
            both.put(key, own);
        }
        if (!HistoryMoves.copy(both, origin, key)) {
            return false;
        }
        bucket.put(key, both.get(key));
        return true;
    }

    /**
     * Folds one recorded revision into the project's index, on the FX thread, against the list <b>as it is
     * now</b> — the executor no longer builds the list, because it could only see the list as it was when the
     * record was submitted.
     */
    private boolean applyRecorded(
            Map<String, List<HistoryRevision>> submittedTo,
            String key,
            HistoryRevision rev,
            HistoryRetention.RetentionPolicy policy,
            long now,
            java.util.function.@Nullable Consumer<Boolean> durableCompletion) {
        Map<String, List<HistoryRevision>> bucket = liveBucket(submittedTo);
        List<HistoryRevision> current = bucket.getOrDefault(key, List.of());
        // The worker compared the content with the newest row as it was when the record was submitted. Two
        // records of one text submitted back to back (the text a save replaced, then the save itself, when
        // nothing was changed) both passed that; and an auto-save a moment after the last one is the same
        // sitting, not another revision.
        List<HistoryRevision> updated = HistoryRetention.fold(current, rev, HistoryRetention.AUTOSAVE_COALESCE_MILLIS);
        if (updated == current) {
            if (durableCompletion != null) {
                durableCompletion.accept(true); // the row that holds this text is already in the index
            }
            return false;
        }
        bucket.put(key, HistoryRetention.prune(updated, policy.maxPerFile(), policy.maxAgeMillis(), now));
        // Enforce the per-project byte budget across the whole bucket, then persist. Nearly every save leaves
        // the project inside its budget, and then there is nothing to rebuild: copying every file's list and
        // refilling the bucket on each save was the cost of a check that a sum answers.
        long budget = policy.maxTotalBytesPerProject();
        if (HistoryRetention.exceedsBudget(bucket, budget)) {
            // A file over its share gives up its own older revisions first (see enforceProjectBudget): one
            // large file saved a few times no longer costs every other file its history.
            replaceChanged(bucket, HistoryRetention.enforceProjectBudget(bucket, budget));
        }
        publish(durableCompletion);
        return true;
    }

    /**
     * {@code submittedTo} while it is still one of the index's buckets; this window's current one when it is not
     * (its project's history was removed meanwhile), so the revision is never filed into a detached map.
     */
    private Map<String, List<HistoryRevision>> liveBucket(Map<String, List<HistoryRevision>> submittedTo) {
        for (Map<String, List<HistoryRevision>> bucket : ops.historyByProject().values()) {
            if (bucket == submittedTo) {
                return submittedTo;
            }
        }
        return ops.historyMap();
    }

    /** Makes {@code bucket} equal to {@code wanted}, touching only the files whose lists differ. */
    private static void replaceChanged(
            Map<String, List<HistoryRevision>> bucket, Map<String, List<HistoryRevision>> wanted) {
        bucket.keySet().removeIf(file -> !wanted.containsKey(file));
        wanted.forEach((file, revisions) -> {
            if (!revisions.equals(bucket.get(file))) {
                bucket.put(file, revisions);
            }
        });
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

            @Override
            public boolean confirmRestoreOverUnsavedEdits(Path file) {
                return confirmOverUnsaved(file);
            }

            @Override
            public void purgeFile(String path) {
                HistoryCoordinator.this.purgeFile(path);
            }

            @Override
            public void focusEditor() {
                EditorBuffer b = host.activeBuffer();
                if (b != null) {
                    b.getArea().requestFocus();
                }
            }
        };
    }

    /** The panel's Restore asks before it replaces text that was never saved (a saved file is one undo away). */
    private boolean confirmRestoreOverUnsaved(Path file) {
        // Not a question before a destructive action: Undo brings the replaced edits back. So the action is
        // named and stays the default button, and is not red.
        ButtonType restore = Dialogs.action(tr("confirm.action.restore"));
        ButtonType cancel = Dialogs.cancel();
        Alert confirm = Dialogs.styled(new Alert(
                Alert.AlertType.CONFIRMATION, tr("history.restoreUnsaved", file.getFileName()), restore, cancel));
        confirm.initOwner(host.window());
        confirm.setTitle(tr("history.menu.restore"));
        confirm.setHeaderText(null);
        return Dialogs.showAndWait(confirm).orElse(cancel) == restore;
    }

    /**
     * Tells the panel when the active file's text changes, so its diff and its "Current" row follow typing,
     * undo and restores rather than waiting for the next save. One subscription, moved with the active
     * buffer; the panel waits for a pause before it re-reads anything.
     */
    private void watchEditorText(@Nullable EditorBuffer buffer) {
        if (buffer == watchedBuffer) {
            return;
        }
        if (watchedText != null) {
            watchedText.unsubscribe();
            watchedText = null;
        }
        watchedBuffer = buffer;
        if (buffer != null) {
            watchedText = buffer.getArea().plainTextChanges().subscribe(change -> {
                if (panel != null) {
                    panel.editorTextChanged();
                }
            });
        }
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
        host.promptText(tr("history.label.editTitle"), tr("history.label.prompt"), initial, name -> {
            String label = name == null ? "" : name.strip();
            if (label.equals(initial)) {
                return; // unchanged
            }
            if (!relabelRevision(revision, label)) {
                return; // the revision was pruned away meanwhile
            }
            publish();
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
        boolean found = false;
        for (Map<String, List<HistoryRevision>> bucket : everyBucket()) {
            List<HistoryRevision> list = bucket.get(revision.path());
            int at = list == null ? -1 : list.indexOf(revision);
            if (at >= 0) {
                List<HistoryRevision> copy = new ArrayList<>(list);
                copy.set(at, revision.withLabel(label));
                bucket.put(revision.path(), copy);
                found = true;
            }
        }
        return found;
    }

    /** Opens the Local History tool window for the active file, leaving a folder listing if one is up. */
    void showActive() {
        if (!isEnabled()) {
            host.setStatus(disabledStatus());
            return;
        }
        openForActive();
    }

    /**
     * Project-tree "Show Local History": for a file, opens it (the tool window is keyed to the active buffer)
     * then shows its history; for a folder, shows the folder-history view (files under it + deleted files).
     */
    void showForPath(@Nullable Path file) {
        if (!isEnabled()) {
            host.setStatus(disabledStatus());
            return;
        }
        if (file == null || !com.editora.vfs.Vfs.isLocal(file)) {
            host.setStatus(tr("status.history.noFile"));
            return;
        }
        // a folder or a file: looked at off the FX thread (PERF-01, Pauta 6.2.0)
        com.editora.perf.OffFx.then("history-look", () -> Files.isDirectory(file), folder -> {
            if (folder.or(false)) {
                showFolderHistory(file);
                return;
            }
            ops.openPath(file); // makes it the active buffer, which the history tool window tracks
            openForActive();
        });
    }

    /** Shows the active file's history in the tool window, leaving a folder listing if one is up. */
    private void openForActive() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !host.isLocalBuffer(b)) {
            host.setStatus(tr("status.history.noFile"));
            return;
        }
        if (panel().folderShown() != null) {
            panel().showFileView(); // refreshes
        } else {
            refresh();
        }
        ops.openToolWindow();
    }

    /** Why Local History is not available, for the status bar: Simple UI mode turns it off whatever the setting. */
    private String disabledStatus() {
        return tr(host.settings().isLocalHistory() ? "status.history.disabledSimple" : "status.history.disabled");
    }

    /** Shows the folder-history view: every file under {@code folder} with recorded revisions (incl. deleted). */
    private void showFolderHistory(Path folder) {
        showFolderHistory(folder, null);
    }

    /** As {@link #showFolderHistory(Path)}, then selects {@code select} in the listing when it is there (U3). */
    private void showFolderHistory(Path folder, @Nullable HistoryRevision select) {
        // Which of its files are gone is looked at off the FX thread first (PERF-01, Pauta 6.2.0).
        List<Path> files = HistoryQueries.folderRevisions(mergedIndex(), historyKey(folder)).keySet().stream()
                .map(Path::of)
                .toList();
        com.editora.perf.OffFx.then("history-look", () -> lookGone(files), looked -> {
            showFolderHistoryLooked(folder);
            if (select != null && panel != null && folder.equals(panel.folderShown())) {
                panel.selectInFolder(select);
            }
        });
    }

    /** Whether each of {@code files} is gone from the disk, looked at now, off the FX thread, and kept. */
    private static boolean lookGone(List<Path> files) {
        for (Path p : files) {
            GONE.lookAgain(p);
        }
        return true;
    }

    /** Looks at each of {@code shown} again and keeps it; whether any differs from what was shown. Off FX. */
    private static boolean lookGone(Map<Path, Boolean> shown) {
        boolean changed = false;
        for (var e : shown.entrySet()) {
            changed |= !e.getValue().equals(Boolean.TRUE.equals(GONE.lookAgain(e.getKey())));
        }
        return changed;
    }

    /**
     * Whether a file with history is gone from the disk: looked at off the FX thread when a folder's history is
     * shown, answered from memory as it is refreshed on each tab switch (PERF-01, Pauta 6.2.0).
     */
    private static final com.editora.perf.Looked<Path, Boolean> GONE =
            new com.editora.perf.Looked<>("HistoryCoordinator.gone", p -> !Files.exists(p));

    private void showFolderHistoryLooked(Path folder) {
        List<FileHistoryPanel.FileGroup> groups = folderGroups(folder);
        if (groups.isEmpty()) {
            host.setStatus(tr("status.history.folderEmpty", folder.getFileName()));
            return;
        }
        folderGroupsShown = groups;
        panel().setFolderHistory(folder, groups);
        ops.setToolWindowAvailable(true);
        ops.openToolWindow();
    }

    /** The files under {@code folder} that have history in any project's bucket, with their revisions. */
    private List<FileHistoryPanel.FileGroup> folderGroups(Path folder) {
        var folderRevs = HistoryQueries.folderRevisions(mergedIndex(), historyKey(folder));
        List<FileHistoryPanel.FileGroup> groups = new ArrayList<>();
        Map<Path, Boolean> shown = new java.util.HashMap<>();
        for (var e : folderRevs.entrySet()) {
            Path p = Path.of(e.getKey());
            String display = folder.relativize(p).toString();
            // what was last looked at (a file recorded since counts as there); looked at again behind, below
            boolean deleted = GONE.knows(p) && Boolean.TRUE.equals(GONE.get(p));
            shown.put(p, deleted);
            groups.add(new FileHistoryPanel.FileGroup(e.getKey(), display, deleted, e.getValue()));
        }
        if (!shown.isEmpty()) {
            // Looked at again off the FX thread (PERF-01, Pauta 6.2.0): a file restored or deleted since is shown so
            // once the look lands.
            com.editora.perf.OffFx.then("history-look", () -> lookGone(shown), changed -> {
                if (changed.or(false)) {
                    Platform.runLater(this::refresh); // after the refresh that asked, which is still showing
                }
            });
        }
        return groups;
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

        // Open in this window: the text the user is looking at is the buffer's. Writing the file underneath
        // left the tab on the old text under a "Restored" message — and replaced the disk below unsaved edits
        // without a word about them. Restore into the buffer instead: one undoable edit, nothing written.
        // In another window, the same (B4): its buffer, not the disk under it — a clean one reloaded silently and
        // a dirty one kept its edits against a changed disk, nobody asked.
        EditorBuffer open = ops.openBufferAnywhere(file);
        if (open != null && !open.isDisposed()) {
            if (open.isDirty() && !confirmOverUnsaved(file)) {
                completion.complete(RestoreResult.CANCELLED);
                return completion;
            }
            if (ops.openBufferFor(file) == open) {
                ops.openPath(file); // bring its tab forward: that is where the restored text appears
            } else {
                ops.revealBuffer(open); // its window and tab, where the restored text appears
            }
            restoreInto(open, revision, completion);
            return completion;
        }

        submitRestoreWork(completion, () -> {
            final TargetState target;
            try {
                boolean existed = Files.exists(file);
                // The charset rule too, here: asked on the FX thread, a file nothing looked at yet has "no rules",
                // and a BOM-less UTF-16 or Latin-1 file was then restored as UTF-8 (R13).
                target = new TargetState(existed, existed ? Files.readAllBytes(file) : null, charsetRuleFor(file));
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
                                byte[] replacement = restoredBytes(
                                        encodingSourceFor(revision),
                                        text,
                                        target.expectedBytes(),
                                        target.charsetRule());
                                // The file is replaced only once what it holds now is safely in history —
                                // as a delete, Replace in Files and an agent's write already wait.
                                recordBeforeOverwrite(
                                        file,
                                        target,
                                        kept -> onFx(() -> {
                                            if (completion.isDone()) {
                                                ticket.close();
                                            } else if (!kept) {
                                                ticket.close();
                                                finishDiskRestore(file, completion, RestoreResult.NOT_PRESERVED);
                                            } else if (!submitRestoreWork(
                                                    completion,
                                                    () -> commitDiskRestore(
                                                            file, target, replacement, ticket, completion))) {
                                                ticket.close();
                                            }
                                        }));
                            }));
        } catch (RuntimeException failure) {
            ticket.close();
            finishDiskRestore(file, completion, RestoreResult.CONTENT_UNAVAILABLE);
        }
    }

    /**
     * Records the file a disk restore is about to replace, as delete and replace-in-files do. Without it,
     * content changed outside the editor since the last recorded save was gone once the user confirmed the
     * overwrite. The text is captured here, from the bytes the write is conditional on.
     */
    private void recordBeforeOverwrite(Path file, TargetState target, Consumer<Boolean> kept) {
        byte[] current = target.existed() ? target.expectedBytes() : null;
        String charsetRule = target.charsetRule();
        // The charset-aware test, as before a delete: BOM-less UTF-16 has a NUL in every ASCII character (R13).
        if (current == null || isBinary(current, charsetRule)) {
            kept.accept(true); // nothing there, or nothing Local History holds
            return;
        }
        String text = LineEndings.toLf(decodeCaptured(current, charsetRule));
        // Also while the feature is off: this is Local History's own destructive action, offered from a
        // listing that is still open, and the file it replaces may hold what no revision does.
        recordFor(file, text, HistoryRevision.REASON_RESTORE, "", false, null, true, kept);
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
            if (result == RestoreResult.RESTORED && !disposed) {
                // From the folder view the user is likely restoring several files: the file opens, but the keyboard
                // stays in the listing they are working through.
                javafx.scene.Node keep = panel != null && panel.folderShown() != null ? focusOwner() : null;
                ops.openPath(file);
                if (keep != null) {
                    Platform.runLater(keep::requestFocus);
                }
                ops.refreshProjectTree();
            }
            reportRestore(file, result);
            completion.complete(result);
        });
    }

    /** The node with the keyboard in this window, or null. */
    private javafx.scene.@Nullable Node focusOwner() {
        javafx.stage.Window window = host.window();
        javafx.scene.Scene scene = window == null ? null : window.getScene();
        return scene == null ? null : scene.getFocusOwner();
    }

    /** The {@code .editorconfig} charset the editor would use for {@code file}, or {@code null}. */
    private @Nullable String charsetRuleFor(Path file) {
        return diff == null ? null : diff.editorConfigCharset(file);
    }

    /**
     * The bytes a restored revision is written as: the encoding of the file being replaced, read the way
     * the editor reads it — its byte-order mark, else its {@code .editorconfig} charset, else UTF-8, else
     * the lossless stand-in for bytes that charset cannot decode — and that file's line ending. Revisions
     * hold the editor's {@code \n}-only text, so writing one verbatim turned a CRLF file into an LF one, and
     * choosing the charset without looking at the bytes rewrote a BOM-less Windows-1252 file as UTF-8.
     * A file that no longer exists (or has no line break to learn from) keeps the revision's own
     * terminators, which a pre-delete capture preserves. UTF-8 is used only when the text has a character
     * the charset cannot hold.
     *
     * @param existing the bytes currently at the path, or {@code null} when the file no longer exists
     */
    static byte[] restoredBytes(String text, byte @Nullable [] existing, @Nullable String editorConfigCharset) {
        String name = EditorConfigCharset.resolveName(existing, editorConfigCharset);
        String body = text;
        if (existing != null) {
            EditorConfigCharset.Decoded current = DiffSideText.decodeRaw(existing, editorConfigCharset, null);
            name = current.charset();
            if (current.text().indexOf('\n') >= 0 || current.text().indexOf('\r') >= 0) {
                body = LineEndings.apply(LineEndings.toLf(text), LineEndings.dominant(current.text()));
            }
        }
        if (!EditorConfigCharset.canEncode(body, name)) {
            name = EditorConfigCharset.UTF_8;
        }
        if (existing != null) {
            // The mark as the file has it: a BOM-less UTF-16 file (its charset from .editorconfig) used to come
            // back with one, which every tool that read it without a mark then saw as two stray bytes.
            return EditorConfigCharset.encode(body, name, EditorConfigCharset.detectByBom(existing) != null);
        }
        return EditorConfigCharset.encode(body, name);
    }

    /**
     * As {@link #restoredBytes(String, byte[], String)}, for a file that no longer exists and whose
     * {@code recorded} revision says how it was written (a pre-delete capture, schema 3 on): the charset and
     * byte-order mark it had, so a UTF-8-with-BOM, UTF-16 or legacy single-byte file comes back as the bytes
     * that were deleted rather than as BOM-less UTF-8. The line terminators are the revision's own, which a
     * pre-delete capture keeps verbatim; the recorded line ending is applied only to a body that has none of
     * its own form left ({@code \n} only). A revision without the metadata, a file that still exists (its
     * bytes are the better witness) and text the recorded charset cannot hold all fall back to the rule above.
     */
    static byte[] restoredBytes(
            HistoryRevision recorded, String text, byte @Nullable [] existing, @Nullable String editorConfigCharset) {
        if (existing != null || recorded == null || !recorded.hasEncoding()) {
            return restoredBytes(text, existing, editorConfigCharset);
        }
        String body = text;
        String ending = recorded.lineEnding();
        if (LineEndings.isLabel(ending) && !LineEndings.LF.equals(ending) && text.indexOf('\r') < 0) {
            body = LineEndings.apply(text, ending);
        }
        if (!EditorConfigCharset.canEncode(body, recorded.charset())) {
            return restoredBytes(text, null, editorConfigCharset);
        }
        return EditorConfigCharset.encode(body, recorded.charset(), recorded.bom());
    }

    /**
     * The revision whose recorded encoding a restore of {@code revision} uses: itself when it has one, else
     * the newest revision of the same file that does. Restoring an older save of a deleted file should give
     * the file the form it had when it was deleted, not UTF-8 because that older row predates the capture.
     */
    private HistoryRevision encodingSourceFor(HistoryRevision revision) {
        if (revision.hasEncoding()) {
            return revision;
        }
        for (HistoryRevision other : revisionsOf(revision.path())) {
            if (other.hasEncoding()) {
                return other;
            }
        }
        return revision;
    }

    /**
     * Decodes a file captured just before deletion the way the editor would have read it (BOM, then the
     * {@code .editorconfig} charset, then UTF-8, then the editor's lossless stand-in when the bytes are not
     * valid in that charset). The history store keeps text, so a decode that substitutes U+FFFD destroyed
     * every non-ASCII character in what may be the only copy left. Line terminators are kept as they were:
     * nothing else records them once the file is gone.
     */
    static String decodeCaptured(byte[] bytes, @Nullable String editorConfigCharset) {
        return DiffSideText.decodeRaw(bytes, editorConfigCharset, null).text();
    }

    private boolean confirmRestoreOverwrite(Path file) {
        return Confirm.ask(host.window(), restoreOverwriteQuestion(String.valueOf(file.getFileName())));
    }

    static boolean writeRestoredContent(
            Path file, byte @Nullable [] expectedBytes, byte[] replacementBytes, BooleanSupplier current)
            throws IOException {
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
        // The file is read, and its charset looked up, off the FX thread (PERF-01, Pauta 6.2.0); the revision is
        // recorded from here, as before.
        com.editora.perf.OffFx.then("history-capture", () -> Captured.read(file, false, this::charsetRuleFor), read -> {
            Captured c = read.value();
            if (c == null) {
                completion.accept(new DeleteCapture(!historyEnabled, null));
                return;
            }
            if (!c.regular()) {
                completion.accept(new DeleteCapture(false, null));
                return;
            }
            byte[] bytes = c.bytes();
            if (bytes == null) { // over the size Local History holds
                completion.accept(new DeleteCapture(true, null));
                return;
            }
            if (isBinary(bytes, c.charsetRule()) || !historyEnabled) {
                completion.accept(new DeleteCapture(true, bytes));
                return;
            }
            EditorConfigCharset.Decoded decoded = DiffSideText.decodeRaw(bytes, c.charsetRule(), null);
            String content = decoded.text();
            // The body is text; with the file gone, these are all that say which bytes it was.
            Encoding encoding = Encoding.of(bytes, decoded);
            recordFor(
                    file,
                    content,
                    HistoryRevision.REASON_DELETE,
                    "",
                    true,
                    encoding,
                    false,
                    durable -> onFx(() -> completion.accept(new DeleteCapture(durable, bytes))));
        });
    }

    /**
     * A file as a capture before a delete or an overwrite reads it, off the FX thread: whether it is a regular file,
     * its bytes (null when it is over {@link #MAX_CAPTURE_BYTES}) and its {@code .editorconfig} charset.
     */
    record Captured(
            boolean regular,
            byte @Nullable [] bytes,
            @Nullable String charsetRule) {
        static Captured read(Path file, boolean noFollow, java.util.function.Function<Path, @Nullable String> charset)
                throws IOException {
            com.editora.perf.BlockingSites.enter("HistoryCoordinator.capture");
            boolean regular = noFollow
                    ? Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    : Files.isRegularFile(file);
            if (!regular) {
                return new Captured(false, null, null);
            }
            if (Files.size(file) > MAX_CAPTURE_BYTES) {
                return new Captured(true, null, null);
            }
            byte[] bytes = Files.readAllBytes(file);
            return new Captured(true, bytes, charset.apply(file));
        }
    }

    /**
     * What {@link #captureBeforeOverwriteDurably} found: {@code safe} when the file may be replaced,
     * {@code recorded} when its content is now a durable history revision, and the exact bytes that were read
     * (null when the file is too large to hold) so the caller can replace only an unchanged file.
     */
    record OverwriteCapture(boolean safe, boolean recorded, byte @Nullable [] expectedBytes) {
        OverwriteCapture {
            expectedBytes = expectedBytes == null ? null : expectedBytes.clone();
        }
    }

    /**
     * Captures a regular file immediately before something outside the editor's own save path replaces it
     * (an HTTP {@code >>!} response redirect), and acknowledges only once the revision is durable. Mirrors
     * {@link #captureBeforeDeleteDurably}: binary and oversized files, and every file while the feature is
     * off, are outside Local History's contract — they are reported as safe to replace but not recorded, so
     * the caller can say so. A file that could not be read, or a revision that could not be stored, is not
     * safe to replace.
     */
    void captureBeforeOverwriteDurably(Path file, Consumer<OverwriteCapture> completion) {
        captureBeforeOverwriteDurably(file, HistoryRevision.REASON_HTTP, completion); // its one caller: >>! (F3)
    }

    /** As {@link #captureBeforeOverwriteDurably(Path, Consumer)}, recording under {@code reason}. */
    void captureBeforeOverwriteDurably(Path file, String reason, Consumer<OverwriteCapture> completion) {
        Objects.requireNonNull(completion, "completion");
        if (file == null) {
            completion.accept(new OverwriteCapture(false, false, null));
            return;
        }
        if (!com.editora.vfs.Vfs.isLocal(file)) {
            completion.accept(new OverwriteCapture(true, false, null)); // history is local-only by contract
            return;
        }
        // read off the FX thread (PERF-01, Pauta 6.2.0), recorded from here
        com.editora.perf.OffFx.then("history-capture", () -> Captured.read(file, true, this::charsetRuleFor), read -> {
            Captured c = read.value();
            if (c == null || !c.regular()) {
                completion.accept(new OverwriteCapture(false, false, null));
                return;
            }
            byte[] bytes = c.bytes();
            if (bytes == null) {
                completion.accept(new OverwriteCapture(true, false, null));
                return;
            }
            if (!isEnabled() || isBinary(bytes, c.charsetRule())) {
                completion.accept(new OverwriteCapture(true, false, bytes));
                return;
            }
            String text;
            try {
                text = LineEndings.toLf(decodeCaptured(bytes, c.charsetRule()));
            } catch (RuntimeException failure) {
                completion.accept(new OverwriteCapture(false, false, null));
                return;
            }
            if (encrypted(file, text)) { // safe to replace, by contract, but not "recorded": nothing was kept
                completion.accept(new OverwriteCapture(true, false, bytes));
                return;
            }
            recordFor(
                    file,
                    text,
                    reason,
                    "",
                    false,
                    durable -> onFx(() -> completion.accept(new OverwriteCapture(durable, durable, bytes))));
        });
    }

    /**
     * {@link #captureBeforeDeleteDurably} for each of {@code files} in turn, for a delete that removes many
     * at once and goes through no per-file approval (Git's "delete untracked"). Reports {@code true} on the
     * FX thread once every file Local History can hold is durably recorded — also when there was nothing to
     * record (history off, binary or oversized files) — and {@code false} at the first one that could not be,
     * so the caller can leave the files where they are.
     */
    void captureAllBeforeDelete(List<Path> files, Consumer<Boolean> completion) {
        Objects.requireNonNull(completion, "completion");
        captureNextBeforeDelete(List.copyOf(files), 0, completion);
    }

    private void captureNextBeforeDelete(List<Path> files, int index, Consumer<Boolean> completion) {
        if (index >= files.size() || !isEnabled()) {
            completion.accept(true);
            return;
        }
        Path file = files.get(index);
        // Looked at off the FX thread (PERF-01, Pauta 6.2.0): a symbolic link, or a file that is already gone, has no
        // text for Local History to hold.
        com.editora.perf.OffFx.then("history-capture", () -> Files.isRegularFile(file), regular -> {
            if (!regular.or(false)) {
                captureNextBeforeDelete(files, index + 1, completion);
                return;
            }
            // One file per FX turn: a folder of them must not hold the UI for the whole batch.
            captureBeforeDeleteDurably(
                    file,
                    capture -> Platform.runLater(() -> {
                        if (capture.durable()) {
                            captureNextBeforeDelete(files, index + 1, completion);
                        } else {
                            completion.accept(false);
                        }
                    }));
        });
    }

    /** Restores {@code revision}'s content into the active file via an undoable whole-file replace. */
    CompletableFuture<RestoreResult> restoreHistory(HistoryRevision revision) {
        CompletableFuture<RestoreResult> completion = new CompletableFuture<>();
        restoreInto(host.activeBuffer(), revision, completion);
        return completion;
    }

    private boolean confirmOverUnsaved(Path file) {
        Predicate<Path> asked = confirmUnsavedRestore;
        return asked == null ? confirmRestoreOverUnsaved(file) : asked.test(file);
    }

    private void restoreInto(
            @Nullable EditorBuffer b, HistoryRevision revision, CompletableFuture<RestoreResult> completion) {
        if (b == null || b.getPath() == null || revision == null) {
            completion.complete(RestoreResult.INVALID_REQUEST);
            return;
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
                                // RESTORED once the text is in the editor: the edit lands after an off-FX diff.
                                diff.applyToLocal(
                                        b,
                                        text,
                                        applied -> finishBufferRestore(
                                                target,
                                                completion,
                                                applied ? RestoreResult.RESTORED : RestoreResult.APPLY_FAILED));
                            }));
        } catch (RuntimeException failure) {
            finishBufferRestore(target, completion, RestoreResult.CONTENT_UNAVAILABLE);
        }
    }

    private void finishBufferRestore(Path target, CompletableFuture<RestoreResult> completion, RestoreResult result) {
        if (completion.isDone()) {
            return;
        }
        reportRestore(target, result);
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
            host.setStatus(disabledStatus());
            return;
        }
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !host.isLocalBuffer(b)) {
            host.setStatus(tr("status.history.noFile"));
            return;
        }
        if (b.encryption().isEncrypted()) {
            host.setStatus(tr("status.history.encrypted")); // a label would say "stored" and keep nothing
            return;
        }
        if (holdsPart(b)) {
            host.setStatus(tr("status.history.partialFile", b.getTitle()));
            return;
        }
        Path file = b.getPath();
        String content = b.getContent(); // snapshot the state at the moment the command was invoked
        host.promptText(tr("history.label.title"), tr("history.label.prompt"), "", name -> {
            String label = name == null ? "" : name.strip();
            if (label.isEmpty()) {
                return;
            }
            // Said once the revision is in the index, not when it was asked for: a label that could not be
            // stored is a restore point the user would rely on and not find.
            recordFor(
                    file,
                    content,
                    HistoryRevision.REASON_LABEL,
                    label,
                    true,
                    stored -> onFx(() -> {
                        if (disposed) {
                            return;
                        }
                        if (stored) {
                            host.setStatus(tr("status.history.labeled", label));
                        } else {
                            host.setError(tr("status.history.labelFailed", label));
                        }
                    }));
        });
    }

    /** "Recent Changes": a cross-file picker of the active project's most recent revisions, newest-first. */
    void showRecentChanges() {
        if (!isEnabled()) {
            host.setStatus(disabledStatus());
            return;
        }
        // One look at the disk per file, not per row, off the FX thread before the picker shows (PERF-01, Pauta
        // 6.2.0).
        List<HistoryRevision> recent = HistoryQueries.recent(recentIndex(), 200);
        List<String> paths =
                recent.stream().map(HistoryRevision::path).distinct().toList();
        com.editora.perf.OffFx.then(
                "history-look",
                () -> {
                    Map<String, Boolean> gone = new java.util.HashMap<>();
                    paths.forEach(p -> gone.put(p, isGone(p)));
                    return gone;
                },
                looked -> showRecentChanges(new java.util.HashMap<>(looked.or(Map.of()))));
    }

    private void showRecentChanges(Map<String, Boolean> gone) {
        Path root = ops.projectRoot();
        Function<HistoryRevision, String> label = r -> recentRowText(
                HistoryRowText.dateTimeText(r.timestamp(), ZoneId.systemDefault(), FileHistoryPanel.locale()),
                r.label(),
                historyReasonLabel(r.reason()));
        Function<HistoryRevision, String> detail =
                r -> recentDetail(r.path(), root, gone.getOrDefault(r.path(), false) ? tr("history.deleted") : null);
        QuickOpen<HistoryRevision> picker = new QuickOpen<>(
                tr("history.recent.title"),
                tr("history.recent.prompt"),
                () -> HistoryQueries.recent(recentIndex(), 200),
                label,
                detail,
                r -> label.apply(r) + " " + r.path(), // the row leads with the time; people type the file's name
                this::openRecentChange);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /**
     * What Recent Changes lists: the files at or under this window's project root, from every bucket — or, in a
     * No-Project window, every file (F10).
     */
    private Map<String, List<HistoryRevision>> recentIndex() {
        return projectFiles(mergedIndex(), ops.projectRoot());
    }

    /** The entries of {@code index} at or under {@code root}; all of them when {@code root} is null. Pure. */
    static Map<String, List<HistoryRevision>> projectFiles(
            Map<String, List<HistoryRevision>> index, @Nullable Path root) {
        if (root == null) {
            return index;
        }
        String rootKey = historyKey(root);
        String separator = root.getFileSystem().getSeparator();
        Map<String, List<HistoryRevision>> out = new LinkedHashMap<>();
        index.forEach((key, revisions) -> {
            if (key.equals(rootKey) || HistoryMoves.renamed(key, rootKey, rootKey, separator) != null) {
                out.put(key, revisions);
            }
        });
        return out;
    }

    private static boolean isGone(String path) {
        try {
            return !Files.exists(Path.of(path));
        } catch (RuntimeException invalidPath) {
            return true;
        }
    }

    /**
     * A Recent Changes row: when, then what kind of revision (its label when it has one). The list is
     * newest-first across files, so the time is what a reader scans down; which file it was is the detail line.
     */
    static String recentRowText(String time, String label, String reason) {
        return time + "  ·  " + (label != null && !label.isBlank() ? label : reason);
    }

    /**
     * The detail line of a Recent Changes row: the file relative to the project root when it is inside it —
     * a bare file name does not tell two {@code index.ts} apart — else its whole path; followed by
     * {@code deletedTag} for a file that is no longer there.
     */
    static String recentDetail(String path, @Nullable Path projectRoot, @Nullable String deletedTag) {
        String shown = path;
        try {
            Path file = Path.of(path);
            if (projectRoot != null && file.startsWith(projectRoot) && !file.equals(projectRoot)) {
                shown = projectRoot.relativize(file).toString();
            }
        } catch (RuntimeException invalidPath) {
            // shown as recorded
        }
        return deletedTag == null ? shown : shown + "  ·  " + deletedTag;
    }

    /**
     * Opens the file behind a recent revision, shows its history and selects that revision — the row that was
     * picked, with its diff, rather than the top of the list. A file that no longer exists cannot be opened:
     * its revisions are shown where they can be restored from, in the listing of the folder it was in.
     */
    private void openRecentChange(HistoryRevision r) {
        if (r == null) {
            return;
        }
        Path file;
        try {
            file = Path.of(r.path());
        } catch (RuntimeException invalidPath) {
            return;
        }
        // still there? looked at off the FX thread (PERF-01, Pauta 6.2.0)
        com.editora.perf.OffFx.then("history-look", () -> Files.exists(file), there -> {
            if (!there.or(false)) {
                Path parent = file.getParent();
                if (parent != null) {
                    showFolderHistory(parent, r); // the row that was picked, with its diff
                }
                return;
            }
            openRecentChange(r, file);
        });
    }

    private void openRecentChange(HistoryRevision r, Path file) {
        ops.openPath(file);
        EditorBuffer active = host.activeBuffer();
        if (active == null
                || active.getPath() == null
                || !historyKey(active.getPath()).equals(historyKey(file))) {
            return; // it did not open (the reason is in the status bar): not another file's history
        }
        openForActive();
        panel().selectRevision(r);
    }

    /** Localized capture-reason label (mirrors {@code FileHistoryPanel.reasonLabel} for cross-file pickers). */
    static String historyReasonLabel(String reason) {
        return FileHistoryPanel.reasonLabel(reason); // one mapping: the picker and the panel name a reason alike
    }
}
