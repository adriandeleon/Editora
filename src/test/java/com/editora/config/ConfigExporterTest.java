package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigExporterTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 6, 4, 15, 30, 12);

    @Test
    void zipNameDropsLeadingDotAndEmbedsVersionUserTime() {
        assertEquals(
                "editora-config-1.0.0-adriandeleon-2026-06-04_153012.zip",
                ConfigExporter.zipName(Path.of(".editora"), "1.0.0", "adriandeleon", WHEN));
        assertEquals(
                "editora-dev-config-1.0.0-adriandeleon-2026-06-04_153012.zip",
                ConfigExporter.zipName(Path.of(".editora-dev"), "1.0.0", "adriandeleon", WHEN));
        // A custom --config-dir with no leading dot keeps its name.
        assertEquals(
                "myconf-config-2.1-bob-2026-06-04_153012.zip",
                ConfigExporter.zipName(Path.of("/tmp/myconf"), "2.1", "bob", WHEN));
    }

    @Test
    void zipNameSanitizesUnsafeVersionAndUser() {
        // Spaces/slashes in a username collapse to a single underscore; result stays a single segment.
        assertEquals(
                "editora-config-1.0.0_beta-jo_hn_doe-2026-06-04_153012.zip",
                ConfigExporter.zipName(Path.of(".editora"), "1.0.0 beta", "jo/hn doe", WHEN));
    }

    @Test
    void sanitizeHandlesEmptyAndNull() {
        assertEquals("unknown", ConfigExporter.sanitize(null));
        assertEquals("unknown", ConfigExporter.sanitize("   "));
        assertEquals("unknown", ConfigExporter.sanitize("///"));
        assertEquals("a.b-c_d", ConfigExporter.sanitize("a.b-c_d"));
    }

    @Test
    void exportZipsAllFilesWithRelativeEntries(@TempDir Path tmp) throws Exception {
        Path cfg = Files.createDirectories(tmp.resolve(".editora"));
        Files.writeString(cfg.resolve("settings.toml"), "fontSize = 14\n");
        Files.createDirectories(cfg.resolve("projects"));
        Files.writeString(cfg.resolve("projects").resolve("p1.json"), "{}");
        Path dest = Files.createDirectories(tmp.resolve("home"));

        Path zip = ConfigExporter.export(cfg, dest, "1.0.0", "tester", WHEN);

        assertEquals(dest.resolve("editora-config-1.0.0-tester-2026-06-04_153012.zip"), zip);
        assertTrue(Files.exists(zip), "zip created");

        List<String> entries = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (var it = zf.entries(); it.hasMoreElements(); ) {
                ZipEntry e = it.nextElement();
                entries.add(e.getName());
            }
            // forward-slash separated, relative to the config dir
            assertTrue(entries.contains("settings.toml"), "settings.toml entry: " + entries);
            assertTrue(entries.contains("projects/p1.json"), "nested entry with / separator: " + entries);
            ZipEntry settings = zf.getEntry("settings.toml");
            String content = new String(zf.getInputStream(settings).readAllBytes());
            assertEquals("fontSize = 14\n", content, "file content preserved");
        }
        assertEquals(2, entries.size(), "only regular files, no directory entries");
    }

    @Test
    void exportOfMissingDirYieldsEmptyZip(@TempDir Path tmp) throws Exception {
        Path dest = Files.createDirectories(tmp.resolve("home"));
        Path zip = ConfigExporter.export(tmp.resolve("does-not-exist"), dest, "1.0.0", "tester", WHEN);
        assertTrue(Files.exists(zip), "empty zip still created");
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertEquals(0, zf.size(), "no entries");
        }
    }
}
