package org.adriandeleon.editora.languages;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FoldingSupportTest {

    // ---- brace folds ----

    @Test
    void detectsSimpleJavaClassFold() {
        String text = String.join("\n",
                "public class Foo {",
                "    void bar() {",
                "        return;",
                "    }",
                "}");
        List<FoldRange> folds = FoldingSupport.computeBraceFolds(text);

        assertFalse(folds.isEmpty(), "Expected at least one fold");
        // The outer class brace at line 0 should fold to line 4
        assertTrue(folds.stream().anyMatch(r -> r.startLine() == 0 && r.endLine() == 4),
                "Expected outer class fold 0→4, got: " + folds);
    }

    @Test
    void detectsNestedMethodFold() {
        String text = String.join("\n",
                "class A {",
                "    void m() {",
                "        int x = 1;",
                "        int y = 2;",
                "    }",
                "}");
        List<FoldRange> folds = FoldingSupport.computeBraceFolds(text);

        // Inner method block 1→4 and outer class 0→5
        assertTrue(folds.stream().anyMatch(r -> r.startLine() == 1 && r.endLine() == 4),
                "Expected inner method fold 1→4, got: " + folds);
        assertTrue(folds.stream().anyMatch(r -> r.startLine() == 0 && r.endLine() == 5),
                "Expected outer class fold 0→5, got: " + folds);
    }

    @Test
    void singleLineBracesProduceNoFold() {
        String text = "void m() { return; }";
        List<FoldRange> folds = FoldingSupport.computeBraceFolds(text);
        assertTrue(folds.isEmpty(), "Single-line braces should not produce a fold");
    }

    @Test
    void emptyTextProducesNoFolds() {
        assertTrue(FoldingSupport.computeBraceFolds("").isEmpty());
        assertTrue(FoldingSupport.computeBraceFolds(null).isEmpty());
    }

    @Test
    void detectsJsonArrayFold() {
        String text = String.join("\n",
                "[",
                "  \"alpha\",",
                "  \"beta\"",
                "]");
        List<FoldRange> folds = FoldingSupport.computeBraceFolds(text);
        assertTrue(folds.stream().anyMatch(r -> r.startLine() == 0 && r.endLine() == 3),
                "Expected JSON array fold 0→3, got: " + folds);
    }

    // ---- indent folds ----

    @Test
    void detectsPythonFunctionFold() {
        String text = String.join("\n",
                "def greet(name):",
                "    msg = 'Hello'",
                "    return msg",
                "",
                "print('done')");
        List<FoldRange> folds = FoldingSupport.computeIndentFolds(text);

        assertTrue(folds.stream().anyMatch(r -> r.startLine() == 0),
                "Expected fold starting at line 0 for the function body, got: " + folds);
    }

    @Test
    void detectsYamlMappingFold() {
        String text = String.join("\n",
                "app:",
                "  name: Editora",
                "  version: 1.0",
                "other: value");
        List<FoldRange> folds = FoldingSupport.computeIndentFolds(text);

        assertTrue(folds.stream().anyMatch(r -> r.startLine() == 0 && r.endLine() == 2),
                "Expected YAML mapping fold 0→2, got: " + folds);
    }

    @Test
    void tooShortIndentBlockProducesNoFold() {
        String text = String.join("\n",
                "def f():",
                "    pass");
        // Only 2 lines total, endLine - startLine == 1 < MIN_FOLD_LINES == 2
        List<FoldRange> folds = FoldingSupport.computeIndentFolds(text);
        assertTrue(folds.isEmpty(), "A 2-line block is below the minimum fold threshold");
    }

    // ---- markdown folds ----

    @Test
    void detectsMarkdownHeadingFolds() {
        String text = String.join("\n",
                "# Introduction",      // line 0
                "Some intro text.",    // line 1
                "More intro.",         // line 2
                "## Section One",      // line 3
                "Content here.",       // line 4
                "More content.",       // line 5
                "## Section Two",      // line 6
                "Final content.");     // line 7
        List<FoldRange> folds = FoldingSupport.computeMarkdownFolds(text);

        // H1 "Introduction" folds from line 0 all the way to the end of the document
        // (there is no subsequent H1, so the fold spans everything beneath it).
        assertTrue(folds.stream().anyMatch(r -> r.startLine() == 0 && r.endLine() == 7),
                "Expected H1 fold 0→7 (to end of document), got: " + folds);
        // H2 "Section One" folds from 3 to 5 (before next H2 at line 6).
        assertTrue(folds.stream().anyMatch(r -> r.startLine() == 3 && r.endLine() == 5),
                "Expected H2 fold 3→5, got: " + folds);
    }

    @Test
    void markdownWithNoHeadingsProducesNoFolds() {
        String text = "Just some plain text.\nNo headings here.";
        assertTrue(FoldingSupport.computeMarkdownFolds(text).isEmpty());
    }

    // ---- FoldRange record ----

    @Test
    void foldRangeRecordReportsCorrectLineCount() {
        FoldRange range = new FoldRange(2, 8);
        assertEquals(6, range.foldedLineCount());
        assertEquals(2, range.startLine());
        assertEquals(8, range.endLine());
    }

    @Test
    void foldRangeConstructorRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new FoldRange(-1, 5));
        assertThrows(IllegalArgumentException.class, () -> new FoldRange(5, 5));
        assertThrows(IllegalArgumentException.class, () -> new FoldRange(6, 5));
    }
}


