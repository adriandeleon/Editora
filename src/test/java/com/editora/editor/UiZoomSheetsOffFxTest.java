package com.editora.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.perf.BlockingSites;
import com.editora.ui.DefaultUiZoom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A zoomed copy of a stylesheet is never read or scaled on the FX thread (PERF-01, Pauta 6.0.1): asked there for the
 * first time it answers the 100% sheet, and the copy is made on {@code ui-zoom-sheets}.
 */
@Tag("fx")
class UiZoomSheetsOffFxTest {

    @BeforeAll
    static void boot() {
        try {
            Platform.startup(() -> {});
        } catch (IllegalStateException alreadyRunning) {
            // another FX test booted the toolkit in this JVM
        }
        Platform.setImplicitExit(false);
    }

    /**
     * Lets every copy this test asked for land while the zoom is still 170% (the theme's lands after the test's
     * own), then zooms back to 100% the way the app does, sheets and all. Setting only the number with
     * {@link UiZoom#init} left the theme's 170% sheet in force: every later class in the fork laid its text out 1.7
     * times larger at a zoom that read 100%.
     */
    @AfterEach
    void back() throws Exception {
        BlockingSites.listenForTest(null);
        UiZoom.awaitSheetsForTest();
        DefaultUiZoom.restore();
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> work) throws Exception {
        CompletableFuture<T> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                done.complete(work.call());
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        });
        return done.get(10, TimeUnit.SECONDS);
    }

    @Test
    void aSheetFirstAskedOnFxIsMadeOffItAndThenServed() throws Exception {
        String css = UiZoom.class.getResource("/com/editora/styles/app.css").toExternalForm();
        int percent = 170; // a zoom no other test asks for, so nothing has made this copy yet
        Thread fx = onFx(Thread::currentThread);
        List<String> onFxThread = Collections.synchronizedList(new ArrayList<>());
        BlockingSites.listenForTest((site, thread) -> {
            if (thread == fx) {
                onFxThread.add(site);
            }
        });
        String first = onFx(() -> {
            UiZoom.init(percent);
            return UiZoom.sheet(css);
        });
        assertEquals(css, first, "not made yet: the 100% sheet serves meanwhile");
        UiZoom.awaitSheetsForTest();
        String then = onFx(() -> UiZoom.sheet(css));
        assertTrue(then.startsWith("data:text/css"), "then the zoomed copy");
        assertEquals(css, UiZoom.base(then), "which remembers its source");
        assertFalse(onFxThread.contains("UiZoom.build"), "nothing read or scaled on the FX thread: " + onFxThread);
    }
}
