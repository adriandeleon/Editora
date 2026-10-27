package com.editora.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The built-in XML formatter Format Document falls back to without a server (XML review C8). */
class XmlFormatterTest {

    @Test
    void aOneLineDocumentGetsOneElementPerLineInTheGivenIndent() {
        String in = "<?xml version=\"1.0\"?><note id=\"n1\"><to>Tove</to><from>Jani</from><body/></note>\n";
        assertEquals("""
                <?xml version="1.0"?>
                <note id="n1">
                \t<to>Tove</to>
                \t<from>Jani</from>
                \t<body/>
                </note>
                """, XmlFormatter.format(in, "\t"));
        assertEquals("<a>\n    <b>\n        <c/>\n    </b>\n</a>", XmlFormatter.format("<a><b><c/></b></a>", "    "));
    }

    @Test
    void reindentingIsStableAndKeepsCommentsAndTheProlog() {
        String in = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- top -->
                <!DOCTYPE config [
                  <!ENTITY corp "Acme > Co">
                  <!-- a ] in a comment -->
                ]>
                <config>
                        <!-- inner -->
                  <feature
                      name="x"
                      on="true"/>
                </config>
                """;
        String out = XmlFormatter.format(in, "  ");
        assertEquals("""
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- top -->
                <!DOCTYPE config [
                  <!ENTITY corp "Acme > Co">
                  <!-- a ] in a comment -->
                ]>
                <config>
                  <!-- inner -->
                  <feature
                      name="x"
                      on="true"/>
                </config>
                """, out);
        assertEquals(out, XmlFormatter.format(out, "  "), "formatting twice changes nothing");
    }

    @Test
    void textIsNeverTouched() {
        String mixed = "<p>Hello <b>world</b>  again\n   and more</p>";
        assertEquals(mixed, XmlFormatter.format(mixed, "  "), "mixed content is kept as written");
        assertEquals(
                "<r>\n  <pre xml:space=\"preserve\">\n   <x/>\n</pre>\n  <c><![CDATA[ <a>  b ]]></c>\n</r>",
                XmlFormatter.format(
                        "<r><pre xml:space=\"preserve\">\n   <x/>\n</pre><c><![CDATA[ <a>  b ]]></c></r>", "  "));
        assertEquals("<a> </a>", XmlFormatter.format("<a> </a>", "  "), "whitespace-only content stays");
    }

    @Test
    void blankLinesAreKeptAtMostTwoInARow() {
        // XML review 2 C3: every blank line was deleted, so a hand-grouped config lost its groups.
        String in =
                "<Configuration>\n\n<Appenders>\n<A/>\n\n\n\n<B/>\n</Appenders>\n\n<Loggers/>\n\n</Configuration>\n";
        String out = XmlFormatter.format(in, "  ");
        assertEquals("""
                <Configuration>

                  <Appenders>
                    <A/>


                    <B/>
                  </Appenders>

                  <Loggers/>

                </Configuration>
                """, out);
        assertEquals(out, XmlFormatter.format(out, "  "), "stable");
    }

    @Test
    void aCommentOnTheLineOfWhatItFollowsStaysThere() {
        // XML review 2 C3: a trailing comment moved onto its own line, above the next element it does not describe.
        String in =
                "<Loggers>\n<Logger level=\"debug\"/> <!-- our code -->\n<Root/><!--x-->\n<!-- own line -->\n</Loggers>";
        assertEquals("""
                <Loggers>
                  <Logger level="debug"/> <!-- our code -->
                  <Root/><!--x-->
                  <!-- own line -->
                </Loggers>""", XmlFormatter.format(in, "  "));
        assertEquals("<a> <!-- c -->\n  <b/>\n</a>", XmlFormatter.format("<a> <!-- c --><b/></a>", "  "));
    }

    @Test
    void markupThatDoesNotNestIsLeftAlone() {
        assertNull(XmlFormatter.format("<a><b></a>", "  "), "mismatched");
        assertNull(XmlFormatter.format("<a><b>", "  "), "unclosed");
        assertNull(XmlFormatter.format("<a></a></b>", "  "), "stray end tag");
        assertNull(XmlFormatter.format("<a><!-- open", "  "), "unterminated comment");
        assertNull(XmlFormatter.format("<a x=\"1></a>", "  "), "unterminated attribute");
        assertNull(XmlFormatter.format("text <a/>", "  "), "text outside the root");
        assertNull(XmlFormatter.format("<a>1 < 2</a>", "  "), "a bare '<'");
    }
}
