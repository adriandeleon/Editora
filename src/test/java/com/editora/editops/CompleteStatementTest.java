package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CompleteStatementTest {

    /** Runs Complete Statement at the {@code |} in {@code marked} and marks the caret afterwards. */
    private static String complete(String marked, CompleteStatement.Style style) {
        int caret = marked.indexOf('|');
        String text = marked.substring(0, caret) + marked.substring(caret + 1);
        Indenter.TabEdit e = CompleteStatement.edit(text, caret, caret, style, "    ", at -> enter(text, at));
        String after = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        return after.substring(0, e.selStart()) + "|" + after.substring(e.selStart());
    }

    private static String java(String marked) {
        return complete(marked, CompleteStatement.Style.SEMICOLON);
    }

    /** Enter as a code buffer would: the line's indentation, one unit more after an opening brace. */
    private static Indenter.EnterEdit enter(String text, int at) {
        int start = LineCommands.lineStart(text, at);
        int i = start;
        while (i < text.length() && text.charAt(i) == ' ') {
            i++;
        }
        String indent = text.substring(start, i) + (at > 0 && text.charAt(at - 1) == '{' ? "    " : "");
        return new Indenter.EnterEdit("\n" + indent, 1 + indent.length());
    }

    // --- statements ---

    @Test
    void aCallGetsItsParenthesesClosedAndASemicolonWhereverTheCaretIsOnTheLine() {
        assertEquals("    System.out.println(x);|\n", java("    System.out.|println(x\n"));
        assertEquals("    list.add(new Item(\"x\", 2.0));|\n", java("    list.add(new Item(\"x\", 2.0|\n"));
        assertEquals("int[] a = {1, 2};|", java("int[] a = {1, 2|"));
    }

    @Test
    void anOpenStringIsClosedFirst() {
        assertEquals("log(\"done\");|", java("log(\"do|ne"));
        assertEquals("char c = 'x';|", java("char c = 'x|"));
    }

    @Test
    void bracketsOpenedOnEarlierLinesOfTheStatementAreClosedToo() {
        String in = "    foo(a,\n        b|\n";
        assertEquals("    foo(a,\n        b);|\n", java(in));
    }

    @Test
    void aBracketLeftOpenOnALineThatReadsFinishedIsNotThisLinesToClose() {
        assertEquals("    g(1\n    h(2);|\n", java("    g(1\n    h(2|\n"));
        assertEquals("    foo(bar\n        .baz());|", java("    foo(bar\n        .baz(|"));
        assertEquals("    ok(a\n        && b);|", java("    ok(a\n        && b|"));
    }

    @Test
    void aSemicolonTypedInsideTheParenthesesMovesAfterThem() {
        assertEquals("add(new Item(1, 2.0));|", java("add(new Item(1, 2.0;|"));
    }

    @Test
    void aTrailingCommentStaysAfterTheSemicolon() {
        assertEquals("int x = f(1);| // why\n", java("int x = f(1| // why\n"));
    }

    @Test
    void aCompleteStatementGetsANewLineBelowAtItsIndent() {
        assertEquals("    x++;\n    |\n", java("    x|++;\n"));
        assertEquals("    @Override\n    |", java("    @Over|ride"));
        assertEquals("    int x =\n    |", java("    int x =|"));
        assertEquals("    case 1:\n    |", java("    case 1:|"));
    }

    @Test
    void lambdasAnonymousClassesAndSwitchExpressionsEndTheStatementAroundThem() {
        assertEquals("list.forEach(x -> {});|", java("list.forEach(x -> {}|"));
        String in = "Runnable r = new Runnable() {\n    public void run() {}\n}|";
        assertEquals("Runnable r = new Runnable() {\n    public void run() {}\n};|", java(in));
        assertEquals(
                "int y = switch (k) {\n    default -> 1;\n};|", java("int y = switch (k) {\n    default -> 1;\n}|"));
    }

    @Test
    void aStatementInsideALambdaIsCompletedOnItsOwn() {
        String in = "items.forEach(x -> {\n    use(x|\n});\n";
        assertEquals("items.forEach(x -> {\n    use(x);|\n});\n", java(in));
    }

    @Test
    void theWhileOfADoWhileGetsASemicolon() {
        assertEquals("do {\n    n--;\n} while (n > 0);|", java("do {\n    n--;\n} while (n > 0|"));
    }

    // --- blocks ---

    @Test
    void aControlHeaderGetsABlockWithTheCaretInside() {
        assertEquals("    if (x) {\n        |\n    }\n", java("    if (|x\n"));
        assertEquals("for (int i = 0; i < n; i++) {\n    |\n}", java("for (int i = 0; i < n; i++|"));
        assertEquals("} else {\n    |\n}", java("} el|se"));
        assertEquals("} catch (IOException e) {\n    |\n}", java("} catch (IOException e|"));
        assertEquals("try (var in = open()) {\n    |\n}", java("try (var in = open(|"));
    }

    @Test
    void theBlockGoesBeforeATrailingComment() {
        assertEquals("while (more()) { // poll\n    |\n}", java("while (more()| // poll"));
    }

    @Test
    void typeAndMethodDeclarationsGetABody() {
        assertEquals("public class Foo extends Bar {\n    |\n}", java("public class Foo extends Bar|"));
        assertEquals("record Point(int x, int y) {\n    |\n}", java("record Point(int x, int y|"));
        assertEquals("    @Test void runs() {\n        |\n    }", java("    @Test void runs(|"));
        assertEquals(
                "    public Foo(int x) throws IOException {\n        |\n    }",
                java("    public Foo(int x) throws IOException|"));
        assertEquals("function add(a, b) {\n    |\n}", java("function add(a, b|"));
    }

    @Test
    void aMethodWithNoBodyGetsASemicolon() {
        assertEquals("    abstract int size();|", java("    abstract int size(|"));
        String in = "interface Shape {\n    double area(|\n}";
        assertEquals("interface Shape {\n    double area();|\n}", java(in));
        String withDefault = "interface Shape {\n    default double area(|\n}";
        assertEquals("interface Shape {\n    default double area() {\n        |\n    }\n}", java(withDefault));
    }

    @Test
    void aCallIsNotMistakenForADeclaration() {
        assertEquals("foo(a, b);|", java("foo(a, b|"));
        assertEquals("return compute(x);|", java("return compute(x|"));
        assertEquals("String s = format(x);|", java("String s = format(x|"));
    }

    @Test
    void aLineEndingInABraceOpensItsBodyAndClosesItWhenNothingDoes() {
        assertEquals("void f() {\n    |\n}", java("void f() {|"));
        assertEquals("void f() {\n    |\n    g();\n}", java("void f() {|\n    g();\n}"));
        assertEquals("if (x) {\n    |\n}", java("if (x) {|}"));
    }

    // --- styles and the rest ---

    @Test
    void kotlinGetsTheClosersAndBlocksButNoSemicolon() {
        CompleteStatement.Style kt = CompleteStatement.Style.NO_SEMICOLON;
        assertEquals("println(x)|", complete("println(x|", kt));
        assertEquals("println(x)\n|", complete("println(x)|", kt));
        assertEquals("fun area(): Double {\n    |\n}", complete("fun area(): Double|", kt));
        assertEquals("if (ok) {\n    |\n}", complete("if (ok|", kt));
    }

    @Test
    void insideACommentOrATextBlockItOnlyOpensALine() {
        assertEquals("/* foo(\n|", java("/* foo(|"));
        assertEquals("String s = \"\"\"\n  foo(\n  |", java("String s = \"\"\"\n  foo(|"));
        assertEquals("// call(x\n|", java("// call(x|"));
    }

    @Test
    void stringsAndCommentsDoNotCountTheirBrackets() {
        assertEquals("log(\"(\" + x);|", java("log(\"(\" + x|"));
        assertEquals("/* { */ go(1);|", java("/* { */ go(1|"));
    }

    @Test
    void theLanguagesItKnows() {
        assertEquals(CompleteStatement.Style.SEMICOLON, CompleteStatement.styleFor("java"));
        assertEquals(CompleteStatement.Style.SEMICOLON, CompleteStatement.styleFor("typescriptreact"));
        assertEquals(CompleteStatement.Style.NO_SEMICOLON, CompleteStatement.styleFor("kotlin"));
        assertNull(CompleteStatement.styleFor("python"));
    }

    // --- statements that go on below the caret's line (re-review AN1) ---

    @Test
    void aStatementThatGoesOnBelowIsCompletedWhereItEndsNotSplit() {
        String chain = "    List<String> up = names.stream()|\n        .map(String::trim)\n        .toList();\n";
        assertEquals(
                "    List<String> up = names.stream()\n        .map(String::trim)\n        .toList();\n    |\n",
                java(chain),
                "a complete chain gets a new line after it, not a ; in the middle");
        assertEquals(
                "    List<String> up = names.stream()\n        .toList();|\n",
                java("    List<String> up = names.stream()|\n        .toList()\n"),
                "an incomplete one gets its ; at the end");
        assertEquals(
                "    String q = ready ? \"a\"\n        : \"b\";\n    |\n",
                java("    String q = ready ? \"a\"|\n        : \"b\";\n"));
        assertEquals("    foo(a,\n        b);\n    |\n", java("    foo(a,|\n        b);\n"));
        assertEquals(
                "    String s = \"a\" +\n        \"b\";\n    |\n", java("    String s = \"a\" +|\n        \"b\";\n"));
        assertEquals(
                "    if (a\n        && b) {\n        |\n    }\n",
                java("    if (a|\n        && b) {\n    }\n"),
                "a header that goes on opens its block where it ends");
    }

    @Test
    void anOpenBracketThatNoLaterLineClosesIsStillClosedHere() {
        assertEquals("    foo(a);|\n    bar();\n", java("    foo(a|\n    bar();\n"));
    }

    // --- braces and parentheses left open (re-review AN9) ---

    @Test
    void aBlockOpenedOnAMethodsLastLineGetsItsOwnClosingBrace() {
        String in = "class A {\n    void f() {\n        if (ready) {|\n    }\n}\n";
        assertEquals("class A {\n    void f() {\n        if (ready) {\n            |\n        }\n    }\n}\n", java(in));
        String lambda = "    void f() {\n        names.forEach(n -> {|\n    }\n";
        assertEquals("    void f() {\n        names.forEach(n -> {\n            |\n        }\n    }\n", java(lambda));
    }

    @Test
    void anAnnotationsOpenParenthesisIsClosed() {
        assertEquals("    @SuppressWarnings(\"x\")\n    |", java("    @SuppressWarnings(\"x\"|"));
    }
}
