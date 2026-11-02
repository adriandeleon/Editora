package com.editora.text;

import java.util.Locale;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * What Editora knows about HTML elements, in one table (HTML review E21): which are void, which hold raw text, whose
 * whitespace is significant, whose end tag may be left out, and which a formatter keeps inline. Tag auto-close,
 * rename and join, the indenter, folding, the Structure outline, spell checking, the built-in formatter and the
 * HTML-to-Markdown paste all read it, where each used to carry its own drifting copy ({@code <title>} was raw text for
 * rename but not for the indenter, {@code <pre>} the other way round).
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

    /**
     * Elements a formatter puts on lines of their own; every other element — phrasing content, {@code <select>},
     * {@code <textarea>}, {@code <svg>}, {@code <video>}, a custom element — stays on the line of the text around it,
     * where a line break would show as a space (HTML review 2 E106, as js-beautify's {@code inline} list and
     * {@code inline_custom_elements}). {@code <script>} is here but stays inline when it follows text on its line.
     */
    public static final Set<String> BLOCK = Set.of(
            "address", "article", "aside", "base", "blockquote", "body", "caption", "center", "col", "colgroup", "dd",
            "details", "dialog", "dir", "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form",
            "frame", "frameset", "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup", "hr", "html",
            "legend", "li", "link", "listing", "main", "menu", "meta", "nav", "noframes", "ol", "optgroup", "option",
            "p", "plaintext", "pre", "script", "search", "section", "style", "summary", "table", "tbody", "td",
            "template", "tfoot", "th", "thead", "title", "tr", "ul", "xmp");

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

    /** Whether a formatter keeps {@code name} on the line of the text around it: not one of the {@link #BLOCK}s. */
    public static boolean isInline(String name) {
        return !BLOCK.contains(lower(name));
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

    /**
     * Elements the HTML parser treats as void although they are not in {@link #VOID}: {@code <image>}, which it reads
     * as {@code <img>} (HTML review 2 C103). Inside {@code <svg>} it is SVG's own element, which may have content.
     */
    public static boolean isVoidInHtml(String name) {
        return isVoid(name) || "image".equals(lower(name));
    }

    /**
     * Whether {@code text} holds template-engine syntax that can leave its HTML tags unbalanced as written (HTML review
     * 2 C101, C108): a Django/Jinja/Twig/Liquid tag {@code {% … %}} or comment <code>{# … #}</code>, an ERB/EJS tag
     * {@code <% … %>}, or a {@code {{ … }}} in a block form — Handlebars/Mustache {@code {{#if}}}, {@code {{/if}}},
     * {@code {{^x}}}, {@code {{! … }}}, or Go/Hugo {@code {{ if }}}, {@code {{ else }}}, {@code {{ end }}}. A plain
     * {@code {{ value }}} (Angular, Vue) is only a value and does not count. The markup check does not look for
     * unclosed elements in such text, and the built-in formatter leaves it alone.
     *
     * <p>Syntax a page only shows or holds as data does not count (HTML review 3 C207): the content of comments,
     * scripts (an Underscore {@code <script type="text/template">} too), styles, {@code <pre>}, {@code <code>} and
     * {@code <textarea>}, as on a page that documents Jinja.
     */
    public static boolean hasTemplateSyntax(CharSequence text) {
        String s = text.toString();
        return syntaxIn(s) && syntaxIn(withoutShownCode(s));
    }

    /** Elements whose content is shown or kept as written, not run by a template engine on the page. */
    private static final Set<String> SHOWN_CODE = Set.of("pre", "code", "textarea", "xmp", "listing", "plaintext");

    /**
     * {@code s} with the content of its comments, scripts, styles and {@link #SHOWN_CODE} elements blanked out
     * (line breaks kept). The same length as {@code s}.
     */
    static String withoutShownCode(String s) {
        char[] out = s.toCharArray();
        for (HtmlRegions.Region r : HtmlRegions.scan(s, false, s.length(), SHOWN_CODE, Set.of())) {
            for (int i = r.start(); i < r.end(); i++) {
                if (out[i] != '\n') {
                    out[i] = ' ';
                }
            }
        }
        return new String(out);
    }

    private static boolean syntaxIn(String s) {
        return paired(s, "{%", "%}", false)
                || paired(s, "{#", "#}", true) // on one line: a JavaScript class body "{#field" is not one
                || paired(s, "<%", "%>", false)
                || s.contains("{{") && MUSTACHE_BLOCK.matcher(s).find();
    }

    private static final java.util.regex.Pattern MUSTACHE_BLOCK = java.util.regex.Pattern.compile(
            "\\{\\{~?-?\\s*(?:[#/^!]|(?:else|end|if|range|with|define|block|unless|each)\\b)");

    /**
     * Angular template syntax (HTML review 3 C201): a control-flow block (<code>@if (…) {</code>, <code>@for (…) {</code>,
     * <code>@switch (…) {</code>, <code>@defer {</code>), a structural directive ({@code *ngIf=}) or a property, event or two-way
     * binding ({@code [value]="…"}, {@code (click)="…"}, {@code [(ngModel)]="…"}) in a start tag.
     */
    private static final java.util.regex.Pattern ANGULAR = java.util.regex.Pattern.compile(
            "@(?:if|for|switch)\\s*\\([^\\n]*\\)\\s*\\{|@defer\\b[^\\n]*\\{|\\s\\*ng[A-Z]\\w*\\s*="
                    + "|\\s(?:\\[\\(?[A-Za-z][\\w.:-]*\\)?]|\\([A-Za-z][\\w.:-]*\\))\\s*=\\s*[\"']");

    /**
     * Whether {@code text} uses Angular template syntax outside its comments, scripts, styles and shown code (see
     * {@link #ANGULAR}): an Angular component template, which the Angular compiler reads, not the browser.
     */
    public static boolean hasAngularSyntax(CharSequence text) {
        String s = text.toString();
        return ANGULAR.matcher(s).find() && ANGULAR.matcher(withoutShownCode(s)).find();
    }

    /** Whether {@code open} occurs with a {@code close} after it (on the same line when {@code sameLine}). */
    private static boolean paired(String s, String open, String close, boolean sameLine) {
        for (int at = s.indexOf(open); at >= 0; at = s.indexOf(open, at + open.length())) {
            int end = s.indexOf(close, at + open.length());
            if (end < 0) {
                return false;
            }
            int nl = s.indexOf('\n', at);
            if (!sameLine || nl < 0 || nl > end) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
