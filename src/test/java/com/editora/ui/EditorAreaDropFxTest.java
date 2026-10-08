package com.editora.ui;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dropping a dragged tab onto an editor group: the middle moves it into the group, an edge splits the group
 * that way, the indicator shows which, and a drop that would change nothing is refused.
 */
@Tag("fx")
class EditorAreaDropFxTest {

    private static final double W = 800;
    private static final double H = 600;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** An 800x600 editor area holding tabs A, B and C in one group, with a settable "tab being dragged". */
    private static final class Rig {
        final TabPane primary = new TabPane();
        final Tab a = tab("A");
        final Tab b = tab("B");
        final Tab c = tab("C");
        final EditorArea area;
        final Region indicator;
        final Stage stage = new Stage();
        Tab dragged;

        Rig() {
            primary.getTabs().addAll(a, b, c);
            area = new EditorArea(primary);
            area.setDraggedTabSource(() -> dragged);
            indicator = FxTestSupport.field(area, "dropIndicator");
            StackPane root = new StackPane(area.node());
            stage.setScene(new Scene(root, W, H));
            stage.show();
            layout();
        }

        void layout() {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
        }

        @SuppressWarnings("unchecked")
        List<TabPane> groups() {
            return (List<TabPane>) FxTestSupport.call(area, "orderedGroups", new Class<?>[0]);
        }

        List<List<String>> layoutOfTabs() {
            return groups().stream()
                    .map(g -> g.getTabs().stream().map(Tab::getText).toList())
                    .toList();
        }

        DragEvent over(TabPane group, double x, double y) {
            DragEvent e = event(DragEvent.DRAG_OVER, x, y);
            group.getOnDragOver().handle(e);
            return e;
        }

        DragEvent drop(TabPane group, double x, double y) {
            DragEvent e = event(DragEvent.DRAG_DROPPED, x, y);
            group.getOnDragDropped().handle(e);
            layout();
            return e;
        }

        /** The indicator's rectangle as {@code x,y,w,h}, in whole pixels. */
        String indicatorBox() {
            return Math.round(indicator.getLayoutX()) + "," + Math.round(indicator.getLayoutY()) + ","
                    + Math.round(indicator.getWidth()) + "," + Math.round(indicator.getHeight());
        }
    }

    private static Tab tab(String name) {
        return new Tab(name, new Label(name));
    }

    private static DragEvent event(javafx.event.EventType<DragEvent> type, double x, double y) {
        return new DragEvent(type, null, x, y, x, y, TransferMode.MOVE, null, null, null);
    }

    @Test
    void withNothingBeingDraggedAGroupNeitherShowsATargetNorTakesTheDrop() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            DragEvent over = r.over(r.primary, 780, 300);
            assertFalse(over.isConsumed());
            assertFalse(r.indicator.isVisible());

            DragEvent drop = r.drop(r.primary, 780, 300);
            assertFalse(drop.isDropCompleted());
            assertTrue(drop.isConsumed());
            assertEquals(List.of(List.of("A", "B", "C")), r.layoutOfTabs());

            r.area.setDraggedTabSource(null);
            assertFalse(r.drop(r.primary, 780, 300).isDropCompleted(), "no drag source at all");
            r.stage.close();
        });
    }

    @Test
    void theIndicatorCoversTheHalfTheTabWouldSplitInto() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.dragged = r.a;

            assertTrue(r.over(r.primary, 790, 300).isConsumed());
            assertTrue(r.indicator.isVisible());
            assertEquals("400,0,400,600", r.indicatorBox(), "right edge: the right half");
            r.over(r.primary, 5, 300);
            assertEquals("0,0,400,600", r.indicatorBox(), "left edge: the left half");
            r.over(r.primary, 400, 5);
            assertEquals("0,0,800,300", r.indicatorBox(), "top edge: the top half");
            r.over(r.primary, 400, 595);
            assertEquals("0,300,800,300", r.indicatorBox(), "bottom edge: the bottom half");

            DragEvent middle = r.over(r.primary, 400, 300);
            assertFalse(middle.isConsumed(), "the middle of the tab's own group is where it already is");
            assertFalse(r.indicator.isVisible());

            r.over(r.primary, 790, 300);
            r.primary.getOnDragExited().handle(event(DragEvent.DRAG_EXITED, 900, 300));
            assertFalse(r.indicator.isVisible(), "the pointer left the group");
            r.stage.close();
        });
    }

    @Test
    void droppingOnAnEdgeSplitsTheGroupOnThatSide() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.dragged = r.a;
            r.over(r.primary, 790, 300);

            DragEvent drop = r.drop(r.primary, 790, 300);

            assertTrue(drop.isDropCompleted() && drop.isConsumed());
            assertFalse(r.indicator.isVisible());
            assertEquals(List.of(List.of("B", "C"), List.of("A")), r.layoutOfTabs(), "A is now on the right");
            assertSame(r.a, r.area.selectedTab(), "and is the active tab");

            r.stage.close();

            Rig left = new Rig();
            left.dragged = left.b;
            assertTrue(left.drop(left.primary, 5, 300).isDropCompleted());
            assertEquals(List.of(List.of("B"), List.of("A", "C")), left.layoutOfTabs(), "B is now on the left");
            assertEquals(
                    javafx.geometry.Orientation.HORIZONTAL,
                    ((javafx.scene.control.SplitPane) left.primary.getParent().getParent()).getOrientation());
            left.stage.close();

            Rig top = new Rig();
            top.dragged = top.c;
            assertTrue(top.drop(top.primary, 400, 5).isDropCompleted());
            assertEquals(List.of(List.of("C"), List.of("A", "B")), top.layoutOfTabs(), "C is now above");
            assertEquals(
                    javafx.geometry.Orientation.VERTICAL,
                    ((javafx.scene.control.SplitPane) top.primary.getParent().getParent()).getOrientation());
            top.stage.close();

            Rig bottom = new Rig();
            bottom.dragged = bottom.a;
            assertTrue(bottom.drop(bottom.primary, 400, 595).isDropCompleted());
            assertEquals(List.of(List.of("B", "C"), List.of("A")), bottom.layoutOfTabs(), "A is now below");
            bottom.stage.close();
        });
    }

    @Test
    void droppingInTheMiddleOfAnotherGroupMovesTheTabThereAndAnEmptiedGroupGoes() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.dragged = r.a;
            r.drop(r.primary, 790, 300);
            TabPane left = r.groups().get(0);
            TabPane right = r.groups().get(1);

            r.dragged = r.b;
            DragEvent over = r.over(right, right.getWidth() / 2, right.getHeight() / 2);
            assertTrue(over.isConsumed());
            assertEquals(
                    Math.round(right.getWidth()) + "," + Math.round(right.getHeight()),
                    Math.round(r.indicator.getWidth()) + "," + Math.round(r.indicator.getHeight()),
                    "the whole group is the target");
            assertTrue(
                    r.drop(right, right.getWidth() / 2, right.getHeight() / 2).isDropCompleted());
            assertEquals(List.of(List.of("C"), List.of("A", "B")), r.layoutOfTabs());

            r.dragged = r.c;
            assertTrue(
                    r.drop(right, right.getWidth() / 2, right.getHeight() / 2).isDropCompleted());
            assertEquals(List.of(List.of("A", "B", "C")), r.layoutOfTabs(), "the emptied group collapsed");
            assertFalse(r.area.isSplit());
            assertFalse(left.getTabs().contains(r.c));
            r.stage.close();
        });
    }

    @Test
    void aGroupsOnlyTabCannotBeSplitOffFromItself() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.primary.getTabs().removeAll(r.b, r.c);
            r.dragged = r.a;

            assertFalse(r.over(r.primary, 790, 300).isConsumed());
            assertFalse(r.indicator.isVisible());
            assertFalse(r.drop(r.primary, 790, 300).isDropCompleted(), "it would leave its group empty");
            assertEquals(List.of(List.of("A")), r.layoutOfTabs());
            r.stage.close();
        });
    }

    @Test
    void focusMovesToTheNextGroupInVisualOrderAndWraps() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            assertFalse(r.area.focusNextGroup(), "one group: nowhere to go");

            r.dragged = r.a;
            r.drop(r.primary, 790, 300); // [B, C] | [A], A's group focused
            assertSame(r.a, r.area.selectedTab());

            assertTrue(r.area.focusNextGroup());
            assertSame(r.b, r.area.selectedTab(), "wrapped round to the left group's selection");
            assertEquals(0, r.area.groupIndexOf(r.area.selectedTab()));
            assertTrue(r.area.focusNextGroup());
            assertSame(r.a, r.area.selectedTab());
            assertEquals(1, r.area.groupIndexOf(r.a));
            assertEquals(-1, r.area.groupIndexOf(new Tab("stranger")));
            r.stage.close();
        });
    }

    @Test
    void duringARestoreNewTabsGoToTheNamedGroupClampedIntoRange() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = new Rig();
            r.dragged = r.a;
            r.drop(r.primary, 790, 300); // [B, C] | [A]

            r.area.setRestoreTargetGroup(0);
            r.area.add(tab("D"));
            r.area.setRestoreTargetGroup(7);
            r.area.add(tab("E"));
            r.area.setRestoreTargetGroup(-1);
            r.area.add(tab("F"));

            assertEquals(
                    List.of(List.of("B", "C", "D"), List.of("A", "E", "F")),
                    r.layoutOfTabs(),
                    "group 0, a group past the end (the last), then the focused group again");
            r.stage.close();
        });
    }
}
