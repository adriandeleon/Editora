package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Language Servers: the master switch, the Python card, and one card per server (data-driven over {@link
 * #lspServerUis()}) with its enable switch, command, detection status, Install button and project-override line,
 * plus the per-server rows of {@link SettingsBashRows}, {@link SettingsXmlRows}, {@link SettingsHtmlRows} and {@link
 * SettingsJsToolRows}. The page's controls, its load and the status refresh live here.
 */
final class SettingsLspPage {

    private final SettingsWindow w;
    private final ConfigManager config;
    private final com.editora.lsp.LspManager lspManager;

    SettingsLspPage(SettingsWindow w, ConfigManager config, com.editora.lsp.LspManager lspManager) {
        this.w = w;
        this.config = config;
        this.lspManager = lspManager;
    }

    @Initializer
    void buildControls() {
        lspCheck = new CheckBox(tr("settings.enableLsp"));
        lspCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setLspSupport(now);
            w.apply();
            w.refreshToolWindowRows(); // reflect on the Tool Windows page's Problems row
            refreshLspStatus();
        });
        for (LspServerUi srv : lspServerUis()) {
            CheckBox enable = new CheckBox(tr(srv.enableLabelKey()));
            enable.selectedProperty().addListener((obs, was, now) -> {
                if (!w.scopeUi.write("lspEnabled." + srv.id(), now)) {
                    srv.setEnabled().accept(now);
                    w.apply();
                }
            });
            TextField field = new TextField();
            // A server with no default command (Maven's pom.xml server) has only what Install… puts here.
            field.setPromptText(
                    srv.defaultCommand().isBlank() ? tr("settings.lsp.installedPrompt") : srv.defaultCommand());
            w.commitOnEnterOrBlur(
                    field,
                    () -> w.scopeUi.shown(
                            "lspCommands." + srv.id(), srv.getCommand().get()),
                    now -> {
                        if (!w.scopeUi.write("lspCommands." + srv.id(), now)) {
                            srv.setCommand().accept(now);
                        }
                        w.apply();
                        refreshLspStatus();
                    });
            lspEnableChecks.put(srv.id(), enable);
            lspCommandFields.put(srv.id(), field);
        }
    }

    CheckBox lspCheck;
    /** Per-server LSP controls, keyed by server id (data-driven so adding a server is one descriptor). */
    final java.util.Map<String, CheckBox> lspEnableChecks = new java.util.LinkedHashMap<>();

    final java.util.Map<String, TextField> lspCommandFields = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, ToolStatusRow> lspStatusLabels = new java.util.LinkedHashMap<>();

    /** Injected by MainController: runs {@code InstallCoordinator.installServer} for the given LSP server id. */
    private java.util.function.@Nullable Consumer<String> onInstallServer;

    /** Installs a pinned command-line tool (shellcheck/shfmt) from the Bash card's Install buttons. */
    private java.util.function.@Nullable Consumer<String> onInstallTool;

    public void setInstallToolActions(java.util.function.Consumer<String> onInstallTool) {
        this.onInstallTool = onInstallTool;
    }

    /** Wires the per-LSP-server Install buttons (json/bash/yaml/…) to {@code InstallCoordinator.installServer}. */
    public void setInstallServerActions(java.util.function.Consumer<String> onInstallServer) {
        this.onInstallServer = onInstallServer;
    }

    /** The install-language key for an installable LSP server row, or {@code null} (no installer). */
    private static @Nullable String installLangForServer(String serverId) {
        return switch (serverId) {
            case "java" -> "java";
            case "python" -> "python";
            case "typescript" -> "javascript";
            default -> null;
        };
    }

    /**
     * The key a language server's Install button is tracked under. Not the bare server id: the Typst page's
     * button for the {@code typst} CLI is tracked as {@code typst} too, and sharing the key left one of the two
     * buttons out of the map — it never showed "Installed", and the other showed whichever probe came last.
     */
    private static String serverInstallKey(String serverId) {
        return "server:" + serverId;
    }

    VBox build() {
        VBox p = w.page(tr("settings.cat.lsp"), tr("settings.lsp.subtitle"));
        Card master = w.card(p, null);
        w.checkRow(
                master,
                Category.LSP,
                lspCheck,
                tr("settings.lsp.hint"),
                "lsp language server protocol enable java typescript python xml json bash diagnostics"
                        + " install jdtls pyright lemminx");
        w.checkRow(
                master,
                Category.LSP,
                w.lspInstallPromptsCheck,
                null,
                "lsp install banner prompt offer language support");
        // One card per server (UI Kit v1). Twenty-one servers × four controls was a ~84-row flat wall in
        // which it took real effort to see which status line belonged to which enable box. The card is
        // deliberately untitled: the server's own enable checkbox already names it in every locale, and a
        // derived title ("Enable Java (jdtls)" minus "Enable ") would not survive translation.
        // The per-feature LSP switches (inlay hints, code lens, …) live with completion; say where.
        Hyperlink features = new Hyperlink(tr("settings.lsp.featuresLink", Category.COMPLETION.display));
        features.setWrapText(true);
        features.setOnAction(e -> w.sidebar.getSelectionModel().select(Category.COMPLETION));
        w.row(
                p,
                Category.LSP,
                null,
                features,
                "lsp inlay hints code lens semantic highlighting format on type imports");
        pythonCard(p); // before dependsOn: editable with language servers off, as Run and Debug use it too
        w.dependsOn(p, lspCheck);
        for (LspServerUi srv : lspServerUis()) {
            Card c = w.card(p, null);
            SettingsDependents.bind(lspCheck.selectedProperty(), c.box());
            CheckBox enable = lspEnableChecks.get(srv.id());
            if (enable != null) { // build() makes one per server
                w.cardRow(
                        c,
                        Category.LSP,
                        SettingsWindow.settingRow(tr(srv.enableLabelKey()), null, SettingsWindow.switchFor(enable)),
                        srv.keywords());
            }
            ToolStatusRow status = new ToolStatusRow(lspToolName(srv.id(), lspArgv(srv.id())));
            String langKey = installLangForServer(srv.id());
            if (langKey != null) {
                w.installButtons.put(
                        langKey, status.install(() -> w.installSupport(langKey)).installButton());
            } else if (com.editora.install.InstallCatalog.installableServerIds().contains(srv.id())) {
                w.installButtons.put(
                        serverInstallKey(srv.id()),
                        status.install(() -> {
                                    if (onInstallServer != null) {
                                        onInstallServer.accept(srv.id());
                                    }
                                })
                                .installButton());
            }
            lspStatusLabels.put(srv.id(), status);
            String title = tr(srv.commandLabelKey());
            w.toolRows(c, Category.LSP, title, null, lspCommandFields.get(srv.id()), null, status, srv.keywords());
            Label project = SettingsWindow.note("");
            project.setVisible(false);
            project.setWrapText(true);
            project.managedProperty().bind(project.visibleProperty());
            lspProjectLabels.put(srv.id(), project);
            c.body().getChildren().add(project);
            if (com.editora.lsp.LspServerRegistry.JAVA_SERVER_ID.equals(srv.id())) {
                w.checkRow( // every dependency's sources at import; a library tab offers them one at a time anyway
                        c,
                        Category.LSP,
                        w.javaDownloadSourcesCheck,
                        tr("settings.lsp.javaDownloadSources.hint"),
                        "lsp java jdtls library sources download maven gradle dependencies decompiled network");
            }
            if (MAVEN_POM.equals(srv.id())) {
                // What the server adds, what a pom gets without it, and which java runs it (review C17); and the
                // Central-index choice that decides whether completion sees more than ~/.m2 (review C10).
                Label hint = SettingsWindow.note(tr("settings.lsp.mavenPom.hint"));
                hint.setWrapText(true);
                w.cardRow(c, Category.LSP, hint, srv.keywords() + " hint jdk fallback xml artifact");
                w.checkRow(
                        c,
                        Category.LSP,
                        w.mavenCentralIndexCheck,
                        tr("settings.lsp.mavenCentralIndex.hint"),
                        "lsp maven central search pom.xml completion coordinates artifacts remote network");
            }
            if ("bash".equals(srv.id())) {
                SettingsBashRows.add(w, this, c);
            } else if (com.editora.lsp.XmlServerSettings.SERVER_ID.equals(srv.id())) {
                SettingsXmlRows.add(w, c);
            } else if (com.editora.lsp.HtmlServerSettings.SERVER_ID.equals(srv.id())) {
                SettingsHtmlRows.add(w, c); // the server's html.* settings (HTML review A4)
            } else if (com.editora.lsp.TypeScriptServer.SERVER_ID.equals(srv.id())) {
                typeScriptServerRows(c);
            }
        }
        return p;
    }

    /**
     * The Python card (Python review 2, C101): the fallback interpreter, a way to the project's own pick and format on
     * save. It sat on the Debugging page's Python card, greyed out while debugging was off — the default — though Run
     * and Pyright use it too.
     */
    private void pythonCard(VBox p) {
        String words = "python interpreter venv virtual environment pyright run debug tests ";
        Card c = w.card(p, tr("settings.lsp.python.title"));
        TextField field = w.debugPage.debugCommandFields.get("python");
        if (field != null) { // build() makes one per adapter
            String title = tr("settings.debug.pythonCommand");
            field.setPrefWidth(SettingsWindow.PATH_FIELD_WIDTH);
            SettingsWindow.fullValueTip(field, title);
            HBox path = new HBox(6, field, w.browseButton(title, field));
            w.controlRow(
                    c, Category.LSP, title, tr("settings.lsp.pythonInterpreter.desc"), path, words + "path python3");
        }
        Button pick = new Button(tr("settings.lsp.pythonPick"));
        pick.setOnAction(e -> {
            if (w.runCommand != null) {
                w.stage.hide(); // the picker opens in the editor window, behind this one
                w.runCommand.accept("python.selectInterpreter");
            }
        });
        String pickTitle = tr("command.python.selectInterpreter");
        w.controlRow(
                c, Category.LSP, pickTitle, tr("settings.lsp.pythonPick.desc"), pick, words + "select pick project");
        w.switchRow(
                c,
                Category.LSP,
                "lsp.pythonFormatOnSave",
                true,
                Settings::isPythonFormatOnSave,
                Settings::setPythonFormatOnSave,
                words + "format formatter ruff black on save actions");
    }

    /** The TypeScript card's "TypeScript" line (the SDK its server runs). */
    private @Nullable ToolStatusRow typeScriptSdkStatus;

    /** The TypeScript card's own rows: the TypeScript its server runs, and automatic type acquisition. */
    private void typeScriptServerRows(Card c) {
        String words = "lsp typescript javascript node tsserver sdk ";
        typeScriptSdkStatus = new ToolStatusRow("TypeScript").install(() -> w.installSupport("javascript"));
        w.controlRow(
                c,
                Category.LSP,
                tr("settings.lsp.typescriptSdk"),
                tr("settings.lsp.typescriptSdk.desc"),
                typeScriptSdkStatus.node(),
                words + "version found not found install");
        w.switchRow(
                c,
                Category.LSP,
                "lsp.typescriptTypeAcquisition",
                true,
                Settings::isTypescriptTypeAcquisition,
                Settings::setTypescriptTypeAcquisition,
                words + "types npm automatic type acquisition download @types");
        SettingsJsToolRows.add(w, c); // ESLint, Prettier/Biome and the actions on save (JS review F)
    }

    /** The ShellCheck / shfmt status lines on the Bash card, by tool id. */
    private final Map<String, ToolStatusRow> bashToolStatusLabels = new java.util.LinkedHashMap<>();

    private static String toolInstallKey(String toolId) {
        return "tool:" + toolId;
    }

    /**
     * Shows whether the ShellCheck and shfmt the Bash server will run are there (the configured path, else
     * Editora's installed copy, else the name on PATH) — a lookup, no process — and enables their Install buttons.
     */
    private void refreshBashToolStatus() {
        if (bashToolStatusLabels.isEmpty()) {
            return;
        }
        // The window's own: a trusted project's tool paths are the ones that run (Bash review A10).
        com.editora.lsp.BashIdeConfig tools = w.projectLsp != null
                ? w.projectLsp.bashIdeConfig()
                : LspCoordinator.userBashIde(config.getSettings(), config.getConfigDir());
        bashToolStatusLabels.forEach((toolId, status) -> {
            String found = com.editora.doctor.DoctorProbes.resolvedPath(List.of(tools.toolCommand(toolId)));
            status.show(!found.isEmpty(), found, lspCheck.isSelected());
        });
    }

    /** A path-with-Browse row for one of the Bash server's tools; blank = Editora's installed copy, else PATH. */
    void bashPathRow(
            Card c,
            String key,
            java.util.function.Function<Settings, String> get,
            java.util.function.BiConsumer<Settings, String> set) {
        TextField field = new TextField();
        field.setPromptText(tr("settings.lsp.bash." + key + ".prompt"));
        w.settingControls.put("lsp.bash." + key, field);
        w.commitOnEnterOrBlur(field, () -> get.apply(config.getSettings()), now -> {
            set.accept(config.getSettings(), now);
            w.apply();
            refreshLspStatus();
        });
        // Its status row (ShellCheck / shfmt: found, Install…) follows, like every other tool's.
        String toolId = key.replace("Path", "");
        ToolStatusRow status = new ToolStatusRow(InstallCoordinator.toolName(toolId)).install(() -> {
            if (onInstallTool != null) {
                onInstallTool.accept(toolId);
            }
        });
        bashToolStatusLabels.put(toolId, status);
        w.installButtons.put(toolInstallKey(toolId), status.installButton());
        w.toolRows(c, Category.LSP, tr("settings.lsp.bash." + key), null, field, null, status, SettingsBashRows.WORDS);
        w.settingSyncs.add(st -> field.setText(get.apply(st)));
    }

    /** A configurable LSP server's Settings row (data-driven so adding a server is one descriptor). */
    private record LspServerUi(
            String id,
            String defaultCommand,
            String enableLabelKey,
            String commandLabelKey,
            String keywords,
            java.util.function.Consumer<Boolean> setEnabled,
            java.util.function.BooleanSupplier getEnabled,
            java.util.function.Consumer<String> setCommand,
            java.util.function.Supplier<String> getCommand) {}

    /** The six configurable LSP servers, in display order. Lambdas read/write the live {@code Settings}. */
    private java.util.List<LspServerUi> lspServerUis() {
        return java.util.List.of(
                new LspServerUi(
                        "java",
                        com.editora.lsp.LspServerRegistry.DEFAULT_JAVA_COMMAND,
                        "settings.lsp.enableJava",
                        "settings.lsp.javaCommand",
                        "lsp java jdtls language server found installed not found command path executable",
                        v -> config.getSettings().setJavaLspEnabled(v),
                        () -> config.getSettings().isJavaLspEnabled(),
                        v -> config.getSettings().setJavaLspCommand(v),
                        () -> config.getSettings().getJavaLspCommand()),
                new LspServerUi(
                        "typescript",
                        com.editora.lsp.LspServerRegistry.DEFAULT_TYPESCRIPT_COMMAND,
                        "settings.lsp.enableTypescript",
                        "settings.lsp.typescriptCommand",
                        "lsp typescript javascript language server found installed not found command path",
                        v -> config.getSettings().setTypescriptLspEnabled(v),
                        () -> config.getSettings().isTypescriptLspEnabled(),
                        v -> config.getSettings().setTypescriptLspCommand(v),
                        () -> config.getSettings().getTypescriptLspCommand()),
                new LspServerUi(
                        "python",
                        com.editora.lsp.LspServerRegistry.DEFAULT_PYTHON_COMMAND,
                        "settings.lsp.enablePython",
                        "settings.lsp.pythonCommand",
                        "lsp python pyright language server found installed not found command path executable",
                        v -> config.getSettings().setPythonLspEnabled(v),
                        () -> config.getSettings().isPythonLspEnabled(),
                        v -> config.getSettings().setPythonLspCommand(v),
                        () -> config.getSettings().getPythonLspCommand()),
                new LspServerUi(
                        "xml",
                        com.editora.lsp.LspServerRegistry.DEFAULT_XML_COMMAND,
                        "settings.lsp.enableXml",
                        "settings.lsp.xmlCommand",
                        "lsp xml lemminx language server found installed not found command path executable",
                        v -> config.getSettings().setXmlLspEnabled(v),
                        () -> config.getSettings().isXmlLspEnabled(),
                        v -> config.getSettings().setXmlLspCommand(v),
                        () -> config.getSettings().getXmlLspCommand()),
                // Right after XML: the pom.xml server replaces it for poms (Maven review C17).
                new LspServerUi(
                        com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID,
                        com.editora.lsp.LspServerRegistry.DEFAULT_MAVEN_POM_COMMAND,
                        "settings.lsp.enableMavenPom",
                        "settings.lsp.mavenPomCommand",
                        "lsp maven pom.xml lemminx dependency completion language server found command path",
                        v -> config.getSettings().setMavenPomLspEnabled(v),
                        () -> config.getSettings().isMavenPomLspEnabled(),
                        v -> config.getSettings().setMavenPomLspCommand(v),
                        () -> config.getSettings().getMavenPomLspCommand()),
                new LspServerUi(
                        "json",
                        com.editora.lsp.LspServerRegistry.DEFAULT_JSON_COMMAND,
                        "settings.lsp.enableJson",
                        "settings.lsp.jsonCommand",
                        "lsp json language server found installed not found command path executable",
                        v -> config.getSettings().setJsonLspEnabled(v),
                        () -> config.getSettings().isJsonLspEnabled(),
                        v -> config.getSettings().setJsonLspCommand(v),
                        () -> config.getSettings().getJsonLspCommand()),
                new LspServerUi(
                        "bash",
                        com.editora.lsp.LspServerRegistry.DEFAULT_BASH_COMMAND,
                        "settings.lsp.enableBash",
                        "settings.lsp.bashCommand",
                        "lsp bash shell shellcheck language server found installed not found command path",
                        v -> config.getSettings().setBashLspEnabled(v),
                        () -> config.getSettings().isBashLspEnabled(),
                        v -> config.getSettings().setBashLspCommand(v),
                        () -> config.getSettings().getBashLspCommand()),
                new LspServerUi(
                        "yaml",
                        com.editora.lsp.LspServerRegistry.DEFAULT_YAML_COMMAND,
                        "settings.lsp.enableYaml",
                        "settings.lsp.yamlCommand",
                        "lsp yaml yml language server found installed not found command path executable",
                        v -> config.getSettings().setYamlLspEnabled(v),
                        () -> config.getSettings().isYamlLspEnabled(),
                        v -> config.getSettings().setYamlLspCommand(v),
                        () -> config.getSettings().getYamlLspCommand()),
                new LspServerUi(
                        "go",
                        com.editora.lsp.LspServerRegistry.DEFAULT_GO_COMMAND,
                        "settings.lsp.enableGo",
                        "settings.lsp.goCommand",
                        "lsp go golang gopls language server found installed not found command path",
                        v -> config.getSettings().setGoLspEnabled(v),
                        () -> config.getSettings().isGoLspEnabled(),
                        v -> config.getSettings().setGoLspCommand(v),
                        () -> config.getSettings().getGoLspCommand()),
                new LspServerUi(
                        "rust",
                        com.editora.lsp.LspServerRegistry.DEFAULT_RUST_COMMAND,
                        "settings.lsp.enableRust",
                        "settings.lsp.rustCommand",
                        "lsp rust rust-analyzer cargo language server found installed not found command path",
                        v -> config.getSettings().setRustLspEnabled(v),
                        () -> config.getSettings().isRustLspEnabled(),
                        v -> config.getSettings().setRustLspCommand(v),
                        () -> config.getSettings().getRustLspCommand()),
                new LspServerUi(
                        "php",
                        com.editora.lsp.LspServerRegistry.DEFAULT_PHP_COMMAND,
                        "settings.lsp.enablePhp",
                        "settings.lsp.phpCommand",
                        "lsp php phpactor intelephense language server found installed not found command path",
                        v -> config.getSettings().setPhpLspEnabled(v),
                        () -> config.getSettings().isPhpLspEnabled(),
                        v -> config.getSettings().setPhpLspCommand(v),
                        () -> config.getSettings().getPhpLspCommand()),
                new LspServerUi(
                        "ruby",
                        com.editora.lsp.LspServerRegistry.DEFAULT_RUBY_COMMAND,
                        "settings.lsp.enableRuby",
                        "settings.lsp.rubyCommand",
                        "lsp ruby ruby-lsp solargraph language server found installed not found command path",
                        v -> config.getSettings().setRubyLspEnabled(v),
                        () -> config.getSettings().isRubyLspEnabled(),
                        v -> config.getSettings().setRubyLspCommand(v),
                        () -> config.getSettings().getRubyLspCommand()),
                new LspServerUi(
                        "clangd",
                        com.editora.lsp.LspServerRegistry.DEFAULT_CLANGD_COMMAND,
                        "settings.lsp.enableClangd",
                        "settings.lsp.clangdCommand",
                        "lsp c cpp c++ clangd language server found installed not found command path",
                        v -> config.getSettings().setClangdLspEnabled(v),
                        () -> config.getSettings().isClangdLspEnabled(),
                        v -> config.getSettings().setClangdLspCommand(v),
                        () -> config.getSettings().getClangdLspCommand()),
                new LspServerUi(
                        "html",
                        com.editora.lsp.LspServerRegistry.DEFAULT_HTML_COMMAND,
                        "settings.lsp.enableHtml",
                        "settings.lsp.htmlCommand",
                        "lsp html language server found installed not found command path executable",
                        v -> config.getSettings().setHtmlLspEnabled(v),
                        () -> config.getSettings().isHtmlLspEnabled(),
                        v -> config.getSettings().setHtmlLspCommand(v),
                        () -> config.getSettings().getHtmlLspCommand()),
                new LspServerUi(
                        "css",
                        com.editora.lsp.LspServerRegistry.DEFAULT_CSS_COMMAND,
                        "settings.lsp.enableCss",
                        "settings.lsp.cssCommand",
                        "lsp css scss less language server found installed not found command path",
                        v -> config.getSettings().setCssLspEnabled(v),
                        () -> config.getSettings().isCssLspEnabled(),
                        v -> config.getSettings().setCssLspCommand(v),
                        () -> config.getSettings().getCssLspCommand()),
                new LspServerUi(
                        "kotlin",
                        com.editora.lsp.LspServerRegistry.DEFAULT_KOTLIN_COMMAND,
                        "settings.lsp.enableKotlin",
                        "settings.lsp.kotlinCommand",
                        "lsp kotlin language server jetbrains kotlin-lsp intellij-server gradle found installed not found"
                                + " command path executable",
                        v -> config.getSettings().setKotlinLspEnabled(v),
                        () -> config.getSettings().isKotlinLspEnabled(),
                        v -> config.getSettings().setKotlinLspCommand(v),
                        () -> config.getSettings().getKotlinLspCommand()),
                new LspServerUi(
                        "groovy",
                        com.editora.lsp.LspServerRegistry.DEFAULT_GROOVY_COMMAND,
                        "settings.lsp.enableGroovy",
                        "settings.lsp.groovyCommand",
                        "lsp groovy gvy gls gradle jenkinsfile language server found installed not found command path"
                                + " java jar",
                        v -> config.getSettings().setGroovyLspEnabled(v),
                        () -> config.getSettings().isGroovyLspEnabled(),
                        v -> config.getSettings().setGroovyLspCommand(v),
                        () -> config.getSettings().getGroovyLspCommand()),
                new LspServerUi(
                        "scala",
                        com.editora.lsp.LspServerRegistry.DEFAULT_SCALA_COMMAND,
                        "settings.lsp.enableScala",
                        "settings.lsp.scalaCommand",
                        "lsp scala metals sbt mill scala-cli bloop bsp language server found installed not found"
                                + " command path java coursier",
                        v -> config.getSettings().setScalaLspEnabled(v),
                        () -> config.getSettings().isScalaLspEnabled(),
                        v -> config.getSettings().setScalaLspCommand(v),
                        () -> config.getSettings().getScalaLspCommand()),
                new LspServerUi(
                        "clojure",
                        com.editora.lsp.LspServerRegistry.DEFAULT_CLOJURE_COMMAND,
                        "settings.lsp.enableClojure",
                        "settings.lsp.clojureCommand",
                        "lsp clojure clojure-lsp clojurescript edn deps lein babashka language server found installed"
                                + " not found command path executable",
                        v -> config.getSettings().setClojureLspEnabled(v),
                        () -> config.getSettings().isClojureLspEnabled(),
                        v -> config.getSettings().setClojureLspCommand(v),
                        () -> config.getSettings().getClojureLspCommand()),
                new LspServerUi(
                        "lua",
                        com.editora.lsp.LspServerRegistry.DEFAULT_LUA_COMMAND,
                        "settings.lsp.enableLua",
                        "settings.lsp.luaCommand",
                        "lsp lua language server found installed not found command path executable",
                        v -> config.getSettings().setLuaLspEnabled(v),
                        () -> config.getSettings().isLuaLspEnabled(),
                        v -> config.getSettings().setLuaLspCommand(v),
                        () -> config.getSettings().getLuaLspCommand()),
                new LspServerUi(
                        "dockerfile",
                        com.editora.lsp.LspServerRegistry.DEFAULT_DOCKERFILE_COMMAND,
                        "settings.lsp.enableDockerfile",
                        "settings.lsp.dockerfileCommand",
                        "lsp dockerfile docker language server found installed not found command path",
                        v -> config.getSettings().setDockerfileLspEnabled(v),
                        () -> config.getSettings().isDockerfileLspEnabled(),
                        v -> config.getSettings().setDockerfileLspCommand(v),
                        () -> config.getSettings().getDockerfileLspCommand()),
                new LspServerUi(
                        "sql",
                        com.editora.lsp.LspServerRegistry.DEFAULT_SQL_COMMAND,
                        "settings.lsp.enableSql",
                        "settings.lsp.sqlCommand",
                        "lsp sql language server found installed not found command path executable",
                        v -> config.getSettings().setSqlLspEnabled(v),
                        () -> config.getSettings().isSqlLspEnabled(),
                        v -> config.getSettings().setSqlLspCommand(v),
                        () -> config.getSettings().getSqlLspCommand()),
                new LspServerUi(
                        "terraform",
                        com.editora.lsp.LspServerRegistry.DEFAULT_TERRAFORM_COMMAND,
                        "settings.lsp.enableTerraform",
                        "settings.lsp.terraformCommand",
                        "lsp terraform hcl terraform-ls language server found installed not found command path",
                        v -> config.getSettings().setTerraformLspEnabled(v),
                        () -> config.getSettings().isTerraformLspEnabled(),
                        v -> config.getSettings().setTerraformLspCommand(v),
                        () -> config.getSettings().getTerraformLspCommand()),
                new LspServerUi(
                        "toml",
                        com.editora.lsp.LspServerRegistry.DEFAULT_TOML_COMMAND,
                        "settings.lsp.enableToml",
                        "settings.lsp.tomlCommand",
                        "lsp toml taplo language server found installed not found command path executable",
                        v -> config.getSettings().setTomlLspEnabled(v),
                        () -> config.getSettings().isTomlLspEnabled(),
                        v -> config.getSettings().setTomlLspCommand(v),
                        () -> config.getSettings().getTomlLspCommand()),
                new LspServerUi(
                        "csharp",
                        com.editora.lsp.LspServerRegistry.DEFAULT_CSHARP_COMMAND,
                        "settings.lsp.enableCsharp",
                        "settings.lsp.csharpCommand",
                        "lsp c# csharp csharp-ls dotnet language server found installed not found command path",
                        v -> config.getSettings().setCsharpLspEnabled(v),
                        () -> config.getSettings().isCsharpLspEnabled(),
                        v -> config.getSettings().setCsharpLspCommand(v),
                        () -> config.getSettings().getCsharpLspCommand()),
                new LspServerUi(
                        "typst",
                        com.editora.lsp.LspServerRegistry.DEFAULT_TYPST_COMMAND,
                        "settings.lsp.enableTypst",
                        "settings.lsp.typstCommand",
                        "lsp typst tinymist language server found installed not found command path executable",
                        v -> config.getSettings().setTypstLspEnabled(v),
                        () -> config.getSettings().isTypstLspEnabled(),
                        v -> config.getSettings().setTypstLspCommand(v),
                        () -> config.getSettings().getTypstLspCommand()),
                new LspServerUi(
                        "astro",
                        com.editora.lsp.LspServerRegistry.DEFAULT_ASTRO_COMMAND,
                        "settings.lsp.enableAstro",
                        "settings.lsp.astroCommand",
                        "lsp astro astro-ls web components language server found installed command path",
                        v -> config.getSettings().setAstroLspEnabled(v),
                        () -> config.getSettings().isAstroLspEnabled(),
                        v -> config.getSettings().setAstroLspCommand(v),
                        () -> config.getSettings().getAstroLspCommand()));
    }

    /** Per LSP server card: what this window's project file overrides (hidden when it overrides nothing). */
    private final Map<String, Label> lspProjectLabels = new java.util.HashMap<>();

    private java.util.function.Function<String, @Nullable String> lspProjectNotes = id -> null;

    /** Injected by MainController: the project-override line for a server id, or null. */
    void setLspProjectNotes(java.util.function.Function<String, @Nullable String> notes) {
        this.lspProjectNotes = notes;
    }

    void refreshLspStatus() {
        refreshBashToolStatus();
        lspProjectLabels.forEach((id, label) -> {
            String text = lspProjectNotes.apply(id);
            label.setText(text == null ? "" : text);
            label.setVisible(text != null);
        });
        if (lspManager == null || lspStatusLabels.isEmpty()) {
            return;
        }
        TypeScriptSdkStatus.refresh(
                typeScriptSdkStatus,
                lspArgv(com.editora.lsp.TypeScriptServer.SERVER_ID),
                config.getConfigDir(),
                lspCheck.isSelected());
        // Probe the commands this page shows, without configuring the manager. This window must not
        // (re)configure it: doing so with the global commands replaced a trusted project's override and
        // shut its running server down every time Settings was merely opened.
        java.util.Map<String, String> shown =
                LspServerCommands.launchCommandsForAllServers(config.getSettings(), config.getConfigDir());
        for (LspServerUi srv : lspServerUis()) {
            ToolStatusRow status = lspStatusLabels.get(srv.id());
            if (status == null) {
                continue;
            }
            List<String> argv = lspArgv(srv.id());
            status.tool(lspToolName(srv.id(), argv));
            status.checking();
            lspManager.detectCommand(
                    srv.id(),
                    shown.getOrDefault(srv.id(), ""),
                    found -> { // blank = default
                        if (!found && MAVEN_POM.equals(srv.id()) && mavenInstallIncomplete(argv)) {
                            // The folder is there but lacks jars (the upstream bundle without the ones it leaves out,
                            // or
                            // a wiped install): not "not found" — "incomplete", with Install… to repair (Maven review
                            // C3).
                            status.showProblem(
                                    tr("settings.lsp.mavenIncomplete", lspToolName(srv.id(), argv)),
                                    lspCheck.isSelected());
                            return;
                        }
                        String outdated = found ? outdatedPin(srv.id(), argv, config.getConfigDir()) : null;
                        if (outdated != null) { // "Installed" over what Doctor offers to update (re-review D8)
                            status.showProblem(
                                    tr("settings.lsp.pinOutdated", lspToolName(srv.id(), argv), outdated),
                                    lspCheck.isSelected());
                            return;
                        }
                        String unknown = found ? unknownPin(srv.id(), argv, config.getConfigDir()) : null;
                        if (unknown != null) { // an install from before the pin: Doctor says Update too (A7)
                            String name = lspToolName(srv.id(), argv); // a warning there too, so amber here (A26)
                            status.showWarning(
                                    tr("settings.lsp.pinUnknownShort", name, unknown),
                                    tr("settings.lsp.pinUnknown", name, unknown));
                            return;
                        }
                        if (found && xmlRefused(srv.id(), argv)) { // not started at all: not "found" (review 4, A32)
                            status.showProblem(
                                    tr("settings.lsp.xmlLoadsMaven", lspToolName(srv.id(), argv)),
                                    lspCheck.isSelected());
                            return;
                        }
                        String crash = found && w.projectLsp != null ? w.projectLsp.serverCrash(srv.id()) : null;
                        if (crash != null) { // found, but it exits before it is ready: not "Installed" (XML A4)
                            String name = lspToolName(srv.id(), argv);
                            status.showProblem(tr("settings.lsp.crashing", name, crash), lspCheck.isSelected());
                            return;
                        }
                        String where = found && !MAVEN_POM.equals(srv.id()) ? ToolStatusRow.pathOf(argv) : "";
                        where = com.editora.install.PinnedVersions.withVersion(
                                srv.id(), argv, config.getConfigDir(), where);
                        String fallback = found && "xml".equals(srv.id())
                                ? com.editora.install.XmlServerInstall.javaFallbackVersion(argv, config.getConfigDir())
                                : null;
                        if (fallback != null) { // the pom bundle's Java lemminx, not the pinned native build (A32)
                            where = tr("lsp.xml.javaFallback", fallback) + " — " + where;
                        }
                        status.show(found, where, lspCheck.isSelected());
                    });
        }
    }

    /**
     * The pinned release, as shown, when {@code argv} runs Editora's own copy of {@code serverId}'s server and that
     * copy is older than it; else null. A copy from elsewhere is not one Install… replaces. File lookups only.
     */
    static @Nullable String outdatedPin(String serverId, List<String> argv, @Nullable Path configDir) {
        return pinIs(serverId, argv, configDir, com.editora.install.PinnedVersions.Freshness.OUTDATED);
    }

    /**
     * The pinned release, as shown, when {@code argv} runs Editora's own copy of {@code serverId}'s server and that
     * copy was installed before Editora recorded versions, so which release it is cannot be told — what Doctor offers
     * to Update (XML/pom review 2, A7); else null.
     */
    static @Nullable String unknownPin(String serverId, List<String> argv, @Nullable Path configDir) {
        return pinIs(serverId, argv, configDir, com.editora.install.PinnedVersions.Freshness.UNKNOWN);
    }

    private static @Nullable String pinIs(
            String serverId,
            List<String> argv,
            @Nullable Path configDir,
            com.editora.install.PinnedVersions.Freshness freshness) {
        var pin = com.editora.install.PinnedVersions.forServer(serverId).orElse(null);
        if (pin == null || configDir == null || argv.isEmpty()) {
            return null;
        }
        List<String> tokens = new java.util.ArrayList<>(argv); // a classpath token names the Maven server's folder
        tokens.add(com.editora.doctor.DoctorProbes.resolvedPath(argv));
        boolean editoras = com.editora.install.PinnedVersions.anyInside(tokens, configDir.resolve(pin.destSubpath()));
        return editoras && com.editora.install.PinnedVersions.installedFreshness(pin, configDir) == freshness
                ? pin.label()
                : null;
    }

    /**
     * Whether Editora will not start the XML command {@code argv} — it loads lemminx-maven — or this window's project
     * command was refused for that (review 4, A32). File lookups only.
     */
    private boolean xmlRefused(String serverId, List<String> argv) {
        if (!"xml".equals(serverId)) {
            return false;
        }
        LspCoordinator.ServerTrouble trouble = w.projectLsp == null ? null : w.projectLsp.serverTrouble(serverId);
        return (trouble != null && trouble.refused())
                || com.editora.lsp.LemminxMavenGuard.loadsLemminxMaven(argv, true);
    }

    /** Whether the Maven server's {@code java -cp "<dir>/*"} folder exists but lacks required jars. Pure (file lookups). */
    static boolean mavenInstallIncomplete(List<String> argv) {
        return com.editora.install.MavenServerInstall.classpathDir(argv)
                .filter(java.nio.file.Files::isDirectory)
                .map(dir -> !com.editora.install.MavenServerInstall.complete(dir))
                .orElse(false);
    }

    private static final String MAVEN_POM = com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID;

    /** The argv the LSP page shows for {@code serverId}: its global command, blank ⇒ the default. */
    private List<String> lspArgv(String serverId) {
        return com.editora.lsp.LspServerRegistry.commandFor(
                serverId, LspServerCommands.launchCommandsForAllServers(config.getSettings(), config.getConfigDir()));
    }

    /** The program a server runs, as its pill names it: the display-name table first (the pom.xml server runs
     *  through java; the native XML download is platform-suffixed — Maven review C14), else the command's name. */
    private static String lspToolName(String serverId, List<String> argv) {
        String named = com.editora.lsp.LspServerRegistry.displayNameFor(serverId, argv); // `node …` names HTML (A15)
        return named != null ? named : ToolStatusRow.toolName(argv, serverId);
    }

    void load(Settings settings) {
        lspCheck.setSelected(settings.isLspSupport());
        for (LspServerUi srv : lspServerUis()) {
            CheckBox enable = lspEnableChecks.get(srv.id());
            if (enable != null) {
                enable.setSelected(srv.getEnabled().getAsBoolean());
            }
            TextField field = lspCommandFields.get(srv.id());
            if (field != null) {
                field.setText(srv.getCommand().get());
            }
        }
        refreshLspStatus();
    }

    void syncLspCheck() {
        boolean prev = w.loading;
        w.loading = true;
        try {
            lspCheck.setSelected(config.getSettings().isLspSupport());
        } finally {
            w.loading = prev;
        }
        w.refreshToolWindowRows(); // refresh the Problems and Debug row gates
        refreshLspStatus();
    }
}
