package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A remote project holding a second repository: the Project tree's Git actions on its files go to that
 * repository, as they do locally. The host is asked once where its repositories are, so the menus need no
 * SFTP round trip to decide.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class GitRemoteNestedRepositoryFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        org.testfx.api.FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void restoreTheDefault() {
        ExecTargets.setRemoteEnabled(null);
    }

    private static String git(Path dir, String... args) {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(dir, Duration.ofSeconds(30), argv);
        assertTrue(r.ok(), String.join(" ", argv) + ": " + r.message());
        return r.out();
    }

    private static void repository(Path dir, String file) throws Exception {
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.email", "ana@example.com");
        git(dir, "config", "user.name", "Ana");
        git(dir, "config", "commit.gpgsign", "false");
        Files.writeString(dir.resolve(file), "one\n");
        git(dir, "add", file);
        git(dir, "commit", "-q", "-m", "first");
    }

    @Test
    void aFileOfANestedRepositoryOnTheHostIsStagedInThatRepository(@TempDir Path dir) throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        try (AsyncTestScope async = new AsyncTestScope()) {
            EmbeddedSftpFixture sftp = async.own(EmbeddedSftpFixture.startWithShell(dir));
            Path outer = Files.createDirectories(sftp.serverPath("proj"));
            repository(outer, "a.txt");
            Path inner = Files.createDirectories(outer.resolve("libs/inner"));
            repository(inner, "b.txt");
            Files.writeString(inner.resolve("b.txt"), "one\ntwo\n");

            Path remoteOuter = sftp.remotePath("proj");
            Path remoteInnerFile = sftp.remotePath("proj/libs/inner/b.txt");
            // Before the host has said anything: the old answer, the active repository's.
            assertEquals(GitPathScope.ACTIVE, GitPathScope.of(remoteInnerFile, remoteOuter));

            GitPathScope.learnRemote(remoteOuter, true);
            for (int i = 0; i < 200 && !GitPathScope.knowsRemote(remoteOuter); i++) {
                Thread.sleep(50);
            }
            assertTrue(GitPathScope.knowsRemote(remoteOuter), "the host answered");
            assertEquals(GitPathScope.OTHER, GitPathScope.of(remoteInnerFile, remoteOuter));
            assertEquals(GitPathScope.ACTIVE, GitPathScope.of(sftp.remotePath("proj/a.txt"), remoteOuter));
            assertEquals(sftp.remotePath("proj/libs/inner"), GitPathScope.nearestRepository(remoteInnerFile));

            FxWindowFixture fx = async.own(FxWindowFixture.create());
            fx.shared.getSettings().setGitSupport(true);
            GitFeatureFx w = GitFeatureFx.attach(fx);
            FxTestSupport.runOnFx(w.git::applySupport);
            w.open(sftp.remotePath("proj/a.txt"));
            assertEquals(remoteOuter, FxTestSupport.callOnFx(w.git::repoRoot), "the outer repository is active");

            FxTestSupport.runOnFx(() -> w.git.gitStagePath(remoteInnerFile)); // the Project tree's Stage
            String staged = "";
            for (int i = 0; i < 200 && staged.isBlank(); i++) {
                Thread.sleep(50);
                staged = git(inner, "diff", "--cached", "--name-only");
            }
            assertEquals("b.txt", staged.strip(), "staged in the nested repository on the host");
            assertEquals("", git(outer, "diff", "--cached", "--name-only").strip(), "not in the outer one");
        }
    }
}
