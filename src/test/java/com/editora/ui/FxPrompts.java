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
 * Answers a window's in-scene overlays the way a user does: the single-line prompt (type, OK / Cancel) and
 * a picker (look at the rows, choose one with Enter, or Escape). Every method runs on the FX thread through
 * {@link FxTestSupport}; an overlay that is not showing is an error, not a silent no-op.
 */
final class FxPrompts {

    private FxPrompts() {}

    private static OverlayHost overlay(MainController controller) {
        return FxTestSupport.field(controller, "overlayHost");
    }

    static boolean showing(MainController controller) throws Exception {
        return FxTestSupport.callOnFx(() -> overlay(controller).isShowing());
    }

    /** The card currently on the overlay. FX thread. */
    private static Node card(MainController controller) {
        OverlayHost overlay = overlay(controller);
        if (!overlay.isShowing()) {
            throw new AssertionError("no overlay is showing");
        }
        StackPane root = FxTestSupport.field(overlay, "overlayRoot");
        return root.getChildren().get(1);
    }

    /** The text the prompt (or a picker's query field) currently holds. */
    static String text(MainController controller) throws Exception {
        return FxTestSupport.callOnFx(() -> ((TextField) card(controller).lookup(".text-field")).getText());
    }

    /** Types {@code text} into the prompt and presses OK. */
    static void answer(MainController controller, String text) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Node card = card(controller);
            ((TextField) card.lookup(".text-field")).setText(text);
            button(card, tr("dialog.ok")).fire();
        });
    }

    /** Presses the prompt's Cancel. */
    static void cancel(MainController controller) throws Exception {
        FxTestSupport.runOnFx(
                () -> button(card(controller), tr("dialog.cancel")).fire());
    }

    private static Button button(Node card, String label) {
        return (Button) card.lookupAll(".button").stream()
                .filter(node -> node instanceof Button b && label.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + label + "' button on the overlay"));
    }

    /** The rows a picker is showing, as their items' {@code toString()}. */
    static List<String> rows(MainController controller) throws Exception {
        return FxTestSupport.callOnFx(
                () -> list(controller).getItems().stream().map(String::valueOf).toList());
    }

    private static ListView<?> list(MainController controller) {
        return (ListView<?>) card(controller).lookup(".list-view");
    }

    /** Selects the picker's row {@code index} and presses Enter in its query field. */
    static void choose(MainController controller, int index) throws Exception {
        FxTestSupport.runOnFx(() -> {
            list(controller).getSelectionModel().select(index);
            pressInQuery(controller, KeyCode.ENTER);
        });
    }

    /** Presses Escape in the picker's query field. */
    static void escape(MainController controller) throws Exception {
        FxTestSupport.runOnFx(() -> pressInQuery(controller, KeyCode.ESCAPE));
    }

    private static void pressInQuery(MainController controller, KeyCode code) {
        Node query = card(controller).lookup(".text-field");
        Event.fireEvent(query, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }
}
