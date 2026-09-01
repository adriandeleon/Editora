package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

/**
 * Settings a project overrides for everyone who opens it, kept in a <b>committed file in the project</b> —
 * {@code .editora/settings.json} — the way IntelliJ, Eclipse, NetBeans and Visual Studio all do it (#771).
 *
 * <p>The case this exists for is concrete: one repository needs a JDK 17 language server and another a JDK
 * 25 one, and until now that was a single global preference you had to remember to flip. Putting it beside
 * the code means it travels with the repository and a colleague gets it by checking out.
 *
 * <p>JSON, and named {@code settings.json}, matches the global preferences and the
 * {@code .editora/run-configurations.json} file next to it. A legacy {@code settings.toml} remains readable;
 * the explicit edit action converts it atomically so existing repositories keep their overrides.
 *
 * <p><b>Deliberately a curated subset, not "any setting".</b> Overriding appearance, keymap or font from a
 * repository would let a checkout silently rearrange someone's editor, which is a different and much more
 * invasive thing than telling it which toolchain the project needs. What is here is toolchain configuration:
 * which language server to run, and whether to run it.
 *
 * <p>Absent, unreadable or half-written ⇒ no overrides. A malformed file in someone's repository must not
 * stop the project opening, so every failure path yields empty rather than throwing.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProjectSettings {

    /** Folder Editora keeps project-local, committable files in — shared with the run configurations. */
    public static final String DIR = ".editora";

    public static final String FILE = "settings.json";
    public static final String LEGACY_FILE = "settings.toml";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final TomlMapper LEGACY_TOML = new TomlMapper();

    /** Server id → command line, overriding the global per-server command. */
    private Map<String, String> lspCommands = new LinkedHashMap<>();

    /** Server id → whether to run it, overriding the global per-server enable. */
    private Map<String, Boolean> lspEnabled = new LinkedHashMap<>();

    /** Where a project's overrides live. */
    public static Path fileFor(Path projectRoot) {
        return projectRoot.resolve(DIR).resolve(FILE);
    }

    static Path legacyFileFor(Path projectRoot) {
        return projectRoot.resolve(DIR).resolve(LEGACY_FILE);
    }

    /** Reads a project's overrides; empty (never null, never throwing) when absent or unreadable. */
    public static ProjectSettings load(Path projectRoot) {
        if (projectRoot == null) {
            return new ProjectSettings();
        }
        Path file = fileFor(projectRoot);
        ObjectMapper mapper = JSON;
        if (!Files.isReadable(file)) {
            file = legacyFileFor(projectRoot);
            mapper = LEGACY_TOML;
        }
        if (!Files.isReadable(file)) {
            return new ProjectSettings();
        }
        try {
            ProjectSettings loaded = mapper.readValue(file.toFile(), ProjectSettings.class);
            return loaded == null ? new ProjectSettings() : loaded;
        } catch (Exception e) {
            // Hand-edited and committed by anyone on the team: a typo must not stop the project opening.
            return new ProjectSettings();
        }
    }

    /**
     * Converts a legacy project TOML file to JSON for editing. Existing JSON wins; malformed TOML is left
     * untouched and reported to the caller instead of being replaced by empty overrides.
     */
    public static Path migrateLegacyForEditing(Path projectRoot) throws java.io.IOException {
        Path file = fileFor(projectRoot);
        if (Files.exists(file)) {
            return file;
        }
        Path legacy = legacyFileFor(projectRoot);
        if (!Files.isReadable(legacy)) {
            return file;
        }
        ProjectSettings migrated = LEGACY_TOML.readValue(legacy.toFile(), ProjectSettings.class);
        if (migrated == null) {
            migrated = new ProjectSettings();
        }
        ConfigWriter.writeAtomic(file, JSON, migrated);
        Files.deleteIfExists(legacy);
        return file;
    }

    /** Whether this overrides anything at all — lets callers skip the resolution entirely. */
    @JsonIgnore
    public boolean isEmpty() {
        return lspCommands.isEmpty() && lspEnabled.isEmpty();
    }

    /**
     * The command for {@code serverId}: the project's override when it sets a non-blank one, else
     * {@code global}.
     *
     * <p>A blank override falls through rather than meaning "no command". An empty string in the global
     * settings already means "use the registry default", so treating a blank override as an override would
     * make it impossible to express, and would silently disable a server for the whole team.
     */
    public String commandFor(String serverId, String global) {
        String override = lspCommands.get(serverId);
        return override == null || override.isBlank() ? global : override;
    }

    /** Whether {@code serverId} runs: the project's override when it sets one, else {@code global}. */
    public boolean enabledFor(String serverId, boolean global) {
        Boolean override = lspEnabled.get(serverId);
        return override == null ? global : override;
    }

    public Map<String, String> getLspCommands() {
        return lspCommands;
    }

    public void setLspCommands(Map<String, String> lspCommands) {
        this.lspCommands = lspCommands == null ? new LinkedHashMap<>() : new LinkedHashMap<>(lspCommands);
    }

    public Map<String, Boolean> getLspEnabled() {
        return lspEnabled;
    }

    public void setLspEnabled(Map<String, Boolean> lspEnabled) {
        this.lspEnabled = lspEnabled == null ? new LinkedHashMap<>() : new LinkedHashMap<>(lspEnabled);
    }
}
