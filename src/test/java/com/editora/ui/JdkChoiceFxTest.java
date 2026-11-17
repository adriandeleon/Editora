package com.editora.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.ComboBox;

import com.editora.perf.BlockingSites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A JDK list is filled from a look off the FX thread (PERF-01, Pauta 6.0.1): at once it holds the inherited choice
 * and the selected JDK, selected; the look's list then comes in, the selection kept, and a later fill wins.
 */
@Tag("fx")
class JdkChoiceFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void stopListening() {
        BlockingSites.listenForTest(null);
    }

    @Test
    void theSelectedJdkIsThereAtOnceAndTheLookRunsOffTheFxThread() throws Exception {
        List<String> onFx = new CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> {
            if (thread.getName().contains("JavaFX")) {
                onFx.add(site);
            }
        });
        AtomicReference<ComboBox<JdkChoice>> box = new AtomicReference<>();
        AtomicReference<JdkChoice> selectedAtOnce = new AtomicReference<>();
        AtomicReference<Integer> sizeAtOnce = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> {
            ComboBox<JdkChoice> combo = new ComboBox<>();
            box.set(combo);
            JdkChoice.select(combo, "/nowhere/jdk-7");
            selectedAtOnce.set(combo.getValue());
            sizeAtOnce.set(combo.getItems().size());
        });
        assertEquals("/nowhere/jdk-7", selectedAtOnce.get().home(), "the selected home, selected at once");
        assertEquals(2, sizeAtOnce.get(), "the inherited choice and the selected one");
        JdkChoice selected = selectedAtOnce.get();
        waitFor(() -> box.get().getItems().stream().anyMatch(c -> c.major() > 0), "the machine's JDKs land");
        AtomicReference<JdkChoice> after = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> after.set(box.get().getValue()));
        assertSame(selected, after.get(), "the same item stays selected: no change is heard");
        assertTrue(onFx.isEmpty(), "no JDK look on the FX thread: " + onFx);
    }

    @Test
    void aLaterFillWins() throws Exception {
        AtomicReference<ComboBox<JdkChoice>> box = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> {
            ComboBox<JdkChoice> combo = new ComboBox<>();
            box.set(combo);
            JdkChoice.select(combo, "/first/jdk");
            JdkChoice.select(combo, "/second/jdk");
        });
        waitFor(() -> box.get().getItems().stream().anyMatch(c -> c.major() > 0), "the JDKs land");
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertEquals("/second/jdk", box.get().getValue().home());
            assertFalse(box.get().getItems().stream().anyMatch(c -> c.home().equals("/first/jdk")), "the first fill");
        });
    }

    private static void waitFor(java.util.function.BooleanSupplier done, String what) throws Exception {
        FxTestSupport.awaitFxCondition(done::getAsBoolean, what, java.time.Duration.ofSeconds(10));
    }
}
