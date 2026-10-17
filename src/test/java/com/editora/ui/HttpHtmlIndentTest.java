package com.editora.ui;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** HTML responses are indented for the view; nothing but whitespace between tags changes, and nothing throws (U13). */
class HttpHtmlIndentTest {

    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }

    @Test
    void aOneLineErrorPageGetsOneElementPerLine() {
        String body = "<!doctype html>\n<html><head><meta charset=\"utf-8\"><title>404 Not Found</title></head>"
                + "<body><h1>Not Found</h1><p>The requested URL was not found on this server.</p><hr>"
                + "<address>demo/1.0 Server</address></body></html>";
        assertEquals("""
                <!doctype html>
                <html>
                  <head>
                    <meta charset="utf-8">
                    <title>404 Not Found</title>
                  </head>
                  <body>
                    <h1>Not Found</h1>
                    <p>The requested URL was not found on this server.</p>
                    <hr>
                    <address>demo/1.0 Server</address>
                  </body>
                </html>""", HttpHtmlIndent.indent(body));
    }

    @Test
    void inlineRunsStayOnOneLineWithTheirSpaces() {
        assertEquals(
                "<div>\n  <p>Some <b>bold</b> and <a href=\"/x\">a <i>link</i></a>, then <br>more.</p>\n</div>",
                HttpHtmlIndent.indent(
                        "<div><p>Some <b>bold</b> and <a href=\"/x\">a <i>link</i></a>, then <br>more.</p></div>"));
        // The space between two inline elements is part of the text: kept.
        assertEquals("<p><b>a</b> <i>b</i></p>", HttpHtmlIndent.indent("<p><b>a</b> <i>b</i></p>"));
        // Text beside a nested block starts its own line.
        assertEquals(
                "<div>before\n  <div>inner</div>\n  after\n</div>",
                HttpHtmlIndent.indent("<div>before<div>inner</div>after</div>"));
    }

    @Test
    void preTextareaScriptStyleAndCommentsAreCopiedExactly() {
        String pre = "<pre>  line 1\n    <b>x</b>\n</pre>";
        String script = "<script>if (a < b && c > d) { x = \"</div>\"; }</script>";
        String style = "<style>p > a { color: red }</style>";
        String comment = "<!-- <div>  not   markup </div> -->";
        String textarea = "<textarea name=\"t\">  <p>raw</p>  </textarea>";
        String out = HttpHtmlIndent.indent(
                "<html><head>" + style + script + "</head><body>" + comment + pre + textarea + "</body></html>");
        assertTrue(out.contains("\n    " + style + "\n"), out);
        assertTrue(out.contains("\n    " + script + "\n"), out);
        assertTrue(out.contains("\n    " + comment + "\n"), out);
        assertTrue(out.contains("\n    " + pre + "\n"), out);
        assertTrue(out.contains("\n    " + textarea + "\n"), out);
        // "</scripts" and an end tag in another case do not end the element early.
        assertEquals("<SCRIPT>a='</scripts>'</Script >", HttpHtmlIndent.indent("<SCRIPT>a='</scripts>'</Script >"));
    }

    @Test
    void voidElementsAndDeclarationsOpenNoLevel() {
        assertEquals(
                "<!DOCTYPE html>\n<head>\n  <meta a=1>\n  <link rel=x>\n  <base href=/>\n</head>\n<body>\n"
                        + "  <p><img src=a.png> caption</p>\n  <hr>\n  <form><input name=q><input type=submit></form>\n"
                        + "  <svg>\n    <path d=\"M0\"/>\n  </svg>\n</body>",
                HttpHtmlIndent.indent("<!DOCTYPE html><head><meta a=1><link rel=x><base href=/></head><body>"
                        + "<p><img src=a.png> caption</p><hr><form><input name=q><input type=submit></form>"
                        + "<svg><path d=\"M0\"/></svg></body>"));
    }

    @Test
    void omittedEndTagsCloseThemselves() {
        assertEquals(
                "<ul>\n  <li>one\n  <li>two\n</ul>\n<p>a\n<p>b\n<div>c</div>",
                HttpHtmlIndent.indent("<ul><li>one<li>two</ul><p>a<p>b<div>c</div>"));
        assertEquals(
                "<table>\n  <tr>\n    <td>1\n    <td>2\n  <tr>\n    <td>3\n</table>",
                HttpHtmlIndent.indent("<table><tr><td>1<td>2<tr><td>3</table>"));
    }

    @Test
    void quotedAnglesInAttributesStayInsideTheTag() {
        assertEquals(
                "<div title=\"a > b\" data-x='<p>'>\n  <p>x</p>\n</div>",
                HttpHtmlIndent.indent("<div title=\"a > b\" data-x='<p>'><p>x</p></div>"));
    }

    @Test
    void strayAnglesAndEndTagsAreTolerated() {
        String out = HttpHtmlIndent.indent("<p>a < b and c<d</p></span></div><p>x</p>");
        assertEquals(squash("<p>a < b and c<d</p></span></div><p>x</p>"), squash(out));
        assertEquals("<p>1 <</p>", HttpHtmlIndent.indent("<p>1 <</p>"));
    }

    @Test
    void unsafeBodiesAreReturnedUnchanged() {
        assertEquals("", HttpHtmlIndent.indent(null));
        String text = "Not Found";
        assertSame(text, HttpHtmlIndent.indent(text));
        String indented = "<html>\n  <body>x</body>\n</html>";
        assertSame(indented, HttpHtmlIndent.indent(indented), "the server already formatted it");
        String clipped = "<html><body><p class=\"a";
        assertSame(clipped, HttpHtmlIndent.indent(clipped), "a tag cut in half: unchanged");
        String openScript = "<div><script>var a = 1;";
        assertSame(openScript, HttpHtmlIndent.indent(openScript), "a script with no end: unchanged");
        String openComment = "<div><!-- never closed <p>x</p>";
        assertSame(openComment, HttpHtmlIndent.indent(openComment));
        String huge = "<p>" + "x".repeat(HttpHtmlIndent.MAX_CHARS) + "</p>";
        assertSame(huge, HttpHtmlIndent.indent(huge), "larger than the cap: unchanged");
        String deep = "<div>".repeat(20_000);
        assertSame(deep, HttpHtmlIndent.indent(deep), "indentation that would dwarf the body: unchanged");
    }

    @Test
    void anUnindentedMultiLinePageIsFormattedToo() {
        String apache = "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\n<html><head>\n<title>404</title>\n"
                + "</head><body>\n<h1>Not Found</h1>\n</body></html>\n";
        assertEquals(
                "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\n<html>\n  <head>\n    <title>404</title>\n"
                        + "  </head>\n  <body>\n    <h1>Not Found</h1>\n  </body>\n</html>",
                HttpHtmlIndent.indent(apache));
    }

    @Test
    void randomMarkupNeverThrowsAndOnlyLosesWhitespace() {
        String[] parts = {
            "<div>",
            "</div>",
            "<p>",
            "</p>",
            "<b>",
            "</b>",
            "<br>",
            "<li>",
            "<ul>",
            "</ul>",
            "<td>",
            "<tr>",
            "<pre>",
            "</pre>",
            "<script>",
            "</script>",
            "<style>",
            "</style>",
            "<!--",
            "-->",
            "<!doctype html>",
            "<",
            ">",
            "/>",
            "</",
            "\"",
            "'",
            "=",
            " ",
            "\n",
            "\t",
            "text",
            "a < b",
            "<a href=\"x>y\">",
            "</a>",
            "<img src=x>",
            "<?x?>",
            "<![CDATA[",
            "]]>",
            "<textarea>",
            "</textarea>",
            "é",
            "😀",
            "<plaintext>",
            "</SPAN>",
            "<Span>"
        };
        Random random = new Random(20261016);
        for (int round = 0; round < 3000; round++) {
            StringBuilder in = new StringBuilder("<");
            int n = 1 + random.nextInt(40);
            for (int k = 0; k < n; k++) {
                in.append(parts[random.nextInt(parts.length)]);
            }
            String body = in.toString();
            String out = HttpHtmlIndent.indent(body);
            assertEquals(squash(body), squash(out), () -> "lost characters from " + body);
        }
    }
}
