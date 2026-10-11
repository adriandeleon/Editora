package com.editora.ui;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.scene.text.Font;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Startup registers only the families the first frame can show and the rest after it has painted, on a
 * daemon thread. Every family must be registered with the toolkit once that load has run, and a caller that
 * needs one earlier (the font picker, a font-setting change) must be able to wait for it.
 *
 * <p>{@code FxTestSupport.bootToolkit} loads every family eagerly for the rest of the suite, so this test
 * resets the loader's bookkeeping (not the toolkit: registering a font twice is harmless) to walk the
 * startup path itself.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FontsDeferredLoadFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @SuppressWarnings("unchecked")
    private static void resetLoader() throws Exception {
        synchronized (Fonts.class) {
            for (String name : List.of("LOADED", "DONE")) {
                Field f = Fonts.class.getDeclaredField(name);
                f.setAccessible(true);
                ((Set<String>) f.get(null)).clear();
            }
            Field remaining = Fonts.class.getDeclaredField("remaining");
            remaining.setAccessible(true);
            remaining.set(null, null);
        }
    }

    @Test
    void firstPaintLoadsOnlyWhatTheFrameNeedsAndTheDeferredLoadBringsTheRest() throws Exception {
        resetLoader();
        try {
            FxTestSupport.runOnFx(() -> Fonts.loadForFirstPaint("IBM Plex Mono"));
            assertEquals(Set.of(Fonts.UI, Fonts.DEFAULT, "IBM Plex Mono"), Fonts.loadedFamilies());
            assertFalse(Fonts.allLoaded());

            // The deferred load, as App.start arms it after the first painted frame.
            FxTestSupport.runOnFx(Fonts::loadRemainingAfterNextPaint);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Fonts.allLoaded() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(Fonts.allLoaded(), "the deferred load did not register every family in time");
            CompletableFuture<Void> load = Fonts.loadRemainingAsync();
            assertTrue(load.isDone(), "a second request returns the finished load");

            List<String> families = Font.getFamilies();
            for (String family : Fonts.BUNDLED) {
                assertTrue(families.contains(family), family + " is not registered with the toolkit");
            }
            assertTrue(families.contains(Fonts.UI));
        } finally {
            Fonts.ensureAllLoaded(); // leave the suite as bootToolkit set it up
        }
    }

    @Test
    void aCallerThatNeedsAFamilyEarlyWaitsForTheDeferredLoadOrLoadsItInline() throws Exception {
        resetLoader();
        try {
            FxTestSupport.runOnFx(() -> Fonts.loadForFirstPaint(null));
            assertEquals(Set.of(Fonts.UI, Fonts.DEFAULT), Fonts.loadedFamilies());

            // No deferred load started yet: a settings change picks a family and gets just that one, inline.
            Fonts.ensureFamily("Cascadia Code");
            assertTrue(Fonts.loadedFamilies().contains("Cascadia Code"));
            assertFalse(Fonts.loadedFamilies().contains("Fira Code"));
            Fonts.ensureFamily("Consolas"); // a system family: nothing to load, nothing to wait for
            assertFalse(Fonts.allLoaded());

            // The font picker needs every family: it waits for the deferred load it finds running.
            Fonts.loadRemainingAsync();
            Fonts.ensureAllLoaded();
            assertTrue(Fonts.allLoaded());
            assertTrue(Fonts.loadRemainingAsync().isDone());
        } finally {
            Fonts.ensureAllLoaded();
        }
    }

    @Test
    void ensureAllLoadedLoadsInlineWhenNothingDeferredWasStarted() throws Exception {
        resetLoader();
        try {
            assertFalse(Fonts.allLoaded());
            Fonts.ensureAllLoaded();
            assertTrue(Fonts.allLoaded());
            assertEquals(6, Fonts.loadedFamilies().size(), "five editor families plus the chrome font");
        } finally {
            Fonts.ensureAllLoaded();
        }
    }
}
