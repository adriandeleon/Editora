package com.editora.deploy;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeployExcludesTest {

    @Test
    void theBuiltInPatternsLeaveOutVersionControlAndWhatTheHostBuilds() {
        DeployExcludes ex = DeployExcludes.of("");
        assertTrue(ex.excludes(".git/config"));
        assertTrue(ex.excludes("target/classes/App.class"));
        assertTrue(ex.excludes("module/target/app.jar"), "a module's target/ too, at any depth");
        assertTrue(ex.excludes("web/node_modules/x/index.js"));
        assertTrue(ex.excludes("src/app/__pycache__/m.cpython-312.pyc"));
        assertTrue(ex.excludes(".DS_Store"));
        assertFalse(ex.excludes("src/main/java/App.java"));
        assertFalse(ex.excludes("pom.xml"));
        assertFalse(ex.excludes("docs/target.md"), "a file named like a folder pattern is kept");
    }

    @Test
    void aTrailingSlashMatchesOnlyFolders() {
        DeployExcludes ex = DeployExcludes.ofPatterns(List.of("logs/"));
        assertTrue(ex.excludes("logs/today.txt"));
        assertTrue(ex.excludesDirectory("logs"));
        assertFalse(ex.excludes("logs"), "a file called logs is not a folder");
    }

    @Test
    void aPatternWithASlashIsFromTheRoot() {
        DeployExcludes ex = DeployExcludes.ofPatterns(List.of("src/generated/", "/docs/*.pdf"));
        assertTrue(ex.excludes("src/generated/A.java"));
        assertFalse(ex.excludes("lib/src/generated/A.java"), "anchored at the project root");
        assertTrue(ex.excludes("docs/manual.pdf"));
        assertFalse(ex.excludes("docs/sub/manual.pdf"), "* does not cross a folder");
    }

    @Test
    void doubleStarCrossesFolders() {
        DeployExcludes ex = DeployExcludes.ofPatterns(List.of("assets/**/*.psd"));
        assertTrue(ex.excludes("assets/a.psd"));
        assertTrue(ex.excludes("assets/x/y/a.psd"));
        assertFalse(ex.excludes("assets/x/a.png"));
    }

    @Test
    void commentsBlanksAndNegationsAreIgnored() {
        assertEquals(List.of("*.log", "tmp/"), DeployExcludes.lines("# notes\n\n  *.log \r\ntmp/\n"));
        DeployExcludes ex = DeployExcludes.ofPatterns(List.of("!keep.txt", "*.txt"));
        assertTrue(ex.excludes("keep.txt"), "negation is not supported: the line is skipped, not inverted");
    }

    @Test
    void aBangBuiltInTakesItOut() {
        DeployExcludes ex = DeployExcludes.of("!build/\n!not-a-default/");
        assertFalse(ex.excludes("build/gen/A.java"), "a project whose sources are in build/ keeps them");
        assertTrue(ex.excludes("target/x.jar"), "the other built-ins stay");
    }

    @Test
    void regexCharactersInAPatternAreLiteral() {
        DeployExcludes ex = DeployExcludes.ofPatterns(List.of("a+b(1).txt"));
        assertTrue(ex.excludes("a+b(1).txt"));
        assertFalse(ex.excludes("aab1.txt"));
    }

    @Test
    void onlyPlainFolderNamesArePrunedOnTheHost() {
        DeployExcludes ex = DeployExcludes.ofPatterns(List.of("target/", "src/gen/", "*.log", "cache"));
        assertEquals(List.of("target"), ex.prunableNames());
    }
}
