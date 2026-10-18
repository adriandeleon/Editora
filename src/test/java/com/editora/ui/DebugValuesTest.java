package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugValuesTest {

    /** The runs of {@code value} as "text|token" strings (plain runs have no "|"). */
    private static List<String> runs(String value) {
        return DebugValues.runs(value).stream()
                .map(r -> r.token() == null ? r.text() : r.text() + "|" + r.token())
                .toList();
    }

    @Test
    void stringsByQuoteStyle() {
        assertEquals(List.of("\"hello\"|string"), runs("\"hello\"")); // Java / JS
        assertEquals(List.of("'hello'|string"), runs("'hello'")); // Python
        assertEquals(List.of("\"say \\\"hi\\\"\"|string"), runs("\"say \\\"hi\\\"\""), "escaped quotes stay inside");
        assertEquals(List.of("\"unterminated|string"), runs("\"unterminated"));
    }

    @Test
    void numbersAcrossNotations() {
        for (String n : List.of("42", "-3.14", "1.5e10", "0xFF", "100L", "1_000")) {
            assertEquals(List.of(n + "|number"), runs(n), n);
        }
    }

    @Test
    void literalWordsAreConstantsAcrossLanguages() {
        for (String c : List.of("true", "False", "null", "None", "undefined", "nil", "NaN")) {
            assertEquals(List.of(c + "|constant"), runs(c), c);
        }
    }

    @Test
    void objectReferencesColourTheTypeButNotTheId() {
        assertEquals(List.of("String|type", "[", "0|number", "]@8"), runs("String[0]@8"));
        assertEquals(List.of("ArrayList|type", "@12 size=", "3|number"), runs("ArrayList@12 size=3"));
        assertEquals(List.of("java.util.HashMap|type", "@1f2a"), runs("java.util.HashMap@1f2a"));
        assertEquals(List.of("DebugDemo|type", "@1f2a"), runs("DebugDemo@1f2a"));
    }

    @Test
    void structuredValuesAreColouredPerElement() {
        assertEquals(List.of("[", "1|number", ", ", "'a'|string", ", ", "None|constant", "]"), runs("[1, 'a', None]"));
        assertEquals(List.of("{a: ", "1|number", ", b: ", "true|constant", "}"), runs("{a: 1, b: true}"));
        assertEquals(List.of("<", "Foo|type", " object at ", "0x7f3a|number", ">"), runs("<Foo object at 0x7f3a>"));
    }

    @Test
    void digitsInsideWordsAndAllCapsNamesStayPlain() {
        assertEquals(List.of("abc123 x2"), runs("abc123 x2"));
        assertEquals(List.of("MAX_VALUE"), runs("MAX_VALUE"));
        assertEquals(List.of("a-", "1|number"), runs("a-1"));
    }

    @Test
    void emptyAndNullHaveNoRuns() {
        assertEquals(List.of(), runs(""));
        assertEquals(List.of(), runs(null));
    }

    /** A 1,000-element list must not become 2,000 Text nodes in one tree cell. */
    @Test
    void longValuesAreCappedToAFewRuns() {
        String big = java.util.stream.IntStream.range(0, 1000)
                .mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        List<DebugValues.Run> runs = DebugValues.runs(big);
        assertTrue(runs.size() <= DebugValues.MAX_RUNS, "runs: " + runs.size());
        assertEquals(big, runs.stream().map(DebugValues.Run::text).collect(java.util.stream.Collectors.joining()));
        assertNull(runs.get(runs.size() - 1).token(), "the remainder is plain");
    }

    /** D2-1: a container's children are shown — and, when the adapter reports the count, fetched — by page. */
    @Test
    void childrenAreShownAPageAtATime() {
        assertEquals(false, DebugValues.fetchedByPage(0), "no count reported: ask for everything");
        assertEquals(false, DebugValues.fetchedByPage(DebugValues.PAGE_SIZE));
        assertEquals(true, DebugValues.fetchedByPage(65_536));
        assertEquals(DebugValues.PAGE_SIZE, DebugValues.nextPage(0, 65_536));
        assertEquals(36, DebugValues.nextPage(65_500, 65_536));
        assertEquals(0, DebugValues.nextPage(65_536, 65_536));
        assertEquals(104, DebugValues.nextPage(0, 104), "a short tail is not a page of its own");
        assertEquals(DebugValues.PAGE_SIZE, DebugValues.nextPage(0, 126));
    }

    /** D2-2: a frame source that is not a file still gets a readable name. */
    @Test
    void sourceNameOfAUriIsItsClassFile() {
        assertEquals("util.py", DebugValues.sourceName(java.nio.file.Path.of("/proj/util.py")));
        assertEquals("", DebugValues.sourceName(null));
    }

    /** As above, for a source that is a URI rather than a file. */
    @Test
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason = "a jdt: URI is not a legal Windows path; DapClient.sourcePath yields no file for it there")
    void sourceNameOfAJdtUriDropsItsQuery() {
        assertEquals(
                "Thread.java",
                DebugValues.sourceName(
                        java.nio.file.Path.of(
                                "jdt:/contents/java.base/java.lang/Thread.java?=myapp/%5C/usr%5C/lib%5C/jvm%3Cjava.lang(Thread.class")));
    }

    /** D2-16: colour codes in program output are not text. */
    @Test
    void ansiSequencesAreStripped() {
        assertEquals("red text plain", DebugValues.stripAnsi("\u001B[31mred text\u001B[0m plain"));
        assertEquals("title", DebugValues.stripAnsi("\u001B]0;window\u0007title"));
        assertEquals("a [b] c", DebugValues.stripAnsi("a [b] c"));
    }

    @Test
    void aMultiLineValueShowsItsFirstLine() {
        // js-debug describes a function by its source (review D9)
        assertEquals("ƒ next() { … }", DebugValues.display("ƒ next() {\n    count += 1;\n    return count;\n  }"));
        assertEquals("first …", DebugValues.display("\nfirst\nsecond"));
        assertEquals("'one line'", DebugValues.display("'one line'"));
        assertNull(DebugValues.display(null));
    }
}
