package com.editora.editor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.tm4e.core.grammar.IGrammar;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void diffScopesMapToDiffClasses() {
        assertEquals(
                "diff-inserted", TextMateHighlighter.styleForScopes(List.of("source.diff", "markup.inserted.diff")));
        assertEquals("diff-deleted", TextMateHighlighter.styleForScopes(List.of("source.diff", "markup.deleted.diff")));
        assertEquals("diff-changed", TextMateHighlighter.styleForScopes(List.of("source.diff", "markup.changed.diff")));
        assertEquals(
                "diff-range", TextMateHighlighter.styleForScopes(List.of("source.diff", "meta.diff.range.unified")));
        assertEquals(
                "diff-header",
                TextMateHighlighter.styleForScopes(List.of("source.diff", "meta.diff.header.from-file")));
        // markup.inserted must not shadow the log-info mapping (shared "markup.in" prefix).
        assertEquals("log-info", TextMateHighlighter.styleForScopes(List.of("source.log", "markup.info.log")));
    }

    @Test
    void storageScopesSeparateTypeNamesFromKeywords() {
        // Java and Groovy scope referenced type names under storage.type — they take the type class.
        for (String scope : List.of(
                "storage.type.java",
                "storage.type.generic.java",
                "storage.type.generic.wildcard.java",
                "storage.type.object.array.java",
                "storage.type.groovy",
                "storage.type.generic.groovy",
                "storage.type.object.array.groovy",
                "storage.type.parameters.groovy")) {
            assertEquals("type", TextMateHighlighter.styleForScopes(List.of("source.x", scope)), scope);
        }
        assertEquals(
                "annotation",
                TextMateHighlighter.styleForScopes(List.of("source.java", "storage.type.annotation.java")));
        // Real modifiers, declaration keywords and primitive type words stay keywords — in every grammar.
        for (String scope : List.of(
                "storage.modifier.java",
                "storage.modifier.extends.java",
                "storage.type.primitive.java",
                "storage.type.primitive.array.java",
                "storage.type.local.java",
                "storage.type.function.arrow.java",
                "storage.type.def.groovy",
                "storage.type.ts",
                "storage.type.class.ts",
                "storage.type.function.ts",
                "storage.type.rust",
                "storage.type.class.cs",
                "storage.type.built-in.primitive.c",
                "storage.type.numeric.go",
                "storage.modifier.kotlin",
                "storage.type.generic.lua")) {
            assertEquals("keyword", TextMateHighlighter.styleForScopes(List.of("source.x", scope)), scope);
        }
    }

    @Test
    void importAndPackagePathsArePlainAndDoNotFallThroughToAnOuterScope() {
        assertNull(TextMateHighlighter.styleForScopes(
                List.of("source.java", "meta.import.java", "storage.modifier.import.java")));
        assertNull(TextMateHighlighter.styleForScopes(
                List.of("source.java", "meta.package.java", "storage.modifier.package.java")));
        assertNull(TextMateHighlighter.styleForScopes(List.of("source.groovy", "storage.modifier.import.groovy")));
        // The path scope decides the token: an enclosing styled scope must not leak into it.
        assertNull(TextMateHighlighter.styleForScopes(List.of("keyword.other.outer", "storage.modifier.import.java")));
    }

    // --- end-to-end tokenization through a real grammar ---

    /** The style class of the first occurrence of {@code token} in {@code text}, or {@code null} if unstyled. */
    private static String styleOf(String fileName, String text, String token) {
        IGrammar grammar = GrammarRegistry.shared().forFileName(fileName);
        assertNotNull(grammar, "grammar should load for " + fileName);
        StyleSpans<Collection<String>> spans = TextMateHighlighter.compute(text, grammar);
        assertNotNull(spans);
        int at = text.indexOf(token);
        assertTrue(at >= 0, "token " + token + " is in the sample");
        Collection<String> style = spans.getStyleSpan(
                        spans.offsetToPosition(at, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                                .getMajor())
                .getStyle();
        return style.isEmpty() ? null : style.iterator().next();
    }

    @Test
    void javaTypeNamesAndImportPathsAreNotStyledAsKeywords() {
        String text = "package com.acme.app;\n"
                + "import java.util.List;\n"
                + "public final class Foo extends Base {\n"
                + "    private final Map<String, Widget> cache = new HashMap<>();\n"
                + "    int count(Registry registry, Entry[] entries) throws IOException {\n"
                + "        var total = 0;\n"
                + "        return total;\n"
                + "    }\n"
                + "}\n";
        for (String type : List.of("Map", "String", "Widget", "HashMap", "Registry", "Entry", "IOException")) {
            assertEquals("type", styleOf("Foo.java", text, type), type);
        }
        for (String plain : List.of("com", "acme", "java", "util", "List")) {
            assertNull(styleOf("Foo.java", text, plain), plain + " is part of a package/import path");
        }
        for (String keyword : List.of(
                "package", "import", "public", "final", "class", "extends", "private", "int", "throws", "var",
                "return")) {
            assertEquals("keyword", styleOf("Foo.java", text, keyword), keyword);
        }
    }

    @Test
    void groovyTypeNamesAndImportPathsAreNotStyledAsKeywords() {
        String text =
                "import groovy.json.JsonSlurper\nclass Foo {\n    def run() {\n        int n = 1\n        Widget w = new Gadget()\n    }\n}\n";
        assertEquals("type", styleOf("Foo.groovy", text, "Widget"));
        assertEquals("type", styleOf("Foo.groovy", text, "Gadget"));
        assertNull(styleOf("Foo.groovy", text, "groovy.json"));
        assertEquals("keyword", styleOf("Foo.groovy", text, "int"));
    }

    @Test
    void otherGrammarsKeepTheirStorageKeywords() {
        record Case(String file, String text, String keyword) {}
        for (Case c : List.of(
                new Case("a.ts", "const x = 1;\nclass K {}\nfunction f() {}\n", "const"),
                new Case("a.ts", "const x = 1;\nclass K {}\nfunction f() {}\n", "class"),
                new Case("a.ts", "const x = 1;\nclass K {}\nfunction f() {}\n", "function"),
                new Case("a.rs", "fn f() {\n    let x = 1;\n}\n", "fn"),
                new Case("a.rs", "fn f() {\n    let x = 1;\n}\n", "let"),
                new Case("a.cs", "public class K {\n}\n", "class"),
                new Case("a.c", "static int f(void) {\n    return 0;\n}\n", "int"),
                new Case("a.cpp", "class K {\n};\n", "class"),
                new Case("a.kt", "private fun f() {}\n", "private"),
                new Case("a.go", "var x int = 1\n", "int"))) {
            assertEquals("keyword", styleOf(c.file(), c.text(), c.keyword()), c.file() + " " + c.keyword());
        }
    }

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
    void astroGrammarHighlightsEmbeddedLanguagesAndFindsStructure() {
        IGrammar astro = GrammarRegistry.shared().forFileName("Card.astro");
        assertNotNull(astro, "Astro grammar should load");
        String text = "---\nconst title = 'Hello';\n---\n<section class=\"card\">\n  <h2>{title}</h2>\n</section>\n";
        TextMateHighlighter.Analysis analysis = TextMateHighlighter.analyze(text, astro);
        assertNotNull(analysis);
        assertEquals(text.length(), analysis.spans().length());
        assertTrue(hasStyle(analysis.spans(), "string"), "expected frontmatter/attribute string highlighting");
        assertTrue(
                analysis.symbols().stream().anyMatch(s -> "tag".equals(s.kind()) && "section".equals(s.name())),
                "expected Astro markup tags in the Structure model");
    }

    @Test
    void plainTextFormatGrammarsTokenize() {
        // The 2026-07 plain-text-format batch: vendored diff/makefile/just/proto/graphql grammars plus
        // the in-house properties/gitattributes ones — each loads and produces the expected span kind.
        record Case(String file, String text, String expectStyle) {}
        for (Case c : List.of(
                new Case("fix.patch", "--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-old line\n+new line\n", "diff-inserted"),
                new Case("fix.patch", "--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-old line\n+new line\n", "diff-deleted"),
                new Case("fix.patch", "--- a/x\n+++ b/x\n@@ -1,2 +1,2 @@\n-old\n+new\n", "diff-range"),
                new Case("Makefile", "# build\nall: main.o\n\tcc -o app main.o\n", "comment"),
                new Case("justfile", "# recipes\nbuild:\n    cargo build\n", "comment"),
                new Case("api.proto", "syntax = \"proto3\";\nmessage User {\n  string name = 1;\n}\n", "string"),
                new Case("schema.graphql", "type Query {\n  user(id: ID!): User\n}\n", "keyword"),
                new Case("app.properties", "# config\ngreeting = hello\\u0021\n", "comment"),
                new Case("app.properties", "greeting = hello\\u0021 \\\n  continued\n", "escape"),
                new Case(".gitattributes", "# attrs\n*.png binary\n*.java diff=java\n", "attribute"))) {
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
    void aCancelledPassStopsAndReturnsNullInsteadOfFinishing() {
        // The cancel check is what lets a superseded pass release the shared grammar monitor at the next
        // line instead of tokenizing a huge document to completion (the CI suite wedge: a leaked 16k-line
        // pass serialized every later java highlight behind the per-grammar lock).
        IGrammar java = GrammarRegistry.shared().forFileName("Foo.java");
        assertNotNull(java);
        String text = "class A {\n".repeat(200) + "}\n".repeat(200);

        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        // Cancel after a couple of lines: the pass must give up rather than deliver a result.
        TextMateHighlighter.IncrementalAnalysis a =
                TextMateHighlighter.analyzeFrom(text, java, 0, null, () -> checks.incrementAndGet() > 2);
        assertNull(a, "a cancelled pass returns null (never a partial analysis)");
        assertTrue(checks.get() <= 4, "cancellation is honoured within a line of being requested");

        // Never cancelled → identical behaviour to the 4-arg form.
        TextMateHighlighter.IncrementalAnalysis full =
                TextMateHighlighter.analyzeFrom(text, java, 0, null, () -> false);
        assertNotNull(full);
        assertEquals(text.length(), full.spans().length());
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
    void javaMethodCallsAreNotListedAsDeclarations() {
        // Java scopes `x.call()` as meta.method-call, not meta.function-call: the outline used to list
        // getCode(), isEmpty() and test() as members of the enclosing method.
        String text = "class KeyDispatcher {\n"
                + "    void handle(KeyEvent event, String s) {\n"
                + "        if (event.getCode() == KeyCode.ALT) {\n"
                + "            s.isEmpty();\n"
                + "            recordTarget.test(x);\n"
                + "            reset();\n"
                + "        }\n"
                + "    }\n"
                + "}\n";
        List<TextMateHighlighter.Symbol> symbols = symbolsOf("KeyDispatcher.java", text);
        assertEquals(
                List.of("KeyDispatcher", "handle"),
                symbols.stream().map(TextMateHighlighter.Symbol::name).toList());
    }

    @Test
    void declarationsNestedInACallsArgumentsAreStillListed() {
        // Java keeps meta.method-call open across the whole argument list, so an anonymous class's methods
        // and a local class sit inside one. Their own declaration scope is nearer and must win — while the
        // calls inside those bodies are still calls.
        String text = "class A {\n"
                + "    void wire() {\n"
                + "        button.setOnAction(new EventHandler<ActionEvent>() {\n"
                + "            public void handle(ActionEvent e) {\n"
                + "                go();\n"
                + "                e.consume();\n"
                + "            }\n"
                + "        });\n"
                + "        submit(new Runnable() {\n"
                + "            public void run() {}\n"
                + "        });\n"
                + "    }\n"
                + "}\n";
        assertEquals(
                List.of("A", "wire", "handle", "run"),
                symbolsOf("A.java", text).stream()
                        .map(TextMateHighlighter.Symbol::name)
                        .toList());
        // The decision is the innermost structural scope, in either direction.
        assertEquals(
                "function",
                TextMateHighlighter.kindForScopes(List.of(
                        "source.java",
                        "meta.method-call.java",
                        "meta.inner-class.java",
                        "meta.method.java",
                        "meta.method.identifier.java",
                        "entity.name.function.java")));
        assertNull(TextMateHighlighter.kindForScopes(List.of(
                "source.java",
                "meta.method.java",
                "meta.method.body.java",
                "meta.function-call.java",
                "entity.name.function.java")));
    }

    @Test
    void callsAreNotDeclarationsInAnyBundledGrammarThatMarksThem() {
        // One sample per bundled grammar with a call scope: a declaration named `decl` followed by plain,
        // member and (where the language has them) static calls. Only the declaration may be a symbol.
        record Case(String file, String text) {}
        for (Case c : List.of(
                new Case("a.java", "class K {\n  void decl() {\n    o.callA();\n    callB(1);\n  }\n}\n"),
                new Case("a.groovy", "class K {\n  def decl() {\n    o.callA()\n    callB(1)\n  }\n}\n"),
                new Case("a.php", "<?php\nfunction decl($a) {\n  $a->callA();\n  K::callB(1);\n  callC(2);\n}\n"),
                new Case("a.c", "int decl(int a) {\n  o.callA(1);\n  p->callB(1);\n  callC(2);\n  return 0;\n}\n"),
                new Case("a.cpp", "int decl(int a) {\n  o.callA(1);\n  callB(2);\n  ns::callC(3);\n  return 0;\n}\n"),
                new Case("a.kt", "fun decl(a: Int) {\n  o.callA(1)\n  callB(2)\n  listOf(1).map(::callC)\n}\n"),
                new Case("a.go", "func decl(a int) {\n  o.CallA(1)\n  callB(2)\n  n := len(s)\n}\n"),
                new Case("a.rs", "fn decl(a: i32) {\n  o.call_a(1);\n  call_b(2);\n  println!(\"x\");\n}\n"),
                new Case("a.ts", "function decl(a: number) {\n  o.callA(1);\n  callB(2);\n  const s = callC`x`;\n}\n"),
                new Case("a.tsx", "function decl(a: number) {\n  o.callA(1);\n  callB(2);\n}\n"),
                new Case("a.py", "def decl(a):\n    o.call_a(1)\n    call_b(2)\n"),
                new Case("a.rb", "def decl(a)\n  o.call_a(1)\n  call_b(2)\nend\n"),
                new Case("a.tf", "locals {\n  decl = max(1, 2)\n}\n"))) {
            List<String> functions = symbolsOf(c.file(), c.text()).stream()
                    .filter(s -> s.kind().equals("function"))
                    .map(TextMateHighlighter.Symbol::name)
                    .toList();
            assertTrue(
                    functions.stream().allMatch("decl"::equals),
                    c.file() + " listed a call as a declaration: " + functions);
        }
    }

    @Test
    void callScopesAreRecognisedButDeclarationScopesAreNot() {
        for (String call : List.of(
                "meta.function-call.java",
                "meta.method-call.java",
                "meta.method-call.static.php",
                "meta.function.call.rust",
                "meta.macro.rust",
                "entity.name.function.call.cpp",
                "entity.name.function.member.c",
                "entity.name.function.support.builtin.go",
                "entity.name.function.reference.kotlin",
                "entity.name.function.tagged-template.ts",
                "entity.name.function.decorator.python")) {
            assertTrue(TextMateHighlighter.isCallScope(call), call);
            assertNull(TextMateHighlighter.kindForScopes(List.of("source.x", call, "entity.name.function.x")), call);
        }
        for (String declaration : List.of(
                "meta.function.definition.rust",
                "meta.method.identifier.java",
                "meta.macro.rules.rust",
                "entity.name.function.java",
                "entity.name.function.definition.cpp",
                "entity.name.function.declaration.kotlin",
                "entity.name.function.preprocessor.c",
                "entity.name.function.macro.rust",
                "entity.name.function.target.makefile")) {
            assertFalse(TextMateHighlighter.isCallScope(declaration), declaration);
        }
        assertEquals(
                "function",
                TextMateHighlighter.kindForScopes(
                        List.of("source.rust", "meta.macro.rules.rust", "entity.name.function.macro.rust")));
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
