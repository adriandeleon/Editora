package com.editora.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maps an editor language id (as resolved by {@code editor.LanguageRegistry.forFileName}) to a
 * <b>server</b> — its id, launch command, and project-root markers. The registry is server-centric, not
 * language-centric: one server can serve several language ids (e.g. the TypeScript server handles
 * {@code javascript}/{@code javascriptreact}/{@code typescript}/{@code typescriptreact}), so the
 * {@link LspManager} keys a single session per {@code (serverId, root)} and all four share one process.
 *
 * <p>Ships twenty-two servers — <b>Java</b> (Eclipse JDT LS), <b>TypeScript</b> (typescript-language-server,
 * which also covers JavaScript/JSX/TSX), <b>Python</b> (Pyright), <b>XML</b> (lemminx), <b>JSON</b>
 * (vscode-json-language-server), <b>Bash</b> (bash-language-server, for shell scripts), <b>YAML</b>
 * (yaml-language-server), <b>Go</b> (gopls), <b>Rust</b> (rust-analyzer), <b>PHP</b> (phpactor),
 * <b>Ruby</b> (ruby-lsp), <b>C/C++</b> (clangd — one server, both language ids), <b>HTML</b> and
 * <b>CSS</b> (vscode-html/css-language-server), <b>Kotlin</b> (kotlin-language-server), <b>Lua</b>
 * (lua-language-server), <b>Dockerfile</b> (docker-langserver), <b>SQL</b> (sqls),
 * <b>Terraform</b> (terraform-ls), <b>TOML</b> (taplo), <b>C#</b> (csharp-ls), <b>Typst</b>
 * (tinymist), and a Maven-aware <b>pom.xml</b> server (JVM lemminx + the lemminx-maven extension — routed by
 * file name so a {@code pom.xml} gets dependency/plugin/GAV completion while other XML keeps the fast native
 * lemminx; see {@link #MAVEN_POM_SERVER_ID}). Commands are
 * user-configurable (Settings) and never bundled. All methods are static + pure (no process launch, no I/O) so they are
 * unit-testable. Adding a server later = one more {@link ServerDef} entry.
 */
public final class LspServerRegistry {

    /** A resolved server launch spec: which server, the argv to launch, and its root markers. */
    public record ServerSpec(String serverId, List<String> command, List<String> rootMarkers) {}

    /** The {@code "java"} server id (jdtls) — used to special-case its dedicated workspace. */
    public static final String JAVA_SERVER_ID = "java";

    /**
     * Returns a copy of {@code spec} with {@code -data <dataDir>} appended to its launch command, so jdtls
     * uses a dedicated Eclipse workspace instead of the shared default (which deadlocks on its {@code .lock}
     * when two roots — or a leaked previous run — contend for it). No-op if the command already specifies
     * {@code -data} (a user-customized command wins) or {@code dataDir} is null.
     */
    public static ServerSpec withDataDir(ServerSpec spec, String dataDir) {
        if (spec == null
                || dataDir == null
                || dataDir.isBlank()
                || spec.command().contains("-data")) {
            return spec;
        }
        List<String> cmd = new ArrayList<>(spec.command());
        cmd.add("-data");
        cmd.add(dataDir);
        return new ServerSpec(spec.serverId(), List.copyOf(cmd), spec.rootMarkers());
    }

    /**
     * A filesystem-safe, stable directory name for a project root's jdtls workspace: a hex SHA-256 of the
     * root's absolute path (truncated). Distinct roots get distinct workspaces; the same root always reuses
     * its workspace (so jdtls's index persists across sessions). Pure (no filesystem access).
     */
    public static String workspaceDirName(Path root) {
        String key = root == null ? "" : root.toAbsolutePath().toString();
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((h[i] >> 4) & 0xF, 16)).append(Character.forDigit(h[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(key.hashCode()); // SHA-256 is always present; defensive fallback
        }
    }

    /** Build files (and {@code .git}) that mark a Java project root, nearest-first when walking up. */
    public static final List<String> JAVA_ROOT_MARKERS =
            List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", ".git");

    /** Markers for a JS/TS project root (a tsconfig/jsconfig wins, else package.json, else the repo). */
    public static final List<String> TS_ROOT_MARKERS =
            List.of("tsconfig.json", "jsconfig.json", "package.json", ".git");

    /** Markers for a Python project root (pyproject/setup/requirements/Pipfile, else the repo). */
    public static final List<String> PYTHON_ROOT_MARKERS =
            List.of("pyproject.toml", "setup.py", "setup.cfg", "requirements.txt", "Pipfile", ".git");

    /** Markers for an XML project root (a build file that often carries the schema, else the repo). */
    public static final List<String> XML_ROOT_MARKERS = List.of("pom.xml", "build.xml", ".git");

    /** Markers for a Maven project root (the pom itself, else the repo). */
    public static final List<String> MAVEN_POM_ROOT_MARKERS = List.of("pom.xml", ".git");

    /** Markers for a JSON project root (package.json, else the repo). */
    public static final List<String> JSON_ROOT_MARKERS = List.of("package.json", ".git");

    /** Markers for a shell project root (the repo; shell scripts are usually standalone). */
    public static final List<String> SHELL_ROOT_MARKERS = List.of(".git");

    /** Markers for a YAML project root (the repo; YAML files are usually standalone). */
    public static final List<String> YAML_ROOT_MARKERS = List.of(".git");

    /** Markers for a Go module root (go.mod/go.work, else the repo). */
    public static final List<String> GO_ROOT_MARKERS = List.of("go.mod", "go.work", ".git");

    /** Markers for a Rust crate/workspace root (Cargo.toml, else the repo). */
    public static final List<String> RUST_ROOT_MARKERS = List.of("Cargo.toml", ".git");

    /** Markers for a PHP project root (composer.json, else the repo). */
    public static final List<String> PHP_ROOT_MARKERS = List.of("composer.json", ".git");

    /** Markers for a Ruby project root (Gemfile/.ruby-version, else the repo). */
    public static final List<String> RUBY_ROOT_MARKERS = List.of("Gemfile", ".ruby-version", ".git");

    /** Markers for a C/C++ project root (a compile DB or build file, else the repo). */
    public static final List<String> C_ROOT_MARKERS =
            List.of("compile_commands.json", ".clangd", "CMakeLists.txt", "Makefile", ".git");

    /** Markers for an HTML/CSS web project root (package.json, else the repo). */
    public static final List<String> WEB_ROOT_MARKERS = List.of("package.json", ".git");

    /** Markers for a Kotlin project root (Gradle/Maven build files, else the repo). */
    public static final List<String> KOTLIN_ROOT_MARKERS =
            List.of("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "pom.xml", ".git");

    /** Markers for a Lua project root (a LuaLS config, else the repo). */
    public static final List<String> LUA_ROOT_MARKERS = List.of(".luarc.json", ".luarc.jsonc", ".git");

    /** Markers for a Terraform module root (a .terraform dir / lock file, else the repo). */
    public static final List<String> TERRAFORM_ROOT_MARKERS = List.of(".terraform", ".terraform.lock.hcl", ".git");

    /** Markers for a C# project root (a global.json, else the repo; csharp-ls finds the .sln/.csproj). */
    public static final List<String> CSHARP_ROOT_MARKERS = List.of("global.json", ".git");

    /** Markers for a Typst project root (a typst.toml package manifest, else the repo). */
    public static final List<String> TYPST_ROOT_MARKERS = List.of("typst.toml", ".git");

    /** Default server commands when the user leaves the Settings field blank. */
    public static final String DEFAULT_JAVA_COMMAND = "jdtls";

    public static final String DEFAULT_TYPESCRIPT_COMMAND = "typescript-language-server --stdio";
    public static final String DEFAULT_PYTHON_COMMAND = "pyright-langserver --stdio";
    public static final String DEFAULT_XML_COMMAND = "lemminx";
    public static final String DEFAULT_JSON_COMMAND = "vscode-json-language-server --stdio";
    public static final String DEFAULT_BASH_COMMAND = "bash-language-server start";
    public static final String DEFAULT_YAML_COMMAND = "yaml-language-server --stdio";
    public static final String DEFAULT_GO_COMMAND = "gopls";
    public static final String DEFAULT_RUST_COMMAND = "rust-analyzer";
    public static final String DEFAULT_PHP_COMMAND = "phpactor language-server";
    public static final String DEFAULT_RUBY_COMMAND = "ruby-lsp";
    public static final String DEFAULT_CLANGD_COMMAND = "clangd";
    public static final String DEFAULT_HTML_COMMAND = "vscode-html-language-server --stdio";
    public static final String DEFAULT_CSS_COMMAND = "vscode-css-language-server --stdio";
    public static final String DEFAULT_KOTLIN_COMMAND = "kotlin-language-server";
    public static final String DEFAULT_LUA_COMMAND = "lua-language-server";
    public static final String DEFAULT_DOCKERFILE_COMMAND = "docker-langserver --stdio";
    public static final String DEFAULT_SQL_COMMAND = "sqls";
    public static final String DEFAULT_TERRAFORM_COMMAND = "terraform-ls serve";
    public static final String DEFAULT_TOML_COMMAND = "taplo lsp stdio";
    public static final String DEFAULT_CSHARP_COMMAND = "csharp-ls";
    public static final String DEFAULT_TYPST_COMMAND = "tinymist lsp";

    /**
     * The Maven-aware {@code pom.xml} server (JVM lemminx + the lemminx-maven extension). It has <b>no</b>
     * built-in default command: unlike the other servers it is a Java launcher whose classpath points at the
     * install directory (under the config dir), which is only known at runtime — the install recipe writes the
     * concrete {@code java -cp "<dir>/*" org.eclipse.lemminx.XMLServerLauncher} command into Settings. Blank
     * means "not installed", so it stays unavailable and {@code pom.xml} falls back to the native XML server.
     */
    public static final String DEFAULT_MAVEN_POM_COMMAND = "";

    /** The server id of the Maven-aware pom.xml server (JVM lemminx + lemminx-maven). */
    public static final String MAVEN_POM_SERVER_ID = "maven-pom";

    /**
     * The pseudo language id the {@link #MAVEN_POM_SERVER_ID} server is keyed on. A {@code pom.xml} buffer's
     * editor language stays {@code xml} (so highlighting/folding/comments are untouched); this id is used only
     * to <i>route the LSP session</i> to the Maven server. The id sent to the server in {@code didOpen} is
     * translated back to {@code xml} via {@link #protocolLanguageId} — lemminx keys its XML handling on that.
     */
    public static final String MAVEN_POM_LANGUAGE_ID = "maven-pom";

    /** A known language server: its id, default command, root markers, and the language ids it serves. */
    private enum ServerDef {
        JAVA("java", DEFAULT_JAVA_COMMAND, JAVA_ROOT_MARKERS, Set.of("java")),
        TYPESCRIPT(
                "typescript",
                DEFAULT_TYPESCRIPT_COMMAND,
                TS_ROOT_MARKERS,
                Set.of("javascript", "javascriptreact", "typescript", "typescriptreact")),
        PYTHON("python", DEFAULT_PYTHON_COMMAND, PYTHON_ROOT_MARKERS, Set.of("python")),
        XML("xml", DEFAULT_XML_COMMAND, XML_ROOT_MARKERS, Set.of("xml")),
        JSON("json", DEFAULT_JSON_COMMAND, JSON_ROOT_MARKERS, Set.of("json")),
        BASH("bash", DEFAULT_BASH_COMMAND, SHELL_ROOT_MARKERS, Set.of("shell")),
        YAML("yaml", DEFAULT_YAML_COMMAND, YAML_ROOT_MARKERS, Set.of("yaml")),
        GO("go", DEFAULT_GO_COMMAND, GO_ROOT_MARKERS, Set.of("go")),
        RUST("rust", DEFAULT_RUST_COMMAND, RUST_ROOT_MARKERS, Set.of("rust")),
        PHP("php", DEFAULT_PHP_COMMAND, PHP_ROOT_MARKERS, Set.of("php")),
        RUBY("ruby", DEFAULT_RUBY_COMMAND, RUBY_ROOT_MARKERS, Set.of("ruby")),
        // clangd serves both C and C++ (one server, two language ids — like the TypeScript server).
        CLANGD("clangd", DEFAULT_CLANGD_COMMAND, C_ROOT_MARKERS, Set.of("c", "cpp")),
        HTML("html", DEFAULT_HTML_COMMAND, WEB_ROOT_MARKERS, Set.of("html")),
        CSS("css", DEFAULT_CSS_COMMAND, WEB_ROOT_MARKERS, Set.of("css")),
        KOTLIN("kotlin", DEFAULT_KOTLIN_COMMAND, KOTLIN_ROOT_MARKERS, Set.of("kotlin")),
        LUA("lua", DEFAULT_LUA_COMMAND, LUA_ROOT_MARKERS, Set.of("lua")),
        DOCKERFILE("dockerfile", DEFAULT_DOCKERFILE_COMMAND, SHELL_ROOT_MARKERS, Set.of("dockerfile")),
        SQL("sql", DEFAULT_SQL_COMMAND, SHELL_ROOT_MARKERS, Set.of("sql")),
        TERRAFORM("terraform", DEFAULT_TERRAFORM_COMMAND, TERRAFORM_ROOT_MARKERS, Set.of("terraform")),
        TOML("toml", DEFAULT_TOML_COMMAND, SHELL_ROOT_MARKERS, Set.of("toml")),
        CSHARP("csharp", DEFAULT_CSHARP_COMMAND, CSHARP_ROOT_MARKERS, Set.of("csharp")),
        TYPST("typst", DEFAULT_TYPST_COMMAND, TYPST_ROOT_MARKERS, Set.of("typst")),
        // Maven-aware pom.xml server: JVM lemminx + lemminx-maven. Served language id is the routing-only
        // pseudo id MAVEN_POM_LANGUAGE_ID; the didOpen id is translated back to "xml" (see protocolLanguageId).
        MAVEN_POM(
                MAVEN_POM_SERVER_ID, DEFAULT_MAVEN_POM_COMMAND, MAVEN_POM_ROOT_MARKERS, Set.of(MAVEN_POM_LANGUAGE_ID));

        final String id;
        final String defaultCommand;
        final List<String> rootMarkers;
        final Set<String> languageIds;

        ServerDef(String id, String defaultCommand, List<String> rootMarkers, Set<String> languageIds) {
            this.id = id;
            this.defaultCommand = defaultCommand;
            this.rootMarkers = rootMarkers;
            this.languageIds = languageIds;
        }
    }

    private LspServerRegistry() {}

    private static ServerDef defFor(String languageId) {
        for (ServerDef d : ServerDef.values()) {
            if (d.languageIds.contains(languageId)) {
                return d;
            }
        }
        return null;
    }

    /** The server id that serves {@code languageId} (e.g. {@code typescript} for {@code javascript}), or null. */
    public static String serverIdFor(String languageId) {
        ServerDef d = defFor(languageId);
        return d == null ? null : d.id;
    }

    /**
     * Whether {@code fileName} is a Maven POM (a file literally named {@code pom.xml}, case-insensitive) — the
     * trigger for routing a buffer to the Maven-aware server instead of the plain XML server. Pure; takes the
     * bare file name (not a path) so it is trivially unit-testable.
     */
    public static boolean isPomFile(String fileName) {
        return fileName != null && fileName.equalsIgnoreCase("pom.xml");
    }

    /**
     * The language id sent to the <i>server</i> in {@code didOpen} for a routing id: the Maven pseudo id
     * {@link #MAVEN_POM_LANGUAGE_ID} maps back to {@code xml} (lemminx keys its XML handling on that, and
     * lemminx-maven detects a POM by its URI, not the language id); every other id is passed through.
     */
    public static String protocolLanguageId(String routeLanguageId) {
        return MAVEN_POM_LANGUAGE_ID.equals(routeLanguageId) ? "xml" : routeLanguageId;
    }

    /** Project-root markers for a <i>server id</i> (empty if unknown). Complements {@link #rootMarkersFor}. */
    public static List<String> rootMarkersForServer(String serverId) {
        for (ServerDef d : ServerDef.values()) {
            if (d.id.equals(serverId)) {
                return d.rootMarkers;
            }
        }
        return List.of();
    }

    /** Whether any registered server serves {@code languageId}. */
    public static boolean isSupported(String languageId) {
        return defFor(languageId) != null;
    }

    /** Project-root markers for the server serving {@code languageId} (empty if unsupported). */
    public static List<String> rootMarkersFor(String languageId) {
        ServerDef d = defFor(languageId);
        return d == null ? List.of() : d.rootMarkers;
    }

    /** The default command for a server id (blank if unknown). */
    public static String defaultCommandFor(String serverId) {
        for (ServerDef d : ServerDef.values()) {
            if (d.id.equals(serverId)) {
                return d.defaultCommand;
            }
        }
        return "";
    }

    /**
     * The launch spec for {@code languageId}, resolving the server's command from {@code commands}
     * (serverId → configured command; a blank/absent entry uses the server's default), or {@code null}
     * when the language is unsupported.
     */
    public static ServerSpec specFor(String languageId, Map<String, String> commands) {
        ServerDef d = defFor(languageId);
        if (d == null) {
            return null;
        }
        String configured = commands == null ? null : commands.get(d.id);
        String cmd = configured == null || configured.isBlank() ? d.defaultCommand : configured;
        return new ServerSpec(d.id, tokenize(cmd), d.rootMarkers);
    }

    /** The tokenized argv for {@code serverId} from {@code commands} (blank ⇒ default), or empty if unknown. */
    public static List<String> commandFor(String serverId, Map<String, String> commands) {
        String configured = commands == null ? null : commands.get(serverId);
        String def = defaultCommandFor(serverId);
        if (def.isEmpty() && (configured == null || configured.isBlank())) {
            return List.of();
        }
        return tokenize(configured == null || configured.isBlank() ? def : configured);
    }

    /**
     * Splits a command string into argv on unquoted whitespace, honoring single/double quotes so a path
     * with spaces stays one token. Pure; mirrors the tokenizing done for the mmdc/maid commands.
     */
    public static List<String> tokenize(String command) {
        List<String> out = new ArrayList<>();
        if (command == null) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                has = true;
            } else if (Character.isWhitespace(c)) {
                if (has) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    has = false;
                }
            } else {
                cur.append(c);
                has = true;
            }
        }
        if (has) {
            out.add(cur.toString());
        }
        return out;
    }
}
