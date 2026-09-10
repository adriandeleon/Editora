package com.editora.ui;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("fx")
class AsyncTestScopeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void reportsFailureFromATestOwnedWorker() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            try (AsyncTestScope async = new AsyncTestScope()) {
                async.start("failing-test-worker", () -> {
                    throw new IllegalStateException("worker failed");
                });
            }
        });

        assertEquals("worker failed", failure.getMessage());
    }

    @Test
    void reportsFailureFromAnUnobservedFxCallback() {
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            try (AsyncTestScope async = new AsyncTestScope()) {
                Platform.runLater(() -> {
                    throw new IllegalStateException("FX callback failed");
                });
                async.awaitFx();
            }
        });

        assertEquals("FX callback failed", failure.getMessage());
    }
}
