package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javafx.application.Platform;

import com.editora.perf.BlockingSites;
import com.editora.perf.OffFx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1): a server command set in Settings a moment ago is looked at (is it Editora's own copy?) off
 * the FX thread. The support is applied when the look lands, never with a command guessed meanwhile, and nothing is
 * looked at on the FX thread.
 */
@Tag("fx")
class LspOwnCopiesOffFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        BlockingSites.listenForTest(null);
        OffFx.runnerForTest(null);
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void aNewServerCommandIsLookedAtOffTheFxThreadThenApplied() throws Exception {
        fx = FxWindowFixture.create();
        MainController mc = fx.controller;
        String command = "my-html-server --stdio --from " + System.nanoTime(); // a key nothing has looked at
        List<String> onFx = new CopyOnWriteArrayList<>();
        List<Runnable> held = new CopyOnWriteArrayList<>();
        OffFx.runnerForTest((name, task) -> held.add(task));
        BlockingSites.listenForTest((site, thread) -> {
            if (Platform.isFxApplicationThread()) {
                onFx.add(site);
            }
        });
        FxTestSupport.runOnFx(() -> {
            mc.config.getSettings().setHtmlLspCommand(command);
            mc.lspCoordinator.applySupport();
        });
        Path dir = mc.config.getConfigDir();
        String jdk = mc.config.getSettings().getMavenJdkHome();
        assertFalse(LspServerCommands.ownCopyKnown("html", command, dir, jdk), "not looked at on the FX thread");
        assertFalse(held.isEmpty(), "a look is under way");

        held.forEach(Runnable::run); // the look lands
        SettingsRig.awaitFx("the support applied", () -> LspServerCommands.ownCopyKnown("html", command, dir, jdk));
        FxTestSupport.drainFx();
        assertEquals(
                command,
                FxTestSupport.callOnFx(
                        () -> mc.lspCoordinator.effectiveCommands().get("html")));
        assertTrue(onFx.stream().noneMatch(s -> s.startsWith("lsp-own-copies")), "heard on FX: " + onFx);
    }
}
