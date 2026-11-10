package com.editora.editor.debug;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The functions that start on a line of JavaScript or TypeScript, and where V8 stops in each. */
class JsFunctionsTest {

    private static List<Integer> starts(String line) {
        return JsFunctions.on(line).stream().map(LineFunctions.Function::start).toList();
    }

    private static List<Integer> stops(String line) {
        return JsFunctions.on(line).stream().map(LineFunctions.Function::stop).toList();
    }

    @Test
    void arrowsStartAtTheirParametersAndStopAtTheirBody() {
        String line = "const out = items.map(x => x * 2).filter((a, b) => { return a > b; });";
        assertEquals(List.of(line.indexOf("x =>"), line.indexOf("(a, b)")), starts(line));
        assertEquals(List.of(line.indexOf("x * 2"), line.indexOf("return")), stops(line));
        String preview = JsFunctions.on(line).getFirst().preview();
        assertTrue(preview.startsWith("x => x * 2).filter(") && preview.endsWith("…"), preview);
    }

    @Test
    void asyncTypeScriptTypesAndABodyOnTheNextLines() {
        String line = "const f = async (n: number): Promise<number> => {";
        assertEquals(List.of(line.indexOf("async")), starts(line));
        assertEquals(List.of(line.length()), stops(line), "the body goes on below: V8 moves on into it");
        String typed = "ids.map((id): string => id.trim())";
        assertEquals(List.of(typed.indexOf("(id)")), starts(typed));
        assertEquals(List.of(typed.indexOf("id.trim")), stops(typed));
        String single = "const g = async x => await load(x);";
        assertEquals(List.of(single.indexOf("async")), starts(single));
    }

    @Test
    void inlineFunctionExpressionsCountAndDeclarationsDoNot() {
        String line = "setTimeout(function () { tick(); }, 10);";
        assertEquals(List.of(line.indexOf("function")), starts(line));
        assertEquals(List.of(line.indexOf("tick")), stops(line));
        assertTrue(JsFunctions.on("function main() {").isEmpty(), "a declaration: its line breakpoint stops in it");
        assertTrue(
                JsFunctions.on("export default async function handler(req) {").isEmpty());
        String assigned = "const h = function named(a) { return a; };";
        assertEquals(List.of(assigned.indexOf("function")), starts(assigned));
    }

    @Test
    void stringsCommentsAndComparisonsAreNotFunctions() {
        assertTrue(JsFunctions.on("if (a >= b && c <= d) log('x => y', `${p => p}`); // z => z")
                .isEmpty());
        assertTrue(JsFunctions.on("/* f => 1 */ const myfunction = 3;").isEmpty());
        assertTrue(JsFunctions.on("no functions here").isEmpty());
        assertTrue(JsFunctions.on("=> 1").isEmpty(), "nothing before the arrow");
    }

    @Test
    void numbersGoByWhereTheCaretIs() {
        String line = "a.map(x => x).map(y => y)";
        List<LineFunctions.Function> found = LineFunctions.on("typescript", line);
        assertEquals(2, LineFunctions.numberAt(found, line.indexOf("y =>") + 1));
        assertEquals(1, LineFunctions.numberAt(found, 0), "before them all: the first");
        assertEquals(0, LineFunctions.numberAt(List.of(), 3));
    }

    @Test
    void languagesAndFileNames() {
        assertTrue(LineFunctions.supports("javascriptreact"));
        assertTrue(LineFunctions.javascript("typescript"));
        assertFalse(LineFunctions.javascript("java"));
        assertTrue(LineFunctions.on("python", "f = lambda x: x").isEmpty());
        assertEquals(
                List.of(4),
                LineFunctions.on("java", "run(x -> go(x));").stream()
                        .map(LineFunctions.Function::stop)
                        .toList(),
                "Java stops where the lambda starts");
        assertEquals("typescript", LineFunctions.languageOf(Path.of("src/App.tsx")));
        assertEquals("javascript", LineFunctions.languageOf(Path.of("lib/x.mjs")));
        assertEquals("java", LineFunctions.languageOf(Path.of("A.java")));
        assertNull(LineFunctions.languageOf(Path.of("README.md")));
        assertNull(LineFunctions.languageOf(null));
    }
}
