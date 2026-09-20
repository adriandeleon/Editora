package com.editora.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The editor context menu follows AI Actions' effective enabled-and-connected gate. */
@Tag("fx")
class AiContextMenuFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void submenuAppearsOnlyWhenAiActionsAreAvailable() throws Exception {
        FxTestSupport.runOnFx(() -> {
            try (MenuFixture fixture = new MenuFixture()) {
                fixture.buffer.getArea().selectRange(0, 4);
                assertFalse(fixture.open().stream().anyMatch(AiContextMenuFxTest::isAiMenu));

                fixture.buffer.setAiActionsEnabled(true);
                Menu ai = (Menu) fixture.open().stream()
                        .filter(AiContextMenuFxTest::isAiMenu)
                        .findFirst()
                        .orElseThrow();
                assertEquals(
                        List.of(tr("command.ai.explainSelection"), tr("command.ai.rewriteSelection")),
                        ai.getItems().stream().map(MenuItem::getText).toList());
                assertNotNull(ai.getGraphic());
                assertTrue(ai.getItems().stream().allMatch(item -> item.getGraphic() != null));
                assertTrue(ai.getItems().stream().noneMatch(MenuItem::isDisable));

                fixture.buffer.setAiActionsEnabled(false);
                assertFalse(fixture.open().stream().anyMatch(AiContextMenuFxTest::isAiMenu));
            }
        });
    }

    @Test
    void entriesRouteToTheInjectedActionsAndRequireASelection() throws Exception {
        FxTestSupport.runOnFx(() -> {
            try (MenuFixture fixture = new MenuFixture()) {
                AtomicInteger explainCalls = new AtomicInteger();
                AtomicInteger rewriteCalls = new AtomicInteger();
                fixture.buffer.setAiActionHandlers(explainCalls::incrementAndGet, rewriteCalls::incrementAndGet);
                fixture.buffer.setAiActionsEnabled(true);

                Menu withoutSelection = fixture.aiMenu();
                assertTrue(withoutSelection.getItems().stream().allMatch(MenuItem::isDisable));

                fixture.buffer.getArea().selectRange(0, 4);
                Menu withSelection = fixture.aiMenu();
                withSelection.getItems().get(0).fire();
                withSelection.getItems().get(1).fire();
                assertEquals(1, explainCalls.get());
                assertEquals(1, rewriteCalls.get());
            }
        });
    }

    private static boolean isAiMenu(MenuItem item) {
        return item instanceof Menu && tr("editmenu.aiActions").equals(item.getText());
    }

    private static final class MenuFixture implements AutoCloseable {
        private final EditorBuffer buffer = new EditorBuffer();
        private final Stage stage = new Stage();

        private MenuFixture() {
            buffer.setContent("code sample");
            stage.setScene(new Scene(new StackPane(buffer.getNode()), 600, 400));
            stage.show();
        }

        private List<MenuItem> open() {
            CodeArea area = buffer.getArea();
            Event.fireEvent(
                    area, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, false, null));
            ContextMenu menu = FxTestSupport.field(buffer, "contextMenu");
            List<MenuItem> items = List.copyOf(menu.getItems());
            menu.hide();
            return items;
        }

        private Menu aiMenu() {
            return (Menu) open().stream()
                    .filter(AiContextMenuFxTest::isAiMenu)
                    .findFirst()
                    .orElseThrow();
        }

        @Override
        public void close() {
            stage.hide();
            buffer.dispose();
        }
    }
}
