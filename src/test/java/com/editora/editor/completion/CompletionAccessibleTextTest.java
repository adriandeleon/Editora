package com.editora.editor.completion;

import com.editora.completion.Completion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a screen reader says for a completion row, which is drawn as a graphic (Python review 2, C112). */
class CompletionAccessibleTextTest {

    @Test
    void theLabelThenTheDetailsFirstLine() {
        assertEquals(
                "OrderedDict, typing",
                CompletionPopup.accessibleText(Completion.lsp("OrderedDict", "OrderedDict", "typing")));
        assertEquals("area", CompletionPopup.accessibleText(Completion.lsp("area", "area", "")));
    }
}
