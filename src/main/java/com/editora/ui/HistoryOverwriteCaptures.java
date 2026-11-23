package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import javafx.application.Platform;

/**
 * Copies files that something outside the editor's save path is about to overwrite in place — Git's Rollback of
 * tracked changes, Decrypt to File onto an existing file — into Local History first, one at a time, and says
 * which one could not be kept. What it captures follows {@link HistoryCoordinator#captureBeforeOverwriteDurably}:
 * text files Local History can hold become durable revisions; binary and oversized files, and everything while
 * the feature is off, pass without a copy. A path that is not a regular file now (gone, a folder, a link) has no
 * text of its own to lose and is passed over.
 */
final class HistoryOverwriteCaptures {

    /** One durable capture: {@link HistoryCoordinator#captureBeforeOverwriteDurably}, or a test's stand-in. */
    @FunctionalInterface
    interface Capturer {
        void capture(Path file, Consumer<HistoryCoordinator.OverwriteCapture> completion);
    }

    private HistoryOverwriteCaptures() {}

    /**
     * Captures each of {@code files} in turn, then tells {@code done} on the FX thread: empty when every one is
     * safe to overwrite, else the first file whose copy could not be made (nothing after it was tried).
     */
    static void captureAll(List<Path> files, Capturer capturer, Consumer<Optional<Path>> done) {
        next(List.copyOf(files), 0, capturer, done);
    }

    private static void next(List<Path> files, int index, Capturer capturer, Consumer<Optional<Path>> done) {
        if (index >= files.size()) {
            done.accept(Optional.empty());
            return;
        }
        Path file = files.get(index);
        // Looked at off the FX thread (PERF-01, Pauta 6.2.0).
        com.editora.perf.OffFx.then(
                "history-overwrite-look", () -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS), regular -> {
                    if (!regular.or(false)) {
                        next(files, index + 1, capturer, done);
                        return;
                    }
                    capturer.capture(
                            file,
                            capture -> Platform.runLater(() -> {
                                if (capture.safe()) {
                                    next(files, index + 1, capturer, done); // one file per FX turn
                                } else {
                                    done.accept(Optional.of(file));
                                }
                            }));
                });
    }
}
