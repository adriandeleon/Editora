package com.editora.ui;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of pinning + bulk-close through {@link MainController}: a pinned tab survives both
 * "Close Others" and "Close All" while unpinned tabs are removed.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TabManagementFxTest {

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
    void pinnedTabSurvivesCloseOthersAndCloseAll() throws Exception {
        TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");

        Tab a = addBuffer();
        Tab pinnedTab = addBuffer();
        Tab c = addBuffer();

        // Pin the middle tab.
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "togglePin", new Class[] {Tab.class}, pinnedTab));

        // Close Others (keep = a): everything except `a` and the pinned tab is closed.
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "closeOtherTabs", new Class[] {Tab.class}, a));
        assertTrue(FxTestSupport.callOnFx(() -> tabPane.getTabs().contains(a)), "kept tab survives Close Others");
        assertTrue(
                FxTestSupport.callOnFx(() -> tabPane.getTabs().contains(pinnedTab)),
                "pinned tab survives Close Others");
        assertFalse(FxTestSupport.callOnFx(() -> tabPane.getTabs().contains(c)), "unpinned non-kept tab closed");

        // Close All: only the pinned tab remains.
        FxTestSupport.runOnFx(() -> FxTestSupport.invoke(fx.controller, "closeAllTabs"));
        assertTrue(
                FxTestSupport.callOnFx(() -> tabPane.getTabs().contains(pinnedTab)), "pinned tab survives Close All");
        assertFalse(
                FxTestSupport.callOnFx(() -> tabPane.getTabs().contains(a)),
                "kept-but-unpinned tab closed by Close All");

        // Unpin + close so the shared fixture is left clean.
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.call(fx.controller, "togglePin", new Class[] {Tab.class}, pinnedTab);
            FxTestSupport.call(fx.controller, "closeTab", new Class[] {Tab.class}, pinnedTab);
        });
    }

    private Tab addBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            return (Tab) FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
        });
    }
}
