package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.search.GitignoreFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review E1, through the symbol index (Go to File, Go to Symbol, Search Everywhere): a Node folder with no
 * {@code .gitignore} indexed 50,000 dependency files and none of its own sources.
 */
class IndexNodeModulesWalkTest {

    private static void file(Path root, String rel, String content) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    @Test
    void aNodeFolderWithoutGitignoreIndexesItsOwnFilesOnly(@TempDir Path root) throws Exception {
        file(root, "package.json", "{\"name\":\"demo\"}\n");
        file(root, "src/app.js", "export function greet() {}\n");
        for (int i = 0; i < 60; i++) {
            file(root, "node_modules/dep" + i + "/index.js", "function dep" + i + "() {}\n");
        }

        IndexCoordinator.Walked walked = IndexCoordinator.walk(root, GitignoreFilter.load(root), 10);

        List<String> rels = walked.files().stream()
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .sorted()
                .toList();
        assertEquals(List.of("package.json", "src/app.js"), rels);
        assertFalse(walked.truncated(), "the cap is not spent on node_modules");
        List<String> symbols = walked.scanned().stream()
                .flatMap(s -> s.symbols().stream())
                .map(com.editora.index.Symbol::name)
                .toList();
        assertTrue(symbols.contains("greet"), symbols.toString());
        assertTrue(symbols.stream().noneMatch(n -> n.startsWith("dep")), "dependencies are not read: " + symbols);
    }

    /**
     * JS review E107: with "Respect .gitignore" off the walk goes into node_modules, but the project's own files
     * come first, so a cap reached inside node_modules still leaves every own file indexed.
     */
    @Test
    void withGitignoreOffOwnFilesComeBeforeDependencies(@TempDir Path root) throws Exception {
        file(root, "package.json", "{\"name\":\"demo\"}\n");
        file(root, "src/app.js", "export function greet() {}\n");
        file(root, "src/zz/last.js", "export function last() {}\n");
        for (int i = 0; i < 60; i++) {
            file(root, "node_modules/dep" + i + "/index.js", "function dep" + i + "() {}\n");
        }

        IndexCoordinator.Walked walked = IndexCoordinator.walk(root, GitignoreFilter.NONE, 10);

        List<String> rels = walked.files().stream()
                .map(p -> root.relativize(p).toString().replace('\\', '/'))
                .toList();
        assertEquals(10, rels.size(), rels.toString());
        assertTrue(rels.containsAll(List.of("package.json", "src/app.js", "src/zz/last.js")), rels.toString());
        assertEquals(7, rels.stream().filter(r -> r.startsWith("node_modules/")).count(), "the rest of the cap");
        assertTrue(walked.truncated(), "53 dependency files were left out");
    }

    @Test
    void withGitignoreOffASmallTreeIsWalkedWhole(@TempDir Path root) throws Exception {
        file(root, "src/app.js", "export function greet() {}\n");
        file(root, "node_modules/a/index.js", "function a() {}\n");

        IndexCoordinator.Walked walked = IndexCoordinator.walk(root, GitignoreFilter.NONE, 10);

        assertEquals(2, walked.files().size());
        assertFalse(walked.truncated());
    }
}
