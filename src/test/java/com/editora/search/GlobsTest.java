package com.editora.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobsTest {

    @Test
    void splitTrimsDropsBlanksAndHandlesNull() {
        assertEquals(List.of("*.java", "src/**"), Globs.split(" *.java , src/** "));
        assertEquals(List.of(), Globs.split(""));
        assertEquals(List.of(), Globs.split(null));
        assertEquals(List.of("a"), Globs.split("a,, ,"));
    }

    @Test
    void acceptWithNoFiltersAlwaysTrue() {
        assertTrue(Globs.accept("any/path/Foo.java", List.of(), List.of()));
    }

    @Test
    void includeRequiresAMatch() {
        List<String> inc = List.of("*.java");
        assertTrue(Globs.accept("src/Foo.java", inc, List.of()), "*.java matches basename in any dir");
        assertFalse(Globs.accept("src/Foo.kt", inc, List.of()));
    }

    @Test
    void excludeWinsOverInclude() {
        assertFalse(
                Globs.accept("src/test/FooTest.java", List.of("*.java"), List.of("**/test/**")),
                "excluded even though it matches the include");
        assertTrue(Globs.accept("src/main/Foo.java", List.of("*.java"), List.of("**/test/**")));
    }

    /** B4(a): a comma inside a brace alternation is part of the glob, not a separator. */
    @Test
    void splitKeepsABraceAlternationInOnePiece() {
        assertEquals(List.of("*.{js,ts}"), Globs.split("*.{js,ts}"));
        assertEquals(List.of("*.{js,ts}", "src/**"), Globs.split(" *.{js,ts} , src/** "));
        assertEquals(List.of("{a,{b,c}}.txt", "d"), Globs.split("{a,{b,c}}.txt,d"), "nested braces too");
        assertEquals(List.of("*.{js,ts"), Globs.split("*.{js,ts"), "an unclosed brace is left for the matcher");
        assertTrue(Globs.accept("web/app.ts", Globs.split("*.{js,ts}"), List.of()), "and the glob then matches");
        assertFalse(Globs.accept("web/app.css", Globs.split("*.{js,ts}"), List.of()));
    }

    /** B4(b): `target` / `node_modules` as an exclude names the directory, like `rg -g '!target'`. */
    @Test
    void aSlashLessExcludeDropsEverythingUnderADirectoryOfThatName() {
        List<String> ex = List.of("target", "node_modules");
        assertFalse(Globs.accept("target/classes/App.class", List.of(), ex));
        assertFalse(Globs.accept("module/target/site/index.html", List.of(), ex), "at any depth");
        assertFalse(Globs.accept("web/node_modules/pkg/index.js", List.of("*.js"), ex), "exclude still beats include");
        assertTrue(Globs.accept("src/Target.java", List.of(), ex), "a different name is untouched");
        assertTrue(Globs.accept("src/targets/a.txt", List.of(), ex), "the whole segment must match");
        assertFalse(Globs.accept("docs/target", List.of(), ex), "a file of that name is still excluded");
    }

    @Test
    void aTrailingSlashExcludesTheDirectoryButNotAFileOfThatName() {
        List<String> ex = List.of("build/");
        assertFalse(Globs.accept("build/out.txt", List.of(), ex));
        assertFalse(Globs.accept("a/build/out.txt", List.of(), ex));
        assertTrue(Globs.accept("scripts/build", List.of(), ex), "build/ names a directory only");
    }

    @Test
    void anAnchoredExcludeDropsThatDirectoryOnly() {
        List<String> ex = List.of("src/gen");
        assertFalse(Globs.accept("src/gen/A.java", List.of(), ex));
        assertTrue(Globs.accept("other/src/gen/A.java", List.of(), ex), "a slash anchors the glob to the root");
    }

    /** The walker prunes with excludesDirectory and then asks acceptFile; together they must equal accept. */
    @Test
    void thePruningPairAgreesWithAccept() {
        List<String> ex = List.of("target", "build/", "*.log");
        assertTrue(Globs.excludesDirectory("target", ex));
        assertTrue(Globs.excludesDirectory("a/b/target", ex));
        assertTrue(Globs.excludesDirectory("a/build", ex));
        assertFalse(Globs.excludesDirectory("src", ex));
        assertFalse(Globs.excludesDirectory("", ex));
        assertFalse(Globs.acceptFile("src/run.log", List.of(), ex));
        assertTrue(Globs.acceptFile("src/build", List.of(), ex), "a directory-only glob never rejects a file");
        assertTrue(Globs.acceptFile("src/A.java", List.of("*.java"), ex));
        assertFalse(Globs.acceptFile("src/A.kt", List.of("*.java"), ex));
    }
}
