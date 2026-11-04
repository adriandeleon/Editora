package com.editora.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * JS review E106: the built-in walker said "(truncated)" whenever a file over 2 MB sat in the tree — a built
 * {@code dist/bundle.min.js} in every JavaScript project — although no match was left out. ripgrep skips such a
 * file without a word ({@code --max-filesize}); the walker now does the same.
 */
class SearchServiceLargeFileTest {

    @Test
    void aLargeBundleIsSkippedWithoutMarkingTheSearchCut(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("dist"));
        Files.writeString(root.resolve("src/index.js"), "// needle\n");
        Files.writeString(root.resolve("dist/bundle.min.js"), "x".repeat(3 * 1024 * 1024));

        Set<Path> candidates = new LinkedHashSet<>();
        boolean partial =
                SearchService.collect(root, candidates, GitignoreFilter.NONE, List.of(), Set.of(), () -> false);

        assertEquals(Set.of(root.resolve("src/index.js")), Set.copyOf(candidates), "the bundle is not read");
        assertFalse(partial, "nothing was cut: the large file is skipped, as ripgrep skips it");
    }
}
