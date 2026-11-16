package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import javafx.scene.layout.HBox;
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

    /** The Settings stage; null until the first show builds the window. */
    Stage stage() {
        return FxTestSupport.field(window, "stage");
    }

    <T> T control(String name) {
        return control(window, name);
    }

    /**
     * The field {@code name} of {@code window} or of the page class that holds it ({@link
     * SettingsWindow#controlOwners}): a page's controls live in its own class.
     */
    static <T> T control(SettingsWindow window, String name) {
        for (Object owner : window.controlOwners()) {
            try {
                owner.getClass().getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                continue;
            }
            return FxTestSupport.field(owner, name);
        }
        throw new IllegalArgumentException("no field '" + name + "' on the Settings window or its pages");
    }

    /** The Keymaps page's shortcut list: a virtualized list, so only the rows on screen have nodes. */
    static ListView<SettingsWindow.Shortcut> shortcutList(SettingsWindow window) {
        return control(window, "shortcutList");
    }

    /** The command ids of every row the shortcut list holds as filtered, in order, on screen or not. */
    static List<String> shortcutIds(SettingsWindow window) {
        return shortcutList(window).getItems().stream()
                .map(SettingsWindow.Shortcut::id)
                .toList();
    }

    /**
     * The row the shortcut list shows for {@code commandId}, as it is now: the window is laid out first (a row
     * is made again by the layout that follows a change), and the list is scrolled to the row when it is not on
     * screen. Null when the filter leaves the command out. FX thread.
     */
    static HBox shortcutRow(SettingsWindow window, String commandId) {
        ListView<SettingsWindow.Shortcut> list = shortcutList(window);
        Map<String, HBox> rows = control(window, "shortcutRowsById");
        int at = shortcutIds(window).indexOf(commandId);
        if (at < 0) {
            return null;
        }
        if (list.getScene() == null) {
            // Another page is showing: a list that is not on screen has no rows to read. Open its page.
            ListView<Object> sidebar = control(window, "sidebar");
            sidebar.getItems().stream()
                    .filter(item ->
                            item instanceof Enum<?> category && category.name().equals("KEYMAPS"))
                    .findFirst()
                    .ifPresent(sidebar.getSelectionModel()::select);
        }
        layOut(list);
        HBox row = rows.get(commandId);
        if (row == null || row.getScene() == null) {
            list.scrollTo(at);
            layOut(list);
            row = rows.get(commandId);
        }
        return row;
    }

    /**
     * Styles and lays out the window and then {@code list} itself, as a pulse would: a page sits in a scroll pane,
     * whose content is laid out on its own and not from the scene's root.
     */
    static void layOut(ListView<?> list) {
        javafx.scene.Parent root =
                list.getScene() == null ? list : list.getScene().getRoot();
        root.applyCss();
        root.layout();
        list.layout();
        list.applyCss(); // the rows the layout has just made
        list.layout();
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
            // None yet: the action may show one later (an answer from another thread). Look again shortly.
            javafx.animation.PauseTransition again =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(10));
            again.setOnFinished(e -> responder[0].run());
            again.play();
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

    /**
     * Answers the next modal dialog to appear, whenever that is — for a dialog opened by something that
     * finishes later (a worker's result), which {@link #answering} cannot see. The future holds what the
     * dialog showed; cancel it to stop waiting.
     */
    static java.util.concurrent.CompletableFuture<Shown> answerNextDialog(ButtonBar.ButtonData answer)
            throws Exception {
        java.util.concurrent.CompletableFuture<Shown> answered = new java.util.concurrent.CompletableFuture<>();
        javafx.animation.AnimationTimer timer = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                if (answered.isDone()) {
                    stop();
                    return;
                }
                for (Window window : List.copyOf(Window.getWindows())) {
                    if (window.isShowing()
                            && window.getScene() != null
                            && window.getScene().getRoot() instanceof DialogPane pane) {
                        stop();
                        answered.complete(new Shown(
                                window instanceof Stage s ? s.getTitle() : null,
                                pane.getHeaderText(),
                                pane.getContentText()));
                        ((Button) pane.lookupButton(pane.getButtonTypes().stream()
                                        .filter(type -> type.getButtonData() == answer)
                                        .findFirst()
                                        .orElseThrow()))
                                .fire();
                        return;
                    }
                }
            }
        };
        FxTestSupport.runOnFx(timer::start);
        return answered;
    }

    /**
     * Runs {@code action}, which must open a modal dialog, and {@code inside} while that dialog is open — for
     * a dialog with controls of its own to drive before it is answered. Whatever {@code inside} does, the
     * dialog is closed afterwards, and a failure in there fails the test.
     */
    static void inDialog(Runnable action, java.util.function.Consumer<DialogPane> inside) {
        Throwable[] failure = new Throwable[1];
        boolean[] seen = new boolean[1];
        Platform.runLater(() -> {
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window.isShowing()
                        && window.getScene() != null
                        && window.getScene().getRoot() instanceof DialogPane pane) {
                    seen[0] = true;
                    try {
                        inside.accept(pane);
                    } catch (Throwable t) {
                        failure[0] = t;
                    } finally {
                        if (window.isShowing()) {
                            window.hide();
                        }
                    }
                    return;
                }
            }
        });
        action.run();
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
        if (!seen[0]) {
            throw new AssertionError("no dialog was shown");
        }
    }

    /**
     * On the FX thread: runs the event loop (nested) until {@code done} holds or ten seconds pass, for a step whose
     * answer arrives from another thread while a test's action is still running there.
     */
    static void pumpUntil(java.util.function.BooleanSupplier done) {
        Object key = new Object();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        javafx.animation.AnimationTimer timer = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                if (done.getAsBoolean() || System.nanoTime() > deadline) {
                    stop();
                    Platform.runLater(() -> Platform.exitNestedEventLoop(key, null));
                }
            }
        };
        timer.start();
        Platform.enterNestedEventLoop(key);
    }

    static List<Shown> answering(ButtonBar.ButtonData answer, Runnable action) {
        return answering(shown -> answer, action);
    }

    @Override
    public void close() throws Exception {
        try {
            FxTestSupport.runOnFx(() -> {
                javafx.stage.Window shown =
                        window.stageIfShowing(); // null when the test never opened Settings: nothing was built
                if (shown != null) {
                    shown.hide();
                }
            });
        } finally {
            fx.dispose();
        }
    }
}
