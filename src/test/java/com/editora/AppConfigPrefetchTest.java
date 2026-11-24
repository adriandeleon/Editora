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
        // The prefetch also opened the session log and pointed the process ledger at the temporary folder.
        com.editora.ui.DebugLog.detachFileForTest();
        com.editora.process.ProcessRegistry.setLedgerFile(null);
    }

    private static CompletableFuture<?> prefetch(Path dir, CountDownLatch gate) {
        return App.prefetchConfig(dir, List.of(), gate);
    }

    private static Object member(Object boot, String name) throws Exception {
        Method m = boot.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(boot);
    }

    /**
     * The keychain is asked on a thread of its own while the prefetch parses the bundled tables (startup R3): what
     * that thread throws still fails the load, unwrapped, as when it ran inline.
     */
    @Test
    void aStepOnItsOwnThreadFailsTheLoadWithWhatItThrew() {
        IllegalStateException thrown = new IllegalStateException("the keychain broke");
        java.util.concurrent.FutureTask<Void> failing = new java.util.concurrent.FutureTask<>(() -> {
            throw thrown;
        });
        Thread.ofVirtual().start(failing);
        RuntimeException caught = org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class, () -> App.awaitRethrowing(failing));
        org.junit.jupiter.api.Assertions.assertSame(thrown, caught);

        StackOverflowError error = new StackOverflowError();
        java.util.concurrent.FutureTask<Void> erring = new java.util.concurrent.FutureTask<>(() -> {
            throw error;
        });
        Thread.ofVirtual().start(erring);
        org.junit.jupiter.api.Assertions.assertSame(
                error, org.junit.jupiter.api.Assertions.assertThrows(Error.class, () -> App.awaitRethrowing(erring)));

        java.util.concurrent.FutureTask<Void> fine = new java.util.concurrent.FutureTask<>(() -> {}, null);
        Thread.ofVirtual().start(fine);
        App.awaitRethrowing(fine); // nothing thrown
    }

    /**
     * The first window's pieces that read no setting run beside the settings load (startup R3): when the load fails,
     * it waits for them before letting the directory go, and reports its own failure rather than theirs.
     */
    @Test
    void aFailingLoadWaitsForTheStepBesideItQuietly() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.FutureTask<Void> slowFailing = new java.util.concurrent.FutureTask<>(() -> {
            release.await();
            throw new IllegalStateException("the side step broke");
        });
        Thread.ofVirtual().start(slowFailing);
        CompletableFuture<Void> waited = CompletableFuture.runAsync(() -> App.awaitQuietly(slowFailing));
        assertFalse(waited.isDone(), "it waits for the step to end");
        release.countDown();
        waited.get(10, TimeUnit.SECONDS); // ended; what the step threw is not rethrown
        assertTrue(slowFailing.isDone());
    }

    /** The warm-up beside the JSON warm-up reads only bundled files, and leaves the catalog init uses. */
    @Test
    void theBundledWarmUpLeavesTheEnglishCatalogParsed() {
        App.warmBundledForLoad();
        Messages.init("en");
        assertEquals("File: New", Messages.tr("command.file.new"), "the catalog the warm-up parsed is the one in use");
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
        // the warm-up has run: the prefetch thread now waits at the gate, and the load must still be waiting
        com.editora.testkit.Await.until(
                () -> Thread.getAllStackTraces().entrySet().stream()
                        .anyMatch(e -> e.getKey().getName().equals("config-prefetch")
                                && java.util.Arrays.stream(e.getValue())
                                        .anyMatch(f -> f.getClassName().endsWith("CountDownLatch")
                                                && f.getMethodName().equals("await"))),
                "the prefetch to reach the gate");
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
