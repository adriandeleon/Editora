package com.editora.ui;

/**
 * Sizes in the HTTP Client response view ({@link HttpClientPanel}) that depend on its width: what its header row
 * keeps on one line, how wide the History picker gets, and how much of the tab strip fits. Pure — tested by {@code
 * HttpPanelLayoutTest}.
 */
final class HttpPanelLayout {

    private HttpPanelLayout() {}

    /** The History picker's usual width, the least it narrows to, and the most it grows to when there is room. */
    static final double HISTORY_WIDTH = 340;

    static final double HISTORY_MIN_WIDTH = 160;
    static final double HISTORY_MAX_WIDTH = 640;

    /** The share of a wide header the History picker grows to (in Preview it was held at 340 px and cut its URL). */
    static final double HISTORY_SHARE = 0.45;

    /** The gap between the header row's parts, and between its rows when it wraps. */
    static final double GAP = 8;

    /**
     * How the header row is laid out: the status's width, whether the timing and size beside it show, the History
     * picker's width, and on how many rows (1, or 2 with History under the status).
     */
    record HeaderFit(double status, boolean meta, double history, int rows) {}

    /** The History picker's width when nothing else limits it: its usual width, wider in a wide panel. */
    static double preferredHistory(double room) {
        return Math.min(HISTORY_MAX_WIDTH, Math.max(HISTORY_WIDTH, room * HISTORY_SHARE));
    }

    /**
     * Fits the header row — Run ({@code run} wide), the status ({@code status}; it may be cut to {@code statusMin}
     * while a request runs), the timing and size ({@code meta}, 0 when none) and the History picker — into {@code
     * room}. What gives way, in order: History narrows to its least, the timing and size hide (the status's tooltip
     * has them), a running status is cut, and then History moves to a row of its own. The status of a response is
     * never cut.
     */
    static HeaderFit header(double room, double run, double status, double statusMin, double meta) {
        double want = preferredHistory(room);
        double side = GAP + HISTORY_MIN_WIDTH; // History at its least
        double left = run + GAP + status;
        if (meta > 0 && left + GAP + meta + side <= room) {
            return new HeaderFit(status, true, Math.min(want, room - left - GAP - meta - GAP), 1);
        }
        if (left + side <= room) {
            return new HeaderFit(status, false, Math.min(want, room - left - GAP), 1);
        }
        if (run + GAP + statusMin + side <= room) {
            return new HeaderFit(room - run - GAP - side, false, HISTORY_MIN_WIDTH, 1);
        }
        double s = Math.max(0, Math.min(status, room - run - GAP));
        boolean m = meta > 0 && s >= status && run + GAP + s + GAP + meta <= room;
        return new HeaderFit(s, m, Math.max(Math.min(HISTORY_MIN_WIDTH, room), Math.min(want, room)), 2);
    }

    /** How much of the tab strip shows: everything, the actions folded into the "⋯" menu, or tabs as counts too. */
    enum StripFit {
        FULL,
        ACTIONS_IN_MENU,
        COUNTS
    }

    /**
     * What fits in a tab strip {@code room} wide: the tabs with their names ({@code tabs}) or with only their counts
     * ({@code countTabs}), the action icons ({@code actions}) and the "⋯" menu ({@code more}). The actions fold into
     * the menu before a tab loses its name.
     */
    static StripFit strip(double room, double tabs, double countTabs, double actions, double more) {
        if (tabs + GAP + actions + more <= room) {
            return StripFit.FULL;
        }
        if (tabs + GAP + more <= room || countTabs >= tabs) {
            return StripFit.ACTIONS_IN_MENU;
        }
        return StripFit.COUNTS;
    }
}
