package com.editora.ui;

import javafx.scene.input.Clipboard;

import com.editora.command.CommandRegistry;
import com.editora.editops.KillRing;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits on a collapsed fold's header must never leave its body in the document as hidden paragraphs with
 * no fold to expand them. The line commands take the header together with its hidden run; any other edit
 * that splits or joins the header or the run expands the fold. Driven through the real command registry.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoldedHeaderEditFxTest {

    private static final String HEAD = "class A {\n    void first() {\n        a();\n    }\n";
    private static final String SECOND = "    void second() {\n        b();\n        c();\n    }\n";
    private static final String TAIL = "    void third() {\n        d();\n    }\n}\n";
    private static final String SRC = HEAD + SECOND + TAIL;
    private static final int HEADER = 4; // the `void second() {` line

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private KillRing ring;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        ring = FxTestSupport.field(FxTestSupport.field(fx.controller, "editing"), "killRing");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
        FxTestSupport.drainFx(); // the fold reconciliation after an edit is deferred by one runLater
    }

    /** A Java buffer with {@code second()} collapsed and the caret on its header at {@code col}. */
    private EditorBuffer folded(int col) throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            ring.clear();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("java");
            buffer.setContent(SRC);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
            buffer.getFoldManager().setLanguage("java");
            buffer.getFoldManager().recompute();
            buffer.getFoldManager()
                    .fold(buffer.getFoldManager().regionStartingAt(HEADER).orElseThrow());
            buffer.getArea().moveTo(HEADER, col);
            return buffer;
        });
        FxTestSupport.drainFx();
        assertEquals(3, hidden(b), "the fold itself must survive being created");
        return b;
    }

    private static int hidden(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea a = b.getArea();
            int n = 0;
            for (int p = 0; p < a.getParagraphs().size(); p++) {
                if (a.isFolded(p)) {
                    n++;
                }
            }
            return n;
        });
    }

    private static String text(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> b.getArea().getText());
    }

    private static String clipboard() throws Exception {
        return FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString());
    }

    // --- the line commands take the header together with its hidden body ---------------------------

    @Test
    void killWholeLineOnACollapsedHeaderRemovesTheBodyToo() throws Exception {
        EditorBuffer b = folded(4);
        run("edit.killWholeLine");
        assertEquals(HEAD + TAIL, text(b));
        assertEquals(0, hidden(b));
        assertEquals(SECOND, FxTestSupport.callOnFx(ring::current), "and the whole block is what a yank restores");
    }

    @Test
    void killLineOnACollapsedHeaderKillsThroughTheHiddenBody() throws Exception {
        EditorBuffer b = folded(0);
        run("edit.killLine");
        assertEquals(HEAD + "\n" + TAIL, text(b));
        assertEquals(0, hidden(b));
    }

    @Test
    void cutAndCopyWithNoSelectionTakeTheWholeFoldedBlock() throws Exception {
        EditorBuffer b = folded(4);
        run("edit.copy");
        assertEquals(SECOND, clipboard());
        assertEquals(SRC, text(b));
        assertEquals(3, hidden(b), "copying changes nothing");
        run("edit.cut");
        assertEquals(SECOND, clipboard());
        assertEquals(HEAD + TAIL, text(b));
        assertEquals(0, hidden(b));
    }

    @Test
    void duplicateLineDuplicatesTheWholeFoldedBlock() throws Exception {
        EditorBuffer b = folded(4);
        run("edit.duplicateLine");
        assertEquals(HEAD + SECOND + SECOND + TAIL, text(b));
        assertEquals(0, hidden(b));
        assertEquals(HEADER + 4, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()), "caret on the copy");
    }

    @Test
    void moveLineMovesTheWholeFoldedBlock() throws Exception {
        EditorBuffer b = folded(4);
        run("edit.moveLineDown");
        assertEquals(HEAD + "    void third() {\n" + SECOND + "        d();\n    }\n}\n", text(b));
        assertEquals(0, hidden(b));
    }

    @Test
    void movingALineOntoACollapsedFoldStepsOverTheWholeBlock() throws Exception {
        EditorBuffer b = folded(4);
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(HEADER - 1, 0)); // first()'s closing brace
        run("edit.moveLineDown");
        assertEquals("class A {\n    void first() {\n        a();\n" + SECOND + "    }\n" + TAIL, text(b));
        assertEquals(0, hidden(b));
    }

    @Test
    void toggleCommentCommentsTheWholeFoldedBlockAndShowsIt() throws Exception {
        EditorBuffer b = folded(4);
        run("edit.toggleComment");
        assertEquals(0, hidden(b));
        assertEquals(
                HEAD + "    /* void second() {\n        b();\n        c();\n    } */\n" + TAIL,
                text(b),
                "one comment around the header and its body, not around the header alone");
    }

    // --- any other edit that splits or joins the header or its hidden run expands the fold ---------

    @Test
    void enterAtTheEndOfACollapsedHeaderExpandsTheFold() throws Exception {
        EditorBuffer b = folded("    void second() {".length());
        FxTestSupport.runOnFx(() -> b.typeString("\n"));
        FxTestSupport.drainFx();
        assertEquals(0, hidden(b), "the new line must not come between the header and a still-hidden body");
    }

    @Test
    void joiningAVisibleLineIntoTheHiddenRunExpandsTheFold() throws Exception {
        EditorBuffer b = folded(0);
        int thirdStart = (HEAD + SECOND).length();
        FxTestSupport.runOnFx(() -> b.getArea().deleteText(thirdStart - 1, thirdStart)); // Backspace at column 0
        FxTestSupport.drainFx();
        assertEquals(0, hidden(b), "`void third() {` must not disappear into the hidden run");
    }

    @Test
    void deletingJustTheHeaderLineExpandsItsBody() throws Exception {
        EditorBuffer b = folded(0);
        int start = HEAD.length();
        FxTestSupport.runOnFx(() -> b.getArea().deleteText(start, start + "    void second() {\n".length()));
        FxTestSupport.drainFx();
        assertEquals(0, hidden(b));
    }

    @Test
    void aHeaderEditedIntoSomethingThatStartsNoRegionExpandsItsBody() throws Exception {
        EditorBuffer b = folded(0);
        int brace = HEAD.length() + "    void second() ".length();
        FxTestSupport.runOnFx(() -> {
            b.getArea().replaceText(brace, brace + 1, ";"); // `void second() ;` no longer opens a block
            b.getFoldManager().recompute();
        });
        FxTestSupport.drainFx();
        assertEquals(0, hidden(b));
    }

    // --- and the edits that leave the fold whole leave it collapsed --------------------------------

    @Test
    void typingOnTheHeaderOrPushingItDownKeepsTheFoldCollapsed() throws Exception {
        EditorBuffer b = folded(0);
        int name = HEAD.length() + "    void second".length();
        FxTestSupport.runOnFx(() -> {
            b.getArea().insertText(name, "X"); // rename: still a block opener
            b.getFoldManager().recompute();
        });
        FxTestSupport.drainFx();
        assertEquals(3, hidden(b), "a same-line edit of the header is not a reason to expand");
        FxTestSupport.runOnFx(() -> {
            b.getArea().insertText(HEAD.length(), "\n"); // a blank line in front of the header
            b.getFoldManager().recompute();
        });
        FxTestSupport.drainFx();
        assertEquals(3, hidden(b), "the header moved down whole");
        assertTrue(FxTestSupport.callOnFx(() -> b.getFoldManager().isCollapsed(HEADER + 1)));
    }
}
