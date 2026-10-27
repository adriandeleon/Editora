package com.editora.terminal;

import javafx.scene.input.KeyCode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Key events in the kitty keyboard protocol, against its specification's own examples. */
class KittyKeysTest {

    private static final int DISAMBIGUATE = KittyKeys.DISAMBIGUATE;
    private static final int EVENTS = KittyKeys.EVENT_TYPES;
    private static final int ALTERNATES = KittyKeys.ALTERNATES;
    private static final int ALL = KittyKeys.ALL_KEYS;
    private static final int TEXT = KittyKeys.TEXT;

    /** A key: modifiers as a string of letters — s(hift) a(lt) c(trl) m (super) C(aps) N(um). */
    private static KittyKeys.Key key(KeyCode code, String text, String mods) {
        return new KittyKeys.Key(
                code,
                text,
                mods.contains("s"),
                mods.contains("a"),
                mods.contains("c"),
                mods.contains("m"),
                mods.contains("C"),
                mods.contains("N"));
    }

    private static String press(KittyKeys.Key key, int flags) {
        return KittyKeys.visible(KittyKeys.encode(key, flags, KittyKeys.Type.PRESS));
    }

    private static String repeat(KittyKeys.Key key, int flags) {
        return KittyKeys.visible(KittyKeys.encode(key, flags, KittyKeys.Type.REPEAT));
    }

    private static String release(KittyKeys.Key key, int flags) {
        return KittyKeys.visible(KittyKeys.encode(key, flags, KittyKeys.Type.RELEASE));
    }

    @Test
    void withNoFlagsEverythingIsLegacy() {
        assertSame(KittyKeys.LEGACY, KittyKeys.encode(key(KeyCode.ESCAPE, "", ""), 0, KittyKeys.Type.PRESS));
        assertEquals("legacy", press(key(KeyCode.A, "a", "c"), 0));
    }

    @Test
    void disambiguateLeavesTextAsText() {
        assertEquals("legacy", press(key(KeyCode.A, "a", ""), DISAMBIGUATE));
        assertEquals("legacy", press(key(KeyCode.A, "A", "s"), DISAMBIGUATE));
        assertEquals("legacy", press(key(KeyCode.DIGIT3, "#", "s"), DISAMBIGUATE));
    }

    @Test
    void disambiguateTurnsModifiedKeysIntoCsiU() {
        // The specification's table: ctrl+shift+i is CSI 105;6u, never ctrl+I.
        assertEquals("\\e[105;6u", press(key(KeyCode.I, "I", "sc"), DISAMBIGUATE));
        assertEquals("\\e[97;5u", press(key(KeyCode.A, "\u0001", "c"), DISAMBIGUATE));
        assertEquals("\\e[97;3u", press(key(KeyCode.A, "a", "a"), DISAMBIGUATE));
        assertEquals("\\e[59;7u", press(key(KeyCode.SEMICOLON, ";", "ac"), DISAMBIGUATE));
        assertEquals("\\e[99;9u", press(key(KeyCode.C, "c", "m"), DISAMBIGUATE));
    }

    @Test
    void escapeIsNoLongerTheStartOfEverySequence() {
        assertEquals("\\e[27u", press(key(KeyCode.ESCAPE, "", ""), DISAMBIGUATE));
        assertEquals("\\e[27;2u", press(key(KeyCode.ESCAPE, "", "s"), DISAMBIGUATE));
    }

    @Test
    void enterTabAndBackspaceKeepTheirBytesUntilModified() {
        assertEquals("legacy", press(key(KeyCode.ENTER, "\r", ""), DISAMBIGUATE));
        assertEquals("legacy", press(key(KeyCode.TAB, "\t", ""), DISAMBIGUATE));
        assertEquals("legacy", press(key(KeyCode.BACK_SPACE, "", ""), DISAMBIGUATE));
        // The reason programs want the protocol at all: Shift+Enter is its own key.
        assertEquals("\\e[13;2u", press(key(KeyCode.ENTER, "\r", "s"), DISAMBIGUATE));
        // Not CSI Z: Shift+Tab is Tab with Shift, which is the erratum the spec fixes.
        assertEquals("\\e[9;6u", press(key(KeyCode.TAB, "", "sc"), DISAMBIGUATE));
    }

    @Test
    void functionalKeysUseTheirOwnForms() {
        assertEquals("\\e[A", press(key(KeyCode.UP, "", ""), DISAMBIGUATE));
        assertEquals("\\e[1;5A", press(key(KeyCode.UP, "", "c"), DISAMBIGUATE));
        assertEquals("\\e[P", press(key(KeyCode.F1, "", ""), DISAMBIGUATE));
        assertEquals("\\e[13~", press(key(KeyCode.F3, "", ""), DISAMBIGUATE));
        assertEquals("\\e[2~", press(key(KeyCode.INSERT, "", ""), DISAMBIGUATE));
        assertEquals("\\e[6;2~", press(key(KeyCode.PAGE_DOWN, "", "s"), DISAMBIGUATE));
        assertEquals("\\e[57376u", press(key(KeyCode.F13, "", ""), DISAMBIGUATE));
        assertEquals("\\e[57419u", press(key(KeyCode.KP_UP, "", ""), DISAMBIGUATE));
    }

    @Test
    void locksAreReportedExceptOnKeysThatType() {
        assertEquals("\\e[1;129A", press(key(KeyCode.UP, "", "N"), DISAMBIGUATE));
        assertEquals("\\e[97;69u", press(key(KeyCode.A, "\u0001", "cC"), DISAMBIGUATE));
        assertEquals("legacy", press(key(KeyCode.A, "A", "C"), DISAMBIGUATE));
    }

    @Test
    void eventTypesReportRepeatAndReleaseForEscapedKeysOnly() {
        int flags = DISAMBIGUATE | EVENTS;
        assertEquals("\\e[1;1:2A", repeat(key(KeyCode.UP, "", ""), flags));
        assertEquals("\\e[1;1:3A", release(key(KeyCode.UP, "", ""), flags));
        assertEquals("\\e[97;5:3u", release(key(KeyCode.A, "", "c"), flags));
        // Text has no events without all keys, and Enter none so `reset` stays typeable.
        assertEquals("nothing", release(key(KeyCode.A, "a", ""), flags));
        assertEquals("nothing", release(key(KeyCode.ENTER, "\r", ""), flags));
        // A repeat without the flag is just another press.
        assertEquals("\\e[A", repeat(key(KeyCode.UP, "", ""), DISAMBIGUATE));
        assertEquals("nothing", release(key(KeyCode.UP, "", ""), DISAMBIGUATE));
    }

    @Test
    void eventTypesAloneChangeOnlyTheRelease() {
        assertEquals("legacy", press(key(KeyCode.UP, "", ""), EVENTS));
        assertEquals("legacy", press(key(KeyCode.ESCAPE, "", ""), EVENTS));
        assertEquals("\\e[1;1:3A", release(key(KeyCode.UP, "", ""), EVENTS));
    }

    @Test
    void allKeysReportsEveryKeyIncludingTheModifiers() {
        assertEquals("\\e[97u", press(key(KeyCode.A, "a", ""), ALL));
        assertEquals("\\e[97;2u", press(key(KeyCode.A, "A", "s"), ALL));
        assertEquals("\\e[13u", press(key(KeyCode.ENTER, "\r", ""), ALL));
        assertEquals("\\e[13;1:3u", release(key(KeyCode.ENTER, "\r", ""), ALL | EVENTS));
        assertEquals("\\e[57441;2u", press(key(KeyCode.SHIFT, "", "s"), ALL));
        // Releasing Shift clears its own bit.
        assertEquals("\\e[57441;1:3u", release(key(KeyCode.SHIFT, "", ""), ALL | EVENTS));
        assertEquals("\\e[57442;5u", press(key(KeyCode.CONTROL, "", ""), ALL));
        assertEquals("legacy", press(key(KeyCode.SHIFT, "", "s"), DISAMBIGUATE));
    }

    @Test
    void theTextRidesAlongWhenAskedFor() {
        // The specification's example: shift+a is CSI 97;2;65u.
        assertEquals("\\e[97;2;65u", press(key(KeyCode.A, "A", "s"), ALL | TEXT));
        assertEquals("\\e[97;;97u", press(key(KeyCode.A, "a", ""), ALL | TEXT));
        // Ctrl types no text, so there is none to send.
        assertEquals("\\e[97;5u", press(key(KeyCode.A, "\u0001", "c"), ALL | TEXT));
        assertEquals("\\e[97;1:3u", release(key(KeyCode.A, "a", ""), ALL | TEXT | EVENTS));
    }

    @Test
    void alternatesCarryTheShiftedKey() {
        assertEquals("\\e[97:65;6u", press(key(KeyCode.A, "A", "sc"), DISAMBIGUATE | ALTERNATES));
        assertEquals("\\e[49:33;2u", press(key(KeyCode.DIGIT1, "!", "s"), ALL | ALTERNATES));
        // Only with Shift.
        assertEquals("\\e[97;5u", press(key(KeyCode.A, "\u0001", "c"), DISAMBIGUATE | ALTERNATES));
    }

    @Test
    void theKeyIsTheUnshiftedOneOfTheCurrentLayout() {
        // Cyrillic С on the C key is reported as С, not as the c on the keycap.
        assertEquals("\\e[1089u", press(key(KeyCode.C, "с", ""), ALL));
        // A key JavaFX has no code for is known by what it typed.
        assertEquals("\\e[252u", press(key(KeyCode.UNDEFINED, "ü", ""), ALL));
        assertEquals("legacy", press(key(KeyCode.UNDEFINED, "", "c"), ALL));
    }

    @Test
    void theKeypadTypesUntilModifiedAndHasItsOwnNumbers() {
        assertEquals("legacy", press(key(KeyCode.NUMPAD1, "1", ""), DISAMBIGUATE));
        assertEquals("\\e[57400;5u", press(key(KeyCode.NUMPAD1, "1", "c"), DISAMBIGUATE));
        assertEquals("\\e[57400;;49u", press(key(KeyCode.NUMPAD1, "1", ""), ALL | TEXT));
    }

    @Test
    void textWithNoKeyIsKeyZero() {
        assertEquals("\\e[0;;233u", KittyKeys.visible(KittyKeys.textEvent("é", ALL | TEXT)));
        assertEquals("legacy", KittyKeys.visible(KittyKeys.textEvent("é", ALL)));
        assertEquals("nothing", KittyKeys.visible(KittyKeys.textEvent("\u0001", ALL | TEXT)));
    }
}
