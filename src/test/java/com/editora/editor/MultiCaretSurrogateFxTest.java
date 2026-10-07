package com.editora.editor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Add caret below" without screen geometry (the edge caret is scrolled out of view, or the area is not
 * laid out) places the new caret by UTF-16 column. That column must not fall between the two halves of an
 * emoji: typing there splits the character into two unpaired surrogates, which no encoding can save.
 */
@Tag("fx")
class MultiCaretSurrogateFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void aCaretAddedByColumnNeverLandsInsideASurrogatePair() throws Exception {
        runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            try {
                buffer.setMultiCaretEnabled(true);
                buffer.setContent("abcdef\nab😀cd\nxyz123\n");
                buffer.getArea().moveTo(3); // column 3: between the halves of the emoji one line down
                buffer.addCaretBelow();
                assertTrue(buffer.hasMultipleCarets());

                MultiCarets carets = (MultiCarets) field(buffer, "multiCaret");
                carets.getManager().typeText("X");

                String text = buffer.getContent();
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    boolean paired = Character.isHighSurrogate(c)
                            ? i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))
                            : !Character.isLowSurrogate(c) || (i > 0 && Character.isHighSurrogate(text.charAt(i - 1)));
                    assertTrue(paired, "unpaired surrogate at " + i + " in " + text.replace("\n", "\\n"));
                }
                assertEquals("abcXdef\nabX😀cd\nxyz123\n", text);
            } finally {
                buffer.dispose();
            }
        });
    }

    private static Object field(Object target, String name) {
        try {
            var f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void runOnFx(Runnable task) throws Exception {
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
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX task timed out");
        if (failure.get() instanceof Error e) {
            throw e;
        }
        if (failure.get() instanceof Exception e) {
            throw e;
        }
    }
}
