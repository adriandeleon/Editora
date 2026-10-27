package com.editora.format;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Format Document for XML when no language server formats it (XML review C8): one element per line, indented by
 * depth with the buffer's own indent unit. Built-in, so XML can be pretty-printed without installing lemminx.
 *
 * <p>Conservative by design — it only moves whitespace that sits between markup:
 * <ul>
 *   <li>an element whose content is only elements, comments and processing instructions (and whitespace) has each
 *       child on its own line, one level deeper;</li>
 *   <li>an element with any text in it (a leaf such as {@code <to>Tove</to>}, or mixed content) is kept exactly as
 *       written, inner whitespace included, so no text value changes;</li>
 *   <li>tags, comments, the declaration and a DOCTYPE (internal subset included) are copied verbatim, attributes
 *       and line breaks inside them too; a CDATA section counts as text;</li>
 *   <li>an element marked {@code xml:space="preserve"} is kept exactly as written.</li>
 * </ul>
 * A document that does not nest properly is left alone ({@link #format} returns null) rather than guessed at.
 * Line breaks are {@code \n}: the editor holds bare {@code \n} and writes the file's own line ending on save, so
 * a CRLF file stays CRLF. Pure; any thread.
 */
public final class XmlFormatter {

    private XmlFormatter() {}

    /** One piece of the document: markup or the text between markup. */
    private record Token(Kind kind, int start, int end, String name) {}

    private enum Kind {
        OPEN,
        CLOSE,
        EMPTY, // a self-closing tag
        OTHER, // comment, PI, DOCTYPE
        TEXT // character data, CDATA sections included
    }

    /** An element (or, at the top, the document) and what it holds. */
    private static final class Element {
        final @Nullable Token open;

        @Nullable
        Token close;

        final List<Object> children = new ArrayList<>(); // Element or Token
        boolean hasText;

        Element(@Nullable Token open) {
            this.open = open;
        }
    }

    /**
     * {@code text} reformatted with {@code indent} per level, or {@code null} when it is not markup that nests
     * properly (an unclosed or mismatched tag, an unterminated comment), in which case nothing should change.
     */
    public static @Nullable String format(String text, String indent) {
        List<Token> tokens = tokenize(text);
        if (tokens == null) {
            return null;
        }
        Element document = new Element(null);
        List<Element> stack = new ArrayList<>();
        stack.add(document);
        for (Token t : tokens) {
            Element current = stack.get(stack.size() - 1);
            switch (t.kind()) {
                case OPEN -> {
                    Element e = new Element(t);
                    current.children.add(e);
                    stack.add(e);
                }
                case CLOSE -> {
                    if (current.open == null || !current.open.name().equals(t.name())) {
                        return null; // a stray or mismatched end tag
                    }
                    current.close = t;
                    stack.remove(stack.size() - 1);
                }
                case TEXT -> {
                    if (!isBlank(text, t.start(), t.end())) {
                        if (current == document) {
                            return null; // text outside the root element: not a document to reflow
                        }
                        current.hasText = true;
                        current.children.add(t);
                    }
                }
                default -> current.children.add(t);
            }
        }
        if (stack.size() != 1) {
            return null; // an element left open
        }
        StringBuilder out = new StringBuilder(text.length() + text.length() / 8);
        for (Object child : document.children) {
            emit(out, text, child, 0, indent);
        }
        if (text.endsWith("\n")) {
            out.append('\n');
        }
        return out.toString();
    }

    private static void emit(StringBuilder out, String text, Object node, int depth, String indent) {
        if (!out.isEmpty()) {
            out.append('\n');
        }
        out.append(indent.repeat(depth));
        if (node instanceof Token t) {
            out.append(text, t.start(), t.end());
            return;
        }
        Element e = (Element) node;
        Token open = e.open;
        Token close = e.close;
        if (open == null || close == null) {
            return;
        }
        if (e.hasText || e.children.isEmpty() || preservesSpace(text, open)) {
            out.append(text, open.start(), close.end()); // text content, or <a></a>: exactly as written
            return;
        }
        out.append(text, open.start(), open.end());
        for (Object child : e.children) {
            emit(out, text, child, depth + 1, indent);
        }
        out.append('\n').append(indent.repeat(depth)).append(text, close.start(), close.end());
    }

    /** Whether a start tag says its whitespace matters ({@code xml:space="preserve"}): kept as written. */
    private static boolean preservesSpace(String text, Token open) {
        String tag = text.substring(open.start(), open.end());
        return tag.contains("xml:space=\"preserve\"") || tag.contains("xml:space='preserve'");
    }

    /** The document as markup and text tokens, or null when a construct never ends. */
    private static @Nullable List<Token> tokenize(String s) {
        List<Token> tokens = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            if (s.charAt(i) != '<') {
                int lt = s.indexOf('<', i);
                int end = lt < 0 ? n : lt;
                tokens.add(new Token(Kind.TEXT, i, end, ""));
                i = end;
                continue;
            }
            int end = constructEnd(s, i);
            if (end < 0) {
                return null;
            }
            if (s.startsWith("<![CDATA[", i)) {
                tokens.add(new Token(Kind.TEXT, i, end, "")); // character data: its element's content is text
            } else if (s.startsWith("<!", i) || s.startsWith("<?", i)) {
                tokens.add(new Token(Kind.OTHER, i, end, ""));
            } else if (s.startsWith("</", i)) {
                tokens.add(new Token(Kind.CLOSE, i, end, name(s, i + 2, end)));
            } else {
                String name = name(s, i + 1, end);
                if (name.isEmpty()) {
                    return null; // a bare '<' in text: not well-formed
                }
                boolean empty = s.charAt(end - 2) == '/';
                tokens.add(new Token(empty ? Kind.EMPTY : Kind.OPEN, i, end, name));
            }
            i = end;
        }
        return tokens;
    }

    private static String name(String s, int from, int end) {
        int i = from;
        while (i < end) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '>' || c == '/') {
                break;
            }
            i++;
        }
        return s.substring(from, i);
    }

    /** The index just past the construct at {@code at} (quotes and a DOCTYPE's {@code [...]} respected), or -1. */
    private static int constructEnd(String s, int at) {
        if (s.startsWith("<!--", at)) {
            int e = s.indexOf("-->", at + 4);
            return e < 0 ? -1 : e + 3;
        }
        if (s.startsWith("<![CDATA[", at)) {
            int e = s.indexOf("]]>", at + 9);
            return e < 0 ? -1 : e + 3;
        }
        if (s.startsWith("<?", at)) {
            int e = s.indexOf("?>", at + 2);
            return e < 0 ? -1 : e + 2;
        }
        boolean declaration = s.startsWith("<!", at);
        char quote = 0;
        int brackets = 0;
        for (int k = at + 1; k < s.length(); k++) {
            char c = s.charAt(k);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (declaration && c == '[') {
                brackets++;
            } else if (declaration && c == ']') {
                brackets--;
            } else if (declaration && brackets > 0 && s.startsWith("<!--", k)) {
                int e = s.indexOf("-->", k + 4); // a comment in the internal subset may hold quotes or '>'
                if (e < 0) {
                    return -1;
                }
                k = e + 2;
            } else if (c == '>' && brackets <= 0) {
                return k + 1;
            } else if (c == '<' && !declaration) {
                return -1; // a tag that never closes
            }
        }
        return -1;
    }

    private static boolean isBlank(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!Character.isWhitespace(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
