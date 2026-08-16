package com.editora.ui;

import java.util.List;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The language-server actions live under one LSP submenu in the editor's right-click menu.
 *
 * <p>They used to sit flat in the menu, where a served buffer's seven of them pushed cut/copy/paste and the
 * spelling suggestions far enough down to hunt for.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LspContextMenuFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** Builds the submenu the context menu inserts, optionally with every optional action advertised. */
    private static Menu lspMenu(boolean allCapabilities) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLspActive(true);
        if (allCapabilities) {
            buffer.setLspImplementationAvailable(true);
            buffer.setLspTypeDefinitionAvailable(true);
            buffer.setLspCodeActionsAvailable(true);
            buffer.setLspRenameAvailable(true);
            buffer.setLspFormatAvailable(true);
        }
        return (Menu) FxTestSupport.call(buffer, "lspMenu", new Class[] {int.class}, 0);
    }

    private static List<String> labels(Menu menu) {
        return menu.getItems().stream().map(MenuItem::getText).toList();
    }

    @Test
    void theActionsAreGroupedUnderAnLspSubmenu() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Menu menu = lspMenu(true);

            assertEquals(tr("editmenu.lsp"), menu.getText());
            assertNotNull(menu.getGraphic(), "context-menu entries carry a leading glyph");
            assertTrue(menu.getItems().size() >= 3, "the submenu should hold the LSP actions, not be empty");
        });
    }

    /** Every action a server advertises is inside the submenu — nothing was dropped by the regrouping. */
    @Test
    void theSubmenuHoldsEveryAdvertisedAction() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> labels = labels(lspMenu(true));

            for (String key : List.of(
                    "command.lsp.gotoDefinition",
                    "command.lsp.gotoImplementation",
                    "command.lsp.gotoTypeDefinition",
                    "command.lsp.findReferences",
                    "command.lsp.hover",
                    "command.lsp.codeActions",
                    "command.lsp.rename",
                    "command.lsp.formatDocument")) {
                assertTrue(labels.contains(tr(key)), tr(key) + " is missing from the LSP submenu");
            }
        });
    }

    /** A server advertising nothing optional still gets the three unconditional navigation actions. */
    @Test
    void aServerWithNoOptionalCapabilitiesStillGetsTheNavigationActions() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertEquals(
                    List.of(
                            tr("command.lsp.gotoDefinition"),
                            tr("command.lsp.findReferences"),
                            tr("command.lsp.hover")),
                    labels(lspMenu(false)));
        });
    }

    /** Every item inside carries its own glyph, as every context-menu entry in the app does. */
    @Test
    void everyItemInTheSubmenuHasAnIcon() throws Exception {
        FxTestSupport.runOnFx(() -> {
            for (MenuItem item : lspMenu(true).getItems()) {
                assertNotNull(item.getGraphic(), item.getText() + " has no icon");
            }
        });
    }
}
