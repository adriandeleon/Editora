package com.editora.text;

import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What Editora knows about HTML elements, in one table (HTML review E21): which are void, which hold raw text, whose
 * whitespace is significant, whose end tag may be left out, and which are phrasing content. Tag auto-close, rename and
 * join, the indenter, folding, the Structure outline, spell checking, the built-in formatter and the HTML-to-Markdown
 * paste all read it, where each used to carry its own drifting copy ({@code <title>} was raw text for rename but not
 * for the indenter, {@code <pre>} the other way round).
 *
 * <p>Names are compared lower-cased: HTML element names are case-insensitive. Pure; any thread.
 */
public final class HtmlElements {

    private HtmlElements() {}

    // spotless:off
    /**
     * Void elements: no content and no end tag. The current ones plus the obsolete ones browsers still parse as void
     * ({@code basefont}, {@code bgsound}, {@code command}, {@code frame}, {@code isindex}, {@code keygen}).
     */
    public static final Set<String> VOID = Set.of(
            "area", "base", "basefont", "bgsound", "br", "col", "command", "embed", "frame", "hr", "img", "input",
            "isindex", "keygen", "link", "meta", "param", "source", "track", "wbr");

    /**
     * Elements whose content is not markup: everything up to their own end tag is text (raw text: {@code script},
     * {@code style}, {@code xmp}; escapable raw text: {@code textarea}, {@code title}). A {@code <b>} inside a
     * {@code <script>} string opens nothing.
     */
    public static final Set<String> RAW_TEXT = Set.of("script", "style", "textarea", "title", "xmp");

    /**
     * Elements a formatter copies verbatim, end tag included: the raw-text ones whose layout matters
     * ({@code script}, {@code style}, {@code textarea}, {@code xmp}) and those whose whitespace is significant
     * ({@code pre}, {@code listing}, {@code plaintext}).
     */
    public static final Set<String> VERBATIM = Set.of(
            "pre", "listing", "plaintext", "textarea", "script", "style", "xmp");

    /** Elements whose end tag may be omitted: an open one does not stop its parent's end tag from closing it. */
    public static final Set<String> OPTIONAL_END = Set.of(
            "body", "caption", "colgroup", "dd", "dt", "head", "html", "li", "optgroup", "option", "p", "rb", "rp",
            "rt", "rtc", "tbody", "td", "tfoot", "th", "thead", "tr");

    /** Phrasing (inline) elements: they stay on the line of the text around them. */
    public static final Set<String> PHRASING = Set.of(
            "a", "abbr", "acronym", "b", "bdi", "bdo", "big", "br", "button", "cite", "code", "data", "del", "dfn",
            "em", "font", "i", "img", "input", "ins", "kbd", "label", "mark", "meter", "nobr", "output", "progress",
            "q", "rp", "rt", "ruby", "s", "samp", "small", "span", "strike", "strong", "sub", "sup", "time", "tt",
            "u", "var", "wbr");

    /** Phrasing elements that hold code or computer output rather than prose: spell check skips their content. */
    public static final Set<String> CODE_PHRASING = Set.of("code", "kbd", "samp", "var");

    /** Blocks whose start tag ends an open {@code <p>}. */
    private static final Set<String> CLOSES_P = Set.of(
            "address", "article", "aside", "blockquote", "details", "div", "dl", "fieldset", "figcaption", "figure",
            "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hgroup", "hr", "main", "menu", "nav",
            "ol", "p", "pre", "section", "table", "ul");
    // spotless:on

    private static final Set<String> P = Set.of("p");
    private static final Set<String> LI = Set.of("li", "p");
    private static final Set<String> DT_DD = Set.of("dt", "dd", "p");
    private static final Set<String> CELL = Set.of("td", "th", "p");
    private static final Set<String> ROW = Set.of("tr", "td", "th", "p");
    private static final Set<String> SECTION = Set.of("thead", "tbody", "tfoot", "tr", "td", "th", "p");
    private static final Set<String> OPTION = Set.of("option");
    private static final Set<String> OPTGROUP = Set.of("optgroup", "option");
    private static final Set<String> HEAD = Set.of("head");

    /**
     * The open elements a start tag {@code name} ends when one of them is innermost (an omitted end tag:
     * {@code <li>} after an open {@code <li>}), or null when it ends none.
     */
    public static @Nullable Set<String> impliedEnds(String name) {
        return switch (lower(name)) {
            case "li" -> LI;
            case "dt", "dd" -> DT_DD;
            case "td", "th" -> CELL;
            case "tr" -> ROW;
            case "thead", "tbody", "tfoot" -> SECTION;
            case "option" -> OPTION;
            case "optgroup" -> OPTGROUP;
            case "body" -> HEAD;
            default -> CLOSES_P.contains(lower(name)) ? P : null;
        };
    }

    public static boolean isVoid(String name) {
        return VOID.contains(lower(name));
    }

    public static boolean isRawText(String name) {
        return RAW_TEXT.contains(lower(name));
    }

    public static boolean isVerbatim(String name) {
        return VERBATIM.contains(lower(name));
    }

    public static boolean hasOptionalEnd(String name) {
        return OPTIONAL_END.contains(lower(name));
    }

    public static boolean isPhrasing(String name) {
        return PHRASING.contains(lower(name));
    }

    /** The HTML template language id: ERB, EJS, Nunjucks, Jinja, Twig, Liquid and Mustache files (HTML review C7). */
    public static final String TEMPLATE_LANGUAGE = "html-template";

    /** Handlebars templates, which the HTML language server also serves (under this language id). */
    public static final String HANDLEBARS_LANGUAGE = "handlebars";

    /**
     * Whether {@code language} is HTML for Editora's own tag features — auto-close, rename, folding, comments,
     * indentation, outline, spell check: plain HTML and the HTML template languages.
     */
    public static boolean isHtmlLanguage(@Nullable String language) {
        return "html".equals(language) || TEMPLATE_LANGUAGE.equals(language) || HANDLEBARS_LANGUAGE.equals(language);
    }

    /** Whether {@code language} is one of the template languages ({@link #isHtmlLanguage} but not plain HTML). */
    public static boolean isTemplateLanguage(@Nullable String language) {
        return TEMPLATE_LANGUAGE.equals(language) || HANDLEBARS_LANGUAGE.equals(language);
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
