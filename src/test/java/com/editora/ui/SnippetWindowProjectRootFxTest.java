package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

import com.editora.config.Project;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A snippet's {@code $WORKSPACE_FOLDER} and {@code $WORKSPACE_NAME} are <em>its window's</em> project.
 * {@code SnippetCoordinator.Ops.projectRoot()} used to return the {@code ProjectManager}'s active project, the
 * one most recently opened or focused in any window, so a snippet expanded in a window left behind another
 * project's window named that other project.
 */
@Tag("fx")
class SnippetWindowProjectRootFxTest {

    @TempDir
    Path work;

    @Test
    void anotherProjectsWindowDoesNotBecomeThisWindowsWorkspace() throws Exception {
        FxTestSupport.bootToolkit();
        Path a = Files.createDirectories(work.resolve("projA"));
        Path b = Files.createDirectories(work.resolve("projB"));
        Path file = Files.writeString(a.resolve("notes.txt"), "text\n");

        FxWindowFixture fx = FxWindowFixture.create(Files.createTempDirectory(work, "cfg"), shared -> {});
        try {
            Project pa = fx.shared.projects().createOrGet("projA", a);
            Project pb = fx.shared.projects().createOrGet("projB", b);
            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(pa));
            FxTestSupport.drainFx();
            MainController windowA = controllerFor(fx, pa.id());
            FxTestSupport.runOnFx(() -> windowA.openAndNavigate(file, 0));
            EditorBuffer buffer = awaitBuffer(windowA, file);

            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(pb)); // its own window; becomes "active"
            FxTestSupport.drainFx();

            UnaryOperator<Path> workspace =
                    FxTestSupport.field(FxTestSupport.field(buffer, "snippetKeys"), "snippetWorkspaceRoot");
            assertEquals(
                    a, FxTestSupport.callOnFx(() -> workspace.apply(file)), "a snippet in window A is in project A");
        } finally {
            fx.dispose();
        }
    }

    private static EditorBuffer awaitBuffer(MainController window, Path file) throws Exception {
        return Await.value(
                () -> onFx(() -> {
                    EditorBuffer active = (EditorBuffer) FxTestSupport.call(window, "activeBuffer", new Class<?>[] {});
                    return active != null && file.equals(active.getPath()) && !active.isLoading() ? active : null;
                }),
                "did not open " + file);
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

    /** {@code probe}'s answer, read on the FX thread, for a condition {@link Await} checks. */
    private static <T> T onFx(java.util.concurrent.Callable<T> probe) {
        try {
            return FxTestSupport.callOnFx(probe);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
