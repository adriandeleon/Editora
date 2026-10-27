package com.editora.terminal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.jediterm.terminal.ArrayTerminalDataStream;
import com.jediterm.terminal.TerminalDataStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which sequences reach the emulator once the keyboard modes are taken out. */
class KeyboardModeInterceptorTest {

    private final List<String> seen = new ArrayList<>();

    private String readAll(String input) throws IOException {
        TerminalDataStream stream = new KeyboardModeInterceptor(
                new ArrayTerminalDataStream(input.toCharArray()), new KeyboardModeInterceptor.Listener() {
                    @Override
                    public void keyboardMode(char marker, String params) {
                        seen.add(marker + params);
                    }

                    @Override
                    public void reset() {
                        seen.add("reset");
                    }
                });
        StringBuilder out = new StringBuilder();
        try {
            while (true) out.append(stream.getChar());
        } catch (TerminalDataStream.EOF end) {
            return out.toString();
        }
    }

    @Test
    void takesAllFourModeSequences() {
        assertEquals("abc", read("a\u001b[>1ub\u001b[<u\u001b[=5;2u\u001b[?uc"));
        assertEquals(List.of(">1", "<", "=5;2", "?"), seen);
    }

    @Test
    void leavesEverythingElseExactlyAsItWas() {
        // SCORC with no marker, a private mode, a cursor move and a colour: all the emulator's.
        String others = "\u001b[u\u001b[?25h\u001b[1;2H\u001b[38;5;1m\u001bM";
        assertEquals(others, read(others));
        assertEquals(List.of(), seen);
    }

    @Test
    void aMarkedSequenceWithAnotherEndingIsNotOurs() {
        // DA2 is CSI > c — the same marker, not a keyboard mode.
        assertEquals("\u001b[>c\u001b[>0;1c", read("\u001b[>c\u001b[>0;1c"));
        assertEquals(List.of(), seen);
    }

    @Test
    void aResetIsReportedAndStillReachesTheEmulator() {
        assertEquals("\u001bcx", read("\u001bcx"));
        assertEquals(List.of("reset"), seen);
    }

    private String read(String input) {
        try {
            return readAll(input);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
