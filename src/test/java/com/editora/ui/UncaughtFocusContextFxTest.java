package com.editora.ui;

import javafx.collections.FXCollections;
import javafx.scene.control.ListView;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An uncaught exception thrown inside a JavaFX control's own event handling has a stack with no application
 * frames, so it names the control's class and nothing about <em>which</em> of the app's many ListViews it
 * was. The uncaught handler therefore records the focus owner beside it.
 *
 * <p>The <em>rendering</em> is what is pinned here, not the focus lookup: which window is focused is global
 * state every other FX test in the suite also moves, so asserting on a real focus owner is a test that
 * passes alone and fails in the suite.
 */
@Tag("fx")
class UncaughtFocusContextFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void namesTheControlAndItsStyleClasses() throws Exception {
        String described = FxTestSupport.callOnFx(() -> {
            ListView<String> list = new ListView<>(FXCollections.observableArrayList("a"));
            list.getStyleClass().add("git-tree"); // the class that says which panel this is
            return DebugLog.describeFocus(list);
        });
        assertTrue(described.contains("ListView"), described);
        assertTrue(described.contains("git-tree"), described);
    }

    @Test
    void saysNothingWhenNothingHasFocus() {
        assertEquals("", DebugLog.describeFocus(null));
    }

    @Test
    void theHandlerStillLogsTheThrowableItself() {
        DebugLog.install(); // idempotent; the app installs it from main
        DebugLog.clear();
        Thread.getDefaultUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), new IllegalStateException("background"));
        String log = DebugLog.snapshot();
        assertTrue(log.contains("background"), log);
        // Off the FX thread the scene graph must not be touched, so there is no focus note to add.
        assertTrue(!log.contains("[focus:"), log);
    }
}
