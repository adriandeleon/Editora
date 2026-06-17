package com.editora.editor;

/**
 * Neutral per-line blame annotation for the IntelliJ-style gutter "Annotate" column rendered by
 * {@link FoldManager}: the {@code author} and {@code date} shown in the column, the full {@code tooltip}
 * (author / date / summary) on hover, the {@code bg} web color for the age heatmap tint (empty = none),
 * and the {@code hash} of the commit that last touched the line (for click-to-open; empty = uncommitted).
 * Kept in {@code editor} so the package stays free of any {@code com.editora.git} dependency —
 * {@code MainController} maps git blame into these (formatting + heatmap color are done there).
 */
public record BlameInfo(String author, String date, String tooltip, String bg, String hash) {

    /** True when the row carries no rendered annotation (e.g. a not-yet-loaded or blank line). */
    public boolean isEmpty() {
        return (author == null || author.isBlank()) && (date == null || date.isBlank());
    }
}
