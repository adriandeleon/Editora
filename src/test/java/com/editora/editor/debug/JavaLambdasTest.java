package com.editora.editor.debug;

import java.util.List;

import com.editora.config.Breakpoint;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lambdas a line of Java starts, where a lambda breakpoint stops: at the column the Java parser starts each. */
class JavaLambdasTest {

    private static List<Integer> columns(String line) {
        return JavaLambdas.on(line).stream().map(JavaLambdas.Lambda::column).toList();
    }

    @Test
    void aLambdaStartsAtItsParameterOrItsParameterList() {
        String line = "        list.forEach(x -> System.out.println(x));";
        assertEquals(List.of(line.indexOf("x ->")), columns(line));
        assertEquals(
                "x -> System.out.println(x));", JavaLambdas.on(line).getFirst().preview());
        String two = "map.merge(k, 1, (a, b) -> a + b); Runnable r = () -> {};";
        assertEquals(List.of(two.indexOf("(a, b)"), two.indexOf("() ->")), columns(two));
        String typed = "BiFunction<A, B, C> f = (A a, B b) -> c(a, b);";
        assertEquals(List.of(typed.indexOf("(A a")), columns(typed));
        assertEquals(List.of(0), columns("x -> x"));
        assertEquals(List.of(7), columns("return x -> x + 1;"));
    }

    @Test
    void lambdasInsideLambdasAreEachTheirOwn() {
        String line = "outer.forEach(a -> inner.forEach(b -> use(a, b)));";
        assertEquals(List.of(line.indexOf("a ->"), line.indexOf("b ->")), columns(line));
        assertEquals(2, JavaLambdas.numberAt(JavaLambdas.on(line), line.indexOf("use")));
        assertEquals(1, JavaLambdas.numberAt(JavaLambdas.on(line), 0), "before them all: the first");
        assertEquals(1, JavaLambdas.numberAt(JavaLambdas.on(line), line.indexOf("inner")));
        assertEquals(0, JavaLambdas.numberAt(List.of(), 3));
        assertEquals(line.indexOf("b ->"), JavaLambdas.column(line, 2));
        assertEquals(-1, JavaLambdas.column(line, 3));
        assertEquals(-1, JavaLambdas.column(line, 0));
    }

    @Test
    void switchArrowsAreNotLambdas() {
        assertEquals(List.of(), columns("            case A, B -> 1;"));
        assertEquals(List.of(), columns("            default -> throw new IllegalStateException();"));
        assertEquals(List.of(), columns("    case Point(int x, int y) -> x + y;"));
        assertEquals(List.of(), columns("    case String s when s.isEmpty() -> 0;"));
        String inCase = "    case 1 -> items.map(y -> y * 2);";
        assertEquals(List.of(inCase.indexOf("y ->")), columns(inCase));
        String afterSwitch = "int n = switch (k) { case 1 -> 2; default -> 3; }; f(z -> z);";
        assertEquals(List.of(afterSwitch.indexOf("z ->")), columns(afterSwitch));
    }

    @Test
    void arrowsInStringsCharsAndCommentsAreNotCode() {
        assertEquals(List.of(), columns("String s = \"x -> y\"; // a -> b"));
        assertEquals(List.of(), columns("char c = '-'; /* q -> r */ int i = 0;"));
        assertEquals(List.of(), columns("String s = \"a \\\" -> b\";"));
        String after = "/* x -> */ f(y -> y);";
        assertEquals(List.of(after.indexOf("y ->")), columns(after));
        assertEquals(List.of(), columns("/* unterminated -> comment"));
        assertEquals(List.of(), columns("int a = b - > c;"));
        assertEquals(List.of(), columns("f(1 -> 2);"), "a number is no parameter");
        assertEquals(List.of(), columns("-> x"));
        assertEquals(List.of(), columns(") -> x"));
        assertEquals(List.of(), columns(null));
        assertTrue(JavaLambdas.on("no arrow here").isEmpty());
    }

    @Test
    void aLongLambdaIsCutForTheMenu() {
        String line = "f(x -> " + "a".repeat(80) + ");";
        String preview = JavaLambdas.on(line).getFirst().preview();
        assertEquals(JavaLambdas.MAX_PREVIEW + 1, preview.length());
        assertTrue(preview.endsWith("…"));
    }

    @Test
    void theBreakpointSaysWhichLambdasItStopsIn() {
        Breakpoint line = Breakpoint.plain(3, "f(x -> x);");
        assertEquals(List.of(), BreakpointDescription.lines(line));
        assertEquals(
                List.of(tr("debug.breakpoint.lambdas", "1, 3")),
                BreakpointDescription.lines(line.withLambdas(List.of(3, 1), false)));
        assertEquals(
                List.of(tr("debug.breakpoint.lambdasOnly", "2")),
                BreakpointDescription.lines(line.withLambdas(List.of(2), true)));
    }
}
