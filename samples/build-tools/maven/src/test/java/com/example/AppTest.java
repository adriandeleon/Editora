package com.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link App}. Open this file for the gutter ▶ on the class and on each test, and run
 * {@code mvn test} from the Maven popup to fill the Test Results tool window. One test fails on purpose and
 * one is skipped, so every status has an example. "Go: Related File" jumps between this and
 * {@code App.java}.
 */
class AppTest {

    @Test
    void greetsByName() {
        assertEquals("Hello from Ada.", App.greeting("Ada"));
    }

    @Test
    @DisplayName("a blank name is rejected")
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> App.greeting("  "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ada", "Grace", "Barbara"})
    void endsWithAFullStop(String who) {
        assertTrue(App.greeting(who).endsWith("."));
    }

    @Test
    void failsOnPurpose() {
        assertEquals("Goodbye from Ada.", App.greeting("Ada"), "this failure is the sample's red test");
    }

    @Test
    @Disabled("skipped on purpose")
    void skippedOnPurpose() {}

    @Nested
    class Punctuation {

        @Test
        void startsWithHello() {
            assertTrue(App.greeting("Ada").startsWith("Hello"));
        }
    }
}
