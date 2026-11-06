package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * The {@code initializationOptions} each language server starts with (jdtls settings and extended client
 * capabilities, Metals, lemminx and lemminx-maven, the vscode servers, astro-ls), and the project and XML settings
 * laid over them. Pure: {@link LspManager} supplies everything it reads.
 */
final class ServerInitOptions {

    private ServerInitOptions() {}

    /**
     * lemminx-maven's {@code xml.maven} object: whether it may search Maven Central, and the local repository Editora
     * reads ({@code repo.local}, null = none sent), so the server keeps its {@code .lemminx-maven} store beside the
     * repository Doctor shows rather than one it worked out differently (XML/pom review 4, A34). Pure.
     */
    static Map<String, Object> mavenSection(boolean centralIndex, @Nullable String repoLocal) {
        Map<String, Object> maven = new java.util.LinkedHashMap<>();
        maven.put("central", Map.of("skip", !centralIndex));
        if (repoLocal != null && !repoLocal.isBlank()) {
            maven.put("repo", Map.of("local", repoLocal));
        }
        return Map.copyOf(maven);
    }

    /** The local Maven repository as an absolute path, read from {@code settings.xml} under Editora's user.home. */
    static @Nullable String mavenRepoLocal() {
        try {
            return com.editora.maven.MavenPluginGoals.localRepository(Path.of(System.getProperty("user.home", "")))
                    .toAbsolutePath()
                    .normalize()
                    .toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * jdtls's {@code initializationOptions}: {@code settings.java.autobuild.enabled=false} always, plus
     * the java-debug plugin {@code bundles} when {@code debugBundles} is non-empty. Editora only needs
     * jdtls for per-file diagnostics/completion/hover/nav — never its on-disk build output — but by
     * default jdtls runs a full incremental Eclipse workspace <em>build</em> on save/change, which m2e
     * points at the project's own configured Maven/Gradle output directory (e.g. {@code target/classes}
     * for a Maven project — the exact same directory {@code mvn}/{@code gradle} CLI builds write to).
     * Racing jdtls's ECJ builder against a CLI build there can leave that directory missing classes (most
     * visibly javac's synthetic enum-switch-map inner classes, since ECJ doesn't regenerate them in
     * lockstep) even right after a clean CLI build. Disabling autobuild stops jdtls from ever touching
     * that directory; live per-file diagnostics still work via reconcile-on-type, which isn't gated by it.
     * Pure + package-private so it's directly unit-tested.
     */
    static Map<String, Object> javaInitOptions(List<String> debugBundles) {
        return javaInitOptions(debugBundles, false);
    }

    static Map<String, Object> javaInitOptions(List<String> debugBundles, boolean onTypeFormatting) {
        return javaInitOptions(debugBundles, onTypeFormatting, null);
    }

    static Map<String, Object> javaInitOptions(
            List<String> debugBundles, boolean onTypeFormatting, @Nullable List<JavaRuntimes.Jdk> jdks) {
        return javaInitOptions(debugBundles, onTypeFormatting, jdks, null);
    }

    static Map<String, Object> javaInitOptions(
            List<String> debugBundles,
            boolean onTypeFormatting,
            @Nullable List<JavaRuntimes.Jdk> jdks,
            @Nullable String preferredHome) {
        Map<String, Object> options = new java.util.HashMap<>();
        options.put("settings", Map.of("java", javaSettings(onTypeFormatting, jdks, preferredHome)));
        return finishJavaInitOptions(options, debugBundles);
    }

    /**
     * jdtls's {@code settings.java}: autobuild off, plus the JDKs its machine actually has ({@code jdks}, or
     * this machine's when null).
     *
     * <p>{@code configuration.runtimes} is not optional polish. m2e writes a project's execution environment
     * into its Eclipse {@code .classpath} from the pom, so {@code maven.compiler.release=17} demands
     * {@code JavaSE-17}; jdtls can only bind that to a JDK it has been told about, and telling it nothing
     * leaves it knowing only the JVM it runs on. A project targeting any other release then has an
     * unresolved container, and an unresolved container makes {@code vscode.java.resolveClasspath} return an
     * <b>empty classpath with no error</b> — which Run could only report as "the project hasn't finished
     * importing" for a project that had imported perfectly. Declaring the real runtimes also gives jdtls a
     * sane default to fall back to when a project's exact release isn't installed at all.
     */
    private static Map<String, Object> javaSettings(
            boolean onTypeFormatting, @Nullable List<JavaRuntimes.Jdk> jdks, @Nullable String preferredHome) {
        // The keys pushed after initialized, sent here too so the first import already runs with them.
        Map<String, Object> java = new java.util.HashMap<>(LspServerSettings.javaSettings(onTypeFormatting));
        java.put("autobuild", Map.of("enabled", false));
        List<Map<String, Object>> runtimes =
                // jdks given: a remote host's, whose sources are not on this machine's disk to look for
                JavaRuntimes.runtimes(jdks == null ? JavaRuntimes.discover() : jdks, preferredHome, jdks == null);
        return Map.copyOf(withRuntimes(java, runtimes));
    }

    /**
     * {@code java} (jdtls's {@code settings.java}) with {@code runtimes} as {@code configuration.runtimes}, Gradle
     * run on their default, and the Gradle daemons jdtls starts told to stop when idle ({@link
     * #GRADLE_DAEMON_ARGUMENTS}). Sent at start and in every later settings push, so a changed Default JDK reaches
     * a running jdtls (Java debug review 2, B102). Pure.
     */
    static Map<String, Object> withRuntimes(Map<String, Object> java, List<Map<String, Object>> runtimes) {
        Map<String, Object> out = new java.util.HashMap<>(java);
        if (runtimes.isEmpty()) {
            return out;
        }
        Map<String, Object> configuration = new java.util.HashMap<>();
        if (out.get("configuration") instanceof Map<?, ?> existing) {
            existing.forEach((k, v) -> configuration.put(String.valueOf(k), v));
        }
        configuration.put("runtimes", runtimes);
        out.put("configuration", Map.copyOf(configuration));
        Map<String, Object> gradle = new java.util.HashMap<>();
        gradle.put("arguments", GRADLE_DAEMON_ARGUMENTS);
        String gradleJdk = JavaRuntimes.defaultPath(runtimes);
        if (gradleJdk != null) {
            // Gradle runs on the same JDK as the projects' default, not whatever jdtls itself runs on.
            gradle.put("java", Map.of("home", gradleJdk));
        }
        out.put("import", Map.of("gradle", Map.copyOf(gradle)));
        return out;
    }

    private static Map<String, Object> finishJavaInitOptions(Map<String, Object> options, List<String> debugBundles) {
        options.put("extendedClientCapabilities", javaExtendedClientCapabilities());
        if (!debugBundles.isEmpty()) {
            options.put("bundles", debugBundles);
        }
        return Map.copyOf(options);
    }

    /**
     * jdtls's {@code initializationOptions.extendedClientCapabilities} (#741) — a vendor extension, distinct
     * from the LSP {@code ClientCapabilities}, that gates a large part of what jdtls will even offer.
     *
     * <p><b>Measured, not assumed</b> (driving jdtls 1.60 against the same file and position): declaring these
     * takes the code actions at a field from <b>9 to 16</b>. Without them, Generate toString / hashCode+equals
     * / Constructors / Delegate Methods, Override-Implement Methods and Extract Interface are not returned at
     * all — the server simply doesn't offer what it thinks the client cannot drive. Re-measured on 1.62 by
     * {@code JdtlsUpgradeProbeTest}: 11 to 17 at its fixture's field, with the same nine titles depending on them.
     *
     * <p><b>Each prompt flag is a contract, not a hint.</b> Turning one on changes that action's reply from a
     * directly-appliable edit into a {@code java.action.*Prompt} command the client must handle. Enabling one
     * without implementing its prompt doesn't leave the feature as it was — it <em>breaks</em> it: the action
     * still appears and then does nothing. That is why the accessors actions (which work today without any
     * flag) are only listed here alongside a real picker; see {@link JdtlsGenerate}.
     *
     * <p>{@code resolveAdditionalTextEditsSupport} is the odd one out and needs no UI: it tells jdtls to defer
     * an auto-import's edits to {@code completionItem/resolve}, which is exactly what
     * {@code LspCoordinator.autoImportAccept} already does. Without it jdtls computes those edits eagerly for
     * every item in a completion list — pure waste on a per-keystroke path.
     */
    static Map<String, Object> javaExtendedClientCapabilities() {
        Map<String, Object> caps = new java.util.LinkedHashMap<>();
        // Needs no client UI.
        caps.put("classFileContentsSupport", true); // we open jdt:// library source (#665)
        caps.put("resolveAdditionalTextEditsSupport", true); // we resolve on accept (#410/#445)
        caps.put("progressReportProvider", true); // legacy language/progressReport, coalesced on the status bar
        // Each of these is backed by a prompt in JdtlsGenerate — DO NOT enable one without its picker.
        caps.put("generateToStringPromptSupport", true);
        caps.put("hashCodeEqualsPromptSupport", true);
        caps.put("generateConstructorsPromptSupport", true);
        caps.put("overrideMethodsPromptSupport", true);
        // Backed by JdtlsGenerate's accessor and delegate-method prompts.
        caps.put("advancedGenerateAccessorsSupport", true);
        caps.put("generateDelegateMethodsPromptSupport", true);
        // Backed by JdtlsRefactor: java.action.applyRefactoringCommand is carried out by the client.
        // jdtls offers Extract Interface only with both flags. The second one also turns the extract
        // refactorings (variable, constant, method, field) into that same command; each is one
        // java/getRefactorEdit away.
        caps.put("extractInterfaceSupport", true);
        // Resolving "Add all missing imports" then asks about each ambiguous name (chooseImports), as Organize
        // Imports does; without it Date was skipped silently (Java review D6).
        caps.put("advancedOrganizeImportsSupport", true);
        caps.put("advancedExtractRefactoringSupport", true);
        caps.put("moveRefactoringSupport", true);
        // jdtls 1.61+: a move with problems answers a confirmationToken instead of applying anyway; the
        // coordinator shows the problems and resends with the token (review D11). Older servers ignore it.
        caps.put("moveRefactoringConfirmationSupport", true);
        // Extract Method / Variable / Field at a bare caret: the action is offered and the client asks
        // java/inferSelection which expression to extract (review D7). Introduce Parameter becomes the same
        // client-driven command, whose edit is one java/getRefactorEdit away.
        caps.put("inferSelectionSupport", List.of("extractMethod", "extractVariable", "extractField"));
        caps.put("advancedIntroduceParameterRefactoringSupport", true);
        // jdtls asks through workspace/executeClientCommand which class an ambiguous name means (Organize
        // Imports) and waits for the answer; LanguageServerSession.executeClientCommand gives it (review A3).
        caps.put("executeClientCommandSupport", true);
        return Map.copyOf(caps);
    }

    /**
     * The {@code initializationOptions} a server starts with: the TypeScript server's own, else the server's defaults
     * with the project's settings and the XML settings laid over them (jdtls's with its library-sources switch).
     */
    static @Nullable Object startOptions(
            String serverId,
            boolean remote,
            Path root,
            LanguageServerSession session,
            @Nullable Path tsserverPath,
            boolean typeAcquisition,
            boolean loose,
            List<String> startBundles,
            @Nullable Path astroTsdk,
            boolean onTypeFormatting,
            @Nullable List<JavaRuntimes.Jdk> jdks,
            boolean centralIndex,
            @Nullable String javaPreferredHome,
            boolean downloadSources) {
        return TypeScriptServer.SERVER_ID.equals(serverId) && !remote
                ? TypeScriptServer.initializationOptions(tsserverPath, typeAcquisition, loose)
                : TypeScriptServer.SERVER_ID.equals(serverId)
                        ? TypeScriptServer.initializationOptions(null, typeAcquisition, false)
                        : withXmlSettings(
                                serverId,
                                withProjectSettings(
                                        serverId,
                                        LspServerRegistry.JAVA_SERVER_ID.equals(serverId)
                                                ? JavaLibrarySources.withDownloadSources(
                                                        initOptionsFor(
                                                                serverId,
                                                                startBundles,
                                                                astroTsdk,
                                                                onTypeFormatting,
                                                                jdks,
                                                                centralIndex,
                                                                javaPreferredHome),
                                                        downloadSources)
                                                : initOptionsFor(
                                                        serverId,
                                                        startBundles,
                                                        astroTsdk,
                                                        onTypeFormatting,
                                                        jdks,
                                                        centralIndex,
                                                        javaPreferredHome),
                                        remote ? null : root),
                                session.xmlSettings());
    }

    /**
     * {@code options} with lemminx's {@code settings.xml} object ({@code xml}, null = none) for an XML or pom server,
     * so its very first validation already runs with them — no schema download before the first push; anything
     * else unchanged.
     */
    static @Nullable Object withXmlSettings(
            String serverId, @Nullable Object options, @Nullable Map<String, Object> xml) {
        return xml == null || !XmlServerSettings.isLemminx(serverId)
                ? options
                : XmlServerSettings.withSection(options, xml);
    }

    /**
     * {@code options} (jdtls's) with the preferences {@code root}'s own files state ({@link JavaProjectSettings})
     * merged into {@code settings.java}; anything else unchanged.
     */
    @SuppressWarnings("unchecked")
    static @Nullable Object withProjectSettings(String serverId, @Nullable Object options, @Nullable Path root) {
        if (!LspServerRegistry.JAVA_SERVER_ID.equals(serverId) || !(options instanceof Map<?, ?> map) || root == null) {
            return options;
        }
        com.editora.process.ExecTarget target = com.editora.process.ExecTargets.forPath(root);
        Map<String, Object> project = JavaProjectSettings.forRoot(
                root, target.isLocal() ? null : WireUris.mapped(target)::toWire); // a container or WSL (DF2)
        Object settings = map.get("settings");
        if (project.isEmpty() || !(settings instanceof Map<?, ?> s) || !(s.get("java") instanceof Map<?, ?> java)) {
            return options;
        }
        Map<String, Object> out = new java.util.HashMap<>((Map<String, Object>) map);
        out.put("settings", Map.of("java", JavaProjectSettings.merge((Map<String, Object>) java, project)));
        return Map.copyOf(out);
    }

    /**
     * The {@code initialize.initializationOptions} for a server, or {@code null} for one that needs none. Pure
     * + package-private so it's unit-tested:
     * <ul>
     *   <li><b>java</b> (jdtls): {@code settings.java.autobuild.enabled=false} always + the java-debug plugin
     *       {@code bundles} when debugging is on (see {@link #javaInitOptions}).</li>
     *   <li><b>go</b> (gopls): {@code semanticTokens=true} — gopls ships semantic tokens disabled and only
     *       advertises {@code semanticTokensProvider} when this flat key is set (confirmed; {@code ui.} doesn't).</li>
     *   <li><b>json/css/html</b> (vscode-*-language-server): {@code provideFormatter=true} — these servers
     *       implement formatting but only advertise {@code documentFormattingProvider} when VS Code's own
     *       {@code provideFormatter} flag is passed; without it, Format Document was silently unavailable on a
     *       {@code .json}/{@code .css}/{@code .html} even though the server would format (#468; verified by
     *       driving the real servers: the flag flips the advertised capability from false to true).</li>
     *   <li><b>astro</b> (astro-ls): {@code typescript.tsdk} points at a <i>trusted</i> folder's own TypeScript
     *       SDK, or the SDK installed beside the language server ({@link #astroTypeScriptSdk}). Astro refuses
     *       initialization without it, so with neither the server is not started.</li>
     *   <li><b>maven-pom</b> (JVM lemminx + lemminx-maven): {@code settings.xml.maven.central.skip=true} —
     *       the lemminx-maven settings live under an {@code xml.maven} object (schema verified against the
     *       extension's {@code XMLMavenSettings}). {@code central.skip=true} keeps lemminx-maven from asking
     *       search.maven.org (its {@code RemoteCentralRepositorySearcher}; there is no index download since 0.5)
     *       as completion is typed, so coordinates come from the local {@code ~/.m2} repository only —
     *       network-conservative, matching how Git/LSP/update-check are gated — unless Settings → LSP "Search Maven
     *       Central when completing pom.xml coordinates" is on ({@link #setMavenCentralIndex}, review C10, D13).
     *       Sending any {@code xml.maven} object is also what activates the extension's POM awareness in the
     *       first place.</li>
     * </ul>
     */
    static @Nullable Object initOptionsFor(String serverId, List<String> debugBundles) {
        return initOptionsFor(serverId, debugBundles, null, false);
    }

    /** {@code astroTsdk} is the SDK {@link #astroTypeScriptSdk} chose; it is only read for the Astro server. */
    static @Nullable Object initOptionsFor(
            String serverId, List<String> debugBundles, @Nullable Path astroTsdk, boolean javaOnTypeFormatting) {
        return initOptionsFor(serverId, debugBundles, astroTsdk, javaOnTypeFormatting, null);
    }

    /** As above; {@code jdks} are the JDKs jdtls is told about (null: this machine's, discovered now). */
    static @Nullable Object initOptionsFor(
            String serverId,
            List<String> debugBundles,
            @Nullable Path astroTsdk,
            boolean javaOnTypeFormatting,
            @Nullable List<JavaRuntimes.Jdk> jdks) {
        return initOptionsFor(serverId, debugBundles, astroTsdk, javaOnTypeFormatting, jdks, false);
    }

    /** As above; {@code mavenCentralIndex} lets lemminx-maven search Maven Central as coordinates are completed. */
    static @Nullable Object initOptionsFor(
            String serverId,
            List<String> debugBundles,
            @Nullable Path astroTsdk,
            boolean javaOnTypeFormatting,
            @Nullable List<JavaRuntimes.Jdk> jdks,
            boolean mavenCentralIndex) {
        return initOptionsFor(serverId, debugBundles, astroTsdk, javaOnTypeFormatting, jdks, mavenCentralIndex, null);
    }

    /** As above; {@code javaPreferredHome} is the JDK jdtls makes its default ({@link JavaRuntimes#preferredHome}). */
    static @Nullable Object initOptionsFor(
            String serverId,
            List<String> debugBundles,
            @Nullable Path astroTsdk,
            boolean javaOnTypeFormatting,
            @Nullable List<JavaRuntimes.Jdk> jdks,
            boolean mavenCentralIndex,
            @Nullable String javaPreferredHome) {
        return switch (serverId == null ? "" : serverId) {
            case "java" -> javaInitOptions(debugBundles, javaOnTypeFormatting, jdks, javaPreferredHome);
            case "go" -> Map.of("semanticTokens", true);
            case "json", "css", "html" -> Map.of("provideFormatter", true);
            // gvy reads a flat map of its VS Code settings. CodeNarc's style rules flag nearly every line of
            // ordinary Groovy at Information level; compiler errors are what an editor needs by default.
            case "groovy" -> Map.of("groovy.codenarc.enabled", false);
            // Pyright publishes its diagnostics: pulled ones it computes only for the open files unless the client
            // keeps a workspace/diagnostic request waiting, so Problems → Whole project listed nothing more
            // (Python review 2, B105). Publishing is how it reports a whole project.
            case "python" -> Map.of("disablePullDiagnostics", true);
            case "scala" -> metalsInitOptions();
            case ASTRO_SERVER_ID ->
                astroTsdk == null ? null : Map.of("typescript", Map.of("tsdk", astroTsdk.toString()));
            // lemminx's protocol extensions Editora carries out (XML review D6). The xml settings object is
            // the Settings → LSP → XML section's (merge it in beside this key).
            case XmlServer.SERVER_ID -> Map.of("extendedClientCapabilities", XmlServer.extendedClientCapabilities());
            // The pom server is lemminx with the Maven extension: the same protocol extensions (review 2, D15).
            case LspServerRegistry.MAVEN_POM_SERVER_ID ->
                Map.of(
                        "extendedClientCapabilities",
                        XmlServer.extendedClientCapabilities(),
                        "settings",
                        Map.of("xml", Map.of("maven", Map.of("central", Map.of("skip", !mavenCentralIndex)))));
            default -> null;
        };
    }

    static final String ASTRO_SERVER_ID = "astro";

    /**
     * Metals' {@code initializationOptions}: what this client implements of its extensions, and nothing more
     * (an option left out means Metals falls back to plain LSP). Its status items arrive as
     * {@code metals/status}; prompts as {@code metals/inputBox} / {@code metals/quickPick}; editor actions as
     * {@code metals/executeClientCommand}; {@code debuggingProvider} lets Debug start Metals' debug adapter
     * ({@code debug-adapter-start}). {@code isHttpEnabled} off: no local web server for its doctor page.
     */
    static Map<String, Object> metalsInitOptions() {
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("statusBarProvider", "on");
        options.put("icons", "vscode"); // the syntax MetalsStatusView reads (and strips)
        options.put("isHttpEnabled", false);
        options.put("debuggingProvider", true);
        options.put("executeClientCommandProvider", true);
        options.put("inputBoxProvider", true);
        options.put("quickPickProvider", true);
        options.put("isExitOnShutdown", true);
        return options;
    }

    /**
     * The Gradle arguments of jdtls's imports: a daemon it starts stops after ten idle minutes, not Gradle's three
     * hours. jdtls leaves its daemons running when it exits, so each Gradle project opened left a JVM behind long
     * after Editora closed (Java debug review 2, lead note). Only the daemons these imports start are affected:
     * Gradle reuses a daemon only when its settings match.
     */
    static final String GRADLE_DAEMON_ARGUMENTS = "-Dorg.gradle.daemon.idletimeout=600000";
}
