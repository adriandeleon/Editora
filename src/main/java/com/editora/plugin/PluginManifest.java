package com.editora.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The parsed {@code plugin.json} manifest (a lenient Jackson POJO, like the template DTOs —
 * unknown fields ignored). A plugin can be a Java plugin ({@code main} set), declarative-only (commands /
 * keymap / contributed asset dirs), or both. The {@code com.editora.plugin} package is opened to
 * jackson.databind in {@code module-info}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PluginManifest {

    /** Stable plugin id (folder name when omitted). */
    public String id = "";

    public String name = "";
    public String version = "";
    /** Fully-qualified class implementing {@link Plugin}; blank = declarative-only plugin. */
    public String main = "";
    /** Chord → command id (a plugin command id, or a built-in). Applied via the shared keymap. */
    public Map<String, String> keymap = new LinkedHashMap<>();
    /**
     * Chords for one keymap only: keymap id ({@code emacs}, {@code cua}, {@code vscode}, {@code intellij}) →
     * chord → command id. While that keymap is active its entry is used <em>instead of</em> {@link #keymap};
     * a keymap with no entry here gets {@link #keymap}. Chords mean different things in different keymaps
     * ({@code C-c} copies in CUA, starts a prefix in Emacs), so one flat map could not suit them all.
     */
    public Map<String, Map<String, String>> keymaps = new LinkedHashMap<>();
    /** The chords to apply while keymap {@code keymapId} is active (see {@link #keymaps}); never null. */
    public Map<String, String> keymapFor(String keymapId) {
        Map<String, String> own = keymaps == null || keymapId == null ? null : keymaps.get(keymapId);
        if (own != null) {
            return own;
        }
        return keymap == null ? Map.of() : keymap;
    }

    /**
     * {@link #keymapFor} with each target as the command id it names: a bare id ({@code "hello"}) is this
     * plugin's own ({@code plugin.<id>.hello}), a dotted one is used as written, a blank one unbinds the chord.
     */
    public Map<String, String> qualifiedKeymapFor(String keymapId) {
        Map<String, String> out = new LinkedHashMap<>();
        keymapFor(keymapId).forEach((chord, command) -> {
            if (chord != null && !chord.isBlank()) {
                out.put(chord, qualify(id, command));
            }
        });
        return out;
    }

    /** The command id {@code command} names for plugin {@code pluginId} (see {@link #qualifiedKeymapFor}). */
    public static String qualify(String pluginId, String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String c = command.strip();
        return c.indexOf('.') < 0 ? "plugin." + pluginId + "." + c : c;
    }

    /** Palette commands that run an external program. */
    public List<DeclaredCommand> commands = new ArrayList<>();

    /** A declarative palette command: runs {@code run} (argv) in {@code dir} via the subprocess runner. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DeclaredCommand {
        public String id = "";
        public String title = "";
        /** The command line as an argv list (e.g. {@code ["bash", "-c", "echo hi"]}). */
        public List<String> run = new ArrayList<>();
        /** Working directory; blank = the plugin dir. Relative paths resolve against the plugin dir. */
        public String dir = "";
    }
}
