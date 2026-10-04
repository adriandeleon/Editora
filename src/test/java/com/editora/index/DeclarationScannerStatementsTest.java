package com.editora.index;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scanner's contract is to under-report, never to invent: an ordinary statement must not become a
 * symbol, and a blanked literal must not make the scan quadratic.
 */
class DeclarationScannerStatementsTest {

    private static List<String> names(String text, String language) {
        return DeclarationScanner.scan(text, language).stream()
                .map(s -> s.kind() + " " + s.name())
                .toList();
    }

    @Test
    void javaStatementsAreNotMethods() {
        String java = "class A {\n"
                + "    int value() {\n"
                + "        if (x < 0) throw new IllegalStateException(\"negative value\");\n"
                + "        throw new IllegalStateException(\"negative value\");\n"
                + "        new Thread();\n"
                + "        if (y) go();\n"
                + "        else doIt();\n"
                + "        return new Spec(\n"
                + "                a);\n"
                + "        return format(\"a b c\");\n"
                + "        return compute();\n"
                + "    }\n"
                + "    abstract Object compute();\n"
                + "    Map<String, List<Integer>> wrapped(\n"
                + "            int a) {\n"
                + "    }\n"
                + "}\n";
        assertEquals(List.of("TYPE A", "METHOD value", "METHOD compute", "METHOD wrapped"), names(java, "java"));
    }

    @Test
    void csharpAndCStatementsAreNotDeclarations() {
        assertEquals(List.of("TYPE A"), names("class A {\n    await FooAsync();\n}\n", "csharp"));
        String c = "using namespace std;\nenum Color c = RED;\nenum Shade {\n  DARK\n};\nnamespace app {\n"
                + "int run() {\n    return run();\n}\n}\n";
        assertEquals(List.of("ENUM Shade", "MODULE app", "FUNCTION run"), names(c, "cpp"));
    }

    @Test
    void aKeywordIsOnlyReservedWhereNoDeclaringKeywordPrecedesIt() {
        assertEquals(List.of("TYPE P", "FUNCTION new"), names("struct P;\npub fn new() -> P {\n}\n", "rust"));
    }

    @Test
    void theBodyOfAMultiLineBacktickLiteralIsNotCode() {
        String ts = "const typeDefs = gql`\n  interface Node {\n    id: ID!\n  }\n`;\nfunction real() {\n}\n";
        List<String> found = names(ts, "typescript");
        assertTrue(found.contains("FUNCTION real"), found.toString());
        assertTrue(found.stream().noneMatch(s -> s.endsWith(" Node")), found.toString());
        String go = "package main\n\nvar tmpl = `\nfunc Generated() {}\n`\n\nfunc Real() {\n}\n";
        List<String> goFound = names(go, "go");
        assertTrue(goFound.contains("FUNCTION Real"), goFound.toString());
        assertTrue(goFound.stream().noneMatch(s -> s.endsWith(" Generated")), goFound.toString());
    }

    @Test
    void aLongStringLiteralDoesNotMakeTheScanQuadratic() {
        String java = "class A {\n    String f() {\n        return \"" + "x y ".repeat(20_000) + "\";\n    }\n}\n";
        List<String> found = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> names(java, "java"));
        assertEquals(List.of("TYPE A", "METHOD f"), found);
    }
}
