package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A tab's context menu costs nothing until it is opened, and is complete when it is. */
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
            ContextMenu menu = FxTestSupport.callOnFx(tab::getContextMenu);
            assertTrue(FxTestSupport.callOnFx(() -> LazyContextMenu.isUnbuilt(menu)), "no items before first use");

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

    private static Tab tabOf(TabPane tabPane, Path file) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab.getUserData() instanceof EditorBuffer buffer && file.equals(buffer.getPath())) {
                return tab;
            }
        }
        return null;
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 250; i++) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
