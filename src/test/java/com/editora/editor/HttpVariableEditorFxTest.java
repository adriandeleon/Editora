package com.editora.editor;

import java.util.List;
import java.util.Map;

import javafx.scene.input.KeyCode;
import javafx.stage.Stage;

import com.editora.completion.Completion;
import com.editora.editor.completion.BufferCompletion;
import com.editora.editor.completion.CompletionPopup;
import com.editora.editor.markdown.MarkdownLintHover;
import com.editora.editor.markdown.MarkdownLintOverlay;
import com.editora.http.HttpVariableIndex;
import com.editora.i18n.Messages;
import com.editora.markdown.MarkdownLint;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A {@code .http} buffer underlines {@code {{variables}}} that cannot resolve and completes after {@code {{}. */
@Tag("fx")
class HttpVariableEditorFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
        Messages.init("en");
    }

    private static EditorBuffer shown(String text, Stage[] stage) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride("http");
        buffer.getArea().replaceText(text);
        stage[0] = EditorFx.show(buffer, 640, 320);
        buffer.getArea().requestFocus();
        buffer.getArea().moveTo(buffer.getArea().getLength());
        buffer.getNode().layout();
        return buffer;
    }

    /** The validator the window injects, run synchronously against a fixed environment. */
    private static void checkAgainst(EditorBuffer buffer, Map<String, String> env) {
        HttpVariableIndex.Context ctx = new HttpVariableIndex.Context(env, null);
        buffer.setHttpHooks(buffer.httpHooks()
                .withVariables(
                        (text, done) -> done.accept(HttpVariableIndex.unresolved(text, ctx, null).stream()
                                .map(p -> new MarkdownLint.Diagnostic(
                                        p.line() + 1, p.column() + 1, p.length(), "warning", p.reference(), p.reason()))
                                .toList()),
                        null));
    }

    @Test
    void unresolvedReferencesAreUnderlinedWithTheReasonInTheTooltip() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("GET {{host}}/a\nAuthorization: Bearer {{tokn}}\n", stage);
            checkAgainst(buffer, Map.of("host", "http://h"));
            assertTrue(buffer.httpVariableDiagnostics().isEmpty(), "off until the HTTP Client is on for the buffer");

            buffer.setHttpEnabled(true);
            List<MarkdownLint.Diagnostic> marks = buffer.httpVariableDiagnostics();
            assertEquals(1, marks.size(), marks.toString());
            assertEquals(2, marks.get(0).line(), "1-based, as lint diagnostics are");
            assertEquals("Authorization: Bearer ".length() + 1, marks.get(0).column());
            MarkdownLintOverlay overlay = EditorFx.field(buffer, "mdLintOverlay");
            List<MarkdownLint.Diagnostic> hover = overlay.at(1, "Authorization: Bearer {{t".length());
            assertEquals(
                    "{{tokn}}: not defined in the file or the selected environment", MarkdownLintHover.text(hover));

            // A new environment that defines it clears the mark once the check runs again.
            checkAgainst(buffer, Map.of("host", "http://h", "tokn", "x"));
            buffer.refreshHttpVariableLint();
            assertTrue(buffer.httpVariableDiagnostics().isEmpty());

            buffer.setHttpEnabled(false);
            assertTrue(buffer.httpVariableDiagnostics().isEmpty());
            stage[0].close();
        });
    }

    @Test
    void twoBracesOfferTheVariablesAndAcceptClosesThePair() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("@id = 7\nGET {{h", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");
            buffer.setCompletionProvider((s, d, p, sp, prose) -> List.of());
            buffer.setAutocomplete(true, true, true, true);
            buffer.setHttpHooks(buffer.httpHooks()
                    .withVariables(null, () -> new HttpVariableIndex.Context(Map.of("host", "http://h"), null)));

            buffer.triggerCompletion();
            assertTrue(buffer.completionShowing(), "the popup opened inside {{");
            CompletionPopup popup = EditorFx.field(completion, "completionPopup");
            assertEquals("host}}", popup.selected().insert());
            area.fireEvent(EditorFx.pressed(KeyCode.ENTER, false, false));
            assertEquals("@id = 7\nGET {{host}}", area.getText());
            assertFalse(buffer.completionShowing());

            area.appendText("/x?n={{");
            area.moveTo(area.getLength());
            buffer.triggerCompletion();
            assertTrue(buffer.completionShowing());
            javafx.scene.control.ListView<Completion> list = EditorFx.field(popup, "list");
            List<String> rows = list.getItems().stream().map(Completion::insert).toList();
            assertTrue(rows.containsAll(List.of("host}}", "id}}", "$uuid}}")), rows.toString());
            stage[0].close();
        });
    }
}
