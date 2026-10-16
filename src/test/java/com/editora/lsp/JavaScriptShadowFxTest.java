package com.editora.lsp;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.application.Platform;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An extensionless {@code java --source N} script is shown to jdtls as a {@code .java} stand-in
 * ({@link JavaScriptShadow}): the server only ever hears of the stand-in, with the shebang commented out,
 * and everything it says about the stand-in reaches the script.
 */
@Tag("fx")
class JavaScriptShadowFxTest {

    static final String SCRIPT = "#!/usr/bin/env -S java --source 25\nvoid main() {\n    IO.println(1);\n}\n";

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();
    private final List<Path> diagnosed = new CopyOnWriteArrayList<>();
    private final AtomicReference<LanguageServerSession> session = new AtomicReference<>();
    private final ServerCapabilities caps = new ServerCapabilities();
    private Path script;

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> diagnosed.add(f), (t, m) -> {});
        caps.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        caps.setRenameProvider(true);
        caps.setCodeActionProvider(true);
        manager.setSessionStarterForTest(s -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            session.set(s);
            s.setRawSinkForTest(fake.rawSink());
            s.attachForTest(fake, caps);
        });
        manager.configure(true, Map.of("java", "jdtls"));
        script = root.resolve("launcher");
        Files.writeString(script, SCRIPT);
        manager.openDocument(script, root, "java", SCRIPT);
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    private FakeLanguageServer fake() {
        return fakes.get(0);
    }

    private String standInUri() {
        return fake().opened.get(0).getTextDocument().getUri();
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

    private static void drainFx() throws Exception {
        var drained = new CountDownLatch(1);
        Platform.runLater(drained::countDown);
        assertTrue(drained.await(10, TimeUnit.SECONDS));
    }

    @Test
    void theScriptOpensAsAJavaFileOutsideTheProjectWithItsShebangCommentedOut() throws Exception {
        var opened = fake().opened.get(0).getTextDocument();
        Path standIn = DocumentUris.toPath(opened.getUri());

        assertNotNull(standIn);
        assertEquals("launcher.java", standIn.getFileName().toString());
        assertTrue(Files.isRegularFile(standIn), "jdtls makes a compilation unit only of a readable file");
        assertFalse(standIn.startsWith(root), "nothing is written beside the script");
        assertEquals(JavaScriptShadow.wireText(SCRIPT), opened.getText());
        assertEquals(SCRIPT.length(), opened.getText().length());
        assertTrue(opened.getText().startsWith("/*"));
        assertEquals(opened.getUri(), manager.serverUri(script), "a command about the script names the stand-in");
    }

    @Test
    void theServerNeverHearsOfTheScriptItself() throws Exception {
        manager.changeDocument(script, SCRIPT.replace("1", "2"));
        manager.saveDocument(script, SCRIPT.replace("1", "2"));
        var pending = new java.util.concurrent.atomic.AtomicInteger();
        manager.hover(script, 2, 5, h -> pending.decrementAndGet());
        manager.definition(script, 2, 5, t -> pending.decrementAndGet());
        manager.documentSymbols(script, s -> pending.decrementAndGet());
        manager.codeActions(script, 2, 0, 2, 1, a -> pending.decrementAndGet());
        manager.isTestFile(script, b -> pending.decrementAndGet());
        pending.addAndGet(5);
        for (int i = 0; i < 500 && pending.get() > 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(0, pending.get(), "a request never answered");
        manager.closeDocument(script);
        drainFx();

        String key = DocumentUris.of(script);
        List<String> leaked = new ArrayList<>();
        for (Field field : FakeLanguageServer.class.getFields()) {
            if (Collection.class.isAssignableFrom(field.getType()) && field.get(fake()) instanceof Collection<?> c) {
                c.forEach(v -> {
                    if (String.valueOf(v).contains(key + "\"")
                            || String.valueOf(v).contains(key + "\n")) {
                        leaked.add(field.getName() + ": " + v);
                    }
                });
            }
        }
        assertEquals(List.of(), leaked, "the script's own URI reached the server");
        assertEquals(1, fake().closed.size());
        assertEquals(standInUri(), fake().closed.get(0).getTextDocument().getUri());
    }

    /** The server holds the rewritten text, so a splice computed on the editor's text would not fit it. */
    @Test
    void aChangeSyncsTheWholeRewrittenText() throws Exception {
        String edited = SCRIPT.replace("IO.println(1);", "IO.println(12);");

        manager.changeDocument(script, edited);
        drainFx();
        for (int i = 0; i < 100 && fake().changed.isEmpty(); i++) {
            Thread.sleep(20);
        }

        var change = fake().changed.get(0).getContentChanges().get(0);
        assertNull(change.getRange(), "a full sync, though the server takes increments");
        assertEquals(JavaScriptShadow.wireText(edited), change.getText());
    }

    @Test
    void whatTheServerSaysAboutTheStandInIsAboutTheScript() throws Exception {
        var at = new Range(new Position(2, 4), new Position(2, 6));
        fake().definitionResponse = List.of(new Location(standInUri(), at));

        List<LspManager.Target> definition = await(cb -> manager.definition(script, 2, 5, cb));
        session.get()
                .publishDiagnostics(new org.eclipse.lsp4j.PublishDiagnosticsParams(
                        standInUri(), List.of(new org.eclipse.lsp4j.Diagnostic(at, "oops"))));
        for (int i = 0; i < 100 && diagnosed.isEmpty(); i++) {
            Thread.sleep(20);
        }

        assertEquals(
                List.of(script),
                definition.stream().map(LspManager.Target::file).toList());
        assertEquals(List.of(script), diagnosed);
        assertEquals(script, manager.pathForServerUri(script, standInUri()));
        assertEquals(script, manager.scriptForStandIn(DocumentUris.toPath(standInUri())), "a debugger's frame");
        assertNull(manager.scriptForStandIn(script));
    }

    /**
     * Edits are checked against the text the server was sent; that is kept as the editor's own, so an edit
     * to the script is not refused for a shebang the editor never had in that form.
     */
    @Test
    void anEditTheServerMakesToTheStandInEditsTheScript() throws Exception {
        var edit = new TextEdit(new Range(new Position(2, 4), new Position(2, 6)), "System.out");
        var versioned = new org.eclipse.lsp4j.TextDocumentEdit(
                new org.eclipse.lsp4j.VersionedTextDocumentIdentifier(standInUri(), 1),
                List.of(org.eclipse.lsp4j.jsonrpc.messages.Either.<TextEdit, org.eclipse.lsp4j.SnippetTextEdit>forLeft(
                        edit)));
        fake().renameResponse = new WorkspaceEdit(List.of(org.eclipse.lsp4j.jsonrpc.messages.Either
                .<org.eclipse.lsp4j.TextDocumentEdit, org.eclipse.lsp4j.ResourceOperation>forLeft(versioned)));
        var applied = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler((mapped, done) -> {
            applied.set(mapped);
            done.accept(true);
        });

        Boolean ok = await(cb -> manager.rename(script, 2, 5, "System.out", cb));

        assertEquals(Boolean.TRUE, ok);
        assertNotNull(applied.get());
        assertEquals(
                List.of(script),
                applied.get().edits().stream()
                        .map(WorkspaceEditMapper.FileEdit::file)
                        .toList());
    }

    @Test
    void closingTheScriptDeletesItsStandIn() throws Exception {
        Path standIn = DocumentUris.toPath(standInUri());

        manager.closeDocument(script);

        assertFalse(Files.exists(standIn));
        assertFalse(Files.exists(standIn.getParent()));
    }

    @Test
    void twoScriptsOfOneNameGetTwoStandIns() throws Exception {
        Path other = Files.createDirectories(root.resolve("other")).resolve("launcher");
        Files.writeString(other, SCRIPT);

        manager.openDocument(other, root, "java", SCRIPT);
        drainFx();
        for (int i = 0; i < 100 && fake().opened.size() < 2; i++) {
            Thread.sleep(20);
        }

        assertNotEquals(
                fake().opened.get(0).getTextDocument().getUri(),
                fake().opened.get(1).getTextDocument().getUri());
    }

    @Test
    void aJavaFileIsShownAsItself() throws Exception {
        Path app = root.resolve("App.java");
        Files.writeString(app, "class App {}\n");

        manager.openDocument(app, root, "java", "class App {}\n");
        for (int i = 0; i < 100 && fake().opened.size() < 2; i++) {
            Thread.sleep(20);
        }

        assertEquals(
                DocumentUris.of(app), fake().opened.get(1).getTextDocument().getUri());
        assertEquals("class App {}\n", fake().opened.get(1).getTextDocument().getText());
    }
}
