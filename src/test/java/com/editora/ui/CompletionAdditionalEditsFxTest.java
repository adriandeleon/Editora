package com.editora.ui;

import java.util.List;

import com.editora.completion.Completion;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LspTextEdit;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for #410: a completion's {@code additionalTextEdits} (an auto-import line) carry positions the
 * server computed against the document as it stood <em>before</em> the accept, but they are resolved and
 * applied <em>after</em> it. When the accepted text spans lines, everything below the caret has moved, and
 * applying those positions verbatim wrote the import into the wrong line — silently.
 *
 * <p>Covers the measurement {@code acceptCompletion} takes around its own edit; {@code LspEditShiftTest}
 * covers the arithmetic that consumes it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompletionAdditionalEditsFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * Accepts {@code insert} over the {@code cons} on line 1 of a 3-line document, then applies {@code extra}
     * the way the {@code completionItem/resolve} callback does, and returns the resulting text.
     */
    private static String acceptThenResolve(String insert, List<LspTextEdit> extra) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("typescript");
            b.setContent("top\ncons\ntarget\n");
            b.getNode();
            CodeArea area = FxTestSupport.field(b, "area");
            area.moveTo(b.getContent().indexOf("cons") + 4); // caret after the typed prefix
            Completion c = Completion.lsp("console", insert, "");
            FxTestSupport.call(b, "acceptCompletion", new Class<?>[] {CodeArea.class, Completion.class}, area, c);
            b.applyCompletionAdditionalEdits(extra);
            return area.getText();
        });
    }

    /** An edit below a multi-line accept must move down by the lines the accept added. */
    @Test
    void movesAnEditBelowAMultiLineAcceptDown() throws Exception {
        // Accepting 3 lines over 1 pushes "target" from line 2 to line 4; the edit addressed line 2.
        String out = acceptThenResolve("one\ntwo\nthree", List.of(new LspTextEdit(2, 0, 2, 0, "IMPORT\n")));
        assertEquals("top\none\ntwo\nthree\nIMPORT\ntarget\n", out);
    }

    /** The common case — an import above the caret — is untouched by the translation. */
    @Test
    void leavesAnEditAboveTheAcceptWhereItIs() throws Exception {
        String out = acceptThenResolve("one\ntwo\nthree", List.of(new LspTextEdit(0, 0, 0, 0, "IMPORT\n")));
        assertEquals("IMPORT\ntop\none\ntwo\nthree\ntarget\n", out);
    }

    /** A single-line accept (the overwhelmingly common shape) shifts no lines. */
    @Test
    void singleLineAcceptDoesNotMoveLines() throws Exception {
        String out = acceptThenResolve("console", List.of(new LspTextEdit(2, 0, 2, 0, "IMPORT\n")));
        assertEquals("top\nconsole\nIMPORT\ntarget\n", out);
    }

    /** Nothing to resolve is a no-op, not an exception. */
    @Test
    void emptyAdditionalEditsAreANoOp() throws Exception {
        assertEquals("top\nconsole\ntarget\n", acceptThenResolve("console", List.of()));
    }
}
