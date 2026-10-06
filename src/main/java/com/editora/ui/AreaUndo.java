package com.editora.ui;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.util.UndoUtils;
import org.fxmisc.undo.UndoManagerFactory;

/**
 * The undo history of a RichTextFX area that is not an editor buffer. Every such area must choose one of
 * the two here ({@code RichTextAreaUndoPolicyTest} holds the main sources to it).
 *
 * <p>A RichTextFX area is born with an <em>unlimited</em> undo history, and that history records every
 * change to the document — a program's {@code appendText}, a console's head trim, a viewer's
 * {@code replaceText} — whether or not the area is editable. A console capped at 200,000 characters kept
 * every line it had ever shown: 111 MB after 200,000 lines, growing for as long as the program ran.
 */
final class AreaUndo {

    /** Undo steps kept by an editable field: plenty for a snippet body, and a bound all the same. */
    static final int FIELD_HISTORY = 300;

    private AreaUndo() {}

    /** For an area the user cannot edit — a console, a viewer, a preview: nothing is recorded. */
    static <A extends CodeArea> A none(A area) {
        area.setUndoManager(UndoUtils.noOpUndoManager());
        return area;
    }

    /** For an area the user types into: the default history, bounded to {@link #FIELD_HISTORY} steps. */
    static <A extends CodeArea> A bounded(A area) {
        area.setUndoManager(
                UndoUtils.plainTextUndoManager(area, UndoManagerFactory.fixedSizeHistoryFactory(FIELD_HISTORY)));
        return area;
    }
}
