package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.lsp.XmlGrammarBinding;
import com.editora.lsp.XmlServer;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client commands lemminx asks the window for, and the binding wizard's questions (XML review D6, B15). The
 * protocol side — the command reaching the window, the bind edit — is {@code XmlServerFxTest}'s.
 */
@Tag("fx")
class XmlLspActionsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final LspManager lsp = new LspManager((f, d) -> {}, (t, m) -> {});

    @AfterEach
    void tearDown() {
        lsp.shutdownAll();
    }

    private XmlLspActions actions() {
        return actions(null, null);
    }

    /** Actions whose active buffer is {@code buffer}, served at {@code path}; a picked fix is recorded, not applied. */
    private XmlLspActions actions(EditorBuffer buffer, Path path) {
        CoordinatorHostStub host = new CoordinatorHostStub() {
            @Override
            public void setStatus(String message) {
                events.add("status:" + message);
            }

            @Override
            public void openExternalUrl(String url) {
                events.add("browse:" + url);
            }
        };
        return new XmlLspActions(
                host,
                lsp,
                (file, line, column) -> events.add("open:" + file.getFileName() + ":" + line + ":" + column),
                () -> buffer,
                b -> path,
                () -> events.add("references"),
                (file, item) -> events.add("apply:" + file.getFileName() + ":" + item.title()));
    }

    @Test
    void openUriOpensAFileOrAWebPage() throws Exception {
        XmlLspActions xml = actions();
        Path xsd = Files.writeString(dir.resolve("note.xsd"), "<xs:schema/>");
        FxTestSupport.runOnFx(() -> {
            xml.clientCommand("xml", XmlServer.OPEN_URI, List.of(xsd.toUri().toString()));
            xml.clientCommand("xml", XmlServer.OPEN_URI, List.of("https://www.w3.org/2001/XMLSchema.xsd"));
        });
        assertEquals(List.of("open:note.xsd:0:0", "browse:https://www.w3.org/2001/XMLSchema.xsd"), events);
    }

    @Test
    void showReferencesGoesToThePositionAndListsTheReferences() throws Exception {
        XmlLspActions xml = actions();
        Path dtd = Files.writeString(dir.resolve("catalog.dtd"), "<!ELEMENT catalog (item*)>\n");
        var position = com.google.gson.JsonParser.parseString("{\"line\":1,\"character\":10}");
        FxTestSupport.runOnFx(() -> xml.clientCommand(
                "xml", XmlServer.SHOW_REFERENCES, List.of(dtd.toUri().toString(), position)));
        FxTestSupport.runOnFx(() -> {}); // the references run after the open
        assertEquals(List.of("open:catalog.dtd:1:10", "references"), events);
    }

    @Test
    void anotherServersOrAnUnknownCommandDoesNothing() throws Exception {
        XmlLspActions xml = actions();
        FxTestSupport.runOnFx(() -> {
            xml.clientCommand("java", XmlServer.OPEN_URI, List.of("file:///tmp/x.xml"));
            xml.clientCommand("xml", "xml.open.settings", List.of());
        });
        assertEquals(List.of(), events);
    }

    @Test
    void theBindingWizardAsksForAGrammarAndOnlyAsksHowWhenThereIsAChoice() throws Exception {
        XmlLspActions xml = actions();
        Path doc = Files.writeString(dir.resolve("config.xml"), "<config/>\n");
        List<String> asked = new CopyOnWriteArrayList<>();
        Path[] grammar = {dir.resolve("addresses.rng")};
        xml.setPromptsForTest(new XmlLspActions.Prompts() {
            @Override
            public Path chooseGrammar(Path xmlFile) {
                asked.add("grammar for " + xmlFile.getFileName());
                return grammar[0];
            }

            @Override
            public void chooseBinding(Path g, List<String> types, Consumer<String> chosen) {
                asked.add("how " + g.getFileName() + " " + types);
                chosen.accept(null); // cancelled
            }

            @Override
            public void chooseGenerated(
                    Path xmlFile, List<LspManager.CodeActionItem> fixes, Consumer<LspManager.CodeActionItem> chosen) {
                throw new AssertionError("not asked here");
            }
        });
        // RELAX NG binds only by xml-model: no question; with no XML server the bind fails and says so.
        FxTestSupport.runOnFx(() -> xml.clientCommand(
                "xml", XmlServer.BINDING_WIZARD, List.of(doc.toUri().toString())));
        waitFor(() -> events.stream().anyMatch(e -> e.startsWith("status:")));
        assertEquals(List.of("grammar for config.xml"), asked);
        // An XSD can be bound either way: the user is asked, and cancelling binds nothing.
        grammar[0] = dir.resolve("note.xsd");
        events.clear();
        asked.clear();
        FxTestSupport.runOnFx(() -> xml.bindingWizard(doc));
        assertEquals(List.of("grammar for config.xml", "how note.xsd [standard, xml-model]"), asked);
        assertEquals(List.of(), events);
    }

    @Test
    void aQuickFixOnAFileTheXmlServerDoesNotServeIsLeftToTheServer() throws Exception {
        XmlLspActions xml = actions();
        var action = new org.eclipse.lsp4j.CodeAction("Bind to existing grammar/schema");
        action.setCommand(new org.eclipse.lsp4j.Command("Bind", XmlServer.BINDING_WIZARD, List.of("file:///x.xml")));
        var item = new LspManager.CodeActionItem(action.getTitle(), "quickfix", false, action);
        assertFalse(FxTestSupport.callOnFx(() -> xml.runsCodeAction(dir.resolve("x.xml"), item)));
        var other = new LspManager.CodeActionItem("Minify XML", "", false, new org.eclipse.lsp4j.CodeAction("Minify"));
        assertFalse(FxTestSupport.callOnFx(() -> xml.runsCodeAction(dir.resolve("x.xml"), other)));
    }

    /** An XML file open on a fake XML server, with its buffer. */
    private record Served(EditorBuffer buffer, Path path, FakeLanguageServer server) {}

    private Served serve(String name, String text) throws Exception {
        List<FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(lsp);
        lsp.configure(true, java.util.Map.of("xml", "lemminx"));
        Path path = Files.writeString(dir.resolve(name), text);
        lsp.openDocument(path, dir, "xml", text);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(text);
            return b;
        });
        return new Served(buffer, path, fakes.get(0));
    }

    /** Records every question; picks the first generate fix offered. */
    private final class Recording implements XmlLspActions.Prompts {
        final List<String> asked = new CopyOnWriteArrayList<>();

        @Override
        public Path chooseGrammar(Path xmlFile) {
            asked.add("grammar for " + xmlFile.getFileName());
            return dir.resolve("catalog.dtd");
        }

        @Override
        public void chooseBinding(Path g, List<String> types, Consumer<String> chosen) {
            asked.add("how " + g.getFileName() + " " + types);
            chosen.accept(null);
        }

        @Override
        public void chooseGenerated(
                Path xmlFile, List<LspManager.CodeActionItem> fixes, Consumer<LspManager.CodeActionItem> chosen) {
            asked.add("generate "
                    + fixes.stream().map(LspManager.CodeActionItem::title).toList());
            chosen.accept(fixes.get(0));
        }
    }

    /** XML review 2 C7: binding a bound file wrote duplicate xsi attributes and said "Bound". */
    @Test
    void bindToGrammarRefusesAFileThatIsAlreadyBound() throws Exception {
        String note = "<note xmlns:xsi=\"" + XmlGrammarBinding.XSI + "\" xsi:noNamespaceSchemaLocation=\"note.xsd\">\n"
                + "<to>x</to></note>\n";
        Served s = serve("note.xml", note);
        XmlLspActions xml = actions(s.buffer(), s.path());
        Recording prompts = new Recording();
        xml.setPromptsForTest(prompts);

        FxTestSupport.runOnFx(xml::bindGrammar);
        FxTestSupport.runOnFx(xml::generateGrammar);

        assertEquals(List.of(), prompts.asked, "nothing is asked");
        assertEquals(2, events.size(), String.valueOf(events));
        for (String e : events) {
            assertTrue(
                    e.startsWith("status:note.xml")
                            && e.contains("note.xsd")
                            && e.contains("noNamespaceSchemaLocation"),
                    e);
        }
        assertEquals(List.of(), s.server().codeActions, "lemminx is not asked to generate one");
    }

    /** A DOCTYPE with only an internal subset: a DTD is bound by xml-model, never by a second DOCTYPE. */
    @Test
    void aFileWithADoctypeBindsADtdOnlyByXmlModel() throws Exception {
        Served s = serve("c.xml", "<!DOCTYPE c [<!ENTITY e \"x\">]>\n<c>&e;</c>\n");
        XmlLspActions xml = actions(s.buffer(), s.path());
        Recording prompts = new Recording();
        xml.setPromptsForTest(prompts);
        FxTestSupport.runOnFx(xml::bindGrammar);
        assertEquals(List.of("grammar for c.xml"), prompts.asked, "no DOCTYPE choice is offered");
    }

    /**
     * XML review 2 C8: with "Files without a grammar" = Nothing lemminx never reports the diagnostic its generate
     * fixes hang on; XML: Generate Grammar… sends it at the root element, lists only the generate fixes and
     * applies the one picked.
     */
    @Test
    void generateGrammarAsksForTheNoGrammarFixesAndAppliesThePickedOne() throws Exception {
        Served s = serve("config.xml", "<?xml version=\"1.0\"?>\n<config>\n  <item>x</item>\n</config>\n");
        var diagnostic = XmlServer.noGrammarDiagnostic(new org.eclipse.lsp4j.Range());
        var xsd = new org.eclipse.lsp4j.CodeAction("Generate 'config.xsd' and bind with xsi:noNamespaceSchemaLocation");
        xsd.setDiagnostics(List.of(diagnostic));
        var dtd = new org.eclipse.lsp4j.CodeAction("Generate 'config.dtd' and bind with DOCTYPE");
        dtd.setDiagnostics(List.of(diagnostic));
        var wizard = new org.eclipse.lsp4j.CodeAction("Bind to existing grammar/schema");
        wizard.setDiagnostics(List.of(diagnostic));
        wizard.setCommand(new org.eclipse.lsp4j.Command("Bind", XmlServer.BINDING_WIZARD));
        s.server().codeActionResponse = List.of(
                Either.forRight(xsd),
                Either.forRight(wizard),
                Either.forRight(new org.eclipse.lsp4j.CodeAction("Minify XML")),
                Either.forRight(dtd));
        XmlLspActions xml = actions(s.buffer(), s.path());
        Recording prompts = new Recording();
        xml.setPromptsForTest(prompts);

        FxTestSupport.runOnFx(xml::generateGrammar);
        waitFor(() -> events.stream().anyMatch(e -> e.startsWith("apply:")));

        assertEquals(List.of("generate [" + xsd.getTitle() + ", " + dtd.getTitle() + "]"), prompts.asked);
        assertEquals(List.of("apply:config.xml:" + xsd.getTitle()), events);
        var sent = s.server().codeActions.get(0);
        var context = sent.getContext().getDiagnostics();
        assertEquals(1, context.size());
        assertEquals(XmlGrammarBinding.NO_GRAMMAR_CODE, context.get(0).getCode().getLeft());
        var at =
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1, 1), new org.eclipse.lsp4j.Position(1, 7));
        assertEquals(at, context.get(0).getRange(), "the root element's name");
        assertEquals(at, sent.getRange());
    }

    @Test
    void generateGrammarSaysSoWhenThereIsNothingToGenerate() throws Exception {
        Served s = serve("empty.xml", "<!-- nothing yet -->\n");
        XmlLspActions xml = actions(s.buffer(), s.path());
        FxTestSupport.runOnFx(xml::generateGrammar);
        assertEquals(1, events.size());
        assertEquals(List.of(), s.server().codeActions, "no root: lemminx is not asked");

        events.clear();
        Path other = Files.writeString(dir.resolve("other.xml"), "<a/>\n");
        lsp.openDocument(other, dir, "xml", "<a/>\n");
        XmlLspActions none = actions(s.buffer(), other);
        FxTestSupport.runOnFx(none::generateGrammar); // the fake offers no fixes
        waitFor(() -> !events.isEmpty());
        assertTrue(events.get(0).startsWith("status:"), String.valueOf(events));
    }

    private static void waitFor(java.util.function.BooleanSupplier done) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < until) {
            FxTestSupport.runOnFx(() -> {});
            Thread.sleep(20);
        }
        assertTrue(done.getAsBoolean(), "timed out");
    }
}
