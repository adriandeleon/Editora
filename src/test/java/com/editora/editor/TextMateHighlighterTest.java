package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextMateHighlighterTest {

    private static boolean hasStyle(StyleSpans<Collection<String>> spans, String style) {
        for (StyleSpan<Collection<String>> span : spans) {
            if (span.getStyle().contains(style)) {
                return true;
            }
        }
        return false;
    }

    // --- scope -> style mapping (pure logic) ---

    @Test
    void mostSpecificScopeWins() {
        assertEquals("keyword", TextMateHighlighter.styleForScopes(List.of("source.java", "keyword.control.java")));
        assertEquals(
                "comment",
                TextMateHighlighter.styleForScopes(List.of("source.java", "comment.line.double-slash.java")));
        assertEquals("number", TextMateHighlighter.styleForScopes(List.of("source.java", "constant.numeric.java")));
        assertEquals("string", TextMateHighlighter.styleForScopes(List.of("source.java", "string.quoted.double.java")));
        assertEquals(
                "function", TextMateHighlighter.styleForScopes(List.of("source.java", "entity.name.function.java")));
    }

    @Test
    void unmappedScopesYieldNull() {
        assertNull(TextMateHighlighter.styleForScopes(List.of("source.java", "meta.class.body.java")));
        assertNull(TextMateHighlighter.styleForScopes(List.of()));
        assertNull(TextMateHighlighter.styleForScopes(null));
    }

    // --- end-to-end tokenization through a real grammar ---

    @Test
    void emptyTextReturnsNull() {
        IGrammar java = GrammarRegistry.shared().forFileName("A.java");
        assertNotNull(java, "java grammar should load");
        assertNull(TextMateHighlighter.compute("", java));
    }

    @Test
    void javaGrammarLoadsAndStylesKeywords() {
        IGrammar java = GrammarRegistry.shared().forFileName("Foo.java");
        assertNotNull(java);
        String text = "public class Foo {\n    int x = 1;\n}\n";
        StyleSpans<Collection<String>> spans = TextMateHighlighter.compute(text, java);
        assertNotNull(spans);
        assertEquals(text.length(), spans.length());
        assertTrue(hasStyle(spans, "keyword"), "expected a keyword span");
        assertTrue(hasStyle(spans, "number"), "expected a number span");
    }

    @Test
    void phpGrammarLoadsAndStylesKeywords() {
        // The PHP grammar was newly bundled for PHP LSP support; verify it loads and tokenizes
        // (its embedded html/css/sql/json/xml scopes resolve against the other bundled grammars).
        IGrammar php = GrammarRegistry.shared().forFileName("Index.php");
        assertNotNull(php, "php grammar should load");
        String text = "<?php\nfunction greet($name) {\n    return \"hi \" . $name;\n}\n";
        StyleSpans<Collection<String>> spans = TextMateHighlighter.compute(text, php);
        assertNotNull(spans);
        assertEquals(text.length(), spans.length());
        assertTrue(hasStyle(spans, "keyword"), "expected a keyword span (function/return)");
        assertTrue(hasStyle(spans, "string"), "expected a string span");
    }

    @Test
    void newlyBundledGrammarsLoadAndTokenize() {
        // Lua, Dockerfile, Terraform, and TOML grammars were bundled for their LSP support — verify each
        // loads through tm4e and produces spans (a malformed grammar would yield null from forFileName).
        record Case(String file, String text, String expectStyle) {}
        for (Case c : List.of(
                new Case("init.lua", "local x = 1\nfunction f() return x end\n", "keyword"),
                new Case("Dockerfile", "FROM alpine:3\nRUN echo hi\n", "keyword"),
                new Case("main.tf", "resource \"aws_s3_bucket\" \"b\" {\n  bucket = \"x\"\n}\n", "string"),
                new Case("config.toml", "[server]\nport = 8080\n", "number"))) {
            IGrammar g = GrammarRegistry.shared().forFileName(c.file());
            assertNotNull(g, c.file() + " grammar should load");
            StyleSpans<Collection<String>> spans = TextMateHighlighter.compute(c.text(), g);
            assertNotNull(spans, c.file() + " should tokenize");
            assertEquals(c.text().length(), spans.length());
            assertTrue(hasStyle(spans, c.expectStyle()), c.file() + " expected a " + c.expectStyle() + " span");
        }
    }

    @Test
    void dockerfileResolvesByBareFilename() {
        // "Dockerfile" has no extension — both registries must still recognize it.
        assertNotNull(GrammarRegistry.shared().forFileName("Dockerfile"));
        assertNotNull(GrammarRegistry.shared().forFileName("Dockerfile.dev"));
    }

    @Test
    void multiLineBlockCommentStaysComment() {
        IGrammar java = GrammarRegistry.shared().forFileName("A.java");
        assertNotNull(java);
        // The comment opens on line 1 and closes on line 3 — state must carry across lines.
        String text = "/* start\n still comment\n end */ int y;\n";
        StyleSpans<Collection<String>> spans = TextMateHighlighter.compute(text, java);
        assertEquals(text.length(), spans.length());
        assertTrue(hasStyle(spans, "comment"));
    }

    // --- incremental tokenization (re-highlight from a changed line) ---

    private static List<Collection<String>> flatten(StyleSpans<Collection<String>> spans) {
        List<Collection<String>> out = new ArrayList<>();
        for (StyleSpan<Collection<String>> span : spans) {
            for (int i = 0; i < span.getLength(); i++) {
                out.add(span.getStyle());
            }
        }
        return out;
    }

    @Test
    void incrementalResumeMatchesFullPass() {
        IGrammar java = GrammarRegistry.shared().forFileName("Foo.java");
        assertNotNull(java);
        String text = "public class Foo {\n    int x = 1;\n    String s = \"hi\";\n}\n";
        TextMateHighlighter.IncrementalAnalysis full = TextMateHighlighter.analyzeFrom(text, java, 0, null);
        assertEquals(0, full.fromOffset());
        assertEquals(text.length(), full.spans().length());
        List<Collection<String>> fullFlat = flatten(full.spans());

        // Resume from line 2 using the stored end-state of line 1; the suffix styling must match.
        int fromLine = 2;
        TextMateHighlighter.IncrementalAnalysis inc = TextMateHighlighter.analyzeFrom(
                text, java, fromLine, full.endStates().get(fromLine - 1));
        assertTrue(inc.fromOffset() > 0);
        assertEquals(text.length() - inc.fromOffset(), inc.spans().length());
        List<Collection<String>> incFlat = flatten(inc.spans());
        for (int i = 0; i < incFlat.size(); i++) {
            assertEquals(
                    fullFlat.get(inc.fromOffset() + i),
                    incFlat.get(i),
                    "style mismatch at offset " + (inc.fromOffset() + i));
        }
    }

    @Test
    void incrementalResumeInsideBlockCommentStaysComment() {
        IGrammar java = GrammarRegistry.shared().forFileName("A.java");
        assertNotNull(java);
        String text = "/* open\n still inside\n closed */ int y;\n";
        TextMateHighlighter.IncrementalAnalysis full = TextMateHighlighter.analyzeFrom(text, java, 0, null);
        // Resume from line 1 ("still inside") carrying line 0's end-state — must still be comment.
        TextMateHighlighter.IncrementalAnalysis inc =
                TextMateHighlighter.analyzeFrom(text, java, 1, full.endStates().get(0));
        assertTrue(hasStyle(inc.spans(), "comment"));
    }

    @Test
    void unknownExtensionHasNoGrammar() {
        assertNull(GrammarRegistry.shared().forFileName("notes.txt"));
        assertNull(GrammarRegistry.shared().forFileName(null));
    }

    // --- symbol extraction (structure view) ---

    private static List<TextMateHighlighter.Symbol> symbolsOf(String fileName, String text) {
        IGrammar grammar = GrammarRegistry.shared().forFileName(fileName);
        assertNotNull(grammar, "grammar should load for " + fileName);
        TextMateHighlighter.Analysis analysis = TextMateHighlighter.analyze(text, grammar);
        assertNotNull(analysis);
        return analysis.symbols();
    }

    private static boolean hasSymbol(List<TextMateHighlighter.Symbol> symbols, String name, String kind) {
        return symbols.stream().anyMatch(s -> s.name().equals(name) && s.kind().equals(kind));
    }

    @Test
    void analyzeExtractsJavaDefinitionsAndExcludesCalls() {
        String text = "public class Foo {\n    void bar() {\n        baz();\n    }\n}\n";
        List<TextMateHighlighter.Symbol> symbols = symbolsOf("Foo.java", text);
        assertTrue(hasSymbol(symbols, "Foo", "type"), "expected the class as a type symbol");
        assertTrue(hasSymbol(symbols, "bar", "function"), "expected the method as a function symbol");
        assertTrue(
                symbols.stream().noneMatch(s -> s.name().equals("baz")),
                "a function call must not appear as a definition");
    }

    @Test
    void analyzeExtractsMarkdownSections() {
        List<TextMateHighlighter.Symbol> symbols = symbolsOf("a.md", "# Title\n\nbody text\n");
        assertTrue(
                symbols.stream().anyMatch(s -> s.kind().equals("section")),
                "expected a markdown heading as a section symbol");
    }

    @Test
    void analyzeExtractsXmlTags() {
        List<TextMateHighlighter.Symbol> symbols = symbolsOf("a.xml", "<root>\n  <child/>\n</root>\n");
        assertTrue(symbols.stream().anyMatch(s -> s.kind().equals("tag")), "expected xml elements as tag symbols");
    }
}
