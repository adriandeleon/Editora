package com.editora.markdown;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Section numbers written into a document's headings: {@code ## 1. Install}, {@code ### 1.1. Linux}. Pure.
 *
 * <p>Numbering starts at the document's top heading level. When that level holds exactly one heading and
 * it is the first of the document, it is the title: it stays unnumbered and its sub-headings are the
 * sections. A number already there is replaced, so the same call adds numbers and brings them up to date
 * after sections moved. Headings are the ones {@link MarkdownOutline} sees — never a {@code #} line in
 * code, front matter or HTML. A setext heading that gets a number is rewritten as an ATX one (a numbered
 * line over dashes would read as a list item and a rule); nothing else adds or removes a line.
 */
public final class MarkdownHeadingNumbers {

    private MarkdownHeadingNumbers() {}

    /** A number this class wrote: digits and dots ending in a dot, then a space. */
    private static final Pattern NUMBER = Pattern.compile("^\\d+(\\.\\d+)*\\.[ \\t]+");

    private static final Pattern ATX = Pattern.compile("^( {0,3}#{1,6}[ \\t]+)");

    /** {@code text} with every section heading numbered in sequence. */
    public static String number(String text) {
        return rewrite(text, true);
    }

    /** {@code text} with the numbers taken off its headings again. */
    public static String remove(String text) {
        return rewrite(text, false);
    }

    private static String rewrite(String text, boolean number) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        List<MarkdownOutline.Heading> headings = MarkdownOutline.headings(text);
        if (headings.isEmpty()) {
            return text;
        }
        int top =
                headings.stream().mapToInt(MarkdownOutline.Heading::level).min().orElse(1);
        long atTop = headings.stream().filter(h -> h.level() == top).count();
        boolean title = atTop == 1 && headings.get(0).level() == top && headings.size() > 1;
        int first = title ? top + 1 : top;
        if (title) {
            int below = headings.stream()
                    .skip(1)
                    .mapToInt(MarkdownOutline.Heading::level)
                    .min()
                    .orElse(first);
            first = Math.max(first, below);
        }
        String[] lines = text.split("\n", -1);
        boolean[] dropped = new boolean[lines.length];
        int[] counters = new int[7];
        boolean changed = false;
        for (MarkdownOutline.Heading h : headings) {
            String line = lines[h.line()];
            Matcher atx = ATX.matcher(line);
            if (!atx.find()) {
                // A setext heading: "1. Part" over dashes would be a list item and a rule, so a numbered one
                // is written as ATX and its underline dropped.
                int underline = number && h.level() >= first ? setextUnderline(lines, dropped, h.line()) : -1;
                if (underline < 0) {
                    continue;
                }
                lines[h.line()] = "#".repeat(h.level()) + " " + h.title().strip();
                for (int k = h.line() + 1; k <= underline; k++) {
                    dropped[k] = true;
                }
                line = lines[h.line()];
                atx = ATX.matcher(line);
                atx.find();
                changed = true;
            }
            int at = atx.end();
            String rest = line.substring(at);
            Matcher old = NUMBER.matcher(rest);
            String bare = old.find() ? rest.substring(old.end()) : rest;
            String label = "";
            if (number && h.level() >= first) {
                int depth = h.level() - first;
                counters[depth]++;
                for (int d = depth + 1; d < counters.length; d++) {
                    counters[d] = 0;
                }
                StringBuilder sb = new StringBuilder();
                for (int d = 0; d <= depth; d++) {
                    sb.append(Math.max(1, counters[d])).append('.');
                    counters[d] = Math.max(1, counters[d]); // a skipped level counts as its first section
                }
                label = sb.append(' ').toString();
            }
            String next = line.substring(0, at) + label + bare;
            if (!next.equals(line)) {
                lines[h.line()] = next;
                changed = true;
            }
        }
        if (!changed) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 64);
        boolean firstLine = true;
        for (int i = 0; i < lines.length; i++) {
            if (dropped[i]) {
                continue;
            }
            if (!firstLine) {
                out.append('\n');
            }
            out.append(lines[i]);
            firstLine = false;
        }
        return out.toString();
    }

    private static int setextUnderline(String[] lines, boolean[] dropped, int start) {
        for (int j = start + 1; j < lines.length && !dropped[j] && !lines[j].isBlank(); j++) {
            String body = lines[j].strip();
            char c = body.charAt(0);
            if ((c == '=' || c == '-') && body.chars().allMatch(ch -> ch == c)) {
                return j;
            }
        }
        return -1;
    }
}
