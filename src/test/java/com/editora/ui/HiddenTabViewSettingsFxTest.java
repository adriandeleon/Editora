package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Tab;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A per-buffer view setting (tab size, rulers, line numbers …) changed in Settings re-laid out every open
 * buffer, hidden tabs included: each spinner step cost a few milliseconds per open file. The buffers on screen
 * change at once; a hidden one when its tab is shown, or before it is saved.
 */
@Tag("fx")
class HiddenTabViewSettingsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void aTabSizeChangeReachesTheShownBufferNowAndAHiddenOneWhenItIsShown() throws Exception {
        try (var fx = FxWindowFixture.create()) {
            List<Tab> tabs = new ArrayList<>();
            List<EditorBuffer> buffers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Path file = Files.writeString(fx.configDir.resolve("f" + i + ".txt"), "\tindented " + i + "\n");
                FxTestSupport.runOnFx(() -> {
                    EditorBuffer b = new EditorBuffer();
                    b.setPath(file);
                    b.setContent("\tindented\n");
                    buffers.add(b);
                    tabs.add((Tab) FxTestSupport.call(
                            fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, b, true));
                });
            }
            FxTestSupport.drainFx();
            Settings settings = fx.shared.getSettings();
            int before = settings.getTabSize();
            int after = before == 8 ? 2 : 8;
            FxTestSupport.runOnFx(() -> {
                fx.controller.reapplyAfterSharedSettingsChange(settings); // what the window last applied
                settings.setTabSize(after); // one spinner step in Settings → Editor
                fx.controller.reapplyAfterSharedSettingsChange(settings);
                assertEquals(after, buffers.get(2).getTabSize(), "the buffer on screen changes at once");
                assertEquals(before, buffers.get(0).getTabSize(), "a hidden one waits");
                tabs.get(0).getTabPane().getSelectionModel().select(tabs.get(0));
                assertEquals(after, buffers.get(0).getTabSize(), "and changes when its tab is shown");
                FileWorkflowCoordinator files = FxTestSupport.field(fx.controller, "fileWorkflows");
                files.save(buffers.get(1)); // File: Save on a hidden tab (Save All saves those too)
                assertEquals(after, buffers.get(1).getTabSize(), "or before it is saved");
            });
        }
    }
}
