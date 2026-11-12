package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtendedWindowTest {

    @Test
    void linuxMayUseIt() {
        // The BSDs and any other Unix count as Linux (Os.parse), where the desktop draws the decorations anyway.
        assertTrue(ExtendedWindow.supportedOn(com.editora.Os.Kind.LINUX));
    }

    @Test
    void macIsExcludedBecauseTheMenuBelongsToTheSystemBar() {
        assertFalse(ExtendedWindow.supportedOn(com.editora.Os.Kind.MAC));
    }

    @Test
    void windowsMayUseItToo() {
        assertTrue(ExtendedWindow.supportedOn(com.editora.Os.Kind.WINDOWS));
    }

    @Test
    void theSettingAndThePlatformMustBothAgree() {
        assertTrue(ExtendedWindow.enabled(true, com.editora.Os.Kind.LINUX));
        assertFalse(ExtendedWindow.enabled(false, com.editora.Os.Kind.LINUX), "off is off even where it would work");
        assertFalse(ExtendedWindow.enabled(true, com.editora.Os.Kind.MAC), "and on is not enough where it should not");
        assertTrue(ExtendedWindow.enabled(true, com.editora.Os.Kind.WINDOWS));
    }
}
