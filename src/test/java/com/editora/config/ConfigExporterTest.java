package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
        Files.writeString(cfg.resolve("settings.json"), "{\"fontSize\":14}\n");
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
            assertTrue(entries.contains("settings.json"), "settings.json entry: " + entries);
            assertTrue(entries.contains("projects/p1.json"), "nested entry with / separator: " + entries);
            ZipEntry settings = zf.getEntry("settings.json");
            String content = new String(zf.getInputStream(settings).readAllBytes());
            assertEquals("{\"fontSize\":14}\n", content, "file content preserved");
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

    @Test
    void theExportedZipIsNotReadableByOtherUsers(@TempDir Path cfg, @TempDir Path home) throws Exception {
        // The export lands in the user's home directory and contains a copy of settings.json — and so of the
        // AI provider's API key — plus the user's private notes. Writing the config dir's own files owner-only
        // while dropping a world-readable archive of them next door would protect nothing.
        assumeTrue(cfg.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.writeString(cfg.resolve("settings.json"), "{\"aiApiKey\":\"sk-ant-api03-BILLABLE\"}\n");

        Path zip = ConfigExporter.export(cfg, home, "1.0.0", "someone", WHEN);

        var perms = Files.getPosixFilePermissions(zip);
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ), "the export must not be world-readable");
        assertEquals("rw-------", PosixFilePermissions.toString(perms));
    }

    private static List<String> entryNames(Path zip) throws Exception {
        List<String> names = new ArrayList<>();
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            for (var it = zf.entries(); it.hasMoreElements(); ) {
                names.add(it.nextElement().getName());
            }
        }
        return names;
    }

    @Test
    void downloadedServersCachesAndRuntimeFilesAreNotConfiguration(@TempDir Path cfg, @TempDir Path home)
            throws Exception {
        for (String kept : List.of(
                "settings.json",
                "settings.json.v104.bak",
                "notes.json",
                "projects/app-1.json",
                "search-history.json",
                "plugins/my-plugin/plugin.jar",
                "snippets/java.json")) {
            Files.createDirectories(cfg.resolve(kept).getParent());
            Files.writeString(cfg.resolve(kept), "kept");
        }
        for (String skipped : List.of(
                "plugins/lsp/java/jdtls/plugins/big.jar",
                "plugins/dap/java/java-debug.jar",
                "jdtls-workspaces/abc/.metadata/index",
                // A15: Local History is the user's files, not their configuration.
                "history/index.json",
                "history/index.json.corrupt.bak",
                "history/blobs/ab/cdef.txt.gz",
                "instance.lock",
                "instance.properties",
                "mcp-endpoint.json",
                "spawned-servers.txt",
                "editora-session.log",
                ".settings.json-1234.tmp")) {
            Files.createDirectories(cfg.resolve(skipped).getParent());
            Files.writeString(cfg.resolve(skipped), "skipped");
        }

        List<String> entries = entryNames(ConfigExporter.export(cfg, home, "1.0.0", "tester", WHEN));

        assertEquals(
                List.of(
                        "notes.json",
                        "plugins/my-plugin/plugin.jar",
                        "projects/app-1.json",
                        "search-history.json",
                        "settings.json",
                        "settings.json.v104.bak",
                        "snippets/java.json"),
                entries.stream().sorted().toList());
    }

    @Test
    void aFailedExportLeavesNoPartialZipBehind(@TempDir Path cfg, @TempDir Path home) throws Exception {
        assumeTrue(cfg.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.writeString(cfg.resolve("a-settings.json"), "{}");
        Path secret = Files.writeString(cfg.resolve("notes.json"), "{}");
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("---------"));
        assumeTrue(!Files.isReadable(secret), "running as a user that can read anything");

        assertThrows(java.io.IOException.class, () -> ConfigExporter.export(cfg, home, "1.0.0", "tester", WHEN));

        try (var left = Files.list(home)) {
            assertEquals(List.of(), left.toList(), "a truncated archive must not pass for a backup");
        }
    }

    @Test
    void theAsyncExportDoesNotBlockItsCallerAndReportsThroughTheFuture(@TempDir Path cfg, @TempDir Path home)
            throws Exception {
        SharedConfig shared = new SharedConfig(cfg, false);
        shared.load();
        shared.saveSettings();
        // An export first waits for pending config writes. Hold one up: a caller that did the export itself
        // (the FX thread, before) would be stuck here until the wait timed out.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        shared.writer().enqueue(cfg.resolve("notes.json"), () -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{}".getBytes();
        });
        try {
            var export = shared.exportConfigAsync(home);
            assertFalse(export.isDone(), "returned while the export is still waiting");
            assertEquals(export, shared.exportConfigAsync(home), "a second request joins the running export");
            release.countDown();

            Path zip = export.get(30, java.util.concurrent.TimeUnit.SECONDS);
            List<String> entries = entryNames(zip);
            assertTrue(entries.contains("settings.json") && entries.contains("notes.json"), "exported: " + entries);

            // A destination that does not exist: the failure arrives through the future, not as a throw.
            var failed = shared.exportConfigAsync(home.resolve("missing"));
            var thrown = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> failed.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(thrown.getCause() instanceof java.io.IOException, String.valueOf(thrown.getCause()));
        } finally {
            release.countDown();
            shared.shutdown();
        }
    }
}
