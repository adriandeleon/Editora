package com.editora.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.editora.macro.Macro;
import com.editora.macro.MacroIds;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The persisted, app-global set of named keyboard macros (in {@code macros.json}). Macros are not scoped
 * per project — like the plugin enable-state, they apply across every window. Schema-versioned via
 * {@link com.editora.config.migration.ConfigSchema#MACROS}.
 *
 * <p>Macros are keyed by their stable {@link Macro#id() id}, never by name: two macros may share a name's
 * letters in a different case, and a rename keeps the id (and so the key binding).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MacroStore {

    public static final int SCHEMA_VERSION = 2;

    /** The id a fresh "last recorded macro" entry gets (suffixed when a user macro already has it). */
    public static final String DEFAULT_LAST_ID = "unnamed-macro";

    public int schemaVersion = SCHEMA_VERSION;
    /** Saved macros, in user order (most recently saved last). */
    public List<Macro> macros = new ArrayList<>();
    /**
     * The id of the entry that holds the most recent recording until the user names it — the one the next
     * recording overwrites. It is that entry only while its name is still empty: once renamed it is an
     * ordinary macro. A fixed key rather than a name, because the name it is shown under is translated.
     */
    public String lastId = "";

    /**
     * Repairs what a hand-edited file can contain that the rest of the code assumes away: a null list, null
     * entries, a missing or repeated id. Called after every load.
     */
    public void sanitize() {
        List<Macro> clean = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (macros != null) {
            for (Macro m : macros) {
                if (m == null) {
                    continue;
                }
                String id = m.id().isBlank() || ids.contains(m.id())
                        ? MacroIds.unique(m.id().isBlank() ? MacroIds.slug(m.name()) : m.id(), ids)
                        : m.id();
                ids.add(id);
                clean.add(id.equals(m.id()) ? m : m.withId(id));
            }
        }
        macros = clean;
        if (lastId == null) {
            lastId = "";
        }
    }

    /** The saved macro with this id, or {@code null} if none. */
    public Macro findById(String id) {
        for (Macro m : macros) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    /** The first saved macro with exactly this (non-empty) name, or {@code null} if none. */
    public Macro findByName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        for (Macro m : macros) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** The unnamed entry holding the most recent recording, or {@code null} if there is none. */
    @JsonIgnore
    public Macro placeholder() {
        if (lastId == null || lastId.isEmpty()) {
            return null;
        }
        Macro m = findById(lastId);
        return m != null && m.name().isBlank() ? m : null;
    }

    public boolean isPlaceholder(Macro m) {
        return m != null && m.name().isBlank() && m.id().equals(lastId);
    }

    /** Every id in use. */
    public Set<String> ids() {
        Set<String> ids = new HashSet<>();
        for (Macro m : macros) {
            ids.add(m.id());
        }
        return ids;
    }

    /**
     * Saves a macro, replacing the one with the same id (in place) else appending it. A macro without an id
     * is new: it is given one from its name, distinct from every id in use.
     */
    public void put(Macro macro) {
        if (macro.id().isBlank()) {
            macros.add(macro.withId(MacroIds.forName(macro.name(), ids())));
            return;
        }
        for (int i = 0; i < macros.size(); i++) {
            if (macros.get(i).id().equals(macro.id())) {
                macros.set(i, macro);
                return;
            }
        }
        macros.add(macro);
    }

    /** Removes the macro with this id; returns whether anything was removed. */
    public boolean removeById(String id) {
        return macros.removeIf(m -> m.id().equals(id));
    }
}
