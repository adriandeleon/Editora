package com.editora.ui;

import java.util.List;
import java.util.Map;

import com.editora.command.UserKeyBindings.Store;
import com.editora.config.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What "Reset to Defaults" clears and what it keeps (pure: no toolkit). */
class SettingsResetFlowTest {

    @Test
    void aResetKeepsCredentialsAndTheSyncConnectionAndClearsTheRest() {
        Settings live = new Settings();
        live.setFontSize(20);
        live.setPluginSupport(true);
        live.setAiApiKey("sk-test-not-real");
        live.setAiApiKeyOpenai("sk-test-openai-not-real");
        live.setSyncEnabled(true);
        live.setSyncRepoUrl("git@example.invalid:me/sync.git");
        live.setHistoryMaxPerFile(900);

        SettingsResetFlow.reset(live);

        Settings defaults = new Settings();
        assertEquals(defaults.getFontSize(), live.getFontSize());
        assertEquals(defaults.isPluginSupport(), live.isPluginSupport());
        assertEquals("sk-test-not-real", live.getAiApiKey(), "a reset is not a sign-out");
        assertEquals("sk-test-openai-not-real", live.getAiApiKeyOpenai());
        assertTrue(live.isSyncEnabled());
        assertEquals("git@example.invalid:me/sync.git", live.getSyncRepoUrl());
        assertEquals(900, live.getHistoryMaxPerFile(), "a looser history limit is kept");
    }

    @Test
    void theKeptSettingsAreTheKeysAndTheSyncConnection() {
        assertTrue(SettingsResetFlow.keptByReset("aiApiKey"));
        assertTrue(SettingsResetFlow.keptByReset("aiApiKeyLmstudio"));
        assertTrue(SettingsResetFlow.keptByReset("syncRepoUrl"));
        assertFalse(SettingsResetFlow.keptByReset("aiProvider"));
        assertFalse(SettingsResetFlow.keptByReset("pluginSupport"));
    }

    @Test
    void aResetCarriesAMacrosKeyToTheDefaultKeymapAsAKeymapSwitchDoes() {
        Settings live = new Settings();
        String defaultKeymap = live.getKeymap();
        String other = "vscode".equals(defaultKeymap) ? "cua" : "vscode";
        live.setKeymap(other);
        Store store = UserKeys.store(live, other);
        store.shared().put("macro.run.Tidy", List.of("C-M-9")); // no bundled keymap binds a macro
        UserKeys.put(live, other, store);

        SettingsResetFlow.reset(live);

        assertEquals(defaultKeymap, live.getKeymap());
        assertEquals(
                Map.of("macro.run.Tidy", List.of("C-M-9")),
                UserKeys.store(live, defaultKeymap).shared(),
                "the macro keeps its key");
        assertTrue(UserKeys.store(live, other).isEmpty(), "moved, not copied");
    }
}
