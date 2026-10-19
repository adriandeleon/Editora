package com.editora.search;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review E1: a Node folder without a {@code .gitignore} (made by {@code npm init} + {@code npm install}, before
 * {@code git init}) must not have its {@code node_modules} walked while "respect .gitignore" is on — 50,000
 * dependency files filled the index cap and Find in Files' budget before the walk reached {@code src/}.
 */
class NodeModulesPruningTest {

    private static void file(Path root, String rel) throws Exception {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "needle\n");
    }

    private static List<String> walk(Path root, GitignoreFilter filter, int cap) {
        List<String> out = new ArrayList<>();
        ProjectWalk.walk(root, new ProjectWalk.Options(Integer.MAX_VALUE, cap, filter), (f, rel, attrs) -> {
            out.add(rel);
            return ProjectWalk.Verdict.ACCEPT;
        });
        out.sort(null);
        return out;
    }

    @Test
    void dependencyDirNamesAreMatchedOnTheLastSegment() {
        assertTrue(DependencyDirs.isDependencyDir("node_modules"));
        assertTrue(DependencyDirs.isDependencyDir("packages/a/node_modules"));
        assertTrue(DependencyDirs.isDependencyDir("node_modules/"));
        assertFalse(DependencyDirs.isDependencyDir("node_modules/react"));
        assertFalse(DependencyDirs.isDependencyDir("my_node_modules"));
        assertFalse(DependencyDirs.isDependencyDir(""));
        assertFalse(DependencyDirs.isDependencyDir(null));
    }

    @Test
    void anIncludeGlobNamingNodeModulesLooksInside() {
        assertTrue(DependencyDirs.namedBy(List.of("node_modules/**")));
        assertTrue(DependencyDirs.namedBy(List.of("*.ts", "**/node_modules/react/**")));
        assertFalse(DependencyDirs.namedBy(List.of("*.js", "src/**")));
        assertFalse(DependencyDirs.namedBy(List.of()));
        assertFalse(DependencyDirs.namedBy(null));
    }

    @Test
    void aLoadedFilterSkipsNodeModulesWithNoGitignore(@TempDir Path root) throws Exception {
        file(root, "src/index.js");
        file(root, "node_modules/pkg/index.js");
        file(root, "packages/a/node_modules/dep/x.js");
        file(root, "packages/a/lib.js");

        GitignoreFilter loaded = GitignoreFilter.load(root);
        assertTrue(loaded.prunesDependencies());
        assertTrue(loaded.ignored("node_modules", true));
        assertTrue(loaded.ignored("packages/a/node_modules", true));
        assertFalse(loaded.ignored("node_modules", false), "a plain file named node_modules is not a folder");
        assertEquals(List.of("packages/a/lib.js", "src/index.js"), walk(root, loaded, Integer.MAX_VALUE));
    }

    @Test
    void respectGitignoreOffWalksEverything(@TempDir Path root) throws Exception {
        file(root, "src/index.js");
        file(root, "node_modules/pkg/index.js");

        assertEquals(
                List.of("node_modules/pkg/index.js", "src/index.js"),
                walk(root, GitignoreFilter.NONE, Integer.MAX_VALUE));
        assertFalse(GitignoreFilter.parse("*.log\n").prunesDependencies(), "a parsed filter is exactly its text");
    }

    @Test
    void aGitignoreThatReincludesNodeModulesWins(@TempDir Path root) throws Exception {
        file(root, "src/index.js");
        file(root, "node_modules/pkg/index.js");
        Files.writeString(root.resolve(".gitignore"), "!node_modules/\n");

        assertEquals(
                List.of("node_modules/pkg/index.js", "src/index.js"),
                walk(root, GitignoreFilter.load(root), Integer.MAX_VALUE));
    }

    @Test
    void aFolderComparisonCanAskForTheGitignoreFilesOnly(@TempDir Path root) throws Exception {
        file(root, "node_modules/pkg/index.js");
        GitignoreFilter plain = GitignoreFilter.load(root, false);
        assertFalse(plain.prunesDependencies());
        assertFalse(plain.ignored("node_modules", true));
        assertFalse(GitignoreFilter.load(root).withoutDependencyPruning().ignored("node_modules", true));
    }

    @Test
    void theCapIsSpentOnTheProjectNotItsDependencies(@TempDir Path root) throws Exception {
        for (int i = 0; i < 40; i++) {
            file(root, "node_modules/dep" + i + "/index.js");
        }
        for (int i = 0; i < 5; i++) {
            file(root, "src/f" + i + ".js");
        }
        // A cap far below the dependency count: before the fix the walk could stop inside node_modules.
        assertEquals(
                List.of("src/f0.js", "src/f1.js", "src/f2.js", "src/f3.js", "src/f4.js"),
                walk(root, GitignoreFilter.load(root), 10));
    }

    @Test
    void theWalkerFilterFollowsTheSettingAndTheIncludeGlobs(@TempDir Path root) throws Exception {
        assertTrue(SearchService.walkerFilter(root, true, List.of()).ignored("node_modules", true));
        assertTrue(SearchService.walkerFilter(root, true, List.of("*.js")).ignored("node_modules", true));
        assertFalse(SearchService.walkerFilter(root, true, List.of("node_modules/**"))
                .ignored("node_modules", true));
        assertFalse(SearchService.walkerFilter(root, false, List.of()).ignored("node_modules", true));
    }

    @Test
    void ripgrepSkipsNodeModulesLikeTheWalker() {
        SearchQuery q = new SearchQuery("needle", true, false, false);
        List<String> on = RipgrepArgs.build(q, List.of("*.js"), List.of("dist"), true, 1000);
        int nm = on.indexOf("!node_modules/");
        assertTrue(nm > 0, on.toString());
        assertEquals("-g", on.get(nm - 1));
        assertTrue(nm > on.indexOf("*.js"), "after the includes: a later glob wins");

        assertFalse(RipgrepArgs.build(q, List.of(), List.of(), false, 1000).contains("!node_modules/"), "--no-ignore");
        assertFalse(
                RipgrepArgs.build(q, List.of("node_modules/react/**"), List.of(), true, 1000)
                        .contains("!node_modules/"),
                "an include glob naming node_modules looks inside it");
    }
}
