package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

/** Bounded transform history for delayed import edits; conflicting edits invalidate the whole batch. */
final class CompletionEditTracker {
    private final List<LspEditShift.Change> changes = new ArrayList<>();
    private int nameStart;
    private int nameEnd;
    private boolean valid = true;

    CompletionEditTracker(LspEditShift.Change acceptance, int nameStart, int nameEnd) {
        if (acceptance != null) changes.add(acceptance);
        this.nameStart = nameStart;
        this.nameEnd = nameEnd;
    }

    void changed(int offset, int removedLength, int insertedLength, LspEditShift.Change change) {
        if (!valid) return;
        if (changes.size() >= 128
                || (offset < nameEnd && offset + removedLength > nameStart)
                || (removedLength == 0 && offset > nameStart && offset < nameEnd)) {
            valid = false; // includes Undo of the acceptance and edits to the accepted symbol
            return;
        }
        if (offset + removedLength <= nameStart) {
            int delta = insertedLength - removedLength;
            nameStart += delta;
            nameEnd += delta;
        }
        changes.add(change);
    }

    List<LspTextEdit> translate(List<LspTextEdit> edits) {
        if (!valid || edits == null) return List.of();
        List<LspTextEdit> result = edits;
        for (var change : changes) {
            for (var edit : result) {
                // Touching the interior of the server's edit would overwrite newer user text.
                if (before(edit.startLine(), edit.startCol(), change.preEndLine(), change.preEndCol())
                        && before(change.startLine(), change.startCol(), edit.endLine(), edit.endCol()))
                    return List.of();
                // A zero-width import inside a removed span is equally stale.
                if (before(change.startLine(), change.startCol(), edit.startLine(), edit.startCol())
                        && before(edit.startLine(), edit.startCol(), change.preEndLine(), change.preEndCol()))
                    return List.of();
            }
            result = LspEditShift.shift(result, change);
        }
        return result;
    }

    private static boolean before(int line, int col, int otherLine, int otherCol) {
        return line < otherLine || (line == otherLine && col < otherCol);
    }
}
