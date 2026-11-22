package com.editora.editor;

import java.util.function.Consumer;

import com.editora.perf.OffFx;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * A command that searches the text from the caret — the matching bracket, the next balanced expression, every
 * occurrence of a word — worked out off the JavaFX thread (PERF-01, Pauta 6.2.0). The text and the caret are taken on
 * the FX thread; the search runs on a virtual thread named for it; its answer is acted on, on the FX thread, only if
 * the area still holds that text with the caret and the selection where they were. Otherwise it is dropped: the
 * command answered a state that is gone, and the user presses it again on the new one.
 *
 * <p>While one look is out for an area, another asked on the same area is refused ({@link #then} answers false): a
 * second press never runs on an answer meant for the first.
 */
public final class CaretLook {

    private CaretLook() {}

    /** The text and the caret a look searches. */
    public record At(String text, int caret, int anchor) {}

    /** A search from {@link At}; may answer null (nothing found). */
    @FunctionalInterface
    public interface Search<T> {
        @Nullable
        T find(At at);
    }

    private static final String EDITS_KEY = CaretLook.class.getName() + ".edits";
    private static final String BUSY_KEY = CaretLook.class.getName() + ".busy";

    /**
     * Searches {@code area}'s text from its caret off the FX thread, on a thread called {@code site}, then hands a
     * non-null answer to {@code then} on the FX thread if nothing moved. False, with nothing started, when a look for
     * this area is still out. FX thread.
     */
    public static <T> boolean then(String site, CodeArea area, Search<T> search, Consumer<T> then) {
        if (busy(area)) {
            return false;
        }
        long edits = edits(area);
        At at = new At(area.getText(), area.getCaretPosition(), area.getAnchor());
        area.getProperties().put(BUSY_KEY, Boolean.TRUE);
        OffFx.then(site, () -> search.find(at), answer -> {
            area.getProperties().remove(BUSY_KEY);
            T found = answer.value();
            if (found != null
                    && edits(area) == edits
                    && area.getCaretPosition() == at.caret()
                    && area.getAnchor() == at.anchor()) {
                then.accept(found);
            }
        });
        return true;
    }

    /** Whether a look is out for {@code area}. */
    public static boolean busy(CodeArea area) {
        return Boolean.TRUE.equals(area.getProperties().get(BUSY_KEY));
    }

    /** How many edits {@code area}'s document has had since it was first asked: a cheap version for any area. */
    static long edits(CodeArea area) {
        Object counter = area.getProperties().get(EDITS_KEY);
        if (counter instanceof long[] n) {
            return n[0];
        }
        long[] n = {0};
        area.plainTextChanges().subscribe(change -> n[0]++);
        area.getProperties().put(EDITS_KEY, n);
        return 0;
    }
}
