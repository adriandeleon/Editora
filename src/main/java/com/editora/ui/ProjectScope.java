package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.editora.config.ConfigManager;
import com.editora.config.ProjectSettings;
import com.editora.config.Settings;

/**
 * One window's project scope: the values its project's committed {@code .editora/settings.json} sets, whether
 * the folder is trusted to have them, and the effective value of each scopable setting — the project's in a
 * trusted folder, else the user's.
 *
 * <p>Every reader of a scopable setting in a window asks this instead of {@link Settings} directly: the view
 * settings ({@link EditorSettingsCoordinator}), saving ({@link FileWorkflowCoordinator}), the default spelling
 * dictionary, build commands and debugger paths, and the language servers ({@link LspCoordinator}, which owns
 * the instance). A window with no project, or a project with no file, answers the user's values.
 *
 * <p>The file is read once per project root and cached: these are read on every settings apply for every open
 * buffer, and re-reading would be disk I/O on the FX thread for a value that changes only when someone edits
 * it. {@link #reload()} drops the cache (an edited file, a Settings change in Project scope, a trust decision).
 */
final class ProjectScope {

    private static final ProjectSettings EMPTY = new ProjectSettings();

    /** For a window that has no project scope at all (tests, the diff window). */
    static final ProjectScope NONE = new ProjectScope(() -> null, () -> null);

    private final Supplier<Path> root;
    private final Supplier<ConfigManager> config;
    private Path cachedRoot;
    private ProjectSettings cache = EMPTY;

    ProjectScope(Supplier<Path> root, Supplier<ConfigManager> config) {
        this.root = root;
        this.config = config;
    }

    /** The window's project folder, or null without a project. */
    Path root() {
        return root.get();
    }

    /** The project's values (empty without a project or a readable file). */
    ProjectSettings settings() {
        Path r = root.get();
        if (r == null) {
            return EMPTY;
        }
        if (!r.equals(cachedRoot)) {
            cachedRoot = r;
            cache = ProjectSettings.load(r);
        }
        return cache;
    }

    private final List<Runnable> changeListeners = new java.util.ArrayList<>();

    /** Runs {@code listener} after a {@link #reload} that changed what applies (the values, or the trust). */
    void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * Forgets the cached file so the next read picks up an edited one, and tells the listeners when what applies
     * changed: an edited file, another project, a trust decision. A reload that changes nothing tells nobody, so
     * a Settings apply does not re-lay out every buffer twice.
     */
    void reload() {
        cachedRoot = null;
        cache = EMPTY;
        String now = signature();
        if (!now.equals(applied)) {
            applied = now;
            List.copyOf(changeListeners).forEach(Runnable::run);
        }
    }

    /**
     * What applied at the last {@link #reload}. Trust is read live, so comparing with a signature taken just
     * before the reload would miss a trust decision made a moment earlier.
     */
    private String applied;

    /** What applies here, as one string: root, trust and the values (language servers aside: LspCoordinator's). */
    private String signature() {
        return root() + "|" + trusted() + "|" + settings().valueOverrides();
    }

    /**
     * Whether the folder is trusted to have its file's values applied. Only consulted when the file sets
     * something, so a project without one never touches the trust list.
     */
    boolean trusted() {
        Path r = root.get();
        ConfigManager c = config.get();
        return r != null
                && c != null
                && !settings().isEmpty()
                && c.getTrustStore().isTrusted(r);
    }

    // --- effective values ----------------------------------------------------------------------------------------

    int tabSize(Settings user) {
        return settings().editor().tabSize(user.getTabSize(), trusted());
    }

    String indentStyle(Settings user) {
        return settings().editor().indentStyle(user.getIndentStyle(), trusted());
    }

    List<Integer> rulers(Settings user) {
        return settings().editor().rulers(user.getRulers(), trusted());
    }

    Map<String, List<Integer>> languageRulers(Settings user) {
        return settings().editor().languageRulers(user.getLanguageRulers(), trusted());
    }

    boolean wordWrap(Settings user) {
        return settings().editor().wordWrap(user.isWordWrap(), trusted());
    }

    boolean wordWrapProse(Settings user) {
        return settings().editor().wordWrapProse(user.isWordWrapProse(), trusted());
    }

    /** Whether saving trims trailing whitespace; null when the project leaves it to .editorconfig and the default. */
    Boolean trimTrailingWhitespace() {
        return settings().editor().trimTrailingWhitespace(trusted());
    }

    /** Whether saving ends a file with a newline; null when the project leaves it. */
    Boolean insertFinalNewline() {
        return settings().editor().insertFinalNewline(trusted());
    }

    String spellLanguage(Settings user) {
        return settings()
                .editor()
                .spellLanguage(
                        user.getSpellLanguage(),
                        trusted(),
                        id -> com.editora.editor.SpellDictionaries.available().contains(id));
    }

    /** The command build tool {@code tool} runs here (blank = the tool's own default lookup). */
    String buildCommand(com.editora.build.BuildTool tool, Settings user) {
        return settings().buildCommand(toolId(tool), tool.commandIn(user), trusted());
    }

    /** The program or command debug adapter {@code adapterId} uses here ({@code java}, {@code python}, …). */
    String debugTool(String adapterId, String user) {
        return settings().debugTool(adapterId, user, trusted());
    }

    /** A build tool's key in the project file: its enum name in lower case ({@code maven}, {@code npm}, …). */
    static String toolId(com.editora.build.BuildTool tool) {
        return tool.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * {@code properties} with the project's save rules filled in where the file's {@code .editorconfig} says
     * nothing: an {@code .editorconfig} rule wins, as it does over every user setting.
     */
    com.editora.editorconfig.EditorConfigProperties saveRules(
            com.editora.editorconfig.EditorConfigProperties properties) {
        Boolean trim = trimTrailingWhitespace();
        Boolean finalNewline = insertFinalNewline();
        if ((trim == null || properties.trimTrailingWhitespace() != null)
                && (finalNewline == null || properties.insertFinalNewline() != null)) {
            return properties;
        }
        return new com.editora.editorconfig.EditorConfigProperties(
                properties.insertSpaces(),
                properties.indentSize(),
                properties.tabWidth(),
                properties.endOfLine(),
                properties.charset(),
                properties.trimTrailingWhitespace() != null ? properties.trimTrailingWhitespace() : trim,
                properties.insertFinalNewline() != null ? properties.insertFinalNewline() : finalNewline,
                properties.maxLineLength());
    }
}
