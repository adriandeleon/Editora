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

    // --- the launch set: what the first discover found is what this session runs -------------------------

    private static PluginManager oneEnabledPlugin(Path pluginsDir) throws Exception {
        Path a = Files.createDirectories(pluginsDir.resolve("alpha"));
        Files.writeString(a.resolve("plugin.json"), "{ \"id\": \"alpha\", \"name\": \"Alpha\", \"main\": \"x.Y\" }");
        return new PluginManager(pluginsDir, id -> true);
    }

    /**
     * Only the launch builds class loaders. A Reload, an install or a removal rescans the manifests for the
     * Settings list; the windows keep applying the launch set, whose loader stays open until the last window
     * closes (#442 closed superseded loaders; now there are none to supersede).
     */
    @Test
    void onlyTheLaunchDiscoverBuildsClassLoadersAndALaterOneLeavesTheLaunchSetAlone(@TempDir Path pluginsDir)
            throws Exception {
        PluginManager pm = oneEnabledPlugin(pluginsDir);
        pm.discover();
        assertEquals(1, pm.liveLoaderCount(), "one loader for the enabled plugin");
        ClassLoader running = pm.launchSet().get(0).classLoader();
        assertNotNull(running);

        Files.writeString(
                pluginsDir.resolve("alpha/plugin.json"),
                "{ \"id\": \"alpha\", \"version\": \"2\", \"main\": \"x.Z\" }");
        Files.createDirectories(pluginsDir.resolve("beta"));
        Files.writeString(pluginsDir.resolve("beta/plugin.json"), "{ \"id\": \"beta\", \"main\": \"b.B\" }");
        pm.discover(); // a Settings Reload while the plugin is live in a window

        assertEquals(1, pm.liveLoaderCount(), "a rescan builds no loader");
        assertEquals(2, pm.descriptors().size(), "but the Settings list sees what is on disk now");
        assertNull(pm.descriptors().get(0).classLoader());
        assertEquals("2", pm.descriptors().get(0).manifest().version);
        assertEquals(1, pm.launchSet().size(), "new windows still get what the launch loaded");
        assertTrue(pm.launchSet().get(0).classLoader() == running);
        assertEquals("x.Y", pm.launchSet().get(0).manifest().main, "the version that runs, not the one on disk");
        assertNotNull(pm.running("alpha"));
        assertNull(pm.running("beta"), "found after launch: runs after a restart");

        pm.closeAll();
        assertEquals(0, pm.liveLoaderCount(), "the last window closing closes everything");
    }

    /** E2/E4: a plugin switched off (or an update declined) mid-session is still the launch's: nothing re-arms. */
    @Test
    void consentGivenOrWithdrawnMidSessionChangesOnlyTheList(@TempDir Path pluginsDir) throws Exception {
        Path a = Files.createDirectories(pluginsDir.resolve("alpha"));
        Files.writeString(a.resolve("plugin.json"), "{ \"id\": \"alpha\", \"main\": \"x.Y\" }");
        java.util.Set<String> on = new java.util.HashSet<>();
        PluginManager pm = new PluginManager(pluginsDir, on::contains);
        pm.discover();
        assertFalse(pm.launchSet().get(0).enabled());

        on.add("alpha"); // enabled in Settings
        pm.discover();
        assertTrue(pm.descriptors().get(0).enabled(), "the row says enabled");
        assertFalse(pm.launchSet().get(0).enabled(), "but nothing of it runs before a restart");
        assertNull(pm.running("alpha"));
    }

    // --- identity: a plugin is its folder --------------------------------------------------------------

    /** B1/C11/E14: a folder whose manifest claims another plugin's id is listed with an error, never loaded. */
    @Test
    void aFolderThatDeclaresAnotherIdIsAnErrorRowAndNeverLoads(@TempDir Path pluginsDir) throws Exception {
        Path real = Files.createDirectories(pluginsDir.resolve("word-count"));
        Files.writeString(real.resolve("plugin.json"), "{ \"id\": \"word-count\" }");
        Path fork = Files.createDirectories(pluginsDir.resolve("zz-cool-theme"));
        Files.writeString(
                fork.resolve("plugin.json"),
                "{ \"id\": \"word-count\", \"main\": \"evil.Main\", \"keymap\": { \"C-x C-s\": \"app.quit\" } }");
        PluginManager pm = new PluginManager(pluginsDir, "word-count"::equals);
        pm.discover();

        assertEquals(2, pm.descriptors().size());
        PluginDescriptor impostor = pm.descriptors().get(1);
        assertEquals("zz-cool-theme", impostor.id(), "listed under its folder's name");
        assertFalse(impostor.enabled());
        assertNull(impostor.classLoader());
        assertEquals(
                PluginDescriptor.LoadProblem.Kind.ID_MISMATCH,
                impostor.problem().kind());
        assertEquals(List.of("word-count", "zz-cool-theme"), impostor.problem().args());
        assertTrue(pm.descriptors().get(0).enabled(), "the real one is unaffected");
    }

    // --- consent fingerprints ----------------------------------------------------------------------------

    @Test
    void theDigestChangesWithWhatTheConsentDisclosesAndOnlyWithThat() throws Exception {
        PluginManifest base = parse("""
                { "id": "x", "name": "X", "version": "1.0", "main": "a.Main",
                  "commands": [ { "id": "c", "title": "C", "run": ["sh", "-c", "a b"] } ],
                  "keymap": { "C-c x": "c" } }
                """);
        String d = PluginDescriptor.digest(base, List.of("x.jar"));
        assertEquals(
                d,
                PluginDescriptor.digest(parse("""
                { "id": "x", "name": "Renamed", "version": "2.0", "description": "new", "main": "a.Main",
                  "commands": [ { "id": "c", "title": "C", "run": ["sh", "-c", "a b"] } ],
                  "keymap": { "C-c x": "c" } }
                """), List.of("x.jar")),
                "name, version and description disclose nothing");
        assertFalse(d.equals(PluginDescriptor.digest(base, List.of("x.jar", "lib/more.jar"))), "a new jar");
        assertFalse(
                d.equals(PluginDescriptor.digest(parse("""
                { "id": "x", "main": "a.Main",
                  "commands": [ { "id": "c", "title": "C", "run": ["sh", "-c", "a", "b"] } ],
                  "keymap": { "C-c x": "c" } }
                """), List.of("x.jar"))),
                "[\"a b\"] and [\"a\", \"b\"] are different commands");
        assertFalse(d.equals(PluginDescriptor.digest(parse("""
                { "id": "x", "main": "a.Main",
                  "commands": [ { "id": "c", "title": "C", "run": ["sh", "-c", "a b"] } ],
                  "keymap": { "C-c x": "c", "C-x C-s": "" } }
                """), List.of("x.jar"))), "a new chord (an unbind too)");
        assertFalse(d.equals(PluginDescriptor.digest(parse("""
                { "id": "x", "main": "a.Main",
                  "commands": [ { "id": "c", "title": "C", "run": ["sh", "-c", "a b"] } ],
                  "keymap": { "C-c x": "c" }, "keymaps": { "cua": { "C-q": "c" } } }
                """), List.of("x.jar"))), "a keymap-specific chord");
    }

    /** E5: the store allows a plugin only with the fingerprint it was enabled with. */
    @Test
    void aStoredConsentCoversOnlyWhatWasShown(@TempDir Path pluginsDir) throws Exception {
        Path a = Files.createDirectories(pluginsDir.resolve("ghost"));
        Files.writeString(a.resolve("plugin.json"), "{ \"id\": \"ghost\" }");
        com.editora.config.PluginStore store = new com.editora.config.PluginStore();
        PluginManager pm = new PluginManager(pluginsDir, (PluginManager.Consent) store::allows);
        pm.discover();
        store.enable("ghost", pm.descriptors().get(0).digest());
        pm.discover();
        assertTrue(pm.descriptors().get(0).enabled());

        // Removed by hand and replaced by something else under the same name: not what was agreed to.
        Files.writeString(a.resolve("plugin.json"), "{ \"id\": \"ghost\", \"main\": \"rm.Rf\" }");
        pm.discover();
        assertFalse(pm.descriptors().get(0).enabled());
        assertTrue(store.changedSinceConsent("ghost", pm.descriptors().get(0).digest()));
    }

    @Test
    void reconcileAdoptsAnOldConsentOnceAndDropsThoseOfMissingFolders() {
        com.editora.config.PluginStore store = new com.editora.config.PluginStore();
        store.enabled.put("kept", true);
        store.consent.put("kept", com.editora.config.PluginStore.ADOPT);
        store.enabled.put("gone", true);
        store.consent.put("gone", com.editora.config.PluginStore.ADOPT);
        store.enabled.put("off", false);
        java.util.Map<String, String> present = new java.util.HashMap<>();
        present.put("kept", "d1");
        present.put("off", "d2");

        assertTrue(store.reconcile(present));
        assertEquals("d1", store.consent.get("kept"), "the plugin there now is the one the user had enabled");
        assertTrue(store.allows("kept", "d1"));
        assertFalse(store.allows("kept", "d2"));
        assertFalse(store.enabled.containsKey("gone"), "a stale true would arm whatever appears there later");
        assertFalse(store.consent.containsKey("gone"));
        assertFalse(store.reconcile(present), "nothing left to change");
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
