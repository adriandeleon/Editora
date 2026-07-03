package com.editora.todo;

/**
 * The default per-part colors for a structured TODO comment ({@code KEYWORD [tag] (priority) description}):
 * a color per priority level and one for tags. The keyword's own color comes from its {@link TodoPattern};
 * the description stays the editor's normal text color. Pure + unit-tested (web-hex strings, no JavaFX).
 */
public final class TodoColors {

    /** Tag ({@code [tag]}) color — a blue that reads on both light and dark editor themes. */
    public static final String TAG_COLOR = "#61AFEF";

    private TodoColors() {}

    /**
     * The color (web hex) for a {@code (priority)} level — red→amber by urgency, a muted cyan for {@code low}
     * — or {@code null} when {@code priority} is null / not one of {@link TodoComment#PRIORITY_ORDER}.
     */
    public static String priorityColor(String priority) {
        if (priority == null) {
            return null;
        }
        return switch (priority) {
            case "critical" -> "#E06C75"; // red
            case "high" -> "#D19A66"; // orange
            case "medium" -> "#E5C07B"; // amber
            case "low" -> "#56B6C2"; // cyan
            default -> null;
        };
    }
}
