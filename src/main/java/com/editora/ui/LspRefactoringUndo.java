package com.editora.ui;

import java.nio.file.Path;
import java.util.Map;

import com.editora.config.PathDisplay;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Undo Last Refactoring for {@link LspCoordinator}: the last applied workspace edit ({@link AppliedEdit}) and its
 * inverse, the question Undo asks in a file the refactoring edited, and the Project tree's rename participant —
 * a rename or drag-move goes through jdtls first, and the moves of one drag become one refactoring to undo. The
 * inverse is applied through {@link LspWorkspaceEdits}, so it gets the same checks, journal and tab remapping.
 * FX thread.
 */
final class LspRefactoringUndo {

    private final CoordinatorHost host;
    private final LspManager lspManager;
    private final LspCoordinator.Ops ops;
    private final LspWorkspaceEdits edits;

    LspRefactoringUndo(CoordinatorHost host, LspManager lspManager, LspCoordinator.Ops ops, LspWorkspaceEdits edits) {
        this.host = host;
        this.lspManager = lspManager;
        this.ops = ops;
        this.edits = edits;
    }

    /** One file of an applied edit: where it is now, and its text before and after. */
    record EditedFile(Path path, String before, String after) {}

    /** The last applied workspace edit (without deletions), as Undo Last Refactoring reverts it. */
    record AppliedEdit(
            java.util.List<EditedFile> files,
            java.util.List<com.editora.lsp.WorkspaceEditMapper.FileRename> renames,
            java.util.List<Path> created) {}

    private @Nullable AppliedEdit lastApplied;
    /** Set while Undo Refactoring applies its inverse, which is not itself recorded. */
    private boolean undoingEdit;

    /** The edit Undo Refactoring would revert (tests). */
    @Nullable
    AppliedEdit lastAppliedEdit() {
        return lastApplied;
    }

    /**
     * The Project tree's rename participant (B15): the edits jdtls wants for a rename go in first — the class
     * renamed with its {@code .java} file, the package lines and imports of a moved folder — then the tree moves
     * the file. Once it has, those edits are finished like any refactoring's: saved, so the project on disk
     * compiles, recorded with the move so Undo takes both back, and told to the server (re-review BN3).
     */
    void willRenameFile(Path from, Path to, java.util.function.Consumer<Boolean> proceed) {
        AppliedEdit previous = lastApplied;
        lastRenameRefused = false;
        renamesInFlight++;
        willRenameFile(from, to, previous, proceed, true);
    }

    private void willRenameFile(
            Path from,
            Path to,
            @Nullable AppliedEdit previous,
            java.util.function.Consumer<Boolean> proceed,
            boolean retry) {
        lspManager.willRenameFile(
                from,
                to,
                ok -> {
                    if (!ok && retry && !lastRenameRefused && java.nio.file.Files.exists(from)) {
                        // jdtls's edit no longer applied: a file it edits was opened a moment before, and the edit
                        // named a document it had not read yet. Asked once more, it answers for the open one
                        // (twelfth review BL4).
                        var later = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1));
                        later.setOnFinished(e -> willRenameFile(from, to, previous, proceed, false));
                        later.play();
                        return;
                    }
                    try {
                        proceed.accept(ok); // the tree moves the file now, and points its tabs at the new path
                        AppliedEdit edit = lastApplied;
                        boolean moved = ok && !java.nio.file.Files.exists(from) && java.nio.file.Files.exists(to);
                        if (moved && edit != null && edit != previous) {
                            treeRenamed(edit, from, to);
                        } else if (moved && moveGroup != null) {
                            // moved with no edit (not Java, or nothing to change): still part of the drag, so
                            // undoing the drag as one refactoring takes it back too (fifth review BV1)
                            moveGroup.add(new AppliedEdit(
                                    java.util.List.of(),
                                    java.util.List.of(
                                            new com.editora.lsp.WorkspaceEditMapper.FileRename(from, to, false)),
                                    java.util.List.of()));
                            moveGroupWhole &= !java.nio.file.Files.isDirectory(to);
                        }
                    } finally {
                        renameDone();
                    }
                },
                this::renameWithoutEdits);
    }

    /**
     * Tree renames whose edit is not recorded yet. Saving a tab the move edited runs a nested event loop, which ran
     * the drag's next move and its end before this move was recorded: the drag ends only once none is in flight
     * (sixth review BS1).
     */
    private int renamesInFlight;

    /** The end of the drag, waiting for a rename still in flight: true for an Undo Move's, null when none waits. */
    private @Nullable Boolean moveEndWaiting;

    /** What the tree said when the drag ended, said again once it is recorded: a save's status landed over it (BF3). */
    private @Nullable String moveEndStatus;

    /** Whether the last refused tree rename was the user's No; false for a failed edit (seventh review BR4). */
    private boolean lastRenameRefused;

    private void renameDone() {
        renamesInFlight = Math.max(0, renamesInFlight - 1);
        Boolean undo = moveEndWaiting;
        if (renamesInFlight == 0 && undo != null) {
            moveEndWaiting = null;
            String said = moveEndStatus;
            // the tree's "Moved 1 to geo", not the save's "Saved /the/whole/path"; but never over the warning that
            // a package line was left as it was (eighth review BQ4)
            if (!endTreeMove(undo) && said != null) {
                host.setStatus(said);
            }
        }
    }

    /**
     * The Project tree's participant: a rename or a drag-move goes through jdtls (B15, BT1), and the moves of one
     * drag, or of its Undo Move, are one change (fourth review BF1).
     */
    ProjectPanel.RenameParticipant treeParticipant() {
        return new ProjectPanel.RenameParticipant() {
            @Override
            public void willRename(Path from, Path to, java.util.function.Consumer<Boolean> proceed) {
                willRenameFile(from, to, proceed);
            }

            @Override
            public void beginMove(int count) {
                moveGroup = new java.util.ArrayList<>();
                moveGroupWhole = true;
                moveCount = count;
                moveWithoutEdits.clear();
                moveWithoutEditsAnswer = null;
            }

            @Override
            public void endMove(boolean undo) {
                endMove(undo, null);
            }

            @Override
            public void endMove(boolean undo, @Nullable String status) {
                moveEndStatus = status;
                if (renamesInFlight > 0) {
                    moveEndWaiting = undo; // a move's edit is still being recorded (sixth review BS1)
                } else {
                    endTreeMove(undo);
                }
            }

            @Override
            public boolean refusedByUser() {
                return lastRenameRefused;
            }
        };
    }

    /** The edits of the drag-move under way, one per moved file; null when none is. */
    private java.util.@Nullable List<AppliedEdit> moveGroup;

    /** False once a folder moved in it: a folder cannot be moved back as one, so Undo Move is the way back. */
    private boolean moveGroupWhole;

    /** How many items the drag under way moves, for the one question asked about them all. */
    private int moveCount;

    /** The {@code .java} files of the drag that moved without their package line edited. */
    private final java.util.List<String> moveWithoutEdits = new java.util.ArrayList<>();

    /** The edit Undo Last Refactoring would revert when it came from a drag-move, so reverting it ends Undo Move. */
    private @Nullable AppliedEdit treeMoveEdit;

    /** Told when Undo Last Refactoring took back a drag-move, which the tree must no longer offer to undo. */
    private Runnable onTreeMoveUndone = () -> {};

    void setOnTreeMoveUndone(Runnable action) {
        onTreeMoveUndone = action == null ? () -> {} : action;
    }

    /** Ends the drag's group; true when it warned that jdtls left a package line as it was. */
    private boolean endTreeMove(boolean undo) {
        java.util.List<AppliedEdit> group = moveGroup;
        moveGroup = null;
        if (group == null) {
            return false;
        }
        // Only a drag in which jdtls edited something is a refactoring: a .txt dragged after a rename took the
        // rename's undo away, and became "Refactoring undone in 0 files" (sixth review BS2).
        boolean edited = group.stream().anyMatch(e -> !e.files().isEmpty());
        if (undo) {
            // Undo Move took the drag back: it is not a refactoring to undo, and the drag's own edit is gone
            if (edited || (lastApplied != null && lastApplied == treeMoveEdit)) {
                lastApplied = null;
            }
            treeMoveEdit = null;
        } else if (edited) {
            lastApplied = moveGroupWhole ? merged(group) : null;
            treeMoveEdit = lastApplied;
        } else {
            // a drag with no edit is the tree's newest move: an older drag's refactoring no longer ends it, nor does
            // its Undo Move take that refactoring's undo away (seventh review BS2)
            treeMoveEdit = null;
        }
        moveEndStatus = null;
        boolean warned = !moveWithoutEdits.isEmpty();
        if (warned) {
            host.setError(tr(
                    "status.lsp.moveWithoutPackageEdit", String.join(", ", moveWithoutEdits), moveWithoutEdits.size()));
            moveWithoutEdits.clear();
        }
        moveWithoutEditsAnswer = null;
        return warned;
    }

    /** One edit made of several, in order: a file edited twice keeps its first text, a later move renames it. */
    static AppliedEdit merged(java.util.List<AppliedEdit> edits) {
        Map<Path, EditedFile> files = new java.util.LinkedHashMap<>();
        java.util.List<com.editora.lsp.WorkspaceEditMapper.FileRename> renames = new java.util.ArrayList<>();
        java.util.List<Path> created = new java.util.ArrayList<>();
        for (AppliedEdit e : edits) {
            for (var r : e.renames()) {
                Path f = r.from().toAbsolutePath().normalize();
                Path t = r.to().toAbsolutePath().normalize();
                Map<Path, EditedFile> moved = new java.util.LinkedHashMap<>();
                files.forEach((p, ef) -> {
                    Path n = p.toAbsolutePath().normalize();
                    Path now = n.startsWith(f) ? t.resolve(f.relativize(n)) : p;
                    moved.put(now, new EditedFile(now, ef.before(), ef.after()));
                });
                files = moved;
            }
            for (EditedFile f : e.files()) {
                files.merge(f.path(), f, (old, now) -> new EditedFile(old.path(), old.before(), now.after()));
            }
            renames.addAll(e.renames());
            created.addAll(e.created());
        }
        return new AppliedEdit(
                java.util.List.copyOf(files.values()), java.util.List.copyOf(renames), java.util.List.copyOf(created));
    }

    /**
     * jdtls had no edit for a rename. When it could not say (still importing, or no answer), the file would move
     * with its package line and its users' imports as they were, with no word (fourth review BF5): ask first. When
     * it answered with nothing for a {@code .java} file moved to another folder, move it and say so (DF3).
     */
    private boolean renameWithoutEdits(Path from, Path to, boolean answered) {
        String name = PathDisplay.fileName(from);
        if (answered) {
            if (moveGroup != null) {
                moveWithoutEdits.add(name);
            } else {
                host.setError(tr("status.lsp.moveWithoutPackageEdit", name, 1));
            }
            return true;
        }
        Boolean asked = moveWithoutEditsAnswer;
        if (moveGroup != null && asked != null) {
            lastRenameRefused = !asked;
            return asked; // asked once for the whole drag (fifth review BV3)
        }
        boolean rename = java.util.Objects.equals(from.getParent(), to.getParent());
        // A drag of several asks once, so it names them all, not only the first (sixth review BV3)
        boolean many = moveGroup != null && moveCount > 1;
        boolean answer = confirmRenameWithoutEdits.test(
                many
                        ? tr("dialog.lsp.renameWithoutEdits.manyBody", name, moveCount - 1)
                        : tr(
                                rename
                                        ? "dialog.lsp.renameWithoutEdits.renameBody"
                                        : "dialog.lsp.renameWithoutEdits.body",
                                name));
        if (moveGroup != null) {
            moveWithoutEditsAnswer = answer;
        }
        lastRenameRefused = !answer;
        return answer;
    }

    /** The answer the user gave about moving without edits during the drag under way, or null when not asked. */
    private @Nullable Boolean moveWithoutEditsAnswer;

    /** Asks whether to rename a file jdtls could not edit for; a field so a test can answer. */
    java.util.function.Predicate<String> confirmRenameWithoutEdits = this::askRenameWithoutEdits;

    private boolean askRenameWithoutEdits(String body) {
        javafx.scene.control.Alert confirm = Dialogs.styled(new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                body,
                javafx.scene.control.ButtonType.OK,
                javafx.scene.control.ButtonType.CANCEL));
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.lsp.renameWithoutEdits.title"));
        confirm.setHeaderText(null);
        return confirm.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL)
                == javafx.scene.control.ButtonType.OK;
    }

    private void treeRenamed(AppliedEdit edit, Path from, Path to) {
        Path f = from.toAbsolutePath().normalize();
        Path t = to.toAbsolutePath().normalize();
        java.util.List<EditedFile> files = new java.util.ArrayList<>();
        for (EditedFile e : edit.files()) {
            Path p = e.path().toAbsolutePath().normalize();
            Path now = p.startsWith(f) ? t.resolve(f.relativize(p)) : e.path();
            files.add(new EditedFile(now, e.before(), e.after()));
        }
        java.util.List<AppliedEdit> group = moveGroup;
        if (java.nio.file.Files.isDirectory(t)) {
            lastApplied = null; // a folder cannot be moved back as one: rename it back, and jdtls edits again
            moveGroupWhole = false;
        } else {
            java.util.List<com.editora.lsp.WorkspaceEditMapper.FileRename> renames =
                    new java.util.ArrayList<>(edit.renames());
            renames.add(new com.editora.lsp.WorkspaceEditMapper.FileRename(from, to, false));
            lastApplied = new AppliedEdit(java.util.List.copyOf(files), java.util.List.copyOf(renames), edit.created());
        }
        lspManager.notifyWatchedFiles(java.util.List.of(
                new com.editora.lsp.LspManager.WatchedFile(from, com.editora.lsp.LspManager.WatchedKind.DELETED),
                new com.editora.lsp.LspManager.WatchedFile(to, com.editora.lsp.LspManager.WatchedKind.CREATED)));
        if (group != null && lastApplied != null) {
            group.add(lastApplied);
        }
        // Saved only now that the move is recorded: a save can run a nested event loop, which ran the drag's next
        // move and its end first (sixth review BS1).
        for (EditedFile e : files) {
            EditorBuffer b = ops.bufferForPath(e.path());
            if (b != null && b.isDirty() && b.getContent().equals(e.after())) {
                ops.saveBuffer(b);
            }
        }
        if (group != null) {
            return; // a drag-move: the tree says what moved, not "Renamed to" (fourth review BF3)
        }
        // the saves above said "Saved /the/whole/path"; the rename is what happened (BT7)
        host.setStatus(tr("status.renamedTo", PathDisplay.fileName(to)));
    }

    /** What the user chose when Undo met a buffer the last refactoring edited. */
    enum RefactoringUndoChoice {
        ALL_FILES,
        THIS_FILE,
        CANCEL
    }

    /** Asks whether Undo should revert the whole refactoring; a field so a test can answer. */
    java.util.function.IntFunction<RefactoringUndoChoice> refactoringUndoChooser = this::askRefactoringUndo;

    private RefactoringUndoChoice askRefactoringUndo(int files) {
        javafx.scene.control.ButtonType all = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.undoRefactoring.all", files), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType one = new javafx.scene.control.ButtonType(
                tr("dialog.lsp.undoRefactoring.thisFile"), javafx.scene.control.ButtonBar.ButtonData.OTHER);
        javafx.scene.control.ButtonType cancel = Dialogs.cancel();
        javafx.scene.control.Alert confirm = Dialogs.styled(new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                tr("dialog.lsp.undoRefactoring.body", files),
                all,
                one,
                cancel));
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.lsp.undoRefactoring.title"));
        confirm.setHeaderText(null);
        var chosen = confirm.showAndWait().orElse(cancel);
        return chosen == all
                ? RefactoringUndoChoice.ALL_FILES
                : chosen == one ? RefactoringUndoChoice.THIS_FILE : RefactoringUndoChoice.CANCEL;
    }

    /**
     * Undo in {@code buffer}: when it still holds what the last refactoring left there (and so do the edit's
     * other files), asks whether to undo the refactoring everywhere. True when this took over the Undo — done
     * for all files, or cancelled; false leaves the ordinary per-file Undo to run.
     */
    boolean offerRefactoringUndo(@Nullable EditorBuffer buffer) {
        AppliedEdit edit = lastApplied;
        if (edit == null || buffer == null) {
            return false;
        }
        Path path = buffer.getPath();
        if (path == null || !holdsAfter(edit, path, buffer.getContent())) {
            return false; // edited since (or never part of it): an ordinary Undo
        }
        if (edit.files().stream().noneMatch(f -> f.path().equals(path)) || !refactoringIntact(edit)) {
            return false;
        }
        int files = edit.files().size() + edit.created().size();
        if (files < 2 && edit.renames().isEmpty()) {
            return false; // one file, nothing moved: the ordinary Undo is the whole of it
        }
        return switch (refactoringUndoChooser.apply(files)) {
            case ALL_FILES -> {
                undoRefactoring();
                yield true;
            }
            case CANCEL -> true;
            case THIS_FILE -> false;
        };
    }

    private static boolean holdsAfter(AppliedEdit edit, Path path, String text) {
        return edit.files().stream()
                .anyMatch(f -> f.path().equals(path) && f.after().equals(text));
    }

    /** Whether every file of {@code edit} still holds what it left — in its tab, or on disk. */
    private boolean refactoringIntact(AppliedEdit edit) {
        for (EditedFile f : edit.files()) {
            EditorBuffer open = ops.bufferForPath(f.path());
            String now;
            if (open != null) {
                now = open.getContent();
            } else {
                try {
                    now = java.nio.file.Files.readString(f.path());
                } catch (java.io.IOException | RuntimeException e) {
                    return false;
                }
            }
            if (!now.equals(f.after())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reverts the last refactoring in every file it changed, moves its renamed files back and deletes the files it
     * created ({@code lsp.undoRefactoring}). The inverse is itself a workspace edit, so it goes through the same
     * checks, journal and tab remapping, and is saved the same way. Refused when any of the files changed since.
     */
    void undoRefactoring() {
        AppliedEdit edit = lastApplied;
        if (edit == null) {
            host.setStatus(tr("status.lsp.noRefactoringToUndo"));
            return;
        }
        if (!refactoringIntact(edit)) {
            host.setError(tr("status.lsp.refactoringChangedSince"));
            return;
        }
        java.util.List<
                        org.eclipse.lsp4j.jsonrpc.messages.Either<
                                org.eclipse.lsp4j.TextDocumentEdit, org.eclipse.lsp4j.ResourceOperation>>
                changes = new java.util.ArrayList<>();
        for (EditedFile f : edit.files()) {
            org.eclipse.lsp4j.Position end = endOf(f.after());
            changes.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new org.eclipse.lsp4j.TextDocumentEdit(
                    new org.eclipse.lsp4j.VersionedTextDocumentIdentifier(
                            f.path().toUri().toString(), null),
                    java.util.List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.forLeft(new org.eclipse.lsp4j.TextEdit(
                            new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0, 0), end), f.before()))))));
        }
        for (int i = edit.renames().size() - 1; i >= 0; i--) {
            var r = edit.renames().get(i);
            changes.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(new org.eclipse.lsp4j.RenameFile(
                    r.to().toUri().toString(), r.from().toUri().toString())));
        }
        for (Path c : edit.created()) {
            changes.add(org.eclipse.lsp4j.jsonrpc.messages.Either.forRight(
                    new org.eclipse.lsp4j.DeleteFile(c.toUri().toString())));
        }
        var inverse = new org.eclipse.lsp4j.WorkspaceEdit(changes);
        com.editora.lsp.WorkspaceEditMapper.Mapped mapped = com.editora.lsp.WorkspaceEditMapper.map(inverse);
        if (mapped == null) {
            host.setError(tr("status.lsp.refactoringUndoFailed"));
            return;
        }
        int files = edit.files().size() + edit.created().size();
        undoingEdit = true;
        edits.beginReportedEdit();
        edits.applyWorkspaceEditsConfirmed(mapped, ok -> {
            undoingEdit = false;
            if (ok) {
                lastApplied = null;
                if (edit == treeMoveEdit) {
                    treeMoveEdit = null;
                    onTreeMoveUndone.run(); // the drag is taken back: Undo Move has nothing left (BF1)
                }
                Path[] made = newPackageFolders.remove(edit);
                if (made != null) {
                    // Move ▸ New package's folder goes with it (BT4), and jdtls hears it is gone: the next Move
                    // still offered the package (fourth review BF4)
                    lspManager.notifyWatchedFiles(WorkspaceEditFiles.removeEmptyFolders(made[0], made[1]).stream()
                            .map(f -> new com.editora.lsp.LspManager.WatchedFile(
                                    f, com.editora.lsp.LspManager.WatchedKind.DELETED))
                            .toList());
                }
            }
            edits.reportEdit(ok, tr("status.lsp.refactoringUndone", files), tr("status.lsp.refactoringUndoFailed"));
        });
    }

    /** Folders Move ▸ New package created, by the refactoring that filled them, with their source root. */
    private final Map<AppliedEdit, Path[]> newPackageFolders = new java.util.IdentityHashMap<>();

    /** The position just past the end of {@code text}, in LSP's UTF-16 units. */
    static org.eclipse.lsp4j.Position endOf(String text) {
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new org.eclipse.lsp4j.Position(line, text.length() - lineStart);
    }

    /**
     * Remembers what a workspace edit just applied, as Undo Last Refactoring reverts it (part of what follows every
     * edit, {@code LspWorkspaceEdits.afterWorkspaceEdit}): {@code touched} its live buffers, {@code before} their text
     * before it, {@code created} the files it made. Undo's own inverse edit is not remembered; neither is an edit
     * that deleted something.
     */
    void recordEdit(
            com.editora.lsp.WorkspaceEditMapper.Mapped mapped,
            java.util.Set<EditorBuffer> touched,
            Map<EditorBuffer, String> before,
            java.util.Set<Path> created) {
        if (undoingEdit) {
            lastApplied = null;
        } else if (!touched.isEmpty() && mapped.deletes().isEmpty()) {
            java.util.List<EditedFile> files = new java.util.ArrayList<>();
            for (EditorBuffer b : touched) {
                Path path = b.getPath();
                if (path != null && !created.contains(path.toAbsolutePath().normalize())) {
                    files.add(new EditedFile(path, before.getOrDefault(b, ""), b.getContent()));
                }
            }
            lastApplied = new AppliedEdit(
                    java.util.List.copyOf(files),
                    java.util.List.copyOf(mapped.renames()),
                    java.util.List.copyOf(created));
        }
    }

    /** After Move ▸ New package: the folder it made goes with the refactoring that filled it, when one was applied. */
    void newPackageFolder(@Nullable AppliedEdit before, Path folder, Path root) {
        if (lastApplied != null && lastApplied != before) {
            newPackageFolders.put(lastApplied, new Path[] {folder, root}); // undo takes the folder back too
        }
    }
}
