package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.text.Text;

import com.editora.search.FuzzyMatch;

/**
 * Turns a picker row's label into {@link Text} runs with the characters that matched the query set apart,
 * so the user can see <em>why</em> a row is in the list and why it ranked where it did.
 *
 * <p>The ranges come from {@link FuzzyMatch} — the same call that produced the row's score — rather than
 * from a second, independent matcher. That matters: the palette previously ranked with one algorithm and
 * emboldened with another ({@code completion.MatchHighlighter}, whose match is greedy left-to-right), so
 * the highlight could point at different characters than the ones the ranking was actually reasoning
 * about.
 */
final class MatchText {

    private MatchText() {}

    /** Style class for the run(s) that matched the query. */
    static final String MATCHED = "palette-match";
    /** Style class for the rest of the label. */
    static final String PLAIN = "palette-cell-text";

    /** Runs for {@code text} against {@code query}, with unmatched runs styled {@link #PLAIN}. */
    static List<Text> runs(String text, String query) {
        return runs(text, query, PLAIN);
    }

    /**
     * As {@link #runs(String, String)}, but unmatched runs carry {@code plainClass} instead of
     * {@link #PLAIN} — the hook a picker uses to keep a per-row treatment (an unsaved buffer's amber
     * italic, say) while still emboldening the match.
     *
     * <p>Nothing is highlighted for a blank query, which is exactly the "no filter typed yet" state.
     */
    static List<Text> runs(String text, String query, String plainClass) {
        String s = text == null ? "" : text;
        List<Text> parts = new ArrayList<>();
        FuzzyMatch.Match m = FuzzyMatch.of(s, query);
        if (m == null) {
            parts.add(run(s, plainClass));
            return parts;
        }
        int pos = 0;
        for (int[] r : m.ranges()) {
            // Defensive: a range outside the string would throw inside a cell factory, which JavaFX
            // swallows into a blank row rather than a stack trace. FuzzyMatch guarantees this can't
            // happen; the clamp means a future bug degrades to a missing highlight instead of a blank list.
            int start = Math.max(pos, Math.min(r[0], s.length()));
            int end = Math.max(start, Math.min(r[1], s.length()));
            if (start > pos) {
                parts.add(run(s.substring(pos, start), plainClass));
            }
            if (end > start) {
                parts.add(run(s.substring(start, end), MATCHED));
            }
            pos = end;
        }
        if (pos < s.length()) {
            parts.add(run(s.substring(pos), plainClass));
        }
        return parts;
    }

    private static Text run(String s, String styleClass) {
        Text t = new Text(s);
        t.getStyleClass().add(styleClass);
        return t;
    }
}
