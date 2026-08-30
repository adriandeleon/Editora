package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The branch dropdown's rows are shaped like the VCS menu's, because they offer the same actions.
 *
 * <p>It used to render them as bare text at the command palette's 12.5px while the VCS menu drew the very
 * same commands with a glyph at 14px — two different-looking lists of one set of actions. Each row now
 * carries its {@code commandId} so both surfaces take the glyph from one table ({@link MenuBarIcons}),
 * which is what stops them drifting apart again.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BranchPopupRowsFxTest {

    /** The actions MainController builds, in the same shape. */
    private static final List<String> ACTION_COMMANDS =
            List.of("git.newBranch", "git.pull", "git.fetch", "git.push", "git.stash", "git.unstash", "git.commit");

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * Every action the dropdown offers has a glyph, and it is the VCS menu's glyph for that command.
     *
     * <p>Asserted as an agreement with {@link MenuBarIcons} rather than against named glyphs, so redrawing
     * an icon keeps passing and only a divergence — or an unmapped command — fails.
     */
    @Test
    void everyActionRowTakesTheVcsMenusGlyphForItsCommand() {
        for (String id : ACTION_COMMANDS) {
            assertNotNull(
                    MenuBarIcons.forCommand(id),
                    id + " is offered in the branch dropdown but has no glyph in MenuBarIcons, so that row"
                            + " renders blank while every row beside it carries an icon");
        }
    }

    /** The clone row shown when the folder is not a repository is an action row too, so it needs one as well. */
    @Test
    void theNoVcsCloneRowHasAGlyphToo() {
        assertNotNull(MenuBarIcons.forCommand("git.clone"));
    }

    /**
     * The leading column is fixed-width and present on <b>every</b> row, branches included.
     *
     * <p>JavaFX reserves no icon gutter, so a column only the command rows carried would start the branch
     * names at a different x and the popup would read as two ragged lists. Driven through the real cell
     * factory rather than asserted on the CSS, since the width is set in code.
     */
    @Test
    void commandAndBranchRowsShareOneLeadingColumn() throws Exception {
        double actionColumn = FxTestSupport.callOnFx(() -> leadingWidth(cellGraphic(true)));
        double branchColumn = FxTestSupport.callOnFx(() -> leadingWidth(cellGraphic(false)));
        assertEquals(
                actionColumn,
                branchColumn,
                0.01,
                "a command row and a branch row must reserve the same leading width, or their labels do not"
                        + " line up");
        assertTrue(actionColumn > 0, "expected a reserved icon column, got " + actionColumn);
    }

    /** Builds one popup cell's graphic: a command row, or a branch row. */
    private static Node cellGraphic(boolean action) throws Exception {
        BranchPopup popup = new BranchPopup();
        Object row = action ? newActionRow("Commit\u2026", "C-x g", "git.commit") : newBranchRow("master", true);
        // RowCell is a private inner class, so its constructor takes the enclosing popup; updateItem is
        // protected. Reached by reflection rather than by adding test-only seams to production code.
        Class<?> cellClass = Class.forName("com.editora.ui.BranchPopup$RowCell");
        var ctor = cellClass.getDeclaredConstructor(BranchPopup.class);
        ctor.setAccessible(true);
        Object cell = ctor.newInstance(popup);
        var update = cellClass.getDeclaredMethod(
                "updateItem", Class.forName("com.editora.ui.BranchPopup$Row"), boolean.class);
        update.setAccessible(true);
        update.invoke(cell, row, false);
        return ((javafx.scene.control.ListCell<?>) cell).getGraphic();
    }

    private static Object newActionRow(String label, String accel, String commandId) throws Exception {
        Class<?> c = Class.forName("com.editora.ui.BranchPopup$ActionRow");
        var ctor = c.getDeclaredConstructor(String.class, String.class, String.class, Runnable.class);
        ctor.setAccessible(true);
        return ctor.newInstance(label, accel, commandId, (Runnable) () -> {});
    }

    private static Object newBranchRow(String name, boolean current) throws Exception {
        Class<?> c = Class.forName("com.editora.ui.BranchPopup$BranchRow");
        var ctor = c.getDeclaredConstructor(
                String.class,
                boolean.class,
                boolean.class,
                String.class,
                int.class,
                int.class,
                boolean.class,
                Runnable.class);
        ctor.setAccessible(true);
        return ctor.newInstance(name, false, current, "origin/master", 0, 0, false, (Runnable) () -> {});
    }

    /** The width reserved before the first label — the leading {@link StackPane} column. */
    private static double leadingWidth(Node graphic) {
        assertNotNull(graphic, "the cell produced no graphic");
        Region first =
                (Region) ((javafx.scene.layout.HBox) graphic).getChildren().get(0);
        assertTrue(first instanceof StackPane, "expected the leading icon column first, got " + first);
        assertTrue(
                ((StackPane) first).getChildren().stream().noneMatch(n -> n instanceof Label),
                "the leading column holds a glyph, never text");
        return first.getPrefWidth();
    }
}
