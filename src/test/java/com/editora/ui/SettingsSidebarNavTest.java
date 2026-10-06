package com.editora.ui;

import java.util.Set;
import java.util.function.IntPredicate;

import javafx.scene.input.KeyCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsSidebarNavTest {

    /** Rows 0 and 4 are group headers; row 6 is a category the search disabled. */
    private static final IntPredicate SELECTABLE = i -> !Set.of(0, 4, 6).contains(i);

    private static int go(int from, KeyCode code) {
        return SettingsSidebarNav.target(9, from, code, 3, SELECTABLE);
    }

    @Test
    void downSkipsAGroupHeaderAndADisabledCategory() {
        assertEquals(2, go(1, KeyCode.DOWN));
        assertEquals(5, go(3, KeyCode.DOWN), "over the header at 4");
        assertEquals(7, go(5, KeyCode.DOWN), "over the disabled category at 6");
    }

    @Test
    void upSkipsThemTooAndStaysPutAtTheTop() {
        assertEquals(3, go(5, KeyCode.UP));
        assertEquals(5, go(7, KeyCode.UP));
        assertEquals(1, go(1, KeyCode.UP), "the header above the first category is not a destination");
        assertEquals(8, go(8, KeyCode.DOWN), "nothing below the last row");
    }

    @Test
    void homeAndEndLandOnTheFirstAndLastSelectableRow() {
        assertEquals(1, go(5, KeyCode.HOME));
        assertEquals(8, go(1, KeyCode.END));
    }

    @Test
    void pageKeysLandOnASelectableRow() {
        assertEquals(5, go(1, KeyCode.PAGE_DOWN), "1 + 3 is the header at 4");
        assertEquals(8, go(7, KeyCode.PAGE_DOWN));
        assertEquals(3, go(7, KeyCode.PAGE_UP), "7 - 3 is the header at 4");
        assertEquals(1, go(2, KeyCode.PAGE_UP));
    }

    @Test
    void withNothingSelectedDownTakesTheFirstRowAndUpTheLast() {
        assertEquals(1, go(-1, KeyCode.DOWN));
        assertEquals(8, go(-1, KeyCode.UP));
    }

    @Test
    void whenNoRowIsSelectableTheSelectionDoesNotMove() {
        assertEquals(2, SettingsSidebarNav.target(5, 2, KeyCode.DOWN, 3, i -> false));
        assertEquals(2, SettingsSidebarNav.target(5, 2, KeyCode.END, 3, i -> false));
        assertEquals(2, SettingsSidebarNav.target(5, 2, KeyCode.PAGE_UP, 3, i -> false));
    }

    @Test
    void otherKeysAreNotNavigation() {
        assertEquals(SettingsSidebarNav.NOT_A_NAVIGATION_KEY, go(1, KeyCode.ENTER));
        assertEquals(SettingsSidebarNav.NOT_A_NAVIGATION_KEY, go(1, KeyCode.A));
    }
}
