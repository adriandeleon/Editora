package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CompactSourceTest {

    @Test
    void detectsInstanceMainCompactSource() {
        String src = "void main() {\n    System.out.println(\"hi\");\n}\n";
        assertTrue(CompactSource.isLaunchable("Hello.java", src));
    }

    @Test
    void detectsCompactSourceWithHelpersAndFields() {
        String src = """
                String greeting = "Hello";
                void main() {
                    System.out.println(greet());
                }
                String greet() { return greeting + ", world"; }
                """;
        assertTrue(CompactSource.isLaunchable("App.java", src));
    }

    @Test
    void detectsStaticTopLevelMain() {
        String src = "static void main(String[] args) {\n  System.out.println(args.length);\n}\n";
        assertTrue(CompactSource.isLaunchable("M.java", src));
    }

    @Test
    void rejectsNormalClassWithMain() {
        String src = """
                public class Demo {
                    public static void main(String[] args) {
                        System.out.println("hi");
                    }
                }
                """;
        assertFalse(CompactSource.isLaunchable("Demo.java", src));
    }

    @Test
    void rejectsClassWithoutMain() {
        assertFalse(CompactSource.isLaunchable("Foo.java", "class Foo { int x; }"));
    }

    @Test
    void rejectsMainOnlyInsideStringOrComment() {
        String src = """
                class Note {
                    String s = "void main(";
                    // void main() here is just a comment
                }
                """;
        assertFalse(CompactSource.isLaunchable("Note.java", src));
    }

    @Test
    void rejectsNonJavaExtension() {
        assertFalse(CompactSource.isLaunchable("script.txt", "void main() {}"));
    }

    @Test
    void rejectsNullsAndEmpty() {
        assertFalse(CompactSource.isLaunchable(null, "void main() {}"));
        assertFalse(CompactSource.isLaunchable("X.java", null));
        assertFalse(CompactSource.isLaunchable("X.java", ""));
    }

    @Test
    void ignoresBracesInsideLiteralsWhenComputingDepth() {
        // The '{' lives inside a string, so the real main stays at depth 0.
        String src = "String braces = \"{{{\";\nvoid main() { System.out.println(braces); }\n";
        assertTrue(CompactSource.isLaunchable("Braces.java", src));
    }

    @Test
    void reportsTheLineOfTheTopLevelMain() {
        String src = "String who = \"x\";\n\nvoid main() {\n  System.out.println(who);\n}\n";
        // Line 0: field, line 1: blank, line 2: void main(...)
        org.junit.jupiter.api.Assertions.assertEquals(2, CompactSource.mainLine(src));
    }

    @Test
    void mainLineIsMinusOneForNormalClass() {
        org.junit.jupiter.api.Assertions.assertEquals(
                -1, CompactSource.mainLine("class A { public static void main(String[] a) {} }"));
    }

    @Test
    void stripKeepsCodeOutsideLiterals() {
        String cleaned = CompactSource.stripCommentsAndLiterals("a=\"x\"; // c\nb={};");
        assertTrue(cleaned.contains("a="));
        assertTrue(cleaned.contains("b={};"));
        assertFalse(cleaned.contains("x"));
        assertFalse(cleaned.contains("c"));
    }
}
