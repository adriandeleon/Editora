package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import javafx.scene.control.ButtonBar;

import com.editora.git.GitRemotes;
import com.editora.git.GitService;
import com.editora.git.GitWorktrees;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The branch, remote and work-tree commands as the user reaches them: through their pickers, prompts, forms
 * and manager cards, against a real repository whose only remotes are bare repositories in the temp folder.
 */
@Tag("fx")
class GitBranchCommandsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Window(GitFeatureFx w, GitBranchCoordinator branches, GitTestRepo repo, Path file) {}

    private static GitTestRepo repoWithCommit(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("file.txt", "base\n");
        repo.commitAll("base");
        return repo;
    }

    private static Path bareRemote(Path dir, String name) throws Exception {
        Path bare = dir.toRealPath().resolve(name + ".git");
        Process init = new ProcessBuilder("git", "init", "-q", "--bare", "-b", "main", bare.toString())
                .inheritIO()
                .start();
        assertEquals(0, init.waitFor());
        return bare;
    }

    private static String bareRev(Path bare, String ref) throws Exception {
        Process process = new ProcessBuilder("git", "--git-dir", bare.toString(), "rev-parse", "-q", "--verify", ref)
                .redirectErrorStream(true)
                .start();
        String out = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return process.waitFor() == 0 ? out.strip() : "";
    }

    private static Window window(AsyncTestScope async, GitTestRepo repo) throws Exception {
        GitFeatureFx w = GitFeatureFx.create(async);
        Path file = repo.root.resolve("file.txt");
        w.open(file);
        OverlayTestKit.await(async, "the branch name", () -> !w.git.branchName().isBlank());
        GitWindowCoordinator windows = FxTestSupport.field(w.fx.controller, "gitWindows");
        return new Window(w, windows.branches, repo, file);
    }

    private static boolean hasBranch(GitTestRepo repo, String name) throws Exception {
        return repo.tryGit("rev-parse", "-q", "--verify", "refs/heads/" + name).exit() == 0;
    }

    private static String currentBranch(GitTestRepo repo) throws Exception {
        return repo.git("branch", "--show-current").text().strip();
    }

    private static void awaitMessage(AsyncTestScope async, Window win, String message) throws Exception {
        OverlayTestKit.await(
                async, "\"" + message + "\"", () -> win.w().messages().contains(message));
    }

    private static List<?> awaitPicker(AsyncTestScope async, Window win) throws Exception {
        OverlayTestKit.await(
                async,
                "a picker",
                () -> !OverlayTestKit.pickerItems(win.w().scene()).isEmpty());
        return FxTestSupport.callOnFx(() -> OverlayTestKit.pickerItems(win.w().scene()));
    }

    private static void pick(Window win, String query) throws Exception {
        assertTrue(
                FxTestSupport.callOnFx(() -> OverlayTestKit.pick(win.w().scene(), query)),
                "the picker has a row for " + query);
    }

    private static GitBranchCoordinator.Ref local(String name) {
        return new GitBranchCoordinator.Ref(name, GitBranchCoordinator.RefKind.LOCAL);
    }

    // --- pickers -----------------------------------------------------------------------------------

    @Test
    void deleteOffersTheOtherLocalBranchesAndSaysWhenThereAreNone(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("branch", "topic");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);

            FxTestSupport.runOnFx(win.branches()::deleteBranch);
            assertEquals(List.of(local("topic")), awaitPicker(async, win), "never the checked-out branch");
            pick(win, "topic");
            awaitMessage(async, win, tr("status.git.deletedBranch", "topic"));
            assertFalse(hasBranch(repo, "topic"));

            FxTestSupport.runOnFx(win.branches()::deleteBranch);
            awaitMessage(async, win, tr("status.git.noRefsToPick"));
            assertNull(
                    FxTestSupport.callOnFx(() -> OverlayTestKit.picker(win.w().scene())));
        }
    }

    @Test
    void renamePicksABranchThenAsksItsNewNameStartingFromTheOldOne(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("branch", "topic");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);

            FxTestSupport.runOnFx(win.branches()::renameBranch);
            assertEquals(List.of(local("main"), local("topic")), awaitPicker(async, win));
            pick(win, "topic");
            assertEquals(
                    tr("dialog.renameBranch.title"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            assertEquals(
                    "topic",
                    FxTestSupport.callOnFx(() ->
                            OverlayTestKit.formFields(win.w().scene()).get(0).getText()));
            assertTrue(FxTestSupport.callOnFx(
                    () -> OverlayTestKit.submitForm(win.w().scene(), "  renamed  ")));
            awaitMessage(async, win, tr("status.git.renamedBranch", "topic", "renamed"));
            assertTrue(hasBranch(repo, "renamed"), "the typed name is trimmed");
            assertFalse(hasBranch(repo, "topic"));
        }
    }

    @Test
    void mergeAndRebaseOfferLocalAndRemoteBranchesAndSayWhenAlreadyUpToDate(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path remote = bareRemote(dir, "origin");
        repo.git("remote", "add", "origin", remote.toString());
        repo.git("push", "-q", "origin", "main");
        repo.git("fetch", "-q", "origin");
        repo.git("checkout", "-q", "-b", "feat");
        repo.write("feat.txt", "feat\n");
        repo.commitAll("feat");
        repo.git("checkout", "-q", "main");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);

            FxTestSupport.runOnFx(win.branches()::mergeBranch);
            assertEquals(
                    List.of(
                            local("feat"),
                            new GitBranchCoordinator.Ref("origin/main", GitBranchCoordinator.RefKind.REMOTE)),
                    awaitPicker(async, win));
            pick(win, "feat");
            awaitMessage(async, win, tr("status.git.merged", "feat", "main"));
            assertTrue(Files.exists(repo.root.resolve("feat.txt")));

            FxTestSupport.runOnFx(() -> win.branches().merge(repo.root, "main", "feat"));
            awaitMessage(async, win, tr("status.git.alreadyUpToDate", "feat"));

            // main now holds feat: rebasing main onto feat has nothing to replay.
            FxTestSupport.runOnFx(win.branches()::rebaseOnto);
            awaitPicker(async, win);
            pick(win, "feat");
            awaitMessage(async, win, tr("status.git.alreadyUpToDate", "feat"));

            // A branch with a commit main lacks: main's own commits are replayed on top of it.
            repo.git("branch", "onto", "feat");
            repo.git("checkout", "-q", "onto");
            repo.write("onto.txt", "onto\n");
            repo.commitAll("onto");
            repo.git("checkout", "-q", "main");
            FxTestSupport.runOnFx(() -> win.branches().rebase(repo.root, "main", "onto"));
            awaitMessage(async, win, tr("status.git.rebased", "main", "onto"));
            assertTrue(Files.exists(repo.root.resolve("onto.txt")));
        }
    }

    @Test
    void aNewBranchCanStartFromATagWithoutSwitchingToIt(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("tag", "v1");
        repo.write("file.txt", "later\n");
        repo.commitAll("later");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);

            FxTestSupport.runOnFx(win.branches()::newBranchFrom);
            assertEquals(
                    List.of(local("main"), new GitBranchCoordinator.Ref("v1", GitBranchCoordinator.RefKind.TAG)),
                    awaitPicker(async, win),
                    "tags are offered as starting points too");
            pick(win, "v1");
            assertEquals(
                    tr("dialog.newBranchFrom.title"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            FxTestSupport.runOnFx(() -> {
                javafx.scene.control.CheckBox switchTo =
                        OverlayTestKit.formChecks(win.w().scene()).get(0);
                assertTrue(switchTo.isSelected(), "switching is the default");
                switchTo.setSelected(false);
                OverlayTestKit.submitForm(win.w().scene(), "from-tag");
            });
            awaitMessage(async, win, tr("status.git.createdBranchFrom", "from-tag", "v1"));
            assertEquals(
                    repo.git("rev-parse", "v1").text().strip(),
                    repo.git("rev-parse", "from-tag").text().strip());
            assertEquals("main", currentBranch(repo), "the checked-out branch is unchanged");
        }
    }

    @Test
    void checkingOutATagDetachesHeadAndTheBranchOnlyCommandsThenRefuse(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("tag", "v1");
        Path remote = bareRemote(dir, "origin");
        repo.git("remote", "add", "origin", remote.toString());
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);

            FxTestSupport.runOnFx(win.branches()::checkoutRevision);
            assertEquals(
                    tr("dialog.checkoutRevision.title"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), " v1 "));
            awaitMessage(async, win, tr("status.checkedOut", "v1"));
            assertEquals("", currentBranch(repo), "HEAD is detached");
            OverlayTestKit.await(
                    async,
                    "the detached state to reach the window",
                    () -> GitService.isDetached(win.w().git.branchName()));

            for (Runnable command : List.<Runnable>of(
                    win.branches()::setUpstream,
                    win.branches()::unsetUpstream,
                    win.branches()::pushForce,
                    win.branches()::pushTo)) {
                win.w().clearStatus();
                FxTestSupport.runOnFx(command);
                assertEquals(tr("status.git.detachedNoBranch"), win.w().status());
            }
            assertEquals("", bareRev(remote, "refs/heads/main"), "nothing was pushed");
        }
    }

    @Test
    void upstreamIsPickedFromTheRemoteBranchesAndUnsetAgain(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path remote = bareRemote(dir, "origin");
        repo.git("remote", "add", "origin", remote.toString());
        repo.git("push", "-q", "origin", "main");
        repo.git("fetch", "-q", "origin");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);

            FxTestSupport.runOnFx(win.branches()::setUpstream);
            assertEquals(
                    List.of(new GitBranchCoordinator.Ref("origin/main", GitBranchCoordinator.RefKind.REMOTE)),
                    awaitPicker(async, win),
                    "only a remote branch can be tracked");
            pick(win, "origin/main");
            awaitMessage(async, win, tr("status.git.upstreamSet", "main", "origin/main"));
            assertEquals(
                    "origin/main",
                    repo.git("for-each-ref", "--format=%(upstream:short)", "refs/heads/main")
                            .text()
                            .strip());

            FxTestSupport.runOnFx(win.branches()::unsetUpstream);
            awaitMessage(async, win, tr("status.git.upstreamUnset", "main"));
            assertEquals(
                    "",
                    repo.git("for-each-ref", "--format=%(upstream:short)", "refs/heads/main")
                            .text()
                            .strip());
        }
    }

    @Test
    void compareWithCurrentOpensTheReviewOfThePickedBranch(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("checkout", "-q", "-b", "feat");
        repo.write("feat.txt", "feat\n");
        repo.commitAll("feat");
        repo.git("checkout", "-q", "main");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);

            FxTestSupport.runOnFx(win.branches()::compareWithCurrent);
            assertEquals(List.of(local("feat")), awaitPicker(async, win));
            pick(win, "feat");
            OverlayTestKit.await(
                    async,
                    "the review tab",
                    () -> win.w().area.selectedTab().getUserData() instanceof DirectoryReviewPane);
            DirectoryReviewPane review = (DirectoryReviewPane) win.w().activeContent();
            assertEquals(tr("diff.title.refVsRef", "feat", "main"), review.title());
        }
    }

    // --- remotes -----------------------------------------------------------------------------------

    @Test
    void withoutARemoteThePushAndFetchCommandsSaySo(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);
            for (Runnable command :
                    List.<Runnable>of(win.branches()::fetchRemote, win.branches()::pushTags, win.branches()::pushTo)) {
                win.w().clearStatus();
                FxTestSupport.runOnFx(command);
                OverlayTestKit.await(
                        async,
                        "the no-remotes message",
                        () -> tr("status.git.noRemotes").equals(win.w().status()));
            }
        }
    }

    @Test
    void theOnlyRemoteIsUsedDirectlyAndOneOfSeveralIsPicked(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("tag", "v1");
        Path origin = bareRemote(dir, "origin");
        repo.git("remote", "add", "origin", origin.toString());
        String head = repo.git("rev-parse", "HEAD").text().strip();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);

            // One remote: straight to the name prompt, which starts from the local branch's name.
            FxTestSupport.runOnFx(win.branches()::pushTo);
            OverlayTestKit.await(
                    async,
                    "the push-to prompt",
                    () -> tr("dialog.pushTo.title", "origin")
                            .equals(OverlayTestKit.formTitle(win.w().scene())));
            assertEquals(
                    "main",
                    FxTestSupport.callOnFx(() ->
                            OverlayTestKit.formFields(win.w().scene()).get(0).getText()));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "published"));
            awaitMessage(async, win, tr("status.git.pushedTo", "main", "origin/published"));
            assertEquals(head, bareRev(origin, "refs/heads/published"));

            FxTestSupport.runOnFx(win.branches()::pushTags);
            awaitMessage(async, win, tr("status.git.pushedTags", "origin"));
            assertEquals(head, bareRev(origin, "refs/tags/v1"));

            // A second remote: now the command asks which.
            Path backup = bareRemote(dir, "backup");
            repo.git("remote", "add", "backup", backup.toString());
            FxTestSupport.runOnFx(win.branches()::fetchRemote);
            List<?> remotes = awaitPicker(async, win);
            assertEquals(
                    List.of("backup", "origin"),
                    remotes.stream()
                            .map(remote -> ((GitRemotes.Remote) remote).name())
                            .sorted()
                            .toList());
            pick(win, "backup");
            awaitMessage(async, win, tr("status.gitDone", tr("gitlabel.fetchRemote", "backup")));

            // An empty answer to the push-to prompt means "the same name".
            FxTestSupport.runOnFx(() -> win.branches().pushTo(repo.root, "main", "backup", "", true));
            awaitMessage(async, win, tr("status.git.pushedTo", "main", "backup/main"));
            assertEquals(head, bareRev(backup, "refs/heads/main"));
        }
    }

    @Test
    void theRemotesManagerAddsRenamesRepointsFetchesPrunesAndRemoves(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path first = bareRemote(dir, "first");
        Path second = bareRemote(dir, "second");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);
            GitBranchCoordinator branches = win.branches();

            FxTestSupport.runOnFx(branches::manageRemotes);
            OverlayTestKit.await(async, "the manager", () -> branches.remotesManager != null);
            assertEquals(List.of(), FxTestSupport.callOnFx(() -> branches.remotesManager.items()));

            // Add: a two-field form, the name pre-filled with "origin".
            FxTestSupport.runOnFx(() -> branches.remotesManager.press(tr("remotes.add"), null));
            assertEquals(
                    tr("remotes.add"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            assertEquals(
                    "origin",
                    FxTestSupport.callOnFx(() ->
                            OverlayTestKit.formFields(win.w().scene()).get(0).getText()));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), null, first.toString()));
            awaitMessage(async, win, tr("status.git.remoteAdded", "origin"));
            GitRemotes.Remote origin = awaitRemote(async, branches, "origin");
            assertEquals(first.toString(), origin.fetchUrl());

            // Rename: a prompt starting from the old name; the manager comes back with the new one.
            FxTestSupport.runOnFx(() -> branches.remotesManager.press(tr("remotes.rename"), origin));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "upstream"));
            awaitMessage(async, win, tr("status.git.remoteRenamed", "origin", "upstream"));
            GitRemotes.Remote upstream = awaitRemote(async, branches, "upstream");

            // Set URL: accepting the shown URL unchanged changes nothing; a new one is stored.
            FxTestSupport.runOnFx(() -> branches.remotesManager.press(tr("remotes.setUrl"), upstream));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene()));
            awaitMessage(async, win, tr("status.git.remoteUnchanged", "upstream"));
            GitRemotes.Remote unchanged = awaitRemote(async, branches, "upstream");
            assertEquals(first.toString(), unchanged.fetchUrl());
            FxTestSupport.runOnFx(() -> branches.remotesManager.press(tr("remotes.setUrl"), unchanged));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), second.toString()));
            awaitMessage(async, win, tr("status.git.remoteUrlSet", "upstream"));
            OverlayTestKit.await(
                    async,
                    "the new URL in the manager",
                    () -> branches.remotesManager.items().stream()
                            .anyMatch(remote -> second.toString().equals(remote.fetchUrl())));
            GitRemotes.Remote repointed =
                    FxTestSupport.callOnFx(() -> branches.remotesManager.items().get(0));

            FxTestSupport.runOnFx(() -> branches.remotesManager.press(tr("remotes.fetch"), repointed));
            awaitMessage(async, win, tr("status.gitDone", tr("gitlabel.fetchRemote", "upstream")));
            FxTestSupport.runOnFx(() -> branches.remotesManager.press(tr("remotes.prune"), repointed));
            awaitMessage(async, win, tr("status.gitDone", tr("gitlabel.pruneRemote", "upstream")));

            // Remove asks first; declined, the remote stays and the manager is back.
            AtomicReference<OverlayTestKit.Shown> question = new AtomicReference<>();
            CountDownLatch declined =
                    OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, question);
            FxTestSupport.runOnFx(() -> branches.remotesManager.press(tr("remotes.remove"), repointed));
            async.await(declined, "the remove question");
            assertEquals(
                    tr("dialog.remoteRemove.confirm", "upstream"),
                    question.get().content());
            assertEquals("upstream", repo.git("remote").text().strip());

            CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
            OverlayTestKit.await(
                    async,
                    "the manager again",
                    () -> OverlayTestKit.form(win.w().scene()) != null);
            FxTestSupport.runOnFx(() -> branches.remotesManager.press(tr("remotes.remove"), repointed));
            async.await(agreed, "the remove question, accepted");
            awaitMessage(async, win, tr("status.git.remoteRemoved", "upstream"));
            assertEquals("", repo.git("remote").text().strip());
        }
    }

    private static GitRemotes.Remote awaitRemote(AsyncTestScope async, GitBranchCoordinator branches, String name)
            throws Exception {
        OverlayTestKit.await(
                async,
                "remote " + name + " in the manager",
                () -> branches.remotesManager.items().stream().anyMatch(remote -> name.equals(remote.name())));
        return FxTestSupport.callOnFx(() -> branches.remotesManager.items().stream()
                .filter(remote -> name.equals(remote.name()))
                .findFirst()
                .orElseThrow());
    }

    @Test
    void invalidRemoteNamesUrlsAndRefsAreRefusedBeforeGitRuns(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);
            GitBranchCoordinator branches = win.branches();
            Path root = repo.root;
            AtomicInteger after = new AtomicInteger();
            record Refusal(String expected, Runnable action) {}
            List<Refusal> refusals = List.of(
                    new Refusal(
                            tr("status.git.invalidRemoteName", "-x"),
                            () -> branches.addRemote(
                                    root, "-x", "https://example.invalid/a.git", after::incrementAndGet)),
                    new Refusal(
                            tr("status.git.invalidRemoteUrl"),
                            () -> branches.addRemote(root, "origin", "", after::incrementAndGet)),
                    new Refusal(
                            tr("status.git.invalidRemoteName", "bad name"),
                            () -> branches.renameRemote(root, "origin", "bad name", after::incrementAndGet)),
                    new Refusal(
                            tr("status.git.invalidRemoteUrl"),
                            () -> branches.setRemoteUrl(root, "origin", "  ", after::incrementAndGet)),
                    new Refusal(
                            tr("status.git.unsafeRef", "--force"),
                            () -> branches.removeRemote(root, "--force", after::incrementAndGet)),
                    new Refusal(
                            tr("status.git.unsafeRef", "--all"),
                            () -> branches.pruneRemote(root, "--all", after::incrementAndGet)),
                    new Refusal(
                            tr("status.git.unsafeRef", "--all"),
                            () -> branches.fetchRemote(root, "--all", after::incrementAndGet)),
                    new Refusal(tr("status.git.unsafeRef", "--mirror"), () -> branches.pushTags(root, "--mirror")),
                    new Refusal(
                            tr("status.git.invalidBranchName", "bad..name"),
                            () -> branches.pushTo(root, "main", "origin", "bad..name", false)),
                    new Refusal(
                            tr("status.git.unsafeRef", "no-remote-here"),
                            () -> branches.deleteRemoteBranch(root, "no-remote-here", List.of("origin"))),
                    new Refusal(tr("status.git.unsafeRef", "-D"), () -> branches.deleteBranch(root, "main", "-D")),
                    new Refusal(tr("status.git.unsafeRef", "--abort"), () -> branches.merge(root, "main", "--abort")),
                    new Refusal(tr("status.git.unsafeRef", "--abort"), () -> branches.rebase(root, "main", "--abort")),
                    new Refusal(tr("status.git.unsafeRef", "-b"), () -> branches.setUpstream(root, "main", "-b")),
                    new Refusal(tr("status.git.unsafeRef", "-b"), () -> branches.unsetUpstream(root, "-b")),
                    new Refusal(tr("status.git.unsafeRef", "-b"), () -> branches.compare(root, "main", "-b")),
                    new Refusal(
                            tr("status.git.worktreeBadPath", "  "),
                            () -> branches.addWorktree(root, "  ", "topic", true, after::incrementAndGet)),
                    new Refusal(
                            tr("status.git.invalidBranchName", "a b"),
                            () -> branches.addWorktree(root, "../wt", "a b", true, after::incrementAndGet)),
                    new Refusal(
                            tr("status.git.unsafeRef", "--detach"),
                            () -> branches.addWorktree(root, "../wt", "--detach", false, after::incrementAndGet)));
            for (Refusal refusal : refusals) {
                win.w().clearStatus();
                FxTestSupport.runOnFx(refusal.action());
                assertEquals(refusal.expected().stripTrailing(), win.w().status());
            }
            async.awaitWorker(FxTestSupport.field(win.w().git.service(), "exec"));
            async.awaitFx();
            assertEquals(0, after.get(), "a refused command never reports completion");
            assertEquals("", repo.git("remote").text(), "no remote was configured");
            assertFalse(Files.exists(dir.toRealPath().resolve("wt")), "no work tree was created");

            // Unchanged names are a no-op, not an error.
            win.w().clearStatus();
            FxTestSupport.runOnFx(() -> {
                branches.renameRemote(root, "origin", "origin", after::incrementAndGet);
                branches.renameBranch(root, "main", "main");
                branches.renameBranch(root, "main", "");
                branches.newBranchFrom(root, "main", "", true);
                branches.checkoutRevision(root, "");
            });
            assertEquals("", win.w().status());
        }
    }

    // --- failures ----------------------------------------------------------------------------------

    /** What git refuses is shown in an error dialog headed by what was attempted, with git's own words. */
    @Test
    void aCommandGitRefusesShowsItsReasonUnderWhatWasAttempted(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        repo.git("branch", "topic");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);
            GitBranchCoordinator branches = win.branches();
            Path root = repo.root;
            Path occupied = Files.writeString(dir.toRealPath().resolve("occupied"), "a file");
            record Failure(String header, Runnable action) {}
            List<Failure> failures = List.of(
                    new Failure(
                            tr("status.git.deleteBranchFailed", "ghost"),
                            () -> branches.deleteBranch(root, "main", "ghost")),
                    new Failure(
                            tr("status.git.renameBranchFailed", "ghost"),
                            () -> branches.renameBranch(root, "ghost", "spirit")),
                    new Failure(tr("status.git.mergeFailed", "ghost"), () -> branches.merge(root, "main", "ghost")),
                    new Failure(tr("status.git.rebaseFailed", "ghost"), () -> branches.rebase(root, "main", "ghost")),
                    new Failure(
                            tr("status.git.createBranchFailed", "topic"),
                            () -> branches.newBranchFrom(root, "main", "topic", false)),
                    new Failure(
                            tr("status.git.createBranchFailed", "topic"),
                            () -> branches.newBranchFrom(root, "main", "topic", true)),
                    new Failure(
                            tr("status.git.checkoutFailed", "ghost"), () -> branches.checkoutRevision(root, "ghost")),
                    new Failure(
                            tr("status.git.upstreamFailed", "main"),
                            () -> branches.setUpstream(root, "main", "origin/ghost")),
                    new Failure(tr("status.git.upstreamFailed", "main"), () -> branches.unsetUpstream(root, "main")),
                    new Failure(
                            tr("status.git.syncFailed", tr("gitlabel.push")),
                            () -> branches.pushTo(root, "main", "nowhere", "main", false)),
                    new Failure(
                            tr("status.git.syncFailed", tr("gitlabel.pushTags")),
                            () -> branches.pushTags(root, "nowhere")),
                    new Failure(
                            tr("status.git.syncFailed", tr("gitlabel.pushForce")),
                            () -> branches.forcePush(root, "main", "nowhere/main")),
                    new Failure(
                            tr("status.git.syncFailed", tr("gitlabel.fetchRemote", "nowhere")),
                            () -> branches.fetchRemote(root, "nowhere", () -> {})),
                    new Failure(
                            tr("status.git.syncFailed", tr("gitlabel.pruneRemote", "nowhere")),
                            () -> branches.pruneRemote(root, "nowhere", () -> {})),
                    new Failure(
                            tr("status.git.remoteFailed"),
                            () -> branches.renameRemote(root, "nowhere", "other", () -> {})),
                    new Failure(
                            tr("status.git.worktreeFailed"),
                            () -> branches.addWorktree(root, occupied.toString(), "topic", false, () -> {})));
            for (Failure failure : failures) {
                AtomicReference<OverlayTestKit.Shown> error = new AtomicReference<>();
                CountDownLatch shown = OverlayTestKit.answerDialog(
                        async,
                        pane -> failure.header().equals(pane.getHeaderText()),
                        ButtonBar.ButtonData.OK_DONE,
                        error);
                FxTestSupport.runOnFx(failure.action());
                async.await(shown, "the error dialog \"" + failure.header() + "\"");
                assertFalse(error.get().content().isBlank(), failure.header() + ": git's reason is shown");
                // The next command starts only after this one has reported and refreshed.
                async.awaitWorker(FxTestSupport.field(win.w().git.service(), "exec"));
                async.awaitFx();
            }
            assertEquals("main", currentBranch(repo));
            assertTrue(hasBranch(repo, "topic"));
            assertEquals("a file", Files.readString(occupied));
        }
    }

    // --- work trees --------------------------------------------------------------------------------

    @Test
    void theWorktreesManagerAddsThroughItsFormAndNeverRemovesTheMainOrTheCurrentTree(@TempDir Path dir)
            throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);
            GitBranchCoordinator branches = win.branches();

            FxTestSupport.runOnFx(branches::manageWorktrees);
            OverlayTestKit.await(async, "the manager", () -> branches.worktreesManager != null);
            List<GitWorktrees.Worktree> rows = FxTestSupport.callOnFx(() -> branches.worktreesManager.items());
            assertEquals(1, rows.size());
            GitWorktrees.Worktree main = rows.get(0);
            assertEquals("main · " + tr("worktrees.main"), GitBranchCoordinator.worktreeDetail(main));

            FxTestSupport.runOnFx(() -> branches.worktreesManager.press(tr("worktrees.add"), null));
            assertEquals(
                    tr("worktrees.add"),
                    FxTestSupport.callOnFx(
                            () -> OverlayTestKit.formTitle(win.w().scene())));
            assertEquals(
                    repo.root + "-",
                    FxTestSupport.callOnFx(() ->
                            OverlayTestKit.formFields(win.w().scene()).get(0).getText()),
                    "the path starts beside the repository");
            Path target = dir.toRealPath().resolve("side");
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "side", "side-branch"));
            awaitMessage(async, win, tr("status.git.worktreeAdded", target));
            assertTrue(Files.isDirectory(target), "a relative path lands beside the repository, not inside it");
            assertTrue(hasBranch(repo, "side-branch"));
            OverlayTestKit.await(
                    async,
                    "the new tree in the manager",
                    () -> branches.worktreesManager.items().size() == 2);
            GitWorktrees.Worktree side = FxTestSupport.callOnFx(() -> branches.worktreesManager.items().stream()
                    .filter(tree -> !tree.main())
                    .findFirst()
                    .orElseThrow());
            assertEquals("side-branch", GitBranchCoordinator.worktreeDetail(side));

            // The main tree is never removed, and neither is the one this window is in.
            win.w().clearStatus();
            FxTestSupport.runOnFx(() -> branches.removeWorktree(repo.root, main, () -> {}));
            assertEquals(tr("status.git.worktreeMain"), win.w().status());
            GitWorktrees.Worktree here =
                    new GitWorktrees.Worktree(repo.root.toString(), "", "main", false, false, false, false, false);
            FxTestSupport.runOnFx(() -> branches.removeWorktree(repo.root, here, () -> {}));
            assertEquals(tr("status.git.worktreeCurrent"), win.w().status());

            // Declining the confirmation keeps the tree and brings the manager back.
            AtomicInteger reopened = new AtomicInteger();
            CountDownLatch declined = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, null);
            FxTestSupport.runOnFx(() -> branches.removeWorktree(repo.root, side, reopened::incrementAndGet));
            async.await(declined, "the remove question");
            async.awaitFx();
            assertEquals(1, reopened.get());
            assertTrue(Files.isDirectory(target));

            // Prune from the manager: nothing is stale, and it says it ran.
            FxTestSupport.runOnFx(() -> branches.worktreesManager.press(tr("worktrees.prune"), null));
            awaitMessage(async, win, tr("status.git.worktreesPruned"));
        }
    }

    @Test
    void openingAWorktreeHandsItsFolderToTheWindowOpenerAndRejectsTextThatIsNoPath(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);
            GitBranchCoordinator branches = win.branches();
            List<Path> opened = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                branches.setWindowOpener(opened::add);
                branches.openWorktree(repo.root.toString());
                branches.openWorktree("bad\u0000path");
            });
            assertEquals(List.of(repo.root), opened);
            assertEquals(
                    tr("status.git.worktreeBadPath", "bad\u0000path"), win.w().status());

            FxTestSupport.runOnFx(() -> {
                branches.setWindowOpener(null); // no opener installed: opening does nothing, and does not fail
                branches.openWorktree(repo.root.toString());
            });
            assertEquals(1, opened.size());
        }
    }

    // --- branch dropdown rows ----------------------------------------------------------------------

    @Test
    void aBranchRowsMenuRunsEachActionOnThatBranchThroughTheGuard(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path remote = bareRemote(dir, "origin");
        repo.git("remote", "add", "origin", remote.toString());
        repo.git("push", "-q", "origin", "main");
        repo.git("fetch", "-q", "origin");
        repo.git("checkout", "-q", "-b", "feat");
        repo.write("feat.txt", "feat\n");
        repo.commitAll("feat");
        repo.git("checkout", "-q", "main");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);
            GitBranchCoordinator branches = win.branches();
            AtomicInteger guarded = new AtomicInteger();
            UnaryOperator<Runnable> guard = run -> () -> {
                guarded.incrementAndGet();
                run.run();
            };
            GitService.Branches listed = listBranches(async, win);
            BranchPopup.BranchRef feat = new BranchPopup.BranchRef("feat", false, false, "origin/main", false);

            List<BranchPopup.RowAction> actions =
                    FxTestSupport.callOnFx(() -> branches.rowActions(repo.root, "main", listed, feat, guard));
            List<String> labels = actions.stream()
                    .filter(action -> action != BranchPopup.RowAction.SEPARATOR)
                    .map(BranchPopup.RowAction::label)
                    .toList();
            assertEquals(
                    List.of(
                            tr("branch.row.newFrom", "feat", "main"),
                            tr("branch.row.compare", "feat", "main"),
                            tr("branch.row.merge", "feat", "main"),
                            tr("branch.row.rebase", "feat", "main"),
                            tr("branch.row.rename", "feat", "main"),
                            tr("branch.row.setUpstream", "feat", "main"),
                            tr("branch.row.unsetUpstream", "feat", "main"),
                            tr("branch.row.delete", "feat", "main")),
                    labels);
            assertEquals(
                    List.of(tr("branch.row.delete", "feat", "main")),
                    actions.stream()
                            .filter(BranchPopup.RowAction::danger)
                            .map(BranchPopup.RowAction::label)
                            .toList(),
                    "only Delete is marked as destructive");

            // Compare reviews the row's branch against the checked-out one.
            FxTestSupport.runOnFx(
                    action(actions, tr("branch.row.compare", "feat", "main")).run());
            OverlayTestKit.await(
                    async,
                    "the review tab",
                    () -> win.w().area.selectedTab().getUserData() instanceof DirectoryReviewPane);
            assertEquals(
                    tr("diff.title.refVsRef", "feat", "main"),
                    ((DirectoryReviewPane) win.w().activeContent()).title());
            win.w().open(win.file());

            // Set upstream opens the remote-branch picker for the row's branch — not the checked-out one.
            FxTestSupport.runOnFx(action(actions, tr("branch.row.setUpstream", "feat", "main"))
                    .run());
            awaitPicker(async, win);
            pick(win, "origin/main");
            awaitMessage(async, win, tr("status.git.upstreamSet", "feat", "origin/main"));
            FxTestSupport.runOnFx(action(actions, tr("branch.row.unsetUpstream", "feat", "main"))
                    .run());
            awaitMessage(async, win, tr("status.git.upstreamUnset", "feat"));

            // Rename and New Branch From open their forms for that branch.
            FxTestSupport.runOnFx(
                    action(actions, tr("branch.row.rename", "feat", "main")).run());
            assertEquals(
                    "feat",
                    FxTestSupport.callOnFx(() ->
                            OverlayTestKit.formFields(win.w().scene()).get(0).getText()));
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "feat")); // unchanged: no-op
            FxTestSupport.runOnFx(
                    action(actions, tr("branch.row.newFrom", "feat", "main")).run());
            FxTestSupport.runOnFx(() -> OverlayTestKit.submitForm(win.w().scene(), "from-feat"));
            awaitMessage(async, win, tr("status.createdBranch", "from-feat", "feat"));
            assertEquals("from-feat", currentBranch(repo));
            repo.git("checkout", "-q", "main");

            // Merge brings the row's branch into the current one; Delete then removes it without a question.
            FxTestSupport.runOnFx(
                    action(actions, tr("branch.row.merge", "feat", "main")).run());
            awaitMessage(async, win, tr("status.git.merged", "feat", "main"));
            FxTestSupport.runOnFx(
                    action(actions, tr("branch.row.rebase", "feat", "main")).run());
            awaitMessage(async, win, tr("status.git.alreadyUpToDate", "feat"));
            repo.git("branch", "-D", "from-feat");
            FxTestSupport.runOnFx(
                    action(actions, tr("branch.row.delete", "feat", "main")).run());
            awaitMessage(async, win, tr("status.git.deletedBranch", "feat"));
            assertFalse(hasBranch(repo, "feat"));

            assertEquals(8, guarded.get(), "each of the eight actions ran through the guard, once");
        }
    }

    @Test
    void aRemoteBranchRowIsDeletedOnItsRemoteOnlyAfterConfirmation(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        Path remote = bareRemote(dir, "origin");
        repo.git("remote", "add", "origin", remote.toString());
        repo.git("push", "-q", "origin", "main:topic");
        repo.git("fetch", "-q", "origin");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);
            GitBranchCoordinator branches = win.branches();
            GitService.Branches listed = listBranches(async, win);
            BranchPopup.BranchRef row = new BranchPopup.BranchRef("origin/topic", true, false, "", false);
            List<BranchPopup.RowAction> actions = FxTestSupport.callOnFx(
                    () -> branches.rowActions(repo.root, "main", listed, row, UnaryOperator.identity()));
            BranchPopup.RowAction delete = action(actions, tr("branch.row.deleteRemote", "origin/topic", "main"));
            assertTrue(delete.danger());

            AtomicReference<OverlayTestKit.Shown> question = new AtomicReference<>();
            CountDownLatch declined =
                    OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, question);
            FxTestSupport.runOnFx(delete.run());
            async.await(declined, "the delete question");
            assertEquals(
                    tr("dialog.deleteRemoteBranch.confirm", "topic", "origin"),
                    question.get().content());
            assertFalse(bareRev(remote, "refs/heads/topic").isEmpty(), "declined: the branch is still there");

            // The command form: pick the remote branch, confirm, and it is gone for everyone.
            CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
            FxTestSupport.runOnFx(branches::deleteRemoteBranch);
            List<?> offered = awaitPicker(async, win);
            assertTrue(offered.contains(
                    new GitBranchCoordinator.Ref("origin/topic", GitBranchCoordinator.RefKind.REMOTE)));
            pick(win, "origin/topic");
            async.await(agreed, "the delete question, accepted");
            awaitMessage(async, win, tr("status.git.deletedRemoteBranch", "topic", "origin"));
            assertEquals("", bareRev(remote, "refs/heads/topic"));
            assertNotNull(bareRev(remote, "refs/heads/main"));
        }
    }

    @Test
    void aRejectedPushLeftAloneOnlySaysItWasRejected(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repoWithCommit(dir);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Window win = window(async, repo);
            FxTestSupport.runOnFx(() -> win.branches()
                    .answerRejectedPush(repo.root, "main", "", GitBranchCoordinator.RejectedPushChoice.CANCEL));
            assertEquals(tr("status.git.pushRejected"), win.w().status());
        }
    }

    private static BranchPopup.RowAction action(List<BranchPopup.RowAction> actions, String label) {
        return actions.stream()
                .filter(action -> label.equals(action.label()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row action " + label));
    }

    private static GitService.Branches listBranches(AsyncTestScope async, Window win) throws Exception {
        java.util.concurrent.CompletableFuture<GitService.Branches> listed =
                new java.util.concurrent.CompletableFuture<>();
        FxTestSupport.runOnFx(() -> win.w().git.service().branches(win.repo().root, listed::complete));
        return async.await(listed);
    }
}
