package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.git.GitService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class GitBlobLookupFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void distinguishesAnEmptyIndexBlobFromAMissingSpec() throws Exception {
        Path repo = Files.createTempDirectory("editora-empty-blob");
        git(repo, "init", "-q");
        Files.write(repo.resolve("empty.txt"), new byte[0]);
        git(repo, "add", "empty.txt");

        GitService service = new GitService();
        try {
            GitService.BlobResult empty = lookup(service, repo, ":empty.txt");
            GitService.BlobResult missing = lookup(service, repo, ":missing.txt");

            assertTrue(empty.found());
            assertTrue(empty.bytes().length == 0);
            assertFalse(missing.found());
        } finally {
            service.shutdown();
        }
    }

    private static GitService.BlobResult lookup(GitService service, Path repo, String spec) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<GitService.BlobResult> result = new AtomicReference<>();
        service.showBlob(repo, spec, value -> {
            result.set(value);
            done.countDown();
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "Git blob lookup timed out");
        return result.get();
    }

    private static void git(Path dir, String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }
}
