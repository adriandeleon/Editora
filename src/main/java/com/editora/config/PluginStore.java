package com.editora.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The persisted plugin enable-state (in {@code plugins.json}). A plugin is <b>disabled by default</b>
 * (plugins run untrusted, full-trust code, so they are opt-in): only ids explicitly set to {@code true}
 * here load, and only while what they can do is what the user was shown when enabling them.
 *
 * <p>{@link #consent} keeps, per enabled plugin, the fingerprint of that disclosure
 * ({@code PluginDescriptor.digest}: main class, jars, commands, keymaps). A plugin whose fingerprint differs —
 * an update that adds code or a command, or a different plugin dropped into the folder of one that was
 * deleted by hand — is not loaded until it is enabled again. Schema-versioned via
 * {@link com.editora.config.migration.ConfigSchema#PLUGINS}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginStore {

    /** v1 → v2: + {@link #consent}; v1's enabled ids are carried as {@link #ADOPT}. */
    public static final int SCHEMA_VERSION = 2;

    /**
     * The consent of a plugin enabled before fingerprints were stored: the first launch that finds the plugin
     * records its fingerprint in its place (the user enabled what was there), and drops it when the folder is
     * gone.
     */
    public static final String ADOPT = "*";

    public int schemaVersion = SCHEMA_VERSION;
    /** Plugin id → enabled. Absent id ⇒ disabled. */
    public Map<String, Boolean> enabled = new LinkedHashMap<>();
    /** Plugin id → the fingerprint of the capabilities the user agreed to (see the class note). */
    public Map<String, String> consent = new LinkedHashMap<>();

    public boolean isEnabled(String id) {
        return Boolean.TRUE.equals(enabled.get(id));
    }

    /** Switches {@code id} off (its consent goes with it: switching it on again asks again). */
    public void setEnabled(String id, boolean on) {
        if (on) {
            // The UI enables through enable(id, digest); this (tests, tools) consents to what is there now.
            enabled.put(id, true);
            consent.putIfAbsent(id, ADOPT);
        } else {
            enabled.remove(id);
            consent.remove(id);
        }
    }

    /** Enables {@code id} with the fingerprint of what the user was just shown. */
    public void enable(String id, String digest) {
        enabled.put(id, true);
        consent.put(id, digest == null ? "" : digest);
    }

    /** Whether {@code id} is enabled and its capabilities are still the ones the user agreed to. */
    public boolean allows(String id, String digest) {
        if (!isEnabled(id)) {
            return false;
        }
        String agreed = consent.get(id);
        return ADOPT.equals(agreed) || (agreed != null && agreed.equals(digest));
    }

    /** Whether {@code id} is switched on but its capabilities changed since (it does not load: ask again). */
    public boolean changedSinceConsent(String id, String digest) {
        return isEnabled(id) && !allows(id, digest);
    }

    /** Drops everything about {@code id} (it was removed). */
    public void forget(String id) {
        enabled.remove(id);
        consent.remove(id);
    }

    /**
     * Brings the store in line with the plugins on disk at launch: a consent carried from v1 ({@link #ADOPT})
     * takes the fingerprint of the plugin it was given to, and entries of plugins whose folder is gone are
     * dropped — a stale "true" would otherwise arm whatever is put under that name later.
     *
     * @param present plugin id → its current fingerprint, for every plugin folder found (null for one that
     *     failed to load: kept, but nothing is adopted for it)
     * @return whether anything changed
     */
    public boolean reconcile(Map<String, String> present) {
        boolean changed = false;
        for (String id : Set.copyOf(enabled.keySet())) {
            if (!present.containsKey(id)) {
                forget(id);
                changed = true;
            }
        }
        for (String id : Set.copyOf(consent.keySet())) {
            if (!present.containsKey(id) || !isEnabled(id)) {
                consent.remove(id);
                changed = true;
            } else if (ADOPT.equals(consent.get(id)) && present.get(id) != null) {
                consent.put(id, present.get(id));
                changed = true;
            }
        }
        return changed;
    }

    /** Ids switched on (with or without a current consent). */
    @JsonIgnore
    public Set<String> enabledIds() {
        Set<String> out = new java.util.LinkedHashSet<>();
        enabled.forEach((id, on) -> {
            if (Boolean.TRUE.equals(on)) {
                out.add(id);
            }
        });
        return out;
    }
}
