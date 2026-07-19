package com.editora.plugin;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for plugin manifest parsing + discovery (pure / temp-dir, no UI). */
class PluginManagerTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static PluginManifest parse(String json) throws Exception {
        return PluginManager.parseManifest(MAPPER, new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesFullManifestAndIgnoresUnknownFields() throws Exception {
        PluginManifest m = parse("""
                {
                  "id": "hello",
                  "name": "Hello Plugin",
                  "version": "1.2.3",
                  "main": "com.example.HelloPlugin",
                  "keymap": { "C-c h": "plugin.hello.greet" },
                  "commands": [
                    { "id": "build", "title": "Run Build", "run": ["bash", "-c", "make"], "dir": "src" }
                  ],
                  "unknownFutureField": 42
                }
                """);
        assertEquals("hello", m.id);
        assertEquals("Hello Plugin", m.name);
        assertEquals("1.2.3", m.version);
        assertEquals("com.example.HelloPlugin", m.main);
        assertEquals("plugin.hello.greet", m.keymap.get("C-c h"));
        assertEquals(1, m.commands.size());
        assertEquals("build", m.commands.get(0).id);
        assertEquals(List.of("bash", "-c", "make"), m.commands.get(0).run);
        assertEquals("src", m.commands.get(0).dir);
    }

    @Test
    void declarativeOnlyManifestHasBlankMainAndEmptyCollections() throws Exception {
        PluginManifest m = parse("{ \"id\": \"declarative\", \"name\": \"D\" }");
        assertTrue(m.main == null || m.main.isBlank());
        assertNotNull(m.keymap);
        assertTrue(m.keymap.isEmpty());
        assertNotNull(m.commands);
        assertTrue(m.commands.isEmpty());
    }

    @Test
    void discoverFindsManifestsAndAppliesEnabledState(@TempDir Path pluginsDir) throws Exception {
        // enabled Java plugin (no jar → loader empty but built)
        Path a = Files.createDirectories(pluginsDir.resolve("alpha"));
        Files.writeString(a.resolve("plugin.json"), "{ \"id\": \"alpha\", \"name\": \"Alpha\", \"main\": \"x.Y\" }");
        // disabled plugin
        Path b = Files.createDirectories(pluginsDir.resolve("beta"));
        Files.writeString(b.resolve("plugin.json"), "{ \"id\": \"beta\", \"name\": \"Beta\" }");
        // a non-plugin folder (no manifest) is ignored
        Files.createDirectories(pluginsDir.resolve("not-a-plugin"));
        // a folder whose id is taken from the directory name when omitted
        Path c = Files.createDirectories(pluginsDir.resolve("gamma"));
        Files.writeString(c.resolve("plugin.json"), "{ \"name\": \"Gamma\" }");

        Set<String> enabled = Set.of("alpha");
        PluginManager pm = new PluginManager(pluginsDir, enabled::contains);
        pm.discover();
        List<PluginDescriptor> d = pm.descriptors();

        assertEquals(3, d.size()); // alpha, beta, gamma (not-a-plugin skipped); folder order
        PluginDescriptor alpha =
                d.stream().filter(x -> x.id().equals("alpha")).findFirst().orElseThrow();
        assertTrue(alpha.enabled());
        assertTrue(alpha.hasJavaEntry());
        assertNotNull(alpha.classLoader()); // enabled + has main → loader built
        assertNull(alpha.loadError());

        PluginDescriptor beta =
                d.stream().filter(x -> x.id().equals("beta")).findFirst().orElseThrow();
        assertFalse(beta.enabled());
        assertNull(beta.classLoader()); // disabled → no loader

        assertTrue(d.stream().anyMatch(x -> x.id().equals("gamma"))); // id defaulted to folder name
    }

    @Test
    void brokenManifestBecomesADescriptorWithLoadErrorAndNeverThrows(@TempDir Path pluginsDir) throws Exception {
        Path bad = Files.createDirectories(pluginsDir.resolve("broken"));
        Files.writeString(bad.resolve("plugin.json"), "{ this is not valid json");
        PluginManager pm = new PluginManager(pluginsDir, id -> true);
        pm.discover();
        assertEquals(1, pm.descriptors().size());
        PluginDescriptor desc = pm.descriptors().get(0);
        assertEquals("broken", desc.id()); // id from folder name
        assertNotNull(desc.loadError());
        assertFalse(desc.enabled());
    }

    @Test
    void missingPluginsDirYieldsEmpty(@TempDir Path tmp) {
        PluginManager pm = new PluginManager(tmp.resolve("does-not-exist"), id -> true);
        pm.discover();
        assertTrue(pm.descriptors().isEmpty());
    }

    // --- #442: class-loader lifecycle (close leaked loaders; never close a pinned one) -------------------

    private static PluginManager oneEnabledPlugin(Path pluginsDir) throws Exception {
        Path a = Files.createDirectories(pluginsDir.resolve("alpha"));
        Files.writeString(a.resolve("plugin.json"), "{ \"id\": \"alpha\", \"name\": \"Alpha\", \"main\": \"x.Y\" }");
        return new PluginManager(pluginsDir, id -> true);
    }

    @Test
    void reDiscoverClosesTheOrphanedLoaderInsteadOfLeakingIt(@TempDir Path pluginsDir) throws Exception {
        PluginManager pm = oneEnabledPlugin(pluginsDir);
        pm.discover();
        assertEquals(1, pm.liveLoaderCount(), "one loader for the enabled plugin");

        // A Settings Reload / install / uninstall re-discovers. With nothing pinned, the previous loader was
        // orphaned (never re-instantiated) — it must be closed, not accumulated (the leak).
        pm.discover();
        assertEquals(1, pm.liveLoaderCount(), "the old loader is closed; only the freshly-built one remains");
    }

    @Test
    void aPinnedLoaderSurvivesReDiscoverAndIsClosedOnceReleased(@TempDir Path pluginsDir) throws Exception {
        PluginManager pm = oneEnabledPlugin(pluginsDir);
        pm.discover();
        ClassLoader running = pm.descriptors().get(0).classLoader(); // a window instantiated this
        pm.pin(running);

        pm.discover(); // a Settings Reload while the plugin is live in a window
        assertEquals(2, pm.liveLoaderCount(), "the running (pinned) loader stays open beside the new one");
        assertTrue(pm.descriptors().get(0).classLoader() != running, "a fresh loader was built");

        pm.release(running); // the window closes → the superseded, now-unpinned loader is closed
        assertEquals(1, pm.liveLoaderCount(), "the released, superseded loader is closed");

        pm.closeAll();
        assertEquals(0, pm.liveLoaderCount(), "shutdown closes everything");
    }

    @Test
    void releasingTheCurrentLoaderKeepsItOpenForItsStillLivePlugin(@TempDir Path pluginsDir) throws Exception {
        // Two windows share one loader; closing one window must not close the loader the other still uses.
        PluginManager pm = oneEnabledPlugin(pluginsDir);
        pm.discover();
        ClassLoader shared = pm.descriptors().get(0).classLoader();
        pm.pin(shared); // window A
        pm.pin(shared); // window B

        pm.release(shared); // window B closes
        assertEquals(1, pm.liveLoaderCount(), "still open — window A is using it and it's the current loader");

        pm.release(shared); // window A closes; still the current descriptor's loader → kept until closeAll
        assertEquals(1, pm.liveLoaderCount(), "kept open (still current) until shutdown");
        pm.closeAll();
        assertEquals(0, pm.liveLoaderCount());
    }

    @Test
    void storeIsDisabledByDefaultAndTogglable() {
        com.editora.config.PluginStore store = new com.editora.config.PluginStore();
        assertFalse(store.isEnabled("x")); // absent = disabled
        store.setEnabled("x", true);
        assertTrue(store.isEnabled("x"));
        store.setEnabled("x", false);
        assertFalse(store.isEnabled("x"));
    }
}
