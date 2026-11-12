package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.command.KeymapManager;
import com.editora.command.UserKeyBindings;
import com.editora.command.UserKeyBindings.Derived;
import com.editora.command.UserKeyBindings.Store;
import com.editora.config.Settings;

/**
 * The user's key bindings as {@link Settings} keeps them (one store per keymap, the same on every operating
 * system) and as the keymap takes them (this system's overrides, chord to command). The translation between
 * the two is {@link UserKeyBindings}; this is where it meets the settings, the running OS and the active keymap.
 */
public final class UserKeys {

    private UserKeys() {}

    /** The bundled keymap {@code settings} runs. */
    static String keymap(Settings settings) {
        return KeymapManager.resolveName(settings.getKeymap());
    }

    /** The bindings made in {@code keymap}; a copy. */
    static Store store(Settings settings, String keymap) {
        return new Store(
                new LinkedHashMap<>(settings.getKeyBindings().getOrDefault(keymap, Map.of())),
                new LinkedHashMap<>(settings.getKeyBindingsMacOnly().getOrDefault(keymap, Map.of())));
    }

    /** Makes {@code store} the bindings of {@code keymap}; a keymap with none leaves no empty entry behind. */
    static void put(Settings settings, String keymap, Store store) {
        put(settings.getKeyBindings(), keymap, store.shared());
        put(settings.getKeyBindingsMacOnly(), keymap, store.macOnly());
    }

    private static void put(
            Map<String, Map<String, List<String>>> byKeymap, String keymap, Map<String, List<String>> bindings) {
        if (bindings.isEmpty()) {
            byKeymap.remove(keymap);
        } else {
            byKeymap.put(keymap, new LinkedHashMap<>(bindings));
        }
    }

    /**
     * What the active keymap's bindings come to on this system, against {@code base}: the keymap's stock
     * bindings here, with the plugins' when a rebind is to unbind theirs too.
     */
    static Derived derived(Settings settings, Map<String, String> base) {
        String keymap = keymap(settings);
        return UserKeyBindings.derive(
                store(settings, keymap), base, KeymapManager.isMac(), KeymapManager.hasMacVariant(keymap));
    }

    /** This system's overrides for the active keymap (see {@link #derived}). */
    static Map<String, String> overrides(Settings settings, Map<String, String> base) {
        return derived(settings, base).overrides();
    }

    /** This system's overrides against the active keymap's stock bindings alone (no plugin layer). */
    public static Map<String, String> overrides(Settings settings) {
        return overrides(settings, KeymapManager.defaults(keymap(settings), KeymapManager.isMac()));
    }

    /** Stores an edit made on this system: {@code newOverrides} replace the active keymap's overrides here. */
    static void edit(Settings settings, Map<String, String> base, Map<String, String> newOverrides) {
        String keymap = keymap(settings);
        put(
                settings,
                keymap,
                UserKeyBindings.edit(
                        store(settings, keymap),
                        base,
                        KeymapManager.isMac(),
                        KeymapManager.hasMacVariant(keymap),
                        newOverrides));
    }

    /** Drops every binding of a command that no longer exists (a deleted macro), in every keymap. */
    static void forget(Settings settings, String commandId) {
        for (Map<String, Map<String, List<String>>> byKeymap :
                List.of(settings.getKeyBindings(), settings.getKeyBindingsMacOnly())) {
            byKeymap.values().forEach(bindings -> bindings.remove(commandId));
            byKeymap.values().removeIf(Map::isEmpty);
        }
    }
}
