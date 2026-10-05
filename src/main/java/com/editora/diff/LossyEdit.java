package com.editora.diff;

import java.util.ArrayList;
import java.util.List;

import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;

/**
 * Carries an edit made in a lossy text control back onto the exact text it was showing. A JavaFX
 * {@code TextArea} silently drops control characters (form feed, ESC, …), so text round-tripped through one
 * loses them on every line — including the lines the user never touched. Given the exact lines, the lines the
 * control showed for them and the lines after the user's edit, only the lines the user changed are taken
 * from the control; the rest keep their exact original form. Pure and unit-tested.
 */
public final class LossyEdit {

    private LossyEdit() {}

    private static final int MAX_EDITS = 20_000;

    /**
     * {@code edited} with every line the user left alone restored from {@code exact}. {@code shown} must be
     * the control's rendering of {@code exact}, line for line; when it is not (or the edit is too large to
     * align) {@code edited} is returned as it is.
     */
    public static List<String> restore(List<String> exact, List<String> shown, List<String> edited) {
        if (exact.size() != shown.size()) {
            return edited;
        }
        Patch<String> patch = BoundedDiff.diff(shown, edited, MAX_EDITS);
        if (patch == null) {
            return edited;
        }
        List<String> out = new ArrayList<>(edited.size());
        int next = 0;
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            int position = delta.getSource().getPosition();
            while (next < position) {
                out.add(exact.get(next++));
            }
            out.addAll(delta.getTarget().getLines());
            next += delta.getSource().size();
        }
        while (next < exact.size()) {
            out.add(exact.get(next++));
        }
        return out;
    }
}
