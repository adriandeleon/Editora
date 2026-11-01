package com.editora.editops;

import java.nio.file.Path;
import java.util.Locale;

import com.editora.text.HtmlElements;
import org.jspecify.annotations.Nullable;

/**
 * Pure: Toggle Comment on the template lines of an HTML template (HTML review 2 C109). An HTML comment does not stop a
 * template engine: {@code <!-- {% include "x" %} -->} still includes, and {@code <!-- <% deleteAll() %> -->} still
 * runs. A line that holds a template construct ({@code {% %}}, {@code {{ }}}, <code>{# #}</code>, {@code <% %>}) is
 * commented in the engine's own syntax, picked by the file's extension or, for a {@code .html} page, by what the page
 * uses; a line with none keeps {@code <!-- -->}.
 *
 * <ul>
 *   <li>Jinja, Twig, Nunjucks ({@code .jinja .j2 .njk .twig}): <code>{# … #}</code>.
 *   <li>Django or Jinja in a {@code .html} page: <code>{# … #}</code> on each line, since Django's <code>{# #}</code>
 *       is one line only and Jinja has no {@code {% comment %}}.
 *   <li>Liquid ({@code .liquid}, a Jekyll page with front matter or in {@code _includes}, {@code _layouts}):
 *       {@code {% comment %} … {% endcomment %}}.
 *   <li>ERB, EJS ({@code .erb .ejs}): each tag on the lines becomes a comment tag, {@code <%= x %>} →
 *       {@code <%#= x %>}. Wrapping the line in {@code <%# … %>} would end at the line's first {@code %>}. A tag
 *       that already is a comment gets a second {@code #} ({@code <%## note %>}, still a comment), so uncommenting,
 *       which takes one {@code #} off every tag, gives the user's own comment back (HTML review 3 C203).
 *   <li>Handlebars ({@code .hbs .handlebars}): {@code {{!-- … --}}}.
 *   <li>Mustache ({@code .mustache}), whose {@code {{! … }}} ends at the first <code>}}</code> and has no
 *       {@code {{!-- --}}} form (HTML review 3 C202): each line is wrapped in {@code <!-- -->} and each tag on it
 *       becomes a comment tag, {@code {{name}}} → {@code {{!name}}}, {@code {{{raw}}}} → <code>{{!{raw}}</code>; a comment
 *       tag gets a second {@code !}. Uncommenting undoes both.
 *   <li>Go templates (Hugo) in a {@code .html} page: <code>{{/* … *&#47;}}</code>.
 * </ul>
 */
final class TemplateComments {

    private TemplateComments() {}

    /** A template engine's comment form. */
    enum Engine {
        JINJA("{#", "#}"),
        /** Django or Jinja, which a {@code .html} page does not tell apart: one comment per line. */
        DJANGO_OR_JINJA("{#", "#}"),
        LIQUID("{% comment %}", "{% endcomment %}"),
        ERB("<%#", "%>"),
        HANDLEBARS("{{!--", "--}}"),
        /** Mustache: each tag a comment tag and the line an HTML comment ({@link #toggleMustacheLines}). */
        MUSTACHE("<!--", "-->"),
        GO("{{/*", "*/}}");

        final String start;
        final String end;

        Engine(String start, String end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * The engine whose comments the selection {@code [a, effEnd]} of {@code text} takes, or null when the lines it
     * touches hold no template construct, are one HTML comment already, or the page's engine is unknown (a plain
     * {@code {{ value }}} of Angular or Vue): those take {@code <!-- -->}.
     */
    static @Nullable Engine engineFor(String text, int a, int effEnd, String language, @Nullable Path file) {
        if (!HtmlElements.isHtmlLanguage(language)) {
            return null;
        }
        String lines = text.substring(lineStart(text, a), lineEnd(text, effEnd));
        String core = lines.strip();
        Engine byName = byExtension(file);
        if (byName == Engine.MUSTACHE) {
            return lines.contains("{{") ? Engine.MUSTACHE : null; // its own uncomment takes the !s back off
        }
        if (!hasConstruct(lines) || core.startsWith("<!--") && core.endsWith("-->")) {
            return null;
        }
        Engine engine = byName != null ? byName : byContent(text, file);
        return engine == Engine.ERB && !lines.contains("<%") ? null : engine; // "{{ x }}" means nothing to ERB
    }

    /** Whether {@code s} holds a template construct. */
    static boolean hasConstruct(String s) {
        return s.contains("{%") || s.contains("{{") || s.contains("{#") && s.contains("#}") || s.contains("<%");
    }

    static @Nullable Engine byExtension(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        if (name == null) {
            return null;
        }
        String n = name.toString().toLowerCase(Locale.ROOT);
        String ext = n.substring(n.lastIndexOf('.') + 1);
        return switch (ext) {
            case "jinja", "jinja2", "j2", "njk", "twig" -> Engine.JINJA;
            case "erb", "ejs" -> Engine.ERB;
            case "hbs", "handlebars" -> Engine.HANDLEBARS;
            case "mustache" -> Engine.MUSTACHE;
            case "liquid" -> Engine.LIQUID;
            default -> null;
        };
    }

    private static final java.util.regex.Pattern GO_ACTION =
            java.util.regex.Pattern.compile("\\{\\{-?\\s*(?:/\\*|(?:if|else|end|range|with|define|block)\\b)");

    /** A <code>{#</code> that is not the <code>{{#</code> of a Handlebars section. */
    private static final java.util.regex.Pattern JINJA_COMMENT = java.util.regex.Pattern.compile("(?<!\\{)\\{#");

    private static final java.util.regex.Pattern MUSTACHE_SECTION =
            java.util.regex.Pattern.compile("\\{\\{~?\\s*(?:[#^!]|/(?!\\*)|else\\b)");

    /** The engine a page with no template extension uses, by its syntax (and, for Jekyll, its place). */
    static @Nullable Engine byContent(String text, @Nullable Path file) {
        if (text.contains("<%") && text.contains("%>")) {
            return Engine.ERB;
        }
        boolean tags = text.contains("{%") || JINJA_COMMENT.matcher(text).find();
        if (tags && (text.startsWith("---\n") || text.startsWith("---\r\n") || jekyllFolder(file))) {
            return Engine.LIQUID;
        }
        if (tags) {
            return Engine.DJANGO_OR_JINJA;
        }
        if (MUSTACHE_SECTION.matcher(text).find()) {
            return Engine.HANDLEBARS;
        }
        return GO_ACTION.matcher(text).find() ? Engine.GO : null;
    }

    private static boolean jekyllFolder(@Nullable Path file) {
        for (Path p = file == null ? null : file.getParent(); p != null; p = p.getParent()) {
            Path name = p.getFileName();
            if (name != null
                    && (name.toString().equals("_includes") || name.toString().equals("_layouts"))) {
                return true;
            }
        }
        return false;
    }

    /** The toggle edit for the selection {@code [selStart, selEnd]} in {@code engine}'s syntax, or null (would nest). */
    static Commenter.@Nullable Edit toggle(String text, int selStart, int selEnd, int effEnd, Engine engine) {
        int a = Math.min(selStart, selEnd);
        if (engine == Engine.ERB) {
            return toggleErbTags(text, a, effEnd, selStart == selEnd);
        }
        if (engine == Engine.MUSTACHE) {
            return toggleMustacheLines(text, a, effEnd, selStart == selEnd);
        }
        if (engine == Engine.DJANGO_OR_JINJA && lineStart(text, a) != lineStart(text, effEnd)) {
            return toggleEachLine(text, a, effEnd, engine);
        }
        return Commenter.toggle(text, selStart, selEnd, new Commenter.CommentStyle(null, engine.start, engine.end));
    }

    /** Each non-blank line of {@code [a, effEnd]} as a comment of its own, or each uncommented when all are. */
    private static Commenter.@Nullable Edit toggleEachLine(String text, int a, int effEnd, Engine engine) {
        int from = lineStart(text, a);
        int to = lineEnd(text, effEnd);
        String[] lines = text.substring(from, to).split("\n", -1);
        boolean uncomment = true;
        for (String line : lines) {
            String core = line.strip();
            if (!core.isEmpty()
                    && !(core.startsWith(engine.start)
                            && core.endsWith(engine.end)
                            && core.indexOf(engine.end) == core.length() - engine.end.length())) {
                uncomment = false;
            }
        }
        StringBuilder out = new StringBuilder(to - from + 8 * lines.length);
        for (int k = 0; k < lines.length; k++) {
            String line = lines[k];
            String core = line.strip();
            if (k > 0) {
                out.append('\n');
            }
            int lead = line.length() - line.stripLeading().length();
            if (core.isEmpty()) {
                out.append(line);
            } else if (uncomment) {
                String inner = core.substring(engine.start.length(), core.length() - engine.end.length());
                out.append(line, 0, lead).append(inner.strip()).append(line.substring(lead + core.length()));
            } else if (core.contains(engine.end)) {
                return null; // a comment already on the line: they do not nest
            } else {
                out.append(line, 0, lead)
                        .append(engine.start)
                        .append(' ')
                        .append(core)
                        .append(' ')
                        .append(engine.end)
                        .append(line.substring(lead + core.length()));
            }
        }
        String replacement = out.toString();
        return new Commenter.Edit(from, to, replacement, from, from + replacement.length());
    }

    /**
     * ERB and EJS: every tag on the lines of {@code [a, effEnd]} made a comment tag ({@code <%} → {@code <%#}, and a
     * comment tag {@code <%#} → {@code <%##}), or, when all already are comments, each one {@code #} shorter: the
     * user's own {@code <%# note %>} comes back as written. {@code <%%} is a literal {@code <%}, not a tag. Null when
     * the lines have no tag. A bare caret stays on its character; a selection selects the lines.
     */
    private static Commenter.@Nullable Edit toggleErbTags(String text, int a, int effEnd, boolean bare) {
        int from = lineStart(text, a);
        int to = lineEnd(text, effEnd);
        String lines = text.substring(from, to);
        boolean any = false;
        boolean allComments = true;
        for (int at = lines.indexOf("<%"); at >= 0; at = lines.indexOf("<%", at + 2)) {
            if (lines.startsWith("<%%", at)) {
                at++;
                continue;
            }
            any = true;
            allComments &= lines.startsWith("<%#", at);
        }
        if (!any) {
            return null;
        }
        StringBuilder out = new StringBuilder(lines.length() + 8);
        int copied = 0;
        for (int at = lines.indexOf("<%"); at >= 0; at = lines.indexOf("<%", at + 2)) {
            if (lines.startsWith("<%%", at)) {
                at++;
                continue;
            }
            out.append(lines, copied, at + 2);
            copied = at + 2;
            if (allComments) {
                copied++; // drop one '#': <%## note %> was the user's own comment
            } else {
                out.append('#');
            }
        }
        out.append(lines, copied, lines.length());
        return edit(from, to, lines, out.toString(), a, bare);
    }

    /**
     * Mustache: each non-blank line of {@code [a, effEnd]} wrapped in {@code <!-- -->} with each of its tags made a
     * comment tag ({@code {{x}}} → {@code {{!x}}}, {@code {{{x}}}} → <code>{{!{x}}</code>, {@code {{! c }}} →
     * {@code {{!! c }}}), or, when every non-blank line is such a comment, each unwrapped and its tags one {@code !}
     * shorter. A Mustache comment ends at its first <code>}}</code>, so it cannot hold a line with a tag. Null when a line
     * holds an HTML comment of its own (they do not nest).
     */
    private static Commenter.@Nullable Edit toggleMustacheLines(String text, int a, int effEnd, boolean bare) {
        int from = lineStart(text, a);
        int to = lineEnd(text, effEnd);
        String[] each = text.substring(from, to).split("\n", -1);
        boolean uncomment = true;
        for (String line : each) {
            String core = line.strip();
            if (!core.isEmpty() && !(core.startsWith("<!--") && core.endsWith("-->") && core.length() >= 7)) {
                uncomment = false;
            }
        }
        StringBuilder out = new StringBuilder(to - from + 16 * each.length);
        int caret = -1;
        for (int k = 0; k < each.length; k++) {
            String line = each[k];
            String core = line.strip();
            if (k > 0) {
                out.append('\n');
            }
            int lead = line.length() - line.stripLeading().length();
            out.append(line, 0, lead);
            int lineFrom = from + out.length() - lead; // where this line starts in the result, less its indent
            if (core.isEmpty()) {
                // a blank line stays as it is
            } else if (uncomment) {
                int open = core.startsWith("<!-- ") ? 5 : 4;
                int close = core.endsWith(" -->") && core.length() - 4 >= open ? 4 : 3;
                String inner = core.substring(open, core.length() - close);
                String plain = mustacheTags(inner, false);
                if (bare) {
                    int rel = Math.clamp(a - from - lead - open, 0, inner.length());
                    caret = lineFrom + lead + Commenter.caretAfter(inner, plain, rel);
                }
                out.append(plain);
            } else if (core.contains("<!--") || core.contains("-->")) {
                return null;
            } else {
                String commented = mustacheTags(core, true);
                if (bare) {
                    int rel = Math.clamp(a - from - lead, 0, core.length());
                    caret = lineFrom + lead + 5 + Commenter.caretAfter(core, commented, rel);
                }
                out.append("<!-- ").append(commented).append(" -->");
            }
            out.append(line, lead + core.length(), line.length());
        }
        String replacement = out.toString();
        if (bare && caret >= 0) {
            return new Commenter.Edit(from, to, replacement, caret, caret);
        }
        return new Commenter.Edit(from, to, replacement, from, from + replacement.length());
    }

    /**
     * {@code s} with each Mustache tag made a comment tag ({@code comment}) or, the other way, each comment tag one
     * {@code !} shorter — a <code>{{!{x}}</code> becoming the triple-brace {@code {{{x}}}} it stood for.
     */
    static String mustacheTags(String s, boolean comment) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        int copied = 0;
        for (int at = s.indexOf("{{"); at >= 0; at = s.indexOf("{{", copied)) {
            out.append(s, copied, at + 2);
            copied = at + 2;
            if (comment) {
                out.append('!');
                if (s.startsWith("{", copied)) { // {{{x}}} → {{!{x}}: the comment ends at the first }}
                    int close = s.indexOf("}}}", copied);
                    if (close >= 0) {
                        out.append(s, copied, close + 2);
                        copied = close + 3;
                    }
                }
            } else if (s.startsWith("!", copied)) {
                copied++;
                if (s.startsWith("{", copied)) { // {{!{x}} → {{{x}}}
                    int close = s.indexOf("}}", copied);
                    if (close >= 0) {
                        out.append(s, copied, close + 2).append('}');
                        copied = close + 2;
                    }
                }
            }
        }
        out.append(s, copied, s.length());
        return out.toString();
    }

    /** The edit replacing {@code [from, to)} ({@code before}) with {@code after}; a bare caret stays on its character. */
    private static Commenter.Edit edit(int from, int to, String before, String after, int a, boolean bare) {
        if (bare) {
            int caret = from + Commenter.caretAfter(before, after, a - from); // stays on its character
            return new Commenter.Edit(from, to, after, caret, caret);
        }
        return new Commenter.Edit(from, to, after, from, from + after.length());
    }

    private static int lineStart(String text, int offset) {
        int nl = text.lastIndexOf('\n', offset - 1);
        return nl < 0 ? 0 : nl + 1;
    }

    private static int lineEnd(String text, int offset) {
        int nl = text.indexOf('\n', offset);
        return nl < 0 ? text.length() : nl;
    }
}
