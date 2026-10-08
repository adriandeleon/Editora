package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DialogPane;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

/**
 * Answers the questions the Git and diff features put to the user in a test: a modal dialog, an in-scene
 * picker ({@link QuickOpen}), a context menu. Nothing here sleeps; a dialog is answered from an
 * {@link AnimationTimer}, which keeps running inside the dialog's nested event loop.
 */
final class OverlayTestKit {

    private OverlayTestKit() {}

    /** What an answered dialog showed. */
    record Shown(String header, String content, List<String> buttons) {}

    /**
     * Answers the next modal dialog {@code which} accepts by firing its button of kind {@code answer}, or —
     * with {@code null} — by closing its window. The latch opens when the dialog was found; {@code shown}
     * then holds its texts. A dialog {@code which} rejects is left alone, so a test that gets a different
     * question times out instead of answering it.
     */
    static CountDownLatch answerDialog(
            AsyncTestScope async,
            Predicate<DialogPane> which,
            ButtonBar.ButtonData answer,
            AtomicReference<Shown> shown)
            throws Exception {
        CountDownLatch answered = new CountDownLatch(1);
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (!window.isShowing()
                            || window.getScene() == null
                            || !(window.getScene().getRoot() instanceof DialogPane pane)
                            || !which.test(pane)) {
                        continue;
                    }
                    stop();
                    if (shown != null) {
                        shown.set(new Shown(
                                pane.getHeaderText(),
                                contentText(pane),
                                pane.getButtonTypes().stream()
                                        .map(javafx.scene.control.ButtonType::getText)
                                        .toList()));
                    }
                    answered.countDown();
                    if (answer == null) {
                        javafx.event.Event.fireEvent(
                                window,
                                new javafx.stage.WindowEvent(window, javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST));
                    } else {
                        ((Button) pane.lookupButton(pane.getButtonTypes().stream()
                                        .filter(type -> type.getButtonData() == answer)
                                        .findFirst()
                                        .orElseThrow()))
                                .fire();
                    }
                    return;
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        async.onClose(() -> FxTestSupport.runOnFx(timer::stop));
        return answered;
    }

    /**
     * Waits until {@code condition} holds, checking it on every FX pulse (no sleeping, no wall-clock poll);
     * fails after the scope's timeout. The condition runs on the FX thread.
     */
    static void await(AsyncTestScope async, String what, java.util.concurrent.Callable<Boolean> condition)
            throws Exception {
        CountDownLatch reached = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AnimationTimer timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                try {
                    if (condition.call()) {
                        stop();
                        reached.countDown();
                    }
                } catch (Exception | AssertionError e) {
                    failure.set(e);
                    stop();
                    reached.countDown();
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        try {
            async.await(reached, what);
        } finally {
            FxTestSupport.runOnFx(timer::stop);
        }
        if (failure.get() != null) {
            throw new AssertionError("while waiting for " + what, failure.get());
        }
    }

    /** Answers the next modal dialog, whatever it asks. */
    static CountDownLatch answerAnyDialog(
            AsyncTestScope async, ButtonBar.ButtonData answer, AtomicReference<Shown> shown) throws Exception {
        return answerDialog(async, pane -> true, answer, shown);
    }

    /** The text a dialog shows under its header: its content text, or the text of a text control it holds. */
    static String contentText(DialogPane pane) {
        if (pane.getContent() instanceof javafx.scene.control.TextInputControl text) {
            return text.getText();
        }
        if (pane.getContent() instanceof javafx.scene.control.Labeled label) {
            return label.getText();
        }
        return pane.getContentText();
    }

    /** The titles of the modal dialogs showing now. Call on the FX thread. */
    static List<String> openDialogHeaders() {
        List<String> headers = new ArrayList<>();
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window.isShowing()
                    && window.getScene() != null
                    && window.getScene().getRoot() instanceof DialogPane pane) {
                headers.add(String.valueOf(pane.getHeaderText()));
            }
        }
        return headers;
    }

    /** The in-scene picker card showing in {@code scene}, or {@code null}. Call on the FX thread. */
    static Node picker(Scene scene) {
        Node found = null;
        for (Node card : scene.getRoot().lookupAll(".command-palette")) {
            if (card.getScene() != null
                    && shownInTree(card)
                    && !card.getStyleClass().contains("overlay-form")) {
                found = card;
            }
        }
        return found;
    }

    private static boolean shownInTree(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** The rows the open picker lists, as their items. Call on the FX thread. */
    static List<?> pickerItems(Scene scene) {
        Node card = picker(scene);
        if (card == null) {
            return List.of();
        }
        ListView<?> list = (ListView<?>) card.lookup(".list-view");
        return List.copyOf(list.getItems());
    }

    /**
     * Types {@code query} into the open picker and accepts the row it selects, as Enter does. Returns false
     * when no picker is open or the query matches nothing. Call on the FX thread.
     */
    static boolean pick(Scene scene, String query) {
        Node card = picker(scene);
        if (card == null) {
            return false;
        }
        TextField input = (TextField) card.lookup(".text-field");
        input.setText(query);
        ListView<?> list = (ListView<?>) card.lookup(".list-view");
        if (list.getItems().isEmpty()) {
            return false;
        }
        javafx.event.Event.fireEvent(
                input, new KeyEvent(KeyEvent.KEY_PRESSED, "\r", "\r", KeyCode.ENTER, false, false, false, false));
        return true;
    }

    /** Selects row {@code index} of the open picker and accepts it. Call on the FX thread. */
    static boolean pickRow(Scene scene, int index) {
        Node card = picker(scene);
        if (card == null) {
            return false;
        }
        TextField input = (TextField) card.lookup(".text-field");
        ListView<?> list = (ListView<?>) card.lookup(".list-view");
        if (index >= list.getItems().size()) {
            return false;
        }
        list.getSelectionModel().select(index);
        javafx.event.Event.fireEvent(
                input, new KeyEvent(KeyEvent.KEY_PRESSED, "\r", "\r", KeyCode.ENTER, false, false, false, false));
        return true;
    }

    /** The in-scene form or prompt card showing in {@code scene}, or {@code null}. Call on the FX thread. */
    static Node form(Scene scene) {
        Node found = null;
        for (Node card : scene.getRoot().lookupAll(".overlay-form")) {
            if (card.getScene() != null && shownInTree(card)) {
                found = card;
            }
        }
        return found;
    }

    /** The title of the open form, or {@code null} when none is open. Call on the FX thread. */
    static String formTitle(Scene scene) {
        Node card = form(scene);
        return card == null ? null : ((javafx.scene.control.Label) card.lookup(".palette-title")).getText();
    }

    /** The text fields of the open form, top to bottom. Call on the FX thread. */
    static List<TextField> formFields(Scene scene) {
        Node card = form(scene);
        List<TextField> fields = new ArrayList<>();
        if (card != null) {
            collect(card, TextField.class, fields);
        }
        return fields;
    }

    /** The check boxes of the open form, top to bottom. Call on the FX thread. */
    static List<javafx.scene.control.CheckBox> formChecks(Scene scene) {
        Node card = form(scene);
        List<javafx.scene.control.CheckBox> checks = new ArrayList<>();
        if (card != null) {
            collect(card, javafx.scene.control.CheckBox.class, checks);
        }
        return checks;
    }

    private static <T> void collect(Node node, Class<T> type, List<T> out) {
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, type, out);
            }
        }
    }

    /**
     * Fills the open form's text fields with {@code texts}, in order ({@code null} leaves a field as it is),
     * and presses its primary button. Returns false when no form is open. Call on the FX thread.
     */
    static boolean submitForm(Scene scene, String... texts) {
        Node card = form(scene);
        if (card == null) {
            return false;
        }
        List<TextField> fields = formFields(scene);
        for (int i = 0; i < texts.length; i++) {
            if (texts[i] != null) {
                fields.get(i).setText(texts[i]);
            }
        }
        List<Button> buttons = new ArrayList<>();
        collect(card, Button.class, buttons);
        buttons.stream()
                .filter(button -> button.getStyleClass().contains("accent")
                        || button.getStyleClass().contains("success"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the form has no primary button"))
                .fire();
        return true;
    }

    /** Dismisses the open picker, as Escape does. Call on the FX thread. */
    static void cancelPicker(Scene scene) {
        Node card = picker(scene);
        if (card != null) {
            javafx.event.Event.fireEvent(
                    card.lookup(".text-field"),
                    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
        }
    }

    /** The context menus showing now. Call on the FX thread. */
    static List<ContextMenu> openContextMenus() {
        List<ContextMenu> menus = new ArrayList<>();
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window instanceof ContextMenu menu && menu.isShowing()) {
                menus.add(menu);
            }
        }
        return menus;
    }

    /** The labels of a menu's entries, separators left out. */
    static List<String> labels(List<MenuItem> items) {
        return items.stream()
                .filter(item -> !(item instanceof javafx.scene.control.SeparatorMenuItem))
                .map(MenuItem::getText)
                .toList();
    }

    /** The entry of {@code items} labelled {@code label}. */
    static MenuItem item(List<MenuItem> items, String label) {
        return items.stream()
                .filter(item -> label.equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no menu entry \"" + label + "\" in " + labels(items)));
    }
}
