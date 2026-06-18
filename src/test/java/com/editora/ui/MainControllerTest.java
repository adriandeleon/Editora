package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Unit tests for pure helpers in {@link MainController} (no toolkit needed). */
class MainControllerTest {

    @Test
    void repoNameFromHttpsUrl() {
        assertEquals("repo", MainController.repoNameFromUrl("https://github.com/user/repo.git"));
        assertEquals("repo", MainController.repoNameFromUrl("https://github.com/user/repo"));
    }

    @Test
    void repoNameFromScpStyleUrl() {
        assertEquals("repo", MainController.repoNameFromUrl("git@github.com:user/repo.git"));
        assertEquals("Editora", MainController.repoNameFromUrl("git@github.com:adriandeleon/Editora.git"));
    }

    @Test
    void repoNameStripsTrailingSlashes() {
        assertEquals("repo", MainController.repoNameFromUrl("https://github.com/user/repo/"));
        assertEquals("repo", MainController.repoNameFromUrl("https://github.com/user/repo.git/"));
    }

    @Test
    void repoNameFromLocalPath() {
        assertEquals("myrepo", MainController.repoNameFromUrl("/home/me/src/myrepo.git"));
        assertEquals("myrepo", MainController.repoNameFromUrl("/home/me/src/myrepo"));
    }

    @Test
    void blankOrNullUrl() {
        assertEquals("", MainController.repoNameFromUrl(""));
        assertEquals("", MainController.repoNameFromUrl(null));
        assertEquals("", MainController.repoNameFromUrl("   "));
    }

    // (C-a smart-line-start column logic moved to editor/TextNavTest along with the rest of the
    //  Emacs caret-navigation math.)

    // --- canonicalPath: a symlinked path and the real path must compare equal ---

    @Test
    void canonicalPathResolvesSymlinkSoServerDiagnosticsMatchTheBuffer(@TempDir Path tmp) throws IOException {
        // A language server reports diagnostics under the file's real path (e.g. /private/tmp/… for a
        // /tmp/… symlink on macOS); a buffer keeps the path as opened. canonicalPath() must map both to
        // the same Path so tabForPath finds the tab — otherwise diagnostics are silently dropped.
        Path realDir = Files.createDirectory(tmp.resolve("real"));
        Path realFile = Files.writeString(realDir.resolve("A.java"), "class A {}");
        Path link;
        try {
            link = Files.createSymbolicLink(tmp.resolve("link"), realDir);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symlinks not supported on this platform/filesystem");
            return;
        }
        Path viaLink = link.resolve("A.java"); // same file reached through the symlinked directory
        assertEquals(MainController.canonicalPath(realFile), MainController.canonicalPath(viaLink));
    }

    @Test
    void canonicalPathFallsBackForMissingFile(@TempDir Path tmp) {
        // A not-yet-on-disk path (e.g. an unsaved buffer) can't be realpath'd; fall back to normalize().
        Path missing = tmp.resolve("nope/../ghost.java");
        assertEquals(missing.toAbsolutePath().normalize(), MainController.canonicalPath(missing));
    }

    @Test
    void compactSourceNoiseMatchesImplicitClassComplaintsOnly() {
        // JDK 23+ JDT wording, the preview-gating message, and the JDK 21/22 preview-era name…
        assertTrue(MainController.isCompactSourceNoise("Implicitly declared class must have a candidate main method"));
        assertTrue(MainController.isCompactSourceNoise(
                "Implicitly Declared Classes and Instance Main Methods is a preview feature and"
                        + " disabled by default. Use --enable-preview to enable"));
        assertTrue(
                MainController.isCompactSourceNoise("Unnamed Classes and Instance Main Methods is a preview feature"));
        // …but real errors in the file still surface.
        assertFalse(MainController.isCompactSourceNoise("The method foo() is undefined"));
        assertFalse(MainController.isCompactSourceNoise("Syntax error on token \";\""));
        assertFalse(MainController.isCompactSourceNoise(null));
    }
}
