package com.editora.snippet;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurroundSnippetsTest {

    private static final Snippet IF =
            new Snippet("if", "if", "if (${1:condition}) {\n\t$TM_SELECTED_TEXT$0\n}", null, "java");
    private static final Snippet MAIN =
            new Snippet("main", "main", "public static void main(String[] args) {\n\t$0\n}", null, "java");
    private static final Snippet NOT = new Snippet("not", "not", "!(${TM_SELECTED_TEXT})", null, "java");
    private static final List<Snippet> ALL = List.of(MAIN, IF, NOT);

    @Test
    void withNothingSelectedEverySnippetIsListed() {
        assertEquals(ALL, SurroundSnippets.forSelection(ALL, "int a;\n", 3, 3));
    }

    @Test
    void wholeLinesAreWrappedByTheSnippetsThatTakeTheSelection() {
        String text = "void f() {\n    a();\n    b();\n}\n";
        int start = text.indexOf("    a();");
        int end = text.indexOf("}\n");
        assertEquals(List.of(IF, NOT), SurroundSnippets.forSelection(ALL, text, start, end), "main would replace them");
        int bStart = text.indexOf("b();");
        assertEquals(
                List.of(IF, NOT), SurroundSnippets.forSelection(ALL, text, bStart, bStart + 4), "indentation left out");
    }

    @Test
    void anExpressionIsWrappedOnlyBySnippetsThatUseItWithinALine() {
        String text = "String s = String.format(\"%d\", n);\n";
        int start = text.indexOf("String.format");
        int end = text.indexOf(';');
        assertEquals(
                List.of(NOT),
                SurroundSnippets.forSelection(ALL, text, start, end),
                "no statement around an expression");
        assertTrue(SurroundSnippets.takesSelectionInline(NOT));
        assertFalse(SurroundSnippets.takesSelectionInline(IF));
    }
}
