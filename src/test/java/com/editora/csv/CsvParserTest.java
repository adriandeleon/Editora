package com.editora.csv;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvParserTest {

    @Test
    void detectsCommaByDefault() {
        assertEquals(',', CsvParser.detectDelimiter("a,b,c\n1,2,3"));
        assertEquals(',', CsvParser.detectDelimiter("single"));
        assertEquals(',', CsvParser.detectDelimiter(""));
        assertEquals(',', CsvParser.detectDelimiter(null));
    }

    @Test
    void detectsSemicolonTabAndPipe() {
        assertEquals(';', CsvParser.detectDelimiter("a;b;c\n1;2;3"));
        assertEquals('\t', CsvParser.detectDelimiter("a\tb\tc"));
        assertEquals('|', CsvParser.detectDelimiter("a|b|c|d"));
    }

    @Test
    void detectionSkipsBlankLeadingLinesAndQuotedDelimiters() {
        // Leading blank lines are skipped; the first real line drives detection.
        assertEquals(';', CsvParser.detectDelimiter("\n\n x ; y ; z "));
        // A delimiter inside quotes doesn't count — here only the two unquoted semicolons do.
        assertEquals(';', CsvParser.detectDelimiter("\"a,b,c\";y;z"));
    }

    @Test
    void tabWinsTiesOverComma() {
        // Equal tabs and commas -> tab (a .tsv whose data also contains commas).
        assertEquals('\t', CsvParser.detectDelimiter("a\tb,c\td,e"));
    }

    @Test
    void fieldCountHonorsQuotes() {
        assertEquals(3, CsvParser.fieldCount("a,b,c", ','));
        assertEquals(1, CsvParser.fieldCount("", ','));
        assertEquals(1, CsvParser.fieldCount("nodelim", ','));
        // The comma inside the quoted first field is not a separator -> 2 fields, not 3.
        assertEquals(2, CsvParser.fieldCount("\"a,b\",c", ','));
        // Trailing delimiter -> an empty final field still counts.
        assertEquals(3, CsvParser.fieldCount("a,b,", ','));
    }

    @Test
    void fieldIndexAtCounts1Based() {
        String line = "aaa,bbb,ccc";
        assertEquals(1, CsvParser.fieldIndexAt(line, ',', 0)); // start
        assertEquals(1, CsvParser.fieldIndexAt(line, ',', 3)); // on the first comma -> still field 1
        assertEquals(2, CsvParser.fieldIndexAt(line, ',', 4)); // just after -> field 2
        assertEquals(3, CsvParser.fieldIndexAt(line, ',', line.length())); // end -> field 3
        // Out-of-range caret columns are clamped.
        assertEquals(1, CsvParser.fieldIndexAt(line, ',', -5));
        assertEquals(3, CsvParser.fieldIndexAt(line, ',', 999));
    }

    @Test
    void fieldIndexIgnoresQuotedDelimiters() {
        String line = "\"x,y\",z";
        // A caret inside the quoted field (which contains a comma) is still field 1.
        assertEquals(1, CsvParser.fieldIndexAt(line, ',', 3));
        assertEquals(2, CsvParser.fieldIndexAt(line, ',', line.length()));
    }

    @Test
    void fieldStartOffsetLocatesFieldBoundaries() {
        String line = "aaa,bbb,ccc";
        assertEquals(0, CsvParser.fieldStartOffset(line, ',', 0));
        assertEquals(4, CsvParser.fieldStartOffset(line, ',', 1));
        assertEquals(8, CsvParser.fieldStartOffset(line, ',', 2));
        // Beyond the last field clamps to end of line.
        assertEquals(line.length(), CsvParser.fieldStartOffset(line, ',', 9));
        // Quoted delimiter is skipped: field 1 starts after the real comma.
        assertEquals(6, CsvParser.fieldStartOffset("\"x,y\",z", ',', 1));
    }

    @Test
    void parseHandlesQuotesEscapesAndEmbeddedNewlines() {
        List<List<String>> rows = CsvParser.parse("a,b\n\"c,d\",\"e\"\"f\"\n\"multi\nline\",z", ',');
        assertEquals(3, rows.size());
        assertEquals(List.of("a", "b"), rows.get(0));
        assertEquals(List.of("c,d", "e\"f"), rows.get(1)); // quoted comma + "" escape
        assertEquals(List.of("multi\nline", "z"), rows.get(2)); // newline inside quotes
    }

    @Test
    void parseAutoDetectsAndColumnCount() {
        List<List<String>> rows = CsvParser.parse("a;b;c\n1;2");
        assertEquals(2, rows.size());
        assertEquals(3, CsvParser.columnCount(rows)); // widest row wins
        assertEquals(0, CsvParser.columnCount(List.of()));
    }

    @Test
    void parseEmptyIsNoRows() {
        assertEquals(0, CsvParser.parse("", ',').size());
        assertEquals(0, CsvParser.parse(null, ',').size());
    }
}
