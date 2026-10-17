package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.process.ExecTargets;
import com.editora.run.PythonInterpreters;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Python file on a remote host (Python review B1, remote half): Pyright running there is told the project's
 * venv as the host names it, found there through Run's own planning; the status-bar item names it; and a
 * dropped connection falls back to the host's own python3, as Run does. Before, a remote Pyright was told
 * nothing and the item was hidden.
 */
@Tag("fx")
class PythonInterpreterRemoteFxTest {

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;

    @BeforeEach
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        Files.createDirectories(sftp.serverPath("app/.venv/bin"));
        Files.writeString(sftp.serverPath("app/.venv/pyvenv.cfg"), "home = /usr/bin\n");
        Files.writeString(sftp.serverPath("app/.venv/bin/python"), "");
        Files.writeString(sftp.serverPath("app/pyproject.toml"), "[project]\nname = \"a\"\n");
        Files.writeString(sftp.serverPath("app/main.py"), "import os\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        sftp.close();
        ExecTargets.setRemoteEnabled(null);
    }

    private static PythonInterpreterCoordinator coordinator(Path projectRoot, String[] item) {
        return new PythonInterpreterCoordinator(new PythonInterpreterCoordinator.Host() {
            @Override
            public Path projectRoot() {
                return projectRoot;
            }

            @Override
            public com.editora.config.ConfigManager config() {
                return null;
            }

            @Override
            public void setStatusItem(String text, String tooltip) {
                item[0] = text;
            }

            @Override
            public void interpreterChanged() {}

            @Override
            public OverlayHost overlayHost() {
                return null;
            }

            @Override
            public javafx.stage.Window window() {
                return null;
            }

            @Override
            public void setStatus(String message) {}
        });
    }

    @Test
    void aRemoteServerIsToldTheVenvAsTheHostNamesIt() throws Exception {
        Path root = sftp.remotePath("app");
        String[] item = new String[1];
        PythonInterpreterCoordinator c = coordinator(root, item);
        FxTestSupport.runOnFx(c::captureSnapshot);

        assertEquals(
                sftp.serverPath("app/.venv/bin/python").toString(),
                c.pythonPathForServerRoot(root),
                "the path on the host, not this machine's view of it");
        assertTrue(c.elsewhere(sftp.remotePath("app/main.py")));
        assertEquals(
                PythonInterpreters.Source.PROJECT_VENV,
                c.resolve(sftp.remotePath("app/main.py")).source());

        com.editora.editor.EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            var b = new com.editora.editor.EditorBuffer();
            b.setPath(sftp.remotePath("app/main.py"));
            b.setLanguageOverride("python");
            return b;
        });
        FxTestSupport.runOnFx(() -> c.refreshStatus(buffer));
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (item[0] == null && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
        }
        assertEquals("python (.venv)", item[0], "shown for a remote file: named, not run here");

        sftp.dropConnection();
        assertNull(c.pythonPathForServerRoot(root), "dropped: the host's own python3, Pyright's default");
    }
}
