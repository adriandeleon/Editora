package com.editora.ui;

import java.util.function.IntPredicate;

import javafx.scene.input.KeyCode;

/**
 * Where a navigation key takes the Settings sidebar's selection. The sidebar is one list holding group
 * headers and categories, and while a search runs the categories without a hit are disabled — but a
 * ListView's own keyboard handling steps onto any row. Left to it, Down from the last category of a
 * group stopped on the header (nothing highlighted, page unchanged), and during a search it opened pages
 * whose every row was filtered out. Pure.
 */
final class SettingsSidebarNav {

    /** Returned for a key that does not move the selection. */
    static final int NOT_A_NAVIGATION_KEY = -1;

    private SettingsSidebarNav() {}

    /**
     * The row to select after {@code code} is pressed with row {@code from} selected ({@code -1}: none),
     * skipping rows that are not {@code selectable}. Returns {@code from} when there is nowhere to go, and
     * {@link #NOT_A_NAVIGATION_KEY} for any other key. {@code page} is the number of rows a Page key moves.
     */
    static int target(int size, int from, KeyCode code, int page, IntPredicate selectable) {
        return switch (code) {
            case DOWN -> scan(size, from + 1, 1, from, selectable);
            case UP -> scan(size, from < 0 ? size - 1 : from - 1, -1, from, selectable);
            case HOME -> scan(size, 0, 1, from, selectable);
            case END -> scan(size, size - 1, -1, from, selectable);
            case PAGE_DOWN -> {
                int landed = scan(size, Math.min(size - 1, Math.max(0, from) + Math.max(1, page)), 1, -1, selectable);
                yield landed >= 0 ? landed : scan(size, size - 1, -1, from, selectable);
            }
            case PAGE_UP -> {
                int landed = scan(size, Math.max(0, Math.max(0, from) - Math.max(1, page)), -1, -1, selectable);
                yield landed >= 0 ? landed : scan(size, 0, 1, from, selectable);
            }
            default -> NOT_A_NAVIGATION_KEY;
        };
    }

    /** The first selectable row from {@code start} going in direction {@code step}, else {@code fallback}. */
    private static int scan(int size, int start, int step, int fallback, IntPredicate selectable) {
        for (int i = start; i >= 0 && i < size; i += step) {
            if (selectable.test(i)) {
                return i;
            }
        }
        return fallback;
    }
}
