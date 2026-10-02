package com.editora.ui;

import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keyboard behaviour of an in-scene input card: Enter activates the <em>focused</em> button rather than
 * always running the primary action, and focus cannot leave the card for the window behind the scrim.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverlayCardKeysFxTest {

    private FxWindowFixture fx;
    private OverlayHost overlay;
    private EditorBuffer buffer;

    private final AtomicInteger accepted = new AtomicInteger();
    private final AtomicInteger deleted = new AtomicInteger();
    private TextField field;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        overlay = FxTestSupport.field(fx.controller, "overlayHost");
        buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("text");
            FxTestSupport.call(fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, b, true);
            return b;
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(() -> overlay.hide());
            fx.dispose();
        }
    }

    /** Shows a form card with a field, a red Delete, Cancel and OK; returns after its focus hook has run. */
    private void showCard() throws Exception {
        accepted.set(0);
        deleted.set(0);
        FxTestSupport.runOnFx(() -> {
            field = new TextField("value");
            OverlayInput.show(
                    overlay,
                    "Title",
                    new VBox(field),
                    field,
                    "OK",
                    null,
                    accepted::incrementAndGet,
                    new OverlayInput.Extra("Delete", deleted::incrementAndGet),
                    false);
        });
        FxTestSupport.drainFx(); // the card focuses its field in a runLater
    }

    private Button button(String text) {
        Node card = field.getParent().getParent();
        return card.lookupAll(".button").stream()
                .map(Button.class::cast)
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow();
    }

    private static void enter(Node target) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

    @Test
    void enterInTheFieldAccepts() throws Exception {
        showCard();
        FxTestSupport.runOnFx(() -> enter(field));
        assertEquals(1, accepted.get());
        assertFalse(FxTestSupport.callOnFx(overlay::isShowing));
    }

    @Test
    void enterOnCancelCancelsInsteadOfAccepting() throws Exception {
        showCard();
        FxTestSupport.runOnFx(() -> enter(button(com.editora.i18n.Messages.tr("dialog.cancel"))));
        assertEquals(0, accepted.get(), "Enter on a focused Cancel must not run the primary action");
        assertEquals(0, deleted.get());
        assertFalse(FxTestSupport.callOnFx(overlay::isShowing), "Cancel fired: the card is dismissed");
    }

    @Test
    void enterOnTheDangerButtonRunsThatButton() throws Exception {
        showCard();
        FxTestSupport.runOnFx(() -> enter(button("Delete")));
        assertEquals(1, deleted.get(), "Enter on the focused Delete runs Delete");
        assertEquals(0, accepted.get(), "…not the primary action");
    }

    @Test
    void theSubmitChordAcceptsEvenFromAButton() throws Exception {
        showCard();
        FxTestSupport.runOnFx(() -> button("Delete")
                .fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, true, false, false)));
        assertEquals(1, accepted.get());
        assertEquals(0, deleted.get());
    }

    private boolean focusInsideCard() {
        Scene scene = field.getScene();
        Node owner = scene == null ? null : scene.getFocusOwner();
        Node card = field.getParent().getParent();
        for (Node n = owner; n != null; n = n.getParent()) {
            if (n == card) {
                return true;
            }
        }
        return false;
    }

    @Test
    void focusCannotLeaveTheCardForTheWindowBehindIt() throws Exception {
        showCard();
        assertTrue(FxTestSupport.callOnFx(this::focusInsideCard), "precondition: the card's field has the focus");
        // Tab past the last control hands focus to the next traversable node in the scene — the editor.
        FxTestSupport.runOnFx(() -> {
            field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false));
            buffer.getArea().requestFocus();
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertTrue(focusInsideCard(), "focus is pulled back into the card");
            assertSame(field, field.getScene().getFocusOwner(), "forward Tab wraps to the first control");
        });
        // Shift+Tab before the first control wraps to the last one.
        FxTestSupport.runOnFx(() -> {
            field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, true, false, false, false));
            buffer.getArea().requestFocus();
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertSame(button("OK"), field.getScene().getFocusOwner(), "Shift+Tab wraps to the last control");
            overlay.hide();
        });
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(this::focusInsideCard), "once hidden, focus is free to return");
    }

    @Test
    void edgeFocusableSkipsHiddenAndDisabledControls() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Button hidden = new Button("hidden");
            hidden.setVisible(false);
            Button disabled = new Button("disabled");
            disabled.setDisable(true);
            Button first = new Button("first");
            Button last = new Button("last");
            VBox box = new VBox(hidden, disabled, new VBox(first), last, new javafx.scene.control.Label("text"));
            assertSame(first, OverlayHost.edgeFocusable(box, false));
            assertSame(last, OverlayHost.edgeFocusable(box, true));
            assertNotNull(OverlayHost.edgeFocusable(first, true));
            assertEquals(null, OverlayHost.edgeFocusable(new VBox(hidden), false));
        });
    }
}
