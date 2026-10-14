package com.editora.markdown;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Source styling the Markdown grammar cannot express, as extra style ranges laid over its tokens:
 *
 * <ul>
 *   <li>the text line(s) of a <b>setext heading</b> ({@code Title} over {@code ===}) — a line is a heading
 *       only because of the line after it, which a line-by-line tokenizer never sees — styled like an ATX
 *       heading ({@code heading});
 *   <li>a <b>task box</b>: {@code [ ]} as a marker ({@code md-marker}), {@code [x]} muted like something
 *       done ({@code comment}), so the two read differently at a glance;
 *   <li>a <b>bare URL</b> ({@code https://…} written without brackets), styled as a link ({@code link}).
 * </ul>
 *
 * Pure. Driven by {@link MarkdownBlocks} and {@link MarkdownOutline}, so nothing in code, front matter, an
 * HTML block or a comment is ever styled. The classes are ones every editor theme already defines.
 */
public final class MarkdownSourceStyles {

    private MarkdownSourceStyles() {}

    /** Characters {@code [start, end)} of the document carry style class {@code style}. */
    public record Range(int start, int end, String style) {}

    /** How far a changed line can change the styling of its neighbours: a setext paragraph is short. */
    public static final int MAX_PARAGRAPH_LINES = 12;

    private static final Pattern TASK =
            Pattern.compile("^\\s*(?:>\\s*)*(?:[-*+]|\\d{1,9}[.)])\\s+(\\[[ xX]\\])(?=\\s|$)");
    private static final Pattern URL = Pattern.compile("(?<![\\w/.:@-])https?://[^\\s<>\\[\\]`\"']+");

    /**
     * The ranges on lines {@code firstLine..lastLine} (0-based, inclusive) of {@code text}, in document
     * order and not overlapping. Lines outside the window are read (a heading's underline may be the line
     * after it) but never styled.
     */
    public static List<Range> ranges(String text, int firstLine, int lastLine) {
        List<Range> out = new ArrayList<>();
        if (text == null || text.isEmpty() || lastLine < firstLine) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        MarkdownBlocks blocks = MarkdownBlocks.scan(lines);
        boolean[] setext = new boolean[lines.length];
        for (MarkdownOutline.Heading h : MarkdownOutline.headings(text)) {
            int at = h.line();
            if (at >= lines.length || lines[at].stripLeading().startsWith("#")) {
                continue;
            }
            for (int j = at; j < lines.length && blocks.isText(j) && !isUnderline(lines[j]); j++) {
                setext[j] = true;
            }
        }
        int offset = 0;
        for (int n = 0; n < lines.length && n <= lastLine; n++) {
            String line = lines[n];
            if (n >= firstLine && blocks.isText(n) && !line.isBlank()) {
                if (setext[n]) {
                    int lead = line.length() - line.stripLeading().length();
                    int end = line.stripTrailing().length();
                    if (end > lead) {
                        out.add(new Range(offset + lead, offset + end, "heading"));
                    }
                } else {
                    Matcher task = TASK.matcher(line);
                    int after = 0;
                    if (task.find()) {
                        boolean done = line.charAt(task.start(1) + 1) != ' ';
                        out.add(new Range(
                                offset + task.start(1), offset + task.end(1), done ? "comment" : "md-marker"));
                        after = task.end(1);
                    }
                    if (line.indexOf("http", after) >= 0) {
                        int[] code = MarkdownBlocks.codeSpans(line);
                        Matcher url = URL.matcher(line);
                        while (url.find(after)) {
                            after = url.end();
                            int end = trimmed(line, url.start(), url.end());
                            // "](url" and "<url" are a link's target and an autolink: the grammar's own.
                            int at = url.start();
                            boolean target = at > 0
                                    && (line.charAt(at - 1) == '<'
                                            || (line.charAt(at - 1) == '(' && at > 1 && line.charAt(at - 2) == ']'));
                            if (!target && !MarkdownBlocks.inRanges(code, url.start()) && end > url.start() + 8) {
                                out.add(new Range(offset + url.start(), offset + end, "link"));
                            }
                        }
                    }
                }
            }
            offset += line.length() + 1;
        }
        return out;
    }

    /** A URL does not end in the punctuation of the sentence around it, nor in an unbalanced {@code )}. */
    private static int trimmed(String line, int start, int end) {
        while (end > start) {
            char c = line.charAt(end - 1);
            if (".,;:!?'\"*_~".indexOf(c) >= 0) {
                end--;
            } else if (c == ')' && count(line, start, end, ')') > count(line, start, end, '(')) {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    private static int count(String line, int start, int end, char c) {
        int n = 0;
        for (int i = start; i < end; i++) {
            if (line.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static boolean isUnderline(String line) {
        String body = line.strip();
        if (body.isEmpty()) {
            return false;
        }
        char c = body.charAt(0);
        return (c == '=' || c == '-') && body.chars().allMatch(ch -> ch == c);
    }
}
