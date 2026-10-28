package com.editora.ui;

import com.editora.config.Settings;
import com.editora.lsp.XmlServerSettings;

import static com.editora.i18n.Messages.tr;

/**
 * Settings → LSP, the XML card's own rows: lemminx's settings (XML review D3, B2, D2, B6, B7, C9, A13). Built here
 * so that {@link SettingsWindow} stays under the file-size limit; the rows are its own kinds, keyed
 * {@code lsp.xml.*}.
 */
final class SettingsXmlRows {

    /** Words the XML card's own rows are found by. */
    private static final String WORDS = "lsp xml lemminx xsd dtd schema grammar ";

    /** The words of each group's rows: a search that finds a row also shows the heading over it. */
    private static final String VALIDATION_WORDS = "validation download remote network http https internet trust"
            + " cache offline no grammar hint problems catalog oasis resolve system public uri docbook dita file"
            + " association pattern glob bind";

    private static final String FORMAT_WORDS = "format formatting wrap line width fill column text space preserve"
            + " keep verbatim attributes split align empty element self closing collapse expand tag";

    private static final String STRUCTURE_WORDS = "structure symbols outline limit folding large";

    private SettingsXmlRows() {}

    /** The titles of a group's rows, in the UI language: a search for a row's title shows its heading too. */
    private static String titles(String... keys) {
        StringBuilder sb = new StringBuilder();
        for (String key : keys) {
            sb.append(' ').append(tr("settings.lsp.xml." + key).toLowerCase(java.util.Locale.ROOT));
        }
        return sb.toString();
    }

    static void add(SettingsWindow w, SettingsWindow.Card c) {
        SettingsWindow.Category cat = SettingsWindow.Category.LSP;
        w.cardSubheading(
                c,
                cat,
                tr("settings.lsp.xml.group.validation"),
                WORDS + VALIDATION_WORDS + titles("schemaDownloads", "noGrammar", "catalogs", "fileAssociations"));
        w.choiceRow(
                c,
                cat,
                "lsp.xml.schemaDownloads",
                true,
                Settings.XML_SCHEMA_DOWNLOADS,
                Settings::getXmlSchemaDownloads,
                Settings::setXmlSchemaDownloads,
                WORDS + "download remote network http https internet trust cache offline");
        w.choiceRow(
                c,
                cat,
                "lsp.xml.noGrammar",
                true,
                Settings.XML_NO_GRAMMAR,
                Settings::getXmlNoGrammar,
                Settings::setXmlNoGrammar,
                WORDS + "no grammar hint problems validation");
        w.textRow(
                c,
                cat,
                "lsp.xml.catalogs",
                "catalog.xml",
                st -> String.join("; ", st.getXmlCatalogs()),
                (st, v) -> st.setXmlCatalogs(XmlServerSettings.parseCatalogs(v)),
                WORDS + "catalog oasis resolve system public uri docbook dita");
        w.widenField("lsp.xml.catalogs", 440);
        w.textRow(
                c,
                cat,
                "lsp.xml.fileAssociations",
                "*.myconf.xml=schemas/myconf.xsd",
                st -> XmlServerSettings.formatAssociations(st.getXmlFileAssociations()),
                (st, v) -> st.setXmlFileAssociations(XmlServerSettings.parseAssociations(v)),
                v -> v.isBlank() || !XmlServerSettings.parseAssociations(v).isEmpty()
                        ? null
                        : tr("settings.lsp.xml.fileAssociations.invalid"),
                WORDS + "file association pattern glob bind");
        w.cardSubheading(
                c,
                cat,
                tr("settings.lsp.xml.group.format"),
                WORDS
                        + FORMAT_WORDS
                        + titles(
                                "formatWrap",
                                "preserveSpace",
                                "splitAttributes",
                                "emptyElements",
                                "spaceBeforeEmptyCloseTag"));
        w.switchRow(
                c,
                cat,
                "lsp.xml.formatWrap",
                true,
                Settings::isXmlFormatWrap,
                Settings::setXmlFormatWrap,
                WORDS + "format wrap line width fill column text");
        w.textRow(
                c,
                cat,
                "lsp.xml.preserveSpace",
                "pre; xsl:text",
                st -> String.join("; ", st.getXmlFormatPreserveSpace()),
                (st, v) -> st.setXmlFormatPreserveSpace(XmlServerSettings.parseNames(v)),
                WORDS + "format text space spaces preserve keep verbatim whitespace xml:space join");
        w.widenField("lsp.xml.preserveSpace", 440); // its default list is 9 names (C28)
        w.choiceRow(
                c,
                cat,
                "lsp.xml.splitAttributes",
                true,
                Settings.XML_SPLIT_ATTRIBUTES,
                Settings::getXmlFormatSplitAttributes,
                Settings::setXmlFormatSplitAttributes,
                WORDS + "format attributes split align");
        w.choiceRow(
                c,
                cat,
                "lsp.xml.emptyElements",
                true,
                Settings.XML_EMPTY_ELEMENTS,
                Settings::getXmlFormatEmptyElements,
                Settings::setXmlFormatEmptyElements,
                WORDS + "format empty element self closing collapse expand");
        w.switchRow(
                c,
                cat,
                "lsp.xml.spaceBeforeEmptyCloseTag",
                true,
                Settings::isXmlFormatSpaceBeforeEmptyCloseTag,
                Settings::setXmlFormatSpaceBeforeEmptyCloseTag,
                WORDS + "format space self closing tag");
        w.cardSubheading(
                c, cat, tr("settings.lsp.xml.group.structure"), WORDS + STRUCTURE_WORDS + titles("symbolLimit"));
        w.intRow(
                c,
                cat,
                "lsp.xml.symbolLimit",
                Settings.XML_SYMBOL_LIMIT_MAX,
                1_000,
                Settings::getXmlSymbolLimit,
                Settings::setXmlSymbolLimit,
                WORDS + "structure symbols outline limit folding large");
    }
}
