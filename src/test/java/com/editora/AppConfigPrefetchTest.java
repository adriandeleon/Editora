package com.editora;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.config.ConfigManager;
import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The config-prefetch thread also parses the message catalog, so {@code App.start} does not do it on the FX
 * thread — the prefetch already has the setting that selects the language. It starts before the
 * single-instance probe, so until its gate opens it may only read.
 */
class AppConfigPrefetchTest {

    @AfterEach
    void restoreEnglish() {
        Messages.init("en"); // the catalog is process-wide; leave it as the other tests expect
    }

    private static CompletableFuture<?> prefetch(Path dir, CountDownLatch gate) {
        return App.prefetchConfig(dir, List.of(), gate);
    }

    private static Object member(Object boot, String name) throws Exception {
        Method m = boot.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(boot);
    }

    @Test
    void thePrefetchLoadsTheCatalogForTheConfiguredLanguage(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"uiLanguage\":\"es\"}");
        Messages.init("en");
        int before = Messages.generation();
        String english = Messages.tr("menu.save");

        CountDownLatch gate = new CountDownLatch(0); // open: a launch that is not forwarded
        Object boot = prefetch(dir, gate).get(30, TimeUnit.SECONDS);
        try {
            assertEquals("es", member(boot, "language"), "the bootstrap says which catalog it loaded");
            assertEquals("es", Messages.current());
            assertEquals(before + 1, Messages.generation(), "loaded exactly once, by the prefetch");
            assertNotEquals(english, Messages.tr("menu.save"), "the Spanish overlay is active");
            assertNotNull(member(boot, "plugins"), "the plugin manifests were discovered off-thread too");
        } finally {
            ((ConfigManager) member(boot, "manager")).shared().shutdown();
        }
    }

    @Test
    void theLoadWaitsForTheGateAndWritesNothingBeforeIt(@TempDir Path dir) throws Exception {
        // A settings file from before the schema stamp: loading it migrates and, on the next save, rewrites
        // it; the one thing the pre-gate stage may never do is touch the directory.
        Files.writeString(dir.resolve("settings.json"), "{\"uiLanguage\":\"en\"}");
        List<Path> before;
        try (var files = Files.list(dir)) {
            before = files.sorted().toList();
        }
        CountDownLatch gate = new CountDownLatch(1);
        CompletableFuture<?> future = prefetch(dir, gate);
        Thread.sleep(200); // the warm-up has had time to run; the load must still be waiting
        assertFalse(future.isDone(), "the load waits for the single-instance decision");
        try (var files = Files.list(dir)) {
            assertEquals(before, files.sorted().toList(), "nothing created, moved or written before the gate");
        }
        gate.countDown();
        Object boot = future.get(30, TimeUnit.SECONDS);
        try {
            assertTrue(future.isDone());
            assertNotNull(member(boot, "manager"));
        } finally {
            ((ConfigManager) member(boot, "manager")).shared().shutdown();
        }
    }
}
