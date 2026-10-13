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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A server that names documents differently from the keys {@link LspManager} routes by — which is what a
 * server on another machine does. The fake here is told {@code wire:} where the manager says {@code file:},
 * so a URI that skipped the session's {@link WireUris} shows up as a {@code file:} string the server was
 * never supposed to see, or as an answer the manager could not turn back into the file.
 */
@Tag("fx")
class LspWireUriFxTest {

    /** {@code file:///x} inside the manager, {@code wire:///x} on the wire. */
    private static final WireUris SWAPPED = new WireUris() {
        @Override
        public String toWire(String key) {
            return key.startsWith("file:") ? "wire:" + key.substring(5) : key;
        }

        @Override
        public String toKey(String wireUri) {
            return wireUri.startsWith("wire:") ? "file:" + wireUri.substring(5) : wireUri;
        }
    };

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
    private Path file;
    private Path other;

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> diagnosed.add(f), (t, m) -> {});
        ServerCapabilities caps = new ServerCapabilities();
        caps.setCodeActionProvider(true);
        caps.setRenameProvider(true);
        caps.setWorkspaceSymbolProvider(true);
        caps.setCallHierarchyProvider(true);
        caps.setTypeHierarchyProvider(true);
        caps.setDiagnosticProvider(new org.eclipse.lsp4j.DiagnosticRegistrationOptions());
        manager.setSessionStarterForTest(s -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            session.set(s);
            s.setUrisForTest(SWAPPED);
            s.setRawSinkForTest(fake.rawSink());
            s.attachForTest(fake, caps);
        });
        manager.configure(true, Map.of("java", "jdtls"));
        file = root.resolve("A.java");
        other = root.resolve("B.java");
        Files.writeString(file, "class A {}\n");
        Files.writeString(other, "class B {}\n");
        manager.openDocument(file, root, "java", "class A {}");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    private static String wire(Path path) {
        return SWAPPED.toWire(path.toUri().toString());
    }

    private static Location at(Path path) {
        return new Location(wire(path), new Range(new Position(2, 1), new Position(2, 4)));
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

    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();

    /** A callback for a request whose answer is not looked at, only waited for. */
    private <T> Consumer<T> done() {
        pending.incrementAndGet();
        return value -> pending.decrementAndGet();
    }

    private static void drainFx() throws Exception {
        var drained = new CountDownLatch(1);
        Platform.runLater(drained::countDown);
        assertTrue(drained.await(10, TimeUnit.SECONDS));
    }

    /** Everything the fake recorded, as text: every {@code public List} field it has. */
    private static List<String> recorded(FakeLanguageServer fake, List<String> populated) throws Exception {
        List<String> out = new ArrayList<>();
        for (Field field : FakeLanguageServer.class.getFields()) {
            if (Collection.class.isAssignableFrom(field.getType())
                    && field.getName().matches("[a-z][A-Za-z]*")) {
                Collection<?> values = (Collection<?>) field.get(fake);
                if (values != null && !values.isEmpty() && !field.getName().endsWith("Response")) {
                    populated.add(field.getName());
                    values.forEach(v -> out.add(field.getName() + ": " + v));
                }
            }
        }
        return out;
    }

    @Test
    void theServerOnlyEverSeesItsOwnUris() throws Exception {
        FakeLanguageServer fake = fakes.get(0);

        manager.changeDocument(file, "class A { }");
        manager.saveDocument(file, "class A { }");
        manager.completion(file, 0, 1, items -> {});
        manager.hover(file, 0, 1, done());
        manager.definition(file, 0, 1, done());
        manager.implementation(file, 0, 1, done());
        manager.typeDefinition(file, 0, 1, done());
        manager.declaration(file, 0, 1, done());
        manager.references(file, 0, 1, done());
        manager.documentSymbols(file, done());
        manager.rename(file, 0, 6, "Z", done());
        manager.prepareCallHierarchy(file, 0, 6, done());
        manager.prepareTypeHierarchy(file, 0, 6, done());
        manager.codeActions(file, 0, 0, 0, 1, done());
        manager.isTestFile(file, done());
        manager.fullyQualifiedName(file, 0, 6, done());
        manager.organizeImports(file, 0, 10, done());
        manager.smartSemicolonPosition(file, 0, 5, done());
        manager.reloadProjectConfiguration(file);
        manager.pullDiagnostics(file);
        manager.notifyWatchedFiles(List.of(new LspManager.WatchedFile(other, LspManager.WatchedKind.CHANGED)));
        for (int i = 0; i < 500 && pending.get() > 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(0, pending.get(), "a request never answered");
        manager.closeDocument(file);
        drainFx();

        List<String> populated = new ArrayList<>();
        List<String> leaked = recorded(fake, populated).stream()
                .filter(text -> text.contains("file:"))
                .toList();
        assertEquals(List.of(), leaked, "a document key reached the server without going through WireUris");
        // The guard is only as wide as what was exercised: these must all have been recorded.
        for (String expected : List.of(
                "opened",
                "changed",
                "saved",
                "closed",
                "completions",
                "hovers",
                "definitions",
                "implementations",
                "typeDefinitions",
                "declarations",
                "references",
                "documentSymbols",
                "renames",
                "codeActions",
                "executedCommands",
                "watchedFiles",
                "rawNotifications")) {
            assertTrue(populated.contains(expected), expected + " was not exercised; recorded: " + populated);
        }
        assertTrue(fake.opened.get(0).getTextDocument().getUri().startsWith("wire:"));
        assertEquals(wire(other), fake.watchedFiles.get(0).getChanges().get(0).getUri());
    }

    @Test
    void locationsTheServerAnswersWithBecomeFiles() throws Exception {
        FakeLanguageServer fake = fakes.get(0);
        fake.definitionResponse = List.of(at(other));
        fake.referenceResponse = List.of(at(other), at(file));

        List<LspManager.Target> definition = await(cb -> manager.definition(file, 0, 1, cb));
        List<LspManager.Target> references = await(cb -> manager.references(file, 0, 1, cb));

        assertEquals(
                List.of(other), definition.stream().map(LspManager.Target::file).toList());
        assertEquals(
                List.of(other, file),
                references.stream().map(LspManager.Target::file).toList());
        assertEquals(other, manager.pathForServerUri(file, wire(other)));
        assertEquals(wire(file), manager.serverUri(file));
    }

    @Test
    void aClassFileUriPassesThroughUntouched() throws Exception {
        FakeLanguageServer fake = fakes.get(0);
        String jdt = "jdt://contents/java.base/java.lang/String.class";
        fake.definitionResponse = List.of(new Location(jdt, new Range(new Position(1, 0), new Position(1, 1))));

        List<LspManager.Target> definition = await(cb -> manager.definition(file, 0, 1, cb));

        assertEquals(1, definition.size());
        assertEquals(jdt, definition.get(0).classFileUri());
        assertEquals(null, manager.pathForServerUri(file, jdt));
    }

    @Test
    void aWorkspaceEditInTheServersUrisEditsTheRightFiles() throws Exception {
        FakeLanguageServer fake = fakes.get(0);
        var edit = new TextEdit(new Range(new Position(0, 6), new Position(0, 7)), "Z");
        fake.renameResponse = new WorkspaceEdit(Map.of(wire(file), List.of(edit)));
        var applied = new AtomicReference<WorkspaceEditMapper.Mapped>();
        manager.setApplyEditHandler((mapped, done) -> {
            applied.set(mapped);
            done.accept(true);
        });

        Boolean ok = await(cb -> manager.rename(file, 0, 6, "Z", cb));

        assertEquals(Boolean.TRUE, ok);
        assertNotNull(applied.get());
        assertEquals(
                List.of(file),
                applied.get().edits().stream()
                        .map(WorkspaceEditMapper.FileEdit::file)
                        .toList());
    }

    @Test
    void diagnosticsPublishedUnderTheServersUriReachTheDocument() throws Exception {
        var params = new org.eclipse.lsp4j.PublishDiagnosticsParams(
                wire(file),
                List.of(new org.eclipse.lsp4j.Diagnostic(new Range(new Position(0, 0), new Position(0, 1)), "oops")));

        session.get().publishDiagnostics(params);
        for (int i = 0; i < 100 && diagnosed.isEmpty(); i++) {
            Thread.sleep(20);
        }

        assertEquals(List.of(file), diagnosed);
    }
}
