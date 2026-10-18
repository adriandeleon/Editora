package com.editora.index;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner is a heuristic, so these tests are as much about what it must <em>not</em> report as what it
 * must. A false positive sends the user to a location that isn't a declaration and costs the feature its
 * credibility; a false negative costs one fallback to search. The asymmetry is deliberate and pinned here.
 */
class DeclarationScannerTest {

    private static List<String> names(String text, String language) {
        return DeclarationScanner.scan(text, language).stream()
                .map(Symbol::name)
                .toList();
    }

    private static Symbol only(String text, String language) {
        List<Symbol> found = DeclarationScanner.scan(text, language);
        assertEquals(1, found.size(), () -> "expected exactly one symbol, got " + found);
        return found.get(0);
    }

    @Nested
    @DisplayName("java")
    class Java {

        @Test
        void findsTypesMethodsAndFields() {
            String src = """
                    package com.example;

                    public class Greeter {
                        private final String name = "world";

                        public String greet(String who) {
                            return "hi " + who;
                        }
                    }
                    """;
            assertEquals(List.of("com.example", "Greeter", "name", "greet"), names(src, "java"));
        }

        @Test
        void aMethodKnowsItsEnclosingType() {
            String src = """
                    class Outer {
                        void inside() {
                        }
                    }
                    """;
            List<Symbol> found = DeclarationScanner.scan(src, "java");
            Symbol method = found.stream()
                    .filter(s -> s.name().equals("inside"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Outer", method.container());
            assertEquals(SymbolKind.METHOD, method.kind());
            assertEquals("Outer.inside", method.qualified());
        }

        @Test
        void aTypeStopsBeingTheContainerAfterItCloses() {
            // A plain stack of names never pops, so everything after the first class claims it forever.
            String src = """
                    class First {
                        void a() {
                        }
                    }
                    class Second {
                        void b() {
                        }
                    }
                    """;
            List<Symbol> found = DeclarationScanner.scan(src, "java");
            Symbol b =
                    found.stream().filter(s -> s.name().equals("b")).findFirst().orElseThrow();
            assertEquals("Second", b.container());
        }

        @Test
        void doesNotReportCallsInsideMethodBodies() {
            String src = """
                    class C {
                        void run() {
                            helper(1);
                            System.out.println("x");
                            if (ready()) {
                                doThing();
                            }
                        }
                    }
                    """;
            assertEquals(List.of("C", "run"), names(src, "java"));
        }

        @Test
        void doesNotReportControlKeywordsAsMethods() {
            String src = """
                    class C {
                        void run() {
                            while (x) {
                            }
                            for (int i = 0; i < 3; i++) {
                            }
                            switch (v) {
                            }
                        }
                    }
                    """;
            List<String> found = names(src, "java");
            assertFalse(found.contains("while"), found.toString());
            assertFalse(found.contains("for"), found.toString());
            assertFalse(found.contains("switch"), found.toString());
        }

        @Test
        void findsAnAbstractMethodWithNoBody() {
            String src = """
                    interface Shape {
                        double area();
                        void scale(double factor);
                    }
                    """;
            assertEquals(List.of("Shape", "area", "scale"), names(src, "java"));
        }

        @Test
        void reportsTheColumnOfTheNameNotTheLine() {
            Symbol s = only("class Greeter {}", "java");
            assertEquals(0, s.line());
            assertEquals("class ".length(), s.column());
        }

        @Test
        void recordsAndEnumsAreFound() {
            assertEquals(List.of("Point"), names("record Point(int x, int y) {}", "java"));
            assertEquals(SymbolKind.ENUM, only("enum Color { RED }", "java").kind());
        }
    }

    @Nested
    @DisplayName("comments and strings are not source")
    class Blanking {

        @Test
        void aDeclarationInsideABlockCommentIsIgnored() {
            String src = """
                    /**
                     * Example: class Frobnicator is not real.
                     */
                    class Real {}
                    """;
            assertEquals(List.of("Real"), names(src, "java"));
        }

        @Test
        void aDeclarationInsideAStringIsIgnored() {
            String src = "class C { String sql = \"create function foo()\"; }";
            List<String> found = names(src, "java");
            assertTrue(found.contains("C"), found.toString());
            assertFalse(found.contains("foo"), found.toString());
        }

        @Test
        void aDeclarationInsideAPythonDocstringIsIgnored() {
            String src = """
                    def real():
                        '''
                        def fake():
                        '''
                        pass
                    """;
            assertEquals(List.of("real"), names(src, "python"));
        }

        @Test
        void blankingPreservesEveryOffset() {
            // The whole reason it blanks rather than strips: reported columns index the ORIGINAL text.
            String src = "/* pad */ class C {}";
            assertEquals("/* pad */ class ".length(), only(src, "java").column());
        }

        @Test
        void anUnterminatedStringDoesNotSwallowTheRestOfTheFile() {
            String src = """
                    class A { String bad = "oops ;
                    }
                    class B {}
                    """;
            assertTrue(names(src, "java").contains("B"), "a stray quote must not blank the rest of the file");
        }
    }

    @Nested
    @DisplayName("other languages")
    class Others {

        @Test
        void python() {
            String src = """
                    class Widget:
                        def render(self):
                            pass

                    def helper():
                        pass
                    """;
            assertEquals(List.of("Widget", "render", "helper"), names(src, "python"));
        }

        @Test
        void go() {
            String src = """
                    package main

                    type Shape interface {
                    }

                    func (s Square) Area() float64 {
                    }

                    func Helper() {
                    }
                    """;
            assertEquals(List.of("main", "Shape", "Area", "Helper"), names(src, "go"));
            assertEquals(
                    SymbolKind.INTERFACE,
                    DeclarationScanner.scan(src, "go").stream()
                            .filter(s -> s.name().equals("Shape"))
                            .findFirst()
                            .orElseThrow()
                            .kind());
        }

        @Test
        void rust() {
            String src = """
                    struct Point {
                    }

                    trait Draw {
                    }

                    fn main() {
                    }
                    """;
            assertEquals(List.of("Point", "Draw", "main"), names(src, "rust"));
        }

        @Test
        void javascriptArrowAndFunctionForms() {
            String src = """
                    export const load = async () => {
                    };
                    function plain() {
                    }
                    class Thing {
                    }
                    const count = 3;
                    """;
            assertEquals(List.of("load", "plain", "Thing", "count"), names(src, "javascript"));
        }

        @Test
        void shellFunctions() {
            String src = """
                    usage() {
                      echo hi
                    }
                    function build {
                      :
                    }
                    """;
            assertEquals(List.of("usage", "build"), names(src, "shell"));
        }

        @Test
        void shellNamesOfEveryStyleAndScriptVariables() {
            // B10: `pkg::fn` (Google's style), dotted and dashed names were missed; variables never indexed.
            String src = """
                    readonly VERSION=1.2
                    declare -A HOSTS=()
                    export PREFIX=/opt
                    mylib::helper() { :; }
                    my.dotted() { :; }
                    my-dash() { :; }
                    function ns::kw {
                      local scratch=1
                    }
                    plain=1
                    """;
            assertEquals(
                    List.of("VERSION", "HOSTS", "PREFIX", "mylib::helper", "my.dotted", "my-dash", "ns::kw"),
                    names(src, "shell"));
            assertEquals(
                    SymbolKind.VARIABLE,
                    DeclarationScanner.scan(src, "shell").get(0).kind());
        }

        @Test
        void nothingIsDeclaredInsideAHereDocOrAMultiLineString() {
            String src = """
                    cat > /tmp/gen.sh <<'EOF'
                    generated_fn() {
                        echo inside
                    }
                    function also_in_heredoc {
                    EOF
                    usage="Usage:
                      deploy()   deploy the app
                    "
                    real() { :; }
                    """;
            assertEquals(List.of("real"), names(src, "shell"));
        }

        @Test
        void ruby() {
            String src = """
                    module Tools
                      class Runner
                        def run!
                        end
                      end
                    end
                    """;
            assertEquals(List.of("Tools", "Runner", "run!"), names(src, "ruby"));
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        void anUnsupportedLanguageFindsNothingRatherThanGuessing() {
            assertEquals(List.of(), DeclarationScanner.scan("class C {}", "brainfuck"));
            assertEquals(List.of(), DeclarationScanner.scan("class C {}", null));
        }

        @Test
        void emptyAndNullAreSafe() {
            assertEquals(List.of(), DeclarationScanner.scan("", "java"));
            assertEquals(List.of(), DeclarationScanner.scan(null, "java"));
        }

        @Test
        void anOversizedFileIsSkippedEntirely() {
            String huge = "class C {}\n".repeat(DeclarationScanner.MAX_CHARS / 5);
            assertTrue(huge.length() > DeclarationScanner.MAX_CHARS);
            assertEquals(List.of(), DeclarationScanner.scan(huge, "java"));
        }

        @Test
        void symbolsPerFileAreCapped() {
            String many = "class C%d {}\n".repeat(1).formatted(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < DeclarationScanner.MAX_SYMBOLS + 500; i++) {
                sb.append("class C").append(i).append(" {}\n");
            }
            assertTrue(many.length() > 0);
            assertEquals(
                    DeclarationScanner.MAX_SYMBOLS,
                    DeclarationScanner.scan(sb.toString(), "java").size());
        }

        @Test
        void everySupportedLanguageHasAtLeastOneWorkingRule() {
            // Guards the table against a typo'd regex that silently matches nothing.
            for (String language : DeclarationRules.supportedLanguages()) {
                assertFalse(
                        DeclarationRules.forLanguage(language).isEmpty(),
                        () -> language + " is listed as supported but has no rules");
            }
        }
    }

    /** {@code \\w} is ASCII-only: a name used to be cut at its first non-ASCII letter, or not seen at all. */
    @Test
    void nonAsciiNamesAreIndexedWhole() {
        assertEquals(List.of("Größe", "größe", "naïve_sum"), names("""
                class Größe:
                    def größe(self):
                        pass

                def naïve_sum(xs):
                    pass
                """, "python"));
        assertTrue(names("package demo;\n\npublic class Café {\n    void größe() {\n    }\n}\n", "java")
                .containsAll(List.of("Café", "größe")));
    }

    @Nested
    @DisplayName("groovy")
    class Groovy {

        @Test
        void findsTypesDefMethodsTypedMethodsAndFields() {
            String src = """
                    package demo

                    trait Named {}

                    class Shelf {
                        private static final int MAX = 64
                        def add(item) {
                            items << item
                        }
                        String describe(Item item) {
                            "x"
                        }
                    }
                    """;
            assertEquals(List.of("demo", "Named", "Shelf", "MAX", "add", "describe"), names(src, "groovy"));
            assertEquals(
                    SymbolKind.METHOD,
                    DeclarationScanner.scan(src, "groovy").get(4).kind());
            assertEquals("Shelf", DeclarationScanner.scan(src, "groovy").get(4).container());
        }

        /** Gradle and script DSL calls look like typed methods to the Java pattern; they are not declarations. */
        @Test
        void dslCallsAreNotMethods() {
            String src = """
                    plugins { id 'java' }
                    task copyDocs(type: Copy) {
                        from 'docs'
                    }
                    dependencies {
                        implementation("a:b:1")
                    }
                    println greet('x')
                    """;
            assertEquals(List.of(), names(src, "groovy"));
        }
    }

    @Nested
    @DisplayName("scala")
    class Scala {

        @Test
        void findsPackagesTypesTraitsEnumsDefsAndVals() {
            String src = """
                    package demo

                    sealed trait Item:
                      def name: String

                    final case class Book(name: String) extends Item

                    enum Size:
                      case Small, Large

                    object Shelf {
                      private[demo] val max = 64
                      override def toString: String = "shelf"
                      type Id = Int
                    }
                    """;
            assertEquals(
                    List.of("demo", "Item", "name", "Book", "Size", "Shelf", "max", "toString", "Id"),
                    names(src, "scala"));
            List<Symbol> found = DeclarationScanner.scan(src, "scala");
            assertEquals(SymbolKind.INTERFACE, found.get(1).kind());
            assertEquals(SymbolKind.ENUM, found.get(4).kind());
            assertEquals("Shelf", found.get(7).container(), "a def inside braces knows its object");
        }

        /** Scala 2's 'symbol is not a string: read as one, it blanked the code after it and lost the def. */
        @Test
        void aSymbolLiteralDoesNotHideTheCodeAfterIt() {
            String src = """
                    object A {
                      val k = 'key; def after(x: Int) = x
                      val c = '"'
                      def next = 1
                    }
                    """;
            assertEquals(List.of("A", "k", "c", "next"), names(src, "scala"));
            assertTrue(names("val s = \"def hidden = 1\"\n", "scala").equals(List.of("s")), "a string still is one");
        }
    }

    @Nested
    @DisplayName("clojure")
    class Clojure {

        @Test
        void findsTheTopLevelDefinitionForms() {
            String src = """
                    (ns demo.shelf
                      (:require [clojure.string :as str]))

                    (def max-items 64)
                    (defonce ^:private state (atom []))
                    (defprotocol Item (item-name [this]))
                    (defrecord Book [title])
                    (defn add! [x] x)
                    (defn- valid? [x] true)
                    (defmacro with-shelf [& body] body)
                    (s/def ::id int?)
                    (deftest add-test (is true))
                    """;
            List<Symbol> found = DeclarationScanner.scan(src, "clojure");
            assertEquals(
                    List.of(
                            "demo.shelf",
                            "max-items",
                            "state",
                            "Item",
                            "Book",
                            "add!",
                            "valid?",
                            "with-shelf",
                            "add-test"),
                    found.stream().map(Symbol::name).toList());
            assertEquals(SymbolKind.MODULE, found.get(0).kind());
            assertEquals(SymbolKind.VARIABLE, found.get(1).kind());
            assertEquals(SymbolKind.INTERFACE, found.get(3).kind());
            assertEquals(SymbolKind.TYPE, found.get(4).kind());
            assertEquals(SymbolKind.FUNCTION, found.get(5).kind());
            assertEquals("", found.get(5).container(), "Clojure is not brace-scoped");
        }

        /** Strings, ; comments, \c characters and indented scratch forms declare nothing. */
        @Test
        void commentsStringsCharactersAndNestedFormsAreNotDeclarations() {
            String src = """
                    ; (defn commented-out [] 1)
                    (def doc "
                    (defn in-a-string [] 1)")
                    (def quote-char \\")
                    (defn after-the-char [] 1)
                    (comment
                      (defn scratch [] 1))
                    (def sym 'quoted)
                    (defn last-one [] 1)
                    """;
            assertEquals(List.of("doc", "quote-char", "after-the-char", "sym", "last-one"), names(src, "clojure"));
        }
    }
}
