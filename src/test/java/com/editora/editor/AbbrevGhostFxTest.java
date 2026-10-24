package com.editora.editor;

import java.util.List;
import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import com.editora.completion.Completion;
import com.editora.editor.completion.BufferCompletion;
import com.editora.snippet.Snippet;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** An abbreviation typed out in full previews its expansion as ghost text, as a list-only snippet does. */
@Tag("fx")
class AbbrevGhostFxTest {

    private static final Map<String, String> TABLE = Map.of("adl-e", "adrian@example.com", "btw", "by the way");

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer shown(String language, String text, Stage[] stage) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride(language);
        // The dictionary's answer for any typed word: a longer word that continues it.
        buffer.setCompletionProvider((lang, dict, prefix, wide, prose) -> List.of(Completion.word(prefix + "s", null)));
        buffer.setAbbrevs(TABLE, true);
        buffer.getArea().replaceText(text);
        stage[0] = EditorFx.show(buffer, 640, 320);
        buffer.getArea().requestFocus();
        buffer.getArea().moveTo(buffer.getArea().getLength());
        buffer.getNode().layout();
        return buffer;
    }

    @Test
    void theExpansionIsPreviewedAndTabExpandsIt() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("markdown", "mail adl-e", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");

            completion.updateCompletion(area, false);
            assertTrue(completion.ghostVisible(), "a one-letter last word still previews: no word minimum");
            assertEquals(" → adrian@example.com", completion.ghostLabel().getText());
            area.fireEvent(EditorFx.pressed(KeyCode.TAB, false, false));
            assertEquals("mail adrian@example.com", area.getText());
            assertEquals(area.getLength(), area.getCaretPosition());
            assertFalse(completion.ghostVisible());
            stage[0].hide();
        });
    }

    @Test
    void thePreviewCarriesTheTypedCaseAndOutranksTheDictionaryWord() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("markdown", "Btw", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");

            completion.updateCompletion(area, false);
            assertEquals(" → By the way", completion.ghostLabel().getText());
            stage[0].hide();
        });
    }

    @Test
    void codeBuffersPreviewTooInPlaceOfTheList() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("java", "// btw", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");

            completion.updateCompletion(area, false);
            assertTrue(completion.ghostVisible());
            assertFalse(completion.completionPopupShowing());
            area.fireEvent(EditorFx.pressed(KeyCode.TAB, false, false));
            assertEquals("// by the way", area.getText());
            stage[0].hide();
        });
    }

    @Test
    void nothingIsPreviewedWithAbbrevModeOffOrTextAfterTheCaret() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("markdown", "btw then", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");

            area.moveTo(3);
            completion.updateCompletion(area, false);
            assertNull(completion.ghostAbbrev(), "the ghost would cover the text after the caret");

            area.replaceText("btw");
            area.moveTo(3);
            buffer.setAbbrevs(TABLE, false);
            completion.updateCompletion(area, false);
            assertNull(completion.ghostAbbrev(), "abbrev-mode off: nothing will expand, so nothing is previewed");
            assertEquals("s", completion.ghostLabel().getText(), "the dictionary word, as before");
            stage[0].hide();
        });
    }

    @Test
    void aTriggerTabExpandsKeepsItsTab() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("markdown", "btw", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");
            Snippet snippet = new Snippet("Btw", "btw", "BETWEEN", "Between", "global");
            buffer.setSnippetProvider((lang, p) -> "btw".equals(p) ? snippet : null);

            completion.updateCompletion(area, false);
            assertFalse(completion.ghostVisible());
            stage[0].hide();
        });
    }

    @Test
    void escapeDropsThePreviewAndAStaleOneExpandsNothing() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("markdown", "btw", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");

            completion.updateCompletion(area, false);
            area.fireEvent(EditorFx.pressed(KeyCode.ESCAPE, false, false));
            assertFalse(completion.ghostVisible());
            assertEquals("btw", area.getText());

            completion.updateCompletion(area, true); // a manual invoke asks for the list, not the preview
            assertNull(completion.ghostAbbrev());

            completion.updateCompletion(area, false);
            assertNotNull(completion.ghostAbbrev());
            buffer.setAbbrevs(Map.of("btw", "between you and me"), true);
            completion.acceptGhost();
            assertEquals("btw", area.getText(), "the table changed under the preview: what it showed is gone");
            stage[0].hide();
        });
    }

    @Test
    void theGhostTextIsOneShortLine() {
        assertEquals(" → a b", BufferCompletion.abbrevGhostText("  a\n\tb "));
        String longText = "x".repeat(100);
        String shown = BufferCompletion.abbrevGhostText(longText);
        assertEquals(" → ".length() + BufferCompletion.ABBREV_GHOST_MAX, shown.length());
        assertTrue(shown.endsWith("…"));
    }
}
