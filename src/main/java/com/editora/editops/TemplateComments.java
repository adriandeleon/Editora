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
 *       {@code <%#= x %>}. Wrapping the line in {@code <%# … %>} would end at the line's first {@code %>}.
 *   <li>Handlebars, Mustache ({@code .hbs .handlebars .mustache}): {@code {{!-- … --}}}.
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
        if (!hasConstruct(lines) || core.startsWith("<!--") && core.endsWith("-->")) {
            return null;
        }
        Engine byName = byExtension(file);
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
            case "hbs", "handlebars", "mustache" -> Engine.HANDLEBARS;
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
     * ERB and EJS: every tag on the lines of {@code [a, effEnd]} made a comment tag ({@code <%} → {@code <%#}), or,
     * when all already are, made code again. {@code <%%} is a literal {@code <%}, not a tag. Null when the lines have
     * no tag. A bare caret stays on its character; a selection selects the lines.
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
                copied++; // drop the '#'
            } else if (!lines.startsWith("<%#", at)) {
                out.append('#');
            }
        }
        out.append(lines, copied, lines.length());
        String replacement = out.toString();
        if (bare) {
            int caret = from + Commenter.caretAfter(lines, replacement, a - from); // stays on its character
            return new Commenter.Edit(from, to, replacement, caret, caret);
        }
        return new Commenter.Edit(from, to, replacement, from, from + replacement.length());
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
