package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedWindowTest {

    @Test
    void linuxAndTheBsdsMayUseIt() {
        assertTrue(ExtendedWindow.supportedOn("Linux"));
        assertTrue(ExtendedWindow.supportedOn("FreeBSD"));
    }

    @Test
    void macIsExcludedBecauseTheMenuBelongsToTheSystemBar() {
        assertFalse(ExtendedWindow.supportedOn("Mac OS X"));
        assertFalse(ExtendedWindow.supportedOn("Darwin"));
    }

    @Test
    void windowsMayUseItToo() {
        assertTrue(ExtendedWindow.supportedOn("Windows 11"));
        assertTrue(ExtendedWindow.supportedOn("Windows Server 2022"));
    }

    @Test
    void anUnknownOsIsNotAssumedToWork() {
        // This decides how the window is decorated, and guessing wrong means a window that will not open.
        assertFalse(ExtendedWindow.supportedOn(""));
        assertFalse(ExtendedWindow.supportedOn(null));
        assertFalse(ExtendedWindow.supportedOn("Some Future OS"));
    }

    @Test
    void theSettingAndThePlatformMustBothAgree() {
        assertTrue(ExtendedWindow.enabled(true, "Linux"));
        assertFalse(ExtendedWindow.enabled(false, "Linux"), "off is off even where it would work");
        assertFalse(ExtendedWindow.enabled(true, "Mac OS X"), "and on is not enough where it should not");
        assertTrue(ExtendedWindow.enabled(true, "Windows 11"));
    }
}
