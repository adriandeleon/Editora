package com.editora.ui;

import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jspecify.annotations.Nullable;
import org.xml.sax.InputSource;

/**
 * Pure: a string value as text to read (IntelliJ's "View as JSON / XML / Text"). Each adapter shows a string its own
 * way, as measured: java-debug as {@code "…"} with the text as it is; debugpy and js-debug as a quoted literal of
 * their language, escapes and all ({@code '{"a": "x\\ny"}'}). {@link #content} undoes that; {@link #prettyJson} and
 * {@link #prettyXml} lay the text out, or say why they cannot.
 */
final class StringViews {

    private StringViews() {}

    /** Whether {@code shown} is a string value: quoted with {@code "} or {@code '} (or a backtick). */
    static boolean isString(@Nullable String shown) {
        if (shown == null || shown.length() < 2) {
            return false;
        }
        char q = shown.charAt(0);
        return (q == '"' || q == '\'' || q == '`') && shown.charAt(shown.length() - 1) == q;
    }

    /**
     * The text of string value {@code shown}, as the adapter of {@code language} shows it: its quotes taken off, and —
     * for every language but Java, whose adapter shows the text as it is — its escapes undone.
     */
    static String content(String shown, @Nullable String language) {
        if (!isString(shown)) {
            return shown == null ? "" : shown;
        }
        String inner = shown.substring(1, shown.length() - 1);
        return "java".equals(language) ? inner : unescape(inner);
    }

    /** {@code s} with the escapes of a Python or JavaScript literal undone: newlines, tabs, quotes, hex and Unicode ones. */
    static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case '0' -> out.append('\0');
                case 'x' -> i = hex(s, i, 2, out);
                case 'u' -> {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '{') {
                        int close = s.indexOf('}', i + 2);
                        i = close < 0 ? appendRaw(out, n, i) : codePoint(s.substring(i + 2, close), close, out, i);
                    } else {
                        i = hex(s, i, 4, out);
                    }
                }
                case 'U' -> i = hex(s, i, 8, out);
                default -> out.append(n); // \\ \' \" and anything else stands for itself
            }
        }
        return out.toString();
    }

    private static int appendRaw(StringBuilder out, char n, int i) {
        out.append('\\').append(n);
        return i;
    }

    /**
     * Appends the {@code digits}-digit hex escape whose letter is at {@code at}, returning the index of its last digit;
     * as written when it is not one.
     */
    private static int hex(String s, int at, int digits, StringBuilder out) {
        int end = at + 1 + digits;
        if (end <= s.length()) {
            String code = s.substring(at + 1, end);
            if (code.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
                out.appendCodePoint(Integer.parseInt(code, 16));
                return end - 1;
            }
        }
        out.append('\\').append(s.charAt(at));
        return at;
    }

    private static int codePoint(String hexDigits, int close, StringBuilder out, int at) {
        try {
            out.appendCodePoint(Integer.parseInt(hexDigits, 16));
            return close;
        } catch (IllegalArgumentException e) {
            out.append("\\u");
            return at;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** {@code text} as indented JSON; an {@link IllegalArgumentException} saying why when it is not JSON. */
    static String prettyJson(String text) {
        try {
            // The pretty printer ends lines the system's way (CRLF on Windows); a tab's text is LF.
            return JSON.writeValueAsString(JSON.readTree(text)).replace("\r\n", "\n") + "\n";
        } catch (Exception e) {
            throw new IllegalArgumentException(firstLine(e.getMessage()), e);
        }
    }

    /** {@code text} as indented XML; an {@link IllegalArgumentException} saying why when it is not XML. */
    static String prettyXml(String text) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // A value from the debuggee is untrusted: no DTDs, no external entities.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setExpandEntityReferences(false);
            dbf.setXIncludeAware(false);
            var builder = dbf.newDocumentBuilder();
            builder.setErrorHandler(null);
            var doc = builder.parse(new InputSource(new StringReader(text)));
            stripBlankText(doc.getDocumentElement());
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            t.setOutputProperty(
                    OutputKeys.OMIT_XML_DECLARATION, text.stripLeading().startsWith("<?xml") ? "no" : "yes");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString().replace("\r\n", "\n").strip() + "\n";
        } catch (Exception e) {
            throw new IllegalArgumentException(firstLine(e.getMessage()), e);
        }
    }

    /** Takes out whitespace-only text between elements, so the indenting does not double it. */
    private static void stripBlankText(org.w3c.dom.Node node) {
        org.w3c.dom.NodeList kids = node.getChildNodes();
        for (int i = kids.getLength() - 1; i >= 0; i--) {
            org.w3c.dom.Node kid = kids.item(i);
            if (kid.getNodeType() == org.w3c.dom.Node.TEXT_NODE
                    && kid.getTextContent().isBlank()) {
                node.removeChild(kid);
            } else if (kid.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                stripBlankText(kid);
            }
        }
    }

    private static String firstLine(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        int nl = message.indexOf('\n');
        return (nl < 0 ? message : message.substring(0, nl)).strip();
    }
}
