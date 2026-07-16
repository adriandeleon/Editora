package com.editora.todo;

import java.util.List;
import java.util.Locale;

/**
 * The structured parse of a TODO-style comment, the IntelliJ "Comment Manager" format:
 *
 * <pre>{@code   KEYWORD [tag] (priority) description}</pre>
 *
 * e.g. {@code // TODO [auth] (high) token refresh races on logout}. Only the {@code keyword} is required;
 * the {@code [tag]} and {@code (priority)} are optional and, when present, come in that order right after
 * the keyword (an optional {@code :} directly after the keyword is tolerated, so {@code TODO: fix it}
 * parses). {@code priority} is one of {@code critical/high/medium/low} (case-insensitive) — a {@code (…)}
 * that isn't one of those is left as part of the description. Everything after the recognized parts is the
 * {@code description}.
 *
 * <p>Offsets ({@code *Start}/{@code *End}) are 0-based indices <b>within the line</b> and {@code -1} when the
 * part is absent; the {@code [tag]} / {@code (priority)} spans <b>include</b> their brackets/parens so the
 * highlighter can color the delimiters. Pure + unit-tested (no JavaFX / no IO).
 */
public record TodoComment(
        String keyword,
        String tag,
        String priority,
        String description,
        int tagStart,
        int tagEnd,
        int priorityStart,
        int priorityEnd,
        int descriptionStart,
        int descriptionEnd) {

    /** The recognized priorities, most-urgent first (the index is the sort rank). */
    public static final List<String> PRIORITY_ORDER = List.of("critical", "high", "medium", "low");

    /**
     * Parses the {@code [tag] (priority) description} that follows the keyword in {@code lineText}, where
     * the keyword occupies {@code [keywordStart, keywordEnd)} (0-based, within the line — the span
     * {@link TodoScanner} matched).
     */
    public static TodoComment parse(String lineText, int keywordStart, int keywordEnd) {
        String keyword = lineText.substring(keywordStart, keywordEnd);
        int n = lineText.length();
        int i = keywordEnd;
        if (i < n && lineText.charAt(i) == ':') {
            i++; // tolerate the common "TODO:" colon right after the keyword
        }

        String tag = null;
        int tagStart = -1;
        int tagEnd = -1;
        String priority = null;
        int priorityStart = -1;
        int priorityEnd = -1;

        // Consume an optional [tag] then an optional (priority), in that order, skipping whitespace between.
        for (int guard = 0; guard < 2; guard++) {
            int j = i;
            while (j < n && Character.isWhitespace(lineText.charAt(j))) {
                j++;
            }
            if (j >= n) {
                break;
            }
            char c = lineText.charAt(j);
            if (c == '[' && tag == null) {
                int close = lineText.indexOf(']', j + 1);
                if (close < 0) {
                    break;
                }
                if (close + 1 < n && lineText.charAt(close + 1) == '(') {
                    break; // a Markdown link "[label](url)" — not a tag; leave the whole thing in the
                    // description. Claiming it split the link across the canonical re-emit
                    // ("[label] (url)") and silently broke it on the next edit action.
                }
                String content = lineText.substring(j + 1, close).trim();
                if (content.isEmpty()) {
                    break; // an empty [] isn't a tag — leave it in the description
                }
                tag = content;
                tagStart = j;
                tagEnd = close + 1;
                i = close + 1;
            } else if (c == '(' && priority == null) {
                int close = lineText.indexOf(')', j + 1);
                if (close < 0) {
                    break;
                }
                String content = lineText.substring(j + 1, close).trim().toLowerCase(Locale.ROOT);
                if (!PRIORITY_ORDER.contains(content)) {
                    break; // a non-priority (…) belongs to the description
                }
                priority = content;
                priorityStart = j;
                priorityEnd = close + 1;
                i = close + 1;
            } else {
                break;
            }
        }

        int ds = i;
        while (ds < n && Character.isWhitespace(lineText.charAt(ds))) {
            ds++;
        }
        int de = closerStart(lineText); // the trailing */ or --> belongs to the line, not the description
        if (de < 0) {
            de = n;
        }
        while (de > ds && Character.isWhitespace(lineText.charAt(de - 1))) {
            de--;
        }
        String description = ds < de ? lineText.substring(ds, de) : "";
        int descriptionStart = description.isEmpty() ? -1 : ds;
        int descriptionEnd = description.isEmpty() ? -1 : de;

        return new TodoComment(
                keyword,
                tag,
                priority,
                description,
                tagStart,
                tagEnd,
                priorityStart,
                priorityEnd,
                descriptionStart,
                descriptionEnd);
    }

    /** Block-comment terminators a TODO line can end with, which are part of the comment, not its text. */
    private static final List<String> CLOSERS = List.of("*/", "-->");

    /**
     * The index at which {@code lineText}'s trailing block-comment terminator begins ({@code /* TODO x *}{@code /}
     * → the index of {@code *}{@code /}), or {@code -1} when the line doesn't end with one.
     *
     * <p>Shared by {@link #parse} (which keeps it out of the description) and {@link TodoEdit#rebuild} (which
     * re-appends it), so a rewrite can't drop it. It used to land in the description: editing the description
     * of {@code /*}{@code  TODO: x *}{@code /} then emitted {@code /*}{@code  TODO <new text>} — an unterminated
     * block comment, silently commenting out everything below it.
     */
    public static int closerStart(String lineText) {
        if (lineText == null) {
            return -1;
        }
        int end = lineText.length();
        while (end > 0 && Character.isWhitespace(lineText.charAt(end - 1))) {
            end--;
        }
        for (String closer : CLOSERS) {
            if (end >= closer.length() && lineText.startsWith(closer, end - closer.length())) {
                return end - closer.length();
            }
        }
        return -1;
    }

    public boolean hasTag() {
        return tag != null;
    }

    public boolean hasPriority() {
        return priority != null;
    }

    /** Sort rank for the priority (0 = critical … 3 = low), or {@code 4} when there's no priority. */
    public int priorityRank() {
        int idx = priority == null ? -1 : PRIORITY_ORDER.indexOf(priority);
        return idx < 0 ? PRIORITY_ORDER.size() : idx;
    }
}
