package com.editora.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.io.DelegatingFileOperations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConfigWriterTest {

    private static final class SubmissionObservingExecutor extends AbstractExecutorService {

        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        private final AtomicInteger submissions = new AtomicInteger();
        private final CountDownLatch secondSubmission = new CountDownLatch(1);

        @Override
        public void execute(Runnable command) {
            if (submissions.incrementAndGet() == 2) {
                secondSubmission.countDown();
            }
            delegate.execute(command);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        void awaitSecondSubmission() throws InterruptedException {
            assertTrue(secondSubmission.await(5, TimeUnit.SECONDS));
        }
    }

    private enum AtomicFailureStage {
        TEMP_CREATION,
        TEMP_WRITE,
        BOTH_MOVES,
        CLEANUP
    }

    public static final class TimedShutdownProcess {
        public static void main(String[] args) throws Exception {
            ConfigWriter writer = new ConfigWriter();
            writer.flushTimeoutMillis = 20;
            java.util.concurrent.CountDownLatch claimed = new java.util.concurrent.CountDownLatch(1);
            writer.afterBatchClaimedForTest = () -> {
                claimed.countDown();
                try {
                    new java.util.concurrent.CountDownLatch(1).await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            };
            Runtime.getRuntime().addShutdownHook(new Thread(writer::flush, "test-config-flush"));
            writer.enqueue(java.nio.file.Path.of(args[0]), bytes("pending"));
            if (!claimed.await(5, java.util.concurrent.TimeUnit.SECONDS) || writer.shutdown()) {
                System.exit(2);
            }
            System.exit(0);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void configFilesAreNotReadableByOtherUsers(@TempDir Path dir) throws IOException {
        // settings.json holds Settings.aiApiKey — the AI provider's billable credential — and notes.json holds
        // the user's private notes. The default umask writes 0644 into a 0755 config dir, so any other account
        // on the machine could simply read the key out.
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path file = dir.resolve("settings.json");

        ConfigWriter.writeAtomic(file, bytes("aiApiKey = 'sk-proj-secret'\n"));

        var perms = Files.getPosixFilePermissions(file);
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ), "a config file must not be world-readable");
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ), "a config file must not be group-readable");
        assertEquals("rw-------", PosixFilePermissions.toString(perms));
    }

    @Test
    void rewritingTightensAFileLeftWorldReadableByAnOlderVersion(@TempDir Path dir) throws IOException {
        // An existing install already has a 0644 settings.json; the next save must fix it, not preserve it.
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "aiApiKey = 'old'\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));

        ConfigWriter.writeAtomic(file, bytes("aiApiKey = 'sk-proj-secret'\n"));

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void aLeftoverTempFileDoesNotLeakItsOldPermissions(@TempDir Path dir) throws IOException {
        // A temp left behind by a crashed write would otherwise be reused with its old, laxer mode.
        assumeTrue(dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Path file = dir.resolve("settings.json");
        Path tmp = dir.resolve("settings.json.tmp");
        Files.writeString(tmp, "stale");
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-rw-rw-"));

        ConfigWriter.writeAtomic(file, bytes("aiApiKey = 'sk-proj-secret'\n"));

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("aiApiKey = 'sk-proj-secret'\n", Files.readString(file));
    }

    @Test
    void writeAtomicCreatesParentDirsAndWritesContent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("nested/sub/settings.json");
        ConfigWriter.writeAtomic(file, bytes("a = 1\n"));
        assertTrue(Files.exists(file));
        assertArrayEquals(bytes("a = 1\n"), Files.readAllBytes(file));
        assertFalse(Files.exists(file.resolveSibling("settings.json.tmp")), "temp file is moved away, not left behind");
    }

    @Test
    void writeAtomicOverwritesExisting(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("x.json");
        ConfigWriter.writeAtomic(file, bytes("old"));
        ConfigWriter.writeAtomic(file, bytes("new"));
        assertEquals("new", Files.readString(file));
    }

    @ParameterizedTest(name = "{0} failure preserves the previous configuration")
    @EnumSource(AtomicFailureStage.class)
    void atomicReplacementFailuresKeepThePreviousConfiguration(AtomicFailureStage stage, @TempDir Path dir)
            throws IOException {
        Path file = Files.writeString(dir.resolve("settings.json"), "previous configuration");
        DelegatingFileOperations files = new DelegatingFileOperations() {
            @Override
            public Path createTempFile(Path directory, String prefix, String suffix, FileAttribute<?>... attributes)
                    throws IOException {
                if (stage == AtomicFailureStage.TEMP_CREATION) {
                    throw new IOException("temp creation failed");
                }
                return super.createTempFile(directory, prefix, suffix, attributes);
            }

            @Override
            public Path createTempFile(String prefix, String suffix, FileAttribute<?>... attributes)
                    throws IOException {
                if (stage == AtomicFailureStage.TEMP_CREATION) {
                    throw new IOException("temp creation failed");
                }
                return super.createTempFile(prefix, suffix, attributes);
            }

            @Override
            public void write(Path path, byte[] content) throws IOException {
                if (stage == AtomicFailureStage.TEMP_WRITE || stage == AtomicFailureStage.CLEANUP) {
                    Files.write(path, Arrays.copyOf(content, 3));
                    throw new IOException("temp write failed");
                }
                super.write(path, content);
            }

            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                if (stage == AtomicFailureStage.BOTH_MOVES) {
                    throw new IOException("move failed");
                }
                super.move(source, target, options);
            }

            @Override
            public boolean deleteIfExists(Path path) throws IOException {
                if (stage == AtomicFailureStage.CLEANUP
                        && path.getFileName().toString().startsWith(".settings.json-")) {
                    throw new IOException("cleanup failed");
                }
                return super.deleteIfExists(path);
            }
        };

        assertThrows(IOException.class, () -> ConfigWriter.writeAtomic(file, bytes("new configuration"), files));

        assertEquals("previous configuration", Files.readString(file));
        if (stage != AtomicFailureStage.CLEANUP) {
            try (var entries = Files.list(dir)) {
                assertEquals(1, entries.count(), "failed config staging must be cleaned up when possible");
            }
        }
    }

    @Test
    void configAtomicMoveFailureUsesTheFallbackMove(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("settings.json"), "previous");
        AtomicInteger moves = new AtomicInteger();
        DelegatingFileOperations files = new DelegatingFileOperations() {
            @Override
            public void move(Path source, Path target, CopyOption... options) throws IOException {
                moves.incrementAndGet();
                if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                    throw new IOException("atomic move unsupported");
                }
                super.move(source, target, options);
            }
        };

        ConfigWriter.writeAtomic(file, bytes("replacement"), files);

        assertEquals(2, moves.get());
        assertEquals("replacement", Files.readString(file));
    }

    @Test
    void configCleanupCannotOverrideAnAlreadyCommittedWrite(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("settings.json"), "previous");
        DelegatingFileOperations files = new DelegatingFileOperations() {
            @Override
            public boolean deleteIfExists(Path path) throws IOException {
                if (path.getFileName().toString().startsWith(".settings.json-")) {
                    throw new IOException("cleanup should not run after commit");
                }
                return super.deleteIfExists(path);
            }
        };

        ConfigWriter.writeAtomic(file, bytes("replacement"), files);

        assertEquals("replacement", Files.readString(file));
    }

    @Test
    void enqueueThenFlushWritesLatestBytes(@TempDir Path dir) throws IOException {
        ConfigWriter w = new ConfigWriter();
        Path file = dir.resolve("settings.json");
        // A burst of writes to the same file coalesces to the last one.
        for (int i = 0; i < 50; i++) {
            w.enqueue(file, bytes("v=" + i + "\n"));
        }
        w.flush();
        assertEquals("v=49\n", Files.readString(file), "the latest queued bytes win after a flush");
        w.shutdown();
    }

    @Test
    void flushWritesAllPendingPaths(@TempDir Path dir) throws IOException {
        ConfigWriter w = new ConfigWriter();
        Path a = dir.resolve("settings.json");
        Path b = dir.resolve("projects/p1.json");
        w.enqueue(a, bytes("A"));
        w.enqueue(b, bytes("B"));
        w.flush();
        assertEquals("A", Files.readString(a));
        assertEquals("B", Files.readString(b), "a queued write to a sub-dir is created + written");
        w.shutdown();
    }

    @Test
    void flushWithNothingPendingIsANoOp() {
        ConfigWriter w = new ConfigWriter();
        w.flush(); // must not throw or block
        w.shutdown();
    }

    @Test
    void enqueueAfterShutdownReportsFailureWithoutWriting(@TempDir Path dir) {
        ConfigWriter w = new ConfigWriter();
        w.shutdown();
        Path file = dir.resolve("late.json");
        java.util.concurrent.atomic.AtomicReference<ConfigWriter.WriteOutcome> outcome =
                new java.util.concurrent.atomic.AtomicReference<>();
        w.enqueue(file, () -> bytes("late"), outcome::set);
        assertEquals(ConfigWriter.WriteOutcome.FAILED, outcome.get());
        assertFalse(Files.exists(file));
        assertFalse(w.flush(), "the rejected write must remain visible to the durability barrier");
    }

    @Test
    void shutdownRejectsAWriteThatArrivesAtItsDurabilityBarrier(@TempDir Path dir) throws Exception {
        SubmissionObservingExecutor io = new SubmissionObservingExecutor();
        ConfigWriter writer = new ConfigWriter(io);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        writer.afterBatchClaimedForTest = () -> {
            claimed.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try {
            writer.enqueue(dir.resolve("first.json"), bytes("first"));
            assertTrue(claimed.await(5, TimeUnit.SECONDS));

            Future<Boolean> shutdown = caller.submit(writer::shutdown);
            io.awaitSecondSubmission();

            java.util.concurrent.atomic.AtomicReference<ConfigWriter.WriteOutcome> lateOutcome =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Path late = dir.resolve("late.json");
            writer.enqueue(late, () -> bytes("late"), lateOutcome::set);
            release.countDown();

            assertFalse(
                    shutdown.get(5, TimeUnit.SECONDS),
                    "a write rejected during shutdown must be visible to the durability result");
            assertEquals(ConfigWriter.WriteOutcome.FAILED, lateOutcome.get());
            assertFalse(Files.exists(late), "shutdown must leave no accepted write running behind its barrier");
        } finally {
            release.countDown();
            caller.shutdownNow();
            io.shutdownNow();
        }
    }

    @Test
    void aWriteFailureNotifiesTheErrorHandler(@TempDir Path dir) throws Exception {
        // #418: a durable-save write failure must be surfaced, not silently swallowed. Make the write fail
        // deterministically by pointing at a target whose parent is a regular file (createDirectories throws).
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x");
        Path target = blocker.resolve("settings.json");

        ConfigWriter w = new ConfigWriter();
        java.util.concurrent.atomic.AtomicReference<Path> failed = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        w.setOnWriteError((f, e) -> {
            failed.set(f);
            latch.countDown();
        });
        w.enqueue(target, bytes("a = 1\n"));

        assertTrue(
                latch.await(5, java.util.concurrent.TimeUnit.SECONDS),
                "the write-error handler must be invoked on a failed write");
        assertEquals(target, failed.get());
        assertFalse(w.flush(), "a completed flush must report that one of its writes failed");
        w.shutdown();
    }

    @Test
    void aFlushTimeoutNeverStartsACompetingWriter(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        ConfigWriter writer = new ConfigWriter();
        writer.flushTimeoutMillis = 50;
        java.util.concurrent.CountDownLatch claimed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        writer.afterBatchClaimedForTest = () -> {
            if (first.compareAndSet(true, false)) {
                claimed.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        try {
            writer.enqueue(file, bytes("old"));
            assertTrue(claimed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            writer.enqueue(file, bytes("new"));
            assertFalse(writer.flush(), "the caller must know durability was not reached by the deadline");
            assertFalse(Files.exists(file), "flush must not bypass the active writer");

            release.countDown();
            writer.flushTimeoutMillis = 5_000;
            assertTrue(writer.flush());
            assertEquals("new", Files.readString(file), "the queued newer state must be the final write");
        } finally {
            release.countDown();
            writer.shutdown();
        }
    }

    @Test
    void aSecondFlushAfterTimedOutShutdownRemainsBounded(@TempDir Path dir) throws Exception {
        ConfigWriter writer = new ConfigWriter();
        writer.flushTimeoutMillis = 20;
        java.util.concurrent.CountDownLatch claimed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        writer.afterBatchClaimedForTest = () -> {
            claimed.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        java.util.concurrent.ExecutorService hook = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            writer.enqueue(dir.resolve("index.json"), bytes("pending"));
            assertTrue(claimed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(writer.shutdown());

            Path late = dir.resolve("late.json");
            java.util.concurrent.Future<?> enqueue = hook.submit(() -> writer.enqueue(late, bytes("late")));
            enqueue.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            assertFalse(Files.exists(late), "shutdown must reject a late write instead of blocking to perform it");

            java.util.concurrent.Future<Boolean> result = hook.submit(writer::flush);
            assertFalse(result.get(500, java.util.concurrent.TimeUnit.MILLISECONDS));
        } finally {
            release.countDown();
            hook.shutdownNow();
            writer.flushTimeoutMillis = 5_000;
            writer.flush();
        }
    }

    @Test
    void realShutdownHookExitsAfterTheConfiguredDeadline(@TempDir Path dir) throws Exception {
        Process helper = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        TimedShutdownProcess.class.getName(),
                        dir.resolve("blocked.json").toString())
                .start();
        try {
            assertTrue(helper.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, helper.exitValue());
        } finally {
            helper.destroyForcibly();
        }
    }
}
