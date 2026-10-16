package com.editora.run;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Terminal colour codes read out of program output instead of shown as raw text. */
class AnsiTextTest {

    private static AnsiText.Chunk feed(String s) {
        return new AnsiText.Parser().feed(s);
    }

    @Test
    void sgrColoursBecomeStyleRunsAndLeaveTheText() {
        AnsiText.Chunk c = feed("\u001b[31mred\u001b[0m and \u001b[1;32mbold green\u001b[0m");
        assertEquals("red and bold green", c.text());
        assertEquals(
                List.of(
                        new AnsiText.Run(3, Set.of("ansi-red")),
                        new AnsiText.Run(5, Set.of()),
                        new AnsiText.Run(10, Set.of("ansi-green", "ansi-bold"))),
                c.runs());
    }

    @Test
    void theColourCarriesOverToTheNextChunkOfTheSameStream() {
        AnsiText.Parser p = new AnsiText.Parser();
        p.feed("\u001b[0;34m");
        assertEquals(
                List.of(new AnsiText.Run(4, Set.of("ansi-blue"))),
                p.feed("next").runs());
        p.feed("\u001b[39m");
        assertEquals(List.of(new AnsiText.Run(5, Set.of())), p.feed("plain").runs());
    }

    @Test
    void aSequenceSplitAcrossChunksIsHeldBack() {
        AnsiText.Parser p = new AnsiText.Parser();
        assertEquals("ok ", p.feed("ok \u001b[3").text());
        AnsiText.Chunk rest = p.feed("3mwarn");
        assertEquals("warn", rest.text());
        assertEquals(List.of(new AnsiText.Run(4, Set.of("ansi-yellow"))), rest.runs());
    }

    @Test
    void brightAnd256ColoursMapToTheBasicOnesAndOthersAreDropped() {
        assertEquals(Set.of("ansi-cyan"), feed("\u001b[96mx").runs().get(0).classes());
        assertEquals(
                Set.of("ansi-magenta"), feed("\u001b[38;5;5mx").runs().get(0).classes());
        assertEquals(Set.of(), feed("\u001b[38;5;208mx").runs().get(0).classes());
        assertEquals(Set.of(), feed("\u001b[38;2;10;20;30mx").runs().get(0).classes());
        assertEquals(Set.of("ansi-red"), feed("\u001b[41;31mx").runs().get(0).classes(), "background ignored");
    }

    @Test
    void eraseLineIsACarriageReturnAndClearScreenIsMarkedOtherControlsVanish() {
        assertEquals("xxxx\rcleared", feed("xxxx\u001b[2K\rcleared").text().replace("\r\r", "\r"));
        assertEquals("\f", feed("\u001b[H\u001b[2J\u001b[3J").text());
        assertEquals("ab", feed("a\u001b[1A\u001b]0;title\u0007\u001b7b").text());
        assertEquals("plain", feed("\u001b(B\u001b[mplain").text(), "tput sgr0's charset choice, no stray B");
    }
}
