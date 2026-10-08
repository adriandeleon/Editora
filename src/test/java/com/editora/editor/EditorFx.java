package com.editora.editor;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.Observable;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.i18n.Messages;
import org.testfx.api.FxToolkit;

/**
 * Plumbing for the {@code editor}-package headless-FX tests: boots the toolkit and the English catalog, runs
 * work on the FX thread, waits for an observable to change (never for wall-clock time), and reads private
 * state by reflection. The {@code ui} package has its own, package-private, {@code FxTestSupport}.
 */
final class EditorFx {

    private static final long TIMEOUT_SECONDS = 60;

    private static volatile boolean booted;

    private EditorFx() {}

    /** Idempotently starts the Headless toolkit and loads the English message catalog. */
    static synchronized void boot() throws Exception {
        if (booted) {
            return;
        }
        FxToolkit.registerPrimaryStage();
        onFx(() -> Messages.init("en"));
        booted = true;
    }

    /** Runs {@code task} on the FX thread and waits for it, rethrowing an assertion failure as itself. */
    static void onFx(Runnable task) throws Exception {
        if (Platform.isFxApplicationThread()) {
            task.run();
            return;
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        Throwable t = failure.get();
        if (t instanceof Error e) {
            throw e;
        }
        if (t instanceof Exception e) {
            throw e;
        }
    }

    static <T> T callFx(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        onFx(() -> {
            try {
                result.set(task.call());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return result.get();
    }

    /** Waits until every FX task queued before this call (and the tasks those queued, two deep) has run. */
    static void drain() throws Exception {
        for (int i = 0; i < 3; i++) {
            CountDownLatch done = new CountDownLatch(1);
            Platform.runLater(done::countDown);
            if (!done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("FX queue did not drain");
            }
        }
    }

    /**
     * Runs {@code trigger} on the FX thread and blocks until the observable {@code target} supplies is next
     * invalidated — the hand-off used for work that finishes on a pool thread and reports through
     * {@code Platform.runLater}. Then drains the FX queue so follow-up tasks have run too.
     */
    static void awaitChange(Supplier<Observable> target, Runnable trigger) throws Exception {
        CountDownLatch changed = new CountDownLatch(1);
        onFx(() -> {
            Observable observable = target.get();
            observable.addListener(new InvalidationListener() {
                @Override
                public void invalidated(Observable o) {
                    o.removeListener(this);
                    changed.countDown();
                }
            });
            trigger.run();
        });
        if (!changed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the observed value never changed");
        }
        drain();
    }

    /** Shows {@code buffer} in its own stage (so hit-testing, popups and focus have a window). FX thread. */
    static Stage show(EditorBuffer buffer, double width, double height) {
        Stage stage = new Stage();
        stage.setX(120);
        stage.setY(90);
        stage.setScene(new Scene(buffer.getNode(), width, height));
        stage.show();
        buffer.getNode().applyCss();
        buffer.getNode().layout();
        return stage;
    }

    @SuppressWarnings("unchecked")
    static <T> T field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(target);
            } catch (NoSuchFieldException e) {
                // look in the superclass
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("no field " + name);
    }

    static void setField(Object target, String name, Object value) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // look in the superclass
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("no field " + name);
    }

    /** Marks {@code node} as (not) under the mouse — there is no pointer to move in the headless toolkit. */
    static void hover(Node node, boolean hover) {
        try {
            Method m = Node.class.getDeclaredMethod("setHover", boolean.class);
            m.setAccessible(true);
            m.invoke(node, hover);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T> T staticField(Class<?> type, String name) {
        try {
            Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Calls a private method, unwrapping what it throws. */
    @SuppressWarnings("unchecked")
    static <T> T call(Object target, String method, Class<?>[] types, Object... args) {
        Class<?> start = target instanceof Class<?> k ? k : target.getClass();
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(method, types);
                m.setAccessible(true);
                return (T) m.invoke(target instanceof Class<?> ? null : target, args);
            } catch (NoSuchMethodException e) {
                // look in the superclass
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException r) {
                    throw r;
                }
                if (e.getCause() instanceof Error err) {
                    throw err;
                }
                throw new IllegalStateException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalArgumentException("no method " + method);
    }

    static <T> T call(Object target, String method) {
        return call(target, method, new Class<?>[0]);
    }

    /** Every label/text string under {@code node}, joined with newlines — what the user can read there. */
    static String textOf(Node node) {
        StringBuilder sb = new StringBuilder();
        collectText(node, sb);
        return sb.toString();
    }

    private static void collectText(Node node, StringBuilder sb) {
        if (node == null) {
            return;
        }
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            sb.append(labeled.getText()).append('\n');
        } else if (node instanceof Text text) {
            sb.append(text.getText()).append('\n');
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll) {
            collectText(scroll.getContent(), sb);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectText(child, sb);
            }
        }
    }

    /** The first item titled {@code text}, searching submenus; fails when the menu has no such item. */
    static MenuItem menuItem(java.util.List<MenuItem> items, String text) {
        MenuItem found = findMenuItem(items, text);
        if (found == null) {
            throw new AssertionError("no menu item \"" + text + "\" in " + titles(items));
        }
        return found;
    }

    static MenuItem findMenuItem(java.util.List<MenuItem> items, String text) {
        for (MenuItem item : items) {
            if (text.equals(item.getText())) {
                return item;
            }
            if (item instanceof javafx.scene.control.Menu menu) {
                MenuItem nested = findMenuItem(menu.getItems(), text);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    static java.util.List<String> titles(java.util.List<MenuItem> items) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (MenuItem item : items) {
            if (item.getText() != null) {
                out.add(item.getText());
            }
            if (item instanceof javafx.scene.control.Menu menu) {
                out.addAll(titles(menu.getItems()));
            }
        }
        return out;
    }

    static void clipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    static KeyEvent pressed(KeyCode code, boolean shift, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, false, false);
    }

    /** A primary-button press at local {@code (x, y)} / screen {@code (sx, sy)}; {@code shortcut} holds Ctrl. */
    static javafx.scene.input.MouseEvent mousePressed(double x, double y, double sx, double sy, boolean shortcut) {
        return mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, x, y, sx, sy, shortcut, 1);
    }

    static javafx.scene.input.MouseEvent mouse(
            javafx.event.EventType<javafx.scene.input.MouseEvent> type,
            double x,
            double y,
            double sx,
            double sy,
            boolean shortcut,
            int clicks) {
        boolean pressed = type != javafx.scene.input.MouseEvent.MOUSE_MOVED;
        return new javafx.scene.input.MouseEvent(
                type,
                x,
                y,
                sx,
                sy,
                pressed ? javafx.scene.input.MouseButton.PRIMARY : javafx.scene.input.MouseButton.NONE,
                clicks,
                false,
                shortcut,
                false,
                false,
                pressed,
                false,
                false,
                false,
                false,
                false,
                null);
    }

    static KeyEvent typed(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, character, KeyCode.UNDEFINED, false, false, false, false);
    }
}
