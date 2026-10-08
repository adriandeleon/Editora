package com.editora.macro;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One step of a recorded keyboard macro: an invoked editor <b>command</b> (by id), a run of literal
 * <b>typed text</b>, or a <b>key</b> press. A single concrete record (rather than a sealed hierarchy) keeps
 * the {@code macros.json} representation trivial — Jackson serializes {@code {kind, value, target}} with no
 * polymorphic type info.
 *
 * <p>{@link #KEY} exists because Backspace, Delete, Enter, Tab, Escape and the arrow/Home/End keys are
 * handled by whatever has the focus and are bound to no command in any bundled keymap — so only the key
 * itself can be recorded, and only a real key press replays it through the same handlers.
 *
 * <p>A text or key step also remembers <b>where</b> it was typed: into the document ({@link #EDITOR}, the
 * default and what every step recorded before this field existed means) or into whatever else had the
 * keyboard focus ({@link #PROMPT} — the find bar, an overlay prompt, a picker, a tool window). Replay sends a
 * prompt step to the control that has the focus then, so "find, type the query, Enter" replays as a search
 * instead of typing the query into the document.
 *
 * @param kind {@link #COMMAND}, {@link #TEXT} or {@link #KEY}
 * @param value the command id (for {@code COMMAND}), the literal characters (for {@code TEXT}), or the
 *     {@link MacroKey} token (for {@code KEY})
 * @param target {@link #PROMPT}, or null for the document; always null for a command step
 */
public record MacroStep(
        String kind,
        String value,
        @JsonInclude(JsonInclude.Include.NON_NULL) String target) {

    public static final String COMMAND = "command";
    public static final String TEXT = "text";
    public static final String KEY = "key";

    /** Step target: the document. Stored as an absent {@code target}. */
    public static final String EDITOR = "editor";
    /** Step target: whatever control has the keyboard focus that is not the document. */
    public static final String PROMPT = "prompt";

    public MacroStep {
        // A hand-edited macros.json can carry anything: an unknown kind is read as text, and only "prompt"
        // is a target worth keeping (so "editor", a typo and null all mean the document).
        kind = COMMAND.equals(kind) || KEY.equals(kind) ? kind : TEXT;
        value = value == null ? "" : value;
        target = PROMPT.equals(target) && !COMMAND.equals(kind) ? PROMPT : null;
    }

    public MacroStep(String kind, String value) {
        this(kind, value, null);
    }

    public static MacroStep command(String commandId) {
        return new MacroStep(COMMAND, commandId, null);
    }

    public static MacroStep text(String literal) {
        return new MacroStep(TEXT, literal, null);
    }

    public static MacroStep text(String literal, boolean prompt) {
        return new MacroStep(TEXT, literal, prompt ? PROMPT : null);
    }

    /** A key press, as a {@link MacroKey} token (e.g. {@code BACK_SPACE}, {@code S-TAB}, {@code C-LEFT}). */
    public static MacroStep key(String keyToken) {
        return new MacroStep(KEY, keyToken, null);
    }

    public static MacroStep key(String keyToken, boolean prompt) {
        return new MacroStep(KEY, keyToken, prompt ? PROMPT : null);
    }

    @JsonIgnore
    public boolean isCommand() {
        return COMMAND.equals(kind);
    }

    @JsonIgnore
    public boolean isText() {
        return TEXT.equals(kind);
    }

    @JsonIgnore
    public boolean isKey() {
        return KEY.equals(kind);
    }

    /** Whether this step was typed into something other than the document. */
    @JsonIgnore
    public boolean isPrompt() {
        return target != null;
    }
}
