package com.editora.plugin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parsed {@code plugin.json} manifest (a lenient Jackson POJO, like {@code SnippetManager.Dto} —
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
