package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
     * One affected file, for a preview row.
     *
     * @param file the file
     * @param edits how many text edits it receives
     * @param renamedTo where the file itself moves to, or null when it stays put
     */
    public record FileChange(Path file, int edits, Path renamedTo) {}

    /**
     * Every file a rename touches, in the order the edit lists them, with any file rename folded into the
     * same row.
     *
     * <p>A file can be renamed without receiving text edits (jdtls moves a {@code .java} file whose public
     * class was renamed), so renames contribute rows of their own rather than only annotating existing ones —
     * otherwise the preview would understate what is about to happen.
     */
    public static List<FileChange> summarise(WorkspaceEditMapper.Mapped mapped) {
        if (mapped == null) {
            return List.of();
        }
        List<FileChange> rows = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (WorkspaceEditMapper.FileEdit edit : mapped.edits()) {
            rows.add(new FileChange(edit.file(), edit.edits().size(), renameTargetOf(mapped, edit.file())));
            seen.add(edit.file());
        }
        for (WorkspaceEditMapper.FileRename rename : mapped.renames()) {
            if (!seen.contains(rename.from())) {
                rows.add(new FileChange(rename.from(), 0, rename.to()));
                seen.add(rename.from());
            }
        }
        return rows;
    }

    private static Path renameTargetOf(WorkspaceEditMapper.Mapped mapped, Path file) {
        for (WorkspaceEditMapper.FileRename rename : mapped.renames()) {
            if (rename.from().equals(file)) {
                return rename.to();
            }
        }
        return null;
    }

    /**
     * The subset of {@code mapped} that touches only {@code keep}.
     *
     * <p>A file rename is kept only when its source file is kept: moving a file whose edits the user just
     * excluded would leave the rename half-applied, which is worse than doing nothing to it.
     */
    public static WorkspaceEditMapper.Mapped filter(WorkspaceEditMapper.Mapped mapped, Set<Path> keep) {
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
        return new WorkspaceEditMapper.Mapped(edits, renames);
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
