package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import com.editora.lsp.LspManager;
import com.editora.lsp.XmlServer;
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
                () -> null,
                b -> null,
                () -> events.add("references"));
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

    private static void waitFor(java.util.function.BooleanSupplier done) throws Exception {
        long until = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean() && System.currentTimeMillis() < until) {
            FxTestSupport.runOnFx(() -> {});
            Thread.sleep(20);
        }
        assertTrue(done.getAsBoolean(), "timed out");
    }
}
