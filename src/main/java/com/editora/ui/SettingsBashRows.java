package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.config.Settings;
import com.editora.lsp.BashIdeConfig;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → LSP, the Bash card's own rows: bash-language-server's settings (its {@code bashIde} section) —
 * ShellCheck and shfmt (each with an on/off switch), their options, the {@code source} hint, the
 * background-analysis cap and explainshell. Built here so that {@link SettingsWindow} stays under the file-size
 * limit; the rows are its own kinds, keyed {@code lsp.bash.*}. A project can override them in
 * {@code .editora/settings.json}; the card says which ({@link #projectNote}).
 */
final class SettingsBashRows {

    /** Words the Bash card's own rows are found by. */
    static final String WORDS = "lsp bash shell shellcheck shfmt format lint bash-language-server ";

    private SettingsBashRows() {}

    static void add(SettingsWindow w, SettingsWindow.Card c) {
        SettingsWindow.Category cat = SettingsWindow.Category.LSP;
        w.switchRow(
                c,
                cat,
                "lsp.bash.shellcheckEnabled",
                true,
                Settings::isBashShellcheckEnabled,
                Settings::setBashShellcheckEnabled,
                WORDS + "lint linting diagnostics problems on off disable");
        w.bashPathRow(c, "shellcheckPath", Settings::getBashShellcheckPath, Settings::setBashShellcheckPath);
        w.textRow(
                c,
                cat,
                "lsp.bash.shellcheckArguments",
                "--severity=warning",
                Settings::getBashShellcheckArguments,
                Settings::setBashShellcheckArguments,
                WORDS + "arguments severity exclude");
        w.switchRow(
                c,
                cat,
                "lsp.bash.sourceErrorDiagnostics",
                true,
                Settings::isBashSourceErrorDiagnostics,
                Settings::setBashSourceErrorDiagnostics,
                WORDS + "source sourced library dirname BASH_SOURCE directive hint definition");
        w.switchRow(
                c,
                cat,
                "lsp.bash.shfmtEnabled",
                true,
                Settings::isBashShfmtEnabled,
                Settings::setBashShfmtEnabled,
                WORDS + "formatting format document on off disable");
        w.bashPathRow(c, "shfmtPath", Settings::getBashShfmtPath, Settings::setBashShfmtPath);
        w.choiceRow(
                c,
                cat,
                "lsp.bash.shfmtDialect",
                true,
                Settings.BASH_SHFMT_DIALECTS,
                Settings::getBashShfmtDialect,
                Settings::setBashShfmtDialect,
                WORDS + "dialect posix mksh bats");
        shfmtSwitch(
                w,
                c,
                "shfmtCaseIndent",
                Settings::isBashShfmtCaseIndent,
                Settings::setBashShfmtCaseIndent,
                "case indent");
        shfmtSwitch(
                w,
                c,
                "shfmtBinaryNextLine",
                Settings::isBashShfmtBinaryNextLine,
                Settings::setBashShfmtBinaryNextLine,
                "binary operator next line");
        shfmtSwitch(
                w,
                c,
                "shfmtFuncNextLine",
                Settings::isBashShfmtFuncNextLine,
                Settings::setBashShfmtFuncNextLine,
                "function brace next line");
        shfmtSwitch(
                w,
                c,
                "shfmtSpaceRedirects",
                Settings::isBashShfmtSpaceRedirects,
                Settings::setBashShfmtSpaceRedirects,
                "redirect space");
        shfmtSwitch(w, c, "shfmtSimplify", Settings::isBashShfmtSimplify, Settings::setBashShfmtSimplify, "simplify");
        shfmtSwitch(
                w,
                c,
                "shfmtKeepPadding",
                Settings::isBashShfmtKeepPadding,
                Settings::setBashShfmtKeepPadding,
                "keep padding align");
        w.switchRow(
                c,
                cat,
                "lsp.bash.shfmtIgnoreEditorconfig",
                true,
                Settings::isBashShfmtIgnoreEditorconfig,
                Settings::setBashShfmtIgnoreEditorconfig,
                WORDS + "editorconfig");
        w.intRow(
                c,
                cat,
                "lsp.bash.backgroundAnalysisMaxFiles",
                Settings.BASH_BACKGROUND_ANALYSIS_MAX,
                100,
                Settings::getBashBackgroundAnalysisMaxFiles,
                Settings::setBashBackgroundAnalysisMaxFiles,
                WORDS + "background analysis files workspace symbols");
        w.switchRow(
                c,
                cat,
                "lsp.bash.includeAllWorkspaceSymbols",
                true,
                Settings::isBashIncludeAllWorkspaceSymbols,
                Settings::setBashIncludeAllWorkspaceSymbols,
                WORDS + "workspace symbols completion rename");
        // Plain http only to this machine: the server sends the whole command line, secrets and all (B10).
        w.textRow(
                c,
                cat,
                "lsp.bash.explainshellEndpoint",
                "http://localhost:5000",
                Settings::getBashExplainshellEndpoint,
                Settings::setBashExplainshellEndpoint,
                v -> BashIdeConfig.explainshellAllowed(v) ? null : tr("settings.lsp.bash.explainshellEndpoint.refused"),
                WORDS + "explainshell hover flags privacy https");
    }

    private static void shfmtSwitch(
            SettingsWindow w,
            SettingsWindow.Card c,
            String key,
            java.util.function.Predicate<Settings> get,
            java.util.function.BiConsumer<Settings, Boolean> set,
            String words) {
        w.switchRow(c, SettingsWindow.Category.LSP, "lsp.bash." + key, false, get, set, WORDS + words);
    }

    /**
     * The project line on {@code serverId}'s card: the window's {@link LspCoordinator#projectOverrideNote}, and on
     * the Bash card also which of its rows the project's {@code bashIde} object overrides, and an explainshell
     * endpoint that is not sent (Bash review A10, B10). Null when there is nothing to say.
     */
    static @Nullable String projectNote(LspCoordinator lsp, String serverId) {
        String note = lsp.projectOverrideNote(serverId);
        if (!com.editora.lsp.BashLsp.SERVER_ID.equals(serverId)) {
            return note;
        }
        List<String> parts = new ArrayList<>();
        if (note != null) {
            parts.add(note);
        }
        List<String> keys = BashIdeConfig.overriddenKeys(lsp.projectSettings().getBashIde(), lsp.projectTrusted());
        if (!keys.isEmpty()) {
            parts.add(tr("settings.lsp.bash.projectOverrides", String.join(", ", keys)));
        }
        String endpoint = lsp.bashIdeConfig().explainshellEndpoint();
        if (!BashIdeConfig.explainshellAllowed(endpoint)) {
            parts.add(tr("settings.lsp.bash.explainshellEndpoint.notSent", endpoint));
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }
}
