package com.editora.terminal;

import java.nio.charset.StandardCharsets;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The key-to-bytes mapping. Pure enough to test without a toolkit. */
class KeyEncodingTest {

    /** Stands in for the emulator: records what it was asked and answers with a fixed sequence. */
    private static final class RecordingLookup implements KeyEncoding.CodeLookup {
        int lastKeyCode = -1;
        int lastModifiers = -1;
        byte[] answer;

        RecordingLookup(byte[] answer) {
            this.answer = answer;
        }

        @Override
        public byte[] apply(int awtKeyCode, int awtModifiers) {
            lastKeyCode = awtKeyCode;
            lastModifiers = awtModifiers;
            return answer;
        }
    }

    private static KeyEvent pressed(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta);
    }

    /**
     * A press carrying the text the platform reports, which for a composed chord is not the key.
     *
     * <p>Option+F on macOS arrives as {@code \u0192} — the ƒ the OS composed — on both the press
     * and the typed event. Recorded from a real key press through {@code -Deditora.terminal.keyTrace}.
     */
    private static KeyEvent pressedWithText(KeyCode code, String text, boolean shift, boolean alt) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", text, code, shift, false, alt, false);
    }

    private static KeyEvent typed(String character, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED, false, ctrl, alt, meta);
    }

    @Test
    void arrowKeysGoThroughTheEmulatorSoCursorKeyModeIsRespected() {
        RecordingLookup lookup = new RecordingLookup(new byte[] {0x1b, 'O', 'A'});
        byte[] out = KeyEncoding.encodePressed(pressed(KeyCode.UP, false, false, false, false), lookup, true);

        // 38 is java.awt.event.KeyEvent.VK_UP — the encoder's vocabulary.
        assertEquals(38, lookup.lastKeyCode);
        assertArrayEquals(new byte[] {0x1b, 'O', 'A'}, out);
    }

    @Test
    void modifiersUseAwtExtendedDownMasks() {
        RecordingLookup lookup = new RecordingLookup(new byte[] {1});
        KeyEncoding.encodePressed(pressed(KeyCode.LEFT, true, true, false, false), lookup, true);

        // SHIFT_DOWN_MASK | CTRL_DOWN_MASK. The plain masks (1 | 2) would silently miss every
        // modified binding, since the encoder falls back to the unmodified entry.
        assertEquals((1 << 6) | (1 << 7), lookup.lastModifiers);
    }

    @Test
    void altIsExcludedFromModifiersWhenItComposesCharactersInstead() {
        RecordingLookup lookup = new RecordingLookup(new byte[] {1});
        KeyEncoding.encodePressed(pressed(KeyCode.LEFT, false, false, true, false), lookup, false);
        assertEquals(0, lookup.lastModifiers);
    }

    @Test
    void controlLetterBecomesTheMatchingC0Byte() {
        RecordingLookup lookup = new RecordingLookup(null);
        assertArrayEquals(
                new byte[] {0x03}, // Ctrl+C -> ETX, which the tty turns into SIGINT
                KeyEncoding.encodePressed(pressed(KeyCode.C, false, true, false, false), lookup, true));
        assertArrayEquals(
                new byte[] {0x01}, // Ctrl+A -> SOH (readline: beginning-of-line)
                KeyEncoding.encodePressed(pressed(KeyCode.A, false, true, false, false), lookup, true));
    }

    @Test
    void tabAndEscapeAreSentEvenThoughTheEmulatorCannotEncodeThem() {
        // JediTerm's key encoder maps Enter and Backspace but not these two, and both are control
        // characters so the KEY_TYPED path discards them as well. Unhandled they reach the shell by
        // no route at all — and worse, the event goes unconsumed and JavaFX treats Tab as focus
        // traversal, moving focus out of the terminal so everything typed afterwards lands
        // elsewhere. That is what "the terminal freezes when I hit tab" was.
        RecordingLookup none = new RecordingLookup(null);
        assertArrayEquals(
                new byte[] {0x09},
                KeyEncoding.encodePressed(pressed(KeyCode.TAB, false, false, false, false), none, true));
        assertArrayEquals(
                new byte[] {0x1b},
                KeyEncoding.encodePressed(pressed(KeyCode.ESCAPE, false, false, false, false), none, true));
    }

    @Test
    void shiftTabIsBackTab() {
        // ESC [ Z (CBT) is what xterm sends and what readline and every TUI expect.
        RecordingLookup none = new RecordingLookup(null);
        assertArrayEquals(
                new byte[] {0x1b, '[', 'Z'},
                KeyEncoding.encodePressed(pressed(KeyCode.TAB, true, false, false, false), none, true));
    }

    @Test
    void theEmulatorStillWinsWhenItHasAnEncoding() {
        // The fallback must not shadow mode-dependent encodings — an emulator answer always wins.
        RecordingLookup lookup = new RecordingLookup(new byte[] {0x1b, 'O', 'A'});
        assertArrayEquals(
                new byte[] {0x1b, 'O', 'A'},
                KeyEncoding.encodePressed(pressed(KeyCode.TAB, false, false, false, false), lookup, true));
    }

    @Test
    void everyKeyWeClaimToHandleProducesSomething() {
        // The real invariant: a key in the VK table must never return null with no encoder, because
        // null means unconsumed, and unconsumed means JavaFX may act on it.
        RecordingLookup none = new RecordingLookup(null);
        for (KeyCode code : new KeyCode[] {KeyCode.TAB, KeyCode.ESCAPE, KeyCode.ENTER, KeyCode.BACK_SPACE}) {
            assertNotNull(
                    KeyEncoding.encodePressed(pressed(code, false, false, false, false), none, true),
                    () -> code + " must not fall through unconsumed");
        }
    }

    @Test
    void unmodifiedLetterFallsThroughToKeyTypedSoImeAndDeadKeysWork() {
        RecordingLookup lookup = new RecordingLookup(null);
        assertNull(KeyEncoding.encodePressed(pressed(KeyCode.A, false, false, false, false), lookup, true));
    }

    @Test
    void typedCharacterIsSentAsUtf8() {
        assertArrayEquals(
                "é".getBytes(StandardCharsets.UTF_8), KeyEncoding.encodeTyped(typed("é", false, false, false), true));
    }

    @Test
    void altPrefixesEscapeWhenActingAsMeta() {
        // From the press, not the typed event. Both produce 1b 62 where the platform leaves the
        // character alone, but only the press still does where the OS composes it: macOS turns
        // Option+B into ∫, and ESC-prefixing that sends bytes no shell can read as M-b.
        byte[] out = KeyEncoding.encodePressed(
                pressedWithText(KeyCode.B, "\u222b", false, true), new RecordingLookup(null), true);
        assertArrayEquals(new byte[] {0x1b, 'b'}, out); // M-b, readline's backward-word

        // And the typed twin is dropped, so the keystroke is not also sent as the composed glyph.
        assertNull(KeyEncoding.encodeTyped(typed("\u222b", false, true, false), true));
    }

    @Test
    void altComposesRatherThanMetaWhenConfiguredThatWay() {
        // On macOS with Option-as-compose, Option+b produces "∫" and must be sent as that character.
        byte[] out = KeyEncoding.encodeTyped(typed("∫", false, true, false), false);
        assertArrayEquals("∫".getBytes(StandardCharsets.UTF_8), out);
    }

    @Test
    void controlCharactersFromKeyTypedAreDroppedToAvoidDoubleSending() {
        // KEY_PRESSED already encoded this chord; some platforms deliver it again as a character.
        assertNull(KeyEncoding.encodeTyped(typed("", true, false, false), true));
        assertNull(KeyEncoding.encodeTyped(typed("\r", false, false, false), true));
    }

    @Test
    void altGrComposesCharactersWithoutSendingControlBytes() {
        for (boolean meta : new boolean[] {false, true}) {
            assertNull(KeyEncoding.encodePressed(
                    pressed(KeyCode.Q, false, true, true, false), new RecordingLookup(null), meta));
            assertArrayEquals(
                    "@".getBytes(StandardCharsets.UTF_8), KeyEncoding.encodeTyped(typed("@", true, true, false), meta));
        }
    }

    @Test
    void ctrlAltChordsAreEscapeAndTheControlByteWhereAltGrIsToldApart() {
        RecordingLookup none = new RecordingLookup(null);
        assertArrayEquals(
                new byte[] {0x1b, 0x06},
                KeyEncoding.encodePressed(pressed(KeyCode.F, false, true, true, false), none, true, true),
                "C-M-f");
        assertNull(KeyEncoding.encodeTyped(typed("f", true, true, false), true, true), "and its character is not sent");
        assertNull(
                KeyEncoding.encodePressed(pressed(KeyCode.Q, false, true, true, false), none, true, false),
                "where Ctrl+Alt is AltGr (Windows), it still composes");
    }

    @Test
    void ctrlDigitsAreXtermsControls() {
        RecordingLookup none = new RecordingLookup(null);
        assertArrayEquals(
                new byte[] {0x1b},
                KeyEncoding.encodePressed(pressed(KeyCode.DIGIT3, false, true, false, false), none, true));
        assertArrayEquals(
                new byte[] {0x1c},
                KeyEncoding.encodePressed(pressed(KeyCode.DIGIT4, false, true, false, false), none, true));
        assertArrayEquals(
                new byte[] {0x7f},
                KeyEncoding.encodePressed(pressed(KeyCode.DIGIT8, false, true, false, false), none, true));
    }

    @Test
    void pasteNormalisesNewlinesToCarriageReturns() {
        // A shell reading a line wants CR; sending LF submits nothing and looks like a hang.
        assertEquals("a\rb\rc", new String(KeyEncoding.encodePaste("a\r\nb\nc", false), StandardCharsets.UTF_8));
    }

    @Test
    void bracketedPasteWrapsTheTextWhenTheShellAskedForIt() {
        String out = new String(KeyEncoding.encodePaste("ls", true), StandardCharsets.UTF_8);
        assertEquals("[200~ls[201~", out);
    }

    @Test
    void aPasteCannotEndTheBracketEarlyOrTypeControlKeys() {
        // Text copied from a web page can hide ESC[201~: the shell would take the rest as typing and run it.
        String clip = "echo harmless\u001b[201~touch /tmp/pwned\nmore\u0003\u0004\u009b201~";
        String out = new String(KeyEncoding.encodePaste(clip, true), StandardCharsets.UTF_8);
        assertEquals("\u001b[200~echo harmless[201~touch /tmp/pwned\rmore201~\u001b[201~", out);
        assertEquals(1, out.split("\u001b\\[201~", -1).length - 1, "only the closing bracket ends the paste");
    }

    @Test
    void aPasteKeepsTabsLineBreaksAndUnicode() {
        String out = new String(KeyEncoding.encodePaste("a\tb\r\nc é 日本 😀\u007f", false), StandardCharsets.UTF_8);
        assertEquals("a\tb\rc é 日本 😀", out);
    }

    // --- Alt as Meta, where the composed character is the trap ----------------------------------

    @Test
    void metaSendsEscapeAndTheUnmodifiedKey() {
        // M-f is forward-word: ESC then 'f'. Not ESC then whatever Option composed.
        byte[] out = KeyEncoding.encodePressed(
                pressedWithText(KeyCode.F, "\u0192", false, true), new RecordingLookup(null), true);
        assertArrayEquals(new byte[] {0x1b, 'f'}, out);
    }

    @Test
    void theComposedCharacterIsNeverSentInMetaMode() {
        // The bug this pins: prefixing ESC to ƒ's UTF-8 put 1b c6 92 on the wire, the shell ate c6
        // with the escape, and the leftover 92 is a lone continuation byte — invalid UTF-8, which
        // renders as an unprintable box wherever the cursor happened to be.
        assertNull(KeyEncoding.encodeTyped(typed("\u0192", false, true, false), true));
    }

    @Test
    void shiftPicksTheUpperCaseWidget() {
        // M-u and M-U are different readline widgets, so the case has to survive.
        byte[] out = KeyEncoding.encodePressed(
                pressedWithText(KeyCode.U, "\u00a8", true, true), new RecordingLookup(null), true);
        assertArrayEquals(new byte[] {0x1b, 'U'}, out);
    }

    @Test
    void withAltAsMetaOffTheComposedCharacterIsTheWholePoint() {
        // Option is a compose key then, and ƒ is what the user asked for.
        assertNull(KeyEncoding.encodePressed(
                pressedWithText(KeyCode.F, "\u0192", false, true), new RecordingLookup(null), false));
        assertArrayEquals(
                "\u0192".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                KeyEncoding.encodeTyped(typed("\u0192", false, true, false), false));
    }

    @Test
    void altGrIsNotMeta() {
        // Ctrl+Alt is how Windows and Linux keyboards reach @ \ and friends. Reading it as Meta
        // would make those layouts unable to type characters they have no other key for.
        assertArrayEquals(new byte[] {'@'}, KeyEncoding.encodeTyped(typed("@", true, true, false), true));
        assertArrayEquals(new byte[] {'@'}, KeyEncoding.encodeTyped(typed("@", false, true, false), false));
    }

    @Test
    void aKeyWithNoPlainCharacterHasNoMetaForm() {
        // Nothing to prefix, and inventing a byte would send a keystroke the user never made.
        assertNull(KeyEncoding.metaBase(pressedWithText(KeyCode.SHIFT, "", false, true)));
    }

    /** A lookup that only knows {@code key} without modifiers. */
    private static KeyEncoding.CodeLookup onlyPlain(byte[] plain) {
        return (vk, mods) -> mods == 0 ? plain : null;
    }

    @Test
    void aModifiedKeyTheEmulatorDoesNotKnowFallsBackToThePlainKeyWithMeta() {
        byte[] plain = {0x1b, '[', 'H'};
        assertArrayEquals(
                new byte[] {0x1b, 0x1b, '[', 'H'},
                KeyEncoding.encodePressed(
                        pressed(KeyCode.HOME, false, false, true, false), onlyPlain(plain), true, false),
                "Alt as Meta prefixes ESC");
        assertArrayEquals(
                plain,
                KeyEncoding.encodePressed(
                        pressed(KeyCode.HOME, false, false, true, false), onlyPlain(plain), false, false),
                "Alt not as Meta sends the plain key");
        assertArrayEquals(
                plain,
                KeyEncoding.encodePressed(
                        pressed(KeyCode.HOME, false, true, false, false), onlyPlain(plain), true, false));
    }

    @Test
    void aKeyTheEmulatorDoesNotKnowAtAllUsesTheLiteralBytes() {
        KeyEncoding.CodeLookup none = (vk, mods) -> null;
        assertArrayEquals(
                new byte[] {0x09},
                KeyEncoding.encodePressed(pressed(KeyCode.TAB, false, false, false, false), none, true));
        assertArrayEquals(
                new byte[] {0x1b, '[', 'Z'},
                KeyEncoding.encodePressed(pressed(KeyCode.TAB, true, false, false, false), none, true));
        assertArrayEquals(
                new byte[] {0x1b, 0x1b},
                KeyEncoding.encodePressed(pressed(KeyCode.ESCAPE, false, false, true, false), none, true),
                "Meta-Escape");
        assertArrayEquals(
                new byte[] {0x0d},
                KeyEncoding.encodePressed(pressed(KeyCode.ENTER, false, false, false, false), none, true));
        assertArrayEquals(
                new byte[] {0x7f},
                KeyEncoding.encodePressed(pressed(KeyCode.BACK_SPACE, false, false, false, false), none, true));
        assertNull(
                KeyEncoding.encodePressed(pressed(KeyCode.PAGE_UP, false, false, false, false), none, true),
                "no mapping and no literal: the key is left alone");
    }

    @Test
    void controlChordsCoverTheDigitRowAndPunctuation() {
        KeyEncoding.CodeLookup none = (vk, mods) -> null;
        Object[][] cases = {
            {KeyCode.SPACE, 0x00},
            {KeyCode.DIGIT2, 0x00},
            {KeyCode.DIGIT3, 0x1b},
            {KeyCode.DIGIT4, 0x1c},
            {KeyCode.DIGIT5, 0x1d},
            {KeyCode.DIGIT6, 0x1e},
            {KeyCode.DIGIT7, 0x1f},
            {KeyCode.DIGIT8, 0x7f},
            {KeyCode.OPEN_BRACKET, 0x1b},
            {KeyCode.BACK_SLASH, 0x1c},
            {KeyCode.CLOSE_BRACKET, 0x1d},
            {KeyCode.MINUS, 0x1f},
            {KeyCode.SLASH, 0x1f},
            {KeyCode.Z, 0x1a}
        };
        for (Object[] c : cases) {
            KeyCode code = (KeyCode) c[0];
            assertArrayEquals(
                    new byte[] {(byte) (int) (Integer) c[1]},
                    KeyEncoding.encodePressed(pressed(code, false, true, false, false), none, true),
                    code.toString());
        }
        assertNull(KeyEncoding.controlByte(KeyCode.DIGIT1));
        assertNull(
                KeyEncoding.encodePressed(pressed(KeyCode.DIGIT1, false, true, false, false), none, true),
                "Ctrl+1 has no control form");
        assertNull(
                KeyEncoding.encodePressed(pressed(KeyCode.A, false, true, false, true), none, true),
                "Ctrl+Meta is not a control byte");
    }

    @Test
    void ctrlAltIsMetaPrefixesTheControlByteOnlyWhenAsked() {
        KeyEncoding.CodeLookup none = (vk, mods) -> null;
        assertArrayEquals(
                new byte[] {0x1b, 0x05},
                KeyEncoding.encodePressed(pressed(KeyCode.E, false, true, true, false), none, true, true));
        assertNull(
                KeyEncoding.encodePressed(pressed(KeyCode.E, false, true, true, false), none, true, false),
                "Ctrl+Alt is AltGr unless told otherwise");
        assertNull(KeyEncoding.encodePressed(pressed(KeyCode.DIGIT1, false, true, true, false), none, true, true));
        assertNull(KeyEncoding.encodePressed(pressed(KeyCode.E, false, true, true, true), none, true, true));
    }

    @Test
    void metaUsesTheKeysOwnCharacterWhenTheTextIsNotPlain() {
        KeyEncoding.CodeLookup none = (vk, mods) -> null;
        assertArrayEquals(
                new byte[] {0x1b, 'F'},
                KeyEncoding.encodePressed(pressedWithText(KeyCode.F, "\u0192", true, true), none, true),
                "Shift asks for the upper case");
        assertArrayEquals(
                new byte[] {0x1b, '1'},
                KeyEncoding.encodePressed(pressedWithText(KeyCode.DIGIT1, "", false, true), none, true));
        assertNull(
                KeyEncoding.encodePressed(pressedWithText(KeyCode.UNDEFINED, "", false, true), none, true),
                "a key with no character");
        assertNull(
                KeyEncoding.encodePressed(pressedWithText(KeyCode.F, "f", false, true), none, false),
                "Alt is not Meta: the typed event sends the character");
        assertNull(KeyEncoding.encodePressed(pressed(KeyCode.F, false, false, true, true), none, true));
        assertArrayEquals(new byte[] {'x'}, KeyEncoding.metaBase(pressedWithText(KeyCode.F, "x", false, true)));
        assertNull(KeyEncoding.metaBase(pressedWithText(KeyCode.UNDEFINED, "ab", false, true)));
    }

    @Test
    void typedCharactersFromChordsAreDropped() {
        assertNull(KeyEncoding.encodeTyped(typed("", false, false, false), true));
        assertNull(KeyEncoding.encodeTyped(typed(KeyEvent.CHAR_UNDEFINED, false, false, false), true));
        assertNull(KeyEncoding.encodeTyped(typed("\u007f", false, false, false), true), "DEL came from the press");
        assertNull(KeyEncoding.encodeTyped(typed("\u0001", false, false, false), true));
        assertArrayEquals(
                "\u0001x".getBytes(StandardCharsets.UTF_8),
                KeyEncoding.encodeTyped(typed("\u0001x", false, false, false), true),
                "two characters are text");
        assertNull(KeyEncoding.encodeTyped(typed("a", true, false, false), true), "Ctrl");
        assertNull(KeyEncoding.encodeTyped(typed("a", false, false, true), true), "Meta");
        assertArrayEquals(
                "@".getBytes(StandardCharsets.UTF_8),
                KeyEncoding.encodeTyped(typed("@", true, true, false), true, false),
                "AltGr composes");
        assertNull(KeyEncoding.encodeTyped(typed("@", true, true, false), true, true), "Ctrl+Alt as Meta was sent");
        assertNull(KeyEncoding.encodeTyped(typed("a", false, true, false), true), "Alt as Meta was sent by the press");
        assertArrayEquals(
                "\u00e5".getBytes(StandardCharsets.UTF_8),
                KeyEncoding.encodeTyped(typed("\u00e5", false, true, false), false),
                "Alt composing");
    }
}
