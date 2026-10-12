package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure (no-toolkit, unit-tested) reduction of a heading's source text to what a reader sees, for the outline:
 * {@code **Bold** and `code` and [a link](url)} is listed as "Bold and code and a link".
 *
 * <p>Deliberately small and local to the outline. The shared "heading plain text" helper that the table of
 * contents is being given replaces it once both are merged; until then this covers emphasis, code spans,
 * links and images, autolinks, inline HTML tags and backslash escapes.
 */
public final class OutlineTitles {

    private static final Pattern CODE_SPAN = Pattern.compile("(`+)(.+?)\\1");
    private static final Pattern IMAGE_OR_LINK =
            Pattern.compile("!?\\[((?:[^\\[\\]\\\\]|\\\\.)*)\\](?:\\((?:<[^>]*>|[^()\\s]*(?:\\([^()]*\\)[^()\\s]*)*)"
                    + "(?:\\s+(?:\"[^\"]*\"|'[^']*'))?\\s*\\)|\\[[^\\]]*\\])");
    private static final Pattern AUTOLINK =
            Pattern.compile("<([A-Za-z][A-Za-z0-9+.-]*:[^\\s<>]*|[^\\s<>@]+@[^\\s<>]+)>");
    private static final Pattern HTML_TAG = Pattern.compile("</?[A-Za-z][A-Za-z0-9-]*(?:\\s[^<>]*)?/?>");
    private static final Pattern STRONG_OR_STRIKE = Pattern.compile("(\\*\\*|__|~~)(?=\\S)(.+?)(?<=\\S)\\1");
    private static final Pattern STAR_EMPHASIS = Pattern.compile("\\*(?=\\S)([^*]+?)(?<=\\S)\\*");
    private static final Pattern UNDERSCORE_EMPHASIS =
            Pattern.compile("(?<![\\p{L}\\p{N}])_(?=\\S)([^_]+?)(?<=\\S)_(?![\\p{L}\\p{N}])");
    private static final Pattern ESCAPE = Pattern.compile("\\\\(\\p{Punct})");

    private OutlineTitles() {}

    /** {@code raw} without its inline markup; whitespace runs collapsed. Never null. */
    public static String plain(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        // Code spans first: what is inside one is literal, so it is parked while the rest is unwrapped.
        List<String> code = new ArrayList<>(); // parked literals, restored at the end
        Matcher m = CODE_SPAN.matcher(raw);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            code.add(m.group(2).strip());
            m.appendReplacement(sb, "\u0000" + (code.size() - 1) + "\u0000");
        }
        m.appendTail(sb);
        // …and escaped punctuation, which is that character and never a marker.
        Matcher esc = ESCAPE.matcher(sb.toString());
        sb = new StringBuilder();
        while (esc.find()) {
            code.add(esc.group(1));
            esc.appendReplacement(sb, "\u0000" + (code.size() - 1) + "\u0000");
        }
        esc.appendTail(sb);
        String s = sb.toString();
        for (int pass = 0; pass < 3; pass++) { // an image inside a link, a link inside emphasis
            s = IMAGE_OR_LINK.matcher(s).replaceAll("$1");
        }
        s = AUTOLINK.matcher(s).replaceAll("$1");
        s = HTML_TAG.matcher(s).replaceAll("");
        for (int pass = 0; pass < 2; pass++) { // ***both***
            s = STRONG_OR_STRIKE.matcher(s).replaceAll("$2");
            s = STAR_EMPHASIS.matcher(s).replaceAll("$1");
            s = UNDERSCORE_EMPHASIS.matcher(s).replaceAll("$1");
        }
        Matcher back = Pattern.compile("\u0000(\\d+)\u0000").matcher(s);
        StringBuilder out = new StringBuilder();
        while (back.find()) {
            back.appendReplacement(out, Matcher.quoteReplacement(code.get(Integer.parseInt(back.group(1)))));
        }
        back.appendTail(out);
        return out.toString().replaceAll("\\s+", " ").strip();
    }
}
