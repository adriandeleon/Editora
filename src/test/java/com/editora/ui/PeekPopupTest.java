package com.editora.ui;

import java.util.Collection;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two pure pieces of the peek popup: which lines it shows, and how a tokenized run is cut back into
 * lines. Both are the kind of index arithmetic that reads correct and ships off by one.
 */
class PeekPopupTest {

    private static String doc(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append("line").append(i).append('\n');
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("the window shown")
    class Window {

        @Test
        void centresOnTheDefinitionWithContextAbove() {
            PeekPopup.Snippet s = PeekPopup.build("t", doc(100), 40, "java");
            assertEquals(40 - PeekPopup.LINES_BEFORE, s.firstLine());
            assertEquals(PeekPopup.LINES_BEFORE, s.focusIndex(), "the focus index is relative to the window");
            assertEquals("line40", s.lines().get(s.focusIndex()), "the focused row must BE the definition");
        }

        @Test
        void clampsAtTheTopOfTheFile() {
            PeekPopup.Snippet s = PeekPopup.build("t", doc(100), 0, "java");
            assertEquals(0, s.firstLine());
            assertEquals(0, s.focusIndex());
            assertEquals("line0", s.lines().get(0));
        }

        @Test
        void clampsAtTheEndOfTheFile() {
            PeekPopup.Snippet s = PeekPopup.build("t", doc(6), 5, "java");
            assertEquals("line5", s.lines().get(s.focusIndex()));
            assertTrue(s.lines().size() <= 6 + 1, "must not invent lines past the end: " + s.lines());
        }

        @Test
        void aTargetLinePastTheEndStillProducesSomething() {
            // A stale index from a file edited since the server read it must not blow up the popup.
            PeekPopup.Snippet s = PeekPopup.build("t", doc(3), 99, "java");
            assertTrue(s.focusIndex() >= 0 && s.focusIndex() < s.lines().size());
        }

        @Test
        void aSingleLineFileIsSafe() {
            PeekPopup.Snippet s = PeekPopup.build("t", "only", 0, "java");
            assertEquals(List.of("only"), s.lines());
            assertEquals(0, s.focusIndex());
        }

        @Test
        void anEmptyDocumentIsSafe() {
            PeekPopup.Snippet s = PeekPopup.build("t", "", 0, "java");
            assertEquals(1, s.lines().size());
            assertEquals(0, s.focusIndex());
        }
    }

    @Nested
    @DisplayName("splitting tokenized text back into lines")
    class Split {

        private static StyleSpans<Collection<String>> spans(int... lengths) {
            StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
            for (int len : lengths) {
                b.add(List.of("kw"), len);
            }
            return b.create();
        }

        private static List<String> texts(List<List<javafx.scene.text.Text>> lines) {
            return lines.stream()
                    .map(l -> l.stream().map(javafx.scene.text.Text::getText).reduce("", String::concat))
                    .toList();
        }

        @Test
        void oneSpanPerLineRoundTrips() {
            String text = "aa\nbb\ncc";
            assertEquals(List.of("aa", "bb", "cc"), texts(PeekPopup.splitByLine(text, spans(3, 3, 2))));
        }

        @Test
        void aSpanCrossingALineBoundaryIsCutAtIt() {
            // The spans cover the joined text INCLUDING newlines, so a block comment or string spanning
            // lines arrives as one span and has to be split, not assigned to whichever side.
            String text = "aa\nbb\ncc";
            assertEquals(List.of("aa", "bb", "cc"), texts(PeekPopup.splitByLine(text, spans(8))));
        }

        @Test
        void producesOneEntryPerLineSoItCanBeMatchedToTheDisplayedRows() {
            String text = "a\nb\nc\nd";
            assertEquals(4, PeekPopup.splitByLine(text, spans(7)).size());
        }

        @Test
        void handlesAnEmptyLineInTheMiddle() {
            String text = "a\n\nb";
            assertEquals(List.of("a", "", "b"), texts(PeekPopup.splitByLine(text, spans(4))));
        }

        @Test
        void spansShorterThanTheTextDoNotLoseALine() {
            assertEquals(3, PeekPopup.splitByLine("a\nb\nc", spans(2)).size());
        }
    }
}
