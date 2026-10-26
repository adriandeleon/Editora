package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.input.MouseEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing the focused tab or a right-click on a tab's header leaves the keyboard in the editor shown (ninth review AP1,
 * AP2). Apart from {@link TabFocusFxTest}: each case gets a window of its own.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TabFocusCloseFxTest {

    private FxWindowFixture fx;
    private CommandRegistry reg;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            reg = FxTestSupport.field(fx.controller, "registry");
            reg.run("file.new");
            reg.run("file.new");
        });
        settle();
    }

    @AfterAll
    void tearDown() throws Exception {
        fx.dispose();
    }

    private void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            FxTestSupport.drainFx();
        }
    }

    private EditorBuffer active() throws Exception {
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    /** The active buffer, called on the FX thread. */
    private EditorBuffer activeNow() {
        return (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
    }

    private Node focusOwner() throws Exception {
        return FxTestSupport.callOnFx(() -> active().getArea().getScene().getFocusOwner());
    }

    /** Closing the focused tab leaves the keyboard in the editor shown, not on the toolbar (ninth review AP1). */
    @Test
    void closingTheFocusedTabFocusesTheNextEditor() throws Exception {
        FxTestSupport.runOnFx(() -> {
            reg.run("file.new");
            reg.run("file.new");
        });
        settle();
        FxTestSupport.runOnFx(() -> activeNow().getFocusedArea().requestFocus());
        settle();
        EditorBuffer before = active();
        FxTestSupport.runOnFx(() -> reg.run("buffer.close"));
        settle();
        EditorBuffer now = active();
        assertTrue(now != null && now != before, "another tab is shown");
        for (int i = 0; i < 100 && now.getFocusedArea() != focusOwner(); i++) { // JavaFX moves it on a later pulse
            Thread.sleep(20);
            settle();
        }
        assertSame(now.getFocusedArea(), focusOwner());
    }

    /** A right-click on a tab's header leaves the keyboard in the editor (ninth review AP2). */
    @Test
    void aRightClickOnAHeaderKeepsTheEditorFocused() throws Exception {
        EditorBuffer before = active();
        Node header = FxTestSupport.callOnFx(
                () -> before.getArea().getScene().getRoot().lookup(".tab"));
        FxTestSupport.runOnFx(() -> {
            header.getScene().getRoot().lookup(".tab-pane").requestFocus(); // where JavaFX puts it on a press
            for (var type : java.util.List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
                header.fireEvent(new MouseEvent(
                        type,
                        5,
                        5,
                        5,
                        5,
                        javafx.scene.input.MouseButton.SECONDARY,
                        1,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        true,
                        true,
                        false,
                        false,
                        null));
            }
        });
        settle();
        assertSame(active().getFocusedArea(), focusOwner());
    }
}
