package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.editora.config.PathKeys;

/**
 * The operations of a workspace edit that destroy something already on disk: a {@code DeleteFile} of an
 * existing path, and a {@code CreateFile} or {@code RenameFile} that replaces an existing file.
 *
 * <p>Text edits land in undoable buffers; these do not. A deleted folder or a truncated file cannot be
 * brought back by undo, so they are what a preview has to show, what a code action has to ask about, and
 * what is captured in Local History first. Pure: the disk is asked through {@link Disk}, so the
 * classification is testable without files.
 */
public final class WorkspaceEditHazards {

    private WorkspaceEditHazards() {}

    /** What happens to the path. */
    public enum Kind {
        /** An existing file is deleted. */
        DELETE_FILE,
        /** An existing folder is deleted together with everything in it. */
        DELETE_DIRECTORY,
        /** An existing file is replaced — by an empty created file, or by a file renamed onto it. */
        OVERWRITE
    }

    /**
     * One destructive operation.
     *
     * @param path the path whose current content is lost
     * @param kind what happens to it
     * @param renamedFrom for an overwrite caused by a rename, the file that moves onto {@code path}; else null
     */
    public record Hazard(Path path, Kind kind, Path renamedFrom) {}

    /** The two questions the classification asks of the filesystem. */
    public interface Disk {
        boolean exists(Path path);

        boolean isDirectory(Path path);

        /** Assumes every path exists as a file: the conservative answer when the disk cannot be asked. */
        Disk ASSUME_FILES = new Disk() {
            @Override
            public boolean exists(Path path) {
                return true;
            }

            @Override
            public boolean isDirectory(Path path) {
                return false;
            }
        };
    }

    /**
     * Every destructive operation in {@code mapped}, deletes first, in the order the edit lists them.
     *
     * <p>A delete of a path the same edit creates is the edit's own scaffolding (jdtls's package rename
     * creates and removes a placeholder), not a loss, and is left out; so is a delete or overwrite whose
     * target does not exist. A non-recursive delete of a folder only succeeds on an empty one and is left
     * out too.
     */
    public static List<Hazard> of(WorkspaceEditMapper.Mapped mapped, Disk disk) {
        if (mapped == null) {
            return List.of();
        }
        Set<String> created = new HashSet<>();
        for (WorkspaceEditMapper.FileCreate create : mapped.creates()) {
            created.add(PathKeys.normalizedKey(create.file()));
        }
        List<Hazard> hazards = new ArrayList<>();
        for (WorkspaceEditMapper.FileDelete delete : mapped.deletes()) {
            Path file = delete.file();
            if (created.contains(PathKeys.normalizedKey(file)) || !disk.exists(file)) {
                continue;
            }
            if (disk.isDirectory(file)) {
                if (delete.recursive()) {
                    hazards.add(new Hazard(file, Kind.DELETE_DIRECTORY, null));
                }
            } else {
                hazards.add(new Hazard(file, Kind.DELETE_FILE, null));
            }
        }
        for (WorkspaceEditMapper.FileCreate create : mapped.creates()) {
            // Staging lets ignoreIfExists win: such a create leaves an existing file alone.
            if (create.overwrite()
                    && !create.ignoreIfExists()
                    && disk.exists(create.file())
                    && !disk.isDirectory(create.file())) {
                hazards.add(new Hazard(create.file(), Kind.OVERWRITE, null));
            }
        }
        Set<String> sources = new HashSet<>();
        for (WorkspaceEditMapper.FileRename rename : mapped.renames()) {
            sources.add(PathKeys.normalizedKey(rename.from()));
        }
        for (WorkspaceEditMapper.FileRename rename : mapped.renames()) {
            String to = PathKeys.normalizedKey(rename.to());
            // A destination that is itself moved away by this edit (A→B, B→C), or the file under another
            // spelling (a case-only rename), is not replaced.
            if (rename.overwrite()
                    && !sources.contains(to)
                    && !rename.from().toString().equalsIgnoreCase(rename.to().toString())
                    && disk.exists(rename.to())) {
                hazards.add(new Hazard(rename.to(), Kind.OVERWRITE, rename.from()));
            }
        }
        return List.copyOf(hazards);
    }

    /**
     * The hazards an edit applied without a preview must ask about first: a folder deleted with its
     * contents, and an existing file replaced. A single-file delete is what the action says it does and is
     * captured in Local History; asking for each one would train the user to click through.
     */
    public static List<Hazard> needingConfirmation(WorkspaceEditMapper.Mapped mapped, Disk disk) {
        return of(mapped, disk).stream()
                .filter(hazard -> hazard.kind() != Kind.DELETE_FILE)
                .toList();
    }
}
