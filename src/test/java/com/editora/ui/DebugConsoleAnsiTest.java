package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.editora.run.AnsiText;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The Debug console draws a program's own colours, as the Run console does (review D104). */
class DebugConsoleAnsiTest {

    private static List<String> styles(StyleSpans<Collection<String>> spans) {
        List<String> out = new ArrayList<>();
        for (StyleSpan<Collection<String>> s : spans) {
            out.add(s.getLength() + ":" + String.join(",", s.getStyle()));
        }
        return out;
    }

    @Test
    void colouredOutputKeepsItsColoursOnTopOfTheLineStyle() {
        AnsiText.Chunk chunk = new AnsiText.Parser().feed("\u001b[31mred\u001b[0m plain\n");
        assertEquals("red plain\n", chunk.text());
        StyleSpans<Collection<String>> spans = DebugConsoleStyle.spans(chunk.text(), null, chunk.runs());
        assertEquals(List.of("3:ansi-red", "7:"), styles(spans));

        AnsiText.Chunk err = new AnsiText.Parser().feed("\u001b[1mboom\u001b[0m\n");
        StyleSpans<Collection<String>> both = DebugConsoleStyle.spans(err.text(), "run-stderr", err.runs());
        assertEquals("run-stderr,ansi-bold", styles(both).get(0).substring(2));
    }

    @Test
    void plainOutputIsStyledAsBefore() {
        AnsiText.Chunk chunk = new AnsiText.Parser().feed("hello\n");
        assertNull(DebugConsoleStyle.spans(chunk.text(), null, chunk.runs()));
    }
}
