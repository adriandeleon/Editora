package com.editora.editops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IndentWindow} must give the edit the whole text gives — it is the same algorithm run on a slice —
 * while reading only a bounded slice. The first half is differential: every Enter / Tab / closer edit is
 * computed both ways, over this repository's own sources and over generated text built to run the back-scans
 * into their bounds, at many caret positions.
 */
class IndentWindowTest {

    private static final char[] TYPED = {'}', ')', ']', '\n', ' ', ';'};

    private record Sample(String name, String language, String text) {}

    @Test
    void everyEditMatchesTheWholeTextOverRealSources() throws IOException {
        List<Sample> corpus = realSources();
        assertTrue(corpus.size() >= 20, "the corpus should be a real one, found " + corpus.size());
        long compared = 0;
        for (Sample s : corpus) {
            compared += compareAt(s, positions(s.text(), 70, new Random(s.name().hashCode())));
        }
        assertTrue(compared > 20_000, "compared " + compared + " edits");
    }

    @Test
    void everyEditMatchesTheWholeTextWhereTheScansReachTheirBounds() {
        Random random = new Random(20261005);
        long compared = 0;
        for (String language : List.of("java", "shell", "ruby", "lua", "python", "xml", "html", "markdown")) {
            for (int round = 0; round < 6; round++) {
                Sample s = new Sample(language + "#" + round, language, generated(language, random, round));
                compared += compareAt(s, positions(s.text(), 100, random));
            }
        }
        assertTrue(compared > 20_000, "compared " + compared + " edits");
    }

    @Test
    void aKeystrokeReadsABoundedSliceHoweverLargeTheDocument() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; sb.length() < 2_000_000; i++) {
            sb.append("    ".repeat(1 + i % 3)).append("call(").append(i).append(");\n");
        }
        String text = sb.toString();
        int caret = text.length() - 1; // the end of the last line
        long[] read = new long[1];
        IndentWindow.Doc whole = IndentWindow.of(text);
        IndentWindow.Doc counting = new IndentWindow.Doc() {
            @Override
            public int length() {
                return whole.length();
            }

            @Override
            public int lineOf(int offset) {
                return whole.lineOf(offset);
            }

            @Override
            public int lineStart(int line) {
                return whole.lineStart(line);
            }

            @Override
            public int lineLength(int line) {
                return whole.lineLength(line);
            }

            @Override
            public String text(int from, int to) {
                read[0] += to - from;
                return whole.text(from, to);
            }
        };

        String unit = IndentWindow.unit(counting, 4, null, null);
        IndentWindow.enterEdit(counting, caret, "java", unit);
        IndentWindow.closerAlignIndent(Indenter.Style.BRACES, counting, caret, 4, "    ", '}');
        IndentWindow.tabEdit(counting, caret, caret, "java", 4, false, unit);
        IndentWindow.tabEdit(counting, caret, caret, "java", 4, true, unit);

        // The unit reads the head; each edit reads at most its back-scan plus the wrapped-statement margin.
        long bound = IndentWindow.HEAD_CHARS + 4L * (Indenter.MAX_SCAN + 64L * (Indenter.MAX_STATEMENT_LINES + 3));
        assertTrue(read[0] <= bound, "read " + read[0] + " characters of " + text.length() + ", bound " + bound);
    }

    @Test
    void theSliceStartsALineThatNoScanCanReach() {
        // 100 lines of 99 characters + newline: the back-scan covers MAX_SCAN characters = 80 lines, then
        // the wrapped-statement follow and one spare line.
        String text = ("x".repeat(99) + "\n").repeat(400);
        IndentWindow.Doc doc = IndentWindow.of(text);
        int line = 300;
        int expectedFirst = line - Indenter.MAX_SCAN / 100 - Indenter.MAX_STATEMENT_LINES - 1;
        assertEquals(doc.lineStart(expectedFirst), IndentWindow.backScanStart(doc, line));
        assertEquals(0, IndentWindow.backScanStart(doc, 5));
        assertEquals(0, IndentWindow.backScanStart(doc, 0));
    }

    @Test
    void theStringDocAddressesLinesLikeTheText() {
        IndentWindow.Doc doc = IndentWindow.of("ab\n\ncde");
        assertEquals(7, doc.length());
        assertEquals(0, doc.lineOf(0));
        assertEquals(0, doc.lineOf(2)); // the line break belongs to the line it ends
        assertEquals(1, doc.lineOf(3));
        assertEquals(2, doc.lineOf(4));
        assertEquals(2, doc.lineOf(7));
        assertEquals(2, doc.lineLength(0));
        assertEquals(0, doc.lineLength(1));
        assertEquals(3, doc.lineLength(2));
        assertEquals(4, doc.lineStart(2));
        assertEquals(0, IndentWindow.of("").lineOf(0));
    }

    // --- the comparison ---------------------------------------------------------------------------

    /** Compares every edit at every position; returns how many edits were compared. */
    private static long compareAt(Sample s, int[] carets) {
        String text = s.text();
        String language = s.language();
        IndentWindow.Doc doc = IndentWindow.of(text);
        Indenter.Style style = Indenter.styleFor(language);
        long compared = 0;
        Random random = new Random(text.length());
        for (int tabSize : new int[] {4, 2}) {
            for (Boolean insertSpaces : new Boolean[] {null, Boolean.TRUE}) {
                Integer indentSize = insertSpaces == null ? null : 3;
                String unit = IndentWindow.unit(doc, tabSize, insertSpaces, indentSize);
                assertEquals(Indenter.unitFor(text, tabSize, insertSpaces, indentSize), unit, s.name() + " unit");
                for (int caret : carets) {
                    String where = s.name() + " @" + caret + " tab " + tabSize + " spaces " + insertSpaces;
                    assertEquals(
                            Indenter.enterEdit(text, caret, language, tabSize, insertSpaces, indentSize),
                            IndentWindow.enterEdit(doc, caret, language, unit),
                            where + " enter");
                    int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
                    String currentIndent = leading(text.substring(lineStart, caret));
                    for (char typed : TYPED) {
                        assertEquals(
                                Indenter.closerAlignIndent(style, text, caret, tabSize, currentIndent, typed),
                                IndentWindow.closerAlignIndent(style, doc, caret, tabSize, currentIndent, typed),
                                where + " closer " + (int) typed);
                    }
                    int end = Math.min(text.length(), caret + random.nextInt(400));
                    for (boolean shift : new boolean[] {false, true}) {
                        assertEquals(
                                wholeTab(text, caret, caret, language, tabSize, shift, insertSpaces, indentSize),
                                IndentWindow.tabEdit(doc, caret, caret, language, tabSize, shift, unit),
                                where + " tab shift=" + shift);
                        assertEquals(
                                wholeTab(text, caret, end, language, tabSize, shift, insertSpaces, indentSize),
                                IndentWindow.tabEdit(doc, end, caret, language, tabSize, shift, unit),
                                where + ".." + end + " block tab shift=" + shift);
                    }
                    compared += 1 + TYPED.length + 4;
                }
            }
        }
        return compared;
    }

    /** The Tab edit as the editor computed it from the whole text: smart Tab, else the plain one. */
    private static Indenter.TabEdit wholeTab(
            String text, int a, int b, String language, int tabSize, boolean shift, Boolean spaces, Integer size) {
        Indenter.TabEdit edit = Indenter.smartTab(text, a, b, language, tabSize, shift, spaces, size);
        return edit != null ? edit : PlainTab.edit(text, a, b, language, tabSize, shift, spaces, size);
    }

    private static String leading(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(0, i);
    }

    /** Caret positions spread over the text: both ends, line starts, ends of indents, line ends, anywhere. */
    private static int[] positions(String text, int count, Random random) {
        int n = text.length();
        List<Integer> out = new ArrayList<>(List.of(0, n));
        while (out.size() < count && n > 0) {
            int at = random.nextInt(n + 1);
            int lineStart = text.lastIndexOf('\n', at - 1) + 1;
            int lineEnd = text.indexOf('\n', at);
            lineEnd = lineEnd < 0 ? n : lineEnd;
            switch (random.nextInt(4)) {
                case 0 -> out.add(lineStart);
                case 1 ->
                    out.add(lineStart
                            + leading(text.substring(lineStart, lineEnd)).length());
                case 2 -> out.add(lineEnd);
                default -> out.add(at);
            }
        }
        // The far end, where everything above is out of reach of a bounded scan.
        out.add(Math.max(0, text.lastIndexOf('\n', Math.max(0, n - 2)) + 1));
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    // --- the corpora ------------------------------------------------------------------------------

    private static List<Sample> realSources() throws IOException {
        List<Sample> out = new ArrayList<>();
        // The largest Java sources are the ones whose carets sit furthest from the top of the file.
        out.addAll(largest(Path.of("src/main/java"), ".java", "java", 14));
        out.addAll(largest(Path.of("src/test/java"), ".java", "java", 4));
        out.addAll(largest(Path.of("scripts"), ".py", "python", 5));
        out.addAll(largest(Path.of("scripts"), ".sh", "shell", 5));
        out.addAll(largest(Path.of(".github"), ".yml", "yaml", 2));
        out.addAll(largest(Path.of("src/main/resources/com/editora/styles"), ".css", "css", 2));
        out.addAll(largest(Path.of("src/main/resources/com/editora"), ".fxml", "xml", 2));
        out.addAll(largest(Path.of("docs"), ".md", "markdown", 3));
        out.addAll(largest(Path.of("src/main/resources/com/editora/keymaps"), ".json", "json", 1));
        add(out, Path.of("pom.xml"), "xml");
        add(out, Path.of("Makefile"), "makefile");
        return out;
    }

    private static List<Sample> largest(Path root, String extension, String language, int count) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(extension) && Files.isRegularFile(p))
                    .sorted(Comparator.comparingLong(IndentWindowTest::size)
                            .reversed()
                            .thenComparing(Path::toString))
                    .limit(count)
                    .toList();
        }
        List<Sample> out = new ArrayList<>();
        for (Path file : files) {
            add(out, file, language);
        }
        return out;
    }

    private static void add(List<Sample> out, Path file, String language) throws IOException {
        if (Files.isRegularFile(file)) {
            out.add(new Sample(file.toString(), language, Files.readString(file).replace("\r\n", "\n")));
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Text shaped to defeat a bounded scan: long runs at one indent (no shallower line within reach), blank
     * stretches, wrapped statements, very long lines, unbalanced brackets and closer keywords.
     */
    private static String generated(String language, Random random, int round) {
        String[] words =
                switch (language) {
                    case "shell" ->
                        new String[] {
                            "if x; then",
                            "fi",
                            "done",
                            "for i in a b; do",
                            "echo hi \\",
                            "cmd arg,",
                            "esac",
                            "else",
                            ";;",
                            "{",
                            "}"
                        };
                    case "ruby" ->
                        new String[] {
                            "def m(a,",
                            "end",
                            "if x",
                            "else",
                            "foo(a,",
                            "b)",
                            "x.each do |i|",
                            "rescue",
                            "}",
                            "{",
                            "puts 1"
                        };
                    case "lua" ->
                        new String[] {
                            "function f(a,",
                            "end",
                            "if x then",
                            "else",
                            "until y",
                            "foo(function()",
                            "local t = {",
                            "}",
                            "x = 1"
                        };
                    case "python" ->
                        new String[] {
                            "def f(a,", "b):", "if x:", "else:", "return [", "]", "x = (1,", "2)", "pass", "except E:"
                        };
                    case "xml", "html" ->
                        new String[] {
                            "<div>",
                            "</div>",
                            "<br>",
                            "<a href=\"x\">",
                            "</a>",
                            "text",
                            "<p",
                            "class=\"x\">",
                            "<!-- c -->"
                        };
                    case "markdown" -> new String[] {"- item", "text here", "```", "| a | b |", "> quote", "1. one"};
                    default ->
                        new String[] {
                            "if (a) {",
                            "}",
                            "foo(a,",
                            "b);",
                            "return x",
                            ".chain()",
                            "#define X",
                            "int[] a = {",
                            "};",
                            "} else {",
                            "call(",
                            ")",
                            "// note {",
                            "String s = \"{(\";",
                            "x = y;"
                        };
                };
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        int lines = 700 + random.nextInt(500);
        for (int i = 0; i < lines; i++) {
            int roll = random.nextInt(100);
            if (round % 3 == 1 && i % 300 > 40) {
                roll = 50 + roll / 2; // long runs with no change of depth: nothing shallower within the scan
            } else if (roll < 12) {
                depth = Math.max(0, depth + (random.nextBoolean() ? 1 : -1));
            }
            if (roll < 18) {
                sb.append(random.nextBoolean() ? "" : "   ").append('\n'); // blank, sometimes whitespace-only
                continue;
            }
            String indent = round % 2 == 0 ? "    ".repeat(depth) : "\t".repeat(depth);
            sb.append(indent).append(words[random.nextInt(words.length)]);
            if (roll > 96) {
                sb.append(" ").append("long ".repeat(400 + random.nextInt(2500))); // a line longer than the scan
            }
            sb.append('\n');
        }
        if (round % 2 == 0) {
            sb.setLength(sb.length() - 1); // no final newline
        }
        return sb.toString();
    }
}
