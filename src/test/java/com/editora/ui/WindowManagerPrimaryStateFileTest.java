package com.editora.ui;

import java.nio.file.Path;

import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.SharedConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The launch's prefetch thread reads the session of the window {@code launch} will focus first; this is the
 * prediction it uses, which must agree with {@code launch}'s own choice.
 */
class WindowManagerPrimaryStateFileTest {

    private static SharedConfig shared(Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        return config.shared();
    }

    @Test
    void nothingSavedMeansTheDefaultWindow(@TempDir Path dir) {
        SharedConfig shared = shared(dir);
        try {
            assertNull(WindowManager.primaryWindowStateFile(shared, null, false));
        } finally {
            shared.shutdown();
        }
    }

    @Test
    void theActiveProjectWindowWhenTheGlobalWindowWasNotOpen(@TempDir Path dir) {
        SharedConfig shared = shared(dir);
        try {
            ProjectManager pm = shared.projects();
            Project a = pm.createOrGet("Alpha", dir.resolve("alpha"));
            Project b = pm.createOrGet("Beta", dir.resolve("beta"));
            pm.setOpenWindows(java.util.List.of(a.id(), b.id()));
            pm.setActive(b.id());
            assertEquals(pm.stateFile(b), WindowManager.primaryWindowStateFile(shared, null, false));

            pm.markOpen(""); // the global window restores and is focused: its session is the bootstrap's
            assertNull(WindowManager.primaryWindowStateFile(shared, null, false));
        } finally {
            shared.shutdown();
        }
    }

    @Test
    void theCliProjectWinsWhenItExistsAndIsUnknownWhenItDoesNot(@TempDir Path dir) {
        SharedConfig shared = shared(dir);
        try {
            ProjectManager pm = shared.projects();
            Project a = pm.createOrGet("Alpha", dir.resolve("alpha"));
            pm.markOpen("");
            assertEquals(
                    pm.stateFile(a),
                    WindowManager.primaryWindowStateFile(
                            shared, dir.resolve("alpha").toString(), false));
            assertNull(
                    WindowManager.primaryWindowStateFile(
                            shared, dir.resolve("new-folder").toString(), false),
                    "launch creates the project; there is no session file to read yet");
        } finally {
            shared.shutdown();
        }
    }

    @Test
    void untitledWindowsAndSingleWindowLaunches(@TempDir Path dir) {
        SharedConfig shared = shared(dir);
        try {
            ProjectManager pm = shared.projects();
            pm.setOpenWindows(java.util.List.of("untitled:abcd1234"));
            assertEquals(
                    dir.resolve("windows").resolve("abcd1234.json"),
                    WindowManager.primaryWindowStateFile(shared, null, false));
            assertNull(WindowManager.primaryWindowStateFile(shared, null, true), "--single-window: not predicted");
        } finally {
            shared.shutdown();
        }
    }
}
