package com.editora.ui;

import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;

import com.editora.i18n.Messages;
import org.testfx.api.FxToolkit;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Shared plumbing for the headless-JavaFX tests (tagged {@code fx}). Boots the toolkit once via the
 * vendored Monocle backend (no display/xvfb), runs work on the FX thread, and reads private
 * {@code @FXML} nodes by reflection (tests run on the classpath as the unnamed module, so
 * {@code setAccessible} is unrestricted).
 *
 * <p>Mirrors the prerequisites {@code App.start} sets up before building any window: register fonts,
 * initialise the i18n catalog, and pin the FXML/context classloader.
 */
final class FxTestSupport {

    private static volatile boolean booted;

    private FxTestSupport() {}

    /** Idempotently start the FX toolkit + the static singletons a window build needs. */
    static synchronized void bootToolkit() throws Exception {
        if (booted) {
            return;
        }
        FxToolkit.registerPrimaryStage(); // starts the Monocle Headless Glass platform
        runOnFx(() -> {
            Fonts.load();
            Messages.init("en");
            // AtlantaFX user-agent stylesheet defines the -color-* vars app.css/syntax.css resolve against;
            // without it the scene logs harmless ClassCastException CSS warnings for every -color lookup.
            javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(Themes.DEFAULT));
            FXMLLoader.setDefaultClassLoader(FxTestSupport.class.getClassLoader());
            Thread.currentThread().setContextClassLoader(FxTestSupport.class.getClassLoader());
        });
        booted = true;
    }

    /** Run {@code task} on the FX thread and block until it finishes, rethrowing any failure. */
    static void runOnFx(Runnable task) throws Exception {
        if (Platform.isFxApplicationThread()) {
            task.run();
            return;
        }
        var error = new AtomicReference<Throwable>();
        var latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(20, SECONDS)) {
            throw new IllegalStateException("FX task timed out");
        }
        if (error.get() != null) {
            throw new RuntimeException(error.get());
        }
    }

    /** Compute a value on the FX thread and return it (blocking). */
    static <T> T callOnFx(Callable<T> task) throws Exception {
        var result = new AtomicReference<T>();
        runOnFx(() -> {
            try {
                result.set(task.call());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return result.get();
    }

    /** Invoke a private no-arg method by name (walks up the class hierarchy). */
    static void invoke(Object target, String method) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var m = c.getDeclaredMethod(method);
                m.setAccessible(true);
                m.invoke(target);
                return;
            } catch (NoSuchMethodException e) {
                // try the superclass
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalArgumentException("No no-arg method '" + method + "' on " + target.getClass());
    }

    /** Invoke a private method with explicit parameter types and return its result (walks the hierarchy). */
    static Object call(Object target, String method, Class<?>[] types, Object... args) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var m = c.getDeclaredMethod(method, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException e) {
                // try the superclass
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalArgumentException("No method '" + method + "' on " + target.getClass());
    }

    /** Read a private/{@code @FXML} field by name from any object (walks up the class hierarchy). */
    @SuppressWarnings("unchecked")
    static <T> T field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(target);
            } catch (NoSuchFieldException e) {
                // try the superclass
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalArgumentException("No field '" + name + "' on " + target.getClass());
    }
}
