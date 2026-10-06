package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Server edits (Format Document, a quick fix, organize imports, a rename) leave the caret, the selection and
 * the viewport where the user was working.
 *
 * <p>They used to be applied with no restoration: an "add import" quick fix threw the caret from line 300
 * into the import block and dropped the selection, a whole-document format left it at the end of the file,
 * and a format touching every line reset the viewport to line 1.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LspEditsKeepViewFxTest {

    private FxWindowFixture fx;
    private String source;
    private String[] lines;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        StringBuilder sb = new StringBuilder("package demo;\n\nimport java.util.List;\n\nclass A {\n");
        for (int i = 0; i < 400; i++) {
            sb.append("    int field").append(i).append("  =  ").append(i).append(";\n");
        }
        source = sb.append("}\n").toString();
        lines = source.split("\n", -1);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private EditorBuffer openAtLine300() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buf = new EditorBuffer();
            buf.setLanguageOverride("java");
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buf, true);
            buf.setContent(source);
            return buf;
        });
        settle();
        FxTestSupport.runOnFx(() -> {
            CodeArea area = b.getArea();
            area.moveTo(300, 8);
            area.showParagraphAtTop(290);
        });
        settle();
        return b;
    }

    private static void settle() throws Exception {
        FxTestSupport.drainFx();
        Thread.sleep(400);
        FxTestSupport.drainFx();
    }

    private static int caretLine(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph());
    }

    private static int[] visible(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(b::visibleLineWindow);
    }

    private static void assertLine300StillVisible(EditorBuffer b, int line) throws Exception {
        int[] window = visible(b);
        assertTrue(
                window[0] <= line && line <= window[1],
                "the line being worked on must stay in view, visible " + window[0] + ".." + window[1]);
    }

    /** One whole-document TextEdit (yaml/bash/html/css-style formatters). */
    @Test
    void aWholeDocumentFormatKeepsTheCaretLineAndTheViewport() throws Exception {
        EditorBuffer b = openAtLine300();
        String formatted = source.replace("  =  ", " = ");
        int lastLine = lines.length - 1;

        FxTestSupport.runOnFx(() -> b.applyLspEdits(List.of(new LspTextEdit(0, 0, lastLine, 0, formatted))));
        settle();

        assertEquals(formatted, FxTestSupport.callOnFx(b::getContent));
        assertEquals(300, caretLine(b), "not the end of the file");
        assertEquals(8, (int) FxTestSupport.callOnFx(() -> b.getArea().getCaretColumn()));
        assertLine300StillVisible(b, 300);
    }

    /** jdtls-style per-line edits that touch every line. */
    @Test
    void aFormatTouchingEveryLineKeepsTheViewport() throws Exception {
        EditorBuffer b = openAtLine300();
        List<LspTextEdit> edits = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            int at = lines[i].indexOf("  =  ");
            if (at >= 0) {
                edits.add(new LspTextEdit(i, at, i, at + 5, " = "));
            }
        }

        FxTestSupport.runOnFx(() -> b.applyLspEdits(edits));
        settle();

        assertEquals(300, caretLine(b));
        assertLine300StillVisible(b, 300);
    }

    /** "Import List (java.util)" / organize imports: one insert far above the caret. */
    @Test
    void anImportQuickFixLeavesTheCaretAndSelectionOnTheLineBeingEdited() throws Exception {
        EditorBuffer b = openAtLine300();
        FxTestSupport.runOnFx(() -> {
            CodeArea area = b.getArea();
            area.selectRange(area.getAbsolutePosition(300, 4), area.getAbsolutePosition(300, 20));
        });
        String selected = FxTestSupport.callOnFx(() -> b.getArea().getSelectedText());

        FxTestSupport.runOnFx(() -> b.applyLspEdits(List.of(new LspTextEdit(3, 0, 3, 0, "import java.util.Map;\n"))));
        settle();

        assertEquals(301, caretLine(b), "the same line, one further down — not the import block");
        assertEquals(selected, FxTestSupport.callOnFx(() -> b.getArea().getSelectedText()), "selection kept");
        assertLine300StillVisible(b, 301);
    }
}
