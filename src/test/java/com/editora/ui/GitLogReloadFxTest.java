package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Label;
import javafx.scene.control.ListView;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.git.GitLog;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An open Git Log follows the repository, and its palette commands never act on a selection nobody can see.
 *
 * <p>The log used to reload only after its own context-menu actions: a commit, pull, fetch or stash left it
 * showing yesterday's history. And {@code git.log.revert} / {@code cherryPick} / {@code checkout} /
 * {@code reset} read the panel's selection whether or not the window was on screen.
 */
@Tag("fx")
class GitLogReloadFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    static GitCoordinator applyRepo(FxWindowFixture fx, Path repo) throws Exception {
        GitCoordinator coordinator = FxTestSupport.field(fx.controller, "git");
        GitStatus status = new GitStatus(true, "main", null, 0, 0, List.of());
        FxTestSupport.runOnFx(() -> coordinator.applyState(new GitService.RepoState(repo, status, Map.of(), Map.of())));
        return coordinator;
    }

    static void open(MainController controller, Path file) throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                EditorBuffer buffer = new EditorBuffer();
                buffer.setPath(file);
                buffer.setContent(Files.readString(file));
                buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
                FxTestSupport.call(
                        controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    private static List<String> subjects(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            GitLogPanel panel = FxTestSupport.field(fx.controller, "gitLogPanel");
            List<GitLog.Entry> rows = FxTestSupport.field(panel, "allCommits");
            return rows.stream().map(GitLog.Entry::subject).toList();
        });
    }

    private static void awaitSubjects(FxWindowFixture fx, List<String> expected) throws Exception {
        for (int i = 0; i < 200 && !expected.equals(subjects(fx)); i++) {
            Thread.sleep(50);
        }
        assertEquals(expected, subjects(fx));
    }

    private static boolean logOpen(FxWindowFixture fx) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
            ToolWindow log = FxTestSupport.field(fx.controller, "gitLogToolWindow");
            return toolWindows.isOpen(log);
        });
    }

    @Test
    void anOpenLogReloadsAfterAGitMutation(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "one\n");
        repo.commitAll("first");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, file);
            GitCoordinator coordinator = applyRepo(fx, repo.root);
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            FxTestSupport.runOnFx(() -> {
                windows.showGitLog();
                windows.loadGitLog(null);
            });
            awaitSubjects(fx, List.of("first"));

            // History moves (a commit, a pull that fast-forwarded…) and the engine reports the mutation, as it
            // does at the end of every Git command it runs.
            repo.write("work.txt", "two\n");
            repo.commitAll("second");
            repo.git("tag", "v1");
            FxTestSupport.runOnFx(coordinator::afterMutation);

            awaitSubjects(fx, List.of("second", "first"));
            List<String> refs = FxTestSupport.callOnFx(() -> {
                GitLogPanel panel = FxTestSupport.field(fx.controller, "gitLogPanel");
                List<GitLog.Entry> rows = FxTestSupport.field(panel, "allCommits");
                return rows.get(0).refs().stream().map(GitLog.Ref::name).toList();
            });
            assertEquals(List.of("main", "v1"), refs, "the reloaded row carries its branch and tag");
        }
    }

    @Test
    void aLogCommandOpensTheHiddenLogInsteadOfRevertingItsRememberedSelection(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "one\n");
        repo.commitAll("first");
        repo.write("work.txt", "two\n");
        repo.commitAll("second");

        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            open(fx.controller, file);
            GitCoordinator coordinator = applyRepo(fx, repo.root);
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
            ToolWindow log = FxTestSupport.field(fx.controller, "gitLogToolWindow");
            GitLogPanel panel = FxTestSupport.field(fx.controller, "gitLogPanel");
            FxTestSupport.runOnFx(() -> {
                windows.showGitLog();
                windows.loadGitLog(null);
            });
            awaitSubjects(fx, List.of("second", "first"));

            // Select a commit, then close the window: the panel keeps the selection.
            FxTestSupport.runOnFx(() -> {
                ListView<GitLog.Entry> commits = FxTestSupport.field(panel, "commits");
                commits.getSelectionModel().select(0);
                toolWindows.close(log);
            });
            assertFalse(logOpen(fx));
            assertTrue(FxTestSupport.callOnFx(panel::selectedHash) != null, "the hidden panel still selects a commit");

            FxTestSupport.runOnFx(() -> registry.run("git.log.revert"));
            async.awaitWorker(FxTestSupport.field(coordinator.service(), "exec"));
            async.awaitFx();

            assertTrue(logOpen(fx), "the command shows the log it is about");
            String echo = FxTestSupport.callOnFx(() -> {
                StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
                Label label = FxTestSupport.field(statusBar, "echo");
                return label.getText();
            });
            assertEquals(tr("status.git.log.chooseCommit"), echo);
            assertEquals(
                    "2",
                    repo.git("rev-list", "--count", "HEAD").text().strip(),
                    "nothing was reverted: no commit was added");
        }
    }
}
