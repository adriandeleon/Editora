package com.editora.editor.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

import com.editora.completion.Completion;
import com.editora.completion.CompletionIconKind;
import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorFx;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Language-server completion in a real buffer ({@link BufferCompletion}): the documentation side-popup that Ctrl+Q
 * toggles beside the list and that follows the selection, a list longer than the popup that scrolls as the
 * selection leaves it, a click that accepts, a prefix that filters every item away, the protocol's commit
 * characters, and the signature-help requests a typed {@code (} or {@code ,} sends.
 */
@Tag("fx")
class LspCompletionFlowFxTest {

    private static final long WAIT_MILLIS = 10_000;

    private final List<Stage> stages = new ArrayList<>();
    private final List<EditorBuffer> buffers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @AfterEach
    void close() throws Exception {
        EditorFx.onFx(() -> {
            buffers.forEach(EditorBuffer::dispose);
            stages.forEach(Stage::close);
        });
    }

    /** A focused Java buffer whose language server answers every completion request with {@code items}. */
    private EditorBuffer lspBuffer(String text, List<Completion> items) throws Exception {
        return EditorFx.callFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent(text);
            b.setLspActive(true);
            b.setLspTriggerChars(Set.of('.'));
            b.setCompletionDocEnabled(false);
            b.setLspCompletionProvider((position, answer) -> answer.accept(items));
            stages.add(EditorFx.show(b, 800, 400));
            buffers.add(b);
            b.getArea().requestFocus();
            b.getArea().moveTo(b.getArea().getLength());
            b.getNode().layout();
            return b;
        });
    }

    private static BufferCompletion completion(EditorBuffer b) {
        return EditorFx.field(b, "completionActions");
    }

    private static void await(String what, BooleanSupplier onFx) throws Exception {
        long end = System.currentTimeMillis() + WAIT_MILLIS;
        while (!EditorFx.callFx(onFx::getAsBoolean)) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(20);
        }
        EditorFx.drain();
    }

    private static void open(EditorBuffer b) throws Exception {
        EditorFx.onFx(b::triggerCompletion);
        await("the completion popup", () -> completion(b).completionPopupShowing());
    }

    private static Completion method(int i) {
        String name = String.format("method%02d", i);
        return Completion.lsp(
                name, name, "void " + name + "()", null, CompletionIconKind.METHOD, null, false, false, "doc-" + name);
    }

    @Test
    void ctrlQShowsTheDocumentationBesideTheListAndItFollowsTheSelection() throws Exception {
        List<Completion> items = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            items.add(method(i));
        }
        EditorBuffer b = lspBuffer("thing.met", items);
        List<Object> asked = new CopyOnWriteArrayList<>();
        EditorFx.onFx(() -> b.setCompletionDocResolver((token, answer) -> {
            asked.add(token);
            answer.accept("**Does** the thing. See [the docs](https://example.org/" + token + ").");
        }));
        open(b);
        BufferCompletion c = completion(b);

        EditorFx.onFx(b::toggleCompletionDoc);
        await("the documentation popup", () -> c.docPopup().isShowing());
        assertEquals("doc-method00", asked.get(asked.size() - 1));

        // Down past the visible rows: the list scrolls, and the documentation follows the selection.
        EditorFx.onFx(() -> {
            for (int i = 0; i < 25; i++) {
                b.getArea().fireEvent(EditorFx.pressed(KeyCode.DOWN, false, false));
            }
        });
        assertEquals(
                "method25", EditorFx.callFx(() -> c.completionPopup().selected().label()));
        await("the documentation of the new selection", () -> asked.contains("doc-method25"));
        assertTrue(EditorFx.callFx(() -> c.docPopup().isShowing()));
        EditorFx.onFx(() -> b.getArea().fireEvent(EditorFx.pressed(KeyCode.UP, false, false)));
        assertEquals(
                "method24", EditorFx.callFx(() -> c.completionPopup().selected().label()));

        EditorFx.onFx(b::toggleCompletionDoc);
        await("the documentation popup to close", () -> !c.docPopup().isShowing());
        EditorFx.onFx(b::toggleCompletionDoc);
        await("the documentation popup again", () -> c.docPopup().isShowing());
        EditorFx.onFx(() -> b.setCompletionDocEnabled(false));
        assertFalse(EditorFx.callFx(() -> c.docPopup().isShowing()), "turning the setting off closes it");

        // A click on the list accepts the selected item.
        EditorFx.onFx(() -> {
            ListView<Completion> list = EditorFx.field(c.completionPopup(), "list");
            Event.fireEvent(
                    list,
                    new MouseEvent(
                            MouseEvent.MOUSE_CLICKED,
                            5,
                            5,
                            5,
                            5,
                            MouseButton.PRIMARY,
                            1,
                            false,
                            false,
                            false,
                            false,
                            true,
                            false,
                            false,
                            true,
                            false,
                            true,
                            null));
        });
        await("the click to accept", () -> !c.completionPopupShowing());
        assertEquals(
                "thing.method24()",
                EditorFx.callFx(() -> b.getArea().getText()),
                "a method lands with its parentheses");
    }

    /**
     * The HTML and CSS servers end nearly every item's documentation with an MDN link. A link was a Tab stop, and a
     * popup takes its owner window's keys first, so Enter followed the link (opening the browser) and Tab moved
     * between links: no tag, attribute or property could be accepted from the keyboard (HTML review B1/C1).
     */
    @Test
    void enterAndTabAcceptAnItemWhoseDocumentationEndsInALink() throws Exception {
        for (KeyCode key : List.of(KeyCode.ENTER, KeyCode.TAB)) {
            EditorBuffer b = lspBuffer(
                    "<bu",
                    List.of(Completion.lsp(
                            "button", "button", null, null, CompletionIconKind.OTHER, null, false, false, "button")));
            List<String> opened = new CopyOnWriteArrayList<>();
            EditorFx.onFx(() -> {
                b.setOpenUrlHandler(opened::add);
                b.setCompletionDocEnabled(true);
                b.setCompletionDocResolver((token, answer) -> answer.accept("The button element.\n\n"
                        + "[MDN Reference](https://developer.mozilla.org/docs/Web/HTML/Reference/Elements/button)"));
            });
            open(b);
            BufferCompletion c = completion(b);
            await("the documentation popup", () -> c.docPopup().isShowing());
            assertEquals(
                    null,
                    EditorFx.callFx(() -> ((javafx.stage.Popup) EditorFx.field(c.docPopup(), "popup"))
                            .getScene()
                            .getFocusOwner()),
                    "nothing in the documentation takes the keyboard");

            EditorFx.onFx(() -> b.getArea().fireEvent(EditorFx.pressed(key, false, false)));
            await(key + " to accept", () -> !c.completionPopupShowing());
            assertEquals("<button", EditorFx.callFx(() -> b.getArea().getText()), key + " accepts the item");
            assertEquals(List.of(), opened, key + " opens no link");
        }
    }

    /**
     * An HTML page's local snippets are those of the language at the caret: CSS's in a {@code <style>}, none in a
     * start tag — the {@code col} and {@code hr} element snippets were preselected there (HTML review B5/C4).
     */
    @Test
    void anHtmlPageAsksForTheSnippetsOfTheLanguageAtTheCaret() throws Exception {
        for (String[] probe : new String[][] {
            {"<style>\np { col", "css"}, {"<script>\n  var", "javascript"}, {"<a hr", "none"}, {"<p>\n  hr", "html"}
        }) {
            List<String> asked = new CopyOnWriteArrayList<>();
            EditorBuffer b = lspBuffer(probe[0], List.of(Completion.lsp("zzz", "zzz", null)));
            EditorFx.onFx(() -> {
                b.setLanguageOverride("html");
                b.setAutocomplete(true, true, true, true);
                b.setCompletionProvider((lang, dict, prefix, wide, prose) -> {
                    asked.add(lang);
                    return List.of();
                });
                b.triggerCompletion();
            });
            EditorFx.drain();
            assertEquals(probe[1].equals("none") ? List.of() : List.of(probe[1]), asked, probe[0]);
        }
    }

    /**
     * A closer typed by hand joins the one Editora inserted ({@code <p>a</|p>}). The server, asked there, offers the
     * next open ancestor, and the Enter that followed renamed {@code </p>} to {@code </body>} (HTML review C6): no
     * list opens in a whole end tag, and the rest of the name steps over the closer.
     */
    @Test
    void noListOpensWhereAHandTypedCloserJoinsTheOneThere() throws Exception {
        EditorBuffer b = lspBuffer("<body>\n<p>a</p>\n</body>", List.of(Completion.lsp("/body", "/body>", null)));
        CodeArea a = EditorFx.callFx(b::getArea);
        EditorFx.onFx(() -> {
            b.setLanguageOverride("html");
            b.setLspTriggerChars(Set.of('<', '/'));
            b.setAutoCloseTags(true);
            a.moveTo("<body>\n<p>a".length());
            a.fireEvent(EditorFx.typed("<"));
            a.fireEvent(EditorFx.typed("/"));
        });
        assertEquals("<body>\n<p>a</p>\n</body>", EditorFx.callFx(a::getText), "precondition: the closer joined");
        Thread.sleep(700); // past the typing pause
        EditorFx.drain();
        assertFalse(EditorFx.callFx(() -> completion(b).completionPopupShowing()), "no list in a whole end tag");
        EditorFx.onFx(() -> {
            a.fireEvent(EditorFx.typed("p"));
            a.fireEvent(EditorFx.typed(">"));
        });
        assertEquals("<body>\n<p>a</p>\n</body>", EditorFx.callFx(a::getText), "the closer is typed over, once");
        assertEquals("<body>\n<p>a</p>".length(), EditorFx.callFx(a::getCaretPosition));
    }

    @Test
    void typingAPrefixNoItemMatchesClosesTheList() throws Exception {
        EditorBuffer b = lspBuffer("out.pr", List.of(Completion.lsp("println", "println", "void println()")));
        open(b);
        EditorFx.onFx(() -> b.getArea().insertText(b.getArea().getLength(), "zz"));
        await("the list to close", () -> !completion(b).completionPopupShowing());
        assertEquals("out.przz", EditorFx.callFx(() -> b.getArea().getText()));
    }

    private static Completion committing(String label, String insert, String... commit) {
        return Completion.lsp(label, insert, "detail")
                .withProtocol(new Completion.Protocol(label, null, List.of(commit)));
    }

    /** Opens the list on {@code text}, types {@code typed} and returns the text after it. */
    private String commit(String text, Completion item, String typed) throws Exception {
        EditorBuffer b = lspBuffer(text, List.of(item));
        open(b);
        EditorFx.onFx(() -> b.getArea().fireEvent(EditorFx.typed(typed)));
        await("the commit character to accept", () -> !completion(b).completionPopupShowing());
        return EditorFx.callFx(() -> b.getArea().getText());
    }

    @Test
    void aCommitCharacterAcceptsTheSelectionAndIsTypedOnlyWhenTheItemLeftRoomForIt() throws Exception {
        assertTrue(
                commit("out.pri", committing("println", "println", "("), "(").startsWith("out.println("),
                "the ( is typed after the accepted name");
        assertEquals(
                "out.call()",
                commit("out.ca", committing("call", "call()", "("), "("),
                "the item brought its own parenthesis: the typed ( is swallowed");
        assertEquals(
                "out.done;",
                commit("out.do", committing("done", "done;", ";"), ";"),
                "an item that ends the statement takes the typed ; as its own");
        assertEquals(
                "out.next.;",
                commit("out.ne", committing("next", "next;", "."), "."),
                "a . after an item that ended the statement goes before its ;");
    }

    @Test
    void aTypedSignatureTriggerAsksTheServerForSignatureHelp() throws Exception {
        EditorBuffer b = lspBuffer("call", List.of());
        List<Character> asked = new CopyOnWriteArrayList<>();
        EditorFx.onFx(() -> {
            b.setLspSignatureTriggerChars(Set.of('(', ','));
            b.setSignatureHelpRequester(asked::add);
        });
        CodeArea a = EditorFx.callFx(b::getArea);
        EditorFx.onFx(() -> a.insertText(a.getLength(), "("));
        EditorFx.onFx(() -> a.insertText(a.getLength(), "()"));
        EditorFx.onFx(() -> a.insertText(a.getLength(), ","));
        EditorFx.onFx(() -> a.insertText(a.getLength(), "(x")); // a paste, not a typed trigger
        EditorFx.onFx(() -> a.insertText(a.getLength(), "x"));
        EditorFx.onFx(() -> a.replaceText(0, 1, "(")); // replaces text: not a typed trigger either
        EditorFx.drain();
        assertEquals(List.of('(', '(', ','), asked);
    }

    @Test
    void theFilteringHelpersMatchInOrderAndFindThePreselectedItem() {
        assertTrue(BufferCompletion.isSubsequence("pln", "println"));
        assertTrue(BufferCompletion.isSubsequence("", "anything"));
        assertFalse(BufferCompletion.isSubsequence("nlp", "println"), "out of order");
        assertFalse(BufferCompletion.isSubsequence("printlnx", "println"), "longer than the text");

        Completion plain = Completion.lsp("a", "a", null);
        Completion chosen = Completion.lsp("b", "b", null, null, CompletionIconKind.OTHER, null, true, false, null);
        assertEquals(1, BufferCompletion.preselectIndexOf(List.of(plain, chosen, plain)));
        assertEquals(0, BufferCompletion.preselectIndexOf(List.of(plain, plain)), "no preselect: the first");

        assertEquals(List.of(), BufferCompletion.filterByPrefix(null, "x"));
        assertEquals(
                List.of("println"),
                BufferCompletion.filterByPrefix(
                                List.of(
                                        Completion.lsp("println", "println", null),
                                        Completion.lsp("size", "size", null)),
                                "prin")
                        .stream()
                        .map(Completion::label)
                        .toList());

        for (char closer : new char[] {')', ']', '}', '>'}) {
            assertTrue(BufferCompletion.isCloserChar(closer));
        }
        assertFalse(BufferCompletion.isCloserChar('('));
    }

    /** Undoing a rename puts the old name back at the caret: that is no word typed, so no list (fifth review BF2). */
    @Test
    void anUndoOpensNoList() throws Exception {
        EditorBuffer b = lspBuffer("int box", List.of(Completion.lsp("boxed", "boxed", "int")));
        EditorFx.onFx(() -> {
            var area = b.getArea();
            area.getUndoManager().preventMerge();
            area.replaceText(4, 7, "boxy"); // what a rename applies
            area.getUndoManager().preventMerge();
            b.cancelCompletion();
        });
        Thread.sleep(600);
        EditorFx.onFx(() -> b.getArea().undo());
        Thread.sleep(800); // past the typing pause
        EditorFx.drain();
        assertEquals("int box", EditorFx.callFx(() -> b.getArea().getText()));
        assertFalse(EditorFx.callFx(b::completionShowing), "no list after Ctrl+Z");
    }
}
