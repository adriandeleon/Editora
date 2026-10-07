package com.editora.plugin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What installing, updating and removing a plugin may delete: an update keeps the plugin's {@code data/}
 * directory, a plugin id cannot collide with the built-in tool folders, and removal deletes the plugin's own
 * folder — a direct child of the plugins folder — whatever its {@code plugin.json} claims to be called.
 */
class PluginInstallSafetyTest {

    @TempDir
    Path config;

    private static byte[] pluginZip(String manifest, String... extraEntries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("plugin.json"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 0; i + 1 < extraEntries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(extraEntries[i]));
                zip.write(extraEntries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * The reproduced loss: updating a plugin deleted {@code plugins/<id>/} first, and with it everything the
     * plugin had stored through {@code PluginContext.dataDir()}.
     */
    @Test
    void updatingAPluginKeepsItsDataDirectory() throws IOException {
        Path pluginsDir = Files.createDirectories(config.resolve("plugins"));
        PluginManager manager = new PluginManager(pluginsDir, id -> false);
        PluginInstaller installer = new PluginInstaller(manager);
        try {
            String v1 = "{\"id\":\"notes\",\"name\":\"Notes\",\"version\":\"1.0\"}";
            assertTrue(
                    installer.installBytes(pluginZip(v1, "old-only.txt", "v1")).ok());
            Path nested = Files.createDirectories(pluginsDir.resolve("notes/data/nested"));
            Files.writeString(pluginsDir.resolve("notes/data/user-notes.db"), "the plugin's stored user data");
            Files.writeString(nested.resolve("deep.txt"), "deep");

            String v2 = "{\"id\":\"notes\",\"name\":\"Notes\",\"version\":\"2.0\"}";
            PluginInstaller.Result update = installer.installBytes(pluginZip(
                    v2, "new-only.txt", "v2", "data/defaults.json", "{}", "data/user-notes.db", "shipped default"));

            assertTrue(update.ok(), String.valueOf(update.error()));
            assertEquals(
                    "the plugin's stored user data",
                    Files.readString(pluginsDir.resolve("notes/data/user-notes.db")),
                    "the user's data survives, and wins over a default shipped under the same name");
            assertEquals("deep", Files.readString(pluginsDir.resolve("notes/data/nested/deep.txt")));
            assertEquals("{}", Files.readString(pluginsDir.resolve("notes/data/defaults.json")));
            assertTrue(Files.exists(pluginsDir.resolve("notes/new-only.txt")), "the new version is installed");
            assertFalse(Files.exists(pluginsDir.resolve("notes/old-only.txt")), "the old version's files are gone");
            assertEquals("2.0", manager.descriptors().get(0).manifest().version);
            try (var entries = Files.list(pluginsDir)) {
                assertEquals(
                        List.of("notes"),
                        entries.map(p -> p.getFileName().toString()).toList(),
                        "no staging folder is left");
            }
        } finally {
            installer.shutdown();
            manager.closeAll();
        }
    }

    @Test
    void anUpdateWithoutShippedDataMovesTheOldDataAcross() throws IOException {
        Path pluginsDir = Files.createDirectories(config.resolve("plugins"));
        PluginInstaller installer = new PluginInstaller(new PluginManager(pluginsDir, id -> false));
        try {
            assertTrue(installer.installBytes(pluginZip("{\"id\":\"notes\"}")).ok());
            Files.writeString(
                    Files.createDirectories(pluginsDir.resolve("notes/data")).resolve("db"), "kept");

            assertTrue(installer
                    .installBytes(pluginZip("{\"id\":\"notes\",\"version\":\"2\"}"))
                    .ok());

            assertEquals("kept", Files.readString(pluginsDir.resolve("notes/data/db")));
        } finally {
            installer.shutdown();
        }
    }

    /** {@code plugins/lsp}, {@code plugins/dap} and {@code plugins/typst} hold downloaded tools, not plugins. */
    @Test
    void aPluginCannotInstallOverTheBuiltInToolFolders() throws IOException {
        Path pluginsDir = Files.createDirectories(config.resolve("plugins"));
        Path server =
                Files.createDirectories(pluginsDir.resolve("lsp/java/bin")).resolve("jdtls");
        Files.writeString(server, "#!/bin/sh");
        PluginInstaller installer = new PluginInstaller(new PluginManager(pluginsDir, id -> false));
        try {
            for (String id : List.of("lsp", "dap", "typst", "Lsp")) {
                PluginInstaller.Result r =
                        installer.installBytes(pluginZip("{\"id\":\"" + id + "\",\"name\":\"helper\"}"));
                assertFalse(r.ok(), id + " must be refused");
                assertTrue(r.error().contains("reserved"), r.error());
            }
            assertEquals("#!/bin/sh", Files.readString(server), "the installed language server is untouched");
            assertFalse(Files.exists(pluginsDir.resolve("dap")));
        } finally {
            installer.shutdown();
        }
    }

    @Test
    void theBuiltInToolFoldersAreReservedWhateverTheirCase() {
        assertTrue(PluginInstaller.isReservedId("lsp"));
        assertTrue(PluginInstaller.isReservedId("dap"));
        assertTrue(PluginInstaller.isReservedId("typst"));
        assertTrue(PluginInstaller.isReservedId("LSP"), "case-insensitive volumes fold the folder name");
        assertFalse(PluginInstaller.isReservedId("lsp-helper"));
        assertFalse(PluginInstaller.isReservedId(null));
    }

    /** A folder that merely shares the name — no {@code plugin.json} — is not an old install to replace. */
    @Test
    void anInstallNeverDeletesAFolderThatIsNotAPlugin() throws IOException {
        Path pluginsDir = Files.createDirectories(config.resolve("plugins"));
        Path mine = Files.createDirectories(pluginsDir.resolve("notes")).resolve("my-file.txt");
        Files.writeString(mine, "not a plugin");
        PluginInstaller installer = new PluginInstaller(new PluginManager(pluginsDir, id -> false));
        try {
            PluginInstaller.Result r = installer.installBytes(pluginZip("{\"id\":\"notes\"}"));
            assertFalse(r.ok());
            assertEquals("not a plugin", Files.readString(mine));
        } finally {
            installer.shutdown();
        }
    }

    /**
     * Remove deleted {@code pluginsDir.resolve(manifest id)}: for a hand-placed folder whose {@code plugin.json}
     * says {@code "id": ".."} that is the whole Editora config directory.
     */
    @Test
    void removingAPluginDeletesItsOwnFolderWhateverItsManifestClaims() throws IOException {
        Path pluginsDir = Files.createDirectories(config.resolve("plugins"));
        Files.writeString(config.resolve("settings.json"), "{}");
        Path other = Files.createDirectories(pluginsDir.resolve("other"));
        Files.writeString(other.resolve("plugin.json"), "{\"id\":\"other\"}");
        Path innocent = Files.createDirectories(pluginsDir.resolve("innocent"));
        Files.writeString(innocent.resolve("plugin.json"), "{\"id\":\"..\",\"name\":\"Innocent\"}");

        PluginManager manager = new PluginManager(pluginsDir, id -> true);
        manager.discover();
        PluginDescriptor found = manager.descriptors().stream()
                .filter(d -> d.dir().equals(innocent))
                .findFirst()
                .orElseThrow();
        assertEquals("innocent", found.id(), "an unsafe id is replaced by the folder name");
        assertNotNull(found.loadError());
        assertFalse(found.enabled(), "nothing is loaded from a plugin with an invalid id");

        // Even a descriptor that still carried the hostile id could only ever delete its own folder.
        PluginManifest hostile = new PluginManifest();
        hostile.id = "..";
        PluginDescriptor crafted = new PluginDescriptor(hostile, innocent, false, null, null);
        assertEquals(innocent, PluginInstaller.removableDir(pluginsDir, crafted));
        assertTrue(PluginInstaller.deleteInstalled(pluginsDir, crafted));

        assertFalse(Files.exists(innocent));
        assertTrue(Files.exists(config.resolve("settings.json")), "the config directory is untouched");
        assertTrue(Files.exists(other.resolve("plugin.json")), "other plugins are untouched");
        manager.closeAll();
    }

    @Test
    void anIdThatIsAnAbsolutePathOrAnotherFolderIsRefusedAtDiscovery() throws IOException {
        Path pluginsDir = Files.createDirectories(config.resolve("plugins"));
        Path victim = Files.createDirectories(config.resolve("victim"));
        Path a = Files.createDirectories(pluginsDir.resolve("a"));
        Files.writeString(
                a.resolve("plugin.json"), "{\"id\":\"" + victim.toString().replace("\\", "\\\\") + "\"}");
        Path b = Files.createDirectories(pluginsDir.resolve("b"));
        Files.writeString(b.resolve("plugin.json"), "{\"id\":\"../../victim\"}");

        PluginManager manager = new PluginManager(pluginsDir, id -> true);
        manager.discover();

        assertEquals(
                List.of("a", "b"),
                manager.descriptors().stream().map(PluginDescriptor::id).toList());
        for (PluginDescriptor d : manager.descriptors()) {
            assertNotNull(d.loadError());
            assertNull(d.classLoader());
            assertEquals(d.dir(), PluginInstaller.removableDir(pluginsDir, d));
        }
    }

    @Test
    void onlyADirectChildOfThePluginsFolderIsRemovable() throws IOException {
        Path pluginsDir = Files.createDirectories(config.resolve("plugins"));
        Path nested = Files.createDirectories(pluginsDir.resolve("a/b"));
        Path outside = Files.createDirectories(config.resolve("elsewhere"));
        Path tools = Files.createDirectories(pluginsDir.resolve("lsp"));
        PluginManifest m = new PluginManifest();
        m.id = "x";

        for (Path dir : List.of(nested, outside, tools, config, pluginsDir, pluginsDir.resolve("missing"))) {
            PluginDescriptor d = new PluginDescriptor(m, dir, false, null, null);
            assertNull(PluginInstaller.removableDir(pluginsDir, d), dir + " must not be removable");
            assertFalse(PluginInstaller.deleteInstalled(pluginsDir, d));
        }
        assertTrue(Files.isDirectory(nested) && Files.isDirectory(outside) && Files.isDirectory(tools));
        assertNull(PluginInstaller.removableDir(pluginsDir, null));
    }

    /** A hand-dropped {@code plugin.json} in a tool folder must not turn it into a removable "plugin". */
    @Test
    void discoveryDoesNotListToolFoldersOrStagedInstalls() throws IOException {
        Path pluginsDir = Files.createDirectories(config.resolve("plugins"));
        Files.writeString(Files.createDirectories(pluginsDir.resolve("lsp")).resolve("plugin.json"), "{}");
        Files.writeString(
                Files.createDirectories(pluginsDir.resolve(".installing-x.new-1"))
                        .resolve("plugin.json"),
                "{}");
        Files.writeString(Files.createDirectories(pluginsDir.resolve("real")).resolve("plugin.json"), "{}");
        Path renamed = Files.createDirectories(pluginsDir.resolve("claims-typst"));
        Files.writeString(renamed.resolve("plugin.json"), "{\"id\":\"typst\"}");

        PluginManager manager = new PluginManager(pluginsDir, id -> false);
        manager.discover();

        assertEquals(
                List.of("claims-typst", "real"),
                manager.descriptors().stream().map(PluginDescriptor::id).toList());
        assertNotNull(manager.descriptors().get(0).loadError(), "a reserved id is refused at discovery");
    }
}
