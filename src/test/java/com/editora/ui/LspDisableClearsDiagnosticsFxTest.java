package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.editor.LspDiagnostic;
import com.editora.editor.LspDiagnostic.Severity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for #469: disabling a language server must clear its already-published diagnostics. The
 * later {@code syncBuffer} cleanup is guarded on {@code isManaged}, which is already false once the server is
 * shut down — so without an explicit clear, the Problems window strands that server's diagnostics forever
 * (no server remains to re-publish an empty list, and a tab close misses the same guard).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LspDisableClearsDiagnosticsFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        fx.shared.getSettings().setLspSupport(true);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void disablingAServerClearsItsPublishedDiagnostics() throws Exception {
        Path dir = Files.createTempDirectory("editora-lsp-disable");
        Path py = dir.resolve("app.py");
        Files.writeString(py, "print('hi')\n");

        Object lsp = FxTestSupport.field(fx.controller, "lspCoordinator");

        // Open the Python file and simulate the server publishing two diagnostics for it.
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class<?>[] {Path.class}, py));
        List<LspDiagnostic> diags = List.of(
                new LspDiagnostic(0, 0, 0, 5, Severity.ERROR, "boom", "E0", "pyright"),
                new LspDiagnostic(1, 0, 1, 5, Severity.WARNING, "meh", "W1", "pyright"));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller, "onLspDiagnostics", new Class<?>[] {Path.class, List.class}, py, diags));

        @SuppressWarnings("unchecked")
        Map<Path, List<LspDiagnostic>> before =
                (Map<Path, List<LspDiagnostic>>) FxTestSupport.call(lsp, "problems", new Class<?>[] {});
        assertEquals(1, before.size(), "the Python file's diagnostics are listed");

        // Disable the Python server (Settings → uncheck Python) and re-apply LSP support.
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setPythonLspEnabled(false);
            FxTestSupport.invoke(lsp, "applySupport");
        });

        @SuppressWarnings("unchecked")
        Map<Path, List<LspDiagnostic>> after =
                (Map<Path, List<LspDiagnostic>>) FxTestSupport.call(lsp, "problems", new Class<?>[] {});
        assertTrue(after.isEmpty(), "disabling the server must clear its diagnostics, still had: " + after.keySet());
    }
}
