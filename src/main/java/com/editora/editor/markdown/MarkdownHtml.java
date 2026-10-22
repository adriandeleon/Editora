package com.editora.editor.markdown;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The small, safe subset of raw HTML the Markdown preview understands, as pure tokenising and classification.
 *
 * <p>A GitHub README is rarely pure Markdown: {@code <kbd>Ctrl</kbd>}, {@code line<br>break} in a table cell,
 * a centred {@code <p align="center"><img …></p>} header, a {@code <details>} block. The preview used to show
 * every one of those as its source, in code pills and code blocks. Nothing here executes or styles anything:
 * a tag is either one of the few mapped to a native node by {@link MarkdownRenderer}, or it is dropped and its
 * text kept. Comments, {@code <script>} and {@code <style>} are dropped with their content.
 */
final class MarkdownHtml {

    private MarkdownHtml() {}

    /** One piece of an HTML fragment: a tag or a run of text. */
    sealed interface Token permits Tag, Chars {}

    /** {@code <name attr="v">}, {@code </name>} ({@code close}) — names and attribute names in lower case. */
    record Tag(String name, boolean close, Map<String, String> attrs) implements Token {
        @Nullable
        String attr(String key) {
            return attrs.get(key);
        }
    }

    /** Text between tags, entities decoded; whitespace as written (the renderer collapses it). */
    record Chars(String text) implements Token {}

    private static final Pattern TAG =
            Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9-]*)((?:\"[^\"]*\"|'[^']*'|[^'\">])*)>");
    private static final Pattern ATTR =
            Pattern.compile("([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s\"'=<>`]+))?");
    private static final Pattern COMMENT = Pattern.compile("<!--.*?(-->|$)", Pattern.DOTALL);
    private static final Pattern DROPPED =
            Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?(</\\1\\s*>|$)|<![A-Za-z][^>]*>|<\\?.*?\\?>");

    /** {@code html} without comments, {@code <script>}/{@code <style>} elements, doctypes and instructions. */
    static String strip(String html) {
        if (html == null) {
            return "";
        }
        return DROPPED.matcher(COMMENT.matcher(html).replaceAll("")).replaceAll("");
    }

    /** Splits {@code html} into tags and text, after {@link #strip}. A {@code <} that opens no tag is text. */
    static List<Token> tokens(String html) {
        String s = strip(html);
        List<Token> out = new ArrayList<>();
        Matcher m = TAG.matcher(s);
        int pos = 0;
        while (m.find()) {
            if (m.start() > pos) {
                out.add(new Chars(decode(s.substring(pos, m.start()))));
            }
            out.add(new Tag(m.group(2).toLowerCase(Locale.ROOT), !m.group(1).isEmpty(), attrs(m.group(3))));
            pos = m.end();
        }
        if (pos < s.length()) {
            out.add(new Chars(decode(s.substring(pos))));
        }
        return out;
    }

    /** The single tag {@code literal} is (an inline HTML node is exactly one), or null for a comment or other. */
    static @Nullable Tag tag(String literal) {
        List<Token> t = tokens(literal == null ? "" : literal.strip());
        return t.size() == 1 && t.get(0) instanceof Tag tag ? tag : null;
    }

    private static Map<String, String> attrs(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        Matcher m = ATTR.matcher(raw);
        while (m.find()) {
            String value = m.group(2);
            if (value == null) {
                value = "";
            } else if (value.length() >= 2 && (value.charAt(0) == '"' || value.charAt(0) == '\'')) {
                value = value.substring(1, value.length() - 1);
            }
            out.putIfAbsent(m.group(1).toLowerCase(Locale.ROOT), decode(value));
        }
        return out;
    }

    private static final Map<String, String> ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"),
            Map.entry("lt", "<"),
            Map.entry("gt", ">"),
            Map.entry("quot", "\""),
            Map.entry("apos", "'"),
            Map.entry("nbsp", "\u00A0"),
            Map.entry("copy", "\u00A9"),
            Map.entry("reg", "\u00AE"),
            Map.entry("trade", "\u2122"),
            Map.entry("hellip", "\u2026"),
            Map.entry("mdash", "\u2014"),
            Map.entry("ndash", "\u2013"),
            Map.entry("larr", "\u2190"),
            Map.entry("rarr", "\u2192"),
            Map.entry("times", "\u00D7"),
            Map.entry("middot", "\u00B7"),
            Map.entry("bull", "\u2022"));

    private static final Pattern ENTITY =
            Pattern.compile("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[A-Za-z][A-Za-z0-9]{1,15});");

    /** Decodes numeric and the common named character references; an unknown one stays as written. */
    static String decode(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder(s.length());
        while (m.find()) {
            String ref = m.group(1);
            String rep = null;
            if (ref.charAt(0) == '#') {
                boolean hex = ref.charAt(1) == 'x' || ref.charAt(1) == 'X';
                try {
                    int cp = Integer.parseInt(ref.substring(hex ? 2 : 1), hex ? 16 : 10);
                    if (Character.isValidCodePoint(cp) && cp != 0) {
                        rep = new String(Character.toChars(cp));
                    }
                } catch (NumberFormatException e) {
                    // left as written
                }
            } else {
                rep = ENTITIES.get(ref);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep == null ? m.group() : rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** The preview style class an inline formatting tag maps to, or null when the tag carries no style. */
    static @Nullable String styleOf(String tag) {
        return switch (tag) {
            case "b", "strong" -> "md-bold";
            case "i", "em", "cite", "var" -> "md-italic";
            case "s", "del", "strike" -> "md-strike";
            case "u", "ins" -> "md-ins";
            case "sub" -> "md-sub";
            case "sup" -> "md-sup";
            case "kbd" -> "md-kbd";
            case "code", "samp", "tt" -> "md-html-code";
            default -> null;
        };
    }

    /** Tags that start a new line of their own in a block of HTML. */
    static boolean isBlockTag(String tag) {
        return switch (tag) {
            case "p",
                    "div",
                    "center",
                    "section",
                    "article",
                    "header",
                    "footer",
                    "blockquote",
                    "ul",
                    "ol",
                    "li",
                    "table",
                    "tr",
                    "dl",
                    "dt",
                    "dd",
                    "summary",
                    "details",
                    "figure",
                    "figcaption",
                    "pre",
                    "hr",
                    "h1",
                    "h2",
                    "h3",
                    "h4",
                    "h5",
                    "h6" -> true;
            default -> false;
        };
    }

    /** 1–6 for {@code h1}…{@code h6}, else 0. */
    static int headingLevel(String tag) {
        return tag.length() == 2 && tag.charAt(0) == 'h' && tag.charAt(1) >= '1' && tag.charAt(1) <= '6'
                ? tag.charAt(1) - '0'
                : 0;
    }

    /** Whether a tag asks for centred content: {@code align="center"}, {@code <center>}, or the CSS spelling. */
    static boolean centered(Tag tag) {
        if (tag.name().equals("center")) {
            return true;
        }
        String align = tag.attr("align");
        if (align != null && align.strip().equalsIgnoreCase("center")) {
            return true;
        }
        String style = tag.attr("style");
        return style != null && style.toLowerCase(Locale.ROOT).replace(" ", "").contains("text-align:center");
    }

    /** How many {@code <details>} a block of HTML leaves open (negative: it closes more than it opens). */
    static int detailsDepth(String html) {
        int depth = 0;
        for (Token t : tokens(html)) {
            if (t instanceof Tag tag && tag.name().equals("details")) {
                depth += tag.close() ? -1 : 1;
            }
        }
        return depth;
    }

    /** A pixel length attribute ({@code width="60"}, {@code "60px"}), or -1 when absent or not a pixel count. */
    static double pixels(@Nullable String value) {
        if (value == null) {
            return -1;
        }
        String v = value.strip().toLowerCase(Locale.ROOT);
        if (v.endsWith("px")) {
            v = v.substring(0, v.length() - 2).strip();
        }
        try {
            double d = Double.parseDouble(v);
            return d > 0 && d < 20_000 ? d : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Collapses runs of whitespace to one space, as HTML does outside {@code <pre>}. */
    static String collapse(String text) {
        return text.replaceAll("[ \\t\\r\\n\\f]+", " ");
    }
}
