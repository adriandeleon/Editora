package com.editora.testkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirDeletionStrategy;

/**
 * How every {@code @TempDir} is deleted (the default, set in {@code junit-platform.properties}): JUnit's own
 * deletion, tried again for up to ten seconds while something still holds the folder.
 *
 * <p>Opening or saving a file asks git about its folder ({@code git rev-parse --show-toplevel}, a status), a run
 * or an agent starts in the project, and each of those programs runs <em>in</em> the folder. On Windows a
 * process's working folder cannot be deleted, and git.exe takes tens of milliseconds to start and end: a test that
 * ended right after a save failed with "Failed to close extension context … being used by another process". About
 * forty test classes did, on Windows only. A folder still held after ten seconds is a real leak, and fails as before.
 */
public final class PatientTempDirDeletion implements TempDirDeletionStrategy {

    private static final Duration PATIENCE = Duration.ofSeconds(10);

    @Override
    public DeletionResult delete(Path tempDir, AnnotatedElementContext element, ExtensionContext extension)
            throws IOException {
        DeletionResult[] last = {Standard.INSTANCE.delete(tempDir, element, extension)};
        if (last[0].isSuccessful() || !Files.exists(tempDir)) {
            return last[0];
        }
        try {
            Await.holdsWithin(
                    () -> {
                        try {
                            last[0] = Standard.INSTANCE.delete(tempDir, element, extension);
                        } catch (IOException e) {
                            return false;
                        }
                        return last[0].isSuccessful() || !Files.exists(tempDir);
                    },
                    PATIENCE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return last[0];
    }
}
