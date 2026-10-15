package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.Project;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The AI agent of a window works in <em>that window's</em> project: its session's working folder, which is also
 * the boundary of the files it may read and write. {@code AgentCoordinator.Ops.projectRoot()} used to return the
 * {@code ProjectManager}'s active project, the one most recently opened in any window (focusing a window does not
 * change it), so an agent started in a project's window after another project had been opened worked in the
 * other project.
 */
@Tag("fx")
class AgentWindowProjectRootFxTest {

    @TempDir
    Path work;

    @Test
    void anotherProjectsWindowDoesNotBecomeThisWindowsAgentFolder() throws Exception {
        FxTestSupport.bootToolkit();
        Path a = Files.createDirectories(work.resolve("projA"));
        Path b = Files.createDirectories(work.resolve("projB"));

        FxWindowFixture fx = FxWindowFixture.create(Files.createTempDirectory(work, "cfg"), shared -> {});
        try {
            Project pa = fx.shared.projects().createOrGet("projA", a);
            Project pb = fx.shared.projects().createOrGet("projB", b);
            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(pa));
            FxTestSupport.drainFx();
            MainController windowA = controllerFor(fx, pa.id());

            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(pb)); // its own window; becomes "active"
            FxTestSupport.drainFx();

            AgentCoordinator agent = windowA.agentCoordinator;
            assertEquals(
                    a,
                    FxTestSupport.callOnFx(() -> (Path) FxTestSupport.call(agent, "sessionCwd", new Class<?>[] {})),
                    "window A's agent works in project A");
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
