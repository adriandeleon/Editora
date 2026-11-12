package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.command.KeymapManager;
import com.editora.command.UserKeyBindings.Store;
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

    /**
     * Reloads {@code keymap} as the one {@code settings} names and layers the plugin keymaps and the user's
     * bindings on it. The user's bindings are worked out against the keymap with the plugins' chords in it, so
     * a command the user rebound loses a plugin's chord for it as it loses the keymap's own.
     */
    static void rebuild(KeymapManager keymap, Settings settings, Iterable<Map<String, String>> pluginKeymaps) {
        keymap.loadNamed(settings.getKeymap());
        Map<String, String> base = new LinkedHashMap<>(keymap.bindings());
        for (Map<String, String> plugin : pluginKeymaps) {
            plugin.forEach((chord, command) -> {
                if (command == null || command.isBlank()) {
                    base.remove(chord);
                } else {
                    base.put(chord, command);
                }
            });
        }
        rebuild(keymap, settings.getKeymap(), pluginKeymaps, UserKeys.overrides(settings, base));
    }

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
     * Makes {@code id} the keymap in {@code settings}. The bindings made in the keymap being left stay with
     * it, except those of commands no bundled keymap binds (a saved macro, an external tool, a run
     * configuration): such a command has no keymap-specific meaning, so its binding moves to the new keymap,
     * unless the chord is taken there. A configured name that is not a bundled keymap was running as the
     * default one, so that is the keymap its bindings were made in.
     */
    static void switchKeymap(Settings settings, String id) {
        String from = KeymapManager.resolveName(settings.getKeymap());
        settings.setKeymap(id);
        String to = KeymapManager.resolveName(id);
        if (from.equals(to)) {
            return;
        }
        Store left = UserKeys.store(settings, from);
        Store entered = UserKeys.store(settings, to);
        carry(left.shared(), entered.shared(), to, false);
        carry(left.macOnly(), entered.macOnly(), to, true);
        UserKeys.put(settings, from, left);
        UserKeys.put(settings, to, entered);
    }

    /**
     * Moves the bindings of commands no keymap binds from {@code left} into {@code entered}, the bindings of
     * keymap {@code to}: unless the command is bound there already, or one of its chords is in use there (by
     * the keymap or by another of the user's bindings); such a binding stays with the keymap it was made in.
     * {@code mac} says which notation the chords are in.
     */
    private static void carry(
            Map<String, List<String>> left, Map<String, List<String>> entered, String to, boolean mac) {
        Map<String, String> live = new LinkedHashMap<>(KeymapManager.defaults(to, mac));
        entered.forEach((command, chords) -> chords.forEach(chord -> live.put(chord, command)));
        for (var it = left.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, List<String>> e = it.next();
            String command = e.getKey();
            if (KeymapManager.boundByAnyKeymap(command, false)
                    || KeymapManager.boundByAnyKeymap(command, true)
                    || entered.containsKey(command)) {
                continue;
            }
            boolean free = e.getValue().stream()
                    .allMatch(chord -> com.editora.command.KeybindingEdits.conflicts(live, chord, command)
                            .isEmpty());
            if (free) {
                entered.put(command, e.getValue());
                e.getValue().forEach(chord -> live.put(chord, command));
                it.remove();
            }
        }
    }

    /**
     * The chords of the plugins this session runs ({@link com.editora.plugin.PluginManager#launchSet()}), in load
     * order: each one's manifest chords for {@code keymapId} ({@link com.editora.plugin.PluginManifest#keymapFor}),
     * then the chords its code bound with {@code PluginContext.bindKey} — recorded, so a keymap rebuild keeps them
     * as the startup path does. None in Simple UI mode, which promises plugins are off.
     */
    static java.util.List<Map<String, String>> pluginKeymaps(
            Settings settings, com.editora.plugin.PluginManager plugins, String keymapId) {
        java.util.List<Map<String, String>> out = new java.util.ArrayList<>();
        if (plugins == null || settings.isSimpleMode()) {
            return out;
        }
        for (com.editora.plugin.PluginDescriptor d : plugins.launchSet()) {
            if (d.enabled() && d.loadError() == null) {
                Map<String, String> chords = new LinkedHashMap<>(d.manifest().qualifiedKeymapFor(keymapId));
                chords.putAll(plugins.codeBindings(d.id()));
                if (!chords.isEmpty()) {
                    out.add(chords);
                }
            }
        }
        return out;
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
