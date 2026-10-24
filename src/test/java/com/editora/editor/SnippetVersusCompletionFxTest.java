package com.editora.editor;

import java.util.List;

import javafx.stage.Stage;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import com.editora.editor.completion.BufferCompletion;
import com.editora.snippet.Snippet;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A snippet trigger typed out in full against the completions that used to take its Tab: in prose the
 * dictionary's ghost text ({@code date} offered {@code d} for "dated" and Tab accepted that), in code the
 * language server's first row.
 */
@Tag("fx")
class SnippetVersusCompletionFxTest {

    private static final Snippet DATE = new Snippet("Date", "date", "2026-10-11", "Current date", "global");

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer shown(String text, Stage[] stage) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride("markdown");
        // The dictionary's answer for the typed word: a longer word that continues it.
        buffer.setCompletionProvider((lang, dict, prefix, wide, prose) -> List.of(Completion.word("dated", null)));
        buffer.getArea().replaceText(text);
        stage[0] = EditorFx.show(buffer, 640, 320);
        buffer.getArea().requestFocus();
        buffer.getArea().moveTo(buffer.getArea().getLength());
        buffer.getNode().layout();
        return buffer;
    }

    @Test
    void withoutASnippetTheDictionaryWordIsStillTheGhost() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("date", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");

            completion.updateCompletion(area, false);
            assertTrue(completion.ghostVisible());
            assertEquals("d", completion.ghostLabel().getText());
            area.fireEvent(EditorFx.pressed(javafx.scene.input.KeyCode.TAB, false, false));
            assertEquals("dated", area.getText());
            stage[0].hide();
        });
    }

    @Test
    void aTriggerTabExpandsGetsNoGhostSoTabExpandsIt() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("date", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");
            buffer.setSnippetProvider((lang, p) -> "date".equals(p) ? DATE : null);

            completion.updateCompletion(area, false);
            assertFalse(completion.ghostVisible(), "the dictionary word must not take the trigger's Tab");
            area.fireEvent(EditorFx.pressed(javafx.scene.input.KeyCode.TAB, false, false));
            assertEquals("2026-10-11", area.getText());
            stage[0].hide();
        });
    }

    @Test
    void aListOnlyTriggerIsOfferedAsTheGhostAndTabExpandsIt() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("on date", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");
            // Tab alone leaves it be (the bundled date/time); a completion may still offer it.
            buffer.setSnippetOfferProvider((lang, p) -> "date".equals(p) ? DATE : null);

            completion.updateCompletion(area, false);
            assertTrue(completion.ghostVisible());
            assertEquals(" → Current date", completion.ghostLabel().getText());
            area.fireEvent(EditorFx.pressed(javafx.scene.input.KeyCode.TAB, false, false));
            assertEquals("on 2026-10-11", area.getText());
            assertFalse(completion.ghostVisible());
            stage[0].hide();
        });
    }

    @Test
    void theOfferNeedsSnippetCompletionsOnAndEscapeDropsIt() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("date", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");
            buffer.setSnippetOfferProvider((lang, p) -> "date".equals(p) ? DATE : null);

            buffer.setAutocomplete(true, true, false, true);
            completion.updateCompletion(area, false);
            assertEquals("d", completion.ghostLabel().getText(), "snippet completions off: the word, as before");

            buffer.setAutocomplete(true, true, true, true);
            completion.updateCompletion(area, false);
            assertSame(DATE, completion.ghostSnippet());
            area.fireEvent(EditorFx.pressed(javafx.scene.input.KeyCode.ESCAPE, false, false));
            assertFalse(completion.ghostVisible());
            assertEquals("date", area.getText());
            stage[0].hide();
        });
    }

    @Test
    void theTypedTriggerLeadsTheServersRowsOnce() {
        Snippet fori = new Snippet("For", "for", "for (;;) {}", "Loop", "java");
        var range = Completion.ReplaceRange.startingAt(3, 4);
        List<Completion> server = List.of(Completion.lsp("format", "format", null), Completion.ofSnippet(fori));

        List<Completion> rows = BufferCompletion.withTypedTrigger(server, fori, range);

        assertEquals(2, rows.size(), "the row the list already had is moved, not repeated");
        assertSame(fori, rows.get(0).snippet());
        assertEquals(range, rows.get(0).replaceRange());
        assertEquals("format", rows.get(1).label());
    }

    /** A language server's own template row, the way jdtls sends {@code sysout}: kind Snippet, label = trigger. */
    private static Completion serverTemplate(String label, String insert) {
        return Completion.lsp(label, insert, null, null, CompletionIconKind.SNIPPET, null, false, false, null);
    }

    @Test
    void theTypedTriggerHidesTheServersTemplateOfTheSameName() {
        Snippet sysout = new Snippet("sysout", "sysout", "System.out.println($1);$0", "Print", "java");
        var range = Completion.ReplaceRange.startingAt(4, 4);
        List<Completion> server = List.of(
                serverTemplate("sysout", "System.out.println(${0});"),
                serverTemplate("System.out.println()", "System.out.println(${0});"));

        List<Completion> rows = BufferCompletion.withTypedTrigger(server, sysout, range);

        assertEquals(
                List.of("sysout", "System.out.println()"),
                rows.stream().map(Completion::label).toList());
        assertSame(sysout, rows.get(0).snippet(), "the local snippet Tab expands, not jdtls's");
    }

    @Test
    void aLocalSnippetShadowsTheServerTemplateWithItsTriggerOnly() {
        Snippet fori = new Snippet("fori", "fori", "for (;;) {}", "Loop", "java");
        Snippet foreach = new Snippet("foreach", "foreach", "for (:) {}", "Loop", "java");
        List<Completion> server = List.of(
                serverTemplate("fori", "for (${1:int} ${2:i} = 0;;) {}"),
                serverTemplate("forEach", "x"),
                Completion.lsp("foreach", "foreach()", "a method that happens to share the name"));
        List<Completion> local = List.of(Completion.ofSnippet(fori), Completion.ofSnippet(foreach));

        List<Completion> rows = BufferCompletion.mergeCompletions(server, local);

        assertEquals(4, rows.size(), rows.toString());
        assertTrue(rows.stream().noneMatch(c -> c.label().equals("fori") && c.snippet() == null));
        assertTrue(rows.stream().anyMatch(c -> c.label().equals("forEach")), "a different name stays");
        assertTrue(
                rows.stream().anyMatch(c -> c.label().equals("foreach") && c.snippet() == null),
                "only server templates are shadowed, never a method of the same name");
        assertSame(fori, rows.get(rows.size() - 2).snippet());
    }

    @Test
    void withoutLocalSnippetsTheServerTemplatesStay() {
        List<Completion> server = List.of(serverTemplate("sysout", "System.out.println(${0});"));
        assertEquals(server, BufferCompletion.mergeCompletions(server, List.of()));
    }
}
