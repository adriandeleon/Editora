package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.run.PythonInterpreters;
import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe against the real Pyright (Python review 2, D108). Every other Python language-server test talks to
 * {@code FakeLanguageServer}, so what Pyright really does with what Editora sends was checked only by hand. In a
 * real window with a project {@code .venv}:
 * <ul>
 *   <li>Pyright is told the venv's interpreter ({@code python.pythonPath}) and resolves a package installed only
 *       there, while a missing one is still an error;</li>
 *   <li>an unused import arrives tagged unnecessary and a call to a {@code @deprecated} function tagged deprecated
 *       (the client's {@code tagSupport});</li>
 *   <li>Organize Imports lands once: Pyright both sends {@code workspace/applyEdit} and returns the edit, and one
 *       Undo takes it back.</li>
 * </ul>
 *
 * <p>Locally: {@code ./mvnw test -Dtest=PyrightProbeFxTest -Dgroups=probe -Dlsp.probe=true}, with
 * {@code pyright-langserver} and a {@code python3} that can make a venv on the PATH. CI's {@code node-probes} job
 * installs Pyright and runs it.
 */
@Tag("probe")
@Tag("fx")
class PyrightProbeFxTest {

    private static final long WAIT_SECONDS = 90;

    @TempDir
    Path work;

    private static List<LspDiagnostic> diagnostics(EditorBuffer buffer) throws Exception {
        Object overlay = FxTestSupport.field(buffer, "lspOverlay");
        if (overlay == null) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        List<LspDiagnostic> list = (List<LspDiagnostic>)
                FxTestSupport.callOnFx(() -> FxTestSupport.call(overlay, "diagnostics", new Class<?>[0]));
        return List.copyOf(list);
    }

    private static List<LspDiagnostic> waitForDiagnostics(MainController c, Predicate<List<LspDiagnostic>> done)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        List<LspDiagnostic> now = List.of();
        while (System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            now = diagnostics(FxTestSupport.callOnFx(c::activeBuffer));
            if (done.test(now)) {
                return now;
            }
            Thread.sleep(200);
        }
        return now;
    }

    private static String text(MainController c) throws Exception {
        return FxTestSupport.callOnFx(() -> c.activeBuffer().getArea().getText());
    }

    @Test
    void pyrightUsesTheProjectVenvTagsHintsAndOrganizesImportsOnce() throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(!PythonInterpreters.isWindows(), "POSIX venv layout (bin/python)");
        assumeTrue(PythonInterpreters.onAugmentedPath("pyright-langserver").isPresent(), "needs pyright-langserver");
        assumeTrue(System.getenv("VIRTUAL_ENV") == null && System.getenv("CONDA_PREFIX") == null, "no activated env");
        FxTestSupport.bootToolkit();
        Path project = Files.createDirectories(work.resolve("proj"));
        Files.writeString(project.resolve("pyproject.toml"), "[project]\nname = \"probe\"\nversion = \"0.1\"\n");
        Process venv = new ProcessBuilder(
                        "python3",
                        "-m",
                        "venv",
                        "--without-pip",
                        project.resolve(".venv").toString())
                .redirectErrorStream(true)
                .start();
        assumeTrue(venv.waitFor(60, TimeUnit.SECONDS) && venv.exitValue() == 0, "python3 can make a venv");
        Path sitePackages;
        try (var walk = Files.walk(project.resolve(".venv/lib"))) {
            sitePackages = walk.filter(p -> p.getFileName().toString().equals("site-packages"))
                    .findFirst()
                    .orElseThrow();
        }
        Files.createDirectories(sitePackages.resolve("fakepkg"));
        Files.writeString(sitePackages.resolve("fakepkg/__init__.py"), "VALUE = 1\n");
        Path app = Files.writeString(project.resolve("app.py"), """
                import os
                import fakepkg
                import missingpkg
                from typing_extensions import deprecated


                @deprecated("use something else")
                def old() -> None: ...


                old()
                print(fakepkg.VALUE, missingpkg)
                """);
        String unsorted = "import sys\nimport  json\nimport abc\n\nprint(sys, json, abc, undefined_name)\n";
        Path org = Files.writeString(project.resolve("org.py"), unsorted);

        try (FxWindowFixture fx = FxWindowFixture.create(Files.createDirectories(work.resolve("cfg")), shared -> {
            shared.getSettings().setLspSupport(true);
            shared.getSettings().setLspInstallPrompts(false);
        })) {
            MainController c = fx.controller;
            LspManager lsp = FxTestSupport.field(c, "lspManager");
            FxTestSupport.runOnFx(() -> c.openAndNavigate(app, 1));
            List<LspDiagnostic> got = waitForDiagnostics(
                    c, d -> d.stream().anyMatch(x -> x.message().contains("missingpkg")));

            assertEquals(project.resolve(".venv/bin/python").toString(), lsp.pythonPathFor(app));
            assertTrue(got.stream().anyMatch(d -> d.message().contains("missingpkg")), "missing import: " + got);
            assertTrue(
                    got.stream().noneMatch(d -> d.message().contains("fakepkg")),
                    "fakepkg lives only in the venv: " + got);
            assertTrue(got.stream().anyMatch(d -> d.unnecessary() && d.message().contains("os")), "unused os: " + got);
            assertTrue(got.stream().anyMatch(LspDiagnostic::deprecated), "deprecated old(): " + got);

            FxTestSupport.runOnFx(() -> c.openAndNavigate(org, 1));
            waitForDiagnostics(c, d -> d.stream().anyMatch(x -> x.message().contains("undefined_name")));
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            FxTestSupport.runOnFx(() -> registry.run("lsp.organizeImports"));
            String sorted = "import abc\nimport json\nimport sys\n\nprint(sys, json, abc, undefined_name)\n";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (text(c).equals(unsorted) && System.nanoTime() < deadline) {
                FxTestSupport.drainFx();
                Thread.sleep(100);
            }
            Thread.sleep(1000); // a second application, had there been one, has landed by now
            FxTestSupport.drainFx();
            assertEquals(sorted, text(c), "organized once");
            FxTestSupport.runOnFx(() -> c.activeBuffer().getArea().undo());
            assertEquals(unsorted, text(c), "one Undo takes it back");
        }
    }
}
