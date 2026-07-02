package com.editora.csv;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvAlignTest {

    @Test
    void alignsColumnsToTheirMaxWidth() {
        String in = "name,age,city\nAlice,30,NYC\nBob,25,LA";
        String out = CsvAlign.align(in, ',');
        assertEquals("name ,age,city\n" + "Alice,30 ,NYC\n" + "Bob  ,25 ,LA", out);
    }

    @Test
    void shrinkTrimsPaddingBackToBareFields() {
        String aligned = "name ,age,city\nAlice,30 ,NYC\nBob  ,25 ,LA";
        assertEquals("name,age,city\nAlice,30,NYC\nBob,25,LA", CsvAlign.shrink(aligned, ','));
    }

    @Test
    void alignIsIdempotentAndShrinkReversesIt() {
        String in = "a,bb,ccc\naaa,b,cc\n";
        String once = CsvAlign.align(in, ',');
        assertEquals(once, CsvAlign.align(once, ','), "aligning twice must be a no-op");
        // shrink(align(x)) is the canonical trimmed form of x (x here had no surrounding spaces)
        assertEquals(CsvAlign.shrink(in, ','), CsvAlign.shrink(once, ','));
        assertEquals("a,bb,ccc\naaa,b,cc\n", CsvAlign.shrink(once, ','));
    }

    @Test
    void preservesQuotedFieldsWithEmbeddedDelimiters() {
        String in = "id,name\n1,\"Doe, John\"\n2,Ann";
        String out = CsvAlign.align(in, ',');
        // The quoted field keeps its quotes + comma; padding is measured on the raw quoted text.
        assertEquals("id,name\n" + "1 ,\"Doe, John\"\n" + "2 ,Ann", out);
        assertEquals("id,name\n1,\"Doe, John\"\n2,Ann", CsvAlign.shrink(out, ','));
    }

    @Test
    void blankLinesAndTrailingNewlineArePreserved() {
        String in = "a,b\n\nc,dd\n";
        String out = CsvAlign.align(in, ',');
        assertEquals("a,b\n\nc,dd\n", out); // blank middle line + trailing newline kept
        assertEquals(in, CsvAlign.shrink(out, ','));
    }

    @Test
    void raggedRowsAlignTheFieldsThatExist() {
        String in = "a,b,c\nlong,x\nq,r,s";
        String out = CsvAlign.align(in, ',');
        assertEquals("a   ,b,c\n" + "long,x\n" + "q   ,r,s", out);
    }

    @Test
    void tabDelimiterUsesTheSameLogic() {
        String out = CsvAlign.align("a\tbb\nccc\td", '\t');
        assertEquals("a  \tbb\nccc\td", out);
        assertEquals("a\tbb\nccc\td", CsvAlign.shrink(out, '\t'));
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertEquals(null, CsvAlign.align(null, ','));
        assertEquals("", CsvAlign.align("", ','));
        assertEquals(null, CsvAlign.shrink(null, ','));
        assertEquals("", CsvAlign.shrink("", ','));
    }

    @Test
    void splitFieldsRawKeepsQuotes() {
        assertEquals(List.of("1", "\"a,b\"", "c"), CsvAlign.splitFieldsRaw("1,\"a,b\",c", ','));
        assertEquals(List.of("\"he said \"\"hi\"\"\""), CsvAlign.splitFieldsRaw("\"he said \"\"hi\"\"\"", ','));
    }
}
