package com.editora.ui;

import com.editora.config.Settings;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → LSP, the TypeScript card's "ESLint and formatting" rows (JS review F): ESLint on or off, who formats
 * JavaScript and TypeScript, and the three actions on save. Built here so that {@link SettingsWindow} stays under the
 * file-size limit. The rows are keyed {@code lsp.*}; their values apply to every window at once.
 */
final class SettingsJsToolRows {

    private static final String WORDS = "lsp javascript typescript js ts jsx tsx node ";

    private SettingsJsToolRows() {}

    static void add(SettingsWindow w, SettingsWindow.Card c) {
        SettingsWindow.Category cat = SettingsWindow.Category.LSP;
        w.cardSubheading(
                c,
                cat,
                tr("settings.lsp.group.jsTools"),
                WORDS + "eslint prettier biome lint linter format formatter save actions on save "
                        + tr("settings.lsp.eslintEnabled").toLowerCase(java.util.Locale.ROOT) + " "
                        + tr("settings.lsp.jsFormatter").toLowerCase(java.util.Locale.ROOT));
        w.switchRow(
                c,
                cat,
                "lsp.eslintEnabled",
                true,
                Settings::isEslintEnabled,
                Settings::setEslintEnabled,
                WORDS + "eslint lint linter problems diagnostics rules eslint.config.js eslintrc trusted folder");
        w.choiceRow(
                c,
                cat,
                "lsp.jsFormatter",
                true,
                Settings.JS_FORMATTERS,
                Settings::getJsFormatter,
                Settings::setJsFormatter,
                WORDS + "format formatter format document prettier biome typescript server prettierrc biome.json");
        w.switchRow(
                c,
                cat,
                "lsp.jsOrganizeImportsOnSave",
                false,
                Settings::isJsOrganizeImportsOnSave,
                Settings::setJsOrganizeImportsOnSave,
                WORDS + "organize imports sort unused on save actions");
        w.switchRow(
                c,
                cat,
                "lsp.eslintFixOnSave",
                false,
                Settings::isEslintFixOnSave,
                Settings::setEslintFixOnSave,
                WORDS + "eslint fix fixes fix all auto-fix on save actions");
        w.switchRow(
                c,
                cat,
                "lsp.jsFormatOnSave",
                false,
                Settings::isJsFormatOnSave,
                Settings::setJsFormatOnSave,
                WORDS + "format reformat prettier biome on save actions");
    }
}
