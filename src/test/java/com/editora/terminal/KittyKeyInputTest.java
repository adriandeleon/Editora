package com.editora.terminal;

import java.nio.charset.StandardCharsets;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** JavaFX's press, typed and release turned into the protocol's press, repeat and release. */
class KittyKeyInputTest {

    private static final int DISAMBIGUATE_EVENTS = KittyKeys.DISAMBIGUATE | KittyKeys.EVENT_TYPES;
    private static final int ALL_EVENTS = KittyKeys.ALL_KEYS | KittyKeys.EVENT_TYPES;

    private final KittyKeyInput input = new KittyKeyInput();

    private static KeyEvent pressed(KeyCode code, String text, boolean ctrl) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", text, code, false, ctrl, false, false);
    }

    private static KeyEvent typed(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }

    private static KeyEvent released(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, false, false, false, false);
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8).replace("\u001b", "\\e");
    }

    @Test
    void aSecondPressWithNoReleaseIsARepeat() {
        assertEquals("\\e[A", text(input.pressed(pressed(KeyCode.UP, "", false), DISAMBIGUATE_EVENTS, true, false)));
        assertEquals(
                "\\e[1;1:2A", text(input.pressed(pressed(KeyCode.UP, "", false), DISAMBIGUATE_EVENTS, true, false)));
        assertEquals("\\e[1;1:3A", text(input.released(released(KeyCode.UP), DISAMBIGUATE_EVENTS, true, false)));
        // Released: the next press is a press again.
        assertEquals("\\e[A", text(input.pressed(pressed(KeyCode.UP, "", false), DISAMBIGUATE_EVENTS, true, false)));
    }

    @Test
    void aReleaseIsSentOnlyForAPressThatWasReported() {
        // Plain text went the legacy way, so the program never saw it go down.
        assertNull(input.pressed(pressed(KeyCode.A, "a", false), DISAMBIGUATE_EVENTS, true, false));
        assertNull(input.released(released(KeyCode.A), DISAMBIGUATE_EVENTS, true, false));
        // A key whose press a menu chord took never reached the view at all.
        assertNull(input.released(released(KeyCode.T), DISAMBIGUATE_EVENTS, true, false));
    }

    @Test
    void theTypedEventAfterAReportedPressIsDropped() {
        assertEquals(
                "\\e[97;5u", text(input.pressed(pressed(KeyCode.A, "\u0001", true), DISAMBIGUATE_EVENTS, true, false)));
        assertArrayEquals(new byte[0], input.typed(typed("\u0001"), DISAMBIGUATE_EVENTS));
        // Text with no reported press behind it is left to the legacy path.
        assertNull(input.typed(typed("b"), DISAMBIGUATE_EVENTS));
    }

    @Test
    void underAllKeysAKeysOwnTextIsDroppedButADeadKeysCompositionIsSent() {
        assertEquals("\\e[101u", text(input.pressed(pressed(KeyCode.E, "e", false), ALL_EVENTS, true, false)));
        assertArrayEquals(new byte[0], input.typed(typed("e"), ALL_EVENTS));
        input.released(released(KeyCode.E), ALL_EVENTS, true, false);
        // The second key of a dead-key pair: the press says e, the OS composed é.
        input.pressed(pressed(KeyCode.E, "e", false), ALL_EVENTS | KittyKeys.TEXT, true, false);
        assertEquals("\\e[0;;233u", text(input.typed(typed("é"), ALL_EVENTS | KittyKeys.TEXT)));
    }

    @Test
    void forgettingTheKeysMakesTheNextPressAPressAndOwesNoRelease() {
        input.pressed(pressed(KeyCode.UP, "", false), DISAMBIGUATE_EVENTS, true, false);
        input.reset();
        assertEquals("\\e[A", text(input.pressed(pressed(KeyCode.UP, "", false), DISAMBIGUATE_EVENTS, true, false)));
        input.reset();
        assertNull(input.released(released(KeyCode.UP), DISAMBIGUATE_EVENTS, true, false));
    }

    @Test
    void withNoFlagsNothingIsTouched() {
        assertNull(input.pressed(pressed(KeyCode.ESCAPE, "", false), 0, true, false));
        assertNull(input.typed(typed("a"), 0));
        assertNull(input.released(released(KeyCode.ESCAPE), 0, true, false));
    }
}
