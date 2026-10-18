package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.ai.AiProvider;
import com.editora.secrets.FakeSecretStore;
import com.editora.secrets.SecretStore;
import com.editora.secrets.SecretStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AI keys move from settings.json to the system keychain (a fake one here: tests never reach the real
 * keychain), and back to the file whenever the keychain cannot be trusted with them.
 */
class ApiKeyVaultTest {

    private final ObjectMapper json = new ObjectMapper();

    private static Settings withKeys() {
        Settings s = new Settings();
        s.setAiApiKey("sk-ant-FAKE");
        s.setAiApiKeyOpenai("or-FAKE");
        s.setAiApiKeyLmstudio("lm-FAKE");
        return s;
    }

    @Test
    void keysMoveToTheKeychainAndLeaveTheSettingsFile() {
        FakeSecretStore store = new FakeSecretStore();
        Settings s = withKeys();
        ApiKeyVault vault = ApiKeyVault.open(store, s);

        assertEquals(ApiKeyVault.Storage.KEYCHAIN, s.apiKeyStorage());
        assertEquals("sk-ant-FAKE", store.items.get("ai-api-key.anthropic"));
        assertEquals("or-FAKE", store.items.get("ai-api-key.openai"));
        assertEquals("lm-FAKE", store.items.get("ai-api-key.lmstudio"));
        assertEquals("sk-ant-FAKE", s.getApiKeyFor(AiProvider.ANTHROPIC), "the key is still there to use");
        assertEquals("lm-FAKE", s.getApiKeyFor(AiProvider.LMSTUDIO));
        JsonNode saved = json.valueToTree(s);
        assertEquals("", saved.get("aiApiKey").asText(), "settings.json no longer holds it");
        assertEquals("", saved.get("aiApiKeyOpenai").asText());
        assertEquals("", saved.get("aiApiKeyLmstudio").asText());
        assertTrue(saved.get("aiApiKeysInKeychain").asBoolean());
        assertTrue(vault.settingsChanged(), "the file must be saved");
    }

    @Test
    void aKeyIsLeftInTheFileUnlessTheKeychainGivesTheSameTextBack() {
        FakeSecretStore store = new FakeSecretStore();
        store.garbleReads = true;
        Settings s = withKeys();
        ApiKeyVault.open(store, s);
        assertEquals(ApiKeyVault.Storage.SETTINGS_FILE, s.apiKeyStorage());
        assertEquals("sk-ant-FAKE", s.getAiApiKey(), "nothing lost");
        assertEquals("or-FAKE", s.getAiApiKeyOpenai());
        assertEquals("sk-ant-FAKE", s.getApiKeyFor(AiProvider.ANTHROPIC));
    }

    @Test
    void withoutAKeychainTheKeysStayInTheFileAndSettingsSaysWhy() {
        FakeSecretStore store = new FakeSecretStore();
        store.available = false;
        Settings s = withKeys();
        ApiKeyVault vault = ApiKeyVault.open(store, s);
        assertEquals(ApiKeyVault.Storage.SETTINGS_FILE, s.apiKeyStorage());
        assertEquals("or-FAKE", s.getApiKeyFor(AiProvider.OPENAI));
        assertFalse(vault.settingsChanged());

        Settings moved = new Settings();
        moved.setAiApiKeysInKeychain(true); // an earlier session moved them
        ApiKeyVault.open(store, moved);
        assertEquals(ApiKeyVault.Storage.KEYCHAIN_UNREACHABLE, moved.apiKeyStorage());
        moved.setApiKeyFor(AiProvider.ANTHROPIC, "typed");
        assertEquals("typed", moved.getAiApiKey(), "a key typed now goes to the file");
    }

    @Test
    void aKeyTypedLaterGoesToTheKeychainAndABlankOneRemovesIt() {
        FakeSecretStore store = new FakeSecretStore();
        Settings s = new Settings();
        ApiKeyVault.open(store, s);
        s.setApiKeyFor(AiProvider.OPENAI, "new-FAKE");
        assertEquals("new-FAKE", store.items.get("ai-api-key.openai"));
        assertEquals("", s.getAiApiKeyOpenai(), "not in the file");
        assertEquals("new-FAKE", s.getApiKeyFor(AiProvider.OPENAI));
        s.setApiKeyFor(AiProvider.OPENAI, "");
        assertFalse(store.items.containsKey("ai-api-key.openai"));
        assertEquals("", s.getApiKeyFor(AiProvider.OPENAI));
    }

    @Test
    void keysAlreadyInTheKeychainAreReadAtStart() {
        FakeSecretStore store = new FakeSecretStore();
        store.items.put("ai-api-key.anthropic", "kept-FAKE");
        Settings s = new Settings();
        ApiKeyVault vault = ApiKeyVault.open(store, s);
        assertEquals("kept-FAKE", s.getApiKeyFor(AiProvider.ANTHROPIC));
        assertEquals("", s.getApiKeyFor(AiProvider.CODEX), "Codex never has a key");
        assertTrue(vault.settingsChanged(), "the flag is written once");
    }

    @Test
    void aKeychainThatRefusesLaterPutsEveryKeyBackInTheFile() {
        FakeSecretStore store = new FakeSecretStore();
        Settings s = withKeys();
        ApiKeyVault vault = ApiKeyVault.open(store, s);
        vault.settingsChanged();
        store.refuseWrites = true;
        s.setApiKeyFor(AiProvider.ANTHROPIC, "changed-FAKE");
        assertEquals(ApiKeyVault.Storage.SETTINGS_FILE, s.apiKeyStorage());
        assertEquals("changed-FAKE", s.getAiApiKey());
        assertEquals("or-FAKE", s.getAiApiKeyOpenai(), "the others come back to the file too");
        assertEquals("lm-FAKE", s.getApiKeyFor(AiProvider.LMSTUDIO));
        assertTrue(vault.settingsChanged());
    }

    @Test
    void aResetKeepsTheKeysInTheKeychain() {
        FakeSecretStore store = new FakeSecretStore();
        Settings s = withKeys();
        ApiKeyVault.open(store, s);
        Settings.resetToDefaults(s);
        assertEquals("sk-ant-FAKE", s.getApiKeyFor(AiProvider.ANTHROPIC));
        assertEquals("sk-ant-FAKE", store.items.get("ai-api-key.anthropic"));
    }

    /** The launch path: SharedConfig moves the keys and saves settings.json without them; export has none. */
    @Test
    void theSavedFileAndAnExportHoldNoKeyOnceTheyAreInTheKeychain(@TempDir Path dir) throws Exception {
        Path cfg = Files.createDirectories(dir.resolve("cfg"));
        ConfigManager first = new ConfigManager(cfg);
        first.load();
        first.getSettings().setAiApiKey("sk-ant-FAKE");
        first.shared().saveSettings();

        ConfigManager manager = new ConfigManager(cfg);
        manager.load();
        FakeSecretStore store = new FakeSecretStore();
        manager.shared().useSecretStore(store);
        String file = Files.readString(cfg.resolve("settings.json"));
        assertFalse(file.contains("sk-ant-FAKE"), file);
        assertEquals("sk-ant-FAKE", store.items.get("ai-api-key.anthropic"));
        assertEquals("sk-ant-FAKE", manager.getSettings().getApiKeyFor(AiProvider.ANTHROPIC));
        assertFalse(manager.shared().hasApiKeys(), "nothing for Export to ask about");
    }

    @Test
    void theNoneStoreIsWhatThePropertyAsksFor() {
        String before = System.getProperty(SecretStores.PROPERTY);
        System.setProperty(SecretStores.PROPERTY, "none");
        try {
            assertSame(SecretStore.NONE, SecretStores.forThisMachine());
        } finally {
            if (before == null) {
                System.clearProperty(SecretStores.PROPERTY);
            } else {
                System.setProperty(SecretStores.PROPERTY, before);
            }
        }
    }
}
