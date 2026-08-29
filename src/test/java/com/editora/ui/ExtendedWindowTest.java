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
        assertTrue(ExtendedWindow.enabled(true, "Linux", true));
        assertFalse(ExtendedWindow.enabled(false, "Linux", true), "off is off even where it would work");
        assertFalse(ExtendedWindow.enabled(true, "Mac OS X", true), "and on is not enough where it should not");
        assertTrue(ExtendedWindow.enabled(true, "Windows 11", true));
    }

    /**
     * The condition that cost a windowless launch to discover: {@code StageStyle.EXTENDED} is a JavaFX 26
     * PREVIEW feature, and {@code Stage.initStyle} throws without {@code -Djavafx.enablePreview=true} —
     * out of window construction, so the application comes up with no window at all.
     */
    @Test
    void withoutJavaFxPreviewFeaturesItStaysOff() {
        assertFalse(ExtendedWindow.enabled(true, "Linux", false));
    }
}
