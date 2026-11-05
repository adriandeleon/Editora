package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    private static Path project(Path work, String name, String tool, String script) throws Exception {
        Path project = Files.createDirectories(work.resolve(name));
        Files.writeString(project.resolve("pyproject.toml"), "[project]\nname = \"p\"\n");
        Files.writeString(Files.createDirectories(project.resolve(".venv")).resolve("pyvenv.cfg"), "home = /usr\n");
        executable(project.resolve(".venv/bin/python"), "#!/bin/sh\necho Python 3.99.1\n");
        executable(project.resolve(".venv/bin/" + tool), script);
        return project;
    }

    private static String echo(MainController c) throws Exception {
        javafx.scene.control.Label echo = FxTestSupport.field(c.statusBar(), "echo");
        return FxTestSupport.callOnFx(echo::getText);
    }

    private static String waitForEcho(MainController c, String part) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String now = echo(c);
        while ((now == null || !now.contains(part)) && System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            now = echo(c);
        }
        return now;
    }

    /**
     * Python review 2, D102: Black prints nothing (exit 0) for a file its force-exclude skips. That emptied the buffer
     * and said "Formatted with black"; now the text stays and the status says why.
     */
    @Test
    void aFormatterThatPrintsNothingLeavesTheTextAlone() throws Exception {
        assumeTrue(!com.editora.run.PythonInterpreters.isWindows(), "POSIX stand-in tools");
        assumeTrue(System.getenv("VIRTUAL_ENV") == null && System.getenv("CONDA_PREFIX") == null);
        FxTestSupport.bootToolkit();
        Path project = project(work, "excluded", "black", "#!/bin/sh\ncat >/dev/null\nexit 0\n");
        Path app = Files.writeString(project.resolve("migration_0001.py"), "x = 1\nprint(x)\n");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {})) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));
            FxTestSupport.drainFx();
            com.editora.command.CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("lsp.formatDocument"));

            assertTrue(waitForEcho(c, "printed nothing").contains("printed nothing"), echo(c));
            assertEquals(
                    "x = 1\nprint(x)\n",
                    FxTestSupport.callOnFx(() -> c.activeBuffer().getArea().getText()));
        }
    }

    /**
     * Python review 2, A103: a Ruff set to {@code line-ending = "cr-lf"} prints CRLF. Its lone {@code \r} used to
     * become an extra blank line at the end of the change, on every run.
     */
    @Test
    void aCrlfFormatterAddsNoBlankLine() throws Exception {
        assumeTrue(!com.editora.run.PythonInterpreters.isWindows(), "POSIX stand-in tools");
        assumeTrue(System.getenv("VIRTUAL_ENV") == null && System.getenv("CONDA_PREFIX") == null);
        FxTestSupport.bootToolkit();
        Path project = project(work, "crlf", "ruff", "#!/bin/sh\nsed 's/x=1/x = 1/; s/$/\\r/'\n");
        Path app = Files.writeString(project.resolve("app.py"), "x=1\nprint(x)\n");
        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {})) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));
            FxTestSupport.drainFx();
            com.editora.command.CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("lsp.formatDocument"));

            assertEquals("x = 1\nprint(x)\n", waitForText(c, "x = 1\nprint(x)\n"));
            FxTestSupport.runOnFx(() -> registry.run("lsp.formatDocument"));
            assertTrue(waitForEcho(c, "Already formatted").contains("Already formatted"), echo(c));
        }
    }

    /**
     * Python review 2, A111: with format on save on, an explicit save runs the project's Ruff and saves again; with
     * it off (the default) the file is saved as typed.
     */
    @Test
    void formatOnSaveRunsAfterAnExplicitSaveWhenTurnedOn() throws Exception {
        assumeTrue(!com.editora.run.PythonInterpreters.isWindows(), "POSIX stand-in tools");
        assumeTrue(System.getenv("VIRTUAL_ENV") == null && System.getenv("CONDA_PREFIX") == null);
        FxTestSupport.bootToolkit();
        Path project = project(work, "onsave", "ruff", "#!/bin/sh\nsed 's/=/ = /'\n");
        Path app = Files.writeString(project.resolve("app.py"), "print(1)\n");
        com.editora.config.Settings[] settings = new com.editora.config.Settings[1];
        try (FxWindowFixture fx = FxWindowFixture.create(
                Files.createDirectories(work.resolve("cfg")), shared -> settings[0] = shared.getSettings())) {
            MainController c = fx.controller;
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));
            FxTestSupport.drainFx();
            com.editora.command.CommandRegistry registry = FxTestSupport.field(c, "registry");

            FxTestSupport.runOnFx(() -> c.activeBuffer().getArea().insertText(0, "a=1\n"));
            FxTestSupport.runOnFx(() -> registry.run("file.save"));
            FxTestSupport.drainFx();
            Thread.sleep(1500); // a format would have run by now
            FxTestSupport.drainFx();
            assertEquals("a=1\nprint(1)\n", Files.readString(app), "off by default: saved as typed");

            FxTestSupport.runOnFx(() -> settings[0].setPythonFormatOnSave(true));
            FxTestSupport.runOnFx(() -> c.activeBuffer().getArea().insertText(0, "b=2\n"));
            FxTestSupport.runOnFx(() -> registry.run("file.save"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.readString(app).equals("b = 2\na = 1\nprint(1)\n") && System.nanoTime() < deadline) {
                FxTestSupport.drainFx();
            }
            assertEquals("b = 2\na = 1\nprint(1)\n", Files.readString(app), "formatted, then saved again");
            assertFalse(FxTestSupport.callOnFx(() -> c.activeBuffer().isDirty()));
        }
    }
}
