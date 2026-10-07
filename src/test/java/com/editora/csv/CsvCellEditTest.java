package com.editora.csv;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class CsvCellEditTest {

    @Test
    void lineSpanFindsALineWithoutItsBreak() {
        String text = "a,b\n1,2\n\nlast";
        assertArrayEquals(new int[] {0, 3}, CsvCellEdit.lineSpan(text, 0));
        assertArrayEquals(new int[] {4, 7}, CsvCellEdit.lineSpan(text, 1));
        assertArrayEquals(new int[] {8, 8}, CsvCellEdit.lineSpan(text, 2));
        assertArrayEquals(new int[] {9, 13}, CsvCellEdit.lineSpan(text, 3));
        assertNull(CsvCellEdit.lineSpan(text, 4));
        assertNull(CsvCellEdit.lineSpan(text, -1));
        assertNull(CsvCellEdit.lineSpan(null, 0));
        assertEquals("1,2", CsvCellEdit.lineText(text, 1));
        assertEquals("", CsvCellEdit.lineText("a\n", 1), "the empty line after a final break");
        assertNull(CsvCellEdit.lineText("a\n", 2));
    }

    @Test
    void onlyTheEditedFieldChanges() {
        assertEquals("9,\"007\",\"\"", CsvCellEdit.replaceField("1,\"007\",\"\"", ',', 0, "9"));
        assertEquals("8, \"x\" ,\"a\"b", CsvCellEdit.replaceField("2, \"x\" ,\"a\"b", ',', 0, "8"));
        assertEquals("1,\"007\",note", CsvCellEdit.replaceField("1,\"007\",\"\"", ',', 2, "note"));
        assertEquals("1,8,\"\"", CsvCellEdit.replaceField("1,\"007\",\"\"", ',', 1, "8"));
        assertEquals("a\tB\tc", CsvCellEdit.replaceField("a\tb\tc", '\t', 1, "B"));
    }

    @Test
    void theNewValueIsQuotedOnlyWhenItNeedsToBe() {
        assertEquals("1,\"x,y\",3", CsvCellEdit.replaceField("1,2,3", ',', 1, "x,y"));
        assertEquals("1,\"say \"\"hi\"\"\",3", CsvCellEdit.replaceField("1,2,3", ',', 1, "say \"hi\""));
        assertEquals("1,,3", CsvCellEdit.replaceField("1,2,3", ',', 1, ""));
        assertEquals("1,,3", CsvCellEdit.replaceField("1,2,3", ',', 1, null));
    }

    @Test
    void delimitersAndEscapedQuotesInsideQuotedFieldsAreNotBoundaries() {
        assertEquals("\"a,b\",X,c", CsvCellEdit.replaceField("\"a,b\",2,c", ',', 1, "X"));
        assertEquals("\"a\"\",b\",X", CsvCellEdit.replaceField("\"a\"\",b\",2", ',', 1, "X"));
        assertEquals("X,2", CsvCellEdit.replaceField("\"a\"\",b\",2", ',', 0, "X"));
        // A quote that does not open the field is literal text, exactly as the parser reads it.
        assertEquals("ab\"c,X,z", CsvCellEdit.replaceField("ab\"c,y,z", ',', 1, "X"));
    }

    @Test
    void anUnchangedValueReturnsTheSameLineSoOptionalQuotesSurvive() {
        String line = "1,\"007\",x";
        assertSame(line, CsvCellEdit.replaceField(line, ',', 1, "007"));
        assertSame(line, CsvCellEdit.replaceField(line, ',', -1, "x"));
    }

    @Test
    void aFieldPastTheEndOfAShortRowIsAppended() {
        assertEquals("1,2,,X", CsvCellEdit.replaceField("1,2", ',', 3, "X"));
        assertEquals("1,2,X", CsvCellEdit.replaceField("1,2", ',', 2, "X"));
        assertEquals("X", CsvCellEdit.replaceField("", ',', 0, "X"));
        assertEquals(",,X", CsvCellEdit.replaceField("", ',', 2, "X"));
        assertEquals("1,X", CsvCellEdit.replaceField("1,", ',', 1, "X"));
    }

    @Test
    void theSpliceAgreesWithTheParserOnEveryField() {
        String[] lines = {"a,b,c", "\"a,b\",\"c\"\"d\",e", " \"x\" ,y", "a\"b,c", ",,", "\"\",\"\",z", "x"};
        for (String line : lines) {
            var before = CsvParser.parse(line, ',').get(0);
            for (int field = 0; field < before.size(); field++) {
                var after = CsvParser.parse(CsvCellEdit.replaceField(line, ',', field, "NEW"), ',')
                        .get(0);
                assertEquals(before.size(), after.size(), line);
                for (int i = 0; i < before.size(); i++) {
                    assertEquals(i == field ? "NEW" : before.get(i), after.get(i), line + " field " + field);
                }
            }
        }
    }
}
