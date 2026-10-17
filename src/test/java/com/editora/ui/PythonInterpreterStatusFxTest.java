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
}
