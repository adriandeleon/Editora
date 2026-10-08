package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;

/**
 * A real, shown window for coordinator tests, with the waits done on FX pulses (no sleeping): open a file
 * and wait for its text, run a command, read the status line, and read or choose the rows of a
 * {@link QuickOpen} picker.
 */
final class WindowRig implements AutoCloseable {

    final AsyncTestScope async = new AsyncTestScope();
    final FxWindowFixture fx;
    final MainController controller;
    final CommandRegistry registry;

    WindowRig() throws Exception {
        this(FxWindowFixture.create());
    }

    WindowRig(FxWindowFixture fixture) throws Exception {
        fx = async.own(fixture);
        controller = fx.controller;
        registry = FxTestSupport.field(controller, "registry");
        FxTestSupport.runOnFx(() -> {
            Stage stage = stage();
            stage.setWidth(1100);
            stage.setHeight(700);
            if (!stage.isShowing()) {
                stage.show();
            }
        });
        async.awaitFx();
    }

    Stage stage() {
        Parent root = FxTestSupport.field(controller, "root");
        return (Stage) root.getScene().getWindow();
    }

    <T> T field(String name) {
        return FxTestSupport.field(controller, name);
    }

    void await(String what, BooleanSupplier condition) throws Exception {
        SaveGuardsFxTest.awaitOnFx(async, what, condition);
    }

    /** The active buffer, or null. FX thread. */
    EditorBuffer active() {
        return (EditorBuffer) FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {});
    }

    /** Whether {@code file} is the active buffer with its text loaded. FX thread. */
    boolean isActive(Path file) {
        EditorBuffer b = active();
        return b != null && file.equals(b.getPath()) && !b.isLoading();
    }

    /** Opens (or re-selects) {@code file} and waits until it is the loaded, active buffer. */
    EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> controller.openAndNavigate(file, 0));
        await(file.getFileName() + " to be open", () -> isActive(file));
        return FxTestSupport.callOnFx(this::active);
    }

    Tab tabFor(Path file) {
        return (Tab) FxTestSupport.call(controller, "tabForPath", new Class<?>[] {Path.class}, file);
    }

    void run(String commandId) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(commandId));
        async.awaitFx();
    }

    /** The newest status message, in full. */
    String status() throws Exception {
        return SaveDecisionsFxTest.lastMessage(fx);
    }

    /** The rows a shown picker lists, each as {@code title|detail}. FX thread. */
    static <T> List<String> rows(QuickOpen<T> picker) {
        ListView<T> list = FxTestSupport.field(picker, "list");
        List<String> rows = new ArrayList<>();
        for (T item : list.getItems()) {
            rows.add(row(picker, item));
        }
        return rows;
    }

    /** One rendered row of a picker: its title text, a bar, its detail text. FX thread. */
    static <T> String row(QuickOpen<T> picker, T item) {
        ListCell<T> cell = cell(picker, item);
        StringBuilder title = new StringBuilder();
        javafx.scene.text.TextFlow flow = FxTestSupport.field(cell, "title");
        for (Node node : flow.getChildren()) {
            title.append(((Text) node).getText());
        }
        Label detail = FxTestSupport.field(cell, "sub");
        return title + "|" + detail.getText();
    }

    static <T> ListCell<T> cell(QuickOpen<T> picker, T item) {
        ListView<T> list = FxTestSupport.field(picker, "list");
        ListCell<T> cell = list.getCellFactory().call(list);
        FxTestSupport.call(cell, "updateItem", new Class<?>[] {Object.class, boolean.class}, item, false);
        return cell;
    }

    /** The items a shown picker lists. FX thread. */
    static <T> List<T> items(QuickOpen<T> picker) {
        ListView<T> list = FxTestSupport.field(picker, "list");
        return List.copyOf(list.getItems());
    }

    /** Highlights {@code item} in a shown picker (which previews it, where the picker previews). FX thread. */
    static <T> void highlight(QuickOpen<T> picker, T item) {
        ListView<T> list = FxTestSupport.field(picker, "list");
        list.getSelectionModel().select(item);
    }

    /** Highlights {@code item} and accepts it, as Enter does. FX thread. */
    static <T> void choose(QuickOpen<T> picker, T item) {
        highlight(picker, item);
        FxTestSupport.invoke(picker, "chooseSelected");
    }

    /**
     * Arms an answer for the next dialog whose header is {@code header}: the button labelled
     * {@code buttonText} is pressed as soon as the dialog is up. A modal dialog blocks its caller, so this
     * comes before the action that opens it; the returned latch says the dialog did appear.
     */
    java.util.concurrent.CountDownLatch answer(String header, String buttonText) throws Exception {
        java.util.concurrent.CountDownLatch pressed = new java.util.concurrent.CountDownLatch(1);
        javafx.animation.AnimationTimer timer = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                for (javafx.stage.Window window : List.copyOf(javafx.stage.Window.getWindows())) {
                    if (pressed.getCount() == 0
                            || window.getScene() == null
                            || !(window.getScene().getRoot() instanceof javafx.scene.control.DialogPane pane)
                            || !header.equals(pane.getHeaderText())) {
                        continue;
                    }
                    pane.getButtonTypes().stream()
                            .filter(type -> buttonText.equals(type.getText()))
                            .findFirst()
                            .ifPresent(type -> {
                                pressed.countDown();
                                stop();
                                ((javafx.scene.control.Button) pane.lookupButton(type)).fire();
                            });
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        async.onClose(() -> FxTestSupport.runOnFx(timer::stop));
        return pressed;
    }

    @Override
    public void close() throws Exception {
        async.close();
    }
}
