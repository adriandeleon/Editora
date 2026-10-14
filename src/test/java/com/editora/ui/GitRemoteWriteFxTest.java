package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.git.GitService;
import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git commands that change a repository on a remote host run there: a real repository reached through the
 * in-process SSH server, changed by the same {@link GitService} calls a window makes, and checked by asking
 * git on the "host" directly.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class GitRemoteWriteFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private GitService service;
    private Path repo;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        repo = Files.createDirectories(sftp.serverPath("app"));
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "ana@example.com");
        git(repo, "config", "user.name", "Ana");
        git(repo, "config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "-m", "first");
        service = new GitService();
        Path file = sftp.remotePath("app/a.txt");
        GitService.RepoState state = await(cb -> service.refresh(file, file, cb));
        root = state.root();
        assertNotNull(root, state.refusal());
    }

    @AfterEach
    void tearDown() throws Exception {
        service.shutdown();
        sftp.close();
        ExecTargets.setRemoteEnabled(null);
    }

    private static String git(Path dir, String... args) {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(dir, Duration.ofSeconds(30), argv);
        assertTrue(r.ok(), String.join(" ", argv) + ": " + r.message());
        return r.out();
    }

    private static <T> T await(Consumer<Consumer<T>> request) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        request.accept(value -> {
            result.set(value);
            done.countDown();
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "git never answered");
        return result.get();
    }

    private ProcessRunner.Result mutate(String... args) throws Exception {
        return await(cb -> service.runWorktreeMutation(root, cb, args));
    }

    @Test
    void stagingAndCommittingHappenOnTheHost() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\nthree\n");

        ProcessRunner.Result add = mutate("add", "a.txt");
        ProcessRunner.Result commit = mutate("commit", "-m", "second");

        assertTrue(add.ok(), add.message());
        assertTrue(commit.ok(), commit.message());
        assertEquals("second\nfirst\n", git(repo, "log", "--format=%s"));
        assertTrue(
                sftp.commands().stream().anyMatch(line -> line.contains("'commit'")),
                "the commit was not run on the host");
    }

    @Test
    void aHunkIsStagedThroughAPrivateIndexAndPublishedAtomically() throws Exception {
        Files.writeString(repo.resolve("a.txt"), "one\ntwo changed\nthree\n");
        GitService.BlobResult before =
                new GitService.BlobResult(true, "one\ntwo\n".getBytes(StandardCharsets.UTF_8), false);

        ProcessRunner.Result staged = await(cb ->
                service.stageBlob(root, "a.txt", before, "one\ntwo changed\n".getBytes(StandardCharsets.UTF_8), cb));

        assertTrue(staged.ok(), staged.message());
        assertEquals("one\ntwo changed\n", git(repo, "show", ":a.txt"), "only the chosen lines are staged");
        assertEquals("one\ntwo changed\nthree\n", Files.readString(repo.resolve("a.txt")), "the file is untouched");
        try (var left = Files.list(repo.resolve(".git"))) {
            assertFalse(
                    left.anyMatch(p -> p.getFileName().toString().startsWith(".editora-index-")
                            || p.getFileName().toString().equals("index.lock")),
                    "the private index and the lock are cleaned up");
        }
    }

    @Test
    void aHunkStagedAgainstAStaleSnapshotIsRefused() throws Exception {
        GitService.BlobResult stale =
                new GitService.BlobResult(true, "not what the index has\n".getBytes(StandardCharsets.UTF_8), false);

        ProcessRunner.Result staged =
                await(cb -> service.stageBlob(root, "a.txt", stale, "x\n".getBytes(StandardCharsets.UTF_8), cb));

        assertFalse(staged.ok());
        assertEquals("one\ntwo\n", git(repo, "show", ":a.txt"));
    }

    @Test
    void pushingGoesFromTheHostToItsRemote() throws Exception {
        Path bare = sftp.serverPath("origin.git");
        git(sftp.serverPath(""), "init", "-q", "--bare", bare.toString());
        git(repo, "remote", "add", "origin", bare.toString());

        ProcessRunner.Result pushed = await(cb -> service.runNetwork(root, cb, "push", "origin", "main"));

        assertTrue(pushed.ok(), pushed.message());
        assertEquals("first\n", git(bare, "log", "--format=%s", "main"));
    }

    @Test
    void cloningOntoARemoteHostIsNotOfferedYet() throws Exception {
        Path destination = sftp.remotePath("copy");
        int before = sftp.commands().size();

        ProcessRunner.Result r = await(cb -> service.clone("https://example.invalid/repo.git", destination, cb));

        assertFalse(r.ok());
        assertEquals("Cloning or creating a repository on a remote host is not available yet", r.err());
        assertEquals(before, sftp.commands().size(), "nothing was run on the host");
    }
}
