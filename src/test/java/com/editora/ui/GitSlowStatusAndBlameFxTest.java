package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.git.BlameParser;
import com.editora.git.GitService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code git status} that times out is not "this is not a repository", and is not asked again at once; and
 * the inline blame, requested after every refresh, is not recomputed for a file and a HEAD that did not change.
 */
@Tag("fx")
class GitSlowStatusAndBlameFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
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

    @Test
    void aStatusThatTimesOutKeepsTheLastStateAndBacksOff(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\n");
        repo.commitAll("init");
        Path log = dir.resolve("invocations.log");
        Path slow = dir.resolve("slow");
        // A stand-in git whose `status` hangs for as long as the marker file exists.
        Path wrapper = GitTestRepo.script(dir.resolve("git-wrapper"), """
                echo "$*" >> '%s'
                case "$*" in
                  *status*) if [ -e '%s' ]; then sleep 30; fi ;;
                esac
                exec git "$@"
                """.formatted(log, slow));
        Assumptions.assumeFalse(
                wrapper.toString().matches(".*\\s.*"), "the git command setting is whitespace-tokenized");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(() -> {
                service.shutdown();
                service.setCommand("");
            });
            service.setCommand(wrapper.toString());
            AtomicInteger applied = new AtomicInteger();
            AtomicReference<GitService.RepoState> last = new AtomicReference<>();
            // Always composed as window.andThen(cb): `call` returns as soon as cb runs, so the window must have
            // counted the state before that, or the assertions below race the FX thread.
            Consumer<GitService.RepoState> window = state -> {
                last.set(state);
                applied.incrementAndGet();
            };
            assertTrue(call(
                            async,
                            "the first refresh",
                            (Consumer<GitService.RepoState> cb) -> service.refresh(file, null, window.andThen(cb)))
                    .isRepo());
            assertEquals(1, applied.get());

            // The repository stops answering in time.
            Files.writeString(slow, "");
            service.setStatusTimeoutForTest(Duration.ofMillis(300));
            for (int i = 0; i < 2; i++) {
                service.refresh(file, null, window);
                // Queued behind it on the same lane: by the time this answers, the refresh ran or was skipped.
                call(async, "the lane to drain", (Consumer<String> cb) -> service.version(cb));
                async.awaitFx();
            }

            assertEquals(1, applied.get(), "a timeout is not delivered as 'not a repository'");
            assertTrue(last.get().isRepo(), "the window still holds the last state it was given");
            long statuses = Files.readAllLines(log).stream()
                    .filter(l -> l.contains("status"))
                    .count();
            assertEquals(2, statuses, "one that answered, one that timed out; the next was not even started");

            // The repository answers again and the wait is lifted (here: by the caches being invalidated).
            Files.delete(slow);
            service.invalidateCaches();
            assertTrue(call(
                            async,
                            "a refresh after recovery",
                            (Consumer<GitService.RepoState> cb) -> service.refresh(file, null, window.andThen(cb)))
                    .isRepo());
            assertEquals(2, applied.get());
        }
    }

    @Test
    void blameIsComputedOncePerFileContentAndHead(@TempDir Path dir) throws Exception {
        GitTestRepo repo = GitTestRepo.init(dir);
        Path file = repo.write("notes.txt", "one\ntwo\n");
        repo.commitAll("init");

        GitService service = new GitService();
        try (AsyncTestScope async = new AsyncTestScope()) {
            async.onClose(service::shutdown);
            Consumer<Consumer<List<BlameParser.BlameLine>>> blame = cb -> service.blameLatest(repo.root, file, cb);

            List<BlameParser.BlameLine> first = call(async, "blame", blame);
            assertEquals(2, first.size());
            assertEquals(first, call(async, "the same blame again", blame));
            assertEquals(1, service.blameRunsForTest(), "an unchanged file at an unchanged HEAD is not blamed twice");

            // The file changes on disk (a save): its uncommitted line must show.
            Files.writeString(file, "one\ntwo\nthree, not committed\n");
            assertEquals(3, call(async, "blame after an edit", blame).size());
            assertEquals(2, service.blameRunsForTest());

            // HEAD moves (a commit): the same bytes are attributed differently.
            repo.commitAll("second");
            List<BlameParser.BlameLine> committed = call(async, "blame after a commit", blame);
            assertEquals(3, service.blameRunsForTest());
            assertFalse(committed.get(2).hash().startsWith("0000000"), "the third line now belongs to a commit");
            call(async, "and again", blame);
            assertEquals(3, service.blameRunsForTest());
        }
    }
}
