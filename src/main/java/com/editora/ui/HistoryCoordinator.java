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

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.history.HistoryBlobStore;
import com.editora.history.HistoryQueries;
import com.editora.history.HistoryRetention;
import com.editora.history.HistoryService;

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

    /** Window hooks beyond {@link CoordinatorHost} that the history flows need. */
    interface Ops {
        /** The active project's history bucket: per-file (absolute-path key) → revisions. */
        Map<String, List<HistoryRevision>> historyMap();

        /** Every project's history (project key → file key → revisions), for blob GC. */
        Map<String, Map<String, List<HistoryRevision>>> historyByProject();

        /** Persists {@code history.json}. */
        void saveHistory();

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
    private final FileHistoryPanel panel;

    HistoryCoordinator(CoordinatorHost host, DiffCoordinator diff, Ops ops) {
        this.host = host;
        this.diff = diff;
        this.ops = ops;
        this.historyService = new HistoryService(new HistoryBlobStore(ops.blobsDir()));
        this.panel = new FileHistoryPanel(historyActions());
    }

    /** The File History tool-window content (the {@code ToolWindow} itself stays in {@code MainController}). */
    FileHistoryPanel panel() {
        return panel;
    }

    void shutdown() {
        historyService.shutdown();
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
            panel.setRevisions(revs, b.getPath().getFileName().toString());
        } else {
            panel.setRevisions(List.of(), null);
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
        recordFor(buffer.getPath(), buffer.getContent(), reason, "", false);
    }

    /**
     * Records a snapshot of arbitrary {@code content} for {@code file} (not necessarily an open buffer — e.g.
     * a manual label, or a file captured at delete time), folding the pruned result into the per-project
     * bucket + persisting + GCing. {@code force} bypasses the unchanged-content skip; {@code label} is the
     * user name ({@code ""} for automatic revisions).
     */
    private void recordFor(Path file, String content, String reason, String label, boolean force) {
        if (!isEnabled() || file == null || content == null || !com.editora.vfs.Vfs.isLocal(file)) {
            return;
        }
        String key = historyKey(file);
        List<HistoryRevision> existing = ops.historyMap().getOrDefault(key, List.of());
        var s = host.settings();
        long maxAgeMillis = s.getHistoryMaxAgeDays() > 0 ? s.getHistoryMaxAgeDays() * 86_400_000L : 0;
        var policy = new HistoryRetention.RetentionPolicy(
                s.getHistoryMaxPerFile(), maxAgeMillis, (long) Math.max(0, s.getHistoryMaxTotalMb()) * 1024L * 1024L);
        long now = System.currentTimeMillis();
        historyService.snapshot(file, content, reason, label, force, existing, policy, now, updated -> {
            Map<String, List<HistoryRevision>> bucket = ops.historyMap();
            bucket.put(key, updated);
            // Enforce the per-project byte budget across the whole bucket, then persist + GC.
            var trimmed = HistoryRetention.enforceProjectBudget(bucket, policy.maxTotalBytesPerProject());
            bucket.clear();
            bucket.putAll(trimmed);
            ops.saveHistory();
            historyService.gc(HistoryRetention.liveHashes(ops.historyByProject()));
            EditorBuffer active = host.activeBuffer();
            if (active != null
                    && active.getPath() != null
                    && historyKey(active.getPath()).equals(key)) {
                refresh();
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
            public void openDiff(HistoryRevision revision) {
                openLocalHistoryDiff(revision);
            }

            @Override
            public void restore(HistoryRevision revision) {
                restoreHistory(revision);
            }

            @Override
            public void restoreToDisk(HistoryRevision revision) {
                restoreRevisionToDisk(revision);
            }
        };
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
    private void restoreRevisionToDisk(HistoryRevision revision) {
        if (revision == null || revision.path().isBlank()) {
            return;
        }
        Path file = Path.of(revision.path());
        if (Files.exists(file)) {
            Alert confirm = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    tr("history.restoreOverwrite", file.getFileName()),
                    ButtonType.OK,
                    ButtonType.CANCEL);
            confirm.initOwner(host.window());
            confirm.setTitle(tr("history.menu.restore"));
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }
        historyService.content(revision, text -> {
            if (text == null) {
                host.setStatus(tr("status.history.restoreFailed", file.getFileName()));
                return;
            }
            try {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, text);
            } catch (IOException e) {
                host.setStatus(tr("status.history.restoreFailed", file.getFileName()));
                return;
            }
            ops.openPath(file);
            ops.refreshProjectTree();
            host.setStatus(tr("status.history.restored", file.getFileName()));
        });
    }

    /** Snapshots {@code file}'s on-disk content into Local History just before it's deleted (in-app delete). */
    void captureBeforeDelete(Path file) {
        if (!isEnabled() || file == null || !com.editora.vfs.Vfs.isLocal(file)) {
            return;
        }
        String content = readTextForHistory(file);
        if (content != null) {
            recordFor(file, content, HistoryRevision.REASON_DELETE, "", true);
        }
    }

    /** Reads a local file as text for a history snapshot; null for a non-existent/binary/oversize/unreadable file. */
    private String readTextForHistory(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > EditorBuffer.LARGE_FILE_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file);
            for (byte b : bytes) {
                if (b == 0) {
                    return null; // looks binary — skip
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Opens a diff of {@code revision} (left, the snapshot) vs the active file's current text (right). The
     * current side is editable so the diff's apply-chevrons let you copy individual fragments from the
     * snapshot back into the file (IntelliJ-style selective restore); the panel's "Restore This Version"
     * still does the whole-file revert.
     */
    private void openLocalHistoryDiff(HistoryRevision revision) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            return;
        }
        Path target = b.getPath();
        String name = target.getFileName().toString();
        java.util.List<HistoryRevision> revs = ops.historyMap().getOrDefault(historyKey(target), java.util.List.of());
        if (revs.isEmpty()) {
            host.setStatus(tr("history.window.none"));
            return;
        }
        // Per-hunk "apply change" (IntelliJ-style selective restore): copy a fragment from the snapshot
        // into the current file through DiffCoordinator's undoable-buffer path (Undo/Save act on it too).
        LocalHistoryWindow.ApplySupport apply = new LocalHistoryWindow.ApplySupport(
                newText -> diff.applyToLocal(target, newText),
                () -> diff.undoLocal(target),
                () -> diff.saveLocal(target));
        LocalHistoryWindow window = new LocalHistoryWindow(
                host.window(),
                name,
                target,
                revs,
                () -> ops.currentTextOf(target),
                (rev, cb) -> historyService.content(rev, text -> cb.accept(text == null ? "" : text)),
                diff::computeDiff,
                this::restoreHistory,
                apply,
                host.settings());
        window.show(revision);
    }

    /** Restores {@code revision}'s content into the active file via an undoable whole-file replace. */
    private void restoreHistory(HistoryRevision revision) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || revision == null) {
            return;
        }
        Path target = b.getPath();
        historyService.content(revision, text -> {
            if (text == null) {
                host.setStatus(tr("status.history.restoreFailed", target.getFileName()));
                return;
            }
            diff.applyToLocal(target, text);
            host.setStatus(tr("status.history.restored", target.getFileName()));
        });
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
