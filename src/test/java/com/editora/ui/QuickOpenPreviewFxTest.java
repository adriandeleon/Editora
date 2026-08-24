package com.editora.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.control.ListView;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The picker preview contract. Two halves of it are ordering-sensitive in a way that reads fine and is
 * wrong: preview must <em>not</em> fire for the selection the picker makes as it opens (or the editor
 * jumps the instant the picker appears), and the "was it chosen" flag must be set before {@code hide()},
 * because hiding is what runs the cancel hook — set it after and every choice also restores, silently
 * undoing the navigation the user just asked for.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuickOpenPreviewFxTest {

    private FxWindowFixture fx;
    private OverlayHost overlay;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        overlay = FxTestSupport.field(fx.controller, "overlayHost");
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** A picker over three strings, recording preview/cancel/choose calls. */
    private record Harness(
            QuickOpen<String> picker,
            AtomicInteger previews,
            AtomicInteger cancels,
            AtomicInteger chooses,
            List<String> previewed) {}

    private Harness harness() throws Exception {
        AtomicInteger previews = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger chooses = new AtomicInteger();
        List<String> previewed = new java.util.ArrayList<>();
        QuickOpen<String> picker = FxTestSupport.callOnFx(() -> {
            QuickOpen<String> p = new QuickOpen<>(
                    "Test",
                    "Filter…",
                    () -> List.of("alpha", "beta", "gamma"),
                    s -> s,
                    s -> "",
                    s -> chooses.incrementAndGet());
            p.setOverlayHost(overlay);
            p.setPreview(
                    s -> {
                        previews.incrementAndGet();
                        previewed.add(s);
                    },
                    cancels::incrementAndGet);
            return p;
        });
        return new Harness(picker, previews, cancels, chooses, previewed);
    }

    @SuppressWarnings("unchecked")
    private static ListView<String> listOf(QuickOpen<String> picker) throws Exception {
        return FxTestSupport.field(picker, "list");
    }

    @Test
    void openingThePickerPreviewsNothing() throws Exception {
        Harness h = harness();
        FxTestSupport.runOnFx(() -> h.picker().show(null));
        assertEquals(0, h.previews().get(), "the selection made on open is not an expressed intent");
        FxTestSupport.runOnFx(() -> overlay.hide());
    }

    @Test
    void movingTheSelectionPreviewsIt() throws Exception {
        Harness h = harness();
        FxTestSupport.runOnFx(() -> h.picker().show(null));
        FxTestSupport.runOnFx(() -> {
            try {
                listOf(h.picker()).getSelectionModel().select(2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(1, h.previews().get());
        assertEquals(List.of("gamma"), h.previewed());
        FxTestSupport.runOnFx(() -> overlay.hide());
    }

    @Test
    void dismissingRestoresWhatWasPreviewed() throws Exception {
        Harness h = harness();
        FxTestSupport.runOnFx(() -> h.picker().show(null));
        FxTestSupport.runOnFx(() -> {
            try {
                listOf(h.picker()).getSelectionModel().select(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        FxTestSupport.runOnFx(() -> overlay.hide());
        assertEquals(1, h.cancels().get(), "Esc must put the editor back where it was");
        assertEquals(0, h.chooses().get());
    }

    @Test
    void choosingDoesNotRestore() throws Exception {
        Harness h = harness();
        FxTestSupport.runOnFx(() -> h.picker().show(null));
        FxTestSupport.runOnFx(() -> {
            try {
                listOf(h.picker()).getSelectionModel().select(1);
                FxTestSupport.invoke(h.picker(), "chooseSelected");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertEquals(1, h.chooses().get());
        assertEquals(0, h.cancels().get(), "restoring after a choice would undo the navigation just asked for");
    }

    @Test
    void previewIsInertWhenNoneWasConfigured() throws Exception {
        // The overwhelming majority of pickers set no preview; they must be entirely unaffected.
        QuickOpen<String> plain = FxTestSupport.callOnFx(() -> {
            QuickOpen<String> p = new QuickOpen<>("Test", "Filter…", () -> List.of("a", "b"), s -> s, s -> "", s -> {});
            p.setOverlayHost(overlay);
            return p;
        });
        FxTestSupport.runOnFx(() -> plain.show(null));
        FxTestSupport.runOnFx(() -> {
            try {
                listOf(plain).getSelectionModel().select(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        FxTestSupport.runOnFx(() -> overlay.hide());
        assertTrue(true, "reaching here without a NullPointerException is the assertion");
    }
}
