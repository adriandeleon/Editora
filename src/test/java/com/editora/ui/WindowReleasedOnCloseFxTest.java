package com.editora.ui;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;

import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing a window must let its {@link MainController} — and with it every buffer, panel and service the
 * window owned — become collectable.
 *
 * <p>Two things used to pin a closed window for the life of the process, each through RichTextFX:
 *
 * <ul>
 *   <li>a read-only console area created with {@code setShowCaret(CaretVisibility.OFF)}: OFF (and ON)
 *       subscribe the caret to a <em>static</em> stream inside {@code CaretNode}, whose observer list then
 *       holds the area, its panel and the window;
 *   <li>an editor area that had focus when the window closed: its caret blink timer is a running JavaFX
 *       animation, which is a GC root, and nothing stopped it because the buffer never disposed its area.
 * </ul>
 *
 * <p>Each retained window cost about 20 MB. In the app that is a leak per closed project window; in the
 * test suite it was enough to exhaust a 4 GB heap. A weak reference keeps both closed (see
 * {@link BufferReleasedOnCloseFxTest} for why a weak reference is the honest measurement).
 */
@Tag("fx")
class WindowReleasedOnCloseFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aClosedWindowIsReleased(@TempDir Path dir) throws Exception {
        assertTrue(collected(openAndClose(dir, false)), "a closed window is still reachable");
    }

    @Test
    void aWindowClosedWhileItsEditorHadFocusIsReleased(@TempDir Path dir) throws Exception {
        assertTrue(
                collected(openAndClose(dir, true)),
                "a window closed while its editor had focus is still reachable (caret blink timer?)");
    }

    /**
     * Builds a window, opens a file in it, optionally gives the editor real focus, and disposes the window.
     * The controller is only ever referenced inside this call: a live local slot on the test's own frame
     * would itself root it and make the assertion meaningless.
     */
    private static WeakReference<MainController> openAndClose(Path dir, boolean focusEditor) throws Exception {
        Path file = Files.writeString(dir.resolve("A.java"), "class A {\n    void m() {}\n}\n");
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
            awaitBuffer(fx.controller);
            if (focusEditor) {
                boolean focused = FxTestSupport.callOnFx(() -> {
                    Stage stage = FxTestSupport.field(fx.controller, "stage");
                    stage.requestFocus();
                    EditorBuffer buffer =
                            (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                    buffer.getArea().requestFocus();
                    return buffer.getArea().isFocused();
                });
                assertTrue(focused, "the editor area took focus, so its caret blink timer is running");
                FxTestSupport.drainFx();
            }
            return new WeakReference<>(fx.controller);
        } finally {
            fx.dispose();
        }
    }

    private static void awaitBuffer(MainController controller) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            boolean open = FxTestSupport.callOnFx(
                    () -> FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {}) != null);
            if (open) {
                return;
            }
            Thread.sleep(20); // the file loads off the FX thread; this only waits for that hand-back
        }
    }

    /** Waits, with GC pressure, for the referent to be cleared. Generous: this is a liveness assertion. */
    private static boolean collected(WeakReference<?> ref) throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            FxTestSupport.runOnFx(() -> {}); // drain the FX queue: a pending event can hold the last edge
            System.gc();
            if (ref.get() == null) {
                return true;
            }
            Thread.sleep(100);
        }
        return ref.get() == null;
    }
}
