package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Path identity + store-keying decisions (the bug-prone canonical-vs-normalized matching). */
class PathKeysTest {

    @Test
    void canonicalResolvesSymlinkSoServerDiagnosticsMatchTheBuffer(@TempDir Path tmp) throws IOException {
        // A language server reports diagnostics under the file's real path (e.g. /private/tmp/… for a
        // /tmp/… symlink on macOS); a buffer keeps the path as opened. canonical() must map both to the
        // same Path — otherwise tabForPath misses the tab and diagnostics are silently dropped.
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
        assertEquals(PathKeys.canonical(realFile), PathKeys.canonical(viaLink));
        assertEquals(PathKeys.key(realFile), PathKeys.key(viaLink)); // string key agrees too
    }

    @Test
    void canonicalFallsBackToNormalizeForMissingFile(@TempDir Path tmp) {
        // A not-yet-on-disk path (e.g. an unsaved buffer) can't be realpath'd; fall back to normalize().
        Path missing = tmp.resolve("nope/../ghost.java");
        assertEquals(missing.toAbsolutePath().normalize(), PathKeys.canonical(missing));
    }

    @Test
    void localKeyIsTheCanonicalString(@TempDir Path tmp) throws IOException {
        Path f = Files.writeString(tmp.resolve("x.txt"), "hi");
        assertEquals(PathKeys.canonical(f).toString(), PathKeys.key(f)); // local → canonical string
    }

    @Test
    void normalizedKeyCollapsesDotSegmentsWithoutResolvingSymlinks() {
        Path p = Path.of("/a/b/../c/file.txt");
        assertEquals(p.toAbsolutePath().normalize().toString(), PathKeys.normalizedKey(p));
    }

    @Test
    void canonicalKeyIsEmptyForNullPath() {
        assertEquals("", PathKeys.canonicalKey(null));
    }

    @Test
    void sameNormalizedTreatsDotSegmentsAsEqualAndGuardsNulls() {
        assertTrue(PathKeys.sameNormalized(Path.of("/a/b/c.txt"), Path.of("/a/x/../b/c.txt")));
        assertFalse(PathKeys.sameNormalized(Path.of("/a/b.txt"), Path.of("/a/c.txt")));
        assertFalse(PathKeys.sameNormalized(null, Path.of("/a")));
        assertFalse(PathKeys.sameNormalized(Path.of("/a"), null));
    }

    @Test
    void findKeyByIdentityMatchesByCanonicalPathAcrossBucketKeys(@TempDir Path tmp) throws IOException {
        Path f = Files.writeString(tmp.resolve("note.md"), "body");
        FileIdentity id = FileIdentity.of(f);
        PersonalNote note = PersonalNote.create(id, NoteScope.LINE, null, "n", List.of());
        Map<String, List<PersonalNote>> map = Map.of(
                "other-key", List.of(),
                "the-key", List.of(note));
        assertEquals("the-key", PathKeys.findKeyByIdentity(map, id));
        assertNull(PathKeys.findKeyByIdentity(map, null));
        // A different file (different identity) → no match.
        Path g = Files.writeString(tmp.resolve("z.md"), "elsewhere");
        assertNull(PathKeys.findKeyByIdentity(map, FileIdentity.of(g)));
    }

    @Test
    void resolveUserInputHandlesAbsoluteRelativeHomeAndBlank() {
        Path base = Path.of("/home/me/project");
        String home = "/home/me";
        // Absolute stays put (normalized).
        assertEquals(Path.of("/etc/hosts"), PathKeys.resolveUserInput("/etc/hosts", base, home));
        // Relative resolves against the base dir.
        assertEquals(Path.of("/home/me/project/notes.md"), PathKeys.resolveUserInput("notes.md", base, home));
        assertEquals(Path.of("/home/me/project/sub/a.txt"), PathKeys.resolveUserInput("sub/a.txt", base, home));
        // `..` segments normalize.
        assertEquals(Path.of("/home/me/other.txt"), PathKeys.resolveUserInput("../other.txt", base, home));
        // A leading ~ expands to the home dir.
        assertEquals(Path.of("/home/me/x.md"), PathKeys.resolveUserInput("~/x.md", base, home));
        assertEquals(Path.of("/home/me"), PathKeys.resolveUserInput("~", base, home));
        // Whitespace is trimmed; blank/null return null.
        assertEquals(Path.of("/home/me/project/t.md"), PathKeys.resolveUserInput("  t.md  ", base, home));
        assertNull(PathKeys.resolveUserInput("   ", base, home));
        assertNull(PathKeys.resolveUserInput(null, base, home));
    }
}
