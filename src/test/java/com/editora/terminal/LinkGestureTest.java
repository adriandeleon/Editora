package com.editora.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which press opens a link, and which one must not. */
class LinkGestureTest {

    @Test
    void aPlainClickNeverOpensAnything() {
        // A plain click is a selection, and once a program has turned mouse reporting on it is that
        // program's click. Taking it would break both.
        assertFalse(LinkGesture.opensLink(true, false, false));
        assertFalse(LinkGesture.opensLink(false, false, false));
    }

    @Test
    void commandOnMacAndControlElsewhere() {
        assertTrue(LinkGesture.opensLink(true, true, false));
        assertTrue(LinkGesture.opensLink(false, false, true));
    }

    @Test
    void controlClickOnMacIsTheContextMenuAndIsLeftAlone() {
        // A one-button mouse raises the menu that way, and a terminal that swallowed it would take
        // away the only mouse route to Copy on that hardware.
        assertFalse(LinkGesture.opensLink(true, false, true));
    }

    @Test
    void theOtherPlatformsModifierDoesNotWork() {
        assertFalse(LinkGesture.opensLink(false, true, false));
    }

    @Test
    void bothTogetherIsNeither() {
        // Ctrl+Cmd is a chord of its own; reading it as either one would fire a link in the middle
        // of something else the user was doing.
        assertFalse(LinkGesture.opensLink(true, true, true));
        assertFalse(LinkGesture.opensLink(false, true, true));
    }

    @Test
    void theKeyIsNamedForTheReader() {
        assertEquals("Cmd", LinkGesture.modifierName(true));
        assertEquals("Ctrl", LinkGesture.modifierName(false));
    }
}
