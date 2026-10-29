package com.editora.format;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Format Document for HTML without a language server (HTML review C17, E21). */
class HtmlFormatterTest {

    private static final String MESSY = """
            <html><head><title>messy</title>
            <style>body{margin:0;padding:0}.a{color:red}</style></head>
            <body><div><p>one</p><p>two <b>three</b></p>
            <ul><li>a</li><li>b</li></ul></div>
            <pre>
              keep   this
                 spacing
            </pre>
            <script>function f(){if(true){return 1}}</script>
            <textarea>  raw   text  </textarea>
            </body></html>
            """;

    @Test
    void aMessyPageGetsOneBlockPerLineAndKeepsItsPreformattedContent() {
        assertEquals("""
                <html>
                  <head>
                    <title>messy</title>
                    <style>body{margin:0;padding:0}.a{color:red}</style>
                  </head>
                  <body>
                    <div>
                      <p>one</p>
                      <p>two <b>three</b></p>
                      <ul>
                        <li>a</li>
                        <li>b</li>
                      </ul>
                    </div>
                    <pre>
                  keep   this
                     spacing
                </pre>
                    <script>function f(){if(true){return 1}}</script>
                    <textarea>  raw   text  </textarea>
                  </body>
                </html>
                """, HtmlFormatter.format(MESSY, "  "));
    }

    @Test
    void theAuthorsLineBreaksAndBlankLinesAreKept() {
        String hand = "<body>\n\n<p>\n  A paragraph written\n     on its own lines.\n</p>\n\n\n<p>Short</p>\n</body>\n";
        assertEquals(
                "<body>\n\n    <p>\n        A paragraph written\n        on its own lines.\n    </p>\n\n"
                        + "    <p>Short</p>\n</body>\n",
                HtmlFormatter.format(hand, "    "));
    }

    @Test
    void formattingTwiceChangesNothing() {
        String once = HtmlFormatter.format(MESSY, "  ");
        assertEquals(once, HtmlFormatter.format(once, "  "));
    }

    @Test
    void aConstructLeftOpenIsNotFormatted() {
        assertNull(HtmlFormatter.format("<div><script>var a = 1;", "  "));
        assertNull(HtmlFormatter.format("<div><!-- never closed", "  "));
        assertNull(HtmlFormatter.format("<p class=\"a", "  "));
    }

    @Test
    void theResponseViewStillLeavesAnIndentedBodyAlone() {
        String indented = "<html>\n  <body>x</body>\n</html>";
        assertTrue(indented == HtmlFormatter.indentResponse(indented, 1000));
        assertEquals("<div>\n  <p>x</p>\n</div>", HtmlFormatter.indentResponse("<div><p>x</p></div>", 1000));
    }
}
