package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.model.StyleSpan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An incremental highlight pass stops where the grammar state re-converges instead of running to the end
 * of the file — and what it leaves on screen must be exactly what tokenizing the whole document would.
 *
 * <p>{@link Doc} stands in for the editor: the text, the styles "painted" on each character, and the state
 * the buffer keeps between passes. Every edit goes through {@link HighlightDirty} as it does in
 * {@code EditorBuffer}, a pass paints only the range it returns, and the result is compared with a
 * from-scratch pass over the same text — styles, per-line grammar states, bracket depths and symbols.
 */
class HighlightPassTest {

    /** The style an inserted character carries until a pass restyles it: a pass that skips one is caught. */
    private static final Collection<String> UNPAINTED = List.of("<unpainted>");

    private static final class Doc {
        final IGrammar grammar;
        final boolean brackets;
        final StringBuilder text = new StringBuilder();
        final List<Collection<String>> painted = new ArrayList<>();
        final HighlightDirty dirty = new HighlightDirty();
        HighlightPass.Lines lines;
        List<TextMateHighlighter.Symbol> symbols = List.of();

        Doc(IGrammar grammar, boolean brackets, String initial) {
            this.grammar = grammar;
            this.brackets = brackets;
            replace(0, 0, initial);
            pass();
        }

        void replace(int position, int removed, String inserted) {
            text.replace(position, position + removed, inserted);
            painted.subList(position, position + removed).clear();
            painted.addAll(position, java.util.Collections.nCopies(inserted.length(), UNPAINTED));
            dirty.edited(position, removed, inserted.length());
        }

        void insert(int position, String inserted) {
            replace(position, 0, inserted);
        }

        /** 0-based line of the {@code nth} (from 0) line that is exactly {@code content}. */
        int lineOf(String content, int nth) {
            String[] all = text.toString().split("\n", -1);
            for (int i = 0, seen = 0; i < all.length; i++) {
                if (all[i].equals(content) && seen++ == nth) {
                    return i;
                }
            }
            throw new AssertionError("no line #" + nth + " reading: " + content);
        }

        /** Offset of the start of 0-based {@code line}. */
        int line(int line) {
            int pos = 0;
            for (int i = 0; i < line; i++) {
                pos = text.indexOf("\n", pos) + 1;
            }
            return pos;
        }

        int lineCount() {
            return (int) text.chars().filter(c -> c == '\n').count() + 1;
        }

        HighlightPass.Request request(List<SemanticToken> semantic) {
            return new HighlightPass.Request(
                    text.toString(),
                    grammar,
                    lines,
                    symbols,
                    dirty.isClean() ? -1 : dirty.start(),
                    dirty.end(),
                    brackets,
                    semantic);
        }

        /** Runs a pass and applies it, as the buffer does when nothing superseded it. */
        HighlightPass.Result pass() {
            return pass(null);
        }

        HighlightPass.Result pass(List<SemanticToken> semantic) {
            HighlightPass.Result r = HighlightPass.run(request(semantic), () -> false);
            assertNotNull(r);
            assertEquals(!r.symbols().equals(symbols), r.symbolsChanged(), "symbolsChanged");
            paint(painted, r);
            lines = r.lines();
            symbols = r.symbols();
            dirty.applied();
            return r;
        }

        /** A pass that is computed and then discarded (superseded): the document keeps owing its range. */
        void droppedPass() {
            assertNotNull(HighlightPass.run(request(null), () -> false));
        }

        void assertMatchesFullTokenize(String context) {
            String now = text.toString();
            HighlightPass.Result full = HighlightPass.run(
                    new HighlightPass.Request(now, grammar, null, List.of(), -1, 0, brackets, null), () -> false);
            List<Collection<String>> expected = new ArrayList<>(java.util.Collections.nCopies(now.length(), UNPAINTED));
            paint(expected, full);
            for (int i = 0; i < now.length(); i++) {
                if (!expected.get(i).equals(painted.get(i))) {
                    int lineStart = now.lastIndexOf('\n', i - 1) + 1;
                    int lineEnd = now.indexOf('\n', i);
                    throw new AssertionError(context + ": style at offset " + i + " is " + painted.get(i)
                            + ", a full tokenize gives " + expected.get(i) + " — line: "
                            + now.substring(lineStart, lineEnd < 0 ? now.length() : lineEnd));
                }
            }
            assertArrayEquals(full.lines().states(), lines.states(), context + ": per-line grammar states");
            assertArrayEquals(full.lines().depths(), lines.depths(), context + ": per-line bracket depths");
            assertEquals(full.symbols(), symbols, context + ": symbols");
        }
    }

    private static void paint(List<Collection<String>> painted, HighlightPass.Result r) {
        if (r.spans() == null) {
            return;
        }
        int at = r.fromOffset();
        for (StyleSpan<Collection<String>> span : r.spans()) {
            for (int i = 0; i < span.getLength(); i++) {
                painted.set(at++, span.getStyle());
            }
        }
    }

    private static IGrammar grammar(String fileName) {
        IGrammar g = GrammarRegistry.shared().forFileName(fileName);
        assertNotNull(g, "bundled grammar for " + fileName);
        return g;
    }

    /** One unit of {@link #javaSource}: javadoc, block and line comments, strings, a text block, generics. */
    private static final String JAVA_UNIT = String.join(
            "\n",
            "/**",
            " * Sample type #: a {@code doc} comment with <b>markup</b>.",
            " */",
            "@SuppressWarnings(\"unchecked\")",
            "final class Sample#<T extends Comparable<T>> {",
            "",
            "    /* a block comment",
            "       over two lines { [ ( */",
            "    private static final String NAME = \"sample-# { [ (\";",
            "    private final java.util.Map<String, java.util.List<int[]>> table = new java.util.HashMap<>();",
            "",
            "    // Adjacent runs with the same style are merged",
            "    int compute(int base, T other) {",
            "        int total = base + #;",
            "        for (int i = 0; i < table.size(); i++) {",
            "            total += table.get(\"k\" + i).get(0)[i % 2];",
            "        }",
            "        String block = \"\"\"",
            "            text block { with \"quotes\"",
            "            and more \"\"\";",
            "        Runnable r = () -> System.out.println(block + NAME + '\\'' + other);",
            "        r.run();",
            "        return total > 0 ? total : -1;",
            "    }",
            "",
            "    static void log(String scope) {",
            "        System.err.println(\"Syntax highlighting: the \" + scope + \" grammar failed\");",
            "    }",
            "}",
            "",
            "");

    /** The first {@code lines} lines of a Java source made of numbered copies of {@link #JAVA_UNIT}. */
    private static String javaSource(int lines) {
        StringBuilder source = new StringBuilder();
        int perUnit = (int) JAVA_UNIT.chars().filter(c -> c == '\n').count();
        for (int unit = 0; unit * perUnit < lines; unit++) {
            source.append(JAVA_UNIT.replace("#", Integer.toString(unit)));
        }
        int end = 0;
        for (int i = 0; i < lines; i++) {
            end = source.indexOf("\n", end) + 1;
        }
        return source.substring(0, end);
    }

    private static final String MARKDOWN = String.join(
            "\n",
            "# Title",
            "",
            "Some *emphasis*, some `inline code` and a [link](https://example.com).",
            "",
            "```java",
            "class A {",
            "    /* a block",
            "       comment */",
            "    String s = \"text\";",
            "}",
            "```",
            "",
            "- item one",
            "- item two",
            "  continued",
            "",
            "> a quote",
            "> over two lines",
            "",
            "```js",
            "function f(a) { return `t${a}`; }",
            "```",
            "",
            "<div class=\"x\">",
            "html block",
            "</div>",
            "",
            "## Section",
            "",
            "1. first",
            "2. second",
            "",
            "    indented code",
            "",
            "Final paragraph.",
            "");

    private static final String PYTHON = String.join(
            "\n",
            "import os",
            "",
            "class Greeter:",
            "    \"\"\"A docstring",
            "    over several lines.\"\"\"",
            "",
            "    def greet(self, name: str) -> str:",
            "        # a comment",
            "        text = f\"hello {name}\"",
            "        table = {'a': [1, 2, (3, 4)], 'b': None}",
            "        return text",
            "",
            "def main():",
            "    s = '''triple",
            "    single'''",
            "    print(Greeter().greet(os.environ.get('USER', 'x')))",
            "",
            "if __name__ == '__main__':",
            "    main()",
            "");

    private static final String HTML = String.join(
            "\n",
            "<!DOCTYPE html>",
            "<html>",
            "<head>",
            "  <style>",
            "    body { color: red; /* note */ }",
            "  </style>",
            "  <script>",
            "    const x = { a: [1, 2], s: \"str\" }; // trailing",
            "    function f() { return `t${x.a}`; }",
            "  </script>",
            "</head>",
            "<body>",
            "  <!-- a comment",
            "       over lines -->",
            "  <p class=\"c\">Text &amp; more</p>",
            "</body>",
            "</html>",
            "");

    // ---- the pass covers only what the edit reaches ---------------------------------------------------

    @Test
    void aOneCharacterEditInALargeFileTokenizesOnlyAFewLines() {
        Doc doc = new Doc(grammar("A.java"), true, javaSource(45_000));
        int lines = doc.lineCount();
        int units = lines / 30;

        // Near the top, the middle and the end.
        int[] targets = {
            doc.lineOf("        r.run();", 0),
            doc.lineOf("        r.run();", units / 2),
            doc.lineOf("        r.run();", units - 1)
        };
        for (int line : targets) {
            doc.insert(doc.line(line) + 8, "x");
            HighlightPass.Result r = doc.pass();
            assertEquals(line, r.firstLine(), "the pass starts at the edited line");
            assertTrue(
                    r.lastLine() - r.firstLine() < 3,
                    "an edit at line " + line + " tokenized lines " + r.firstLine() + ".." + r.lastLine() + " of "
                            + lines);
            assertTrue(r.length() < 400, "and restyles only those lines: " + r.length() + " chars");
        }
        assertTrue(targets[2] > lines - 40, "the last edit was at the end of the file");
        doc.assertMatchesFullTokenize("after the three edits");
    }

    @Test
    void openingABlockCommentRestylesToItsCloseAndDeletingItRestores() {
        Doc doc = new Doc(grammar("A.java"), true, javaSource(300));
        int lines = doc.lineCount();
        int at = doc.line(3);

        doc.insert(at, "/*");
        HighlightPass.Result opened = doc.pass();
        doc.assertMatchesFullTokenize("comment opened");
        assertTrue(opened.lastLine() > 20, "the comment swallows the code below: reached " + opened.lastLine());
        assertTrue(opened.lastLine() < lines - 1, "but only to the next '*/', not the end of the file");

        doc.replace(at, 2, "");
        HighlightPass.Result closed = doc.pass();
        doc.assertMatchesFullTokenize("comment deleted again");
        assertEquals(opened.lastLine(), closed.lastLine(), "restoring covers the same lines");
    }

    @Test
    void anUnterminatedBlockCommentRunsToTheEndOfTheFile() {
        String code = "class A {\n    int a;\n    int b;\n    String s = \"x\";\n}\n";
        Doc doc = new Doc(grammar("A.java"), true, code);
        doc.insert(doc.line(1), "/*");
        HighlightPass.Result r = doc.pass();
        assertEquals(doc.lineCount() - 1, r.lastLine(), "nothing closes it, so every line below changes");
        doc.assertMatchesFullTokenize("unterminated comment");
        doc.replace(doc.line(1), 2, "");
        doc.pass();
        doc.assertMatchesFullTokenize("restored");
    }

    @Test
    void aBracketThatShiftsEveryDepthBelowIsFollowedToTheEnd() {
        // The grammar state re-converges on the very line, but the nesting depth below does not.
        String code = "class A {\n    void f() {\n        g(1);\n    }\n    int[] a = {1, 2};\n}\n";
        Doc colored = new Doc(grammar("A.java"), true, code);
        colored.insert(colored.line(2), "(");
        HighlightPass.Result r = colored.pass();
        assertEquals(colored.lineCount() - 1, r.lastLine(), "every bracket below changes colour");
        colored.assertMatchesFullTokenize("extra open paren, bracket colours on");

        Doc plain = new Doc(grammar("A.java"), false, code);
        plain.insert(plain.line(2), "(");
        plain.pass();
        assertNull(plain.lines.depths(), "no depths are kept while the feature is off");
        plain.assertMatchesFullTokenize("extra open paren, bracket colours off");
    }

    // ---- equivalence with a full tokenize -------------------------------------------------------------

    @Test
    void aBatteryOfJavaEditsMatchesAFullTokenize() {
        Doc doc = new Doc(grammar("A.java"), true, javaSource(260));
        doc.assertMatchesFullTokenize("initial");

        doc.insert(0, "// first line\n");
        doc.pass();
        doc.assertMatchesFullTokenize("line inserted at the top");

        doc.insert(doc.text.length(), "class Tail {}");
        doc.pass();
        doc.assertMatchesFullTokenize("text appended to the last line");

        doc.replace(doc.line(40), doc.line(55) - doc.line(40), "");
        doc.pass();
        doc.assertMatchesFullTokenize("fifteen lines deleted");

        StringBuilder paste = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            paste.append("    int pasted")
                    .append(i)
                    .append(" = compute(\"s\" + ")
                    .append(i)
                    .append(");\n");
        }
        doc.insert(doc.line(70), paste.toString());
        doc.pass();
        doc.assertMatchesFullTokenize("120 lines pasted");

        int inString = doc.text.indexOf("\"Syntax highlighting: the \"") + 5;
        assertTrue(inString > 5, "the sample still contains the string this edits");
        doc.insert(inString, "{ [ ( x");
        doc.pass();
        doc.assertMatchesFullTokenize("brackets typed inside a string");
        doc.insert(inString, "\"");
        doc.pass();
        doc.assertMatchesFullTokenize("the string split by a quote");
        doc.replace(inString, 1, "");
        doc.pass();
        doc.assertMatchesFullTokenize("the quote removed");

        doc.insert(doc.line(100), "String block = \"\"\"\n");
        doc.pass();
        doc.assertMatchesFullTokenize("a text block opened");
        doc.insert(doc.line(104), "    \"\"\";\n");
        doc.pass();
        doc.assertMatchesFullTokenize("and closed four lines down");

        doc.replace(doc.line(20) - 1, 1, "");
        doc.pass();
        doc.assertMatchesFullTokenize("two lines joined");

        doc.replace(0, doc.line(1), "");
        doc.pass();
        doc.assertMatchesFullTokenize("the first line deleted");

        doc.replace(doc.line(doc.lineCount() - 2), doc.text.length() - doc.line(doc.lineCount() - 2), "}");
        doc.pass();
        doc.assertMatchesFullTokenize("the last lines replaced");
    }

    @Test
    void editsAcrossSupersededPassesAreAllCovered() {
        Doc doc = new Doc(grammar("A.java"), true, javaSource(260));
        doc.insert(doc.line(30), "/* ");
        doc.droppedPass(); // superseded by the next keystroke
        doc.insert(doc.line(200), "int late = 1;\n");
        doc.droppedPass(); // the text changed under this one too
        doc.insert(doc.line(120), "\"unterminated\n");
        HighlightPass.Result r = doc.pass();
        assertEquals(30, r.firstLine(), "the pass that lands starts at the earliest edit still owed");
        doc.assertMatchesFullTokenize("three edits, one applied pass");
    }

    @Test
    void severalEditsBeforeOnePassAreCoveredAsOneRange() {
        Doc doc = new Doc(grammar("A.java"), true, javaSource(260));
        doc.insert(doc.line(150), "x");
        doc.insert(doc.line(10), "y\n\n");
        doc.replace(doc.line(80), 5, "");
        HighlightPass.Result r = doc.pass();
        assertEquals(10, r.firstLine());
        assertTrue(r.lastLine() >= 152, "reaches the furthest edit (now two lines lower): " + r.lastLine());
        doc.assertMatchesFullTokenize("three coalesced edits");
    }

    @Test
    void markdownFencesAndEmbeddedGrammarsMatchAFullTokenize() {
        Doc doc = new Doc(grammar("a.md"), true, MARKDOWN.repeat(3));
        doc.assertMatchesFullTokenize("initial");

        int fence = doc.text.indexOf("```java");
        doc.replace(fence, 7, "");
        doc.pass();
        doc.assertMatchesFullTokenize("opening fence removed");

        doc.insert(fence, "```java");
        doc.pass();
        doc.assertMatchesFullTokenize("opening fence restored");

        doc.insert(doc.text.indexOf("class A {") + 9, "\n    int x = 1; // inside the fence");
        doc.pass();
        doc.assertMatchesFullTokenize("a line typed inside the embedded java");

        int close = doc.text.indexOf("```\n", fence + 7);
        doc.replace(close, 4, "");
        doc.pass();
        doc.assertMatchesFullTokenize("closing fence removed: the block runs on");

        doc.insert(doc.line(2), "```\nunfenced?\n");
        doc.pass();
        doc.assertMatchesFullTokenize("a fence opened near the top");

        doc.insert(0, "---\ntitle: x\n---\n");
        doc.pass();
        doc.assertMatchesFullTokenize("front matter added");

        doc.replace(doc.text.indexOf("# Title"), 2, "");
        doc.pass();
        doc.assertMatchesFullTokenize("a heading demoted to a paragraph");
    }

    @Test
    void randomEditsMatchAFullTokenizeInEveryLanguage() throws Exception {
        String[][] cases = {
            {"A.java", javaSource(140)},
            {"a.md", MARKDOWN.repeat(2)},
            {"a.py", PYTHON.repeat(4)},
            {"a.html", HTML.repeat(3)},
        };
        String[] snippets = {
            "x",
            " ",
            "\n",
            "/*",
            "*/",
            "\"",
            "'",
            "{",
            "}",
            "(",
            ")",
            "```",
            "```\n",
            "\"\"\"",
            "//",
            "#",
            "<!--",
            "-->",
            "<script>",
            "</script>",
            "\n\n",
            "int a = 1;\n",
            "    def f():\n",
            "`",
            "\\",
            "*",
            "> ",
            "\n    \n",
        };
        for (String[] c : cases) {
            Random random = new Random(20261005L + c[0].hashCode());
            for (boolean brackets : new boolean[] {true, false}) {
                Doc doc = new Doc(grammar(c[0]), brackets, c[1]);
                for (int step = 0; step < 60; step++) {
                    int edits = 1 + random.nextInt(3); // sometimes several edits settle into one pass
                    StringBuilder log = new StringBuilder();
                    for (int e = 0; e < edits; e++) {
                        int length = doc.text.length();
                        int pos = random.nextInt(length + 1);
                        int kind = random.nextInt(10);
                        int removed = kind < 3 ? Math.min(length - pos, random.nextInt(kind == 0 ? 200 : 4)) : 0;
                        String inserted = kind >= 2 ? snippets[random.nextInt(snippets.length)] : "";
                        if (kind == 9) { // a paste of several lines taken from the document itself
                            int from = random.nextInt(length + 1);
                            inserted = doc.text.substring(from, Math.min(length, from + random.nextInt(300)));
                        }
                        doc.replace(pos, removed, inserted);
                        log.append(" replace(")
                                .append(pos)
                                .append(", ")
                                .append(removed)
                                .append(", ")
                                .append(inserted.replace("\n", "\\n"))
                                .append(')');
                        if (random.nextInt(6) == 0) {
                            doc.droppedPass();
                        }
                    }
                    doc.pass();
                    doc.assertMatchesFullTokenize(c[0] + " brackets=" + brackets + " step " + step + ":" + log);
                }
            }
        }
    }

    // ---- symbols --------------------------------------------------------------------------------------

    @Test
    void symbolsAreOnlyReportedChangedWhenTheyAre() {
        Doc doc = new Doc(grammar("A.java"), true, javaSource(260));
        List<TextMateHighlighter.Symbol> before = doc.symbols;
        assertFalse(before.isEmpty());

        doc.insert(doc.text.indexOf("// Adjacent runs") + 3, "really ");
        HighlightPass.Result comment = doc.pass();
        assertFalse(comment.symbolsChanged(), "typing in a comment changes no definition");
        assertSame(before, comment.symbols(), "and hands the same list back");

        doc.insert(doc.line(50), "\n");
        HighlightPass.Result shifted = doc.pass();
        assertTrue(shifted.symbolsChanged(), "a new line moves every definition below it");
        doc.assertMatchesFullTokenize("line inserted");

        doc.insert(doc.text.indexOf("class Sample2") + 6, "Renamed");
        assertTrue(doc.pass().symbolsChanged(), "a renamed type is a changed symbol");
        doc.assertMatchesFullTokenize("class renamed");
    }

    // ---- style-only restyle (semantic tokens) -----------------------------------------------------------

    @Test
    void aRestyleRequestTokenizesExactlyItsLinesAndOverlaysTheTokens() {
        Doc doc = new Doc(grammar("A.java"), true, javaSource(260));
        int first = doc.lineOf("        int total = base + 3;", 0);
        int line = first + 2; // "            total += table.get(…"
        List<SemanticToken> tokens = List.of(
                new SemanticToken(first, 12, 5, "sem-variable"),
                new SemanticToken(line, 21, 5, "sem-parameter sem-readonly"),
                new SemanticToken(first + 60, 0, 2, "sem-type"));

        doc.dirty.include(doc.line(first), doc.line(line + 1) - 1); // three lines, as setSemanticTokens asks
        HighlightPass.Result r = doc.pass(tokens);
        assertEquals(first, r.firstLine());
        assertEquals(line, r.lastLine(), "unchanged text converges on the last requested line");
        assertFalse(r.symbolsChanged());
        assertEquals(List.of("sem-variable"), List.copyOf(doc.painted.get(doc.line(first) + 12)));
        assertEquals(List.of("sem-parameter", "sem-readonly"), List.copyOf(doc.painted.get(doc.line(line) + 21)));
        assertEquals(List.of("sem-parameter", "sem-readonly"), List.copyOf(doc.painted.get(doc.line(line) + 25)));
        assertFalse(doc.painted.get(doc.line(line) + 26).contains("sem-parameter"), "the token is five chars");
        assertFalse(doc.painted.get(doc.line(first + 60)).contains("sem-type"), "that line is outside the pass");

        // Without the tokens the same range goes back to the lexical styles.
        doc.dirty.include(doc.line(first), doc.line(line + 1) - 1);
        doc.pass();
        doc.assertMatchesFullTokenize("overlay removed again");
    }

    // ---- scheduling -------------------------------------------------------------------------------------

    @Test
    void passesForOneGrammarRunOneAtATimeWithoutHoldingMoreThanOnePoolThread() throws Exception {
        IGrammar java = grammar("A.java");
        IGrammar markdown = grammar("a.md");
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch firstRunning = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger javaRan = new AtomicInteger();
            AtomicInteger javaConcurrent = new AtomicInteger();
            AtomicInteger javaMaxConcurrent = new AtomicInteger();
            Runnable javaPass = () -> {
                javaMaxConcurrent.accumulateAndGet(javaConcurrent.incrementAndGet(), Math::max);
                firstRunning.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                javaConcurrent.decrementAndGet();
                javaRan.incrementAndGet();
            };
            for (int i = 0; i < 6; i++) {
                HighlightPass.submit(pool, java, javaPass);
            }
            assertTrue(firstRunning.await(30, TimeUnit.SECONDS));
            // Six java passes are queued behind one blocked pass; on a three-thread pool they used to
            // occupy every thread. Another language's pass and plain pool work must still get through.
            CountDownLatch others = new CountDownLatch(2);
            HighlightPass.submit(pool, markdown, others::countDown);
            pool.execute(others::countDown);
            assertTrue(others.await(30, TimeUnit.SECONDS), "the pool is not parked behind the java lane");
            assertEquals(0, javaRan.get(), "while the first java pass is still running");

            release.countDown();
            CountDownLatch drained = new CountDownLatch(1);
            HighlightPass.submit(pool, java, drained::countDown);
            assertTrue(drained.await(30, TimeUnit.SECONDS));
            assertEquals(6, javaRan.get(), "every queued pass ran, in order, before the one submitted last");
            assertEquals(1, javaMaxConcurrent.get(), "never two passes of one grammar at once");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aCancelledPassReturnsNothing() {
        assertNull(HighlightPass.run(
                new HighlightPass.Request("class A {}\n", grammar("A.java"), null, List.of(), -1, 0, true, null),
                () -> true));
        // …and a throwing pass does not wedge its grammar's lane.
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch after = new CountDownLatch(1);
            IGrammar g = grammar("a.py");
            HighlightPass.submit(pool, g, () -> {
                throw new IllegalStateException("boom");
            });
            HighlightPass.submit(pool, g, after::countDown);
            assertTrue(after.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
    }
}
