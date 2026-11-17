package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.editora.process.ExecTargets;
import com.editora.process.MappedTestTarget;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A folder of this machine whose tools run elsewhere, reached through a share that does not report its changes
 * here (a WSL distribution's {@code \\wsl.localhost}): it is watched where its tools run, and what is reported
 * there comes back by this machine's names. The target here maps the folder to a symlink and runs commands on
 * this machine; the polling fallback is forced (this machine may or may not have inotifywait).
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the target's alias is a symlink
class MappedFolderWatchFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private Path folder;
    private MappedTestTarget target;
    private RemoteWatcher watcher;
    private ProjectPanel panel;
    private final List<ProjectPanel.FsChange> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        RemoteWatcher.pollForTest = true;
        folder = Files.createDirectories(base.resolve("app")).toRealPath();
        Files.createDirectories(folder.resolve("src"));
        Files.writeString(folder.resolve("src/Old.java"), "class Old {}\n");
        target = MappedTestTarget.register(folder, base.resolve("alias")).withoutLocalWatch();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (watcher != null) {
            watcher.stop();
        }
        if (panel != null) {
            ProjectPanel p = panel;
            FxTestSupport.runOnFx(p::dispose);
        }
        target.unregister();
        RemoteWatcher.pollForTest = false;
        ExecTargets.setRemoteEnabled(null);
    }

    private void awaitChange(Path path) throws InterruptedException {
        if (!Await.holdsWithin(() -> seen.stream().anyMatch(c -> c.path().equals(path)), Duration.ofSeconds(15))) {
            throw new AssertionError("never reported " + path + "; saw " + seen);
        }
    }

    private void awaitWatch(boolean remotely, String what) throws Exception {
        OnFx.until(() -> panel.watchesRemotely() == remotely, what, Duration.ofSeconds(10));
    }

    @Test
    void itIsWatchedWhereItsToolsRunAndReportedByThisMachinesNames() throws Exception {
        assertTrue(ProjectPanel.watchedOnTarget(folder));

        watcher = RemoteWatcher.start(folder, seen::addAll);
        assertNotNull(watcher);
        // The poll reports what is newer than its stamp, which it takes as it starts: dated a minute ahead, the change
        // is newer than the stamp whenever the stamp was taken.
        Path changed = Files.writeString(folder.resolve("src/New.java"), "class New {}\n");
        Files.setLastModifiedTime(
                changed,
                java.nio.file.attribute.FileTime.from(java.time.Instant.now().plusSeconds(60)));

        awaitChange(folder.resolve("src/New.java"));
        assertTrue(
                target.started().stream()
                        .anyMatch(argv -> argv.contains(base.resolve("alias").toString())),
                "the watch was given the target's name for the folder: " + target.started());
        assertEquals(List.of(), target.leaks());
    }

    @Test
    void aFolderThisMachinesWatcherSeesKeepsIt() throws Exception {
        target.unregister();
        MappedTestTarget container = MappedTestTarget.register(folder, base.resolve("mount"));
        try {
            assertFalse(ProjectPanel.watchedOnTarget(folder), "a bind mount is this machine's own folder");
        } finally {
            container.unregister();
        }
        target = MappedTestTarget.register(folder, base.resolve("alias2")).withoutLocalWatch();

        ExecTargets.setRemoteEnabled(() -> false);
        assertFalse(ProjectPanel.watchedOnTarget(folder), "its tools run here: so does its watch");
    }

    @Test
    void theProjectPanelMovesItsWatchWhenWhereToolsRunChanges() throws Exception {
        panel = FxTestSupport.callOnFx(() -> new ProjectPanel(p -> {}, (a, b) -> {}, p -> {}, p -> false));
        FxTestSupport.runOnFx(() -> panel.setRoot(folder));
        awaitWatch(true, "watched where its tools run");

        ExecTargets.setRemoteEnabled(() -> false); // remote commands switched off, or the answer withdrawn
        FxTestSupport.runOnFx(panel::refreshRemoteWatch);

        awaitWatch(false, "back on this machine's watcher");
    }
}
