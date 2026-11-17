package com.editora.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bundled keymap is read and parsed once; a later switch or rebind's base reads nothing (PERF-01, Pauta 6.0.1). */
class KeymapManagerParsedTest {

    @AfterEach
    void stopListening() {
        BlockingSites.listenForTest(null);
    }

    @Test
    void aKeymapLoadedAgainIsNotReadAgain() {
        new KeymapManager().loadNamed("default", false);
        List<String> heard = Collections.synchronizedList(new ArrayList<>());
        BlockingSites.listenForTest((site, thread) -> {
            if (thread == Thread.currentThread()) {
                heard.add(site);
            }
        });
        KeymapManager again = new KeymapManager();
        again.loadNamed("default", false);
        assertFalse(heard.contains("KeymapManager.parsed"), "the kept copy serves: " + heard);
        assertFalse(again.bindings().isEmpty(), "and it holds the bindings");
    }

    @Test
    void theOtherBundledKeymapsAreReadAheadOffTheCallingThread() throws Exception {
        new KeymapManager().loadNamed("default", false);
        boolean all = com.editora.testkit.Await.holdsWithin(
                () -> KeymapManager.AVAILABLE.keySet().stream()
                        .allMatch(n -> KeymapManager.isParsedForTest("/com/editora/keymaps/" + n + ".json")),
                java.time.Duration.ofSeconds(10));
        assertTrue(all, "every bundled keymap is parsed by the warm-up thread");
    }

    @Test
    void aSwitchWaitsForEveryKeymapAndBothOfItsVariantsThenGoesOnAtOnce() throws Exception {
        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
        KeymapManager.whenParsed(ran::countDown); // off FX here: runs where the parse lands, or at once
        assertTrue(ran.await(10, java.util.concurrent.TimeUnit.SECONDS), "the switch went on");
        assertTrue(KeymapManager.allParsed());
        for (String name : KeymapManager.AVAILABLE.keySet()) {
            assertTrue(KeymapManager.isParsedForTest("/com/editora/keymaps/" + name + ".json"), name);
            if (KeymapManager.hasMacVariant(name)) {
                assertTrue(KeymapManager.isParsedForTest("/com/editora/keymaps/" + name + ".mac.json"), name + " mac");
            }
        }
        List<String> heard = Collections.synchronizedList(new ArrayList<>());
        BlockingSites.listenForTest((site, thread) -> heard.add(site));
        boolean[] now = {false};
        KeymapManager.whenParsed(() -> now[0] = true);
        assertTrue(now[0], "once parsed, a switch goes on in place");
        KeymapManager.defaults("default", true); // a Mac variant asked on any system: kept, not read
        assertFalse(heard.contains("KeymapManager.parsed"), "nothing read again: " + heard);
    }
}
