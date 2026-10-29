package com.editora.ui;

import com.editora.format.HtmlFormatter;
import org.jspecify.annotations.Nullable;

/**
 * Indents an HTML response body for the HTTP Client's response view, the way {@link HttpXmlIndent} does XML: one
 * block element per line, indented by nesting depth, with {@code <pre>}, {@code <script>}, comments and text copied
 * exactly. The formatting itself is {@link HtmlFormatter#indentResponse}, which Format Document shares (HTML review
 * E21); this keeps the response view's own size cap. The body is returned unchanged when it does not start with a
 * tag, when the server already indented it, when it is larger than {@link #MAX_CHARS}, or when a construct is left
 * open. Pure — tested by {@code HttpHtmlIndentTest}.
 */
final class HttpHtmlIndent {

    private HttpHtmlIndent() {}

    /** The largest body indented: the response view's own limit, which is also all {@link HttpXmlIndent} ever sees. */
    static final int MAX_CHARS = HttpClientPanel.MAX_BODY_CHARS;

    /** {@code html} with one block element per line, indented by depth; unchanged when that is not safe to do. */
    static String indent(@Nullable String html) {
        return HtmlFormatter.indentResponse(html, MAX_CHARS);
    }
}
