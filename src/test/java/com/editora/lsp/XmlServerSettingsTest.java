package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.Settings;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The XML server's (lemminx's) settings (XML review D3): what Editora sends at initialize, pushes on a change and
 * answers to {@code workspace/configuration}. Before, lemminx got nothing and ran on its defaults: remote schemas
 * always downloaded (B2), its cache in {@code ~/.lemminx} (D4), no catalogs or associations (D2), a no-grammar hint
 * on every plain file (B6), {@code [null, null]} for the document's indentation (B12).
 */
class XmlServerSettingsTest {

    @TempDir
    Path root;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static boolean downloads(Map<String, Object> xml) {
        return (Boolean) map(xml.get("downloadExternalResources")).get("enabled");
    }

    @Test
    void schemaDownloadsFollowTheFoldersTrustByDefault() {
        XmlServerSettings defaults = XmlServerSettings.defaults();
        assertEquals("trusted", defaults.schemaDownloads());
        assertFalse(downloads(defaults.section(root, false, true)), "an untrusted folder downloads nothing");
        assertTrue(downloads(defaults.section(root, true, true)), "a trusted one does");

        Settings s = new Settings();
        s.setXmlSchemaDownloads("never");
        assertFalse(downloads(XmlServerSettings.of(s, null).section(root, true, true)));
        s.setXmlSchemaDownloads("always");
        assertTrue(downloads(XmlServerSettings.of(s, null).section(root, false, true)));
        s.setXmlSchemaDownloads("sometimes");
        assertEquals("trusted", s.getXmlSchemaDownloads(), "an unknown mode reads as the default");
    }

    @Test
    void noGrammarIsIgnoredAndExternalEntitiesAreNeverResolved() {
        Map<String, Object> validation =
                map(XmlServerSettings.defaults().section(root, true, true).get("validation"));
        assertEquals("ignore", validation.get("noGrammar"));
        assertEquals(false, validation.get("resolveExternalEntities"));
        Settings s = new Settings();
        s.setXmlNoGrammar("hint");
        assertEquals(
                "hint",
                map(XmlServerSettings.of(s, null).section(root, true, true).get("validation"))
                        .get("noGrammar"));
    }

    @Test
    void theCacheLivesUnderTheConfigFolderForALocalServerOnly(@TempDir Path config) {
        XmlServerSettings settings = XmlServerSettings.of(new Settings(), config);
        Path expected = config.resolve("cache").resolve("lemminx");
        assertEquals(expected, XmlServerSettings.cacheDir(config));
        assertEquals(
                Map.of("workDir", expected.toString()),
                settings.section(root, false, true).get("server"));
        assertNull(settings.section(root, false, false).get("server"), "a remote server keeps its own work dir");
        assertNull(XmlServerSettings.defaults().section(root, false, true).get("server"));
    }

    @Test
    void catalogsResolveUnderTheRootAndAMissingRelativeOneIsLeftOut() throws Exception {
        Files.writeString(root.resolve("catalog.xml"), "<catalog/>");
        String absolute =
                root.resolveSibling("elsewhere").resolve("catalog.xml").toString(); // kept, even absent
        Settings s = new Settings();
        s.setXmlCatalogs(List.of("catalog.xml", "missing/catalog.xml", absolute, "https://x.test/c.xml"));
        Object catalogs =
                XmlServerSettings.of(s, null).section(root, false, true).get("catalogs");
        assertEquals(List.of(root.resolve("catalog.xml").toString(), absolute, "https://x.test/c.xml"), catalogs);
        assertEquals(
                List.of("catalog.xml", "missing/catalog.xml", absolute, "https://x.test/c.xml"),
                XmlServerSettings.of(s, null).section(root, false, false).get("catalogs"),
                "a remote host's paths are sent as typed");
    }

    @Test
    void anAssociationMatchesTheFileNameAnywhereAndItsGrammarResolvesUnderTheRoot() {
        Settings s = new Settings();
        s.setXmlFileAssociations(Map.of("*.myconf.xml", "schemas/myconf.xsd"));
        Object associations =
                XmlServerSettings.of(s, null).section(root, false, true).get("fileAssociations");
        assertEquals(
                List.of(Map.of(
                        "pattern",
                        "**/*.myconf.xml",
                        "systemId",
                        root.resolve("schemas/myconf.xsd").toString())),
                associations);
        assertEquals("**/conf/*.xml", XmlServerSettings.globPattern("conf/*.xml"));
        assertEquals("**/x/*.xml", XmlServerSettings.globPattern("**/x/*.xml"));
        assertEquals("/srv/x/*.xml", XmlServerSettings.globPattern("/srv/x/*.xml"));
        assertEquals("**/web.xml", XmlServerSettings.globPattern("web.xml"));
    }

    @Test
    void formattingKeepsTextUnwrappedUnlessAskedThenWrapsAtTheFillColumn() {
        Map<String, Object> format =
                map(XmlServerSettings.defaults().section(root, true, true).get("format"));
        assertEquals(0, format.get("maxLineWidth"));
        assertEquals("preserve", format.get("splitAttributes"));
        assertEquals("ignore", format.get("emptyElements"));
        assertEquals(true, format.get("spaceBeforeEmptyCloseTag"));
        Settings s = new Settings();
        s.setXmlFormatWrap(true);
        s.setFillColumn(88);
        s.setXmlFormatSplitAttributes("alignWithFirstAttr");
        s.setXmlFormatEmptyElements("collapse");
        s.setXmlFormatSpaceBeforeEmptyCloseTag(false);
        format = map(XmlServerSettings.of(s, null).section(root, true, true).get("format"));
        assertEquals(88, format.get("maxLineWidth"));
        assertEquals("alignWithFirstAttr", format.get("splitAttributes"));
        assertEquals("collapse", format.get("emptyElements"));
        assertEquals(false, format.get("spaceBeforeEmptyCloseTag"));
        assertEquals(true, format.get("enabled"), "the user's settings never drop the rest of the object");
        assertEquals(2, format.get("preservedNewlines"));
    }

    @Test
    void theFormatObjectIsCompleteSoLemminxRegistersFormattingAndKeepsTheLayout() {
        // lemminx reads xml.format as a whole: a key left out is Java's default, not lemminx's. Without "enabled" it
        // never registered formatting (XML review 2 C1); without the rest it dropped blank lines and joined a pom's
        // multi-line <project> start tag (E8), and joined comment and CDATA lines.
        Map<String, Object> format =
                map(XmlServerSettings.defaults().section(root, true, true).get("format"));
        assertEquals(true, format.get("enabled"));
        assertEquals(2, format.get("preservedNewlines"));
        assertEquals(true, format.get("preserveAttributeLineBreaks"));
        assertEquals(false, format.get("joinContentLines"));
        assertEquals(false, format.get("joinCDATALines"));
        assertEquals(false, format.get("joinCommentLines"));
        assertEquals(false, format.get("preserveEmptyContent"));
        assertEquals("ignore", format.get("enforceQuoteStyle"));
        assertEquals(Settings.XML_PRESERVE_SPACE_DEFAULT, format.get("preserveSpace"));
        assertEquals(
                true,
                XmlServerSettings.answer(
                        XmlServerSettings.defaults().section(root, true, true), "xml.format.enabled", null),
                "workspace/configuration says so too");
        Map<String, Object> init =
                XmlServerSettings.withSection(null, XmlServerSettings.defaults().section(root, true, true));
        assertEquals(
                true,
                map(map(map(init.get("settings")).get("xml")).get("format")).get("enabled"));
    }

    @Test
    void elementsWhoseTextIsKeptAreTheUsersList() {
        Settings s = new Settings();
        assertEquals(Settings.XML_PRESERVE_SPACE_DEFAULT, s.getXmlFormatPreserveSpace());
        s.setXmlFormatPreserveSpace(XmlServerSettings.parseNames("p; pre,  xsl:text\n p "));
        assertEquals(List.of("p", "pre", "xsl:text"), s.getXmlFormatPreserveSpace());
        assertEquals(
                List.of("p", "pre", "xsl:text"),
                map(XmlServerSettings.of(s, null).section(root, true, true).get("format"))
                        .get("preserveSpace"));
        s.setXmlFormatPreserveSpace(XmlServerSettings.parseNames(""));
        assertEquals(List.of(), s.getXmlFormatPreserveSpace(), "an empty list is kept: every text is reflowed");
    }

    @Test
    void theSymbolLimitAndCodeLensAreSent() {
        Settings s = new Settings();
        assertEquals(5_000, s.getXmlSymbolLimit());
        s.setXmlSymbolLimit(20_000);
        Map<String, Object> xml = XmlServerSettings.of(s, null).section(root, true, true);
        assertEquals(Map.of("maxItemsComputed", 20_000, "showReferencedGrammars", false), xml.get("symbols"));
        assertEquals(Map.of("enabled", true), xml.get("codeLens"));
        s.setXmlSymbolLimit(0);
        assertEquals(5_000, s.getXmlSymbolLimit(), "0 reads as the default");
    }

    @Test
    void configurationIsAnsweredFromTheSectionAndIndentationFromTheDocument() {
        Map<String, Object> xml = XmlServerSettings.defaults().section(root, false, true);
        FormattingIndent tabs = new FormattingIndent(4, false);
        assertEquals(4, XmlServerSettings.answer(xml, "xml.format.tabSize", tabs));
        assertEquals(false, XmlServerSettings.answer(xml, "xml.format.insertSpaces", tabs));
        assertEquals("ignore", XmlServerSettings.answer(xml, "xml.validation.noGrammar", null));
        assertEquals(xml, XmlServerSettings.answer(xml, "xml", null));
        assertNull(XmlServerSettings.answer(xml, "xml.format.tabSize", null), "unknown indent: the server's default");
        assertNull(XmlServerSettings.answer(xml, "xml.nothing.here", null));
        assertNull(XmlServerSettings.answer(xml, "files.exclude", null));
    }

    @Test
    void initOptionsCarryTheSectionAndKeepLemminxMavensOwn() {
        Map<String, Object> xml = Map.of("validation", Map.of("noGrammar", "ignore"));
        Object maven = LspManager.initOptionsFor(LspServerRegistry.MAVEN_POM_SERVER_ID, List.of());
        Map<String, Object> merged =
                map(map(LspManager.withXmlSettings(LspServerRegistry.MAVEN_POM_SERVER_ID, maven, xml))
                        .get("settings"));
        Map<String, Object> mergedXml = map(merged.get("xml"));
        assertEquals(Map.of("noGrammar", "ignore"), mergedXml.get("validation"));
        assertEquals(Map.of("central", Map.of("skip", true)), mergedXml.get("maven"), "lemminx-maven's object stays");
        assertEquals(
                Map.of(
                        "extendedClientCapabilities",
                        XmlServer.extendedClientCapabilities(),
                        "settings",
                        Map.of("xml", xml)),
                LspManager.withXmlSettings("xml", LspManager.initOptionsFor("xml", List.of()), xml),
                "the settings sit beside lemminx's extended client capabilities");
        assertEquals(
                Map.of("provideFormatter", true),
                LspManager.withXmlSettings("json", Map.of("provideFormatter", true), xml));
        assertNull(LspManager.withXmlSettings("xml", null, null), "no section: as before");
    }

    @Test
    void associationAndCatalogTextRoundTrips() {
        Map<String, String> parsed =
                XmlServerSettings.parseAssociations("*.a.xml = a.xsd; b/*.xml=https://x.test/b.xsd;junk;=x");
        assertEquals(Map.of("*.a.xml", "a.xsd", "b/*.xml", "https://x.test/b.xsd"), parsed);
        assertEquals(parsed, XmlServerSettings.parseAssociations(XmlServerSettings.formatAssociations(parsed)));
        assertEquals(List.of("a.xml", "/b/c.xml"), XmlServerSettings.parseCatalogs(" a.xml ;; /b/c.xml\n"));
    }

    @Test
    void aSessionAnswersAndPushesItsXmlObject() throws Exception {
        var spec = new LspServerRegistry.ServerSpec("xml", List.of("lemminx"), List.of());
        LanguageServerSession session = new LanguageServerSession(spec, root, d -> {}, (t, m) -> {});
        FakeLanguageServer fake = new FakeLanguageServer();
        session.attachForTest(fake, new ServerCapabilities());
        String uri = root.resolve("a.xml").toUri().toString();
        session.setIndentForUri(u -> u.equals(uri) ? new FormattingIndent(3, true) : null);

        Map<String, Object> xml = XmlServerSettings.defaults().section(root, false, true);
        session.setXmlSettings(xml);
        assertEquals(
                Map.of("xml", xml), FakeLanguageServer.last(fake.configurations).getSettings());
        int pushes = fake.configurations.size();
        session.setXmlSettings(xml);
        assertEquals(pushes, fake.configurations.size(), "an unchanged object is not pushed again");

        ConfigurationItem tab = new ConfigurationItem();
        tab.setSection("xml.format.tabSize");
        tab.setScopeUri(uri);
        ConfigurationItem spaces = new ConfigurationItem();
        spaces.setSection("xml.format.insertSpaces");
        spaces.setScopeUri(uri);
        ConfigurationItem noGrammar = new ConfigurationItem();
        noGrammar.setSection("xml.validation.noGrammar");
        assertEquals(
                java.util.Arrays.asList(3, true, "ignore"),
                session.configuration(new ConfigurationParams(List.of(tab, spaces, noGrammar)))
                        .get());
    }
}
