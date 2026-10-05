package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import com.editora.command.KeymapManager;
import com.editora.config.Settings;

/**
 * The order the shared keymap's layers are applied in: base keymap → the user's overrides → plugin keymaps →
 * the user's own bindings again.
 *
 * <p>Plugin keymaps go on after the user's overrides so a plugin can use a chord the user merely freed (an
 * override that <em>unbinds</em> a default). Applied last on their own, though, they also replaced a chord the
 * user had bound to something — the user's rebind lost to the plugin on every launch. Re-applying the user's
 * bindings (not the unbinds) on top gives the user the last word without taking freed chords away from plugins.
 */
final class KeymapLayers {

    private KeymapLayers() {}

    /** Reloads {@code keymap} as {@code name} and layers the plugin keymaps and the user's overrides on it. */
    static void rebuild(
            KeymapManager keymap,
            String name,
            Iterable<Map<String, String>> pluginKeymaps,
            Map<String, String> userOverrides) {
        keymap.loadNamed(name);
        keymap.applyOverrides(userOverrides);
        for (Map<String, String> plugin : pluginKeymaps) {
            keymap.applyOverrides(plugin);
        }
        keymap.applyOverrides(userBindings(userOverrides));
    }

    /**
     * Makes {@code id} the keymap in {@code settings}, parking the current key-binding overrides under the
     * keymap that was <em>in force</em>: a configured name that is not a bundled keymap was running as the
     * default one, so that is the keymap its overrides were made against.
     */
    static void switchKeymap(Settings settings, String id) {
        settings.setKeymap(KeymapManager.resolveName(settings.getKeymap()));
        settings.switchKeymap(id);
    }

    /** The overrides that bind a chord to a command — everything except the blank-valued "unbind" entries. */
    static Map<String, String> userBindings(Map<String, String> userOverrides) {
        Map<String, String> bindings = new LinkedHashMap<>();
        if (userOverrides != null) {
            userOverrides.forEach((chord, command) -> {
                if (command != null && !command.isBlank()) {
                    bindings.put(chord, command);
                }
            });
        }
        return bindings;
    }
}
