package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.editor.EditorBuffer;
import com.editora.editor.GrammarRegistry;
import com.editora.editor.LanguageRegistry;
import com.editora.editor.SemanticToken;
import com.editora.editor.TextMateHighlighter;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a highlight pass does to a live document: it restyles the lines an edit reaches rather than
 * everything below it, a buffer without a grammar is not restyled at all, and a semantic-tokens reply
 * restyles only the lines whose tokens changed. The convergence logic itself is covered against real
 * grammars by {@code HighlightPassTest}; this is the wiring — that the buffer hands the pass the right
 * range and applies only what comes back.
 *
 * <p>"Restyled" is measured on the document's own change stream: every style-only change the area reports
 * between two points, as an offset range.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IncrementalHighlightFxTest {

    private static final int LINES = 1200;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** {@value #LINES} lines of Java, one small method each, so every line has brackets and a definition. */
    private static String source(int lines) {
        StringBuilder sb = new StringBuilder("class Big {\n");
        for (int i = 1; i < lines - 1; i++) {
            sb.append("    int method")
                    .append(i)
                    .append("(int p) { return p + ")
                    .append(i)
                    .append("; } // note\n");
        }
        return sb.append("}\n").toString();
    }

    /** Style-only changes the area reported, as {@code {start, end}} offset ranges. */
    private static final class Restyles {
        final List<int[]> ranges = new ArrayList<>();

        int count() {
            return ranges.size();
        }

        /** The largest single restyle, in characters. */
        int widest() {
            return ranges.stream().mapToInt(r -> r[1] - r[0]).max().orElse(0);
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            ranges.forEach(
                    r -> sb.append('[').append(r[0]).append(", ").append(r[1]).append(") "));
            return sb.toString();
        }

        int lowest() {
            return ranges.stream().mapToInt(r -> r[0]).min().orElse(Integer.MAX_VALUE);
        }

        int highest() {
            return ranges.stream().mapToInt(r -> r[1]).max().orElse(-1);
        }
    }

    private static Restyles watch(EditorBuffer b) throws Exception {
        Restyles restyles = new Restyles();
        FxTestSupport.runOnFx(() -> b.getArea().richChanges().subscribe(c -> {
            if (c.getInserted().getText().equals(c.getRemoved().getText())) {
                restyles.ranges.add(new int[] {
                    c.getPosition(), c.getPosition() + c.getInserted().length()
                });
            }
        }));
        return restyles;
    }

    private EditorBuffer javaBuffer(boolean bracketColors) throws Exception {
        return javaBuffer(bracketColors, LINES);
    }

    private EditorBuffer javaBuffer(boolean bracketColors, int lines) throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.getNode();
            b.setContent(source(lines));
            b.setBracketColorsEnabled(bracketColors);
            return b;
        });
        awaitHighlighted(buffer);
        return buffer;
    }

    /** Waits until a pass covering every edit so far has been applied (nothing is owed any more). */
    private static void awaitHighlighted(EditorBuffer b) throws Exception {
        for (int i = 0; i < 1200; i++) {
            boolean done = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "highlightLines") != null
                    && "clean".equals(String.valueOf(FxTestSupport.<Object>field(b, "highlightDirty"))));
            if (done) {
                FxTestSupport.drainFx(); // the coalesced brace re-match the apply scheduled
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no highlight pass applied within 30s: highlightDirty="
                + FxTestSupport.callOnFx(() -> String.valueOf(FxTestSupport.<Object>field(b, "highlightDirty"))));
    }

    private static int lineStart(EditorBuffer b, int line) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getAbsolutePosition(line, 0));
    }

    private static Collection<String> styleAt(EditorBuffer b, int offset) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getStyleOfChar(offset));
    }

    /** Asserts the document's styles are exactly what tokenizing its whole text from scratch gives. */
    private static void assertStylesMatchAFullTokenize(EditorBuffer b, String context) throws Exception {
        String text = FxTestSupport.callOnFx(() -> b.getArea().getText());
        var expected =
                TextMateHighlighter.compute(text, GrammarRegistry.shared().forLanguageName("java"));
        List<Collection<String>> actual = FxTestSupport.callOnFx(() -> {
            CodeArea area = b.getArea();
            List<Collection<String>> styles = new ArrayList<>(area.getLength());
            for (StyleSpan<Collection<String>> span : area.getStyleSpans(0, area.getLength())) {
                for (int i = 0; i < span.getLength(); i++) {
                    styles.add(span.getStyle());
                }
            }
            return styles;
        });
        int at = 0;
        for (StyleSpan<Collection<String>> span : expected) {
            for (int i = 0; i < span.getLength(); i++, at++) {
                if (text.charAt(at) == '\n') {
                    continue; // the area reports a line terminator with the style of the text before it
                }
                List<String> shown = new ArrayList<>(actual.get(at));
                shown.remove("brace-match");
                assertEquals(List.copyOf(span.getStyle()), shown, context + ": style at offset " + at);
            }
        }
        assertEquals(text.length(), at, context);
    }

    // ---- E1: a pass restyles what the edit reaches ----------------------------------------------------

    @Test
    void typingNearTheTopRestylesThatLineNotEverythingBelowIt() throws Exception {
        EditorBuffer buffer = javaBuffer(true);
        int length = FxTestSupport.callOnFx(() -> buffer.getArea().getLength());
        AtomicInteger symbolChanges = new AtomicInteger();
        FxTestSupport.runOnFx(() -> buffer.setOnSymbolsChanged(symbolChanges::incrementAndGet));
        Restyles restyles = watch(buffer);

        int line = lineStart(buffer, 5);
        int inComment = FxTestSupport.callOnFx(() -> buffer.getArea().getText().indexOf("// note", line)) + 3;
        FxTestSupport.runOnFx(() -> buffer.getArea().insertText(inComment, "x"));
        awaitHighlighted(buffer);

        assertTrue(restyles.count() > 0, "the edited line was restyled");
        assertTrue(restyles.lowest() >= line, "nothing above the edited line: " + restyles.lowest());
        assertTrue(
                restyles.highest() <= lineStart(buffer, 7),
                "the pass stopped where the grammar state re-converged, " + (length - restyles.highest())
                        + " chars before the end; it restyled up to offset " + restyles.highest() + " of " + length);
        assertEquals(0, symbolChanges.get(), "typing in a comment changed no symbol, so no listener ran");
    }

    @Test
    void aBlockCommentOpenedAtTheTopReachesTheEndAndDeletingItRestoresEverything() throws Exception {
        EditorBuffer buffer = javaBuffer(false);
        int far = lineStart(buffer, LINES - 10) + 4; // the "int" of a method near the end
        assertEquals(List.of("keyword"), List.copyOf(styleAt(buffer, far)));

        int at = lineStart(buffer, 2);
        FxTestSupport.runOnFx(() -> buffer.getArea().insertText(at, "/*"));
        awaitHighlighted(buffer);
        assertEquals(List.of("comment"), List.copyOf(styleAt(buffer, far + 2)), "all of it is a comment now");
        assertStylesMatchAFullTokenize(buffer, "block comment opened");

        FxTestSupport.runOnFx(() -> buffer.getArea().deleteText(at, at + 2));
        awaitHighlighted(buffer);
        assertEquals(List.of("keyword"), List.copyOf(styleAt(buffer, far)));
        assertStylesMatchAFullTokenize(buffer, "block comment deleted");
    }

    @Test
    void insertedAndDeletedLinesKeepTheRestOfTheDocumentInStep() throws Exception {
        EditorBuffer buffer = javaBuffer(false);
        List<TextMateHighlighter.Symbol> before = FxTestSupport.callOnFx(buffer::symbols);
        int at = lineStart(buffer, 300);
        FxTestSupport.runOnFx(() -> buffer.getArea().insertText(at, "    String added = \"x\";\n\n"));
        awaitHighlighted(buffer);
        assertStylesMatchAFullTokenize(buffer, "two lines inserted");
        List<TextMateHighlighter.Symbol> after = FxTestSupport.callOnFx(buffer::symbols);
        assertEquals(before.size(), after.size());
        assertEquals(
                before.get(before.size() - 2).line() + 2,
                after.get(after.size() - 2).line(),
                "symbols moved down");

        // A second edit resumes from the per-line state the first pass spliced in.
        int from = lineStart(buffer, 700);
        int to = lineStart(buffer, 710);
        FxTestSupport.runOnFx(() -> buffer.getArea().deleteText(from, to));
        awaitHighlighted(buffer);
        assertStylesMatchAFullTokenize(buffer, "ten lines deleted");
    }

    // ---- E2: no grammar, nothing to restyle -----------------------------------------------------------

    @Test
    void aBufferWithoutAGrammarIsNotRestyledWhenTypingSettles() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.getNode();
            b.setContent("(abc)\nplain text\n");
            FxTestSupport.invoke(b, "applyHighlighting"); // the pause after the load
            b.getArea().insertText(b.getArea().getLength(), "q"); // an edit, so a typing pause is due
            b.getArea().moveTo(1); // just inside the "(" — its pair is the ")" at offset 4
            return b;
        });
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
        assertTrue(styleAt(buffer, 0).contains("brace-match"), "the pair is highlighted");
        assertTrue(styleAt(buffer, 4).contains("brace-match"));
        Restyles restyles = watch(buffer);

        // What the 150 ms typing-pause milestone runs.
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(buffer, "applyHighlighting"));
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();

        assertEquals(0, restyles.count(), "no style change at all: " + restyles.describe());
        assertTrue(styleAt(buffer, 0).contains("brace-match"), "and the brace-match highlight is still there");
        assertTrue(styleAt(buffer, 4).contains("brace-match"));
    }

    /** Offsets of the characters carrying the brace-match class. */
    private static List<Integer> braceMatched(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<Integer> offsets = new ArrayList<>();
            int at = 0;
            for (StyleSpan<Collection<String>> span :
                    b.getArea().getStyleSpans(0, b.getArea().getLength())) {
                for (int i = 0; i < span.getLength(); i++, at++) {
                    if (span.getStyle().contains("brace-match")
                            && b.getArea().getText(at, at + 1).charAt(0) != '\n') {
                        offsets.add(at);
                    }
                }
            }
            return offsets;
        });
    }

    @Test
    void textTypedNextToAMatchedBraceDoesNotKeepTheMatchStyleInAPlainBuffer() throws Exception {
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.getNode();
            b.setContent("(abc\nmore\n)\ntail\n");
            FxTestSupport.invoke(b, "applyHighlighting"); // the pause after the load
            b.getArea().moveTo(1);
            return b;
        });
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
        assertEquals(List.of(0, 10), braceMatched(buffer), "the pair spans three lines");

        // Typed right after the "(": the new character takes on its neighbour's style, the ")" moves one
        // to the right, and the caret is no longer next to a brace.
        FxTestSupport.runOnFx(() -> buffer.getArea().insertText(1, "x"));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(buffer, "applyHighlighting")); // the typing pause
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
        assertEquals(List.of(), braceMatched(buffer), "no stale match style is left anywhere");
    }

    @Test
    void aMatchedBraceBelowAnEditIsStillClearedInAGrammarBuffer() throws Exception {
        EditorBuffer buffer = javaBuffer(false, 400);
        int open = FxTestSupport.callOnFx(() -> buffer.getArea().getText().indexOf('{'));
        int close = FxTestSupport.callOnFx(() -> buffer.getArea().getText().lastIndexOf('}'));
        FxTestSupport.runOnFx(() -> buffer.getArea().moveTo(open + 1));
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
        assertEquals(List.of(open, close), braceMatched(buffer), "the class braces, four hundred lines apart");

        // The pass for this edit restyles one line at the top; the closing brace at the bottom has moved.
        FxTestSupport.runOnFx(() -> buffer.getArea().insertText(open + 1, " // x"));
        awaitHighlighted(buffer);
        FxTestSupport.drainFx();
        assertEquals(List.of(), braceMatched(buffer), "the match is gone from both braces");
    }

    @Test
    void losingTheGrammarClearsTheStylesOnce() throws Exception {
        EditorBuffer buffer = javaBuffer(false);
        assertFalse(styleAt(buffer, 0).isEmpty(), "\"class\" is a keyword");

        FxTestSupport.runOnFx(() -> buffer.setLanguageOverride(LanguageRegistry.plaintext()));
        FxTestSupport.drainFx();
        int styled = FxTestSupport.callOnFx(() -> {
            CodeArea area = buffer.getArea();
            int n = 0;
            for (StyleSpan<Collection<String>> span : area.getStyleSpans(0, area.getLength())) {
                n += span.getStyle().isEmpty() || span.getStyle().equals(List.of("brace-match")) ? 0 : span.getLength();
            }
            return n;
        });
        assertEquals(0, styled, "the java styles are gone");

        Restyles restyles = watch(buffer);
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(buffer, "applyHighlighting"));
        FxTestSupport.drainFx();
        assertEquals(0, restyles.count(), "and are not cleared again at the next typing pause");
    }

    // ---- P1: a semantic-tokens reply is a style-only update of the lines it changes -------------------

    @Test
    void semanticTokensRestyleOnlyTheLinesTheyChange() throws Exception {
        EditorBuffer buffer = javaBuffer(true);
        FxTestSupport.runOnFx(() -> buffer.setSemanticActive(true));
        awaitHighlighted(buffer);
        Restyles restyles = watch(buffer);
        int line100 = lineStart(buffer, 100);
        int line101 = lineStart(buffer, 101);
        int line102 = lineStart(buffer, 102);

        // "    int method100(int p) …": the name starts at column 8.
        List<SemanticToken> tokens = List.of(
                new SemanticToken(100, 8, 9, "sem-method sem-declaration"), new SemanticToken(101, 8, 9, "sem-method"));
        long gen = FxTestSupport.callOnFx(buffer::semanticGen);
        FxTestSupport.runOnFx(() -> buffer.setSemanticTokens(tokens, gen));
        awaitHighlighted(buffer);
        assertEquals(List.of("sem-method", "sem-declaration"), List.copyOf(styleAt(buffer, line100 + 8)));
        assertEquals(List.of("sem-method"), List.copyOf(styleAt(buffer, line101 + 10)));
        assertTrue(restyles.lowest() >= line100, "nothing above the first token's line");
        assertTrue(
                restyles.highest() <= line102,
                "nothing below the last token's line — restyled up to offset " + restyles.highest());

        // One token reclassified, the other unchanged: only its line.
        restyles.ranges.clear();
        List<SemanticToken> changed = List.of(
                new SemanticToken(100, 8, 9, "sem-method sem-declaration"), new SemanticToken(101, 8, 9, "sem-type"));
        FxTestSupport.runOnFx(() -> buffer.setSemanticTokens(changed, gen));
        awaitHighlighted(buffer);
        assertEquals(List.of("sem-type"), List.copyOf(styleAt(buffer, line101 + 10)));
        assertTrue(restyles.lowest() >= line101 && restyles.highest() <= line102, "only line 101 was restyled");

        // The tokens go away: their lines return to the lexical styles, and to nothing else.
        restyles.ranges.clear();
        FxTestSupport.runOnFx(() -> buffer.setSemanticTokens(List.of(), gen));
        awaitHighlighted(buffer);
        assertTrue(restyles.lowest() >= line100 && restyles.highest() <= line102);
        FxTestSupport.runOnFx(() -> buffer.setBracketColorsEnabled(false));
        awaitHighlighted(buffer);
        assertStylesMatchAFullTokenize(buffer, "semantic tokens removed");
    }

    @Test
    void anEditElsewhereKeepsThePaintedTokensAndTheSameReplyRestylesNothing() throws Exception {
        EditorBuffer buffer = javaBuffer(true);
        FxTestSupport.runOnFx(() -> buffer.setSemanticActive(true));
        awaitHighlighted(buffer);
        List<SemanticToken> tokens = List.of(new SemanticToken(100, 8, 9, "sem-method"));
        long gen = FxTestSupport.callOnFx(buffer::semanticGen);
        FxTestSupport.runOnFx(() -> buffer.setSemanticTokens(tokens, gen));
        awaitHighlighted(buffer);

        // A line typed above the token: the lexical pass for it restyles that line, and the token — one
        // line lower now — keeps its colour instead of blinking off until the server answers.
        int at = lineStart(buffer, 50);
        FxTestSupport.runOnFx(() -> buffer.getArea().insertText(at, "    int added;\n"));
        awaitHighlighted(buffer);
        int moved = lineStart(buffer, 101);
        assertEquals(List.of("sem-method"), List.copyOf(styleAt(buffer, moved + 8)));

        // The server's reply for the new text: the same token, one line lower. Nothing to restyle.
        long passes = FxTestSupport.callOnFx(() -> FxTestSupport.<Long>field(buffer, "highlightGen"));
        long gen2 = FxTestSupport.callOnFx(buffer::semanticGen);
        FxTestSupport.runOnFx(
                () -> buffer.setSemanticTokens(List.of(new SemanticToken(101, 8, 9, "sem-method")), gen2));
        assertEquals(
                passes,
                FxTestSupport.callOnFx(() -> FxTestSupport.<Long>field(buffer, "highlightGen")),
                "no pass was dispatched");
        assertEquals(List.of("sem-method"), List.copyOf(styleAt(buffer, moved + 8)));

        // A token on the edited line itself has to be painted: the lexical pass left that line bare.
        Restyles restyles = watch(buffer);
        long gen3 = FxTestSupport.callOnFx(buffer::semanticGen);
        FxTestSupport.runOnFx(() -> buffer.setSemanticTokens(
                List.of(new SemanticToken(50, 8, 5, "sem-variable"), new SemanticToken(101, 8, 9, "sem-method")),
                gen3));
        awaitHighlighted(buffer);
        assertEquals(List.of("sem-variable"), List.copyOf(styleAt(buffer, at + 8)));
        assertTrue(restyles.lowest() >= at && restyles.highest() <= lineStart(buffer, 51), "just line 50");
    }
}
