package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import com.editora.completion.Completion;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The caret-anchored assists and their keys: the AI ghost suggestion (when one is asked for, shown, accepted
 * or dropped), the quick-fix list and the completion popup.
 */
@Tag("fx")
class CompletionAssistFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    /** One pending AI request: what the editor sent and the callback that answers it. */
    private record Request(String language, String prefix, String suffix, Consumer<String> answer) {}

    private static KeyEvent key(KeyCode code, boolean shift, boolean control) {
        return EditorFx.pressed(code, shift, control);
    }

    private static EditorBuffer shown(String language, String text, Stage[] stage) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride(language);
        buffer.getArea().replaceText(text);
        stage[0] = EditorFx.show(buffer, 640, 320);
        buffer.getArea().requestFocus();
        buffer.getArea().moveTo(buffer.getArea().getLength());
        buffer.getNode().layout();
        return buffer;
    }

    @Test
    void anAiSuggestionIsShownAsGhostTextAndTabAcceptsIt() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("markdown", "The quick brown", stage);
            CodeArea area = buffer.getArea();
            assertTrue(area.isFocused(), "the test needs a focused editor");
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");
            List<Request> requests = new ArrayList<>();
            buffer.setAiCompletionProvider(
                    (language, prefix, suffix, answer) -> requests.add(new Request(language, prefix, suffix, answer)));

            completion.maybeRequestAiCompletion(area);
            assertTrue(requests.isEmpty(), "the feature is off: nothing is sent");

            buffer.setAiCompletionEnabled(true);
            completion.maybeRequestAiCompletion(area);
            assertEquals(1, requests.size());
            assertEquals("markdown", requests.get(0).language());
            assertEquals("The quick brown", requests.get(0).prefix());
            assertEquals("", requests.get(0).suffix());

            requests.get(0).answer().accept(" fox jumps  \nover the lazy dog");
            Label ghost = completion.ghostLabel();
            assertTrue(completion.ghostVisible());
            assertEquals(" fox jumps", ghost.getText(), "only the first line, without trailing blanks");
            assertEquals("The quick brown", area.getText(), "a suggestion is not text until accepted");

            area.fireEvent(key(KeyCode.TAB, false, false));
            assertEquals("The quick brown fox jumps", area.getText());
            assertFalse(completion.ghostVisible());
            assertEquals(area.getLength(), area.getCaretPosition());

            // Escape drops a suggestion; so does Shift-Tab, which then dedents as usual.
            completion.maybeRequestAiCompletion(area);
            requests.get(1).answer().accept(" over");
            assertTrue(completion.ghostVisible());
            area.fireEvent(key(KeyCode.ESCAPE, false, false));
            assertFalse(completion.ghostVisible());
            assertEquals("The quick brown fox jumps", area.getText());

            completion.maybeRequestAiCompletion(area);
            requests.get(2).answer().accept(" over");
            area.fireEvent(key(KeyCode.TAB, true, false));
            assertFalse(completion.ghostVisible());
            assertFalse(area.getText().contains("over"), "Shift-Tab never accepts");

            // Turning the feature off hides what is showing and drops what is still in flight.
            completion.maybeRequestAiCompletion(area);
            requests.get(3).answer().accept(" again");
            assertTrue(completion.ghostVisible());
            completion.maybeRequestAiCompletion(area);
            buffer.setAiCompletionEnabled(false);
            assertFalse(completion.ghostVisible());
            requests.get(4).answer().accept(" too late");
            assertFalse(completion.ghostVisible(), "an answer to a request made before the switch-off is ignored");
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void anAiSuggestionIsOnlyRequestedWhereItCanBeShownAndOnlyShownIfStillCurrent() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("markdown", "first line\nsecond", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");
            List<Request> requests = new ArrayList<>();
            buffer.setAiCompletionEnabled(true);
            completion.maybeRequestAiCompletion(area);
            assertTrue(requests.isEmpty(), "no provider is wired");
            buffer.setAiCompletionProvider(
                    (language, prefix, suffix, answer) -> requests.add(new Request(language, prefix, suffix, answer)));

            area.moveTo(0);
            completion.maybeRequestAiCompletion(area);
            assertTrue(requests.isEmpty(), "nothing to continue at the very start");
            area.moveTo(5);
            completion.maybeRequestAiCompletion(area);
            assertTrue(requests.isEmpty(), "text follows the caret on its line");
            area.selectRange(11, area.getLength());
            completion.maybeRequestAiCompletion(area);
            assertTrue(requests.isEmpty(), "a selection is not a place to continue");
            area.moveTo(10); // end of "first line": only the next line follows
            buffer.setViewMode(true);
            completion.maybeRequestAiCompletion(area);
            assertTrue(requests.isEmpty(), "a document in view mode is not completed");
            buffer.setViewMode(false);

            completion.maybeRequestAiCompletion(area);
            assertEquals(1, requests.size());
            assertEquals("first line", requests.get(0).prefix());
            assertEquals("\nsecond", requests.get(0).suffix(), "the text after the caret is context too");

            requests.get(0).answer().accept(null);
            requests.get(0).answer().accept("   ");
            requests.get(0).answer().accept("\nonly a second line");
            assertFalse(completion.ghostVisible(), "an empty answer shows nothing");

            // A newer request supersedes the older one's answer.
            completion.maybeRequestAiCompletion(area);
            requests.get(0).answer().accept(" stale");
            assertFalse(completion.ghostVisible());
            // The caret moved on before the answer arrived.
            area.moveTo(area.getLength());
            requests.get(1).answer().accept(" late");
            assertFalse(completion.ghostVisible());
            completion.acceptGhost(); // nothing pending: nothing is inserted
            assertEquals("first line\nsecond", area.getText());
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void theQuickFixListIsDrivenFromTheKeyboard() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("java", "class A { int x }", stage);
            CodeArea area = buffer.getArea();
            List<CodeAction> actions = List.of(
                    new CodeAction("Add semicolon", "quickfix", true, "a"),
                    new CodeAction("Remove field", "quickfix", false, "b"),
                    new CodeAction("Suppress warning", "quickfix", false, "c"));
            List<String> accepted = new ArrayList<>();
            Consumer<CodeAction> onAccept = action -> accepted.add(action.title());

            buffer.showCodeActions(List.of(), onAccept);
            buffer.showCodeActions(null, onAccept);
            assertFalse(buffer.codeActionsShowing(), "no actions, no list");

            buffer.showCodeActions(actions, onAccept);
            assertTrue(buffer.codeActionsShowing());
            area.fireEvent(key(KeyCode.DOWN, false, false));
            area.fireEvent(key(KeyCode.N, false, true)); // C-n
            area.fireEvent(key(KeyCode.P, false, true)); // C-p
            area.fireEvent(key(KeyCode.CONTROL, false, true)); // the modifier going down must not dismiss
            assertTrue(buffer.codeActionsShowing());
            area.fireEvent(key(KeyCode.HOME, false, false)); // a key that moves the caret does
            assertFalse(buffer.codeActionsShowing());
            assertTrue(accepted.isEmpty());

            buffer.showCodeActions(actions, onAccept);
            area.fireEvent(key(KeyCode.DOWN, false, false));
            area.fireEvent(key(KeyCode.DOWN, false, false));
            area.fireEvent(key(KeyCode.UP, false, false));
            area.fireEvent(key(KeyCode.ENTER, false, false));
            assertEquals(List.of("Remove field"), accepted);
            assertFalse(buffer.codeActionsShowing(), "accepting closes the list");
            assertEquals("class A { int x }", area.getText(), "Enter chose an action, it did not type a newline");

            buffer.showCodeActions(actions, onAccept);
            area.fireEvent(key(KeyCode.G, false, true)); // C-g cancels
            assertFalse(buffer.codeActionsShowing());
            buffer.showCodeActions(actions, onAccept);
            area.fireEvent(key(KeyCode.ESCAPE, false, false));
            assertFalse(buffer.codeActionsShowing());
            // n, p and g without Control are ordinary letters: the press does nothing here, and the edit
            // that typing them makes is what closes the list (below).
            buffer.showCodeActions(actions, onAccept);
            area.fireEvent(key(KeyCode.N, false, false));
            area.fireEvent(key(KeyCode.P, false, false));
            area.fireEvent(key(KeyCode.G, false, false));
            assertTrue(buffer.codeActionsShowing());
            area.fireEvent(key(KeyCode.ENTER, false, false));
            assertEquals(List.of("Remove field", "Add semicolon"), accepted, "and none of them moved the selection");

            // An edit invalidates the list: it was computed for the text as it was.
            buffer.showCodeActions(actions, onAccept);
            area.insertText(0, " ");
            assertFalse(buffer.codeActionsShowing());
            buffer.hideCodeActions();
            assertEquals(2, accepted.size());
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void theCompletionPopupIsNavigatedAndAcceptedFromTheKeyboard() throws Exception {
        EditorFx.onFx(() -> {
            Stage[] stage = new Stage[1];
            EditorBuffer buffer = shown("java", "app", stage);
            CodeArea area = buffer.getArea();
            BufferCompletion completion = EditorFx.field(buffer, "completionActions");
            List<String> words = List.of("apple", "applet", "application", "apply", "approve");
            buffer.setCompletionProvider((snippetLang, dictLang, prefix, snippetPrefix, prose) ->
                    words.stream().map(w -> Completion.word(w, null)).toList());
            buffer.setAutocomplete(true, true, true, true);

            buffer.toggleCompletionDoc(); // no popup: nothing to document
            buffer.triggerCompletion();
            assertTrue(buffer.completionShowing(), "the popup opened on the typed prefix");
            CompletionPopup popup = EditorFx.field(completion, "completionPopup");
            assertEquals("apple", popup.selected().insert());

            area.fireEvent(key(KeyCode.DOWN, false, false));
            assertEquals("applet", popup.selected().insert());
            area.fireEvent(key(KeyCode.N, false, true));
            assertEquals("application", popup.selected().insert());
            area.fireEvent(key(KeyCode.P, false, true));
            area.fireEvent(key(KeyCode.UP, false, false));
            assertEquals("apple", popup.selected().insert());
            area.fireEvent(key(KeyCode.PAGE_DOWN, false, false));
            assertEquals("approve", popup.selected().insert(), "a page is longer than this list");
            area.fireEvent(key(KeyCode.PAGE_UP, false, false));
            assertEquals("apple", popup.selected().insert());
            area.fireEvent(key(KeyCode.DOWN, false, false));
            area.fireEvent(key(KeyCode.DOWN, false, false));
            area.fireEvent(key(KeyCode.DOWN, false, false));

            area.fireEvent(key(KeyCode.ENTER, false, false));
            assertEquals("apply", area.getText(), "Enter replaced the prefix with the selected word");
            assertFalse(buffer.completionShowing());

            // Shift-Enter, an arrow that moves the caret, C-g and Escape all dismiss without accepting.
            for (KeyEvent dismiss : List.of(
                    key(KeyCode.ENTER, true, false),
                    key(KeyCode.LEFT, false, false),
                    key(KeyCode.G, false, true),
                    key(KeyCode.ESCAPE, false, false))) {
                area.replaceText("app");
                area.moveTo(3);
                buffer.triggerCompletion();
                assertTrue(buffer.completionShowing());
                area.fireEvent(dismiss);
                assertFalse(buffer.completionShowing(), dismiss.getCode() + " dismisses the popup");
                assertTrue(area.getText().startsWith("app") && !area.getText().contains("apple"), area.getText());
            }

            // The master switch closes an open popup and keeps it closed.
            area.replaceText("app");
            area.moveTo(3);
            buffer.triggerCompletion();
            assertTrue(buffer.completionShowing());
            buffer.setAutocomplete(false, true, true, true);
            assertFalse(buffer.completionShowing());
            buffer.triggerCompletion();
            assertFalse(buffer.completionShowing());
            buffer.cancelCompletion();
            assertNotNull(popup);
            stage[0].close();
            buffer.dispose();
        });
    }
}
