package com.editora.install;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The pure, toolkit-free catalog of installable language support: which {@link Step}s make up each
 * {@link Lang}'s LSP server + DAP adapter (Mermaid is render-tools only), plus the small pure helpers that
 * build the install commands/URLs. {@link InstallService} executes the steps; {@code ui/InstallCoordinator}
 * orchestrates. Kept dependency-light (java.base only) so the recipe is unit-tested without a network or FX.
 *
 * <p>Most tools have a clean cross-platform installer: npm globals (Pyright, typescript-language-server,
 * astro-ls, mermaid-cli), a pip {@code --target} (debugpy), and an Open VSX {@code .vsix} — a ZIP, extracted by the
 * existing {@code plugin/Unzip} — for java-debug. Eclipse JDT-LS and vscode-js-debug ship only a
 * {@code .tar.gz}, extracted via the system {@code tar} (present on macOS/Linux + Windows 10+).
 */
public final class InstallCatalog {

    private InstallCatalog() {}

    /** A language whose support can be installed. */
    public enum Lang {
        JAVA,
        PYTHON,
        JAVASCRIPT,
        MERMAID
    }

    /** How a {@link Step} installs. */
    public enum Kind {
        /** {@code npm install -g <pkgs>} (global). */
        NPM_GLOBAL,
        /** {@code python -m pip install --upgrade --target <dest> <pkg>}. */
        PIP_TARGET,
        /** Download a {@code .vsix} (a ZIP) and extract it (optionally just the java-debug jar). */
        VSIX,
        /** Download a {@code .tar.gz} and extract it with the system {@code tar}. */
        TARBALL,
        /** Run a fixed command via the language's own package manager (go/gem/dotnet/rustup/composer);
         *  the argv is carried in {@code npmPackages}. */
        TOOL_COMMAND,
        /** Download a per-OS/arch binary archive (zip or tar.gz) and extract it into the config dir; the
         *  details (release API, per-platform asset, binary name) come from {@link #archiveSpec}. */
        ARCHIVE,
        /** {@code npx puppeteer browsers install chrome-headless-shell} — the headless Chrome that
         *  mermaid-cli (mmdc) drives; run from mmdc's own dir so the version matches its Puppeteer. */
        PUPPETEER_BROWSER,
        /** Download a self-contained JVM LSP "zip with dependencies" ({@code directUrl}), extract it into the
         *  config dir, add the pinned jars the bundle leaves out ({@link #jvmLspExtraJars}), prove the server
         *  answers a request ({@link JvmLspSmoke}), and set the server command to
         *  {@code java -cp "<dir>/*" <mainClass>}. Used for the Maven-aware pom.xml server (JVM lemminx +
         *  lemminx-maven), whose bundle is cross-platform. */
        JVM_LSP_ZIP,
        /** A JVM language server shipped as one pinned fat jar ({@link #jvmLspJar}): downloaded, refused unless its
         *  SHA-256 matches, put at {@code <config>/<destSubpath>/<file>}, and the server command set to
         *  {@code java -jar "<file>"}. The Groovy server (gvy's {@code gls}). */
        JVM_LSP_JAR,
        /** A JVM language server published only as Maven artifacts ({@link #coursierLsp}): coursier's pinned
         *  native launcher ({@link #COURSIER_TOOL}) is downloaded and checked, {@code cs fetch} resolves the
         *  server's classpath into {@code <config>/<destSubpath>/lib}, and the server command becomes
         *  {@code java <options> -cp "<lib>/*" <mainClass>}. Metals, for Scala. */
        COURSIER_LSP,
        /** A command-line tool from a pinned release (ShellCheck, shfmt): the per-platform asset is downloaded,
         *  its SHA-256 checked against the hash in {@link #pinnedTool}, and the binary put at
         *  {@code <config>/plugins/tools/<id>/<binary>} — see {@link #installedToolPath}. */
        PINNED_TOOL,
        /** A language server distribution from a pinned release ({@link #pinnedServer}): too big to hold in memory
         *  (JetBrains' Kotlin LSP is ~370 MB with its own Java runtime), so the per-platform archive is streamed
         *  to disk with its SHA-256 checked as it arrives, refused unless it matches, unpacked by the system
         *  {@code tar} into {@code <config>/<destSubpath>}, and the server command set to its launcher. */
        PINNED_SERVER
    }

    /** The host OS/arch buckets used to pick a per-platform release asset. */
    public enum Platform {
        MAC_ARM,
        MAC_X64,
        LINUX_X64,
        LINUX_ARM,
        WIN_X64
    }

    /** An external runtime a step needs already on PATH — Editora never installs these itself. */
    public enum Prereq {
        /** Node.js + {@code npm}. */
        NPM,
        /** {@code python3}/{@code python} + pip. */
        PYTHON,
        /** The {@code tar} command (system bsdtar on Win10+). */
        TAR,
        /** The Go toolchain ({@code go install}). */
        GO,
        /** Ruby + {@code gem}. */
        RUBY,
        /** The .NET SDK ({@code dotnet tool}). */
        DOTNET,
        /** {@code rustup} (Rust toolchain manager). */
        RUSTUP,
        /** PHP's {@code composer}. */
        COMPOSER,
        /** A Java runtime ({@code java} on PATH) — the JVM lemminx + lemminx-maven server. Same requirement
         *  jdtls already imposes; the packaged Editora runtime's {@code bin/} is stripped so it can't be used. */
        JAVA
    }

    /**
     * The Eclipse JDT-LS milestone Editora installs. <b>Pinned deliberately.</b> This was the
     * {@code snapshots/jdt-language-server-latest.tar.gz} nightly, which means two users installing a week
     * apart get different servers and a bad overnight build ships straight to them. It matters more for jdtls
     * than for most servers because Editora's documented jdtls behaviour is <i>measured</i> against a specific
     * build — the {@code extendedClientCapabilities} code-action set, the inlay-hint line-range off-by-one, the
     * {@code java/…} raw requests that never appear in {@code executeCommandProvider.commands} — so a version
     * change has to be a deliberate bump, not whatever landed last night.
     *
     * <p>Bump this and {@link #JDTLS_BUILD} together (each milestone dir holds exactly one tarball) and re-check
     * those behaviours with {@code JdtlsUpgradeProbeTest} (opt-in; it measures all three against the new
     * tarball); the milestone list is at {@code https://download.eclipse.org/jdtls/milestones/}.
     */
    public static final String JDTLS_VERSION = "1.63.0";

    /**
     * The TypeScript the language servers that need its {@code tsserver} are installed with. TypeScript 7 is
     * the native (Go) compiler and ships no {@code lib/tsserver.js}, so typescript-language-server (and astro-ls)
     * cannot start on it; since TS 7 became npm's {@code latest}, an unpinned {@code typescript} gave a server
     * that crashed on every file. Pinned to the last major with a tsserver.
     */
    public static final String TYPESCRIPT_PACKAGE = "typescript@6";

    /** The build stamp of the {@link #JDTLS_VERSION} tarball. */
    public static final String JDTLS_BUILD = "202610191322";

    /** Eclipse JDT-LS pinned milestone; extracts to top-level {@code bin/ config/ features/ plugins/}. */
    public static final String JDTLS_TARBALL_URL = "https://download.eclipse.org/jdtls/milestones/"
            + JDTLS_VERSION
            + "/jdt-language-server-"
            + JDTLS_VERSION
            + "-"
            + JDTLS_BUILD
            + ".tar.gz";

    /**
     * The vscode-js-debug release Editora installs: pinned, not "latest" (review D16) — a new release can raise the
     * Node.js it needs or move its entry point under users, so a newer one is adopted deliberately, after testing.
     * No checksum: GitHub publishes none for the release asset.
     */
    public static final String JS_DEBUG_VERSION = "v1.117.0";

    /** GitHub release metadata for {@link #JS_DEBUG_VERSION} (the {@code js-debug-dap-v*.tar.gz} asset). */
    public static final String JS_DEBUG_RELEASES_API =
            "https://api.github.com/repos/microsoft/vscode-js-debug/releases/tags/" + JS_DEBUG_VERSION;

    /** The lemminx-maven release used for the Maven-aware pom.xml server. */
    public static final String LEMMINX_MAVEN_VERSION = "0.12.0";

    /**
     * What the pom.xml server's install downloads in MB, rounded: the lemminx-maven bundle from the Eclipse
     * repository plus {@link #LEMMINX_MAVEN_EXTRA_JARS} from Maven Central (16.1 MB in all for 0.12.0).
     */
    public static final int LEMMINX_MAVEN_DOWNLOAD_MB = 16;

    /**
     * The lemminx-maven "zip with dependencies" — a single self-contained bundle (~10 MB) holding lemminx core
     * ({@code org.eclipse.lemminx}), the Maven extension, the Maven resolver/embedder deps, and lsp4j. Hosted on
     * Eclipse's own repository (lemminx-maven is published to neither Maven Central nor GitHub release assets).
     */
    public static final String LEMMINX_MAVEN_ZIP_URL =
            "https://repo.eclipse.org/content/repositories/lemminx-releases/org/eclipse/lemminx/lemminx-maven/"
                    + LEMMINX_MAVEN_VERSION
                    + "/lemminx-maven-"
                    + LEMMINX_MAVEN_VERSION
                    + "-zip-with-dependencies.zip";

    /** lemminx's stdio launcher main class (inside the bundle's lemminx-core jar). */
    public static final String LEMMINX_MAIN_CLASS = "org.eclipse.lemminx.XMLServerLauncher";

    /** Maven Central, where {@link #LEMMINX_MAVEN_EXTRA_JARS} come from. */
    public static final String MAVEN_CENTRAL = "https://repo1.maven.org/maven2/";

    /**
     * The jars the lemminx-maven "zip with dependencies" does <b>not</b> contain although the server cannot answer
     * a single request without them: lemminx-maven's pom marks guava, commons-lang3 and jsoup {@code provided}
     * (vscode-xml supplies them from its own lemminx uber-jar), so the bundle starts, says it is ready, and then
     * fails every completion, hover, definition and outline inside the server with
     * {@code NoClassDefFoundError: com/google/common/cache/CacheBuilder} (Maven review C1). They are downloaded
     * from Maven Central into the same folder — the launch command's {@code <dir>/*} classpath picks them up.
     * Pinned by version <b>and</b> SHA-256 (the hashes Central publishes beside each jar, checked against a
     * downloaded copy); the set was validated against the real server: with exactly these three, local-repo
     * completion, hover, diagnostics and go-to-parent all work. Bump with {@link #LEMMINX_MAVEN_VERSION}.
     */
    public static final List<JvmLspJar> LEMMINX_MAVEN_EXTRA_JARS = List.of(
            mavenCentralJar(
                    "com.google.guava",
                    "guava",
                    "33.7.2-jre",
                    "b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c"),
            mavenCentralJar(
                    "org.apache.commons",
                    "commons-lang3",
                    "3.21.0",
                    "a2af247ec4db6eb8947e18e23e0433e4871a7c5d0a40e1f92e46ede54ae1defe"),
            mavenCentralJar(
                    "org.jsoup",
                    "jsoup",
                    "1.23.2",
                    "e9d8c856856680427f096d156f02a414289985c930998fcb519ce5be39b42003"));

    /**
     * Lombok, downloaded on request for a project that declares it when no build has cached a jar yet
     * ({@code JavaLombok} runs jdtls with it as a Java agent). Pinned by version and SHA-256 (checked against a
     * copy verified by Central's SHA-1); stored under {@link #LOMBOK_SUBPATH}.
     */
    public static final JvmLspJar LOMBOK = mavenCentralJar(
            "org.projectlombok",
            "lombok",
            "1.18.48",
            "85477a4655ebb2c074a9099cfb749be454449fee564d4282610df1b85f7c508b");

    /** Where Editora keeps its own Lombok jar, under the config dir. */
    public static final String LOMBOK_SUBPATH = "plugins/lsp/lombok";

    /** A pinned Maven Central jar: {@code <MAVEN_CENTRAL>/<group as path>/<artifact>/<version>/<artifact>-<version>.jar}. Pure. */
    public static JvmLspJar mavenCentralJar(String groupId, String artifactId, String version, String sha256) {
        String file = artifactId + "-" + version + ".jar";
        return new JvmLspJar(
                MAVEN_CENTRAL + groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + file,
                sha256,
                file);
    }

    /**
     * The extra jars a {@link Kind#JVM_LSP_ZIP} step downloads beside its bundle, by step id: the lemminx-maven
     * step's {@link #LEMMINX_MAVEN_EXTRA_JARS}; none for any other. Pure.
     */
    public static List<JvmLspJar> jvmLspExtraJars(String stepId) {
        return LEMMINX_MAVEN_STEP.equals(stepId) ? LEMMINX_MAVEN_EXTRA_JARS : List.of();
    }

    /** The id of the {@code maven-pom} install step (the status line says "Installing lemminx-maven…"). */
    public static final String LEMMINX_MAVEN_STEP = "lemminx-maven";

    /**
     * The launch command for a JVM LSP whose jars were extracted to {@code dir}:
     * {@code java -cp "<dir>/*" <mainClass>}. The trailing {@code /*} classpath wildcard is expanded by the JVM
     * itself (not a shell), so every jar in {@code dir} is picked up; the directory is quoted so a config path
     * with spaces survives re-tokenizing as one argv element. Pure/unit-testable.
     */
    public static String jvmClasspathCommand(java.nio.file.Path dir, String mainClass) {
        return "java -cp \"" + dir + java.io.File.separator + "*\" " + mainClass;
    }

    /**
     * The command stored in Settings for an extracted server {@code binary}: its path — quoted when it needs
     * to be (see {@link #quoteCommandPath}) — followed by {@code suffix} (e.g. {@code " serve"}). Pure.
     */
    public static String binaryCommand(java.nio.file.Path binary, String suffix) {
        return quoteCommandPath(binary.toString()) + (suffix == null ? "" : suffix);
    }

    /**
     * Quotes {@code path} for storage in a command setting, so the quote-aware tokenizers that read it back
     * ({@code LspServerRegistry.tokenize}, {@code ProgramArgs.tokenize}) return it as <b>one</b> argv element.
     *
     * <p>The stored value is a command line, not a path: unquoted, a config dir with a space
     * ({@code C:\Users\Jane Doe\.editora}) split into {@code C:\Users\Jane} + {@code Doe\...}, so a
     * server was reported missing the moment its install finished. A path with nothing to protect is returned
     * unchanged, so an ordinary install stores exactly what it always did. Those tokenizers do no escape
     * processing — a backslash is literal, which is what keeps Windows paths intact — so a path containing
     * one kind of quote is wrapped in the other, and one containing both alternates styles (adjacent quoted
     * runs join into a single token). Pure; unit-tested against the tokenizers.
     */
    public static String quoteCommandPath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        boolean plain = path.chars().noneMatch(c -> Character.isWhitespace(c) || c == '"' || c == '\'');
        if (plain) {
            return path;
        }
        if (path.indexOf('"') < 0) {
            return '"' + path + '"';
        }
        if (path.indexOf('\'') < 0) {
            return '\'' + path + '\'';
        }
        return '"' + path.replace("\"", "\"'\"'\"") + '"';
    }

    /**
     * One install action. Only the fields relevant to {@link #kind} are populated; the rest are
     * {@code null}/empty. {@code id} is a stable identifier (also the i18n + availability key);
     * {@code destSubpath} is relative to the config dir.
     */
    public record Step(
            String id,
            Kind kind,
            Set<Prereq> prereqs,
            @Nullable List<String> npmPackages,
            @Nullable String pipPackage,
            @Nullable String apiUrl,
            @Nullable String assetPattern,
            @Nullable String directUrl,
            boolean extractJarOnly,
            String destSubpath,
            @Nullable String verifyEntry) {}

    /**
     * The missing steps split by whether their prerequisites are {@code present}: {@code runnable} ones install
     * now, {@code blocked} ones wait for what they {@code need}. Python support is Pyright (npm) and debugpy
     * (pip): without Node, debugpy alone still installs instead of nothing at all.
     */
    public record Plan(List<Step> runnable, List<Step> blocked, Set<Prereq> need) {}

    /** Splits {@code missing} by {@code present} prerequisites. Pure. */
    public static Plan plan(List<Step> missing, Set<Prereq> present) {
        List<Step> runnable = new ArrayList<>();
        List<Step> blocked = new ArrayList<>();
        Set<Prereq> need = java.util.EnumSet.noneOf(Prereq.class);
        for (Step s : missing) {
            Set<Prereq> lacking = java.util.EnumSet.noneOf(Prereq.class);
            lacking.addAll(s.prereqs());
            lacking.removeAll(present);
            if (lacking.isEmpty()) {
                runnable.add(s);
            } else {
                blocked.add(s);
                need.addAll(lacking);
            }
        }
        return new Plan(List.copyOf(runnable), List.copyOf(blocked), need);
    }

    /** The Open VSX "latest release" metadata URL for an extension. Pure. */
    public static String openVsxLatestUrl(String namespace, String name) {
        return "https://open-vsx.org/api/" + namespace + "/" + name + "/latest";
    }

    /** {@code [npm, install, -g, <pkgs…>]}. Pure. */
    public static List<String> npmInstallGlobalArgv(@Nullable List<String> pkgs) {
        List<String> argv = new ArrayList<>(List.of("npm", "install", "-g"));
        argv.addAll(pkgs);
        return List.copyOf(argv);
    }

    /** {@code [<python>, -m, pip, install, --upgrade, --target, <dest>, <pkg>]}. Pure. */
    public static List<String> pipInstallTargetArgv(String python, Path dest, @Nullable String pkg) {
        return List.of(python, "-m", "pip", "install", "--upgrade", "--target", dest.toString(), pkg);
    }

    /** {@code [tar, -xzf, <archive>, -C, <dest>]}. Pure. */
    public static List<String> tarExtractArgv(Path archive, Path dest) {
        return List.of("tar", "-xzf", archive.toString(), "-C", dest.toString());
    }

    /** Like {@link #tarExtractArgv} but with {@code -xf} (auto-detect compression) — for {@code .tar.xz}
     *  archives (bsdtar/GNU tar both sniff xz). */
    public static List<String> tarExtractArgvAuto(Path archive, Path dest) {
        return List.of("tar", "-xf", archive.toString(), "-C", dest.toString());
    }

    /**
     * The hosts an in-app install may start a download from: the catalog's own API/archive endpoints plus the
     * asset hosts their release metadata points at. An asset URL is <em>picked out of fetched JSON</em> by a
     * pattern (a release body is free text), so without this a response could steer the download — of an
     * archive that is then extracted and executed — to any HTTPS host.
     */
    private static final java.util.Set<String> DOWNLOAD_HOSTS = java.util.Set.of(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "open-vsx.org",
            "openvsxorg.blob.core.windows.net",
            "download.eclipse.org",
            "repo.eclipse.org",
            // Maven Central: the three jars the lemminx-maven bundle leaves out (LEMMINX_MAVEN_EXTRA_JARS).
            "repo1.maven.org",
            "api.releases.hashicorp.com",
            "releases.hashicorp.com",
            // JetBrains' Kotlin LSP (a pinned URL with a pinned hash), and the CDN it redirects to.
            "download.jetbrains.com",
            "download-cdn.jetbrains.com");

    /** Whether {@code url} is an {@code https} URL on one of the {@link #DOWNLOAD_HOSTS} (default port, no
     *  credentials). Pure — unit-tested. */
    public static boolean isTrustedDownloadUrl(@Nullable String url) {
        if (url == null) {
            return false;
        }
        try {
            java.net.URI uri = new java.net.URI(url.strip());
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && DOWNLOAD_HOSTS.contains(uri.getHost().toLowerCase(java.util.Locale.ROOT));
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /** The first substring of {@code text} matching {@code regex}, or {@code null}. Pure. */
    public static @Nullable String firstMatch(String text, @Nullable String regex) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Maps a buffer's language id to its installable {@link Lang}, or empty if none. Pure. */
    public static Optional<Lang> forBufferLanguage(String languageId) {
        if (languageId == null) {
            return Optional.empty();
        }
        return switch (languageId) {
            case "java" -> Optional.of(Lang.JAVA);
            case "python" -> Optional.of(Lang.PYTHON);
            case "javascript", "javascriptreact", "typescript", "typescriptreact" -> Optional.of(Lang.JAVASCRIPT);
            case "mermaid", "mmd" -> Optional.of(Lang.MERMAID);
            default -> Optional.empty();
        };
    }

    /** The ordered steps (LSP then DAP) that install a language's support. Pure. */
    public static List<Step> steps(Lang lang) {
        return switch (lang) {
            case JAVA -> List.of(jdtls(), javaDebug());
            case PYTHON -> List.of(pyright(), debugpy());
            case JAVASCRIPT -> List.of(typescriptLs(), jsDebug());
            case MERMAID -> List.of(mmdc(), puppeteerChrome());
        };
    }

    /**
     * LSP servers (beyond the Java/Python/JS bundles above) that have an in-app installer, in display order.
     * These are LSP-only (no DAP); the install is keyed by the {@code LspServerRegistry} server id.
     */
    public static List<String> installableServerIds() {
        return List.of(
                // npm-installable (need only Node). json/html/css all ship in one package.
                "json",
                "html",
                "css",
                "bash",
                "yaml",
                "dockerfile",
                "toml",
                "astro",
                // installed via the language's own toolchain (go/gem/dotnet/rustup/composer must be present).
                "go",
                "sql",
                "ruby",
                "csharp",
                "rust",
                "php",
                // per-OS/arch binary archives (download + extract; needs only network + tar/unzip).
                "clangd",
                "lua",
                // a pinned native binary per OS/arch, its SHA-256 checked: lemminx, from vscode-xml's release.
                "xml",
                "terraform",
                "typst",
                // JVM LSP zip-with-dependencies (needs java): the Maven-aware pom.xml server.
                "maven-pom",
                // a pinned JVM fat jar (needs java 17+): the Groovy server.
                "groovy",
                // Maven artifacts fetched by a pinned coursier (needs java 17+ to run): Metals, for Scala.
                "scala",
                // a pinned server distribution with its own Java runtime (needs tar): JetBrains' Kotlin LSP.
                "kotlin",
                // a pinned native binary per OS/arch, its SHA-256 checked: clojure-lsp.
                "clojure");
    }

    /** The install steps for an LSP-only server id (empty if it has no installer). Pure. */
    public static java.util.Optional<List<Step>> serverInstall(String serverId) {
        if (serverId == null) {
            return java.util.Optional.empty();
        }
        return switch (serverId) {
            // --- npm (Node) ---
            // vscode-langservers-extracted ships the JSON, HTML, and CSS servers in one npm package.
            case "json", "html", "css" -> java.util.Optional.of(List.of(npmStep("vscode-langservers-extracted")));
            case "bash" -> java.util.Optional.of(List.of(npmStep("bash-language-server")));
            case "yaml" -> java.util.Optional.of(List.of(npmStep("yaml-language-server")));
            case "dockerfile" -> java.util.Optional.of(List.of(npmStep("dockerfile-language-server-nodejs")));
            case "toml" -> java.util.Optional.of(List.of(npmStep("@taplo/cli")));
            case "astro" -> java.util.Optional.of(List.of(npmStep("@astrojs/language-server", TYPESCRIPT_PACKAGE)));
            // --- language toolchains (install only if the toolchain is already on PATH) ---
            case "go" ->
                java.util.Optional.of(List.of(
                        toolStep("gopls", Prereq.GO, List.of("go", "install", "golang.org/x/tools/gopls@latest"))));
            case "sql" ->
                java.util.Optional.of(List.of(
                        toolStep("sqls", Prereq.GO, List.of("go", "install", "github.com/sqls-server/sqls@latest"))));
            case "ruby" ->
                java.util.Optional.of(
                        List.of(toolStep("ruby-lsp", Prereq.RUBY, List.of("gem", "install", "ruby-lsp"))));
            case "csharp" ->
                java.util.Optional.of(List.of(toolStep(
                        "csharp-ls", Prereq.DOTNET, List.of("dotnet", "tool", "install", "--global", "csharp-ls"))));
            case "rust" ->
                java.util.Optional.of(List.of(toolStep(
                        "rust-analyzer", Prereq.RUSTUP, List.of("rustup", "component", "add", "rust-analyzer"))));
            case "php" ->
                java.util.Optional.of(List.of(toolStep(
                        "phpactor", Prereq.COMPOSER, List.of("composer", "global", "require", "phpactor/phpactor"))));
            // --- per-OS/arch binary archives (download + extract into the config dir) ---
            case "clangd", "lua", "terraform", "typst" -> java.util.Optional.of(List.of(archiveStep(serverId)));
            // --- lemminx (XML): vscode-xml's native build, pinned per OS/arch with its SHA-256 (review A2) ---
            case "xml" ->
                java.util.Optional.of(List.of(new Step(
                        LEMMINX_STEP,
                        Kind.PINNED_TOOL,
                        Set.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        LEMMINX_DIR,
                        null)));
            // --- JetBrains' Kotlin LSP: a pinned per-platform distribution, streamed to disk (needs tar) ---
            case "kotlin" ->
                java.util.Optional.of(List.of(new Step(
                        KOTLIN_LSP_STEP,
                        Kind.PINNED_SERVER,
                        Set.of(Prereq.TAR),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        "plugins/lsp/kotlin",
                        null)));
            // --- JVM lemminx + lemminx-maven (one cross-platform zip; needs java on PATH) ---
            case "maven-pom" ->
                java.util.Optional.of(List.of(new Step(
                        LEMMINX_MAVEN_STEP,
                        Kind.JVM_LSP_ZIP,
                        Set.of(Prereq.JAVA),
                        null,
                        null,
                        null,
                        null,
                        LEMMINX_MAVEN_ZIP_URL,
                        false,
                        LEMMINX_MAVEN_DIR,
                        null)));
            // --- clojure-lsp: a pinned GraalVM native binary per OS/arch (no JVM needed) ---
            case "clojure" ->
                java.util.Optional.of(List.of(new Step(
                        CLOJURE_LSP_ID,
                        Kind.PINNED_TOOL,
                        Set.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        false,
                        "plugins/lsp/clojure",
                        null)));
            // --- gvy's gls: one pinned cross-platform jar (needs java 17+) ---
            case "groovy" ->
                java.util.Optional.of(List.of(new Step(
                        "gls",
                        Kind.JVM_LSP_JAR,
                        Set.of(Prereq.JAVA),
                        null,
                        null,
                        null,
                        null,
                        GLS_JAR.url(),
                        false,
                        "plugins/lsp/groovy",
                        null)));
            // --- Metals: coursier resolves its classpath (needs java 17+ to run, not to install) ---
            case "scala" ->
                java.util.Optional.of(List.of(new Step(
                        "metals",
                        Kind.COURSIER_LSP,
                        Set.of(Prereq.JAVA),
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        "plugins/lsp/scala",
                        null)));
            default -> java.util.Optional.empty();
        };
    }

    /** A language server shipped as one jar: where it is, its SHA-256 (lower-case hex), and its file name. */
    public record JvmLspJar(String url, String sha256, String fileName) {}

    /** The gvy release Editora installs for Groovy. Bump with {@link #GLS_JAR}'s hash. */
    public static final String GLS_VERSION = "0.4.8";

    /**
     * gvy's language server, {@code gls-<version>.jar} (Apache-2.0, github.com/albertocavalcante/gvy). The hash
     * is the release asset's SHA-256 as GitHub publishes it for the tag, checked against a downloaded copy.
     */
    public static final JvmLspJar GLS_JAR = new JvmLspJar(
            "https://github.com/albertocavalcante/gvy/releases/download/v" + GLS_VERSION + "/gls-" + GLS_VERSION
                    + ".jar",
            "552558bc588968c3127aa25114a86bb9137fa740572b39e7af44ff732a2802f2",
            "gls-" + GLS_VERSION + ".jar");

    /**
     * A language server resolved by coursier: the artifact {@code cs fetch} is given, the class that starts it,
     * and the JVM options its launcher would pass (Metals' own recommendations: a larger thread stack for the
     * Scala compiler, G1 with string de-duplication for its symbol tables).
     */
    public record CoursierLsp(String artifact, String mainClass, List<String> jvmOptions) {}

    /** The Metals release Editora installs (the 1.x stable line; 2.0 was a release candidate when pinned). */
    public static final String METALS_VERSION = "1.6.9";

    /** Metals, the Scala language server (Apache-2.0, scalameta.org/metals), built for Scala 2.13. */
    public static final CoursierLsp METALS = new CoursierLsp(
            "org.scalameta:metals_2.13:" + METALS_VERSION,
            "scala.meta.metals.Main",
            List.of("-Xss4m", "-Xms100m", "-XX:+UseG1GC", "-XX:+UseStringDeduplication"));

    /** The coursier-resolved server a {@link Kind#COURSIER_LSP} step installs, by step id, or empty. Pure. */
    public static java.util.Optional<CoursierLsp> coursierLsp(String stepId) {
        return "metals".equals(stepId) ? java.util.Optional.of(METALS) : java.util.Optional.empty();
    }

    /**
     * The command that runs a coursier-installed server: {@code java <options> -cp "<lib>/*" <mainClass>} — the
     * same {@code java -cp} shape as the pom.xml server, so the Settings JDK replaces the bare {@code java}
     * ({@code JvmServerCommand}). Pure.
     */
    public static String coursierLspCommand(java.nio.file.Path lib, CoursierLsp server) {
        StringBuilder out = new StringBuilder("java");
        for (String option : server.jvmOptions()) {
            out.append(' ').append(option);
        }
        return out.append(" -cp \"")
                .append(lib)
                .append(java.io.File.separator)
                .append("*\" ")
                .append(server.mainClass())
                .toString();
    }

    /** The coursier release whose native launcher Editora downloads to resolve Metals. */
    public static final String COURSIER_VERSION = "2.1.26";

    private static final String COURSIER_BASE =
            "https://github.com/coursier/coursier/releases/download/v" + COURSIER_VERSION + "/cs-";

    /**
     * coursier's native launcher ({@code cs}, Apache-2.0, github.com/coursier/coursier), pinned per platform. The
     * hashes are the release assets' SHA-256 as GitHub publishes them for the tag, checked against downloaded
     * copies. It is a GraalVM native image, so resolving needs no Java; only the server it installs does.
     * Upstream publishes no Windows-on-ARM build.
     */
    public static final PinnedTool COURSIER_TOOL = new PinnedTool(
            "coursier",
            COURSIER_VERSION,
            java.util.Map.of(
                    Platform.LINUX_X64,
                    new PinnedAsset(
                            COURSIER_BASE + "x86_64-pc-linux.gz",
                            "348e37bc2a8c706640e6b032c4551a0e055a7f1537485b4a91540e9d3598ec6d",
                            Packing.GZ),
                    Platform.LINUX_ARM,
                    new PinnedAsset(
                            COURSIER_BASE + "aarch64-pc-linux.gz",
                            "6c358ac223332c8981eb86417d3faf9338a5ef6c95d1879a58162b24c510025c",
                            Packing.GZ),
                    Platform.MAC_X64,
                    new PinnedAsset(
                            COURSIER_BASE + "x86_64-apple-darwin.gz",
                            "c8045f36fadec075c8647b979d1028435935b0ecd43efa1387cd286c3926a421",
                            Packing.GZ),
                    Platform.MAC_ARM,
                    new PinnedAsset(
                            COURSIER_BASE + "aarch64-apple-darwin.gz",
                            "8e4d36aa2565276f262af073f0999399e4c2f920f2fe436bb0ae67665eb505e9",
                            Packing.GZ),
                    Platform.WIN_X64,
                    new PinnedAsset(
                            COURSIER_BASE + "x86_64-pc-win32.zip",
                            "d23f4ba6afa7f092135b485303b0a020358fbb314abf9c6dc2576df3e2abd941",
                            Packing.ZIP)),
            "cs");

    /** The {@code cs fetch} argv that prints {@code artifact}'s resolved classpath, one jar per line. Pure. */
    public static List<String> coursierFetchArgv(String cs, String artifact) {
        return List.of(cs, "fetch", artifact);
    }

    /** The pinned jar a {@link Kind#JVM_LSP_JAR} step installs, by step id, or empty. Pure. */
    public static java.util.Optional<JvmLspJar> jvmLspJar(String stepId) {
        return "gls".equals(stepId) ? java.util.Optional.of(GLS_JAR) : java.util.Optional.empty();
    }

    /** The command that runs an installed server jar: {@code java -jar "<jar>"} (quoted when needed). Pure. */
    public static String jvmJarCommand(java.nio.file.Path jar) {
        return "java -jar " + quoteCommandPath(jar.toString());
    }

    /**
     * The per-OS binary-archive recipe for a server id (clangd/lua/terraform/typst), or empty.
     * {@code apiUrl} returns JSON whose asset/download URLs we match by the per-{@link Platform} substring;
     * the extracted tree is searched for {@code binaryName} (a prefix when {@code binaryPrefix}) and the
     * server's command is set to that path + {@code commandSuffix}. Pure.
     */
    public static java.util.Optional<ArchiveSpec> archiveSpec(String serverId) {
        if (serverId == null) {
            return java.util.Optional.empty();
        }
        return switch (serverId) {
            case "clangd" ->
                java.util.Optional.of(new ArchiveSpec(
                        "https://api.github.com/repos/clangd/clangd/releases/latest",
                        java.util.Map.of(
                                Platform.MAC_ARM, "clangd-mac",
                                Platform.MAC_X64, "clangd-mac",
                                Platform.LINUX_X64, "clangd-linux",
                                Platform.LINUX_ARM, "clangd-linux",
                                Platform.WIN_X64, "clangd-windows"),
                        "clangd",
                        false,
                        ""));
            case "lua" ->
                java.util.Optional.of(new ArchiveSpec(
                        "https://api.github.com/repos/LuaLS/lua-language-server/releases/latest",
                        java.util.Map.of(
                                Platform.MAC_ARM, "darwin-arm64",
                                Platform.MAC_X64, "darwin-x64",
                                Platform.LINUX_X64, "linux-x64",
                                Platform.LINUX_ARM, "linux-arm64",
                                Platform.WIN_X64, "win32-x64"),
                        "lua-language-server",
                        false,
                        ""));
            case "terraform" ->
                java.util.Optional.of(new ArchiveSpec(
                        "https://api.releases.hashicorp.com/v1/releases/terraform-ls/latest",
                        java.util.Map.of(
                                Platform.MAC_ARM, "darwin_arm64",
                                Platform.MAC_X64, "darwin_amd64",
                                Platform.LINUX_X64, "linux_amd64",
                                Platform.LINUX_ARM, "linux_arm64",
                                Platform.WIN_X64, "windows_amd64"),
                        "terraform-ls",
                        false,
                        " serve"));
            case "typst" ->
                java.util.Optional.of(new ArchiveSpec(
                        "https://api.github.com/repos/Myriad-Dreamin/tinymist/releases/latest",
                        // The bare "tinymist-<triple>" binary — distinct from tinymist-docs-tool/-viewer/typlite.
                        java.util.Map.of(
                                Platform.MAC_ARM, "tinymist-aarch64-apple-darwin",
                                Platform.MAC_X64, "tinymist-x86_64-apple-darwin",
                                Platform.LINUX_X64, "tinymist-x86_64-unknown-linux-gnu",
                                Platform.LINUX_ARM, "tinymist-aarch64-unknown-linux-gnu",
                                Platform.WIN_X64, "tinymist-x86_64-pc-windows-msvc"),
                        "tinymist",
                        false,
                        " lsp"));
            case "typst-cli" ->
                java.util.Optional.of(new ArchiveSpec(
                        "https://api.github.com/repos/typst/typst/releases/latest",
                        // typst's own release binaries (.tar.xz on unix, .zip on Windows); musl = static/portable.
                        java.util.Map.of(
                                Platform.MAC_ARM, "typst-aarch64-apple-darwin",
                                Platform.MAC_X64, "typst-x86_64-apple-darwin",
                                Platform.LINUX_X64, "typst-x86_64-unknown-linux-musl",
                                Platform.LINUX_ARM, "typst-aarch64-unknown-linux-musl",
                                Platform.WIN_X64, "typst-x86_64-pc-windows-msvc"),
                        "typst",
                        false,
                        ""));
            default -> java.util.Optional.empty();
        };
    }

    // --- pinned server distributions (JetBrains' Kotlin LSP) --------------------------------------------

    /** The step id of the Kotlin LSP install (what the status line names while it runs). */
    public static final String KOTLIN_LSP_STEP = "kotlin-lsp";

    /**
     * The JetBrains Kotlin LSP release Editora installs (github.com/Kotlin/kotlin-lsp, alpha). <b>Pinned</b>, with
     * a hash per platform: JetBrains publishes no GitHub assets, only these downloads and a {@code .sha256} beside
     * each, which the hashes below were checked against (and the Linux x64 one against a downloaded copy). Bump
     * the version and every hash together.
     */
    public static final String KOTLIN_LSP_VERSION = "263.7084.0";

    private static final String KOTLIN_LSP_BASE = "https://download.jetbrains.com/language-server/kotlin-server/"
            + KOTLIN_LSP_VERSION + "/kotlin-server-" + KOTLIN_LSP_VERSION;

    /**
     * A language server installed from a pinned upstream distribution.
     *
     * @param launcher the launcher's file name without a Windows {@code .exe}, found anywhere in the unpacked
     *     tree (the Windows archive has no top folder, the others do)
     * @param arguments what follows the launcher on the server's command line
     * @param downloadMegabytes the download's size, said before it starts
     * @param installedMegabytes the size once unpacked, said with it
     */
    public record PinnedServer(
            String id,
            String version,
            java.util.Map<Platform, PinnedAsset> assets,
            String launcher,
            String arguments,
            int downloadMegabytes,
            int installedMegabytes) {

        /** This platform's asset, or null when upstream publishes none for it. */
        public @Nullable PinnedAsset assetFor(Platform platform) {
            return assets.get(platform);
        }

        /** The most one download may be: its size and a margin, so a wrong body cannot fill the disk. */
        public long maxBytes() {
            return (downloadMegabytes + 128L) * 1024 * 1024;
        }
    }

    /**
     * The pinned distribution a {@link Kind#PINNED_SERVER} step installs, by step id, or empty. JetBrains' Kotlin
     * LSP bundles its own Java runtime (JetBrains Runtime 25), so it needs no JDK. The macOS {@code .sit} is a
     * zip; every archive is unpacked by the system {@code tar} (bsdtar reads zips on macOS and Windows, and keeps
     * the launcher's and the runtime's execute bits). Windows on ARM runs the x64 build, as for every tool here.
     */
    public static Optional<PinnedServer> pinnedServer(String stepId) {
        if (!KOTLIN_LSP_STEP.equals(stepId)) {
            return Optional.empty();
        }
        return Optional.of(new PinnedServer(
                KOTLIN_LSP_STEP,
                KOTLIN_LSP_VERSION,
                java.util.Map.of(
                        Platform.LINUX_X64,
                        new PinnedAsset(
                                KOTLIN_LSP_BASE + ".tar.gz",
                                "ae0c4b01e17ab73a4a5227a78ea99e6dcbe3e499600315b22759a4e4cddd11df",
                                Packing.TAR_GZ),
                        Platform.LINUX_ARM,
                        new PinnedAsset(
                                KOTLIN_LSP_BASE + "-aarch64.tar.gz",
                                "450ab7c1d80e48106aa429b48cb4226d82ca12a8a2400447ae02386794780cac",
                                Packing.TAR_GZ),
                        Platform.MAC_X64,
                        new PinnedAsset(
                                KOTLIN_LSP_BASE + ".sit",
                                "b29c393a5e541153d03d626e7946268437e358a667a5f914a126dbb4007664b1",
                                Packing.ZIP),
                        Platform.MAC_ARM,
                        new PinnedAsset(
                                KOTLIN_LSP_BASE + "-aarch64.sit",
                                "b403bf8a54bd458e3ba6731cf8abb3340be08b439c03576dfc02b13faf3694b8",
                                Packing.ZIP),
                        Platform.WIN_X64,
                        new PinnedAsset(
                                KOTLIN_LSP_BASE + ".win.zip",
                                "be3bc4ef430cde315e6a83900ffb5d9cc454a36aabe9dd3ba7d4141b2d628164",
                                Packing.ZIP)),
                "intellij-server",
                " --stdio",
                370,
                1200));
    }

    /**
     * The download a server's install makes, as {@code [download MB, installed MB]}, when it is big enough to ask
     * about first; null otherwise. Pure.
     */
    public static int @Nullable [] largeDownload(String serverId) {
        return serverInstall(serverId).orElse(List.of()).stream()
                .filter(s -> s.kind() == Kind.PINNED_SERVER)
                .map(s -> pinnedServer(s.id()).orElse(null))
                .filter(java.util.Objects::nonNull)
                .map(p -> new int[] {p.downloadMegabytes(), p.installedMegabytes()})
                .findFirst()
                .orElse(null);
    }

    // --- pinned command-line tools (ShellCheck, shfmt) -------------------------------------------------

    /** The ShellCheck release Editora installs. Bump with every hash in {@link #pinnedTool}. */
    public static final String SHELLCHECK_VERSION = "0.11.0";

    /** The shfmt release Editora installs. Bump with every hash in {@link #pinnedTool}. */
    public static final String SHFMT_VERSION = "3.14.1";

    /** The clojure-lsp release Editora installs. Bump with every hash in {@link #pinnedTool}. */
    public static final String CLOJURE_LSP_VERSION = "2026.10.15-11.42.38";

    /** The install step (and {@link #pinnedTool} recipe) id of the Clojure server, installed for server "clojure". */
    public static final String CLOJURE_LSP_ID = "clojure-lsp";

    private static final String CLOJURE_LSP_BASE = "https://github.com/clojure-lsp/clojure-lsp/releases/download/"
            + CLOJURE_LSP_VERSION + "/clojure-lsp-native-";

    /**
     * The vscode-xml release whose native lemminx Editora installs for XML (github.com/redhat-developer/vscode-xml,
     * EPL-2.0). <b>Pinned</b>, with a hash per platform, instead of following {@code releases/latest}: a new
     * lemminx is adopted deliberately, and a replaced or tampered upload is refused (review A2/D10). Bump it with
     * every hash in {@link #pinnedTool} and {@link #LEMMINX_SERVER_VERSION}.
     */
    public static final String LEMMINX_VERSION = "0.29.3";

    /** The lemminx (the server itself) that vscode-xml {@link #LEMMINX_VERSION} ships. */
    public static final String LEMMINX_SERVER_VERSION = "0.31.2";

    /** The size of {@link #LEMMINX_VERSION}'s download in MB, rounded up (every platform's zip is 16–17 MB). */
    public static final int LEMMINX_DOWNLOAD_MB = 17;

    /** The install step (and {@link #pinnedTool} recipe) id of the XML server, installed for server "xml". */
    public static final String LEMMINX_STEP = "lemminx";

    /** Where Editora installs lemminx, relative to the config dir. */
    public static final String LEMMINX_DIR = "plugins/lsp/xml";

    /** Where the pom.xml server's bundle (Java lemminx + lemminx-maven) is extracted, under the config dir. */
    public static final String LEMMINX_MAVEN_DIR = "plugins/lsp/maven";

    private static final String LEMMINX_BASE =
            "https://github.com/redhat-developer/vscode-xml/releases/download/" + LEMMINX_VERSION + "/lemminx-";

    /** The tools {@link #pinnedTool} knows, in display order. */
    public static final List<String> PINNED_TOOL_IDS = List.of("shellcheck", "shfmt");

    /** How a pinned asset is packed. */
    public enum Packing {
        /** The file is the program itself (shfmt). */
        RAW,
        /** A {@code .tar.gz} holding the program somewhere inside (extracted with the system tar). */
        TAR_GZ,
        /** A {@code .zip} holding the program somewhere inside. */
        ZIP,
        /** The program itself, gzip-compressed (coursier's {@code cs-<platform>.gz}). */
        GZ
    }

    /**
     * One platform's download: where it is, its SHA-256 (lower-case hex), and how it is packed. An archive may
     * also name the {@code member} that is the program (when its name differs from the tool's) and that
     * program's own SHA-256, checked once it is unpacked — vscode-xml publishes both.
     */
    public record PinnedAsset(
            String url,
            String sha256,
            Packing packing,
            @Nullable String member,
            @Nullable String memberSha256) {

        /** An asset whose program is the tool's {@code binaryName} and is checked only as a download. */
        public PinnedAsset(String url, String sha256, Packing packing) {
            this(url, sha256, packing, null, null);
        }
    }

    /**
     * A tool installed from a pinned upstream release.
     *
     * @param binaryName the program's file name without a Windows {@code .exe}
     */
    public record PinnedTool(
            String id, String version, java.util.Map<Platform, PinnedAsset> assets, String binaryName) {

        /** This platform's asset, or null when upstream publishes none for it. */
        public @Nullable PinnedAsset assetFor(Platform platform) {
            return assets.get(platform);
        }
    }

    private static final String SHELLCHECK_BASE = "https://github.com/koalaman/shellcheck/releases/download/v"
            + SHELLCHECK_VERSION + "/shellcheck-v" + SHELLCHECK_VERSION;

    private static final String SHFMT_BASE =
            "https://github.com/mvdan/sh/releases/download/v" + SHFMT_VERSION + "/shfmt_v" + SHFMT_VERSION;

    /**
     * The pinned release of a command-line tool, or empty. The hashes are the release assets' SHA-256 as
     * published by GitHub for these tags (and checked against downloaded copies); an asset that does not match
     * is refused, so a replaced or tampered upload is never run. ShellCheck and shfmt are what
     * bash-language-server lints and formats with — without them a shell script gets no diagnostics and Format
     * Document does nothing. Upstream publishes no Windows-on-ARM or macOS-universal builds beyond these.
     */
    public static Optional<PinnedTool> pinnedTool(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return switch (id) {
            case "shellcheck" ->
                Optional.of(new PinnedTool(
                        "shellcheck",
                        SHELLCHECK_VERSION,
                        java.util.Map.of(
                                Platform.LINUX_X64,
                                new PinnedAsset(
                                        SHELLCHECK_BASE + ".linux.x86_64.tar.gz",
                                        "b7af85e41cc99489dcc21d66c6d5f3685138f06d34651e6d34b42ec6d54fe6f6",
                                        Packing.TAR_GZ),
                                Platform.LINUX_ARM,
                                new PinnedAsset(
                                        SHELLCHECK_BASE + ".linux.aarch64.tar.gz",
                                        "68a8133197a50beb8803f8d42f9908d1af1c5540d4bb05fdfca8c1fa47decefc",
                                        Packing.TAR_GZ),
                                Platform.MAC_X64,
                                new PinnedAsset(
                                        SHELLCHECK_BASE + ".darwin.x86_64.tar.gz",
                                        "c2c15e08df0e8fbc374c335b230a7ee958c313fa5714817a59aa59f1aa594f51",
                                        Packing.TAR_GZ),
                                Platform.MAC_ARM,
                                new PinnedAsset(
                                        SHELLCHECK_BASE + ".darwin.aarch64.tar.gz",
                                        "339b930feb1ea764467013cc1f72d09cd6b869ebf1013296ba9055ab2ffbd26f",
                                        Packing.TAR_GZ),
                                Platform.WIN_X64,
                                new PinnedAsset(
                                        SHELLCHECK_BASE + ".zip",
                                        "8a4e35ab0b331c85d73567b12f2a444df187f483e5079ceffa6bda1faa2e740e",
                                        Packing.ZIP)),
                        "shellcheck"));
            case "shfmt" ->
                Optional.of(new PinnedTool(
                        "shfmt",
                        SHFMT_VERSION,
                        java.util.Map.of(
                                Platform.LINUX_X64,
                                new PinnedAsset(
                                        SHFMT_BASE + "_linux_amd64",
                                        "76e77641faa025814b77f153b29796b8e6fa2fca03e0c76a691608b86c7ea7bf",
                                        Packing.RAW),
                                Platform.LINUX_ARM,
                                new PinnedAsset(
                                        SHFMT_BASE + "_linux_arm64",
                                        "5f2db09dae91fca848f7adbdd014632e921a383863a2ad7e0450ad3aba0c6489",
                                        Packing.RAW),
                                Platform.MAC_X64,
                                new PinnedAsset(
                                        SHFMT_BASE + "_darwin_amd64",
                                        "d33eee0da0f92835b3562e9767a05cee7e4eaeef47daa03bfd09da17b4b590a6",
                                        Packing.RAW),
                                Platform.MAC_ARM,
                                new PinnedAsset(
                                        SHFMT_BASE + "_darwin_arm64",
                                        "b7c872db63553ccffc7253aba3ed7d4885a27d83f1ba567b1138c6315a5847e5",
                                        Packing.RAW),
                                Platform.WIN_X64,
                                new PinnedAsset(
                                        SHFMT_BASE + "_windows_amd64.exe",
                                        "13629ce28442ca80b6b5a819f7574ab39e1c28c6e26734ca816c9714e04851df",
                                        Packing.RAW)),
                        "shfmt"));
            // clojure-lsp's GraalVM native builds (MIT, github.com/clojure-lsp/clojure-lsp): the hashes are the
            // assets' SHA-256 as GitHub publishes them for the tag — the macOS arm64 build has no .sha256 file
            // in this release — each checked against a downloaded copy. No Windows-on-ARM build exists; the x64
            // one runs under emulation there. Installed as a language server (serverInstall "clojure"), not as
            // one of PINNED_TOOL_IDS.
            case CLOJURE_LSP_ID ->
                Optional.of(new PinnedTool(
                        CLOJURE_LSP_ID,
                        CLOJURE_LSP_VERSION,
                        java.util.Map.of(
                                Platform.LINUX_X64,
                                new PinnedAsset(
                                        CLOJURE_LSP_BASE + "linux-amd64.zip",
                                        "d68acf630465cec9d3685fd958f914d9228d90a07222edd301f1a4740b8e8b76",
                                        Packing.ZIP),
                                Platform.LINUX_ARM,
                                new PinnedAsset(
                                        CLOJURE_LSP_BASE + "linux-aarch64.zip",
                                        "232ac0cd191070a3b7b5532135ec9f97217b82324de7e97da0b1bf81353a3c94",
                                        Packing.ZIP),
                                Platform.MAC_X64,
                                new PinnedAsset(
                                        CLOJURE_LSP_BASE + "macos-amd64.zip",
                                        "468c261b48a7276c79cb92a82ddfce5474bc563ba6673df30cabf17a7f239ef3",
                                        Packing.ZIP),
                                Platform.MAC_ARM,
                                new PinnedAsset(
                                        CLOJURE_LSP_BASE + "macos-aarch64.zip",
                                        "84df57e51e27f4ecf20d0cd65aab3296e2ebf28dbaacca4fe216b6cf20485716",
                                        Packing.ZIP),
                                Platform.WIN_X64,
                                new PinnedAsset(
                                        CLOJURE_LSP_BASE + "windows-amd64.zip",
                                        "63bb6567a2280a563a8de22c3eb833a69159e054b6545a29d25ab588c1b749b1",
                                        Packing.ZIP)),
                        "clojure-lsp"));
            // lemminx's GraalVM native builds from the vscode-xml release: each zip's hash is its SHA-256 as GitHub
            // publishes it for the tag, and each program's is the release's own lemminx-<platform>.sha256, so
            // what is unpacked is checked too. The program is renamed to plain "lemminx" (the zips name it per
            // platform). No Windows-on-ARM build; the x64 one runs under emulation there.
            case LEMMINX_STEP ->
                Optional.of(new PinnedTool(
                        LEMMINX_STEP,
                        LEMMINX_VERSION,
                        java.util.Map.of(
                                Platform.LINUX_X64,
                                new PinnedAsset(
                                        LEMMINX_BASE + "linux-x86_64.zip",
                                        "1acc44e24201c1d2f5ccb4e43e7426ed0df6909207a81ff199810e2808104d89",
                                        Packing.ZIP,
                                        "lemminx-linux-x86_64",
                                        "84cbbe3c31eb158799327d5f68a0dc876e81c431da596747b92eecffe956b3f2"),
                                Platform.LINUX_ARM,
                                new PinnedAsset(
                                        LEMMINX_BASE + "linux-aarch_64.zip",
                                        "0b88a49e83c2611282672f5123ce33abf1371c8e3c55cad6ee45ea00626ffa1b",
                                        Packing.ZIP,
                                        "lemminx-linux-aarch_64",
                                        "e706d10fbda4fa354952edb604800d067518401521d4d1e491b8bee7cf4cbcdb"),
                                Platform.MAC_X64,
                                new PinnedAsset(
                                        LEMMINX_BASE + "osx-x86_64.zip",
                                        "9e56123194b5c1fdc7d87f1bd21c83cc996ba7be913def3a2836a13305bcd273",
                                        Packing.ZIP,
                                        "lemminx-osx-x86_64",
                                        "e13b4c135f56f906a7450bc9f64f49718bf743c5c4df8003a5610e1fb8772a55"),
                                Platform.MAC_ARM,
                                new PinnedAsset(
                                        LEMMINX_BASE + "osx-aarch_64.zip",
                                        "185db5630ce85be43ea0fab034e7841b1327c2793db05ab481e029cf493d86ce",
                                        Packing.ZIP,
                                        "lemminx-osx-aarch_64",
                                        "c5e9e2aee768eb69d041e9541cd0568a432ebd51c7fe9c1d7b7a1130b759475a"),
                                Platform.WIN_X64,
                                new PinnedAsset(
                                        LEMMINX_BASE + "win32.zip",
                                        "7eaefaac68253b0ec8e0ad1f1c0f2d0755423d4e99e52497428b52f80df28eb7",
                                        Packing.ZIP,
                                        "lemminx-win32.exe",
                                        "25f571c0f07d0ad76be60a86c1b864d46cf7bf94773dc21e5d5da646478aac79")),
                        "lemminx"));
            default -> Optional.empty();
        };
    }

    /** The install step for a pinned tool (empty for an unknown id). Pure. */
    public static Optional<List<Step>> toolInstall(String id) {
        return pinnedTool(id).map(tool -> {
            PinnedAsset asset = tool.assetFor(currentPlatform());
            Set<Prereq> prereqs = asset != null && asset.packing() == Packing.TAR_GZ ? Set.of(Prereq.TAR) : Set.of();
            return List.of(new Step(
                    tool.id(),
                    Kind.PINNED_TOOL,
                    prereqs,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    toolDir(tool.id()),
                    null));
        });
    }

    /** Where a pinned tool is installed, relative to the config dir. */
    static String toolDir(String id) {
        return "plugins/tools/" + id;
    }

    /** The program file name of tool {@code id} on {@code platform} ({@code shfmt.exe} on Windows). Pure. */
    static String toolFileName(String id, Platform platform) {
        String name = pinnedTool(id).map(PinnedTool::binaryName).orElse(id);
        return platform == Platform.WIN_X64 ? name + ".exe" : name;
    }

    /** Where Editora's own copy of tool {@code id} is (whether or not it has been installed). Pure. */
    public static Path installedToolPath(Path configDir, String id) {
        return configDir.resolve(toolDir(id)).resolve(toolFileName(id, currentPlatform()));
    }

    /** Editora's own copy of tool {@code id} when it is installed, else null ({@code configDir} may be null). */
    public static @Nullable String installedToolIfPresent(@Nullable Path configDir, String id) {
        if (configDir == null) {
            return null;
        }
        Path p = installedToolPath(configDir, id);
        return java.nio.file.Files.isRegularFile(p) ? p.toString() : null;
    }

    /** Lower-case hex SHA-256 of {@code data}. Pure. */
    public static String sha256Hex(byte[] data) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    /**
     * The language servers install step {@code stepId} provides: a language bundle's server (jdtls → {@code java},
     * Pyright → {@code python}, typescript-language-server → {@code typescript}) or every installable server whose
     * install includes the step (one npm package serves JSON, HTML and CSS). Empty for a debugger or a tool. Pure.
     */
    public static List<String> serversOfStep(@Nullable String stepId) {
        if (stepId == null) {
            return List.of();
        }
        switch (stepId) {
            case "jdtls" -> {
                return List.of("java");
            }
            case "pyright" -> {
                return List.of("python");
            }
            case "typescript-language-server" -> {
                return List.of("typescript");
            }
            default -> {
                List<String> servers = new ArrayList<>();
                for (String server : installableServerIds()) {
                    if (serverInstall(server).orElse(List.of()).stream().anyMatch(s -> s.id().equals(stepId))) {
                        servers.add(server);
                    }
                }
                return List.copyOf(servers);
            }
        }
    }

    /** A per-OS binary-archive recipe. */
    public record ArchiveSpec(
            String apiUrl,
            java.util.Map<Platform, String> assetByPlatform,
            String binaryName,
            boolean binaryPrefix,
            String commandSuffix) {}

    /** The host {@link Platform} for an OS/arch (e.g. from {@code os.name}/{@code os.arch}). Pure. */
    public static Platform platformKey(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(java.util.Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64") || arch.equals("arm");
        if (os.contains("win")) {
            return Platform.WIN_X64; // Windows-on-ARM runs the x64 build under emulation
        }
        if (os.contains("mac") || os.contains("darwin") || os.contains("osx")) {
            return arm ? Platform.MAC_ARM : Platform.MAC_X64;
        }
        return arm ? Platform.LINUX_ARM : Platform.LINUX_X64;
    }

    /** This machine's {@link Platform}. */
    public static Platform currentPlatform() {
        return platformKey(System.getProperty("os.name"), System.getProperty("os.arch"));
    }

    /** A single {@code npm install -g <pkgs>} step (the step id = the primary package). Pure. */
    private static Step npmStep(String... packages) {
        return new Step(
                packages[0],
                Kind.NPM_GLOBAL,
                Set.of(Prereq.NPM),
                List.of(packages),
                null,
                null,
                null,
                null,
                false,
                "",
                null);
    }

    /** A step that runs {@code argv} via a language toolchain (argv carried in {@code npmPackages}). Pure. */
    private static Step toolStep(String id, Prereq prereq, List<String> argv) {
        return new Step(id, Kind.TOOL_COMMAND, Set.of(prereq), argv, null, null, null, null, false, "", null);
    }

    /** A per-OS binary-archive step; the recipe lives in {@link #archiveSpec}. Extracts to plugins/lsp/&lt;id&gt;.
     *  Declares the TAR prereq since some platforms ship a {@code .tar.gz} (a no-op where the asset is a zip). */
    private static Step archiveStep(String id) {
        return new Step(
                id,
                Kind.ARCHIVE,
                Set.of(Prereq.TAR),
                List.of(),
                null,
                null,
                null,
                null,
                false,
                "plugins/lsp/" + id,
                null);
    }

    /** The install step for the typst render CLI (the preview tool, distinct from the tinymist LSP server):
     *  a per-OS binary archive from typst's GitHub releases, extracted to {@code plugins/typst}. Pure. */
    public static List<Step> typstCliSteps() {
        return List.of(new Step(
                "typst-cli",
                Kind.ARCHIVE,
                Set.of(Prereq.TAR),
                List.of(),
                null,
                null,
                null,
                null,
                false,
                "plugins/typst",
                null));
    }

    // --- Step factories ------------------------------------------------------------------------

    private static final String VSIX_PATTERN = "https://[^\"\\s]+\\.vsix";
    private static final String JS_DEBUG_ASSET_PATTERN = "https://[^\"\\s]+/js-debug-dap-v[^\"\\s]+\\.tar\\.gz";

    private static Step jdtls() {
        return new Step(
                "jdtls",
                Kind.TARBALL,
                Set.of(Prereq.TAR),
                List.of(),
                null,
                null,
                null,
                JDTLS_TARBALL_URL,
                false,
                "plugins/lsp/java",
                "bin/jdtls");
    }

    private static Step javaDebug() {
        return new Step(
                "java-debug",
                Kind.VSIX,
                Set.of(),
                List.of(),
                null,
                openVsxLatestUrl("vscjava", "vscode-java-debug"),
                VSIX_PATTERN,
                null,
                true,
                "plugins/dap/java",
                null);
    }

    private static Step pyright() {
        return new Step(
                "pyright",
                Kind.NPM_GLOBAL,
                Set.of(Prereq.NPM),
                List.of("pyright"),
                null,
                null,
                null,
                null,
                false,
                "",
                null);
    }

    private static Step debugpy() {
        return new Step(
                "debugpy",
                Kind.PIP_TARGET,
                Set.of(Prereq.PYTHON),
                List.of(),
                "debugpy",
                null,
                null,
                null,
                false,
                "plugins/dap/python",
                null);
    }

    private static Step typescriptLs() {
        return new Step(
                "typescript-language-server",
                Kind.NPM_GLOBAL,
                Set.of(Prereq.NPM),
                List.of("typescript-language-server", TYPESCRIPT_PACKAGE),
                null,
                null,
                null,
                null,
                false,
                "",
                null);
    }

    private static Step jsDebug() {
        return new Step(
                "js-debug",
                Kind.TARBALL,
                Set.of(Prereq.TAR),
                List.of(),
                null,
                JS_DEBUG_RELEASES_API,
                JS_DEBUG_ASSET_PATTERN,
                null,
                false,
                "plugins/dap/javascript",
                "dapDebugServer.js");
    }

    private static Step mmdc() {
        return new Step(
                "mmdc",
                Kind.NPM_GLOBAL,
                Set.of(Prereq.NPM),
                List.of("@mermaid-js/mermaid-cli"),
                null,
                null,
                null,
                null,
                false,
                "",
                null);
    }

    /** The headless Chrome mmdc renders with — installed via mmdc's own Puppeteer so the version matches. */
    private static Step puppeteerChrome() {
        return new Step(
                "chrome-headless-shell",
                Kind.PUPPETEER_BROWSER,
                Set.of(Prereq.NPM),
                puppeteerInstallArgv(),
                null,
                null,
                null,
                null,
                false,
                "",
                null);
    }

    /** {@code [npx, -y, puppeteer, browsers, install, chrome-headless-shell]}. Pure. */
    public static List<String> puppeteerInstallArgv() {
        return List.of("npx", "-y", "puppeteer", "browsers", "install", "chrome-headless-shell");
    }
}
