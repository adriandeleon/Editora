package com.editora.ui;

import java.util.Collection;
import java.util.List;

import com.editora.ui.ReferencesPanel.Run;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link ReferencesPanel#previewRuns}: a source line + the editor's style spans → the stripped preview's runs. */
class ReferencesPanelPreviewTest {

    private static StyleSpans<Collection<String>> spans(Object... lengthThenStyles) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        for (int i = 0; i < lengthThenStyles.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> styles = (List<String>) lengthThenStyles[i + 1];
            b.add(styles, (Integer) lengthThenStyles[i]);
        }
        return b.create();
    }

    private static String text(List<Run> runs) {
        StringBuilder sb = new StringBuilder();
        runs.forEach(r -> sb.append(r.text()));
        return sb.toString();
    }

    @Test
    void cutsTheStrippedLineIntoStyledRuns() {
        String line = "    private static void run(  ";
        List<Run> runs = ReferencesPanel.previewRuns(
                line,
                spans(
                        4,
                        List.of(),
                        7,
                        List.of("keyword"),
                        1,
                        List.of(),
                        6,
                        List.of("keyword"),
                        1,
                        List.of(),
                        4,
                        List.of("keyword"),
                        1,
                        List.of(),
                        3,
                        List.of("function"),
                        3,
                        List.of()));

        assertEquals(line.strip(), text(runs), "the runs spell the stripped preview");
        assertEquals(new Run("private", List.of("keyword")), runs.get(0), "leading indent is not a run");
        assertEquals(new Run("run", List.of("function")), runs.get(runs.size() - 2));
        assertEquals(new Run("(", List.of()), runs.getLast(), "trailing whitespace is dropped");
    }

    @Test
    void keepsSyntaxClassesAndDropsEditorStateOnes() {
        List<Run> runs = ReferencesPanel.previewRuns(
                "foo(x)",
                spans(
                        3,
                        List.of("function", "lsp-error"),
                        1,
                        List.of("punct", "brace-match", "bracket-depth-1"),
                        1,
                        List.of("sem-parameter"),
                        1,
                        List.of("brace-match")));

        assertEquals(
                List.of(
                        new Run("foo", List.of("function")),
                        new Run("(", List.of("punct", "bracket-depth-1")),
                        new Run("x", List.of("sem-parameter")),
                        new Run(")", List.of())),
                runs);
    }

    @Test
    void mergesNeighboursThatEndUpWithTheSameStyles() {
        List<Run> runs = ReferencesPanel.previewRuns("ab", spans(1, List.of("brace-match"), 1, List.of()));
        assertEquals(List.of(new Run("ab", List.of())), runs);
    }

    @Test
    void spansShorterThanTheLineLeaveThePlainTail() {
        List<Run> runs = ReferencesPanel.previewRuns("int x;", spans(3, List.of("keyword")));
        assertEquals(List.of(new Run("int", List.of("keyword")), new Run(" x;", List.of())), runs);
    }

    @Test
    void nothingToStyleIsEmpty() {
        assertEquals(List.of(), ReferencesPanel.previewRuns("   ", spans(3, List.of())));
        assertEquals(List.of(), ReferencesPanel.previewRuns(null, spans(1, List.of())));
        assertEquals(List.of(), ReferencesPanel.previewRuns("x", null));
    }
}
