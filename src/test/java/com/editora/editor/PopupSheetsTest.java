package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A popup's own app/syntax sheets are followed by the owner's editor theme, which then wins (JS review B107). */
class PopupSheetsTest {

    @Test
    void theOwnersThemeComesAfterThePopupsOwnSheets() {
        List<String> mine = List.of("app.css", "syntax.css");
        assertEquals(
                List.of("editor-themes/editora-dark.css"),
                PopupSheets.extraSheets(mine, List.of("app.css", "syntax.css", "editor-themes/editora-dark.css")));
        assertEquals(
                List.of(), PopupSheets.extraSheets(mine, List.of("app.css", "syntax.css")), "light: nothing to add");
    }
}
