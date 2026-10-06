package com.editora.git;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitChangeBarsTest {

    @Test
    void cssClassesByLineMapsEachChangeType() {
        Map<Integer, ChangeType> changes = new LinkedHashMap<>();
        changes.put(1, ChangeType.ADDED);
        changes.put(5, ChangeType.MODIFIED);
        changes.put(9, ChangeType.DELETED);

        Map<Integer, String> classes = GitChangeBars.cssClassesByLine(changes);
        assertEquals(ChangeType.ADDED.cssClass(), classes.get(1));
        assertEquals(ChangeType.MODIFIED.cssClass(), classes.get(5));
        assertEquals(ChangeType.DELETED.cssClass(), classes.get(9));
        assertEquals(3, classes.size());
    }

    @Test
    void cssClassesByLineEmptyOrNullIsEmpty() {
        assertTrue(GitChangeBars.cssClassesByLine(Map.of()).isEmpty());
        assertTrue(GitChangeBars.cssClassesByLine(null).isEmpty());
    }

    @Test
    void shouldRediffOnlyBackgroundOnDiskFilesInsideTheRepo() {
        Path root = Path.of("/work/repo");
        Path inside = Path.of("/work/repo/src/Foo.java");
        Path outside = Path.of("/work/other/Bar.java");

        assertTrue(GitChangeBars.shouldRediff(inside, root, false, false), "background file inside the repo");
        assertFalse(GitChangeBars.shouldRediff(inside, root, true, false), "the active buffer is handled separately");
        assertFalse(GitChangeBars.shouldRediff(inside, root, false, true), "huge files have no gutter");
        assertFalse(GitChangeBars.shouldRediff(outside, root, false, false), "files outside the affected repo");
        assertFalse(GitChangeBars.shouldRediff(null, root, false, false), "an unsaved buffer has no path");
        assertFalse(GitChangeBars.shouldRediff(inside, null, false, false), "no repo root");
    }

    @Test
    void shouldRediffResolvesRelativePathsAgainstAbsoluteRoot() {
        // A relative buffer path + an absolute root: comparison is on absolute paths, so equal cwd → inside.
        Path cwd = Path.of("").toAbsolutePath();
        Path root = cwd.resolve("repo");
        Path relInside = Path.of("repo/file.txt");
        assertTrue(GitChangeBars.shouldRediff(relInside, root, false, false));
    }

    @Test
    void shouldRediffSeesThroughASymlinkedProjectPath(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        // git reports the real root; the buffer keeps the path it was opened with.
        Path real =
                java.nio.file.Files.createDirectories(dir.resolve("repo/src")).getParent();
        java.nio.file.Files.writeString(real.resolve("src/Foo.java"), "x");
        Path link;
        try {
            link = java.nio.file.Files.createSymbolicLink(dir.resolve("app-link"), real);
        } catch (java.io.IOException | UnsupportedOperationException noSymlinks) {
            org.junit.jupiter.api.Assumptions.abort("symbolic links are not available here");
            return;
        }
        assertTrue(GitChangeBars.shouldRediff(link.resolve("src/Foo.java"), real.toRealPath(), false, false));
        assertFalse(GitChangeBars.shouldRediff(dir.resolve("elsewhere.txt"), real.toRealPath(), false, false));
    }
}
