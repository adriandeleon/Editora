package com.editora.macro;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * One step of a recorded keyboard macro: either an invoked editor <b>command</b> (by id) or a run of
 * literal <b>typed text</b>. A single concrete record (rather than a sealed hierarchy) keeps the
 * {@code macros.json} representation trivial — Jackson serializes {@code {kind, value}} with no
 * polymorphic type info.
 *
 * @param kind {@link #COMMAND} or {@link #TEXT}
 * @param value the command id (for {@code COMMAND}) or the literal characters (for {@code TEXT})
 */
public record MacroStep(String kind, String value) {

    public static final String COMMAND = "command";
    public static final String TEXT = "text";

    public static MacroStep command(String commandId) {
        return new MacroStep(COMMAND, commandId);
    }

    public static MacroStep text(String literal) {
        return new MacroStep(TEXT, literal);
    }

    @JsonIgnore
    public boolean isCommand() {
        return COMMAND.equals(kind);
    }

    @JsonIgnore
    public boolean isText() {
        return TEXT.equals(kind);
    }
}
