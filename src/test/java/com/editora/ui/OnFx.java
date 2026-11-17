package com.editora.ui;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

import com.editora.testkit.Await;

/**
 * A condition read on the FX thread, for {@link Await} (QUAL-07, Pauta 6.0.1): the test thread asks the FX thread
 * after each pulse, so FX state is never read from another thread.
 */
final class OnFx {

    private OnFx() {}

    /** {@code condition}, evaluated on the FX thread each time it is asked. */
    static BooleanSupplier of(Callable<Boolean> condition) {
        return () -> {
            try {
                return FxTestSupport.callOnFx(condition);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /** {@code condition}, evaluated on the waiting thread (it may hop to the FX thread itself), as a supplier. */
    static BooleanSupplier checked(Callable<Boolean> condition) {
        return () -> {
            try {
                return condition.call();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /** Waits until {@code condition}, read on the FX thread, holds; fails with {@code what} after 30 s. */
    static void until(Callable<Boolean> condition, String what) throws InterruptedException {
        Await.until(of(condition), what);
    }

    /** Waits until {@code condition}, read on the FX thread, holds; fails with {@code what} after {@code timeout}. */
    static void until(Callable<Boolean> condition, String what, Duration timeout) throws InterruptedException {
        Await.until(of(condition), what, timeout);
    }

    /** Waits up to {@code timeout} for {@code condition}, read on the FX thread, and says whether it held. */
    static boolean holdsWithin(Callable<Boolean> condition, Duration timeout) throws InterruptedException {
        return Await.holdsWithin(of(condition), timeout);
    }

    /**
     * Waits until every settled-edit milestone of {@code buffer}'s last edit has run (its debounce timer is no longer
     * armed): what the highlighting, folding and server sync of an edit wait for.
     */
    static void untilEditsSettled(com.editora.editor.EditorBuffer buffer) throws InterruptedException {
        until(
                () -> FxTestSupport.<Long>field(FxTestSupport.field(buffer, "settledEdits"), "armedMillis") == -1,
                "the buffer's settled-edit milestones");
    }

    /**
     * Waits until {@code buffer}'s edit has settled ({@link #untilEditsSettled}), its highlighting has caught up with
     * the text, and its fold scan of the current text has answered.
     */
    static void untilHighlightAndFoldsSettled(com.editora.editor.EditorBuffer buffer) throws InterruptedException {
        untilEditsSettled(buffer);
        until(
                () -> {
                    Object dirty = FxTestSupport.field(FxTestSupport.field(buffer, "highlighter"), "highlightDirty");
                    return (Boolean) FxTestSupport.call(dirty, "isClean", new Class<?>[] {})
                            && buffer.getFoldManager().scansSettledForTest()
                            && FxTestSupport.<Long>field(FxTestSupport.field(buffer, "settledEdits"), "armedMillis")
                                    == -1;
                },
                "the buffer's highlighting and fold scan");
    }

    /** Waits for {@code count} FX pulses (each lays out and renders what changed before it). */
    static void pulses(int count) throws Exception {
        java.util.concurrent.CountDownLatch seen = new java.util.concurrent.CountDownLatch(count);
        javafx.animation.AnimationTimer counter = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                seen.countDown();
            }
        };
        FxTestSupport.runOnFx(counter::start);
        try {
            if (!seen.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for " + count + " FX pulses");
            }
        } finally {
            FxTestSupport.runOnFx(counter::stop);
        }
    }
}
