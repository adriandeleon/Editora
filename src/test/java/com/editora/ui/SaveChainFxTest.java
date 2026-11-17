package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SaveChain#savedOrThen}: a command that must run on saved files goes on now when nothing needs saving, else
 * once every edited or untitled buffer has been saved and left clean (PERF-01, Pauta 6.0.1).
 */
@Tag("fx")
class SaveChainFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer(String name, boolean dirty, boolean titled) {
        EditorBuffer b = new EditorBuffer();
        if (titled) {
            b.setPath(Path.of("/nonexistent", name));
        }
        b.setContent(name);
        if (dirty) {
            b.markUnsaved();
        }
        return b;
    }

    /** A save path that answers at once with {@code outcome}, cleaning the buffer only when {@code cleans}. */
    private static BiConsumer<EditorBuffer, Consumer<Boolean>> saver(
            List<EditorBuffer> saved, boolean outcome, boolean cleans) {
        return (b, then) -> {
            saved.add(b);
            if (cleans) {
                b.markClean();
            }
            then.accept(outcome);
        };
    }

    @Test
    void nothingEditedOrUntitledGoesOnNow() throws Exception {
        List<EditorBuffer> saved = new CopyOnWriteArrayList<>();
        AtomicInteger again = new AtomicInteger();
        boolean now = FxTestSupport.callOnFx(() -> SaveChain.savedOrThen(
                List.of(buffer("a", false, true), buffer("b", false, true)),
                saver(saved, true, true),
                again::incrementAndGet));
        assertTrue(now, "every buffer is saved: the command goes on now");
        assertEquals(List.of(), saved, "nothing is saved");
        assertEquals(0, again.get(), "and it is not asked again");
    }

    @Test
    void theEditedAndTheUntitledAreSavedThenTheCommandIsAskedAgain() throws Exception {
        List<EditorBuffer> saved = new CopyOnWriteArrayList<>();
        AtomicInteger again = new AtomicInteger();
        EditorBuffer clean = FxTestSupport.callOnFx(() -> buffer("clean", false, true));
        EditorBuffer edited = FxTestSupport.callOnFx(() -> buffer("edited", true, true));
        EditorBuffer untitled = FxTestSupport.callOnFx(() -> buffer("untitled", false, false));
        boolean now = FxTestSupport.callOnFx(() -> SaveChain.savedOrThen(
                List.of(clean, edited, untitled), saver(saved, true, true), again::incrementAndGet));
        assertFalse(now, "there was something to save first");
        assertEquals(List.of(edited, untitled), saved, "the edited and the untitled, in order; the clean one left");
        assertEquals(1, again.get(), "every write landed clean: the command is asked again");
    }

    @Test
    void aSaveThatLeavesItsBufferEditedDoesNotAskAgain() throws Exception {
        List<EditorBuffer> saved = new CopyOnWriteArrayList<>();
        AtomicInteger again = new AtomicInteger();
        EditorBuffer edited = FxTestSupport.callOnFx(() -> buffer("edited", true, true));
        boolean now = FxTestSupport.callOnFx(
                () -> SaveChain.savedOrThen(List.of(edited), saver(saved, true, false), again::incrementAndGet));
        assertFalse(now);
        assertEquals(List.of(edited), saved);
        assertTrue(FxTestSupport.callOnFx(edited::isDirty), "still edited after its save (edited meanwhile)");
        assertEquals(0, again.get(), "so the command does not run on an unsaved file");
    }

    @Test
    void aRefusedSaveStopsTheChainAndTheCommand() throws Exception {
        List<EditorBuffer> saved = new CopyOnWriteArrayList<>();
        AtomicInteger again = new AtomicInteger();
        EditorBuffer first = FxTestSupport.callOnFx(() -> buffer("first", true, true));
        EditorBuffer second = FxTestSupport.callOnFx(() -> buffer("second", true, true));
        boolean now = FxTestSupport.callOnFx(() ->
                SaveChain.savedOrThen(List.of(first, second), saver(saved, false, false), again::incrementAndGet));
        assertFalse(now);
        assertEquals(List.of(first), saved, "the one after the refused save is left");
        assertEquals(0, again.get());
    }
}
