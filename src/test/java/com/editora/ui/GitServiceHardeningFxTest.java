package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.diff.BlobRewrite;
import com.editora.git.GitService;
import com.editora.git.GitService.CommitFile;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GitService} against a real repository: what it may run, what it must not, and what it must not break. */
@Tag("fx")
class GitServiceHardeningFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Assumptions.assumeFalse(GitTestRepo.windows(), "uses /bin/sh scripts and named pipes");
    }

    /** Runs one asynchronous service call and returns what it posted on the FX thread. */
    private static <T> T call(AsyncTestScope async, String what, Consumer<Consumer<T>> invocation) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        invocation.accept(result -> {
            value.set(result);
            done.countDown();
        });
        async.await(done, what);
        return value.get();
    }

    // --- S1: automatic git must not run programs named by the folder's own config -----------------------

    @Test
    void backgroundCommandsDoNotRunProgramsNamedByRepositoryConfig(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\ntwo\n");
        repo.write(".gitattributes", "*.txt diff=evil\n");
        repo.commitAll("init");
        repo.write("notes.txt", "one\nTWO\n");

        Path fsmonitorRan = dir.resolve("fsmonitor-ran");
        Path externalDiffRan = dir.resolve("external-diff-ran");
        Path textconvRan = dir.resolve("textconv-ran");
        repo.git(
                "config",
                "core.fsmonitor",
                GitTestRepo.script(dir.resolve("fsmonitor.sh"), "touch '" + fsmonitorRan + "'")
                        .toString());
        repo.git(
                "config",
                "diff.external",
                GitTestRepo.script(dir.resolve("extdiff.sh"), "touch '" + externalDiffRan + "'")
                        .toString());
        repo.git(
                "config",
                "diff.evil.textconv",
                GitTestRepo.script(dir.resolve("textconv.sh"), "touch '" + textconvRan + "'\ncat \"$1\"")
                        .toString());

        // Control: the configuration really is live. Plain git, as Editora used to invoke it, runs all three.
        repo.tryGit("status", "--porcelain=v2", "--branch");
        assertTrue(Files.exists(fsmonitorRan), "plain `git status` runs core.fsmonitor");
        repo.tryGit("diff", "HEAD", "--", "notes.txt");
        assertTrue(Files.exists(externalDiffRan), "plain `git diff` runs diff.external");
        repo.tryGit("diff", "--no-ext-diff", "HEAD", "--", "notes.txt");
        assertTrue(Files.exists(textconvRan), "plain `git diff` runs the textconv driver");
        Files.delete(fsmonitorRan);
        Files.delete(externalDiffRan);
        Files.delete(textconvRan);

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            // Everything a tab activation, a diff tab, the log, blame and the review surfaces run by themselves.
            GitService.RepoState state =
                    call(async, "refresh", (Consumer<GitService.RepoState> cb) -> service.refresh(file, file, cb));
            assertTrue(state.isRepo());
            assertFalse(state.changes().isEmpty(), "the gutter diff still reports the modified line");
            assertTrue(call(async, "status", (Consumer<GitService.RepoState> cb) -> service.status(file, cb))
                    .isRepo());
            assertFalse(call(async, "diff", (Consumer<GitService.GitDiff> cb) -> service.diff(repo.root, file, cb))
                    .changes()
                    .isEmpty());
            assertArrayEquals(
                    "one\ntwo\n".getBytes(StandardCharsets.UTF_8),
                    call(async, "show", (Consumer<byte[]> cb) -> service.showBytes(repo.root, "HEAD:notes.txt", cb)));
            assertEquals(
                    1,
                    call(async, "log", (Consumer<List<GitService.Commit>> cb) -> service.log(repo.root, file, 10, cb))
                            .size());
            assertEquals(
                    2,
                    call(
                                    async,
                                    "blame",
                                    (Consumer<List<com.editora.git.BlameParser.BlameLine>> cb) ->
                                            service.blame(repo.root, file, cb))
                            .size());
            call(async, "branches", (Consumer<GitService.Branches> cb) -> service.branches(repo.root, cb));
            call(async, "tags", (Consumer<List<String>> cb) -> service.tags(repo.root, cb));
            call(
                    async,
                    "stash list",
                    (Consumer<List<com.editora.git.StashParser.StashEntry>> cb) -> service.stashList(repo.root, cb));
            String head = repo.git("rev-parse", "HEAD").text().strip();
            assertFalse(call(
                            async,
                            "commit files",
                            (Consumer<List<CommitFile>> cb) -> service.commitFiles(repo.root, head, cb))
                    .isEmpty());
            assertTrue(call(
                            async,
                            "folder diff",
                            (Consumer<GitService.WorkingTreeDiff> cb) ->
                                    service.workingTreeDiff(repo.root, repo.root, "HEAD", cb))
                    .ok());
            repo.git("-c", "core.fsmonitor=false", "add", "notes.txt");
            Files.deleteIfExists(fsmonitorRan);
            String staged = call(async, "staged diff", (Consumer<String> cb) -> service.stagedDiff(repo.root, cb));
            assertTrue(staged.contains("+TWO"), staged);

            assertFalse(Files.exists(fsmonitorRan), "core.fsmonitor from .git/config must not run");
            assertFalse(Files.exists(externalDiffRan), "diff.external from .git/config must not run");
            assertFalse(Files.exists(textconvRan), "a textconv driver from .git/config must not run");
        }
    }

    /**
     * A partial-clone repository fetches a missing blob the first time a command needs it, through the
     * transport program its own config names. Background reads must fail instead of running that program.
     */
    @Test
    void backgroundReadsDoNotFetchMissingObjectsThroughTheRepositorysTransport(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\ntwo\n");
        repo.commitAll("init");
        repo.write("notes.txt", "one\nTWO\n"); // the working tree differs, so every diff needs the HEAD blob

        // Make the HEAD blob a promised-but-absent object of a partial clone.
        String blob = repo.git("rev-parse", "HEAD:notes.txt").text().strip();
        Files.delete(
                repo.root.resolve(".git/objects").resolve(blob.substring(0, 2)).resolve(blob.substring(2)));
        Path uploadPackRan = dir.resolve("uploadpack-ran");
        Path sshRan = dir.resolve("ssh-ran");
        repo.git("config", "core.repositoryformatversion", "1");
        repo.git("config", "extensions.partialClone", "origin");
        repo.git("config", "remote.origin.promisor", "true");
        repo.git("config", "remote.origin.partialclonefilter", "blob:none");
        repo.git("config", "remote.origin.url", dir.resolve("no-such-remote").toString());
        repo.git("config", "remote.origin.uploadpack", "touch '" + uploadPackRan + "'; false");
        repo.git("config", "core.sshCommand", "touch '" + sshRan + "'; false");

        // Control: the trap is live. The gutter diff as Editora ran it before (no lazy-fetch suppression)
        // starts the local transport; with an ssh URL it starts core.sshCommand.
        repo.tryGit("diff", "--no-ext-diff", "--no-textconv", "-U0", "HEAD", "--", "notes.txt");
        assertTrue(Files.exists(uploadPackRan), "a plain `git diff` lazily fetches through remote.origin.uploadpack");
        repo.git("config", "remote.origin.url", "ssh://user@example.invalid/x.git");
        repo.tryGit("show", "HEAD:notes.txt");
        assertTrue(Files.exists(sshRan), "a plain `git show` lazily fetches through core.sshCommand");
        Files.delete(uploadPackRan);
        Files.delete(sshRan);

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            for (String url : List.of(
                    "ssh://user@example.invalid/x.git",
                    dir.resolve("no-such-remote").toString())) {
                repo.git("config", "remote.origin.url", url);
                GitService.RepoState state =
                        call(async, "refresh", (Consumer<GitService.RepoState> cb) -> service.refresh(file, file, cb));
                assertTrue(state.isRepo(), "status does not need the blob and still works");
                call(async, "diff", (Consumer<GitService.GitDiff> cb) -> service.diff(repo.root, file, cb));
                assertArrayEquals(
                        new byte[0],
                        call(
                                async,
                                "show",
                                (Consumer<byte[]> cb) -> service.showBytes(repo.root, "HEAD:notes.txt", cb)),
                        "the absent blob reads as not found");
                call(
                        async,
                        "blame",
                        (Consumer<List<com.editora.git.BlameParser.BlameLine>> cb) ->
                                service.blame(repo.root, file, cb));
                call(async, "log", (Consumer<List<GitService.Commit>> cb) -> service.log(repo.root, file, 10, cb));
                String head = repo.git("rev-parse", "HEAD").text().strip();
                call(
                        async,
                        "commit files",
                        (Consumer<List<CommitFile>> cb) -> service.commitFiles(repo.root, head, cb));
                call(
                        async,
                        "folder diff",
                        (Consumer<GitService.WorkingTreeDiff> cb) ->
                                service.workingTreeDiff(repo.root, repo.root, "HEAD", cb));
                call(async, "staged diff", (Consumer<String> cb) -> service.stagedDiff(repo.root, cb));

                assertFalse(Files.exists(uploadPackRan), "remote.origin.uploadpack must not run for " + url);
                assertFalse(Files.exists(sshRan), "core.sshCommand must not run for " + url);
            }
        }
    }

    @Test
    void userInitiatedCommitsStillRunTheUsersHooks(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        Path hookRan = dir.resolve("pre-commit-ran");
        GitTestRepo.script(repo.root.resolve(".git/hooks/pre-commit"), "touch '" + hookRan + "'");
        repo.write("notes.txt", "two\n");
        repo.git("add", "notes.txt");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            ProcessRunner.Result result = call(
                    async,
                    "commit",
                    (Consumer<ProcessRunner.Result> cb) ->
                            service.runWorktreeMutation(repo.root, cb, "commit", "-m", "second"));

            assertTrue(result.ok(), result.message());
            assertTrue(Files.exists(hookRan), "a commit the user asked for runs their pre-commit hook");
            assertEquals("second", repo.git("log", "-1", "--format=%s").text().strip());
        }
    }

    // --- G11: revisions from repository data, prompts, lanes ---------------------------------------------

    @Test
    void aTagNamedLikeAnOptionCannotBecomeOne(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        repo.write("notes.txt", "two\n");
        // Porcelain refuses such a name; a hand-made or hostile repository can still contain the ref.
        repo.git("update-ref", "refs/tags/--output=written-by-git.txt", "HEAD");
        Path written = repo.root.resolve("written-by-git.txt");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            List<String> tags = call(async, "tags", (Consumer<List<String>> cb) -> service.tags(repo.root, cb));
            assertEquals(
                    List.of("--output=written-by-git.txt"), tags, "the hostile tag is what the picker would offer");

            GitService.WorkingTreeDiff diff = call(
                    async,
                    "folder diff",
                    (Consumer<GitService.WorkingTreeDiff> cb) ->
                            service.workingTreeDiff(repo.root, repo.root, tags.get(0), cb));
            GitService.BlobResult blob = call(
                    async,
                    "show",
                    (Consumer<GitService.BlobResult> cb) ->
                            service.showBlob(repo.root, tags.get(0) + ":notes.txt", cb));

            assertFalse(diff.ok(), "the comparison is refused, not run with the name as an option");
            assertFalse(blob.found());
            assertFalse(Files.exists(written), "git must not have been told to --output= into the working tree");
        }
    }

    @Test
    void aRunningNetworkCommandCanBeCancelled(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        Path started = GitTestRepo.fifo(dir.resolve("fetch-started"));
        // A stand-in git whose `fetch` never comes back by itself: only a kill ends it.
        Path wrapper = GitTestRepo.script(dir.resolve("git-wrapper"), """
                if [ "$1" = "fetch" ]; then
                  echo started > '%s'
                  sleep 120
                fi
                exec git "$@"
                """.formatted(started));
        Assumptions.assumeFalse(
                wrapper.toString().matches(".*\\s.*"), "the git command setting is whitespace-tokenized");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(() -> {
                service.shutdown();
                service.setCommand(""); // the command is app-wide state: restore PATH git for other tests
            });
            service.setCommand(wrapper.toString());
            assertFalse(service.cancelNetworkCommand(), "nothing is running yet");

            CountDownLatch fetched = new CountDownLatch(1);
            AtomicReference<ProcessRunner.Result> fetchResult = new AtomicReference<>();
            service.runNetwork(
                    repo.root,
                    r -> {
                        fetchResult.set(r);
                        fetched.countDown();
                    },
                    "fetch");
            assertEquals(List.of("started"), Files.readAllLines(started), "the fetch is now parked inside git");

            assertTrue(service.cancelNetworkCommand());
            async.await(fetched, "the cancelled fetch reporting back");
            assertTrue(fetchResult.get().cancelled(), fetchResult.get().toString());
            assertFalse(service.cancelNetworkCommand(), "and nothing is left to cancel");
        }
    }

    @Test
    void everyInvocationDisablesTerminalPromptsAndNetworkWorkDoesNotBlockStatus(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        Path log = dir.resolve("invocations.log");
        Path started = GitTestRepo.fifo(dir.resolve("fetch-started"));
        Path release = GitTestRepo.fifo(dir.resolve("fetch-release"));
        // A stand-in git: records the prompt setting of every call, and parks `fetch` until released.
        Path wrapper = GitTestRepo.script(dir.resolve("git-wrapper"), """
                echo "${GIT_TERMINAL_PROMPT-unset} $*" >> '%s'
                if [ "$1" = "fetch" ]; then
                  echo started > '%s'
                  read go < '%s'
                fi
                exec git "$@"
                """.formatted(log, started, release));
        Assumptions.assumeFalse(
                wrapper.toString().matches(".*\\s.*"), "the git command setting is whitespace-tokenized");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(() -> {
                service.shutdown();
                service.setCommand(""); // the command is app-wide state: restore PATH git for other tests
            });
            service.setCommand(wrapper.toString());

            CountDownLatch fetched = new CountDownLatch(1);
            AtomicReference<ProcessRunner.Result> fetchResult = new AtomicReference<>();
            service.runNetwork(
                    repo.root,
                    r -> {
                        fetchResult.set(r);
                        fetched.countDown();
                    },
                    "fetch");
            assertEquals(List.of("started"), Files.readAllLines(started), "the fetch is now parked inside git");

            // With one shared executor this status sat behind the fetch until the network timeout.
            assertTrue(call(
                            async,
                            "status during a fetch",
                            (Consumer<GitService.RepoState> cb) -> service.status(file, cb))
                    .isRepo());
            assertEquals(1, fetched.getCount(), "the fetch is still running");

            async.start("release-fetch", () -> Files.writeString(release, "go\n"));
            async.await(fetched, "fetch completion");
            assertTrue(fetchResult.get().ok(), fetchResult.get().message());
            call(
                    async,
                    "commit",
                    (Consumer<ProcessRunner.Result> cb) ->
                            service.runWorktreeMutation(repo.root, cb, "commit", "--allow-empty", "-m", "empty"));

            List<String> invocations = Files.readAllLines(log);
            assertTrue(invocations.size() >= 4, invocations.toString());
            for (String invocation : invocations) {
                assertTrue(invocation.startsWith("0 "), "GIT_TERMINAL_PROMPT=0 on every call, got: " + invocation);
            }
            assertTrue(invocations.stream().anyMatch(i -> i.endsWith(" fetch --progress")), invocations.toString());
        }
    }

    // --- G1: an in-flight mutation survives window close --------------------------------------------------

    @Test
    void closingTheWindowLetsARunningCommitFinish(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        Path started = GitTestRepo.fifo(dir.resolve("hook-started"));
        Path release = GitTestRepo.fifo(dir.resolve("hook-release"));
        GitTestRepo.script(
                repo.root.resolve(".git/hooks/pre-commit"),
                "echo started > '" + started + "'\nread go < '" + release + "'\nexit 0");
        repo.write("notes.txt", "two\n");
        repo.git("add", "notes.txt");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            CountDownLatch committed = new CountDownLatch(1);
            AtomicReference<ProcessRunner.Result> result = new AtomicReference<>();
            service.runWorktreeMutation(
                    repo.root,
                    r -> {
                        result.set(r);
                        committed.countDown();
                    },
                    "commit",
                    "-m",
                    "slow hook");
            assertEquals(List.of("started"), Files.readAllLines(started), "the commit is inside its pre-commit hook");

            service.shutdown(); // the window closes while the hook is still running
            async.start("release-hook", () -> Files.writeString(release, "go\n"));

            async.await(committed, "the commit that was running at close");
            assertTrue(
                    result.get().ok(),
                    "the commit must finish, not be killed mid-hook: "
                            + result.get().message());
            assertEquals(
                    "slow hook", repo.git("log", "-1", "--format=%s").text().strip());
            assertFalse(Files.exists(repo.root.resolve(".git/index.lock")));
        }
    }

    // --- G2: a lock that is not ours ---------------------------------------------------------------------

    @Test
    void anotherProcessIndexLockIsReportedBusyAndLeftInPlace(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("notes.txt", "one\ntwo\n");
        repo.commitAll("init");
        byte[] index = "one\ntwo\n".getBytes(StandardCharsets.UTF_8);
        Path lock = Files.writeString(repo.root.resolve(".git/index.lock"), "held by another git process");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            GitService.BlobResult expected = new GitService.BlobResult(true, index);
            ProcessRunner.Result blob = call(
                    async,
                    "stage blob",
                    (Consumer<ProcessRunner.Result> cb) -> service.stageBlob(
                            repo.root, "notes.txt", expected, "ONE\ntwo\n".getBytes(StandardCharsets.UTF_8), cb));

            assertFalse(blob.ok());
            assertEquals("The Git index is busy", blob.message());
            assertTrue(Files.exists(lock), "a lock this call did not create must not be deleted");
            assertEquals("held by another git process", Files.readString(lock));
            Files.delete(lock);
            assertEquals("one\ntwo\n", repo.git("show", ":notes.txt").text(), "nothing was staged");
        }
    }

    // --- G5: staging the desired blob bytes --------------------------------------------------------------

    @Test
    void aHunkOfACrlfFileIsStagedWithItsLineEndingsIntact(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        byte[] committed = "one\r\ntwo\r\nthree\r\n".getBytes(StandardCharsets.UTF_8);
        Path file = repo.write("win.txt", committed);
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        repo.commitAll("init");
        repo.write("win.txt", "ONE\r\ntwo\r\nTHREE\r\n");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            GitService.BlobResult index = call(
                    async,
                    "index blob",
                    (Consumer<GitService.BlobResult> cb) -> service.showBlob(repo.root, ":win.txt", cb));
            assertArrayEquals(committed, index.bytes());
            // The view's text for "stage the first hunk only": index text with line 1 taken from the file.
            byte[] desired = BlobRewrite.rewrite(
                    index.bytes(),
                    StandardCharsets.UTF_8,
                    new byte[0],
                    "one\r\ntwo\r\nthree\r\n",
                    "ONE\r\ntwo\r\nthree\r\n");

            ProcessRunner.Result result = call(
                    async,
                    "stage blob",
                    (Consumer<ProcessRunner.Result> cb) -> service.stageBlob(repo.root, "win.txt", index, desired, cb));

            assertTrue(result.ok(), result.message());
            assertArrayEquals(
                    "ONE\r\ntwo\r\nthree\r\n".getBytes(StandardCharsets.UTF_8),
                    repo.git("show", ":win.txt").out(),
                    "only the chosen hunk is staged, CRLF preserved");
            assertTrue(
                    repo.git("ls-files", "-s", "--", "win.txt").text().startsWith("100755 "),
                    "the entry keeps its executable mode");
            assertArrayEquals(
                    "ONE\r\ntwo\r\nTHREE\r\n".getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(file),
                    "the file is untouched");
            assertFalse(Files.exists(repo.root.resolve(".git/index.lock")));
        }
    }

    @Test
    void aLatin1BlobIsStagedAsLatin1AndAStaleSnapshotIsRefused(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        byte[] committed = "café\nnaïve\n".getBytes(StandardCharsets.ISO_8859_1);
        repo.write("legacy.txt", committed);
        repo.commitAll("init");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            GitService.BlobResult index = new GitService.BlobResult(true, committed);
            byte[] desired = BlobRewrite.rewrite(
                    committed, StandardCharsets.ISO_8859_1, new byte[0], "café\nnaïve\n", "café\nNAÏVE\n");

            ProcessRunner.Result result = call(
                    async,
                    "stage blob",
                    (Consumer<ProcessRunner.Result> cb) ->
                            service.stageBlob(repo.root, "legacy.txt", index, desired, cb));

            assertTrue(result.ok(), result.message());
            byte[] staged = repo.git("show", ":legacy.txt").out();
            assertArrayEquals("café\nNAÏVE\n".getBytes(StandardCharsets.ISO_8859_1), staged);

            // The index moved on (the stage above); a second action from the same displayed snapshot is stale.
            ProcessRunner.Result stale = call(
                    async,
                    "stale stage",
                    (Consumer<ProcessRunner.Result> cb) ->
                            service.stageBlob(repo.root, "legacy.txt", index, committed, cb));
            assertFalse(stale.ok());
            assertArrayEquals(staged, repo.git("show", ":legacy.txt").out());
        }
    }

    @Test
    void stagingAHunkOfAnUntrackedFileCreatesItsIndexEntry(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("new.txt", "new contents\n");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            ProcessRunner.Result result = call(
                    async,
                    "stage blob",
                    (Consumer<ProcessRunner.Result> cb) -> service.stageBlob(
                            repo.root,
                            "new.txt",
                            new GitService.BlobResult(false, new byte[0]),
                            "new contents\n".getBytes(StandardCharsets.UTF_8),
                            cb));

            assertTrue(result.ok(), result.message());
            assertEquals("new contents\n", repo.git("show", ":new.txt").text());
            assertTrue(repo.git("ls-files", "-s", "--", "new.txt").text().startsWith("100644 "));
        }
    }

    // --- G7: the Git Log file list ------------------------------------------------------------------------

    @Test
    void commitFileListHandlesNonAsciiNamesRootCommitsAndMerges(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("café notes.txt", "one\n");
        repo.commitAll("root");
        String root = repo.git("rev-parse", "HEAD").text().strip();
        repo.git("checkout", "-q", "-b", "side");
        repo.write("side.txt", "side\n");
        repo.commitAll("side");
        repo.git("checkout", "-q", "main");
        repo.write("café notes.txt", "one\ntwo\n");
        repo.commitAll("main");
        String main = repo.git("rev-parse", "HEAD").text().strip();
        repo.git("merge", "-q", "--no-ff", "--no-edit", "side");
        String merge = repo.git("rev-parse", "HEAD").text().strip();

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            // Without -z the name came back as the C-quoted "caf\303\251 notes.txt", which is not a path.
            assertEquals(
                    List.of(new CommitFile('M', "café notes.txt", null)),
                    call(
                            async,
                            "modified",
                            (Consumer<List<CommitFile>> cb) -> service.commitFiles(repo.root, main, cb)));
            // Without --root the first commit listed nothing.
            assertEquals(
                    List.of(new CommitFile('A', "café notes.txt", null)),
                    call(async, "root", (Consumer<List<CommitFile>> cb) -> service.commitFiles(repo.root, root, cb)));
            // A merge lists what it brought in relative to its first parent — what the file diff then shows.
            assertEquals(
                    List.of(new CommitFile('A', "side.txt", null)),
                    call(async, "merge", (Consumer<List<CommitFile>> cb) -> service.commitFiles(repo.root, merge, cb)));
        }
    }
}
