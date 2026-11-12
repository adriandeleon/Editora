package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A string value as each adapter shows it (as measured), back to its text, and laid out as JSON or XML. */
class StringViewsTest {

    @Test
    void eachAdaptersStringComesBackToItsText() {
        // java-debug shows the text as it is: a backslash in it stays one.
        assertEquals("{\"a\": \"x\\ny\"}", StringViews.content("\"{\"a\": \"x\\ny\"}\"", "java"));
        // debugpy and js-debug show a literal of their language.
        assertEquals("{\"a\": \"x\\ny\"}", StringViews.content("'{\"a\": \"x\\\\ny\"}'", "python"));
        assertEquals("it's\n\té", StringViews.content("'it\\'s\\n\\t\\xe9'", "javascript"));
        assertEquals("é😀", StringViews.content("\"\\u00e9\\u{1F600}\"", "javascript"));
        assertEquals(
                "q \\x4",
                StringViews.unescape("\\q \\x4"),
                "an unknown escape is its letter; a short hex one as written");
        assertEquals("42", StringViews.content("42", "python"), "not a string");
        assertTrue(StringViews.isString("`tpl`"));
        assertFalse(StringViews.isString("'"));
        assertFalse(StringViews.isString("\"half'"));
    }

    @Test
    void jsonAndXmlAreLaidOutOrSayWhyNot() {
        assertEquals("{\n  \"a\" : [ 1, 2 ]\n}\n", StringViews.prettyJson("{\"a\":[1,2]}"));
        assertThrows(IllegalArgumentException.class, () -> StringViews.prettyJson("{a:"));
        String xml = StringViews.prettyXml("<a><b x=\"1\">t</b><c/></a>");
        assertEquals("<a>\n  <b x=\"1\">t</b>\n  <c/>\n</a>\n", xml.replace("\r\n", "\n"));
        assertThrows(IllegalArgumentException.class, () -> StringViews.prettyXml("<a>"));
        assertThrows(
                IllegalArgumentException.class,
                () -> StringViews.prettyXml("<!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><a>&e;</a>"),
                "no DTDs: a debuggee's value is untrusted");
    }
}
