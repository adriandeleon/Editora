package com.editora.markdown;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One source line split into the prefixes a line-wise Markdown command has to respect, in the order they
 * appear: indentation, blockquote prefix ({@code > > }), list marker ({@code - } / {@code 12. }), task box
 * ({@code [ ] }), ATX heading hashes, then the content. Pure.
 *
 * <p>The bullet, task, heading and Enter/Tab helpers all read a line through this one model, so a command
 * converts the marker that is there instead of stacking a second one in front of it ({@code - 1. item},
 * {@code # - item}).
 */
public record MarkdownLine(
        String indent, String quote, String marker, String task, int heading, String headingGap, String content) {

    private static final Pattern QUOTE = Pattern.compile("^(?:>[ \\t]?)+");
    private static final Pattern BULLET = Pattern.compile("^[-*+][ \\t]+");
    private static final Pattern ORDERED = Pattern.compile("^(\\d{1,9})([.)])([ \\t]+)");
    private static final Pattern TASK = Pattern.compile("^\\[[ xX]\\](?:[ \\t]+|$)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})([ \\t]+|$)");
    /** A thematic break ({@code ---}, {@code - - -}, {@code * * *}, {@code ___}): a rule, not a list item. */
    private static final Pattern RULE = Pattern.compile("^([-*_])(?:[ \\t]*\\1){2,}[ \\t]*$");

    /** Splits {@code line} (no line terminator). */
    public static MarkdownLine parse(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        String indent = line.substring(0, i);
        String rest = line.substring(i);
        String quote = take(QUOTE, rest);
        rest = rest.substring(quote.length());
        String marker = "";
        String task = "";
        if (!RULE.matcher(rest).matches()) {
            marker = take(BULLET, rest);
            if (marker.isEmpty()) {
                marker = take(ORDERED, rest);
            }
            rest = rest.substring(marker.length());
            if (!marker.isEmpty()) {
                task = take(TASK, rest);
                rest = rest.substring(task.length());
            }
        }
        int heading = 0;
        String gap = "";
        Matcher h = HEADING.matcher(rest);
        if (h.find()) {
            heading = h.group(1).length();
            gap = h.group(2);
            rest = rest.substring(h.end());
        }
        return new MarkdownLine(indent, quote, marker, task, heading, gap, rest);
    }

    private static String take(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group() : "";
    }

    /** True for a bullet or ordered list item. */
    public boolean isListItem() {
        return !marker.isEmpty();
    }

    /** True for an ordered ({@code N.} / {@code N)}) list item. */
    public boolean isOrdered() {
        return !marker.isEmpty() && Character.isDigit(marker.charAt(0));
    }

    public boolean isTask() {
        return !task.isEmpty();
    }

    /** True when the line has no text of its own: nothing but indentation, markers and an empty task box. */
    public boolean isBlank() {
        return content.isBlank() && heading == 0;
    }

    /** The number of an ordered item; -1 for anything else. */
    public long number() {
        if (!isOrdered()) {
            return -1;
        }
        Matcher m = ORDERED.matcher(marker);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    /** This ordered item's marker with {@code n} as its number (delimiter and spacing kept). */
    public String markerNumbered(long n) {
        Matcher m = ORDERED.matcher(marker);
        return m.find() ? n + m.group(2) + m.group(3) : marker;
    }

    /** The {@code .} or {@code )} of an ordered marker, the bullet character of a bullet, else 0. */
    public char markerKind() {
        if (marker.isEmpty()) {
            return 0;
        }
        return isOrdered() ? marker.charAt(marker.stripTrailing().length() - 1) : marker.charAt(0);
    }

    /** Where the item's content starts: after indentation, quote prefix, list marker and task box. */
    public int prefixLength() {
        return indent.length() + quote.length() + marker.length() + task.length();
    }

    /** The column a nested item must be indented to: after indentation and the list marker. */
    public int contentIndent() {
        return indent.length() + marker.length();
    }

    private String headingText() {
        return heading == 0
                ? ""
                : "#".repeat(heading) + (headingGap.isEmpty() && !content.isEmpty() ? " " : headingGap);
    }

    /** The line put back together. */
    public String text() {
        return indent + quote + marker + task + headingText() + content;
    }

    public MarkdownLine withIndent(String newIndent) {
        return new MarkdownLine(newIndent, quote, marker, task, heading, headingGap, content);
    }

    public MarkdownLine withMarker(String newMarker) {
        return new MarkdownLine(
                indent, quote, newMarker, newMarker.isEmpty() ? "" : task, heading, headingGap, content);
    }

    public MarkdownLine withTask(String newTask) {
        return new MarkdownLine(indent, quote, marker, newTask, heading, headingGap, content);
    }

    /** The same line at heading {@code level} (0 = no heading). */
    public MarkdownLine withHeading(int level) {
        String gap = level == 0 ? "" : (headingGap.isEmpty() ? " " : headingGap);
        String text = level == 0 ? content : (heading == 0 ? content.stripLeading() : content);
        return new MarkdownLine(indent, quote, marker, task, level, gap, text);
    }
}
