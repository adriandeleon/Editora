package com.editora.editor;

import java.util.Collection;

import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a {@code .http} file is coloured as. Variables used to be the plain text colour, bodies were never
 * coloured, the header rule also matched body lines, and file references, redirects, handler scripts and
 * directives looked like ordinary text or comments.
 */
class HttpSourceHighlightTest {

    private static final String FILE = String.join(
            "\n",
            "@host = http://127.0.0.1:8080",
            "",
            "### Login",
            "# @name login",
            "# plain remark",
            "POST {{host}}/login HTTP/1.1",
            "Content-Type: application/json",
            "",
            "{",
            "  # body remark",
            "  \"user\": \"{{user}}\",",
            "  \"n\": 42,",
            "  \"ok\": true",
            "}",
            "",
            "> {% client.global.set(\"t\", response.body.token); %}",
            ">> ./out.json",
            "",
            "### YAML body",
            "https://example.com/upload",
            "    ?mode=fast",
            "Content-Type: application/yaml",
            "",
            "name: value",
            "",
            "### File body",
            "PUT {{host}}/doc",
            "",
            "< ./body.json",
            "",
            "### Multipart",
            "POST {{host}}/form",
            "Content-Type: multipart/form-data; boundary=B",
            "",
            "--B",
            "Content-Disposition: form-data; name=\"f\"",
            "",
            "<?xml version=\"1.0\"?><doc attr=\"v\">x</doc>",
            "--B--",
            "");

    private static String style(String needle) {
        return style(needle, 0);
    }

    /** The style class at the first occurrence of {@code needle} after {@code from} ("" = unstyled). */
    private static String style(String needle, int from) {
        int at = FILE.indexOf(needle, from);
        assertTrue(at >= 0, "'" + needle + "' is in the text");
        StyleSpans<Collection<String>> spans =
                TextMateHighlighter.compute(FILE, GrammarRegistry.shared().forFileName("api.http"));
        int pos = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            if (at < pos + span.getLength()) {
                return String.join(" ", span.getStyle());
            }
            pos += span.getLength();
        }
        return "";
    }

    @Test
    void variablesHaveAColourOfTheirOwn() {
        assertEquals("escape", style("{{host}}/login"));
        assertEquals("escape", style("user}}"));
        assertEquals("link", style("/login"));
    }

    @Test
    void theRequestLineHeadersAndDirectives() {
        assertEquals("keyword", style("POST {{host}}/login"));
        assertEquals("constant", style("HTTP/1.1"));
        assertEquals("tag", style("Content-Type: application/json"));
        assertEquals("attribute", style("@name"));
        assertEquals("comment", style("# plain remark"));
        assertEquals("heading", style("Login"));
    }

    @Test
    void aJsonBodyIsColouredAndItsCommentsAreComments() {
        assertEquals("type", style("\"user\""));
        assertEquals("string", style("\"{{user}}"), "the opening quote of a JSON string value");
        assertEquals("number", style("42"));
        assertEquals("constant", style("true"));
        assertEquals("comment", style("# body remark"));
    }

    @Test
    void handlersRedirectsAndFileReferences() {
        assertEquals("operator", style(">> ./out.json"));
        assertEquals("string", style("./out.json"));
        assertEquals("operator", style("< ./body.json"));
        assertEquals("operator", style("> {%"));
    }

    @Test
    void aBodyLineIsNotAHeaderAndAMethodlessTargetIsAUrl() {
        assertEquals("", style("name: value"), "a YAML body line is body text, not a header");
        assertEquals("link", style("https://example.com/upload"));
        assertEquals("link", style("?mode=fast"));
        assertEquals("tag", style("Content-Type: application/yaml"));
    }

    @Test
    void multipartBoundariesAndAnXmlPart() {
        assertEquals("heading", style("--B\n"));
        assertEquals("tag", style("Content-Disposition"));
        assertEquals("tag", style("doc attr"));
        assertEquals("attribute", style("attr="));
    }

    @Test
    void aBrowserBoundaryWithLeadingDashesIsABoundary() {
        String text = "POST http://h/form\nContent-Type: multipart/form-data; boundary=----WebKitFormBoundaryAb12\n\n"
                + "------WebKitFormBoundaryAb12\nContent-Disposition: form-data; name=\"t\"\n\nhi\n"
                + "------WebKitFormBoundaryAb12--\n";
        StyleSpans<Collection<String>> spans =
                TextMateHighlighter.compute(text, GrammarRegistry.shared().forFileName("api.http"));
        int at = text.indexOf("------WebKit");
        int pos = 0;
        String style = "";
        for (StyleSpan<Collection<String>> span : spans) {
            if (at < pos + span.getLength()) {
                style = String.join(" ", span.getStyle());
                break;
            }
            pos += span.getLength();
        }
        assertEquals("heading", style);
    }

    @Test
    void anEscapedBraceIsNotAVariable() {
        String text = "GET http://h/x\nX-Tpl: \\{{name}} and {{real}}\n";
        StyleSpans<Collection<String>> spans =
                TextMateHighlighter.compute(text, GrammarRegistry.shared().forFileName("api.http"));
        java.util.function.IntFunction<String> at = i -> {
            int pos = 0;
            for (StyleSpan<Collection<String>> span : spans) {
                if (i < pos + span.getLength()) {
                    return String.join(" ", span.getStyle());
                }
                pos += span.getLength();
            }
            return "";
        };
        assertEquals("operator", at.apply(text.indexOf("\\{{")));
        assertTrue(!at.apply(text.indexOf("{{name")).equals("escape"), at.apply(text.indexOf("{{name")));
        assertTrue(!at.apply(text.indexOf("name}}")).equals("escape"));
        assertEquals("escape", at.apply(text.indexOf("{{real")));
    }
}
