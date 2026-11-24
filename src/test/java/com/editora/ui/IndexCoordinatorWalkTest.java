package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import com.editora.search.GitignoreFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The symbol index's project walk. It used to enumerate the whole tree and filter files afterwards, which
 * excluded nothing under {@code target/} or {@code node_modules/}: their files were read, offered in Search
 * Everywhere, and counted against the cap — so a big {@code node_modules} left the project's own sources
 * unindexed, silently. Pure: the walk is a static function of a directory.
 */
class IndexCoordinatorWalkTest {

    private static Path file(Path root, String rel, String content) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        return Files.writeString(f, content);
    }

    private static List<String> rels(Path root, IndexCoordinator.Walked walked) {
        return walked.files().stream()
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();
    }

    @Test
    void ignoredDirectoriesAreNeitherListedNorScannedNorCounted(@TempDir Path root) throws Exception {
        file(root, "src/main/java/App.java", "class App { void run() {} }\n");
        file(root, "README.md", "# hi\n");
        file(root, "target/classes/x.properties", "a=b\n");
        file(root, "target/generated/Gen.java", "class GeneratedNoise {}\n");
        for (int i = 0; i < 40; i++) {
            file(root, "node_modules/pkg/lib" + i + ".js", "function leaked" + i + "() {}\n");
        }
        file(root, ".git/HEAD", "ref: refs/heads/main\n");
        Files.writeString(root.resolve(".gitignore"), "target/\nnode_modules\n");

        // A cap far below the ignored file count: before the fix the walk gave up inside node_modules.
        IndexCoordinator.Walked walked = IndexCoordinator.walk(root, GitignoreFilter.load(root), 5);

        assertEquals(List.of("README.md", "src/main/java/App.java"), rels(root, walked));
        assertFalse(walked.truncated(), "two real files under a cap of five is a complete index");
        List<String> symbols = walked.scanned().stream()
                .flatMap(s -> s.symbols().stream())
                .map(com.editora.index.Symbol::name)
                .toList();
        assertTrue(symbols.contains("App"), "the project's own source is indexed: " + symbols);
        assertFalse(symbols.contains("GeneratedNoise"), "build output is not");
        assertTrue(symbols.stream().noneMatch(n -> n.startsWith("leaked")), "nor are dependencies");
    }

    @Test
    void anExtensionlessScriptIsIndexedByItsShebang(@TempDir Path root) throws Exception {
        // B10: `bin/release` opened as shell in the editor but was never in Go to Symbol.
        file(root, "bin/release", "#!/usr/bin/env bash\nbump_version() {\n  :\n}\n");
        file(root, "LICENSE", "release() is not code here\n");

        IndexCoordinator.Walked walked = IndexCoordinator.walk(root, GitignoreFilter.NONE, 10);

        List<String> symbols = walked.scanned().stream()
                .flatMap(s -> s.symbols().stream())
                .map(com.editora.index.Symbol::name)
                .toList();
        assertEquals(List.of("bump_version"), symbols);
        assertEquals("shell", IndexCoordinator.languageOf(root.resolve("bin/release")));
        assertEquals("plaintext", IndexCoordinator.languageOf(root.resolve("LICENSE")));
    }

    @Test
    void reachingTheCapIsReportedInsteadOfLeavingASilentlyPartialIndex(@TempDir Path root) throws Exception {
        file(root, "a/A.java", "class A {}\n");
        file(root, "b/B.java", "class B {}\n");
        file(root, "c/C.java", "class C {}\n");

        IndexCoordinator.Walked walked = IndexCoordinator.walk(root, GitignoreFilter.NONE, 2);

        assertEquals(2, walked.files().size());
        assertTrue(walked.truncated());
    }

    @Test
    void oneUnreadableDirectoryDoesNotCostTheRestOfTheIndex(@TempDir Path root) throws Exception {
        file(root, "a/First.java", "class First {}\n");
        file(root, "b/Hidden.java", "class Hidden {}\n");
        file(root, "c/Last.java", "class Last {}\n");
        Path locked = root.resolve("b");
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(locked), "running as root: nothing is unreadable");

            IndexCoordinator.Walked walked = IndexCoordinator.walk(root, GitignoreFilter.NONE, 100);

            // Files.walk threw UncheckedIOException at b/ and the index ended there, without c/.
            assertEquals(List.of("a/First.java", "c/Last.java"), rels(root, walked));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * Every file is listed (Search Everywhere offers it) whether or not it can be read; only a language
     * with declaration rules is ever opened, and failing to open one is not fatal.
     */
    @Test
    void unreadableFilesAreStillListedAndOnlyDeclarationLanguagesAreRead(@TempDir Path root) throws Exception {
        Path data = file(root, "data/big.csv", "a,b\n1,2\n");
        Path source = file(root, "src/Tool.java", "class Tool {}\n");
        assumeTrue(root.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("---------"));
        Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(data), "running as root: nothing is unreadable");

            IndexCoordinator.Walked walked = IndexCoordinator.walk(root, GitignoreFilter.NONE, 100);

            assertEquals(List.of("data/big.csv", "src/Tool.java"), rels(root, walked), "both are still files");
            assertTrue(walked.scanned().isEmpty(), "and an unreadable source is skipped, not fatal");
        } finally {
            Files.setPosixFilePermissions(data, PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(source, PosixFilePermissions.fromString("rw-------"));
        }
        assertTrue(com.editora.index.DeclarationScanner.supports("java"));
        assertFalse(com.editora.index.DeclarationScanner.supports("csv"));
        assertFalse(com.editora.index.DeclarationScanner.supports(null));
    }

    @Test
    void aSupersededWalkStopsReadingFiles(@TempDir Path root) throws Exception {
        for (int i = 0; i < 40; i++) {
            Files.writeString(root.resolve("C" + i + ".java"), "class C" + i + " {}\n");
        }
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();

        IndexCoordinator.Walked walked =
                IndexCoordinator.walk(root, GitignoreFilter.NONE, 100, () -> asked.incrementAndGet() > 10);

        assertTrue(walked.files().size() <= 10, "read " + walked.files().size() + " of 40 files");
        assertEquals(
                40,
                IndexCoordinator.walk(root, GitignoreFilter.NONE, 100).files().size(),
                "the control");
    }

    private static void age(Path file) throws Exception {
        Files.setLastModifiedTime(
                file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
    }

    private static IndexCoordinator.Prior prior(IndexCoordinator.Walked walked) {
        com.editora.index.SymbolIndex index = new com.editora.index.SymbolIndex();
        walked.scanned().forEach(s -> index.put(s.file(), s.symbols()));
        return new IndexCoordinator.Prior(walked.stamps(), index.freeze());
    }

    private static List<String> symbolNames(IndexCoordinator.Walked walked) {
        return walked.scanned().stream()
                .flatMap(s -> s.symbols().stream())
                .map(com.editora.index.Symbol::name)
                .sorted()
                .toList();
    }

    /** F5: a re-walk reads only the files whose stamp changed, and takes the rest from the index it replaces. */
    @Test
    void aReWalkReadsOnlyWhatChangedOnDisk(@TempDir Path root) throws Exception {
        Path kept = file(root, "Kept.java", "class Kept {}\n");
        Path changed = file(root, "Changed.java", "class Before {}\n");
        Path empty = file(root, "Empty.java", "// nothing declared\n");
        age(kept);
        age(changed);
        age(empty);
        IndexCoordinator.Walked first = IndexCoordinator.walk(root, GitignoreFilter.NONE, 100, () -> false);
        assertEquals(0, first.reused());
        assertEquals(3, first.stamps().size(), "settled files keep their stamps, a file with no symbols too");

        Files.writeString(changed, "class AfterTheCheckout {}\n"); // another size, a new time
        file(root, "Arrived.java", "class Arrived {}\n");
        IndexCoordinator.Walked second =
                IndexCoordinator.walk(root, GitignoreFilter.NONE, 100, () -> false, prior(first));

        assertEquals(2, second.reused(), "Kept and Empty came from the old index");
        assertEquals(List.of("AfterTheCheckout", "Arrived", "Kept"), symbolNames(second));
        assertFalse(second.stamps().containsKey(changed), "just written: read again next time too");
    }

    /** A same-size change keeps a stamp only once the file has settled, so a rewrite in the same tick is reread. */
    @Test
    void aJustWrittenFileIsNeverTakenOnItsStamp(@TempDir Path root) throws Exception {
        file(root, "Fresh.java", "class Fresh {}\n");
        IndexCoordinator.Walked first = IndexCoordinator.walk(root, GitignoreFilter.NONE, 100, () -> false);
        assertTrue(first.stamps().isEmpty());
        IndexCoordinator.Walked second =
                IndexCoordinator.walk(root, GitignoreFilter.NONE, 100, () -> false, prior(first));
        assertEquals(0, second.reused());
        assertEquals(List.of("Fresh"), symbolNames(second));
    }

    /** F5: a stale index's hits are checked against their files before Go to Definition jumps. */
    @Test
    void aStaleHitIsRecheckedAgainstItsFile(@TempDir Path root) throws Exception {
        Path moved = file(root, "Moved.java", "\n\n\nclass Target {}\n");
        Path gone = root.resolve("Gone.java");
        Path renamed = file(root, "Renamed.java", "class Other {}\n");
        com.editora.index.Symbol stale =
                new com.editora.index.Symbol("Target", com.editora.index.SymbolKind.TYPE, 0, 6, "");
        List<com.editora.index.SymbolIndex.Hit> hits = List.of(
                new com.editora.index.SymbolIndex.Hit(moved, stale, 9),
                new com.editora.index.SymbolIndex.Hit(gone, stale, 9),
                new com.editora.index.SymbolIndex.Hit(renamed, stale, 9));

        List<com.editora.index.SymbolIndex.Hit> now = IndexCoordinator.recheck(hits, "Target");

        assertEquals(1, now.size());
        assertEquals(moved, now.get(0).file());
        assertEquals(3, now.get(0).symbol().line(), "where the declaration is now, not where it was");
    }
}
