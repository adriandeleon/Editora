package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.TreeView;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code lenses from the setting to the editor, through a real {@link LspCoordinator} and {@link LspManager}
 * over a recording fake server: what is asked for, what reaches the buffer, and what a click does.
 */
@Tag("fx")
class CodeLensCoordinatorFxTest {

    private static final String SOURCE = "class A {\n    void run() {}\n    int size() { return 0; }\n}\n";

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private LspCoordinator coordinator;
    private FakeHost host;
    private List<FakeLanguageServer> fakes;
    private CountingOps ops;

    private static final class CountingOps extends LspOpsStub {
        int referencesWindowOpened;
        int jumps;

        @Override
        public void openReferencesWindow() {
            referencesWindowOpened++;
        }

        @Override
        public void openAndGoto(Path file, int line0, int col0) {
            jumps++;
        }
    }

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;

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
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        var caps = LspTestHooks.refreshableCaps();
        caps.setCodeLensProvider(new CodeLensOptions(true));
        caps.setReferencesProvider(true);
        fakes = LspTestHooks.useFakeSessions(manager, caps);
        manager.configure(true, Map.of("java", "jdtls"));
        host = new FakeHost();
        host.settings.setSemanticHighlight(false);
        host.settings.setCodeLens(true);
        FxTestSupport.runOnFx(() -> {
            ops = new CountingOps();
            coordinator = new LspCoordinator(host, manager, ops);
            coordinator.setServerAvailableForTest("java", true);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(manager::shutdownAll);
    }

    private EditorBuffer open() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path f = root.resolve("A.java");
        Files.writeString(f, SOURCE);
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(f);
            b.setContent(SOURCE);
            host.buffers.add(b);
            host.active = b;
            coordinator.wireBuffer(b);
            FxTestSupport.invoke(FxTestSupport.field(b, "settledEdits"), "dispose"); // pauses are played by hand
            return b;
        });
    }

    private static CodeLens unresolved(int line, int col, int count) {
        return new CodeLens(new Range(new Position(line, col), new Position(line, col + 3)), null, count);
    }

    private void request(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> coordinator.requestCodeLens(b));
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    @Test
    void lensesInViewAreResolvedAndDrawnAfterTheirLines() throws Exception {
        EditorBuffer b = open();
        FakeLanguageServer fake = fakes.get(0);
        fake.codeLensResponse = List.of(unresolved(1, 9, 2), unresolved(2, 8, 0), unresolved(900, 0, 7));

        request(b);

        assertEquals(1, fake.codeLenses.size());
        assertEquals(2, fake.resolvedCodeLenses.size(), "the lens far below the window is not resolved");
        assertEquals(
                "2 references",
                FxTestSupport.callOnFx(() -> b.codeLensesOn(1).get(0).label()));
        assertEquals(
                "0 references",
                FxTestSupport.callOnFx(() -> b.codeLensesOn(2).get(0).label()));
        assertEquals(SOURCE, FxTestSupport.callOnFx(b::getContent));
    }

    @Test
    void turningTheSettingOffClearsThemAndStopsAsking() throws Exception {
        EditorBuffer b = open();
        FakeLanguageServer fake = fakes.get(0);
        fake.codeLensResponse = List.of(unresolved(1, 9, 2));
        request(b);
        assertEquals(1, FxTestSupport.callOnFx(() -> b.codeLensesOn(1).size()));

        host.settings.setCodeLens(false);
        FxTestSupport.runOnFx(coordinator::applyInlayHints);
        FxTestSupport.drainFx();

        assertTrue(FxTestSupport.callOnFx(() -> b.codeLensesOn(1).isEmpty()));
        request(b);
        assertEquals(1, fake.codeLenses.size(), "nothing is requested while the setting is off");
    }

    @Test
    void aServerWithoutCodeLensesIsNotAsked() throws Exception {
        FxTestSupport.runOnFx(manager::shutdownAll);
        fakes = LspTestHooks.useFakeSessions(manager, LspTestHooks.refreshableCaps());
        EditorBuffer b = open();

        request(b);

        assertTrue(fakes.get(0).codeLenses.isEmpty());
    }

    @Test
    void clickingALensAsksForTheReferencesOfItsDeclaration() throws Exception {
        EditorBuffer b = open();
        FakeLanguageServer fake = fakes.get(0);
        fake.codeLensResponse = List.of(unresolved(1, 9, 2));
        request(b);

        FxTestSupport.runOnFx(() -> b.activateCodeLens(1));
        FxTestSupport.drainFx();

        assertEquals(1, fake.references.size());
        assertEquals(new Position(1, 9), fake.references.get(0).getPosition(), "the declaration's name");
        assertEquals(1, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()));
        assertEquals(9, FxTestSupport.callOnFx(() -> b.getArea().getCaretColumn()));
    }

    @Test
    void aLensMovedByAnEditStillOpensItsOwnDeclaration() throws Exception {
        EditorBuffer b = open();
        FakeLanguageServer fake = fakes.get(0);
        fake.codeLensResponse = List.of(unresolved(1, 9, 2));
        request(b);

        FxTestSupport.runOnFx(() -> {
            b.getArea().insertText(0, "// note\n");
            b.activateCodeLens(2);
        });
        FxTestSupport.drainFx();

        assertEquals(new Position(2, 9), fake.references.get(0).getPosition());
    }

    private int rowsInReferencesWindow() throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.<TreeView<?>>field(coordinator.referencesPanel(), "tree")
                .getExpandedItemCount());
    }

    @Test
    void aLensOnOneReferenceOpensTheReferencesWindowInsteadOfJumping() throws Exception {
        EditorBuffer b = open();
        FakeLanguageServer fake = fakes.get(0);
        fake.codeLensResponse = List.of(unresolved(1, 9, 1));
        fake.referenceResponse = List.of(new Location(
                root.resolve("A.java").toUri().toString(), new Range(new Position(2, 4), new Position(2, 7))));
        request(b);

        FxTestSupport.runOnFx(() -> b.activateCodeLens(1));
        FxTestSupport.drainFx();

        assertEquals(1, ops.referencesWindowOpened);
        assertEquals(0, ops.jumps);
        assertEquals(2, rowsInReferencesWindow(), "the file header and its one reference");
    }

    @Test
    void aLensOnNoReferencesOpensTheReferencesWindowEmpty() throws Exception {
        EditorBuffer b = open();
        fakes.get(0).codeLensResponse = List.of(unresolved(1, 9, 0));
        request(b);

        FxTestSupport.runOnFx(() -> b.activateCodeLens(1));
        FxTestSupport.drainFx();

        assertEquals(1, ops.referencesWindowOpened);
        assertEquals(0, rowsInReferencesWindow());
    }

    @Test
    void theFindReferencesCommandStillJumpsToALoneReference() throws Exception {
        EditorBuffer b = open();
        fakes.get(0).referenceResponse = List.of(new Location(
                root.resolve("A.java").toUri().toString(), new Range(new Position(2, 4), new Position(2, 7))));

        FxTestSupport.runOnFx(() -> {
            b.getArea().moveTo(1, 9);
            coordinator.findReferences();
        });
        FxTestSupport.drainFx();

        assertEquals(0, ops.referencesWindowOpened);
        assertEquals(1, ops.jumps);
    }
}
