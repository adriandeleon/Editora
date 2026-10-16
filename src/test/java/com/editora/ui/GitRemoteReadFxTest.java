package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.git.BlameParser;
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
 * Git for a remote folder runs on the folder's host: a real repository reached through an in-process SSH
 * server, read by the same {@link GitService} calls a window makes. What changes a repository is refused
 * there for now.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class GitRemoteReadFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private GitService service;
    private Path repo; // as the server sees it
    private Path remoteRepo; // as Editora reaches it

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        repo = Files.createDirectories(sftp.serverPath("app"));
        git("init", "-q", "-b", "main");
        git("config", "user.email", "ana@example.com");
        git("config", "user.name", "Ana");
        git("config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        git("add", "a.txt");
        git("commit", "-q", "-m", "first");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo changed\nthree\n");
        remoteRepo = sftp.remotePath("app");
        service = new GitService();
    }

    @AfterEach
    void tearDown() throws Exception {
        service.shutdown();
        sftp.close();
        ExecTargets.setRemoteEnabled(null);
    }

    private void git(String... args) {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(repo, Duration.ofSeconds(30), argv);
        assertTrue(r.ok(), String.join(" ", argv) + ": " + r.message());
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

    @Test
    void statusAndTheGutterDiffOfARemoteFileComeFromTheHost() throws Exception {
        Path file = remoteRepo.resolve("a.txt");
        int before = sftp.commands().size();

        GitService.RepoState state = await(cb -> service.refresh(file, file, cb));

        assertNotNull(state.root(), "not recognised as a repository: " + state.refusal());
        assertEquals(remoteRepo, state.root(), "the root is a path on the remote filesystem");
        assertEquals("main", state.status().branch());
        assertFalse(state.changes().isEmpty(), "the modified file has no change bars");
        assertTrue(
                sftp.commands().size() > before
                        && sftp.commands().stream().anyMatch(line -> line.contains("'rev-parse'")),
                "git did not run on the host: " + sftp.commands());
        assertTrue(service.gitAvailable(file));
    }

    @Test
    void logAndBlameOfARemoteFileComeFromTheHost() throws Exception {
        Path file = remoteRepo.resolve("a.txt");
        GitService.RepoState state = await(cb -> service.refresh(file, file, cb));

        List<GitService.Commit> commits = await(cb -> service.log(state.root(), file, 10, cb));
        List<BlameParser.BlameLine> blame = await(cb -> service.blame(state.root(), file, cb));

        assertEquals(
                List.of("first"),
                commits.stream().map(GitService.Commit::subject).toList());
        assertEquals(3, blame.size(), "one entry per line of the file as it is now");
    }

    @Test
    void aHostTheUserHasNotAllowedIsNotAskedEither() throws Exception {
        ExecTargets.setRemoteHostAllowed(id -> false);
        try {
            Path file = remoteRepo.resolve("a.txt");
            int before = sftp.commands().size();

            GitService.RepoState state = await(cb -> service.refresh(file, file, cb));

            assertEquals(GitService.RepoState.NONE, state);
            assertEquals(before, sftp.commands().size());
        } finally {
            ExecTargets.setRemoteHostAllowed(null);
        }
    }

    @Test
    void theGitConfiguredForTheHostIsTheOneThatRuns() throws Exception {
        Path wrapper = base.resolve("my-git");
        Files.writeString(wrapper, "#!/bin/sh\nexec git \"$@\"\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        ExecTargets.setRemoteTools((id, tool) -> tool.equals("git") ? wrapper.toString() : null);
        try {
            Path file = remoteRepo.resolve("a.txt");

            GitService.RepoState state = await(cb -> service.refresh(file, file, cb));

            assertEquals(remoteRepo, state.root(), state.refusal());
            assertTrue(
                    sftp.commands().stream().anyMatch(line -> line.contains("'" + wrapper + "' ")),
                    "the configured command was not the one run: " + sftp.commands());
        } finally {
            ExecTargets.setRemoteTools(null);
        }
    }

    @Test
    void withTheSettingOffNothingIsAskedOfTheHost() throws Exception {
        ExecTargets.setRemoteEnabled(() -> false);
        Path file = remoteRepo.resolve("a.txt");
        int before = sftp.commands().size();

        GitService.RepoState state = await(cb -> service.refresh(file, file, cb));

        assertEquals(GitService.RepoState.NONE, state);
        assertEquals(before, sftp.commands().size());
        assertFalse(service.gitAvailable(file));
    }

    private String head() {
        ProcessRunner.Result r = ProcessRunner.run(repo, Duration.ofSeconds(30), List.of("git", "rev-parse", "HEAD"));
        assertTrue(r.ok(), r.message());
        return r.out().strip();
    }

    /**
     * A {@code .git-blame-ignore-revs} at the root of a repository on a host is used without any
     * configuration, as for a local one: the reformat it lists is looked through.
     */
    @Test
    void theIgnoreRevsFileOfARemoteRepositoryIsUsed() throws Exception {
        String first = head();
        git("commit", "-q", "-am", "the big reformat");
        String reformat = head();
        Files.writeString(repo.resolve(".git-blame-ignore-revs"), "# reformatting\n" + reformat + "\n");
        Path file = remoteRepo.resolve("a.txt");
        GitService.RepoState state = await(cb -> service.refresh(file, file, cb));

        List<BlameParser.BlameLine> blame = await(cb -> service.blame(state.root(), file, cb));

        assertEquals(3, blame.size());
        assertEquals(first, blame.get(1).hash(), "the reformat is looked through on the host");
    }

    /**
     * The host's global config naming an ignore-revs file this repository does not have — the common
     * {@code blame.ignoreRevsFile = .git-blame-ignore-revs} in {@code ~/.gitconfig} — makes git blame fail
     * outright. Blame runs with a copy of that config without the key, written once on the host.
     */
    @Test
    void aMissingIgnoreRevsFileInTheHostsGlobalConfigDoesNotBreakBlame() throws Exception {
        Files.writeString(
                sftp.home().resolve(".gitconfig"),
                "[blame]\n\tignoreRevsFile = .git-blame-ignore-revs\n[diff]\n\talgorithm = histogram\n");
        Path file = remoteRepo.resolve("a.txt");
        GitService.RepoState state = await(cb -> service.refresh(file, file, cb));

        List<BlameParser.BlameLine> blame = await(cb -> service.blame(state.root(), file, cb));

        assertEquals(3, blame.size(), "blamed, not failed: " + service.blameIgnoreRevsProblem());
        Path cache = sftp.home().resolve(".cache/editora");
        try (var copies = Files.list(cache)) {
            List<Path> configs = copies.filter(p -> p.getFileName().toString().startsWith("git-global-"))
                    .toList();
            assertEquals(1, configs.size(), "the stand-in global config, on the host: " + configs);
            String copy = Files.readString(configs.get(0));
            assertFalse(copy.contains("ignoreRevsFile"), copy);
            assertTrue(copy.contains("algorithm = \"histogram\""), "the rest of the user's config is kept: " + copy);
        }
    }
}
