package com.editora.todo;

/**
 * User-configurable per-part colors for a structured TODO comment
 * ({@code KEYWORD [tag] (priority) description}): a color for the {@code [tag]} span and one per
 * {@code (priority)} level. The keyword's own color comes from its {@link TodoPattern}; the description
 * stays the editor's normal text color. Pure + unit-tested (web-hex strings, no JavaFX).
 *
 * <p>{@link #of} defensively replaces any {@code null}/blank field with the {@link TodoColors} default, so a
 * missing or cleared setting never yields an empty color.
 */
public record TodoPartColors(String tag, String critical, String high, String medium, String low) {

    /** The built-in defaults (the same palette {@link TodoColors} exposes). */
    public static TodoPartColors defaults() {
        return new TodoPartColors(
                TodoColors.TAG_COLOR,
                TodoColors.priorityColor("critical"),
                TodoColors.priorityColor("high"),
                TodoColors.priorityColor("medium"),
                TodoColors.priorityColor("low"));
    }

    /** Builds an instance, substituting the built-in default for any {@code null}/blank hex. */
    public static TodoPartColors of(String tag, String critical, String high, String medium, String low) {
        TodoPartColors d = defaults();
        return new TodoPartColors(
                orDefault(tag, d.tag()),
                orDefault(critical, d.critical()),
                orDefault(high, d.high()),
                orDefault(medium, d.medium()),
                orDefault(low, d.low()));
    }

    private static String orDefault(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }

    /** The color (web hex) for a {@code (priority)} level, or {@code null} when {@code priority} is null /
     *  not one of {@link TodoComment#PRIORITY_ORDER}. */
    public String priorityColor(String priority) {
        if (priority == null) {
            return null;
        }
        return switch (priority) {
            case "critical" -> critical;
            case "high" -> high;
            case "medium" -> medium;
            case "low" -> low;
            default -> null;
        };
    }
}
