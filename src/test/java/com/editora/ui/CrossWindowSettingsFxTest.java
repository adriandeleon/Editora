package com.editora.ui;

import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A preference toggled through one window's command reaches every window. The {@link Settings} object is
 * shared, but each window applies it to its own buffers — and the palette/key-binding commands applied only
 * in the window they ran in, so another window kept the old view while holding the new value (its next toggle
 * then flipped the value back with nothing visibly changing).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossWindowSettingsFxTest {

    private FxWindowFixture fx;
    private MainController a;
    private MainController b;
    private EditorBuffer bufferA;
    private EditorBuffer bufferB;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        a = fx.controller;
        b = FxTestSupport.callOnFx(() -> {
            fx.windowManager.newWindow();
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            return (MainController)
                    FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
        });
        assertNotSame(a, b, "a genuinely second window");
        bufferA = addBuffer(a);
        bufferB = addBuffer(b);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static EditorBuffer addBuffer(MainController window) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("one\ntwo\n");
            FxTestSupport.call(window, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    /** Runs {@code commandId} in window A, lets its coalesced save run, then delivers the pending re-apply. */
    private void runInWindowA(String commandId) throws Exception {
        CommandRegistry registry = FxTestSupport.field(a, "registry");
        assertTrue(FxTestSupport.callOnFx(() -> registry.run(commandId)), commandId + " is registered");
        // The save requested by the command runs on a later pulse, and that save is where the other windows
        // learn of the change. One drain is not guaranteed to span that pulse on a slow machine, so give it a
        // few frames: flushing with nothing pending is a no-op.
        for (int i = 0; i < 10; i++) {
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(fx.windowManager::flushPendingSettingsBroadcast);
            Thread.sleep(20);
        }
    }

    private static boolean lineNumbers(EditorBuffer buffer) throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.<Boolean>field(buffer, "lineNumbersVisible"));
    }

    @Test
    void aBespokeToggleCommandReachesTheOtherWindow() throws Exception {
        Settings settings = fx.shared.getSettings();
        boolean before = settings.isShowLineNumbers();
        assertEquals(before, lineNumbers(bufferB), "window B starts in step with the setting");

        runInWindowA("view.toggleLineNumbers");

        assertEquals(!before, settings.isShowLineNumbers());
        assertEquals(!before, lineNumbers(bufferA), "the invoking window applied it itself");
        assertEquals(!before, lineNumbers(bufferB), "and the other window re-applied the shared setting");

        runInWindowA("view.toggleLineNumbers"); // and back, so the classes' other test starts from defaults
        assertEquals(before, lineNumbers(bufferB));
    }

    @Test
    void aGenericToggleSettingCommandReachesTheOtherWindow() throws Exception {
        Settings settings = fx.shared.getSettings();
        boolean before = settings.isStickyScroll();
        assertEquals(before, FxTestSupport.callOnFx(bufferB::isStickyScrollEnabled));

        runInWindowA("view.toggleStickyScroll");

        assertEquals(!before, settings.isStickyScroll());
        assertEquals(!before, FxTestSupport.callOnFx(bufferB::isStickyScrollEnabled));
    }

    @Test
    void aChangeMadeInTheOtherWindowComesBackToo() throws Exception {
        Settings settings = fx.shared.getSettings();
        boolean before = settings.isShowLineNumbers();
        CommandRegistry registryB = FxTestSupport.field(b, "registry");

        FxTestSupport.callOnFx(() -> registryB.run("view.toggleLineNumbers"));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(fx.windowManager::flushPendingSettingsBroadcast);
        assertEquals(!before, lineNumbers(bufferA), "window A follows a toggle made in window B");

        FxTestSupport.callOnFx(() -> registryB.run("view.toggleLineNumbers"));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(fx.windowManager::flushPendingSettingsBroadcast);
        assertEquals(before, lineNumbers(bufferA));
    }
}
