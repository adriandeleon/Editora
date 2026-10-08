package com.editora.plugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import com.editora.io.LoopbackDownloads;
import com.editora.io.TestArchives;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installing a plugin from a registry entry (download, checksum, unpack) and from a zip on disk, through the
 * entry points the UI uses. Archives are served by a loopback server ({@link LoopbackDownloads}); the plugins
 * folder is a JUnit temp dir.
 *
 * <p>The hostile-archive cases repeat {@link PluginInstallSafetyTest}'s through the download path, each with
 * a <em>correct</em> checksum: a registry that lists a malicious archive lists its real hash too, so the
 * checksum is no defence there and the unpacking rules have to hold on their own.
 */
class PluginInstallerUrlTest {

    private static final String ZIP_URL = "https://plugins.example.org/releases/notes-1.0.zip";
    private static final long ARCHIVE_CAP = 256 * 1024;

    @TempDir
    Path sandbox;

    private Path pluginsDir;
    private LoopbackDownloads web;
    private PluginManager manager;
    private PluginInstaller installer;

    @BeforeEach
    void setUp() throws IOException {
        pluginsDir = sandbox.resolve("config/plugins"); // not created: a first install has to make it
        web = new LoopbackDownloads();
        manager = new PluginManager(pluginsDir, id -> false);
        installer = new PluginInstaller(manager, web.client(), ARCHIVE_CAP);
    }

    @AfterEach
    void tearDown() {
        installer.shutdown();
        manager.closeAll();
        web.close();
    }

    private static RegistryEntry entry(String url, String sha256) {
        RegistryEntry e = new RegistryEntry();
        e.id = "notes";
        e.name = "Notes";
        e.download = url;
        e.sha256 = sha256;
        return e;
    }

    /** Serves {@code zip} and returns a registry entry for it carrying its true checksum. */
    private RegistryEntry published(byte[] zip) {
        web.serve(ZIP_URL, zip);
        return entry(ZIP_URL, PluginInstaller.sha256(zip));
    }

    private static byte[] notesZip(String version, String... more) {
        List<String> entries = new ArrayList<>(
                List.of("plugin.json", "{\"id\":\"notes\",\"name\":\"Notes\",\"version\":\"" + version + "\"}"));
        entries.addAll(Arrays.asList(more));
        return TestArchives.zip(entries.toArray(String[]::new));
    }

    private List<String> installedFiles() throws IOException {
        if (!Files.exists(pluginsDir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(pluginsDir)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> pluginsDir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /** Every path in the sandbox that is not inside the plugins folder. */
    private List<String> outsideThePluginsFolder() throws IOException {
        try (Stream<Path> walk = Files.walk(sandbox)) {
            return walk.filter(p -> !p.startsWith(pluginsDir) && !pluginsDir.startsWith(p))
                    .map(p -> sandbox.relativize(p).toString())
                    .sorted()
                    .toList();
        }
    }

    private void assertRefused(PluginInstaller.Result result, String expectedInError) throws IOException {
        assertFalse(result.ok());
        assertTrue(
                result.error() != null && result.error().contains(expectedInError),
                "expected \"" + expectedInError + "\" in: " + result.error());
        assertEquals("", result.id());
        assertEquals(List.of(), installedFiles(), "a refused archive installs nothing — and leaves no staging folder");
        assertEquals(List.of(), outsideThePluginsFolder());
        assertEquals(List.of(), manager.descriptors());
    }

    // --- a good download ----------------------------------------------------------------------------

    @Test
    void aDownloadWithTheListedChecksumIsInstalledAndDiscovered() throws IOException {
        RegistryEntry e = published(notesZip("1.0", "snippets/markdown.json", "{}"));

        PluginInstaller.Result result = installer.installFromUrlSync(e);

        assertTrue(result.ok(), result.error());
        assertEquals("notes", result.id());
        assertEquals("Notes", result.name());
        assertEquals(List.of("notes/plugin.json", "notes/snippets/markdown.json"), installedFiles());
        assertEquals(1, manager.descriptors().size(), "the plugin is listed without a restart");
        assertEquals("1.0", manager.descriptors().get(0).manifest().version);
        assertFalse(manager.descriptors().get(0).enabled(), "installing is not enabling");
        assertEquals(List.of(ZIP_URL), web.requests());
    }

    @Test
    void theChecksumIsComparedWithoutRegardToCaseOrPadding() throws IOException {
        RegistryEntry e = published(notesZip("1.0"));
        e.sha256 = "  " + e.sha256.toUpperCase(Locale.ROOT) + "\n";

        assertTrue(installer.installFromUrlSync(e).ok());
    }

    /** A release asset is usually reached through a redirect to the host that stores it. */
    @Test
    void aRedirectedDownloadIsFollowedAndStillChecksummed() throws IOException {
        byte[] zip = notesZip("1.0");
        String stored = "https://objects.example.org/blobs/7f3a";
        web.redirect(ZIP_URL, stored);
        web.serve(stored, zip);

        assertTrue(installer
                .installFromUrlSync(entry(ZIP_URL, PluginInstaller.sha256(zip)))
                .ok());

        web.serve(stored, notesZip("6.6.6")); // the storage host now serves something else
        assertRefusedKeepingVersion(installer.installFromUrlSync(entry(ZIP_URL, PluginInstaller.sha256(zip))), "1.0");
    }

    @Test
    void anUpdateFromTheRegistryKeepsThePluginsData() throws IOException {
        assertTrue(installer
                .installFromUrlSync(published(notesZip("1.0", "old-only.txt", "v1")))
                .ok());
        Files.writeString(
                Files.createDirectories(pluginsDir.resolve("notes/data")).resolve("db"), "user data");

        PluginInstaller.Result update = installer.installFromUrlSync(published(notesZip("2.0", "new-only.txt", "v2")));

        assertTrue(update.ok(), update.error());
        assertEquals(List.of("notes/data/db", "notes/new-only.txt", "notes/plugin.json"), installedFiles());
        assertEquals("user data", Files.readString(pluginsDir.resolve("notes/data/db")));
        assertEquals("2.0", manager.descriptors().get(0).manifest().version);
    }

    // --- what is refused before or after the download ------------------------------------------------

    @Test
    void aDownloadThatDoesNotMatchItsChecksumIsNotUnpacked() throws IOException {
        web.serve(ZIP_URL, notesZip("1.0"));

        assertRefused(installer.installFromUrlSync(entry(ZIP_URL, "0".repeat(64))), "checksum mismatch");
        assertRefused(
                installer.installFromUrlSync(entry(ZIP_URL, PluginInstaller.sha256(notesZip("1.1")))),
                "checksum mismatch");
    }

    @Test
    void anEntryWithoutAChecksumIsRefusedWithoutARequest() throws IOException {
        web.serve(ZIP_URL, notesZip("1.0"));

        for (String missing : Arrays.asList(null, "", "   ")) {
            assertRefused(installer.installFromUrlSync(entry(ZIP_URL, missing)), "no sha256");
        }
        assertEquals(List.of(), web.requests());
    }

    @Test
    void aDownloadUrlThatIsNotHttpsIsRefusedWithoutARequest() throws IOException {
        String sha = PluginInstaller.sha256(notesZip("1.0"));

        for (String url : Arrays.asList(
                "http://plugins.example.org/releases/notes-1.0.zip",
                "file:///tmp/notes.zip",
                "jar:https://plugins.example.org/a.zip!/b",
                "",
                null)) {
            assertRefused(installer.installFromUrlSync(entry(url, sha)), "must be https");
        }
        assertRefused(installer.installFromUrlSync(null), "must be https");
        assertEquals(List.of(), web.requests());
    }

    @Test
    void aDownloadThatFailsIsReportedWithItsStatus() throws IOException {
        String sha = PluginInstaller.sha256(notesZip("1.0"));

        assertRefused(installer.installFromUrlSync(entry(ZIP_URL, sha)), "HTTP 404");
        web.status(ZIP_URL, 500);
        assertRefused(installer.installFromUrlSync(entry(ZIP_URL, sha)), "HTTP 500");
    }

    @Test
    void aDownloadCutOffPartWayInstallsNothing() throws IOException {
        byte[] zip = notesZip("1.0", "big.txt", "x".repeat(20_000));
        web.cutOff(ZIP_URL, Arrays.copyOf(zip, zip.length / 2), zip.length);

        PluginInstaller.Result result = installer.installFromUrlSync(entry(ZIP_URL, PluginInstaller.sha256(zip)));

        assertFalse(result.ok());
        assertFalse(result.error() == null || result.error().isBlank());
        assertEquals(List.of(), installedFiles());
    }

    @Test
    void aBodyThatIsNotAnArchiveIsRefused() throws IOException {
        byte[] page = "<html>Sign in to continue</html>".getBytes(StandardCharsets.UTF_8);

        assertRefused(installer.installFromUrlSync(published(page)), "no plugin.json in archive");
    }

    @Test
    void anOversizeDownloadIsAbandonedAtTheCap() throws IOException {
        byte[] big = new byte[(int) ARCHIVE_CAP + 1];
        Arrays.fill(big, (byte) 'x');

        assertRefused(installer.installFromUrlSync(published(big)), "exceeds " + ARCHIVE_CAP + " bytes");
    }

    // --- hostile archives, correctly checksummed ------------------------------------------------------

    @Test
    void anArchiveEntryThatClimbsOutOfThePluginFolderIsRefused() throws IOException {
        assertRefused(
                installer.installFromUrlSync(published(notesZip("1.0", "../../escaped.txt", "pwned"))),
                "unsafe zip entry name");
        assertRefused(
                installer.installFromUrlSync(published(notesZip("1.0", "lib/../../../escaped.txt", "pwned"))),
                "unsafe zip entry name");
        assertRefused(
                installer.installFromUrlSync(published(notesZip("1.0", "..\\..\\escaped.txt", "pwned"))),
                "unsafe zip entry name");
    }

    @Test
    void anArchiveEntryWithAnAbsoluteNameIsRefused() throws IOException {
        Path target = sandbox.resolve("absolute-target.txt");

        assertRefused(
                installer.installFromUrlSync(
                        published(notesZip("1.0", target.toString().replace('\\', '/'), "pwned"))),
                "unsafe zip entry name");
        assertRefused(
                installer.installFromUrlSync(published(notesZip("1.0", "C:\\Windows\\escaped.txt", "pwned"))),
                "unsafe zip entry name");
        assertFalse(Files.exists(target));
    }

    /** The folder a plugin is installed into is named by its manifest — which the archive's author writes. */
    @Test
    void aManifestIdThatIsAPathIsRefused() throws IOException {
        Path victim = Files.createDirectories(sandbox.resolve("victim"));
        Files.writeString(victim.resolve("keep.txt"), "keep");
        for (String id : List.of(
                "..",
                "../../victim",
                "../victim",
                "a/b",
                "a\\\\b",
                ".hidden",
                victim.toString().replace("\\", "\\\\"))) {
            byte[] zip = TestArchives.zip("plugin.json", "{\"id\":\"" + id + "\"}", "keep.txt", "replaced");
            PluginInstaller.Result result = installer.installFromUrlSync(published(zip));
            assertFalse(result.ok(), id + " must be refused");
            assertTrue(result.error().contains("invalid plugin id"), id + ": " + result.error());
            assertEquals(List.of(), installedFiles(), id);
        }
        assertEquals("keep", Files.readString(victim.resolve("keep.txt")));
        assertEquals(List.of("victim", "victim/keep.txt"), outsideThePluginsFolder());
    }

    /** {@code plugins/lsp}, {@code dap} and {@code typst} hold the downloaded language tools. */
    @Test
    void aDownloadedPluginCannotTakeTheNameOfABuiltInToolFolder() throws IOException {
        Path server =
                Files.createDirectories(pluginsDir.resolve("lsp/java/bin")).resolve("jdtls");
        Files.writeString(server, "launcher");

        for (String id : List.of("lsp", "dap", "typst", "LSP")) {
            PluginInstaller.Result result = installer.installFromUrlSync(
                    published(TestArchives.zip("plugin.json", "{\"id\":\"" + id + "\"}", "java/bin/jdtls", "pwned")));
            assertFalse(result.ok(), id);
            assertTrue(result.error().contains("reserved"), result.error());
        }
        assertEquals(List.of("lsp/java/bin/jdtls"), installedFiles());
        assertEquals("launcher", Files.readString(server));
    }

    @Test
    void anArchiveWithNoManifestAtItsRootIsRefused() throws IOException {
        assertRefused(
                installer.installFromUrlSync(published(TestArchives.zip("README.md", "hello"))),
                "no plugin.json in archive");
        assertRefused(
                installer.installFromUrlSync(published(TestArchives.zip(
                        "one/plugin.json", "{\"id\":\"one\"}", "two/plugin.json", "{\"id\":\"two\"}"))),
                "no plugin.json in archive");
    }

    @Test
    void anArchiveWithAnAbsurdNumberOfEntriesIsRefused() throws IOException {
        String[] entries = new String[2 * (Unzip.MAX_ENTRIES + 1)];
        entries[0] = "plugin.json";
        entries[1] = "{\"id\":\"notes\"}";
        for (int i = 1; i <= Unzip.MAX_ENTRIES; i++) {
            entries[2 * i] = "f/" + i;
            entries[2 * i + 1] = "";
        }
        PluginInstaller bigger = new PluginInstaller(manager, web.client(), 16L * 1024 * 1024);
        try {
            assertRefused(bigger.installFromUrlSync(published(TestArchives.zip(entries))), "too many entries");
        } finally {
            bigger.shutdown();
        }
    }

    /** An update that is refused must not cost the user the version that was working. */
    @Test
    void aRefusedUpdateLeavesTheInstalledVersionInPlace() throws IOException {
        assertTrue(installer
                .installFromUrlSync(published(notesZip("1.0", "main.js", "v1")))
                .ok());

        assertRefusedKeepingVersion(
                installer.installFromUrlSync(published(notesZip("2.0", "main.js", "v2", "../escaped.txt", "pwned"))),
                "1.0");
        web.serve(ZIP_URL, notesZip("2.0", "main.js", "v2"));
        assertRefusedKeepingVersion(installer.installFromUrlSync(entry(ZIP_URL, "0".repeat(64))), "1.0");
        web.status(ZIP_URL, 503);
        assertRefusedKeepingVersion(
                installer.installFromUrlSync(entry(ZIP_URL, PluginInstaller.sha256(notesZip("2.0")))), "1.0");
        assertEquals("v1", Files.readString(pluginsDir.resolve("notes/main.js")));
        assertEquals(List.of(), outsideThePluginsFolder());
    }

    private void assertRefusedKeepingVersion(PluginInstaller.Result result, String version) throws IOException {
        assertFalse(result.ok());
        manager.discover();
        assertEquals(1, manager.descriptors().size());
        assertEquals(version, manager.descriptors().get(0).manifest().version);
        try (Stream<Path> top = Files.list(pluginsDir)) {
            assertEquals(
                    List.of("notes"), top.map(p -> p.getFileName().toString()).toList(), "no staging folder");
        }
    }

    // --- from a file on disk --------------------------------------------------------------------------

    @Test
    void aZipPickedFromDiskIsInstalled() throws IOException {
        Path zip = sandbox.resolve("downloads-notes.zip");
        Files.write(zip, TestArchives.zip("notes/plugin.json", "{\"name\":\"Notes\"}", "notes/main.js", "code"));

        PluginInstaller.Result result = installer.installFromZipSync(zip);

        assertTrue(result.ok(), result.error());
        assertEquals("notes", result.id(), "with no id in the manifest, the archive's folder names the plugin");
        assertEquals(List.of("notes/main.js", "notes/plugin.json"), installedFiles());
        assertTrue(Files.exists(zip), "the user's file is left where it was");
        assertEquals(List.of(), web.requests());
    }

    @Test
    void aZipFromDiskIsHeldToTheSameRules() throws IOException {
        Path zip = sandbox.resolve("picked.zip");

        Files.write(zip, notesZip("1.0", "../escaped.txt", "pwned"));
        PluginInstaller.Result climbing = installer.installFromZipSync(zip);
        assertFalse(climbing.ok());
        assertTrue(climbing.error().contains("unsafe zip entry name"), climbing.error());

        Files.write(zip, new byte[(int) ARCHIVE_CAP + 1]);
        PluginInstaller.Result oversize = installer.installFromZipSync(zip);
        assertFalse(oversize.ok());
        assertEquals("archive too large", oversize.error());

        Files.writeString(zip, "not a zip at all");
        PluginInstaller.Result notZip = installer.installFromZipSync(zip);
        assertFalse(notZip.ok());
        assertEquals("no plugin.json in archive", notZip.error());

        PluginInstaller.Result missing = installer.installFromZipSync(sandbox.resolve("gone.zip"));
        assertFalse(missing.ok());
        assertTrue(missing.error().startsWith("read failed"), missing.error());

        assertEquals(List.of(), installedFiles());
        assertEquals(List.of("picked.zip"), outsideThePluginsFolder());
    }
}
