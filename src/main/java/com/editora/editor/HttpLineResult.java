package com.editora.editor;

import java.util.List;

/**
 * A {@code .http} request's last result as the editor draws it after the request's method/URL line (U11): the
 * 0-based {@code line} and the text it read ({@code anchor}) when the result was put there, the coloured
 * {@code segments} of the text, the class the line's ▶ takes ({@code glyphClass}, null for the plain ▶), the hover
 * text and what a click does. Never part of the document. See {@link HttpResultMarks}.
 */
public record HttpLineResult(
        int line, String anchor, List<Segment> segments, String glyphClass, String tooltip, Runnable onClick) {

    /** One run of the text with its style class (every class starts with {@code http-result}). */
    public record Segment(String text, String styleClass) {}

    public HttpLineResult {
        anchor = anchor == null ? "" : anchor;
        segments = segments == null ? List.of() : List.copyOf(segments);
        onClick = onClick == null ? () -> {} : onClick;
    }

    /** The whole text after the line. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            sb.append(s.text());
        }
        return sb.toString();
    }

    /** Whether {@code other} draws the same thing on the same line (the click is not compared). */
    boolean looksLike(HttpLineResult other) {
        return other != null
                && line == other.line
                && anchor.equals(other.anchor)
                && segments.equals(other.segments)
                && java.util.Objects.equals(glyphClass, other.glyphClass)
                && java.util.Objects.equals(tooltip, other.tooltip);
    }
}
