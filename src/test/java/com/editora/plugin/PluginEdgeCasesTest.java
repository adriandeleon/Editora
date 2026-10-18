package com.editora.plugin;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.io.TestArchives;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The plugin package's less travelled paths: broken manifests, odd leftovers, and every problem's wording. */
class PluginEdgeCasesTest {

    @TempDir
    Path pluginsDir;

    @Test
    void aStagedArchiveWithABrokenManifestSaysWhere() {
        PluginInstaller installer = new PluginInstaller(new PluginManager(pluginsDir, id -> false));
        PluginInstaller.Staging s =
                installer.stageBytes(TestArchives.zip("plugin.json", "{\n  \"id\": \"x\",\n  oops\n}"), null);
        assertEquals(PluginProblem.Kind.MANIFEST_INVALID, s.problem().kind());
        assertEquals("3", s.problem().args().get(0), "the line where it stops being valid");
        assertFalse(s.problem().detail().isBlank());
    }

    @Test
    void anInstallOverAPluginWhoseManifestIsBrokenStillNamesWhatItReplaces() throws Exception {
        Path old = Files.createDirectories(pluginsDir.resolve("notes"));
        Files.writeString(old.resolve("plugin.json"), "{ broken");
        PluginInstaller installer = new PluginInstaller(new PluginManager(pluginsDir, id -> false));
        PluginInstaller.Staging s = installer.stageBytes(TestArchives.zip("plugin.json", "{\"id\":\"notes\"}"), null);
        assertTrue(s.ok());
        assertEquals("notes", s.staged().previous().name, "a stub named after the id");
        PluginInstaller.discard(s.staged());
    }

    @Test
    void leftoversThatAreNotAPluginAreClearedAtLaunch() throws Exception {
        Files.createFile(pluginsDir.resolve(".remove-lsp")); // a reserved name: never a plugin to remove
        Files.createFile(pluginsDir.resolve(".remove-gone")); // its plugin is already gone
        Files.createDirectories(pluginsDir.resolve(".pending-empty")); // no plugin.json
        Files.createDirectories(pluginsDir.resolve(".pending-tools")); // reserved
        Files.writeString(pluginsDir.resolve(".pending-tools/plugin.json"), "{}");
        Files.createDirectories(pluginsDir.resolve("lsp/java"));

        new PluginManager(pluginsDir, id -> true).discover();

        try (var left = Files.list(pluginsDir)) {
            assertEquals(
                    List.of("lsp"), left.map(p -> p.getFileName().toString()).toList());
        }
        assertTrue(PluginInstaller.deletePluginFolder(pluginsDir.resolve("never-there")));
    }

    @Test
    void theManagerAnswersForPluginsItDoesNotKnow() throws Exception {
        PluginManager pm = new PluginManager(pluginsDir, id -> true);
        assertEquals(List.of(), pm.launchSet(), "nothing before the launch discover");
        pm.discover();
        assertEquals(Map.of(), pm.codeBindings("nobody"));
        assertNull(pm.runtimeProblem(null));
        assertNull(pm.running("nobody"));
        assertNull(pm.pendingUpdate("nobody"));
        assertFalse(pm.pendingRemoval("nobody"));
        assertNull(pm.instantiate(null));
        pm.recordRuntimeProblem(null, null); // ignored
        pm.recordCodeBinding("p", "C-c x", "plugin.p.x");
        assertEquals(Map.of("C-c x", "plugin.p.x"), pm.codeBindings("p"));
    }

    @Test
    void aManifestWithNullCollectionsIsUsable() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        PluginManifest m = PluginManager.parseManifest(
                mapper,
                new ByteArrayInputStream(("{\"id\":\"n\",\"keymap\":null,\"keymaps\":null,\"commands\":[null]}")
                        .getBytes(StandardCharsets.UTF_8)));
        assertNotNull(m.keymap);
        assertNotNull(m.keymaps);
        assertEquals(List.of(), m.commands, "a null command is dropped");
        assertEquals(Map.of(), m.keymapFor("emacs"));
        assertNotNull(PluginDescriptor.digest(m, null));
    }

    @Test
    void aManifestProblemIsReadableWithOrWithoutAPlace() {
        assertEquals(
                PluginDescriptor.LoadProblem.Kind.MANIFEST_UNREADABLE,
                PluginManager.manifestProblem(new java.io.IOException("denied")).kind());
        PluginDescriptor.LoadProblem odd = PluginManager.manifestProblem(new IllegalStateException("odd"));
        assertEquals(PluginDescriptor.LoadProblem.Kind.MANIFEST_INVALID, odd.kind());
        assertEquals(List.of("", ""), odd.args());
        assertEquals("denied", PluginManager.readableError(new java.io.IOException("denied")));
    }

    @Test
    void everyProblemHasAnEnglishSentenceForTheLog() {
        for (PluginProblem.Kind k : PluginProblem.Kind.values()) {
            String text = new PluginProblem(k, List.of("a", "b"), "detail").english();
            assertFalse(text.isBlank(), k.name());
        }
        for (PluginDescriptor.LoadProblem.Kind k : PluginDescriptor.LoadProblem.Kind.values()) {
            PluginManifest m = new PluginManifest();
            m.id = "x";
            PluginDescriptor d = PluginDescriptor.withProblem(
                    m,
                    pluginsDir.resolve("x"),
                    false,
                    null,
                    new PluginDescriptor.LoadProblem(k, List.of("a", "b"), "d"));
            assertFalse(d.loadError().isBlank(), k.name());
        }
        assertEquals("not a url ", PluginProblem.hostOf("not a url "), "no host: shown as written");
        assertEquals("plugins.example.org", PluginProblem.hostOf("https://plugins.example.org/x"));
    }

    @Test
    void aRegistryIndexDropsRowsWithoutAnIdAndCapsTheRest() throws Exception {
        ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        RegistryIndex none = RegistryIndex.parse(
                mapper, new ByteArrayInputStream("{\"plugins\":null}".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(), none.plugins);
        RegistryIndex idx = RegistryIndex.parse(
                mapper,
                new ByteArrayInputStream(("{\"plugins\":[{\"id\":\"a\",\"name\":null,\"deprecated\":\""
                                + "x".repeat(500) + "\"},{\"name\":\"no id\"},null]}")
                        .getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, idx.plugins.size());
        assertEquals("", idx.plugins.get(0).name);
        assertEquals(300, idx.plugins.get(0).deprecated.length());
    }
}
