package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dropping a tab on a tab <em>header</em>, and pinning, once the editor area is split into groups.
 *
 * <p>A header takes a drop from any group, but the reorder behind it used to be written for one strip: it
 * removed the tab from its own group and re-inserted it into the <em>focused</em> group at the target's
 * index in the <em>target's</em> group. With the index out of range the insert threw and the tab — unsaved
 * text and all — was in no group at all.
 *
 * <p>A native drag cannot be started in a headless toolkit, so the tests hand a {@code DRAG_DROPPED} event to
 * the header's own handler, with the controller's "tab in flight" set as the drag-detected handler sets it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TabHeaderDropFxTest {

    private FxWindowFixture fx;
    private EditorArea area;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        area = FxTestSupport.field(fx.controller, "editorArea");
    }

    @BeforeEach
    void emptyTheArea() throws Exception {
        FxTestSupport.runOnFx(() -> {
            area.unsplit();
            pinned().clear(); // closing a pinned tab asks first, and a modal prompt would block the FX thread
            for (Tab tab : new ArrayList<>(area.tabs())) {
                if (tab.getUserData() instanceof EditorBuffer buffer) {
                    buffer.markClean();
                }
                FxTestSupport.call(fx.controller, "closeTab", new Class[] {Tab.class}, tab);
            }
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        emptyTheArea();
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void aTabDroppedAfterTheLastHeaderOfAnotherGroupJoinsThatGroup() throws Exception {
        Tab a1 = addBuffer();
        Tab a2 = addBuffer();
        List<Tab> b = splitOff(4);
        EditorBuffer unsaved = FxTestSupport.callOnFx(() -> (EditorBuffer) a1.getUserData());
        FxTestSupport.runOnFx(() -> {
            unsaved.getArea().appendText("UNSAVED");
            area.select(a1); // pressing a header selects its tab and focuses its group
            drop(a1, b.get(3), true);
        });

        assertEquals(List.of(List.of(a2), List.of(b.get(0), b.get(1), b.get(2), b.get(3), a1)), groups());
        assertFalse(unsaved.isDisposed(), "a move is not a close");
        assertTrue(unsaved.isDirty(), "the unsaved text travelled with the tab");
        assertSame(a1, FxTestSupport.callOnFx(() -> area.selectedTab()), "the dropped tab is the active one");
    }

    @Test
    void aTabDroppedBeforeAHeaderOfAnotherGroupLandsAtThatPosition() throws Exception {
        Tab a1 = addBuffer();
        Tab a2 = addBuffer();
        List<Tab> b = splitOff(3);
        FxTestSupport.runOnFx(() -> {
            area.select(a2);
            drop(a2, b.get(1), false);
        });

        assertEquals(List.of(List.of(a1), List.of(b.get(0), a2, b.get(1), b.get(2))), groups());
    }

    @Test
    void droppingAGroupsOnlyTabOnAnotherGroupCollapsesTheEmptiedGroup() throws Exception {
        Tab a1 = addBuffer();
        List<Tab> b = splitOff(2);
        FxTestSupport.runOnFx(() -> {
            area.select(a1);
            drop(a1, b.get(0), false);
        });

        assertEquals(List.of(List.of(a1, b.get(0), b.get(1))), groups());
    }

    @Test
    void aDropWithinOneGroupStillReordersIt() throws Exception {
        Tab a1 = addBuffer();
        Tab a2 = addBuffer();
        Tab a3 = addBuffer();
        FxTestSupport.runOnFx(() -> drop(a1, a3, true));
        assertEquals(List.of(List.of(a2, a3, a1)), groups());

        FxTestSupport.runOnFx(() -> drop(a1, a2, false));
        assertEquals(List.of(List.of(a1, a2, a3)), groups());
    }

    @Test
    void aDropFromAnotherGroupRespectsTheTargetStripsPinnedTabs() throws Exception {
        Tab a1 = addBuffer();
        Tab a2 = addBuffer();
        List<Tab> b = splitOff(3);
        FxTestSupport.runOnFx(() -> {
            togglePin(b.get(0));
            area.select(a2);
            drop(a2, b.get(0), false); // before the pinned tab: clamped to just after the pinned group
        });
        assertEquals(List.of(List.of(a1), List.of(b.get(0), a2, b.get(1), b.get(2))), groups());
    }

    @Test
    void pinningCountsThePinnedTabsOfTheTabsOwnGroup() throws Exception {
        Tab a1 = addBuffer();
        Tab a2 = addBuffer();
        Tab a3 = addBuffer();
        List<Tab> b = splitOff(3);
        FxTestSupport.runOnFx(() -> {
            togglePin(a1);
            togglePin(a2);
            togglePin(b.get(1)); // two tabs are pinned, but none of them in this strip
        });
        assertEquals(List.of(List.of(a1, a2, a3), List.of(b.get(1), b.get(0), b.get(2))), groups());

        // Unpinning from a group that is not the focused one keeps the tab in its own group.
        FxTestSupport.runOnFx(() -> {
            area.select(b.get(2));
            togglePin(a1);
        });
        assertEquals(List.of(List.of(a2, a1, a3), List.of(b.get(1), b.get(0), b.get(2))), groups());
    }

    @Test
    void pinningAGroupsOnlyTabKeepsTheGroup() throws Exception {
        Tab a1 = addBuffer();
        List<Tab> b = splitOff(1);
        FxTestSupport.runOnFx(() -> togglePin(b.get(0)));
        assertEquals(List.of(List.of(a1), List.of(b.get(0))), groups());
    }

    /** Opens {@code count} more buffers and moves them into a second group, to the right of the first. */
    private List<Tab> splitOff(int count) throws Exception {
        List<Tab> moved = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            moved.add(addBuffer());
        }
        FxTestSupport.runOnFx(() -> {
            area.select(moved.get(0));
            assertTrue(area.splitActive(Orientation.HORIZONTAL), "split");
            for (Tab tab : moved.subList(1, moved.size())) {
                area.select(tab);
                area.moveActiveToNextGroup();
            }
        });
        FxTestSupport.drainFx();
        return moved;
    }

    /** Delivers a drop of {@code dragged} on {@code target}'s header: its right half when {@code after}. */
    private void drop(Tab dragged, Tab target, boolean after) {
        try {
            java.lang.reflect.Field inFlight = MainController.class.getDeclaredField("draggedTab");
            inFlight.setAccessible(true);
            inFlight.set(fx.controller, dragged);
            Node header = target.getGraphic();
            double x = after ? header.getBoundsInLocal().getWidth() + 1000 : 0;
            header.getOnDragDropped()
                    .handle(new DragEvent(
                            header,
                            header,
                            DragEvent.DRAG_DROPPED,
                            null,
                            x,
                            5,
                            0,
                            0,
                            TransferMode.MOVE,
                            header,
                            header,
                            null));
            inFlight.set(fx.controller, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void togglePin(Tab tab) {
        FxTestSupport.call(fx.controller, "togglePin", new Class[] {Tab.class}, tab);
    }

    private Set<Tab> pinned() {
        return FxTestSupport.field(fx.controller, "pinned");
    }

    /** Every group's tabs, in visual order. */
    @SuppressWarnings("unchecked")
    private List<List<Tab>> groups() throws Exception {
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(() -> {
            List<List<Tab>> out = new ArrayList<>();
            for (TabPane group : (List<TabPane>) FxTestSupport.call(area, "orderedGroups", new Class<?>[] {})) {
                out.add(List.copyOf(group.getTabs()));
            }
            return out;
        });
    }

    private Tab addBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            return (Tab) FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
        });
    }
}
