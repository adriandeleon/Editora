package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Label;

import com.editora.command.CommandRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git actions that existed only behind a button or a context menu are commands — so the palette, a key
 * binding and the VCS menu reach them — and a branch-dropdown action runs in the repository it was listed for.
 */
@Tag("fx")
class GitCommandReachabilityFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String echo(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
            Label label = FxTestSupport.field(statusBar, "echo");
            return label.getText();
        });
    }

    @Test
    void stageAllAndAddToGitignoreRunFromTheRegistry(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "one\n");
        repo.commitAll("first");
        repo.write("work.txt", "two\n");
        Path scratch = repo.write("scratch.log", "noise\n");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitLogReloadFxTest.open(fx.controller, file);
            GitLogReloadFxTest.open(fx.controller, scratch); // the active file
            GitCoordinator coordinator = GitLogReloadFxTest.applyRepo(fx, repo.root);
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            for (String id :
                    List.of("git.stageAll", "git.addToGitignore", "diff.vsBranch", "diff.vsTag", "diff.vsCommit")) {
                assertTrue(FxTestSupport.callOnFx(() -> registry.get(id).isPresent()), id + " is not a command");
            }

            FxTestSupport.runOnFx(() -> registry.run("git.addToGitignore"));
            assertEquals("/scratch.log\n", java.nio.file.Files.readString(repo.root.resolve(".gitignore")));

            FxTestSupport.runOnFx(() -> registry.run("git.stageAll"));
            List<String> staged = List.of();
            for (int i = 0; i < 200 && staged.size() < 2; i++) {
                Thread.sleep(50);
                staged = repo.git("diff", "--cached", "--name-only")
                        .text()
                        .lines()
                        .toList();
            }
            assertEquals(
                    List.of(".gitignore", "work.txt"),
                    staged,
                    "git add -A staged the change (and the new ignore file)");
            async.awaitWorker(FxTestSupport.field(coordinator.service(), "exec"));
            async.awaitFx();
        }
    }

    @Test
    void aBranchDropdownActionIsRefusedOnceAnotherRepositoryIsActive(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "one\n");
        repo.commitAll("first");
        Path other = java.nio.file.Files.createDirectory(dir.resolve("other"));

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitLogReloadFxTest.open(fx.controller, file);
            GitLogReloadFxTest.applyRepo(fx, repo.root);
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            boolean[] ran = new boolean[2];

            FxTestSupport.runOnFx(() -> {
                // What the dropdown wraps every action and checkout in, for the root it listed.
                Runnable listedHere = (Runnable) FxTestSupport.call(
                        windows, "inRoot", new Class<?>[] {Path.class, Runnable.class}, repo.root, (Runnable)
                                () -> ran[0] = true);
                Runnable listedElsewhere = (Runnable) FxTestSupport.call(
                        windows, "inRoot", new Class<?>[] {Path.class, Runnable.class}, other, (Runnable)
                                () -> ran[1] = true);
                listedHere.run();
                listedElsewhere.run();
            });

            assertTrue(ran[0], "the listed repository is still active: the action runs");
            assertFalse(ran[1], "a checkout or pull must not run in a repository the list never described");
            assertEquals(tr("status.git.repoChanged"), echo(fx));
        }
    }
}
