package com.editora.ui;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bringing a window forward for an externally-delivered launch pins it above other windows briefly — and
 * must always let go again.
 *
 * <p>The pin exists because GNOME/Mutter refuses a background application's focus request (it raises
 * {@code _NET_WM_STATE_DEMANDS_ATTENTION} instead, which is the "click to bring it forward" notification),
 * while {@code alwaysOnTop} maps to a window <em>state</em> the compositor does not arbitrate. Whether that
 * actually beats a given compositor's focus-stealing prevention cannot be asserted headlessly — it is a
 * property of the desktop, not of this code.
 *
 * <p>What <em>can</em> be pinned down is the half that would be a worse bug than the one it fixes: a window
 * left permanently above every other application. So this asserts the pin is taken and, without any further
 * help, released again.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExternalLaunchRaiseFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void presentingForAnExternalLaunchPinsTheWindowAndThenReleasesIt() throws Exception {
        Stage stage = FxTestSupport.callOnFx(() -> {
            Stage s = new Stage();
            s.setScene(new Scene(new StackPane(), 300, 200));
            s.show();
            return s;
        });
        try {
            assertFalse(FxTestSupport.callOnFx(stage::isAlwaysOnTop), "precondition: not pinned");

            Method present = WindowManager.class.getDeclaredMethod("presentForExternalLaunch", Stage.class);
            present.setAccessible(true);
            FxTestSupport.runOnFx(() -> {
                try {
                    present.invoke(null, stage);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });

            // Taken synchronously: the raise has to happen the moment the launch is delivered, not a frame
            // later, or the window sits behind the file manager for exactly as long as it takes to notice.
            assertTrue(FxTestSupport.callOnFx(stage::isAlwaysOnTop), "the window should be pinned to raise it");

            assertTrue(
                    waitUntil(() -> !FxTestSupport.callOnFx(stage::isAlwaysOnTop)),
                    "the pin was never released — the window would stay above every other application");
        } finally {
            FxTestSupport.runOnFx(stage::close);
        }
    }

    /** Polls for up to ~5 s; the release rides a short animation timer, not the caller's thread. */
    private static boolean waitUntil(BooleanCheck condition) throws Exception {
        for (int i = 0; i < 250; i++) {
            if (condition.get()) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        return false;
    }

    /** A condition that may throw, since reading FX state hops threads. */
    private interface BooleanCheck {
        boolean get() throws Exception;
    }
}
