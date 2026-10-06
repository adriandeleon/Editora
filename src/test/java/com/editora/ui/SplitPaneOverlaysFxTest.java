package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LspDiagnostic;
import com.editora.editor.OccurrenceSpan;
import com.editora.markdown.MarkdownLint;
import com.editora.mermaid.MaidOutput;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Everything the primary pane draws over or beside its text, the split's second pane draws too: fed the
 * same data, switched together, and repainted on that pane's own scrolling. "Draws" is checked on the
 * pixels of pane 2's overlay, so an overlay that exists but paints nothing does not pass.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitPaneOverlaysFxTest {

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private int files;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            stage.setWidth(1300);
            stage.setHeight(760);
            if (!stage.isShowing()) {
                stage.show();
            }
        });
        settle();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private void run(String id) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run(id));
        FxTestSupport.drainFx();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            FxTestSupport.drainFx();
            Thread.sleep(120);
        }
        FxTestSupport.drainFx();
    }

    private EditorBuffer open(String name, String content) throws Exception {
        Path file = fx.configDir.resolve((files++) + name);
        Files.writeString(file, content);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        for (int i = 0; i < 100; i++) {
            settle();
            EditorBuffer b = FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
            if (b != null && file.equals(b.getPath()) && !b.isLoading()) {
                settle();
                return b;
            }
        }
        throw new IllegalStateException("did not open " + file);
    }

    /** Splits side by side and returns the second view. */
    private CodeArea split(EditorBuffer b) throws Exception {
        run("view.splitVertical");
        settle();
        CodeArea second = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "area2"));
        assertNotNull(second, "the split created a second view");
        return second;
    }

    private static Parent pane2(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "root2"));
    }

    private static Node overlay(Parent pane, String styleClass) throws Exception {
        return FxTestSupport.callOnFx(() -> pane.getChildrenUnmodifiable().stream()
                .filter(n -> n.getStyleClass().contains(styleClass))
                .findFirst()
                .orElse(null));
    }

    /** How many pixels {@code node} paints (anything not fully transparent); 0 for a hidden node. */
    private static int ink(Node node) throws Exception {
        settle();
        return FxTestSupport.callOnFx(() -> {
            if (!node.isVisible() || node.getLayoutBounds().getWidth() < 1) {
                return 0;
            }
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            WritableImage img = node.snapshot(params, null);
            PixelReader px = img.getPixelReader();
            int n = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((px.getArgb(x, y) >>> 24) != 0) {
                        n++;
                    }
                }
            }
            return n;
        });
    }

    /** The overlay of pane 2 with {@code styleClass}, which must exist and paint something. */
    private static Node drawn(EditorBuffer b, String styleClass) throws Exception {
        Node twin = overlay(pane2(b), styleClass);
        assertNotNull(twin, "pane 2 has a " + styleClass);
        assertTrue(ink(twin) > 0, styleClass + " paints in pane 2");
        return twin;
    }

    private static void scrollTo(CodeArea view, int line) throws Exception {
        FxTestSupport.runOnFx(() -> view.showParagraphAtTop(line));
        settle();
    }

    /** A screenshot for a human to look at; written only with {@code EDITORA_TEST_SHOTS=<dir>} set. */
    private void shot(String name) throws Exception {
        String dir = System.getProperty("editora.test.shots", System.getenv("EDITORA_TEST_SHOTS"));
        if (dir == null) {
            return;
        }
        settle();
        WritableImage img = FxTestSupport.callOnFx(() ->
                ((Stage) FxTestSupport.field(fx.controller, "stage")).getScene().snapshot(null));
        javax.imageio.ImageIO.write(
                javafx.embed.swing.SwingFXUtils.fromFXImage(img, null),
                "png",
                Path.of(dir, name + ".png").toFile());
    }

    private static String javaSource() {
        StringBuilder src = new StringBuilder("class Overlays {\n");
        src.append("    int total = 0; // TODO tidy this up\n");
        src.append("    void run(int count) {\n");
        src.append("        int value = count + total;\n");
        src.append("        total = value;\n");
        src.append("        System.out.println(value);\n");
        src.append("    }\n");
        for (int i = 0; i < 120; i++) {
            src.append("    // filler ").append(i).append('\n');
        }
        return src.append("}\n").toString();
    }

    private static LspDiagnostic error(int line, int from, int to, String message) {
        return new LspDiagnostic(line, from, line, to, LspDiagnostic.Severity.ERROR, message, "E1", "test");
    }

    @Test
    void theSecondPaneDrawsDiagnosticsSearchOccurrencesTodoAndDebugValues() throws Exception {
        EditorBuffer b = open("Overlays.java", javaSource());
        CodeArea first = b.getArea();
        // Diagnostics and search matches arrive BEFORE the split (their overlays exist, the pane does not)...
        FxTestSupport.runOnFx(() -> {
            b.setLspActive(true);
            b.setLspDiagnostics(List.of(error(3, 12, 17, "cannot find symbol value")));
            int at = first.getText().indexOf("value");
            b.setSearchMatches(List.of(new int[] {at, at + 5}), 0);
        });
        CodeArea second = split(b);
        Node squiggles = drawn(b, "lsp-diagnostic-overlay");
        Node search = drawn(b, "search-overlay");
        drawn(b, "diagnostic-stripe");
        drawn(b, "todo-overlay");
        // ...occurrences and debug values AFTER it (their overlays are created while the pane exists).
        FxTestSupport.runOnFx(() -> {
            b.setInlineValues(Map.of("value", "42", "count", "7"), 3);
            b.setExecutionLine(3);
            b.setWhitespaceVisible(true);
        });
        Node values = drawn(b, "inline-values-overlay");
        drawn(b, "whitespace-overlay");
        // (Set once the caret has come to rest: a caret move asks the language server again and clears them.)
        FxTestSupport.runOnFx(() -> b.setOccurrenceSpans(
                List.of(new OccurrenceSpan(4, 8, 4, 13, true), new OccurrenceSpan(1, 8, 1, 13, false))));
        Node occurrences = drawn(b, "occurrence-overlay");
        assertTrue(
                FxTestSupport.callOnFx(
                        () -> second.getParagraph(3).getParagraphStyle().contains("exec-line")),
                "the execution line is a paragraph style, so both views show it");
        shot("split-java-overlays");

        // Each twin lies over pane 2's text, not at 0x0 or over the minimap.
        double textWidth = FxTestSupport.callOnFx(
                () -> second.getParent().getBoundsInParent().getWidth());
        for (Node twin : List.of(squiggles, search, occurrences, values)) {
            assertEquals(
                    textWidth,
                    FxTestSupport.callOnFx(() -> twin.getBoundsInParent().getWidth()),
                    0.5);
        }

        // Pane 2 scrolls on its own: its marks leave with its text while pane 1 still shows them.
        scrollTo(second, 60);
        assertEquals(0, ink(squiggles), "scrolled away in pane 2");
        assertEquals(0, ink(search));
        assertEquals(0, ink(occurrences));
        assertEquals(0, ink(values));
        Node primarySquiggles = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "lspOverlay"));
        assertTrue(ink(primarySquiggles) > 0, "pane 1 did not move");
        shot("split-java-overlays-scrolled");
        scrollTo(second, 0);
        assertTrue(ink(squiggles) > 0, "and back");

        // New data and an edit made in pane 1 reach pane 2.
        FxTestSupport.runOnFx(() -> b.setLspDiagnostics(List.of()));
        assertEquals(0, ink(squiggles), "cleared diagnostics clear pane 2");
        FxTestSupport.runOnFx(() -> {
            b.setLspDiagnostics(List.of(error(5, 8, 14, "second")));
            b.clearSearchMatches();
            b.clearOccurrenceSpans();
            b.setInlineValues(null, -1);
            b.clearExecutionLine();
        });
        assertTrue(ink(squiggles) > 0);
        assertEquals(0, ink(search));
        assertEquals(0, ink(occurrences));
        assertEquals(0, ink(values));
        FxTestSupport.runOnFx(() -> b.setLspActive(false));
        assertEquals(0, ink(squiggles), "LSP off hides pane 2's squiggles");
        assertEquals(0, ink(overlay(pane2(b), "diagnostic-stripe")));
    }

    @Test
    void theSecondPaneDrawsMarkdownLintMarksAndItsStripe() throws Exception {
        EditorBuffer b = open("lint.md", "# Title\n\nSome  text here with a problem\n\n" + "para\n\n".repeat(60));
        CodeArea second = split(b);
        List<MarkdownLint.Diagnostic> diags =
                List.of(new MarkdownLint.Diagnostic(3, 6, 9, "warning", "MD999", "a lint problem"));
        FxTestSupport.runOnFx(() -> {
            b.setMarkdownLintValidator((text, sink) -> sink.accept(diags));
            b.setMarkdownLintEnabled(true);
        });
        Node marks = drawn(b, "markdown-lint-overlay");
        drawn(b, "markdown-lint-stripe");
        shot("split-markdown-lint");
        scrollTo(second, 40);
        assertEquals(0, ink(marks));
        scrollTo(second, 0);
        FxTestSupport.runOnFx(() -> b.setMarkdownLintEnabled(false));
        assertEquals(0, ink(marks), "lint off hides pane 2's marks");
        assertEquals(0, ink(overlay(pane2(b), "markdown-lint-stripe")));
    }

    @Test
    void theSecondPaneDrawsMermaidLintMarks() throws Exception {
        EditorBuffer b = open("diagram.mmd", "graph TD\n    A --> B\n    B -- broken\n");
        split(b);
        FxTestSupport.runOnFx(() -> {
            b.setMermaidValidator((text, sink) ->
                    sink.accept(List.of(new MaidOutput.Diagnostic(3, 5, 11, "error", "M1", "broken edge"))));
            b.setMermaidLintEnabled(true);
        });
        Node marks = drawn(b, "mermaid-lint-overlay");
        shot("split-mermaid-lint");
        FxTestSupport.runOnFx(() -> b.setMermaidLintEnabled(false));
        assertEquals(0, ink(marks));
    }

    @Test
    void theSecondPaneTintsLogLevels() throws Exception {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            log.append("2026-10-05 10:00:").append(String.format("%02d", i % 60));
            log.append(i % 3 == 0 ? " ERROR failed to connect\n" : i % 3 == 1 ? " WARN slow reply\n" : " INFO ok\n");
        }
        EditorBuffer b = open("server.log", log.toString());
        split(b);
        FxTestSupport.runOnFx(() -> b.setLogHighlightEnabled(true));
        Node tints = drawn(b, "log-highlight-overlay");
        shot("split-log");
        FxTestSupport.runOnFx(() -> b.setLogHighlightEnabled(false));
        assertEquals(0, ink(tints));
    }

    @Test
    void hoverPopupsOpenFromThePaneThePointerIsIn() throws Exception {
        EditorBuffer b = open("Hover.java", javaSource());
        CodeArea second = split(b);
        FxTestSupport.runOnFx(() -> {
            b.setLspActive(true);
            b.setLspDiagnostics(List.of(error(3, 12, 17, "cannot find symbol value")));
        });
        settle();
        moveOver(second, 3, 14);
        javafx.scene.control.Tooltip tip = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "lspTooltip"));
        assertNotNull(tip, "hovering a squiggle in pane 2 opens the diagnostic popup");
        assertTrue(FxTestSupport.callOnFx(tip::isShowing));
        assertTrue(FxTestSupport.callOnFx(tip::getText).contains("cannot find symbol value"));
        assertEquals(second, FxTestSupport.callOnFx(tip::getOwnerNode), "anchored to pane 2");
        double paneLeft = FxTestSupport.callOnFx(
                () -> second.localToScreen(second.getBoundsInLocal()).getMinX());
        assertTrue(FxTestSupport.callOnFx(tip::getAnchorX) >= paneLeft, "and shown over pane 2, not pane 1");
        FxTestSupport.runOnFx(tip::hide);

        // The debugger's value popup: the evaluator is asked for the word under pane 2's pointer.
        String[] asked = new String[1];
        FxTestSupport.runOnFx(() -> {
            b.setDebugHoverEvaluator((word, sink) -> {
                asked[0] = word;
                sink.accept("42");
            });
            b.setDebugHoverActive(true);
        });
        moveOver(second, 4, 9);
        assertEquals("total", asked[0]);
        javafx.scene.control.Tooltip value = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "debugTooltip"));
        assertNotNull(value);
        assertEquals("total = 42", FxTestSupport.callOnFx(value::getText));
        assertEquals(second, FxTestSupport.callOnFx(value::getOwnerNode));
        FxTestSupport.runOnFx(() -> b.setDebugHoverActive(false));
    }

    @Test
    void theSecondPaneHasTheColumnRulerAtItsOwnScrollPosition() throws Exception {
        String wide = "0123456789".repeat(30) + "\n";
        EditorBuffer b = open("ruler.txt", wide.repeat(40));
        CodeArea second = split(b);
        FxTestSupport.runOnFx(() -> {
            b.setWordWrap(false);
            b.setColumnRulerVisible(true);
            b.setRulerColumn(40);
        });
        Parent pane = pane2(b);
        javafx.scene.shape.Line ruler = (javafx.scene.shape.Line) overlay(pane, "column-ruler");
        assertNotNull(ruler, "pane 2 has a column ruler");
        settle();
        assertTrue(FxTestSupport.callOnFx(ruler::isVisible), "shown with pane 1's");
        assertEquals(columnX(pane, second, 40), FxTestSupport.callOnFx(ruler::getStartX), 1.0, "at column 40");
        assertTrue(FxTestSupport.callOnFx(ruler::getEndY) > 300, "as tall as the pane");
        shot("split-ruler");

        FxTestSupport.runOnFx(() -> second.scrollXBy(120)); // pane 2 only
        settle();
        assertEquals(
                columnX(pane, second, 40),
                FxTestSupport.callOnFx(ruler::getStartX),
                1.0,
                "it stays on the column when pane 2 scrolls sideways");
        javafx.scene.shape.Line primary = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "columnRuler"));
        assertTrue(
                FxTestSupport.callOnFx(primary::getStartX) - FxTestSupport.callOnFx(ruler::getStartX) > 100,
                "while pane 1's did not move");
        shot("split-ruler-scrolled");

        FxTestSupport.runOnFx(() -> b.setColumnRulerVisible(false));
        settle();
        assertTrue(!FxTestSupport.callOnFx(ruler::isVisible), "and is hidden with pane 1's");
    }

    /** Pane-local left edge of column {@code col} on the first line of {@code view}. */
    private static double columnX(Parent pane, CodeArea view, int col) throws Exception {
        return FxTestSupport.callOnFx(() -> pane.screenToLocal(
                        view.getCharacterBoundsOnScreen(col, col + 1).orElseThrow())
                .getMinX());
    }

    @Test
    void aceJumpLabelsThePaneThatHasFocus() throws Exception {
        EditorBuffer b = open("ace.txt", "alpha\nbeta\ngamma\ndelta\n");
        CodeArea second = split(b);
        FxTestSupport.runOnFx(() -> {
            ((Stage) FxTestSupport.field(fx.controller, "stage")).requestFocus();
            second.requestFocus();
        });
        settle();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                FxTestSupport.callOnFx(second::isFocused), "headless window could not focus the editor view");
        run("nav.aceJumpLine");
        Node labels = drawn(b, "acejump-overlay");
        Node primary = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "aceJump"));
        assertTrue(!FxTestSupport.callOnFx(primary::isVisible), "pane 1 is not labelled");
        shot("split-acejump");
        // Typing the label of the third line jumps pane 2's caret, not pane 1's.
        FxTestSupport.runOnFx(() -> second.fireEvent(new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_TYPED,
                "d",
                "",
                javafx.scene.input.KeyCode.UNDEFINED,
                false,
                false,
                false,
                false)));
        settle();
        assertEquals(2, FxTestSupport.callOnFx(second::getCurrentParagraph));
        assertEquals(0, FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()));
        assertTrue(!FxTestSupport.callOnFx(labels::isVisible));
    }

    /** A pointer move over column {@code col} of {@code line} in {@code view}, as the scene would deliver it. */
    private static void moveOver(CodeArea view, int line, int col) throws Exception {
        FxTestSupport.runOnFx(() -> {
            int at = view.getAbsolutePosition(line, col);
            var screen = view.getCharacterBoundsOnScreen(at, at + 1).orElseThrow();
            double sx = screen.getMinX() + 1;
            double sy = screen.getMinY() + screen.getHeight() / 2;
            var inScene = view.localToScene(view.screenToLocal(sx, sy)); // no source yet: x/y are scene coordinates
            view.fireEvent(new MouseEvent(
                    MouseEvent.MOUSE_MOVED,
                    inScene.getX(),
                    inScene.getY(),
                    sx,
                    sy,
                    MouseButton.NONE,
                    0,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    null));
        });
        settle();
    }
}
