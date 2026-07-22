package com.editora.ui;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.command.CommandRegistry;
import com.editora.editops.KillRing;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * End-to-end kill/yank behaviour through the real {@link CommandRegistry} against a wired window — the
 * half the pure {@code KillRingTest} cannot reach, because it depends on the controller's sequencing
 * decisions.
 *
 * <p>Consecutive-kill accumulation in particular is asserted here rather than left to the unit test: the
 * verdict is computed from {@link EditorBuffer#docVersion()}, which the kill's own {@code replaceText}
 * bumps, so taking it a moment too late silently disables accumulation while every unit test still
 * passes and {@code C-k C-k C-y} quietly restores only the last line.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KillRingFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private KillRing ring;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        ring = FxTestSupport.field(fx.controller, "killRing");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    /** Opens {@code content} as the active buffer with the caret at {@code line}:{@code col}. */
    private EditorBuffer open(String content, int line, int col) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ring.clear();
            setClipboard(""); // no stale external text to adopt
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().moveTo(line, col);
            return b;
        });
    }

    private static void setClipboard(String s) {
        ClipboardContent c = new ClipboardContent();
        c.putString(s);
        Clipboard.getSystemClipboard().setContent(c);
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    // --- kill ------------------------------------------------------------------------------------

    @Test
    void killLinePutsTheTextOnTheRingAndTheClipboard() throws Exception {
        EditorBuffer b = open("alpha\nbeta\ngamma", 1, 0);
        run("edit.killLine");
        assertEquals("alpha\n\ngamma", text(b), "C-k removes to end of line");
        assertEquals("beta", FxTestSupport.callOnFx(ring::current), "the killed text is yankable");
        assertEquals(
                "beta",
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()),
                "a kill is also visible to other applications");
    }

    @Test
    void consecutiveKillLinesAccumulateIntoOneRingEntry() throws Exception {
        EditorBuffer b = open("one\ntwo\nthree\nrest", 0, 0);
        run("edit.killLine"); // "one"
        run("edit.killLine"); // the newline
        run("edit.killLine"); // "two"
        run("edit.killLine"); // the newline
        assertEquals("three\nrest", text(b));
        assertEquals(1, FxTestSupport.callOnFx(ring::size), "consecutive kills must not push separate entries");
        assertEquals("one\ntwo\n", FxTestSupport.callOnFx(ring::current), "and they accumulate in order");
    }

    @Test
    void anEditBetweenTwoKillsStartsANewRingEntry() throws Exception {
        EditorBuffer b = open("one\ntwo\n", 0, 0);
        run("edit.killLine");
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "x")); // user types: the run is broken
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(0, 1));
        run("edit.killLine");
        assertEquals(2, FxTestSupport.callOnFx(ring::size), "an intervening edit separates the kills");
    }

    @Test
    void backwardKillWordsPrependSoTheTextStaysInReadingOrder() throws Exception {
        EditorBuffer b = open("alpha beta", 0, 10);
        run("edit.backwardKillWord");
        run("edit.backwardKillWord");
        assertEquals("", text(b));
        assertEquals(1, FxTestSupport.callOnFx(ring::size));
        assertEquals("alpha beta", FxTestSupport.callOnFx(ring::current), "M-DEL M-DEL then C-y restores the text");
    }

    @Test
    void deleteCommandsThatAreNotKillsStayOffTheRing() throws Exception {
        open("a    b", 0, 1);
        run("edit.deleteHorizontalSpace"); // M-\ deletes, it does not kill
        assertEquals(0, FxTestSupport.callOnFx(ring::size), "M-backslash must not disturb the kill ring");
    }

    // --- yank ------------------------------------------------------------------------------------

    @Test
    void yankInsertsTheMostRecentKill() throws Exception {
        EditorBuffer b = open("alpha\nbeta", 0, 0);
        run("edit.killLine"); // kills "alpha"
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(1, 4)); // end of "beta"
        run("edit.paste");
        assertEquals("\nbetaalpha", text(b));
    }

    @Test
    void yankPrefersTextCopiedInAnotherApplication() throws Exception {
        EditorBuffer b = open("x", 0, 1);
        FxTestSupport.runOnFx(() -> {
            ring.kill("ours", KillRing.Direction.FORWARD, false);
            setClipboard("from elsewhere");
        });
        run("edit.paste");
        assertEquals("xfrom elsewhere", text(b), "an external copy is newer than our kill and wins");
        assertEquals("ours", FxTestSupport.callOnFx(ring::rotate), "our kill stays reachable via yank-pop");
    }

    @Test
    void yankPopReplacesTheYankedTextWithTheOlderEntry() throws Exception {
        EditorBuffer b = open("", 0, 0);
        FxTestSupport.runOnFx(() -> {
            ring.kill("older", KillRing.Direction.FORWARD, false);
            ring.kill("newer", KillRing.Direction.FORWARD, false);
        });
        run("edit.paste");
        assertEquals("newer", text(b));
        run("edit.yankPop");
        assertEquals("older", text(b), "M-y swaps in the previous entry rather than inserting alongside");
    }

    @Test
    void yankPopAfterAnythingElseIsRefusedRatherThanGuessing() throws Exception {
        EditorBuffer b = open("", 0, 0);
        FxTestSupport.runOnFx(() -> {
            ring.kill("older", KillRing.Direction.FORWARD, false);
            ring.kill("newer", KillRing.Direction.FORWARD, false);
        });
        run("edit.paste");
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "typed")); // no longer directly after a yank
        run("edit.yankPop");
        assertEquals("typednewer", text(b), "the document must be left alone when M-y has no known range");
    }

    @Test
    void copyPushesToTheRingWithoutMerging() throws Exception {
        EditorBuffer b = open("alpha\nbeta", 0, 0);
        run("edit.killLine");
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(1, 5)); // select part of "beta"
        run("edit.copy");
        assertEquals(2, FxTestSupport.callOnFx(ring::size), "a copy is always its own entry");
        assertNotEquals("alpha", FxTestSupport.callOnFx(ring::current), "and it is what the next yank inserts");
    }
}
