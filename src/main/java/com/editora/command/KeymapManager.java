package com.editora.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Maps key-chord sequences (e.g. {@code "C-x C-s"}) to command ids. A named keymap is loaded from a
 * bundled JSON resource; user overrides are layered on top.
 */
public class KeymapManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The bundled keymaps, in display order, mapped to their (untranslated) product display names — like
     * git verbs or detected language names, these are proper nouns left as-is across locales. The keys are
     * the {@code <name>} passed to {@link #loadNamed(String)} and stored in {@code Settings.keymap}.
     */
    public static final Map<String, String> AVAILABLE;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("emacs", "Emacs");
        m.put("cua", "CUA");
        m.put("sublime", "Sublime Text");
        m.put("vscode", "Visual Studio Code");
        m.put("intellij", "IntelliJ IDEA");
        AVAILABLE = Map.copyOf(m);
    }

    /** Display name for a keymap id, or the id itself if unknown. */
    public static String displayName(String id) {
        return AVAILABLE.getOrDefault(id, id);
    }

    /** Whether the running OS is macOS — selects the {@code .mac} keymap variant and the Cmd-based override map. */
    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private final Map<String, String> bindings = new LinkedHashMap<>();

    /**
     * Loads the named bundled keymap as the base, replacing current bindings. On macOS a
     * {@code <name>.mac.json} variant (Cmd-based accelerators) is preferred when present, falling back to
     * {@code <name>.json} (the Ctrl-based Win/Linux map).
     */
    public void loadNamed(String name) {
        loadNamed(name, isMac());
    }

    /** Package-visible variant with an explicit platform flag so tests don't depend on the host OS. */
    void loadNamed(String name, boolean mac) {
        String macResource = "/com/editora/keymaps/" + name + ".mac.json";
        String baseResource = "/com/editora/keymaps/" + name + ".json";
        String resource = mac && KeymapManager.class.getResource(macResource) != null ? macResource : baseResource;
        try (InputStream in = KeymapManager.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Keymap resource not found: " + resource);
            }
            Map<String, String> loaded = MAPPER.readValue(in, new TypeReference<Map<String, String>>() {});
            bindings.clear();
            bindings.putAll(loaded);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read keymap " + resource, e);
        }
    }

    /** Sentinel override value meaning "unbind this chord" (suppress a base-keymap default). */
    public static final String UNBIND = "";

    /**
     * Layers user overrides on top of the loaded keymap (chord sequence -> command id). A blank value
     * ({@link #UNBIND}) <em>removes</em> the chord instead of binding it, so a user override can suppress a
     * default from the base keymap (the keybinding editor's "clear"/rebind uses this).
     */
    public void applyOverrides(Map<String, String> overrides) {
        if (overrides == null) {
            return;
        }
        overrides.forEach((sequence, id) -> {
            if (id == null || id.isBlank()) {
                bindings.remove(sequence);
            } else {
                bindings.put(sequence, id);
            }
        });
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
