package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Summarising and filtering a rename's {@link WorkspaceEditMapper.Mapped} so it can be previewed before it is
 * applied (#768).
 *
 * <p>A rename edits files the user cannot see. Every desktop IDE treats "show me what you are about to do" as
 * the thing that makes refactoring usable at all — Eclipse's preview page, IntelliJ's refactoring preview.
 * Editora applied the server's edit immediately: safe, in that the whole thing is one undoable batch, but
 * safety after the fact is not the same as consent before it.
 *
 * <p>Pure, so the counting and the filtering are testable without a language server: the interesting bugs are
 * a file dropped from the summary, or a deselected file still being written.
 */
public final class RenamePreview {

    private RenamePreview() {}

    /**
     * One affected path, for a preview row.
     *
     * @param file the file (or, for a folder delete, the folder)
     * @param edits how many text edits it receives
     * @param renamedTo where the file itself moves to, or null when it stays put
     * @param deletion how the path is deleted ({@code DELETE_FILE} / {@code DELETE_DIRECTORY}), or null
     * @param overwrites whether an existing file is replaced — this one by an empty created file, or the
     *     one at {@code renamedTo} by this file moving onto it
     */
    public record FileChange(
            Path file, int edits, Path renamedTo, WorkspaceEditHazards.Kind deletion, boolean overwrites) {
        public FileChange(Path file, int edits, Path renamedTo) {
            this(file, edits, renamedTo, null, false);
        }
    }

    /** As {@link #summarise(WorkspaceEditMapper.Mapped, WorkspaceEditHazards.Disk)}, assuming every target exists. */
    public static List<FileChange> summarise(WorkspaceEditMapper.Mapped mapped) {
        return summarise(mapped, WorkspaceEditHazards.Disk.ASSUME_FILES);
    }

    /**
     * Every path a rename touches, in the order the edit lists them, with any file rename folded into the
     * same row.
     *
     * <p>A file can be renamed without receiving text edits (jdtls moves a {@code .java} file whose public
     * class was renamed), so renames contribute rows of their own rather than only annotating existing ones —
     * otherwise the preview would understate what is about to happen.
     *
     * <p>The same holds for what the edit destroys ({@link WorkspaceEditHazards}): a deleted file or folder
     * and a replaced file are rows too. They used to be applied whatever was unticked, and they are the
     * part of a refactoring that undo does not bring back.
     */
    public static List<FileChange> summarise(WorkspaceEditMapper.Mapped mapped, WorkspaceEditHazards.Disk disk) {
        if (mapped == null) {
            return List.of();
        }
        Map<Path, FileChange> rows = new LinkedHashMap<>();
        for (WorkspaceEditMapper.FileEdit edit : mapped.edits()) {
            rows.put(
                    edit.file(), new FileChange(edit.file(), edit.edits().size(), renameTargetOf(mapped, edit.file())));
        }
        for (WorkspaceEditMapper.FileRename rename : mapped.renames()) {
            rows.putIfAbsent(rename.from(), new FileChange(rename.from(), 0, rename.to()));
        }
        for (WorkspaceEditHazards.Hazard hazard : WorkspaceEditHazards.of(mapped, disk)) {
            boolean overwrite = hazard.kind() == WorkspaceEditHazards.Kind.OVERWRITE;
            Path key = hazard.renamedFrom() != null ? hazard.renamedFrom() : hazard.path();
            FileChange row = rows.getOrDefault(key, new FileChange(key, 0, null));
            rows.put(
                    key,
                    new FileChange(
                            row.file(),
                            row.edits(),
                            row.renamedTo(),
                            overwrite ? row.deletion() : hazard.kind(),
                            overwrite || row.overwrites()));
        }
        return List.copyOf(rows.values());
    }

    private static Path renameTargetOf(WorkspaceEditMapper.Mapped mapped, Path file) {
        for (WorkspaceEditMapper.FileRename rename : mapped.renames()) {
            if (rename.from().equals(file)) {
                return rename.to();
            }
        }
        return null;
    }

    /** As {@link #filter(WorkspaceEditMapper.Mapped, Set, Set)}, with the rows {@link #summarise} lists. */
    public static WorkspaceEditMapper.Mapped filter(WorkspaceEditMapper.Mapped mapped, Set<Path> keep) {
        Set<Path> listed = new LinkedHashSet<>();
        summarise(mapped).forEach(row -> listed.add(row.file()));
        return filter(mapped, keep, listed);
    }

    /**
     * The subset of {@code mapped} that touches only {@code keep}, out of the rows {@code listed} in the
     * preview.
     *
     * <p>A file rename is kept only when its source file is kept: moving a file whose edits the user just
     * excluded would leave the rename half-applied, which is worse than doing nothing to it.
     *
     * <p>A create or delete that was shown as a row follows its tick like everything else. One that was
     * not shown is the scaffolding of the refactoring (jdtls's package rename creates and removes a
     * placeholder) and travels with it.
     */
    public static WorkspaceEditMapper.Mapped filter(
            WorkspaceEditMapper.Mapped mapped, Set<Path> keep, Set<Path> listed) {
        if (mapped == null) {
            return null;
        }
        List<WorkspaceEditMapper.FileEdit> edits = new ArrayList<>();
        for (WorkspaceEditMapper.FileEdit edit : mapped.edits()) {
            if (keep.contains(edit.file())) {
                edits.add(edit);
            }
        }
        List<WorkspaceEditMapper.FileRename> renames = new ArrayList<>();
        for (WorkspaceEditMapper.FileRename rename : mapped.renames()) {
            if (keep.contains(rename.from())) {
                renames.add(rename);
            }
        }
        List<WorkspaceEditMapper.FileCreate> creates = new ArrayList<>();
        for (WorkspaceEditMapper.FileCreate create : mapped.creates()) {
            if (keep.contains(create.file()) || !listed.contains(create.file())) {
                creates.add(create);
            }
        }
        List<WorkspaceEditMapper.FileDelete> deletes = new ArrayList<>();
        for (WorkspaceEditMapper.FileDelete delete : mapped.deletes()) {
            if (keep.contains(delete.file()) || !listed.contains(delete.file())) {
                deletes.add(delete);
            }
        }
        return new WorkspaceEditMapper.Mapped(edits, renames, creates, deletes);
    }

    /**
     * The unticked rows that a delete the user left ticked would remove anyway — a file kept in place
     * inside a folder the edit deletes. Non-empty means the filtered edit must be refused: applying it
     * would delete exactly what the user chose to leave alone.
     */
    public static List<Path> excludedButDeleted(WorkspaceEditMapper.Mapped filtered, Set<Path> keep, Set<Path> listed) {
        if (filtered == null) {
            return List.of();
        }
        List<Path> covered = new ArrayList<>();
        for (Path row : listed) {
            if (keep.contains(row)) {
                continue;
            }
            Path excluded = row.toAbsolutePath().normalize();
            for (WorkspaceEditMapper.FileDelete delete : filtered.deletes()) {
                if (excluded.startsWith(delete.file().toAbsolutePath().normalize())) {
                    covered.add(row);
                    break;
                }
            }
        }
        return covered;
    }

    /**
     * Whether a rename is worth previewing.
     *
     * <p>True once it reaches beyond the current file — several files, or a file rename. A single-file rename
     * is already visible in the editor and is one undo away, so interposing a confirmation there would be
     * friction with nothing to confirm; the case the preview exists for is the edit you cannot see.
     */
    public static boolean worthPreviewing(WorkspaceEditMapper.Mapped mapped) {
        if (mapped == null) {
            return false;
        }
        return mapped.edits().size() > 1 || !mapped.renames().isEmpty();
    }

    /**
     * As {@link #worthPreviewing(WorkspaceEditMapper.Mapped)}, and also true when the rename deletes or
     * replaces something on disk — even a single-file rename is no longer "one undo away" then.
     */
    public static boolean worthPreviewing(WorkspaceEditMapper.Mapped mapped, WorkspaceEditHazards.Disk disk) {
        return worthPreviewing(mapped) || !WorkspaceEditHazards.of(mapped, disk).isEmpty();
    }

    /** Total text edits across every file, for a one-line summary. */
    public static int totalEdits(WorkspaceEditMapper.Mapped mapped) {
        if (mapped == null) {
            return 0;
        }
        int n = 0;
        for (WorkspaceEditMapper.FileEdit edit : mapped.edits()) {
            n += edit.edits().size();
        }
        return n;
    }
}
