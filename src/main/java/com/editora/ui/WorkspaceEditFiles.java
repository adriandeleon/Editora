package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.EditorBuffer;
import com.editora.ui.LspCoordinator.WorkspaceFileIdentity;
import com.editora.ui.LspCoordinator.WorkspaceFileOperations;
import org.jspecify.annotations.Nullable;

/** Stateless helpers of {@link LspCoordinator}'s workspace-edit transaction: the paths an edit names, disk views. */
final class WorkspaceEditFiles {
    private WorkspaceEditFiles() {}

    /** Every path {@code mapped}'s create, rename and delete operations name. */
    static List<Path> resourcePaths(com.editora.lsp.WorkspaceEditMapper.Mapped mapped) {
        List<Path> out = new java.util.ArrayList<>();
        mapped.creates().forEach(c -> out.add(c.file()));
        mapped.renames().forEach(r -> {
            out.add(r.from());
            out.add(r.to());
        });
        mapped.deletes().forEach(d -> out.add(d.file()));
        return out;
    }

    /** Where {@link #staleDiskTargets} will look: each disk-preimage target, at the place a staged rename put it. */
    static List<Path> preimagePaths(com.editora.lsp.WorkspaceEditMapper.Mapped mapped, Map<Path, Path> movedTo) {
        List<Path> out = new java.util.ArrayList<>();
        for (var edit : mapped.edits()) {
            if (edit.diskPreimageAt() != null) {
                out.add(movedTo.getOrDefault(edit.file().toAbsolutePath().normalize(), edit.file()));
            }
        }
        return out;
    }

    static Map<EditorBuffer, String> textsOf(java.util.List<EditorBuffer> buffers) {
        Map<EditorBuffer, String> out = new java.util.IdentityHashMap<>();
        for (EditorBuffer b : buffers) {
            if (b != null) {
                out.putIfAbsent(b, b.getContent());
            }
        }
        return out;
    }

    static com.editora.lsp.WorkspaceEditHazards.Disk workspaceDisk(WorkspaceFileOperations files) {
        return new com.editora.lsp.WorkspaceEditHazards.Disk() {
            @Override
            public boolean exists(Path path) {
                return files.exists(path);
            }

            @Override
            public boolean isDirectory(Path path) {
                return files.isDirectory(path);
            }
        };
    }

    static boolean sameFileInAnotherCase(WorkspaceFileOperations files, Path from, Path to) {
        if (!from.toString().equalsIgnoreCase(to.toString())) {
            return false;
        }
        try {
            WorkspaceFileIdentity fromIdentity = files.identity(from);
            if (fromIdentity == null) {
                return false;
            }
            WorkspaceFileIdentity toIdentity = files.identity(to);
            if (toIdentity == null) {
                return false;
            }
            Object source = fromIdentity.fileKey();
            Object destination = toIdentity.fileKey();
            // No file keys (Windows): the names differ only in case and both resolve, which is the same file
            // on every volume but a case-sensitive directory.
            return source == null || destination == null || source.equals(destination);
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Deletes {@code folder} and its parents up to (not including) {@code root} while they are empty, and returns
     * the folders it deleted.
     */
    static java.util.List<Path> removeEmptyFolders(Path folder, Path root) {
        java.util.List<Path> deleted = new java.util.ArrayList<>();
        @Nullable Path at = folder.toAbsolutePath().normalize();
        Path stop = root.toAbsolutePath().normalize();
        while (at != null && at.startsWith(stop) && !at.equals(stop)) {
            try (var entries = java.nio.file.Files.list(at)) {
                if (entries.findAny().isPresent()) {
                    break;
                }
                java.nio.file.Files.delete(at);
                deleted.add(at);
            } catch (java.io.IOException | RuntimeException e) {
                break;
            }
            at = at.getParent();
        }
        return deleted;
    }
}
