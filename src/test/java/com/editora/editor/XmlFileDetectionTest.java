package com.editora.editor;

import java.util.List;

import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.spell.SpellMode;
import com.editora.lsp.LspServerRegistry;
import com.editora.text.LanguageRegistry;
import com.editora.text.Modeline;
import com.editora.text.Shebang;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XML dialects reach the XML grammar, spell mode and language server (XML review A8, B8, C5, D5): a DTD, a RELAX NG
 * schema, an MSBuild project or a property list used to open as spell-checked plain text with no server.
 */
class XmlFileDetectionTest {

    /** The dialects the review's position 6 maps to XML, plus the extensions that already were. */
    private static final List<String> XML_EXTENSIONS = List.of(
            "xml", "xsd", "xsl", "xslt", "svg", "fxml", "pom", "rss", "wsdl", "dtd", "ent", "mod", "rng", "fo", "xspec",
            "sch", "csproj", "vbproj", "fsproj", "props", "targets", "nuspec", "resx", "xaml", "xlf", "xliff", "plist",
            "xul", "kml", "gpx", "tld", "jnlp", "xmi", "ditamap", "dita");

    @Test
    void everyXmlDialectIsXmlInBothTablesAndRoutesToTheXmlServer() {
        GrammarRegistry grammars = GrammarRegistry.shared();
        for (String ext : XML_EXTENSIONS) {
            String file = "/work/file." + ext;
            assertEquals("xml", LanguageRegistry.forFileName(file), ext);
            assertTrue(grammars.hasGrammarFor(file), ext + " has the XML grammar");
            assertEquals("xml", LspServerRegistry.serverIdFor(LanguageRegistry.forFileName(file)), ext);
        }
        assertEquals("xml", LanguageRegistry.forFileName("App.CSPROJ"), "extensions are case-blind");
    }

    @Test
    void markupInXmlDialectsIsNotSpellCheckedAsProse() {
        SpellMode xml = SpellMode.forLanguage(LanguageRegistry.forFileName("catalog.dtd"));
        assertEquals(SpellMode.forLanguage("xml"), xml);
        assertFalse(xml == SpellMode.forLanguage(LanguageRegistry.PLAINTEXT), "a DTD is not prose");
    }

    @Test
    void goModulesAndXhtmlKeepTheirOwnLanguages() {
        assertEquals(LanguageRegistry.PLAINTEXT, LanguageRegistry.forFileName("/src/app/go.mod"));
        assertFalse(GrammarRegistry.shared().hasGrammarFor("/src/app/go.mod"), "go.mod is not coloured as XML");
        assertEquals("xml", LanguageRegistry.forFileName("/dtd/xhtml-lat1.mod"));
        assertEquals("html", LanguageRegistry.forFileName("page.xhtml"));
    }

    @Test
    void aFileThatOpensWithAnXmlDeclarationIsXml() {
        String manifest = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<manifest package=\"a.b\"/>\n";
        Shebang.Result r = Modeline.detect(manifest, manifest, "/app/AndroidManifest");
        assertNotNull(r);
        assertEquals("xml", LanguageRegistry.forFileName("shebang." + r.extension()));
        // after a byte-order mark and blank lines, and with the declaration's own "?>" right after the name
        assertEquals("xml", Modeline.detect("﻿\n  <?xml?><a/>", "", null).extension());
        // not an XML declaration
        assertNull(Modeline.detect("<?xml-stylesheet href=\"a.css\"?>\n<a/>", "", null));
        assertNull(Modeline.detect("<note/>", "", null));
        assertNull(Modeline.detect("text <?xml version=\"1.0\"?>", "", null));
        // a .txt file is plain text by its name; a modeline still wins over the sniff
        assertNull(Modeline.detect(manifest, manifest, "/notes/output.txt"));
        String withModeline = "<?xml version=\"1.0\"?>\n<a/>\n<!-- vim: set ft=html : -->\n";
        assertEquals("html", Modeline.detect(withModeline, withModeline, null).extension());
    }
}
