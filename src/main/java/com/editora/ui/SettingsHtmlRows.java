package com.editora.ui;

import com.editora.config.Settings;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → LSP, the HTML card's own rows: the HTML server's formatting, completion and validation settings (HTML
 * review A4, B10). Built here so that {@link SettingsWindow} stays under the file-size limit; the rows are its own
 * kinds, keyed {@code lsp.html.*}. A project's {@code .editora/settings.json} may lay an {@code html} object (VS
 * Code's key names) over them.
 */
final class SettingsHtmlRows {

    /** Words the HTML card's own rows are found by. */
    private static final String WORDS = "lsp html vscode-html-language-server markup web page ";

    private static final String FORMAT_WORDS = "format formatting wrap line length width column attributes align"
            + " force preserve pre code textarea unformatted content keep verbatim whitespace editorconfig";

    private static final String CODE_WORDS = "completion attribute quotes double single empty validate validation"
            + " embedded script javascript style css problems diagnostics";

    private SettingsHtmlRows() {}

    /** The titles of a group's rows, in the UI language: a search for a row's title shows its heading too. */
    private static String titles(String... keys) {
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            sb.append(' ').append(tr("settings.lsp.html." + key).toLowerCase(java.util.Locale.ROOT));
        }
        return sb.toString();
    }

    static void add(SettingsWindow w, SettingsWindow.Card c) {
        SettingsWindow.Category cat = SettingsWindow.Category.LSP;
        w.cardSubheading(
                c,
                cat,
                tr("settings.lsp.html.group.format"),
                WORDS + FORMAT_WORDS + titles("wrapLineLength", "wrapAttributes", "contentUnformatted"));
        w.intRow(
                c,
                cat,
                "lsp.html.wrapLineLength",
                Settings.HTML_WRAP_LINE_LENGTH_MAX,
                10,
                Settings::getHtmlFormatWrapLineLength,
                Settings::setHtmlFormatWrapLineLength,
                WORDS + "format wrap line length width column max_line_length editorconfig");
        w.choiceRow(
                c,
                cat,
                "lsp.html.wrapAttributes",
                true,
                Settings.HTML_WRAP_ATTRIBUTES,
                Settings::getHtmlFormatWrapAttributes,
                Settings::setHtmlFormatWrapAttributes,
                WORDS + "format wrap attributes align force preserve multiline");
        w.textRow(
                c,
                cat,
                "lsp.html.contentUnformatted",
                Settings.HTML_CONTENT_UNFORMATTED_DEFAULT,
                Settings::getHtmlFormatContentUnformatted,
                Settings::setHtmlFormatContentUnformatted,
                WORDS + "format pre code textarea unformatted content keep verbatim whitespace spaces");
        w.widenField("lsp.html.contentUnformatted", 320);
        w.cardSubheading(
                c,
                cat,
                tr("settings.lsp.html.group.code"),
                WORDS + CODE_WORDS + titles("attributeQuotes", "validateScripts", "validateStyles"));
        w.choiceRow(
                c,
                cat,
                "lsp.html.attributeQuotes",
                true,
                Settings.HTML_ATTRIBUTE_QUOTES,
                Settings::getHtmlAttributeQuotes,
                Settings::setHtmlAttributeQuotes,
                WORDS + "completion attribute quotes double single empty value");
        w.switchRow(
                c,
                cat,
                "lsp.html.validateScripts",
                true,
                Settings::isHtmlValidateScripts,
                Settings::setHtmlValidateScripts,
                WORDS + "validate validation embedded script javascript problems diagnostics");
        w.switchRow(
                c,
                cat,
                "lsp.html.validateStyles",
                true,
                Settings::isHtmlValidateStyles,
                Settings::setHtmlValidateStyles,
                WORDS + "validate validation embedded style css problems diagnostics");
    }
}
