package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A context menu costs nothing until it is opened, and is complete when it is. A {@link ContextMenu} is a popup
 * window of its own, and a new window used to make about forty (one per tool-window stripe button, two per tab, the
 * toolbar's, the breakpoint gutter's, the debugger's value popup) before its first paint.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LazyContextMenuFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void itemsAreBuiltOnceOnTheFirstShowingAndTheBuildersRefreshRunsForIt() throws Exception {
        AtomicInteger builds = new AtomicInteger();
        AtomicInteger refreshes = new AtomicInteger();
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            ContextMenu menu = FxTestSupport.callOnFx(() -> LazyContextMenu.of(m -> {
                builds.incrementAndGet();
                m.getItems().setAll(new MenuItem("one"), new MenuItem("two"));
                m.setOnShowing(e -> refreshes.incrementAndGet());
            }));
            assertEquals(0, builds.get(), "nothing is built for a menu nobody opened");
            assertTrue(FxTestSupport.callOnFx(() -> LazyContextMenu.isUnbuilt(menu)));

            TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");
            FxTestSupport.runOnFx(() -> menu.show(tabPane, 10, 10));
            assertEquals(1, builds.get());
            assertEquals(1, refreshes.get(), "the builder's own onShowing must see the first showing too");
            assertEquals(
                    List.of("one", "two"),
                    FxTestSupport.callOnFx(() ->
                            menu.getItems().stream().map(MenuItem::getText).toList()));
            assertTrue(FxTestSupport.callOnFx(menu::isShowing));

            FxTestSupport.runOnFx(() -> {
                menu.hide();
                menu.show(tabPane, 10, 10);
                menu.hide();
            });
            assertEquals(1, builds.get(), "built once");
            assertEquals(2, refreshes.get());
        } finally {
            fx.dispose();
        }
    }

    @Test
    void aFileTabsMenuIsUnbuiltUntilShownThenCarriesItsItems() throws Exception {
        Path dir = Files.createTempDirectory("editora-tab-menu");
        Path file = Files.writeString(dir.resolve("a.txt"), "text\n");
        FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");
            assertTrue(waitUntil(() -> tabOf(tabPane, file) != null
                    && !((EditorBuffer) tabOf(tabPane, file).getUserData()).isLoading()));
            Tab tab = FxTestSupport.callOnFx(() -> tabOf(tabPane, file));
            assertNull(FxTestSupport.callOnFx(tab::getContextMenu), "no menu before the header is right-clicked");
            ContextMenu menu = FxTestSupport.callOnFx(() -> {
                Node header = header(tabPane, tab);
                Event.fireEvent(header, rightClick(header));
                return tab.getContextMenu();
            });
            assertNotNull(menu, "a right-click on the header makes it");
            FxTestSupport.runOnFx(menu::hide);

            FxTestSupport.runOnFx(() -> menu.show(tabPane, 10, 10));
            List<MenuItem> items = FxTestSupport.callOnFx(() -> List.copyOf(menu.getItems()));
            assertEquals(tr("menu.save"), items.get(0).getText());
            assertTrue(items.get(0).isDisable(), "Save is disabled for a clean on-disk file on the first showing");
            assertTrue(items.stream().anyMatch(i -> tr("menu.rename").equals(i.getText())));
            assertTrue(items.stream().anyMatch(i -> tr("project.menu.git").equals(i.getText())));
            assertFalse(FxTestSupport.callOnFx(() -> LazyContextMenu.isUnbuilt(menu)));
            FxTestSupport.runOnFx(menu::hide);
        } finally {
            fx.dispose();
        }
    }

    @Test
    void aFreshWindowMakesNoContextMenuUntilOneIsAskedFor() throws Exception {
        Path dir = Files.createTempDirectory("editora-lazy-menus");
        Path file = Files.writeString(dir.resolve("Sample.java"), "class Sample {}\n");
        try (FxWindowFixture fx = FxWindowFixture.create(
                dir, false, false, false, List.of(new MainController.OpenTarget(file, -1, -1)), true, c -> {})) {
            TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");
            assertTrue(waitUntil(() -> tabOf(tabPane, file) != null));
            FxTestSupport.drainFx();
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            List<String> made = FxTestSupport.callOnFx(() -> {
                List<String> found = new java.util.ArrayList<>();
                for (Node n : stage.getScene().getRoot().lookupAll("*")) {
                    if (n instanceof Control c && c.getContextMenu() != null) {
                        found.add(c.toString());
                    }
                    if (n instanceof TabPane pane) {
                        pane.getTabs().stream()
                                .filter(t -> t.getContextMenu() != null)
                                .forEach(t -> found.add("tab " + t.getText()));
                    }
                }
                return found;
            });
            assertTrue(
                    made.isEmpty(),
                    () -> made.size() + " context menu(s) made before any right-click; give the control its menu"
                            + " with LazyContextMenu.install instead:\n" + String.join("\n", made));
            DebugCoordinator debug = FxTestSupport.field(fx.controller, "debugCoordinator");
            assertNull(FxTestSupport.field(debug, "valuePopup"), "the debugger's value popup waits for a value");
            assertNull(FxTestSupport.field(debug, "gutterMenu"), "the breakpoint menu waits for a breakpoint");
            Object toolbar = FxTestSupport.field(fx.controller, "toolbarCoordinator");
            assertNull(FxTestSupport.field(toolbar, "contextMenu"), "the toolbar's menu waits for a right-click");

            // A stripe button's menu comes with the first right-click, and is the same one after that.
            ToolWindowManager toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
            java.util.Map<?, Button> buttons = FxTestSupport.field(toolWindows, "stripeButtons");
            FxTestSupport.runOnFx(() -> {
                Button button = buttons.values().stream()
                        .filter(b -> b.getScene() == stage.getScene() && b.isVisible())
                        .findFirst()
                        .orElseThrow();
                assertNull(button.getContextMenu());
                Event.fireEvent(button, rightClick(button));
                ContextMenu menu = button.getContextMenu();
                assertNotNull(menu, "the right-click makes the menu");
                assertTrue(menu.isShowing(), "and the button shows it, as before");
                assertEquals(
                        List.of(tr("toolwindow.openInSplit"), tr("toolwindow.hide")),
                        menu.getItems().stream().map(MenuItem::getText).toList());
                menu.hide();
                Event.fireEvent(button, rightClick(button));
                assertSame(menu, button.getContextMenu(), "made once");
                menu.hide();
            });
        }
    }

    private static Node header(TabPane tabPane, Tab tab) {
        for (Node n : tabPane.lookupAll(".tab")) {
            if (n.getProperties().get(Tab.class) == tab) {
                return n;
            }
        }
        throw new AssertionError("no header for " + tab.getText());
    }

    private static ContextMenuEvent rightClick(Node on) {
        javafx.geometry.Point2D p = on.localToScreen(2, 2);
        return new ContextMenuEvent(
                ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                2,
                2,
                p == null ? 0 : p.getX(),
                p == null ? 0 : p.getY(),
                false,
                null);
    }

    private static Tab tabOf(TabPane tabPane, Path file) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab.getUserData() instanceof EditorBuffer buffer && file.equals(buffer.getPath())) {
                return tab;
            }
        }
        return null;
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        return OnFx.holdsWithin(condition::getAsBoolean, java.time.Duration.ofSeconds(5));
    }
}
