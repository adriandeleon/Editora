package com.editora.ui;

import java.util.List;

/**
 * Sizes in the HTTP Client response view ({@link HttpClientPanel}) that depend on its content and width: how tall
 * the top section (Request, warnings, headers) is, how many rows the warnings box needs once its lines wrap, and
 * how wide the History picker gets. Pure — tested by {@code HttpPanelLayoutTest}.
 */
final class HttpPanelLayout {

    private HttpPanelLayout() {}

    /** The History picker's usual width, the least it narrows to, and the most it grows to when there is room. */
    static final double HISTORY_WIDTH = 340;

    static final double HISTORY_MIN_WIDTH = 160;
    static final double HISTORY_MAX_WIDTH = 640;

    /** What the Environment picker and its label take beside the History picker on the same row. */
    static final double ENVIRONMENT_ROW_WIDTH = 266;

    /** The top section never takes less or more than this share of the response view's split. */
    static final double HEAD_MIN_SHARE = 0.12;

    static final double HEAD_MAX_SHARE = 0.7;

    /** The warnings box shows at most this many rows; more scroll. */
    static final int NOTES_MAX_ROWS = 6;

    /**
     * The divider position that gives the top section the {@code wanted} height of a split {@code height} tall,
     * within {@link #HEAD_MIN_SHARE}–{@link #HEAD_MAX_SHARE}: a fixed 30% left the headers two lines tall as soon as
     * two warnings showed.
     */
    static double headShare(double wanted, double height) {
        if (height <= 0 || Double.isNaN(wanted)) {
            return 0.3;
        }
        return Math.max(HEAD_MIN_SHARE, Math.min(HEAD_MAX_SHARE, wanted / height));
    }

    /** How many characters of {@code charWidth} fit in {@code width} (at least 1). */
    static int columns(double width, double charWidth) {
        if (width <= 0 || charWidth <= 0) {
            return 80;
        }
        return Math.max(1, (int) Math.floor(width / charWidth));
    }

    /**
     * The rows the warnings box needs for {@code lines} wrapped at {@code columns} (one at least, at most {@link
     * #NOTES_MAX_ROWS}): it counted lines, so two warnings that wrap showed one and a half.
     */
    static int notesRows(List<String> lines, int columns) {
        int rows = 0;
        int width = Math.max(1, columns);
        for (String line : lines) {
            rows += Math.max(1, (line.length() + width - 1) / width);
        }
        return Math.max(1, Math.min(NOTES_MAX_ROWS, rows));
    }

    /**
     * The History picker's width for the {@code room} its row has (the panel's inner width less the "History:"
     * label): the usual width, narrower in a tight Split, and wider when the Environment picker still fits beside
     * it — in Preview it was held at 340 px and cut its URL.
     */
    static double historyWidth(double room) {
        if (room - ENVIRONMENT_ROW_WIDTH > HISTORY_WIDTH) {
            return Math.min(HISTORY_MAX_WIDTH, room - ENVIRONMENT_ROW_WIDTH);
        }
        return Math.max(HISTORY_MIN_WIDTH, Math.min(HISTORY_WIDTH, room));
    }
}
