package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import com.editora.editor.UiZoom;
import com.editora.lsp.LspManager;
import com.editora.ui.LspCoordinator.WorkspaceFileIdentity;
import com.editora.ui.LspCoordinator.WorkspaceFileOperations;
import com.editora.ui.WorkspaceEditStaging.StagedCreate;
import com.editora.ui.WorkspaceEditStaging.StagedDelete;
import com.editora.ui.WorkspaceEditStaging.StagedRename;
import com.editora.ui.WorkspaceEditStaging.WorkspaceTransactionStatus;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Applies a language server's workspace edits for {@link LspCoordinator}: a code action's, a refactoring's, a
 * rename's or a server's own {@code workspace/applyEdit}. Owns the all-or-nothing pipeline — the checks that the
 * targets are still what the server read, the Local History copies of what an edit destroys, the staged
 * filesystem transaction ({@link WorkspaceEditStaging}), the buffer edits, the tab remapping and what follows a
 * committed edit — plus the outcome reporting ({@link #beginReportedEdit} / {@link #reportEdit}) and the offer to
 * recover a transaction a crash interrupted. Unopened files load and filesystem work runs on the workspace-edit
 * worker; buffer validation, RichTextFX edits and UI bookkeeping run on the FX thread.
 */
final class LspWorkspaceEdits {

    /** What the edit pipeline needs of {@link LspCoordinator}, resolved when called. */
    interface Host {
        /** The path {@code buffer}'s document is registered under with its server (an untitled one's too). */
        @Nullable
        Path lspPath(EditorBuffer buffer);

        /** The open buffer whose LSP document is {@code path}, or null. */
        @Nullable
        EditorBuffer bufferForLspPath(@Nullable Path path);

        void clearDiagnostics(@Nullable Path file);

        void syncBufferWhenShown(EditorBuffer buffer);

        /** Sends {@code buffer} to its server now, even when its sync was deferred until the tab is shown. */
        void syncBufferNow(EditorBuffer buffer);

        /** Remembers an applied edit for Undo Last Refactoring ({@link LspRefactoringUndo#recordEdit}). */
        void recordEdit(
                com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
                java.util.Set<EditorBuffer> touched,
                Map<EditorBuffer, String> before,
                java.util.Set<Path> created);
    }

    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final LspCoordinator.Ops ops;
    private final Host lsp;
    private final WorkspaceFileOperations workspaceFiles;
    private final Executor workspaceExecutor;
    private final WorkspaceEditStaging staging;

    LspWorkspaceEdits(
            CoordinatorHost host,
            LspManager lspManager,
            LspCoordinator.Ops ops,
            Host lsp,
            WorkspaceFileOperations workspaceFiles,
            Executor workspaceExecutor) {
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
        this.lsp = lsp;
        this.workspaceFiles = workspaceFiles;
        this.workspaceExecutor = workspaceExecutor;
        this.staging = new WorkspaceEditStaging(workspaceFiles, this::journalDir);
    }

    /** The filesystem as the workspace-edit transaction sees it, for classifying destructive operations. */
    private com.editora.lsp.WorkspaceEditHazards.Disk workspaceDisk() {
        return WorkspaceEditFiles.workspaceDisk(workspaceFiles);
    }

    /**
     * Runs {@code next} on the FX thread with a view of the disk for {@code paths}: the live disk when they are
     * all local (a stat there is cheap, and the check stays in this FX turn), else a {@link WorkspaceFileSnapshot}
     * taken on the workspace worker first — on a remote host every stat is a round trip, and a rename touching
     * a few dozen files froze the window for the length of them.
     */
    void withDiskView(java.util.Collection<Path> paths, java.util.function.Consumer<WorkspaceFileOperations> next) {
        if (!WorkspaceFileSnapshot.anyRemote(paths)) {
            next.accept(workspaceFiles);
            return;
        }
        workspaceExecutor.execute(() -> {
            WorkspaceFileSnapshot snapshot = WorkspaceFileSnapshot.of(workspaceFiles, paths);
            Platform.runLater(() -> next.accept(snapshot));
        });
    }

    /** The files that made the workspace edit in progress unsafe to apply; consumed by {@link #reportEdit}. */
    private List<Path> blockedTargets = List.of();

    /**
     * Starts an operation whose outcome {@link #reportEdit} will announce. The blocked-files note belongs to
     * one operation: a server-initiated edit that was blocked earlier has no {@code reportEdit} of its own
     * to consume it, and left standing it silenced the failure message of the next, unrelated operation.
     */
    void beginReportedEdit() {
        blockedTargets = List.of();
    }

    /**
     * A workspace edit was refused because of specific files — changed since the request, unsaved, or not
     * loaded — rather than for a reason nobody can act on. Names them, so "Rename failed" is not all the
     * user gets when the fix is to save or reload one file.
     */
    void editBlocked(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        blockedTargets = List.copyOf(files);
        host.setError(tr("status.lsp.editBlocked", blockedFileNames(files)));
    }

    /** As {@link #editBlocked}, for a refusal with a reason of its own to give. */
    void editRefused(List<Path> files, String message) {
        blockedTargets = files == null || files.isEmpty() ? List.of(Path.of("")) : List.copyOf(files);
        host.setError(message);
    }

    /** Pure: up to three file names, then a count of the rest. */
    static String blockedFileNames(List<Path> files) {
        StringBuilder names = new StringBuilder();
        int shown = Math.min(3, files.size());
        for (int i = 0; i < shown; i++) {
            Path name = files.get(i).getFileName();
            names.append(i == 0 ? "" : ", ").append(name == null ? files.get(i) : name);
        }
        if (files.size() > shown) {
            names.append(" +").append(files.size() - shown);
        }
        return names.toString();
    }

    /** Reports an edit's outcome. A failure whose blocking files were already named keeps that message. */
    void reportEdit(boolean ok, String success, String failure) {
        boolean named = !blockedTargets.isEmpty();
        blockedTargets = List.of();
        if (ok) {
            host.setStatus(success);
        } else if (!named) {
            host.setStatus(failure);
        }
    }

    /**
     * Whether an edit to a file the server did not have open may be applied to {@code buffer}. The server
     * computed it from the file on disk, so three things must hold: no window has unsaved changes to the
     * file, the buffer mirrors the file as it is on disk now (its snapshot was taken by a load or a save and
     * still matches), and the file has not been modified since the request was sent. Anything that cannot
     * be shown refuses the edit.
     */
    private boolean diskPreimageHolds(
            com.editora.lsp.WorkspaceEditMapper.FileEdit edit,
            EditorBuffer buffer,
            Path onDiskAt,
            WorkspaceFileOperations files) {
        Long since = edit.diskPreimageAt();
        if (since == null) {
            return true;
        }
        if (buffer.isDirty() || ops.buffersAtOrUnder(edit.file()).stream().anyMatch(EditorBuffer::isDirty)) {
            return false;
        }
        try {
            WorkspaceFileIdentity onDisk =
                    files.identity(onDiskAt.toAbsolutePath().normalize());
            EditorBuffer.DiskSnapshot loaded = buffer.diskSnapshot();
            // The content fingerprint is recorded only by a load or a save — the two moments the buffer's
            // text is known to be the file's. Answering "changed on disk" with Keep re-baselines the time
            // and size without either, leaving a clean buffer that holds different text from the file; the
            // snapshot then matched the disk and the server's edit landed at the wrong offsets.
            return loaded.modifiedMillis() >= 0
                    && loaded.fingerprint() != null
                    && onDisk != null
                    && !loaded.differsFrom(onDisk.lastModifiedMillis(), onDisk.size())
                    && com.editora.lsp.WorkspaceEditMapper.unchangedSince(onDiskAt, onDisk.lastModifiedMillis(), since);
        } catch (java.io.IOException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * The disk-preimage targets of {@code mapped} that no longer hold, for {@link #editBlocked}.
     *
     * <p>{@code movedTo} is where the staged renames have <b>already put</b> each rename source (normalized
     * source → destination; empty before staging). A target the same edit also moves is no longer at its
     * old path, so it is examined where it now is: a move keeps size and modification time, which is all
     * the comparison reads. Looking at the old path instead refused every class rename requested from a
     * usage site, because the declaring file had just been moved by this very edit.
     *
     * <p>{@code skipUnloaded} leaves out targets with no buffer yet — the files this edit creates, which are
     * loaded only after staging and are checked by the pass that follows it.
     */
    private List<Path> staleDiskTargets(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            Map<Path, Path> movedTo,
            boolean skipUnloaded,
            WorkspaceFileOperations files) {
        List<Path> stale = new java.util.ArrayList<>();
        for (int i = 0; i < mapped.edits().size() && i < buffers.size(); i++) {
            var edit = mapped.edits().get(i);
            EditorBuffer buffer = buffers.get(i);
            if (edit.diskPreimageAt() == null || (buffer == null && skipUnloaded)) {
                continue;
            }
            Path onDiskAt = movedTo.getOrDefault(edit.file().toAbsolutePath().normalize(), edit.file());
            if (buffer == null || !diskPreimageHolds(edit, buffer, onDiskAt, files)) {
                stale.add(edit.file());
            }
        }
        return stale;
    }

    /**
     * The open buffer for a path a workspace edit is about to move or delete, with the buffer's <b>own</b>
     * path — resolved while the file still exists. Afterwards the server's spelling of the old path can no
     * longer be canonicalised, so under a symlinked project (jdtls answers with the resolved path) looking the
     * tab up again by that spelling finds nothing and the tab is left behind on a file that is gone.
     */
    private record OpenTarget(EditorBuffer buffer, Path path) {}

    private Map<Path, OpenTarget> openTargets(java.util.stream.Stream<Path> files) {
        Map<Path, OpenTarget> open = new java.util.HashMap<>();
        files.forEach(file -> {
            EditorBuffer buffer = lsp.bufferForLspPath(file);
            Path path = buffer == null ? null : lsp.lspPath(buffer);
            if (buffer != null && path != null) {
                open.put(file.toAbsolutePath().normalize(), new OpenTarget(buffer, path));
            }
        });
        return open;
    }

    private Map<Path, OpenTarget> openResourceTargets(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        return openTargets(java.util.stream.Stream.concat(
                mapped.renames().stream().map(com.editora.lsp.WorkspaceEditMapper.FileRename::from),
                mapped.deletes().stream().map(deletion -> deletion.file())));
    }

    /**
     * Pure: {@code serverTo} written the way the tab spells its path. {@code tabFrom} and {@code serverFrom}
     * name the same file; the trailing names they share are stripped to find where the two spellings part
     * ({@code ~/dev/app} against {@code /mnt/data/dev/app}), and a destination under the server's side is
     * re-rooted on the tab's, so the renamed tab stays under the project and LSP root it was opened in.
     */
    static Path inSpellingOf(Path tabFrom, Path serverFrom, Path serverTo) {
        Path tab = tabFrom.toAbsolutePath().normalize();
        Path server = serverFrom.toAbsolutePath().normalize();
        Path to = serverTo.toAbsolutePath().normalize();
        if (tab.equals(server)) {
            return serverTo;
        }
        while (tab.getParent() != null
                && server.getParent() != null
                && java.util.Objects.equals(tab.getFileName(), server.getFileName())) {
            tab = tab.getParent();
            server = server.getParent();
        }
        return to.startsWith(server) ? tab.resolve(server.relativize(to).toString()) : serverTo;
    }

    /** Whether every moved-or-deleted file that had a tab still has that same tab, at the same path. */
    private boolean openTargetsCurrent(Map<Path, OpenTarget> targets) {
        for (OpenTarget target : targets.values()) {
            EditorBuffer buffer = target.buffer();
            if (buffer.isDisposed()
                    || !target.path().equals(lsp.lspPath(buffer))
                    || lsp.bufferForLspPath(target.path()) != buffer) {
                return false;
            }
        }
        return true;
    }

    /**
     * Moves each renamed file's open tab to the new path, by buffer identity, and retires the old LSP
     * document under the path it was opened with. Returns the tabs that did <b>not</b> follow their file —
     * the caller must not report success for those.
     */
    private List<Path> remapRenamedBuffers(java.util.List<StagedRename> renames, Map<Path, OpenTarget> targets) {
        List<Path> orphaned = new java.util.ArrayList<>();
        for (StagedRename item : renames) {
            var rename = item.rename();
            OpenTarget target = targets.get(rename.from().toAbsolutePath().normalize());
            EditorBuffer open = target == null ? null : target.buffer();
            Path from = target == null ? rename.from() : target.path();
            Path to = target == null ? rename.to() : inSpellingOf(from, rename.from(), rename.to());
            if (lspManager.isManaged(from)) {
                lspManager.closeDocument(from); // didClose the OLD uri before the buffer re-opens as new
            }
            lsp.clearDiagnostics(from);
            lsp.clearDiagnostics(rename.from());
            ops.fileRenamed(from, to); // remaps the open buffer's path + tab + session state
            if (open != null) {
                if (from.equals(lsp.lspPath(open))) {
                    orphaned.add(from);
                } else {
                    lsp.syncBufferWhenShown(open); // re-open the document under its NEW uri
                }
            }
        }
        return orphaned;
    }

    /** Closes the LSP document and the tab of each deleted file, under the path the tab was opened with. */
    private void retireDeleted(java.util.List<StagedDelete> deletes, Map<Path, OpenTarget> targets) {
        for (StagedDelete item : deletes) {
            Path file = item.operation().file();
            OpenTarget target = targets.get(file.toAbsolutePath().normalize());
            Path opened = target == null ? file : target.path();
            if (lspManager.isManaged(opened)) {
                lspManager.closeDocument(opened);
            }
            lsp.clearDiagnostics(opened);
            lsp.clearDiagnostics(file);
            ops.fileDeleted(opened);
        }
    }

    /** A file was moved but its open tab could not follow: say which, instead of reporting success. */
    private void tabsNotRemapped(List<Path> files) {
        blockedTargets = List.copyOf(files);
        host.setError(tr("status.lsp.editTabNotRemapped", blockedFileNames(files)));
    }

    /** Asks before an unpreviewed edit destroys something on disk; a field so a test can answer. */
    java.util.function.Predicate<List<com.editora.lsp.WorkspaceEditHazards.Hazard>> destructiveEditConfirmer =
            this::confirmDestructiveEdit;

    private boolean confirmDestructiveEdit(List<com.editora.lsp.WorkspaceEditHazards.Hazard> hazards) {
        StringBuilder lines = new StringBuilder();
        for (var hazard : hazards) {
            lines.append(lines.isEmpty() ? "" : "\n")
                    .append(tr(
                            hazard.kind() == com.editora.lsp.WorkspaceEditHazards.Kind.DELETE_DIRECTORY
                                    ? "dialog.lsp.destructiveEdit.deleteFolder"
                                    : "dialog.lsp.destructiveEdit.overwrite",
                            ops.homeCollapsed(hazard.path().toString())));
        }
        javafx.scene.control.ButtonType apply = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.destructiveEdit.apply"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType cancel = Dialogs.cancel();
        javafx.scene.control.Alert confirm = Dialogs.styled(new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING, lines.toString(), cancel, apply));
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.lsp.destructiveEdit.title"));
        confirm.setHeaderText(tr("dialog.lsp.destructiveEdit.header"));
        UiZoom.minWidth(confirm.getDialogPane(), 520);
        // The edit deletes or overwrites files: its button is red, and Enter answers Cancel, the default.
        Dialogs.destructive(confirm, apply);
        return confirm.showAndWait().orElse(cancel) == apply;
    }

    /**
     * The applier for edits nobody previewed — a code action, a refactoring command, a server's
     * {@code workspace/applyEdit}. One that deletes a folder with its contents or replaces an existing
     * file is applied only after the user has seen the paths and agreed (a rename shows them as preview
     * rows instead).
     */
    void applyWorkspaceEditsConfirmed(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.function.Consumer<Boolean> done) {
        withDiskView(WorkspaceEditFiles.resourcePaths(mapped), files -> {
            List<com.editora.lsp.WorkspaceEditHazards.Hazard> hazards =
                    com.editora.lsp.WorkspaceEditHazards.needingConfirmation(
                            mapped, WorkspaceEditFiles.workspaceDisk(files));
            if (!hazards.isEmpty() && !destructiveEditConfirmer.test(hazards)) {
                editRefused(
                        hazards.stream()
                                .map(com.editora.lsp.WorkspaceEditHazards.Hazard::path)
                                .toList(),
                        tr("status.lsp.destructiveEditDeclined"));
                done.accept(false);
                return;
            }
            applyWorkspaceEditsAsync(mapped, done);
        });
    }

    /** Most files of one deleted folder that are copied to Local History; a larger folder is not walked further. */
    static final int MAX_CAPTURED_PER_EDIT = 200;

    /**
     * Copies what the edit is about to delete or replace into Local History, then continues on the FX
     * thread with whether every copy was made. Runs before staging, while each file is still at the path
     * its history belongs to. A folder is listed off the FX thread; an edit with nothing to lose continues
     * at once.
     */
    private void captureBeforeDestruction(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.function.Consumer<Boolean> done) {
        withDiskView(WorkspaceEditFiles.resourcePaths(mapped), disk -> captureBeforeDestruction(mapped, disk, done));
    }

    private void captureBeforeDestruction(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            WorkspaceFileOperations disk,
            java.util.function.Consumer<Boolean> done) {
        List<com.editora.lsp.WorkspaceEditHazards.Hazard> hazards =
                com.editora.lsp.WorkspaceEditHazards.of(mapped, WorkspaceEditFiles.workspaceDisk(disk));
        if (hazards.isEmpty()) {
            done.accept(true);
            return;
        }
        boolean folders =
                hazards.stream().anyMatch(h -> h.kind() == com.editora.lsp.WorkspaceEditHazards.Kind.DELETE_DIRECTORY);
        if (!folders) {
            captureEach(
                    hazards.stream()
                            .map(com.editora.lsp.WorkspaceEditHazards.Hazard::path)
                            .toList(),
                    0,
                    done);
            return;
        }
        workspaceExecutor.execute(() -> {
            List<Path> files = new java.util.ArrayList<>();
            for (var hazard : hazards) {
                if (hazard.kind() != com.editora.lsp.WorkspaceEditHazards.Kind.DELETE_DIRECTORY) {
                    files.add(hazard.path());
                    continue;
                }
                try {
                    for (Path path : workspaceFiles.walk(hazard.path())) {
                        if (files.size() >= MAX_CAPTURED_PER_EDIT) {
                            break;
                        }
                        if (workspaceFiles.isRegularFile(path)) {
                            files.add(path);
                        }
                    }
                } catch (java.io.IOException | RuntimeException unreadable) {
                    // Staging will fail on the same folder and refuse the edit; nothing to copy from here.
                }
            }
            Platform.runLater(() -> captureEach(files, 0, done));
        });
    }

    private void captureEach(List<Path> files, int index, java.util.function.Consumer<Boolean> done) {
        if (index >= files.size()) {
            done.accept(true);
            return;
        }
        Path file = files.get(index);
        ops.captureBeforeDestruction(file, kept -> {
            if (!kept) {
                editRefused(List.of(file), tr("status.lsp.editHistoryFailed", blockedFileNames(List.of(file))));
                done.accept(false);
                return;
            }
            captureEach(files, index + 1, done);
        });
    }

    /**
     * Production workspace-edit path. Unopened files are decoded through the host's background loader and
     * create/move/delete operations run on a virtual thread; only buffer validation, RichTextFX edits and UI
     * bookkeeping return to the FX thread.
     */
    void applyWorkspaceEditsAsync(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.function.Consumer<Boolean> whenDone) {
        java.util.Set<EditorBuffer> before = openBuffers();
        openBeforeEdit.put(mapped, before);
        java.util.function.Consumer<Boolean> done = ok -> {
            openBeforeEdit.remove(mapped);
            if (!committedEdits.remove(mapped) && !ok) {
                WorkspaceEditTabs.discardOpened(mapped, before, lsp::bufferForLspPath, ops::closeBackgroundBuffer);
            }
            whenDone.accept(ok);
        };
        if (!resourceTargetsSafe(mapped)) {
            resourceTargetsBlocked(mapped);
            done.accept(false);
            return;
        }
        invalidateResourceWrites(mapped);
        java.util.List<EditorBuffer> buffers =
                new java.util.ArrayList<>(mapped.edits().size());
        java.util.Set<Path> createdPaths = mapped.creates().stream()
                .map(c -> c.file().toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toSet());
        collectWorkspaceBuffers(
                mapped,
                createdPaths,
                0,
                buffers,
                () -> withDiskView(WorkspaceEditFiles.preimagePaths(mapped, Map.of()), files -> {
                    // Before anything is staged: a target the server did not have open must still be the file
                    // it read. Afterwards the same question is asked again where a staged rename moved it.
                    List<Path> stale = staleDiskTargets(mapped, buffers, Map.of(), true, files);
                    if (!stale.isEmpty()) {
                        editBlocked(stale);
                        done.accept(false);
                        return;
                    }
                    Map<Path, OpenTarget> targets = openResourceTargets(mapped); // while the files still exist
                    captureBeforeDestruction(mapped, captured -> {
                        if (captured) {
                            stageWorkspaceEdit(mapped, buffers, targets, done);
                        } else {
                            done.accept(false);
                        }
                    });
                }));
    }

    /** Every path {@link #finishWorkspaceEdit}'s checks read. */
    private List<Path> finishPaths(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<StagedCreate> creates,
            java.util.List<StagedRename> renames,
            java.util.List<StagedDelete> deletes) {
        List<Path> out = new java.util.ArrayList<>();
        creates.forEach(item -> out.add(item.operation().file()));
        renames.forEach(item -> {
            out.add(item.rename().from());
            out.add(item.rename().to());
        });
        deletes.forEach(item -> {
            out.add(item.operation().file());
            out.add(item.stage());
        });
        out.addAll(WorkspaceEditFiles.preimagePaths(mapped, WorkspaceEditStaging.movedTo(renames)));
        return out;
    }

    /** Stages the edit's filesystem operations off the FX thread, then finishes (or rolls back) on it. */
    private void stageWorkspaceEdit(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            Map<Path, OpenTarget> targets,
            java.util.function.Consumer<Boolean> done) {
        workspaceExecutor.execute(() -> {
            WorkspaceTransactionStatus transaction = staging.beginTransaction(mapped);
            java.util.List<StagedCreate> creates = staging.stageCreates(mapped.creates(), transaction);
            java.util.List<StagedRename> renames =
                    creates == null ? null : staging.stageRenames(mapped.renames(), transaction);
            java.util.List<StagedDelete> deletes =
                    creates == null || renames == null ? null : staging.stageDeletes(mapped.deletes(), transaction);
            if (creates == null || renames == null || deletes == null) {
                if (renames != null) {
                    staging.rollbackRenames(renames, transaction);
                }
                if (creates != null) {
                    staging.rollbackCreates(creates, transaction);
                }
                staging.endTransaction(transaction);
                Platform.runLater(() -> {
                    reportIncompleteRollback(transaction);
                    done.accept(false);
                });
                return;
            }
            // The finish step checks the staged files and the preimages where the renames put them, on the FX
            // thread: for remote files those stats are taken here, on the worker (see withDiskView).
            List<Path> checked = finishPaths(mapped, creates, renames, deletes);
            WorkspaceFileOperations files = WorkspaceFileSnapshot.anyRemote(checked)
                    ? WorkspaceFileSnapshot.of(workspaceFiles, checked)
                    : workspaceFiles;
            Platform.runLater(() -> collectCreatedWorkspaceBuffers(
                    mapped,
                    buffers,
                    0,
                    () -> finishWorkspaceEdit(
                            mapped, buffers, creates, renames, deletes, targets, transaction, files, done)));
        });
    }

    private void invalidateResourceWrites(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        java.util.Set<Path> paths = new java.util.LinkedHashSet<>();
        mapped.creates().forEach(operation -> paths.add(operation.file()));
        mapped.renames().forEach(operation -> {
            paths.add(operation.from());
            paths.add(operation.to());
        });
        mapped.deletes().forEach(operation -> paths.add(operation.file()));
        paths.forEach(ops::invalidatePendingWrite);
    }

    private void collectWorkspaceBuffers(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.Set<Path> createdPaths,
            int index,
            java.util.List<EditorBuffer> buffers,
            Runnable done) {
        if (index >= mapped.edits().size()) {
            done.run();
            return;
        }
        var edit = mapped.edits().get(index);
        EditorBuffer open = lsp.bufferForLspPath(edit.file());
        if (open != null || createdPaths.contains(edit.file().toAbsolutePath().normalize())) {
            buffers.add(open);
            collectWorkspaceBuffers(mapped, createdPaths, index + 1, buffers, done);
            return;
        }
        ops.openBackgroundBufferAsync(edit.file(), buffer -> {
            buffers.add(buffer);
            collectWorkspaceBuffers(mapped, createdPaths, index + 1, buffers, done);
        });
    }

    private void collectCreatedWorkspaceBuffers(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            int index,
            Runnable done) {
        if (index >= buffers.size()) {
            done.run();
            return;
        }
        if (buffers.get(index) != null) {
            collectCreatedWorkspaceBuffers(mapped, buffers, index + 1, done);
            return;
        }
        int slot = index;
        ops.openBackgroundBufferAsync(mapped.edits().get(index).file(), buffer -> {
            buffers.set(slot, buffer);
            collectCreatedWorkspaceBuffers(mapped, buffers, slot + 1, done);
        });
    }

    /**
     * Applies a workspace edit's per-file batches through undoable buffers (FX thread; the manager's apply-edit
     * handler, {@link #applyWorkspaceEditsConfirmed}, ends here — both a picked action's inline edit and a
     * server-initiated {@code workspace/applyEdit} land here). All-or-nothing: if any touched file can't be opened editable,
     * nothing is applied — half a refactoring corrupts the workspace. A file with no open tab opens in a
     * background tab so its change is visible and undoable.
     *
     * <p>Package-private so {@code LspWorkspaceEditFxTest} can drive it: this is the only LSP path that
     * writes and MOVES files on disk, so its all-or-nothing refusals need direct tests.
     */
    boolean applyWorkspaceEdits(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        if (!resourceTargetsSafe(mapped)) {
            resourceTargetsBlocked(mapped);
            return false;
        }
        invalidateResourceWrites(mapped);
        java.util.Set<EditorBuffer> before = openBuffers();
        WorkspaceTransactionStatus transaction = staging.beginTransaction(mapped);
        boolean applied = applyWorkspaceEdits(mapped, transaction);
        staging.endTransaction(transaction);
        if (!committedEdits.remove(mapped) && !applied) {
            WorkspaceEditTabs.discardOpened(mapped, before, lsp::bufferForLspPath, ops::closeBackgroundBuffer);
        }
        return applied;
    }

    private boolean applyWorkspaceEdits(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, WorkspaceTransactionStatus transaction) {
        java.util.Set<EditorBuffer> openBefore = openBuffers();
        var files = mapped.edits();
        java.util.List<StagedCreate> creates = staging.stageCreates(mapped.creates(), transaction);
        if (creates == null) {
            reportIncompleteRollback(transaction);
            return false;
        }
        java.util.List<EditorBuffer> buffers = new java.util.ArrayList<>(files.size());
        for (var fe : files) {
            EditorBuffer buf = lsp.bufferForLspPath(fe.file());
            if (buf == null) {
                buf = ops.openBackgroundBuffer(fe.file());
            }
            if (buf == null || !buf.isEditable() || buf.isNarrowed()) {
                staging.rollbackCreates(creates, transaction);
                reportIncompleteRollback(transaction);
                return false;
            }
            // A versioned edit is valid only for the exact server document it names. JDT LS commonly sends
            // a null version, so request-time text is retained as the equivalent guard for those edits.
            if (fe.version() != null
                    && !java.util.Objects.equals(fe.version(), lspManager.documentVersion(fe.file()))) {
                staging.rollbackCreates(creates, transaction);
                reportIncompleteRollback(transaction);
                return false;
            }
            if (fe.expectedText() != null && !fe.expectedText().equals(buf.getContent())) {
                staging.rollbackCreates(creates, transaction);
                reportIncompleteRollback(transaction);
                return false;
            }
            buffers.add(buf);
        }
        List<Path> stale = staleDiskTargets(mapped, buffers, Map.of(), false, workspaceFiles);
        if (!stale.isEmpty()) {
            editBlocked(stale);
            staging.rollbackCreates(creates, transaction);
            reportIncompleteRollback(transaction);
            return false;
        }
        Map<Path, OpenTarget> targets = openResourceTargets(mapped); // while the files still exist
        java.util.List<StagedRename> staged = staging.stageRenames(mapped.renames(), transaction);
        if (staged == null) {
            staging.rollbackCreates(creates, transaction);
            reportIncompleteRollback(transaction);
            return false;
        }
        java.util.List<StagedDelete> deletes = staging.stageDeletes(mapped.deletes(), transaction);
        if (deletes == null) {
            staging.rollbackRenames(staged, transaction);
            staging.rollbackCreates(creates, transaction);
            reportIncompleteRollback(transaction);
            return false;
        }
        boolean safe = resourceTargetsSafe(mapped);
        List<Path> unplaceable = safe ? unplaceableTargets(mapped, buffers) : List.of();
        if (!staging.resourceStateCurrent(workspaceFiles, creates, staged, deletes)
                || !safe
                || !unplaceable.isEmpty()) {
            if (!safe) {
                resourceTargetsBlocked(mapped);
            }
            editUnplaceable(unplaceable);
            staging.rollbackDeletes(deletes, transaction);
            staging.rollbackRenames(staged, transaction);
            staging.rollbackCreates(creates, transaction);
            reportIncompleteRollback(transaction);
            return false;
        }
        Map<EditorBuffer, String> before = WorkspaceEditFiles.textsOf(buffers);
        if (!applyWorkspaceTextEdits(mapped, buffers, transaction)) {
            staging.rollbackDeletes(deletes, transaction);
            staging.rollbackRenames(staged, transaction);
            staging.rollbackCreates(creates, transaction);
            reportIncompleteRollback(transaction);
            return false;
        }
        // The filesystem transaction completed before any text changed. Now remap open buffers/session state
        // in protocol order; this part is in-memory and cannot leave a failed disk move behind.
        transaction.journal.committed();
        committedEdits.add(mapped);
        List<Path> orphaned = remapRenamedBuffers(staged, targets);
        for (StagedCreate created : creates) {
            ops.fileCreated(created.operation().file());
        }
        retireDeleted(deletes, targets);
        staging.commitRenames(staged);
        staging.commitCreates(creates);
        staging.commitDeletes(deletes);
        lspManager.notifyWatchedFiles(WorkspaceEditStaging.resourceEvents(staged, creates, deletes));
        if (!orphaned.isEmpty()) {
            tabsNotRemapped(orphaned);
        }
        afterWorkspaceEdit(mapped, buffers, before, openBefore);
        return orphaned.isEmpty();
    }

    private void finishWorkspaceEdit(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            java.util.List<StagedCreate> creates,
            java.util.List<StagedRename> renames,
            java.util.List<StagedDelete> deletes,
            Map<Path, OpenTarget> targets,
            WorkspaceTransactionStatus transaction,
            WorkspaceFileOperations files,
            java.util.function.Consumer<Boolean> done) {
        // Supersede saves started while the resource transaction was staging, before UI identity changes.
        invalidateResourceWrites(mapped);
        if (!staging.resourceStateCurrent(files, creates, renames, deletes) || !openTargetsCurrent(targets)) {
            rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
            return;
        }
        // The preflight ran before files were loaded and staged, several FX turns ago: a keystroke since
        // then — in any window — may have dirtied a buffer this edit is about to close with its file.
        // Asked again here, in the same FX turn that retires the tabs, nothing can slip in between.
        if (!resourceTargetsSafe(mapped)) {
            resourceTargetsBlocked(mapped);
            rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
            return;
        }
        Map<Path, Path> movedTo = WorkspaceEditStaging.movedTo(renames);
        List<Path> stale = staleDiskTargets(mapped, buffers, movedTo, false, files);
        if (!stale.isEmpty()) {
            editBlocked(stale);
            rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
            return;
        }
        for (int i = 0; i < mapped.edits().size(); i++) {
            var edit = mapped.edits().get(i);
            EditorBuffer buffer = buffers.get(i);
            // A file this edit has just moved is gone from its old path, where the server's spelling can
            // no longer be resolved to the tab's: ask by the tab's own path, pinned before the move.
            OpenTarget moved = targets.get(edit.file().toAbsolutePath().normalize());
            boolean relocated = movedTo.containsKey(edit.file().toAbsolutePath().normalize());
            if (buffer == null
                    || buffer.isDisposed()
                    || (relocated
                            ? moved == null || moved.buffer() != buffer
                            : lsp.bufferForLspPath(edit.file()) != buffer)
                    || !buffer.isEditable()
                    || buffer.isNarrowed()
                    || (edit.version() != null
                            && !java.util.Objects.equals(edit.version(), lspManager.documentVersion(edit.file())))
                    || (edit.expectedText() != null && !edit.expectedText().equals(buffer.getContent()))) {
                rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
                return;
            }
        }
        List<Path> unplaceable = unplaceableTargets(mapped, buffers);
        if (!unplaceable.isEmpty()) {
            editUnplaceable(unplaceable); // before any buffer changes: all of the edit lands, or none of it
            rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
            return;
        }
        // Buffers without undo (large-file mode) keep a Local History copy first; one refusal stops the edit.
        for (EditorBuffer buffer : new java.util.LinkedHashSet<>(buffers)) {
            if (!NoUndoGuard.allow(buffer, tr("noUndo.op.lsp"))) {
                rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
                return;
            }
        }
        Map<EditorBuffer, String> before = WorkspaceEditFiles.textsOf(buffers);
        if (!applyWorkspaceTextEdits(mapped, buffers, transaction)) {
            rollbackWorkspaceAsync(creates, renames, deletes, transaction, done);
            return;
        }
        transaction.journal.committed();
        committedEdits.add(mapped);
        List<Path> orphaned = remapRenamedBuffers(renames, targets);
        creates.forEach(created -> ops.fileCreated(created.operation().file()));
        retireDeleted(deletes, targets);
        workspaceExecutor.execute(() -> {
            staging.commitRenames(renames);
            staging.commitCreates(creates);
            staging.commitDeletes(deletes);
            transaction.journal.close();
            lspManager.notifyWatchedFiles(WorkspaceEditStaging.resourceEvents(renames, creates, deletes));
        });
        if (!orphaned.isEmpty()) {
            tabsNotRemapped(orphaned); // the text is edited and the file moved; never call that "applied"
        }
        afterWorkspaceEdit(mapped, buffers, before, openBeforeEdit.get(mapped));
        done.accept(orphaned.isEmpty());
    }

    // --- after an edit: save, close what it opened, remember it for Undo (Java review B1, B2, B18) -------------

    /** The buffers open when each asynchronous workspace edit began; the others it opened itself. */
    private final Map<com.editora.lsp.WorkspaceEditMapper.Mapped, java.util.Set<EditorBuffer>> openBeforeEdit =
            new java.util.IdentityHashMap<>();

    /** Edits whose text went in: a later failure (a tab not remapped) must not close the tabs they opened. */
    private final java.util.Set<com.editora.lsp.WorkspaceEditMapper.Mapped> committedEdits =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private java.util.Set<EditorBuffer> openBuffers() {
        java.util.Set<EditorBuffer> out = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        host.forEachBuffer(out::add);
        return out;
    }

    /**
     * What happens once a workspace edit is in. One that moved, created or deleted files is saved whole — its
     * files were already changed on disk, and their new text left unsaved made a project that did not compile
     * until every tab was saved (B2). A file the edit had to open (it had no tab) is saved and its tab closed,
     * as IntelliJ edits closed files, instead of a dirty tab per file (B18). An edit that spanned files or
     * moved them can be undone as one (B1); every edit is remembered for Undo Last Refactoring.
     */
    private void afterWorkspaceEdit(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            Map<EditorBuffer, String> before,
            java.util.@Nullable Set<EditorBuffer> openBefore) {
        java.util.Set<EditorBuffer> touched =
                java.util.Collections.newSetFromMap(new java.util.LinkedHashMap<EditorBuffer, Boolean>());
        for (EditorBuffer b : buffers) {
            if (b != null && !b.isDisposed()) {
                touched.add(b);
                // An applied edit is not typing: the auto-popup read a rename or inline at the caret as an
                // identifier typed, and its list took the next Enter (fourth review BF2).
                b.cancelCompletion();
            }
        }
        boolean resources = !mapped.renames().isEmpty()
                || !mapped.creates().isEmpty()
                || !mapped.deletes().isEmpty();
        java.util.Set<Path> created = new java.util.HashSet<>();
        mapped.creates().forEach(c -> created.add(c.file().toAbsolutePath().normalize()));
        lsp.recordEdit(mapped, touched, before, created);
        java.util.List<com.editora.lsp.LspManager.WatchedFile> written = new java.util.ArrayList<>();
        for (EditorBuffer b : touched) {
            boolean opened = openBefore != null && !openBefore.contains(b);
            Path path = b.getPath();
            boolean unmanaged = path != null && !lspManager.isManaged(path); // a background tab is never opened on it
            if (!resources && !opened) {
                if (unmanaged) {
                    // An open file the user can see stays as it is: they save it. But the server must hear its new
                    // text now: one never opened there kept its old text, so a second refactoring before a save
                    // skipped it and left the project broken (fourth review, BT2's remainder).
                    lsp.syncBufferNow(b);
                }
                continue;
            }
            boolean saved = b.isDirty() && ops.saveBuffer(b);
            if (saved && unmanaged && path != null) {
                written.add(new com.editora.lsp.LspManager.WatchedFile(
                        path, com.editora.lsp.LspManager.WatchedKind.CHANGED));
            }
            if ((saved || !b.isDirty())
                    && opened
                    && (path == null || !created.contains(path.toAbsolutePath().normalize()))) {
                ops.closeBackgroundBuffer(b); // a created file (an extracted interface) stays open to be seen
            }
        }
        // A file the server does not have open keeps its old text there until it hears the file changed: the next
        // rename skipped it and Find References missed it (re-review BN1).
        lspManager.notifyWatchedFiles(written);
    }

    /** Protects dirty delete victims and every live owner of an overwritten destination. */
    private boolean resourceTargetsSafe(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        for (var deletion : mapped.deletes()) {
            if (ops.buffersAtOrUnder(deletion.file()).stream().anyMatch(EditorBuffer::isDirty)) {
                return false;
            }
        }
        for (var creation : mapped.creates()) {
            if (creation.overwrite() && !ops.buffersAtOrUnder(creation.file()).isEmpty()) {
                return false;
            }
        }
        java.util.Set<String> sources = mapped.renames().stream()
                .map(rename -> com.editora.config.PathKeys.key(rename.from()))
                .collect(java.util.stream.Collectors.toSet());
        for (var rename : mapped.renames()) {
            if (com.editora.config.PathKeys.sameNormalized(rename.from(), rename.to())) {
                continue;
            }
            if (!sources.contains(com.editora.config.PathKeys.key(rename.to()))
                    && !ops.buffersAtOrUnder(rename.to()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** Names the unsaved buffers that made {@link #resourceTargetsSafe} refuse, in whichever window they are. */
    private void resourceTargetsBlocked(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        java.util.Set<Path> unsaved = new java.util.LinkedHashSet<>();
        java.util.List<Path> targets = new java.util.ArrayList<>();
        mapped.deletes().forEach(deletion -> targets.add(deletion.file()));
        mapped.creates().stream().filter(c -> c.overwrite()).forEach(creation -> targets.add(creation.file()));
        mapped.renames().forEach(rename -> targets.add(rename.to()));
        for (Path target : targets) {
            for (EditorBuffer buffer : ops.buffersAtOrUnder(target)) {
                if (buffer.isDirty() && lsp.lspPath(buffer) != null) {
                    unsaved.add(lsp.lspPath(buffer));
                }
            }
        }
        if (!unsaved.isEmpty()) {
            List<Path> files = List.copyOf(unsaved);
            editRefused(files, tr("status.lsp.editTargetsUnsaved", blockedFileNames(files)));
        }
    }

    /** The files whose buffer cannot take every one of its edits — see {@code applyLspEditsAtomically}. */
    private static List<Path> unplaceableTargets(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped, java.util.List<EditorBuffer> buffers) {
        List<Path> files = new java.util.ArrayList<>();
        for (int i = 0; i < mapped.edits().size(); i++) {
            EditorBuffer buffer = buffers.get(i);
            if (buffer != null && !buffer.canPlaceLspEdits(mapped.edits().get(i).edits())) {
                files.add(mapped.edits().get(i).file());
            }
        }
        return files;
    }

    /** An edit the server sent does not fit the file it names: nothing was applied; say which file. */
    private void editUnplaceable(List<Path> files) {
        if (!files.isEmpty()) {
            editRefused(files, tr("status.lsp.editUnplaceable", blockedFileNames(files)));
        }
    }

    private void rollbackWorkspaceAsync(
            java.util.List<StagedCreate> creates,
            java.util.List<StagedRename> renames,
            java.util.List<StagedDelete> deletes,
            WorkspaceTransactionStatus transaction,
            java.util.function.Consumer<Boolean> done) {
        workspaceExecutor.execute(() -> {
            staging.rollbackDeletes(deletes, transaction);
            staging.rollbackRenames(renames, transaction);
            staging.rollbackCreates(creates, transaction);
            staging.endTransaction(transaction);
            Platform.runLater(() -> {
                reportIncompleteRollback(transaction);
                done.accept(false);
            });
        });
    }

    private boolean applyWorkspaceTextEdits(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.List<EditorBuffer> buffers,
            WorkspaceTransactionStatus transaction) {
        java.util.List<AppliedWorkspaceText> applied = new java.util.ArrayList<>();
        try {
            for (int i = 0; i < mapped.edits().size(); i++) {
                var edit = mapped.edits().get(i);
                EditorBuffer buffer = buffers.get(i);
                String original = buffer.getContent();
                if (!buffer.applyLspEditsAtomically(edit.edits())) {
                    // Checked for every buffer before the first was touched; reaching this means the
                    // document changed underneath. The catch below restores the buffers already edited.
                    editUnplaceable(List.of(edit.file()));
                    throw new IllegalStateException("workspace edit does not fit " + edit.file());
                }
                if (!original.equals(buffer.getContent())) {
                    applied.add(new AppliedWorkspaceText(buffer, edit.file(), original));
                }
                if (lspManager.isManaged(edit.file())) {
                    // Keep the server document synchronized before a trailing CodeAction command runs.
                    lspManager.changeDocument(edit.file(), buffer.getContent());
                }
            }
            return true;
        } catch (RuntimeException failure) {
            for (int i = applied.size() - 1; i >= 0; i--) {
                try {
                    var snapshot = applied.get(i);
                    snapshot.buffer().replaceWholeDocument(snapshot.original());
                    if (lspManager.isManaged(snapshot.file())) {
                        lspManager.changeDocument(snapshot.file(), snapshot.original());
                    }
                } catch (RuntimeException rollbackFailure) {
                    transaction.rollbackFailed = true;
                }
            }
            return false;
        }
    }

    private void reportIncompleteRollback(WorkspaceTransactionStatus transaction) {
        if (transaction.rollbackFailed) {
            host.setError(tr("status.lsp.workspaceEditRollbackFailed"));
        }
    }

    /** What the user chose to do about an interrupted transaction. */
    enum InterruptedEditChoice {
        /** Put the staged files back (an undecided edit). */
        RESTORE,
        /** Remove the old copies (a decided edit that did not get to clean up). */
        REMOVE,
        /** Leave every file where it is and stop asking. */
        KEEP,
        /** Ask again at the next project open. */
        LATER
    }

    /** Asks what to do about an interrupted transaction; a field so a test can answer without a dialog. */
    java.util.function.Function<com.editora.lsp.WorkspaceEditJournal.Interrupted, InterruptedEditChoice>
            interruptedEditPrompt = this::promptInterruptedEdit;

    private @Nullable Path interruptedEditsCheckedFor;

    /**
     * Once per opened project: looks for workspace-edit transactions that were interrupted between staging
     * and commit — the process died with files moved aside under hidden {@code .editora-lsp-*} names — and
     * offers to put them back. Nothing is moved or removed without the user's answer.
     */
    void offerInterruptedEdits() {
        Path root = ops.lspProjectRoot();
        Path directory = journalDir();
        if (root == null || directory == null || root.equals(interruptedEditsCheckedFor)) {
            return;
        }
        interruptedEditsCheckedFor = root;
        workspaceExecutor.execute(() -> {
            List<com.editora.lsp.WorkspaceEditJournal.Interrupted> found = new java.util.ArrayList<>();
            for (var interrupted : com.editora.lsp.WorkspaceEditJournal.pending(directory)) {
                if (interrupted.touches(root)) {
                    found.add(interrupted);
                } else {
                    com.editora.lsp.WorkspaceEditJournal.release(interrupted); // another project's
                }
            }
            if (!found.isEmpty()) {
                Platform.runLater(() -> found.forEach(this::resolveInterruptedEdit));
            }
        });
    }

    private void resolveInterruptedEdit(com.editora.lsp.WorkspaceEditJournal.Interrupted interrupted) {
        InterruptedEditChoice choice = interruptedEditPrompt.apply(interrupted);
        switch (choice == null ? InterruptedEditChoice.LATER : choice) {
            case RESTORE ->
                workspaceExecutor.execute(() -> {
                    var outcome = interrupted.restore();
                    Platform.runLater(() -> interruptedEditResolved(
                            outcome.restored(),
                            outcome.leftInPlace(),
                            tr(
                                    "status.lsp.interruptedEdit.restored",
                                    outcome.restored().size())));
                });
            case REMOVE ->
                workspaceExecutor.execute(() -> {
                    List<Path> left = interrupted.discardLeftovers();
                    Platform.runLater(
                            () -> interruptedEditResolved(List.of(), left, tr("status.lsp.interruptedEdit.removed")));
                });
            case KEEP -> {
                List<Path> left = interrupted.leftovers();
                interrupted.dismiss();
                host.setStatus(tr("status.lsp.interruptedEdit.kept", blockedFileNames(left)));
            }
            case LATER -> {
                // The journal stays; this run does not ask again.
            }
        }
    }

    private void interruptedEditResolved(List<Path> restored, List<Path> left, String success) {
        if (!restored.isEmpty()) {
            ops.fileCreated(restored.get(0)); // refreshes the Project tree
        }
        if (left.isEmpty()) {
            host.setStatus(success);
        } else {
            host.setError(tr("status.lsp.interruptedEdit.left", blockedFileNames(left)));
        }
    }

    private InterruptedEditChoice promptInterruptedEdit(com.editora.lsp.WorkspaceEditJournal.Interrupted interrupted) {
        List<Path> originals = interrupted.originals();
        StringBuilder lines = new StringBuilder();
        int shown = Math.min(10, originals.size());
        for (int i = 0; i < shown; i++) {
            lines.append(i == 0 ? "" : "\n")
                    .append(ops.homeCollapsed(originals.get(i).toString()));
        }
        if (originals.size() > shown) {
            lines.append("\n+").append(originals.size() - shown);
        }
        javafx.scene.control.ButtonType act = new javafx.scene.control.ButtonType(
                tr(
                        interrupted.committed()
                                ? "dialog.lsp.interruptedEdit.remove"
                                : "dialog.lsp.interruptedEdit.restore"),
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType keep = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.interruptedEdit.keep"), javafx.scene.control.ButtonBar.ButtonData.OTHER);
        javafx.scene.control.ButtonType later = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.interruptedEdit.later"), javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        javafx.scene.control.Alert ask = Dialogs.styled(new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING, lines.toString(), later, keep, act));
        ask.initOwner(host.window());
        ask.setTitle(tr("dialog.lsp.interruptedEdit.title"));
        ask.setHeaderText(tr(
                interrupted.committed()
                        ? "dialog.lsp.interruptedEdit.headerCommitted"
                        : "dialog.lsp.interruptedEdit.header"));
        UiZoom.minWidth(ask.getDialogPane(), 560);
        javafx.scene.control.ButtonType answer = ask.showAndWait().orElse(later);
        if (answer == act) {
            return interrupted.committed() ? InterruptedEditChoice.REMOVE : InterruptedEditChoice.RESTORE;
        }
        return answer == keep ? InterruptedEditChoice.KEEP : InterruptedEditChoice.LATER;
    }

    /** Where interrupted-transaction journals are kept; a field so a test can point it at a temp directory. */
    @Nullable
    Path workspaceEditJournalDir;

    private @Nullable Path journalDir() {
        if (workspaceEditJournalDir != null) {
            return workspaceEditJournalDir;
        }
        com.editora.config.ConfigManager config = ops.config();
        return config == null ? null : config.getConfigDir().resolve("lsp-edit-journal");
    }

    private record AppliedWorkspaceText(EditorBuffer buffer, Path file, String original) {}
}
