package com.editora.diff;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LossyEditTest {

    private static final List<String> EXACT = List.of("one", "\f", "two \u001b[0m esc", "three", "end");
    private static final List<String> SHOWN = List.of("one", "", "two [0m esc", "three", "end");

    @Test
    void untouchedLinesKeepTheirExactText() {
        assertEquals(EXACT, LossyEdit.restore(EXACT, SHOWN, SHOWN));
    }

    @Test
    void onlyEditedLinesComeFromTheControl() {
        List<String> edited = List.of("one", "", "two [0m esc", "THREE", "added", "end");
        assertEquals(
                List.of("one", "\f", "two \u001b[0m esc", "THREE", "added", "end"),
                LossyEdit.restore(EXACT, SHOWN, edited));
        assertEquals(
                List.of("\f", "two \u001b[0m esc", "three"),
                LossyEdit.restore(EXACT, SHOWN, List.of("", "two [0m esc", "three")));
    }

    @Test
    void aRenderingThatIsNotLineForLineIsLeftAlone() {
        List<String> edited = List.of("a", "b");
        assertEquals(edited, LossyEdit.restore(List.of("a\rb", "c"), List.of("ab"), edited));
    }
}
