package com.editora.ui;

import java.util.List;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.ui.SettingsWindow.Card;
import com.editora.ui.SettingsWindow.Category;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → Debugging: the master switch, where the debugged program runs, and one card per debug adapter (Java,
 * Python, JavaScript, Go, Rust) with its enable switch, command and detection status. Also owns the Node.js path field the Build
 * Tools page shows, which is loaded with these controls. {@link SettingsWindow} builds the page with the others.
 */
final class SettingsDebugPage {

    private final SettingsWindow w;
    private final ConfigManager config;
    private final com.editora.dap.DapManager dapManager;

    SettingsDebugPage(SettingsWindow w, ConfigManager config, com.editora.dap.DapManager dapManager) {
        this.w = w;
        this.config = config;
        this.dapManager = dapManager;
    }

    @Initializer
    void buildControls() {
        debugCheck = new CheckBox(tr("settings.enableDebug"));
        debugCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDebugSupport(now);
            w.apply();
            w.refreshToolWindowRows(); // reflect on the Tool Windows page's Debug row
            refreshDebugStatus();
        });
        debugProgramConsoleCheck = new CheckBox(tr("settings.debug.programConsole"));
        debugProgramConsoleCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDebugProgramConsole(now);
            w.apply();
        });
        debugProgramInTerminalCheck = new CheckBox(tr("settings.debug.programInTerminal"));
        debugProgramInTerminalCheck
                .disableProperty()
                .bind(debugProgramConsoleCheck.selectedProperty().not());
        debugProgramInTerminalCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDebugProgramInTerminal(now);
            w.apply();
        });
        debugHotCodeReplaceCombo = new ComboBox<>();
        debugHotCodeReplaceCombo.getItems().setAll("auto", "manual", "off");
        debugHotCodeReplaceCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String key) {
                return key == null ? "" : tr("settings.debug.hotCodeReplace." + key);
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        debugHotCodeReplaceCombo.setMinWidth(170);
        debugHotCodeReplaceCombo.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null) {
                return;
            }
            config.getSettings().setDebugHotCodeReplace(now);
            w.apply();
        });
        debugStepFiltersCheck = new CheckBox(tr("settings.debug.stepFilters"));
        debugStepFiltersCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDebugStepFilters(now);
            w.apply();
        });
        debugShowStaticsCheck = new CheckBox(tr("settings.debug.showStatics"));
        debugShowStaticsCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDebugShowStatics(now);
            w.apply();
        });
        debugShowToStringCheck = new CheckBox(tr("settings.debug.showToString"));
        debugShowToStringCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDebugShowToString(now);
            w.apply();
        });
        debugSuspendAllThreadsCheck = new CheckBox(tr("settings.debug.suspendAllThreads"));
        debugSuspendAllThreadsCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setDebugSuspendAllThreads(now);
            w.apply();
        });
        debugCppDebuggerCombo = new ComboBox<>();
        debugCppDebuggerCombo.getItems().setAll("codelldb", "gdb");
        debugCppDebuggerCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(String key) {
                return key == null ? "" : tr("settings.debug.cppDebugger." + key);
            }

            @Override
            public String fromString(String label) {
                return label;
            }
        });
        debugCppDebuggerCombo.valueProperty().addListener((obs, was, now) -> {
            if (w.loading || now == null) {
                return;
            }
            config.getSettings().setCppDebugger(now);
            w.apply();
        });
        debugGdbPathField = new TextField();
        debugGdbPathField.setPromptText("gdb");
        w.commitOnEnterOrBlur(debugGdbPathField, () -> config.getSettings().getGdbPath(), now -> {
            config.getSettings().setGdbPath(now.strip());
            w.apply();
        });
        debugCppCheck = new CheckBox(tr("settings.debug.enableCpp"));
        debugCppCheck.selectedProperty().addListener((obs, was, now) -> {
            config.getSettings().setCppDebugEnabled(now);
            w.apply();
            refreshDebugStatus();
        });
        debugExceptionTypesField = new TextField();
        debugExceptionTypesField.setPromptText(tr("settings.debug.exceptionTypes.prompt"));
        w.commitOnEnterOrBlur(
                debugExceptionTypesField, () -> config.getSettings().getDebugExceptionTypes(), now -> {
                    config.getSettings().setDebugExceptionTypes(now);
                    w.apply();
                });
        nodePathField = new TextField();
        nodePathField.setPromptText(tr("settings.debug.nodePath.prompt"));
        w.commitOnEnterOrBlur(nodePathField, () -> config.getSettings().getNodePath(), now -> {
            config.getSettings().setNodePath(now.strip());
            w.apply();
            refreshDebugStatus();
            w.webPage.refreshHttpNodeStatus(); // the Web page's Node.js row follows this field
        });
        for (DebugAdapterUi dbg : debugAdapterUis()) {
            if (dbg.setEnabled() instanceof java.util.function.Consumer<Boolean> setEnabled
                    && dbg.enableLabelKey() instanceof String labelKey) { // the two are set together
                CheckBox enable = new CheckBox(tr(labelKey));
                enable.selectedProperty().addListener((obs, was, now) -> {
                    setEnabled.accept(now);
                    w.apply();
                    refreshDebugStatus();
                });
                debugEnableChecks.put(dbg.id(), enable);
            }
            TextField field = new TextField();
            field.setPromptText(dbg.commandPrompt());
            String debugKey = "debug." + dbg.id();
            w.commitOnEnterOrBlur(
                    field, () -> w.scopeUi.shown(debugKey, dbg.getCommand().get()), now -> {
                        if (!w.scopeUi.write(debugKey, now)) {
                            dbg.setCommand().accept(now);
                        }
                        w.apply();
                        refreshDebugStatus();
                    });
            debugCommandFields.put(dbg.id(), field);
        }
    }

    private CheckBox debugCheck;
    private CheckBox debugProgramConsoleCheck;
    private CheckBox debugProgramInTerminalCheck;
    /** Java hot code replace: auto / manual / off ({@code Settings.debugHotCodeReplace}). */
    private ComboBox<String> debugHotCodeReplaceCombo;

    private CheckBox debugStepFiltersCheck;
    private CheckBox debugShowStaticsCheck;
    private CheckBox debugShowToStringCheck;
    private CheckBox debugSuspendAllThreadsCheck;
    private TextField debugExceptionTypesField;
    /** C and C++ debugging, on the Rust card: they run on the same CodeLLDB. */
    private CheckBox debugCppCheck;
    /** C and C++'s debugger: CodeLLDB or GDB. */
    private ComboBox<String> debugCppDebuggerCombo;
    /** GDB's path, for its DAP mode. */
    private TextField debugGdbPathField;
    /** The Node.js for Run, Debug, npm scripts and code intelligence (Settings.nodePath; blank = automatic). */
    TextField nodePathField;
    /** Per-language debug-adapter controls, keyed by language id (java/python/javascript). */
    private final java.util.Map<String, CheckBox> debugEnableChecks = new java.util.LinkedHashMap<>();

    final java.util.Map<String, TextField> debugCommandFields = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, ToolStatusRow> debugStatusLabels = new java.util.LinkedHashMap<>();

    /** The Debugging page's line saying Java debugging needs LSP; empty while it is on. */
    private @Nullable Label javaDebugLspNote;

    VBox build() {
        VBox p = w.page(tr("settings.cat.debug"), tr("settings.debug.subtitle"));
        Card master = w.card(p, null);
        w.checkRow(
                master,
                Category.DEBUG,
                debugCheck,
                tr("settings.debug.note"),
                "debug dap breakpoint step variables enable install plugin vscode mason jdtls debugpy js-debug");
        // The Python interpreter is on the LSP page, editable with debugging off (Python review 2, C101).
        Hyperlink pythonLink = new Hyperlink(tr("settings.debug.pythonInterpreterLink"));
        pythonLink.setWrapText(true);
        pythonLink.setOnAction(e -> w.sidebar.getSelectionModel().select(Category.LSP));
        w.row(p, Category.DEBUG, null, pythonLink, "debug python interpreter venv path lsp language servers");
        // One card per adapter, titled with the language (Java has no per-adapter enable — it rides
        // the Java LSP server; see DebugCoordinator.debugEffectiveFor).
        w.dependsOn(p, debugCheck);
        // Where the debugged program runs: Java, Python and JavaScript alike, so not on the Java card (E108).
        Card programs = w.card(p, tr("settings.debug.programs"));
        SettingsDependents.bind(debugCheck.selectedProperty(), programs.box());
        for (CheckBox check : List.of(debugProgramConsoleCheck, debugProgramInTerminalCheck)) {
            String key = check == debugProgramConsoleCheck ? "programConsole" : "programInTerminal";
            String words = "debug python javascript program console input stdin terminal tab tty screen keys";
            w.checkRow(programs, Category.DEBUG, check, tr("settings.debug." + key + ".hint"), words);
        }
        for (DebugAdapterUi dbg : debugAdapterUis()) {
            Card c = w.card(p, tr(dbg.sectionKey()));
            SettingsDependents.bind(debugCheck.selectedProperty(), c.box());
            CheckBox enable = debugEnableChecks.get(dbg.id());
            if (enable != null) {
                w.checkRow(c, Category.DEBUG, enable, null, dbg.keywords());
            }
            if ("java".equals(dbg.id())) {
                // Java debugging runs through the Java language server: the card says when that is off (review
                // C6); its Install (below) fetches jdtls and java-debug together, as the LSP page's Java row does.
                javaDebugLspNote = SettingsWindow.note("");
                javaDebugLspNote.setWrapText(true);
                javaDebugLspNote.setMaxWidth(440);
                javaDebugLspNote.getStyleClass().add("settings-row"); // own row: a wrapper left a gap (C209)
                w.cardRow(c, Category.DEBUG, javaDebugLspNote, "debug java lsp language server jdtls off");
                w.controlRow(
                        c,
                        Category.DEBUG,
                        tr("settings.debug.hotCodeReplace"),
                        tr("settings.debug.hotCodeReplace.hint"),
                        debugHotCodeReplaceCombo,
                        "debug java hot code replace hotswap apply code changes reload save running");
                w.checkRow(
                        c,
                        Category.DEBUG,
                        debugStepFiltersCheck,
                        tr("settings.debug.stepFilters.hint"),
                        "debug java step into filter skip jdk library synthetic");
                w.checkRow(
                        c, Category.DEBUG, debugShowStaticsCheck, null, "debug java static fields variables statics");
                w.checkRow(
                        c,
                        Category.DEBUG,
                        debugShowToStringCheck,
                        tr("settings.debug.showToString.hint"),
                        "debug java tostring object view values variables slow");
                w.checkRow(
                        c,
                        Category.DEBUG,
                        debugSuspendAllThreadsCheck,
                        tr("settings.debug.suspendAllThreads.hint"),
                        "debug java suspend all threads policy breakpoint stop every thread");
                debugExceptionTypesField.setPrefWidth(SettingsWindow.PATH_FIELD_WIDTH);
                w.controlRow(
                        c,
                        Category.DEBUG,
                        tr("settings.debug.exceptionTypes"),
                        tr("settings.debug.exceptionTypes.hint"),
                        debugExceptionTypesField,
                        "debug java exception breakpoint class type filter caught uncaught");
            }
            if ("rust".equals(dbg.id())) {
                w.checkRow(
                        c,
                        Category.DEBUG,
                        debugCppCheck,
                        tr("settings.debug.enableCpp.hint"),
                        "debug c cpp c++ cmake make gcc clang codelldb native");
                w.controlRow(
                        c,
                        Category.DEBUG,
                        tr("settings.debug.cppDebugger"),
                        tr("settings.debug.cppDebugger.hint"),
                        debugCppDebuggerCombo,
                        "debug c cpp c++ gdb lldb codelldb debugger dap");
                debugGdbPathField.setPrefWidth(SettingsWindow.PATH_FIELD_WIDTH);
                w.controlRow(
                        c,
                        Category.DEBUG,
                        tr("settings.debug.gdbPath"),
                        null,
                        debugGdbPathField,
                        "debug gdb path dap c cpp");
            }
            if ("javascript".equals(dbg.id())) {
                // The one Node.js setting lives on Build Tools, editable with debugging off (review E102).
                Hyperlink nodeLink = new Hyperlink(tr("settings.debug.nodePathLink"));
                nodeLink.setWrapText(true);
                nodeLink.setOnAction(e -> w.sidebar.getSelectionModel().select(Category.BUILD_TOOLS));
                w.cardRow(c, Category.DEBUG, nodeLink, "node nodejs node.js runtime path debug build tools");
            }
            // Install fetches what this language's support lacks — the adapter included — keyed on the adapter.
            DebugAdapterSettings.Binding binding = java.util.Objects.requireNonNull(DebugAdapterSettings.of(dbg.id()));
            ToolStatusRow status =
                    new ToolStatusRow(binding.toolId()).install(() -> w.installSupport(binding.installId())); // D111
            debugStatusLabels.put(dbg.id(), status);
            w.installButtons.put("debug:" + dbg.id(), status.installButton());
            if ("python".equals(dbg.id())) { // its field is on the LSP page's Python card
                w.controlRow(c, Category.DEBUG, tr("settings.git.detected"), null, status.node(), dbg.keywords());
                continue;
            }
            String title = tr(dbg.commandLabelKey());
            w.toolRows(c, Category.DEBUG, title, null, debugCommandFields.get(dbg.id()), null, status, dbg.keywords());
        }
        return p;
    }

    /** A per-language debug-adapter Settings row group (data-driven, mirroring {@link LspServerUi}).
     *  {@code setEnabled}/{@code getEnabled} are null for java (it has no separate enable flag — it is
     *  gated by the java LSP server). {@code detect} runs the availability probe and reports found. */
    private record DebugAdapterUi(
            String id,
            String sectionKey,
            @Nullable String enableLabelKey,
            String commandLabelKey,
            String commandPrompt,
            String keywords,
            java.util.function.@Nullable Consumer<Boolean> setEnabled,
            java.util.function.@Nullable BooleanSupplier getEnabled,
            java.util.function.Consumer<String> setCommand,
            java.util.function.Supplier<String> getCommand,
            java.util.function.Consumer<java.util.function.BiConsumer<Boolean, String>> detect) {}

    /** The debug adapters with a card of their own (java/python/javascript/go/rust), in display order. */
    private java.util.List<DebugAdapterUi> debugAdapterUis() {
        return java.util.List.of(
                new DebugAdapterUi(
                        "java",
                        "settings.debug.java",
                        null,
                        "settings.debug.pluginPath",
                        "com.microsoft.java.debug.plugin-*.jar",
                        "debug java jdtls java-debug plugin jar path found",
                        null,
                        null,
                        v -> config.getSettings().setJavaDebugPluginPath(v),
                        () -> config.getSettings().getJavaDebugPluginPath(),
                        cb -> { // lists the VS Code / mason folders: off the FX thread, like the other probes
                            String configured = config.getSettings().getJavaDebugPluginPath();
                            Thread.ofVirtual().name("settings-java-debug-probe").start(() -> {
                                String jar = com.editora.dap.DebugAdapterLocator.locate(
                                                configured, java.nio.file.Path.of(System.getProperty("user.home", "")))
                                        .map(Object::toString)
                                        .orElse(null);
                                // A jdtls that ships java-debug has no jar to find (review A7).
                                boolean served = jar == null && dapManager != null && dapManager.isJavaDebugInstalled();
                                javafx.application.Platform.runLater(() -> cb.accept(jar != null || served, jar));
                            });
                        }),
                new DebugAdapterUi(
                        "python",
                        "settings.debug.python",
                        "settings.debug.enablePython",
                        "settings.debug.pythonCommand",
                        com.editora.dap.DapServerRegistry.DEFAULT_PYTHON_INTERPRETER,
                        "debug python debugpy interpreter command path found",
                        v -> config.getSettings().setPythonDebugEnabled(v),
                        () -> config.getSettings().isPythonDebugEnabled(),
                        v -> config.getSettings().setPythonDebugCommand(v),
                        () -> config.getSettings().getPythonDebugCommand(),
                        cb -> {
                            if (dapManager != null) {
                                // installed, whether or not switched on; the detail is the interpreter that has it
                                dapManager.detectPythonInstalled(found -> cb.accept(
                                        found,
                                        ToolStatusRow.pathOf(com.editora.dap.DapServerRegistry.interpreterArgv(
                                                "python", config.getSettings().getPythonDebugCommand()))));
                            } else {
                                cb.accept(false, "");
                            }
                        }),
                new DebugAdapterUi(
                        "javascript",
                        "settings.debug.javascript",
                        "settings.debug.enableJs",
                        "settings.debug.jsPath",
                        "dapDebugServer.js",
                        "debug javascript node js-debug vscode dapDebugServer path found",
                        v -> config.getSettings().setJsDebugEnabled(v),
                        () -> config.getSettings().isJsDebugEnabled(),
                        v -> config.getSettings().setJsDebugPath(v),
                        () -> config.getSettings().getJsDebugPath(),
                        cb -> {
                            if (dapManager != null) {
                                // installed, whether or not switched on (review D13): its version and the Node.js
                                dapManager.detectJsInstalled(js -> cb.accept(
                                        js.server() != null && js.nodeVersion() != null,
                                        ToolStatusRow.jsDebugDetail(js)));
                            } else {
                                cb.accept(false, "");
                            }
                        }),
                new DebugAdapterUi(
                        "go",
                        "settings.debug.go",
                        "settings.debug.enableGo",
                        "settings.debug.goPath",
                        "dlv",
                        "debug go golang delve dlv path found install",
                        v -> config.getSettings().setGoDebugEnabled(v),
                        () -> config.getSettings().isGoDebugEnabled(),
                        v -> config.getSettings().setGoDebugPath(v),
                        () -> config.getSettings().getGoDebugPath(),
                        cb -> { // found, whether or not switched on: the PATH lookup is off the FX thread
                            String configured = config.getSettings().getGoDebugPath();
                            Thread.ofVirtual().name("settings-delve-probe").start(() -> {
                                String dlv = com.editora.dap.DapManager.locateExternal(
                                        com.editora.dap.DebugLanguages.GO, configured);
                                javafx.application.Platform.runLater(
                                        () -> cb.accept(dlv != null, dlv == null ? "" : dlv));
                            });
                        }),
                new DebugAdapterUi(
                        "rust",
                        "settings.debug.rust",
                        "settings.debug.enableRust",
                        "settings.debug.rustPath",
                        "codelldb",
                        "debug rust cargo codelldb lldb path found install",
                        v -> config.getSettings().setRustDebugEnabled(v),
                        () -> config.getSettings().isRustDebugEnabled(),
                        v -> config.getSettings().setRustDebugPath(v),
                        () -> config.getSettings().getRustDebugPath(),
                        cb -> { // found, whether or not switched on: it lists folders, off the FX thread
                            String configured = config.getSettings().getRustDebugPath();
                            Thread.ofVirtual().name("settings-codelldb-probe").start(() -> {
                                String found = com.editora.dap.DapManager.locateExternal(
                                        com.editora.dap.DebugLanguages.RUST, configured);
                                javafx.application.Platform.runLater(
                                        () -> cb.accept(found != null, found == null ? "" : found));
                            });
                        }));
    }

    /** Re-checks each debug adapter's availability (java plugin jar / debugpy / js-debug+node) and colors
     *  its status label green/red, like the LSP/Mermaid status. java locate is cheap; python/js probe a
     *  subprocess off-thread and call back on the FX thread. */
    void refreshDebugStatus() {
        for (DebugAdapterUi dbg : debugAdapterUis()) {
            ToolStatusRow status = debugStatusLabels.get(dbg.id());
            if (status != null) {
                status.checking();
                dbg.detect().accept((found, detail) -> status.show(found, detail, debugCheck.isSelected()));
            }
        }
        if (javaDebugLspNote != null) {
            String why = !config.getSettings().isLspSupport()
                    ? tr("status.debug.javaNeedsLsp")
                    : !config.getSettings().isJavaLspEnabled() ? tr("status.debug.javaServerOff") : "";
            javaDebugLspNote.setText(why);
            javaDebugLspNote.setVisible(!why.isEmpty());
            javaDebugLspNote.setManaged(!why.isEmpty());
        }
    }

    void load(Settings settings) {
        debugCheck.setSelected(settings.isDebugSupport());
        debugProgramConsoleCheck.setSelected(settings.isDebugProgramConsole());
        debugProgramInTerminalCheck.setSelected(settings.isDebugProgramInTerminal());
        debugHotCodeReplaceCombo.setValue(settings.getDebugHotCodeReplace());
        debugStepFiltersCheck.setSelected(settings.isDebugStepFilters());
        debugShowStaticsCheck.setSelected(settings.isDebugShowStatics());
        debugShowToStringCheck.setSelected(settings.isDebugShowToString());
        debugSuspendAllThreadsCheck.setSelected(settings.isDebugSuspendAllThreads());
        debugExceptionTypesField.setText(settings.getDebugExceptionTypes());
        debugCppCheck.setSelected(settings.isCppDebugEnabled());
        debugCppDebuggerCombo.setValue(settings.getCppDebugger());
        debugGdbPathField.setText(settings.getGdbPath());
        nodePathField.setText(settings.getNodePath());
        for (DebugAdapterUi dbg : debugAdapterUis()) {
            CheckBox enable = debugEnableChecks.get(dbg.id());
            if (enable != null && dbg.getEnabled() != null) {
                enable.setSelected(dbg.getEnabled().getAsBoolean());
            }
            TextField field = debugCommandFields.get(dbg.id());
            if (field != null) {
                field.setText(dbg.getCommand().get());
            }
        }
        refreshDebugStatus();
    }
}
