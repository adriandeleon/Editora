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

    /**
     * A folder typed for a remote host (the clone form, git init) is a path on that host's file system: an
     * absolute one names the host's folder, not this machine's, and {@code ~} is the home there. A zip file
     * system stands in for the host's.
     */
    @Test
    void aPathTypedForAnotherFileSystemIsOnThatFileSystem(@TempDir Path tmp) throws IOException {
        try (var zip = java.nio.file.FileSystems.newFileSystem(tmp.resolve("host.zip"), Map.of("create", "true"))) {
            Path base = zip.getPath("/srv/projects");

            Path absolute = PathKeys.resolveUserInputOn("/opt/app", base, "/home/ana");
            Path relative = PathKeys.resolveUserInputOn("lib/../tool", base, "/home/ana");
            Path home = PathKeys.resolveUserInputOn("~/src/x", base, "/home/ana");

            assertEquals(zip, absolute.getFileSystem());
            assertEquals("/opt/app", absolute.toString());
            assertEquals("/srv/projects/tool", relative.toString());
            assertEquals("/home/ana/src/x", home.toString());
            assertNull(PathKeys.resolveUserInputOn("~/x", base, null), "no home known: no guess");
            assertNull(PathKeys.resolveUserInputOn("  ", base, "/home/ana"));
        }
        // On this machine's file system it is resolveUserInput, unchanged.
        assertEquals(
                PathKeys.resolveUserInput("~/x", tmp, "/home/me"), PathKeys.resolveUserInputOn("~/x", tmp, "/home/me"));
    }

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
    void aMissingFileIsKeyedUnderTheRealPathOfTheFolderItWouldBeIn(@TempDir Path tmp) throws IOException {
        // A path that is not on disk (a Save As target before its first write) can't be realpath'd. Its folder
        // can: the temp dir itself is reached through a link on macOS (/var -> /private/var).
        Path missing = tmp.resolve("nope/../ghost.java");
        assertEquals(tmp.toRealPath().resolve("ghost.java"), PathKeys.canonical(missing));
        assertEquals(
                tmp.toRealPath().resolve("no/such/folder/ghost.java"),
                PathKeys.canonical(tmp.resolve("no/such/folder/ghost.java")),
                "the nearest folder that exists is the one resolved");
    }

    @Test
    void aFileHasTheSameKeyBeforeItIsCreatedAndAfter(@TempDir Path tmp) throws IOException {
        // Save As keys the buffer's notes by the new path before the file is written, and looks them up
        // afterwards. Through a linked folder the two used to be different spellings.
        Path realDir = Files.createDirectory(tmp.resolve("real"));
        Path link;
        try {
            link = Files.createSymbolicLink(tmp.resolve("link"), realDir);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symlinks not supported on this platform/filesystem");
            return;
        }
        Path target = link.resolve("copy.txt");
        String before = PathKeys.canonicalKey(target);
        Files.writeString(target, "x");
        assertEquals(before, PathKeys.canonicalKey(target));
        assertEquals(realDir.toRealPath().resolve("copy.txt").toString(), before);

        Files.delete(target);
        assertEquals(before, PathKeys.canonicalKey(target), "and the key it had is still its key once deleted");
    }

    @Test
    void anotherSpellingOfAnExistingFileIsThatFile(@TempDir Path tmp) throws IOException {
        // "missing/../a.txt" is not a path the OS resolves, but what it names is on disk.
        Path file = Files.writeString(tmp.resolve("a.txt"), "x");
        assertEquals(PathKeys.canonical(file), PathKeys.canonical(tmp.resolve("missing/../a.txt")));
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

    /** Every real file "still exists" — the production predicate. */
    private static final java.util.function.Predicate<String> ON_DISK = p -> !p.isBlank() && Files.exists(Path.of(p));

    @Test
    void findKeyByIdentityMatchesByCanonicalPathAcrossBucketKeys(@TempDir Path tmp) throws IOException {
        Path f = Files.writeString(tmp.resolve("note.md"), "body");
        FileIdentity id = FileIdentity.of(f);
        PersonalNote note = PersonalNote.create(id, NoteScope.LINE, null, "n", List.of());
        Map<String, List<PersonalNote>> map = Map.of(
                "other-key", List.of(),
                "the-key", List.of(note));
        assertEquals("the-key", PathKeys.findKeyByIdentity(map, id, ON_DISK));
        assertNull(PathKeys.findKeyByIdentity(map, null, ON_DISK));
        // A different file (different identity) → no match.
        Path g = Files.writeString(tmp.resolve("z.md"), "elsewhere");
        assertNull(PathKeys.findKeyByIdentity(map, FileIdentity.of(g), ON_DISK));
    }

    /**
     * Two different files can hold identical bytes — a {@code cp config.yaml config.backup.yaml}, a
     * duplicated LICENSE, the boilerplate {@code index.ts} of every package in a monorepo. The caller
     * re-keys onto whatever this returns, so answering "same file" there <b>moved the notes off the file
     * they were written on and deleted them from it</b>. Merely opening the copy was enough.
     */
    @Test
    void identicalContentIsNotIdentityWhileTheOriginalStillExists(@TempDir Path tmp) throws IOException {
        Path original = Files.writeString(tmp.resolve("web-index.ts"), "export {};\n");
        Path copy = Files.writeString(tmp.resolve("api-index.ts"), "export {};\n");
        FileIdentity idOriginal = FileIdentity.of(original);
        assertEquals(
                FileIdentity.Match.CONTENT_HASH,
                FileIdentity.match(idOriginal, FileIdentity.of(copy)),
                "same bytes, different files");
        Map<String, List<PersonalNote>> map = Map.of(
                original.toString(), List.of(PersonalNote.create(idOriginal, NoteScope.LINE, null, "n", List.of())));
        assertNull(
                PathKeys.findKeyByIdentity(map, FileIdentity.of(copy), ON_DISK),
                "the original is still on disk — this is a copy, not a rename");
    }

    /** The case the re-key exists for: renamed outside Editora, so the old path is gone. */
    @Test
    void aContentMatchIsTrustedOnceTheOriginalIsGone(@TempDir Path tmp) throws IOException {
        Path original = Files.writeString(tmp.resolve("old-name.ts"), "export const x = 1;\n");
        FileIdentity idOriginal = FileIdentity.of(original);
        Map<String, List<PersonalNote>> map = Map.of(
                original.toString(), List.of(PersonalNote.create(idOriginal, NoteScope.LINE, null, "n", List.of())));
        Path renamed = tmp.resolve("new-name.ts");
        Files.move(original, renamed);
        assertEquals(
                original.toString(),
                PathKeys.findKeyByIdentity(map, FileIdentity.of(renamed), ON_DISK),
                "the old path is gone and the bytes match — a rename; the notes must follow");
    }

    /**
     * Restoring notes asks this on every file open whenever any note exists, and hashing reads the whole file
     * (up to 5 MB) on the FX thread. The hash is needed only to recognise a rename: a stored note whose file is
     * gone and was the same size.
     */
    @Test
    void theContentHashIsComputedOnlyForAGoneFileOfTheSameSize(@TempDir Path tmp) throws IOException {
        Path annotated = Files.writeString(tmp.resolve("annotated.ts"), "export const a = 1;\n");
        Path opened = Files.writeString(tmp.resolve("opened.ts"), "export const b = 22;\n");
        Map<String, List<PersonalNote>> map = new java.util.LinkedHashMap<>();
        map.put(
                annotated.toString(),
                List.of(PersonalNote.create(FileIdentity.of(annotated), NoteScope.LINE, null, "n", List.of())));
        java.util.concurrent.atomic.AtomicInteger hashed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<String> hash = () -> {
            hashed.incrementAndGet();
            return FileIdentity.hashOf(opened);
        };

        assertNull(PathKeys.findKeyByIdentity(map, FileIdentity.unhashed(opened), hash, ON_DISK));
        assertEquals(0, hashed.get(), "the annotated file is still on disk: no rename to look for");

        Path renamed = tmp.resolve("renamed.ts");
        Files.move(annotated, renamed);
        assertNull(PathKeys.findKeyByIdentity(map, FileIdentity.unhashed(opened), hash, ON_DISK));
        assertEquals(0, hashed.get(), "gone, but a different size: these cannot be the same bytes");

        java.util.function.Supplier<String> renamedHash = () -> {
            hashed.incrementAndGet();
            return FileIdentity.hashOf(renamed);
        };
        assertEquals(
                annotated.toString(),
                PathKeys.findKeyByIdentity(map, FileIdentity.unhashed(renamed), renamedHash, ON_DISK),
                "gone and the same size: hashed, matched, a rename");
        assertEquals(1, hashed.get());
        assertEquals(FileIdentity.of(renamed).hash(), FileIdentity.hashOf(renamed));
        assertEquals(FileIdentity.of(renamed), FileIdentity.unhashed(renamed).withHash(FileIdentity.hashOf(renamed)));
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

    // --- Canonical-path cache (#680) -----------------------------------------------------------

    @Test
    void canonicalCachesSuccessfulResolutions(@TempDir Path dir) throws Exception {
        PathKeys.invalidateCanonicalCache();
        Path real = Files.writeString(dir.resolve("real.txt"), "x");
        Path first = PathKeys.canonical(real);
        assertEquals(first, PathKeys.canonical(real), "second lookup returns the cached resolution");
    }

    /**
     * The not-exists fallback must NEVER be cached: what a path names can change when it is created — the
     * new file may itself be a link. A cached fallback would re-introduce the #470 identity mismatch that
     * silently dropped diagnostics.
     */
    @Test
    void theNotExistsFallbackIsNotCached(@TempDir Path dir) throws Exception {
        PathKeys.invalidateCanonicalCache();
        Path realDir = Files.createDirectory(dir.resolve("realdir"));
        Path realFile = Files.writeString(realDir.resolve("file.txt"), "x");
        Path link = dir.resolve("link.txt");
        // Not created yet -> the fallback: the name inside its folder's real path.
        Path before = PathKeys.canonical(link);
        assertEquals(dir.toRealPath().resolve("link.txt"), before);
        try {
            Files.createSymbolicLink(link, realFile);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return; // platform without symlink support — nothing to prove here
        }
        // Now it exists, as a link — canonical must resolve it, proving no stale cache entry.
        assertEquals(realFile.toRealPath(), PathKeys.canonical(link), "the created link resolves to its target");
    }

    @Test
    void invalidateClearsTheCache(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("f.txt"), "x");
        Path cached = PathKeys.canonical(f);
        PathKeys.invalidateCanonicalCache();
        assertEquals(cached, PathKeys.canonical(f), "re-resolves to the same identity after a clear");
    }

    /** A11 (engine half): one file, one history key, whichever way it was reached. */
    @Test
    void theHistoryKeyOfAFileIsTheSameThroughALinkAndDirectly(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        Path real = java.nio.file.Files.createDirectories(dir.resolve("real"));
        Path file = java.nio.file.Files.writeString(real.resolve("a.txt"), "x");
        Path link = dir.resolve("link");
        try {
            java.nio.file.Files.createSymbolicLink(link, real);
        } catch (java.io.IOException | UnsupportedOperationException noLinks) {
            org.junit.jupiter.api.Assumptions.abort("no symbolic links here");
        }
        PathKeys.invalidateCanonicalCache();
        org.junit.jupiter.api.Assertions.assertNotEquals(
                PathKeys.normalizedKey(file), PathKeys.normalizedKey(link.resolve("a.txt")), "today's key: two");
        org.junit.jupiter.api.Assertions.assertEquals(
                PathKeys.historyKey(file), PathKeys.historyKey(link.resolve("a.txt")));
        // …and for a file that is gone (its pre-delete copy must stay reachable).
        org.junit.jupiter.api.Assertions.assertEquals(
                PathKeys.historyKey(real.resolve("deleted.txt")), PathKeys.historyKey(link.resolve("deleted.txt")));
    }
}
