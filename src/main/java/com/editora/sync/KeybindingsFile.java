package com.editora.sync;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.editora.config.Settings;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * The user's key-binding overrides as the sync repository holds them. In the config directory they are part
 * of {@code settings.json} — the active keymap's in {@link Settings#getKeybindings()} /
 * {@link Settings#getKeybindingsMac()}, the other keymaps' parked in {@link Settings#getKeymapKeybindings()} /
 * {@link Settings#getKeymapKeybindingsMac()} — and only those maps are synced, never the rest of the file.
 *
 * <pre>{
 *   "formatVersion" : 1,
 *   "keymaps" : {
 *     "emacs" : {
 *       "macOS" : { "Cmd-k" : "editor.killLine" },
 *       "windowsLinux" : { "C-x C-s" : "", "C-x s" : "file.save" }
 *     }
 *   }
 * }</pre>
 *
 * <p>Overrides are kept per keymap, so a computer running another keymap takes them for when it switches, and
 * which keymap is active stays a choice of each computer. A blank command is an unbind
 * ({@link com.editora.command.KeymapManager#UNBIND}). The merge unit is one chord of one keymap and platform.
 * Pure.
 */
public final class KeybindingsFile {

    /** The file's path in the repository. */
    public static final String PATH = "keybindings.json";

    /** The layout this build writes; a file with a higher one was written by a newer Editora. */
    public static final int FORMAT_VERSION = 1;

    static final String WINDOWS_LINUX = "windowsLinux";
    static final String MAC = "macOS";

    /** Joins the parts of an entry name: keymap, platform and chord never hold a tab. */
    private static final char SEP = '\t';

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter PRETTY =
            MAPPER.writer(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));

    private KeybindingsFile() {}

    /** A newer Editora wrote the file. */
    static final class NewerFormatException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** The file for {@code settings}' overrides; null when there are none. */
    public static @Nullable String render(Settings settings) {
        Map<String, String> entries = new LinkedHashMap<>();
        String active = String.valueOf(settings.getKeymap());
        put(entries, active, WINDOWS_LINUX, settings.getKeybindings());
        put(entries, active, MAC, settings.getKeybindingsMac());
        parked(entries, settings.getKeymapKeybindings(), active, WINDOWS_LINUX);
        parked(entries, settings.getKeymapKeybindingsMac(), active, MAC);
        return text(entries);
    }

    /**
     * Replaces {@code settings}' overrides, of every keymap, with those of {@code text} (null: no file, so no
     * overrides). The keymap {@code settings} runs gets its overrides as the active ones.
     */
    public static void apply(Settings settings, @Nullable String text) throws IOException {
        Map<String, Map<String, String>> parked = new LinkedHashMap<>();
        Map<String, Map<String, String>> parkedMac = new LinkedHashMap<>();
        String active = String.valueOf(settings.getKeymap());
        Map<String, String> mine = new LinkedHashMap<>();
        Map<String, String> mineMac = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries(text).entrySet()) {
            String[] parts = split(e.getKey());
            boolean mac = MAC.equals(parts[1]);
            Map<String, String> target = active.equals(parts[0])
                    ? (mac ? mineMac : mine)
                    : (mac ? parkedMac : parked).computeIfAbsent(parts[0], k -> new LinkedHashMap<>());
            target.put(parts[2], e.getValue());
        }
        settings.setKeybindings(mine);
        settings.setKeybindingsMac(mineMac);
        settings.setKeymapKeybindings(parked);
        settings.setKeymapKeybindingsMac(parkedMac);
    }

    /**
     * The entries of {@code text} — {@code keymap \t platform \t chord} to command — in file order. Empty for
     * null or blank text.
     *
     * @throws NewerFormatException when a newer Editora wrote the file
     * @throws IOException when it is not this layout
     */
    static Map<String, String> entries(@Nullable String text) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        JsonNode tree = MAPPER.readTree(text);
        if (tree == null || !tree.isObject()) {
            throw new IOException("not an object");
        }
        if (tree.path("formatVersion").asInt(0) > FORMAT_VERSION) {
            throw new NewerFormatException();
        }
        JsonNode keymaps = tree.path("keymaps");
        if (keymaps.isMissingNode()) {
            return out;
        }
        if (!keymaps.isObject()) {
            throw new IOException("keymaps is not an object");
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = keymaps.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> keymap = it.next();
            for (String platform : new String[] {WINDOWS_LINUX, MAC}) {
                JsonNode chords = keymap.getValue().path(platform);
                for (Iterator<Map.Entry<String, JsonNode>> c = chords.fields(); c.hasNext(); ) {
                    Map.Entry<String, JsonNode> chord = c.next();
                    if (chord.getValue().isTextual() && valid(keymap.getKey(), chord.getKey())) {
                        out.put(
                                name(keymap.getKey(), platform, chord.getKey()),
                                chord.getValue().asText());
                    }
                }
            }
        }
        return out;
    }

    /** The file holding {@code entries}, in a canonical form (sorted); null when there are none. */
    static @Nullable String text(Map<String, String> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Map<String, String>>> sorted = new TreeMap<>();
        entries.forEach((name, command) -> {
            String[] parts = split(name);
            sorted.computeIfAbsent(parts[0], k -> new TreeMap<>())
                    .computeIfAbsent(parts[1], k -> new TreeMap<>())
                    .put(parts[2], command);
        });
        ObjectNode root = MAPPER.createObjectNode();
        root.put("formatVersion", FORMAT_VERSION);
        root.set("keymaps", MAPPER.valueToTree(sorted));
        try {
            return PRETTY.writeValueAsString(root) + "\n";
        } catch (IOException e) {
            throw new IllegalStateException(e); // a tree of strings always serializes
        }
    }

    /** How an entry is named in a sync report: {@code emacs: C-x C-s}, {@code emacs (macOS): Cmd-s}. */
    static String label(String name) {
        String[] parts = split(name);
        return parts[0] + (MAC.equals(parts[1]) ? " (macOS)" : "") + ": " + parts[2];
    }

    private static void put(
            Map<String, String> entries, String keymap, String platform, @Nullable Map<String, String> overrides) {
        if (overrides == null) {
            return;
        }
        overrides.forEach((chord, command) -> {
            if (chord != null && valid(keymap, chord)) {
                entries.put(name(keymap, platform, chord), command == null ? "" : command);
            }
        });
    }

    private static void parked(
            Map<String, String> entries,
            @Nullable Map<String, Map<String, String>> parked,
            String active,
            String platform) {
        if (parked == null) {
            return;
        }
        parked.forEach((keymap, overrides) -> {
            if (keymap != null && !keymap.equals(active)) { // the active keymap's are the live maps, never these
                put(entries, keymap, platform, overrides);
            }
        });
    }

    private static boolean valid(String keymap, String chord) {
        return !keymap.isBlank() && !chord.isBlank() && keymap.indexOf(SEP) < 0 && chord.indexOf(SEP) < 0;
    }

    private static String name(String keymap, String platform, String chord) {
        return keymap + SEP + platform + SEP + chord;
    }

    private static String[] split(String name) {
        return name.split(String.valueOf(SEP), 3);
    }
}
