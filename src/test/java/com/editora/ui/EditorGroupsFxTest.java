package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Orientation;
import javafx.scene.control.Tab;

import com.editora.config.EditorGroupLayout;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * The point of nesting: a horizontal split can contain a vertical one, so three groups can form an
     * L-shape rather than three equal columns. Splitting the *second* group downward must nest inside that
     * group's slot and leave the first column alone.
     */
    @Test
    void splittingAcrossOrientationsNests() throws Exception {
        addBuffer();
        addBuffer();

        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));
        assertEquals(2, groupCount(), "two groups side by side");
        assertEquals(2, depth(), "one branch above the leaves");

        // A new file opens in the focused group — the new right-hand one — giving it the second tab a
        // further split needs. (Moving the active tab on would instead send it back and collapse the group.)
        addBuffer();
        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.VERTICAL));

        assertEquals(3, groupCount(), "three groups");
        assertEquals(3, depth(), "the vertical split nests inside the horizontal one");

        cleanUp();
    }

    /**
     * Splitting the same way twice must widen the existing branch rather than nest another inside it —
     * otherwise repeated "split right" builds a right-leaning chain of two-way splits that is awkward to
     * traverse, to persist, and to drag into.
     */
    @Test
    void splittingTheSameWayTwiceAddsASiblingRatherThanNesting() throws Exception {
        addBuffer();
        addBuffer();

        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));
        addBuffer(); // opens in the newly focused group, so it has something to split off
        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));

        assertEquals(3, groupCount(), "three columns");
        assertEquals(2, depth(), "still a single branch — no nesting for a same-orientation split");

        cleanUp();
    }

    /**
     * Emptying a nested group must not leave a one-item {@code SplitPane} behind. Such a branch is invisible
     * but still takes part in layout, and every traversal has to keep stepping through it.
     */
    @Test
    void collapsingANestedGroupRemovesTheRedundantBranch() throws Exception {
        addBuffer();
        addBuffer();

        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));
        addBuffer(); // opens in the newly focused group, so it has something to split off
        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.VERTICAL));
        assertEquals(3, depth(), "nested to start with");

        // Close the file in the innermost group: its group goes, and so must the branch that held it.
        Tab innermost = FxTestSupport.callOnFx(() -> area.selectedTab());
        FxTestSupport.runOnFx(() -> area.remove(innermost));

        assertEquals(2, groupCount(), "two groups left");
        assertEquals(2, depth(), "the emptied branch collapsed away instead of lingering with one child");

        cleanUp();
    }

    /**
     * Closing the last file with the tab's own ✕ must collapse the group too.
     *
     * <p>A distinct path from {@link #closingTheLastTabInAGroupCollapsesThatGroup}, and it needs its own test
     * because the ✕ never reaches {@code EditorArea.remove}: {@code Tab.onCloseRequest} fires and then
     * <em>JavaFX itself</em> removes the tab from the pane, so the group empties without this class being
     * told. Stale empty groups accumulated as a result — found by reading a real session file that had
     * recorded five groups for two open files, not by any test, because the existing one drove the command
     * path (the API I had written) rather than the one a user actually takes.
     *
     * <p>Removing straight from the pane's tab list is exactly what the close button does.
     */
    @Test
    void closingATabWithItsCloseButtonAlsoCollapsesTheGroup() throws Exception {
        addBuffer();
        Tab moved = addBuffer();
        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));
        assertEquals(2, groupCount(), "split first");

        // Bypass EditorArea entirely, as the ✕ does.
        FxTestSupport.runOnFx(() -> moved.getTabPane().getTabs().remove(moved));
        FxTestSupport.runOnFx(() -> {}); // let the deferred collapse run on the next pulse

        assertEquals(1, groupCount(), "the emptied group collapsed even though remove() was never called");
        assertEquals(1, FxTestSupport.callOnFx(() -> area.size()), "the other file is untouched");

        cleanUp();
    }

    /**
     * The saved selection index must count only the tabs the session will actually write, not every tab in
     * the group.
     *
     * <p>Editora does not persist a tab with no path — the Welcome tab, an unsaved buffer — but such a tab
     * still occupies a slot. Recording the live index saved a number in one coordinate system and restored it
     * in another: a group of {@code [unsaved, a, b]} with {@code b} selected saved index 2 and, restoring into
     * a two-tab group, selected {@code a}. Found in a real session file, where a one-file group had recorded
     * {@code selected: 1}; the restore clamp masked it that time, which is why the symptom would have surfaced
     * as "sometimes focuses the wrong file" rather than an obvious break.
     */
    @Test
    void theSavedSelectionCountsOnlyPersistedTabs() throws Exception {
        Tab unsaved = addBuffer(); // no path — exactly what persistSession skips
        Tab first = addBuffer();
        Tab second = addBuffer();
        FxTestSupport.runOnFx(() -> {
            // Force all three into one group beside another, so the layout is split and gets recorded.
            area.splitActive(Orientation.HORIZONTAL);
            area.unsplit();
        });
        addBuffer();
        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));
        FxTestSupport.runOnFx(() -> {
            area.select(second); // the last of the three in group 0
        });

        // Persist as the session does: skip the path-less tab.
        EditorGroupLayout saved = FxTestSupport.callOnFx(() -> area.snapshotLayout(t -> t != unsaved));
        List<EditorGroupLayout> leaves = new ArrayList<>();
        collectLeaves(saved, leaves);

        int groupOfSecond = FxTestSupport.callOnFx(() -> area.groupIndexOf(second));
        int liveIndex = FxTestSupport.callOnFx(() -> {
            for (Tab t : area.tabs()) {
                if (t == second) {
                    return area.indexOf(t);
                }
            }
            return -1;
        });
        int savedIndex = leaves.get(groupOfSecond).getSelected();

        assertTrue(
                liveIndex > savedIndex,
                "the live index counts the unsaved tab, the saved one must not" + " (live=" + liveIndex + ", saved="
                        + savedIndex + ")");
        assertEquals(liveIndex - 1, savedIndex, "exactly one skipped tab sits before the selection");

        cleanUp();
    }

    private static void collectLeaves(EditorGroupLayout node, List<EditorGroupLayout> out) {
        if (node.isLeaf()) {
            out.add(node);
            return;
        }
        for (EditorGroupLayout child : node.getChildren()) {
            collectLeaves(child, out);
        }
    }

    /** An unsplit area writes no layout at all, so an unsplit session file is byte-identical to before. */
    @Test
    void anUnsplitAreaSavesNoLayout() throws Exception {
        addBuffer();
        addBuffer();

        assertNull(FxTestSupport.callOnFx(() -> area.snapshotLayout(t -> true)), "nothing to record while unsplit");

        cleanUp();
    }

    /**
     * The round trip a restart performs: save the shape, rebuild it, put each file back in the group its
     * saved index names, and reapply the per-group selections. Exercised at the {@code EditorArea} level so
     * the layout format itself is under test, not just the controller's use of it.
     */
    @Test
    void theSplitLayoutSurvivesASaveAndRestore() throws Exception {
        addBuffer();
        addBuffer();
        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));
        addBuffer();
        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.VERTICAL));

        EditorGroupLayout saved = FxTestSupport.callOnFx(() -> area.snapshotLayout(t -> true));
        assertEquals(3, saved.leafCount(), "three groups recorded");
        // Which group each file was in, exactly as persistSession records it on OpenFile.group.
        List<Integer> savedGroups = new ArrayList<>();
        for (Tab tab : FxTestSupport.callOnFx(() -> new ArrayList<>(area.tabs()))) {
            savedGroups.add(FxTestSupport.callOnFx(() -> area.groupIndexOf(tab)));
        }
        List<Tab> savedTabs = FxTestSupport.callOnFx(() -> new ArrayList<>(area.tabs()));

        // Rebuild from the saved shape, then refill exactly the way openInitialBuffer does.
        FxTestSupport.runOnFx(() -> {
            area.unsplit();
            for (Tab tab : new ArrayList<>(area.tabs())) {
                area.remove(tab);
            }
            area.restoreLayout(saved);
            for (int i = 0; i < savedTabs.size(); i++) {
                area.addToGroup(savedGroups.get(i), savedTabs.get(i));
            }
            area.applyRestoredSelection(saved);
        });

        assertEquals(3, groupCount(), "the three groups came back");
        assertEquals(3, depth(), "and so did the nesting");
        for (int i = 0; i < savedTabs.size(); i++) {
            int expected = savedGroups.get(i);
            Tab tab = savedTabs.get(i);
            assertEquals(expected, FxTestSupport.callOnFx(() -> area.groupIndexOf(tab)), "file back in its group");
        }

        cleanUp();
    }

    /**
     * A file saved in a session can be gone by the next launch. A group that loses every one of its files
     * must not come back as a blank pane the user has to close by hand.
     */
    @Test
    void aGroupWhoseFilesAllVanishedIsPrunedOnRestore() throws Exception {
        addBuffer();
        addBuffer();
        FxTestSupport.runOnFx(() -> area.splitActive(Orientation.HORIZONTAL));
        EditorGroupLayout saved = FxTestSupport.callOnFx(() -> area.snapshotLayout(t -> true));
        List<Tab> savedTabs = FxTestSupport.callOnFx(() -> new ArrayList<>(area.tabs()));

        FxTestSupport.runOnFx(() -> {
            area.unsplit();
            for (Tab tab : new ArrayList<>(area.tabs())) {
                area.remove(tab);
            }
            area.restoreLayout(saved);
            area.addToGroup(0, savedTabs.get(0)); // only group 0's file still exists
            area.pruneEmptyGroups();
            area.applyRestoredSelection(saved);
        });

        assertEquals(1, groupCount(), "the group with no surviving files was dropped");
        assertEquals(1, FxTestSupport.callOnFx(() -> area.size()), "and the surviving file is still open");

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

    /** How deeply the group tree nests — 1 unsplit, 2 after one split, 3 once a split nests inside another. */
    private int depth() throws Exception {
        return FxTestSupport.callOnFx(() -> area.depth());
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
