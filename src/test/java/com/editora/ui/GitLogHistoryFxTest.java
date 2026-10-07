package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.git.GitLog;
import com.editora.git.GitService.CommitFile;
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
 * The Git Log against a real repository: paging, the all-branches view, history search, a file history that
 * follows a rename, commit details, whole-commit review and two-commit compare, tags, and reverting a merge.
 *
 * <p>Before this the log was the newest 200 commits of the checked-out branch and nothing else: no way to
 * reach commit 201, no other branch, a filter over those 200 rows only, a file history that stopped at a
 * rename, and {@code git revert} of a merge failing with git's "no -m option was given".
 */
@Tag("fx")
class GitLogHistoryFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A window on {@code repo} with its Git Log open; everything a test drives. */
    private record Log(
            FxWindowFixture fx,
            GitCoordinator git,
            GitWindowCoordinator windows,
            GitLogPanel panel,
            AsyncTestScope async) {

        static Log open(AsyncTestScope async, GitTestRepo repo, Path file, int pageSize) throws Exception {
            return open(async, repo, file, pageSize, false);
        }

        /** {@code loadOnScroll} off: the test asks for each page itself (every row of a tiny log is on screen). */
        static Log open(AsyncTestScope async, GitTestRepo repo, Path file, int pageSize, boolean loadOnScroll)
                throws Exception {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            GitLogReloadFxTest.open(fx.controller, file);
            GitCoordinator git = GitLogReloadFxTest.applyRepo(fx, repo.root);
            GitWindowCoordinator windows = FxTestSupport.field(fx.controller, "gitWindows");
            GitLogPanel panel = FxTestSupport.field(fx.controller, "gitLogPanel");
            Log log = new Log(fx, git, windows, panel, async);
            FxTestSupport.runOnFx(() -> {
                if (!loadOnScroll) {
                    panel.loadAheadRows = 0;
                }
                windows.logPageSize = pageSize;
                windows.showGitLog();
                windows.loadGitLog(null);
            });
            log.settle();
            return log;
        }

        /** Waits until every queued git read and mutation, and the FX work they posted, has run. */
        void settle() throws Exception {
            for (int round = 0; round < 3; round++) {
                for (String lane : List.of("exec", "networkExec", "historyExec")) {
                    ExecutorService worker = FxTestSupport.field(git.service(), lane);
                    async.awaitWorker(worker);
                    async.awaitFx();
                }
            }
        }

        List<String> subjects() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                List<GitLog.Entry> rows = FxTestSupport.field(panel, "allCommits");
                return rows.stream().map(GitLog.Entry::subject).toList();
            });
        }

        String hashOf(String subject) throws Exception {
            return FxTestSupport.callOnFx(() -> {
                List<GitLog.Entry> rows = FxTestSupport.field(panel, "allCommits");
                return rows.stream()
                        .filter(c -> c.subject().equals(subject))
                        .findFirst()
                        .orElseThrow()
                        .hash();
            });
        }

        void onFx(Runnable action) throws Exception {
            FxTestSupport.runOnFx(action);
            settle();
        }

        String echo() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
                Label label = FxTestSupport.field(statusBar, "echo");
                return label.getText();
            });
        }

        String header() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                Label label = FxTestSupport.field(panel, "filterLabel");
                return label.getText();
            });
        }
    }

    private static GitTestRepo linearRepo(Path dir, int commits) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        for (int i = 1; i <= commits; i++) {
            repo.write("work.txt", "line " + i + "\n");
            repo.commitAll("commit " + i);
        }
        return repo;
    }

    private static List<String> commitSubjects(int newest, int oldest) {
        List<String> subjects = new ArrayList<>();
        for (int i = newest; i >= oldest; i--) {
            subjects.add("commit " + i);
        }
        return subjects;
    }

    @Test
    void theLogLoadsOlderHistoryAPageAtATimeAndKeepsTheSelection(@TempDir Path dir) throws Exception {
        GitTestRepo repo = linearRepo(dir, 8);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Log log = Log.open(async, repo, repo.root.resolve("work.txt"), 3);

            assertEquals(commitSubjects(8, 6), log.subjects(), "the first page");
            assertTrue(FxTestSupport.callOnFx(log.panel::hasMore), "older history exists and the panel says so");
            String selected = log.hashOf("commit 7");
            log.onFx(() -> log.panel.selectCommit(selected));

            log.onFx(log.windows::loadMoreGitLog);
            assertEquals(commitSubjects(8, 3), log.subjects(), "the next page is appended, nothing repeated");
            assertEquals(selected, FxTestSupport.callOnFx(log.panel::selectedHash), "the selection stays");

            log.onFx(log.windows::loadMoreGitLog);
            assertEquals(commitSubjects(8, 1), log.subjects());
            assertFalse(FxTestSupport.callOnFx(log.panel::hasMore), "the root commit is loaded: nothing more");
            List<?> graphRows = FxTestSupport.callOnFx(() -> FxTestSupport.field(log.panel, "graphRows"));
            assertEquals(8, graphRows.size(), "the graph continued into every page");

            // A reload after a Git command keeps the depth loaded so far instead of snapping back to a page.
            repo.write("work.txt", "line 9\n");
            repo.commitAll("commit 9");
            log.onFx(log.git::afterMutation);
            assertEquals(commitSubjects(9, 2), log.subjects());
            assertEquals(selected, FxTestSupport.callOnFx(log.panel::selectedHash));
        }
    }

    @Test
    void scrollingNearTheEndOfTheLoadedRowsFetchesTheNextPage(@TempDir Path dir) throws Exception {
        GitTestRepo repo = linearRepo(dir, 8);
        try (AsyncTestScope async = new AsyncTestScope()) {
            // Eight commits in pages of three all fit on screen, so the end of the loaded rows is always in
            // view: the pages follow one another without a click until the history is exhausted.
            Log log = Log.open(async, repo, repo.root.resolve("work.txt"), 3, true);
            for (int i = 0; i < 100 && log.subjects().size() < 8; i++) {
                Thread.sleep(50);
                log.settle();
            }
            assertEquals(commitSubjects(8, 1), log.subjects());
            assertFalse(FxTestSupport.callOnFx(log.panel::hasMore));
        }
        assertTrue(GitLogPanel.loadsAhead(170, 200, true, false, GitLogPanel.LOAD_AHEAD_ROWS));
        assertFalse(GitLogPanel.loadsAhead(100, 200, true, false, GitLogPanel.LOAD_AHEAD_ROWS), "not near the end");
        assertFalse(GitLogPanel.loadsAhead(199, 200, false, false, GitLogPanel.LOAD_AHEAD_ROWS), "nothing more");
        assertFalse(
                GitLogPanel.loadsAhead(3, 4, true, true, GitLogPanel.LOAD_AHEAD_ROWS),
                "a filtered list is short: its end is always on screen, and must not pull the history in");
    }

    @Test
    void aPageThatNoLongerContinuesTheLoadedRowsReloadsInsteadOfRepeatingACommit(@TempDir Path dir) throws Exception {
        GitTestRepo repo = linearRepo(dir, 6);
        try (AsyncTestScope async = new AsyncTestScope()) {
            Log log = Log.open(async, repo, repo.root.resolve("work.txt"), 3);
            assertEquals(commitSubjects(6, 4), log.subjects());

            // A commit made in a terminal: Editora is not told, and "skip 3" now starts one commit early.
            repo.write("work.txt", "line 7\n");
            repo.commitAll("commit 7");
            log.onFx(log.windows::loadMoreGitLog);

            List<String> subjects = log.subjects();
            assertEquals(subjects.stream().distinct().toList(), subjects, "no commit is listed twice");
            assertEquals("commit 7", subjects.get(0), "the list was reloaded from the top");
        }
    }

    @Test
    void aRepositoryWithoutCommitsIsAnEmptyLogAndABrokenSearchSaysWhy(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("work.txt", "one\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Log log = Log.open(async, repo, file, 50);
            Label placeholder = FxTestSupport.callOnFx(() -> FxTestSupport.field(log.panel, "placeholder"));
            assertTrue(log.subjects().isEmpty());
            assertEquals(
                    tr("gitlog.noCommits"),
                    FxTestSupport.callOnFx(placeholder::getText),
                    "an unborn branch is an empty history, not git's fatal message");

            repo.commitAll("first");
            log.onFx(() -> log.windows.searchGitLog("no-such-text-anywhere"));
            assertEquals(tr("gitlog.noMatches"), FxTestSupport.callOnFx(placeholder::getText));
        }
    }

    @Test
    void theAllBranchesToggleListsCommitsTheCurrentBranchDoesNotHave(@TempDir Path dir) throws Exception {
        GitTestRepo repo = linearRepo(dir, 2);
        repo.git("checkout", "-q", "-b", "side");
        repo.write("side.txt", "side\n");
        repo.commitAll("side work");
        repo.git("tag", "v-side");
        repo.git("checkout", "-q", "main");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Log log = Log.open(async, repo, repo.root.resolve("work.txt"), 50);
            assertEquals(commitSubjects(2, 1), log.subjects(), "one branch's history");
            assertEquals(tr("gitlog.branch", "main"), log.header());

            CommandRegistry registry = FxTestSupport.field(log.fx.controller, "registry");
            log.onFx(() -> registry.run("git.log.toggleAllBranches"));
            assertEquals(List.of("side work", "commit 2", "commit 1"), log.subjects());
            assertEquals(tr("gitlog.allBranches"), log.header(), "the header says what is listed");

            log.onFx(() -> registry.run("git.log.toggleAllBranches"));
            assertEquals(commitSubjects(2, 1), log.subjects());
        }
    }

    @Test
    void aHistorySearchRunsOverTheWholeHistoryNotTheLoadedRows(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("work.txt", "alpha\n");
        repo.commitAll("add the NEEDLE feature");
        repo.git("config", "user.name", "Grace Hopper");
        repo.write("docs/guide.md", "guide\n");
        repo.commitAll("write the guide");
        repo.git("config", "user.name", "Editora Test");
        for (int i = 1; i <= 4; i++) {
            repo.write("work.txt", "alpha " + i + "\n");
            repo.commitAll("commit " + i);
        }
        repo.write("work.txt", "alpha 4\nsecret-token\n");
        repo.commitAll("commit 5");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Log log = Log.open(async, repo, repo.root.resolve("work.txt"), 2);
            assertEquals(List.of("commit 5", "commit 4"), log.subjects(), "the match is far below the loaded page");

            log.onFx(() -> log.windows.searchGitLog("needle"));
            assertEquals(List.of("add the NEEDLE feature"), log.subjects(), "message text, case-insensitive");
            Label chip = FxTestSupport.callOnFx(() -> FxTestSupport.field(log.panel, "searchLabel"));
            assertEquals(tr("gitlog.searching", "needle"), FxTestSupport.callOnFx(chip::getText));
            assertTrue(
                    FxTestSupport.callOnFx(() -> chip.getParent().isVisible()),
                    "an active search is shown, with its clear button");
            int graph = FxTestSupport.callOnFx(() -> FxTestSupport.<Integer>field(log.panel, "graphColumns"));
            assertEquals(0, graph, "a search result is a subset of the history: no graph");

            log.onFx(() -> log.windows.searchGitLog("author:grace"));
            assertEquals(List.of("write the guide"), log.subjects());

            log.onFx(() -> log.windows.searchGitLog("path:docs"));
            assertEquals(List.of("write the guide"), log.subjects());

            log.onFx(() -> log.windows.searchGitLog("content:secret-token"));
            assertEquals(List.of("commit 5"), log.subjects(), "the pickaxe finds the commit that added the text");

            // A term that would be an option or pathspec magic on its own is searched for, not obeyed.
            log.onFx(() -> log.windows.searchGitLog("--all path::!work.txt"));
            assertTrue(log.subjects().isEmpty(), "no message contains --all; :!work.txt is a literal pattern");

            log.onFx(() -> log.windows.searchGitLog(""));
            assertEquals(List.of("commit 5", "commit 4"), log.subjects(), "clearing the search brings the log back");
            assertFalse(FxTestSupport.callOnFx(() -> chip.getParent().isVisible()));
        }
    }

    @Test
    void aFileHistoryFollowsARenameAndComparesTheOldNameWithTheFileAsItIsNow(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("old-name.txt", "one\ntwo\nthree\nfour\nfive\n");
        repo.write("other.txt", "unrelated\n");
        repo.commitAll("add the file");
        repo.git("mv", "old-name.txt", "new-name.txt");
        repo.commitAll("rename it");
        Path file = repo.write("new-name.txt", "one\ntwo\nthree\nfour\nfive\nsix\n");
        repo.commitAll("edit after the rename");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Log log = Log.open(async, repo, file, 50);
            log.onFx(() -> log.windows.gitFileHistoryForPath(file));

            assertEquals(
                    List.of("edit after the rename", "rename it", "add the file"),
                    log.subjects(),
                    "the history does not stop at the rename");
            String oldest = log.hashOf("add the file");
            assertEquals("old-name.txt", FxTestSupport.callOnFx(() -> log.panel.followedPath(oldest)));
            assertEquals("new-name.txt", FxTestSupport.callOnFx(() -> log.panel.followedPath(log.hashOf("rename it"))));

            // Selecting the oldest commit lists both of its files and puts the selection on the followed one.
            log.onFx(() -> log.panel.selectCommit(oldest));
            ListView<CommitFile> files = FxTestSupport.callOnFx(() -> FxTestSupport.field(log.panel, "files"));
            assertEquals(2, FxTestSupport.callOnFx(() -> files.getItems().size()));
            assertEquals(
                    "old-name.txt",
                    FxTestSupport.callOnFx(
                            () -> files.getSelectionModel().getSelectedItem().path()));

            // Its row is compared with the file under its present name — it used to answer "file is gone".
            log.onFx(() -> FxTestSupport.call(log.panel, "openSelectedFile", new Class<?>[] {}));
            Object tab = awaitTab(log.fx.controller, DiffViewerPane.class);
            assertNotNull(tab, "a diff opened for the renamed file (status: " + log.echo() + ")");
        }
        assertEquals(
                dir.resolve("f"),
                GitWindowCoordinator.historyWorkingFile(dir, dir.resolve("f"), "was/elsewhere", "was/elsewhere"));
        assertEquals(
                dir.resolve("other"),
                GitWindowCoordinator.historyWorkingFile(dir, dir.resolve("f"), "other", "was/elsewhere"),
                "any other file of the commit is still compared with its own working copy");
    }

    private static Object awaitTab(MainController controller, Class<?> type) throws Exception {
        EditorArea area = FxTestSupport.field(controller, "editorArea");
        for (int i = 0; i < 120; i++) {
            Object data = FxTestSupport.callOnFx(() -> {
                Tab selected = area.selectedTab();
                return selected == null ? null : selected.getUserData();
            });
            if (type.isInstance(data)) {
                return data;
            }
            Thread.sleep(50);
        }
        return null;
    }

    @Test
    void selectingACommitShowsItsFullMessageAuthorAndParents(@TempDir Path dir) throws Exception {
        GitTestRepo repo = linearRepo(dir, 1);
        repo.write("work.txt", "changed\n");
        repo.git("add", "-A");
        repo.git("commit", "-q", "--no-verify", "-m", "Subject line", "-m", "Body paragraph\nsecond line.");
        String parent = repo.git("rev-parse", "HEAD~1").text().strip();
        String head = repo.git("rev-parse", "HEAD").text().strip();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Log log = Log.open(async, repo, repo.root.resolve("work.txt"), 50);
            log.onFx(() -> log.panel.selectCommit(head));

            String hash = FxTestSupport.callOnFx(
                    () -> FxTestSupport.<Label>field(log.panel, "detailsHash").getText());
            String author = FxTestSupport.callOnFx(
                    () -> FxTestSupport.<Label>field(log.panel, "detailsAuthor").getText());
            String message = FxTestSupport.callOnFx(() ->
                    FxTestSupport.<Label>field(log.panel, "detailsMessage").getText());
            assertEquals(head, hash, "the full hash");
            assertTrue(author.contains("Editora Test <editora-test@example.invalid>"), author);
            assertTrue(author.contains(GitLogPanel.absoluteDate(
                    Long.parseLong(repo.git("log", "-1", "--format=%at").text().strip()))));
            assertEquals("Subject line\n\nBody paragraph\nsecond line.", message, "the whole message, not the subject");

            // The parent is a link to its row.
            javafx.scene.layout.HBox parents =
                    FxTestSupport.callOnFx(() -> FxTestSupport.field(log.panel, "detailsParents"));
            log.onFx(() -> parents.getChildren().stream()
                    .filter(n -> n instanceof javafx.scene.control.Hyperlink)
                    .map(n -> (javafx.scene.control.Hyperlink) n)
                    .findFirst()
                    .orElseThrow()
                    .fire());
            assertEquals(parent, FxTestSupport.callOnFx(log.panel::selectedHash));
        }
    }

    @Test
    void aCommitOpensAsOneReviewAndTwoCommitsCompare(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("a.txt", "a1\n");
        repo.write("b.txt", "b1\n");
        repo.write("moved.txt", "one\ntwo\nthree\nfour\n");
        repo.commitAll("first");
        repo.write("a.txt", "a2\n");
        repo.write("c.txt", "new\n");
        Files.delete(repo.root.resolve("b.txt"));
        repo.git("mv", "moved.txt", "renamed.txt");
        repo.commitAll("second");
        repo.write("a.txt", "a3\n");
        repo.commitAll("third");
        try (AsyncTestScope async = new AsyncTestScope()) {
            Log log = Log.open(async, repo, repo.root.resolve("a.txt"), 50);
            String first = log.hashOf("first");
            String second = log.hashOf("second");
            String third = log.hashOf("third");
            CommandRegistry registry = FxTestSupport.field(log.fx.controller, "registry");

            // Enter on a commit (the git.log.reviewCommit command): every file it changed, in one tab.
            log.onFx(() -> log.panel.selectCommit(second));
            log.onFx(() -> registry.run("git.log.reviewCommit"));
            DirectoryReviewPane review = (DirectoryReviewPane) awaitTab(log.fx.controller, DirectoryReviewPane.class);
            assertNotNull(review, "the commit opened as a review (status: " + log.echo() + ")");
            assertEquals(List.of("a.txt", "b.txt", "c.txt", "renamed.txt"), labels(review));
            assertEquals(tr("diff.title.commitReview", com.editora.git.GitFormat.shortHash(second)), review.title());

            // The root commit has no parent: all additions, and it still opens.
            GitLogReloadFxTest.applyRepo(log.fx, repo.root);
            log.onFx(() -> log.windows.reviewCommitIn(repo.root, first));
            DirectoryReviewPane rootReview = awaitOther(log.fx.controller, review);
            assertEquals(List.of("a.txt", "b.txt", "moved.txt"), labels(rootReview));

            // Two selected rows compare, older on the left whatever order they were clicked in.
            GitLogReloadFxTest.applyRepo(log.fx, repo.root);
            log.onFx(() -> log.windows.showGitLog());
            log.onFx(() -> {
                ListView<GitLog.Entry> commits = FxTestSupport.field(log.panel, "commits");
                commits.getSelectionModel().clearSelection();
                commits.getSelectionModel().selectIndices(2, 0); // "first", then "third"
            });
            assertEquals(List.of(third, first), FxTestSupport.callOnFx(log.panel::selectedHashes));
            log.onFx(() -> registry.run("git.log.compareSelected"));
            DirectoryReviewPane compare = awaitOther(log.fx.controller, rootReview);
            assertNotNull(compare, "the comparison opened (status: " + log.echo() + ")");
            assertEquals(List.of("a.txt", "b.txt", "c.txt", "renamed.txt"), labels(compare));
            assertEquals(
                    tr(
                            "diff.title.commitCompare",
                            com.editora.git.GitFormat.shortHash(first),
                            com.editora.git.GitFormat.shortHash(third)),
                    compare.title());
        }
    }

    private static DirectoryReviewPane awaitOther(MainController controller, DirectoryReviewPane previous)
            throws Exception {
        EditorArea area = FxTestSupport.field(controller, "editorArea");
        for (int i = 0; i < 120; i++) {
            Object data = FxTestSupport.callOnFx(() -> {
                Tab selected = area.selectedTab();
                return selected == null ? null : selected.getUserData();
            });
            if (data instanceof DirectoryReviewPane review && review != previous) {
                return review;
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static List<String> labels(DirectoryReviewPane review) {
        List<DirectoryReviewPane.Entry> entries = FxTestSupport.field(review, "entries");
        return entries.stream().map(DirectoryReviewPane.Entry::label).toList();
    }

    @Test
    void tagsAreCreatedCheckedOutPushedAndDeletedFromTheLog(@TempDir Path dir) throws Exception {
        GitTestRepo repo = linearRepo(dir, 3);
        Path remote = dir.resolve("remote.git");
        repo.git("init", "-q", "--bare", remote.toString());
        repo.git("remote", "add", "origin", remote.toString());
        try (AsyncTestScope async = new AsyncTestScope()) {
            Log log = Log.open(async, repo, repo.root.resolve("work.txt"), 50);
            String second = log.hashOf("commit 2");
            String third = log.hashOf("commit 3");

            // Lightweight (no message) at a chosen commit; annotated when a message is given.
            log.onFx(() -> log.windows.createTag(repo.root, "v2", "", second));
            log.onFx(() -> log.windows.createTag(repo.root, "v3", "Release three", third));
            assertEquals(second, repo.git("rev-parse", "v2^{commit}").text().strip());
            assertEquals("commit", repo.git("cat-file", "-t", "v2").text().strip(), "no message: lightweight");
            assertEquals("tag", repo.git("cat-file", "-t", "v3").text().strip(), "a message: annotated");
            assertTrue(repo.git("tag", "-l", "-n1", "v3").text().contains("Release three"));
            // A Git command shows its transcript in the Output console, which takes the log's place in the
            // bottom panel; reopening the log loads it afresh.
            log.onFx(log.windows::showGitLog);
            assertEquals(
                    List.of("v2"),
                    FxTestSupport.callOnFx(() -> log.panel.entry(second).tags()),
                    "the reloaded row carries its new tag");

            // A name git would refuse, or read as an option, never reaches the command line.
            for (String bad : List.of("--force", "-d", "bad name", "a..b", "x.lock")) {
                log.onFx(() -> log.windows.createTag(repo.root, bad, "", third));
                assertEquals(tr("status.git.tag.invalidName", bad), log.echo());
            }
            assertEquals(
                    List.of("v2", "v3"), repo.git("tag", "-l").text().lines().toList());

            // Push one tag; the branch itself is not pushed with it.
            log.onFx(() -> log.windows.pushTagIn(repo.root, "v2"));
            GitTestRepo.Output remoteRefs = repo.git("ls-remote", "origin");
            assertTrue(remoteRefs.text().contains("refs/tags/v2"), remoteRefs.text() + log.echo());
            assertFalse(remoteRefs.text().contains("refs/heads/main"), remoteRefs.text());
            assertFalse(remoteRefs.text().contains("refs/tags/v3"), remoteRefs.text());

            // Checkout: a detached HEAD at the tag, even when a branch has the same name.
            repo.git("branch", "v2", third);
            log.onFx(() -> log.windows.checkoutTagIn(repo.root, "v2"));
            assertEquals(second, repo.git("rev-parse", "HEAD").text().strip());
            assertEquals("", repo.git("branch", "--show-current").text().strip(), "detached, not the branch named v2");

            // Delete asks first; declining leaves the tag.
            List<String> asked = new ArrayList<>();
            log.onFx(() -> {
                log.windows.tagDeleteConfirmer = (tag, root) -> {
                    asked.add(tag);
                    return false;
                };
                log.windows.deleteTagIn(repo.root, "v3");
            });
            assertEquals(List.of("v3"), asked);
            assertTrue(repo.git("tag", "-l").text().contains("v3"));
            log.onFx(() -> {
                log.windows.tagDeleteConfirmer = (tag, root) -> true;
                log.windows.deleteTagIn(repo.root, "v3");
            });
            assertFalse(repo.git("tag", "-l").text().contains("v3"));

            CommandRegistry registry = FxTestSupport.field(log.fx.controller, "registry");
            for (String id : List.of(
                    "git.tag.create",
                    "git.tag.delete",
                    "git.tag.push",
                    "git.tag.checkout",
                    "git.log.toggleAllBranches",
                    "git.log.search",
                    "git.log.loadMore",
                    "git.log.reviewCommit",
                    "git.log.compareSelected")) {
                assertTrue(FxTestSupport.callOnFx(() -> registry.get(id).isPresent()), id + " is not a command");
            }
        }
    }

    @Test
    void revertingAMergeAsksForTheMainlineInsteadOfFailing(@TempDir Path dir) throws Exception {
        GitTestRepo repo = linearRepo(dir, 2);
        repo.git("checkout", "-q", "-b", "side");
        repo.write("side.txt", "side\n");
        repo.commitAll("side work");
        repo.git("checkout", "-q", "main");
        repo.write("main.txt", "main\n");
        repo.commitAll("main work");
        repo.git("merge", "-q", "--no-ff", "--no-edit", "side");
        String merge = repo.git("rev-parse", "HEAD").text().strip();
        String mainParent = repo.git("rev-parse", "HEAD^1").text().strip();
        String sideParent = repo.git("rev-parse", "HEAD^2").text().strip();
        try (AsyncTestScope async = new AsyncTestScope()) {
            Log log = Log.open(async, repo, repo.root.resolve("work.txt"), 50);
            assertTrue(FxTestSupport.callOnFx(() -> log.panel.entry(merge).isMerge()));
            List<List<String>> asked = new ArrayList<>();

            // Cancelling the choice reverts nothing and says so.
            log.onFx(() -> {
                log.windows.mainlineChooser = (hash, parents) -> {
                    asked.add(parents);
                    return null;
                };
                log.windows.revertIn(repo.root, merge);
            });
            assertEquals(List.of(List.of(mainParent, sideParent)), asked, "both parents are offered, mainline first");
            assertEquals(merge, repo.git("rev-parse", "HEAD").text().strip());
            assertEquals(tr("status.git.revertMergeCancelled"), log.echo());

            // Parent 1 as the mainline: what the side branch brought in is taken out again.
            log.onFx(() -> {
                log.windows.mainlineChooser = (hash, parents) -> 1;
                log.windows.revertIn(repo.root, merge);
            });
            assertEquals(merge, repo.git("rev-parse", "HEAD~1").text().strip(), "a revert commit was added");
            assertFalse(Files.exists(repo.root.resolve("side.txt")), "the merged-in file is gone");
            assertTrue(Files.exists(repo.root.resolve("main.txt")));

            // An ordinary commit is reverted without a question.
            asked.clear();
            String plain = repo.git("rev-parse", "HEAD~1^1").text().strip(); // "main work"
            log.onFx(() -> log.windows.revertIn(repo.root, plain));
            assertTrue(asked.isEmpty());
            assertFalse(Files.exists(repo.root.resolve("main.txt")));
        }
        assertEquals(List.of("revert", "--no-edit", "abc"), List.of(GitWindowCoordinator.revertArgs("abc", 0)));
        assertEquals(
                List.of("revert", "--no-edit", "-m", "2", "abc"), List.of(GitWindowCoordinator.revertArgs("abc", 2)));
        assertNull(GitLog.parseDetails(""));
    }
}
