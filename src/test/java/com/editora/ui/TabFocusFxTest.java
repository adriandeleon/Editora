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

    /** The active buffer, called on the FX thread. */
    private EditorBuffer activeNow() {
        return (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
    }

    private Node focusOwner() throws Exception {
        return FxTestSupport.callOnFx(() -> active().getArea().getScene().getFocusOwner());
    }

    @Test
    void nextTabFocusesItsEditor() throws Exception {
        EditorBuffer before = active();
        FxTestSupport.runOnFx(() -> before.getArea().getParent().requestFocus()); // focus off the text
        EditorBuffer now = null;
        for (int i = 0; i < 6 && (now == null || now == before); i++) { // past the Welcome tab, if it is next
            FxTestSupport.runOnFx(() -> reg.run("buffer.next"));
            settle();
            now = active();
        }
        assertTrue(now != before, "another tab");
        assertSame(now.getFocusedArea(), focusOwner());
    }

    @Test
    void aTabHeaderClickFocusesItsEditor() throws Exception {
        EditorBuffer before = active();
        Node header = FxTestSupport.callOnFx(() -> {
            Node other = null;
            for (Node n : before.getArea().getScene().getRoot().lookupAll(".editor-group .tab")) {
                EditorBuffer of =
                        n.getProperties().get(javafx.scene.control.Tab.class) instanceof javafx.scene.control.Tab tab
                                ? MainController.bufferOf(tab)
                                : null;
                if (onTabHeader(n) && of != null && of != before) {
                    other = n;
                }
            }
            return other;
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

    /**
     * Buffer: Next and the Switcher onto a page tab (Welcome) focus its page: the toolbar's New button or the Project
     * panel's Float button had it, and Space opened a buffer or floated the panel (twelfth review AL1).
     */
    @Test
    void nextTabAndTheSwitcherOntoAPageFocusThePage() throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "addWelcomeTab", new Class<?>[] {}));
        settle();
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        javafx.scene.control.Tab page = null;
        for (int i = 0; i < 6 && page == null; i++) {
            FxTestSupport.runOnFx(() -> reg.run("buffer.next"));
            settle();
            javafx.scene.control.Tab tab = FxTestSupport.callOnFx(area::selectedTab);
            page = MainController.bufferOf(tab) == null ? tab : null;
        }
        assertTrue(page != null, "a page tab reached");
        javafx.scene.control.Tab welcome = page;
        assertTrue(FxTestSupport.callOnFx(() -> within(welcome.getContent())), "Buffer: Next: the page has focus");

        FxTestSupport.runOnFx(() -> reg.run("buffer.next"));
        settle();
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller, "activateAndFocusTab", new Class<?>[] {javafx.scene.control.Tab.class}, welcome));
        settle();
        assertTrue(FxTestSupport.callOnFx(() -> within(welcome.getContent())), "the Switcher: the page has focus");
    }

    /**
     * View ▸ Welcome on a Welcome tab already open, from an editor, focuses the page: the focus stayed where JavaFX had
     * moved it, on the toolbar's New button (thirteenth review AK1).
     */
    @Test
    void reSelectingAnOpenPageFocusesThePage() throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "addWelcomeTab", new Class<?>[] {}));
        settle();
        EditorArea area = FxTestSupport.field(fx.controller, "editorArea");
        javafx.scene.control.Tab welcome = FxTestSupport.callOnFx(area::selectedTab);
        EditorBuffer editor = null;
        for (int i = 0; i < 6 && editor == null; i++) {
            FxTestSupport.runOnFx(() -> reg.run("buffer.next"));
            settle();
            editor = FxTestSupport.callOnFx(() -> MainController.bufferOf(area.selectedTab()));
        }
        assertTrue(editor != null, "an editor reached");
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "addWelcomeTab", new Class<?>[] {}));
        settle();
        assertSame(welcome, FxTestSupport.callOnFx(area::selectedTab));
        assertTrue(FxTestSupport.callOnFx(() -> within(welcome.getContent())), "the page has focus");
        for (int i = 0;
                i < 6 && FxTestSupport.callOnFx(() -> MainController.bufferOf(area.selectedTab())) == null;
                i++) {
            FxTestSupport.runOnFx(() -> reg.run("buffer.next")); // back on an editor, for the other tests
            settle();
        }
    }

    /** Whether the window's focus owner is {@code page} or inside it. */
    private boolean within(Node page) {
        Node owner = page.getScene() == null ? null : page.getScene().getFocusOwner();
        for (Node n = owner; n != null; n = n.getParent()) {
            if (n == page) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code target} is (in) a tab's header, not its close button. */
    private static boolean onTabHeader(Node target) {
        for (Node n = target; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("tab-close-button")) {
                return false;
            }
            if (n.getStyleClass().contains("tab")
                    && n.getParent() != null
                    && n.getParent().getStyleClass().contains("headers-region")) {
                return true;
            }
        }
        return false;
    }
}
