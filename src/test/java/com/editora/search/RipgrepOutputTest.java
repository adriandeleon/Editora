package com.editora.search;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RipgrepOutputTest {

    @Test
    void parsesMatchesGroupedByFile() {
        String out = String.join(
                "\n",
                "{\"type\":\"begin\",\"data\":{\"path\":{\"text\":\"src/Foo.java\"}}}",
                "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"src/Foo.java\"},"
                        + "\"lines\":{\"text\":\"  int foo = bar;\\n\"},\"line_number\":12,"
                        + "\"submatches\":[{\"match\":{\"text\":\"foo\"},\"start\":6,\"end\":9}]}}",
                "{\"type\":\"end\",\"data\":{\"path\":{\"text\":\"src/Foo.java\"}}}",
                "{\"type\":\"summary\",\"data\":{\"stats\":{\"matched_lines\":1}}}");
        List<FileResult> results = RipgrepOutput.parse(out);
        assertEquals(1, results.size());
        FileResult fr = results.get(0);
        assertEquals("src/Foo.java", fr.file().toString());
        assertEquals(1, fr.matches().size());
        LineMatch m = fr.matches().get(0);
        assertEquals(12, m.line());
        assertEquals(7, m.col(), "byte offset 6 → 1-based char col 7 (ASCII)");
        assertEquals(3, m.length());
        assertEquals("  int foo = bar;", m.lineText(), "trailing newline stripped");
    }

    @Test
    void convertsByteOffsetToCharColumnForMultibyteLine() {
        // "café foo": 'c''a''f' = 3 bytes, 'é' = 2 bytes, ' ' = 1 byte → "foo" starts at BYTE 6 but CHAR 5.
        String out = "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"u.txt\"},"
                + "\"lines\":{\"text\":\"café foo\\n\"},\"line_number\":1,"
                + "\"submatches\":[{\"match\":{\"text\":\"foo\"},\"start\":6,\"end\":9}]}}";
        List<FileResult> results = RipgrepOutput.parse(out);
        assertEquals(1, results.size());
        LineMatch m = results.get(0).matches().get(0);
        assertEquals(6, m.col(), "char col 5 + 1 = 6, not byte 6 + 1 = 7");
        assertEquals(3, m.length());
        assertEquals("café foo", m.lineText());
    }

    @Test
    void emptyAndNoMatchYieldNoResults() {
        assertTrue(RipgrepOutput.parse(null).isEmpty());
        assertTrue(RipgrepOutput.parse("").isEmpty());
        assertTrue(
                RipgrepOutput.parse("{\"type\":\"summary\",\"data\":{\"stats\":{\"matched_lines\":0}}}")
                        .isEmpty(),
                "summary-only stream → no file results");
    }

    @Test
    void skipsMalformedLinesAndBinaryPaths() {
        String out = String.join(
                "\n",
                "this is not json",
                // binary path (base64 bytes, no text) → skipped
                "{\"type\":\"match\",\"data\":{\"path\":{\"bytes\":\"eA==\"},"
                        + "\"lines\":{\"text\":\"x\\n\"},\"line_number\":1,"
                        + "\"submatches\":[{\"match\":{\"text\":\"x\"},\"start\":0,\"end\":1}]}}",
                // valid match survives
                "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"a.txt\"},"
                        + "\"lines\":{\"text\":\"xyz\\n\"},\"line_number\":3,"
                        + "\"submatches\":[{\"match\":{\"text\":\"y\"},\"start\":1,\"end\":2}]}}");
        List<FileResult> results = RipgrepOutput.parse(out);
        assertEquals(1, results.size());
        assertEquals("a.txt", results.get(0).file().toString());
        assertEquals(2, results.get(0).matches().get(0).col());
    }
}
