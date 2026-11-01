package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Map's rows as a tree for assistive technology ({@link MapAccessibleRows}, Map explore position 14), without a
 * map: the tree view's count, rows by index, selection and focus; each row's attributes, parent and children among
 * the rows on screen, and screen bounds; its actions; and a row that stays on screen keeping its node.
 */
@Tag("fx")
class MapAccessibleRowsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final Path ROOT = Path.of("/project");
    private static final Path SRC = ROOT.resolve("src");
    private static final Path APP = SRC.resolve("App.java");
    private static final Path LIB = SRC.resolve("Lib.java");
    private static final Path README = ROOT.resolve("README.md");

    /** What the rows asked of the map, in order. */
    private static final class Recorder implements MapAccessibleRows.Actions {
        final List<String> calls = new ArrayList<>();
        boolean offScreen;

        @Override
        public void select(Path path) {
            calls.add("select " + path.getFileName());
        }

        @Override
        public void activate(Path path) {
            calls.add("activate " + path.getFileName());
        }

        @Override
        public void expand(Path path, boolean expand) {
            calls.add((expand ? "expand " : "collapse ") + path.getFileName());
        }

        @Override
        public @Nullable Bounds toScreen(double x, double y, double width, double height) {
            return offScreen ? null : new BoundingBox(x + 100, y + 200, width, height);
        }
    }

    private static MapAccessibleRows.Spec spec(
            Path path, @Nullable Path parent, String name, int level, boolean selected, boolean expanded) {
        return new MapAccessibleRows.Spec(
                path, parent, name, level, selected, expanded, !expanded && level > 1, 10, 20 * level, 180, 24);
    }

    private static List<MapAccessibleRows.Spec> screen(boolean appSelected, boolean srcExpanded, String appName) {
        return List.of(
                spec(SRC, null, "src, folder", 1, false, srcExpanded),
                spec(README, null, "README.md, file", 1, false, false),
                spec(APP, SRC, appName, 2, appSelected, false),
                spec(LIB, SRC, "Lib.java, file", 2, false, false));
    }

    @Test
    void theTreeViewAnswersItsCountRowsSelectionAndFocus() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MapAccessibleRows rows = new MapAccessibleRows(new Recorder());
            assertEquals(AccessibleRole.TREE_VIEW, rows.getAccessibleRole());
            assertEquals(0, rows.queryAccessibleAttribute(AccessibleAttribute.ROW_COUNT));
            assertNull(rows.queryAccessibleAttribute(AccessibleAttribute.FOCUS_ITEM), "nothing on screen");

            MapAccessibleRows.Row selected = rows.update(screen(true, true, "App.java, file"));
            assertEquals(4, rows.queryAccessibleAttribute(AccessibleAttribute.ROW_COUNT));
            assertSame(rows.rows().get(2), selected);
            assertSame(rows.rows().get(1), rows.queryAccessibleAttribute(AccessibleAttribute.ROW_AT_INDEX, 1));
            assertNull(rows.queryAccessibleAttribute(AccessibleAttribute.ROW_AT_INDEX, 9));
            assertNull(rows.queryAccessibleAttribute(AccessibleAttribute.ROW_AT_INDEX, -1));
            assertNull(rows.queryAccessibleAttribute(AccessibleAttribute.ROW_AT_INDEX), "no index asked");
            assertNull(rows.queryAccessibleAttribute(AccessibleAttribute.ROW_AT_INDEX, "1"), "not an index");
            assertEquals(List.of(selected), rows.queryAccessibleAttribute(AccessibleAttribute.SELECTED_ITEMS));
            assertSame(selected, rows.queryAccessibleAttribute(AccessibleAttribute.FOCUS_ITEM));
            assertEquals(false, rows.queryAccessibleAttribute(AccessibleAttribute.MULTIPLE_SELECTION));
            assertEquals(true, rows.queryAccessibleAttribute(AccessibleAttribute.VISIBLE));
            assertEquals(AccessibleRole.TREE_VIEW, rows.queryAccessibleAttribute(AccessibleAttribute.ROLE));
            assertSame(selected, rows.row(APP));
            assertNull(rows.row(null));
            assertNull(rows.row(ROOT.resolve("elsewhere")));

            assertNull(rows.update(screen(false, true, "App.java, file")), "the selection scrolled off screen");
            assertEquals(List.of(), rows.queryAccessibleAttribute(AccessibleAttribute.SELECTED_ITEMS));
            assertNull(rows.queryAccessibleAttribute(AccessibleAttribute.FOCUS_ITEM));
        });
    }

    @Test
    void eachRowIsATreeItemWithItsStatesItsParentAndItsChildrenOnScreen() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Recorder actions = new Recorder();
            MapAccessibleRows rows = new MapAccessibleRows(actions);
            rows.update(screen(true, true, "App.java, file, unsaved"));
            MapAccessibleRows.Row src = rows.row(SRC);
            MapAccessibleRows.Row app = rows.row(APP);
            MapAccessibleRows.Row lib = rows.row(LIB);
            assertEquals(AccessibleRole.TREE_ITEM, app.getAccessibleRole());
            assertEquals("App.java, file, unsaved", app.queryAccessibleAttribute(AccessibleAttribute.TEXT));
            assertEquals(true, app.queryAccessibleAttribute(AccessibleAttribute.SELECTED));
            assertEquals(true, app.queryAccessibleAttribute(AccessibleAttribute.FOCUSED));
            assertEquals(false, lib.queryAccessibleAttribute(AccessibleAttribute.FOCUSED));
            assertEquals(true, src.queryAccessibleAttribute(AccessibleAttribute.EXPANDED));
            assertEquals(false, src.queryAccessibleAttribute(AccessibleAttribute.LEAF));
            assertEquals(true, app.queryAccessibleAttribute(AccessibleAttribute.LEAF));
            assertEquals(2, app.queryAccessibleAttribute(AccessibleAttribute.DISCLOSURE_LEVEL));
            assertEquals(2, app.queryAccessibleAttribute(AccessibleAttribute.INDEX));
            assertEquals(true, app.queryAccessibleAttribute(AccessibleAttribute.VISIBLE));
            assertSame(src, app.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_PARENT));
            assertNull(src.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_PARENT), "a first-column row");
            assertEquals(2, src.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_COUNT));
            assertEquals(0, app.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_COUNT));
            assertSame(lib, src.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_AT_INDEX, 1));
            assertNull(src.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_AT_INDEX, 2));
            assertNull(src.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_AT_INDEX, -1));
            assertNull(src.queryAccessibleAttribute(AccessibleAttribute.TREE_ITEM_AT_INDEX), "no index asked");
            assertEquals(new BoundingBox(110, 240, 180, 24), app.queryAccessibleAttribute(AccessibleAttribute.BOUNDS));
            actions.offScreen = true;
            assertEquals(
                    new BoundingBox(0, 0, 0, 0),
                    app.queryAccessibleAttribute(AccessibleAttribute.BOUNDS),
                    "no screen yet: empty bounds, never null");
            assertEquals(AccessibleRole.TREE_ITEM, app.queryAccessibleAttribute(AccessibleAttribute.ROLE));
            assertEquals("Row[App.java, file, unsaved]", app.toString());
            assertSame(app.spec(), rows.row(APP).spec());
        });
    }

    @Test
    void aRowsActionsSelectActivateExpandAndCollapseItsPath() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Recorder actions = new Recorder();
            MapAccessibleRows rows = new MapAccessibleRows(actions);
            rows.update(screen(false, false, "App.java, file"));
            MapAccessibleRows.Row src = rows.row(SRC);
            src.executeAccessibleAction(AccessibleAction.REQUEST_FOCUS);
            src.executeAccessibleAction(AccessibleAction.FIRE);
            src.executeAccessibleAction(AccessibleAction.EXPAND);
            src.executeAccessibleAction(AccessibleAction.COLLAPSE);
            src.executeAccessibleAction(AccessibleAction.DECREMENT); // not a row's: handed to the node
            assertEquals(List.of("select src", "activate src", "expand src", "collapse src"), actions.calls);
        });
    }

    @Test
    void aRowStayingOnScreenKeepsItsNodeAndRowsThatLeaveAreDropped() throws Exception {
        FxTestSupport.runOnFx(() -> {
            MapAccessibleRows rows = new MapAccessibleRows(new Recorder());
            rows.update(screen(false, false, "App.java, file"));
            MapAccessibleRows.Row app = rows.row(APP);
            MapAccessibleRows.Row src = rows.row(SRC);
            long before = rows.rebuildsForTest;

            // Selected, expanded and renamed: the same nodes, told what changed.
            rows.update(screen(true, true, "App.java, file, unsaved"));
            assertSame(app, rows.row(APP), "a reader's place survives the change");
            assertSame(src, rows.row(SRC));
            assertEquals(true, app.queryAccessibleAttribute(AccessibleAttribute.SELECTED));
            assertEquals(true, src.queryAccessibleAttribute(AccessibleAttribute.EXPANDED));
            assertEquals("App.java, file, unsaved", app.queryAccessibleAttribute(AccessibleAttribute.TEXT));
            List<MapAccessibleRows.Row> order = rows.rows();
            rows.update(screen(true, true, "App.java, file, unsaved"));
            assertSame(order, rows.rows(), "nothing changed: the children are left alone");
            assertEquals(before + 2, rows.rebuildsForTest);

            rows.update(List.of(spec(README, null, "README.md, file", 1, true, false)));
            assertEquals(1, rows.getChildren().size());
            assertNull(rows.row(APP), "a row off screen has no node");
            assertNull(app.getParent(), "its node left the scene");
            assertFalse(rows.getChildren().contains(src));
            assertTrue(rows.isMouseTransparent(), "it never takes the mouse");
        });
    }
}
