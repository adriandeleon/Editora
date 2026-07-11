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
 * mermaid-cli), a pip {@code --target} (debugpy), and an Open VSX {@code .vsix} — a ZIP, extracted by the
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
        PUPPETEER_BROWSER
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
        COMPOSER
    }

    /** Eclipse JDT-LS rolling release; extracts to top-level {@code bin/ config/ features/ plugins/}. */
    public static final String JDTLS_TARBALL_URL =
            "https://download.eclipse.org/jdtls/snapshots/jdt-language-server-latest.tar.gz";

    /** GitHub "latest release" metadata for vscode-js-debug (the {@code js-debug-dap-v*.tar.gz} asset). */
    public static final String JS_DEBUG_RELEASES_API =
            "https://api.github.com/repos/microsoft/vscode-js-debug/releases/latest";

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
                "typst");
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
            default -> java.util.Optional.empty();
        };
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

    /** A single {@code npm install -g <pkg>} step (the step id = the package). Pure. */
    private static Step npmStep(String pkg) {
        return new Step(
                pkg, Kind.NPM_GLOBAL, Set.of(Prereq.NPM), List.of(pkg), null, null, null, null, false, "", null);
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
                List.of("typescript-language-server", "typescript"),
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
