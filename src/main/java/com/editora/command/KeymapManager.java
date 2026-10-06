package com.editora.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
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
        AVAILABLE = Collections.unmodifiableMap(m); // not Map.copyOf: the pickers list these in this order
    }

    /** The keymap used when {@code Settings.keymap} names none of {@link #AVAILABLE}. */
    public static final String DEFAULT = "emacs";

    /**
     * The bundled keymap to load for a configured name: the name itself when it is one of
     * {@link #AVAILABLE}, else {@link #DEFAULT}. A hand-edited {@code "keymap": "vim"}, a typo or a null must
     * cost the user their preferred bindings, not the whole launch. Pure.
     */
    public static String resolveName(String name) {
        return name != null && AVAILABLE.containsKey(name) ? name : DEFAULT;
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

    /** The bundled keymap currently loaded (always one of {@link #AVAILABLE}). */
    private String activeName = DEFAULT;
    /** The platform the keymap was loaded for; decides chord notation and which chords are typable. */
    private boolean mac = isMac();
    /** A configured name that was not a bundled keymap and has not been reported yet (see {@link #takeUnknownName}). */
    private String unknownName;
    /** The unknown name already handed out, so reloading the same bad setting does not report it again. */
    private String reportedUnknown;
    /** command id -> the chord to show for it, formatted; rebuilt lazily after the bindings change. */
    private Map<String, String> displayChords;

    /**
     * Loads the named bundled keymap as the base, replacing current bindings. On macOS a
     * {@code <name>.mac.json} variant (Cmd-based accelerators) is preferred when present, falling back to
     * {@code <name>.json} (the Ctrl-based Win/Linux map). A name that is not a bundled keymap loads
     * {@link #DEFAULT} instead (see {@link #resolveName}) and is remembered for {@link #takeUnknownName}.
     */
    public void loadNamed(String name) {
        loadNamed(name, isMac());
    }

    /** Package-visible variant with an explicit platform flag so tests don't depend on the host OS. */
    void loadNamed(String configured, boolean mac) {
        String name = resolveName(configured);
        if (name.equals(configured)) {
            reportedUnknown = null;
            unknownName = null;
        } else {
            unknownName = String.valueOf(configured);
        }
        this.activeName = name;
        this.mac = mac;
        this.displayChords = null;
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
        displayChords = null;
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

    /**
     * A snapshot of the current bindings in keymap order — the file's order, then overrides in the order
     * they were applied. The order is part of the contract: "the first chord bound to a command" must mean
     * the same chord on every launch, which {@code Map.copyOf} (unspecified iteration order) did not give.
     */
    public Map<String, String> bindings() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
    }

    /** The bundled keymap currently loaded — {@link #DEFAULT} when the configured name was unknown. */
    public String activeName() {
        return activeName;
    }

    /**
     * The configured keymap name that could not be loaded, exactly once per bad name: the caller reports it
     * (status bar + message log) and later calls return null until a different unknown name is configured.
     */
    public String takeUnknownName() {
        String name = unknownName;
        unknownName = null;
        if (name == null || name.equals(reportedUnknown)) {
            return null;
        }
        reportedUnknown = name;
        return name;
    }

    /**
     * The chord to advertise for {@code commandId} as raw keymap tokens, or null when it is unbound: the first
     * binding in keymap order that can be typed on this platform (so never {@code Cmd-…} on Windows/Linux),
     * falling back to the first binding of any kind.
     */
    public String chordFor(String commandId) {
        String fallback = null;
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            if (e.getValue().equals(commandId)) {
                if (ChordFormat.typable(e.getKey(), mac)) {
                    return e.getKey();
                }
                if (fallback == null) {
                    fallback = e.getKey();
                }
            }
        }
        return fallback;
    }

    /** Formats a chord sequence in the active keymap's notation — see {@link ChordFormat}. */
    public String display(String sequence) {
        return ChordFormat.format(sequence, ChordFormat.styleFor(activeName, mac));
    }

    /** The formatted chord to show for {@code commandId}, or null when it is unbound. */
    public String displayChord(String commandId) {
        return displayChords().get(commandId);
    }

    /**
     * command id → the formatted chord to show for it (see {@link #chordFor}), for every bound command. Cached
     * until the bindings change, so a menu, a palette and a toolbar that each ask per row share one pass.
     */
    public Map<String, String> displayChords() {
        if (displayChords == null) {
            ChordFormat.Style style = ChordFormat.styleFor(activeName, mac);
            Map<String, String> raw = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : bindings.entrySet()) {
                String current = raw.get(e.getValue());
                if (current == null || (!ChordFormat.typable(current, mac) && ChordFormat.typable(e.getKey(), mac))) {
                    raw.put(e.getValue(), e.getKey());
                }
            }
            raw.replaceAll((id, sequence) -> ChordFormat.format(sequence, style));
            displayChords = Collections.unmodifiableMap(raw);
        }
        return displayChords;
    }
}
