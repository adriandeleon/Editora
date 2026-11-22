package com.editora.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import com.editora.build.BuildTool;
import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The palette commands for the settings whose Settings rows had none (SET-06, Pauta 6.2.0): every setting the
 * Settings window shows can be flipped, chosen or typed from the palette too. A command here either changes one
 * setting ({@code view.toggleWordWrapProse}) or opens a picker of the settings of one Settings page, each named
 * as its row and showing its value, and then flips the one picked, lists its choices or asks for its value
 * ({@code terminal.changeSetting}): one command per page keeps the palette from growing by a hundred rows while
 * every row stays reachable. Each writes the same {@link Settings} field as its row, saves, re-applies the
 * settings to every window as the Settings window does, keeps an open Settings window in step, and says what
 * the setting is now. {@code EverySettingHasACommandFxTest} fails on a row whose setting no command writes.
 */
final class SettingCommands {

    private SettingCommands() {}

    /** One setting a command can change: named as its Settings row. */
    sealed interface Item permits Flag, Choice, Value {
        /** The setting's name, as its Settings row says it. */
        String name();

        /** Its value, as the picker shows it beside the name. */
        String shown(Settings s);
    }

    /** An on/off setting: picking it flips it. */
    record Flag(Supplier<String> label, Predicate<Settings> get, BiConsumer<Settings, Boolean> set) implements Item {
        @Override
        public String name() {
            return label.get();
        }

        @Override
        public String shown(Settings s) {
            return tr(get.test(s) ? "common.on" : "common.off");
        }
    }

    /** A setting with a few values: picking it lists them, the one in force selected. */
    record Choice(
            Supplier<String> label,
            Supplier<List<String>> values,
            Function<String, String> valueLabel,
            Function<Settings, String> get,
            BiConsumer<Settings, String> set)
            implements Item {
        @Override
        public String name() {
            return label.get();
        }

        @Override
        public String shown(Settings s) {
            return valueLabel.apply(get.apply(s));
        }
    }

    /**
     * A number or a text: picking it asks for a new value, the current one filled in. A number outside
     * {@code [min, max]} is brought inside it; a text {@code refuse} gives a reason for is not kept, and the
     * reason shows. A {@code secret} is typed into a field of dots and never shown in the picker.
     */
    record Value(
            Supplier<String> label,
            Function<Settings, String> get,
            BiConsumer<Settings, String> set,
            @Nullable NumberRange range,
            Function<String, @Nullable String> refuse,
            boolean secret)
            implements Item {
        @Override
        public String name() {
            return label.get();
        }

        @Override
        public String shown(Settings s) {
            String value = get.apply(s);
            return secret ? (value.isEmpty() ? "" : "••••••") : value;
        }
    }

    /** The values a number may take. */
    record NumberRange(int min, int max) {}

    /** A command and the settings it changes: one is changed at once, several are picked from. */
    record Entry(String id, List<Item> items) {}

    // ---- the items ----

    private static Supplier<String> key(String key) {
        return () -> tr(key);
    }

    /** {@code prefix ▸ the row's title}: for a picker that holds the rows of several cards. */
    private static Supplier<String> under(String prefix, String key) {
        return () -> prefix + " ▸ " + tr(key);
    }

    private static Flag flag(String titleKey, Predicate<Settings> get, BiConsumer<Settings, Boolean> set) {
        return new Flag(key(titleKey), get, set);
    }

    /** A choice whose values are labelled {@code labelPrefix + value}, as a Settings choice row labels them. */
    private static Choice choice(
            Supplier<String> label,
            List<String> values,
            String labelPrefix,
            Function<Settings, String> get,
            BiConsumer<Settings, String> set) {
        return new Choice(label, () -> values, v -> tr(labelPrefix + v), get, set);
    }

    private static Value text(
            Supplier<String> label, Function<Settings, String> get, BiConsumer<Settings, String> set) {
        return new Value(label, get, set, null, v -> null, false);
    }

    private static Value text(
            Supplier<String> label,
            Function<Settings, String> get,
            BiConsumer<Settings, String> set,
            Function<String, @Nullable String> refuse) {
        return new Value(label, get, set, null, refuse, false);
    }

    private static Value number(
            Supplier<String> label, ToIntFunction<Settings> get, int min, int max, BiConsumer<Settings, Integer> set) {
        return new Value(
                label,
                s -> Integer.toString(get.applyAsInt(s)),
                (s, v) -> set.accept(s, Integer.parseInt(v)),
                new NumberRange(min, max),
                v -> null,
                false);
    }

    /** A list kept as one line of names, separated as its Settings row separates them. */
    private static Value list(
            Supplier<String> label,
            Function<Settings, List<String>> get,
            String separator,
            BiConsumer<Settings, List<String>> set) {
        return text(label, s -> String.join(separator, get.apply(s)), (s, v) -> set.accept(s, names(v)));
    }

    /** The names in {@code text}, split at commas and white space. Pure. */
    static List<String> names(String text) {
        return Arrays.stream(text.split("[,;\\s]+")).filter(n -> !n.isBlank()).toList();
    }

    private static List<Item> terminal() {
        return List.of(
                text(key("settings.terminal.shell"), Settings::getTerminalShell, Settings::setTerminalShell),
                flag(
                        "settings.terminal.shellIntegration",
                        Settings::isTerminalShellIntegration,
                        Settings::setTerminalShellIntegration),
                flag("settings.terminal.typeAhead", Settings::isTerminalTypeAhead, Settings::setTerminalTypeAhead),
                flag(
                        "settings.terminal.useExternal",
                        Settings::isTerminalUseExternal,
                        Settings::setTerminalUseExternal),
                new Choice(
                        key("settings.terminal.theme"),
                        () -> {
                            List<String> themes = new ArrayList<>();
                            themes.add(com.editora.terminal.TerminalThemes.FOLLOW_EDITOR);
                            themes.addAll(com.editora.terminal.TerminalThemes.NAMES);
                            return themes;
                        },
                        name -> name.isEmpty() ? tr("settings.terminal.theme.editor") : name,
                        s -> com.editora.terminal.TerminalThemes.normalize(s.getTerminalTheme()),
                        Settings::setTerminalTheme),
                number(
                        key("settings.terminal.fontSize"),
                        Settings::getTerminalFontSize,
                        0,
                        72,
                        Settings::setTerminalFontSize),
                choice(
                        key("settings.terminal.cursor"),
                        List.of("block", "underline", "bar"),
                        "settings.terminal.cursor.",
                        Settings::getTerminalCursor,
                        Settings::setTerminalCursor),
                flag("settings.terminal.bell", Settings::isTerminalBell, Settings::setTerminalBell),
                number(
                        key("settings.terminal.scrollback"),
                        Settings::getTerminalScrollback,
                        0,
                        Settings.TERMINAL_SCROLLBACK_MAX,
                        Settings::setTerminalScrollback),
                list(
                        key("settings.terminal.ideCommands"),
                        Settings::getTerminalIdeCommands,
                        ", ",
                        Settings::setTerminalIdeCommands),
                flag(
                        "settings.terminal.macOptionIsMeta",
                        Settings::isTerminalMacOptionIsMeta,
                        Settings::setTerminalMacOptionIsMeta),
                flag(
                        "settings.terminal.confirmMultilinePaste",
                        Settings::isTerminalConfirmMultilinePaste,
                        Settings::setTerminalConfirmMultilinePaste),
                flag(
                        "settings.terminal.copyOnSelect",
                        Settings::isTerminalCopyOnSelect,
                        Settings::setTerminalCopyOnSelect),
                flag(
                        "settings.terminal.clipboardWrites",
                        Settings::isTerminalClipboardWrites,
                        Settings::setTerminalClipboardWrites),
                flag(
                        "settings.terminal.notifications",
                        Settings::isTerminalNotifications,
                        Settings::setTerminalNotifications),
                flag(
                        "settings.terminal.confirmClose",
                        Settings::isTerminalConfirmClose,
                        Settings::setTerminalConfirmClose));
    }

    private static List<Item> debug() {
        List<String> stops = List.of("0", "50", "200", "1000", "5000");
        List<String> limits = List.of("0", "5", "10", "30", "60", "300");
        List<String> browsers = new ArrayList<>();
        browsers.add("");
        browsers.addAll(com.editora.dap.BrowserLaunch.BROWSER_IDS);
        return List.of(
                flag(
                        "settings.debug.programInTerminal",
                        Settings::isDebugProgramInTerminal,
                        Settings::setDebugProgramInTerminal),
                choice(
                        key("settings.debug.hotCodeReplace"),
                        List.of("auto", "manual", "off"),
                        "settings.debug.hotCodeReplace.",
                        Settings::getDebugHotCodeReplace,
                        Settings::setDebugHotCodeReplace),
                flag("settings.debug.stepFilters", Settings::isDebugStepFilters, Settings::setDebugStepFilters),
                flag("settings.debug.showStatics", Settings::isDebugShowStatics, Settings::setDebugShowStatics),
                flag("settings.debug.showToString", Settings::isDebugShowToString, Settings::setDebugShowToString),
                choice(
                        key("settings.debug.pythonTemplates"),
                        Arrays.stream(com.editora.dap.PythonTemplates.Mode.values())
                                .map(com.editora.dap.PythonTemplates.Mode::setting)
                                .toList(),
                        "settings.debug.pythonTemplates.",
                        s -> com.editora.dap.PythonTemplates.Mode.of(s.getDebugPythonTemplates())
                                .setting(),
                        Settings::setDebugPythonTemplates),
                flag(
                        "settings.debug.pythonSubprocesses",
                        Settings::isDebugPythonSubprocesses,
                        Settings::setDebugPythonSubprocesses),
                flag("settings.debug.stepTiming", Settings::isDebugStepTiming, Settings::setDebugStepTiming),
                flag(
                        "settings.debug.suspendAllThreads",
                        Settings::isDebugSuspendAllThreads,
                        Settings::setDebugSuspendAllThreads),
                choice(
                        key("settings.debug.cppDebugger"),
                        List.of("codelldb", "gdb"),
                        "settings.debug.cppDebugger.",
                        Settings::getCppDebugger,
                        Settings::setCppDebugger),
                new Choice(
                        key("settings.debug.browser"),
                        () -> browsers,
                        id -> id.isEmpty() ? tr("settings.debug.browser.auto") : com.editora.web.Browsers.knownName(id),
                        s -> s.getDebugBrowser() == null ? "" : s.getDebugBrowser(),
                        Settings::setDebugBrowser),
                new Choice(
                        key("settings.debug.historyStops"),
                        () -> stops,
                        n -> n.equals("0") ? tr("settings.debug.historyStops.off") : n,
                        s -> Integer.toString(s.getDebugHistoryStops()),
                        (s, n) -> s.setDebugHistoryStops(Integer.parseInt(n))),
                flag(
                        "settings.debug.goHideRuntime",
                        Settings::isDebugGoHideRuntimeGoroutines,
                        Settings::setDebugGoHideRuntimeGoroutines),
                text(key("settings.debug.goLabels"), Settings::getDebugGoPprofLabels, Settings::setDebugGoPprofLabels),
                text(
                        key("settings.debug.goFilter"),
                        Settings::getDebugGoGoroutineFilter,
                        Settings::setDebugGoGoroutineFilter),
                new Choice(
                        key("settings.debug.evaluationTimeLimit"),
                        () -> limits,
                        n -> n.equals("0")
                                ? tr("settings.debug.evaluationTimeLimit.off")
                                : tr("settings.debug.evaluationTimeLimit.seconds", Integer.parseInt(n)),
                        s -> Integer.toString(s.getDebugEvaluationTimeLimit()),
                        (s, n) -> s.setDebugEvaluationTimeLimit(Integer.parseInt(n))),
                text(key("settings.debug.gdbPath"), Settings::getGdbPath, (s, v) -> s.setGdbPath(v.strip())),
                text(
                        key("settings.debug.flutterPath"),
                        Settings::getFlutterDebugPath,
                        (s, v) -> s.setFlutterDebugPath(v.strip())),
                text(key("settings.debug.flutterDevice"), Settings::getFlutterDevice, Settings::setFlutterDevice),
                text(
                        key("settings.debug.exceptionTypes"),
                        Settings::getDebugExceptionTypes,
                        Settings::setDebugExceptionTypes));
    }

    private static List<Item> languages() {
        String bash = "Bash";
        String xml = "XML";
        String html = "HTML";
        String js = "JavaScript";
        List<Item> items = new ArrayList<>();
        items.add(new Flag(
                under(bash, "settings.lsp.bash.shellcheckEnabled"),
                Settings::isBashShellcheckEnabled,
                Settings::setBashShellcheckEnabled));
        items.add(text(
                under(bash, "settings.lsp.bash.shellcheckPath"),
                Settings::getBashShellcheckPath,
                Settings::setBashShellcheckPath));
        items.add(text(
                under(bash, "settings.lsp.bash.shellcheckArguments"),
                Settings::getBashShellcheckArguments,
                Settings::setBashShellcheckArguments));
        items.add(new Flag(
                under(bash, "settings.lsp.bash.sourceErrorDiagnostics"),
                Settings::isBashSourceErrorDiagnostics,
                Settings::setBashSourceErrorDiagnostics));
        items.add(new Flag(
                under(bash, "settings.lsp.bash.shfmtEnabled"),
                Settings::isBashShfmtEnabled,
                Settings::setBashShfmtEnabled));
        items.add(text(
                under(bash, "settings.lsp.bash.shfmtPath"), Settings::getBashShfmtPath, Settings::setBashShfmtPath));
        items.add(choice(
                under(bash, "settings.lsp.bash.shfmtDialect"),
                Settings.BASH_SHFMT_DIALECTS,
                "settings.lsp.bash.shfmtDialect.",
                Settings::getBashShfmtDialect,
                Settings::setBashShfmtDialect));
        items.add(new Flag(
                under(bash, "settings.lsp.bash.shfmtCaseIndent"),
                Settings::isBashShfmtCaseIndent,
                Settings::setBashShfmtCaseIndent));
        items.add(new Flag(
                under(bash, "settings.lsp.bash.shfmtBinaryNextLine"),
                Settings::isBashShfmtBinaryNextLine,
                Settings::setBashShfmtBinaryNextLine));
        items.add(new Flag(
                under(bash, "settings.lsp.bash.shfmtFuncNextLine"),
                Settings::isBashShfmtFuncNextLine,
                Settings::setBashShfmtFuncNextLine));
        items.add(new Flag(
                under(bash, "settings.lsp.bash.shfmtSpaceRedirects"),
                Settings::isBashShfmtSpaceRedirects,
                Settings::setBashShfmtSpaceRedirects));
        items.add(new Flag(
                under(bash, "settings.lsp.bash.shfmtSimplify"),
                Settings::isBashShfmtSimplify,
                Settings::setBashShfmtSimplify));
        items.add(new Flag(
                under(bash, "settings.lsp.bash.shfmtKeepPadding"),
                Settings::isBashShfmtKeepPadding,
                Settings::setBashShfmtKeepPadding));
        items.add(new Flag(
                under(bash, "settings.lsp.bash.shfmtIgnoreEditorconfig"),
                Settings::isBashShfmtIgnoreEditorconfig,
                Settings::setBashShfmtIgnoreEditorconfig));
        items.add(number(
                under(bash, "settings.lsp.bash.backgroundAnalysisMaxFiles"),
                Settings::getBashBackgroundAnalysisMaxFiles,
                0,
                Settings.BASH_BACKGROUND_ANALYSIS_MAX,
                Settings::setBashBackgroundAnalysisMaxFiles));
        items.add(new Flag(
                under(bash, "settings.lsp.bash.includeAllWorkspaceSymbols"),
                Settings::isBashIncludeAllWorkspaceSymbols,
                Settings::setBashIncludeAllWorkspaceSymbols));
        items.add(text(
                under(bash, "settings.lsp.bash.explainshellEndpoint"),
                Settings::getBashExplainshellEndpoint,
                Settings::setBashExplainshellEndpoint,
                v -> com.editora.lsp.BashIdeConfig.explainshellAllowed(v)
                        ? null
                        : tr("settings.lsp.bash.explainshellEndpoint.refused")));
        items.add(choice(
                under(xml, "settings.lsp.xml.schemaDownloads"),
                Settings.XML_SCHEMA_DOWNLOADS,
                "settings.lsp.xml.schemaDownloads.",
                Settings::getXmlSchemaDownloads,
                Settings::setXmlSchemaDownloads));
        items.add(choice(
                under(xml, "settings.lsp.xml.noGrammar"),
                Settings.XML_NO_GRAMMAR,
                "settings.lsp.xml.noGrammar.",
                Settings::getXmlNoGrammar,
                Settings::setXmlNoGrammar));
        items.add(text(
                under(xml, "settings.lsp.xml.catalogs"),
                s -> String.join("; ", s.getXmlCatalogs()),
                (s, v) -> s.setXmlCatalogs(com.editora.lsp.XmlServerSettings.parseCatalogs(v))));
        items.add(text(
                under(xml, "settings.lsp.xml.fileAssociations"),
                s -> com.editora.lsp.XmlServerSettings.formatAssociations(s.getXmlFileAssociations()),
                (s, v) -> s.setXmlFileAssociations(com.editora.lsp.XmlServerSettings.parseAssociations(v)),
                v -> v.isBlank()
                                || !com.editora.lsp.XmlServerSettings.parseAssociations(v)
                                        .isEmpty()
                        ? null
                        : tr("settings.lsp.xml.fileAssociations.invalid")));
        items.add(new Flag(
                under(xml, "settings.lsp.xml.formatWrap"), Settings::isXmlFormatWrap, Settings::setXmlFormatWrap));
        items.add(text(
                under(xml, "settings.lsp.xml.preserveSpace"),
                s -> String.join("; ", s.getXmlFormatPreserveSpace()),
                (s, v) -> s.setXmlFormatPreserveSpace(com.editora.lsp.XmlServerSettings.parseNames(v))));
        items.add(choice(
                under(xml, "settings.lsp.xml.splitAttributes"),
                Settings.XML_SPLIT_ATTRIBUTES,
                "settings.lsp.xml.splitAttributes.",
                Settings::getXmlFormatSplitAttributes,
                Settings::setXmlFormatSplitAttributes));
        items.add(choice(
                under(xml, "settings.lsp.xml.emptyElements"),
                Settings.XML_EMPTY_ELEMENTS,
                "settings.lsp.xml.emptyElements.",
                Settings::getXmlFormatEmptyElements,
                Settings::setXmlFormatEmptyElements));
        items.add(new Flag(
                under(xml, "settings.lsp.xml.spaceBeforeEmptyCloseTag"),
                Settings::isXmlFormatSpaceBeforeEmptyCloseTag,
                Settings::setXmlFormatSpaceBeforeEmptyCloseTag));
        items.add(number(
                under(xml, "settings.lsp.xml.symbolLimit"),
                Settings::getXmlSymbolLimit,
                0,
                Settings.XML_SYMBOL_LIMIT_MAX,
                Settings::setXmlSymbolLimit));
        items.add(new Flag(
                under("Maven", "settings.lsp.mavenCentralIndex"),
                Settings::isMavenPomCentralIndex,
                Settings::setMavenPomCentralIndex));
        items.add(number(
                under(html, "settings.lsp.html.wrapLineLength"),
                Settings::getHtmlFormatWrapLineLength,
                0,
                Settings.HTML_WRAP_LINE_LENGTH_MAX,
                Settings::setHtmlFormatWrapLineLength));
        items.add(choice(
                under(html, "settings.lsp.html.wrapAttributes"),
                Settings.HTML_WRAP_ATTRIBUTES,
                "settings.lsp.html.wrapAttributes.",
                Settings::getHtmlFormatWrapAttributes,
                Settings::setHtmlFormatWrapAttributes));
        items.add(text(
                under(html, "settings.lsp.html.contentUnformatted"),
                Settings::getHtmlFormatContentUnformatted,
                Settings::setHtmlFormatContentUnformatted));
        items.add(new Flag(
                under(html, "settings.lsp.html.validateScripts"),
                Settings::isHtmlValidateScripts,
                Settings::setHtmlValidateScripts));
        items.add(new Flag(
                under(html, "settings.lsp.html.validateStyles"),
                Settings::isHtmlValidateStyles,
                Settings::setHtmlValidateStyles));
        items.add(choice(
                under(html, "settings.htmlAttributeQuotes"),
                Settings.HTML_ATTRIBUTE_QUOTES,
                "settings.htmlAttributeQuotes.",
                Settings::getHtmlAttributeQuotes,
                Settings::setHtmlAttributeQuotes));
        items.add(new Flag(
                under(html, "settings.htmlMarkupChecks"), Settings::isHtmlMarkupChecks, Settings::setHtmlMarkupChecks));
        items.add(new Flag(
                under(js, "settings.lsp.typescriptTypeAcquisition"),
                Settings::isTypescriptTypeAcquisition,
                Settings::setTypescriptTypeAcquisition));
        items.add(new Flag(
                under(js, "settings.lsp.eslintEnabled"), Settings::isEslintEnabled, Settings::setEslintEnabled));
        items.add(choice(
                under(js, "settings.lsp.jsFormatter"),
                Settings.JS_FORMATTERS,
                "settings.lsp.jsFormatter.",
                Settings::getJsFormatter,
                Settings::setJsFormatter));
        items.add(new Flag(
                under(js, "settings.lsp.jsOrganizeImportsOnSave"),
                Settings::isJsOrganizeImportsOnSave,
                Settings::setJsOrganizeImportsOnSave));
        items.add(new Flag(
                under(js, "settings.lsp.eslintFixOnSave"), Settings::isEslintFixOnSave, Settings::setEslintFixOnSave));
        items.add(new Flag(
                under(js, "settings.lsp.jsFormatOnSave"), Settings::isJsFormatOnSave, Settings::setJsFormatOnSave));
        items.add(new Flag(
                key("settings.javaOrganizeImportsOnSave"),
                Settings::isJavaOrganizeImportsOnSave,
                Settings::setJavaOrganizeImportsOnSave));
        items.add(new Flag(
                key("settings.javaFormatOnSave"), Settings::isJavaFormatOnSave, Settings::setJavaFormatOnSave));
        items.add(new Flag(
                key("settings.javaCleanupOnSave"), Settings::isJavaCleanupOnSave, Settings::setJavaCleanupOnSave));
        items.add(list(
                key("settings.javaExcludedTypes"),
                Settings::getJavaExcludedTypes,
                ", ",
                Settings::setJavaExcludedTypes));
        items.add(new Flag(
                under("Python", "settings.lsp.pythonFormatOnSave"),
                Settings::isPythonFormatOnSave,
                Settings::setPythonFormatOnSave));
        return items;
    }

    private static List<Item> build() {
        List<Item> items = new ArrayList<>();
        for (BuildTool tool : BuildTool.enabled()) {
            items.add(text(
                    under(tool.displayName(), "settings.buildTools.commandOverride"),
                    tool::commandIn,
                    tool::setCommandIn));
        }
        items.add(flag("settings.buildBeforeRun", Settings::isBuildBeforeRun, Settings::setBuildBeforeRun));
        items.add(text(under("Maven", "settings.maven.jdk"), Settings::getMavenJdkHome, Settings::setMavenJdkHome));
        items.add(text(key("settings.debug.nodePath"), Settings::getNodePath, (s, v) -> s.setNodePath(v.strip())));
        return items;
    }

    private static List<Item> agentCommands() {
        return List.of(
                text(
                        key("settings.agent.command.gemini"),
                        Settings::getGeminiAgentCommand,
                        Settings::setGeminiAgentCommand),
                text(
                        key("settings.agent.command.copilot"),
                        Settings::getCopilotAgentCommand,
                        Settings::setCopilotAgentCommand),
                text(
                        key("settings.agent.command.codex"),
                        Settings::getCodexAgentCommand,
                        Settings::setCodexAgentCommand),
                text(key("settings.agent.command.qwen"), Settings::getQwenAgentCommand, Settings::setQwenAgentCommand),
                text(
                        key("settings.agent.command.opencode"),
                        Settings::getOpencodeAgentCommand,
                        Settings::setOpencodeAgentCommand));
    }

    private static List<Item> previews() {
        return List.of(
                flag("settings.svgPreview", Settings::isSvgPreview, Settings::setSvgPreview),
                flag("settings.crontabPreview", Settings::isCrontabPreview, Settings::setCrontabPreview),
                flag("settings.fstabPreview", Settings::isFstabPreview, Settings::setFstabPreview),
                flag("settings.systemdPreview", Settings::isSystemdPreview, Settings::setSystemdPreview),
                flag("settings.sshConfigPreview", Settings::isSshConfigPreview, Settings::setSshConfigPreview),
                flag("settings.dockerfilePreview", Settings::isDockerfilePreview, Settings::setDockerfilePreview),
                flag(
                        "settings.githubActionsPreview",
                        Settings::isGithubActionsPreview,
                        Settings::setGithubActionsPreview));
    }

    private static List<Item> encryption() {
        return List.of(
                text(
                        key("settings.encryption.gpgPath"),
                        Settings::getEncryptionGpgPath,
                        Settings::setEncryptionGpgPath),
                flag(
                        "settings.encryption.includeSelf",
                        Settings::isEncryptionIncludeSelf,
                        Settings::setEncryptionIncludeSelf),
                choice(
                        key("settings.encryption.prompt"),
                        List.of(Settings.PASSPHRASE_PROMPT_AGENT, Settings.PASSPHRASE_PROMPT_EDITORA),
                        "settings.encryption.prompt.",
                        Settings::getEncryptionPassphrasePrompt,
                        Settings::setEncryptionPassphrasePrompt),
                flag(
                        "settings.encryption.lsp",
                        Settings::isEncryptionLanguageServers,
                        Settings::setEncryptionLanguageServers));
    }

    private static List<Item> sync() {
        return List.of(
                text(
                        key("settings.sync.repo"),
                        Settings::getSyncRepoUrl,
                        Settings::setSyncRepoUrl,
                        v -> com.editora.sync.SyncEngine.hasCredentials(v.strip())
                                ? tr("settings.sync.repo.credentials")
                                : null),
                text(
                        key("settings.sync.branch"),
                        Settings::getSyncBranch,
                        Settings::setSyncBranch,
                        v -> v.isBlank() || com.editora.sync.SyncEngine.isUsableBranch(v.strip())
                                ? null
                                : tr("settings.sync.branch.invalid")),
                number(
                        () -> tr("settings.sync.auto") + " (" + tr("settings.git.autoFetchMinutes") + ")",
                        Settings::getSyncIntervalMinutes,
                        1,
                        Settings.MAX_GIT_AUTO_FETCH_MINUTES,
                        Settings::setSyncIntervalMinutes),
                flag("settings.sync.snippets", Settings::isSyncSnippets, Settings::setSyncSnippets),
                flag("settings.sync.abbreviations", Settings::isSyncAbbreviations, Settings::setSyncAbbreviations),
                flag("settings.sync.templates", Settings::isSyncTemplates, Settings::setSyncTemplates),
                flag("settings.sync.dictionary", Settings::isSyncDictionary, Settings::setSyncDictionary),
                flag("settings.sync.keybindings", Settings::isSyncKeybindings, Settings::setSyncKeybindings));
    }

    private static List<Item> web() {
        return List.of(
                flag("settings.httpClient.scripts", Settings::isHttpScriptsEnabled, Settings::setHttpScriptsEnabled),
                text(key("settings.httpClient.nodePath"), Settings::getHttpNodePath, Settings::setHttpNodePath),
                number(
                        key("settings.httpClient.scriptTimeout"),
                        Settings::getHttpScriptTimeoutSeconds,
                        1,
                        600,
                        Settings::setHttpScriptTimeoutSeconds),
                new Choice(
                        key("settings.htmlPreview.browser"),
                        () -> {
                            List<String> ids = new ArrayList<>();
                            ids.add(com.editora.web.Browsers.SYSTEM_DEFAULT);
                            ids.addAll(com.editora.web.Browsers.knownIds());
                            return ids;
                        },
                        id -> com.editora.web.Browsers.SYSTEM_DEFAULT.equals(id)
                                ? tr("htmlPreview.systemDefault")
                                : com.editora.web.Browsers.knownName(id),
                        s -> s.getHtmlPreviewBrowser().isBlank()
                                ? com.editora.web.Browsers.SYSTEM_DEFAULT
                                : s.getHtmlPreviewBrowser(),
                        Settings::setHtmlPreviewBrowser));
    }

    private static List<Item> pdf() {
        return List.of(
                choice(
                        key("settings.pdf.orientation"),
                        Settings.PDF_ORIENTATIONS,
                        "settings.pdf.orientation.",
                        Settings::getPdfOrientation,
                        Settings::setPdfOrientation),
                choice(
                        key("settings.pdf.margins"),
                        Settings.PDF_MARGINS,
                        "settings.pdf.margins.",
                        Settings::getPdfMargins,
                        Settings::setPdfMargins),
                new Choice(
                        key("settings.pdf.codeFontSize"),
                        () -> Settings.PDF_CODE_FONT_SIZES.stream()
                                .map(String::valueOf)
                                .toList(),
                        points -> tr("settings.pdf.codeFontSize.points", Integer.parseInt(points)),
                        s -> Integer.toString(s.getPdfCodeFontSize()),
                        (s, points) -> s.setPdfCodeFontSize(Integer.parseInt(points))));
    }

    /** One severity choice per Markdown lint rule, named by its code and what it checks. */
    private static List<Item> lintSeverities() {
        List<Item> items = new ArrayList<>();
        for (com.editora.markdown.MarkdownLint.Rule rule : com.editora.markdown.MarkdownLint.RULES) {
            String code = rule.code();
            items.add(choice(
                    () -> code + " — " + tr("mdlint.rule." + code),
                    Settings.LINT_SEVERITIES,
                    "settings.markdownLintSeverity.",
                    s -> s.markdownLintSeverity(code),
                    (s, severity) -> s.setMarkdownLintSeverity(code, severity)));
        }
        return items;
    }

    /** The AI provider whose key the settings name now. */
    private static com.editora.ai.AiProvider provider(Settings s) {
        return com.editora.ai.AiProvider.from(s.getAiProvider());
    }

    /** Every command this class registers, with the settings it changes. */
    static List<Entry> entries() {
        return List.of(
                new Entry("terminal.changeSetting", terminal()),
                new Entry("debug.changeSetting", debug()),
                new Entry("lsp.changeSetting", languages()),
                new Entry("build.changeSetting", build()),
                new Entry("agent.setClientCommand", agentCommands()),
                new Entry("view.toggleFilePreview", previews()),
                new Entry("crypto.changeSetting", encryption()),
                new Entry("sync.changeSetting", sync()),
                new Entry("web.changeSetting", web()),
                new Entry("editor.changePdfSetting", pdf()),
                new Entry("markdownLint.setRuleSeverity", lintSeverities()),
                new Entry(
                        "view.toggleWordWrapProse",
                        List.of(flag("settings.wordWrapProse", Settings::isWordWrapProse, Settings::setWordWrapProse))),
                new Entry(
                        "view.toggleLightbulb",
                        List.of(flag("settings.lspLightbulb", Settings::isLspLightbulb, Settings::setLspLightbulb))),
                new Entry(
                        "view.toggleProblemCounter",
                        List.of(flag(
                                "settings.problemCounter", Settings::isProblemCounter, Settings::setProblemCounter))),
                new Entry(
                        "github.setCommand",
                        List.of(text(key("settings.github.ghPath"), Settings::getGhPath, Settings::setGhPath))),
                new Entry(
                        "remote.setContainerEngine",
                        List.of(text(
                                key("settings.remote.containerEngine"),
                                Settings::getContainerEngine,
                                Settings::setContainerEngine))),
                new Entry(
                        "spell.setDefaultLanguage",
                        List.of(new Choice(
                                key("settings.language"),
                                com.editora.editor.spell.SpellDictionaries::available,
                                SpellCoordinator::languageName,
                                Settings::getSpellLanguage,
                                Settings::setSpellLanguage))),
                new Entry(
                        "ai.setApiKey",
                        List.of(new Value(
                                key("settings.ai.apiKey"),
                                s -> s.getApiKeyFor(provider(s)),
                                (s, v) -> s.setApiKeyFor(provider(s), v.strip()),
                                null,
                                v -> null,
                                true))));
    }

    /** The ids {@link #register} registers, for the command sweep and the catalogs' tests. */
    static List<String> ids() {
        return entries().stream().map(Entry::id).toList();
    }

    /**
     * Registers every command. {@code applied} re-applies the settings to every window, as the Settings window
     * does after one of its rows changes.
     */
    static void register(
            CommandRegistry registry, EditorSettingsCoordinator editor, Supplier<Settings> settings, Runnable applied) {
        for (Entry entry : entries()) {
            registry.register(Command.of(entry.id(), () -> run(entry, editor, settings, applied)));
        }
    }

    /** One setting is changed at once; several are picked from first, each showing its value. */
    private static void run(
            Entry entry, EditorSettingsCoordinator editor, Supplier<Settings> settings, Runnable applied) {
        if (entry.items().size() == 1) {
            change(entry.id(), entry.items().get(0), editor, settings, applied);
            return;
        }
        editor.pickSetting(
                entry.id(),
                entry.items(),
                Item::name,
                item -> item.shown(settings.get()),
                null,
                item -> change(entry.id(), item, editor, settings, applied));
    }

    /** Flips {@code item}, lists its choices, or asks for its value; then saves, applies and reports. */
    static void change(
            String commandId,
            Item item,
            EditorSettingsCoordinator editor,
            Supplier<Settings> settings,
            Runnable applied) {
        switch (item) {
            case Flag flag -> {
                Settings s = settings.get();
                flag.set().accept(s, !flag.get().test(s));
                editor.settingWritten(flag.name(), flag.shown(s), applied);
            }
            case Choice choice -> {
                List<String> values = choice.values().get();
                editor.pickSetting(
                        commandId,
                        values,
                        choice.valueLabel(),
                        v -> "",
                        choice.get().apply(settings.get()),
                        v -> {
                            choice.set().accept(settings.get(), v);
                            editor.settingWritten(choice.name(), choice.shown(settings.get()), applied);
                        });
            }
            case Value value ->
                editor.promptSettingValue(value.name(), value.get().apply(settings.get()), value.secret(), typed -> {
                    String answer = accepted(value, typed, editor);
                    if (answer != null) {
                        value.set().accept(settings.get(), answer);
                        editor.settingWritten(value.name(), value.shown(settings.get()), applied);
                    }
                });
        }
    }

    /**
     * What is kept of {@code typed} for {@code value}: a number inside its range, or the text; null, with the
     * reason said, when it is not a number or the text is refused.
     */
    private static @Nullable String accepted(Value value, String typed, EditorSettingsCoordinator editor) {
        String text = typed == null ? "" : typed;
        NumberRange range = value.range();
        if (range != null) {
            int parsed;
            try {
                parsed = Integer.parseInt(text.strip());
            } catch (NumberFormatException e) {
                editor.status(tr("status.setting.invalidNumber", text.strip()));
                return null;
            }
            return Integer.toString(Math.max(range.min(), Math.min(range.max(), parsed)));
        }
        String why = value.refuse().apply(text);
        if (why != null) {
            editor.status(why);
            return null;
        }
        return text;
    }
}
