package com.editora.sync;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.editora.command.KeymapManager;
import com.editora.command.UserKeyBindings;
import com.editora.config.Settings;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

/**
 * The user's key bindings as the sync repository holds them. In the config directory they are part of
 * {@code settings.json} ({@link Settings#getKeyBindings()} and {@link Settings#getKeyBindingsMacOnly()}), and
 * only those are synced, never the rest of the file.
 *
 * <pre>{
 *   "formatVersion" : 2,
 *   "keymaps" : {
 *     "vscode" : {
 *       "shared" : { "file.save" : [ "C-M-s" ], "view.toggleZen" : [ ] },
 *       "macOnly" : { "nav.goToLine" : [ "C-l" ] }
 *     }
 *   }
 * }</pre>
 *
 * <p>Bindings are kept per keymap, so a computer running another keymap takes them for when it switches, and
 * which keymap is active stays a choice of each computer. They are kept by command and are the same on every
 * operating system ({@link UserKeyBindings}): {@code shared} in Windows/Linux notation, {@code macOnly} the
 * chords only a Mac can type. An empty list unbinds the command. The merge unit is one command of one keymap.
 *
 * <p>A file in layout 1 (overrides by chord, apart for macOS and for Windows and Linux) is still read: it is
 * converted as a settings file of that time is ({@link UserKeyBindings#fromSplit}). Pure.
 */
public final class KeybindingsFile {

    /** The file's path in the repository. */
    public static final String PATH = "keybindings.json";

    /** The layout this build writes; a file with a higher one was written by a newer Editora. */
    public static final int FORMAT_VERSION = 2;

    static final String SHARED = "shared";
    static final String MAC_ONLY = "macOnly";

    /** Layout 1's two sections. */
    private static final String V1_WINDOWS_LINUX = "windowsLinux";

    private static final String V1_MAC = "macOS";

    /** Joins the parts of an entry name: keymap, section and command never hold a tab. */
    private static final char SEP = '\t';

    /** Joins the chords of an entry's value: a chord never holds a line break. */
    private static final String CHORDS = "\n";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectWriter PRETTY =
            MAPPER.writer(new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));

    private KeybindingsFile() {}

    /** A newer Editora wrote the file. */
    static final class NewerFormatException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** The file for {@code settings}' bindings; null when there are none. */
    public static @Nullable String render(Settings settings) {
        Map<String, String> entries = new LinkedHashMap<>();
        put(entries, SHARED, settings.getKeyBindings());
        put(entries, MAC_ONLY, settings.getKeyBindingsMacOnly());
        return text(entries);
    }

    /** Replaces {@code settings}' bindings, of every keymap, with those of {@code text} (null: no file, so none). */
    public static void apply(Settings settings, @Nullable String text) throws IOException {
        Map<String, Map<String, List<String>>> shared = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> macOnly = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries(text).entrySet()) {
            String[] parts = split(e.getKey());
            (MAC_ONLY.equals(parts[1]) ? macOnly : shared)
                    .computeIfAbsent(parts[0], k -> new LinkedHashMap<>())
                    .put(parts[2], chords(e.getValue()));
        }
        settings.setKeyBindings(shared);
        settings.setKeyBindingsMacOnly(macOnly);
    }

    /**
     * The entries of {@code text} — {@code keymap \t section \t command} to its chords, one per line — in file
     * order. Empty for null or blank text.
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
        int format = tree.path("formatVersion").asInt(0);
        if (format > FORMAT_VERSION) {
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
            if (format < 2) {
                UserKeyBindings.Store store = fromLayout1(keymap.getKey(), keymap.getValue());
                put(out, keymap.getKey(), SHARED, store.shared());
                put(out, keymap.getKey(), MAC_ONLY, store.macOnly());
                continue;
            }
            for (String section : new String[] {SHARED, MAC_ONLY}) {
                JsonNode commands = keymap.getValue().path(section);
                for (Iterator<Map.Entry<String, JsonNode>> c = commands.fields(); c.hasNext(); ) {
                    Map.Entry<String, JsonNode> command = c.next();
                    if (command.getValue().isArray() && valid(keymap.getKey(), command.getKey())) {
                        List<String> chords = new ArrayList<>();
                        command.getValue().forEach(chord -> {
                            if (chord.isTextual() && !chord.asText().isBlank()) {
                                chords.add(chord.asText());
                            }
                        });
                        out.put(name(keymap.getKey(), section, command.getKey()), String.join(CHORDS, chords));
                    }
                }
            }
        }
        return out;
    }

    /** One keymap of a layout 1 file, as the store it becomes. */
    private static UserKeyBindings.Store fromLayout1(String keymap, JsonNode sections) {
        return UserKeyBindings.fromSplit(
                overrides(sections.path(V1_WINDOWS_LINUX)),
                overrides(sections.path(V1_MAC)),
                KeymapManager.stockOf(keymap, false),
                KeymapManager.stockOf(keymap, true),
                KeymapManager.AVAILABLE.containsKey(keymap) && KeymapManager.hasMacVariant(keymap));
    }

    private static Map<String, String> overrides(JsonNode chords) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> c = chords.fields(); c.hasNext(); ) {
            Map.Entry<String, JsonNode> chord = c.next();
            if (chord.getValue().isTextual() && !chord.getKey().isBlank()) {
                out.put(chord.getKey(), chord.getValue().asText());
            }
        }
        return out;
    }

    /** The file holding {@code entries}, in a canonical form (sorted); null when there are none. */
    static @Nullable String text(Map<String, String> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Map<String, List<String>>>> sorted = new TreeMap<>();
        entries.forEach((name, chords) -> {
            String[] parts = split(name);
            sorted.computeIfAbsent(parts[0], k -> new TreeMap<>())
                    .computeIfAbsent(parts[1], k -> new TreeMap<>())
                    .put(parts[2], chords(chords));
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

    /** How an entry is named in a sync report: {@code emacs: file.save}, {@code vscode (macOS only): nav.goToLine}. */
    static String label(String name) {
        String[] parts = split(name);
        return parts[0] + (MAC_ONLY.equals(parts[1]) ? " (macOS only)" : "") + ": " + parts[2];
    }

    private static void put(
            Map<String, String> entries, String section, @Nullable Map<String, Map<String, List<String>>> byKeymap) {
        if (byKeymap == null) {
            return;
        }
        byKeymap.forEach((keymap, bindings) -> {
            if (keymap != null && bindings != null) {
                put(entries, keymap, section, bindings);
            }
        });
    }

    private static void put(
            Map<String, String> entries, String keymap, String section, Map<String, List<String>> bindings) {
        bindings.forEach((command, chords) -> {
            if (command != null && valid(keymap, command)) {
                entries.put(name(keymap, section, command), chords == null ? "" : String.join(CHORDS, chords));
            }
        });
    }

    private static List<String> chords(@Nullable String value) {
        return value == null || value.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(value.split(CHORDS)));
    }

    private static boolean valid(String keymap, String command) {
        return !keymap.isBlank() && !command.isBlank() && keymap.indexOf(SEP) < 0 && command.indexOf(SEP) < 0;
    }

    private static String name(String keymap, String section, String command) {
        return keymap + SEP + section + SEP + command;
    }

    private static String[] split(String name) {
        return name.split(String.valueOf(SEP), 3);
    }
}
