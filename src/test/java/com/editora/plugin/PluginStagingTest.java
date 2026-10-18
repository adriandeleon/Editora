package com.editora.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;

import com.editora.io.TestArchives;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two-step install (stage → consent → commit), the registry archive check, and the folder clean-up rules. */
class PluginStagingTest {

    @TempDir
    Path pluginsDir;

    private PluginInstaller installer() {
        return new PluginInstaller(new PluginManager(pluginsDir, id -> false));
    }

    private static byte[] zip(String manifest) {
        return TestArchives.zip("plugin.json", manifest, "snippets/markdown.json", "{}");
    }

    private List<String> entries() throws Exception {
        try (var s = Files.list(pluginsDir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void aStagedInstallTouchesNothingUntilCommittedAndADiscardLeavesNoTrace() throws Exception {
        PluginInstaller installer = installer();
        PluginInstaller.Staging s = installer.stageBytes(zip("{\"id\":\"notes\",\"version\":\"1\"}"), null);
        assertTrue(s.ok());
        assertNull(s.staged().previous());
        assertFalse(Files.exists(pluginsDir.resolve("notes")), "nothing in place before the answer");
        assertEquals(1, entries().size(), "only the staging folder");
        assertTrue(entries().get(0).startsWith(PluginInstaller.STAGING_PREFIX));

        PluginInstaller.discard(s.staged());
        assertEquals(List.of(), entries());

        PluginInstaller.Staging again = installer.stageBytes(zip("{\"id\":\"notes\",\"version\":\"1\"}"), null);
        PluginInstaller.Commit c = installer.commit(again.staged(), false);
        assertTrue(c.ok() && !c.pending());
        assertEquals(List.of("notes"), entries(), "in place, and the staging folder is gone");
    }

    /** E6: a registry entry that points at another plugin's archive installs nothing. */
    @Test
    void aRegistryArchiveMustBeThePluginAndVersionItsEntryNames() throws Exception {
        PluginInstaller installer = installer();
        RegistryEntry entry = new RegistryEntry();
        entry.id = "harmless";
        entry.version = "1.0";

        PluginInstaller.Staging other = installer.stageBytes(zip("{\"id\":\"victim\",\"version\":\"1.0\"}"), entry);
        assertFalse(other.ok());
        assertEquals(PluginProblem.Kind.ID_MISMATCH, other.problem().kind());
        assertEquals(List.of("harmless", "victim"), other.problem().args());

        PluginInstaller.Staging older = installer.stageBytes(zip("{\"id\":\"harmless\",\"version\":\"0.1\"}"), entry);
        assertEquals(PluginProblem.Kind.VERSION_MISMATCH, older.problem().kind());
        assertEquals(List.of(), entries(), "nothing staged is left behind");

        assertTrue(installer
                .stageBytes(zip("{\"id\":\"harmless\",\"version\":\"1.0\"}"), entry)
                .ok());
    }

    /** B8: an install over a plugin linked in from a source folder is refused — it would move that folder's data. */
    @Test
    void aSymlinkedPluginIsNeverReplaced() throws Exception {
        Path source = Files.createDirectories(pluginsDir.getParent().resolve(pluginsDir.getFileName() + "-src"));
        Files.writeString(source.resolve("plugin.json"), "{\"id\":\"dev\"}");
        Files.createDirectories(source.resolve("data"));
        Files.writeString(source.resolve("data/notes.txt"), "mine");
        try {
            Files.createSymbolicLink(pluginsDir.resolve("dev"), source);
        } catch (UnsupportedOperationException | java.io.IOException noLinks) {
            Assumptions.abort("symbolic links are not available here");
        }
        PluginInstaller.Staging s = installer().stageBytes(zip("{\"id\":\"dev\",\"version\":\"2\"}"), null);
        assertEquals(PluginProblem.Kind.SYMLINKED, s.problem().kind());
        assertTrue(Files.isSymbolicLink(pluginsDir.resolve("dev")));
        assertEquals("mine", Files.readString(source.resolve("data/notes.txt")));
    }

    /** D3: a picked file over the cap is refused by its size, without being read into memory. */
    @Test
    void aPickedFileOverTheCapIsRefusedBeforeItIsRead() throws Exception {
        Path big = pluginsDir.getParent().resolve("big.zip");
        try (var f = new java.io.RandomAccessFile(big.toFile(), "rw")) {
            f.setLength(4096); // sparse: no memory, no disk
        }
        PluginInstaller small = new PluginInstaller(
                new PluginManager(pluginsDir, id -> false),
                com.editora.io.LazyHttpClient.following(java.time.Duration.ofSeconds(1)),
                1024);
        PluginInstaller.Staging s = small.stageFromZipSync(big);
        assertEquals(PluginProblem.Kind.TOO_LARGE, s.problem().kind());
        small.shutdown();
    }

    /** E10: staging folders a crash left behind are swept at the next start (fresh ones are not). */
    @Test
    void staleStagingFoldersAreSweptAtLaunch() throws Exception {
        Path stale = Files.createDirectories(pluginsDir.resolve(".installing-123"));
        Files.writeString(stale.resolve("x.jar"), "x");
        Files.setLastModifiedTime(
                stale, FileTime.from(Instant.now().minus(PluginInstaller.STALE_STAGING.multipliedBy(2))));
        Path fresh = Files.createDirectories(pluginsDir.resolve(".installing-456"));

        new PluginManager(pluginsDir, id -> false).discover();

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(fresh), "may be another editor's install in progress");
    }

    /**
     * E3: deleting a plugin whose files cannot all go (a jar Windows keeps locked) leaves its plugin.json, so it
     * is still listed and can be removed again — it used to lose its manifest and data and block a reinstall.
     */
    @Test
    void aFolderThatCannotBeFullyDeletedKeepsItsManifest() throws Exception {
        Assumptions.assumeTrue(java.nio.file.FileSystems.getDefault()
                .supportedFileAttributeViews()
                .contains("posix"));
        Path dir = Files.createDirectories(pluginsDir.resolve("stuck"));
        Files.writeString(dir.resolve("plugin.json"), "{\"id\":\"stuck\"}");
        Path locked = Files.createDirectories(dir.resolve("a-locked"));
        Files.writeString(locked.resolve("a.jar"), "jar");
        Files.writeString(dir.resolve("z.txt"), "z");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertFalse(PluginInstaller.deletePluginFolder(dir));
            assertTrue(Files.isRegularFile(dir.resolve("plugin.json")), "still a listed, removable plugin");
            assertFalse(Files.exists(dir.resolve("z.txt")), "what could go went");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        assertTrue(PluginInstaller.deletePluginFolder(dir));
        assertFalse(Files.exists(dir));
    }

    /** E9: pre-release numbers compare as numbers, build metadata is ignored, a leading v is dropped. */
    @Test
    void versionsCompareTheWaySemverSays() {
        assertTrue(PluginInstaller.compareVersions("1.0.0-rc10", "1.0.0-rc2") > 0);
        assertTrue(PluginInstaller.compareVersions("1.0.0-rc.10", "1.0.0-rc.2") > 0);
        assertEquals(0, PluginInstaller.compareVersions("1.0.0+build.5", "1.0.0"));
        assertTrue(PluginInstaller.compareVersions("v2.0", "10.0") < 0);
        assertTrue(PluginInstaller.compareVersions("1.0.0", "1.0.0-rc1") > 0);
        assertTrue(PluginInstaller.compareVersions("1.10", "1.9") > 0);
        assertTrue(PluginInstaller.compareVersions("0.22.1-SNAPSHOT", "0.22.0") > 0);
        assertTrue(PluginInstaller.compareVersions("1.0.0-alpha", "1.0.0-alpha.1") < 0);
        assertEquals(0, PluginInstaller.compareVersions(null, ""));
    }

    @Test
    void aProblemSaysWhatWentWrongInEnglishForTheLog() {
        assertEquals(
                "checksum mismatch",
                PluginProblem.of(PluginProblem.Kind.CHECKSUM_MISMATCH).english());
        assertEquals(
                "HTTP 404",
                PluginProblem.of(PluginProblem.Kind.HTTP_STATUS, "404").english());
        assertEquals(
                PluginProblem.Kind.UNREACHABLE,
                PluginProblem.network("https://plugins.example.org/x", new java.net.ConnectException("refused"))
                        .kind());
        assertEquals(
                List.of("plugins.example.org"),
                PluginProblem.network(
                                "https://plugins.example.org/x",
                                new java.io.IOException(new java.net.UnknownHostException("plugins.example.org")))
                        .args());
        assertEquals(
                PluginProblem.Kind.TIMED_OUT,
                PluginProblem.network("https://h/x", new java.net.http.HttpTimeoutException("slow"))
                        .kind());
        assertEquals(
                PluginProblem.Kind.FAILED,
                PluginProblem.network("https://h/x", new IllegalStateException())
                        .kind());
    }

    /** D4: a body that drips in for longer than its deadline is abandoned. */
    @Test
    void aDrippingBodyIsAbandonedAtItsDeadline() {
        java.io.InputStream drip = new java.io.InputStream() {
            @Override
            public int read() {
                return 'x';
            }

            @Override
            public int read(byte[] b, int off, int len) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                b[off] = 'x';
                return 1;
            }
        };
        java.io.IOException e = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> PluginRegistry.readCapped(drip, 1_000_000, java.time.Duration.ofMillis(60)));
        assertTrue(e.getMessage().startsWith(PluginProblem.DEADLINE), e.getMessage());
        assertEquals(
                PluginProblem.Kind.TIMED_OUT,
                PluginProblem.network("https://h/x", e).kind());
    }
}
