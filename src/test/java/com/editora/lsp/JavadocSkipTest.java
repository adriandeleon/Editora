package com.editora.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavadocSkipTest {

    private static final String SRC = String.join(
            "\n",
            "public class Assertions {", // 0
            "    /**", // 1
            "     * Asserts that two doubles are equal.", // 2
            "     */", // 3
            "    @Deprecated", // 4
            "    @SuppressWarnings(\"unused\")", // 5
            "    public static void assertEquals(double a, double b) {", // 6
            "    }", // 7
            "    /** One line. */", // 8
            "    void m() {}", // 9
            "    /* never closed", // 10
            "    void n() {}");

    @Test
    void aJavadocStartMovesToTheDeclarationPastItsAnnotations() {
        assertEquals(6, JavadocSkip.declarationLine(SRC, 1));
        assertEquals(9, JavadocSkip.declarationLine(SRC, 8), "a one-line Javadoc");
    }

    @Test
    void anyOtherLineStays() {
        assertEquals(6, JavadocSkip.declarationLine(SRC, 6));
        assertEquals(0, JavadocSkip.declarationLine(SRC, 0));
        assertEquals(10, JavadocSkip.declarationLine(SRC, 10), "an unclosed comment");
        assertEquals(99, JavadocSkip.declarationLine(SRC, 99));
    }
}
