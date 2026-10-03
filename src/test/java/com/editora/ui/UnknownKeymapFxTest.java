package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.config.ConfigManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code settings.json} naming a keymap that is not bundled ({@code "keymap": "vim"}, a typo, a keymap from
 * a newer build) used to throw out of {@code KeymapManager.loadNamed} and abort startup. The window must
 * come up on the default keymap, say so once, and leave the user's setting as they wrote it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnknownKeymapFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static long reports(MainController controller, String message) {
        StatusBar statusBar = FxTestSupport.field(controller, "statusBar");
        MessageLog log = FxTestSupport.field(statusBar, "messageLog");
        return log.entries().stream().filter(e -> e.text().equals(message)).count();
    }

    @Test
    void anUnknownKeymapNameFallsBackToTheDefaultAndIsReportedOnce() throws Exception {
        Path dir = Files.createTempDirectory("editora-fx-keymap");
        ConfigManager seed = new ConfigManager(dir);
        seed.load();
        seed.getSettings().setKeymap("vim");
        assertTrue(seed.save(), "precondition: the bad setting was written");

        FxWindowFixture fx = FxWindowFixture.create(dir, false, false, false, List.of(), c -> {});
        try {
            String message = tr("status.keymap.unknown", "vim", KeymapManager.displayName(KeymapManager.DEFAULT));
            FxTestSupport.runOnFx(() -> {
                KeymapManager keymap = TextInputKeymap.sharedKeymap();
                assertEquals(KeymapManager.DEFAULT, keymap.activeName(), "the default keymap is in use");
                assertEquals("file.save", keymap.commandFor("C-x C-s"));
                assertEquals("vim", fx.shared.getSettings().getKeymap(), "the user's setting is not rewritten");
                assertEquals(1, reports(fx.controller, message), "the fallback is in the message log");

                // The keybinding editor's "defaults to reset against" path loads the same name.
                assertTrue(!FxTestSupport.<EditorSettingsCoordinator>field(fx.controller, "editorSettings")
                        .baseBindings()
                        .isEmpty());

                // Reloading with the same bad setting (any keybinding edit does this) must not nag again.
                fx.windowManager.reloadSharedKeymap();
                assertEquals(1, reports(fx.controller, message), "reported once, not on every reload");
            });
        } finally {
            fx.dispose();
        }
    }
}
