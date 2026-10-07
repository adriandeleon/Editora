package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.git.GitLog;
import com.editora.git.GitLogQuery;
import com.editora.git.GitService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** A Git Log search through file contents can take seconds or minutes: it must be possible to stop it. */
@Tag("fx")
class GitHistorySearchCancelFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * A {@code content:} search that is replaced or cleared kills its process instead of running on: the
     * stand-in git blocks in {@code log} until it is killed, and the history lane is free again at once.
     */
    @Test
    void aSupersededHistorySearchIsKilled(@TempDir Path dir) throws Exception {
        assumeFalse(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win"));
        Path started = dir.resolve("started");
        Path script = dir.resolve("fake-git");
        Files.writeString(
                script,
                "#!/bin/sh\n"
                        + "case \" $* \" in\n"
                        + "  *\" --version \"*) echo 'git version 2.45.0' ;;\n"
                        + "  *\" log \"*) : > '" + started + "'; exec sleep 60 ;;\n"
                        + "  *) exit 0 ;;\n"
                        + "esac\n");
        Files.setPosixFilePermissions(script, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        GitService service = new GitService();
        try {
            service.setCommand(script.toString());
            assertFalse(service.cancelHistoryRead(), "nothing is being read yet");
            AtomicReference<GitLog.Page> answered = new AtomicReference<>();
            GitLog.Request search = new GitLog.Request(false, null, GitLogQuery.parse("content:needle"), 0, 50);
            service.logPage(dir, search, answered::set);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(started)) {
                assertTrue(System.nanoTime() < deadline, "the search never started");
                Thread.sleep(20);
            }

            long before = System.nanoTime();
            assertTrue(service.cancelHistoryRead(), "the running search is stopped");
            CountDownLatch next = new CountDownLatch(1);
            service.commitDetails(dir, "HEAD", details -> next.countDown());
            assertTrue(next.await(15, TimeUnit.SECONDS), "the history lane is free again");
            assertTrue(
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - before) < 15,
                    "without waiting for the search to finish");
            assertNull(answered.get(), "a cancelled listing is never delivered");
        } finally {
            service.setCommand("");
            service.shutdown();
        }
    }
}
