package com.editora.ui;

import javafx.geometry.Orientation;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of independent editor groups (#762): two different files on screen at once, moving a
 * tab between groups, collapsing an emptied group, and merging back.
 *
 * <p>Driven through the real {@link MainController} + {@link EditorArea} rather than against {@code EditorArea}
 * alone, because the interesting failure is in the seam between them — a relocated tab reaches the
 * controller's tab-list listener as a remove followed by an add, which is indistinguishable from a close
 * followed by an open unless the relocation guard holds.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditorGroupsFxTest {

    private FxWindowFixture fx;
    private EditorArea area;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        area = FxTestSupport.field(fx.controller, "editorArea");
    }

    /**
     * The fixture opens a Welcome tab when there is no session to restore, and these tests assert on exact
     * tab and group counts — so each starts from a genuinely empty area rather than subtracting a constant.
     */
    @org.junit.jupiter.api.BeforeEach
    void emptyTheArea() throws Exception {
        cleanUp();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void splitMovesTheActiveTabIntoASecondGroup() throws Exception {
        Tab first = addBuffer();
        Tab second = addBuffer();

        assertEquals(1, groupCount(), "the area starts unsplit");
        assertTrue(FxTestSupport.callOnFx(() -> area.splitActive(Orientation.HORIZONTAL)), "split succeeds");

        assertEquals(2, groupCount(), "the area is now two groups");
        assertTrue(FxTestSupport.callOnFx(() -> area.contains(first)), "the untouched file stays open");
        assertTrue(FxTestSupport.callOnFx(() -> area.contains(second)), "the moved file stays open");
        assertEquals(2, FxTestSupport.callOnFx(() -> area.size()), "both files are open, once each");
        assertSame(second, FxTestSupport.callOnFx(() -> area.selectedTab()), "focus follows the moved file");

        cleanUp();
    }

    /**
     * Moving the only tab in a group would empty that group, collapse it, and land back exactly where it
     * started — a flicker, not a split. Refusing is what lets the caller report why nothing happened.
     */
    @Test
    void splittingTheOnlyTabIsRefused() throws Exception {
        addBuffer();

        assertFalse(FxTestSupport.callOnFx(() -> area.splitActive(Orientation.HORIZONTAL)), "split is refused");
        assertEquals(1, groupCount(), "the area is still unsplit");

        cleanUp();
    }

    /**
     * The regression this whole guard exists for. A relocation removes the tab from one group and adds it to
     * another; without {@code EditorArea.isRelocating()} the controller's tab-list listener would read that
     * removal as a close and dispose the buffer — killing its highlighter and language server underneath a
     * tab that is still on screen. {@code dispose()} bumps {@code previewGen}, so that counter is a direct
     * probe for "was this buffer torn down".
     */
    @Test
    void movingATabBetweenGroupsDoesNotDisposeItsBuffer() throws Exception {
        addBuffer();
        Tab moved = addBuffer();
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> (EditorBuffer) moved.getUserData());
        long generationBefore = previewGen(buffer);

        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));

        assertEquals(generationBefore, previewGen(buffer), "the moved buffer was not disposed");
        assertTrue(FxTestSupport.callOnFx(() -> area.contains(moved)), "and it is still open");

        // Moving it on again (back round to the first group) must be equally harmless.
        FxTestSupport.runOnFx(() -> area.moveActiveToNextGroup());
        assertEquals(generationBefore, previewGen(buffer), "still not disposed after a second move");

        cleanUp();
    }

    /** Closing the last file in a split must not leave a dead empty pane behind. */
    @Test
    void closingTheLastTabInAGroupCollapsesThatGroup() throws Exception {
        addBuffer();
        Tab moved = addBuffer();
        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));
        assertEquals(2, groupCount(), "split first");

        FxTestSupport.runOnFx(() -> area.remove(moved));

        assertEquals(1, groupCount(), "the emptied group collapsed");
        assertEquals(1, FxTestSupport.callOnFx(() -> area.size()), "the other file is untouched");

        cleanUp();
    }

    @Test
    void unsplitMergesEveryGroupBackIntoOne() throws Exception {
        Tab first = addBuffer();
        Tab second = addBuffer();
        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));
        assertEquals(2, groupCount(), "split first");

        assertTrue(FxTestSupport.callOnFx(() -> area.unsplit()), "merge reports a change");

        assertEquals(1, groupCount(), "back to a single group");
        assertEquals(2, FxTestSupport.callOnFx(() -> area.size()), "with both files still open");
        assertTrue(FxTestSupport.callOnFx(() -> area.contains(first) && area.contains(second)), "neither was lost");
        assertFalse(FxTestSupport.callOnFx(() -> area.unsplit()), "merging an unsplit area is a no-op");

        cleanUp();
    }

    private int groupCount() throws Exception {
        return FxTestSupport.callOnFx(() -> area.groupCount());
    }

    private long previewGen(EditorBuffer buffer) throws Exception {
        return FxTestSupport.callOnFx(() -> ((Number) FxTestSupport.field(buffer, "previewGen")).longValue());
    }

    private Tab addBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            return (Tab) FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
        });
    }

    /** Leaves the shared fixture unsplit and empty for the next test. */
    private void cleanUp() throws Exception {
        FxTestSupport.runOnFx(() -> {
            area.unsplit();
            for (Tab tab : new java.util.ArrayList<>(area.tabs())) {
                // userData is Object and the Welcome tab holds a WelcomePane, not a buffer — the same reason
                // MainController reads it through bufferOf() rather than casting.
                if (tab.getUserData() instanceof EditorBuffer buffer) {
                    buffer.markClean();
                }
                FxTestSupport.call(fx.controller, "closeTab", new Class[] {Tab.class}, tab);
            }
        });
    }
}
