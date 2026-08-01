package com.editora.ui;

import java.util.Collection;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of bracket-pair colorization against a real grammar. The depth arithmetic is
 * unit-tested in {@code BracketColorsTest}; what only a live buffer can prove is the half that pure tests
 * take as <em>input</em>:
 *
 * <ul>
 *   <li>the depth spans actually survive the overlay onto the token spans and reach the document, and
 *   <li>the string/comment skip really fires — that depends on the token class names matching what
 *       {@code TextMateHighlighter} emits, so a rename there would leave every pure test green while
 *       brackets in strings silently shifted the colour of everything below them.
 * </ul>
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BracketColorsFxTest {

    // A "(" in a string and a ")" in a comment sit between the outer braces on purpose: if either were
    // counted, the closing brace of the class would not come back to depth 0.
    private static final String SRC = "class C {\n"
            + "    void m() {\n"
            + "        int x = (1 + (2));\n"
            + "    }\n"
            + "    // ) not a bracket\n"
            + "    String s = \"(\";\n"
            + "}\n";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private EditorBuffer javaBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.getNode();
            b.setContent(SRC);
            b.setBracketColorsEnabled(true);
            return b;
        });
    }

    /** The style classes on the character at {@code i}. */
    private static Collection<String> styleAt(EditorBuffer b, int i) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            return area.getStyleOfChar(i);
        });
    }

    /** The {@code bracket-depth-N} code on the character at {@code i}, or -1 for no bracket class. */
    private static int depthAt(EditorBuffer b, int i) throws Exception {
        for (String c : styleAt(b, i)) {
            if (c.startsWith("bracket-depth-")) {
                return Integer.parseInt(c.substring("bracket-depth-".length()));
            }
        }
        return -1;
    }

    /**
     * Highlighting is debounced and applied from a background pass, so poll rather than assume it has
     * landed. Returns as soon as the first brace carries a depth class.
     *
     * <p>The deadline is generous because the pass is queued behind a shared, per-grammar-serialized
     * highlight pool whose width scales with core count — two threads on a CI runner against four here — so
     * a deadline tuned to a developer machine reports a scheduling delay as a broken feature.
     *
     * <p>On timeout it reports whether <em>any</em> character carries a style at all. That is the difference
     * between "this feature's overlay is broken" and "the highlight pipeline underneath it never applied",
     * which the bare timeout could not distinguish — and only one of those is this change's fault.
     */
    private static void awaitColored(EditorBuffer b, int probe) throws Exception {
        for (int i = 0; i < 600; i++) {
            if (depthAt(b, probe) >= 0) {
                return;
            }
            Thread.sleep(50);
        }
        String diagnosis = FxTestSupport.callOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            int styled = 0;
            for (int i = 0; i < area.getLength(); i++) {
                if (!area.getStyleOfChar(i).isEmpty()) {
                    styled++;
                }
            }
            return "hasHighlighting=" + b.hasHighlighting() + " length=" + area.getLength() + " styledChars=" + styled
                    + " styleAtProbe=" + area.getStyleOfChar(probe);
        });
        throw new AssertionError("bracket colours never reached the document after 30s — " + diagnosis);
    }

    @Test
    void bracketsAreTintedByNestingDepth() throws Exception {
        EditorBuffer b = javaBuffer();
        int classBrace = SRC.indexOf('{');
        awaitColored(b, classBrace);

        assertEquals(0, depthAt(b, classBrace), "the class brace is the outermost");

        int methodBrace = SRC.indexOf('{', SRC.indexOf("void m()"));
        assertEquals(1, depthAt(b, methodBrace), "the method body is one level in");

        int outerParen = SRC.indexOf("(1 + (2))");
        assertEquals(2, depthAt(b, outerParen));
        assertEquals(3, depthAt(b, outerParen + 5), "the inner ( is a level deeper");
        assertEquals(3, depthAt(b, outerParen + 7), "a closer takes its opener's depth");
        assertEquals(2, depthAt(b, outerParen + 8));
    }

    @Test
    void bracketsInStringsAndCommentsAreNotColouredAndDoNotShiftTheRest() throws Exception {
        EditorBuffer b = javaBuffer();
        int classBrace = SRC.indexOf('{');
        awaitColored(b, classBrace);

        int commentParen = SRC.indexOf("// ) not") + 3;
        assertEquals(')', SRC.charAt(commentParen));
        assertEquals(-1, depthAt(b, commentParen), "a bracket inside a comment carries no depth class");

        int stringParen = SRC.indexOf("\"(\"") + 1;
        assertEquals('(', SRC.charAt(stringParen));
        assertEquals(-1, depthAt(b, stringParen), "a bracket inside a string carries no depth class");

        // The real check: the class's closing brace is back at depth 0. Had either of the above counted,
        // it would not be — which is what makes this the assertion worth having.
        int closingBrace = SRC.lastIndexOf('}');
        assertEquals(0, depthAt(b, closingBrace), "the outer pair still matches at depth 0");
    }

    @Test
    void aColouredBracketCarriesOnlyItsDepthClassSoNoThemeCanOutrankIt() throws Exception {
        EditorBuffer b = javaBuffer();
        int classBrace = SRC.indexOf('{');
        awaitColored(b, classBrace);

        Collection<String> style = styleAt(b, classBrace);
        assertNotNull(style);
        assertEquals(
                1,
                style.size(),
                "the overlay replaces rather than unions, so the fill cannot be decided by a theme's "
                        + "punctuation rule: " + style);
        assertTrue(style.contains("bracket-depth-0"));
    }

    @Test
    void turningItOffRemovesTheDepthClasses() throws Exception {
        EditorBuffer b = javaBuffer();
        int classBrace = SRC.indexOf('{');
        awaitColored(b, classBrace);

        FxTestSupport.runOnFx(() -> b.setBracketColorsEnabled(false));
        for (int i = 0; i < 600 && depthAt(b, classBrace) >= 0; i++) {
            Thread.sleep(50);
        }
        assertEquals(-1, depthAt(b, classBrace), "disabling re-highlights the buffer without depth classes");
    }

    @Test
    void anEditReColoursTheLinesBelowIt() throws Exception {
        // The incremental path: the depth carried per line has to splice in step with the grammar
        // end-states, or a pass starting at the edited line resumes from another line's depth.
        EditorBuffer b = javaBuffer();
        int classBrace = SRC.indexOf('{');
        awaitColored(b, classBrace);

        // Wrap the method body in one more brace level by opening a block on its own line.
        int methodBrace = SRC.indexOf('{', SRC.indexOf("void m()"));
        FxTestSupport.runOnFx(() -> {
            CodeArea area = FxTestSupport.field(b, "area");
            area.insertText(methodBrace + 1, "{");
        });

        int outerParen = SRC.indexOf("(1 + (2))") + 1; // shifted by the inserted char
        for (int i = 0; i < 600 && depthAt(b, outerParen) != 3; i++) {
            Thread.sleep(50);
        }
        assertEquals(3, depthAt(b, outerParen), "the added level pushed everything below it one deeper");
    }
}
