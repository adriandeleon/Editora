package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProjectPanel#search} (the filter-box file search). The key case is the bug fix: a
 * top-level dotfile like {@code .profile} must be found even when the root also contains subdirectories —
 * the breadth-first walk visits shallow entries before descending, so a top-level match is never starved
 * by a deep sibling subtree (the old depth-first walk could exhaust its visit budget inside a huge
 * {@code .cache}/{@code node_modules} subtree before ever reaching it). Tagged fx only to keep class
 * loading off the toolkit's path; {@code search} itself is pure file IO.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectPanelSearchTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static boolean containsName(List<Path> results, String name) {
        return results.stream().anyMatch(p -> p.getFileName().toString().equals(name));
    }

    @Test
    void findsTopLevelDotfileWhenShowingHidden(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".profile"), "export X=1\n");
        Files.writeString(root.resolve("notes.txt"), "hi\n");
        Files.createDirectories(root.resolve("sub")); // a sibling subtree that the old DFS could dive into
        Files.writeString(root.resolve("sub/deep.txt"), "x\n");

        List<Path> hits = ProjectPanel.search(root, ".profile", true);
        assertTrue(containsName(hits, ".profile"), "top-level .profile is found (breadth-first)");
    }

    @Test
    void hiddenFilesExcludedUnlessShowingHidden(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve(".profile"), "x\n");
        assertTrue(ProjectPanel.search(root, "profile", false).isEmpty(), "dotfile excluded when not showing hidden");
        assertTrue(
                containsName(ProjectPanel.search(root, "profile", true), ".profile"), "included when showing hidden");
    }

    @Test
    void findsNestedFilesAndSkipsHiddenDirsWhenNotShowingHidden(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/deep.txt"), "x\n");
        Files.createDirectories(root.resolve(".hidden"));
        Files.writeString(root.resolve(".hidden/secret.txt"), "x\n");

        assertTrue(containsName(ProjectPanel.search(root, "deep", false), "deep.txt"), "nested non-hidden file found");
        assertTrue(ProjectPanel.search(root, "secret", false).isEmpty(), "files under a dot-dir skipped (hidden off)");
        assertTrue(
                containsName(ProjectPanel.search(root, "secret", true), "secret.txt"),
                "files under a dot-dir found when showing hidden");
    }

    @Test
    void caseInsensitiveMatch(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("README.md"), "x\n");
        assertTrue(containsName(ProjectPanel.search(root, "readme", false), "README.md"));
        assertFalse(ProjectPanel.search(root, "nomatch", false).stream().anyMatch(p -> true));
    }
}
