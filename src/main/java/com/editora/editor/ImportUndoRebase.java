package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.RichTextChange;
import org.fxmisc.richtext.model.TextChange;

/** Commutes disjoint import edits before later typing, without changing the current document. */
final class ImportUndoRebase {
    record Plan<C>(List<C> accepted, List<List<C>> later) {}

    private ImportUndoRebase() {}

    /** Null means overlap/unknown change/too much history: keep ordinary chronological undo. */
    static <C> Plan<C> plan(List<C> accepted, List<List<C>> later, List<C> imports) {
        int count = accepted.size() + imports.size();
        for (var batch : later) count += batch.size();
        if (count > 256 || accepted.isEmpty() || imports.isEmpty()) return null;
        var movedImports = new ArrayList<>(imports);
        var movedLater = new ArrayList<List<C>>(later);
        for (int batchIndex = later.size() - 1; batchIndex >= 0; batchIndex--) {
            var batch = new ArrayList<>(later.get(batchIndex));
            for (int index = batch.size() - 1; index >= 0; index--) {
                C userEdit = batch.get(index);
                for (int i = 0; i < movedImports.size(); i++) {
                    C importEdit = movedImports.get(i);
                    if (!(userEdit instanceof TextChange<?, ?> user)
                            || !(importEdit instanceof TextChange<?, ?> addition)) return null;
                    if (addition.getRemovalEnd() <= user.getPosition() && addition.getPosition() < user.getPosition()) {
                        userEdit = shift(userEdit, addition.getNetLength());
                    } else if (addition.getPosition() >= user.getInsertionEnd()
                            && addition.getPosition() > user.getPosition()) {
                        importEdit = shift(importEdit, -user.getNetLength());
                    } else return null; // overlap, or ambiguous insertion at the same boundary
                    if (userEdit == null || importEdit == null) return null;
                    movedImports.set(i, importEdit);
                }
                batch.set(index, userEdit);
            }
            movedLater.set(batchIndex, List.copyOf(batch));
        }
        var combined = new ArrayList<>(accepted);
        combined.addAll(movedImports);
        return new Plan<>(List.copyOf(combined), List.copyOf(movedLater));
    }

    @SuppressWarnings("unchecked")
    private static <C> C shift(C change, int delta) {
        if (delta == 0) return change;
        int position;
        try {
            position = Math.addExact(((TextChange<?, ?>) change).getPosition(), delta);
        } catch (ArithmeticException error) {
            return null;
        }
        if (position < 0) return null;
        if (change instanceof PlainTextChange plain)
            return (C) new PlainTextChange(position, plain.getRemoved(), plain.getInserted());
        if (change instanceof RichTextChange<?, ?, ?> rich) return (C) moveRich(rich, position);
        return null;
    }

    private static <PS, SEG, S> RichTextChange<PS, SEG, S> moveRich(RichTextChange<PS, SEG, S> change, int position) {
        return new RichTextChange<>(position, change.getRemoved(), change.getInserted());
    }
}
