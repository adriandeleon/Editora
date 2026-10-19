package com.editora.editor;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.Selection;

/**
 * Turns RichTextFX's current-line highlight on and off without leaking it. Turning it off only takes the line
 * selection out of the area's selection set: the selection itself stays subscribed to the document and, on every
 * edit, lays out the caret's whole paragraph to find the visual line — ~230 ms a keystroke at the end of a 2 MB
 * minified line, with the highlight long switched off. So the selection is disposed as well. FX thread.
 */
final class LineHighlighter {

    private LineHighlighter() {}

    private static final String WANTED = "editora.lineHighlightWanted";

    /**
     * Records the current-line highlight setting {@code wanted} on {@code area} and shows the highlight when it is
     * wanted and not {@code suppressed} (large-file mode, or a loading shell that may yet turn out to be one: once
     * on, RichTextFX keeps watching the caret's bounds even after it is turned off, so it is not turned on early).
     */
    static void set(CodeArea area, boolean wanted, boolean suppressed) {
        area.getProperties().put(WANTED, wanted);
        set(area, wanted && !suppressed);
    }

    /** The setting last recorded by {@link #set(CodeArea, boolean, boolean)}. */
    static boolean wanted(CodeArea area) {
        return Boolean.TRUE.equals(area.getProperties().get(WANTED));
    }

    /** Shows or hides {@code area}'s current-line highlight. */
    static void set(CodeArea area, boolean on) {
        if (on || !area.isLineHighlighterOn()) {
            area.setLineHighlighterOn(on);
            return;
        }
        Selection<?, ?, ?> line = null;
        for (Selection<?, ?, ?> s : area.getSelectionSet()) {
            if (s.getClass().getSimpleName().equals("LineSelection")) {
                line = s; // the class is package-private in RichTextFX: found by name
            }
        }
        area.setLineHighlighterOn(false);
        if (line != null) {
            line.dispose();
        }
    }
}
