package com.editora.run;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainClassSourceTest {

    @Test
    void standardMavenLayoutIsTriedFirst() {
        List<String> c = MainClassSource.candidates("com.example.demo.App");
        assertEquals("src/main/java/com/example/demo/App.java", c.get(0));
        assertTrue(c.contains("src/test/java/com/example/demo/App.java"));
        assertTrue(c.contains("com/example/demo/App.java"), "a flat layout is the last resort");
    }

    @Test
    void mainSourcesAreTriedBeforeTestSources() {
        List<String> c = MainClassSource.candidates("com.example.App");
        assertTrue(
                c.indexOf("src/main/java/com/example/App.java") < c.indexOf("src/test/java/com/example/App.java"),
                "Run means the application, not its tests");
    }

    @Test
    void aDefaultPackageClassHasNoDirectories() {
        assertEquals("src/main/java/App.java", MainClassSource.candidates("App").get(0));
    }

    @Test
    void aNestedClassMapsToItsOuterFile() {
        assertEquals(
                "src/main/java/com/example/App.java",
                MainClassSource.candidates("com.example.App$Inner").get(0));
    }

    @Test
    void aFileNameYieldsNothingRatherThanAGuess() {
        // That mistake is caught earlier with a precise message; guessing App/java.java would re-hide it.
        assertTrue(MainClassSource.candidates("App.java").isEmpty());
        assertTrue(MainClassSource.candidates("App.class").isEmpty());
    }

    @Test
    void blankOrMalformedYieldsNothing() {
        assertTrue(MainClassSource.candidates(null).isEmpty());
        assertTrue(MainClassSource.candidates("").isEmpty());
        assertTrue(MainClassSource.candidates("  ").isEmpty());
        assertTrue(MainClassSource.candidates("com.example.").isEmpty());
    }
}
