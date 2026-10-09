package com.editora.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the menu-bar icon map honest against the menu it decorates.
 *
 * <p>A mapping for a command that is not in any menu is dead weight nobody will ever see, and — the reason
 * this test exists — a mapping whose id is a TYPO is indistinguishable from one: the item simply renders
 * with an empty icon column, which is a legitimate state for half the entries.
 */
class MenuBarIconsTest {

    private static List<String> menuCommandIds() {
        List<String> ids = new ArrayList<>();
        for (MenuBarModel.MenuSpec spec : MenuBarModel.menus()) {
            for (String entry : spec.allEntries()) {
                if (!MenuBarModel.SEPARATOR.equals(entry)) {
                    ids.add(entry);
                }
            }
        }
        return ids;
    }

    @Test
    void everyMappedCommandActuallyAppearsInAMenu() {
        Set<String> inMenus = new LinkedHashSet<>(menuCommandIds());
        List<String> orphans = MenuBarIcons.mappedCommandIds().stream()
                .filter(id -> !inMenus.contains(id))
                .sorted()
                .toList();
        assertTrue(orphans.isEmpty(), "mapped to an icon but in no menu: " + orphans);
    }

    @Test
    void aCommandWithNoFittingGlyphGetsNoneRatherThanAnInventedOne() {
        // An icon that does not depict its item has to be read and then discounted, which is worse than
        // the empty column the layout reserves anyway.
        assertFalse(menuCommandIds().isEmpty());
        for (String id : List.of("edit.toggleComment", "view.toggleWhitespace", "nav.goToLine", "lsp.codeActions")) {
            assertTrue(MenuBarIcons.forCommand(id) == null, id + " should not have been given a glyph");
        }
    }

    @Test
    void anUnknownCommandIsSimplyUnmapped() {
        assertTrue(MenuBarIcons.forCommand("no.such.command") == null);
        assertTrue(MenuBarIcons.forCommand("") == null);
    }

    /**
     * Git's history of a file wears the Git Log glyph in the menu bar, as it does in the tab and Project-tree
     * menus; the clock is Local History's. They used to share the clock.
     */
    @Test
    void gitHistoryAndLocalHistoryDoNotShareAnIcon() {
        assertEquals(shape(Icons.gitLog()), shape(MenuBarIcons.forCommand("git.fileHistory")));
        assertEquals(shape(Icons.history()), shape(MenuBarIcons.forCommand("tool.fileHistory")));
        assertFalse(shape(Icons.gitLog()).equals(shape(Icons.history())));
    }

    /** The path data of an icon node, wherever in the node it is kept. */
    private static String shape(javafx.scene.Node icon) {
        StringBuilder out = new StringBuilder();
        collect(icon, out);
        assertFalse(out.isEmpty(), "no path data found in " + icon);
        return out.toString();
    }

    private static void collect(javafx.scene.Node node, StringBuilder out) {
        if (node instanceof javafx.scene.shape.SVGPath path) {
            out.append(path.getContent());
        }
        if (node instanceof javafx.scene.layout.Region region
                && region.getShape() instanceof javafx.scene.shape.SVGPath path) {
            out.append(path.getContent());
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }
}
