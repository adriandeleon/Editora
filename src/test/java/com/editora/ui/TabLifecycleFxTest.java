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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the tab/buffer lifecycle through {@link MainController}: a fresh window shows
 * the Welcome tab (no active buffer); {@code addBuffer} adds a real buffer tab that {@code activeBuffer}
 * tracks; and {@code closeTab} removes it.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TabLifecycleFxTest {

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
    void addBufferAddsTrackedTabAndCloseRemovesIt() throws Exception {
        TabPane tabPane = FxTestSupport.field(fx.controller, "tabPane");

        // Fresh window: a Welcome tab, no active buffer (bufferOf(welcome) is null).
        assertNull(FxTestSupport.callOnFx(this::activeBuffer), "no active buffer on the Welcome tab");
        int before = FxTestSupport.callOnFx(() -> tabPane.getTabs().size());

        // addBuffer adds a real buffer tab and selects it; activeBuffer tracks the selection.
        EditorBuffer buffer = FxTestSupport.callOnFx(EditorBuffer::new);
        Tab tab = FxTestSupport.callOnFx(() -> (Tab) FxTestSupport.call(
                fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true));
        assertTrue(FxTestSupport.callOnFx(() -> tabPane.getTabs().contains(tab)), "buffer tab added to the pane");
        assertSame(buffer, FxTestSupport.callOnFx(this::activeBuffer), "activeBuffer tracks the selected buffer");

        // closeTab removes it.
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "closeTab", new Class[] {Tab.class}, tab));
        assertFalse(FxTestSupport.callOnFx(() -> tabPane.getTabs().contains(tab)), "buffer tab removed after close");
        assertTrue(
                FxTestSupport.callOnFx(() -> tabPane.getTabs().size() <= before),
                "tab count back at or below the pre-add count");
    }

    private EditorBuffer activeBuffer() {
        return (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {});
    }
}
