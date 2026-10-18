package com.editora.install;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
         *  config dir, and set the server command to {@code java -cp "<dir>/*" <mainClass>}. Used for the
         *  Maven-aware pom.xml server (JVM lemminx + lemminx-maven), whose bundle is cross-platform. */
        JVM_LSP_ZIP,
        /** A command-line tool from a pinned release (ShellCheck, shfmt): the per-platform asset is downloaded,
         *  its SHA-256 checked against the hash in {@link #pinnedTool}, and the binary put at
         *  {@code <config>/plugins/tools/<id>/<binary>} — see {@link #installedToolPath}. */
        PINNED_TOOL
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
    public static final String JDTLS_VERSION = "1.62.0";

    /**
     * The TypeScript the language servers that need its {@code tsserver} are installed with. TypeScript 7 is
     * the native (Go) compiler and ships no {@code lib/tsserver.js}, so typescript-language-server (and astro-ls)
     * cannot start on it; since TS 7 became npm's {@code latest}, an unpinned {@code typescript} gave a server
     * that crashed on every file. Pinned to the last major with a tsserver.
     */
    public static final String TYPESCRIPT_PACKAGE = "typescript@6";

    /** The build stamp of the {@link #JDTLS_VERSION} tarball. */
    public static final String JDTLS_BUILD = "202610131109";

    /** Eclipse JDT-LS pinned milestone; extracts to top-level {@code bin/ config/ features/ plugins/}. */
    public static final String JDTLS_TARBALL_URL = "https://download.eclipse.org/jdtls/milestones/"
            + JDTLS_VERSION
            + "/jdt-language-server-"
            + JDTLS_VERSION
            + "-"
            + JDTLS_BUILD
            + ".tar.gz";

    /** GitHub "latest release" metadata for vscode-js-debug (the {@code js-debug-dap-v*.tar.gz} asset). */
    public static final String JS_DEBUG_RELEASES_API =
            "https://api.github.com/repos/microsoft/vscode-js-debug/releases/latest";

    /** The lemminx-maven release used for the Maven-aware pom.xml server. */
    public static final String LEMMINX_MAVEN_VERSION = "0.12.0";

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
            List<String> npmPackages,
            String pipPackage,
            String apiUrl,
            String assetPattern,
            String directUrl,
            boolean extractJarOnly,
            String destSubpath,
            String verifyEntry) {}

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
    public static List<String> npmInstallGlobalArgv(List<String> pkgs) {
        List<String> argv = new ArrayList<>(List.of("npm", "install", "-g"));
        argv.addAll(pkgs);
        return List.copyOf(argv);
    }

    /** {@code [<python>, -m, pip, install, --upgrade, --target, <dest>, <pkg>]}. Pure. */
    public static List<String> pipInstallTargetArgv(String python, Path dest, String pkg) {
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
            "api.releases.hashicorp.com",
            "releases.hashicorp.com");

    /** Whether {@code url} is an {@code https} URL on one of the {@link #DOWNLOAD_HOSTS} (default port, no
     *  credentials). Pure — unit-tested. */
    public static boolean isTrustedDownloadUrl(String url) {
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
    public static String firstMatch(String text, String regex) {
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
                "kotlin",
                "lua",
                "xml",
                "terraform",
                "typst",
                // JVM LSP zip-with-dependencies (needs java): the Maven-aware pom.xml server.
                "maven-pom");
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
            case "clangd", "kotlin", "lua", "xml", "terraform", "typst" ->
                java.util.Optional.of(List.of(archiveStep(serverId)));
            // --- JVM lemminx + lemminx-maven (one cross-platform zip; needs java on PATH) ---
            case "maven-pom" ->
                java.util.Optional.of(List.of(new Step(
                        "lemminx-maven",
                        Kind.JVM_LSP_ZIP,
                        Set.of(Prereq.JAVA),
                        null,
                        null,
                        null,
                        null,
                        LEMMINX_MAVEN_ZIP_URL,
                        false,
                        "plugins/lsp/maven",
                        null)));
            default -> java.util.Optional.empty();
        };
    }

    /**
     * The per-OS binary-archive recipe for a server id (clangd/kotlin/lua/xml/terraform/typst), or empty.
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
            case "kotlin" ->
                java.util.Optional.of(new ArchiveSpec(
                        "https://api.github.com/repos/fwcd/kotlin-language-server/releases/latest",
                        java.util.Map.of(
                                Platform.MAC_ARM, "server.zip",
                                Platform.MAC_X64, "server.zip",
                                Platform.LINUX_X64, "server.zip",
                                Platform.LINUX_ARM, "server.zip",
                                Platform.WIN_X64, "server.zip"),
                        "kotlin-language-server",
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
            case "xml" ->
                java.util.Optional.of(new ArchiveSpec(
                        "https://api.github.com/repos/redhat-developer/vscode-xml/releases/latest",
                        java.util.Map.of(
                                Platform.MAC_ARM, "osx-aarch_64",
                                Platform.MAC_X64, "osx-x86_64",
                                Platform.LINUX_X64, "linux-x86_64",
                                Platform.LINUX_ARM, "linux-aarch_64",
                                Platform.WIN_X64, "win32"),
                        "lemminx",
                        true,
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

    // --- pinned command-line tools (ShellCheck, shfmt) -------------------------------------------------

    /** The ShellCheck release Editora installs. Bump with every hash in {@link #pinnedTool}. */
    public static final String SHELLCHECK_VERSION = "0.11.0";

    /** The shfmt release Editora installs. Bump with every hash in {@link #pinnedTool}. */
    public static final String SHFMT_VERSION = "3.14.1";

    /** The tools {@link #pinnedTool} knows, in display order. */
    public static final List<String> PINNED_TOOL_IDS = List.of("shellcheck", "shfmt");

    /** How a pinned asset is packed. */
    public enum Packing {
        /** The file is the program itself (shfmt). */
        RAW,
        /** A {@code .tar.gz} holding the program somewhere inside (extracted with the system tar). */
        TAR_GZ,
        /** A {@code .zip} holding the program somewhere inside. */
        ZIP
    }

    /** One platform's download: where it is, its SHA-256 (lower-case hex), and how it is packed. */
    public record PinnedAsset(String url, String sha256, Packing packing) {}

    /**
     * A tool installed from a pinned upstream release.
     *
     * @param binaryName the program's file name without a Windows {@code .exe}
     */
    public record PinnedTool(
            String id, String version, java.util.Map<Platform, PinnedAsset> assets, String binaryName) {

        /** This platform's asset, or null when upstream publishes none for it. */
        public PinnedAsset assetFor(Platform platform) {
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
    public static String installedToolIfPresent(Path configDir, String id) {
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
