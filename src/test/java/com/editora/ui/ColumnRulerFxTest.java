package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.shape.Line;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 80-column ruler, on both halves of its contract.
 *
 * <p><b>Correctness:</b> its x is a function of the glyph advance, the gutter width and the horizontal
 * scroll — so removing the gutter (Simple UI mode) must move it left, and restoring the gutter must put it
 * back. That is the behaviour the measurement gate below could plausibly break.
 *
 * <p><b>Cost:</b> a measure is two forced {@code VirtualFlow} layouts plus character-bounds queries, and it
 * used to run on every {@code viewportDirtyEvents} — which fires for a vertical scroll and for every edit,
 * neither of which can move the ruler. That was ~6–7 full measures per chrome toggle and one per keystroke.
 * These assert the gate holds, since a regression there is invisible from the ruler's position alone.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ColumnRulerFxTest {

    private FxWindowFixture fx;
    private Settings settings;
    private EditorBuffer buffer;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        settings = config.getSettings();

        Path file = Files.createTempFile("editora-ruler-", ".java");
        Files.writeString(file, "class A {\n" + ("    // " + "x".repeat(160) + "\n").repeat(60) + "}\n");
        FxTestSupport.runOnFx(() -> FxTestSupport.call(fx.controller, "openPath", new Class<?>[] {Path.class}, file));
        settle(40);
        buffer = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /**
     * The gutter's width is where column 0 starts, so a change to it must move the ruler. Driven by toggling
     * <b>line numbers</b> rather than Simple UI mode on purpose: Simple mode also hides the minimap, which
     * resizes the area — and a viewport <em>width</em> change is one of the gate's own triggers, so that
     * scenario would pass even if {@code refreshGutter} had stopped marking the ruler dirty. Line numbers
     * change the gutter and nothing else, which is exactly the case the mark exists for.
     */
    @Test
    void theRulerFollowsAGutterWidthChange() throws Exception {
        setLineNumbers(true);
        double withNumbers = rulerX();
        assertTrue(withNumbers > 0, "the ruler is placed with line numbers shown, was " + withNumbers);

        setLineNumbers(false);
        double withoutNumbers = rulerX();
        assertTrue(withoutNumbers > 0, "still placed with line numbers hidden, was " + withoutNumbers);
        assertTrue(
                withoutNumbers < withNumbers - 1,
                "a narrower gutter moves column 80 left: " + withNumbers + " -> " + withoutNumbers);

        setLineNumbers(true);
        assertEquals(withNumbers, rulerX(), 1.0, "restoring the gutter width puts the ruler back");
    }

    private void setLineNumbers(boolean on) {
        FxTestSupport.runOnFxUnchecked(() -> {
            settings.setShowLineNumbers(on);
            FxTestSupport.invokeWith(fx.controller, "applyViewSettingsToAllBuffers", Settings.class, settings);
        });
    }

    /**
     * The gate must not starve a measure that IS needed: a chrome toggle changes the gutter width, so the
     * ruler has to be re-placed. (The upper bound is deliberately not asserted — in this one-file fixture a
     * toggle produces few viewport-dirty events either way, so a "costs at most N" assertion here would pass
     * with the gate removed and give false confidence. The two below are what discriminate.)
     */
    @Test
    void aChromeToggleStillRemeasures() throws Exception {
        setSimple(false);
        settle(15);

        int measures = countMeasures(this::toggleSimpleModeCommand);
        try {
            assertTrue(measures >= 1, "the gutter changed, so the ruler must be re-placed");
        } finally {
            toggleSimpleModeCommand();
            settle(10);
        }
    }

    /** The toolbar/palette gesture itself, not a hand-assembled approximation of it. */
    private void toggleSimpleModeCommand() {
        FxTestSupport.runOnFxUnchecked(() -> FxTestSupport.invoke(fx.controller, "toggleSimpleMode"));
    }

    /**
     * Typing cannot move the ruler — the advance, the gutter and the horizontal scroll are all unchanged —
     * yet every edit fires a viewport-dirty event. Zero measures, on the hottest path there is.
     */
    @Test
    void typingDoesNotRemeasureTheRuler() throws Exception {
        setSimple(false);
        settle(15);

        int measures = countMeasures(() -> {
            FxTestSupport.runOnFxUnchecked(() -> buffer.getFocusedArea().insertText(0, "// typed\n"));
            settleUnchecked(10);
            FxTestSupport.runOnFxUnchecked(() -> buffer.getFocusedArea().insertText(0, "// more\n"));
        });
        assertEquals(0, measures, "an edit must not re-measure the ruler");
    }

    private int countMeasures(Runnable action) throws Exception {
        AtomicInteger counter = counter();
        settle(10);
        counter.set(0);
        action.run();
        settle(20);
        return counter.get();
    }

    /** The package-private test seam on {@code EditorBuffer} (classpath tests, so access is unrestricted). */
    private static AtomicInteger counter() throws Exception {
        var f = EditorBuffer.class.getDeclaredField("RULER_MEASURES_FOR_TEST");
        f.setAccessible(true);
        return (AtomicInteger) f.get(null);
    }

    /** Root-local x of the buffer's column ruler, after letting the deferred measure run. */
    private double rulerX() throws Exception {
        settle(20);
        Line ruler = FxTestSupport.field(buffer, "columnRuler");
        return FxTestSupport.callOnFx(() -> ruler.isVisible() ? ruler.getStartX() : -1);
    }

    private void setSimple(boolean on) {
        FxTestSupport.runOnFxUnchecked(() -> {
            settings.setSimpleMode(on);
            FxTestSupport.invoke(fx.controller, "applyChromeVisibility");
            FxTestSupport.invokeWith(fx.controller, "applyViewSettingsToAllBuffers", Settings.class, settings);
        });
    }

    private static void settle(int pulses) throws Exception {
        for (int i = 0; i < pulses; i++) {
            FxTestSupport.runOnFx(() -> {});
        }
    }

    private static void settleUnchecked(int pulses) {
        for (int i = 0; i < pulses; i++) {
            FxTestSupport.runOnFxUnchecked(() -> {});
        }
    }
}
