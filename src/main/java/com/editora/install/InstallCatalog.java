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
        TOOL_COMMAND
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
            case MERMAID -> List.of(mmdc());
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
                "php");
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
            default -> java.util.Optional.empty();
        };
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
}
