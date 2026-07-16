package com.editora.ui;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.completion.Completion;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression for the completion re-offering itself the moment you accept it. The accept paths raised a
 * {@code suppressCompletion} boolean and cleared it in a {@code Platform.runLater} — i.e. on the next pulse
 * (~16 ms) — while the auto-trigger it exists to gate is the ~280 ms debounce on the accept's own edit. The
 * flag was always back down by then, so it suppressed nothing: accepting "apple" immediately grew a fresh
 * ghost ("s"), and accepting an LSP item re-opened the popup on the word just completed.
 *
 * <p>Asserts on whether the completion source is consulted at all (the gate is the first thing
 * {@code updateCompletion} evaluates), and lets the FX pulse run before triggering — which is where the old
 * boolean was cleared, and what the real debounce always outlasts.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompletionAfterAcceptFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private final AtomicInteger consulted = new AtomicInteger();

    private EditorBuffer buffer() {
        consulted.set(0); // one instance per class (PER_CLASS) — the count must not carry between tests
        EditorBuffer b = new EditorBuffer();
        b.setCompletionProvider((snippetLang, dictLang, prefix, prose) -> {
            consulted.incrementAndGet();
            return List.of(Completion.word(prefix + "s", null));
        });
        b.setAutocomplete(true, true, true, true);
        b.setContent("app");
        b.getArea().moveTo(3);
        return b;
    }

    /** Fires the debounced auto-trigger the way the 280 ms subscription does. */
    private static void autoTrigger(EditorBuffer b) {
        FxTestSupport.call(
                b,
                "updateCompletion",
                new Class<?>[] {org.fxmisc.richtext.CodeArea.class, boolean.class},
                b.getArea(),
                false);
    }

    /** The popup's accept path (replaces the typed prefix with the item). */
    private static void accept(EditorBuffer b, String insert) {
        FxTestSupport.call(
                b,
                "acceptCompletion",
                new Class<?>[] {org.fxmisc.richtext.CodeArea.class, Completion.class},
                b.getArea(),
                Completion.word(insert, null));
    }

    @Test
    void acceptingDoesNotRe_offerCompletionOnTheWordJustAccepted() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(this::buffer);
        FxTestSupport.runOnFx(() -> autoTrigger(b));
        assertEquals(1, consulted.get(), "completion offered while typing 'app'");

        FxTestSupport.runOnFx(() -> accept(b, "apple"));
        assertEquals("apple", FxTestSupport.callOnFx(() -> b.getArea().getText()));

        FxTestSupport.runOnFx(() -> {}); // the pulse the old boolean was cleared on...
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(() -> autoTrigger(b)); // ...long before the debounce actually fires
        assertEquals(1, consulted.get(), "the accept's own edit must not trigger a fresh completion");
    }

    @Test
    void theUsersNextEditReEnablesCompletion() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(this::buffer);
        FxTestSupport.runOnFx(() -> accept(b, "apple"));
        FxTestSupport.runOnFx(() -> {});
        // Suppression is stamped to the accept's own edit, so the user typing on must lift it.
        FxTestSupport.runOnFx(() -> b.getArea().insertText(b.getArea().getLength(), "t"));
        FxTestSupport.runOnFx(() -> autoTrigger(b));
        assertEquals(1, consulted.get(), "typing after an accept completes normally again");
    }

    @Test
    void manualInvokeIsNeverSuppressed() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(this::buffer);
        FxTestSupport.runOnFx(() -> accept(b, "apple"));
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                b,
                "updateCompletion",
                new Class<?>[] {org.fxmisc.richtext.CodeArea.class, boolean.class},
                b.getArea(),
                true)); // the edit.completion command
        assertEquals(1, consulted.get(), "an explicit invoke overrides the post-accept suppression");
    }
}
