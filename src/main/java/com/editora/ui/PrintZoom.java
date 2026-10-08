package com.editora.ui;

/**
 * The zoom arithmetic of the Print Preview: the scale factor a zoom setting gives a sheet in a viewport, and
 * the percentage steps the − / + controls and Ctrl+wheel walk. Pure — no toolkit.
 */
final class PrintZoom {

    /** How the sheet is scaled. */
    enum Mode {
        /** The whole sheet is visible. */
        FIT_PAGE,
        /** The sheet is as wide as the window; a tall page scrolls. */
        FIT_WIDTH,
        /** A fixed percentage of the paper's real size (1 pt = 1 px at 100%). */
        PERCENT
    }

    /** A zoom setting: a mode and, for {@link Mode#PERCENT}, the factor (1.0 = 100%). */
    record Setting(Mode mode, double percent) {
        static final Setting FIT_PAGE = new Setting(Mode.FIT_PAGE, 1);
        static final Setting FIT_WIDTH = new Setting(Mode.FIT_WIDTH, 1);

        static Setting percent(double factor) {
            return new Setting(Mode.PERCENT, clamp(factor));
        }
    }

    /** The smallest and largest percentage a user can choose. */
    static final double MIN = 0.5;

    static final double MAX = 4.0;
    /** The percentages offered in the zoom menu and walked by − / +, ascending. */
    private static final double[] STEPS = {0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0};
    /** A fit never shrinks the sheet below this — a window dragged to nothing must not divide by it. */
    private static final double MIN_FIT = 0.05;

    private PrintZoom() {}

    static double[] steps() {
        return STEPS.clone();
    }

    static double clamp(double factor) {
        return Double.isFinite(factor) ? Math.max(MIN, Math.min(MAX, factor)) : 1;
    }

    /**
     * The scale factor of {@code setting} for a sheet of {@code sheetWidth}×{@code sheetHeight} in a viewport
     * of {@code viewWidth}×{@code viewHeight}, where {@code chrome} px on each side of the sheet are not
     * available to it (the grey gap and the drop shadow). A fit is not capped at 100%: in a large window the
     * page grows to use it, up to {@link #MAX}. Before the viewport has a size a fit answers 1.
     */
    static double factor(
            Setting setting,
            double sheetWidth,
            double sheetHeight,
            double viewWidth,
            double viewHeight,
            double chrome) {
        if (setting.mode() == Mode.PERCENT) {
            return clamp(setting.percent());
        }
        double availWidth = viewWidth - 2 * chrome;
        double availHeight = viewHeight - 2 * chrome;
        if (sheetWidth <= 0 || sheetHeight <= 0 || availWidth <= 0) {
            return 1;
        }
        double fit = availWidth / sheetWidth;
        if (setting.mode() == Mode.FIT_PAGE) {
            if (availHeight <= 0) {
                return 1;
            }
            fit = Math.min(fit, availHeight / sheetHeight);
        }
        return Math.max(MIN_FIT, Math.min(MAX, fit));
    }

    /** The next step above {@code factor} (the current effective scale), or {@link #MAX} at the top. */
    static double stepUp(double factor) {
        for (double step : STEPS) {
            if (step > factor + 0.005) {
                return step;
            }
        }
        return MAX;
    }

    /** The next step below {@code factor}, or {@link #MIN} at the bottom. */
    static double stepDown(double factor) {
        for (int i = STEPS.length - 1; i >= 0; i--) {
            if (STEPS[i] < factor - 0.005) {
                return STEPS[i];
            }
        }
        return MIN;
    }

    /** {@code factor} as a whole percentage, e.g. {@code 125} for 1.25. */
    static int percentOf(double factor) {
        return (int) Math.round(factor * 100);
    }

    /**
     * The 0-based page index for what was typed into the page field, or -1 when it is not a page of a
     * {@code count}-page document (not a number, zero, negative, past the end).
     */
    static int pageIndex(String typed, int count) {
        if (typed == null) {
            return -1;
        }
        String digits = typed.strip();
        if (digits.isEmpty() || digits.length() > 9 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return -1;
        }
        int page = Integer.parseInt(digits);
        return page >= 1 && page <= count ? page - 1 : -1;
    }
}
