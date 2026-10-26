package com.editora.ui;

import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tab chosen by a click on its header, or by Window ▸ Next Tab, has the keyboard focus in its editor: JavaFX left it
 * on the tab pane or the breadcrumbs, so keys went nowhere and the arrows switched tabs (eighth review AQ2).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TabFocusFxTest {

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

    private Node focusOwner() throws Exception {
        return FxTestSupport.callOnFx(() -> active().getArea().getScene().getFocusOwner());
    }

    @Test
    void nextTabFocusesItsEditor() throws Exception {
        EditorBuffer before = active();
        FxTestSupport.runOnFx(() -> before.getArea().getParent().requestFocus()); // focus off the text
        FxTestSupport.runOnFx(() -> reg.run("buffer.next"));
        settle();
        EditorBuffer now = active();
        assertTrue(now != before, "another tab");
        assertSame(now.getFocusedArea(), focusOwner());
    }

    @Test
    void aTabHeaderClickFocusesItsEditor() throws Exception {
        EditorBuffer before = active();
        Node header = FxTestSupport.callOnFx(() -> {
            Node selectedHeader = null;
            for (Node n : before.getArea().getScene().getRoot().lookupAll(".tab")) {
                if (TabFocus.onTabHeader(n)
                        && !n.getPseudoClassStates().toString().contains("selected")) {
                    selectedHeader = n;
                }
            }
            return selectedHeader;
        });
        assertTrue(header != null, "another tab's header");
        FxTestSupport.runOnFx(() -> {
            for (var type : java.util.List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED)) {
                header.fireEvent(new MouseEvent(
                        type,
                        5,
                        5,
                        5,
                        5,
                        MouseButton.PRIMARY,
                        1,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false,
                        null));
            }
        });
        settle();
        EditorBuffer now = active();
        assertTrue(now != before, "the clicked tab is chosen");
        assertSame(now.getFocusedArea(), focusOwner());
    }

    @Test
    void aCloseButtonIsNoHeaderClick() throws Exception {
        Node close = FxTestSupport.callOnFx(
                () -> active().getArea().getScene().getRoot().lookup(".tab-close-button"));
        if (close != null) {
            assertFalse(TabFocus.onTabHeader(close));
        }
        assertFalse(TabFocus.onTabHeader(FxTestSupport.callOnFx(() -> active().getArea())));
    }
}
