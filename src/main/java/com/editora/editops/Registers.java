package com.editora.editops;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * Emacs registers: named slots, one character each, that hold either text ({@code copy-to-register},
 * {@code C-x r s}) or a position in a file ({@code point-to-register}, {@code C-x r SPC}). A position follows
 * the edits made to its file while it is open, as an Emacs marker does ({@link #shift}). Pure: one store per
 * window, kept for the session.
 */
public final class Registers {

    /** What a register holds. */
    public sealed interface Register permits Text, Position {}

    /** Text, from copy-to-register. */
    public record Text(String text) implements Register {}

    /**
     * A position, from point-to-register.
     *
     * @param file the file's storable path ({@code Vfs.toStorableString})
     * @param offset the offset in the file's text
     */
    public record Position(String file, int offset) implements Register {}

    private final Map<Character, Register> registers = new TreeMap<>();

    /** Stores {@code register} under {@code name}, replacing what was there. */
    public void put(char name, Register register) {
        registers.put(name, register);
    }

    /** What {@code name} holds, or null when it is empty. */
    public @Nullable Register get(char name) {
        return registers.get(name);
    }

    /** Every non-empty register, by name. */
    public Map<Character, Register> all() {
        return Collections.unmodifiableMap(registers);
    }

    /**
     * Moves the positions in {@code file} past an edit there: {@code removed} characters at {@code pos}
     * replaced by {@code inserted}. A position inside removed text goes to where the edit started.
     */
    public void shift(String file, int pos, int removed, int inserted) {
        registers.replaceAll(
                (name, register) -> register instanceof Position p && p.file().equals(file)
                        ? new Position(file, MarkRing.shiftOne(p.offset(), pos, removed, inserted))
                        : register);
    }

    /** Whether any position register is in {@code file}. */
    public boolean hasPositionIn(String file) {
        return registers.values().stream()
                .anyMatch(r -> r instanceof Position p && p.file().equals(file));
    }
}
