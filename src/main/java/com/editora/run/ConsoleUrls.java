package com.editora.run;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure URL detection for console output. */
public final class ConsoleUrls {

    public record Link(int start, int end, String url) {
        public boolean contains(int offset) {
            return offset >= start && offset < end;
        }
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\\\"']+");

    private ConsoleUrls() {}

    /** Finds HTTP(S) URLs in {@code text}, excluding punctuation that merely closes the surrounding prose. */
    public static List<Link> find(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Link> links = new ArrayList<>();
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            int end = trimEnd(text, matcher.start(), matcher.end());
            if (end > matcher.start() + "http://".length()) {
                links.add(new Link(matcher.start(), end, text.substring(matcher.start(), end)));
            }
        }
        return List.copyOf(links);
    }

    /** Returns the URL covering {@code offset}, or {@code null} when the character is plain output. */
    public static Link at(String text, int offset) {
        if (text == null || offset < 0 || offset >= text.length()) {
            return null;
        }
        int lineStart = text.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int lineEnd = text.indexOf('\n', offset);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        for (Link link : find(text.substring(lineStart, lineEnd))) {
            Link absolute = new Link(lineStart + link.start(), lineStart + link.end(), link.url());
            if (absolute.contains(offset)) {
                return absolute;
            }
        }
        return null;
    }

    private static int trimEnd(String text, int start, int end) {
        while (end > start) {
            char last = text.charAt(end - 1);
            if (last == '.' || last == ',' || last == ';' || last == '!') {
                end--;
            } else if ((last == ')' && unbalanced(text, start, end, '(', ')'))
                    || (last == ']' && unbalanced(text, start, end, '[', ']'))
                    || (last == '}' && unbalanced(text, start, end, '{', '}'))) {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    private static boolean unbalanced(String text, int start, int end, char open, char close) {
        int balance = 0;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == open) balance++;
            if (text.charAt(i) == close) balance--;
        }
        return balance < 0;
    }
}
