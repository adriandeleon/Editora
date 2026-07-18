package com.editora.ui;

import java.util.List;
import java.util.Map;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for #443: "Add to Dictionary" re-runs the spell pass in EVERY window's tabs, not just the
 * one where the word was added — the user dictionary is shared app-wide, so another window's buffers otherwise
 * keep the stale squiggle on the just-added word until they happen to apply a setting. Here window B's spell
 * overlay cache is seeded, "Add to Dictionary" is invoked in window A, and window B's cache must be cleared
 * (which is what {@code SpellCheckOverlay.refresh()} does when the dictionary changes).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DictionaryBroadcastFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create(); // window A
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void addToDictionaryInOneWindowRefreshesTheOtherWindowsTabs() throws Exception {
        WindowManager wm = fx.windowManager;

        // A second window (as "New Window" creates), sharing the same SharedConfig / user dictionary.
        MainController b = FxTestSupport.callOnFx(() -> {
            wm.newWindow();
            List<?> holders = FxTestSupport.field(wm, "windows");
            Object holder = holders.get(holders.size() - 1);
            return (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
        });
        assertTrue(b != fx.controller, "a genuinely second window");

        // Open a buffer in window B.
        EditorBuffer bufferB = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setContent("Kubernetes zzz\n");
            FxTestSupport.call(b, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buf, true);
            return buf;
        });

        // Seed window B's overlay cache, invoke "Add to Dictionary" in window A, and read the result — all in
        // ONE synchronous FX pass, so no intervening pulse (e.g. a deferred refresh from addBuffer) can clear
        // the seed and make the test flaky. SpellCheckOverlay.refresh() clears the cache synchronously.
        boolean[] state = FxTestSupport.callOnFx(() -> {
            Map<String, Boolean> cache =
                    FxTestSupport.field(FxTestSupport.<Object>field(bufferB, "spellOverlay"), "spellCache");
            cache.clear();
            cache.put("kubernetes", true); // a cached "misspelled" verdict window B already computed
            boolean seeded = !cache.isEmpty();
            FxTestSupport.call(fx.controller, "addUserWordAndRefreshAll", new Class<?>[] {String.class}, "Kubernetes");
            return new boolean[] {seeded, cache.isEmpty()};
        });

        assertTrue(state[0], "window B's overlay cache was seeded");
        assertTrue(state[1], "window B's overlay cache must be cleared by the cross-window refresh");
    }
}
