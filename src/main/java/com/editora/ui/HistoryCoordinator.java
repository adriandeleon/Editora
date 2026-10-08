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

import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.config.SharedConfig;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LineEndings;
import com.editora.editorconfig.EditorConfigCharset;
import com.editora.history.HistoryMoves;
import com.editora.history.HistoryQueries;
import com.editora.history.HistoryRetention;
import com.editora.history.HistoryService;
import com.editora.io.AtomicFileWrite;
import com.editora.io.DocumentWriteSequencer;
import com.editora.vfs.Vfs;

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
    static String restoreMessageKey(RestoreResult result) {
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

        /** The buffer this window has open for {@code file}, or {@code null}. */
        default EditorBuffer openBufferFor(Path file) {
            return null;
        }

        /** The index changed here: the other windows' panels must not go on showing what it was. */
        default void historyChanged() {}

        /** The window's project root, for showing a path relative to it; {@code null} without a project. */
        default Path projectRoot() {
            return null;
        }

        /**
         * Whether revision bodies nothing refers to any more can be deleted from disk now — not while another
         * running Editora shares the configuration folder, or while the index cannot be trusted.
         */
        default boolean canCollectNow() {
            return true;
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
            public void revert(HistoryRevision revision, Runnable done) {
                restoreHistory(revision).whenComplete((result, failure) -> onFx(done));
            }

            @Override
            public void applyToLocalIfUnchanged(
                    Path target, String expectedText, String newText, Consumer<Boolean> done) {
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

    /** Test seam: answers the "replace unsaved edits?" question instead of the dialog. */
    Predicate<Path> confirmUnsavedRestore;

    private EditorBuffer watchedBuffer; // the buffer whose edits are forwarded to the panel
    private org.reactfx.Subscription watchedText;

    /** The File History tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    FileHistoryPanel panel() {
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
                boolean apply = asked == null
                        ? confirmTightening(impact, host.window(), tr("dialog.history.limits.pending") + "\n\n")
                        : asked.test(impact);
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
        return historyService.effectivePolicy(
                policyOf(s.getHistoryMaxPerFile(), s.getHistoryMaxAgeDays(), s.getHistoryMaxTotalMb()));
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
        return confirmTightening(impact, owner, "");
    }

    private boolean confirmTightening(HistoryRetention.Impact impact, javafx.stage.Window owner, String preface) {
        javafx.scene.control.ButtonType delete = new javafx.scene.control.ButtonType(
                tr("dialog.history.purge.button"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                preface + tr("dialog.history.limits.confirm", impact.revisions(), impact.files()),
                delete,
                ButtonType.CANCEL);
        confirm.initOwner(owner != null && owner.isShowing() ? owner : host.window());
        confirm.setTitle(tr("dialog.history.limits.title"));
        confirm.setHeaderText(null);
        confirm.getDialogPane().lookupButton(delete).getStyleClass().add("danger");
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == delete;
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
        String question = tr("dialog.history.purgeFile.confirm", count, name == null ? key : name);
        int sharing = filesSharingSnapshots(key);
        if (sharing > 0) {
            // Save As gives the copy the history of the file it came from, and bodies are stored once by
            // content: deleting this file's rows does not take the text away from those.
            question += "\n\n" + tr("dialog.history.purgeFile.shared", sharing);
        }
        if (!(confirmPurgeAnswer == null ? confirmPurge(question) : confirmPurgeAnswer.test(question))) {
            return;
        }
        // Re-read after the modal dialog: revisions recorded while it was open are purged too. Every
        // project's bucket, not only this window's: the same file recorded from a No-Project window or an
        // overlapping project kept its revisions (and its content on disk) while the status said "purged".
        int removed = revisionsInEveryProject(key);
        ops.historyMap().remove(key);
        for (Map<String, List<HistoryRevision>> bucket : ops.historyByProject().values()) {
            bucket.remove(key);
        }
        finishPurge(removed);
    }

    /** Test seam: answers the purge confirmation (given its text) instead of the dialog. */
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

    /** Deletes every recorded revision of every file in the active project's history, after confirmation. */
    void purgeProject() {
        int files = ops.historyMap().size();
        int count = ops.historyMap().values().stream().mapToInt(List::size).sum();
        if (count == 0) {
            host.setStatus(tr("status.history.nothingToPurge"));
            return;
        }
        String question = tr("dialog.history.purgeProject.confirm", count, files);
        if (!(confirmPurgeAnswer == null ? confirmPurge(question) : confirmPurgeAnswer.test(question))) {
            return;
        }
        Map<String, List<HistoryRevision>> bucket = ops.historyMap();
        int removed = bucket.values().stream().mapToInt(List::size).sum();
        bucket.clear();
        finishPurge(removed);
    }

    private void finishPurge(int removed) {
        // The content must leave the disk now, not at the next throttled collection.
        historyService.requestGc();
        publish();
        refresh();
        // The rows are gone from the index either way. The bodies leave the disk with the collection that
        // follows — which is refused while another running Editora shares this configuration, or while the
        // index cannot be trusted. Someone purging a pasted secret has to be told it is still there.
        host.setStatus(tr(ops.canCollectNow() ? "status.history.purged" : "status.history.purgedPending", removed));
    }

    private boolean confirmPurge(String message) {
        javafx.scene.control.ButtonType delete = new javafx.scene.control.ButtonType(
                tr("dialog.history.purge.button"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, message, delete, ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.history.purge.title"));
        confirm.setHeaderText(null);
        confirm.getDialogPane().lookupButton(delete).getStyleClass().add("danger");
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == delete;
    }

    /** Sets the history tool window's availability (local file + feature on) and reloads its list. */
    void refresh() {
        EditorBuffer b = host.activeBuffer();
        boolean available = isEnabled() && b != null && b.getPath() != null && host.isLocalBuffer(b);
        Path folder = isEnabled() ? panel.folderShown() : null;
        if (folder != null) {
            // The folder view is not "the active file's history": restoring one deleted file opens it, a save
            // is recorded, a tab is clicked — and the listing the user was working through must still be
            // there, with what just changed in it. It is left with the panel's back button.
            List<FileHistoryPanel.FileGroup> groups = folderGroups(folder);
            if (!groups.isEmpty()) {
                ops.setToolWindowAvailable(true);
                watchEditorText(available ? b : null);
                if (!groups.equals(folderGroupsShown)) {
                    folderGroupsShown = groups;
                    panel.setFolderHistory(folder, groups);
                }
                return;
            }
        }
        folderGroupsShown = null;
        ops.setToolWindowAvailable(available);
        watchEditorText(available ? b : null);
        if (available) {
            panel.setRevisions(
                    revisionsOf(historyKey(b.getPath())),
                    b.getPath().getFileName().toString(),
                    b.getPath());
        } else {
            panel.setRevisions(List.of(), null, null);
        }
    }

    /** What the folder view lists now; a refresh that finds the same leaves the tree (and its selection) alone. */
    private List<FileHistoryPanel.FileGroup> folderGroupsShown;

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

    /** How long {@link #recordSafetyCopy} lets the FX thread wait for the previous text to reach the disk. */
    private static final long SAFETY_COPY_TIMEOUT_MILLIS = 15_000;

    /**
     * Stores {@code buffer}'s current text — unsaved edits included — as a labelled (and therefore
     * retention-protected) revision, and returns only once its content is on disk and the revision is in the
     * index. This is the recovery copy {@link NoUndoGuard} requires before a bulk edit in a buffer that has
     * no undo; anything other than {@link NoUndoGuard.Copy#STORED} means there is none and the edit must not
     * happen. Blocks the FX thread for the blob write (bounded), which is the point: the edit comes after.
     */
    NoUndoGuard.Copy recordSafetyCopy(EditorBuffer buffer, String label) {
        if (!isEnabled()) {
            return NoUndoGuard.Copy.HISTORY_OFF;
        }
        if (buffer == null || buffer.getPath() == null || !host.isLocalBuffer(buffer)) {
            return NoUndoGuard.Copy.NO_LOCAL_FILE;
        }
        String key = historyKey(buffer.getPath());
        var policy = retentionPolicy();
        long now = System.currentTimeMillis();
        boolean[] stored = {false};
        historyService.snapshotBlocking(
                buffer.getPath(),
                buffer.getContent(),
                HistoryRevision.REASON_LABEL,
                label,
                now,
                SAFETY_COPY_TIMEOUT_MILLIS,
                outcome -> {
                    if (outcome.successful() && outcome.revision() != null) {
                        applyRecorded(key, HistoryMoves.at(key, outcome.revision()), policy, now, null);
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
            Path file,
            String content,
            String reason,
            String label,
            boolean force,
            java.util.function.Consumer<Boolean> durableCompletion) {
        recordFor(file, content, reason, label, force, null, false, durableCompletion);
    }

    /** How a captured file was written, kept on its pre-delete revision (see {@link HistoryRevision}). */
    record Encoding(String charset, boolean bom, String lineEnding) {

        /** The encoding of {@code bytes}, read the way the editor reads a file under {@code editorConfigCharset}. */
        static Encoding of(byte[] bytes, String editorConfigCharset) {
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
            Path file,
            String content,
            String reason,
            String label,
            boolean force,
            Encoding encoding,
            boolean evenWhenOff,
            java.util.function.Consumer<Boolean> durableCompletion) {
        if (file == null || content == null || !com.editora.vfs.Vfs.isLocal(file) || !(evenWhenOff || isEnabled())) {
            if (durableCompletion != null) {
                durableCompletion.accept(true);
            }
            return;
        }
        String submittedKey = historyKey(file);
        List<HistoryRevision> existing = ops.historyMap().getOrDefault(submittedKey, List.of());
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
            boolean listed = rev != null && applyRecorded(key, rev, policy, now, durableCompletion);
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
                    && (panel.folderShown() != null
                            || (active != null
                                    && active.getPath() != null
                                    && historyKey(active.getPath()).equals(key)))) {
                refresh();
            }
        });
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

    private void publish(java.util.function.Consumer<Boolean> completion) {
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
    void saveReplaced(Path target, byte[] replaced, byte[] written) {
        if (target == null || written == null || !Vfs.isLocal(target)) {
            return;
        }
        Long previous = lastSavedStamps.put(historyKey(target), stamp(written));
        if (replaced == null
                || replaced.length == 0 // an empty file has no text to bring back
                || replaced.length > EditorBuffer.LARGE_FILE_BYTES
                || (previous != null && previous >>> 32 == replaced.length && previous == stamp(replaced))) {
            return;
        }
        // What the first save of a session replaces is the file as it was before the session; what a later
        // one replaces, when it is not this window's own save, was written by something else meanwhile.
        String reason = previous == null ? REASON_BASELINE : HistoryRevision.REASON_EXTERNAL;
        Platform.runLater(() -> recordReplaced(target, replaced, reason));
    }

    /** FX half of {@link #saveReplaced}: the same text contract as a pre-delete capture, in the editor's form. */
    private void recordReplaced(Path file, byte[] replaced, String reason) {
        if (!isEnabled()) {
            return;
        }
        String charsetRule = charsetRuleFor(file);
        if (isBinary(replaced, charsetRule)) {
            return;
        }
        String text = LineEndings.toLf(decodeCaptured(replaced, charsetRule));
        recordFor(file, text, reason, "", false, null);
    }

    /**
     * Records a closed file as Replace in Files found it, before it is rewritten, and reports once that is
     * durable. The text arrives as it was read from disk; the index holds the editor's form of a text
     * ({@code \n} only, no byte-order mark), so that an equal save is recognised as the same content.
     */
    void recordBeforeReplace(Path file, String content, java.util.function.Consumer<Boolean> completion) {
        recordFor(file, editorForm(content), REASON_BEFORE_REPLACE, "", false, completion);
    }

    /** {@code text} as the editor holds it: line feeds only, without a leading byte-order mark. */
    static String editorForm(String text) {
        if (text == null) {
            return null;
        }
        String lf = LineEndings.toLf(text);
        return lf.startsWith("\uFEFF") ? lf.substring(1) : lf;
    }

    /** A NUL byte marks a binary file — except in UTF-16 text, where every ASCII character has one. */
    private static boolean isBinary(byte[] bytes, String charsetRule) {
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
    void bufferPathChanged(EditorBuffer buffer, Path oldPath) {
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
        return origin != null && HistoryMoves.copy(ops.historyMap(), origin, key);
    }

    /**
     * Folds one recorded revision into the project's index, on the FX thread, against the list <b>as it is
     * now</b> — the executor no longer builds the list, because it could only see the list as it was when the
     * record was submitted.
     */
    private boolean applyRecorded(
            String key,
            HistoryRevision rev,
            HistoryRetention.RetentionPolicy policy,
            long now,
            java.util.function.Consumer<Boolean> durableCompletion) {
        Map<String, List<HistoryRevision>> bucket = ops.historyMap();
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
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("history.restoreUnsaved", file.getFileName()),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("history.menu.restore"));
        confirm.setHeaderText(null);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /**
     * Tells the panel when the active file's text changes, so its diff and its "Current" row follow typing,
     * undo and restores rather than waiting for the next save. One subscription, moved with the active
     * buffer; the panel waits for a pause before it re-reads anything.
     */
    private void watchEditorText(EditorBuffer buffer) {
        if (buffer == watchedBuffer) {
            return;
        }
        if (watchedText != null) {
            watchedText.unsubscribe();
            watchedText = null;
        }
        watchedBuffer = buffer;
        if (buffer != null) {
            watchedText = buffer.getArea().plainTextChanges().subscribe(change -> panel.editorTextChanged());
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
        host.promptText(tr("history.label.title"), tr("history.label.prompt"), initial, name -> {
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
            host.setStatus(disabledStatus());
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
        openForActive();
    }

    /** Shows the active file's history in the tool window, leaving a folder listing if one is up. */
    private void openForActive() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !host.isLocalBuffer(b)) {
            host.setStatus(tr("status.history.noFile"));
            return;
        }
        if (panel.folderShown() != null) {
            panel.showFileView(); // refreshes
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
        List<FileHistoryPanel.FileGroup> groups = folderGroups(folder);
        if (groups.isEmpty()) {
            host.setStatus(tr("status.history.folderEmpty", folder.getFileName()));
            return;
        }
        folderGroupsShown = groups;
        panel.setFolderHistory(folder, groups);
        ops.setToolWindowAvailable(true);
        ops.openToolWindow();
    }

    /** The files under {@code folder} that have history in any project's bucket, with their revisions. */
    private List<FileHistoryPanel.FileGroup> folderGroups(Path folder) {
        var folderRevs = HistoryQueries.folderRevisions(mergedIndex(), historyKey(folder));
        List<FileHistoryPanel.FileGroup> groups = new ArrayList<>();
        for (var e : folderRevs.entrySet()) {
            Path p = Path.of(e.getKey());
            String display = folder.relativize(p).toString();
            boolean deleted = !Files.exists(p);
            groups.add(new FileHistoryPanel.FileGroup(e.getKey(), display, deleted, e.getValue()));
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
        EditorBuffer open = ops.openBufferFor(file);
        if (open != null && !open.isDisposed()) {
            if (open.isDirty() && !confirmOverUnsaved(file)) {
                completion.complete(RestoreResult.CANCELLED);
                return completion;
            }
            ops.openPath(file); // bring its tab forward: that is where the restored text appears
            restoreInto(open, revision, completion);
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
                                byte[] replacement = restoredBytes(
                                        encodingSourceFor(revision),
                                        text,
                                        target.expectedBytes(),
                                        charsetRuleFor(file));
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
        if (current == null || com.editora.diff.BinaryDiff.isProbablyBinary(current)) {
            kept.accept(true); // nothing there, or nothing Local History holds
            return;
        }
        String text = LineEndings.toLf(decodeCaptured(current, charsetRuleFor(file)));
        // Also while the feature is off: this is Local History's own destructive action, offered from a
        // listing that is still open, and the file it replaces may hold what no revision does.
        recordFor(file, text, HistoryRevision.REASON_EXTERNAL, "", false, null, true, kept);
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
                ops.openPath(file);
                ops.refreshProjectTree();
            }
            reportRestore(file, result);
            completion.complete(result);
        });
    }

    /** The {@code .editorconfig} charset the editor would use for {@code file}, or {@code null}. */
    private String charsetRuleFor(Path file) {
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
    static byte[] restoredBytes(String text, byte[] existing, String editorConfigCharset) {
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
    static byte[] restoredBytes(HistoryRevision recorded, String text, byte[] existing, String editorConfigCharset) {
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
    static String decodeCaptured(byte[] bytes, String editorConfigCharset) {
        return DiffSideText.decodeRaw(bytes, editorConfigCharset, null).text();
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
            String charsetRule = charsetRuleFor(file);
            if (isBinary(bytes, charsetRule)) {
                completion.accept(new DeleteCapture(true, bytes));
                return;
            }
            if (!historyEnabled) {
                completion.accept(new DeleteCapture(true, bytes));
                return;
            }
            EditorConfigCharset.Decoded decoded = DiffSideText.decodeRaw(bytes, charsetRule, null);
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
        } catch (IOException e) {
            completion.accept(new DeleteCapture(!historyEnabled, null));
        }
    }

    /**
     * What {@link #captureBeforeOverwriteDurably} found: {@code safe} when the file may be replaced,
     * {@code recorded} when its content is now a durable history revision, and the exact bytes that were read
     * (null when the file is too large to hold) so the caller can replace only an unchanged file.
     */
    record OverwriteCapture(boolean safe, boolean recorded, byte[] expectedBytes) {
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
        Objects.requireNonNull(completion, "completion");
        if (file == null) {
            completion.accept(new OverwriteCapture(false, false, null));
            return;
        }
        if (!com.editora.vfs.Vfs.isLocal(file)) {
            completion.accept(new OverwriteCapture(true, false, null)); // history is local-only by contract
            return;
        }
        try {
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                completion.accept(new OverwriteCapture(false, false, null));
                return;
            }
            if (Files.size(file) > EditorBuffer.LARGE_FILE_BYTES) {
                completion.accept(new OverwriteCapture(true, false, null));
                return;
            }
            byte[] bytes = Files.readAllBytes(file);
            if (!isEnabled() || com.editora.diff.BinaryDiff.isProbablyBinary(bytes)) {
                completion.accept(new OverwriteCapture(true, false, bytes));
                return;
            }
            String text = LineEndings.toLf(decodeCaptured(bytes, charsetRuleFor(file)));
            recordFor(
                    file,
                    text,
                    HistoryRevision.REASON_EXTERNAL,
                    "",
                    false,
                    durable -> onFx(() -> completion.accept(new OverwriteCapture(durable, durable, bytes))));
        } catch (IOException | RuntimeException failure) {
            completion.accept(new OverwriteCapture(false, false, null));
        }
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
        if (!Files.isRegularFile(files.get(index))) {
            // A symbolic link, or a file that is already gone: no text for Local History to hold.
            captureNextBeforeDelete(files, index + 1, completion);
            return;
        }
        // One file per FX turn: a folder of them must not hold the UI for the whole batch.
        captureBeforeDeleteDurably(
                files.get(index),
                capture -> Platform.runLater(() -> {
                    if (capture.durable()) {
                        captureNextBeforeDelete(files, index + 1, completion);
                    } else {
                        completion.accept(false);
                    }
                }));
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

    private void restoreInto(EditorBuffer b, HistoryRevision revision, CompletableFuture<RestoreResult> completion) {
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
                                RestoreResult result = diff.applyToLocal(target, text)
                                        ? RestoreResult.RESTORED
                                        : RestoreResult.APPLY_FAILED;
                                finishBufferRestore(target, completion, result);
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
        Path root = ops.projectRoot();
        Map<String, Boolean> gone = new java.util.HashMap<>(); // one look at the disk per file, not per row
        Function<HistoryRevision, String> label = r -> recentRowText(
                HistoryRowText.dateTimeText(r.timestamp(), ZoneId.systemDefault(), FileHistoryPanel.locale()),
                r.label(),
                historyReasonLabel(r.reason()));
        Function<HistoryRevision, String> detail = r -> recentDetail(
                r.path(),
                root,
                gone.computeIfAbsent(r.path(), HistoryCoordinator::isGone) ? tr("history.deleted") : null);
        QuickOpen<HistoryRevision> picker = new QuickOpen<>(
                tr("history.recent.title"),
                tr("history.recent.prompt"),
                () -> HistoryQueries.recent(mergedIndex(), 200),
                label,
                detail,
                r -> label.apply(r) + " " + r.path(), // the row leads with the time; people type the file's name
                this::openRecentChange);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
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
    static String recentDetail(String path, Path projectRoot, String deletedTag) {
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
        if (!Files.exists(file)) {
            Path parent = file.getParent();
            if (parent != null) {
                showFolderHistory(parent);
            }
            return;
        }
        ops.openPath(file);
        EditorBuffer active = host.activeBuffer();
        if (active == null
                || active.getPath() == null
                || !historyKey(active.getPath()).equals(historyKey(file))) {
            return; // it did not open (the reason is in the status bar): not another file's history
        }
        openForActive();
        panel.selectRevision(r);
    }

    /** Localized capture-reason label (mirrors {@code FileHistoryPanel.reasonLabel} for cross-file pickers). */
    static String historyReasonLabel(String reason) {
        return FileHistoryPanel.reasonLabel(reason); // one mapping: the picker and the panel name a reason alike
    }
}
