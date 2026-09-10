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

    private final Function<Path, EditorBuffer> findBuffer;
    private final Consumer<Path> selectBuffer;
    private final Predicate<EditorBuffer> hasPendingSave;
    private final Predicate<EditorBuffer> confirmClose;
    private final Consumer<Path> invalidatePendingWrite;
    private final BiConsumer<Path, Consumer<HistoryCoordinator.DeleteCapture>> captureHistory;
    private final Consumer<String> setStatus;

    ProjectDeleteCoordinator(
            Function<Path, EditorBuffer> findBuffer,
            Consumer<Path> selectBuffer,
            Predicate<EditorBuffer> hasPendingSave,
            Predicate<EditorBuffer> confirmClose,
            Consumer<Path> invalidatePendingWrite,
            BiConsumer<Path, Consumer<HistoryCoordinator.DeleteCapture>> captureHistory,
            Consumer<String> setStatus) {
        this.findBuffer = Objects.requireNonNull(findBuffer, "findBuffer");
        this.selectBuffer = Objects.requireNonNull(selectBuffer, "selectBuffer");
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
        Map<EditorBuffer, Long> acceptedVersions = new IdentityHashMap<>();
        for (Path file : targets) {
            EditorBuffer buffer = findBuffer.apply(file);
            if (buffer == null) {
                continue;
            }
            if (buffer.isDirty() || hasPendingSave.test(buffer)) {
                selectBuffer.accept(file);
                if (!confirmClose.test(buffer)) {
                    completion.accept(ProjectPanel.DeleteApproval.denied());
                    return;
                }
            }
            acceptedVersions.put(buffer, buffer.docVersion());
        }

        // Cancel every window's older ticket before history publication so it cannot recreate a deleted path.
        targets.forEach(invalidatePendingWrite);
        capture(targets, 0, acceptedVersions, new LinkedHashMap<>(), completion);
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
                        || files.stream().noneMatch(path -> PathKeys.sameNormalized(path, buffer.getPath()))) {
                    setStatus.accept(tr("project.deleteBufferChanged"));
                    completion.accept(ProjectPanel.DeleteApproval.denied());
                    return;
                }
            }
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
