package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import com.editora.command.KeymapManager;
import com.editora.config.Settings;
import org.jspecify.annotations.Nullable;

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
        String from = settings.getKeymap();
        Map<String, String> carry = portable(settings.keybindingsFor(false), false);
        Map<String, String> carryMac = portable(settings.keybindingsFor(true), true);
        settings.switchKeymap(id);
        if (!java.util.Objects.equals(from, settings.getKeymap())) {
            carry(
                    carry,
                    settings.keybindingsFor(false),
                    settings.getKeymapKeybindings().get(from),
                    id,
                    false);
            carry(
                    carryMac,
                    settings.keybindingsFor(true),
                    settings.getKeymapKeybindingsMac().get(from),
                    id,
                    true);
            settings.getKeymapKeybindings().remove(from, Map.of()); // nothing left behind: no empty entry
            settings.getKeymapKeybindingsMac().remove(from, Map.of());
        }
    }

    /** The bindings of commands no bundled keymap binds by default (macros, external tools, run configurations). */
    private static Map<String, String> portable(Map<String, String> overrides, boolean mac) {
        Map<String, String> out = new LinkedHashMap<>();
        overrides.forEach((chord, command) -> {
            if (command != null && !command.isBlank() && !KeymapManager.boundByAnyKeymap(command, mac)) {
                out.put(chord, command);
            }
        });
        return out;
    }

    /**
     * Moves {@code bindings} from the keymap just left ({@code parked}) into the new one's {@code active}
     * overrides — unless the chord is taken there or collides with the new keymap's own chords; such a
     * binding stays with the keymap it was made in and comes back with it.
     */
    private static void carry(
            Map<String, String> bindings,
            Map<String, String> active,
            @Nullable Map<String, String> parked,
            String keymap,
            boolean mac) {
        Map<String, String> live = new LinkedHashMap<>(KeymapManager.defaults(keymap, mac));
        live.putAll(active);
        bindings.forEach((chord, command) -> {
            if (!active.containsKey(chord)
                    && !active.containsValue(command)
                    && com.editora.command.KeybindingEdits.conflicts(live, chord, command)
                            .isEmpty()) {
                active.put(chord, command);
                live.put(chord, command);
                if (parked != null) {
                    parked.remove(chord);
                }
            }
        });
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
