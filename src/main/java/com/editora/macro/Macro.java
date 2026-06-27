package com.editora.macro;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A recorded keyboard macro: a display {@code name} plus the ordered list of {@link MacroStep}s that make
 * it up. The just-recorded ("last") macro carries an empty name until the user saves it under a name.
 */
public record Macro(String name, List<MacroStep> steps) {

    public Macro {
        name = name == null ? "" : name; // a name-less entry in a hand-edited macros.json must not NPE lookups
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return steps.isEmpty();
    }
}
