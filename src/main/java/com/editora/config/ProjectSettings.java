package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import org.jspecify.annotations.Nullable;

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
 * invasive thing than telling it which toolchain the project needs. What is here is what a project decides
 * for everyone who works on it: which language server to run, whether to run it, and how the shell server
 * checks and formats scripts (version 1); how its files are indented, ruled, wrapped and trimmed, which
 * spelling dictionary they use, which command runs each build tool and which program each debugger uses
 * (version 2, Settings' Project scope). Every value here applies only in a trusted folder; see
 * {@link #editor()}, {@link #buildCommand} and {@link #debugTool}.
 *
 * <p><b>Versions.</b> A file without {@code version} is version 1 and reads unchanged. Settings writes
 * {@link #VERSION} and keeps every key it does not know ({@link ProjectSettingsFile}); it refuses to write a
 * file from a newer Editora, whose keys it could not keep in step.
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

    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final TomlMapper LEGACY_TOML = new TomlMapper();

    /** The newest file shape this Editora reads and writes: 2 added {@code editor}, build and debug values. */
    public static final int VERSION = 2;

    /** The file's own {@code version}; null in a version-1 file, which never had one. */
    private @Nullable Integer version;

    /** Editor values for the project's files ({@link Editor}); every field null means "the user's own". */
    private Editor editor = new Editor();

    /** Build tool id ({@code maven}, {@code npm}, …) → the command that runs it, overriding the user's. */
    private Map<String, String> buildCommands = new LinkedHashMap<>();

    /** Debug adapter id ({@code java}, {@code python}, {@code javascript}) → its program or command. */
    private Map<String, String> debugTools = new LinkedHashMap<>();

    /** Server id → command line, overriding the global per-server command. */
    private Map<String, String> lspCommands = new LinkedHashMap<>();

    /** Server id → whether to run it, overriding the global per-server enable. */
    private Map<String, Boolean> lspEnabled = new LinkedHashMap<>();

    /**
     * bash-language-server's own settings for this project, in the server's key names (its {@code bashIde}
     * section: {@code shellcheckArguments}, {@code shfmt.caseIndent}, {@code backgroundAnalysisMaxFiles}, …),
     * laid over the user's. The program paths and the explainshell endpoint need a trusted folder.
     */
    private Map<String, Object> bashIde = new LinkedHashMap<>();

    /** Where a project's overrides live. */
    public static Path fileFor(Path projectRoot) {
        return projectRoot.resolve(DIR).resolve(FILE);
    }

    static Path legacyFileFor(Path projectRoot) {
        return projectRoot.resolve(DIR).resolve(LEGACY_FILE);
    }

    /** Reads a project's overrides; empty (never null, never throwing) when absent or unreadable. */
    public static ProjectSettings load(Path projectRoot) {
        if (projectRoot == null || com.editora.vfs.Vfs.isRemote(projectRoot)) {
            // A remote project's overrides would be read over the network on the FX thread, and trust for
            // a remote folder is not yet keyed for this: none for now.
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
     * untouched and reported to the caller instead of being replaced by empty overrides. The converted TOML
     * file is kept as {@code settings.toml.migrated}.
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
        // Kept, renamed: the JSON was produced from the model, so the comments in a hand-written (and often
        // committed) TOML file, and any key the model does not carry, exist nowhere else.
        SharedConfig.retireLegacyFile(legacy);
        return file;
    }

    /** Whether this overrides anything at all — lets callers skip the resolution entirely. */
    @JsonIgnore
    public boolean isEmpty() {
        return lspCommands.isEmpty()
                && lspEnabled.isEmpty()
                && bashIde.isEmpty()
                && editor.isEmpty()
                && blankValues(buildCommands)
                && blankValues(debugTools);
    }

    private static boolean blankValues(Map<String, String> map) {
        return map.values().stream().allMatch(v -> v == null || v.isBlank());
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

    /**
     * The command to <b>launch</b> for {@code serverId}. A project-supplied command is a program a checkout
     * chooses to run with the user's privileges, so it is honoured only for a trusted folder; in an
     * untrusted one the user's own {@code global} command runs, exactly as if the file were absent.
     */
    public String commandFor(String serverId, String global, boolean trusted) {
        return trusted ? commandFor(serverId, global) : global;
    }

    /**
     * Whether {@code serverId} runs, honouring trust. A project may always switch a server <em>off</em> —
     * that only ever runs less. Switching one <em>on</em> that the user disabled globally starts a program
     * the user chose not to run, so it needs a trusted folder.
     */
    public boolean enabledFor(String serverId, boolean global, boolean trusted) {
        boolean wanted = enabledFor(serverId, global);
        return wanted && !global && !trusted ? false : wanted;
    }

    /**
     * What this file asks for that only a trusted folder is given: one line per server whose command it
     * replaces ({@code "java: /opt/jdtls/bin/jdtls"}) or that it switches on against the user's global
     * setting ({@code "rust"}). Empty when it asks for nothing that needs trust. Sorted by server id.
     */
    public java.util.List<String> trustRequests(
            java.util.function.Function<String, String> globalCommand,
            java.util.function.Predicate<String> globalEnabled) {
        java.util.Map<String, String> requests = new java.util.TreeMap<>();
        lspEnabled.forEach((serverId, on) -> {
            if (serverId != null && Boolean.TRUE.equals(on) && !globalEnabled.test(serverId)) {
                requests.put(serverId, serverId);
            }
        });
        lspCommands.forEach((serverId, command) -> {
            if (serverId == null || command == null || command.isBlank()) {
                return;
            }
            String global = globalCommand.apply(serverId);
            if (!command.equals(global)) {
                requests.put(serverId, serverId + ": " + command.strip());
            }
        });
        return java.util.List.copyOf(requests.values());
    }

    // --- version 2: the values Settings' Project scope edits ---------------------------------------------------

    /**
     * The command that runs build tool {@code toolId} here: the project's non-blank one in a trusted folder, else
     * {@code user}. A build command is a program the checkout chooses to run, so it needs trust like a server's.
     */
    public String buildCommand(String toolId, String user, boolean trusted) {
        String own = buildCommands.get(toolId);
        return trusted && own != null && !own.isBlank() ? own.strip() : user;
    }

    /** The program or command debug adapter {@code adapterId} uses here; as {@link #buildCommand}. */
    public String debugTool(String adapterId, String user, boolean trusted) {
        String own = debugTools.get(adapterId);
        return trusted && own != null && !own.isBlank() ? own.strip() : user;
    }

    /**
     * Every value this file sets besides the language-server ones, as {@code key → shown value} in a fixed order
     * ({@code editor.tabSize}, …, {@code build.maven}, {@code debug.python}) — what trusting the folder would
     * apply, for the trust prompt and Settings to list. Values that would be ignored (out of range) are left out.
     */
    @JsonIgnore
    public Map<String, String> valueOverrides() {
        Map<String, String> out = new LinkedHashMap<>();
        Editor e = editor;
        if (e.validIndentStyle() != null) {
            out.put("editor.indentStyle", e.validIndentStyle());
        }
        if (e.validTabSize() != null) {
            out.put("editor.tabSize", String.valueOf(e.validTabSize()));
        }
        if (e.getRulers() != null) {
            out.put("editor.rulers", Editor.columns(e.cleanRulers()));
        }
        if (e.getLanguageRulers() != null && !e.getLanguageRulers().isEmpty()) {
            StringBuilder b = new StringBuilder();
            e.cleanLanguageRulers().forEach((lang, cols) -> {
                if (b.length() > 0) {
                    b.append("; ");
                }
                b.append(lang).append(':');
                if (!cols.isEmpty()) {
                    b.append(' ').append(Editor.columns(cols));
                }
            });
            out.put("editor.languageRulers", b.toString());
        }
        putFlag(out, "editor.wordWrap", e.getWordWrap());
        putFlag(out, "editor.wordWrapProse", e.getWordWrapProse());
        putFlag(out, "editor.trimTrailingWhitespace", e.getTrimTrailingWhitespace());
        putFlag(out, "editor.insertFinalNewline", e.getInsertFinalNewline());
        if (e.getSpellLanguage() != null && !e.getSpellLanguage().isBlank()) {
            out.put("editor.spellLanguage", e.getSpellLanguage().strip());
        }
        new java.util.TreeMap<>(buildCommands).forEach((id, cmd) -> {
            if (cmd != null && !cmd.isBlank()) {
                out.put("build." + id, cmd.strip());
            }
        });
        new java.util.TreeMap<>(debugTools).forEach((id, cmd) -> {
            if (cmd != null && !cmd.isBlank()) {
                out.put("debug." + id, cmd.strip());
            }
        });
        return out;
    }

    private static void putFlag(Map<String, String> out, String key, @Nullable Boolean value) {
        if (value != null) {
            out.put(key, value.toString());
        }
    }

    public @Nullable Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    /** The editor values (never null). */
    public Editor editor() {
        return editor;
    }

    public Editor getEditor() {
        return editor;
    }

    public void setEditor(Editor editor) {
        this.editor = editor == null ? new Editor() : editor;
    }

    public Map<String, String> getBuildCommands() {
        return buildCommands;
    }

    public void setBuildCommands(Map<String, String> buildCommands) {
        this.buildCommands = buildCommands == null ? new LinkedHashMap<>() : new LinkedHashMap<>(buildCommands);
    }

    public Map<String, String> getDebugTools() {
        return debugTools;
    }

    public void setDebugTools(Map<String, String> debugTools) {
        this.debugTools = debugTools == null ? new LinkedHashMap<>() : new LinkedHashMap<>(debugTools);
    }

    /**
     * How a project's files are edited: indentation, tab size, rulers, wrapping, trimming and the spelling
     * dictionary. A null field leaves the user's own value; a value out of range is ignored rather than
     * clamped, so a typo in a committed file never silently becomes something else. Each resolver takes the
     * user's value and whether the folder is trusted, and returns what applies.
     *
     * <p>{@code .editorconfig} still wins where it says something: these replace the user's values in the same
     * place the user's values sit, below a file's {@code .editorconfig} rules.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Editor {
        /** The tab sizes Settings accepts (Settings.MIN_TAB_SIZE..MAX_TAB_SIZE). */
        private static final int MIN_TAB = 1;

        private static final int MAX_TAB = 16;
        private static final int MAX_COLUMN = 1000;

        private @Nullable String indentStyle;
        private @Nullable Integer tabSize;
        private java.util.@Nullable List<Integer> rulers;
        private @Nullable Map<String, java.util.List<Integer>> languageRulers;
        private @Nullable Boolean wordWrap;
        private @Nullable Boolean wordWrapProse;
        private @Nullable Boolean trimTrailingWhitespace;
        private @Nullable Boolean insertFinalNewline;
        private @Nullable String spellLanguage;

        @JsonIgnore
        public boolean isEmpty() {
            return validIndentStyle() == null
                    && validTabSize() == null
                    && rulers == null
                    && (languageRulers == null || languageRulers.isEmpty())
                    && wordWrap == null
                    && wordWrapProse == null
                    && trimTrailingWhitespace == null
                    && insertFinalNewline == null
                    && (spellLanguage == null || spellLanguage.isBlank());
        }

        @Nullable
        String validIndentStyle() {
            return "detect".equals(indentStyle) || "space".equals(indentStyle) || "tab".equals(indentStyle)
                    ? indentStyle
                    : null;
        }

        @Nullable
        Integer validTabSize() {
            return tabSize != null && tabSize >= MIN_TAB && tabSize <= MAX_TAB ? tabSize : null;
        }

        java.util.List<Integer> cleanRulers() {
            java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
            if (rulers != null) {
                for (Integer c : rulers) {
                    if (c != null && c > 0 && c <= MAX_COLUMN) {
                        set.add(c);
                    }
                }
            }
            return java.util.List.copyOf(set);
        }

        Map<String, java.util.List<Integer>> cleanLanguageRulers() {
            Map<String, java.util.List<Integer>> out = new java.util.TreeMap<>();
            if (languageRulers != null) {
                languageRulers.forEach((lang, cols) -> {
                    if (lang != null && !lang.isBlank()) {
                        Editor one = new Editor();
                        one.rulers = cols;
                        out.put(lang.strip().toLowerCase(java.util.Locale.ROOT), one.cleanRulers());
                    }
                });
            }
            return out;
        }

        static String columns(java.util.List<Integer> cols) {
            StringBuilder b = new StringBuilder();
            for (Integer c : cols) {
                if (b.length() > 0) {
                    b.append(", ");
                }
                b.append(c);
            }
            return b.toString();
        }

        public String indentStyle(String user, boolean trusted) {
            String own = validIndentStyle();
            return trusted && own != null ? own : user;
        }

        public int tabSize(int user, boolean trusted) {
            Integer own = validTabSize();
            return trusted && own != null ? own : user;
        }

        public java.util.List<Integer> rulers(java.util.List<Integer> user, boolean trusted) {
            return trusted && rulers != null ? cleanRulers() : user;
        }

        /** The user's per-language rulers with the project's laid over them, language by language. */
        public Map<String, java.util.List<Integer>> languageRulers(
                Map<String, java.util.List<Integer>> user, boolean trusted) {
            if (!trusted || languageRulers == null || languageRulers.isEmpty()) {
                return user;
            }
            Map<String, java.util.List<Integer>> out = new LinkedHashMap<>();
            if (user != null) {
                user.forEach((lang, cols) -> out.put(lang.toLowerCase(java.util.Locale.ROOT), cols));
            }
            out.putAll(cleanLanguageRulers());
            return out;
        }

        public boolean wordWrap(boolean user, boolean trusted) {
            return trusted && wordWrap != null ? wordWrap : user;
        }

        public boolean wordWrapProse(boolean user, boolean trusted) {
            return trusted && wordWrapProse != null ? wordWrapProse : user;
        }

        /** Whether saving trims trailing whitespace, or null when the project leaves it (Editora's default: no). */
        public @Nullable Boolean trimTrailingWhitespace(boolean trusted) {
            return trusted ? trimTrailingWhitespace : null;
        }

        /** Whether saving ends the file with a newline, or null when the project leaves it. */
        public @Nullable Boolean insertFinalNewline(boolean trusted) {
            return trusted ? insertFinalNewline : null;
        }

        /** The default spelling dictionary: the project's when {@code available} has it, else {@code user}. */
        public String spellLanguage(String user, boolean trusted, java.util.function.Predicate<String> available) {
            String own = spellLanguage == null ? null : spellLanguage.strip();
            return trusted && own != null && !own.isEmpty() && available.test(own) ? own : user;
        }

        public @Nullable String getIndentStyle() {
            return indentStyle;
        }

        public void setIndentStyle(String indentStyle) {
            this.indentStyle = indentStyle;
        }

        public @Nullable Integer getTabSize() {
            return tabSize;
        }

        public void setTabSize(Integer tabSize) {
            this.tabSize = tabSize;
        }

        public java.util.@Nullable List<Integer> getRulers() {
            return rulers;
        }

        public void setRulers(java.util.List<Integer> rulers) {
            this.rulers = rulers;
        }

        public @Nullable Map<String, java.util.List<Integer>> getLanguageRulers() {
            return languageRulers;
        }

        public void setLanguageRulers(Map<String, java.util.List<Integer>> languageRulers) {
            this.languageRulers = languageRulers;
        }

        public @Nullable Boolean getWordWrap() {
            return wordWrap;
        }

        public void setWordWrap(Boolean wordWrap) {
            this.wordWrap = wordWrap;
        }

        public @Nullable Boolean getWordWrapProse() {
            return wordWrapProse;
        }

        public void setWordWrapProse(Boolean wordWrapProse) {
            this.wordWrapProse = wordWrapProse;
        }

        public @Nullable Boolean getTrimTrailingWhitespace() {
            return trimTrailingWhitespace;
        }

        public void setTrimTrailingWhitespace(Boolean trimTrailingWhitespace) {
            this.trimTrailingWhitespace = trimTrailingWhitespace;
        }

        public @Nullable Boolean getInsertFinalNewline() {
            return insertFinalNewline;
        }

        public void setInsertFinalNewline(Boolean insertFinalNewline) {
            this.insertFinalNewline = insertFinalNewline;
        }

        public @Nullable String getSpellLanguage() {
            return spellLanguage;
        }

        public void setSpellLanguage(String spellLanguage) {
            this.spellLanguage = spellLanguage;
        }
    }

    public Map<String, String> getLspCommands() {
        return lspCommands;
    }

    public void setLspCommands(Map<String, String> lspCommands) {
        this.lspCommands = lspCommands == null ? new LinkedHashMap<>() : new LinkedHashMap<>(lspCommands);
    }

    public Map<String, Object> getBashIde() {
        return bashIde;
    }

    public void setBashIde(Map<String, Object> bashIde) {
        this.bashIde = bashIde == null ? new LinkedHashMap<>() : new LinkedHashMap<>(bashIde);
    }

    public Map<String, Boolean> getLspEnabled() {
        return lspEnabled;
    }

    public void setLspEnabled(Map<String, Boolean> lspEnabled) {
        this.lspEnabled = lspEnabled == null ? new LinkedHashMap<>() : new LinkedHashMap<>(lspEnabled);
    }
}
