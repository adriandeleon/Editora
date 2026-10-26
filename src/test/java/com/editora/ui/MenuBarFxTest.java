package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.stage.Window;

import com.editora.command.KeymapManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the menu bar (#763): that it is actually in the window, that its items are wired to
 * real commands, that they carry the live keybinding, and that it obeys the chrome toggles.
 *
 * <p>{@link MenuBarModelTest} already proves the table names commands that exist. What it cannot prove is the
 * wiring — a menu whose items are all present but inert would pass every check there.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MenuBarFxTest {

    private FxWindowFixture fx;
    private MainMenuBar menuBar;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        menuBar = FxTestSupport.field(fx.controller, "menuBar");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private MenuBar bar() throws Exception {
        return FxTestSupport.callOnFx(() -> menuBar.node());
    }

    /**
     * Two rows that would read the same without their prefixes keep them: "Rerun Last" twice in Run, and "Find in
     * Files" in both Find and Window (fourth review EF1). Other rows still drop theirs.
     */
    @Test
    void rowsThatWouldReadTheSameKeepTheirPrefixes() throws Exception {
        java.util.Map<String, String> titles = FxTestSupport.callOnFx(
                () -> new java.util.HashMap<String, String>(FxTestSupport.field(menuBar, "menuTitles")));
        assertEquals(com.editora.i18n.Messages.tr("command.run.rerun"), titles.get("run.rerun"));
        assertEquals(com.editora.i18n.Messages.tr("command.maven.rerunLast"), titles.get("maven.rerunLast"));
        assertEquals(com.editora.i18n.Messages.tr("command.tool.search"), titles.get("tool.search"));
        java.util.Map<String, Long> counts = titles.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(t -> t, java.util.stream.Collectors.counting()));
        assertTrue(
                counts.entrySet().stream()
                        .filter(e -> e.getValue() > 1)
                        .allMatch(e -> e.getKey().contains(":")),
                "a shortened title is never shared: " + counts);
        assertEquals(
                com.editora.editor.MenuTitles.of(com.editora.i18n.Messages.tr("command.lsp.formatDocument")),
                titles.get("lsp.formatDocument"),
                "a row with no twin still drops its prefix");
    }

    @Test
    void theMenuBarIsBuiltAndInTheWindow() throws Exception {
        assertNotNull(menuBar, "the controller built a menu bar");
        MenuBar bar = bar();
        assertEquals(MenuBarModel.menus().size(), bar.getMenus().size(), "one menu per entry in the model");
        assertTrue(FxTestSupport.callOnFx(() -> bar.getScene() != null), "the menu bar is attached to the window");
    }

    /** Every non-separator item must be hooked to something; an inert menu is the failure this catches. */
    @Test
    void everyItemIsWiredToAnAction() throws Exception {
        MenuBar bar = bar();
        int wired = 0;
        java.util.ArrayDeque<Menu> menus = new java.util.ArrayDeque<>(bar.getMenus());
        while (!menus.isEmpty()) {
            for (MenuItem item : menus.poll().getItems()) {
                if (item instanceof SeparatorMenuItem) {
                    continue;
                }
                if (item instanceof Menu submenu) {
                    assertFalse(submenu.getText().isBlank(), "a submenu has no title");
                    assertFalse(submenu.getItems().isEmpty(), "an empty submenu: " + submenu.getText());
                    menus.add(submenu);
                    continue;
                }
                String text = MainMenuBar.displayTextOf(item);
                assertNotNull(item.getOnAction(), "menu item has no action: " + text);
                assertFalse(text.isBlank(), "menu item has no label");
                wired++;
            }
        }
        long modelled = MenuBarModel.menus().stream()
                .flatMap(m -> m.allEntries().stream())
                .filter(e -> !MenuBarModel.SEPARATOR.equals(e))
                .count();
        assertEquals(modelled, wired, "every modelled command became an item");
    }

    /**
     * Items show their keybinding, which is the menu's main teaching value. Checked against a command that is
     * bound in every bundled keymap so the assertion does not depend on which one is active.
     */
    @Test
    void itemsCarryTheLiveKeybinding() throws Exception {
        MenuBar bar = bar();
        MenuItem save = itemFor(bar, "file.save");
        assertNotNull(save, "File > Save is present");
        String text = MainMenuBar.displayTextOf(save);
        assertTrue(text.length() > "Save".length(), "the item shows a chord beside the title, was: " + text);
    }

    /**
     * A command whose feature is switched off is shown disabled rather than removed — the point of a menu is
     * to be a stable map. Git is off in the test fixture's default settings only if disabled, so this drives
     * the gate directly rather than assuming a default.
     */
    @Test
    void aGatedCommandIsDisabledRatherThanMissing() throws Exception {
        MenuBar bar = bar();
        MenuItem commit = itemFor(bar, "git.commit");
        assertNotNull(commit, "VCS > Commit is present regardless of whether Git is on");

        setFlag(s -> s.setGitSupport(false));
        assertTrue(commit.isDisable(), "with Git off the item is disabled, not hidden");

        setFlag(s -> s.setGitSupport(true));
    }

    /**
     * Simple UI mode strips chrome to a minimum, but the menu bar <em>stays</em> — it is the browsable map,
     * which a beginner-facing mode needs most. What changes is its contents: the reduced table, whose entries
     * are all still actionable in a mode with no LSP, VCS, debugging or tool windows.
     */
    @Test
    void simpleModeReducesTheMenuBarRatherThanHidingIt() throws Exception {
        MenuBar bar = bar();
        assertTrue(FxTestSupport.callOnFx(bar::isVisible), "visible by default");
        int full = FxTestSupport.callOnFx(() -> bar.getMenus().size());

        setFlag(s -> s.setSimpleMode(true));
        assertTrue(FxTestSupport.callOnFx(bar::isVisible), "still visible in Simple UI mode");
        assertEquals(
                MenuBarModel.menus(true).size(),
                (int) FxTestSupport.callOnFx(() -> bar.getMenus().size()),
                "rebuilt from the Simple-mode table");

        setFlag(s -> s.setSimpleMode(false));
        assertEquals(full, (int) FxTestSupport.callOnFx(() -> bar.getMenus().size()), "and back again");
    }

    /**
     * A submenu's arrow sits at the menu's right edge, past every chord. JavaFX places the arrow after the
     * widest plain label, and the command rows (custom items) take no part in that column, so a submenu
     * titled with its plain label had its arrow halfway across the menu.
     */
    @Test
    void submenuArrowsSitAtTheMenusRightEdge() throws Exception {
        Assumptions.assumeFalse(KeymapManager.isMac(), "the system menu bar draws its own arrows");
        MenuBar bar = bar();
        int checked = 0;
        java.util.List<Menu> menus = FxTestSupport.callOnFx(() -> java.util.List.copyOf(bar.getMenus()));
        for (Menu menu : menus) {
            if (FxTestSupport.callOnFx(() -> menu.getItems().stream().noneMatch(Menu.class::isInstance))) {
                continue;
            }
            FxTestSupport.runOnFx(menu::show);
            try {
                Parent popup = awaitPopup();
                checked += FxTestSupport.callOnFx(() -> {
                    double chordsEnd = 0;
                    for (Node chord : popup.lookupAll(".menu-item-chord")) {
                        if (chord.isVisible()) {
                            chordsEnd = Math.max(
                                    chordsEnd,
                                    chord.localToScene(chord.getLayoutBounds()).getMaxX());
                        }
                    }
                    int rows = 0;
                    for (Node n : popup.lookupAll(".menu-submenu")) {
                        if (!(n instanceof Parent row) || !row.getStyleClass().contains("menu-item")) {
                            continue;
                        }
                        Node arrow = row.lookup(".right-container");
                        double arrowEnd =
                                arrow.localToScene(arrow.getLayoutBounds()).getMaxX();
                        double rowEnd = row.localToScene(row.getLayoutBounds()).getMaxX();
                        String what = menu.getText() + " row at y=" + row.getLayoutY();
                        assertTrue(
                                arrowEnd > chordsEnd, what + ": arrow " + arrowEnd + " not past chords " + chordsEnd);
                        assertTrue(rowEnd - arrowEnd < 16, what + ": arrow " + arrowEnd + " far from edge " + rowEnd);
                        rows++;
                    }
                    return rows;
                });
            } finally {
                FxTestSupport.runOnFx(menu::hide);
            }
        }
        assertTrue(checked > 0, "some menu has a submenu to check");
    }

    /** The root of the showing menu popup, once it has been laid out. */
    private static Parent awaitPopup() throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            Parent root = FxTestSupport.callOnFx(() -> {
                for (Window w : Window.getWindows()) {
                    if (w instanceof ContextMenu && w.isShowing() && w.getScene() != null) {
                        Parent r = w.getScene().getRoot();
                        return r.lookup(".menu-submenu") == null ? null : r;
                    }
                }
                return null;
            });
            if (root != null) {
                FxTestSupport.drainFx();
                return root;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("the menu popup never showed");
    }

    /** Flips a setting on the controller's own config and re-applies the chrome, as a settings apply does. */
    private void setFlag(java.util.function.Consumer<com.editora.config.Settings> change) throws Exception {
        FxTestSupport.runOnFx(() -> {
            com.editora.config.ConfigManager cfg = FxTestSupport.field(fx.controller, "config");
            change.accept(cfg.getSettings());
            FxTestSupport.invoke(FxTestSupport.field(fx.controller, "chrome"), "applyChromeVisibility");
        });
    }

    /** The label of the item bound to {@code commandId}, found by its position in the model. */
    private MenuItem itemFor(MenuBar bar, String commandId) {
        for (int m = 0; m < MenuBarModel.menus().size(); m++) {
            MenuBarModel.MenuSpec spec = MenuBarModel.menus().get(m);
            Menu menu = bar.getMenus().get(m);
            MenuItem found = itemIn(spec.entries(), menu.getItems(), commandId);
            // A submenu sits at its inline entry, or else after the entries, below one separator, in order.
            int below = 0;
            for (int s = 0; found == null && s < spec.submenus().size(); s++) {
                MenuBarModel.MenuSpec nested = spec.submenus().get(s);
                int inline = spec.entries().indexOf(MenuBarModel.submenu(nested.titleKey()));
                int index = inline >= 0 ? inline : spec.entries().size() + 1 + below++;
                Menu submenu = (Menu) menu.getItems().get(index);
                found = itemIn(nested.entries(), submenu.getItems(), commandId);
            }
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static MenuItem itemIn(java.util.List<String> entries, java.util.List<MenuItem> items, String commandId) {
        int index = entries.indexOf(commandId);
        return index < 0 ? null : items.get(index);
    }
}
