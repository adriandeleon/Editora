package com.editora.terminal;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OSC 9 and 777 as programs send them, and the ConEmu subcommands that share the number. */
class NotificationSequencesTest {

    private static Optional<OscSequences.Notification> n(int code, String payload) {
        return OscSequences.notification(code, payload);
    }

    @Test
    void iterm2sFormIsTheMessage() {
        assertEquals(Optional.of(new OscSequences.Notification("", "Build finished")), n(9, "Build finished"));
        assertEquals(Optional.of(new OscSequences.Notification("", "42 tests passed")), n(9, "42 tests passed"));
    }

    @Test
    void urxvtsFormHasATitleAndABodyWhichMayHoldSemicolons() {
        assertEquals(
                Optional.of(new OscSequences.Notification("make", "done; 0 errors")),
                n(777, "notify;make;done; 0 errors"));
    }

    @Test
    void aTitleWithNoBodyIsShownAsTheMessage() {
        assertEquals(Optional.of(new OscSequences.Notification("", "Done")), n(777, "notify;Done"));
        assertEquals(Optional.of(new OscSequences.Notification("", "Done")), n(777, "notify;Done;"));
    }

    @Test
    void conEmuSubcommandsAreNotNotifications() {
        assertTrue(n(9, "4;1;50").isEmpty(), "a progress bar");
        assertTrue(n(9, "4").isEmpty());
        assertTrue(n(9, "9;C:\\Users\\adl").isEmpty(), "a directory");
        assertTrue(n(9, "").isEmpty());
    }

    @Test
    void otherUrxvtExtensionsAreIgnored() {
        assertTrue(n(777, "preexec").isEmpty());
        assertTrue(n(777, "notify").isEmpty());
    }

    @Test
    void controlCharactersCannotForgeLinesOrEscape() {
        var cleaned = n(777, "notify;a\u001b[31mred;line one\nline two\u0007").orElseThrow();
        assertEquals("a [31mred", cleaned.title());
        assertEquals("line one line two", cleaned.body());
    }

    @Test
    void longTextIsCut() {
        var cut = n(9, "x".repeat(5000)).orElseThrow();
        assertEquals(OscSequences.MAX_BODY, cut.body().length());
        var title = n(777, "notify;" + "t".repeat(500) + ";b").orElseThrow();
        assertEquals(OscSequences.MAX_TITLE, title.title().length());
    }

    @Test
    void anythingElseUnderTheNumbersIsNoNotification() {
        org.junit.jupiter.api.Assertions.assertTrue(
                OscSequences.notification(OscSequences.NOTIFY, null).isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(
                OscSequences.notification(OscSequences.NOTIFY_RXVT, "notify").isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(
                OscSequences.notification(OscSequences.NOTIFY_RXVT, "other;t;b").isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(
                OscSequences.notification(2, "hello").isEmpty());
        org.junit.jupiter.api.Assertions.assertTrue(
                OscSequences.clipboardText(null).isEmpty());
        assertEquals(-1, OscSequences.conEmuSubcommand("1234"), "four digits are a message");
        assertEquals(-1, OscSequences.conEmuSubcommand(""));
    }

    @Test
    void controlCharactersBecomeOneSpaceAndTheBodyIsCutWhole() {
        assertEquals(
                "a b c",
                OscSequences.notification(
                                OscSequences.NOTIFY, "\u0001a\u0001\u0002b" + (char) 0x2028 + (char) 0x2029 + "c")
                        .orElseThrow()
                        .body());
        String smile = new String(Character.toChars(0x1F600));
        String body = "x".repeat(OscSequences.MAX_BODY - 1) + smile;
        assertEquals(
                "x".repeat(OscSequences.MAX_BODY - 1),
                OscSequences.notification(OscSequences.NOTIFY, body)
                        .orElseThrow()
                        .body(),
                "a pair that would cross the limit is left out");
    }
}
