package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.Settings;
import com.editora.i18n.Messages;
import com.editora.plugin.PluginManager;
import com.editora.plugin.PluginManifest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of the plugin UI: the consent text, the Settings row state, dialog sizing, the MCP refusal. */
class PluginUiHelpersTest {

    @AfterEach
    void english() {
        Messages.init("en");
    }

    // --- the disclosure (A1, B3, B7, A7) -------------------------------------------------------------

    @Test
    void manifestStringsAreShownOnOneLineWithInvisibleCharactersMadeVisible() {
        assertEquals("a\\nb\\tc", PluginDisclosure.clean("a\nb\tc", 100));
        assertEquals("rtl<U+202E>txt", PluginDisclosure.clean("rtl‮txt", 100));
        assertEquals("zero<U+200B>width", PluginDisclosure.clean("zero​width", 100));
        assertEquals("bell<U+0007>", PluginDisclosure.clean("bell\u0007", 100));
        assertEquals("abc…", PluginDisclosure.clean("abcdef", 3));
        assertEquals("", PluginDisclosure.clean(null, 3));
        assertEquals("[\"sh\", \"-c\", \"a b\"]", PluginDisclosure.quoted(List.of("sh", "-c", "a b")));
        assertEquals("[\"sh\", \"-c\", \"a\", \"b\"]", PluginDisclosure.quoted(List.of("sh", "-c", "a", "b")));
        assertEquals("[\"say \\\"hi\\\"\"]", PluginDisclosure.quoted(List.of("say \"hi\"")));
    }

    private static PluginManifest manifest() {
        PluginManifest m = new PluginManifest();
        m.id = "fmt";
        m.name = "Formatter";
        m.main = "com.example.Fmt";
        PluginManifest.DeclaredCommand run = new PluginManifest.DeclaredCommand();
        run.id = "pretty";
        run.title = "Format: Pretty";
        run.run = List.of("prettier", "--write", "my file.js");
        run.dir = "..";
        PluginManifest.DeclaredCommand blank = new PluginManifest.DeclaredCommand();
        blank.id = " ";
        blank.run = List.of("never");
        m.commands = List.of(run, blank);
        m.keymap = new java.util.LinkedHashMap<>(Map.of("C-x C-s", "pretty"));
        m.keymap.put("C-x C-f", "");
        m.keymaps = Map.of("cua", Map.of("C-S-f", "pretty"));
        return m;
    }

    private static final PluginDisclosure.Keys KEYS = new PluginDisclosure.Keys() {
        @Override
        public String display(String chord) {
            return chord.replace("C-", "Ctrl+");
        }

        @Override
        public String boundTitle(String chord) {
            return switch (chord) {
                case "C-x C-s" -> "Save";
                case "C-x C-f" -> "Open File";
                default -> null;
            };
        }

        @Override
        public String commandTitle(String id) {
            return id.equals("plugin.fmt.pretty") ? "Format: Pretty" : id;
        }
    };

    @Test
    void theDisclosureListsTheCodeEachCommandExactlyAndWhatEachChordReplaces() {
        String text = PluginDisclosure.capabilities(
                manifest(), List.of("fmt.jar", "lib/dep.jar"), true, false, "emacs", KEYS);
        assertTrue(text.contains(tr("plugins.cap.code")), text);
        assertTrue(text.contains(tr("plugins.cap.mainClass", "com.example.Fmt")), text);
        assertTrue(text.contains(tr("plugins.cap.jars", "fmt.jar, lib/dep.jar")), "every jar it loads: " + text);
        assertTrue(text.contains("Format: Pretty: [\"prettier\", \"--write\", \"my file.js\"]"), text);
        assertTrue(text.contains(tr("plugins.cap.inDir", "..")), "where it runs: " + text);
        assertFalse(text.contains("never"), "a command with no id is never registered, so not listed");
        assertTrue(text.contains(tr("plugins.cap.replaces", "Ctrl+x Ctrl+s", "Format: Pretty", "Save")), text);
        assertTrue(text.contains(tr("plugins.cap.unbindsNamed", "Ctrl+x Ctrl+f", "Open File")), text);
        assertTrue(text.contains(tr("plugins.cap.keymapOther", "cua")), "the other keymaps' chords too: " + text);
        assertTrue(text.contains(tr("plugins.cap.snippets")), text);
        assertFalse(text.contains(tr("plugins.cap.templates")));

        String cua = PluginDisclosure.capabilities(manifest(), List.of(), false, false, "cua", KEYS);
        assertTrue(cua.contains("Ctrl+S-f → Format: Pretty"), "the active keymap's own chords: " + cua);
        assertTrue(cua.contains(tr("plugins.cap.noJars")), cua);
    }

    @Test
    void aPluginThatDoesNothingSaysSoInsteadOfClaimingToBeSafeAssets() {
        PluginManifest empty = new PluginManifest();
        empty.id = "empty";
        assertEquals(
                tr("plugins.cap.nothing"),
                PluginDisclosure.capabilities(empty, List.of(), false, false, "emacs", PluginDisclosure.RAW));
    }

    @Test
    void anUnqualifiedChordTargetIsThePluginsOwnCommand() {
        assertEquals("plugin.fmt.pretty", PluginManifest.qualify("fmt", "pretty"));
        assertEquals("file.save", PluginManifest.qualify("fmt", "file.save"));
        assertEquals("", PluginManifest.qualify("fmt", " "));
        PluginManifest m = manifest();
        assertEquals("plugin.fmt.pretty", m.qualifiedKeymapFor("emacs").get("C-x C-s"));
        assertEquals("", m.qualifiedKeymapFor("emacs").get("C-x C-f"));
        assertEquals(Map.of("C-S-f", "plugin.fmt.pretty"), m.qualifiedKeymapFor("cua"));
    }

    // --- the Settings row (E12) -----------------------------------------------------------------------

    @Test
    void aRowSaysWhatARestartWouldChange() {
        assertEquals(PluginRowState.Pill.NONE, PluginRowState.pill(true, true, true, "1", "1", null, false));
        assertEquals(PluginRowState.Pill.NONE, PluginRowState.pill(false, false, false, null, "1", null, false));
        assertEquals(
                PluginRowState.Pill.RESTART_TO_LOAD,
                PluginRowState.pill(true, true, false, null, "1", null, false),
                "installed and accepted this session: it does not run yet");
        assertEquals(
                PluginRowState.Pill.RESTART_TO_UNLOAD, PluginRowState.pill(false, false, true, "1", "1", null, false));
        assertEquals(PluginRowState.Pill.UPDATE_PENDING, PluginRowState.pill(false, true, true, "1", "1", "2", false));
        assertEquals(
                PluginRowState.Pill.REMOVAL_PENDING, PluginRowState.pill(false, false, true, "1", "1", null, true));
        assertEquals(
                PluginRowState.Pill.CHANGED,
                PluginRowState.pill(false, true, false, null, "2", null, false),
                "switched on, but not what was agreed to");
        assertEquals(PluginRowState.Pill.UPDATE_PENDING, PluginRowState.pill(true, true, true, "1", "2", null, false));
        assertNull(PluginRowState.text(PluginRowState.Pill.NONE, null));
        assertEquals(
                tr("settings.plugins.updatePill", "2"), PluginRowState.text(PluginRowState.Pill.UPDATE_PENDING, "2"));
    }

    // --- dialogs (A19) ------------------------------------------------------------------------------

    @Test
    void anErrorBoxIsSizedForItsWrappedLinesNotItsLineBreaks() {
        assertEquals(2, PluginDialogs.rows(""));
        assertEquals(2, PluginDialogs.rows("short"));
        String sentence = "x".repeat(PluginDialogs.COLUMNS * 3 + 1);
        assertEquals(5, PluginDialogs.rows(sentence), "one long sentence wraps to four rows");
        assertEquals(4, PluginDialogs.rows("a\nb\nc"));
    }

    // --- MCP (B6) -----------------------------------------------------------------------------------

    @Test
    void anMcpClientCannotFlipPluginOrTrustSettings() {
        for (String id : List.of(
                "view.togglePlugins",
                "plugins.toggleRequireSignature",
                "plugins.setRegistryUrl",
                "plugins.browse",
                "plugins.installFromDisk",
                "lsp.trustProjectSettings",
                "workspace.manageTrust",
                "workspace.revokeTrust")) {
            assertNotNull(McpAccess.commandRefusal(id), id);
        }
        for (String id : List.of("file.save", "view.toggleGit", "plugin.fmt.pretty", "edit.selectAll")) {
            assertNull(McpAccess.commandRefusal(id), id);
        }
        assertNull(McpAccess.commandRefusal(null));
    }

    // --- registry URL (A18) -------------------------------------------------------------------------

    @Test
    void theRegistrySetterRefusesWhatIsNotHttpsForEveryCaller() {
        Settings s = new Settings();
        s.setPluginRegistryUrl("https://plugins.example.org/index.json");
        s.setPluginRegistryUrl("http://insecure.example/index.json");
        assertEquals(
                "https://plugins.example.org/index.json", s.getPluginRegistryUrl(), "refused: the one in use stays");
        s.setPluginRegistryUrl("not a url");
        assertEquals("https://plugins.example.org/index.json", s.getPluginRegistryUrl());
        s.setPluginRegistryUrl("");
        assertEquals(Settings.DEFAULT_PLUGIN_REGISTRY, s.getPluginRegistryUrl(), "blank: back to the built-in");
        assertTrue(Settings.isAcceptableRegistryUrl(null));
        assertFalse(Settings.isAcceptableRegistryUrl("https:///nohost"));
    }

    // --- keymap rebuilds (C4, B5, C10) ---------------------------------------------------------------

    @Test
    void aKeymapRebuildKeepsChordsBoundInCodeAndHonoursSimpleModeAndTheActiveKeymap(@TempDir Path dir)
            throws Exception {
        Path p = Files.createDirectories(dir.resolve("demo"));
        Files.writeString(
                p.resolve("plugin.json"),
                "{\"id\":\"demo\",\"keymap\":{\"C-c C-d\":\"run\"},\"keymaps\":{\"cua\":{\"C-S-d\":\"run\"}}}");
        PluginManager pm = new PluginManager(dir, id -> true);
        pm.discover();
        pm.recordCodeBinding("demo", "C-c C-h", "plugin.demo.hello");
        Settings settings = new Settings();

        List<Map<String, String>> emacs = KeymapLayers.pluginKeymaps(settings, pm, "emacs");
        assertEquals(List.of(Map.of("C-c C-d", "plugin.demo.run", "C-c C-h", "plugin.demo.hello")), emacs);
        assertEquals(
                List.of(Map.of("C-S-d", "plugin.demo.run", "C-c C-h", "plugin.demo.hello")),
                KeymapLayers.pluginKeymaps(settings, pm, "cua"));

        settings.setSimpleMode(true);
        assertEquals(List.of(), KeymapLayers.pluginKeymaps(settings, pm, "emacs"), "Simple mode: plugins are off");
    }
}
