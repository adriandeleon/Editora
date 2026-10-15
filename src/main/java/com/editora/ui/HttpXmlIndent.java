package com.editora.ui;

/**
 * Indents an XML response body for the HTTP Client's response view, the way the view pretty-prints JSON. A
 * lexical pass, not a parser: nothing is resolved (no DTDs, no external entities — a response cannot make the
 * editor fetch anything), and text, attributes, comments, CDATA and processing instructions are kept exactly.
 * Only the whitespace <em>between</em> tags changes. A body that already spans lines is returned unchanged,
 * as is anything that does not look like XML. Pure — tested by {@code HttpXmlIndentTest}.
 */
final class HttpXmlIndent {

    private HttpXmlIndent() {}

    private static final String INDENT = "  ";

    /** {@code xml} with one element per line, indented by depth; unchanged when it is not a one-line XML body. */
    static String indent(String xml) {
        if (xml == null) {
            return "";
        }
        String s = xml.strip();
        if (!s.startsWith("<") || s.indexOf('\n') >= 0) {
            return xml; // not XML, or the server already formatted it
        }
        StringBuilder out = new StringBuilder(s.length() + s.length() / 4);
        int depth = 0;
        int i = 0;
        int n = s.length();
        boolean lastWasText = false;
        while (i < n) {
            if (s.charAt(i) != '<') {
                int lt = s.indexOf('<', i);
                int end = lt < 0 ? n : lt;
                String text = s.substring(i, end);
                if (!text.isBlank()) {
                    out.append(text.strip()); // text stays on the line of the tag that opened it
                    lastWasText = true;
                }
                i = end;
                continue;
            }
            int end = tagEnd(s, i);
            if (end < 0) {
                return xml; // an unterminated construct: leave the body alone
            }
            String tag = s.substring(i, end);
            boolean closing = tag.startsWith("</");
            boolean special = tag.startsWith("<?") || tag.startsWith("<!");
            boolean selfClosing = tag.endsWith("/>");
            if (closing) {
                depth = Math.max(0, depth - 1);
                if (!lastWasText) {
                    newline(out, depth);
                }
                out.append(tag);
                lastWasText = false;
            } else {
                newline(out, depth);
                out.append(tag);
                if (!special && !selfClosing) {
                    depth++;
                }
                lastWasText = false;
            }
            i = end;
        }
        return out.toString();
    }

    /** The index just past the construct starting at {@code at} ({@code <…>}, a comment, CDATA), or -1. */
    private static int tagEnd(String s, int at) {
        if (s.startsWith("<!--", at)) {
            int e = s.indexOf("-->", at + 4);
            return e < 0 ? -1 : e + 3;
        }
        if (s.startsWith("<![CDATA[", at)) {
            int e = s.indexOf("]]>", at + 9);
            return e < 0 ? -1 : e + 3;
        }
        char quote = 0;
        for (int k = at + 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '>') {
                return k + 1;
            }
        }
        return -1;
    }

    private static void newline(StringBuilder out, int depth) {
        if (!out.isEmpty()) {
            out.append('\n');
        }
        out.append(INDENT.repeat(depth));
    }
}
