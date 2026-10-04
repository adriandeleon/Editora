package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.git.ChangeType;
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
 * {@link GitService} against a real repository: the order user commands run in across its two lanes, what
 * its caches forget, and file names that are data rather than patterns or options.
 */
@Tag("fx")
class GitServiceRequestOrderFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Assumptions.assumeFalse(GitTestRepo.windows(), "uses /bin/sh scripts");
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

    private static void awaitLine(Path log, String line) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(log) || !Files.readAllLines(log).contains(line)) {
            assertTrue(System.nanoTime() < deadline, "never saw '" + line + "' in the command log");
            Thread.sleep(20);
        }
    }

    /** A stand-in git that records when {@code slow} starts and ends, taking {@code seconds} over it. */
    private static Path slowGit(Path dir, Path log, String slow, String seconds) throws Exception {
        return GitTestRepo.script(dir.resolve("gitw"), """
                case "$1" in
                  %1$s|push|add) echo "start $1" >> '%2$s' ;;
                esac
                if [ "$1" = "%1$s" ]; then sleep %3$s; fi
                git "$@"
                code=$?
                case "$1" in
                  %1$s|push|add) echo "end $1" >> '%2$s' ;;
                esac
                exit $code""".formatted(slow, log, seconds));
    }

    // --- B2-2: a push requested after a commit runs after it ------------------------------------------

    @Test
    void aPushRequestedAfterACommitWaitsForTheCommit(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("f.txt", "one\n");
        repo.commitAll("init");
        Path remote = dir.resolve("remote.git");
        repo.git("init", "-q", "--bare", remote.toString());
        repo.git("remote", "add", "origin", remote.toString());
        repo.git("push", "-q", "-u", "origin", "main");
        repo.write("f.txt", "two\n");
        repo.git("add", "-A");

        Path log = dir.resolve("order.log");
        GitService service = new GitService();
        service.setCommand(slowGit(dir, log, "commit", "1").toString()); // a commit still in its hooks
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            CountDownLatch both = new CountDownLatch(2);
            AtomicReference<ProcessRunner.Result> pushed = new AtomicReference<>();
            service.runWorktreeMutation(repo.root, r -> both.countDown(), "commit", "-q", "-m", "second");
            service.runNetwork(
                    repo.root,
                    r -> {
                        pushed.set(r);
                        both.countDown();
                    },
                    "push");
            async.await(both, "commit and push");

            assertEquals(List.of("start commit", "end commit", "start push", "end push"), Files.readAllLines(log));
            assertTrue(pushed.get().ok(), pushed.get().message());
            assertEquals(
                    repo.git("rev-parse", "HEAD").text().strip(),
                    repo.git("rev-parse", "origin/main").text().strip(),
                    "the push carried the commit requested before it");
        }
    }

    // --- B2-5: a local mutation during a pull does not hold up the reads ------------------------------

    @Test
    void stagingDuringASlowPullDoesNotStallStatus(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("f.txt", "one\n");
        repo.commitAll("init");
        repo.write("f.txt", "two\n");

        Path log = dir.resolve("order.log");
        GitService service = new GitService();
        service.setCommand(slowGit(dir, log, "pull", "3").toString()); // a remote that does not answer
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            CountDownLatch pulled = new CountDownLatch(1);
            CountDownLatch staged = new CountDownLatch(1);
            AtomicReference<ProcessRunner.Result> stageResult = new AtomicReference<>();
            service.runNetworkWorktreeMutation(repo.root, r -> pulled.countDown(), "pull");
            awaitLine(log, "start pull");

            service.runWorktreeMutation(
                    repo.root,
                    r -> {
                        stageResult.set(r);
                        staged.countDown();
                    },
                    "add",
                    "f.txt");
            CountDownLatch status = new CountDownLatch(1);
            service.status(file, state -> status.countDown());

            assertTrue(status.await(1500, TimeUnit.MILLISECONDS), "status answers while the pull is still running");
            assertEquals(1, pulled.getCount(), "the pull is still running");
            async.await(staged, "the staged file");
            assertTrue(stageResult.get().ok(), stageResult.get().message());
            // The working tree still has one writer at a time, in request order.
            assertEquals(List.of("start pull", "end pull", "start add", "end add"), Files.readAllLines(log));
        }
    }

    // --- C2-6: a superseded refresh does no work --------------------------------------------------------

    @Test
    void refreshesSupersededWhileQueuedDoNotRunGit(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("f.txt", "one\n");
        repo.commitAll("init");

        Path log = dir.resolve("argv.log");
        Path wrapper = GitTestRepo.script(
                dir.resolve("gitw"),
                "case \" $* \" in *\" status \"*) echo status >> '" + log + "'; sleep 0.3 ;; esac\nexec git \"$@\"");
        GitService service = new GitService();
        service.setCommand(wrapper.toString());
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            CountDownLatch last = new CountDownLatch(1);
            for (int i = 0; i < 9; i++) {
                service.refresh(file, file, state -> {});
            }
            service.refresh(file, file, state -> last.countDown());
            async.await(last, "the last refresh");

            // The one already running when the others arrived, and the last: not all ten.
            int statusRuns = Files.readAllLines(log).size();
            assertTrue(statusRuns <= 2, "git status ran " + statusRuns + " times for 10 queued refreshes");
        }
    }

    // --- C2-2 / E1-8: "not a repository" is not remembered for the session -----------------------------

    @Test
    void aFolderThatBecomesARepositoryIsNoticed(@TempDir Path dir) throws Exception {
        Path folder = Files.createDirectory(dir.resolve("plain"));
        Path file = Files.writeString(folder.resolve("main.txt"), "x\n");
        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            assertFalse(call(async, "status", (Consumer<GitService.RepoState> cb) -> service.status(file, cb))
                    .isRepo());

            // In a terminal: git init. Nothing tells the service.
            Process init = new ProcessBuilder("git", "init", "-q", "-b", "main")
                    .directory(folder.toFile())
                    .start();
            assertEquals(0, init.waitFor());
            Thread.sleep(2300); // longer than the service believes a "not a repository" answer
            assertTrue(
                    call(async, "status", (Consumer<GitService.RepoState> cb) -> service.status(file, cb))
                            .isRepo(),
                    "a refresh after an external `git init` finds the repository");
        }
    }

    // --- D1-7 / E1-8: every window re-probes when the git command changes ------------------------------

    @Test
    void changingTheGitCommandIsSeenByEveryWindowsService(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("f.txt", "one\n");
        repo.commitAll("init");
        GitService first = new GitService();
        GitService second = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(first::shutdown);
            async.onClose(second::shutdown);
            first.setCommand(dir.resolve("no-such-git").toString());
            second.setCommand(dir.resolve("no-such-git").toString());
            assertFalse(call(async, "status", (Consumer<GitService.RepoState> cb) -> first.status(file, cb))
                    .isRepo());
            assertFalse(call(async, "status", (Consumer<GitService.RepoState> cb) -> second.status(file, cb))
                    .isRepo());

            // The path is corrected in Settings: each window applies the same new value in turn.
            first.setCommand("");
            second.setCommand("");
            assertTrue(call(async, "status", (Consumer<GitService.RepoState> cb) -> first.status(file, cb))
                    .isRepo());
            assertTrue(
                    call(async, "status", (Consumer<GitService.RepoState> cb) -> second.status(file, cb))
                            .isRepo(),
                    "the second window re-probes too");
        }
    }

    // --- A6-2: a file name is a literal path, not a glob ------------------------------------------------

    @Test
    void aFileNameWithGlobCharactersOnlyMatchesItself(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path bracket = repo.write("a[1].txt", "one\ntwo\nthree\n");
        repo.write("a1.txt", "one\ntwo\nthree\n");
        repo.commitAll("init");
        repo.write("a1.txt", "one\ntwo\nTHREE\n");
        repo.commitAll("neighbour");
        repo.write("a[1].txt", "ONE\ntwo\nthree\n");
        repo.write("a1.txt", "one\ntwo\nthree again\n");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            GitService.GitDiff diff =
                    call(async, "diff", (Consumer<GitService.GitDiff> cb) -> service.diff(repo.root, bracket, cb));
            assertEquals(Map.of(0, ChangeType.MODIFIED), diff.changes(), "only this file's own change");
            List<GitService.Commit> history = call(
                    async, "log", (Consumer<List<GitService.Commit>> cb) -> service.log(repo.root, bracket, 10, cb));
            assertEquals(
                    List.of("init"),
                    history.stream().map(GitService.Commit::subject).toList());
            assertEquals(
                    3,
                    call(
                                    async,
                                    "blame",
                                    (Consumer<List<com.editora.git.BlameParser.BlameLine>> cb) ->
                                            service.blame(repo.root, bracket, cb))
                            .size());
        }
    }

    // --- B2-8: a control character in a file name is not an unsafe revision ---------------------------

    @Test
    void aTrackedFileWithATabInItsNameIsFound(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("tab\tname.txt", "tracked\n");
        repo.commitAll("init");
        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            GitService.BlobResult blob = call(
                    async,
                    "show",
                    (Consumer<GitService.BlobResult> cb) -> service.showBlob(repo.root, "HEAD:tab\tname.txt", cb));
            assertTrue(blob.found());
            assertEquals("tracked\n", new String(blob.bytes(), StandardCharsets.UTF_8));
        }
    }

    // --- B2-6 / B4-3: the user's hooks run in the user's locale ----------------------------------------

    @Test
    void hooksOfAUserCommandDoNotRunInTheCLocale(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        repo.write("f.txt", "one\n");
        repo.git("add", "-A");
        Path seen = dir.resolve("hook-env");
        GitTestRepo.script(
                repo.root.resolve(".git/hooks/pre-commit"),
                "echo \"LC_ALL=${LC_ALL:-unset} LC_MESSAGES=${LC_MESSAGES:-unset}\" > '" + seen + "'");
        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            ProcessRunner.Result committed = call(
                    async,
                    "commit",
                    (Consumer<ProcessRunner.Result> cb) ->
                            service.runWorktreeMutation(repo.root, cb, "commit", "-q", "-m", "first"));
            assertTrue(committed.ok(), committed.message());
            // LC_ALL=C made a JVM hook decode file names as ASCII; only the message language is pinned.
            assertEquals("LC_ALL=unset LC_MESSAGES=C", Files.readString(seen).strip());
        }
    }

    // --- A6-4: a pasted clone "URL" is never an option --------------------------------------------------

    @Test
    void aCloneUrlThatLooksLikeAnOptionRunsNothing(@TempDir Path dir) throws Exception {
        GitTestRepo source = GitTestRepo.init(dir);
        source.write("f.txt", "one\n");
        source.commitAll("init");
        Path bare = dir.resolve("bare.git");
        source.git("clone", "-q", "--bare", source.root.toString(), bare.toString());
        Path marker = dir.resolve("upload-pack-ran");
        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            // Read as --upload-pack=…, the value turns the destination into the repository to clone and
            // git runs the program to talk to it.
            ProcessRunner.Result result = call(
                    async,
                    "clone",
                    (Consumer<ProcessRunner.Result> cb) ->
                            service.clone("--upload-pack=touch '" + marker + "';false", bare, cb));
            assertFalse(result.ok());
            assertFalse(Files.exists(marker), "the pasted value was executed as an upload-pack program");
        }
    }
}
