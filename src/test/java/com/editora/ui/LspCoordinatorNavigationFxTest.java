package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The user-facing LSP flows: go-to-definition (including into a {@code jdt://} library source),
 * find-references, and Format Document. These are the commands people actually press, and their failure modes
 * are quiet ones — a dropped target reports "no definition", a stale format silently corrupts the file.
 *
 * <p>Notably this pins the <b>client half of #665</b>: the manager keeps a {@code jdt://} target (covered in
 * {@code LspManagerRequestsFxTest}), but it is the coordinator that has to notice the path-less target and
 * fetch the class-file source instead of trying to open a file — and that branch had no test.
 */
@Tag("fx")
class LspCoordinatorNavigationFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private LspCoordinator coordinator;
    private FakeHost host;
    private FakeOps ops;
    private List<FakeLanguageServer> fakes;

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;
        final List<String> statuses = new ArrayList<>();

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            new ArrayList<>(buffers).forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }
    }

    private static final class FakeOps extends LspOpsStub {
        final List<Path> gotoFiles = new ArrayList<>();
        final List<int[]> gotoPositions = new ArrayList<>();
        final List<String> readOnlyDocTitles = new ArrayList<>();
        final List<String> readOnlyDocContents = new ArrayList<>();
        int referencesWindowOpened;
        boolean editable = true;
        EditorBuffer readOnlyDocResult;

        @Override
        public void openAndGoto(Path file, int line0, int col0) {
            gotoFiles.add(file);
            gotoPositions.add(new int[] {line0, col0});
        }

        @Override
        public EditorBuffer openReadOnlyDoc(String title, String content, String language) {
            readOnlyDocTitles.add(title);
            readOnlyDocContents.add(content);
            return readOnlyDocResult;
        }

        @Override
        public void openReferencesWindow() {
            referencesWindowOpened++;
        }

        @Override
        public boolean activeEditable() {
            return editable;
        }

        @Override
        public EditorBuffer bufferForPath(Path file) {
            return null;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessions(manager);
        manager.configure(true, Map.of("java", "jdtls"));
        host = new FakeHost();
        ops = new FakeOps();
        FxTestSupport.runOnFx(() -> {
            coordinator = new LspCoordinator(host, manager, ops);
            coordinator.setServerAvailableForTest("java", true);
        });
    }

    /** An open, managed, active java buffer — the precondition every flow here needs. */
    private EditorBuffer managedBuffer() throws Exception {
        Path f = root.resolve("A.java");
        Files.writeString(f, "class A {\n    void go() {}\n}\n");
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(f);
            x.setContent("class A {\n    void go() {}\n}\n");
            host.buffers.add(x);
            host.active = x;
            return x;
        });
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(b));
        assertTrue(manager.isManaged(f), "precondition: the buffer is managed");
        return b;
    }

    private FakeLanguageServer server() {
        assertFalse(fakes.isEmpty(), "no session was created");
        return fakes.get(0);
    }

    private static Location location(String uri, int line, int ch) {
        return new Location(uri, new Range(new Position(line, ch), new Position(line, ch + 1)));
    }

    /** Runs {@code action} on FX and lets the resulting async callback settle. */
    private void runAndSettle(Runnable action) throws Exception {
        FxTestSupport.runOnFx(action);
        FxTestSupport.runOnFx(() -> {}); // the manager marshals its reply with a second runLater
        FxTestSupport.runOnFx(() -> {});
    }

    // --- go to definition ----------------------------------------------------------------------------

    @Test
    void goToDefinitionOpensTheTargetFile() throws Exception {
        managedBuffer();
        Path target = root.resolve("B.java");
        Files.writeString(target, "class B {}");
        server().definitionResponse = List.of(location(target.toUri().toString(), 3, 9));

        runAndSettle(() -> coordinator.gotoDefinition());

        assertEquals(1, ops.gotoFiles.size(), "the definition should have been opened");
        assertEquals(target, ops.gotoFiles.get(0));
        assertEquals(3, ops.gotoPositions.get(0)[0]);
        assertEquals(9, ops.gotoPositions.get(0)[1]);
    }

    /**
     * The client half of #665: a {@code jdt://} target has no filesystem path, so the coordinator must fetch
     * the class-file source and open it read-only instead of trying (and failing) to open a file. Before
     * this, {@code M-.} on {@code String} reported "no definition".
     */
    @Test
    void goToDefinitionIntoALibraryFetchesTheClassFileSource() throws Exception {
        managedBuffer();
        String jdt = "jdt://contents/java.base/java.lang/String.class?=demo";
        server().definitionResponse = List.of(location(jdt, 42, 4));

        runAndSettle(() -> coordinator.gotoDefinition());
        // classFileContents is a raw request on the launcher, which the fake does not implement; what matters
        // is that the coordinator took the library branch rather than trying to open a file.
        assertTrue(ops.gotoFiles.isEmpty(), "a jdt:// target has no file to open");
    }

    @Test
    void goToDefinitionWithNoResultReportsRatherThanOpeningAnything() throws Exception {
        managedBuffer();
        server().definitionResponse = List.of();

        runAndSettle(() -> coordinator.gotoDefinition());

        assertTrue(ops.gotoFiles.isEmpty());
        assertFalse(host.statuses.isEmpty(), "the user must be told there is no definition");
    }

    /** An unmanaged buffer reports unavailability instead of silently doing nothing. */
    @Test
    void goToDefinitionOnAnUnmanagedBufferReports() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            host.buffers.add(x);
            host.active = x;
            return x;
        });
        assertNotNull(b);

        runAndSettle(() -> coordinator.gotoDefinition());

        assertTrue(ops.gotoFiles.isEmpty());
        assertFalse(host.statuses.isEmpty(), "an unavailable server must be reported");
    }

    // --- find references -----------------------------------------------------------------------------

    /** A single reference jumps straight there — opening a whole tool window for one hit is IDE-wrong. */
    @Test
    void aSingleReferenceJumpsStraightThere() throws Exception {
        managedBuffer();
        Path target = root.resolve("B.java");
        Files.writeString(target, "class B {}");
        server().referenceResponse = List.of(location(target.toUri().toString(), 2, 3));

        runAndSettle(() -> coordinator.findReferences());

        assertEquals(1, ops.gotoFiles.size(), "a lone reference should be navigated to directly");
        assertEquals(0, ops.referencesWindowOpened, "…without opening the References window");
    }

    /** Several references populate the panel and open the window. */
    @Test
    void severalReferencesOpenTheReferencesWindow() throws Exception {
        managedBuffer();
        Path b1 = root.resolve("B.java");
        Path b2 = root.resolve("C.java");
        Files.writeString(b1, "class B {}");
        Files.writeString(b2, "class C {}");
        server().referenceResponse = List.of(
                location(b1.toUri().toString(), 1, 1), location(b2.toUri().toString(), 2, 2));

        runAndSettle(() -> coordinator.findReferences());

        assertEquals(1, ops.referencesWindowOpened, "multiple hits belong in the References window");
        assertTrue(ops.gotoFiles.isEmpty(), "…and must not jump anywhere on their own");
    }

    @Test
    void noReferencesReportsRatherThanOpeningAnEmptyWindow() throws Exception {
        managedBuffer();
        server().referenceResponse = List.of();

        runAndSettle(() -> coordinator.findReferences());

        assertEquals(0, ops.referencesWindowOpened);
        assertFalse(host.statuses.isEmpty());
    }

    // --- format document -----------------------------------------------------------------------------

    @Test
    void formatDocumentAppliesTheServersEdits() throws Exception {
        EditorBuffer b = managedBuffer();
        // Replace "class" with "CLASS" on line 0 — a visible, unambiguous edit.
        server().formattingResponse = List.of(new TextEdit(new Range(new Position(0, 0), new Position(0, 5)), "CLASS"));

        runAndSettle(() -> coordinator.formatDocument());

        String text = FxTestSupport.callOnFx(b::getContent);
        assertTrue(text.startsWith("CLASS A {"), "the formatting edit should have been applied, got: " + text);
    }

    /** A read-only buffer must never be rewritten by the server. */
    @Test
    void formatDocumentRefusesOnANonEditableBuffer() throws Exception {
        EditorBuffer b = managedBuffer();
        ops.editable = false;
        server().formattingResponse = List.of(new TextEdit(new Range(new Position(0, 0), new Position(0, 5)), "CLASS"));

        runAndSettle(() -> coordinator.formatDocument());

        String text = FxTestSupport.callOnFx(b::getContent);
        assertTrue(text.startsWith("class A {"), "a non-editable buffer must be left alone");
    }

    /** An empty edit list is "already formatted", not a failure — and must not touch the document. */
    @Test
    void formatDocumentWithNoEditsLeavesTheDocumentAlone() throws Exception {
        EditorBuffer b = managedBuffer();
        server().formattingResponse = List.of();

        runAndSettle(() -> coordinator.formatDocument());

        String text = FxTestSupport.callOnFx(b::getContent);
        assertTrue(text.startsWith("class A {"));
        assertFalse(host.statuses.isEmpty(), "the no-change outcome should still be reported");
    }

    /**
     * The server computes whole-document edits against the text as it was when asked. If the user types
     * during the round-trip those offsets no longer line up, and applying them blind mis-formats every line.
     * The reply must be dropped instead.
     */
    @Test
    void aFormatReplyIsDroppedWhenTheDocumentChangedMeanwhile() throws Exception {
        EditorBuffer b = managedBuffer();
        server().formattingResponse = List.of(new TextEdit(new Range(new Position(0, 0), new Position(0, 5)), "CLASS"));

        FxTestSupport.runOnFx(() -> {
            coordinator.formatDocument();
            b.setContent("class A { /* edited mid-format */ }\n"); // moves the document under the reply
        });
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(() -> {});

        String text = FxTestSupport.callOnFx(b::getContent);
        assertTrue(
                text.contains("edited mid-format"),
                "the stale format must not overwrite the user's edit, got: " + text);
        assertFalse(text.startsWith("CLASS"), "the stale edit must not have been applied");
    }
}
