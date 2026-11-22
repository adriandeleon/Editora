package com.editora.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;

import com.editora.i18n.Messages;
import org.testfx.api.FxToolkit;

import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Shared plumbing for the headless-JavaFX tests (tagged {@code fx}). Boots the toolkit once via
 * JavaFX's built-in Headless Glass platform (available since 26; no display/xvfb), runs work on the FX thread, and
 * reads private {@code @FXML} nodes by reflection (tests run on the classpath as the unnamed module, so
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
        FxToolkit.registerPrimaryStage(); // starts the built-in Headless Glass platform (-Dglass.platform=Headless)
        runOnFx(() -> {
            Fonts.load();
            Messages.init("en");
            // AtlantaFX user-agent stylesheet defines the -color-* vars app.css/syntax.css resolve against;
            // without it the scene logs harmless ClassCastException CSS warnings for every -color lookup.
            javafx.application.Application.setUserAgentStylesheet(Themes.stylesheetFor(Themes.DEFAULT));
            FXMLLoader.setDefaultClassLoader(FxTestSupport.class.getClassLoader());
            Thread.currentThread().setContextClassLoader(FxTestSupport.class.getClassLoader());
            ContextMenuDismissal.install();
            MenuRowLayout.install(); // as App.start: every menu keeps the icon column
        });
        booted = true;
    }

    /**
     * {@link #runOnFx} without the checked exception, for use inside a lambda that cannot declare one.
     * A failure still fails the test — it is rethrown unchecked rather than swallowed.
     */
    static void runOnFxUnchecked(Runnable task) {
        try {
            runOnFx(task);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    /** Wait until all FX work queued before this call has completed. */
    static void drainFx() throws Exception {
        runOnFx(() -> {});
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

    /**
     * Waits until {@code condition}, read on the FX thread, holds: re-checked after each FX pulse by {@link
     * com.editora.testkit.Await} (QUAL-07, Pauta 6.0.1), failing with {@code what} after {@code timeout}.
     */
    static void awaitFxCondition(Callable<Boolean> condition, String what, java.time.Duration timeout)
            throws InterruptedException {
        com.editora.testkit.Await.until(fxCondition(condition), what, timeout);
    }

    /** As {@link #awaitFxCondition(Callable, String, java.time.Duration)} with the default timeout. */
    static void awaitFxCondition(Callable<Boolean> condition, String what) throws InterruptedException {
        com.editora.testkit.Await.until(fxCondition(condition), what);
    }

    /** Waits until {@code value}, read on the FX thread, is not null, and returns it. */
    static <T> T awaitFxValue(Callable<T> value, String what, java.time.Duration timeout) throws InterruptedException {
        AtomicReference<T> found = new AtomicReference<>();
        com.editora.testkit.Await.until(
                fxCondition(() -> {
                    T t = value.call();
                    found.set(t);
                    return t != null;
                }),
                what,
                timeout);
        return found.get();
    }

    /**
     * Waits until {@code value}, read on the calling thread (it may itself call {@link #callOnFx}), is not null, and
     * returns it, re-checked by {@link com.editora.testkit.Await}.
     */
    static <T> T awaitValue(Callable<T> value, String what, java.time.Duration timeout) throws InterruptedException {
        AtomicReference<T> found = new AtomicReference<>();
        com.editora.testkit.Await.until(
                unchecked(() -> {
                    T t = value.call();
                    found.set(t);
                    return t != null;
                }),
                what,
                timeout);
        return found.get();
    }

    /** {@code condition}, read on the calling thread, as a {@link java.util.function.BooleanSupplier}. */
    static java.util.function.BooleanSupplier unchecked(Callable<Boolean> condition) {
        return () -> {
            try {
                return Boolean.TRUE.equals(condition.call());
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Waits until every settled-edit milestone of {@code buffer}'s last edit has fired (its lint, highlighting,
     * preview reload, …), then drains the FX queue: what a test once slept past the slowest milestone for.
     */
    static void awaitSettled(com.editora.editor.EditorBuffer buffer) throws Exception {
        awaitFxCondition(() -> !buffer.settledEditsPendingForTest(), "the settled-edit pulse");
        drainFx();
    }

    /** {@code condition} as a {@link java.util.function.BooleanSupplier} read on the FX thread. */
    static java.util.function.BooleanSupplier fxCondition(Callable<Boolean> condition) {
        return () -> {
            try {
                return Boolean.TRUE.equals(callOnFx(condition));
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * The scale every screenshot is written at (DOCS-18, Pauta 6.0.1): twice the scene's size, as a HiDPI screen
     * shows it, so the website and the README never carry a blurry 1x picture.
     */
    static final double SHOT_SCALE = 2;

    /**
     * Renders {@code window} at {@link #SHOT_SCALE} from its next pulse on: a canvas (the Project Map, the terminal)
     * rasterises at its window's render scale, so without this it would come out at 1x, stretched. Call on the FX
     * thread.
     */
    static void renderAtShotScale(javafx.stage.Window window) {
        if (window.getRenderScaleX() != SHOT_SCALE || window.getRenderScaleY() != SHOT_SCALE) {
            window.setRenderScaleX(SHOT_SCALE);
            window.setRenderScaleY(SHOT_SCALE);
        }
    }

    /**
     * {@code scene}'s root drawn at {@link #SHOT_SCALE}: an image twice the scene's width and height, on the scene's
     * own fill. Call on the FX thread.
     */
    static javafx.scene.image.WritableImage snapshotAtShotScale(javafx.scene.Scene scene) {
        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setTransform(javafx.scene.transform.Transform.scale(SHOT_SCALE, SHOT_SCALE));
        javafx.scene.paint.Paint fill = scene.getFill();
        params.setFill(fill == null ? javafx.scene.paint.Color.TRANSPARENT : fill);
        // The viewport keeps the image to the scene's own size even when a node lies outside it.
        params.setViewport(
                new javafx.geometry.Rectangle2D(0, 0, scene.getWidth() * SHOT_SCALE, scene.getHeight() * SHOT_SCALE));
        return scene.getRoot().snapshot(params, null);
    }

    /** {@code image} as an ARGB {@link java.awt.image.BufferedImage}, to write with ImageIO or composite. */
    static java.awt.image.BufferedImage toBufferedImage(javafx.scene.image.Image image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        java.awt.image.BufferedImage out =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[w * h];
        image.getPixelReader().getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, w);
        out.setRGB(0, 0, w, h, argb, 0, w);
        return out;
    }

    /**
     * Writes {@code scene} as a PNG at {@link #SHOT_SCALE} to {@code file}: the window is switched to that render
     * scale, two pulses lay out and render it, then the scene is drawn. Call off the FX thread.
     */
    static java.awt.image.BufferedImage writeShot(javafx.scene.Scene scene, java.nio.file.Path file) throws Exception {
        runOnFx(() -> renderAtShotScale(scene.getWindow()));
        PulseChecks.pulses(2);
        java.awt.image.BufferedImage image = toBufferedImage(callOnFx(() -> snapshotAtShotScale(scene)));
        java.nio.file.Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        javax.imageio.ImageIO.write(image, "png", file.toFile());
        return image;
    }

    /** Calls a private one-argument method, searching the class hierarchy like {@link #invoke}. */
    static Object invokeWith(Object target, String method, Class<?> paramType, Object arg) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var m = c.getDeclaredMethod(method, paramType);
                m.setAccessible(true);
                return m.invoke(target, arg);
            } catch (NoSuchMethodException e) {
                // try the superclass
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalArgumentException("no such method: " + method);
    }

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

    /**
     * Read a private/{@code @FXML} field by name from any object (walks up the class hierarchy).
     *
     * <p>A {@link MainController} panel field ({@code gitPanel}, {@code projectPanel}, …) is {@code null} until
     * its tool window first opens: the panel is built on first use by the private no-argument accessor of the
     * same name. A test that reaches for such a panel wants the panel, not the fact that nothing has built
     * it yet, so a null window field with a same-named accessor is read through the accessor, on the FX
     * thread. A test about laziness itself asks the {@link ToolWindow} ({@code contentIfBuilt}) instead.
     */
    @SuppressWarnings("unchecked")
    static <T> T field(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object value = f.get(target);
                if (value == null && target instanceof MainController) {
                    value = builtByAccessor(target, c, name);
                }
                return (T) value;
            } catch (NoSuchFieldException e) {
                // try the superclass
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new IllegalArgumentException("No field '" + name + "' on " + target.getClass());
    }

    /** The value of {@code target}'s private no-argument method {@code name}, run on the FX thread, or null. */
    private static Object builtByAccessor(Object target, Class<?> c, String name) {
        Method accessor;
        try {
            accessor = c.getDeclaredMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
        accessor.setAccessible(true);
        java.util.concurrent.Callable<Object> build = () -> accessor.invoke(target);
        try {
            return javafx.application.Platform.isFxApplicationThread() ? build.call() : callOnFx(build);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
