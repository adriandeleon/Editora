package org.adriandeleon.editora;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsViewFxmlTest {
    private static final AtomicBoolean JAVA_FX_STARTED = new AtomicBoolean();

    @BeforeAll
    static void startJavaFx() throws Exception {
        Assumptions.assumeTrue(canStartJavaFxToolkit(),
                "Skipping JavaFX FXML test because no GUI display is available");
        if (JAVA_FX_STARTED.compareAndSet(false, true)) {
            CountDownLatch startupLatch = new CountDownLatch(1);
            Platform.startup(startupLatch::countDown);
            assertTrue(startupLatch.await(5, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        }
    }

    @Test
    void settingsViewFxmlLoads() throws Exception {
        AtomicReference<Parent> rootReference = new AtomicReference<>();
        AtomicReference<Object> controllerReference = new AtomicReference<>();
        runOnFxThread(() -> {
            FXMLLoader loader = new FXMLLoader(SettingsController.class.getResource("settings-view.fxml"));
            rootReference.set(loader.load());
            controllerReference.set(loader.getController());
        });

        assertNotNull(rootReference.get());
        assertInstanceOf(SettingsController.class, controllerReference.get());
    }

    private static void runOnFxThread(ThrowingRunnable action) throws Exception {
        CountDownLatch finishedLatch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                finishedLatch.countDown();
            }
        });
        assertTrue(finishedLatch.await(5, TimeUnit.SECONDS), "JavaFX action timed out");
        if (failure.get() != null) {
            if (failure.get() instanceof Exception exception) {
                throw exception;
            }
            if (failure.get() instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(failure.get());
        }
    }

    private static boolean canStartJavaFxToolkit() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("linux")) {
            return true;
        }

        if (Boolean.getBoolean("java.awt.headless")) {
            return false;
        }

        Map<String, String> env = System.getenv();
        return hasText(env.get("DISPLAY"))
                || hasText(env.get("WAYLAND_DISPLAY"))
                || hasText(env.get("MIR_SOCKET"));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}

