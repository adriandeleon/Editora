package com.editora.editops;

import java.util.ArrayList;
import java.util.List;

import com.editora.text.HtmlElements;

/**
 * Pure: one forward walk over an HTML document's tags, for the checks and the link features that need every
 * start and end tag with its position (HTML review B6, D5, C16). Comments, CDATA, processing instructions and
 * declarations are skipped, quoted attribute values are honoured, and the content of the raw-text elements
 * ({@code script}, {@code style}, {@code textarea}, {@code title}) is not read as markup — the same lexing as
 * {@link TagJoin} and {@link TagRename}, which it builds on.
 */
final class HtmlTags {

    private HtmlTags() {}

    /** What the walk reports. Names are as written; callers compare them case-insensitively. */
    interface Visitor {
        /**
         * A start tag {@code <name …>} from {@code start} (its {@code <}) to {@code end} (past its {@code >});
         * {@code closed} is false for one the text ends inside.
         */
        void start(String name, int start, int nameEnd, int end, boolean selfClosing, boolean closed);

        /** An end tag {@code </name>} from {@code start} to {@code end}. */
        void end(String name, int start, int end);
    }

    /** Walks {@code text}, reporting each tag to {@code visitor} in document order. */
    static void walk(String text, Visitor visitor) {
        int n = text.length();
        int i = 0;
        while (i < n) {
            int lt = text.indexOf('<', i);
            if (lt < 0) {
                return;
            }
            i = lt;
            if (text.startsWith("<!--", i)) {
                i = TagRename.skipTo(text, i + 4, "-->");
            } else if (text.startsWith("<![CDATA[", i)) {
                i = TagRename.skipTo(text, i + 9, "]]>");
            } else if (text.startsWith("<?", i)) {
                i = TagRename.skipTo(text, i + 2, "?>");
            } else if (text.startsWith("<!", i)) {
                i = TagRename.skipTo(text, i + 2, ">");
            } else if (text.startsWith("</", i) && i + 2 < n && Character.isLetter(text.charAt(i + 2))) {
                int nameEnd = TagRename.nameEnd(text, i + 2);
                int end = TagRename.skipTag(text, nameEnd)[0];
                visitor.end(text.substring(i + 2, nameEnd), i, end);
                i = end;
            } else if (i + 1 < n && Character.isLetter(text.charAt(i + 1))) {
                int nameEnd = TagRename.nameEnd(text, i + 1);
                String name = text.substring(i + 1, nameEnd);
                int[] end = TagRename.skipTag(text, nameEnd);
                boolean closed = end[0] > nameEnd && text.charAt(end[0] - 1) == '>';
                visitor.start(name, i, nameEnd, end[0], end[1] == 1, closed);
                i = end[0];
                if (closed && end[1] == 0 && HtmlElements.isRawText(name)) {
                    i = TagRename.skipToCloseTag(text, i, name); // script/style content is not markup
                }
            } else {
                i++; // a '<' in text
            }
        }
    }

    /** One attribute of a start tag: its name, and its value's text and span ({@code -1} when it has none). */
    record Attribute(String name, String value, int valueStart, int valueEnd) {}

    /** The attributes of the start tag whose name ends at {@code from} and which ends before {@code end}. */
    static List<Attribute> attributes(String text, int from, int end) {
        List<Attribute> out = new ArrayList<>();
        int i = from;
        int stop = Math.min(end, text.length());
        while (i < stop) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '/') {
                i++;
                continue;
            }
            if (c == '>') {
                break;
            }
            int nameStart = i;
            while (i < stop && !Character.isWhitespace(text.charAt(i)) && "=>/\"'".indexOf(text.charAt(i)) < 0) {
                i++;
            }
            if (i == nameStart) {
                i++; // a stray quote: step over it
                continue;
            }
            String name = text.substring(nameStart, i);
            int j = i;
            while (j < stop && Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            if (j >= stop || text.charAt(j) != '=') {
                out.add(new Attribute(name, "", -1, -1));
                continue;
            }
            j++;
            while (j < stop && Character.isWhitespace(text.charAt(j))) {
                j++;
            }
            if (j < stop && (text.charAt(j) == '"' || text.charAt(j) == '\'')) {
                char quote = text.charAt(j);
                int close = text.indexOf(quote, j + 1);
                int valueEnd = close < 0 || close > stop ? stop : close;
                out.add(new Attribute(name, text.substring(j + 1, valueEnd), j + 1, valueEnd));
                i = valueEnd + 1;
            } else {
                int valueStart = j;
                while (j < stop && !Character.isWhitespace(text.charAt(j)) && text.charAt(j) != '>') {
                    j++;
                }
                out.add(new Attribute(name, text.substring(valueStart, j), valueStart, j));
                i = j;
            }
        }
        return out;
    }
}
