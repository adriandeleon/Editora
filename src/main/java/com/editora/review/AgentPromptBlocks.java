package com.editora.review;

import java.util.List;

import com.editora.config.ReviewComment;
import com.editora.config.ReviewThread;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;
import org.jspecify.annotations.Nullable;

/**
 * What "Add to Agent Prompt" puts in the agent's prompt: a selection, a review thread or a diff change, each as a
 * short heading and a fenced block, for you to add your question under. English: it is read by a model. Lines are
 * 1-based. Pure.
 */
public final class AgentPromptBlocks {

    private AgentPromptBlocks() {}

    /** Lines {@code start}–{@code end} of {@code path}: {@code text}. */
    public static String selection(String path, int start, int end, String text) {
        return "In " + path + (start == end ? ", line " + start : ", lines " + start + "-" + end) + ":\n"
                + fence("", text);
    }

    /** Review thread {@code t} on lines {@code start}–{@code end} of {@code path}, with the lines as they are now. */
    public static String thread(String path, int start, int end, ReviewThread t, String lines) {
        StringBuilder b = new StringBuilder();
        b.append("A review ")
                .append(t.severity() == null ? "thread" : "finding")
                .append(" on ")
                .append(path)
                .append(start == end ? ", line " + start : ", lines " + start + "-" + end)
                .append(" (thread ")
                .append(t.id())
                .append(", ")
                .append(t.state().name().toLowerCase(java.util.Locale.ROOT))
                .append("):\n");
        for (ReviewComment c : t.comments()) {
            String who = switch (c.author().kind()) {
                case USER -> "The user";
                case AGENT ->
                    c.author().name().isBlank() ? "An agent" : c.author().name();
                case GITHUB -> "@" + c.author().name();
            };
            b.append(who).append(": ").append(c.body().strip()).append('\n');
        }
        return b.append(fence("", lines)).toString();
    }

    /**
     * The change of {@code rows} (one block of a diff: the rows that are not equal) between {@code leftName} and
     * {@code rightName}, as a unified hunk; null when it has no changed row.
     */
    public static @Nullable String change(@Nullable String leftName, @Nullable String rightName, List<Row> rows) {
        StringBuilder removed = new StringBuilder();
        StringBuilder added = new StringBuilder();
        int firstLeft = -1;
        int firstRight = -1;
        int lefts = 0;
        int rights = 0;
        for (Row r : rows) {
            if (r.type() == RowType.EQUAL) {
                continue;
            }
            if (r.left() != null) {
                firstLeft = firstLeft < 0 ? r.leftLine() : firstLeft;
                removed.append('-').append(r.left()).append('\n');
                lefts++;
            }
            if (r.right() != null) {
                firstRight = firstRight < 0 ? r.rightLine() : firstRight;
                added.append('+').append(r.right()).append('\n');
                rights++;
            }
        }
        if (lefts + rights == 0) {
            return null;
        }
        String left = leftName == null || leftName.isBlank() ? "left" : leftName;
        String right = rightName == null || rightName.isBlank() ? "right" : rightName;
        String hunk = "--- " + left + "\n+++ " + right + "\n@@ -" + Math.max(firstLeft, 0) + "," + lefts + " +"
                + Math.max(firstRight, 0) + "," + rights + " @@\n" + removed + added;
        return "A change in " + right + (left.equals(right) ? "" : " (compared with " + left + ")") + ":\n"
                + fence("diff", hunk.stripTrailing());
    }

    /** {@code text} in a fence long enough that a fence inside it does not end it. */
    static String fence(String info, String text) {
        String ticks = "```";
        while (text.contains(ticks)) {
            ticks += "`";
        }
        return ticks + info + "\n" + text.stripTrailing() + "\n" + ticks + "\n";
    }
}
