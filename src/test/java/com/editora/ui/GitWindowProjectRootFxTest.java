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
 * A window's Git follows <em>that window's</em> project. With no tab open, the Git context is the project root,
 * and {@code WindowOps.projectRoot()} used to return the {@code ProjectManager}'s active project, the one most
 * recently opened or focused in any window. A project window left behind another project's window then said
 * "Not a Git repository" as soon as its last tab closed, and every Git command in it refused to run.
 */
@Tag("fx")
class GitWindowProjectRootFxTest {

    @TempDir
    Path work;

    @Test
    void anotherProjectsWindowDoesNotTakeAwayThisWindowsRepository() throws Exception {
        FxTestSupport.bootToolkit();
        GitTestRepo repo = GitTestRepo.init(Files.createDirectories(work.resolve("projA")));
        repo.write("a.txt", "a\n");
        repo.commitAll("first");
        Path b = Files.createDirectories(work.resolve("projB")); // not a repository

        FxWindowFixture fx = FxWindowFixture.create(Files.createTempDirectory(work, "cfg"), shared -> {});
        try {
            Project pa = fx.shared.projects().createOrGet("projA", repo.root);
            Project pb = fx.shared.projects().createOrGet("projB", b);
            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(pa));
            FxTestSupport.drainFx();
            MainController windowA = controllerFor(fx, pa.id());
            awaitRepoRoot(windowA, repo.root);

            FxTestSupport.runOnFx(() -> fx.windowManager.openOrFocus(pb)); // its own window; becomes "active"
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(windowA.git::refresh); // as closing window A's last tab does

            assertEquals(repo.root, FxTestSupport.callOnFx(windowA.git::contextPath), "window A is still in project A");
            awaitRepoRoot(windowA, repo.root);
        } finally {
            fx.dispose();
        }
    }

    /** The refresh runs git off the FX thread; an answer of "not a repository" sets the root to null. */
    private static void awaitRepoRoot(MainController window, Path expected) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        Path root = null;
        while (System.nanoTime() < deadline) {
            FxTestSupport.drainFx();
            root = FxTestSupport.callOnFx(window.git::repoRoot);
            if (expected.equals(root)) {
                return;
            }
            Thread.sleep(20);
        }
        assertEquals(expected, root);
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
