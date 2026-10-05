package com.editora.search;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure .gitignore matcher used by the search walker. */
class GitignoreFilterTest {

    @Test
    void directoryOnlyPatternsSkipBuildOutputAnywhere() {
        GitignoreFilter f = GitignoreFilter.parse("target/\nnode_modules/\nbuild/\n");
        assertTrue(f.ignored("target", true));
        assertTrue(f.ignored("node_modules", true));
        // base-name match at any depth (the walker skips the whole subtree).
        assertTrue(f.ignored("web/node_modules", true));
        assertTrue(f.ignored("a/b/target", true));
        // a *file* named "target" is not excluded by "target/".
        assertFalse(f.ignored("target", false));
        // unrelated paths stay searchable.
        assertFalse(f.ignored("src", true));
        assertFalse(f.ignored("src/Main.java", false));
    }

    @Test
    void fileGlobsAndExtensions() {
        GitignoreFilter f = GitignoreFilter.parse("*.log\n*.class\n");
        assertTrue(f.ignored("app.log", false));
        assertTrue(f.ignored("logs/server.log", false)); // base-name match at depth
        assertTrue(f.ignored("Main.class", false));
        assertFalse(f.ignored("app.log.txt", false));
        assertFalse(f.ignored("README.md", false));
    }

    @Test
    void anchoredVsUnanchored() {
        // leading slash anchors to the root
        GitignoreFilter anchored = GitignoreFilter.parse("/dist\n");
        assertTrue(anchored.ignored("dist", true));
        assertFalse(anchored.ignored("packages/dist", true)); // not at root → kept

        // no slash → matches the base name at any depth
        GitignoreFilter loose = GitignoreFilter.parse("dist\n");
        assertTrue(loose.ignored("dist", true));
        assertTrue(loose.ignored("packages/dist", true));
    }

    @Test
    void negationReincludes() {
        GitignoreFilter f = GitignoreFilter.parse("*.log\n!keep.log\n");
        assertTrue(f.ignored("debug.log", false));
        assertFalse(f.ignored("keep.log", false)); // last matching rule (the negation) wins
    }

    @Test
    void commentsAndBlankLinesIgnored() {
        GitignoreFilter f = GitignoreFilter.parse("# comment\n\n   \ntarget/\n");
        assertTrue(f.ignored("target", true));
        assertFalse(f.ignored("comment", true));
    }

    @Test
    void doubleStarCrossesDirectories() {
        GitignoreFilter f = GitignoreFilter.parse("**/generated\nlogs/**\n");
        assertTrue(f.ignored("generated", true));
        assertTrue(f.ignored("a/b/generated", true));
        assertTrue(f.ignored("logs/2026/app", false));
        assertFalse(f.ignored("src/logsmith", false));
    }

    @Test
    void emptyOrNoGitignoreMatchesNothing() {
        assertTrue(GitignoreFilter.NONE.isEmpty());
        assertFalse(GitignoreFilter.NONE.ignored("target", true));
        assertTrue(GitignoreFilter.parse("").isEmpty());
        assertTrue(GitignoreFilter.parse("# only a comment\n").isEmpty());
    }

    // --- B5-5: a module opened as the project still honours the repository's .gitignore --------------------

    @org.junit.jupiter.api.Test
    void aFolderInsideARepositoryInheritsTheGitignoresAboveIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Path repo = java.nio.file.Files.createDirectories(dir.resolve("repo"));
        java.nio.file.Files.createDirectories(repo.resolve(".git"));
        java.nio.file.Files.writeString(repo.resolve(".gitignore"), "node_modules/\ntarget/\n/app/gen\n*.log\n");
        java.nio.file.Path app = java.nio.file.Files.createDirectories(repo.resolve("app"));

        GitignoreFilter f = GitignoreFilter.load(app);
        assertTrue(f.ignored("node_modules", true));
        assertTrue(f.ignored("sub/target", true));
        assertTrue(f.ignored("gen", true), "a rule anchored at the repository top, seen from the module");
        assertFalse(f.ignored("src/gen", true));
        assertTrue(f.ignored("a.log", false));
        assertFalse(f.ignored("src/Main.java", false));

        // The module's own file outranks the ones above it.
        java.nio.file.Files.writeString(app.resolve(".gitignore"), "!keep.log\n");
        GitignoreFilter own = GitignoreFilter.load(app);
        assertFalse(own.ignored("keep.log", false));
        assertTrue(own.ignored("other.log", false));
    }

    @org.junit.jupiter.api.Test
    void outsideARepositoryOnlyTheRootsOwnFileApplies(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        java.nio.file.Files.writeString(dir.resolve(".gitignore"), "build/\n");
        java.nio.file.Path sub = java.nio.file.Files.createDirectories(dir.resolve("sub"));
        org.junit.jupiter.api.Assumptions.assumeTrue(!insideRepository(dir), "temp dir is inside a repository");
        assertFalse(GitignoreFilter.load(sub).ignored("build", true), "no .git above: the parent's file is not read");
        assertTrue(GitignoreFilter.load(dir).ignored("build", true));
    }

    private static boolean insideRepository(java.nio.file.Path dir) {
        for (java.nio.file.Path p = dir.toAbsolutePath(); p != null; p = p.getParent()) {
            if (java.nio.file.Files.exists(p.resolve(".git"))) {
                return true;
            }
        }
        return false;
    }
}
