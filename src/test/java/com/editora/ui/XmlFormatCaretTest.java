package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The built-in XML Format Document puts the caret back on the character it was on (XML review 2 C4). */
class XmlFormatCaretTest {

    private static final String BEFORE = "<a><b x=\"1\"/>\n<c>t</c></a>\n";

    @Test
    void theCaretStaysOnItsCharacterNotAtTheEndOfTheFormattedText() {
        String after = XmlFormatCoordinator.format(BEFORE, "  ");
        assertEquals("<a>\n  <b x=\"1\"/>\n  <c>t</c>\n</a>\n", after);
        int inC = BEFORE.indexOf("<c>") + 2; // between "<c" and ">"
        assertEquals(after.indexOf("<c>") + 2, XmlFormatCoordinator.caretAfter(BEFORE, after, inC));
        int afterB = BEFORE.indexOf("/>") + 2; // the end of the <b/> line
        assertEquals(after.indexOf("/>") + 2, XmlFormatCoordinator.caretAfter(BEFORE, after, afterB));
        assertEquals(0, XmlFormatCoordinator.caretAfter(BEFORE, after, 0));
        assertEquals(after.length(), XmlFormatCoordinator.caretAfter(BEFORE, after, BEFORE.length()));
    }

    @Test
    void aCaretInWhitespaceGoesToTheStartOfWhatFollows() {
        String before = "<a>\n<b/>\n</a>";
        String after = XmlFormatCoordinator.format(before, "    ");
        assertEquals("<a>\n    <b/>\n</a>", after);
        assertEquals(after.indexOf("<b/>"), XmlFormatCoordinator.caretAfter(before, after, before.indexOf("<b/>")));
    }
}
