package com.editora.web;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shared site-root rule and the unsafe-folder rules ({@link SiteRoots}): HTML review E2–E4, 2: A101, E108. */
class SiteRootsTest {

    /** A missing catch-all folder under a home reached through a link (macOS /var → /private/var). */
    @Test
    @DisabledOnOs(OS.WINDOWS) // symlinks need a right
    void aMissingCatchAllFolderIsUnsafeWhenHomeIsReachedThroughALink(@TempDir Path tmp) throws Exception {
        Path realHome = Files.createDirectories(tmp.resolve("private/home/alice"));
        Path link = Files.createSymbolicLink(tmp.resolve("home"), realHome.getParent());
        Path home = link.resolve("alice");
        assertTrue(SiteRoots.isUnsafeDocRoot(home.resolve("Desktop"), home), "Desktop, not created yet");
        assertTrue(SiteRoots.isUnsafeDocRoot(realHome.resolve("Documents"), home), "through the real path");
        assertFalse(SiteRoots.isUnsafeDocRoot(home.resolve("projects/site"), home));
    }

    @Test
    void homeItsAncestorsCatchAllFoldersTempAndRootsAreUnsafe(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home/alice"));
        Path site = Files.createDirectories(home.resolve("projects/site"));
        Path downloads = Files.createDirectories(home.resolve("Downloads"));
        Path descargas = Files.createDirectories(home.resolve("Descargas"));
        Files.createDirectories(home.resolve(".config"));
        Files.writeString(
                home.resolve(".config/user-dirs.dirs"),
                "# xdg-user-dirs\nXDG_DOWNLOAD_DIR=\"$HOME/Descargas\"\nXDG_MUSIC_DIR=\"$HOME/Music\"\n");
        assertTrue(SiteRoots.isUnsafeDocRoot(home, home), "the home directory itself");
        assertTrue(SiteRoots.isUnsafeDocRoot(home.getParent(), home), "an ancestor of home (/home)");
        assertTrue(SiteRoots.isUnsafeDocRoot(tmp.getRoot(), home), "a filesystem root");
        assertTrue(SiteRoots.isUnsafeDocRoot(tmp.getRoot(), null), "a root is refused even with no home");
        assertTrue(SiteRoots.isUnsafeDocRoot(null, home));
        assertTrue(SiteRoots.isUnsafeDocRoot(downloads, home), "a page sitting directly in Downloads");
        assertTrue(SiteRoots.isUnsafeDocRoot(home.resolve("Desktop"), home));
        assertTrue(SiteRoots.isUnsafeDocRoot(home.resolve("Documents"), home));
        assertTrue(SiteRoots.isUnsafeDocRoot(descargas, home), "the localized XDG download folder");
        assertFalse(SiteRoots.isUnsafeDocRoot(home.resolve("Music"), home), "not a catch-all key");
        assertTrue(SiteRoots.isUnsafeDocRoot(Path.of(System.getProperty("java.io.tmpdir")), home), "the temp folder");
        assertFalse(SiteRoots.isUnsafeDocRoot(site, home), "a project folder under home is fine");
        assertFalse(SiteRoots.isUnsafeDocRoot(downloads.resolve("site"), home), "a site inside Downloads");
        assertFalse(SiteRoots.isUnsafeDocRoot(tmp.resolve("elsewhere"), home), "an unrelated folder");
    }

    /** E108: the shared temp folders, wherever java.io.tmpdir points, and OneDrive's Desktop and Documents. */
    @Test
    void sharedTempFoldersAndOneDriveFoldersAreUnsafe(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home/bob"));
        Path business = Files.createDirectories(home.resolve("OneDrive - Contoso"));
        Map<String, String> env = Map.of("OneDriveCommercial", business.toString());
        assertTrue(SiteRoots.isUnsafeDocRoot(home.resolve("OneDrive/Desktop"), home, env::get));
        assertTrue(SiteRoots.isUnsafeDocRoot(home.resolve("OneDrive/Documents"), home, env::get));
        assertTrue(SiteRoots.isUnsafeDocRoot(business.resolve("Documents"), home, env::get), "%OneDriveCommercial%");
        assertFalse(SiteRoots.isUnsafeDocRoot(business.resolve("Documents/site"), home, env::get), "a site in it");
        assertFalse(SiteRoots.isUnsafeDocRoot(home.resolve("OneDrive/web"), home, env::get));
        if (!System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win")) {
            for (String t : new String[] {"/tmp", "/var/tmp", "/dev/shm", "/var"}) {
                assertTrue(SiteRoots.isUnsafeDocRoot(Path.of(t), home), t);
            }
            assertFalse(SiteRoots.isUnsafeDocRoot(Path.of("/var/www/site"), home), "a folder beside them is fine");
        }
    }

    @Test
    void aRemoteFileSystemIsNeverASafeRoot(@TempDir Path tmp) throws Exception {
        Path zip = tmp.resolve("remote.zip");
        try (FileSystem zfs = FileSystems.newFileSystem(zip, Map.of("create", "true"))) {
            Path remote = Files.createDirectories(zfs.getPath("/home/bob/site"));
            // A remote home cannot be compared with ours, so even a deep folder there is refused.
            assertTrue(SiteRoots.isUnsafeDocRoot(remote, Path.of(System.getProperty("user.home"))));
            assertTrue(SiteRoots.isUnsafeDocRoot(zfs.getPath("/home/bob"), null));
        }
    }

    @Test
    void siteRootIsTheProjectElseTheContiguousIndexElseTheFolder(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home/alice"));
        Path project = Files.createDirectories(home.resolve("work/site"));
        Path page = Files.createDirectories(project.resolve("pages")).resolve("about.html");
        assertEquals(project, SiteRoots.siteRoot(page, project, home), "inside the project: its root");

        Path loose = Files.createDirectories(home.resolve("loose/blog/2026"));
        Files.writeString(home.resolve("loose/index.html"), "<html/>");
        assertEquals(
                home.resolve("loose"),
                SiteRoots.siteRoot(loose.resolve("post.html"), null, home),
                "a loose file: the nearest folder up holding an index.html");
        Files.writeString(home.resolve("index.html"), "<html/>"); // in the home: never a site root
        Path lone = Files.createDirectories(home.resolve("notes/html"));
        assertEquals(
                lone,
                SiteRoots.siteRoot(lone.resolve("x.html"), null, home),
                "the search stops below the home; else the file's own folder");
        assertEquals(
                lone,
                SiteRoots.siteRoot(lone.resolve("x.html"), home, home),
                "a project rooted at the home is not served whole");
        assertEquals(home, SiteRoots.siteRoot(home.resolve("page.html"), null, home), "unsafe: caller refuses");
    }

    /** A101: the highest contiguous index.html, never through a folder without one, never into Downloads. */
    @Test
    void theClimbFollowsAContiguousChainOfIndexPages(@TempDir Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home/alice"));
        Path site = Files.createDirectories(home.resolve("sites/site"));
        Path blog = Files.createDirectories(site.resolve("blog"));
        Files.writeString(site.resolve("index.html"), "");
        Files.writeString(blog.resolve("index.html"), "");
        Path post = Files.writeString(blog.resolve("post.html"), "");
        assertEquals(site, SiteRoots.looseRoot(post, home), "blog/ and site/ both hold one: the site");

        Path gap = Files.createDirectories(home.resolve("src/a/b/c"));
        Files.writeString(home.resolve("src/a/index.html"), ""); // a stray saved page two folders up
        Files.writeString(gap.resolve("index.html"), "");
        assertEquals(gap, SiteRoots.looseRoot(gap.resolve("page.html"), home), "b/ has none: the chain stops");

        Path downloads = Files.createDirectories(home.resolve("Downloads"));
        Files.writeString(downloads.resolve("index.html"), ""); // a saved page in Downloads
        Path dsite = Files.createDirectories(downloads.resolve("site/blog"));
        Files.writeString(downloads.resolve("site/index.html"), "");
        assertEquals(
                downloads.resolve("site"),
                SiteRoots.looseRoot(dsite.resolve("post.html"), home),
                "never into the catch-all folder");
        assertEquals(downloads, SiteRoots.looseRoot(downloads.resolve("saved.html"), home), "the page's folder");
    }

    @Test
    void xdgUserDirReadsOnlyTheCatchAllKeys() {
        Path home = Path.of("/home/u");
        assertEquals(home.resolve("Descargas"), SiteRoots.xdgUserDir("XDG_DOWNLOAD_DIR=\"$HOME/Descargas\"", home));
        assertEquals(Path.of("/data/docs"), SiteRoots.xdgUserDir("XDG_DOCUMENTS_DIR=/data/docs", home));
        assertEquals(null, SiteRoots.xdgUserDir("XDG_MUSIC_DIR=\"$HOME/Music\"", home));
        assertEquals(null, SiteRoots.xdgUserDir("no equals sign", home));
        assertEquals(null, SiteRoots.xdgUserDir("XDG_DESKTOP_DIR=relative", home));
    }
}
