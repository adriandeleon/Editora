package com.editora.ui;

import java.util.List;
import java.util.Set;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code buffer.togglePin} acts on whatever tab is selected, and that is not always a file: a fresh window
 * shows the Welcome tab. The command used to pin it and then throw while redrawing a header it does not have,
 * leaving a Welcome tab that Close All skipped.
 */
@Tag("fx")
class PinNonBufferTabFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void pinningTheWelcomeTabSaysThereIsNoFileAndPinsNothing() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            EditorArea editorArea = FxTestSupport.field(fx.controller, "editorArea");
            Set<Tab> pinned = FxTestSupport.field(fx.controller, "pinned");
            List<Tab> tabs = FxTestSupport.callOnFx(() -> List.copyOf(editorArea.tabs()));
            assertEquals(1, tabs.size(), "a fresh window shows one tab");
            assertFalse(tabs.get(0).getUserData() instanceof EditorBuffer, "and it is the Welcome tab, not a file");

            FxTestSupport.runOnFx(() -> registry.run("buffer.togglePin"));
            async.awaitFx();

            assertTrue(FxTestSupport.callOnFx(pinned::isEmpty), "the Welcome tab is not pinned");
            assertEquals(tr("status.noFileOpen"), FxTestSupport.callOnFx(() -> {
                StatusBar statusBar = FxTestSupport.field(fx.controller, "statusBar");
                Label echo = FxTestSupport.field(statusBar, "echo");
                return echo.getText();
            }));

            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(fx.controller, "closeAllTabs"));
            assertFalse(
                    FxTestSupport.callOnFx(() -> editorArea.tabs().contains(tabs.get(0))),
                    "so Close All still closes it");
        }
    }
}
