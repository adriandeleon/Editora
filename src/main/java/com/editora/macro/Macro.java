package com.editora.macro;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A recorded keyboard macro: a stable {@code id}, a display {@code name} and the ordered list of
 * {@link MacroStep}s that make it up.
 *
 * <p>The {@code id} — not the name — is the macro's identity: it names the {@code macro.run.<id>} command a
 * key binding points at, so renaming a macro keeps its binding, and two names that differ only in case or
 * script are still two macros. A macro that has not been stored yet (the one just recorded) has an empty id.
 * The auto-saved last recording has an empty <em>name</em>; it is shown under a localized label.
 */
public record Macro(String id, String name, List<MacroStep> steps) {

    public Macro {
        id = id == null ? "" : id;
        name = name == null ? "" : name; // a name-less entry in a hand-edited macros.json must not NPE lookups
        steps = withoutNulls(steps);
    }

    public Macro(String name, List<MacroStep> steps) {
        this("", name, steps);
    }

    /** {@code [null]} is valid JSON for the steps list; {@code List.copyOf} would throw on it. */
    private static List<MacroStep> withoutNulls(List<MacroStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        List<MacroStep> out = new ArrayList<>(steps.size());
        for (MacroStep step : steps) {
            if (step != null) {
                out.add(step);
            }
        }
        return List.copyOf(out);
    }

    public Macro withId(String newId) {
        return new Macro(newId, name, steps);
    }

    @JsonIgnore
    public boolean isEmpty() {
        return steps.isEmpty();
    }
}
