package com.editora.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import javafx.animation.AnimationTimer;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * Runs an FX action that blocks on a modal dialog ({@code showAndWait}) and answers that dialog from inside
 * its nested event loop. Only a dialog the caller's predicate accepts is answered; any other one is left
 * alone, so a test that gets a different question times out instead of answering it.
 */
final class FxDialogs {

    private FxDialogs() {}

    /**
     * Runs {@code action} on the FX thread; the first showing dialog matching {@code which} gets its button of
     * kind {@code answer} pressed ({@code null} closes the dialog window instead).
     *
     * @return the answered dialog's pane, or null when {@code action} finished without showing one
     */
    static DialogPane during(Runnable action, Predicate<DialogPane> which, ButtonBar.ButtonData answer)
            throws Exception {
        AtomicReference<DialogPane> seen = new AtomicReference<>();
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
                    seen.set(pane);
                    if (answer == null) {
                        javafx.event.Event.fireEvent(window, new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST));
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
        try {
            FxTestSupport.runOnFx(() -> {
                timer.start();
                action.run();
            });
        } finally {
            FxTestSupport.runOnFx(timer::stop);
        }
        return seen.get();
    }

    /** {@link #during} for a dialog recognised by its content text. */
    static DialogPane duringContent(Runnable action, String content, ButtonBar.ButtonData answer) throws Exception {
        return during(action, pane -> content.equals(pane.getContentText()), answer);
    }

    /** {@link #during} for a dialog recognised by its header text. */
    static DialogPane duringHeader(Runnable action, String header, ButtonBar.ButtonData answer) throws Exception {
        return during(action, pane -> header.equals(pane.getHeaderText()), answer);
    }
}
