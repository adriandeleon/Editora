package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Format Document on a Python file in a real window, language server off (adl's decision 4): the project's
 * environment's Ruff formats the buffer, as one undoable edit. Before, it said "No formatter available".
 */
@Tag("fx")
class PythonFormatDocumentFxTest {

    @TempDir
    Path work;

    private static void executable(Path file, String body) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private static String waitForText(MainController c, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String now = FxTestSupport.callOnFx(() -> c.activeBuffer().getArea().getText());
        while (!expected.equals(now) && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            now = FxTestSupport.callOnFx(() -> c.activeBuffer().getArea().getText());
        }
        return now;
    }

    @Test
    void formatDocumentRunsTheProjectsRuff() throws Exception {
        assumeTrue(!com.editora.run.PythonInterpreters.isWindows(), "POSIX stand-in tools");
        assumeTrue(System.getenv("VIRTUAL_ENV") == null && System.getenv("CONDA_PREFIX") == null);
        FxTestSupport.bootToolkit();
        Path project = Files.createDirectories(work.resolve("proj"));
        Files.writeString(project.resolve("pyproject.toml"), "[project]\nname = \"p\"\n");
        Files.writeString(Files.createDirectories(project.resolve(".venv")).resolve("pyvenv.cfg"), "home = /usr\n");
        executable(project.resolve(".venv/bin/python"), "#!/bin/sh\necho Python 3.99.1\n");
        executable(project.resolve(".venv/bin/ruff"), "#!/bin/sh\n[ \"$1\" = format ] || exit 9\nsed 's/x=1/x = 1/'\n");
        Path app = Files.writeString(project.resolve("app.py"), "x=1\nprint(x)\n");

        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {})) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));
            FxTestSupport.drainFx();
            com.editora.command.CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("lsp.formatDocument"));

            assertEquals("x = 1\nprint(x)\n", waitForText(c, "x = 1\nprint(x)\n"));
            FxTestSupport.runOnFx(() -> c.activeBuffer().getArea().undo());
            assertEquals(
                    "x=1\nprint(x)\n",
                    FxTestSupport.callOnFx(() -> c.activeBuffer().getArea().getText()),
                    "one undo step takes it back");
        }
    }
}
