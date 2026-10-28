package com.editora.lsp;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What an XML document's prolog and root start tag say about its grammar (XML review 2 C7, C8): whether it is
 * already bound — a DOCTYPE with an external DTD, an {@code <?xml-model?>} instruction, or an
 * {@code xsi:noNamespaceSchemaLocation}/{@code xsi:schemaLocation} on the root — whether it has a DOCTYPE at all, and
 * where the root element's name is.
 *
 * <p>XML: Bind to Grammar… on a bound file had lemminx add a second binding — the root got {@code xmlns:xsi} and
 * {@code xsi:noNamespaceSchemaLocation} twice, which is not well-formed — while the status line said "Bound". Pure;
 * any thread.
 */
public final class XmlGrammarBinding {

    /** The XML Schema instance namespace, whose {@code schemaLocation} attributes bind a document to an XSD. */
    public static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    /** lemminx's diagnostic code for a document without a grammar, the one its generate-a-grammar fixes answer. */
    public static final String NO_GRAMMAR_CODE = "NoGrammarConstraints";

    /** How a document is bound ({@code DOCTYPE}, {@code xml-model}, or the attribute's name) and the grammar named. */
    public record Binding(String how, String grammar) {}

    /** The prolog read up to the root start tag. */
    private record Prolog(
            @Nullable String xmlModel,
            boolean doctype,
            @Nullable String doctypeSystemId,
            int rootStart,
            int rootNameEnd,
            int rootTagEnd) {}

    private static final Pattern ATTRIBUTE =
            Pattern.compile("([A-Za-z_][-A-Za-z0-9_.:]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /** A DOCTYPE's external id: {@code SYSTEM "sys"} or {@code PUBLIC "pub" "sys"}, quoted either way. */
    private static final Pattern EXTERNAL_ID =
            Pattern.compile("\\b(SYSTEM|PUBLIC)\\s+(?:\"([^\"]*)\"|'([^']*)')(?:\\s+(?:\"([^\"]*)\"|'([^']*)'))?");

    private XmlGrammarBinding() {}

    /** The document's existing grammar binding, or null when it has none. */
    public static @Nullable Binding existing(String text) {
        Prolog p = prolog(text);
        if (p.doctypeSystemId() != null) {
            return new Binding("DOCTYPE", p.doctypeSystemId());
        }
        if (p.xmlModel() != null) {
            return new Binding("xml-model", p.xmlModel());
        }
        if (p.rootStart() < 0) {
            return null;
        }
        String tag = text.substring(p.rootNameEnd(), p.rootTagEnd());
        Set<String> xsiPrefixes = new HashSet<>(Set.of("xsi"));
        Matcher m = ATTRIBUTE.matcher(tag);
        while (m.find()) {
            if (m.group(1).startsWith("xmlns:") && XSI.equals(value(m))) {
                xsiPrefixes.add(m.group(1).substring("xmlns:".length()));
            }
        }
        m.reset();
        while (m.find()) {
            String name = m.group(1);
            int colon = name.indexOf(':');
            if (colon <= 0 || !xsiPrefixes.contains(name.substring(0, colon))) {
                continue;
            }
            String local = name.substring(colon + 1);
            String v = value(m).trim();
            if (local.equals("noNamespaceSchemaLocation")) {
                return new Binding(name, v);
            }
            if (local.equals("schemaLocation")) {
                String[] pairs = v.split("\\s+"); // namespace, location, namespace, location…
                return new Binding(name, pairs.length > 1 ? pairs[1] : v);
            }
        }
        return null;
    }

    /** Whether the document has a DOCTYPE, bound to a DTD or with an internal subset only. */
    public static boolean hasDoctype(String text) {
        return prolog(text).doctype();
    }

    /** The root element's name as {start, end} offsets, or null when there is no root start tag. */
    public static int @Nullable [] rootName(String text) {
        Prolog p = prolog(text);
        return p.rootStart() < 0 ? null : new int[] {p.rootStart(), p.rootNameEnd()};
    }

    /** {@code offset} in {@code text} as a 0-based {line, UTF-16 column}, as LSP counts them. */
    public static int[] lineColumn(String text, int offset) {
        int line = 0;
        int lineStart = 0;
        for (int i = text.indexOf('\n'); i >= 0 && i < offset; i = text.indexOf('\n', i + 1)) {
            line++;
            lineStart = i + 1;
        }
        return new int[] {line, offset - lineStart};
    }

    /**
     * A Schematron rule set: rules checked on top of a grammar, not a grammar (XML/pom review 3, C27). Its
     * {@code xml-model} does not bind the document, so Bind to Grammar… and Generate Grammar… are still offered.
     */
    static boolean isSchematron(String href, String schematypens) {
        String ns = schematypens.trim();
        return ns.equals("http://purl.oclc.org/dsdl/schematron")
                || ns.equals("http://www.ascc.net/xml/schematron")
                || (ns.isEmpty() && href.toLowerCase(java.util.Locale.ROOT).endsWith(".sch"));
    }

    private static String value(Matcher m) {
        return m.group(2) != null ? m.group(2) : m.group(3);
    }

    private static Prolog prolog(String text) {
        String xmlModel = null;
        boolean doctype = false;
        String systemId = null;
        int n = text.length();
        int i = !text.isEmpty() && text.charAt(0) == '\uFEFF' ? 1 : 0;
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (text.startsWith("<!--", i)) {
                i = after(text, i + 4, "-->");
            } else if (text.startsWith("<?", i)) {
                int end = text.indexOf("?>", i + 2);
                String pi = text.substring(i + 2, end < 0 ? n : end);
                if (xmlModel == null
                        && pi.startsWith("xml-model")
                        && pi.length() > 9
                        && Character.isWhitespace(pi.charAt(9))) {
                    String href = null;
                    String typens = "";
                    Matcher m = ATTRIBUTE.matcher(pi);
                    while (m.find()) {
                        if (m.group(1).equals("href")) {
                            href = value(m);
                        } else if (m.group(1).equals("schematypens")) {
                            typens = value(m);
                        }
                    }
                    if (href != null && !isSchematron(href, typens)) {
                        xmlModel = href;
                    }
                }
                i = end < 0 ? n : end + 2;
            } else if (text.startsWith("<!DOCTYPE", i)) {
                doctype = true;
                int end = doctypeEnd(text, i + 9);
                systemId = systemId(text.substring(i + 9, end));
                i = end;
            } else if (c == '<' && i + 1 < n && isNameStart(text.charAt(i + 1))) {
                int nameEnd = i + 1;
                while (nameEnd < n && isNameChar(text.charAt(nameEnd))) {
                    nameEnd++;
                }
                return new Prolog(xmlModel, doctype, systemId, i + 1, nameEnd, tagEnd(text, nameEnd));
            } else {
                break; // text before the root, or something that is not XML
            }
        }
        return new Prolog(xmlModel, doctype, systemId, -1, -1, -1);
    }

    /** A DOCTYPE's external system id ({@code SYSTEM "…"} or {@code PUBLIC "…" "…"}), or null. */
    private static @Nullable String systemId(String declaration) {
        int subset = declaration.indexOf('[');
        String head = subset < 0 ? declaration : declaration.substring(0, subset);
        Matcher m = EXTERNAL_ID.matcher(head);
        if (!m.find()) {
            return null;
        }
        if (m.group(1).equals("SYSTEM")) {
            return m.group(2) != null ? m.group(2) : m.group(3);
        }
        String system = m.group(4) != null ? m.group(4) : m.group(5);
        return system != null ? system : (m.group(2) != null ? m.group(2) : m.group(3));
    }

    /** Just past the DOCTYPE's {@code >}: quoted strings and the internal subset skipped. */
    private static int doctypeEnd(String text, int from) {
        int n = text.length();
        int depth = 0;
        int i = from;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int close = text.indexOf(c, i + 1);
                i = close < 0 ? n : close + 1;
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == '>' && depth == 0) {
                return i + 1;
            }
            i++;
        }
        return n;
    }

    /** The offset of the start tag's {@code >} (or the end of the text), quoted values skipped. */
    private static int tagEnd(String text, int from) {
        int n = text.length();
        int i = from;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int close = text.indexOf(c, i + 1);
                i = close < 0 ? n : close + 1;
            } else if (c == '>') {
                return i;
            } else {
                i++;
            }
        }
        return n;
    }

    private static int after(String text, int from, String marker) {
        int at = text.indexOf(marker, from);
        return at < 0 ? text.length() : at + marker.length();
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == ':';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == ':';
    }
}
