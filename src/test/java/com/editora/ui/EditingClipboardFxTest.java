package com.editora.ui;

import java.util.Map;

import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cut, copy, paste and the kill ring's yank-pop, with nothing selected, with several carets, and over a
 * Markdown selection; and the narrowing commands' answers when there is nothing to narrow to. Run through
 * the command registry in a real window against the toolkit's clipboard.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditingClipboardFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private EditingCoordinator editing;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        editing = FxTestSupport.field(fx.controller, "editing");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @BeforeEach
    void emptyRingAndClipboard() throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.<com.editora.editops.KillRing>field(editing, "killRing")
                    .clear();
            Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, ""));
            fx.shared.getSettings().setCopyLineWhenNoSelection(false);
        });
    }

    private EditorBuffer buffer(String content) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(content);
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            b.getArea().moveTo(0);
            return b;
        });
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
    }

    private String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private String clipboard() throws Exception {
        return FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString());
    }

    private void clipboard(String text) throws Exception {
        FxTestSupport.runOnFx(() -> Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, text)));
    }

    private String status() throws Exception {
        return SaveDecisionsFxTest.lastMessage(fx);
    }

    // --- nothing selected -------------------------------------------------------------------------------

    @Test
    void withNothingSelectedCutAndCopySayThereIsNothing() throws Exception {
        EditorBuffer b = buffer("one\ntwo\nthree");
        clipboard("untouched");
        run("edit.cut");
        assertEquals(tr("status.nothingToCut"), status());
        run("edit.copy");
        assertEquals(tr("status.nothingToCopy"), status());
        assertEquals("one\ntwo\nthree", text(b));
        assertEquals("untouched", clipboard());
    }

    @Test
    void withTheWholeLineSettingCutAndCopyTakeTheCaretLine() throws Exception {
        EditorBuffer b = buffer("one\ntwo\nthree");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setCopyLineWhenNoSelection(true);
            b.getArea().moveTo(1, 1);
        });
        run("edit.copy");
        assertEquals(tr("status.copiedLine"), status());
        assertEquals("two\n", clipboard());
        assertEquals("one\ntwo\nthree", text(b));

        run("edit.cut");
        assertEquals(tr("status.cutLine"), status());
        assertEquals("one\nthree", text(b));
        assertEquals("two\n", clipboard());
    }

    @Test
    void aSelectionIsCutToTheClipboardAndTheKillRing() throws Exception {
        EditorBuffer b = buffer("keep CUT keep");
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(5, 8));
        run("edit.cut");
        assertEquals("keep  keep", text(b));
        assertEquals("CUT", clipboard());
        assertEquals(tr("status.cut"), status());

        clipboard(""); // another program emptied the clipboard: the kill ring still has it
        run("edit.paste");
        assertEquals("keep CUT keep", text(b));
        assertEquals(tr("status.pasted"), status());
    }

    // --- several carets ---------------------------------------------------------------------------------

    @Test
    void severalCaretsCutCopyAndPasteOneLinePerCaret() throws Exception {
        EditorBuffer b = buffer("foo bar foo");
        run("edit.addCaretNextOccurrence"); // selects the word at the caret
        run("edit.addCaretNextOccurrence"); // and its next occurrence
        assertTrue(FxTestSupport.callOnFx(b::hasMultipleCarets));

        run("edit.copy");
        assertEquals("foo\nfoo", clipboard());
        assertEquals(tr("status.copied"), status());

        run("edit.cut");
        assertEquals(" bar ", text(b));
        assertEquals(tr("status.cut"), status());

        clipboard("X\nY");
        run("edit.paste");
        assertEquals("X bar Y", text(b), "one clipboard line per caret");
        assertEquals(tr("status.pasted"), status());
    }

    @Test
    void severalCaretsWithNothingSelectedHaveNothingToCutOrCopy() throws Exception {
        EditorBuffer b = buffer("aaa\nbbb\nccc");
        run("edit.addCaretBelow");
        assertTrue(FxTestSupport.callOnFx(b::hasMultipleCarets));
        run("edit.copy");
        assertEquals(tr("status.nothingToCopy"), status());
        run("edit.cut");
        assertEquals(tr("status.nothingToCut"), status());
        assertEquals("aaa\nbbb\nccc", text(b));

        run("edit.collapseCarets");
        assertFalse(FxTestSupport.callOnFx(b::hasMultipleCarets));
    }

    // --- markdown ---------------------------------------------------------------------------------------

    @Test
    void pastingAnAddressOverAMarkdownSelectionMakesItALink() throws Exception {
        EditorBuffer b = buffer("read the docs today");
        FxTestSupport.runOnFx(() -> {
            b.setLanguageOverride("markdown");
            b.getArea().selectRange(9, 13);
        });
        clipboard("https://example.com/docs");
        run("edit.paste");
        assertEquals("read the [docs](https://example.com/docs) today", text(b));
        assertEquals(tr("status.markdown.linkPasted"), status());
    }

    // --- yank-pop ---------------------------------------------------------------------------------------

    @Test
    void yankPopOnlyFollowsAYankAndNeedsAnOlderKillToRotateTo() throws Exception {
        EditorBuffer b = buffer("first\nsecond\n");
        run("edit.yankPop");
        assertEquals(tr("status.yankPop.notAfterYank"), status());

        run("edit.killLine"); // "first"
        run("edit.paste"); // yank it back
        assertEquals("first\nsecond\n", text(b));
        run("edit.yankPop");
        assertEquals(tr("status.yankPop.ringEmpty"), status(), "one entry: nothing older to rotate to");

        FxTestSupport.runOnFx(() -> b.getArea().moveTo(1, 0));
        run("edit.killLine"); // "second": a second ring entry
        assertEquals("first\n\n", text(b));
        run("edit.paste");
        assertEquals("first\nsecond\n", text(b));
        run("edit.yankPop");
        assertEquals("first\nfirst\n", text(b), "the yank was replaced by the next-older kill");
        assertEquals(tr("status.yankPop", 2, 2), status());
        assertEquals("first", clipboard());

        FxTestSupport.runOnFx(() -> b.getArea().appendText("typed since"));
        run("edit.yankPop");
        assertEquals(tr("status.yankPop.notAfterYank"), status(), "the document moved on: no range to replace");
    }

    // --- narrowing --------------------------------------------------------------------------------------

    @Test
    void narrowingSaysWhenThereIsNoRegionFunctionOrFoldAndWideningWhenNotNarrowed() throws Exception {
        buffer(""); // an empty buffer has no function to narrow to
        run("edit.narrowToDefun");
        assertEquals(tr("status.narrow.noDefun"), status());

        EditorBuffer b = buffer("just some words\nand more\n");
        run("edit.narrowToRegion");
        assertEquals(tr("status.narrow.noRegion"), status());
        run("edit.narrowToFoldRegion");
        assertEquals(tr("status.narrow.noFoldRegion"), status());
        run("edit.widen");
        assertEquals(tr("status.narrow.notNarrowed"), status());
        assertFalse(FxTestSupport.callOnFx(b::isNarrowed));

        FxTestSupport.runOnFx(() -> b.getArea().selectRange(5, 9));
        run("edit.narrowToRegion");
        assertTrue(FxTestSupport.callOnFx(b::isNarrowed));
        assertEquals("some", text(b));
        run("edit.widen");
        assertEquals(tr("status.narrow.widened"), status());
        assertEquals("just some words\nand more\n", text(b));
    }

    @Test
    void aViewModeBufferCanStillBeNarrowed() throws Exception {
        EditorBuffer b = buffer("alpha beta gamma");
        FxTestSupport.runOnFx(() -> {
            b.setViewMode(true);
            b.getArea().selectRange(6, 10);
        });
        run("edit.narrowToRegion");
        assertEquals("beta", text(b), "narrowing restricts the view; it is not an edit of the file");
        run("edit.widen");
        assertEquals("alpha beta gamma", text(b));
    }
}
