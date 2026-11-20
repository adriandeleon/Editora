package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.editora.config.ConfigManager;
import com.editora.config.ProjectSettings;
import com.editora.config.Settings;
import com.editora.toolchain.ProjectToolchain;
import org.jspecify.annotations.Nullable;

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
 * it. {@link #reload()} reads it again (an edited file, a Settings change in Project scope, a trust decision).
 *
 * <p>Every read is made off the FX thread (PERF-01, Pauta 6.0.1) and applied when it lands, under a generation
 * guard, so a slower, older read never replaces a newer one. Meanwhile the scope answers the last values it knew
 * of this root: after a {@link #reload()}, those read before it, until the new read lands and the listeners
 * re-apply. A root whose file has never been read here (a window's first ask is normally answered by the read
 * made with its session, see {@link ConfigManager#takeStartupProjectSettings}) answers as a project with no
 * file — the user's values — and nothing of the project's applies, trusted or not, until its read lands.
 */
final class ProjectScope {

    private static final ProjectSettings EMPTY = new ProjectSettings();

    /** For a window that has no project scope at all (tests, the diff window). */
    static final ProjectScope NONE = new ProjectScope(() -> null, () -> null);

    private final Supplier<Path> root;
    private final Supplier<ConfigManager> config;
    /** The root {@link #cache} holds the values of, or {@code null} while none is known. */
    private @Nullable Path cachedRoot;

    private ProjectSettings cache = EMPTY;
    /** Counts the reads started; a read's result is applied only while it is the latest one. */
    private long readGeneration;
    /** The root of the read under way, or {@code null}. */
    private @Nullable Path readingRoot;

    /**
     * The JDK, Maven and Gradle the project asks for ({@code .editora/settings.json}'s {@code toolchain}, else its
     * {@code .sdkmanrc}), matched to the installs, for the root {@link #toolchainRoot}: with the project file's
     * requests ({@link #toolchainTrusted}) and without them ({@link #toolchainFileOnly}, its untrusted answer).
     */
    private ProjectToolchain.Resolution toolchainTrusted = ProjectToolchain.Resolution.NONE;

    private ProjectToolchain.Resolution toolchainFileOnly = ProjectToolchain.Resolution.NONE;
    private @Nullable Path toolchainRoot;
    private long toolchainGeneration;
    private @Nullable Path toolchainReading;

    /**
     * How the installs are found (a seam: a test stands in for the disk): jkit's and SDKMAN's folders and the
     * machine's other JDKs.
     */
    java.util.function.Supplier<ProjectToolchain.Installs> installs = () -> ProjectToolchain.scan(
            com.editora.toolchain.JkitInstalls.home(),
            com.editora.toolchain.SdkmanInstalls.dir(),
            com.editora.lsp.JavaRuntimes.discover().stream()
                    .map(com.editora.lsp.JavaRuntimes.Jdk::path)
                    .toList());

    /** Where a toolchain read runs: a thread of its own (a seam: a test may read in place). */
    java.util.concurrent.Executor toolchainReads = com.editora.process.BlockingTasks.named("project-toolchain-read");

    /** Where a read runs: a thread of its own (a seam: a test may read in place). */
    java.util.concurrent.Executor reads = com.editora.process.BlockingTasks.named("project-settings-read");

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
            // Read with the window's session, off the FX thread, when the window was built from one that was
            // read ahead (every window but one with no session file); else read now, off the thread too, and
            // answer as a project with no file until it lands.
            ConfigManager c = config.get();
            ProjectSettings ahead = c == null ? null : c.takeStartupProjectSettings(r);
            if (ahead != null) {
                cachedRoot = r;
                cache = ahead;
                if (!r.equals(toolchainReading) && !r.equals(toolchainRoot)) {
                    startToolchainRead(r);
                }
            } else {
                if (!r.equals(readingRoot)) {
                    startRead(r);
                }
                return r.equals(cachedRoot) ? cache : EMPTY; // landed already when read in place
            }
        }
        return cache;
    }

    /** Whether the values of the window's current root are known (read), not stood in for by the user's. */
    boolean known() {
        Path r = root.get();
        return r == null || r.equals(cachedRoot);
    }

    /**
     * Reads {@code r}'s file off the FX thread and applies it when it lands — if no newer read was started
     * meanwhile — telling the listeners when what applies changed.
     */
    private void startRead(Path r) {
        long generation = ++readGeneration;
        readingRoot = r; // before the toolchain read, which may land in place and ask settings() again
        startToolchainRead(r);
        java.util.concurrent.CompletableFuture.supplyAsync(() -> ProjectSettings.load(r), reads)
                .whenComplete((read, failure) -> {
                    Runnable land = () -> landRead(generation, r, read);
                    if (javafx.application.Platform.isFxApplicationThread()) {
                        land.run(); // read in place (a test's seam)
                    } else {
                        javafx.application.Platform.runLater(land);
                    }
                });
    }

    private void landRead(long generation, Path r, @Nullable ProjectSettings read) {
        if (generation != readGeneration) {
            return; // a newer read is under way; it brings the newer values
        }
        readingRoot = null;
        cachedRoot = r;
        cache = read == null ? EMPTY : read; // load never throws; a failure reads as no file, as it always did
        notifyIfChanged();
    }

    /**
     * Reads {@code r}'s toolchain requests ({@code .editora/settings.json}, {@code .sdkmanrc}) and what is installed
     * off the FX thread, and keeps both resolutions when it lands, if no newer read was started meanwhile. A remote
     * project's tools are its host's: nothing is resolved here for it.
     */
    private void startToolchainRead(Path r) {
        long generation = ++toolchainGeneration;
        toolchainReading = r;
        if (!com.editora.process.ExecTargets.runsHere(r)) {
            // Nothing to resolve here: the same answer as before any read, so nobody is told (telling them asks
            // settings(), which would start this read again).
            toolchainReading = null;
            toolchainRoot = r;
            toolchainTrusted = ProjectToolchain.Resolution.NONE;
            toolchainFileOnly = ProjectToolchain.Resolution.NONE;
            return;
        }
        java.util.function.Supplier<ProjectToolchain.Installs> scan = installs;
        java.util.concurrent.CompletableFuture.supplyAsync(
                        () -> {
                            Map<String, String> rc = com.editora.toolchain.SdkmanRc.read(r);
                            Map<String, String> own = ProjectSettings.load(r).getToolchain();
                            boolean asks =
                                    !rc.isEmpty() || own.values().stream().anyMatch(v -> v != null && !v.isBlank());
                            ProjectToolchain.Installs found = asks ? scan.get() : ProjectToolchain.Installs.NONE;
                            return new ProjectToolchain.Resolution[] {
                                ProjectToolchain.resolve(own, rc, found), ProjectToolchain.resolve(Map.of(), rc, found)
                            };
                        },
                        toolchainReads)
                .whenComplete((read, failure) -> {
                    ProjectToolchain.Resolution trusted = read == null ? ProjectToolchain.Resolution.NONE : read[0];
                    ProjectToolchain.Resolution fileOnly = read == null ? ProjectToolchain.Resolution.NONE : read[1];
                    Runnable land = () -> landToolchain(generation, r, trusted, fileOnly);
                    if (javafx.application.Platform.isFxApplicationThread()) {
                        land.run();
                    } else {
                        javafx.application.Platform.runLater(land);
                    }
                });
    }

    private void landToolchain(
            long generation, Path r, ProjectToolchain.Resolution trusted, ProjectToolchain.Resolution fileOnly) {
        if (generation != toolchainGeneration) {
            return;
        }
        toolchainReading = null;
        toolchainRoot = r;
        toolchainTrusted = trusted;
        toolchainFileOnly = fileOnly;
        notifyIfChanged();
    }

    /**
     * The JDK, Maven and Gradle this project asks for and what answers each: the project file's requests in a
     * trusted folder, else its {@code .sdkmanrc}'s alone. {@link ProjectToolchain.Resolution#NONE} without a
     * project, for a remote one, and until the first read lands.
     */
    ProjectToolchain.Resolution toolchain() {
        Path r = root.get();
        if (r == null || !r.equals(toolchainRoot)) {
            if (r != null && !r.equals(toolchainReading)) {
                startToolchainRead(r); // a root not read yet: answered as the user's until the read lands
            }
            return ProjectToolchain.Resolution.NONE;
        }
        return trusted() ? toolchainTrusted : toolchainFileOnly;
    }

    /**
     * The JDK this project's builds, runs, debug sessions and Java language server use: the project's own
     * ({@link #toolchain}) when it names one that is installed, else the user's Default JDK (blank: PATH's java).
     */
    String jdkHome(Settings user) {
        String own = toolchain().home(com.editora.toolchain.ToolInstall.JDK);
        if (own != null) {
            return own;
        }
        String mine = user.getMavenJdkHome();
        return mine == null ? "" : mine;
    }

    /** Whether the project names its own JDK and it is installed, so {@link #jdkHome} is the project's. */
    boolean projectJdk() {
        return toolchain().home(com.editora.toolchain.ToolInstall.JDK) != null;
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
        Path r = root.get();
        if (r == null) {
            readGeneration++; // a read still under way is of a root this window no longer has
            readingRoot = null;
            cachedRoot = null;
            cache = EMPTY;
            toolchainGeneration++;
            toolchainReading = null;
            toolchainRoot = null;
            notifyIfChanged();
            return;
        }
        // A trust decision applies at once: trust is read live, and the values known so far are the file's.
        notifyIfChanged();
        startRead(r); // the file may have changed too: its values apply when the read lands
    }

    /** Tells the listeners when what applies changed since they were last told. */
    private void notifyIfChanged() {
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
    private @Nullable String applied;

    /** What applies here, as one string: root, trust and the values (language servers aside: LspCoordinator's). */
    private String signature() {
        return root() + "|" + trusted() + "|" + settings().valueOverrides() + "|"
                + toolchain().signature();
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

    /** A copy of the trust list, for a check made off the FX thread (Doctor's ESLint/Prettier rows). */
    com.editora.config.TrustStore trustSnapshot() {
        com.editora.config.TrustStore copy = new com.editora.config.TrustStore();
        ConfigManager c = config.get();
        if (c != null) {
            copy.roots.putAll(c.getTrustStore().roots);
        }
        return copy;
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
    @Nullable
    Boolean trimTrailingWhitespace() {
        return settings().editor().trimTrailingWhitespace(trusted());
    }

    /** Whether saving ends a file with a newline; null when the project leaves it. */
    @Nullable
    Boolean insertFinalNewline() {
        return settings().editor().insertFinalNewline(trusted());
    }

    String spellLanguage(Settings user) {
        return settings()
                .editor()
                .spellLanguage(
                        user.getSpellLanguage(),
                        trusted(),
                        id -> com.editora.editor.spell.SpellDictionaries.available()
                                .contains(id));
    }

    /** The command build tool {@code tool} runs here (blank = the tool's own default lookup). */
    String buildCommand(com.editora.build.BuildTool tool, Settings user) {
        String own = settings().buildCommand(toolId(tool), "", trusted());
        if (!own.isBlank()) {
            return own;
        }
        String toolchainTool = tool == com.editora.build.BuildTool.MAVEN
                ? com.editora.toolchain.ToolInstall.MAVEN
                : tool == com.editora.build.BuildTool.GRADLE ? com.editora.toolchain.ToolInstall.GRADLE : null;
        String home = toolchainTool == null ? null : toolchain().home(toolchainTool);
        if (toolchainTool != null && home != null) {
            // the Maven or Gradle the project names (.sdkmanrc's maven=, …); the project's wrapper still wins
            return ProjectToolchain.executable(toolchainTool, home, com.editora.Os.isWindows());
        }
        return tool.commandIn(user);
    }

    /**
     * The project file's own command for {@code tool}, applied or not ({@code ""} when it sets none): what the
     * build trust prompt names, since trusting the folder is what would apply it. Never the user's Settings
     * command, unlike {@link #buildCommand}.
     */
    String projectBuildCommand(com.editora.build.BuildTool tool) {
        return settings().ownBuildCommand(toolId(tool));
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
