package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void prefersStringArrayMainOverEarlierNoArgumentMain() {
        String src = "void main() {}\nvoid main(String[] args) {}\n";
        assertEquals(1, CompactSource.mainLine(src));
    }

    @Test
    void acceptsMultilineAndVarargsSignatures() {
        assertEquals(1, CompactSource.mainLine("int n;\nvoid main(\n    String... args\n) {}"));
        assertEquals(0, CompactSource.mainLine("void main(final String args[]) {}"));
    }

    @Test
    void rejectsInaccessibleAndWrongParameterMains() {
        assertFalse(CompactSource.isLaunchable("Wrong.java", "private void main() {}\nvoid main(int count) {}"));
        assertEquals(1, CompactSource.mainLine("private void main(String[] args) {}\nvoid main() {}"));
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

    @Test
    void aNullSourceHasNoMain() {
        assertEquals(-1, CompactSource.mainLine(null));
        assertFalse(CompactSource.hasTopLevelMain(null));
    }

    @Test
    void aMainSpelledInsideATextBlockOrABlockCommentIsNotAnEntryPoint() {
        String textBlock = "String doc = \"\"\"\n    void main() {}\n    \"\"\";\nint n;\n";
        assertEquals(-1, CompactSource.mainLine(textBlock));
        assertEquals(4, CompactSource.mainLine(textBlock + "void main() {}\n"), "the real one after it counts");

        assertEquals(-1, CompactSource.mainLine("/* void main() {} */\nint n;\n"));
        assertEquals(1, CompactSource.mainLine("/* a * star and a / slash */\nvoid main() {}\n"));
    }

    @Test
    void unterminatedCommentsAndLiteralsBlankTheRestOfTheFile() {
        assertEquals(-1, CompactSource.mainLine("/* never closed\nvoid main() {}\n"));
        assertEquals(-1, CompactSource.mainLine("String s = \"never closed\nvoid main() {}\n"));
        assertEquals(-1, CompactSource.mainLine("String s = \"\"\"\nnever closed\nvoid main() {}\n"));
        assertEquals(-1, CompactSource.mainLine("int n; // void main() {}"), "a comment that ends the file");
    }

    @Test
    void bracesAndQuotesInsideLiteralsDoNotChangeTheDepth() {
        // A '{' char literal, an escaped quote in a string and an escaped quote char: none opens a block.
        String src = "char open = '{';\nchar quote = '\\'';\nString s = \"a \\\" {\";\nvoid main() {}\n";
        assertEquals(3, CompactSource.mainLine(src));
        String cleaned = CompactSource.stripCommentsAndLiterals(src);
        assertEquals(src.length(), cleaned.length(), "stripping keeps every offset where it was");
        assertEquals(
                src.chars().filter(c -> c == '\n').count(),
                cleaned.chars().filter(c -> c == '\n').count(),
                "and every line break");
        assertEquals(1, cleaned.chars().filter(c -> c == '{').count(), "only main's own brace survives");
    }

    @Test
    void anUnbalancedCloserDoesNotPushTheDepthBelowZero() {
        assertEquals(0, CompactSource.braceDepthAt("} } {", 3));
        assertEquals(1, CompactSource.braceDepthAt("} } {", 5));
        assertEquals(1, CompactSource.mainLine("}\nvoid main() {}\n"), "a stray closer above main is ignored");
    }

    @Test
    void aPrivateHelperDeclaredBeforeMainDoesNotMakeMainPrivate() {
        assertEquals(1, CompactSource.mainLine("private int n;\nvoid main() {}\n"));
        assertEquals(1, CompactSource.mainLine("private void helper() {}\nvoid main() {}\n"));
        assertEquals(-1, CompactSource.mainLine("int n;\nprivate static void main(String[] args) {}\n"));
        // The first no-argument main wins when there are two; a String[] one beats both.
        assertEquals(0, CompactSource.mainLine("void main() {}\nvoid main() {}\n"));
        assertEquals(1, CompactSource.mainLine("void main() {}\nvoid main(String[] args) {}\n"));
    }

    @Test
    void lineOfClampsAnOffsetPastTheEnd() {
        assertEquals(2, CompactSource.lineOf("a\nb\nc", 99));
        assertEquals(0, CompactSource.lineOf("a\nb", 0));
    }
}
