package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;

/**
 * A {@link SettingsWindow} of the test's own, over a real window's config and tool windows but with every
 * callback recorded instead of wired to the controller. A change made in it is written to the settings and
 * reported here, without starting what the real window would start for it (a server, a probe, a process).
 *
 * <p>All methods are to be called on the FX thread, except {@link #create} and {@link #close}.
 */
final class SettingsRig implements AutoCloseable {

    final FxWindowFixture fx;
    final ConfigManager config;
    final Settings settings;
    final ToolWindowManager toolWindows;
    final SettingsWindow window;
    final Stage owner;

    /** One entry per time the window applied the settings. */
    final List<Settings> applied = new ArrayList<>();

    final List<Boolean> zen = new ArrayList<>();
    final List<Boolean> expert = new ArrayList<>();
    final List<Path> opened = new ArrayList<>();
    final List<String> events = new ArrayList<>();

    private SettingsRig(FxWindowFixture fx, boolean detection) {
        this.fx = fx;
        this.config = FxTestSupport.field(fx.controller, "config");
        this.settings = config.getSettings();
        this.toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
        this.owner = FxTestSupport.field(fx.controller, "stage");
        this.window = new SettingsWindow(
                config,
                toolWindows,
                null,
                null,
                null,
                null,
                detection
                        ? FxTestSupport.<TypstCoordinator>field(fx.controller, "typst")
                                .service()
                        : null,
                List.of(),
                detection ? FxTestSupport.<com.editora.lsp.LspManager>field(fx.controller, "lspManager") : null,
                null,
                applied::add,
                on -> {
                    zen.add(on);
                    config.getWorkspaceState().setZenMode(on); // as the controller does: the boxes re-read it
                },
                on -> {
                    expert.add(on);
                    config.getWorkspaceState().setExpertMode(on);
                },
                opened::add,
                () -> events.add("export"),
                () -> events.add("debugLog"));
    }

    static SettingsRig create() throws Exception {
        return create(false);
    }

    /**
     * As {@link #create()}, but the Typst and language-server pages probe for their tools through the real
     * window's services, which only look whether the configured executable exists.
     */
    static SettingsRig createWithDetection() throws Exception {
        return create(true);
    }

    private static SettingsRig create(boolean detection) throws Exception {
        FxWindowFixture fx = FxWindowFixture.create();
        try {
            return FxTestSupport.callOnFx(() -> new SettingsRig(fx, detection));
        } catch (Exception e) {
            fx.dispose();
            throw e;
        }
    }

    void show() {
        window.show(owner);
    }

    Stage stage() {
        return FxTestSupport.field(window, "stage");
    }

    <T> T control(String name) {
        return FxTestSupport.field(window, name);
    }

    /** Opens the page of the category whose enum constant is {@code name} and returns its content. */
    Region page(String name) {
        ListView<Object> sidebar = control("sidebar");
        Object category = sidebar.getItems().stream()
                .filter(item -> item instanceof Enum<?> e && e.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no settings category " + name));
        sidebar.getSelectionModel().select(category);
        java.util.Map<Object, Region> pages = control("pages");
        return pages.get(category);
    }

    /** The name of the category whose page is open. */
    String openPage() {
        ListView<Object> sidebar = control("sidebar");
        return sidebar.getSelectionModel().getSelectedItem() instanceof Enum<?> e ? e.name() : null;
    }

    /** Types {@code text} into a commit-on-Enter field and presses Enter. */
    static void typeAndEnter(TextField field, String text) {
        field.setText(text);
        field.fireEvent(new ActionEvent());
    }

    static <T extends Node> List<T> all(Node root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out;
    }

    private static <T extends Node> void collect(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(child -> collect(child, type, out));
        }
    }

    /** The button or link under {@code root} labelled {@code text}. */
    static ButtonBase button(Node root, String text) {
        return all(root, ButtonBase.class).stream()
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no button \"" + text + "\""));
    }

    static List<String> texts(Node root) {
        return all(root, Labeled.class).stream()
                .map(Labeled::getText)
                .filter(t -> t != null && !t.isBlank())
                .toList();
    }

    /** What a dialog showed. */
    record Shown(String title, String header, String content) {}

    /**
     * Runs {@code action}; each modal dialog it opens is answered by pressing its button of the kind
     * {@code answer} returns for it (or closed when that is {@code null}). Returns the dialogs in order.
     * The answer is queued before the action, so it runs inside the dialog's own event loop.
     */
    static List<Shown> answering(Function<Shown, ButtonBar.ButtonData> answer, Runnable action) {
        List<Shown> shown = new ArrayList<>();
        boolean[] done = new boolean[1];
        Runnable[] responder = new Runnable[1];
        responder[0] = () -> {
            if (done[0]) {
                return;
            }
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window.isShowing()
                        && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane) {
                    Shown it = new Shown(
                            window instanceof Stage s ? s.getTitle() : null,
                            pane.getHeaderText(),
                            pane.getContentText());
                    shown.add(it);
                    ButtonBar.ButtonData kind = answer.apply(it);
                    // Another dialog may follow this one: look again once this answer has been taken.
                    Platform.runLater(responder[0]);
                    if (kind == null) {
                        window.hide();
                    } else {
                        ((Button) pane.lookupButton(pane.getButtonTypes().stream()
                                        .filter(type -> type.getButtonData() == kind)
                                        .findFirst()
                                        .orElseThrow()))
                                .fire();
                    }
                    return;
                }
            }
        };
        Platform.runLater(responder[0]);
        try {
            action.run();
        } finally {
            done[0] = true;
        }
        return shown;
    }

    /**
     * Waits, from the test thread, until {@code condition} holds on the FX thread. It is looked at on every
     * pulse, so the wait ends with the event that makes it true rather than after a fixed delay.
     */
    static void awaitFx(String what, java.util.function.BooleanSupplier condition) throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        javafx.animation.AnimationTimer timer = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                if (condition.getAsBoolean()) {
                    stop();
                    done.countDown();
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        try {
            if (!done.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for " + what);
            }
        } finally {
            FxTestSupport.runOnFx(timer::stop);
        }
    }

    static List<Shown> answering(ButtonBar.ButtonData answer, Runnable action) {
        return answering(shown -> answer, action);
    }

    @Override
    public void close() throws Exception {
        try {
            FxTestSupport.runOnFx(() -> stage().hide());
        } finally {
            fx.dispose();
        }
    }
}
