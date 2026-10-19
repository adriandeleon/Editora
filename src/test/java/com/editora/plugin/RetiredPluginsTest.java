package com.editora.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The GnuPG plugin is built in now: an installed copy goes at startup, and it never loads or installs again. */
class RetiredPluginsTest {

    private static Path installGpgTools(Path plugins, String properties) throws Exception {
        Path dir = Files.createDirectories(plugins.resolve("gpg-tools"));
        Files.writeString(
                dir.resolve("plugin.json"),
                "{\"id\":\"gpg-tools\",\"name\":\"GnuPG\",\"version\":\"1.1.0\",\"main\":\"x.Y\"}");
        Files.createDirectories(dir.resolve("data"));
        if (properties != null) {
            Files.writeString(dir.resolve("data/gpg.properties"), properties);
        }
        return dir;
    }

    @Test
    void theInstalledPluginIsRemovedAfterItsSettingsAreHandedOver(@TempDir Path plugins) throws Exception {
        Path dir = installGpgTools(
                plugins, "gpg.path=/opt/gnupg/bin/gpg\nrecipient=A945A093C019D17EF48E6AFCB2B49ED225B2A685\n");
        List<RetiredPlugins.GpgToolsSettings> carried = new ArrayList<>();
        List<String> removed = RetiredPlugins.retire(plugins, carried::add);
        assertEquals(List.of("GnuPG"), removed);
        assertFalse(Files.exists(dir));
        assertEquals(
                List.of(new RetiredPlugins.GpgToolsSettings(
                        "/opt/gnupg/bin/gpg", "A945A093C019D17EF48E6AFCB2B49ED225B2A685")),
                carried);
        assertEquals(List.of("GnuPG"), RetiredPlugins.takeNotice(), "the first window says so, once");
        assertEquals(null, RetiredPlugins.takeNotice());
    }

    @Test
    void aPluginWithoutSettingsHandsOverBlanks(@TempDir Path plugins) throws Exception {
        installGpgTools(plugins, null);
        List<RetiredPlugins.GpgToolsSettings> carried = new ArrayList<>();
        RetiredPlugins.retire(plugins, carried::add);
        assertEquals(List.of(new RetiredPlugins.GpgToolsSettings("", "")), carried);
        RetiredPlugins.takeNotice();
    }

    @Test
    void nothingInstalledNothingToSay(@TempDir Path plugins) {
        assertTrue(RetiredPlugins.retire(plugins, s -> {}).isEmpty());
        assertTrue(RetiredPlugins.retire(plugins.resolve("missing"), s -> {}).isEmpty());
    }

    @Test
    void aRetiredPluginIsNeverDiscoveredOrOffered(@TempDir Path plugins) throws Exception {
        installGpgTools(plugins, null);
        PluginManager manager = new PluginManager(plugins, id -> true);
        manager.discover();
        assertTrue(manager.descriptors().stream().noneMatch(p -> "gpg-tools".equals(p.id())));
        assertTrue(RetiredPlugins.isRetired("gpg-tools"));
        assertTrue(RetiredPlugins.isRetired(" GPG-Tools "));
        assertFalse(RetiredPlugins.isRetired("hash-tools"));
    }
}
