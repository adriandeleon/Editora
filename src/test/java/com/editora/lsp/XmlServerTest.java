package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of {@link XmlServer} (XML review A7, A8, A10, B5, B10, D6). */
class XmlServerTest {

    @TempDir
    Path dir;

    @Test
    void dtdsAndStylesheetsOpenUnderVscodeXmlsLanguageIds() {
        assertEquals("dtd", XmlServer.languageId("catalog.dtd"));
        assertEquals("dtd", XmlServer.languageId("chars.ENT"));
        assertEquals("dtd", XmlServer.languageId("xhtml-lat1.mod"));
        assertEquals("xsl", XmlServer.languageId("transform.xsl"));
        assertEquals("xsl", XmlServer.languageId("page.xslt"));
        assertEquals("xml", XmlServer.languageId("note.xml"));
        assertEquals("xml", XmlServer.languageId("App.csproj"));
        assertEquals("xml", XmlServer.languageId("schema.xsd"));
    }

    @Test
    void theProtocolLanguageIdFollowsTheFileOnlyForTheXmlRoute() {
        assertEquals("dtd", LspServerRegistry.protocolLanguageId("xml", Path.of("/p/catalog.dtd")));
        assertEquals("xsl", LspServerRegistry.protocolLanguageId("xml", Path.of("/p/t.xsl")));
        assertEquals("xml", LspServerRegistry.protocolLanguageId("xml", Path.of("/p/note.xml")));
        // the Maven route still opens a pom as xml; other routes are untouched
        assertEquals("xml", LspServerRegistry.protocolLanguageId("maven-pom", Path.of("/p/pom.xml")));
        assertEquals("shellscript", LspServerRegistry.protocolLanguageId("shell", Path.of("/p/a.sh")));
        assertEquals("xml", LspServerRegistry.protocolLanguageId("xml", null));
    }

    @Test
    void xmlKeepsItsServerInTheLargeSourceTierUpToAHundredThousandLines() {
        assertEquals(100_000, LspServerRegistry.heavyFileLspLineLimit("xml"));
        assertTrue(LspServerRegistry.lspAllowedForSize("xml", true, 60_004)); // big.xml from the review
        assertFalse(LspServerRegistry.lspAllowedForSize("xml", true, 100_000));
        assertFalse(LspServerRegistry.lspAllowedForSize("json", true, 12_000)); // others keep the tier's default
    }

    @Test
    void everyXmlFileOutsideTheProjectSharesTheLooseRoot() throws Exception {
        Path loose = Files.createDirectories(dir.resolve("loose"));
        Path project = Files.createDirectories(dir.resolve("project"));
        Path a = Files.createDirectories(dir.resolve("a")).resolve("note.xml");
        Path b = Files.createDirectories(dir.resolve("b/module")).resolve("pom.xml");
        Files.writeString(dir.resolve("b/pom.xml"), "<project/>");
        // No project: one root for every folder (and every Maven module), not one lemminx each.
        assertEquals(loose, XmlServer.sharedRoot(null, a, loose));
        assertEquals(loose, XmlServer.sharedRoot(null, b, loose));
        // In the project: the project's root; outside it: the loose root.
        Path inside =
                Files.createDirectories(project.resolve("src/main/resources")).resolve("beans.xml");
        assertEquals(project.toAbsolutePath().normalize(), XmlServer.sharedRoot(project, inside, loose));
        assertEquals(loose, XmlServer.sharedRoot(project, a, loose));
    }

    @Test
    void theExtendedCapabilitiesDeclareWhatEditoraCarriesOut() {
        Map<String, Object> caps = XmlServer.extendedClientCapabilities();
        assertEquals(Boolean.TRUE, caps.get("bindingWizardSupport"));
        assertEquals(Boolean.TRUE, caps.get("actionableNotificationSupport"));
        assertEquals(Boolean.FALSE, caps.get("openSettingsCommandSupport"));
        assertEquals(Map.of("codeLensKind", Map.of("valueSet", List.of("references"))), caps.get("codeLens"));
        Object init = LspManager.initOptionsFor("xml", List.of());
        assertEquals(Map.of("extendedClientCapabilities", caps), init);
    }

    @Test
    void onlyTheXmlServerIsToldItMayRegisterCommandsDynamically() {
        var xml = LanguageServerSession.clientCapabilities("xml").getWorkspace().getExecuteCommand();
        assertNotNull(xml);
        assertEquals(Boolean.TRUE, xml.getDynamicRegistration());
        assertNull(
                LanguageServerSession.clientCapabilities("java").getWorkspace().getExecuteCommand());
        assertNotNull(
                LanguageServerSession.clientCapabilities().getTextDocument().getDocumentLink());
    }

    @Test
    void aRelaxNgGrammarIsBoundOnlyByXmlModel() {
        assertEquals(List.of("standard", "xml-model"), XmlServer.bindingTypes("note.xsd"));
        assertEquals(List.of("standard", "xml-model"), XmlServer.bindingTypes("catalog.DTD"));
        assertEquals(List.of("xml-model"), XmlServer.bindingTypes("addresses.rng"));
    }

    @Test
    void theAssociateCommandsTextDocumentEditBecomesAWorkspaceEdit() {
        var json =
                com.google.gson.JsonParser.parseString("{\"textDocument\":{\"version\":1,\"uri\":\"file:///p/n.xml\"},"
                        + "\"edits\":[{\"range\":{\"start\":{\"line\":1,\"character\":7},\"end\":{\"line\":1,\"character\":7}},"
                        + "\"newText\":\" xsi:noNamespaceSchemaLocation=\\\"note.xsd\\\"\"}]}");
        var edit = XmlServer.associateEdit(json);
        assertNotNull(edit);
        var change = edit.getDocumentChanges().get(0).getLeft();
        assertEquals("file:///p/n.xml", change.getTextDocument().getUri());
        assertEquals(1, change.getTextDocument().getVersion());
        assertEquals(
                " xsi:noNamespaceSchemaLocation=\"note.xsd\"",
                change.getEdits().get(0).getLeft().getNewText());
        assertNull(XmlServer.associateEdit(null));
        assertNull(XmlServer.associateEdit(com.google.gson.JsonParser.parseString("{\"x\":1}")));
    }

    @Test
    void aTagNameIsReadAroundAPositionInAStartOrEndTag() {
        String text = "<?xml version=\"1.0\"?>\n<note>\n  <to>Tove</to>\n  <a:b x=\"1\"/>\n</note>\n";
        assertEquals("to", XmlServer.tagNameAt(text, new Position(2, 4)));
        assertEquals("to", XmlServer.tagNameAt(text, new Position(2, 3))); // just after the <
        assertEquals("to", XmlServer.tagNameAt(text, new Position(2, 12))); // in </to>
        assertEquals("a:b", XmlServer.tagNameAt(text, new Position(3, 5)));
        assertNull(XmlServer.tagNameAt(text, new Position(2, 8))); // text content
        assertNull(XmlServer.tagNameAt(text, new Position(3, 8))); // an attribute name
        assertNull(XmlServer.tagNameAt(text, new Position(9, 0))); // past the end
    }

    @Test
    void lemminxsAnswerOnATagNameIsTheElementsOwnOtherTag() {
        String text = "<note>\n  <to>Tove</to>\n  <ref id=\"to\"/>\n</note>\n";
        Position onStart = new Position(1, 4);
        List<Range> endTag = List.of(new Range(new Position(1, 14), new Position(1, 16)));
        assertTrue(XmlServer.onlyPairedTag(text, onStart, new ArrayList<>(endTag)));
        // a target in another file, or one that is not a tag of that name, is a real definition
        List<Range> elsewhere = new ArrayList<>();
        elsewhere.add(null);
        assertFalse(XmlServer.onlyPairedTag(text, onStart, elsewhere));
        assertFalse(XmlServer.onlyPairedTag(
                text, onStart, List.of(new Range(new Position(2, 11), new Position(2, 13))))); // the "to" value
        assertFalse(XmlServer.onlyPairedTag(text, new Position(1, 7), endTag)); // not on a tag name
        assertFalse(XmlServer.onlyPairedTag(null, onStart, endTag));
    }

    @Test
    void theLinkUnderThePositionIsFound() {
        var link = new DocumentLink(new Range(new Position(1, 91), new Position(1, 99)), "file:///p/note.xsd");
        assertEquals(link, XmlServer.linkAt(List.of(link), new Position(1, 95)));
        assertEquals(link, XmlServer.linkAt(List.of(link), new Position(1, 99))); // at its end
        assertNull(XmlServer.linkAt(List.of(link), new Position(1, 90)));
        assertNull(XmlServer.linkAt(null, new Position(1, 95)));
    }

    /** XML review 2 B3: the href of an xsl:include or xsl:import, at the caret. */
    @Test
    void theHrefOfAStylesheetIncludeIsFoundAtTheCaret() {
        String xsl = "<xsl:stylesheet>\n  <xsl:include href=\"common/base.xsl\"/>\n  <xsl:import href='more.xsl'/>\n"
                + "  <xs:include schemaLocation=\"a.xsd\"/>\n  <xsl:template match=\"/\" href=\"x.xsl\"/>\n";
        int inInclude = xsl.indexOf("common/") + 3;
        assertEquals("common/base.xsl", XmlServer.includeHref(xsl, inInclude));
        assertEquals("common/base.xsl", XmlServer.includeHref(xsl, xsl.indexOf("common/")), "at the value's start");
        assertEquals("more.xsl", XmlServer.includeHref(xsl, xsl.indexOf("more.xsl") + 8), "at its end, single quotes");
        assertNull(XmlServer.includeHref(xsl, xsl.indexOf("xsl:include") + 2), "on the element name");
        assertNull(XmlServer.includeHref(xsl, xsl.indexOf("a.xsd")), "an XSD include is lemminx's own link");
        assertNull(XmlServer.includeHref(xsl, xsl.indexOf("x.xsl")), "not an include");
        assertNull(XmlServer.includeHref(xsl, xsl.indexOf("<xsl:import") - 1), "between tags");
        assertNull(XmlServer.includeHref(xsl, -1));
    }

    @Test
    void anIncludeResolvesToAnExistingFileNextToTheStylesheet(@TempDir Path dir) throws Exception {
        Path base = Files.createDirectories(dir.resolve("common")).resolve("base file.xsl");
        Files.writeString(base, "<xsl:stylesheet/>");
        String doc = dir.resolve("main.xsl").toUri().toString();
        String target = XmlServer.resolveInclude(doc, "common/base file.xsl", WireUris.LOCAL);
        assertEquals(base.toUri(), java.net.URI.create(target));
        assertNull(XmlServer.resolveInclude(doc, "common/missing.xsl", WireUris.LOCAL), "no such file");
        assertNull(XmlServer.resolveInclude(doc, "https://example.com/a.xsl", WireUris.LOCAL), "a web address");
    }

    /** XML review 2 C8: the diagnostic that brings lemminx's generate fixes, and which fixes those are. */
    @Test
    void generateGrammarFixesAreTheNoGrammarFixesButTheBindingWizard() {
        Range root = new Range(new Position(1, 1), new Position(1, 7));
        var d = XmlServer.noGrammarDiagnostic(root);
        assertEquals("NoGrammarConstraints", d.getCode().getLeft());
        assertEquals("xml", d.getSource());
        assertEquals(root, d.getRange());

        var generate =
                new org.eclipse.lsp4j.CodeAction("Generate 'config.xsd' and bind with xsi:noNamespaceSchemaLocation");
        generate.setDiagnostics(List.of(d));
        assertTrue(XmlServer.generatesGrammar(generate));
        var wizard = new org.eclipse.lsp4j.CodeAction("Bind to existing grammar/schema");
        wizard.setDiagnostics(List.of(d));
        wizard.setCommand(new org.eclipse.lsp4j.Command("Bind", XmlServer.BINDING_WIZARD));
        assertFalse(XmlServer.generatesGrammar(wizard));
        assertFalse(XmlServer.generatesGrammar(new org.eclipse.lsp4j.CodeAction("Minify XML")));
        assertFalse(XmlServer.generatesGrammar(new org.eclipse.lsp4j.Command("x", "y")));
    }
}
