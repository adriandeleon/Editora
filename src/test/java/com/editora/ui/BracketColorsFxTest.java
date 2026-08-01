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

    /** Uncaught FX-thread exceptions, collected so a throw inside a runLater apply is not invisible. */
    private static final java.util.List<Throwable> fxUncaught =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        // The highlight apply runs inside Platform.runLater; if it throws (e.g. a bad overlay), the
        // exception goes to the FX thread's uncaught handler and the symptom is indistinguishable from
        // "the pass never ran". Chain a collector in front of whatever handler is installed.
        FxTestSupport.runOnFx(() -> {
            Thread fx = Thread.currentThread();
            Thread.UncaughtExceptionHandler prev = fx.getUncaughtExceptionHandler();
            fx.setUncaughtExceptionHandler((t, e) -> {
                fxUncaught.add(e);
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            });
        });
    }

    /**
     * The threads that could explain a highlight pass never applying: the shared {@code editor-highlighter}
     * pool (are its workers idle, or stuck — and where?), plus any other thread currently inside
     * com.editora/tm4e code (a holder of the shared grammar monitor would show up here).
     */
    private static String interestingThreads() {
        StringBuilder sb = new StringBuilder();
        for (var entry : Thread.getAllStackTraces().entrySet()) {
            Thread t = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            boolean pool = t.getName().startsWith("editor-highlighter");
            boolean inOurCode = false;
            for (StackTraceElement f : stack) {
                String cn = f.getClassName();
                if (cn.startsWith("com.editora") || cn.startsWith("org.eclipse.tm4e") || cn.startsWith("org.joni")) {
                    inOurCode = true;
                    break;
                }
            }
            if (!pool && !inOurCode) {
                continue;
            }
            sb.append("\n  ")
                    .append(t.getName())
                    .append(" [")
                    .append(t.getState())
                    .append("]");
            int limit = Math.min(stack.length, 12);
            for (int i = 0; i < limit; i++) {
                sb.append("\n    at ").append(stack[i]);
            }
        }
        return sb.length() == 0 ? "\n  (no highlighter/editora/tm4e threads found)" : sb.toString();
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
            // These separate the remaining candidates, which the style counts alone cannot:
            //   lineStates empty while highlightGen > 0 → passes dispatched but none ever applied
            //   lineStates non-empty                    → a pass applied but produced no styles
            //   bracketColors false                     → the per-buffer flag never took
            java.util.List<?> states = FxTestSupport.field(b, "lineStates");
            java.util.List<?> depths = FxTestSupport.field(b, "lineDepths");
            return "hasHighlighting=" + b.hasHighlighting() + " length=" + area.getLength() + " styledChars=" + styled
                    + " styleAtProbe=" + area.getStyleOfChar(probe) + " bracketColors="
                    + FxTestSupport.field(b, "bracketColors") + " highlightGen="
                    + FxTestSupport.field(b, "highlightGen") + " dirtyFromLine="
                    + FxTestSupport.field(b, "dirtyFromLine") + " lineStates=" + states.size() + " lineDepths="
                    + depths.size();
        });
        // highlightGen>0 with lineStates=0 means passes were handed to the pool and none ever applied.
        // The thread dump says whether the pool's workers are stuck (and on what — the shared grammar
        // monitor is the prime suspect: CLAUDE.md records a full-suite-only deadlock on it before), or
        // idle (⇒ the task threw and was swallowed, or the apply threw — see fxUncaught).
        throw new AssertionError("bracket colours never reached the document after 30s — " + diagnosis + "\nfxUncaught="
                + fxUncaught + "\nthreads:" + interestingThreads());
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
