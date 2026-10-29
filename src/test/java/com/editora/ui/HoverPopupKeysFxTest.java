package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Popup;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A hover that ends in a link, as every HTML and CSS hover does ("MDN Reference"): the link was the popup's focus
 * owner, so the popup opened scrolled to it at the bottom, and an Enter typed while it showed followed it instead
 * of reaching the text (HTML review B8/C9).
 */
@Tag("fx")
class HoverPopupKeysFxTest {

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    @Test
    void aHoverEndingInALinkOpensAtItsTopAndLeavesEnterToTheText() throws Exception {
        EditorBuffer b = fx.open("A.java", "class A {\n    void go() {}\n}\n");
        fx.awaitHighlighted(b);
        fx.caret(b, 1, 9);
        StringBuilder md = new StringBuilder("The **first** line of the description.\n\n");
        for (int i = 0; i < 60; i++) {
            md.append("A paragraph of documentation, number ").append(i).append(", long enough to wrap.\n\n");
        }
        md.append("[MDN Reference](https://developer.mozilla.org/docs/Web/HTML/Reference/Elements/nav)");
        fx.server().hoverResponse = new Hover(new MarkupContent("markdown", md.toString()));

        fx.run(() -> fx.coordinator.showHover());
        fx.layout();
        Popup popup = FxTestSupport.field(fx.coordinator, "hoverPopup");
        assertNotNull(popup, "the hover is shown");
        FxTestSupport.runOnFx(() -> {
            popup.getScene().getRoot().applyCss();
            popup.getScene().getRoot().layout();
        });
        FxTestSupport.drainFx();
        ScrollPane scroll = FxTestSupport.callOnFx(
                () -> (ScrollPane) popup.getScene().getRoot().lookup(".scroll-pane"));
        assertTrue(FxTestSupport.callOnFx(() -> scroll.getVmax() > 0), "precondition: the hover scrolls");
        assertEquals(0.0, FxTestSupport.callOnFx(scroll::getVvalue), "the hover opens at its first line");
        assertNull(FxTestSupport.callOnFx(() -> popup.getScene().getFocusOwner()), "nothing in the hover takes keys");

        String before = FxTestSupport.callOnFx(() -> b.getArea().getText());
        FxTestSupport.runOnFx(() -> b.getArea()
                .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
        fx.settle();
        String after = FxTestSupport.callOnFx(() -> b.getArea().getText());
        assertEquals(lines(before) + 1, lines(after), "Enter typed a line break: " + after);
        assertEquals(List.of(), fx.host.urls, "Enter opened no link");
    }

    private static long lines(String s) {
        return s.chars().filter(c -> c == '\n').count();
    }
}
