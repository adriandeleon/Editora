package com.editora.editor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TabStopsTest {

    @Test
    void theRuleCarriesTheTabSize() {
        assertEquals(".paragraph-text { -fx-tab-size: 4; }", TabStops.css(4));
        assertEquals(".paragraph-text { -fx-tab-size: 2; }", TabStops.css(2));
    }

    @Test
    void anUnusableSizeIsClamped() {
        assertEquals(TabStops.css(1), TabStops.css(0));
        assertEquals(TabStops.css(1), TabStops.css(-3));
        assertEquals(TabStops.css(TabStops.MAX), TabStops.css(10_000));
    }

    @Test
    void theSheetIsADataUrlOfTheRule() {
        String sheet = TabStops.sheet(8);
        assertTrue(sheet.startsWith(TabStops.PREFIX));
        String decoded = new String(
                Base64.getDecoder().decode(sheet.substring(TabStops.PREFIX.length())), StandardCharsets.UTF_8);
        assertEquals(TabStops.css(8), decoded);
    }
}
