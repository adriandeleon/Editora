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
}
