package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Format Document applies a multi-edit set whose replacements CHANGE LENGTH (real formatting: re-indent,
 * collapse whitespace). The existing multi-edit test only used same-length swaps, where a wrong apply
 * order is invisible — this reproduces the device-reported file mangling.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LspEditsOrderFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void lengthChangingEditsApplyAtTheRightPlaces() throws Exception {
        String result = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            //            0123456789...
            b.setContent("a();\nbb();\nccc();\n");
            b.getNode();
            // Three GROWING replacements, as a formatter re-indenting lines would produce:
            //   line 0: "a"   -> "AAAA"  (+3)
            //   line 1: "bb"  -> "BBBB"  (+2)
            //   line 2: "ccc" -> "CCCC"  (+1)
            b.applyLspEdits(List.of(
                    new LspTextEdit(0, 0, 0, 1, "AAAA"),
                    new LspTextEdit(1, 0, 1, 2, "BBBB"),
                    new LspTextEdit(2, 0, 2, 3, "CCCC")));
            return b.getContent();
        });
        assertEquals("AAAA();\nBBBB();\nCCCC();\n", result, "each edit must land on ITS OWN line");
    }

    @Test
    void lengthShrinkingEditsApplyAtTheRightPlaces() throws Exception {
        String result = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("    x();\n        y();\n            z();\n");
            b.getNode();
            // Dedent each line (shrinking replacements) — the classic Format Document shape.
            b.applyLspEdits(List.of(
                    new LspTextEdit(0, 0, 0, 4, ""),
                    new LspTextEdit(1, 0, 1, 8, "  "),
                    new LspTextEdit(2, 0, 2, 12, "    ")));
            return b.getContent();
        });
        assertEquals("x();\n  y();\n    z();\n", result, "dedents must not shift into each other");
    }
}
