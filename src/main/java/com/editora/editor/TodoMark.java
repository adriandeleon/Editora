package com.editora.editor;

/**
 * A neutral in-editor TODO/highlight match for {@link TodoHighlightOverlay} (which uses the absolute
 * {@code [start,end)} offsets) and the {@link TodoStripe}/{@link Minimap} overview stripes (which use the
 * 0-based {@code line} + {@code lineText} for the hover tooltip). {@code colorWeb} is the pattern's web hex
 * (e.g. {@code #E5C07B}). Kept in {@code editor} so the package stays free of the
 * {@code com.editora.todo}/{@code config} packages — {@code MainController} maps scanner results into these.
 */
public record TodoMark(int start, int end, int line, String lineText, String colorWeb) {}
