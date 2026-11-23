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
        assertFalse(LspServerRegistry.lspAllowedForSize("xml", true, 100_001));
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
        Object init = ServerInitOptions.initOptionsFor("xml", List.of());
        assertEquals(Map.of("extendedClientCapabilities", caps), init);
    }

    @Test
    void onlyTheLemminxServersAreToldTheyMayRegisterCommandsDynamically() {
        var xml = LanguageServerSession.clientCapabilities("xml").getWorkspace().getExecuteCommand();
        assertNotNull(xml);
        assertEquals(Boolean.TRUE, xml.getDynamicRegistration());
        // the pom server is lemminx too: without it "XML: Revalidate File" could not run on a pom (review 2, D15)
        var pom = LanguageServerSession.clientCapabilities("maven-pom")
                .getWorkspace()
                .getExecuteCommand();
        assertNotNull(pom);
        assertEquals(Boolean.TRUE, pom.getDynamicRegistration());
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

    /**
     * XML/pom review 3, B22 + C26: a percent-encoded href ({@code sub%20dir/lib.xsl}) re-encoded its {@code %}, a
     * {@code file:} href was refused as a scheme, and an XInclude in the default namespace was not an include.
     */
    @Test
    void anIncludeHrefIsAUriReference(@TempDir Path dir) throws Exception {
        Path lib = Files.createDirectories(dir.resolve("sub dir")).resolve("lib.xsl");
        Files.writeString(lib, "<xsl:stylesheet/>");
        Path chap = Files.writeString(dir.resolve("chap.xml"), "<chapter/>");
        String doc = dir.resolve("main.xsl").toUri().toString();
        assertEquals(
                lib.toUri(), java.net.URI.create(XmlServer.resolveInclude(doc, "sub%20dir/lib.xsl", WireUris.LOCAL)));
        assertEquals(
                lib.toUri(), java.net.URI.create(XmlServer.resolveInclude(doc, "sub dir/lib.xsl", WireUris.LOCAL)));
        assertEquals(
                lib.toUri(),
                java.net.URI.create(XmlServer.resolveInclude(doc, lib.toUri().toString(), WireUris.LOCAL)),
                "a file: URI");
        assertEquals(
                chap.toUri(),
                java.net.URI.create(XmlServer.resolveInclude(doc, "chap.xml#xpointer(/chapter)", WireUris.LOCAL)),
                "without its fragment");
        assertNull(XmlServer.resolveInclude(doc, "http://example.com/lib.xsl", WireUris.LOCAL));
        assertNull(XmlServer.resolveInclude(doc, "file:lib.xsl", WireUris.LOCAL), "an opaque file: URI");
        assertNull(XmlServer.resolveInclude(doc, "missing.xsl", WireUris.LOCAL));
        assertEquals(dir.resolve("missing.xsl").toUri(), java.net.URI.create(XmlServer.includeUri(doc, "missing.xsl")));

        String book = "<book>\n  <include xmlns=\"http://www.w3.org/2001/XInclude\" href=\"chap.xml\"/>\n"
                + "  <include href=\"other.xml\"/>\n</book>\n";
        assertEquals("chap.xml", XmlServer.includeHref(book, book.indexOf("chap.xml") + 2), "by its namespace");
        assertNull(XmlServer.includeHref(book, book.indexOf("other.xml") + 2), "another vocabulary's include");
        String inherited = "<doc xmlns=\"http://www.w3.org/2001/XInclude\">\n  <include href=\"a.xml\"/>\n</doc>";
        assertEquals("a.xml", XmlServer.includeHref(inherited, inherited.indexOf("a.xml")), "declared on an ancestor");
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

    /** XML/pom review 3, C24: the generate fix that binds with a DOCTYPE, by lemminx's resolver id or its title. */
    @Test
    void theDoctypeGenerateFixIsKnownByItsResolverOrTitle() {
        var unresolved = new org.eclipse.lsp4j.CodeAction("Generate 'a.dtd' and bind with DOCTYPE");
        assertTrue(XmlServer.bindsWithDoctype(unresolved));
        var byData = new org.eclipse.lsp4j.CodeAction("Generate");
        byData.setData(java.util.Map.of(
                "participantId",
                "org.eclipse.lemminx.extensions.contentmodel.participants.codeactions.nogrammarconstraints"
                        + ".GenerateDocTypeCodeActionResolver"));
        assertTrue(XmlServer.bindsWithDoctype(byData));
        assertFalse(XmlServer.bindsWithDoctype(
                new org.eclipse.lsp4j.CodeAction("Generate 'a.dtd' and bind with xml-model")));
        assertFalse(XmlServer.bindsWithDoctype(null));
    }

    /** XML/pom review 4, B32: where a document link can be — a quoted value of the markup the offset is in. */
    @Test
    void aQuotedValueIsWhereALinkCanBe() {
        String xsd = "<xs:include schemaLocation=\"other.xsd\"/>\n<a>text \"quoted\"</a>\n"
                + "<!DOCTYPE a SYSTEM 'a.dtd'>\n<?xml-model href=\"g.rng\"?>";
        assertTrue(XmlServer.inQuotedValue(xsd, xsd.indexOf("other.xsd") + 2));
        assertTrue(XmlServer.inQuotedValue(xsd, xsd.indexOf("other.xsd")), "at the value's start");
        assertTrue(XmlServer.inQuotedValue(xsd, xsd.indexOf("other.xsd") + 9), "at its end");
        assertFalse(XmlServer.inQuotedValue(xsd, xsd.indexOf("schemaLocation") + 3), "the attribute's name");
        assertFalse(XmlServer.inQuotedValue(xsd, xsd.indexOf("quoted")), "quotes in text content");
        assertTrue(XmlServer.inQuotedValue(xsd, xsd.indexOf("a.dtd") + 1), "a DOCTYPE system id");
        assertTrue(XmlServer.inQuotedValue(xsd, xsd.indexOf("g.rng") + 1), "an xml-model href");
        assertFalse(XmlServer.inQuotedValue(xsd, 0));
        assertFalse(XmlServer.inQuotedValue(xsd, xsd.length() + 1));
    }

    /** XML/pom review 4, B33: a link target that is a missing local file is its name, not a file to open. */
    @Test
    void aLinkToAMissingLocalFileIsItsName() throws Exception {
        Path there = Files.writeString(dir.resolve("note.xsd"), "<xs:schema/>");
        var found = XmlServer.linkTarget(there.toUri().toString(), WireUris.LOCAL);
        assertNotNull(found.location());
        assertEquals(there.toUri(), java.net.URI.create(found.location().getUri()));
        var missing = XmlServer.linkTarget(dir.resolve("inc/nochap.xml").toUri().toString(), WireUris.LOCAL);
        assertNull(missing.location());
        assertEquals("nochap.xml", missing.missing());
        assertFalse(XmlServer.linkTarget(dir.toUri().toString(), WireUris.LOCAL).present(), "a folder");
        assertFalse(
                XmlServer.linkTarget("https://example.com/a.xsd", WireUris.LOCAL)
                        .present(),
                "a web page");
        assertFalse(XmlServer.linkTarget(null, WireUris.LOCAL).present());
    }

    /** XML/pom review 4, B35: {@code file://localhost/}, a query, and a {@code >} in another quoted attribute. */
    @Test
    void includeHrefEdgeCases(@TempDir Path dir) throws Exception {
        Path lib = Files.writeString(dir.resolve("lib.xsl"), "<xsl:stylesheet/>");
        String doc = dir.resolve("main.xsl").toUri().toString();
        String abs = lib.toAbsolutePath().toString().replace('\\', '/');
        assertEquals(
                lib.toUri(),
                java.net.URI.create(XmlServer.resolveInclude(
                        doc, "file://localhost" + (abs.startsWith("/") ? "" : "/") + abs, WireUris.LOCAL)),
                "RFC 8089's local host");
        assertEquals(
                lib.toUri(),
                java.net.URI.create(XmlServer.resolveInclude(doc, "lib.xsl?v=1", WireUris.LOCAL)),
                "a query");
        assertEquals(
                lib.toUri(),
                java.net.URI.create(XmlServer.resolveInclude(doc, "lib.xsl?v=1#frag", WireUris.LOCAL)),
                "a query and a fragment");

        String xi = "<doc xmlns:xi=\"http://www.w3.org/2001/XInclude\">\n"
                + "  <xi:include xpointer='a>b' href='x.xml'/>\n</doc>";
        assertEquals("x.xml", XmlServer.includeHref(xi, xi.indexOf("x.xml") + 1), "a '>' in a quoted value");
    }

    /** XML/pom review 4, C33: the XSD generate fixes, and a root element in a namespace. */
    @Test
    void theXsdGenerateFixesAreLeftOutForANamespacedRoot() {
        var xsi = new org.eclipse.lsp4j.CodeAction("Generate 'note.xsd' and bind with xsi:noNamespaceSchemaLocation");
        var model = new org.eclipse.lsp4j.CodeAction("Generate 'note.xsd' and bind with xml-model");
        var byData = new org.eclipse.lsp4j.CodeAction("Generate");
        byData.setData(Map.of(
                "participantId",
                "org.eclipse.lemminx.extensions.contentmodel.participants.codeactions.nogrammarconstraints"
                        + ".GenerateXMLModelWithXSDCodeActionResolver"));
        assertTrue(XmlServer.generatesXsd(xsi));
        assertTrue(XmlServer.generatesXsd(model));
        assertTrue(XmlServer.generatesXsd(byData));
        assertFalse(
                XmlServer.generatesXsd(new org.eclipse.lsp4j.CodeAction("Generate 'note.dtd' and bind with DOCTYPE")));
        assertFalse(
                XmlServer.generatesXsd(new org.eclipse.lsp4j.CodeAction("Generate 'note.rng' and bind with RelaxNG")));
        assertFalse(XmlServer.generatesXsd(null));

        assertTrue(rootNs("<?xml version=\"1.0\"?>\n<note xmlns=\"urn:example:note\">\n  <to/>\n</note>"));
        assertTrue(rootNs("<n:note xmlns:n='urn:example:note'><n:to/></n:note>"), "a prefixed root");
        assertFalse(rootNs("<note>\n  <to xmlns=\"urn:x\"/>\n</note>"), "a namespace below the root only");
        assertFalse(rootNs("<note xmlns=\"\" a='x>y'/>"), "the empty namespace is none");
        assertFalse(
                rootNs("<note xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"/>"), "only a prefix declared");
    }

    private static boolean rootNs(String text) {
        int[] root = XmlGrammarBinding.rootName(text);
        assertNotNull(root);
        return XmlServer.rootInNamespace(text, root[0], root[1]);
    }

    /** XML/pom review 4, B31: the watched changes that are an open grammar's, still on disk. */
    @Test
    void anOpenGrammarChangedOnDiskIsPickedOut() {
        Path xsd = dir.resolve("note.xsd");
        Path dtd = dir.resolve("gone.dtd");
        Path xml = dir.resolve("note.xml");
        Path closed = dir.resolve("closed.rng");
        var open = java.util.Set.of(LspManager.uri(xsd), LspManager.uri(dtd), LspManager.uri(xml));
        var picked = LspFileWatching.openGrammarChanges(
                List.of(
                        new LspManager.WatchedFile(xsd, LspManager.WatchedKind.CHANGED),
                        new LspManager.WatchedFile(dtd, LspManager.WatchedKind.DELETED),
                        new LspManager.WatchedFile(xml, LspManager.WatchedKind.CHANGED),
                        new LspManager.WatchedFile(closed, LspManager.WatchedKind.CREATED)),
                open::contains);
        assertEquals(List.of(new LspManager.WatchedFile(xsd, LspManager.WatchedKind.CHANGED)), picked);
    }

    // --- Go to Definition against an in-process server ---------------------------------------------------------

    private record Served(LanguageServerSession session, FakeLanguageServer fake, String uri) {}

    private static Served served(Path file, String text) {
        var caps = new org.eclipse.lsp4j.ServerCapabilities();
        caps.setDocumentLinkProvider(new org.eclipse.lsp4j.DocumentLinkOptions(false));
        var spec = new LspServerRegistry.ServerSpec(XmlServer.SERVER_ID, List.of("lemminx"), List.of());
        var session = new LanguageServerSession(spec, file.getParent(), d -> {}, (type, message) -> {});
        var fake = new FakeLanguageServer();
        session.attachForTest(fake, caps);
        String uri = DocumentUris.of(file);
        session.didOpen(uri, "xml", text);
        return new Served(session, fake, uri);
    }

    private static List<? extends org.eclipse.lsp4j.Location> definitionAt(Served s, Position pos, List<String> missing)
            throws Exception {
        var found = XmlServer.definition(s.session(), s.uri(), pos, missing::add)
                .get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(found.isLeft(), "answered as locations");
        return found.getLeft();
    }

    private static Range range(int line, int from, int to) {
        return new Range(new Position(line, from), new Position(line, to));
    }

    @Test
    void aLinkToTheElementsOtherTagFallsToTheGrammarDeclaration(@TempDir Path dir) throws Exception {
        Served s = served(dir.resolve("doc.xml"), "<root>\n</root>\n");
        s.fake().definitionLinksResponse =
                List.of(new org.eclipse.lsp4j.LocationLink(s.uri(), range(1, 0, 7), range(1, 2, 6)));
        var declared = new org.eclipse.lsp4j.Location("file:///schemas/doc.xsd", range(3, 0, 0));
        s.fake().typeDefinitionResponse = List.of(declared);

        assertEquals(List.of(declared), definitionAt(s, new Position(0, 2), new ArrayList<>()));
        assertEquals(1, s.fake().typeDefinitions.size());
    }

    @Test
    void aLinkElsewhereOrWithoutASelectionRangeIsTheAnswer(@TempDir Path dir) throws Exception {
        Served s = served(dir.resolve("doc.xml"), "<root>\n</root>\n");
        var elsewhere = new org.eclipse.lsp4j.LocationLink();
        elsewhere.setTargetUri("file:///schemas/doc.xsd");
        elsewhere.setTargetRange(range(4, 0, 9));
        s.fake().definitionLinksResponse = new ArrayList<>(java.util.Arrays.asList(elsewhere, null));

        var found = XmlServer.definition(s.session(), s.uri(), new Position(0, 2), m -> {})
                .get(30, java.util.concurrent.TimeUnit.SECONDS);

        assertTrue(found.isRight(), "the server's own answer stands");
        assertEquals(elsewhere, found.getRight().get(0));
        assertTrue(s.fake().typeDefinitions.isEmpty());
    }

    @Test
    void aLocationWithoutARangeIsNotTheOtherTag(@TempDir Path dir) throws Exception {
        Served s = served(dir.resolve("doc.xml"), "<root>\n</root>\n");
        var noRange = new org.eclipse.lsp4j.Location();
        noRange.setUri(s.uri());
        var noUri = new org.eclipse.lsp4j.Location();
        noUri.setRange(range(1, 2, 6));
        s.fake().definitionResponse = new ArrayList<>(java.util.Arrays.asList(noRange, noUri, null));

        assertEquals(3, definitionAt(s, new Position(0, 2), new ArrayList<>()).size());
        assertTrue(s.fake().typeDefinitions.isEmpty(), "a target in no known document is no paired tag");
    }

    @Test
    void aLinkInAQuotedValueToAMissingFileIsNamed(@TempDir Path dir) throws Exception {
        String text = "<?xml-model href=\"missing.rng\"?>\n<root/>\n";
        Served s = served(dir.resolve("doc.xml"), text);
        s.fake().documentLinkResponse =
                List.of(new DocumentLink(range(0, 18, 29), DocumentUris.of(dir.resolve("missing.rng"))));
        List<String> missing = new ArrayList<>();

        assertEquals(List.of(), definitionAt(s, new Position(0, 20), missing));
        assertEquals(List.of("missing.rng"), missing);
        assertTrue(s.fake().typeDefinitions.isEmpty(), "the link in the value is asked first and ends the search");
    }

    @Test
    void aLinkToAnExistingFileOpensItsStart(@TempDir Path dir) throws Exception {
        Path schema = Files.writeString(dir.resolve("doc.xsd"), "<xs:schema/>");
        String text = "<root xsi:noNamespaceSchemaLocation=\"doc.xsd\">\n</root>\n";
        Served s = served(dir.resolve("doc.xml"), text);
        s.fake().documentLinkResponse = List.of(new DocumentLink(range(0, 37, 44), DocumentUris.of(schema)));

        var found = definitionAt(s, new Position(0, 40), new ArrayList<>());

        assertEquals(1, found.size());
        assertEquals(DocumentUris.of(schema), found.get(0).getUri());
        assertEquals(range(0, 0, 0), found.get(0).getRange());

        List<String> missing = new ArrayList<>();
        assertEquals(List.of(), definitionAt(s, new Position(1, 3), missing), "on a tag name no link is asked first");
        assertEquals(List.of(), missing);
    }

    @Test
    void aStylesheetIncludeIsFollowedWhenNothingElseAnswers(@TempDir Path dir) throws Exception {
        Path lib = Files.writeString(dir.resolve("lib.xsl"), "<xsl:stylesheet/>");
        String text = "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">\n"
                + "  <xsl:include href=\"lib.xsl\"/>\n</xsl:stylesheet>\n";
        Served s = served(dir.resolve("main.xsl"), text);

        var found = definitionAt(s, new Position(1, 22), new ArrayList<>());

        assertEquals(1, found.size());
        assertEquals(lib, Path.of(java.net.URI.create(found.get(0).getUri())));
        assertNull(XmlServer.missingInclude(s.session(), s.uri(), new Position(1, 22)), "it exists");
    }

    @Test
    void aMissingIncludeIsNamedForTheMessage(@TempDir Path dir) throws Exception {
        String text = "<xsl:stylesheet xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">\n"
                + "  <xsl:include href=\"gone.xsl\"/>\n</xsl:stylesheet>\n";
        Served s = served(dir.resolve("main.xsl"), text);
        assertEquals("gone.xsl", XmlServer.missingInclude(s.session(), s.uri(), new Position(1, 22)));
        assertNull(XmlServer.missingInclude(s.session(), s.uri(), new Position(0, 2)), "not on an include");
        assertNull(XmlServer.missingInclude(s.session(), "file:///not/open.xsl", new Position(0, 2)));
        assertEquals(List.of(), definitionAt(s, new Position(1, 22), new ArrayList<>()));
    }
}
