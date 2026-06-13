package com.editora.editor;

import com.editora.config.NoteScope;
import com.editora.config.TextAnchor;

/**
 * A captured anchor for a not-yet-created Personal Note: the inferred {@link NoteScope} and the
 * {@link TextAnchor} (position + selected text + context) from the editor's current selection/caret.
 * The controller turns this into a {@link com.editora.config.PersonalNote} once it has the body/tags.
 */
public record NoteDraft(NoteScope scope, TextAnchor anchor) {}
