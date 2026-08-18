package com.editora.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelatedFilesTest {

    @Test
    void aSourceFileOffersItsTest() {
        assertTrue(RelatedFiles.candidates("Greeter.java").contains("GreeterTest.java"));
    }

    @Test
    void aTestOffersItsSubjectFirst() {
        // Coming from the test, the subject is overwhelmingly the destination — it must lead.
        assertEquals("Greeter.java", RelatedFiles.candidates("GreeterTest.java").get(0));
    }

    @Test
    void theRoundTripIsSymmetric() {
        assertTrue(RelatedFiles.candidates("Greeter.java").contains("GreeterTest.java"));
        assertTrue(RelatedFiles.candidates("GreeterTest.java").contains("Greeter.java"));
    }

    @Test
    void headerAndImplementationPairBothWays() {
        assertTrue(RelatedFiles.candidates("parser.c").contains("parser.h"));
        assertTrue(RelatedFiles.candidates("parser.h").contains("parser.c"));
        assertTrue(RelatedFiles.candidates("parser.cpp").contains("parser.hpp"));
    }

    @Test
    void aComponentOffersItsStylesheet() {
        assertTrue(RelatedFiles.candidates("Button.tsx").contains("Button.css"));
        assertTrue(RelatedFiles.candidates("Button.tsx").contains("Button.scss"));
    }

    @Test
    void pythonAndGoTestConventions() {
        assertEquals("parser.py", RelatedFiles.candidates("test_parser.py").get(0));
        assertTrue(RelatedFiles.candidates("parser.go").contains("parser_test.go"));
        assertEquals("parser.go", RelatedFiles.candidates("parser_test.go").get(0));
    }

    @Test
    void aFileNamedTestIsASubjectNotATestOfNothing() {
        // The suffix has to leave something behind, or "Test.java" maps to ".java".
        assertNull(RelatedFiles.testSubject("Test"));
        assertFalse(RelatedFiles.candidates("Test.java").contains(".java"));
    }

    @Test
    void neverOffersTheFileItself() {
        for (String name : List.of("Greeter.java", "GreeterTest.java", "parser.c", "Button.tsx")) {
            assertFalse(RelatedFiles.candidates(name).contains(name), name + " offered itself");
        }
    }

    @Test
    void anUnknownExtensionStillOffersATest() {
        assertTrue(RelatedFiles.candidates("thing.zig").contains("thingTest.zig"));
    }

    @Test
    void aDotfileIsAllBaseAndNoExtension() {
        // ".gitignore" must not be read as base "" with extension "gitignore".
        assertFalse(RelatedFiles.candidates(".gitignore").contains(".gitignore"));
        assertTrue(RelatedFiles.candidates(".gitignore").stream().allMatch(c -> c.startsWith(".gitignore")));
    }

    @Test
    void candidatesAreUniqueAndOrdered() {
        List<String> c = RelatedFiles.candidates("Greeter.java");
        assertEquals(c.size(), c.stream().distinct().count(), "duplicates would show as duplicate rows");
    }

    @Test
    void degenerateInputIsSafe() {
        assertEquals(List.of(), RelatedFiles.candidates(null));
        assertEquals(List.of(), RelatedFiles.candidates("  "));
        assertNull(RelatedFiles.testSubject(null));
    }
}
