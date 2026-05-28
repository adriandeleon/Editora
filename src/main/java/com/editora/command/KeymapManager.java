package com.editora.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Maps key-chord sequences (e.g. {@code "C-x C-s"}) to command ids. A named keymap is loaded from a
 * bundled JSON resource; user overrides are layered on top.
 */
public class KeymapManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, String> bindings = new LinkedHashMap<>();

    /** Loads {@code /com/editora/keymaps/<name>.json} as the base keymap, replacing current bindings. */
    public void loadNamed(String name) {
        String resource = "/com/editora/keymaps/" + name + ".json";
        try (InputStream in = KeymapManager.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Keymap resource not found: " + resource);
            }
            Map<String, String> loaded = MAPPER.readValue(in, new TypeReference<Map<String, String>>() {
            });
            bindings.clear();
            bindings.putAll(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read keymap " + resource, e);
        }
    }

    /** Layers user overrides on top of the loaded keymap (chord sequence -> command id). */
    public void applyOverrides(Map<String, String> overrides) {
        if (overrides != null) {
            bindings.putAll(overrides);
        }
    }

    /** The command id bound exactly to the given chord sequence, or null. */
    public String commandFor(String sequence) {
        return bindings.get(sequence);
    }

    /** True if some binding's sequence starts with {@code sequence + " "} (i.e. more keys expected). */
    public boolean isPrefix(String sequence) {
        String withSep = sequence + " ";
        for (String key : bindings.keySet()) {
            if (key.startsWith(withSep)) {
                return true;
            }
        }
        return false;
    }

    public Map<String, String> bindings() {
        return Map.copyOf(bindings);
    }
}
