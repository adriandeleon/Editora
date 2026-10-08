package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.ButtonBar;

import com.editora.git.GitPullMode;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repository-level Git commands in a real window: creating a repository in a folder, starting a branch,
 * checking out a remote branch, pushing for a caller that waits on the result, the pull-mode setting and
 * "Add to .gitignore". The only remote is a bare repository in the temp folder.
 */
@Tag("fx")
class GitRepositoryCommandsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static GitTestRepo repoWithCommit(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("file.txt", "base\n");
        repo.commitAll("base");
        return repo;
    }

    private static Path bareRemote(Path dir) throws Exception {
        Path bare = dir.toRealPath().resolve("origin.git");
        assertEquals(
                0,
                new ProcessBuilder("git", "init", "-q", "--bare", "-b", "main", bare.toString())
                        .inheritIO()
                        .start()
                        .waitFor());
        return bare;
    }

    private static String bareRev(Path bare, String ref) throws Exception {
        Process process = new ProcessBuilder("git", "--git-dir", bare.toString(), "rev-parse", "-q", "--verify", ref)
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return process.waitFor() == 0 ? out.strip() : "";
    }

    private static void awaitMessage(AsyncTestScope async, GitFeatureFx w, String message) throws Exception {
        OverlayTestKit.await(async, "\"" + message + "\"", () -> w.messages().contains(message));
    }

    @Test
    void initCreatesARepositoryInTheTypedFolderAndRefusesWhatIsNotOne(@TempDir Path temp) throws Exception {
        Path dir = temp.toRealPath();
        Path folder = Files.createDirectories(dir.resolve("fresh"));
        Path file = Files.writeString(folder.resolve("readme.txt"), "not under version control yet\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            FxTestSupport.runOnFx(() -> w.fx.controller.openAndNavigate(file, 0));
            OverlayTestKit.await(
                    async,
                    "the tab",
                    () -> w.active() != null && file.equals(w.active().getPath()));

            // The prompt starts from the active file's folder.
            FxTestSupport.runOnFx(w.git::initRepo);
            assertEquals(tr("dialog.gitInit.title"), FxTestSupport.callOnFx(() -> OverlayTestKit.formTitle(w.scene())));
            assertEquals(
                    folder.toString(),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formFields(w.scene()).get(0).getText()));

            // Blank: nothing happens. Not a folder: said so, and nothing is created.
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(w.scene(), "   "));
            async.awaitFx();
            assertFalse(Files.exists(folder.resolve(".git")));
            FxTestSupport.runOnFx(w.git::initRepo);
            Path missing = dir.resolve("no-such-folder");
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(w.scene(), missing.toString()));
            assertEquals(tr("status.gitInit.notAFolder", missing.toString()), w.status());
            assertFalse(Files.exists(missing));

            FxTestSupport.runOnFx(w.git::initRepo);
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(w.scene())); // the suggested folder as it stands
            awaitMessage(async, w, tr("status.gitInit.done", folder.toString()));
            assertTrue(Files.isDirectory(folder.resolve(".git")));
            OverlayTestKit.await(async, "the window to see the new repository", () -> folder.equals(w.git.repoRoot()));

            // A folder already inside a repository is not given a nested one.
            Path inner = Files.createDirectories(folder.resolve("inner"));
            FxTestSupport.runOnFx(w.git::initRepo);
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(w.scene(), inner.toString()));
            awaitMessage(async, w, tr("status.gitInit.alreadyRepo", folder.toString()));
            assertFalse(Files.exists(inner.resolve(".git")));
        }
    }

    @Test
    void aNewBranchIsNamedInAPromptAndAnUnusableNameIsRefused(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("branch", "taken");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(repo.root.resolve("file.txt"));

            FxTestSupport.runOnFx(w.git::newBranch);
            assertEquals(
                    tr("dialog.newBranch.title"), FxTestSupport.callOnFx(() -> OverlayTestKit.formTitle(w.scene())));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(w.scene(), "  feature/login  "));
            awaitMessage(async, w, tr("status.createdBranch", "feature/login"));
            assertEquals(
                    "feature/login", repo.git("branch", "--show-current").text().strip());

            // Blank: dropped. An option-like name: refused before git runs.
            FxTestSupport.runOnFx(w.git::newBranch);
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(w.scene(), "  "));
            FxTestSupport.runOnFx(w.git::newBranch);
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(w.scene(), "--orphan"));
            assertTrue(w.status().startsWith(tr("status.git.unsafeRef", "").stripTrailing()), w.status());
            async.awaitWorker(FxTestSupport.field(w.git.service(), "exec"));
            assertEquals(
                    "feature/login", repo.git("branch", "--show-current").text().strip());

            // A name git refuses is shown with git's reason.
            AtomicReference<OverlayTestKit.Shown> error = new AtomicReference<>();
            CountDownLatch shown = OverlayTestKit.answerDialog(
                    async,
                    pane -> tr("status.git.createBranchFailed", "taken").equals(pane.getHeaderText()),
                    ButtonBar.ButtonData.OK_DONE,
                    error);
            FxTestSupport.runOnFx(w.git::newBranch);
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(w.scene(), "taken"));
            async.await(shown, "the error dialog");
            assertTrue(error.get().content().contains("taken"), error.get().content());
        }
    }

    @Test
    void checkingOutARemoteBranchCreatesItsLocalTrackingBranch(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path remote = bareRemote(dir);
        repo.git("remote", "add", "origin", remote.toString());
        repo.git("push", "-q", "origin", "main", "main:topic");
        repo.git("fetch", "-q", "origin");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(repo.root.resolve("file.txt"));

            FxTestSupport.runOnFx(() -> {
                w.git.checkoutRemoteBranch(" "); // nothing named: nothing to do
                w.git.checkoutRemoteBranch(null);
                w.git.checkoutRemoteBranch("origin/topic");
            });
            awaitMessage(async, w, tr("status.checkedOut", "origin/topic"));
            assertEquals("topic", repo.git("branch", "--show-current").text().strip());
            assertEquals(
                    "origin/topic",
                    repo.git("for-each-ref", "--format=%(upstream:short)", "refs/heads/topic")
                            .text()
                            .strip());

            // Again: the local branch exists now, and git's refusal is shown.
            AtomicReference<OverlayTestKit.Shown> error = new AtomicReference<>();
            CountDownLatch shown = OverlayTestKit.answerDialog(
                    async,
                    pane -> tr("status.git.checkoutFailed", "origin/topic").equals(pane.getHeaderText()),
                    ButtonBar.ButtonData.OK_DONE,
                    error);
            FxTestSupport.runOnFx(() -> w.git.checkoutRemoteBranch("origin/topic"));
            async.await(shown, "the error dialog");
            assertFalse(error.get().content().isBlank());

            FxTestSupport.runOnFx(() -> w.git.checkoutRemoteBranch("--detach"));
            assertTrue(w.status().startsWith(tr("status.git.unsafeRef", "").stripTrailing()), w.status());
        }
    }

    @Test
    void pushingForACallerReportsTheOutcomeToIt(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path remote = bareRemote(dir);
        repo.git("remote", "add", "origin", remote.toString());
        String head = repo.git("rev-parse", "HEAD").text().strip();
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(repo.root.resolve("file.txt"));
            OverlayTestKit.await(
                    async, "the branch name", () -> !w.git.branchName().isBlank());
            assertTrue(FxTestSupport.callOnFx(w.git::currentBranchUnpushed), "main tracks nothing yet");

            CompletableFuture<ProcessRunner.Result> pushed = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> w.git.pushCurrentBranch(pushed::complete));
            assertTrue(async.await(pushed).ok());
            assertEquals(head, bareRev(remote, "refs/heads/main"));
            OverlayTestKit.await(async, "the upstream to be seen", () -> !w.git.currentBranchUnpushed());
            assertEquals(
                    "origin/main",
                    repo.git("for-each-ref", "--format=%(upstream:short)", "refs/heads/main")
                            .text()
                            .strip(),
                    "a first push sets the upstream");

            // A push the remote cannot take: the caller is told, with git's message.
            repo.git(
                    "remote",
                    "set-url",
                    "origin",
                    dir.toRealPath().resolve("gone.git").toString());
            repo.write("file.txt", "base\nmore\n");
            repo.commitAll("more");
            CompletableFuture<ProcessRunner.Result> failed = new CompletableFuture<>();
            FxTestSupport.runOnFx(() -> w.git.pushCurrentBranch(failed::complete));
            ProcessRunner.Result result = async.await(failed);
            assertFalse(result.ok());
            assertFalse(result.message().isBlank());
            assertEquals(head, bareRev(remote, "refs/heads/main"), "the remote is as it was");
        }
    }

    @Test
    void thePullModeCommandStoresTheSettingAndNamesIt(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(repo.root.resolve("file.txt"));

            for (GitPullMode mode : GitPullMode.values()) {
                FxTestSupport.runOnFx(() -> w.git.setPullMode(mode.id()));
                assertEquals(mode, FxTestSupport.callOnFx(w.git::pullMode));
                assertEquals(mode.id(), w.fx.shared.getSettings().getGitPullMode());
                assertEquals(
                        tr("status.settingChanged", tr("command.git.setPullMode"), GitCoordinator.pullModeLabel(mode)),
                        w.status());
            }
            assertEquals(tr("settings.git.pullMode.ffOnly"), GitCoordinator.pullModeLabel(GitPullMode.FF_ONLY));
            assertEquals(tr("settings.git.pullMode.rebase"), GitCoordinator.pullModeLabel(GitPullMode.REBASE));
            assertEquals(tr("settings.git.pullMode.merge"), GitCoordinator.pullModeLabel(GitPullMode.MERGE));
        }
    }

    @Test
    void addToGitignoreAppendsTheEntryOnceAndMarksAFolderAsOne(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path log = repo.write("build/out.log", "noise\n");
        Path ignore = repo.root.resolve(".gitignore");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitFeatureFx w = GitFeatureFx.create(async);
            w.open(repo.root.resolve("file.txt"));

            FxTestSupport.runOnFx(() -> w.git.addToGitignore(log));
            OverlayTestKit.await(async, "the .gitignore", () -> Files.exists(ignore));
            assertEquals("/build/out.log\n", Files.readString(ignore).replace("\r\n", "\n"));

            FxTestSupport.runOnFx(() -> w.git.addToGitignore(log));
            OverlayTestKit.await(
                    async,
                    "the already-ignored message",
                    () -> w.messages().stream()
                            .anyMatch(message -> message.equals(tr("status.git.alreadyIgnored", "/build/out.log"))));
            assertEquals("/build/out.log\n", Files.readString(ignore).replace("\r\n", "\n"), "not added a second time");

            FxTestSupport.runOnFx(() -> w.git.addToGitignore(log.getParent()));
            OverlayTestKit.await(
                    async, "the folder entry", () -> Files.readString(ignore).contains("/build/\n"));
            assertEquals(
                    "",
                    repo.git("status", "--porcelain", "--", "build").text(),
                    "git no longer lists anything under build/");
            assertNull(FxTestSupport.callOnFx(() -> OverlayTestKit.form(w.scene())));
        }
    }
}
