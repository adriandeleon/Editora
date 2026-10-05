package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.Node;
import javafx.scene.control.Label;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Narrowing and the things that look at a buffer from outside it. A narrowed text area holds only the
 * region, so "open this file at line N" (a search hit, a Problems entry, a stack-trace link) used to index
 * the region with a document line: line 4 landed on the region's fourth line and line 301 did nothing. And
 * the status bar's "Narrowed" chip was set by the narrow command alone, so it stayed lit on every other tab.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NarrowedNavigationFxTest {

    private EditingFx e;

    @BeforeAll
    void setUp() throws Exception {
        e = EditingFx.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (e != null) {
            e.dispose();
        }
    }

    /** Opens a 400-line file and narrows it to document lines 20..25 (1-based), i.e. 0-based 19..24. */
    private EditorBuffer narrowed(String name) throws Exception {
        EditorBuffer b = e.open(name, EditingFx.lines("line", 400));
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> area.selectRange(
                area.getAbsolutePosition(19, 0), area.getAbsolutePosition(24, area.getParagraphLength(24))));
        e.run("edit.narrowToRegion");
        assertTrue(FxTestSupport.callOnFx(b::isNarrowed), "narrowed");
        assertEquals(6, (int) FxTestSupport.callOnFx(() -> area.getParagraphs().size()));
        return b;
    }

    private String caretLine(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() ->
                b.getArea().getParagraph(b.getArea().getCurrentParagraph()).getText());
    }

    private void openAt(Path file, int line0) throws Exception {
        FxTestSupport.runOnFx(() -> e.fx.controller.openAndNavigate(file, line0));
        e.pulses(25);
    }

    @Test
    void aTargetInsideTheRegionIsRebasedOntoIt() throws Exception {
        EditorBuffer b = narrowed("inside.txt");
        openAt(b.getPath(), 21); // document line 22
        assertEquals("line 22", caretLine(b));
        assertTrue(FxTestSupport.callOnFx(b::isNarrowed), "a jump inside the region leaves it narrowed");
    }

    @Test
    void aTargetBeforeTheRegionWidensInsteadOfLandingOnTheWrongLine() throws Exception {
        EditorBuffer b = narrowed("before.txt");
        openAt(b.getPath(), 3); // document line 4 — used to land on "line 23", the region's fourth line
        assertEquals("line 4", caretLine(b));
        assertFalse(FxTestSupport.callOnFx(b::isNarrowed), "widened to reach it");
    }

    @Test
    void aTargetAfterTheRegionWidensInsteadOfDoingNothing() throws Exception {
        EditorBuffer b = narrowed("after.txt");
        openAt(b.getPath(), 300);
        assertEquals("line 301", caretLine(b));
        assertFalse(FxTestSupport.callOnFx(b::isNarrowed));
    }

    /** The line:column path (search hits, go-to-definition, {@code file:line} on the command line). */
    @Test
    void gotoInFileTakesADocumentLineToo() throws Exception {
        EditorBuffer b = narrowed("goto.txt");
        Object sessions = FxTestSupport.field(e.fx.controller, "sessions");
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                sessions, "gotoInFile", new Class<?>[] {Path.class, int.class, int.class}, b.getPath(), 23, 1));
        e.pulses(10);
        assertEquals("line 23", caretLine(b));
        assertTrue(FxTestSupport.callOnFx(b::isNarrowed));

        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                sessions, "gotoInFile", new Class<?>[] {Path.class, int.class, int.class}, b.getPath(), 4, 1));
        e.pulses(10);
        assertEquals("line 4", caretLine(b), "not the region's fourth line");
        assertFalse(FxTestSupport.callOnFx(b::isNarrowed));
    }

    @Test
    void theNarrowedChipBelongsToTheNarrowedTabOnly() throws Exception {
        EditorBuffer a = narrowed("chip-a.txt");
        assertTrue(chipShown(), "shown on the narrowed tab");

        EditorBuffer other = e.open("chip-b.txt", "plain\n");
        assertFalse(chipShown(), "another tab is not narrowed");

        e.selectTab(a.getPath());
        assertTrue(chipShown(), "back on the narrowed tab");

        e.run("buffer.close");
        e.pulses(10);
        assertFalse(FxTestSupport.callOnFx(() -> e.active() == a), "the narrowed tab is closed");
        assertFalse(chipShown(), "closing the narrowed tab takes the chip with it (now on " + other.getPath() + ")");
    }

    private boolean chipShown() throws Exception {
        e.pulses(5);
        Node statusBar = FxTestSupport.field(e.fx.controller, "statusBar");
        Label chip = FxTestSupport.field(statusBar, "narrowed");
        return FxTestSupport.callOnFx(chip::isVisible);
    }
}
