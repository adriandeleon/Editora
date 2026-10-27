package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.editora.text.LspTextEdit;
import org.eclipse.lsp4j.DocumentLink;
import org.eclipse.lsp4j.DocumentLinkOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The XML server's protocol round-trips through {@link LspManager} against a {@link FakeLanguageServer} (XML review
 * A8, B5, B10, B15, D6): Go to Definition's fallbacks to the grammar declaration and to the linked schema, the
 * {@code dtd} language id, lemminx's command registrations, the bind-to-grammar edit and its client commands.
 */
@Tag("fx")
class XmlServerFxTest {

    private static final String NOTE = "<?xml version=\"1.0\"?>\n"
            + "<note xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"note.xsd\">\n"
            + "  <to>Tove</to>\n"
            + "</note>\n";

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();
    private Path file;
    private Path xsd;

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            ServerCapabilities caps = new ServerCapabilities();
            caps.setDocumentLinkProvider(new DocumentLinkOptions(true));
            session.attachForTest(fake, caps);
        });
        manager.configure(true, Map.of("xml", "lemminx"));
        file = root.resolve("note.xml");
        Files.writeString(file, NOTE);
        xsd = root.resolve("note.xsd");
        Files.writeString(xsd, "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\"/>\n");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    private FakeLanguageServer open() {
        manager.openDocument(file, root, "xml", NOTE);
        return fakes.get(0);
    }

    private <T> T await(Consumer<Consumer<T>> request) throws Exception {
        var result = new AtomicReference<T>();
        var latch = new CountDownLatch(1);
        request.accept(v -> {
            result.set(v);
            latch.countDown();
        });
        assertTrue(latch.await(10, TimeUnit.SECONDS), "the callback never fired");
        return result.get();
    }

    private static Range range(int line, int from, int to) {
        return new Range(new Position(line, from), new Position(line, to));
    }

    @Test
    void definitionOnATagGoesToTheGrammarDeclarationNotTheElementsOwnEndTag() throws Exception {
        var fake = open();
        fake.definitionResponse = List.of(new Location(file.toUri().toString(), range(2, 12, 14))); // </to>
        fake.typeDefinitionResponse = List.of(new Location(xsd.toUri().toString(), range(0, 4, 9)));

        List<LspManager.Target> targets = await(cb -> manager.definition(file, 2, 4, cb));

        assertEquals(1, targets.size());
        assertEquals(xsd, targets.get(0).file());
        assertEquals(1, fake.typeDefinitions.size());
    }

    @Test
    void withNoGrammarDeclarationTheEndTagStillAnswers() throws Exception {
        var fake = open();
        fake.definitionResponse = List.of(new Location(file.toUri().toString(), range(2, 12, 14)));

        List<LspManager.Target> targets = await(cb -> manager.definition(file, 2, 4, cb));

        assertEquals(List.of(new LspManager.Target(file, 2, 12)), targets);
    }

    @Test
    void aRealDefinitionIsKeptWithoutAskingForTheType() throws Exception {
        var fake = open();
        fake.definitionResponse = List.of(new Location(xsd.toUri().toString(), range(0, 1, 3)));

        List<LspManager.Target> targets = await(cb -> manager.definition(file, 2, 4, cb));

        assertEquals(xsd, targets.get(0).file());
        assertEquals(0, fake.typeDefinitions.size());
    }

    @Test
    void definitionOnASchemaLocationOpensTheLinkedSchema() throws Exception {
        var fake = open();
        fake.documentLinkResponse =
                List.of(new DocumentLink(range(1, 91, 99), xsd.toUri().toString()));

        List<LspManager.Target> targets = await(cb -> manager.definition(file, 1, 94, cb));

        assertEquals(List.of(new LspManager.Target(xsd, 0, 0)), targets);
    }

    @Test
    void anUnresolvedLinkIsResolvedAndAWebLinkIsNotFollowed() throws Exception {
        var fake = open();
        var unresolved = new DocumentLink(range(1, 91, 99));
        unresolved.setData(xsd.toUri().toString());
        fake.documentLinkResponse = List.of(unresolved);
        List<LspManager.Target> resolved = await(cb -> manager.definition(file, 1, 94, cb));
        assertEquals(List.of(new LspManager.Target(xsd, 0, 0)), resolved);

        fake.documentLinkResponse = List.of(new DocumentLink(range(1, 91, 99), "https://example.com/note.xsd"));
        List<LspManager.Target> none = await(cb -> manager.definition(file, 1, 94, cb));
        assertEquals(List.of(), none);
    }

    /** XML review 2 B3: lemminx has no link for xsl:include/xsl:import; Go to Definition opens the file anyway. */
    @Test
    void definitionOnAnXslIncludeHrefOpensTheStylesheet() throws Exception {
        Path base = root.resolve("base.xsl");
        Files.writeString(base, "<xsl:stylesheet/>\n");
        Path main = root.resolve("main.xsl");
        String text = "<xsl:stylesheet>\n  <xsl:import href=\"base.xsl\"/>\n</xsl:stylesheet>\n";
        Files.writeString(main, text);
        manager.openDocument(main, root, "xml", text);

        List<LspManager.Target> targets = await(cb -> manager.definition(main, 1, 22, cb));

        assertEquals(List.of(new LspManager.Target(base, 0, 0)), targets);
        assertEquals(1, fakes.get(0).typeDefinitions.size(), "lemminx is asked first");
    }

    @Test
    void aDtdOpensUnderTheDtdLanguageId() throws Exception {
        Path dtd = root.resolve("catalog.dtd");
        Files.writeString(dtd, "<!ELEMENT catalog (item*)>\n");
        manager.openDocument(dtd, root, "xml", "<!ELEMENT catalog (item*)>\n");
        var fake = fakes.get(0);
        assertEquals("dtd", fake.opened.get(0).getTextDocument().getLanguageId());
    }

    @Test
    void lemminxsCommandRegistrationsAddUpAndCanBeWithdrawn() throws Exception {
        open();
        LanguageServerSession s = manager.sessionForTest(file);
        s.registerCapability(new RegistrationParams(List.of(new Registration(
                "a",
                "workspace/executeCommand",
                new ExecuteCommandOptions(List.of(XmlServer.VALIDATE_CURRENT, XmlServer.VALIDATE_ALL))))));
        s.registerCapability(new RegistrationParams(List.of(new Registration(
                "b", "workspace/executeCommand", new ExecuteCommandOptions(List.of(XmlServer.ASSOCIATE_GRAMMAR))))));

        assertTrue(manager.supportsCommand(file, XmlServer.VALIDATE_CURRENT));
        assertTrue(manager.supportsCommand(file, XmlServer.ASSOCIATE_GRAMMAR), "the second registration adds");

        s.unregisterCapability(new UnregistrationParams(List.of(new Unregistration("b", "workspace/executeCommand"))));
        assertTrue(manager.supportsCommand(file, XmlServer.VALIDATE_ALL));
        assertEquals(
                List.of(XmlServer.VALIDATE_CURRENT, XmlServer.VALIDATE_ALL),
                s.capabilities().getExecuteCommandProvider().getCommands());
    }

    @Test
    void bindingToAGrammarAppliesTheEditLemminxComputes() throws Exception {
        var fake = open();
        var applied = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler((mapped, done) -> {
            applied.set(mapped);
            done.accept(true);
        });
        var seen = new AtomicReference<ExecuteCommandParams>();
        fake.executeCommandHandler = params -> {
            seen.set(params);
            return com.google.gson.JsonParser.parseString(
                    "{\"textDocument\":{\"version\":1,\"uri\":\""
                            + file.toUri() + "\"},\"edits\":[{\"range\":{\"start\":{\"line\":0,\"character\":21},"
                            + "\"end\":{\"line\":0,\"character\":21}},\"newText\":\"\\n<?xml-model href=\\\"note.xsd\\\"?>\"}]}");
        };

        Boolean ok = await(cb -> manager.xmlAssociateGrammar(file, xsd, XmlServer.BINDING_XML_MODEL, cb));

        assertTrue(ok);
        assertEquals(XmlServer.ASSOCIATE_GRAMMAR, seen.get().getCommand());
        assertEquals(3, seen.get().getArguments().size());
        assertEquals(XmlServer.BINDING_XML_MODEL, seen.get().getArguments().get(2));
        assertNotNull(applied.get());
        assertEquals(file, applied.get().edits().get(0).file());
        assertEquals(
                List.of(new LspTextEdit(0, 21, 0, 21, "\n<?xml-model href=\"note.xsd\"?>")),
                applied.get().edits().get(0).edits());
    }

    @Test
    void xmlClientCommandsReachTheWindow() throws Exception {
        open();
        var asked = new CopyOnWriteArrayList<String>();
        manager.setClientUi(new LspClientUi() {
            @Override
            public java.util.concurrent.CompletableFuture<String> chooseAction(
                    String serverId, org.eclipse.lsp4j.MessageType type, String message, List<String> actions) {
                asked.add("choose:" + String.join("|", actions));
                return java.util.concurrent.CompletableFuture.completedFuture(actions.get(0));
            }

            @Override
            public java.util.concurrent.CompletableFuture<String> inputBox(
                    String serverId, String prompt, String value, String placeHolder) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public java.util.concurrent.CompletableFuture<String> quickPick(
                    String serverId, String placeHolder, List<PickItem> items) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }

            @Override
            public void openLocation(String serverId, Path file, int line, int column) {}

            @Override
            public java.util.concurrent.CompletableFuture<Object> clientCommand(
                    String serverId, String command, List<Object> arguments) {
                asked.add(serverId + ":" + command + ":" + arguments.size());
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        });
        LanguageServerSession s = manager.sessionForTest(file);
        s.xmlExecuteClientCommand(new ExecuteCommandParams(
                        XmlServer.OPEN_URI, List.of(xsd.toUri().toString())))
                .get(10, TimeUnit.SECONDS);

        // An actionable notification offers a client command as a button, and the chosen one runs in the window.
        var note = new LanguageServerSession.LanguageActionableNotification();
        note.message = "Bind this document to a grammar?";
        note.commands = List.of(new org.eclipse.lsp4j.Command(
                "Bind…", XmlServer.BINDING_WIZARD, List.of(file.toUri().toString())));
        s.xmlActionableNotification(note);
        long until = System.currentTimeMillis() + 10_000;
        while (asked.size() < 3 && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }

        assertEquals(
                List.of("xml:" + XmlServer.OPEN_URI + ":1", "choose:Bind…", "xml:" + XmlServer.BINDING_WIZARD + ":1"),
                asked);
    }
}
