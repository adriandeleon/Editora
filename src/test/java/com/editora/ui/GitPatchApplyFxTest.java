package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.diff.PatchWriter;
import com.editora.git.GitService;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class GitPatchApplyFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void appliesOnlySelectedPatchToIndex() throws Exception {
        Path repo = Files.createTempDirectory("editora-hunk-stage");
        Path file = repo.resolve("f.txt");
        Files.writeString(file, "one\ntwo\nthree\n");
        git(repo, "init", "-q");
        git(repo, "add", "f.txt");
        git(repo, "-c", "user.email=t@e.st", "-c", "user.name=Test", "commit", "-q", "-m", "init");
        Files.writeString(file, "ONE\ntwo\nTHREE\n");

        String patch = PatchWriter.unifiedDiff("a/f.txt", "b/f.txt", "one\ntwo\nthree\n", "ONE\ntwo\nthree\n");
        GitService service = new GitService();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ProcessRunner.Result> result = new AtomicReference<>();
        service.applyPatch(repo, patch, true, r -> {
            result.set(r);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertTrue(result.get().ok(), result.get().message());
        assertEquals("ONE\ntwo\nthree\n", git(repo, "show", ":f.txt"));
        assertEquals("ONE\ntwo\nTHREE\n", Files.readString(file));
        service.shutdown();
    }

    @Test
    void cachedHunkRefusesAnIndexThatChangedAfterTheDiffSnapshot() throws Exception {
        Path repo = Files.createTempDirectory("editora-stale-hunk");
        Path file = repo.resolve("f.txt");
        Files.writeString(file, "same\nold\nsame\nold\n");
        git(repo, "init", "-q");
        git(repo, "add", "f.txt");
        git(repo, "-c", "user.email=t@e.st", "-c", "user.name=Test", "commit", "-q", "-m", "init");
        String patch =
                PatchWriter.unifiedDiff("a/f.txt", "b/f.txt", "same\nold\nsame\nold\n", "same\nNEW\nsame\nold\n");
        GitService service = new GitService();
        try {
            CountDownLatch identityDone = new CountDownLatch(1);
            AtomicReference<GitService.BlobResult> identity = new AtomicReference<>();
            service.showBlob(repo, ":f.txt", value -> {
                identity.set(value);
                identityDone.countDown();
            });
            assertTrue(identityDone.await(10, TimeUnit.SECONDS));

            Files.writeString(file, "prefix\nsame\nold\nsame\nold\n");
            git(repo, "add", "f.txt");
            String indexBeforeApply = git(repo, "show", ":f.txt");
            CountDownLatch applyDone = new CountDownLatch(1);
            AtomicReference<ProcessRunner.Result> result = new AtomicReference<>();
            service.applyCachedPatch(repo, "f.txt", identity.get(), patch, value -> {
                result.set(value);
                applyDone.countDown();
            });

            assertTrue(applyDone.await(10, TimeUnit.SECONDS));
            assertFalse(result.get().ok());
            assertEquals(indexBeforeApply, git(repo, "show", ":f.txt"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void cachedHunkCanCreateTheFirstIndexForAnUntrackedFile() throws Exception {
        Path repo = Files.createTempDirectory("editora-empty-index-hunk");
        git(repo, "init", "-q");
        Files.writeString(repo.resolve("new.txt"), "new contents\n");
        String patch = PatchWriter.unifiedDiff("a/new.txt", "b/new.txt", "", "new contents\n");
        GitService service = new GitService();
        try {
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<ProcessRunner.Result> result = new AtomicReference<>();
            service.applyCachedPatch(
                    repo, "new.txt", new GitService.BlobResult(false, new byte[0], false), patch, value -> {
                        result.set(value);
                        done.countDown();
                    });

            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(result.get().ok(), result.get().message());
            assertEquals("new contents\n", git(repo, "show", ":new.txt"));
        } finally {
            service.shutdown();
        }
    }

    private static String git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) throw new IllegalStateException(output);
        return output;
    }
}
