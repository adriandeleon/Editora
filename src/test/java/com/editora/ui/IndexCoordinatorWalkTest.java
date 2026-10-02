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
}
