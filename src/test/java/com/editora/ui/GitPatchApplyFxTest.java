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
