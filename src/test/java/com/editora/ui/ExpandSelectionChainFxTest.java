package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.lsp.SelectionRangeFake;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SelectionRange;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expand Selection against a server that answers {@code selectionRange}: a new ladder started elsewhere in
 * the same, unedited buffer must not pick its first range from the chain fetched for the previous ladder.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.junit.jupiter.api.extension.ExtendWith(OffFxTracked.class)
class ExpandSelectionChainFxTest {

    private static final String SRC =
            "class A {\n  void a() {\n    int alpha = 1;\n  }\n\n  void b() {\n    int beta = 2;\n  }\n}\n";

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static Position pos(int offset) {
        int line = 0;
        int col = 0;
        for (int i = 0; i < offset; i++) {
            if (SRC.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new Position(line, col);
    }

    private static int offset(Position p) {
        int line = 0;
        int i = 0;
        while (line < p.getLine()) {
            if (SRC.charAt(i++) == '\n') {
                line++;
            }
        }
        return i + p.getCharacter();
    }

    private static SelectionRange range(int start, int end, SelectionRange parent) {
        return new SelectionRange(new Range(pos(start), pos(end)), parent);
    }

    /** What a Java server answers: identifier, statement, method, class body, file. */
    private static List<SelectionRange> answer(Position p) {
        SelectionRange file = range(0, SRC.length(), null);
        SelectionRange body = range(SRC.indexOf('{') + 1, SRC.lastIndexOf('}'), file);
        boolean inA = offset(p) < SRC.indexOf("void b");
        String word = inA ? "alpha" : "beta";
        int methodStart = SRC.indexOf(inA ? "void a" : "void b");
        SelectionRange method = range(methodStart, SRC.indexOf("  }", methodStart) + 3, body);
        int statementStart = SRC.indexOf("int " + word);
        SelectionRange statement = range(statementStart, SRC.indexOf(';', statementStart) + 1, method);
        int wordStart = SRC.indexOf(word);
        return List.of(range(wordStart, wordStart + word.length(), statement));
    }

    /** Opens {@code file} as the active buffer on the Java server and syncs it. */
    private EditorBuffer open(Object coordinator, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            FxTestSupport.call(
                    coordinator,
                    "setServerAvailableForTest",
                    new Class<?>[] {String.class, boolean.class},
                    "java",
                    true);
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(SRC);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class[] {EditorBuffer.class, boolean.class}, buffer, true);
            FxTestSupport.call(coordinator, "syncBuffer", new Class<?>[] {EditorBuffer.class}, buffer);
            buffer.getArea().requestFocus();
            return buffer;
        });
    }

    @Test
    void aNewLadderDoesNotReuseThePreviousLaddersChain() throws Exception {
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        LspManager manager = FxTestSupport.field(fx.controller, "lspManager");
        Object coordinator = FxTestSupport.field(fx.controller, "lspCoordinator");
        Path file = Files.createTempDirectory("expand-chain").resolve("A.java");
        Files.writeString(file, SRC);
        SelectionRangeFake.install(manager, ExpandSelectionChainFxTest::answer);
        fx.shared.getSettings().setLspSupport(true);
        fx.shared.getSettings().setJavaLspEnabled(true);
        manager.configure(true, Map.of("java", "jdtls"));
        // The server's root is looked for off the FX thread (PERF-01, Pauta 6.0.1), and the buffer opens on the server
        // when it lands; LspRootMemoFxTest and ChecksOffFxThreadFxTest cover that. Here the test waits until every
        // look the open and the window set off has landed, so the buffer sits on the fake for good: this test is
        // about the ladder, not about when a root lands.
        EditorBuffer b = open(coordinator, file);
        OffFxTracked.awaitIdle();
        com.editora.testkit.Await.holdsWithin(() -> manager.supportsSelectionRanges(file), Duration.ofSeconds(5));
        OffFxTracked.awaitIdle();
        assertTrue(manager.supportsSelectionRanges(file), "the fake server is attached");

        // Ladder 1 in method a(): the first press is local, the second uses the fetched chain.
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(SRC.indexOf("alpha") + 2));
        expand(registry, b);
        // let the chain for ladder 1 arrive
        EventuallyFx.holdsWithin(
                Duration.ofSeconds(2),
                () -> !FxTestSupport.<List<?>>callOnFx(() -> (List<?>) FxTestSupport.call(
                                coordinator, "selectionChain", new Class<?>[] {EditorBuffer.class}, b))
                        .isEmpty());
        expand(registry, b);
        assertEquals("int alpha = 1;", FxTestSupport.callOnFx(() -> b.getArea().getSelectedText()));

        // Ladder 2: move into method b() — no edit in between — and press once.
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(SRC.indexOf("beta") + 2));
        expand(registry, b);
        assertEquals("beta", FxTestSupport.callOnFx(() -> b.getArea().getSelectedText()));
    }

    /**
     * Presses Expand Selection and waits for it to land: the next range is looked for off the FX thread and applied
     * when it comes back (PERF-01), which changes the selection.
     */
    private static void expand(CommandRegistry registry, EditorBuffer b) throws Exception {
        String before = FxTestSupport.callOnFx(() -> b.getArea().getSelectedText());
        FxTestSupport.runOnFx(() -> registry.run("edit.expandSelection"));
        EventuallyFx.holdsWithin(
                Duration.ofSeconds(5),
                () -> !before.equals(FxTestSupport.callOnFx(() -> b.getArea().getSelectedText())));
        FxTestSupport.drainFx();
    }
}
