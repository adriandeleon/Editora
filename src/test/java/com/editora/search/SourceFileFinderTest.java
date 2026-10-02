package com.editora.search;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Resolving {@code com/foo/Bar.java} — a stack frame's or a test's class — to the file in the project. */
class SourceFileFinderTest {

    private static Path file(Path root, String rel) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        return Files.writeString(f, "class X {}\n");
    }

    @Test
    void aClassIsFoundUnderTheStandardSourceRootsWithoutBeingAtTheProjectRoot(@TempDir Path root) throws Exception {
        Path main = file(root, "src/main/java/com/foo/Bar.java");
        Path test = file(root, "src/test/java/com/foo/BarTest.java");

        assertEquals(main, SourceFileFinder.find(root, "com/foo/Bar.java", () -> false));
        assertEquals(test, SourceFileFinder.find(root, "com/foo/BarTest.java", () -> false));
    }

    @Test
    void aModuleOfAMultiModuleProjectIsFoundByWalking(@TempDir Path root) throws Exception {
        Path inModule = file(root, "services/billing/src/test/java/com/foo/InvoiceTest.java");

        assertEquals(inModule, SourceFileFinder.find(root, "com/foo/InvoiceTest.java", () -> false));
    }

    @Test
    void thePackageDecidesBetweenTwoClassesOfTheSameName(@TempDir Path root) throws Exception {
        file(root, "a/src/main/java/com/one/Util.java");
        Path wanted = file(root, "b/src/main/java/com/two/Util.java");

        assertEquals(wanted, SourceFileFinder.find(root, "com/two/Util.java", () -> false));
    }

    @Test
    void buildOutputAndIgnoredTreesAreNotSearched(@TempDir Path root) throws Exception {
        file(root, "mod/target/generated-sources/com/foo/Gen.java");
        file(root, "mod/build/tmp/com/foo/Gen.java");
        file(root, "mod/node_modules/pkg/com/foo/Gen.java");
        file(root, "dist/com/foo/Gen.java");
        Files.writeString(root.resolve(".gitignore"), "dist/\n");

        assertNull(
                SourceFileFinder.find(root, "com/foo/Gen.java", () -> false), "copies in build output are not sources");
    }

    @Test
    void aSourcePackageNamedBuildIsStillSearched(@TempDir Path root) throws Exception {
        Path wanted = file(root, "mod/src/test/java/com/acme/build/WrapperTest.java");

        assertEquals(wanted, SourceFileFinder.find(root, "com/acme/build/WrapperTest.java", () -> false));
    }

    @Test
    void aBareNameMatchesByFileNameAndASuffixNeverMatchesMidSegment(@TempDir Path root) throws Exception {
        Path bar = file(root, "mod/src/main/java/com/foo/Bar.java");
        file(root, "mod/src/main/java/com/foo/FooBar.java");

        assertEquals(bar, SourceFileFinder.find(root, "Bar.java", () -> false));
        assertNull(
                SourceFileFinder.find(root, "oo/Bar.java", () -> false),
                "\"foo/Bar.java\" must not match \"oo/Bar.java\"");
    }

    @Test
    void nothingToFindAndACancelledLookupBothAnswerNull(@TempDir Path root) throws Exception {
        file(root, "mod/src/main/java/com/foo/Bar.java");

        assertNull(SourceFileFinder.find(root, "com/foo/Missing.java", () -> false));
        assertNull(SourceFileFinder.find(root, "", () -> false));
        assertNull(SourceFileFinder.find(null, "com/foo/Bar.java", () -> false));
        assertNull(
                SourceFileFinder.find(root, "foo/Bar.java", () -> true),
                "a superseded lookup stops walking instead of finishing a search nobody is waiting for");
    }
}
