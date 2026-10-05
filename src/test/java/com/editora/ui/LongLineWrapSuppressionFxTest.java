package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file with a giant line is loaded with word wrap held off, because wrapping that paragraph is the layout
 * cost the load path exists to avoid. Any later settings re-apply used to turn wrap straight back on.
 */
@Tag("fx")
class LongLineWrapSuppressionFxTest {

    @TempDir
    Path dir;

    private static boolean waitUntil(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition)) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    @Test
    void aSettingsReApplyLeavesWrapOffUntilTheUserAsksForItOnThatBuffer() throws Exception {
        FxTestSupport.bootToolkit();
        Path file = dir.resolve("minified.js");
        String content = "x".repeat(FileWorkflowCoordinator.LONG_LINE_FILE_CHARS + 16);
        Files.writeString(file, content);
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            fx.shared.getSettings().setWordWrap(true);
            EditorBuffer plain = FxTestSupport.callOnFx(() -> {
                EditorBuffer b = new EditorBuffer();
                b.setContent("short\n");
                FxTestSupport.call(
                        fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true);
                return b;
            });
            FxTestSupport.runOnFx(() -> FxTestSupport.call(
                    FxTestSupport.field(fx.controller, "fileWorkflows"),
                    "openPath",
                    new Class<?>[] {Path.class},
                    file));
            EditorBuffer buffer = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
            assertTrue(waitUntil(() -> content.equals(buffer.getContent())), "background load did not complete");
            FxTestSupport.drainFx();
            assertFalse(FxTestSupport.callOnFx(() -> buffer.getArea().isWrapText()), "precondition: safe profile");

            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            FxTestSupport.runOnFx(() -> registry.run("view.toggleMinimap")); // any settings change at all
            FxTestSupport.runOnFx(() -> fx.windowManager.broadcastSettingsApplied()); // as a Settings switch does

            assertFalse(FxTestSupport.callOnFx(() -> buffer.getArea().isWrapText()), "wrap stays held off");
            assertTrue(FxTestSupport.callOnFx(() -> plain.getArea().isWrapText()), "other buffers follow the setting");

            FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap")); // the explicit opt-in, on this buffer
            assertTrue(fx.shared.getSettings().isWordWrap(), "the preference was on already and stays on");
            assertTrue(FxTestSupport.callOnFx(() -> buffer.getArea().isWrapText()), "and this buffer now wraps");
            FxTestSupport.runOnFx(() -> fx.windowManager.broadcastSettingsApplied());
            assertTrue(FxTestSupport.callOnFx(() -> buffer.getArea().isWrapText()), "the opt-in survives a re-apply");

            FxTestSupport.runOnFx(() -> registry.run("view.toggleWordWrap"));
            assertFalse(fx.shared.getSettings().isWordWrap(), "after which the command is the plain toggle again");
        } finally {
            fx.dispose();
        }
    }
}
