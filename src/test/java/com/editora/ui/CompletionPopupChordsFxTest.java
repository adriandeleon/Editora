package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Popup;

import com.editora.command.CommandRegistry;
import com.editora.editor.CodeAction;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completion popup and the quick-fix list used to mark the editor {@code editora.ownsKeys}, which took
 * <em>every</em> single-chord {@code nav.*}/{@code edit.*} binding off the keymap while they were up: in the
 * default Emacs keymap {@code C-a} selected the whole document (RichTextFX's Ctrl+A), {@code C-e} did nothing
 * and {@code M-f} typed an {@code f}. They now take only the chords they handle. Also covers the local
 * (no language server) list going stale: it was recomputed only by the 280 ms typing pause, so Enter/Tab
 * replaced a finished word with a snippet offered for its first two letters.
 *
 * <p>A real window with default settings; keys are fired at the scene's focus owner, as the scene does.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompletionPopupChordsFxTest {

    private static final String DOC = "import os\n\ndef area(w, h):\n    return w * h\n\n";

    private FxWindowFixture fx;
    private EditorBuffer buf;
    private final List<String> ran = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        Path file = Files.createTempDirectory("popup-chords").resolve("probe.py");
        Files.writeString(file, DOC);
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 1));
        settle(800);
        buf = (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        Consumer<String> prev = FxTestSupport.field(registry, "executionListener");
        FxTestSupport.runOnFx(() -> registry.setExecutionListener(id -> {
            ran.add(id);
            if (prev != null) prev.accept(id);
        }));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) fx.dispose();
    }

    private static void settle(int ms) throws Exception {
        Thread.sleep(ms);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    /** Press (+ typed, when the key has a character and the press was not consumed) + release, on the FX thread. */
    private void fireNow(KeyCode code, boolean ctrl, boolean alt, String ch) {
        Node fo = buf.getArea().getScene().getFocusOwner();
        KeyEvent press = new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, false, ctrl, alt, false);
        Event.fireEvent(fo, press);
        if (ch != null) {
            Event.fireEvent(fo, new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, ctrl, alt, false));
        }
        Event.fireEvent(
                fo, new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, "", code, false, ctrl, alt, false));
    }

    private void fire(KeyCode code, boolean ctrl, boolean alt, String ch) throws Exception {
        FxTestSupport.runOnFx(() -> fireNow(code, ctrl, alt, ch));
        settle(60);
    }

    private void type(String s) throws Exception {
        for (char c : s.toCharArray()) {
            fire(KeyCode.valueOf(String.valueOf(Character.toUpperCase(c))), false, false, String.valueOf(c));
        }
    }

    /** Resets the document to {@code DOC + lead}, types {@code prefix} and waits for the snippet popup. */
    private void openPopup(String lead, String prefix) throws Exception {
        FxTestSupport.runOnFx(() -> {
            buf.cancelCompletion();
            var a = buf.getArea();
            a.replaceText(DOC + lead);
            a.moveTo(a.getLength());
            a.requestFocus();
        });
        settle(150);
        type(prefix);
        settle(600); // the local list opens on the 280 ms typing pause
        ran.clear();
        assertTrue(popup(), "the snippet popup opened for '" + prefix + "'");
    }

    private boolean popup() throws Exception {
        return FxTestSupport.callOnFx(buf::completionShowing);
    }

    private String text() throws Exception {
        return FxTestSupport.callOnFx(() -> buf.getArea().getText());
    }

    @Test
    void controlAStillMovesToTheLineStartWhileThePopupIsOpen() throws Exception {
        openPopup("", "fo");
        fire(KeyCode.A, true, false, "\u0001");
        assertEquals(List.of("nav.lineStart"), ran, "C-a ran its command");
        assertEquals(
                0,
                (int) FxTestSupport.callOnFx(() -> buf.getArea().getSelection().getLength()),
                "nothing selected");
        assertEquals(
                DOC.length(), (int) FxTestSupport.callOnFx(() -> buf.getArea().getCaretPosition()));
        assertEquals(DOC + "fo", text());
        assertFalse(popup(), "the caret left the word, so the list closed");
    }

    @Test
    void controlEAndAnAltChordReachTheirCommands() throws Exception {
        openPopup("", "fo");
        fire(KeyCode.E, true, false, "\u0005");
        assertEquals(List.of("nav.lineEnd"), ran);

        openPopup("", "fo");
        fire(KeyCode.F, false, true, "f");
        assertEquals(List.of("nav.wordForward"), ran, "M-f ran its command");
        assertEquals(DOC + "fo", text(), "and did not type an 'f'");
    }

    @Test
    void theListStillTakesControlNAndCancel() throws Exception {
        openPopup("", "fo");
        Object actions = FxTestSupport.field(buf, "completionActions");
        Object list = FxTestSupport.field(actions, "completionPopup");
        Object first = FxTestSupport.callOnFx(() -> FxTestSupport.call(list, "selected", new Class<?>[] {}));
        fire(KeyCode.N, true, false, "\u000e");
        assertTrue(ran.isEmpty(), "C-n is the list's, not nav.lineDown");
        assertTrue(popup());
        Object second = FxTestSupport.callOnFx(() -> FxTestSupport.call(list, "selected", new Class<?>[] {}));
        assertFalse(first.equals(second), "the selection moved down");
        fire(KeyCode.G, true, false, "\u0007");
        assertTrue(ran.isEmpty(), "C-g closes the list rather than running edit.cancel");
        assertFalse(popup());
    }

    @Test
    void theDocumentationPanelLeavesEscapeToTheEditor() throws Exception {
        Object actions = FxTestSupport.field(buf, "completionActions");
        Popup doc = FxTestSupport.callOnFx(
                () -> FxTestSupport.field(FxTestSupport.call(actions, "docPopup", new Class<?>[] {}), "popup"));
        assertFalse(doc.isHideOnEscape(), "else the panel swallows the first Escape and the list needs a second");
    }

    @Test
    void anAltChordOverTheQuickFixListRunsItsCommandAndDismissesTheList() throws Exception {
        FxTestSupport.runOnFx(() -> {
            buf.cancelCompletion();
            var a = buf.getArea();
            a.replaceText(DOC);
            a.moveTo(0);
            a.requestFocus();
            buf.showCodeActions(List.of(new CodeAction("Fix A", "quickfix", false, "A")), x -> {});
        });
        settle(100);
        ran.clear();
        assertTrue(FxTestSupport.callOnFx(buf::codeActionsShowing));
        fire(KeyCode.F, false, true, "f");
        assertEquals(List.of("nav.wordForward"), ran);
        assertEquals(DOC, text(), "no 'f' was typed");
        assertFalse(FxTestSupport.callOnFx(buf::codeActionsShowing), "the caret moved, so the list is gone");
    }

    // ---- the local list going stale (no language server) ----

    @Test
    void theLocalListFollowsTheTypedWord() throws Exception {
        openPopup("total = ", "fo");
        type("otprint"); // 60 ms per key: the 280 ms pause never fires
        assertFalse(popup(), "nothing starts with 'footprint', so the list closed without waiting for a pause");
        fire(KeyCode.ENTER, false, false, null);
        assertTrue(
                text().startsWith(DOC + "total = footprint\n"), "Enter is a newline, not the 'for' snippet: " + text());
    }

    @Test
    void enterRefusesARowTheWordHasOutgrown() throws Exception {
        openPopup("total = ", "fo");
        // The rest of the word and Enter inside one FX task: no deferred refresh can run in between, so this
        // is the accept-time check alone.
        FxTestSupport.runOnFx(() -> {
            var a = buf.getArea();
            a.insertText(a.getCaretPosition(), "otprint");
            fireNow(KeyCode.ENTER, false, false, null);
        });
        settle(100);
        assertFalse(text().contains("for value"), "the stale 'for' row was not expanded: " + text());
        assertTrue(text().startsWith(DOC + "total = footprint\n"), text());
        assertFalse(popup());
    }

    @Test
    void aRowThatStillMatchesIsAccepted() throws Exception {
        openPopup("", "fo");
        fire(KeyCode.ENTER, false, false, null);
        assertTrue(text().startsWith(DOC + "for "), "the selected 'for…' snippet expanded: " + text());
        fire(KeyCode.ESCAPE, false, false, null); // end the snippet session
    }
}
