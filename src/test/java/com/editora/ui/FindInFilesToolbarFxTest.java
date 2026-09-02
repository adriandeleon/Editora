package com.editora.ui;

import javafx.css.PseudoClass;
import javafx.scene.control.Button;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Find in Files has one visual toggle: the toolbar button, whose selected state tracks its tool window. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FindInFilesToolbarFxTest {

    private static final PseudoClass OPEN = PseudoClass.getPseudoClass("open");

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void toolbarButtonTracksEveryToolWindowOpenAndClose() throws Exception {
        ToolWindowManager manager = FxTestSupport.field(fx.controller, "toolWindows");
        ToolWindow search = FxTestSupport.field(fx.controller, "searchToolWindow");
        Button button = FxTestSupport.field(fx.controller, "findInFilesButton");

        FxTestSupport.runOnFx(() -> manager.close(search));
        assertFalse(FxTestSupport.callOnFx(() -> button.getPseudoClassStates().contains(OPEN)));

        FxTestSupport.runOnFx(() -> manager.open(search));
        assertTrue(FxTestSupport.callOnFx(() -> button.getPseudoClassStates().contains(OPEN)));

        FxTestSupport.runOnFx(() -> manager.close(search));
        assertFalse(FxTestSupport.callOnFx(() -> button.getPseudoClassStates().contains(OPEN)));
        assertTrue(manager.getRegisteredToolWindows().contains(search));
        assertFalse(manager.getStripeToolWindows().contains(search), "Search must not be offered on the stripe");
    }
}
