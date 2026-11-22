package com.editora.config;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.ai.AiProvider;
import com.editora.secrets.EncryptedFileStore;
import com.editora.secrets.FakeSecretStore;
import com.editora.secrets.SecretStore;
import com.editora.secrets.SecretStores;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The AI keys move from settings.json to the system keychain (a fake one here: tests never reach the real
 * keychain), or to the encrypted file when there is none or it cannot be trusted with them; settings.json is never
 * written with them again (SET-13, Pauta 6.2.0).
 */
class ApiKeyVaultTest {

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path cfg;

    private static Settings withKeys() {
        Settings s = new Settings();
        s.setAiApiKey("sk-ant-FAKE");
        s.setAiApiKeyOpenai("or-FAKE");
        s.setAiApiKeyLmstudio("lm-FAKE");
        return s;
    }

    private EncryptedFileStore file() {
        return new EncryptedFileStore(cfg);
    }

    private static FakeSecretStore noKeychain() {
        FakeSecretStore store = new FakeSecretStore();
        store.available = false;
        return store;
    }

    private static void assertNoKeyIn(JsonNode saved) {
        assertNull(saved.get("aiApiKey"), saved.toString());
        assertNull(saved.get("aiApiKeyOpenai"), saved.toString());
        assertNull(saved.get("aiApiKeyLmstudio"), saved.toString());
    }

    @Test
    void theKeyFieldsAreReadFromTheFileButNeverWrittenToIt() throws Exception {
        Settings read = json.readValue(
                "{\"aiApiKey\":\"sk-ant-FAKE\",\"aiApiKeyOpenai\":\"or-FAKE\",\"aiApiKeyLmstudio\":\"lm-FAKE\"}",
                Settings.class);
        assertEquals("sk-ant-FAKE", read.getAiApiKey(), "an older file's key is read, to be moved");
        assertEquals("lm-FAKE", read.getAiApiKeyLmstudio());
        assertNoKeyIn(json.valueToTree(read));
        assertNoKeyIn(json.valueToTree(withKeys()));
    }

    @Test
    void keysMoveToTheKeychainAndLeaveTheSettingsFile() {
        FakeSecretStore store = new FakeSecretStore();
        Settings s = withKeys();
        ApiKeyVault vault = ApiKeyVault.open(store, file(), s);

        assertEquals(ApiKeyVault.Storage.KEYCHAIN, s.apiKeyStorage());
        assertEquals("sk-ant-FAKE", store.items.get("ai-api-key.anthropic"));
        assertEquals("or-FAKE", store.items.get("ai-api-key.openai"));
        assertEquals("lm-FAKE", store.items.get("ai-api-key.lmstudio"));
        assertEquals("sk-ant-FAKE", s.getApiKeyFor(AiProvider.ANTHROPIC), "the key is still there to use");
        assertEquals("lm-FAKE", s.getApiKeyFor(AiProvider.LMSTUDIO));
        assertEquals("", s.getAiApiKey(), "the field let go of it");
        JsonNode saved = json.valueToTree(s);
        assertNoKeyIn(saved);
        assertTrue(saved.get("aiApiKeysInKeychain").asBoolean());
        assertTrue(vault.settingsChanged(), "the file must be saved");
        assertFalse(Files.exists(file().dataFile()), "no file when the keychain took them");
    }

    @Test
    void aKeychainThatGivesBackOtherTextSendsTheKeysToTheEncryptedFile() throws Exception {
        FakeSecretStore store = new FakeSecretStore();
        store.garbleReads = true;
        Settings s = withKeys();
        ApiKeyVault.open(store, file(), s);
        assertEquals(ApiKeyVault.Storage.ENCRYPTED_FILE, s.apiKeyStorage());
        assertEquals("", s.getAiApiKey(), "not in settings");
        assertEquals("sk-ant-FAKE", s.getApiKeyFor(AiProvider.ANTHROPIC));
        assertEquals("or-FAKE", file().read("ai-api-key.openai").orElseThrow(), "nothing lost");
    }

    /** The migration with no keychain: settings.json's keys go to the encrypted file, and a later launch reads them. */
    @Test
    void withoutAKeychainTheKeysMoveToTheEncryptedFileAndSettingsSaysSo() throws Exception {
        Settings s = withKeys();
        ApiKeyVault vault = ApiKeyVault.open(noKeychain(), file(), s);
        assertEquals(ApiKeyVault.Storage.ENCRYPTED_FILE, s.apiKeyStorage());
        assertEquals("or-FAKE", s.getApiKeyFor(AiProvider.OPENAI));
        assertTrue(vault.settingsChanged(), "settings.json is saved without them");
        assertNoKeyIn(json.valueToTree(s));
        byte[] onDisk = Files.readAllBytes(file().dataFile());
        assertFalse(new String(onDisk, StandardCharsets.ISO_8859_1).contains("or-FAKE"), "encrypted, not plain");

        Settings next = new Settings(); // the next launch
        ApiKeyVault.open(noKeychain(), file(), next);
        assertEquals("sk-ant-FAKE", next.getApiKeyFor(AiProvider.ANTHROPIC));
        assertEquals("lm-FAKE", next.getApiKeyFor(AiProvider.LMSTUDIO));

        Settings moved = new Settings();
        moved.setAiApiKeysInKeychain(true); // an earlier session moved them to a keychain that does not answer now
        ApiKeyVault unreachable = ApiKeyVault.open(noKeychain(), file(), moved);
        assertEquals(ApiKeyVault.Storage.KEYCHAIN_UNREACHABLE, moved.apiKeyStorage());
        moved.setApiKeyFor(AiProvider.ANTHROPIC, "typed");
        assertTrue(unreachable.awaitWrites());
        assertEquals("", moved.getAiApiKey(), "not in settings");
        assertEquals("typed", file().read("ai-api-key.anthropic").orElseThrow(), "a key typed now goes to the file");
    }

    @Test
    void keysTypedWithoutAKeychainMoveToItWhenItAnswersAgain() throws Exception {
        Settings first = new Settings();
        ApiKeyVault offline = ApiKeyVault.open(noKeychain(), file(), first);
        first.setApiKeyFor(AiProvider.OPENAI, "or-TYPED");
        assertTrue(offline.awaitWrites());

        FakeSecretStore keychain = new FakeSecretStore();
        keychain.items.put("ai-api-key.openai", "or-OLD");
        Settings next = new Settings();
        ApiKeyVault.open(keychain, file(), next);
        assertEquals(ApiKeyVault.Storage.KEYCHAIN, next.apiKeyStorage());
        assertEquals("or-TYPED", keychain.items.get("ai-api-key.openai"), "the file's key is the newer one");
        assertEquals("or-TYPED", next.getApiKeyFor(AiProvider.OPENAI));
        assertTrue(file().readAll().isEmpty(), "the file let go of it");
    }

    @Test
    void aKeyTypedLaterGoesToTheKeychainOffTheCallersThreadAndABlankOneRemovesIt() {
        FakeSecretStore store = new FakeSecretStore();
        Settings s = new Settings();
        ApiKeyVault vault = ApiKeyVault.open(store, file(), s);
        store.threads.clear();
        s.setApiKeyFor(AiProvider.OPENAI, "new-FAKE");
        assertEquals("new-FAKE", s.getApiKeyFor(AiProvider.OPENAI), "usable at once");
        assertTrue(vault.awaitWrites());
        assertEquals("new-FAKE", store.items.get("ai-api-key.openai"));
        assertEquals("", s.getAiApiKeyOpenai(), "not in the field");
        s.setApiKeyFor(AiProvider.OPENAI, "");
        assertTrue(vault.awaitWrites());
        assertFalse(store.items.containsKey("ai-api-key.openai"));
        assertEquals("", s.getApiKeyFor(AiProvider.OPENAI));
        String caller = Thread.currentThread().getName();
        assertEquals(2, store.threads.size());
        assertTrue(store.threads.stream().noneMatch(caller::equals), "never the caller's thread: " + store.threads);
    }

    @Test
    void keysAlreadyInTheKeychainAreReadAtStart() {
        FakeSecretStore store = new FakeSecretStore();
        store.items.put("ai-api-key.anthropic", "kept-FAKE");
        Settings s = new Settings();
        ApiKeyVault vault = ApiKeyVault.open(store, file(), s);
        assertEquals("kept-FAKE", s.getApiKeyFor(AiProvider.ANTHROPIC));
        assertEquals("", s.getApiKeyFor(AiProvider.CODEX), "Codex never has a key");
        assertTrue(vault.settingsChanged(), "the flag is written once");
    }

    @Test
    void aKeychainThatRefusesLaterSendsEveryKeyToTheEncryptedFile() throws Exception {
        FakeSecretStore store = new FakeSecretStore();
        Settings s = withKeys();
        ApiKeyVault vault = ApiKeyVault.open(store, file(), s);
        vault.settingsChanged();
        store.refuseWrites = true;
        s.setApiKeyFor(AiProvider.ANTHROPIC, "changed-FAKE");
        assertTrue(vault.awaitWrites());
        assertEquals(ApiKeyVault.Storage.ENCRYPTED_FILE, s.apiKeyStorage());
        assertEquals("", s.getAiApiKey(), "never back to settings");
        assertEquals("changed-FAKE", file().read("ai-api-key.anthropic").orElseThrow());
        assertEquals("or-FAKE", file().read("ai-api-key.openai").orElseThrow(), "the others go to the file too");
        assertEquals("lm-FAKE", s.getApiKeyFor(AiProvider.LMSTUDIO));
        assertFalse(vault.settingsChanged(), "settings.json has nothing to save");
    }

    /** No keychain and no file that can be written: the keys are held for the session; stderr and Settings say so. */
    @Test
    void whenNeitherStoreWorksTheKeysAreKeptForTheSessionOnly() throws Exception {
        Files.writeString(cfg.resolve(EncryptedFileStore.DIR), "a file where the folder goes");
        PrintStream before = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Settings s = withKeys();
        ApiKeyVault vault;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            vault = ApiKeyVault.open(noKeychain(), file(), s);
        } finally {
            System.setErr(before);
        }
        assertEquals(ApiKeyVault.Storage.SESSION_ONLY, s.apiKeyStorage());
        assertEquals("sk-ant-FAKE", s.getApiKeyFor(AiProvider.ANTHROPIC), "usable this session");
        assertNoKeyIn(json.valueToTree(s));
        assertFalse(vault.failure().isBlank());
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("for this session only"), err.toString());
    }

    @Test
    void aFileThatDoesNotDecryptIsReportedReadAsNoKeysAndMovedAsideBeforeANewOne() throws Exception {
        Settings first = new Settings();
        ApiKeyVault offline = ApiKeyVault.open(noKeychain(), file(), first);
        first.setApiKeyFor(AiProvider.ANTHROPIC, "sk-ant-FAKE");
        assertTrue(offline.awaitWrites());
        byte[] damaged = Files.readAllBytes(file().dataFile());
        damaged[damaged.length - 1] ^= 1;
        Files.write(file().dataFile(), damaged);

        Settings next = new Settings();
        ApiKeyVault vault = ApiKeyVault.open(noKeychain(), file(), next);
        assertEquals(ApiKeyVault.Problem.FILE_UNREADABLE, vault.problem());
        assertFalse(vault.problemDetail().isBlank());
        assertEquals("", next.getApiKeyFor(AiProvider.ANTHROPIC), "no key from it");
        assertTrue(java.util.Arrays.equals(damaged, Files.readAllBytes(file().dataFile())), "and not written over");

        next.setApiKeyFor(AiProvider.OPENAI, "or-NEW");
        assertTrue(vault.awaitWrites());
        assertEquals(ApiKeyVault.Problem.FILE_SET_ASIDE, vault.problem());
        Path kept = file().dataFile().resolveSibling(EncryptedFileStore.DATA_FILE + EncryptedFileStore.UNREADABLE);
        assertEquals(kept.toString(), vault.problemDetail(), "says where the old one went");
        assertTrue(java.util.Arrays.equals(damaged, Files.readAllBytes(kept)));
        assertEquals("or-NEW", file().read("ai-api-key.openai").orElseThrow());
    }

    /**
     * settings.json re-read on the config writer's thread with a key typed into it: the key goes to the vault, the
     * live settings never hold it, and the file is saved without it.
     */
    @Test
    void aKeyTypedIntoTheSettingsFileGoesToTheVaultAndLeavesTheFile() throws Exception {
        ConfigManager config = new ConfigManager(cfg);
        config.load();
        config.shared().saveSettings();
        FakeSecretStore store = new FakeSecretStore();
        config.shared().useSecretStore(store);
        Settings live = config.getSettings();
        assertTrue(config.shared().awaitWrites());
        String text = Files.readString(config.getSettingsFile());
        assertFalse(text.contains("aiApiKey\""), text);
        Files.writeString(config.getSettingsFile(), text.replaceFirst("\\{", "{\n  \"aiApiKey\" : \"typed-FAKE\","));
        java.util.List<Runnable> toFx = new java.util.concurrent.CopyOnWriteArrayList<>();
        config.shared().setFxThreadForTest(toFx::add);
        config.shared().reloadSettings(issues -> {});
        assertEquals(1, toFx.size(), "the re-read is waiting for the FX thread");
        toFx.remove(0).run(); // the FX thread adopts the re-read copy
        assertEquals("", live.getAiApiKey(), "the field never holds it");
        assertEquals("typed-FAKE", live.getApiKeyFor(AiProvider.ANTHROPIC));
        assertTrue(config.shared().apiKeyVault().awaitWrites());
        assertEquals("typed-FAKE", store.items.get("ai-api-key.anthropic"));
        assertTrue(config.shared().awaitWrites());
        assertFalse(Files.readString(config.getSettingsFile()).contains("typed-FAKE"), "and the file let go of it");
        assertTrue(config.shared().settingsFileIssues().isEmpty(), "the key is no unknown setting");
        config.shared().shutdown();
    }

    @Test
    void aResetKeepsTheKeysInTheKeychain() {
        FakeSecretStore store = new FakeSecretStore();
        Settings s = withKeys();
        ApiKeyVault.open(store, file(), s);
        Settings.resetToDefaults(s);
        assertEquals("sk-ant-FAKE", s.getApiKeyFor(AiProvider.ANTHROPIC));
        assertEquals("sk-ant-FAKE", store.items.get("ai-api-key.anthropic"));
    }

    /**
     * The launch path, as an older version left things: settings.json and a backup of it with a key in plain text.
     * SharedConfig moves it to the vault, saves settings.json without it, blanks the backup, and an export has none.
     */
    @Test
    void theLaunchMovesAnOlderFilesKeysAndLeavesThemInNoFileAndNoExport(@TempDir Path out) throws Exception {
        ConfigManager first = new ConfigManager(cfg);
        first.load();
        first.shared().saveSettings();
        first.shared().shutdown();
        Path settingsFile = cfg.resolve("settings.json");
        String older = Files.readString(settingsFile)
                .replaceFirst("\\{", "{\n  \"aiApiKey\" : \"sk-ant-FAKE\",\n  \"aiApiKeyOpenai\" : \"or-FAKE\",");
        Files.writeString(settingsFile, older);
        Files.writeString(cfg.resolve("settings.json.v104.bak"), older);

        ConfigManager manager = new ConfigManager(cfg);
        manager.load();
        manager.shared().useSecretStore(noKeychain());
        assertTrue(manager.shared().awaitWrites());
        String file = Files.readString(settingsFile);
        assertFalse(file.contains("sk-ant-FAKE") || file.contains("or-FAKE"), file);
        String backup = Files.readString(cfg.resolve("settings.json.v104.bak"));
        assertFalse(backup.contains("sk-ant-FAKE") || backup.contains("or-FAKE"), backup);
        assertEquals("sk-ant-FAKE", manager.getSettings().getApiKeyFor(AiProvider.ANTHROPIC));
        assertEquals(
                "or-FAKE", new EncryptedFileStore(cfg).read("ai-api-key.openai").orElseThrow());

        Path zip = manager.shared().exportConfig(out, true, true);
        try (var zf = new java.util.zip.ZipFile(zip.toFile())) {
            assertTrue(
                    zf.stream().noneMatch(e -> e.getName().startsWith(EncryptedFileStore.DIR + "/")),
                    "the encrypted file and its key stay home");
            String zipped =
                    new String(zf.getInputStream(zf.getEntry("settings.json")).readAllBytes());
            assertFalse(zipped.contains("FAKE"), zipped);
        }
        manager.shared().shutdown();
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
