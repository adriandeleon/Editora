package com.editora.macro;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One step of a recorded keyboard macro: an invoked editor <b>command</b> (by id), a run of literal
 * <b>typed text</b>, or a bare editing/navigation <b>key</b> press. A single concrete record (rather than a
 * sealed hierarchy) keeps the {@code macros.json} representation trivial — Jackson serializes
 * {@code {kind, value}} with no polymorphic type info.
 *
 * <p>{@link #KEY} exists because Backspace, Delete and the arrow/Home/End keys are handled natively by the
 * editor area and are bound to no command in any bundled keymap — so they reach neither capture hook, and a
 * macro recorded with them replayed as if they had never been pressed.
 *
 * @param kind {@link #COMMAND}, {@link #TEXT} or {@link #KEY}
 * @param value the command id (for {@code COMMAND}), the literal characters (for {@code TEXT}), or the
 *     {@code KeyCode} name (for {@code KEY})
 */
public record MacroStep(String kind, String value) {

    public static final String COMMAND = "command";
    public static final String TEXT = "text";
    public static final String KEY = "key";

    public static MacroStep command(String commandId) {
        return new MacroStep(COMMAND, commandId);
    }

    public static MacroStep text(String literal) {
        return new MacroStep(TEXT, literal);
    }

    /** A bare key press, by {@code javafx.scene.input.KeyCode} name (e.g. {@code BACK_SPACE}, {@code DOWN}). */
    public static MacroStep key(String keyCodeName) {
        return new MacroStep(KEY, keyCodeName);
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
}
