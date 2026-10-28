package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A schema changed on disk where the Project panel does not look — a collapsed folder — reaches the XML server,
 * which revalidates against it; its errors stayed stale until the next keystroke (XML review B9).
 */
@Tag("fx")
class XmlGrammarWatchFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            session.attachForTest(fake, new ServerCapabilities());
        });
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void aSchemaChangedInAFolderNobodyWatchesReachesTheXmlServer() throws Exception {
        Path xsd = Files.createDirectories(root.resolve("xsd")).resolve("note.xsd");
        Files.writeString(xsd, "<xs:schema/>\n");
        Path xml = Files.writeString(root.resolve("note.xml"), "<note/>\n");
        manager.configure(true, Map.of("xml", "lemminx"));
        manager.openDocument(xml, root, "xml", "<note/>\n");
        // the whole tree, project or not: the Project panel watches only its expanded folders (Bash review B16)
        assertEquals(java.util.Set.of(root.toAbsolutePath().normalize()), manager.watchedRoots());

        Files.writeString(root.resolve("readme.txt"), "not a grammar\n");
        Files.writeString(xsd, "<xs:schema><!-- urgent added --></xs:schema>\n");
        String uri = xsd.toUri().toString();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<org.eclipse.lsp4j.FileEvent> seen = List.of();
        while (seen.stream().noneMatch(e -> e.getUri().equals(uri)) && System.nanoTime() < deadline) {
            Thread.sleep(50);
            seen = onFx(() -> List.copyOf(fakes.get(0).watchedFiles)).stream()
                    .flatMap(p -> p.getChanges().stream())
                    .toList();
        }
        assertTrue(seen.stream().anyMatch(e -> e.getUri().equals(uri)), "the schema change: " + seen);

        manager.close();
        assertTrue(manager.watchedRoots().isEmpty(), "closed with the window");
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> task) throws Exception {
        var f = new java.util.concurrent.CompletableFuture<T>();
        javafx.application.Platform.runLater(() -> {
            try {
                f.complete(task.call());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }

    @Test
    void grammarFilesAreKnownByName() {
        assertTrue(LspManager.xmlGrammarFile(Path.of("a/b/note.XSD")));
        assertTrue(LspManager.xmlGrammarFile(Path.of("doc.dtd")));
        assertTrue(LspManager.xmlGrammarFile(Path.of("s.rnc")));
        assertFalse(LspManager.xmlGrammarFile(Path.of("note.xml")));
        assertFalse(LspManager.xmlGrammarFile(Path.of("xsd")));
    }
}
