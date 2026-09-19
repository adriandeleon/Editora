package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;

/** Cross-cutting path lookup and off-thread disk reconciliation for a window's open buffers. */
final class OpenBufferLifecycle {

    private OpenBufferLifecycle() {}

    static List<EditorBuffer> atOrUnder(EditorArea editorArea, Function<Tab, EditorBuffer> bufferOf, Path target) {
        if (target == null) {
            return List.of();
        }
        Path root = target.toAbsolutePath().normalize();
        List<EditorBuffer> out = new ArrayList<>();
        for (Tab tab : editorArea.tabs()) {
            EditorBuffer buffer = bufferOf.apply(tab);
            Path path = buffer == null ? null : buffer.getPath();
            if (path == null) {
                continue;
            }
            try {
                Path normalized = path.toAbsolutePath().normalize();
                if (com.editora.config.PathKeys.sameNormalized(path, target) || normalized.startsWith(root)) {
                    out.add(buffer);
                }
            } catch (java.nio.file.ProviderMismatchException ignored) {
                if (com.editora.config.PathKeys.sameNormalized(path, target)) {
                    out.add(buffer);
                }
            }
        }
        return List.copyOf(out);
    }

    static void reloadChanged(
            EditorArea editorArea,
            Function<Tab, EditorBuffer> bufferOf,
            FileWorkflowCoordinator files,
            LspCoordinator lsp) {
        List<ReloadCandidate> candidates = new ArrayList<>();
        for (Tab tab : editorArea.tabs()) {
            EditorBuffer buffer = bufferOf.apply(tab);
            if (buffer != null && buffer.getPath() != null && !buffer.isDirty()) {
                candidates.add(new ReloadCandidate(tab, buffer, buffer.getPath(), buffer.diskSnapshot()));
            }
        }
        files.fileLoadExecutor.execute(() -> {
            List<ReloadCandidate> changed = candidates.stream()
                    .filter(candidate -> Files.exists(candidate.file())
                            && candidate
                                    .disk()
                                    .differsFrom(
                                            files.lastModifiedMillis(candidate.file()),
                                            files.fileSize(candidate.file())))
                    .toList();
            Platform.runLater(() -> applyReloads(editorArea, files, lsp, changed));
        });
    }

    static void invalidateGitWrites(
            EditorArea editorArea,
            Function<Tab, EditorBuffer> bufferOf,
            FileWorkflowCoordinator files,
            Path root,
            List<String> pathspecs) {
        if (root == null) {
            return;
        }
        for (EditorBuffer buffer : atOrUnder(editorArea, bufferOf, root)) {
            Path file = buffer.getPath();
            String relative = com.editora.git.GitService.repoRelative(root, file);
            if (relative != null
                    && (pathspecs.isEmpty()
                            || pathspecs.stream().anyMatch(pathspec -> GitCoordinator.selects(pathspec, relative)))) {
                files.invalidatePendingWrite(file);
            }
        }
    }

    private static void applyReloads(
            EditorArea editorArea, FileWorkflowCoordinator files, LspCoordinator lsp, List<ReloadCandidate> changed) {
        if (changed.isEmpty()) {
            return;
        }
        List<Path> reloaded = new ArrayList<>();
        AtomicInteger remaining = new AtomicInteger(changed.size());
        for (ReloadCandidate candidate : changed) {
            EditorBuffer buffer = candidate.buffer();
            if (!buffer.isDisposed()
                    && !buffer.isDirty()
                    && Objects.equals(candidate.file(), buffer.getPath())
                    && editorArea.tabs().contains(candidate.tab())) {
                files.reloadFromDisk(candidate.tab(), buffer, applied -> {
                    if (applied) {
                        reloaded.add(candidate.file());
                    }
                    if (remaining.decrementAndGet() == 0) {
                        lsp.watchedFilesReloaded(reloaded);
                    }
                });
            } else if (remaining.decrementAndGet() == 0) {
                lsp.watchedFilesReloaded(reloaded);
            }
        }
    }

    private record ReloadCandidate(Tab tab, EditorBuffer buffer, Path file, EditorBuffer.DiskSnapshot disk) {}
}
