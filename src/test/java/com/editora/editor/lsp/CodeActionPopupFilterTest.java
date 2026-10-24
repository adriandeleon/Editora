package com.editora.editor.lsp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Typing while the quick-fix list is open narrows it to the titles containing what was typed (Java review E6). */
class CodeActionPopupFilterTest {

    private static CodeAction action(String title) {
        return new CodeAction(title, "", false, title);
    }

    @Test
    void theFilterMatchesTitlesIgnoringCase() {
        var actions =
                List.of(action("Extract to method"), action("Extract to local variable"), action("Inline Method"));
        assertEquals(actions, CodeActionPopup.matching(actions, ""));
        assertEquals(List.of(actions.get(0), actions.get(2)), CodeActionPopup.matching(actions, "METHOD"));
        assertEquals(List.of(actions.get(1)), CodeActionPopup.matching(actions, "local"));
        assertEquals(List.of(), CodeActionPopup.matching(actions, "zzz"));
    }
}
