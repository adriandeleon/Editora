package com.editora.ui;

import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.application.Platform;

import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/** Prepares Project-tree deletion without allowing dirty or unrecoverable content to be discarded. */
final class ProjectDeleteCoordinator implements ProjectPanel.DeletePreparation {

    private final Function<Path, List<EditorBuffer>> findBuffers;
    private final Consumer<EditorBuffer> revealBuffer;
    private final Predicate<EditorBuffer> hasPendingSave;
    private final Predicate<EditorBuffer> confirmClose;
    private final Consumer<Path> invalidatePendingWrite;
    private final BiConsumer<Path, Consumer<HistoryCoordinator.DeleteCapture>> captureHistory;
    private final Consumer<String> setStatus;

    /**
     * The exact buffer states the last successful preflight covered: clean, saved by the prompt, or
     * explicitly discarded. Closing a tab because its file was deleted consults this, so a buffer nobody was
     * asked about — or one edited after the answer — is never dropped with the file.
     */
    private final Map<EditorBuffer, Long> approved = new java.util.WeakHashMap<>();

    /**
     * @param findBuffers every open buffer at or under a path, in <em>every</em> window — the tabs are closed
     *     in all of them once the file is gone, so all of them are asked first
     * @param revealBuffer brings the buffer's window and tab forward before its prompt
     * @param hasPendingSave asked of the window that owns the buffer
     * @param confirmClose the unsaved-changes prompt of the window that owns the buffer
     */
    ProjectDeleteCoordinator(
            Function<Path, List<EditorBuffer>> findBuffers,
            Consumer<EditorBuffer> revealBuffer,
            Predicate<EditorBuffer> hasPendingSave,
            Predicate<EditorBuffer> confirmClose,
            Consumer<Path> invalidatePendingWrite,
            BiConsumer<Path, Consumer<HistoryCoordinator.DeleteCapture>> captureHistory,
            Consumer<String> setStatus) {
        this.findBuffers = Objects.requireNonNull(findBuffers, "findBuffers");
        this.revealBuffer = Objects.requireNonNull(revealBuffer, "revealBuffer");
        this.hasPendingSave = Objects.requireNonNull(hasPendingSave, "hasPendingSave");
        this.confirmClose = Objects.requireNonNull(confirmClose, "confirmClose");
        this.invalidatePendingWrite = Objects.requireNonNull(invalidatePendingWrite, "invalidatePendingWrite");
        this.captureHistory = Objects.requireNonNull(captureHistory, "captureHistory");
        this.setStatus = Objects.requireNonNull(setStatus, "setStatus");
    }

    /**
     * Resolves dirty-buffer choices, cancels every old-path write, and publishes each eligible pre-delete
     * snapshot durably. Approval is returned only after every selected file is recoverable.
     */
    @Override
    public void prepare(List<Path> files, Consumer<ProjectPanel.DeleteApproval> completion) {
        Objects.requireNonNull(completion, "completion");
        List<Path> targets = files == null
                ? List.of()
                : files.stream().filter(Objects::nonNull).distinct().toList();
        approved.clear();
        Map<EditorBuffer, Long> acceptedVersions = new IdentityHashMap<>();
        for (Path file : targets) {
            for (EditorBuffer buffer : findBuffers.apply(file)) {
                if (buffer == null || buffer.isDisposed() || acceptedVersions.containsKey(buffer)) {
                    continue;
                }
                if (buffer.isDirty() || hasPendingSave.test(buffer)) {
                    revealBuffer.accept(buffer);
                    if (!confirmClose.test(buffer)) {
                        completion.accept(ProjectPanel.DeleteApproval.denied());
                        return;
                    }
                }
                acceptedVersions.put(buffer, buffer.docVersion());
            }
        }

        // Cancel every window's older ticket before history publication so it cannot recreate a deleted path.
        targets.forEach(invalidatePendingWrite);
        capture(targets, 0, acceptedVersions, new LinkedHashMap<>(), completion);
    }

    /** Whether the last approved delete covered {@code buffer} exactly as it is now. FX thread. */
    boolean covers(EditorBuffer buffer) {
        Long version = approved.get(buffer);
        return version != null && version == buffer.docVersion();
    }

    private static boolean atOrUnder(Path target, Path file) {
        if (PathKeys.sameNormalized(target, file)) {
            return true;
        }
        try {
            return file.toAbsolutePath()
                    .normalize()
                    .startsWith(target.toAbsolutePath().normalize());
        } catch (java.nio.file.ProviderMismatchException | java.io.IOError differentFileSystem) {
            return false;
        }
    }

    private void capture(
            List<Path> files,
            int index,
            Map<EditorBuffer, Long> acceptedVersions,
            Map<Path, byte[]> expectedBytes,
            Consumer<ProjectPanel.DeleteApproval> completion) {
        if (index >= files.size()) {
            for (Map.Entry<EditorBuffer, Long> accepted : acceptedVersions.entrySet()) {
                EditorBuffer buffer = accepted.getKey();
                if (buffer.isDisposed()
                        || buffer.getPath() == null
                        || buffer.docVersion() != accepted.getValue()
                        || files.stream().noneMatch(path -> atOrUnder(path, buffer.getPath()))) {
                    setStatus.accept(tr("project.deleteBufferChanged"));
                    completion.accept(ProjectPanel.DeleteApproval.denied());
                    return;
                }
            }
            approved.putAll(acceptedVersions);
            completion.accept(new ProjectPanel.DeleteApproval(true, expectedBytes));
            return;
        }

        Path file = files.get(index);
        captureHistory.accept(
                file,
                result -> Platform.runLater(() -> {
                    if (!result.durable()) {
                        setStatus.accept(tr("project.deleteHistoryFailed"));
                        completion.accept(ProjectPanel.DeleteApproval.denied());
                        return;
                    }
                    if (result.expectedBytes() != null) {
                        expectedBytes.put(file, result.expectedBytes());
                    }
                    capture(files, index + 1, acceptedVersions, expectedBytes, completion);
                }));
    }
}
