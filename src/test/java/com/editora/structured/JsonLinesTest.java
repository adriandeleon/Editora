package com.editora.structured;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Pure tests for the JSON Lines check: a value per line, the bad ones placed on their own line. */
class JsonLinesTest {

    @Test
    void everyKindOfValueOnItsOwnLineIsClean() {
        String text = "{\"a\":1}\n[1,2]\n\"s\"\n42\ntrue\nnull\n";
        assertEquals(List.of(), JsonLines.check(text));
    }

    @Test
    void blankLinesCrLfAndSurroundingSpacesAreAllowed() {
        assertEquals(List.of(), JsonLines.check("{\"a\":1}\r\n\r\n  {\"b\":2}  \r\n   \n"));
        assertEquals(List.of(), JsonLines.check(""));
    }

    @Test
    void anInvalidLineIsMarkedOverItsContentWithTheParserColumn() {
        List<JsonLines.Problem> ps = JsonLines.check("{\"a\":1}\n  {\"b\":2,}\n{\"c\":3}");
        assertEquals(1, ps.size());
        JsonLines.Problem p = ps.get(0);
        assertEquals(2, p.line());
        assertSame(JsonLines.Kind.INVALID, p.kind());
        assertEquals(3, p.column(), "the mark starts at the line's first non-blank character");
        assertEquals(8, p.length(), "and covers it to its last one");
        assertEquals(10, p.errorColumn(), "the '}' after the trailing comma");
        assertFalse(p.detail().isBlank());
        assertFalse(p.detail().contains("[Source"), "Jackson's location suffix is dropped: the mark is the place");
    }

    @Test
    void aSecondValueOnTheLineIsMarkedFromWhereItStarts() {
        List<JsonLines.Problem> ps = JsonLines.check("{\"a\":1} {\"b\":2}");
        assertEquals(1, ps.size());
        JsonLines.Problem p = ps.get(0);
        assertSame(JsonLines.Kind.TRAILING, p.kind());
        assertEquals(9, p.column());
        assertEquals(7, p.length());
    }

    @Test
    void aRecordSplitOverTwoLinesMarksBothLines() {
        List<JsonLines.Problem> ps = JsonLines.check("{\"a\":1,\n \"b\":2}\n");
        assertEquals(List.of(1, 2), ps.stream().map(JsonLines.Problem::line).toList());
        assertSame(JsonLines.Kind.INVALID, ps.get(0).kind(), "the first half is an unclosed object");
    }

    @Test
    void commentsAndNonJsonLiteralsAreNotJson() {
        assertEquals(1, JsonLines.check("// note\n").size());
        assertEquals(1, JsonLines.check("NaN\n").size());
        assertEquals(1, JsonLines.check("{'a': 1}\n").size());
    }

    @Test
    void problemsAreCapped() {
        String text = "x\n".repeat(JsonLines.MAX_PROBLEMS + 50);
        assertEquals(JsonLines.MAX_PROBLEMS, JsonLines.check(text).size());
    }

    @Test
    void anOversizedDocumentIsNotChecked() {
        String text = "x".repeat(JsonLines.MAX_CHARS + 1);
        assertEquals(List.of(), JsonLines.check(text));
    }
}
