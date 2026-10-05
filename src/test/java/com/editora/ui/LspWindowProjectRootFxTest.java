package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.Project;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A window's language servers are rooted at, configured by and trusted through <em>that window's</em>
 * project.
 *
 * <p>{@code Ops.lspProjectRoot()} used to return the {@code ProjectManager}'s active project — the one most
 * recently opened in any window. Opening a second project therefore re-rooted the first window's next file
 * at a nested module marker (a second jdtls for the same project) and applied the other project's
 * {@code .editora/settings.json} to it, including a committed {@code lspEnabled: false}.
 */
@Tag("fx")
class LspWindowProjectRootFxTest {

    @TempDir
    Path work;

    @Test
    void openingAnotherProjectsWindowDoesNotChangeThisWindowsLspProject() throws Exception {
        FxTestSupport.bootToolkit();
        Path a = Files.createDirectories(work.resolve("projA"));
        Path b = Files.createDirectories(work.resolve("projB"));
        Files.createDirectories(b.resolve(".editora"));
        Files.writeString(b.resolve(".editora/settings.json"), "{\"lspEnabled\":{\"java\":false}}\n");

        FxWindowFixture fx = FxWindowFixture.create(Files.createTempDirectory(work, "cfg"), shared -> {});
        try {
            Project pa = fx.shared.projects().createOrGet("projA", a);
            Project pb = fx.shared.projects().createOrGet("projB", b);
            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(pa));
            FxTestSupport.drainFx();
            MainController windowA = controllerFor(fx, pa.id());
            LspCoordinator lsp = FxTestSupport.field(windowA, "lspCoordinator");
            LspCoordinator.Ops ops = FxTestSupport.field(lsp, "ops");
            assertEquals(a, FxTestSupport.callOnFx(ops::lspProjectRoot));

            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(pb)); // its own window; becomes "active"
            FxTestSupport.drainFx();

            assertEquals(a, FxTestSupport.callOnFx(ops::lspProjectRoot), "window A still edits project A");
            assertTrue(
                    FxTestSupport.callOnFx(() -> lsp.serverEnabled("java")),
                    "project B's committed lspEnabled=false must not reach window A");
        } finally {
            fx.dispose();
        }
    }

    private static MainController controllerFor(FxWindowFixture fx, String key) {
        List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
        for (Object h : holders) {
            if (key.equals(FxTestSupport.call(h, "key", new Class<?>[] {}))) {
                return (MainController) FxTestSupport.call(h, "controller", new Class<?>[] {});
            }
        }
        throw new IllegalStateException("no window for " + key);
    }
}
