package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import com.editora.git.GitLog;
import com.editora.git.GitLogQuery;
import com.editora.git.GitNumstat;
import com.editora.git.GitService;
import com.editora.git.StashOptions;
import com.editora.git.StashParser;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link GitService} answers at its edges: with no git to run, with no repository, with a revision name
 * that would be read as an option — each query still calls back, with its documented empty answer — and, in a
 * real repository, the answers of the queries the rest of the suite only reaches through the UI.
 */
@Tag("fx")
class GitServiceAnswersFxTest {

    private static final String NO_SUCH_GIT = "/no/such/directory/git-that-is-not-installed";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void restoreTheGitCommand() {
        new GitService().setCommand(""); // the command is app-wide state: PATH git for the tests that follow
    }

    /** One read-only query, reduced to a description of its answer so answers can be compared. */
    private record Query(String name, BiFunction<GitService, Path, CompletableFuture<String>> ask, String empty) {}

    private static <T> CompletableFuture<String> describe(
            Consumer<Consumer<T>> call, java.util.function.Function<T, String> description) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        call.accept(result -> answer.complete(description.apply(result)));
        return answer;
    }

    private static String listOrNull(List<?> list) {
        return list == null ? "null" : "size=" + list.size();
    }

    private static List<Query> queries(Path file) {
        return List.of(
                new Query(
                        "diff",
                        (git, root) -> describe(
                                done -> git.diff(root, file, done::accept),
                                (GitService.GitDiff diff) -> String.valueOf(diff == GitService.GitDiff.EMPTY)),
                        "true"),
                new Query(
                        "unstagedHunks",
                        (git, root) -> describe(
                                done -> git.unstagedHunks(root, file, done::accept),
                                GitServiceAnswersFxTest::listOrNull),
                        "null"),
                new Query(
                        "showBlob",
                        (git, root) -> describe(
                                done -> git.showBlob(root, "HEAD:file.txt", done::accept),
                                (GitService.BlobResult blob) -> blob.found() + "/" + blob.truncated()),
                        "false/false"),
                new Query(
                        "showBytes",
                        (git, root) -> describe(
                                done -> git.showBytes(root, "HEAD:file.txt", done::accept),
                                (byte[] bytes) -> String.valueOf(bytes.length)),
                        "0"),
                new Query(
                        "workingTreeDiff",
                        (git, root) -> describe(
                                done -> git.workingTreeDiff(root, file.getParent(), "HEAD", done::accept),
                                (GitService.WorkingTreeDiff diff) ->
                                        diff.files().size() + "/" + diff.error()),
                        "0/Git is not available"),
                new Query(
                        "log",
                        (git, root) -> describe(
                                done -> git.log(root, null, 10, done::accept), GitServiceAnswersFxTest::listOrNull),
                        "size=0"),
                new Query(
                        "logPage",
                        (git, root) -> describe(
                                done -> git.logPage(
                                        root,
                                        new GitLog.Request(false, null, GitLogQuery.parse(""), 0, 10),
                                        done::accept),
                                (GitLog.Page page) -> String.valueOf(page == GitLog.Page.EMPTY)),
                        "true"),
                new Query(
                        "commitDetails",
                        (git, root) -> describe(
                                done -> git.commitDetails(root, "HEAD", done::accept),
                                (GitLog.Details details) -> String.valueOf(details == null)),
                        "true"),
                new Query(
                        "diffFiles",
                        (git, root) -> describe(
                                done -> git.diffFiles(root, "HEAD", "HEAD", done::accept),
                                (GitService.WorkingTreeDiff diff) ->
                                        diff.files().size() + "/" + diff.error()),
                        "0/Git is not available"),
                new Query(
                        "refDiff",
                        (git, root) -> describe(
                                done -> git.refDiff(root, "HEAD", "HEAD", done::accept),
                                (GitService.WorkingTreeDiff diff) ->
                                        diff.files().size() + "/" + diff.error()),
                        "0/Git is not available"),
                new Query(
                        "blame",
                        (git, root) -> describe(
                                done -> git.blame(root, file, done::accept), GitServiceAnswersFxTest::listOrNull),
                        "size=0"),
                new Query(
                        "commitFiles",
                        (git, root) -> describe(
                                done -> git.commitFiles(root, "HEAD", done::accept),
                                GitServiceAnswersFxTest::listOrNull),
                        "size=0"),
                new Query(
                        "stashList",
                        (git, root) -> describe(
                                done -> git.stashList(root, done::accept), GitServiceAnswersFxTest::listOrNull),
                        "size=0"),
                new Query(
                        "stashFiles",
                        (git, root) -> describe(
                                done -> git.stashFiles(root, "stash@{0}", done::accept),
                                GitServiceAnswersFxTest::listOrNull),
                        "size=0"),
                new Query(
                        "branches",
                        (git, root) -> describe(
                                done -> git.branches(root, done::accept),
                                (GitService.Branches branches) ->
                                        String.valueOf(branches == GitService.Branches.EMPTY)),
                        "true"),
                new Query(
                        "untrackedFiles",
                        (git, root) -> describe(
                                done -> git.untrackedFiles(root, List.of(), done::accept),
                                GitServiceAnswersFxTest::listOrNull),
                        "null"),
                new Query(
                        "commitsLeftBehind",
                        (git, root) -> describe(
                                done -> git.commitsLeftBehind(root, "HEAD", true, done::accept),
                                (GitService.LeftBehind left) -> String.valueOf(left.known())),
                        "false"),
                new Query(
                        "tags",
                        (git, root) ->
                                describe(done -> git.tags(root, done::accept), GitServiceAnswersFxTest::listOrNull),
                        "size=0"),
                new Query(
                        "remotes",
                        (git, root) ->
                                describe(done -> git.remotes(root, done::accept), GitServiceAnswersFxTest::listOrNull),
                        "size=0"),
                new Query(
                        "pushRemoteOf",
                        (git, root) -> describe(
                                done -> git.pushRemoteOf(root, "main", done::accept), (String remote) -> remote),
                        "origin"),
                new Query(
                        "worktrees",
                        (git, root) -> describe(
                                done -> git.worktrees(root, done::accept), GitServiceAnswersFxTest::listOrNull),
                        "size=0"),
                new Query(
                        "unmergedCount",
                        (git, root) -> describe(
                                done -> git.unmergedCount(root, "main", done::accept),
                                (Integer count) -> String.valueOf(count)),
                        "-1"),
                new Query(
                        "commitMessagesSince",
                        (git, root) -> describe(
                                done -> git.commitMessagesSince(root, "HEAD", 5, done::accept),
                                GitServiceAnswersFxTest::listOrNull),
                        "size=0"),
                new Query(
                        "headCommit",
                        (git, root) -> describe(
                                done -> git.headCommit(root, done::accept),
                                (GitService.HeadCommit head) -> String.valueOf(head == null)),
                        "true"),
                new Query(
                        "commitTemplate",
                        (git, root) -> describe(
                                done -> git.commitTemplate(root, done::accept),
                                (GitService.CommitTemplate template) ->
                                        String.valueOf(template == GitService.CommitTemplate.NONE)),
                        "true"),
                new Query(
                        "lineCounts",
                        (git, root) -> describe(
                                done -> git.lineCounts(root, done::accept),
                                (GitNumstat.Changes counts) -> String.valueOf(counts == GitNumstat.Changes.NONE)),
                        "true"),
                new Query(
                        "stagedDiff",
                        (git, root) -> describe(
                                done -> git.stagedDiff(root, done::accept), (String diff) -> String.valueOf(diff)),
                        "null"),
                new Query(
                        "diffPatch",
                        (git, root) -> describe(
                                done -> git.diffPatch(root, true, done::accept),
                                (GitService.PatchText patch) -> patch.ok() + "/" + patch.bytes().length),
                        "false/0"),
                new Query(
                        "commitPatch",
                        (git, root) -> describe(
                                done -> git.commitPatch(root, "HEAD", done::accept),
                                (GitService.PatchText patch) -> patch.ok() + "/" + patch.bytes().length),
                        "false/0"),
                new Query(
                        "applyPatch",
                        (git, root) -> describe(
                                done -> git.applyPatch(root, (String) null, true, done::accept),
                                (ProcessRunner.Result result) -> result.ok() + "/" + result.err()),
                        "false/Git is not installed"),
                new Query(
                        "runNetwork",
                        (git, root) -> describe(
                                done -> git.runNetwork(root, done::accept, "fetch", "--dry-run"),
                                (ProcessRunner.Result result) -> result.ok() + "/" + result.err()),
                        "false/Git is not installed"));
    }

    private static GitTestRepo repo(Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("file.txt", "one\ntwo\n");
        repo.commitAll("base");
        return repo;
    }

    @Test
    void withoutGitInstalledEveryQueryStillAnswersWithItsEmptyResult(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repo(dir);
        Path file = repo.root.resolve("file.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitService git = new GitService();
            async.onClose(git::shutdown);
            git.setCommand(NO_SUCH_GIT);
            assertFalse(git.gitAvailable(), "the stand-in command cannot be started");

            for (Query query : queries(file)) {
                assertEquals(query.empty(), async.await(query.ask().apply(git, repo.root)), query.name());
            }
            CompletableFuture<ProcessRunner.Result> cloned = new CompletableFuture<>();
            git.clone("https://example.invalid/never-contacted.git", dir.resolve("clone"), cloned::complete);
            assertEquals("Git is not installed", async.await(cloned).err());
            assertFalse(Files.exists(dir.resolve("clone")), "nothing was created");
            CompletableFuture<String> version = new CompletableFuture<>();
            git.version(version::complete);
            assertEquals("", String.valueOf(async.await(version)).replace("null", ""), "no version to report");
        }
    }

    @Test
    void withoutARepositoryEveryQueryStillAnswersWithItsEmptyResult(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("file.txt"), "not in any repository\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitService git = new GitService();
            async.onClose(git::shutdown);
            for (Query query : queries(file)) {
                assertEquals(query.empty(), async.await(query.ask().apply(git, null)), query.name());
            }
        }
    }

    /** A revision name is repository data; one that starts with a dash is never passed to git as an argument. */
    @Test
    void aRevisionThatLooksLikeAnOptionIsNeverRun(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repo(dir);
        Path root = repo.root;
        String evil = "--output=" + dir.resolve("written-by-git.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitService git = new GitService();
            async.onClose(git::shutdown);

            assertEquals(
                    "Unsafe revision name: " + evil,
                    async.await(describe(
                            done -> git.workingTreeDiff(root, root, evil, done::accept),
                            GitService.WorkingTreeDiff::error)));
            assertEquals(
                    "Unsafe revision name: " + evil + " HEAD",
                    async.await(describe(
                            done -> git.diffFiles(root, evil, "HEAD", done::accept),
                            GitService.WorkingTreeDiff::error)));
            assertEquals(
                    "Unsafe revision name: " + evil,
                    async.await(describe(
                            done -> git.refDiff(root, "HEAD", evil, done::accept), GitService.WorkingTreeDiff::error)));
            assertEquals(
                    "Unsafe revision name: " + evil,
                    async.await(describe(
                            done -> git.refDiff(root, evil, "HEAD", done::accept), GitService.WorkingTreeDiff::error)));
            assertEquals(
                    "true",
                    async.await(describe(
                            done -> git.commitDetails(root, evil, done::accept),
                            (GitLog.Details details) -> String.valueOf(details == null))));
            assertEquals(
                    "size=0",
                    async.await(describe(
                            done -> git.commitFiles(root, evil, done::accept), GitServiceAnswersFxTest::listOrNull)));
            assertEquals(
                    "size=0",
                    async.await(describe(
                            done -> git.stashFiles(root, evil, done::accept), GitServiceAnswersFxTest::listOrNull)));
            assertEquals(
                    "false",
                    async.await(describe(
                            done -> git.commitsLeftBehind(root, evil, false, done::accept),
                            (GitService.LeftBehind left) -> String.valueOf(left.known()))));
            assertEquals(
                    "-1",
                    async.await(describe(
                            done -> git.unmergedCount(root, evil, done::accept),
                            (Integer count) -> String.valueOf(count))));
            assertEquals(
                    "size=0",
                    async.await(describe(
                            done -> git.commitMessagesSince(root, evil, 5, done::accept),
                            GitServiceAnswersFxTest::listOrNull)));
            assertEquals(
                    "size=0",
                    async.await(describe(
                            done -> git.commitMessagesSince(root, " ", 5, done::accept),
                            GitServiceAnswersFxTest::listOrNull)));
            assertEquals(
                    "size=0",
                    async.await(describe(
                            done -> git.commitMessagesSince(root, null, 5, done::accept),
                            GitServiceAnswersFxTest::listOrNull)));
            assertEquals(
                    "invalid revision",
                    async.await(
                            describe(done -> git.commitPatch(root, evil, done::accept), GitService.PatchText::error)));
            assertFalse(Files.exists(dir.resolve("written-by-git.txt")), "git was never handed the option");
        }
    }

    @Test
    void theCommitBoxQueriesDescribeHeadTheStagedChangesAndTheTemplate(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repo(dir);
        Path root = repo.root;
        Path template = Files.writeString(dir.resolve("template.txt"), "Subject\n\n; why\n");
        repo.git("config", "commit.template", template.toString());
        repo.git("config", "core.commentChar", ";");
        repo.write("file.txt", "one\ntwo\nthree\n");
        repo.git("add", "file.txt");
        repo.write("file.txt", "one\ntwo\nthree\nfour\nfive\n");
        repo.write("untracked.txt", "new\n");
        Files.createDirectories(root.resolve("nested"));
        repo.write("nested/also-new.txt", "new\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitService git = new GitService();
            async.onClose(git::shutdown);

            CompletableFuture<GitService.HeadCommit> head = new CompletableFuture<>();
            git.headCommit(root, head::complete);
            GitService.HeadCommit commit = async.await(head);
            assertEquals(repo.git("rev-parse", "HEAD").text().strip(), commit.hash());
            assertEquals("base", commit.subject());
            assertEquals(List.of(), commit.parents(), "the first commit has no parent");
            assertEquals("", commit.upstream());
            assertFalse(commit.pushed(), "nothing to have been pushed to");

            CompletableFuture<String> staged = new CompletableFuture<>();
            git.stagedDiff(root, staged::complete);
            String diff = async.await(staged);
            assertTrue(diff.contains("+three") && !diff.contains("+four"), diff);

            CompletableFuture<GitNumstat.Changes> counts = new CompletableFuture<>();
            git.lineCounts(root, counts::complete);
            GitNumstat.Changes changes = async.await(counts);
            assertEquals(new GitNumstat.Counts(1, 0, false), changes.staged().get("file.txt"));
            assertEquals(new GitNumstat.Counts(2, 0, false), changes.unstaged().get("file.txt"));

            CompletableFuture<GitService.CommitTemplate> templated = new CompletableFuture<>();
            git.commitTemplate(root, templated::complete);
            GitService.CommitTemplate read = async.await(templated);
            assertEquals("Subject\n\n; why\n", read.text());
            assertEquals(';', read.commentChar());

            CompletableFuture<List<String>> untracked = new CompletableFuture<>();
            git.untrackedFiles(root, List.of(), untracked::complete);
            assertEquals(
                    List.of("nested/also-new.txt", "untracked.txt"),
                    async.await(untracked).stream().sorted().toList());
            CompletableFuture<List<String>> narrowed = new CompletableFuture<>();
            git.untrackedFiles(root, List.of("nested"), narrowed::complete);
            assertEquals(List.of("nested/also-new.txt"), async.await(narrowed));

            CompletableFuture<String> pushRemote = new CompletableFuture<>();
            git.pushRemoteOf(root, "main", pushRemote::complete);
            assertEquals("origin", async.await(pushRemote), "no remote configured: git's own default name");

            // An unborn branch has no HEAD commit to amend, and no history to list.
            GitTestRepo fresh = GitTestRepo.init(Files.createDirectory(dir.resolve("fresh")));
            CompletableFuture<GitService.HeadCommit> none = new CompletableFuture<>();
            git.headCommit(fresh.root, none::complete);
            assertNull(async.await(none));
            CompletableFuture<GitLog.Page> page = new CompletableFuture<>();
            git.logPage(fresh.root, new GitLog.Request(false, null, GitLogQuery.parse(""), 0, 10), page::complete);
            GitLog.Page listed = async.await(page);
            assertEquals(List.of(), listed.entries());
            assertEquals("", listed.error(), "\"no commits yet\" is an empty history, not a failure");
        }
    }

    @Test
    void theBranchQueriesCountWhatAMoveOfHeadLeavesBehindAndWhatABranchAdds(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repo(dir);
        Path root = repo.root;
        String base = repo.git("rev-parse", "HEAD").text().strip();
        repo.write("file.txt", "one\ntwo\nthree\n");
        repo.commitAll("second\n\nWith a body.");
        repo.write("file.txt", "one\ntwo\nthree\nfour\n");
        repo.commitAll("third");
        repo.git("branch", "topic", base);
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitService git = new GitService();
            async.onClose(git::shutdown);

            // Resetting main to base takes its two commits off the only branch that has them.
            CompletableFuture<GitService.LeftBehind> reset = new CompletableFuture<>();
            git.commitsLeftBehind(root, base, true, reset::complete);
            GitService.LeftBehind left = async.await(reset);
            assertTrue(left.known());
            assertEquals(2, left.leaving());
            assertEquals(2, left.unreferenced(), "no other branch or tag keeps them");
            assertFalse(left.hasUpstream());

            // Checking base out leaves main where it is: nothing is lost.
            CompletableFuture<GitService.LeftBehind> checkout = new CompletableFuture<>();
            git.commitsLeftBehind(root, base, false, checkout::complete);
            assertEquals(0, async.await(checkout).unreferenced());

            // Moving to where HEAD already is leaves nothing behind at all.
            CompletableFuture<GitService.LeftBehind> nowhere = new CompletableFuture<>();
            git.commitsLeftBehind(root, "HEAD", true, nowhere::complete);
            GitService.LeftBehind none = async.await(nowhere);
            assertTrue(none.known());
            assertEquals(0, none.leaving());

            CompletableFuture<List<String[]>> since = new CompletableFuture<>();
            git.commitMessagesSince(root, "topic", 10, since::complete);
            List<String[]> messages = async.await(since);
            assertEquals(
                    List.of("third|", "second|With a body."),
                    messages.stream().map(parts -> parts[0] + "|" + parts[1]).toList(),
                    "newest first, subject and body apart");
            CompletableFuture<List<String[]>> capped = new CompletableFuture<>();
            git.commitMessagesSince(root, "topic", 0, capped::complete);
            assertEquals(1, async.await(capped).size(), "a limit below one still lists one commit");

            CompletableFuture<Integer> unmerged = new CompletableFuture<>();
            git.unmergedCount(root, "topic", unmerged::complete);
            assertEquals(0, async.await(unmerged), "topic has nothing main lacks");
            repo.git("checkout", "-q", "topic");
            CompletableFuture<Integer> ahead = new CompletableFuture<>();
            git.unmergedCount(root, "main", ahead::complete);
            assertEquals(2, async.await(ahead));
            CompletableFuture<Integer> missing = new CompletableFuture<>();
            git.unmergedCount(root, "no-such-branch", missing::complete);
            assertEquals(-1, async.await(missing), "git could not count: unknown, not zero");
        }
    }

    @Test
    void aStashListsItsTrackedAndUntrackedFilesAndAStaleEntryIsRefused(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repo(dir);
        Path root = repo.root;
        repo.write("file.txt", "one\ntwo\nstashed edit\n");
        repo.write("brand-new.txt", "untracked\n");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitService git = new GitService();
            async.onClose(git::shutdown);

            CompletableFuture<ProcessRunner.Result> pushed = new CompletableFuture<>();
            git.stashPush(root, new StashOptions("  work in progress  ", true, false, false), pushed::complete);
            assertTrue(async.await(pushed).ok());
            assertEquals("one\ntwo\n", Files.readString(root.resolve("file.txt")));
            assertFalse(Files.exists(root.resolve("brand-new.txt")), "the untracked file went into the stash too");

            CompletableFuture<List<StashParser.StashEntry>> listed = new CompletableFuture<>();
            git.stashList(root, listed::complete);
            List<StashParser.StashEntry> stashes = async.await(listed);
            assertEquals(1, stashes.size());
            StashParser.StashEntry entry = stashes.get(0);
            assertTrue(entry.subject().contains("work in progress"), entry.subject());
            assertFalse(entry.hash().isBlank());

            CompletableFuture<List<GitService.CommitFile>> files = new CompletableFuture<>();
            git.stashFiles(root, entry.ref(), files::complete);
            assertEquals(
                    List.of("?brand-new.txt", "Mfile.txt"),
                    async.await(files).stream()
                            .map(file -> file.status() + file.path())
                            .sorted()
                            .toList());

            // The list the user is looking at is out of date: a second stash took the first one's place.
            repo.write("file.txt", "one\ntwo\nanother edit\n");
            repo.git("stash", "push", "-q", "-m", "newer");
            CompletableFuture<ProcessRunner.Result> stale = new CompletableFuture<>();
            git.runStashMutation(root, entry, stale::complete, "stash", "drop", entry.ref());
            ProcessRunner.Result refused = async.await(stale);
            assertFalse(refused.ok());
            assertEquals(GitService.STASH_MOVED, refused.err());
            assertEquals(
                    2,
                    repo.git("stash", "list").text().lines().count(),
                    "nothing was dropped: stash@{0} is no longer the stash that was listed");

            // An entry from a listing without hashes cannot be checked, and is acted on as named.
            StashParser.StashEntry unchecked = new StashParser.StashEntry(0, "stash@{0}", "main", "newer");
            CompletableFuture<ProcessRunner.Result> dropped = new CompletableFuture<>();
            git.runStashMutation(root, unchecked, dropped::complete, "stash", "drop", "stash@{0}");
            assertTrue(async.await(dropped).ok());
            assertEquals(1, repo.git("stash", "list").text().lines().count());

            // The listed stash is still there under its new position, and applying its bytes restores the work.
            CompletableFuture<ProcessRunner.Result> popped = new CompletableFuture<>();
            git.runStashMutation(root, null, popped::complete, "stash", "pop");
            assertTrue(async.await(popped).ok());
            assertEquals("one\ntwo\nstashed edit\n", Files.readString(root.resolve("file.txt")));
            assertEquals("untracked\n", Files.readString(root.resolve("brand-new.txt")));
        }
    }

    @Test
    void aPatchGivenAsTextIsAppliedToTheIndexAsItsBytes(@TempDir Path dir) throws Exception {
        GitTestRepo repo = repo(dir);
        Path root = repo.root;
        repo.write("file.txt", "one\ntwo\nthree\n");
        String patch = repo.git("diff").text();
        repo.git("checkout", "-q", "--", "file.txt");
        try (AsyncTestScope async = new AsyncTestScope()) {
            GitService git = new GitService();
            async.onClose(git::shutdown);

            CompletableFuture<ProcessRunner.Result> applied = new CompletableFuture<>();
            git.applyPatch(root, patch, true, applied::complete);
            assertTrue(async.await(applied).ok());
            assertEquals("one\ntwo\nthree\n", repo.git("show", ":file.txt").text());
            assertEquals("one\ntwo\n", Files.readString(root.resolve("file.txt")), "the working tree is untouched");

            CompletableFuture<GitService.PatchText> staged = new CompletableFuture<>();
            git.diffPatch(root, true, staged::complete);
            GitService.PatchText text = async.await(staged);
            assertTrue(text.ok());
            assertEquals(new String(text.bytes(), StandardCharsets.UTF_8), text.text());
            assertTrue(text.text().contains("+three"), text.text());

            CompletableFuture<byte[]> bytes = new CompletableFuture<>();
            git.showBytes(root, ":file.txt", bytes::complete);
            assertTrue(Arrays.equals("one\ntwo\nthree\n".getBytes(StandardCharsets.UTF_8), async.await(bytes)));
            CompletableFuture<byte[]> missing = new CompletableFuture<>();
            git.showBytes(root, "HEAD:no-such-file.txt", missing::complete);
            assertEquals(0, async.await(missing).length, "a path the revision does not have is empty, not an error");
        }
    }
}
