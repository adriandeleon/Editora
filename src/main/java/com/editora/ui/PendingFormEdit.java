package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.TextInputControl;

/**
 * What the user has typed into a master–detail form but not committed yet, kept across a reload of the
 * list behind it.
 *
 * <p>The Settings pages for Remote sites and Abbreviations edit a working copy of a shared store, and
 * re-read it whenever the store changes — a Connect that finishes remembers its site, a command defines an
 * abbreviation, another window's Settings saves. Re-reading replaces the list's items, which re-selects the
 * row and reloads the form from the store: a host name or an expansion half typed at that moment was gone.
 * Fields commit on Enter or on losing focus, so "half typed" is exactly "differs from what the selected
 * entry holds". {@link #capture} notes those fields before the reload; {@link #restore} puts them back
 * afterwards — the caller does so only when the same entry is still there to edit.
 */
final class PendingFormEdit {

    /** One field the user changed: its text, selection and whether it had the keyboard focus. */
    private record Typed(TextInputControl field, String text, int anchor, int caret, boolean focused) {}

    private final List<Typed> typed = new ArrayList<>();

    private PendingFormEdit() {}

    /** No form, or no entry selected: nothing to keep. */
    static PendingFormEdit none() {
        return new PendingFormEdit();
    }

    /**
     * @param fields the form's text fields
     * @param stored what each field shows for the selected entry when nothing has been typed, in order
     */
    static PendingFormEdit capture(List<? extends TextInputControl> fields, List<String> stored) {
        PendingFormEdit edit = new PendingFormEdit();
        for (int i = 0; i < fields.size(); i++) {
            TextInputControl field = fields.get(i);
            String text = field.getText() == null ? "" : field.getText();
            if (!text.equals(stored.get(i) == null ? "" : stored.get(i))) {
                edit.typed.add(new Typed(field, text, field.getAnchor(), field.getCaretPosition(), field.isFocused()));
            }
        }
        return edit;
    }

    /** Whether anything was typed and not committed. */
    boolean any() {
        return !typed.isEmpty();
    }

    /** Puts the typed text back, with the caret where it was and the focus in the field that had it. */
    void restore() {
        for (Typed t : typed) {
            t.field().setText(t.text());
        }
        for (Typed t : typed) {
            if (t.focused() && !t.field().isFocused()) {
                t.field().requestFocus(); // the reload disabled the form for a moment, which drops the focus
            }
            int length = t.field().getLength();
            t.field().selectRange(Math.min(t.anchor(), length), Math.min(t.caret(), length));
        }
    }
}
