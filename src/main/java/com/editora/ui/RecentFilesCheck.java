package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Predicate;

import javafx.application.Platform;

import com.editora.config.RecentFiles;
import com.editora.vfs.Vfs;

/**
 * Which recent files still exist, learnt off the FX thread. The recent menu is rebuilt at window setup and
 * whenever any window opens a file, and each rebuild used to {@code stat} every local entry on the FX thread —
 * twenty blocking calls that a recent file on an unreachable mount turns into a frozen window.
 *
 * <p>The menu is rendered from the last known answer (an entry not checked yet is shown), the disk is asked
 * on a worker, and the caller is told only when the answer differs from what it rendered.
 */
final class RecentFilesCheck {

    private final Executor worker;
    private final Executor fx;
    private final Predicate<Path> exists;

    /** Local entries the last completed check found missing. FX thread only. */
    private Set<Path> missing = Set.of();

    private long generation;

    RecentFilesCheck() {
        this(Thread::startVirtualThread, Platform::runLater, Files::exists);
    }

    RecentFilesCheck(Executor worker, Executor fx, Predicate<Path> exists) {
        this.worker = worker;
        this.fx = fx;
        this.exists = exists;
    }

    /** {@link RecentFiles#showable} against the cached answer: no filesystem access. */
    List<Path> showable(List<Path> entries) {
        return RecentFiles.showable(entries, Vfs::isLocal, path -> !missing.contains(path));
    }

    /**
     * Asks the disk about {@code recent}'s local entries in the background and runs {@code onChanged} on the
     * FX thread if the set of missing ones is not what {@link #showable} has been answering from. A newer
     * request supersedes an older one still in flight.
     */
    void revalidate(RecentFiles recent, Runnable onChanged) {
        List<Path> local = new ArrayList<>();
        if (recent != null) {
            for (Path path : recent.getList()) {
                if (path != null && Vfs.isLocal(path)) {
                    local.add(path);
                }
            }
        }
        long mine = ++generation;
        if (local.isEmpty()) {
            missing = Set.of();
            return;
        }
        worker.execute(() -> {
            Set<Path> gone = new HashSet<>();
            for (Path path : local) {
                try {
                    if (!exists.test(path)) {
                        gone.add(path);
                    }
                } catch (RuntimeException e) {
                    // cannot tell: keep offering it, as an unchecked entry is
                }
            }
            fx.execute(() -> {
                if (mine != generation || gone.equals(missing)) {
                    return;
                }
                missing = Set.copyOf(gone);
                onChanged.run();
            });
        });
    }
}
