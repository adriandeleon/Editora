package com.editora.config;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.editora.config.ConfigImport.Part;
import com.editora.config.ConfigImport.Refused;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Import Configuration's checks and its effect on a config folder (a temp folder; never the real one). */
class ConfigImportTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 10, 18, 9, 30, 0);

    @TempDir
    Path tmp;

    private Path zip(Map<String, byte[]> entries) throws IOException {
        Path zip = Files.createTempFile(tmp, "export-", ".zip");
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return zip;
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String settingsJson(Map<String, Object> values) throws IOException {
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("schemaVersion", Settings.SCHEMA_VERSION);
        all.putAll(values);
        return JSON.writeValueAsString(all);
    }

    private Refused refused(Map<String, byte[]> entries) throws IOException {
        Path zip = zip(entries);
        return assertThrows(Refused.class, () -> ConfigImport.scan(zip, JSON));
    }

    @Test
    void entryNamesThatLeaveTheFolderAreUnsafe() {
        assertTrue(ConfigImport.isSafeName("settings.json"));
        assertTrue(ConfigImport.isSafeName("snippets/java.json"));
        assertTrue(ConfigImport.isSafeName("themes/"));
        for (String bad : List.of(
                "../settings.json", "snippets/../../x", "/etc/passwd", "C:/x", "a\\b", "a//b", "./x", "", "x\0y")) {
            assertFalse(ConfigImport.isSafeName(bad), bad);
        }
    }

    @Test
    void whatIsNotAnEditoraExportIsRefusedBeforeAnythingIsWritten() throws Exception {
        Path notZip = Files.writeString(tmp.resolve("notes.zip"), "plain text");
        assertEquals(
                Refused.Reason.NOT_A_ZIP,
                assertThrows(Refused.class, () -> ConfigImport.scan(notZip, JSON))
                        .reason());
        assertEquals(
                Refused.Reason.NOT_AN_EXPORT,
                refused(Map.of("README.md", text("hi"))).reason());
        assertEquals(
                Refused.Reason.NOT_AN_EXPORT,
                refused(Map.of("settings.json", text("[1, 2]"))).reason());
        byte[] settings = text(settingsJson(Map.of()));
        Refused traversal = refused(Map.of("settings.json", settings, "../outside.txt", text("x")));
        assertEquals(Refused.Reason.UNSAFE_PATH, traversal.reason());
        assertEquals("../outside.txt", traversal.entry());
        assertEquals(
                Refused.Reason.UNSAFE_PATH,
                refused(Map.of("settings.json", settings, "/abs.json", text("x")))
                        .reason());
        assertEquals(
                Refused.Reason.NEWER_VERSION,
                refused(Map.of("settings.json", text("{\"schemaVersion\": 99999}")))
                        .reason());
    }

    @Test
    void anOversizedEntryIsRefusedByItsRealSizeNotItsHeader() throws Exception {
        Path zip = Files.createTempFile(tmp, "bomb-", ".zip");
        try (OutputStream out = Files.newOutputStream(zip);
                ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.putNextEntry(new ZipEntry("settings.json"));
            zos.write(text(settingsJson(Map.of())));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("snippets/huge.json"));
            byte[] zeros = new byte[1 << 20];
            for (long written = 0; written <= ConfigImport.MAX_ENTRY_BYTES; written += zeros.length) {
                zos.write(zeros); // compresses to almost nothing: only the inflated size gives it away
            }
            zos.closeEntry();
        }
        Refused r = assertThrows(Refused.class, () -> ConfigImport.scan(zip, JSON));
        assertEquals(Refused.Reason.TOO_LARGE, r.reason());
        assertEquals("snippets/huge.json", r.entry());
    }

    @Test
    void theZipIsSortedIntoPartsAndThisComputersOwnStateIsLeftOut() throws Exception {
        Path cfg = Files.createDirectories(tmp.resolve("cfg"));
        Files.createDirectories(cfg.resolve("snippets"));
        Files.writeString(cfg.resolve("snippets/java.json"), "{}");
        Files.writeString(cfg.resolve("snippets/go.json"), "{}");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("settings.json", text(settingsJson(Map.of("fontSize", 20, "keymap", "default"))));
        entries.put("snippets/java.json", text("{\"a\":1}"));
        entries.put("snippets/python.json", text("{}"));
        entries.put("workspace-state.json", text("{}"));
        entries.put("trusted-folders.json", text("{}"));
        entries.put("history/index.json", text("{}"));
        entries.put("plugins/lsp/jdtls/x.jar", text("bin")); // a built-in tool folder: never a plugin
        ConfigImport.Scan scan = ConfigImport.scan(zip(entries), JSON);

        assertEquals(
                List.of(
                        "history/index.json",
                        "plugins/lsp/jdtls/x.jar",
                        "trusted-folders.json",
                        "workspace-state.json"),
                new ArrayList<>(scan.leftOut()));
        Map<Part, ConfigImport.Summary> byPart = new LinkedHashMap<>();
        scan.summaries(cfg).forEach(s -> byPart.put(s.part(), s));
        assertEquals(new ConfigImport.Summary(Part.SNIPPETS, 2, 1, 1, 1), byPart.get(Part.SNIPPETS));
        assertTrue(byPart.get(Part.PREFERENCES).available());
        assertTrue(byPart.get(Part.KEY_BINDINGS).available(), "the zip names a keymap");
        assertFalse(byPart.get(Part.EXTERNAL_TOOLS).available());
        assertFalse(byPart.get(Part.MACROS).available());
    }

    @Test
    void theTickedPartsReplaceTheirsHereAndNothingElseChanges() throws Exception {
        Path cfg = Files.createDirectories(tmp.resolve("cfg"));
        Files.writeString(
                cfg.resolve("settings.json"),
                settingsJson(Map.of(
                        "fontSize",
                        14,
                        "aiApiKey",
                        "sk-ant-FAKE-HERE",
                        "keymap",
                        "emacs",
                        "keybindings",
                        Map.of("file.save", "C-x C-s"))));
        Files.createDirectories(cfg.resolve("snippets"));
        Files.writeString(cfg.resolve("snippets/go.json"), "{\"mine\":1}");
        Files.writeString(cfg.resolve("macros.json"), "{\"mine\":true}");
        Files.createDirectories(cfg.resolve("plugins/lsp"));
        Files.writeString(cfg.resolve("plugins/lsp/server.bin"), "keep me");

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(
                "settings.json",
                text(settingsJson(Map.of(
                        "fontSize",
                        20,
                        "aiApiKey",
                        "", // an export leaves the keys blank
                        "keymap",
                        "default",
                        "keybindings",
                        Map.of("file.save", "C-s")))));
        entries.put("snippets/java.json", text("{\"theirs\":1}"));
        entries.put("macros.json", text("{\"theirs\":true}"));
        entries.put("plugins.json", text("{}"));
        ConfigImport.Scan scan = ConfigImport.scan(zip(entries), JSON);

        Path backup = ConfigImport.backup(cfg, WHEN);
        ConfigImport.apply(scan, EnumSet.of(Part.PREFERENCES, Part.SNIPPETS, Part.PLUGINS), cfg, JSON);

        var settings = JSON.readTree(cfg.resolve("settings.json").toFile());
        assertEquals(20, settings.get("fontSize").asInt(), "preferences came in");
        assertEquals("sk-ant-FAKE-HERE", settings.get("aiApiKey").asText(), "a blank key never replaces one");
        assertEquals("emacs", settings.get("keymap").asText(), "key bindings were left unticked");
        assertEquals("C-x C-s", settings.get("keybindings").get("file.save").asText());
        assertEquals("{\"theirs\":1}", Files.readString(cfg.resolve("snippets/java.json")));
        assertFalse(Files.exists(cfg.resolve("snippets/go.json")), "the part is replaced as a whole");
        assertEquals("{\"mine\":true}", Files.readString(cfg.resolve("macros.json")), "unticked: untouched");
        assertEquals("keep me", Files.readString(cfg.resolve("plugins/lsp/server.bin")), "built-in tools stay");

        assertEquals(cfg.resolve("backups/config.before-import-20261018-093000.zip"), backup);
        try (ZipFile zf = new ZipFile(backup.toFile())) {
            String before =
                    new String(zf.getInputStream(zf.getEntry("settings.json")).readAllBytes());
            assertTrue(before.contains("sk-ant-FAKE-HERE"), "the copy is complete, keys included");
            assertTrue(new String(
                            zf.getInputStream(zf.getEntry("snippets/go.json")).readAllBytes())
                    .contains("mine"));
        }
        Path export = ConfigExporter.export(cfg, Files.createDirectories(tmp.resolve("out")), "1", "me", WHEN);
        try (ZipFile zf = new ZipFile(export.toFile())) {
            assertNull(zf.getEntry("backups/config.before-import-20261018-093000.zip"), "backups are not exported");
        }
    }
}
