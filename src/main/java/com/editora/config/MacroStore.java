package com.editora.config;

import java.util.ArrayList;
import java.util.List;

import com.editora.macro.Macro;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The persisted, app-global set of named keyboard macros (in {@code macros.json}). Macros are not scoped
 * per project — like the plugin enable-state, they apply across every window. Schema-versioned via
 * {@link com.editora.config.migration.ConfigSchema#MACROS}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MacroStore {

    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    /** Saved macros, in user order (most recently saved last). */
    public List<Macro> macros = new ArrayList<>();

    /** The saved macro with this name, or {@code null} if none. */
    public Macro find(String name) {
        for (Macro m : macros) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** Saves a macro, replacing any existing one of the same name (in place) else appending it. */
    public void put(Macro macro) {
        for (int i = 0; i < macros.size(); i++) {
            if (macros.get(i).name().equals(macro.name())) {
                macros.set(i, macro);
                return;
            }
        }
        macros.add(macro);
    }

    /** Removes the macro of this name; returns whether anything was removed. */
    public boolean remove(String name) {
        return macros.removeIf(m -> m.name().equals(name));
    }
}
