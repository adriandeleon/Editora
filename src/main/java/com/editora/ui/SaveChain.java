package com.editora.ui;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * Saves several buffers one after another without waiting on the FX thread (PERF-01, Pauta 6.0.1): each write runs
 * where the save path puts it, and the next starts when the one before has landed. What a command that must run on
 * the saved files goes on with, instead of a nested event loop per save.
 */
final class SaveChain {

    private SaveChain() {}

    /**
     * Saves each of {@code buffers} in turn with {@code saver} (which hands the outcome back on the FX thread), then
     * runs {@code then} with null once every one is saved, or with the first that was not; the ones after it are left.
     */
    static void all(
            List<EditorBuffer> buffers,
            BiConsumer<EditorBuffer, Consumer<Boolean>> saver,
            Consumer<@Nullable EditorBuffer> then) {
        if (buffers.isEmpty()) {
            then.accept(null);
            return;
        }
        EditorBuffer first = buffers.get(0);
        List<EditorBuffer> rest = List.copyOf(buffers.subList(1, buffers.size()));
        saver.accept(first, saved -> {
            if (Boolean.TRUE.equals(saved)) {
                all(rest, saver, then);
            } else {
                then.accept(first);
            }
        });
    }

    /**
     * True when none of {@code buffers} is edited or untitled. Otherwise they are saved one after another off the FX
     * thread (PERF-01, Pauta 6.0.1) and {@code again} — the command, asked again — runs once every write has landed
     * and left its buffer clean; a refused save stops it, having said why.
     */
    static boolean savedOrThen(
            List<EditorBuffer> buffers, BiConsumer<EditorBuffer, Consumer<Boolean>> saver, Runnable again) {
        List<EditorBuffer> dirty =
                buffers.stream().filter(b -> b.isDirty() || b.getPath() == null).toList();
        if (dirty.isEmpty()) {
            return true;
        }
        all(dirty, saver, failed -> {
            if (failed == null && dirty.stream().noneMatch(EditorBuffer::isDirty)) {
                again.run();
            }
        });
        return false;
    }
}
