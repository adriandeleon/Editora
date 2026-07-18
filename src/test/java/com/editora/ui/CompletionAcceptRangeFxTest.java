package com.editora.ui;

import com.editora.completion.Completion;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for #445: accepting an LSP completion whose insert begins with the non-identifier trigger that
 * opened the popup (phpactor's {@code $}, bash variables) must not duplicate that char. Before the fix the
 * identifier walk stopped at the {@code $}, so accepting {@code $user} after typing {@code $} inserted
 * {@code $user} <em>after</em> the existing {@code $}, yielding {@code $$user}. Two mechanisms are covered:
 * the trigger-overlap heuristic (insertText-only items) and an explicit LSP {@code textEdit.range}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompletionAcceptRangeFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static String acceptOverPhpSigil(Completion c) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("php");
            b.setContent("<?php\n$user = 1;\n$\n");
            b.getNode();
            CodeArea area = FxTestSupport.field(b, "area");
            // Caret right after the lone '$' on line 2 (0-based line 2, col 1): offset of that '$' + 1.
            int dollar = b.getContent().lastIndexOf('$');
            area.moveTo(dollar + 1);
            FxTestSupport.call(b, "acceptCompletion", new Class<?>[] {CodeArea.class, Completion.class}, area, c);
            return area.getText();
        });
    }

    @Test
    void triggerOverlapDoesNotDuplicateTheSigil() throws Exception {
        // insertText-only item (phpactor's actual shape): no replaceStart → the editor's overlap heuristic
        // must consume the typed '$'.
        Completion c = Completion.lsp("$user", "$user", "");
        assertEquals("<?php\n$user = 1;\n$user\n", acceptOverPhpSigil(c));
    }

    @Test
    void explicitTextEditRangeReplacesTheSigil() throws Exception {
        // A server that sends textEdit.range starting at the '$' (line 2, char 0): the range start is honored.
        Completion c = Completion.lsp("$user", "$user", "").withReplaceStart(new Completion.ReplaceStart(2, 0));
        assertEquals("<?php\n$user = 1;\n$user\n", acceptOverPhpSigil(c));
    }

    @Test
    void ordinaryIdentifierCompletionIsUnaffected() throws Exception {
        // Typing "us" then accepting "user" still replaces the identifier, not appends.
        String result = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("php");
            b.setContent("<?php\n$us\n");
            b.getNode();
            CodeArea area = FxTestSupport.field(b, "area");
            int after = b.getContent().indexOf("$us") + 3; // caret after "us"
            area.moveTo(after);
            Completion c = Completion.lsp("user", "user", "");
            FxTestSupport.call(b, "acceptCompletion", new Class<?>[] {CodeArea.class, Completion.class}, area, c);
            return area.getText();
        });
        assertEquals("<?php\n$user\n", result);
    }
}
