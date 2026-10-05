package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DoubleClickWordTest {

    private static String word(String line, int column, boolean prose, boolean hyphenated) {
        int[] span = DoubleClickWord.at(line, column, prose, hyphenated);
        return span == null ? null : line.substring(span[0], span[1]);
    }

    private static String prose(String line, String clickedOn) {
        return word(line, line.indexOf(clickedOn) + 1, true, false);
    }

    private static String code(String line, String clickedOn) {
        return word(line, line.indexOf(clickedOn) + 1, false, false);
    }

    @Test
    void anApostropheBetweenLettersIsPartOfAProseWord() {
        assertEquals("don't", prose("we don't stop", "don"));
        assertEquals("don't", word("we don't stop", 7, true, false)); // on the t
        assertEquals("l’été", prose("c'est l’été ici", "ét"));
    }

    @Test
    void quotesAroundAWordAreNotPartOfIt() {
        assertEquals("quoted", prose("a 'quoted' word", "quoted"));
        assertEquals("dogs", prose("the dogs' bowls", "dogs"));
    }

    @Test
    void anApostropheIsAQuoteInCode() {
        assertEquals("a", word("x = 'a'+'b'", 5, false, false));
        assertEquals("don", code("// don't", "don"));
    }

    @Test
    void aDecimalPointBetweenDigitsIsPartOfTheNumber() {
        assertEquals("3.14", code("pi = 3.14;", "14"));
        assertEquals("3.14", prose("about 3.14 of them", "3"));
        assertEquals("1.2.3", prose("version 1.2.3 is out", "2"));
    }

    @Test
    void aMemberAccessIsNotANumber() {
        assertEquals("get", code("list.get(0)", "get"));
        assertEquals("size", code("a1.size", "size"));
        assertEquals("x2", code("p.x2.y", "x2"));
        assertEquals("5", word("row.5", 4, false, false));
    }

    @Test
    void aHyphenJoinsOnlyWhereIdentifiersHaveHyphens() {
        String css = "  margin-top: 4px;";
        assertEquals("margin-top", word(css, css.indexOf("top") + 1, false, true));
        assertEquals("top", word(css, css.indexOf("top") + 1, false, false));
        assertEquals("b", word("a - b", 4, false, true));
    }

    @Test
    void theBaseRuleIsUnchanged() {
        assertEquals("item_count", code("list.add(item_count);", "tem"));
        assertEquals("café", prose("un café noir", "af"));
        assertEquals("beta", code("alpha  beta", " beta")); // on whitespace: the next word, as before
        assertEquals("alpha", code("alpha beta", "alpha"));
        assertNull(word("alpha  ", 6, false, false));
        assertNull(word("", 0, true, true));
    }

    @Test
    void theColumnAtAWordsEndBelongsToTheNextWord() {
        // The component's rule: a word is taken when it ends *after* the column.
        assertEquals("beta", word("alpha beta", 5, false, false));
        assertEquals("stop", word("don't stop", 5, true, false));
    }
}
