package com.editora.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.editora.text.SemanticToken;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A code card's colours with the server's semantic tokens over them (Map code browser round 2). */
class MapCardSemanticsTest {

    /** "int" coloured as a keyword, the rest plain. */
    private static StyleSpans<Collection<String>> base(int length) {
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        b.add(List.of("keyword"), 3);
        b.add(List.of(), length - 3);
        return b.create();
    }

    /** The classes at each offset, joined, one entry per character. */
    private static List<String> perChar(StyleSpans<Collection<String>> spans) {
        List<String> out = new ArrayList<>();
        for (StyleSpan<Collection<String>> span : spans) {
            for (int i = 0; i < span.getLength(); i++) {
                out.add(String.join(" ", span.getStyle()));
            }
        }
        return out;
    }

    @Test
    void tokensGoOverTheColoursAtTheirPlaceShiftedForADeclarationCard() {
        String text = "int a = b;\nfoo(c);\n";
        StyleSpans<Collection<String>> base = base(text.length());
        // The card shows the file from its line 10: the tokens are the file's.
        List<SemanticToken> tokens = List.of(
                new SemanticToken(10, 4, 1, "sem-variable"),
                new SemanticToken(11, 0, 3, "sem-function sem-static"),
                new SemanticToken(11, 4, 1, "sem-parameter"),
                new SemanticToken(9, 0, 3, "sem-type"), // above the card
                new SemanticToken(12, 0, 2, "sem-type"), // the empty last line: nothing to colour
                new SemanticToken(11, 6, 9, "sem-type")); // runs past its line: cut at its end
        List<String> chars = perChar(MapCardSemantics.overlay(base, text, tokens, 10));
        assertEquals(text.length(), chars.size());
        assertEquals("keyword", chars.get(0), "no token there: the TextMate colour stays");
        assertEquals("sem-variable", chars.get(4));
        assertEquals("", chars.get(5));
        assertEquals("sem-function sem-static", chars.get(11));
        assertEquals("sem-parameter", chars.get(15));
        assertEquals("sem-type", chars.get(17), "the ';' of foo(c);");
        assertEquals("", chars.get(18), "the line break is not the token's");
    }

    @Test
    void nothingToLayOverLeavesTheColoursAsTheyAre() {
        String text = "int a;\n";
        StyleSpans<Collection<String>> base = base(text.length());
        assertSame(base, MapCardSemantics.overlay(base, text, List.of(), 0));
        assertSame(base, MapCardSemantics.overlay(base, text, List.of(new SemanticToken(5, 0, 3, "sem-type")), 0));
        // Overlapping tokens: the first wins; one past the coloured prefix is left out.
        StyleSpans<Collection<String>> prefix = base(4); // only "int " coloured
        List<String> chars = perChar(MapCardSemantics.overlay(
                prefix,
                text,
                List.of(
                        new SemanticToken(0, 0, 3, "sem-type"),
                        new SemanticToken(0, 1, 1, "sem-x"),
                        new SemanticToken(0, 4, 1, "sem-variable")),
                0));
        assertEquals(List.of("sem-type", "sem-type", "sem-type", ""), chars);
    }
}
