package com.editora.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Loss-aware text decomposition for the diff pipeline. Unlike a plain {@code split("\\n")}, this keeps
 * the document's preferred line separator and whether the final line is terminated, so applying a hunk
 * cannot accidentally turn CRLF into LF or add/remove the final newline.
 */
public record DiffText(List<String> lines, String lineSeparator, boolean finalNewline) {

    public DiffText {
        lines = List.copyOf(lines == null ? List.of() : lines);
        lineSeparator = lineSeparator == null || lineSeparator.isEmpty() ? "\n" : lineSeparator;
    }

    public static DiffText parse(String text) {
        if (text == null || text.isEmpty()) {
            return new DiffText(List.of(), "\n", false);
        }
        List<String> lines = new ArrayList<>();
        int lf = 0;
        int crlf = 0;
        int cr = 0;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                lines.add(text.substring(start, i));
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    crlf++;
                    i++;
                } else {
                    cr++;
                }
                start = i + 1;
            } else if (c == '\n') {
                lines.add(text.substring(start, i));
                lf++;
                start = i + 1;
            }
        }
        boolean terminated = start == text.length();
        if (!terminated) {
            lines.add(text.substring(start));
        } else if (lines.isEmpty()) {
            lines.add("");
        }
        String separator = crlf >= lf && crlf >= cr && crlf > 0 ? "\r\n" : cr > lf && cr > 0 ? "\r" : "\n";
        return new DiffText(lines, separator, terminated);
    }

    public String compose(List<String> replacementLines) {
        if (replacementLines == null || replacementLines.isEmpty()) {
            return finalNewline ? lineSeparator : "";
        }
        String body = String.join(lineSeparator, replacementLines);
        return finalNewline ? body + lineSeparator : body;
    }
}
