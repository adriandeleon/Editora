package com.editora.ui;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of command-palette feature gating: the controller's {@code paletteGates()} must
 * reflect the live {@link Settings} feature flags AND the Simple-mode override, and {@link Chrome#paletteVisible}
 * must then hide a feature's commands. Complements the pure {@code ChromeTest} (which tests the decision in
 * isolation) by exercising the controller's {@code gitEnabled()}/{@code simpleModeActive()} composition.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaletteGatingFxTest {

    private FxWindowFixture fx;
    private Settings settings;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        settings = config.getSettings();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void gitCommandsGatedByGitSupportAndSimpleMode() throws Exception {
        // Git off by default → git gate false → git.commit hidden from the palette.
        FxTestSupport.runOnFx(() -> {
            settings.setGitSupport(false);
            settings.setSimpleMode(false);
        });
        assertFalse(gitGate(), "git gate off when gitSupport=false");
        assertFalse(gitCommitVisible(), "git.commit hidden when Git disabled");

        // Enable Git → gate true → git.commit visible.
        FxTestSupport.runOnFx(() -> settings.setGitSupport(true));
        assertTrue(gitGate(), "git gate on when gitSupport=true");
        assertTrue(gitCommitVisible(), "git.commit visible when Git enabled");

        // Simple UI mode disables the heavier features even with gitSupport on.
        FxTestSupport.runOnFx(() -> settings.setSimpleMode(true));
        assertFalse(gitGate(), "git gate forced off in Simple mode");
        assertFalse(gitCommitVisible(), "git.commit hidden in Simple mode");

        // Leave the shared fixture clean.
        FxTestSupport.runOnFx(() -> {
            settings.setSimpleMode(false);
            settings.setGitSupport(false);
        });
    }

    private boolean gitGate() throws Exception {
        return FxTestSupport.callOnFx(
                () -> ((Chrome.PaletteGates) FxTestSupport.call(fx.controller, "paletteGates", new Class[] {})).git());
    }

    private boolean gitCommitVisible() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Chrome.PaletteGates gates =
                    (Chrome.PaletteGates) FxTestSupport.call(fx.controller, "paletteGates", new Class[] {});
            return Chrome.paletteVisible("git.commit", gates);
        });
    }
}
