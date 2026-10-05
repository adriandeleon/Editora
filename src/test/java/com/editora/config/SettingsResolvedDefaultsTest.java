package com.editora.config;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Settings whose getter resolves a blank value to something else (the OS user, the built-in registry) persist
 * the <em>configured</em> value. Jackson serializes through getters, so the resolved one used to be written
 * on the first save — which ended the "blank = follow the default" mode for good.
 */
class SettingsResolvedDefaultsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aBlankAuthorNameStaysBlankOnDisk(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertEquals(System.getProperty("user.name", ""), config.getSettings().getAuthorName(), "resolved for use");
        config.save();

        JsonNode saved = JSON.readTree(dir.resolve("settings.json").toFile());
        assertEquals("", saved.get("authorName").asText(), "the OS user name is not frozen into the file");
        assertFalse(saved.has("authorNameRaw"), "no accidental helper-getter key");

        Settings reloaded = new ConfigManager(dir).load();
        assertEquals("", reloaded.getAuthorNameRaw());
    }

    @Test
    void aConfiguredAuthorNameRoundTrips(@TempDir Path dir) {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.getSettings().setAuthorName("Ada Lovelace");
        config.save();

        Settings reloaded = new ConfigManager(dir).load();
        assertEquals("Ada Lovelace", reloaded.getAuthorNameRaw());
        assertEquals("Ada Lovelace", reloaded.getAuthorName());
    }

    @Test
    void aBlankPluginRegistryUrlStaysBlankOnDisk(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.getSettings().setPluginRegistryUrl("");
        config.save();

        JsonNode saved = JSON.readTree(dir.resolve("settings.json").toFile());
        assertEquals("", saved.get("pluginRegistryUrl").asText());
        assertFalse(saved.has("pluginRegistryUrlRaw"));
        assertEquals(
                Settings.DEFAULT_PLUGIN_REGISTRY, new ConfigManager(dir).load().getPluginRegistryUrl());
    }

    @Test
    void resetToDefaultsDoesNotFreezeTheOsUserName() {
        Settings live = new Settings();
        live.setAuthorName("Someone");
        Settings.resetToDefaults(live);
        assertEquals("", live.getAuthorNameRaw());
    }

    @Test
    void theRetiredIjhttpCommandIsNoLongerWritten() throws Exception {
        JsonNode tree = JSON.valueToTree(new Settings());
        assertFalse(tree.has("ijhttpCommand"));
    }

    @Test
    void aFreshSettingsFileDoesNotFreezeTheDefaultPluginRegistry(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.save();

        JsonNode saved = JSON.readTree(dir.resolve("settings.json").toFile());
        assertEquals("", saved.get("pluginRegistryUrl").asText(), "blank = follow the built-in registry");
        assertEquals(Settings.DEFAULT_PLUGIN_REGISTRY, config.getSettings().getPluginRegistryUrl());
    }

    @Test
    void resetToDefaultsDoesNotFreezeTheDefaultPluginRegistry() {
        Settings live = new Settings();
        live.setPluginRegistryUrl("https://example.com/index.json");
        Settings.resetToDefaults(live);
        assertEquals("", live.getPluginRegistryUrlRaw());
    }

    @Test
    void aStoredDefaultPluginRegistryIsReadAsFollowTheDefault(@TempDir Path dir) throws Exception {
        // What every earlier first save wrote, and what the Settings field hands back when it is left alone.
        java.nio.file.Files.writeString(
                dir.resolve("settings.json"),
                "{\"schemaVersion\": " + Settings.SCHEMA_VERSION + ", \"pluginRegistryUrl\": \""
                        + Settings.DEFAULT_PLUGIN_REGISTRY + "\"}");
        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();

        assertEquals("", settings.getPluginRegistryUrlRaw());
        assertEquals(Settings.DEFAULT_PLUGIN_REGISTRY, settings.getPluginRegistryUrl());

        settings.setPluginRegistryUrl("https://example.com/index.json");
        assertEquals("https://example.com/index.json", settings.getPluginRegistryUrlRaw(), "a custom URL is kept");
    }
}
