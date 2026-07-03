package com.editora.editor;

/**
 * A neutral in-editor TODO/highlight match for {@link TodoHighlightOverlay} (which uses the absolute
 * {@code [start,end)} offsets) and the {@link TodoStripe}/{@link Minimap} overview stripes (which use the
 * 0-based {@code line} + {@code lineText} for the hover tooltip). {@code colorWeb} is the keyword's web hex
 * (e.g. {@code #E5C07B}).
 *
 * <p>The optional structured parts — the {@code [tag]} span ({@code tagStart}/{@code tagEnd}/{@code tagColorWeb})
 * and the {@code (priority)} span ({@code priStart}/{@code priEnd}/{@code priColorWeb}), all <b>absolute</b>
 * offsets — let the overlay color each part of a {@code KEYWORD [tag] (priority) description} comment
 * separately; a span is absent when its {@code *Start} is {@code -1}. Kept in {@code editor} so the package
 * stays free of {@code com.editora.todo}/{@code config} — {@code TodoCoordinator} maps scanner results here.
 */
public record TodoMark(
        int start,
        int end,
        int line,
        String lineText,
        String colorWeb,
        int tagStart,
        int tagEnd,
        String tagColorWeb,
        int priStart,
        int priEnd,
        String priColorWeb) {

    /** Back-compat constructor for a keyword-only mark (no structured tag/priority parts). */
    public TodoMark(int start, int end, int line, String lineText, String colorWeb) {
        this(start, end, line, lineText, colorWeb, -1, -1, null, -1, -1, null);
    }

    public boolean hasTag() {
        return tagStart >= 0 && tagEnd > tagStart;
    }

    public boolean hasPriority() {
        return priStart >= 0 && priEnd > priStart;
    }
}
