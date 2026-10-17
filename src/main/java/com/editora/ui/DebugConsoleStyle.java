package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.editora.run.StackTraceLinks;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * Pure: the style spans of a chunk of Debug console output — its category's colour ({@code run-stderr},
 * {@code run-command}) and, on a line that names a source location ({@code at Main.main(Main.java:22)}), the
 * {@code console-link} look that says it can be clicked (review C21). Null when the chunk is plain text.
 */
final class DebugConsoleStyle {

    /** Lines longer than this are not looked at for a location (long base64 or JSON lines). */
    static final int MAX_LINK_LINE = 1000;

    static final String LINK = "console-link";

    private DebugConsoleStyle() {}

    static StyleSpans<Collection<String>> spans(String text, String baseStyle) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        List<String> base = baseStyle == null ? List.of() : List.of(baseStyle);
        List<String> link = new ArrayList<>(base);
        link.add(LINK);
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        boolean anyLink = false;
        int start = 0;
        while (start < text.length()) {
            int nl = text.indexOf('\n', start);
            int end = nl < 0 ? text.length() : nl + 1;
            String line = text.substring(start, nl < 0 ? end : nl);
            boolean isLink = line.length() <= MAX_LINK_LINE && StackTraceLinks.parse(line) != null;
            anyLink |= isLink;
            int indent = 0;
            while (isLink && indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
                indent++;
            }
            if (indent > 0) {
                b.add(base, indent); // the frame's indentation is not underlined
            }
            b.add(isLink ? link : base, end - start - indent);
            start = end;
        }
        return anyLink || baseStyle != null ? b.create() : null;
    }
}
