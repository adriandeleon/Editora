package com.editora.terminal;

import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an OSC 52 would put on the clipboard. */
class OscClipboardTest {

    @Test
    void decodesTheClipboardText() {
        assertEquals(Optional.of("hello, wörld"), OscSequences.clipboardText("c;" + b64("hello, wörld")));
        // The selection letters are accepted and ignored: there is one clipboard.
        assertEquals(Optional.of("x"), OscSequences.clipboardText("p;" + b64("x")));
        assertEquals(Optional.of("x"), OscSequences.clipboardText(";" + b64("x")));
    }

    @Test
    void neverAnswersAQuery() {
        assertTrue(OscSequences.clipboardText("c;?").isEmpty());
    }

    @Test
    void rejectsWhatIsNotBase64OrNotText() {
        assertTrue(OscSequences.clipboardText("c;***").isEmpty());
        assertTrue(OscSequences.clipboardText(
                        "c;" + Base64.getEncoder().encodeToString(new byte[] {(byte) 0xff, (byte) 0xfe}))
                .isEmpty());
        assertTrue(OscSequences.clipboardText("c;").isEmpty());
        assertTrue(OscSequences.clipboardText("no semicolon").isEmpty());
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
    }
}
