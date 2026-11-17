package com.editora.ui;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import org.jspecify.annotations.Nullable;

/**
 * Where a file or folder chooser opens, without a look at the disk on the FX thread first (PERF-01, Pauta 6.0.1).
 *
 * <p>A dialog used to open on the project root or the file's folder only after {@code Files.isDirectory} said it
 * was one. JavaFX checks the folder itself when the dialog is shown, and refuses one that is not there with an
 * {@link IllegalArgumentException}: the folder is named as it is, and a refused one is dropped and the dialog
 * shown again where the system opens it.
 */
final class ChooserStart {

    private ChooserStart() {}

    /** Opens {@code chooser} in {@code folder} when it is a local path (a remote one cannot be). */
    static void set(FileChooser chooser, @Nullable Path folder) {
        File local = local(folder);
        if (local != null) {
            chooser.setInitialDirectory(local);
        }
    }

    /** As above, for a folder chooser. */
    static void set(DirectoryChooser chooser, @Nullable Path folder) {
        File local = local(folder);
        if (local != null) {
            chooser.setInitialDirectory(local);
        }
    }

    /** Shows {@code chooser} with {@code show}; when its start folder is refused, shows it again without one. */
    static @Nullable File show(FileChooser chooser, Function<FileChooser, @Nullable File> show) {
        return retry(() -> show.apply(chooser), () -> chooser.setInitialDirectory(null));
    }

    /** As above, for a folder chooser. */
    static @Nullable File show(DirectoryChooser chooser, Function<DirectoryChooser, @Nullable File> show) {
        return retry(() -> show.apply(chooser), () -> chooser.setInitialDirectory(null));
    }

    private static @Nullable File retry(Supplier<@Nullable File> show, Runnable dropStart) {
        try {
            return show.get();
        } catch (IllegalArgumentException startNotAFolder) {
            dropStart.run();
            return show.get();
        }
    }

    private static @Nullable File local(@Nullable Path folder) {
        if (folder == null || folder.getFileSystem() != FileSystems.getDefault()) {
            return null;
        }
        return folder.toFile();
    }
}
