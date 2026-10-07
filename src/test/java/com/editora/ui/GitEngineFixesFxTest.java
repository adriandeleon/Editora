package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.git.BlameParser;
import com.editora.git.GitService;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitService} against real repositories for the engine findings of the Git review (E1–E6, W13): what a
 * cut-off read becomes, where a first push goes, the mode of a newly staged file, what the root and
 * availability caches forget, what a refused repository reports, and stopping a local command.
 */
@Tag("fx")
class GitEngineFixesFxTest {

    private static final long SECOND = 1_000_000_000L;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Assumptions.assumeFalse(GitTestRepo.windows(), "uses /bin/sh scripts, named pipes and POSIX modes");
    }

    @AfterEach
    void restoreTheGitCommand() {
        new GitService().setCommand(""); // the command is app-wide state
    }

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

    private static GitService.RepoState refresh(AsyncTestScope async, GitService service, Path context)
            throws Exception {
        return call(async, "refresh", (Consumer<GitService.RepoState> cb) -> service.refresh(context, null, cb));
    }

    private static long count(Path log, String subcommand) throws Exception {
        return Files.exists(log)
                ? Files.readAllLines(log).stream()
                        .filter(line -> (" " + line + " ").contains(" " + subcommand + " "))
                        .count()
                : 0;
    }

    /** A stand-in git that appends each invocation's arguments to {@code log}, then runs {@code extra}. */
    private static Path loggingGit(Path dir, Path log, String extra) throws Exception {
        Path wrapper = GitTestRepo.script(dir.resolve("git-wrapper"), """
                echo "$*" >> '%s'
                %s
                exec git "$@"
                """.formatted(log, extra));
        Assumptions.assumeFalse(wrapper.toString().matches(".*\\s.*"), "the wrapper path must be one token");
        return wrapper;
    }

    // --- E1: a capture cut off at the limit is a failure, never a smaller answer ---------------------------

    @Test
    void aBlameLargerThanTheCaptureIsNotShownAndNotCached(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\ntwo\n");
        repo.commitAll("init");
        String head = repo.git("rev-parse", "HEAD").text().strip();
        // Well-formed blame blocks, 11 MB of them: more than the runner captures.
        Path wrapper = loggingGit(dir, dir.resolve("log"), """
                case " $* " in *" blame "*)
                  yes '%s 1 1 1
                author A
                filename notes.txt
                \ttext' | head -c 11000000
                  exit 0;;
                esac
                """.formatted(head));

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            service.setCommand(wrapper.toString());
            for (int attempt = 1; attempt <= 2; attempt++) {
                List<BlameParser.BlameLine> lines = call(
                        async,
                        "blame",
                        (Consumer<List<BlameParser.BlameLine>> cb) -> service.blameLatest(repo.root, file, cb));
                assertTrue(lines.isEmpty(), "blame for part of the file must not be shown as the file's blame");
                assertEquals(attempt, service.blameRunsForTest(), "and a cut-off result is never cached");
            }
        }
    }

    @Test
    void aStatusLargerThanTheCaptureIsReportedNotShownInPart(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        Path wrapper = loggingGit(dir, dir.resolve("log"), """
                case " $* " in *" status "*)
                  echo '# branch.head main'
                  yes '? some/untracked/file.txt' | head -c 11000000
                  exit 0;;
                esac
                """);

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            service.setCommand(wrapper.toString());
            GitService.RepoState state = refresh(async, service, file);
            assertFalse(state.isRepo(), "the entries that happened to fit are not the change list");
            assertTrue(state.refused());
            assertEquals("git output is too large to read", state.refusal());
        }
    }

    @Test
    void realBlameStillAnnotatesEveryLineWithTheCompactFormat(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\ntwo\nthree\n");
        repo.commitAll("first");
        repo.write("notes.txt", "one\nTWO\nthree\n");
        repo.commitAll("second");
        repo.write("notes.txt", "one\nTWO\nthree\nfour\n");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            List<BlameParser.BlameLine> lines = call(
                    async, "blame", (Consumer<List<BlameParser.BlameLine>> cb) -> service.blame(repo.root, file, cb));
            assertEquals(4, lines.size());
            // Lines 1 and 3 are the same commit, not adjacent: --porcelain describes it once.
            assertEquals("first", lines.get(0).summary());
            assertEquals("second", lines.get(1).summary());
            assertEquals("first", lines.get(2).summary());
            assertEquals(lines.get(0).hash(), lines.get(2).hash());
            assertEquals("Editora Test", lines.get(2).author());
            assertTrue(lines.get(2).epochSeconds() > 0);
            assertEquals("notes.txt", lines.get(2).path());
            assertTrue(lines.get(3).uncommitted());
        }
    }

    // --- E2 / E11: the first push goes to the configured remote --------------------------------------------

    private static Path bareRemote(Path dir, String name) throws Exception {
        Path bare = dir.resolve(name + ".git");
        Process init = new ProcessBuilder("git", "init", "-q", "--bare", bare.toString())
                .inheritIO()
                .start();
        assertEquals(0, init.waitFor());
        return bare;
    }

    private static boolean hasBranch(Path bare, String branch) throws Exception {
        Process p = new ProcessBuilder(
                        "git", "--git-dir", bare.toString(), "rev-parse", "--verify", "-q", "refs/heads/" + branch)
                .start();
        p.getInputStream().readAllBytes();
        return p.waitFor() == 0;
    }

    private static ProcessRunner.Result push(AsyncTestScope async, GitService service, GitTestRepo repo, String branch)
            throws Exception {
        // Exactly what the UI does: the two-argument form on the FX thread, handed to runNetwork.
        String[] args = GitService.pushArgs(branch, "");
        return call(async, "push", (Consumer<ProcessRunner.Result> cb) -> service.runNetwork(repo.root, cb, args));
    }

    @Test
    void theFirstPushUsesTheSoleRemoteWhateverItIsCalled(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        Path upstream = bareRemote(dir, "upstream");
        repo.git("remote", "add", "upstream", upstream.toString());

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            ProcessRunner.Result result = push(async, service, repo, "main");
            assertTrue(result.ok(), result.message()); // was: 'origin' does not appear to be a git repository
            assertTrue(hasBranch(upstream, "main"));
            assertEquals(
                    "upstream", repo.git("config", "branch.main.remote").text().strip());
            assertEquals(
                    "refs/heads/main",
                    repo.git("config", "branch.main.merge").text().strip());
        }
    }

    @Test
    void theFirstPushHonoursPushDefaultAndThePerBranchPushRemote(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        Path origin = bareRemote(dir, "origin");
        Path fork = bareRemote(dir, "fork");
        Path mine = bareRemote(dir, "mine");
        repo.git("remote", "add", "origin", origin.toString());
        repo.git("remote", "add", "fork", fork.toString());
        repo.git("remote", "add", "mine", mine.toString());

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            // Nothing configured: origin, because it exists.
            repo.git("checkout", "-q", "-b", "plain");
            assertTrue(push(async, service, repo, "plain").ok());
            assertTrue(hasBranch(origin, "plain"));

            repo.git("config", "remote.pushDefault", "fork");
            repo.git("checkout", "-q", "-b", "feature");
            ProcessRunner.Result result = push(async, service, repo, "feature");
            assertTrue(result.ok(), result.message());
            assertTrue(hasBranch(fork, "feature"), "remote.pushDefault names the fork");
            assertFalse(hasBranch(origin, "feature"), "the branch used to be created on origin");

            repo.git("checkout", "-q", "-b", "topic");
            repo.git("config", "branch.topic.pushRemote", "mine");
            assertTrue(push(async, service, repo, "topic").ok());
            assertTrue(hasBranch(mine, "topic"), "branch.<name>.pushRemote outranks remote.pushDefault");
            assertFalse(hasBranch(fork, "topic"));
        }
    }

    // --- E3: a file new to the index is staged with the working file's mode --------------------------------

    @Test
    void stagingANewExecutableOrSymlinkKeepsItsMode(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("readme.txt", "hello\n");
        repo.commitAll("init");
        byte[] script = "#!/bin/sh\necho hi\n".getBytes(StandardCharsets.UTF_8);
        Path run = repo.write("run.sh", script);
        Files.setPosixFilePermissions(run, PosixFilePermissions.fromString("rwxr-xr-x"));
        repo.write("plain.txt", "plain\n");
        Files.createSymbolicLink(repo.root.resolve("link"), Path.of("readme.txt"));
        Path untrusted = repo.write("untrusted.sh", script);
        Files.setPosixFilePermissions(untrusted, PosixFilePermissions.fromString("rwxr-xr-x"));

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            GitService.BlobResult absent = new GitService.BlobResult(false, new byte[0]);
            assertTrue(call(
                            async,
                            "stage script",
                            (Consumer<ProcessRunner.Result> cb) ->
                                    service.stageBlob(repo.root, "run.sh", absent, script, cb))
                    .ok());
            assertTrue(
                    repo.git("ls-files", "-s", "--", "run.sh").text().startsWith("100755 "),
                    "a new script was committed without its executable bit");

            assertTrue(call(
                            async,
                            "stage plain",
                            (Consumer<ProcessRunner.Result> cb) -> service.stageBlob(
                                    repo.root, "plain.txt", absent, "plain\n".getBytes(StandardCharsets.UTF_8), cb))
                    .ok());
            assertTrue(repo.git("ls-files", "-s", "--", "plain.txt").text().startsWith("100644 "));

            // The diff viewer read "hello\n" through the link; what must be staged is the link itself.
            assertTrue(call(
                            async,
                            "stage link",
                            (Consumer<ProcessRunner.Result> cb) -> service.stageBlob(
                                    repo.root, "link", absent, "hello\n".getBytes(StandardCharsets.UTF_8), cb))
                    .ok());
            assertTrue(repo.git("ls-files", "-s", "--", "link").text().startsWith("120000 "));
            assertEquals("readme.txt", repo.git("show", ":link").text());
            assertEquals(
                    "", repo.git("diff", "--name-only", "--", "link", "run.sh").text(), "as `git add` leaves them");

            // core.fileMode=false: the execute bits of this file system mean nothing to git.
            repo.git("config", "core.fileMode", "false");
            assertTrue(call(
                            async,
                            "stage untrusted",
                            (Consumer<ProcessRunner.Result> cb) ->
                                    service.stageBlob(repo.root, "untrusted.sh", absent, script, cb))
                    .ok());
            assertTrue(repo.git("ls-files", "-s", "--", "untrusted.sh").text().startsWith("100644 "));
        }
    }

    // --- E4: a cached root is dropped when a repository appears below it -----------------------------------

    @Test
    void aRepositoryCreatedInsideAnotherIsNoticedWithoutAProcessPerRefresh(@TempDir Path dir) throws Exception {
        GitTestRepo outer = GitTestRepo.init(dir);
        Path inner = Files.createDirectories(outer.root.resolve("vendor/lib"));
        Path file = Files.writeString(inner.resolve("notes.txt"), "one\n");
        outer.commitAll("init");
        Path log = dir.resolve("log");
        Path wrapper = loggingGit(dir, log, "");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            service.setCommand(wrapper.toString());
            assertEquals(outer.root, refresh(async, service, file).root());
            assertEquals(outer.root, refresh(async, service, file).root());
            assertEquals(outer.root, refresh(async, service, file).root());
            assertEquals(1, count(log, "rev-parse"), "an unchanged folder is answered from the cache");

            // `git init` in a terminal, in a subfolder of the repository already visited.
            Process init = new ProcessBuilder("git", "init", "-q", "-b", "main")
                    .directory(inner.toFile())
                    .inheritIO()
                    .start();
            assertEquals(0, init.waitFor());

            GitService.RepoState state = refresh(async, service, file);
            assertEquals(inner.toRealPath(), state.root(), "the file now belongs to the inner repository");
            assertEquals(2, count(log, "rev-parse"));
            assertEquals(inner.toRealPath(), refresh(async, service, file).root());
            assertEquals(2, count(log, "rev-parse"), "and the new answer is cached in turn");
        }
    }

    // --- E5: a repository git refuses is not "no repository" ------------------------------------------------

    @Test
    void aRepositoryGitRefusesSaysWhyAndIsNotProbedOnEveryRefresh(@TempDir Path dir) throws Exception {
        Path bare = bareRemote(dir, "bare").toRealPath();
        Path plain = Files.createDirectories(dir.resolve("plain"));
        Path log = dir.resolve("log");
        Path wrapper = loggingGit(dir, log, "");
        AtomicLong now = new AtomicLong(1_000 * SECOND);

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            service.setCommand(wrapper.toString());
            service.setNanoClockForTest(now::get);

            GitService.RepoState state = refresh(async, service, bare);
            assertFalse(state.isRepo());
            assertTrue(state.refused(), "a bare repository is not the same as no repository");
            assertEquals("this operation must be run in a work tree", state.refusal());

            now.addAndGet(SECOND);
            assertEquals(state.refusal(), refresh(async, service, bare).refusal());
            assertEquals(1, count(log, "rev-parse"), "the failing probe is not rerun by every refresh");

            now.addAndGet(GitService.REFUSED_TTL.toNanos());
            assertTrue(refresh(async, service, bare).refused());
            assertEquals(2, count(log, "rev-parse"), "but it is asked again once the answer has aged");

            // An ordinary folder is still simply not a repository.
            GitService.RepoState none = refresh(async, service, plain);
            assertFalse(none.isRepo());
            assertFalse(none.refused());
            assertEquals("", none.refusal());
        }
    }

    // --- E6: "git unavailable" is not for the life of the window --------------------------------------------

    @Test
    void gitInstalledWhileRunningIsFoundAfterTheRetryInterval(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        Path later = dir.resolve("git-installed-later");
        AtomicLong now = new AtomicLong(1_000 * SECOND);

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            service.setNanoClockForTest(now::get);
            service.setCommand(later.toString());
            assertFalse(refresh(async, service, file).isRepo());
            assertFalse(service.gitAvailable());

            GitTestRepo.script(later, "exec git \"$@\""); // the user installs git
            now.addAndGet(SECOND);
            assertFalse(refresh(async, service, file).isRepo(), "inside the retry interval nothing is probed");

            now.addAndGet(GitService.UNAVAILABLE_RETRY.toNanos());
            assertTrue(refresh(async, service, file).isRepo(), "after it, git is found without a restart");
            assertTrue(service.gitAvailable());
        }
    }

    @Test
    void aManualRefreshAsksForGitAgainAtOnce(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        Path later = dir.resolve("git-installed-later");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            service.setCommand(later.toString());
            assertFalse(refresh(async, service, file).isRepo());
            GitTestRepo.script(later, "exec git \"$@\"");
            service.invalidateCaches(); // what the palette's "refresh Git" does
            assertTrue(refresh(async, service, file).isRepo());
        }
    }

    // --- W13: a local user command can be stopped -----------------------------------------------------------

    @Test
    void aCommitStuckInAHookCanBeCancelledAndTheLaneMovesOn(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        repo.write("notes.txt", "one\ntwo\n");
        repo.git("add", "-A");
        Path started = GitTestRepo.fifo(dir.resolve("hook-started"));
        // A pre-commit hook that never comes back by itself: only a kill ends the commit.
        GitTestRepo.script(repo.root.resolve(".git/hooks/pre-commit"), "echo started > '" + started + "'\nsleep 120");
        Path ranAfterwards = dir.resolve("second-command-ran");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            assertFalse(service.cancelRunningCommand(), "nothing is running yet");

            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<ProcessRunner.Result> commit = new AtomicReference<>();
            // Two commands as one job: the second must not run once the user has stopped the first.
            service.runWorktreeMutation(
                    repo.root,
                    List.of(
                            new String[] {"commit", "-m", "stuck"},
                            new String[] {"-c", "alias.mark=!touch '" + ranAfterwards + "'", "mark"}),
                    r -> {
                        commit.set(r);
                        finished.countDown();
                    });
            assertEquals(List.of("started"), Files.readAllLines(started), "the commit is now parked in its hook");
            assertFalse(service.cancelNetworkCommand(), "it is not a network command");

            assertTrue(service.cancelRunningCommand());
            async.await(finished, "the cancelled commit reporting back");
            assertTrue(commit.get().cancelled(), commit.get().toString());
            assertFalse(Files.exists(ranAfterwards), "the rest of the job was not started");
            assertFalse(service.cancelRunningCommand(), "and nothing is left to cancel");

            // The lane is free again: status answers, and nothing was committed.
            assertTrue(refresh(async, service, file).isRepo());
            assertEquals("init", repo.git("log", "-1", "--format=%s").text().strip());
            assertFalse(Files.exists(repo.root.resolve(".git/index.lock")), "git removed its lock on SIGTERM");
        }
    }
}
