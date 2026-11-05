package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Label;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The Python interpreter in a real window (Python review B1, adl's decision 1): a project with a {@code .venv}
 * shows {@code Python <version> (.venv)} in the status bar, the language server is told that venv's
 * interpreter, and a pick from the picker is stored for the project and followed — until Detect automatically
 * clears it.
 */
@Tag("fx")
class PythonInterpreterStatusFxTest {

    @TempDir
    Path work;

    /** A stand-in interpreter: answers {@code --version} like CPython does. */
    private static Path fakePython(Path file, String version) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\necho \"Python " + version + "\"\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    private static String statusItem(MainController c) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            var node = c.stage.getScene().getRoot().lookup(".status-python");
            return node instanceof Label l && l.isVisible() ? l.getText() : null;
        });
    }

    private static String waitForItem(MainController c, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String now = statusItem(c);
        while (!expected.equals(now) && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            now = statusItem(c);
        }
        return now;
    }

    @Test
    void theProjectVenvIsShownToldToTheServerAndAPickIsFollowed() throws Exception {
        assumeTrue(!com.editora.run.PythonInterpreters.isWindows(), "POSIX stand-in interpreter");
        assumeTrue(
                System.getenv("VIRTUAL_ENV") == null && System.getenv("CONDA_PREFIX") == null,
                "an activated environment would win, by design");
        FxTestSupport.bootToolkit();
        Path project = Files.createDirectories(work.resolve("proj"));
        Files.writeString(project.resolve("pyproject.toml"), "[project]\nname = \"p\"\n");
        Files.writeString(Files.createDirectories(project.resolve(".venv")).resolve("pyvenv.cfg"), "home = /usr\n");
        Path venvPython = fakePython(project.resolve(".venv/bin/python"), "3.99.1");
        Path other = fakePython(work.resolve("elsewhere/bin/python3"), "3.98.0");
        Path app = Files.writeString(project.resolve("app.py"), "import os\n");

        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {})) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));

            assertEquals("Python 3.99.1 (.venv)", waitForItem(c, "Python 3.99.1 (.venv)"));
            PythonInterpreterCoordinator interpreters = c.lspCoordinator.pythonInterpreters;
            assertEquals(
                    venvPython.toString(),
                    interpreters.pythonPathForServerRoot(project),
                    "Pyright is told the project's venv, not PATH's python3");

            FxTestSupport.runOnFx(() -> interpreters.store(other.toString()));
            String picked = "Python 3.98.0 (" + com.editora.i18n.Messages.tr("python.interpreter.source.picked") + ")";
            assertEquals(picked, waitForItem(c, picked));
            assertEquals(
                    other.toString(), fx.controller.config.getWorkspaceState().getPythonInterpreter());
            assertEquals(other.toString(), interpreters.pythonPathForServerRoot(project));

            FxTestSupport.runOnFx(() -> interpreters.store(""));
            assertEquals("Python 3.99.1 (.venv)", waitForItem(c, "Python 3.99.1 (.venv)"));
            assertTrue(fx.controller
                    .config
                    .getWorkspaceState()
                    .getPythonInterpreter()
                    .isEmpty());
        }
    }

    /**
     * A project whose Python code and {@code .venv} live in a {@code backend/} folder: the file's server is rooted
     * at {@code backend/} and told that venv, and the item says what the server was told — it said {@code (.venv)}
     * while Pyright, rooted at the project, analysed with the PATH's Python (Python review 2, B101).
     */
    @Test
    void aSubProjectsVenvIsWhatItsServerIsToldAndWhatTheItemShows() throws Exception {
        assumeTrue(!com.editora.run.PythonInterpreters.isWindows(), "POSIX stand-in interpreter");
        assumeTrue(
                System.getenv("VIRTUAL_ENV") == null && System.getenv("CONDA_PREFIX") == null,
                "an activated environment would win, by design");
        FxTestSupport.bootToolkit();
        Path repo = Files.createDirectories(work.resolve("repo"));
        Path backend = Files.createDirectories(repo.resolve("backend"));
        Files.writeString(backend.resolve("pyproject.toml"), "[project]\nname = \"b\"\n");
        Files.writeString(Files.createDirectories(backend.resolve(".venv")).resolve("pyvenv.cfg"), "home = /usr\n");
        Path venvPython = fakePython(backend.resolve(".venv/bin/python"), "3.97.2");
        Path test = Files.createDirectories(backend.resolve("tests")).resolve("test_x.py");
        Files.writeString(test, "import os\n");

        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setLspInstallPrompts(false);
            shared.getSettings()
                    .setPythonLspCommand(work.resolve("no-such-server").toString()); // nothing starts
        })) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setProjectSupport(true);
                c.setWindowContext(fx.windowManager, new com.editora.config.Project("repo", "repo", repo.toString()));
            });
            FxTestSupport.runOnFx(() -> c.openAndNavigate(test, 1));
            assertEquals("Python 3.97.2 (.venv)", waitForItem(c, "Python 3.97.2 (.venv)"));
            Path serverRoot = FxTestSupport.callOnFx(() -> c.lspCoordinator.lspRootFor(c.activeBuffer(), "python"));
            assertEquals(
                    backend.toAbsolutePath().normalize(),
                    serverRoot.toAbsolutePath().normalize());
            assertEquals(
                    venvPython.toString(),
                    c.lspCoordinator.pythonInterpreters.pythonPathForServerRoot(serverRoot),
                    "the server is told the backend's venv, not PATH's python3");
        }
    }
}
