package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.editora.history.HistoryBlobStore;
import com.editora.history.HistoryService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class HistoryDurabilityFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    @Test
    void gcKeepsBlobsReferencedByTheDurableIndexUntilItsReplacementLands() throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        CountDownLatch release = new CountDownLatch(1);
        try {
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            HistoryService service = fx.shared.historyService();
            ExecutorService worker = FxTestSupport.field(service, "exec");
            HistoryBlobStore blobs = FxTestSupport.field(service, "blobs");
            Path file = dir.resolve("history.txt");

            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setHistoryMaxPerFile(1);
                history.record(file, "old durable body", "save");
            });
            barrier(worker);
            FxTestSupport.runOnFx(() -> {});
            assertTrue(fx.shared.flushWrites());
            barrier(worker);

            String oldSha = HistoryBlobStore.sha256("old durable body");
            assertEquals("old durable body", blobs.get(oldSha));
            assertTrue(Files.readString(fx.shared.getHistoryFile()).contains(oldSha));

            Object writer = FxTestSupport.field(fx.shared, "writer");
            CountDownLatch claimed = new CountDownLatch(1);
            set(writer, "afterBatchClaimedForTest", (Runnable) () -> {
                claimed.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            FxTestSupport.runOnFx(() -> history.record(file, "new body", "save"));
            barrier(worker);
            FxTestSupport.runOnFx(() -> {});
            assertTrue(claimed.await(5, TimeUnit.SECONDS));
            barrier(worker);

            assertTrue(Files.readString(fx.shared.getHistoryFile()).contains(oldSha));
            assertNotNull(blobs.get(oldSha), "GC must retain every blob named by the durable index");

            release.countDown();
            assertTrue(fx.shared.flushWrites());
            barrier(worker);
            String newSha = HistoryBlobStore.sha256("new body");
            assertTrue(Files.readString(fx.shared.getHistoryFile()).contains(newSha));
            assertEquals("new body", blobs.get(newSha));
        } finally {
            release.countDown();
            fx.dispose();
            fx.shared.shutdown();
        }
    }

    @Test
    void failedIndexPublicationPreservesDurableAndCurrentRevisionBodies() throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        Path index = fx.shared.getHistoryFile();
        Path backup = index.resolveSibling("index.backup");
        try {
            HistoryCoordinator history = FxTestSupport.field(fx.controller, "historyCoordinator");
            HistoryService service = fx.shared.historyService();
            ExecutorService worker = FxTestSupport.field(service, "exec");
            HistoryBlobStore blobs = FxTestSupport.field(service, "blobs");
            Path file = dir.resolve("failed-history.txt");

            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setHistoryMaxPerFile(1);
                history.record(file, "durable body", "save");
            });
            barrier(worker);
            FxTestSupport.runOnFx(() -> {});
            assertTrue(fx.shared.flushWrites());
            barrier(worker);

            String durableSha = HistoryBlobStore.sha256("durable body");
            Files.move(index, backup);
            Files.createDirectory(index);
            Files.writeString(index.resolve("block"), "prevent replacement");

            FxTestSupport.runOnFx(() -> history.record(file, "current body", "save"));
            barrier(worker);
            FxTestSupport.runOnFx(() -> {});
            assertFalse(fx.shared.flushWrites(), "the failed atomic index move must reach the durability result");
            barrier(worker);

            Files.delete(index.resolve("block"));
            Files.delete(index);
            Files.move(backup, index);
            assertTrue(Files.readString(index).contains(durableSha));
            assertEquals("durable body", blobs.get(durableSha));
            assertEquals("current body", blobs.get(HistoryBlobStore.sha256("current body")));
        } finally {
            if (Files.isDirectory(index)) {
                Files.deleteIfExists(index.resolve("block"));
                Files.delete(index);
            }
            if (Files.exists(backup)) {
                Files.move(backup, index, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            fx.dispose();
            fx.shared.shutdown();
        }
    }

    private static void barrier(ExecutorService executor) throws Exception {
        executor.submit(() -> {}).get(10, TimeUnit.SECONDS);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
