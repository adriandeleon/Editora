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
import com.editora.process.MappedTestTarget;
import com.editora.process.ProcessRunner;
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
 * Git for a folder of this machine whose tools run elsewhere (a container, WSL): every command goes to that
 * target, and every path handed to git is the name the target knows the folder by — never this machine's.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the stand-in target is a symlink
class GitMappedTargetFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private Path repo;
    private MappedTestTarget target;
    private GitService service;

    private void git(String... args) {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(null, Duration.ofSeconds(30), prepend("-C", repo.toString(), argv));
        assertTrue(r.ok(), String.join(" ", argv) + ": " + r.message());
    }

    private static List<String> prepend(String flag, String value, List<String> argv) {
        List<String> all = new java.util.ArrayList<>(List.of(argv.get(0), flag, value));
        all.addAll(argv.subList(1, argv.size()));
        return all;
    }

    private String head() {
        ProcessRunner.Result r = ProcessRunner.run(
                null, Duration.ofSeconds(30), List.of("git", "-C", repo.toString(), "rev-parse", "HEAD"));
        return r.out().strip();
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = Files.createDirectories(base.resolve("app")).toRealPath();
        git("init", "-q", "-b", "main");
        git("config", "user.email", "ana@example.com");
        git("config", "user.name", "Ana");
        git("config", "commit.gpgsign", "false");
        Files.writeString(repo.resolve("a.txt"), "one\ntwo\n");
        git("add", "a.txt");
        git("commit", "-q", "-m", "first");
        ExecTargets.setRemoteEnabled(() -> true);
        target = MappedTestTarget.register(repo, base.resolve("mounted"));
        service = new GitService();
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
        target.unregister();
        ExecTargets.setRemoteEnabled(null);
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
    void gitForAMappedFolderRunsThereAndIsHandedItsNameThere() throws Exception {
        String first = head();
        Files.writeString(repo.resolve("a.txt"), "one\nTWO\n");
        git("commit", "-q", "-am", "the big reformat");
        String reformat = head();
        Files.writeString(repo.resolve(".git-blame-ignore-revs"), reformat + "\n");
        Files.writeString(repo.resolve("a.txt"), "one\nTWO\nthree\n");
        Path file = repo.resolve("a.txt");
        assertFalse(ExecTargets.runsHere(file), "the folder's tools run elsewhere");

        GitService.RepoState state = await(cb -> service.refresh(file, file, cb));
        assertNotNull(state.root(), state.refusal());
        assertFalse(state.changes().isEmpty(), "the change bars, from git diff of the file");
        List<GitService.Commit> log = await(cb -> service.log(state.root(), file, 10, cb));
        List<BlameParser.BlameLine> blame = await(cb -> service.blame(state.root(), file, cb));

        assertEquals(2, log.size());
        assertEquals(first, blame.get(1).hash(), "the ignore-revs file was passed, by its name there");
        assertFalse(target.started().isEmpty(), "git ran on the mapped target");
        assertEquals(List.of(), target.leaks(), "a path handed to git by this machine's name");
    }
}
