package com.editora.ui;

import java.util.List;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import static com.editora.i18n.Messages.tr;

/**
 * Answers what a window shows in its in-scene overlay: a picker, or a one-line prompt. To be called on the
 * FX thread, after the command that opened it.
 */
final class OverlayDriver {

    private OverlayDriver() {}

    static boolean showing(MainController controller) {
        return FxTestSupport.<OverlayHost>field(controller, "overlayHost").isShowing();
    }

    /** The card the overlay is showing. */
    static Node card(MainController controller) {
        OverlayHost overlay = FxTestSupport.field(controller, "overlayHost");
        if (!overlay.isShowing()) {
            throw new IllegalStateException("nothing is showing in the overlay");
        }
        StackPane root = FxTestSupport.field(overlay, "overlayRoot");
        return root.getChildren().get(1);
    }

    /** The rows the picker on screen offers. */
    @SuppressWarnings("unchecked")
    static <T> List<T> choices(MainController controller) {
        return List.copyOf(((ListView<T>) card(controller).lookup(".list-view")).getItems());
    }

    /** The row the picker opened on. */
    @SuppressWarnings("unchecked")
    static <T> T preselected(MainController controller) {
        return ((ListView<T>) card(controller).lookup(".list-view"))
                .getSelectionModel()
                .getSelectedItem();
    }

    /** Selects {@code item} in the picker on screen and presses Enter. */
    @SuppressWarnings("unchecked")
    static <T> void pick(MainController controller, T item) {
        Node card = card(controller);
        ListView<T> list = (ListView<T>) card.lookup(".list-view");
        if (!list.getItems().contains(item)) {
            throw new IllegalStateException(item + " is not offered: " + list.getItems());
        }
        list.getSelectionModel().select(item);
        Event.fireEvent(
                card.lookup(".text-field"),
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

    /** The text a prompt on screen opened with. */
    static String promptText(MainController controller) {
        return ((TextField) card(controller).lookup(".text-field")).getText();
    }

    /** Types {@code text} into the prompt on screen and accepts it. */
    static void answer(MainController controller, String text) {
        Node card = card(controller);
        ((TextField) card.lookup(".text-field")).setText(text);
        Button ok = (Button) card.lookupAll(".button").stream()
                .filter(node -> node instanceof Button button && tr("dialog.ok").equals(button.getText()))
                .findFirst()
                .orElseThrow();
        ok.fire();
    }

    static void dismiss(MainController controller) {
        FxTestSupport.<OverlayHost>field(controller, "overlayHost").hide();
    }
}
