package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.editor.PreviewLayout;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** What the window remembers about a file's preview, and the three-state preview theme toggle. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PreviewLayoutFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private EditorBuffer active() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    @Test
    void aFilesPreviewZoomIsStoredAndComesBackWhenItIsOpenedAgain(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("notes.md"), "# Notes\n\ntext\n");
        var layouts = FxTestSupport.<com.editora.config.ConfigManager>field(fx.controller, "config")
                .getWorkspaceState()
                .getMarkdownPreviewLayouts();
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        FxTestSupport.drainFx();
        EditorBuffer first = active();
        assertEquals(file, FxTestSupport.callOnFx(first::getPath));
        assertFalse(layouts.containsKey(file.toString()), "a preview left as it came stores nothing");

        FxTestSupport.runOnFx(() -> {
            first.zoomPreviewIn();
            first.zoomPreviewIn();
        });
        assertEquals(new PreviewLayout(1.21, 0.5), PreviewLayout.parse(layouts.get(file.toString())));

        // Close the tab and open the file again: a new buffer, the same zoom.
        FxTestSupport.runOnFx(() -> registry.run("buffer.close"));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        FxTestSupport.drainFx();
        EditorBuffer again = active();
        assertNotNull(again);
        assertEquals(1.21, FxTestSupport.callOnFx(() -> again.getPreviewLayout().zoom()), 1e-6);

        FxTestSupport.runOnFx(again::resetPreviewZoom);
        assertFalse(layouts.containsKey(file.toString()), "back at 100%: nothing left to remember");
        FxTestSupport.runOnFx(() -> registry.run("buffer.close"));
    }

    @Test
    void thePreviewThemeToggleCyclesBackToFollowingTheApp() throws Exception {
        var settings = fx.shared.getSettings();
        FxTestSupport.runOnFx(() -> settings.setMarkdownPreviewTheme(""));
        // The default app theme is light: follow → dark → light (pinned) → follow.
        String[] expected = {"dark", "light", ""};
        for (String next : expected) {
            FxTestSupport.runOnFx(() -> registry.run("view.toggleMarkdownPreviewTheme"));
            assertEquals(next, FxTestSupport.callOnFx(settings::getMarkdownPreviewTheme));
        }
    }
}
