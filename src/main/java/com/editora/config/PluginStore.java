package com.editora.config;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The persisted plugin enable-state (in {@code plugins.json}). A plugin is <b>disabled by default</b>
 * (plugins run untrusted, full-trust code, so they are opt-in): only ids explicitly set to {@code true}
 * here load. Schema-versioned via {@link com.editora.config.migration.ConfigSchema#PLUGINS}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginStore {

    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    /** Plugin id → enabled. Absent id ⇒ disabled. */
    public Map<String, Boolean> enabled = new LinkedHashMap<>();

    public boolean isEnabled(String id) {
        return Boolean.TRUE.equals(enabled.get(id));
    }

    public void setEnabled(String id, boolean on) {
        enabled.put(id, on);
    }
}
