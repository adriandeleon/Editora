package com.editora.review;

import java.util.List;

import com.editora.config.ReviewComment;
import com.editora.config.ReviewThread;
import org.jspecify.annotations.Nullable;

/**
 * The text around a review comment: the suggestion a body carries in a GitHub {@code ```suggestion} block, and
 * the short label a thread's marker shows after its line. Pure.
 */
public final class ReviewText {

    /** How much of the first comment a marker shows. */
    static final int LABEL_CHARS = 48;

    private ReviewText() {}

    /** A body split into the text a person reads and the suggestion it carries (null: none). */
    public record Parsed(String body, @Nullable String suggestion) {}

    /**
     * {@code body} without its first {@code ```suggestion} block, and that block's text: the lines between the fence
     * and the closing {@code ```}, without the line break before the closing fence. An unclosed block runs to the end.
     * A body with no such block is returned as it is, with no suggestion.
     */
    public static Parsed parse(String body) {
        String[] lines = body.split("\n", -1);
        int open = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].strip().equals("```suggestion")) {
                open = i;
                break;
            }
        }
        if (open < 0) {
            return new Parsed(body.strip(), null);
        }
        int close = lines.length;
        for (int i = open + 1; i < lines.length; i++) {
            if (lines[i].strip().equals("```")) {
                close = i;
                break;
            }
        }
        StringBuilder suggestion = new StringBuilder();
        for (int i = open + 1; i < close; i++) {
            if (i > open + 1) {
                suggestion.append('\n');
            }
            suggestion.append(stripCr(lines[i]));
        }
        StringBuilder rest = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i >= open && i <= Math.min(close, lines.length - 1)) {
                continue;
            }
            if (!rest.isEmpty()) {
                rest.append('\n');
            }
            rest.append(lines[i]);
        }
        return new Parsed(rest.toString().strip(), suggestion.toString());
    }

    /** A comment's body as it is edited: the text, then its suggestion as a {@code ```suggestion} block. */
    public static String editable(ReviewComment c) {
        String s = c.suggestion();
        if (s == null) {
            return c.body();
        }
        return (c.body().isEmpty() ? "" : c.body() + "\n\n") + "```suggestion\n" + s + "\n```";
    }

    /** The {@code ```suggestion} block a new comment starts with, holding the lines it is on. */
    public static String suggestionTemplate(String lines) {
        return "```suggestion\n" + lines + "\n```";
    }

    /**
     * What a thread's marker says: a speech balloon and the comment count, an agent's severity, and the start of the
     * first comment on one line, e.g. {@code 💬 2 · Why b?} or {@code ◆ IMPORTANT · Off by one}.
     */
    public static String label(ReviewThread thread) {
        ReviewComment root = thread.root();
        String first = root == null
                ? ""
                : oneLine(
                        root.body().isEmpty() && root.suggestion() != null
                                ? com.editora.i18n.Messages.tr("review.suggestedChange")
                                : root.body());
        String head = thread.severity() != null
                ? "◆ " + thread.severity().name()
                : "💬 " + thread.comments().size();
        if (thread.severity() != null && thread.comments().size() > 1) {
            head += " · " + thread.comments().size();
        }
        return first.isEmpty() ? head : head + " · " + first;
    }

    /** The threads' labels for one line, most important first: a line with several threads lists them all. */
    public static List<String> labels(List<ReviewThread> threads) {
        return threads.stream()
                .sorted(java.util.Comparator.comparingInt(ReviewText::rank))
                .map(ReviewText::label)
                .toList();
    }

    private static int rank(ReviewThread t) {
        return t.severity() == null
                ? ReviewThread.Severity.values().length
                : t.severity().ordinal();
    }

    /** {@code text}'s first line, cut to {@link #LABEL_CHARS} with an ellipsis. */
    static String oneLine(String text) {
        String s = text.strip();
        int nl = s.indexOf('\n');
        if (nl >= 0) {
            s = s.substring(0, nl).strip() + " …";
        }
        return s.length() > LABEL_CHARS ? s.substring(0, LABEL_CHARS - 1).strip() + "…" : s;
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
