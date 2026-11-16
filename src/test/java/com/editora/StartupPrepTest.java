package com.editora;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launch's load ({@link App#loadForFirstWindow}) claims the config dir first. When a later step fails, the
 * claim is let go before the failure reaches the caller, so {@code takeBootstrap}'s inline load on the same
 * directory is the primary instance, not a second one; and a start-up step that fails does not stop the launch.
 */
class StartupPrepTest {

    @AfterEach
    void putBack() {
        com.editora.ui.DebugLog.detachFileForTest();
        com.editora.process.ProcessRegistry.setLedgerFile(null);
        com.editora.i18n.Messages.init("en");
    }

    @Test
    void aFailedLoadLetsGoOfItsClaimAndSaysWhy(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"projectSupport\":true}");
        // A --project folder that is no path: the primary window's lookup throws, after the claim.
        assertThrows(
                InvalidPathException.class,
                () -> App.loadForFirstWindow(dir, List.of("--project", "bad\u0000folder")),
                "the failure reaches the caller");

        ConfigManager inline = new ConfigManager(dir);
        try {
            assertTrue(inline.shared().claimInstance(), "the inline load that follows is the primary instance");
        } finally {
            inline.shared().shutdown();
        }
    }

    @Test
    void aStepThatFailsIsLoggedAndTheLaunchGoesOn() {
        assertDoesNotThrow(() -> StartupPrep.step("a failing step", () -> {
            throw new IllegalStateException("broken");
        }));
    }
}
