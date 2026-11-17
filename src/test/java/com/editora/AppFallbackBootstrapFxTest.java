package com.editora;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.config.ConfigManager;
import com.editora.config.SharedConfig;
import com.editora.i18n.Messages;
import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The start-up fallback (the prefetch failed, or {@code main()} was bypassed) loads on a thread {@code start()} does
 * not wait for (PERF-01, Pauta 6.0.1): the FX thread opens no log, takes no lock, reads no ledger or store. That
 * thread still claims the instance lock first, before the settings are loaded or anything is written, so a second
 * instance on the same directory is still told apart.
 */
@Tag("fx")
class AppFallbackBootstrapFxTest {

    private final List<String> heard = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @AfterEach
    void restore() {
        BlockingSites.listenForTest(null);
        Messages.init("en");
        com.editora.ui.DebugLog.detachFileForTest();
        com.editora.process.ProcessRegistry.setLedgerFile(null);
    }

    private App.Bootstrap fallback(Path dir) throws Exception {
        CompletableFuture<App.Bootstrap> landed = new CompletableFuture<>();
        CompletableFuture<Boolean> onFx = new CompletableFuture<>();
        Platform.runLater(() -> App.fallbackBootstrap(dir, List.of(), boot -> {
            onFx.complete(Platform.isFxApplicationThread());
            landed.complete(boot);
        }));
        App.Bootstrap boot = landed.get(60, TimeUnit.SECONDS);
        assertTrue(onFx.get(), "the first window is built on the FX thread once the load lands");
        return boot;
    }

    @Test
    void theFallbackTakesTheLockFirstOffTheFxThread(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"uiLanguage\":\"en\"}");
        BlockingSites.listenForTest((site, thread) ->
                heard.add((Platform.isFxApplicationThread() ? "FX " : "") + site + " @" + thread.getName()));
        App.Bootstrap boot = fallback(dir);
        BlockingSites.listenForTest(null);
        try {
            assertTrue(boot.manager().shared().isPrimaryInstance(), "the only process on the directory");
            List<String> onFx = new ArrayList<>();
            for (String h : heard) {
                if (h.startsWith("FX ")) {
                    onFx.add(h);
                }
            }
            assertEquals(List.of(), onFx, "nothing loaded, locked or read on the FX thread");
            assertFalse(heard.isEmpty(), "the load was heard");
            assertTrue(
                    heard.stream().anyMatch(h -> h.startsWith(App.FALLBACK_FIRST_WINDOW_SITE + " @")),
                    "the first window's fonts, catalog, keymap and frame are read off the FX thread: " + heard);
            int lock = -1;
            int firstStore = -1;
            for (int i = 0; i < heard.size(); i++) {
                String h = heard.get(i);
                if (lock < 0 && h.startsWith("InstanceLock.claim @config-prefetch")) {
                    lock = i;
                }
                if (firstStore < 0 && h.startsWith("json ")) {
                    firstStore = i;
                }
            }
            assertTrue(lock >= 0, "the lock is taken on the load's thread: " + heard);
            assertTrue(firstStore < 0 || lock < firstStore, "before any store is read or written: " + heard);
        } finally {
            boot.manager().shared().shutdown();
        }
    }

    @Test
    void aSecondInstanceIsStillToldApart(@TempDir Path dir) throws Exception {
        SharedConfig first = new ConfigManager(dir).shared();
        assertTrue(first.claimInstance(), "the first process claims the directory");
        try {
            App.Bootstrap boot = fallback(dir);
            try {
                assertFalse(boot.manager().shared().isPrimaryInstance(), "the fallback's process is the second");
            } finally {
                boot.manager().shared().shutdown();
            }
        } finally {
            first.shutdown();
        }
    }
}
