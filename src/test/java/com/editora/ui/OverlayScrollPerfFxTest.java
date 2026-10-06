package com.editora.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.editora.editor.EditorBuffer;
import com.editora.editor.LspDiagnostic;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the per-scroll and per-keystroke budgets of the things drawn over and beside the text: the pinned
 * scope headers, the Canvas overlays, the minimap and the gutter. Every assertion is a count, an identity or
 * a size — none is a timing — so they hold on any machine.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OverlayScrollPerfFxTest {

    private FxWindowFixture fx;
    private int files;

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

    // ---------------------------------------------------------------- sticky scroll

    /** A class with a long method, so scrolling into the body leaves both headers above the viewport. */
    private static String nested() {
        StringBuilder sb = new StringBuilder("class Outer {\n    void body() {\n");
        for (int i = 0; i < 200; i++) {
            sb.append("        int v").append(i).append(" = ").append(i).append(";\n");
        }
        return sb.append("    }\n}\n").toString();
    }

    @Test
    void scrollingInsideOneScopeRebuildsNothingAndAChangeRebuildsOnlyItsRow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            try {
                b.setLanguageOverride("java");
                b.setContent(nested());
                b.setStickyScrollEnabled(true);
                b.getFoldManager().recompute();
                StackPane host = new StackPane(b.getNode());
                new Scene(host, 900, 600);
                Object bar = FxTestSupport.field(b, "stickyScroll");
                Parent box = (Parent) FxTestSupport.call(bar, "node", new Class[] {});
                Region root = FxTestSupport.field(b, "root");

                pin(b, 10);
                host.applyCss();
                host.layout();
                for (int i = 0; i < 5 && root.isNeedsLayout(); i++) {
                    host.layout(); // overlays ask for one more pass while they size themselves
                }
                assertFalse(root.isNeedsLayout(), "precondition: the code pane's layout has settled");
                List<Node> rows = List.copyOf(box.getChildrenUnmodifiable());
                assertEquals(2, rows.size(), "the class and the method are pinned");
                int built = rowsBuilt(bar);
                int tooltips = staticInt("com.editora.editor.LazyTooltip", "created");
                assertEquals(0, tooltips, "no row has been hovered, so no tooltip exists");

                for (int first = 11; first < 150; first++) {
                    pin(b, first);
                }
                assertEquals(built, rowsBuilt(bar), "the same two lines stay pinned: no row is built");
                assertSame(rows.get(0), box.getChildrenUnmodifiable().get(0));
                assertSame(rows.get(1), box.getChildrenUnmodifiable().get(1));
                assertFalse(root.isNeedsLayout(), "an unchanged bar must not dirty the code pane's layout");
                assertEquals(tooltips, staticInt("com.editora.editor.LazyTooltip", "created"));

                // The pinned set shrinks: the surviving row is the same node.
                pin(b, 1);
                assertEquals(List.of(rows.get(0)), box.getChildrenUnmodifiable());
                assertEquals(built, rowsBuilt(bar));
                pin(b, 10);
                assertSame(rows.get(0), box.getChildrenUnmodifiable().get(0));
                assertEquals(built + 1, rowsBuilt(bar), "the method row went away and is built again");
                Node method = box.getChildrenUnmodifiable().get(1);

                // The text of a pinned line changes: that row, and only that row, is rebuilt.
                b.getArea().insertText(1, 4, "final ");
                pin(b, 10);
                assertSame(rows.get(0), box.getChildrenUnmodifiable().get(0));
                assertNotSame(method, box.getChildrenUnmodifiable().get(1));
                assertTrue(rowText(box.getChildrenUnmodifiable().get(1)).contains("final void body()"));
                assertEquals(built + 2, rowsBuilt(bar));

                // The styling of a pinned line changes (a re-highlight replaces the paragraph).
                b.getArea().setStyleClass(0, 5, "perf-probe-style");
                pin(b, 10);
                Node restyled = box.getChildrenUnmodifiable().get(0);
                assertNotSame(rows.get(0), restyled);
                assertTrue(
                        ((Parent) restyled)
                                .getChildrenUnmodifiable().stream()
                                        .anyMatch(n -> n.getStyleClass().contains("perf-probe-style")),
                        "the rebuilt row carries the new style class");
                assertEquals(built + 3, rowsBuilt(bar));

                // A font change restyles the rows through CSS: same nodes, new inherited font.
                b.setFont("Monospaced", 23);
                pin(b, 10);
                assertSame(restyled, box.getChildrenUnmodifiable().get(0));
                assertTrue(box.getStyle().contains("23px"), box.getStyle());

                // The tooltip arrives with the pointer.
                assertNull(restyled.getProperties().get("javafx.scene.control.Tooltip"));
                javafx.event.Event.fireEvent(restyled, mouse(MouseEvent.MOUSE_ENTERED));
                assertNotNull(restyled.getProperties().get("javafx.scene.control.Tooltip"));
                assertEquals(tooltips + 1, staticInt("com.editora.editor.LazyTooltip", "created"));
            } finally {
                b.dispose();
            }
        });
    }

    private static void pin(EditorBuffer b, int firstVisible) {
        FxTestSupport.invokeWith(b, "stickyLinesFor", int.class, firstVisible);
    }

    private static int rowsBuilt(Object bar) {
        return (int) FxTestSupport.call(bar, "rowsBuiltForTest", new Class[] {});
    }

    private static String rowText(Node row) {
        StringBuilder sb = new StringBuilder();
        for (Node n : ((Parent) row).getChildrenUnmodifiable()) {
            sb.append(((javafx.scene.text.Text) n).getText());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- overlay textures (rule 6)

    private static final List<String> OVERLAYS = List.of(
            "whitespace",
            "mdLintOverlay",
            "lintOverlay",
            "lspOverlay",
            "logOverlay",
            "spellOverlay",
            "todoOverlay",
            "noteOverlay");

    @Test
    void backgroundTabsAndEmptyOverlaysHoldNoViewportTexture() throws Exception {
        List<EditorBuffer> buffers = new ArrayList<>();
        buffers.add(open("one.md", "# Title\n\nsome   text\n".repeat(40)));
        buffers.add(open("two.java", nested()));
        buffers.add(open("three.md", "# Other\n\nmore  text\n".repeat(40)));
        for (EditorBuffer b : buffers) {
            FxTestSupport.runOnFx(() -> {
                b.setWhitespaceVisible(true);
                Object md = FxTestSupport.field(b, "mdLintOverlay");
                FxTestSupport.invokeWith(md, "setActive", boolean.class, true);
                FxTestSupport.invokeWith(
                        md,
                        "setDiagnostics",
                        List.class,
                        List.of(new com.editora.markdown.MarkdownLint.Diagnostic(1, 1, 3, "warning", "MD000", "m")));
                Object mermaid = FxTestSupport.call(b, "lintOverlay", new Class[] {});
                FxTestSupport.invokeWith(mermaid, "setActive", boolean.class, true);
                FxTestSupport.invokeWith(
                        mermaid,
                        "setDiagnostics",
                        List.class,
                        List.of(new com.editora.mermaid.MaidOutput.Diagnostic(1, 1, 3, "error", "E", "m")));
                Object lsp = FxTestSupport.call(b, "lspOverlay", new Class[] {});
                FxTestSupport.invokeWith(lsp, "setActive", boolean.class, true);
                FxTestSupport.invokeWith(lsp, "setDiagnostics", List.class, List.of(diagnostic()));
                Object log = FxTestSupport.call(b, "logOverlay", new Class[] {});
                FxTestSupport.invokeWith(log, "setActive", boolean.class, true);
            });
        }
        for (EditorBuffer b : buffers) {
            select(b);
        }
        EditorBuffer shown = buffers.get(buffers.size() - 1);
        for (EditorBuffer b : buffers) {
            for (String name : OVERLAYS) {
                Canvas canvas = canvasOf(b, name);
                if (b != shown) {
                    assertEquals(1, canvas.getWidth(), name + " of a background tab keeps its texture");
                    assertEquals(1, canvas.getHeight(), name + " of a background tab keeps its texture");
                }
            }
        }
        for (String name : List.of("whitespace", "mdLintOverlay", "lintOverlay", "lspOverlay", "logOverlay")) {
            assertTrue(drawn(shown, name), name + " of the visible tab draws");
        }

        // An active overlay with nothing to draw lets its texture go, and takes it back with content.
        Object lsp = FxTestSupport.callOnFx(() -> FxTestSupport.field(shown, "lspOverlay"));
        Object md = FxTestSupport.callOnFx(() -> FxTestSupport.field(shown, "mdLintOverlay"));
        Object mermaid = FxTestSupport.callOnFx(() -> FxTestSupport.field(shown, "lintOverlay"));
        FxTestSupport.runOnFx(() -> {
            for (Object overlay : List.of(lsp, md, mermaid)) {
                FxTestSupport.invokeWith(overlay, "setDiagnostics", List.class, List.of());
            }
        });
        settle();
        for (String name : List.of("lspOverlay", "mdLintOverlay", "lintOverlay")) {
            assertEquals(1, canvasOf(shown, name).getWidth(), name + " with an empty list");
            assertEquals(1, canvasOf(shown, name).getHeight(), name + " with an empty list");
        }
        FxTestSupport.runOnFx(() -> {
            shown.getFocusedArea().scrollYBy(40); // scrolling a clean file queues no repaint for it
            assertFalse((boolean) FxTestSupport.field(lsp, "redrawPending"));
            assertFalse((boolean) FxTestSupport.field(md, "redrawPending"));
            FxTestSupport.invokeWith(lsp, "setDiagnostics", List.class, List.of(diagnostic()));
        });
        assertTrue(drawn(shown, "lspOverlay"), "content brings the canvas back");

        // Reselecting a background tab restores its drawing and releases the one just left.
        EditorBuffer back = buffers.get(0);
        select(back);
        for (String name : List.of("whitespace", "mdLintOverlay", "lintOverlay", "lspOverlay", "logOverlay")) {
            assertTrue(drawn(back, name), name + " repaints when its tab is shown again");
            assertEquals(1, canvasOf(shown, name).getWidth(), name + " of the tab just left");
        }

        // An overlay first attached while its tab is in the background starts released.
        EditorBuffer hidden = buffers.get(1);
        FxTestSupport.runOnFx(() -> {
            hidden.setSearchMatches(
                    com.editora.editor.SearchMatches.ofPairs(List.of()),
                    -1); // unrelated lazy overlay: must not disturb the others
            FxTestSupport.invokeWith(
                    FxTestSupport.field(hidden, "lspOverlay"), "setDiagnostics", List.class, List.of(diagnostic()));
        });
        settle();
        assertEquals(1, canvasOf(hidden, "lspOverlay").getWidth());
    }

    private static LspDiagnostic diagnostic() {
        return new LspDiagnostic(0, 0, 0, 3, LspDiagnostic.Severity.ERROR, "m", null, null);
    }

    /** Whether the overlay sizes its canvas up within a few frames (a repaint follows the layout pass). */
    private boolean drawn(EditorBuffer b, String overlayField) throws Exception {
        for (int i = 0; i < 40; i++) {
            if (canvasOf(b, overlayField).getWidth() > 1) {
                return true;
            }
            settle();
        }
        return false;
    }

    private Canvas canvasOf(EditorBuffer b, String overlayField) throws Exception {
        return FxTestSupport.callOnFx(() -> FxTestSupport.field(FxTestSupport.field(b, overlayField), "canvas"));
    }

    // ---------------------------------------------------------------- whitespace overlay

    @Test
    void whitespaceMarkersCostAFewLayoutQueriesPerLineAndLandWhereMeasuredOnesDo() throws Exception {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            code.append("    int value").append(i).append(" = compute(alpha, beta) + offset;  // trailing note\n");
        }
        EditorBuffer b = open("spaces.txt", code.toString());
        FxTestSupport.runOnFx(() -> {
            b.setWordWrap(false);
            b.setWhitespaceVisible(true);
        });
        settle();
        Object overlay = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "whitespace"));
        AtomicInteger queries = staticCounter("com.editora.editor.WhitespaceOverlay", "LAYOUT_QUERIES_FOR_TEST");

        int[] cost = redrawCost(b, overlay, queries);
        assertTrue(cost[1] >= 20, "a real viewport is being drawn: " + cost[1] + " lines");
        // Each of these lines has ten whitespace runs, so measuring per run costs 10+ queries a line.
        assertTrue(
                cost[0] <= 2 * cost[1] + 2,
                "a code viewport costs at most two queries per line, but " + cost[0] + " for " + cost[1] + " lines");
        assertPicturesAgree(b, overlay);

        // One 20,000-character line: the cost must not depend on its length, at either end of it.
        EditorBuffer wide = open("wide.txt", "word ".repeat(4000) + "\nshort line\n".repeat(60));
        FxTestSupport.runOnFx(() -> {
            wide.setWordWrap(false);
            wide.setWhitespaceVisible(true);
        });
        settle();
        Object wideOverlay = FxTestSupport.callOnFx(() -> FxTestSupport.field(wide, "whitespace"));
        cost = redrawCost(wide, wideOverlay, queries);
        assertTrue(cost[0] <= 3 * cost[1] + 2, "20k-character line at column 0: " + cost[0] + " queries");
        assertPicturesAgree(wide, wideOverlay);
        FxTestSupport.runOnFx(() -> wide.getFocusedArea().scrollXBy(60_000));
        settle();
        cost = redrawCost(wide, wideOverlay, queries);
        assertTrue(cost[0] <= 3 * cost[1] + 2, "20k-character line scrolled to its middle: " + cost[0]);
        assertPicturesAgree(wide, wideOverlay);

        // Tab-indented code: the indentation is measured tab by tab, the rest of the line by arithmetic.
        EditorBuffer indented = open("indented.txt", "\t\tint value = compute(alpha, beta);  // note\n".repeat(80));
        FxTestSupport.runOnFx(() -> {
            indented.setWordWrap(false);
            indented.setWhitespaceVisible(true);
        });
        settle();
        Object indentedOverlay = FxTestSupport.callOnFx(() -> FxTestSupport.field(indented, "whitespace"));
        cost = redrawCost(indented, indentedOverlay, queries);
        assertTrue(cost[0] <= 4 * cost[1] + 3, "two tabs and two measurements a line: " + cost[0]);
        assertPicturesAgree(indented, indentedOverlay);

        // Tabs put a line on the measured path; a long one is still culled to the visible columns first.
        EditorBuffer tabs = open("tabs.txt", "a\tb ".repeat(4000) + "\nshort line\n".repeat(60));
        FxTestSupport.runOnFx(() -> {
            tabs.setWordWrap(false);
            tabs.setWhitespaceVisible(true);
        });
        settle();
        Object tabsOverlay = FxTestSupport.callOnFx(() -> FxTestSupport.field(tabs, "whitespace"));
        cost = redrawCost(tabs, tabsOverlay, queries);
        assertTrue(cost[0] < 1000, "8,000 whitespace runs, a screenful measured: " + cost[0] + " queries");

        // Large-file mode (which includes the 64 KiB long-line profile) draws nothing at all.
        FxTestSupport.runOnFx(() -> tabs.setLargeFile(true));
        settle();
        assertFalse((boolean) FxTestSupport.callOnFx(() -> FxTestSupport.field(tabsOverlay, "active")));
        assertEquals(1, canvasOf(tabs, "whitespace").getWidth());
        cost = redrawCost(tabs, tabsOverlay, queries);
        assertEquals(0, cost[0], "large-file mode issues no layout query");
        FxTestSupport.runOnFx(() -> tabs.setLargeFile(false));
        settle();
        assertTrue(
                (boolean) FxTestSupport.callOnFx(() -> FxTestSupport.field(tabsOverlay, "active")),
                "the setting applies again on leaving large-file mode");
    }

    /** Runs one repaint; returns {queries issued, visible lines}. */
    private int[] redrawCost(EditorBuffer b, Object overlay, AtomicInteger queries) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            CodeArea a = b.getArea();
            int before = queries.get();
            FxTestSupport.invoke(overlay, "redraw");
            int lines = a.lastVisibleParToAllParIndex() - a.firstVisibleParToAllParIndex() + 1;
            return new int[] {queries.get() - before, lines};
        });
    }

    /**
     * Paints the viewport through the arithmetic path and through the per-run measured path and requires
     * the same markers in the same places: one placed by arithmetic must sit where a measured one would.
     */
    private void assertPicturesAgree(EditorBuffer b, Object overlay) throws Exception {
        Class<?> type = Class.forName("com.editora.editor.WhitespaceOverlay");
        Field path = type.getDeclaredField("uniformPath");
        Field recorder = type.getDeclaredField("markersForTest");
        path.setAccessible(true);
        recorder.setAccessible(true);
        FxTestSupport.runOnFx(() -> {
            try {
                List<double[]> fast = new ArrayList<>();
                recorder.set(null, fast);
                FxTestSupport.invoke(overlay, "redraw");
                List<double[]> measured = new ArrayList<>();
                recorder.set(null, measured);
                path.setBoolean(null, false);
                FxTestSupport.invoke(overlay, "redraw");
                assertTrue(measured.size() > 20, "the measured rendering drew markers: " + measured.size());
                assertEquals(measured.size(), fast.size(), "both paths draw the same number of markers");
                java.util.Comparator<double[]> order = java.util.Comparator.<double[]>comparingDouble(m -> m[2])
                        .thenComparingDouble(m -> m[1]);
                fast.sort(order);
                measured.sort(order);
                for (int i = 0; i < measured.size(); i++) {
                    assertEquals(measured.get(i)[0], fast.get(i)[0], "marker " + i + " glyph");
                    assertEquals(measured.get(i)[1], fast.get(i)[1], 0.05, "marker " + i + " x");
                    assertEquals(measured.get(i)[2], fast.get(i)[2], 0.05, "marker " + i + " y");
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            } finally {
                try {
                    path.setBoolean(null, true);
                    recorder.set(null, null);
                } catch (IllegalAccessException ignored) {
                    // unreachable: both fields were made accessible above
                }
            }
        });
    }

    // ---------------------------------------------------------------- minimap

    @Test
    void theMinimapRepaintsOnlyForAChangeAndRendersOncePerTabSwitch() throws Exception {
        EditorBuffer first = open("mini-a.java", nested());
        EditorBuffer second = open("mini-b.java", nested());
        Object minimap = FxTestSupport.callOnFx(() -> FxTestSupport.field(second, "minimap"));
        for (int i = 0;
                i < 40 && FxTestSupport.callOnFx(() -> FxTestSupport.field(minimap, "contentImage")) == null;
                i++) {
            settle();
        }
        assertNotNull(FxTestSupport.callOnFx(() -> FxTestSupport.field(minimap, "contentImage")), "minimap rendered");

        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invokeWith(minimap, "setDiagnostics", List.class, List.of());
            FxTestSupport.invokeWith(minimap, "setTodoMarks", List.class, List.of());
            FxTestSupport.invokeWith(minimap, "setLintMarks", List.class, List.of());
            assertFalse(
                    (boolean) FxTestSupport.field(minimap, "redrawPending"),
                    "clearing marks that are already clear (every keystroke of an LSP buffer) repaints nothing");
            FxTestSupport.invokeWith(minimap, "setDiagnosticsEnabled", boolean.class, true);
            FxTestSupport.invokeWith(minimap, "setDiagnostics", List.class, List.of(diagnostic()));
            assertTrue((boolean) FxTestSupport.field(minimap, "redrawPending"), "a real change is repainted");
        });
        settle();
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invokeWith(minimap, "setDiagnostics", List.class, List.of());
            assertTrue((boolean) FxTestSupport.field(minimap, "redrawPending"), "and so is its removal");
        });
        settle();

        AtomicInteger renders = staticCounter("com.editora.editor.Minimap", "RENDERS_FOR_TEST");
        int before = renders.get();
        select(first);
        settle();
        assertEquals(1, renders.get() - before, "a tab switch renders the shown tab's minimap exactly once");

        // A height change while content is cached re-blits it and defers the expensive render.
        Object shown = FxTestSupport.callOnFx(() -> FxTestSupport.field(first, "minimap"));
        int beforeResize = renders.get();
        FxTestSupport.runOnFx(() -> {
            Region region = (Region) shown;
            for (int i = 1; i <= 8; i++) {
                region.resize(region.getWidth(), region.getHeight() - i);
                region.layout();
            }
            assertFalse((boolean) FxTestSupport.field(shown, "renderPending"), "no render is queued per resize step");
            javafx.animation.Animation settleTimer = FxTestSupport.field(shown, "resizeRender");
            assertEquals(javafx.animation.Animation.Status.RUNNING, settleTimer.getStatus());
        });
        for (int i = 0; i < 40 && renders.get() == beforeResize; i++) {
            settle();
        }
        assertEquals(1, renders.get() - beforeResize, "one render follows once the size has settled");
    }

    @Test
    void aMinimapRowStopsScanningAtTheRightEdge() throws Exception {
        Method drawRuns = Class.forName("com.editora.editor.Minimap")
                .getDeclaredMethod(
                        "drawRuns",
                        javafx.scene.canvas.GraphicsContext.class,
                        String.class,
                        double.class,
                        double.class,
                        double.class,
                        int.class);
        drawRuns.setAccessible(true);
        FxTestSupport.runOnFx(() -> {
            try {
                Canvas canvas = new Canvas(100, 10);
                String minified = "x=1;".repeat(12_500); // 50,000 characters
                int scanned = (int) drawRuns.invoke(null, canvas.getGraphicsContext2D(), minified, 0d, 1d, 100d, 4);
                assertTrue(scanned < 1000, "a 100 px column examined " + scanned + " of 50,000 characters");
                int all = (int) drawRuns.invoke(null, canvas.getGraphicsContext2D(), "int x = 1;", 0d, 1d, 100d, 4);
                assertEquals(10, all, "a line that fits is scanned whole");
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    // ---------------------------------------------------------------- gutter

    @Test
    void aGutterRowBuildsAChevronOnlyWhereAFoldStartsAndNoTooltipUntilHovered() throws Exception {
        EditorBuffer b = open("gutter.java", nested());
        FxTestSupport.runOnFx(() -> b.getFoldManager().recompute());
        settle();
        int tooltips = staticInt("com.editora.editor.LazyTooltip", "created");
        FxTestSupport.runOnFx(() -> {
            var factory = b.getFoldManager().gutterFactory(true);
            Parent header = (Parent) factory.apply(1); // "void body() {"
            Parent plain = (Parent) factory.apply(40);
            for (int i = 0; i < 200; i++) {
                factory.apply(i);
            }
            assertEquals(tooltips, staticInt("com.editora.editor.LazyTooltip", "created"), "rows build no tooltip");

            assertEquals(1, labels(header, "fold-chevron"), "a fold-start line has its chevron");
            assertEquals(0, labels(plain, "fold-chevron"), "any other line builds no chevron control");
            assertEquals(
                    1,
                    plain.getChildrenUnmodifiable().stream()
                            .filter(n -> n.getStyleClass().contains("fold-chevron-slot"))
                            .count(),
                    "it reserves the column with a bare spacer instead");

            // Laid out under the real stylesheet, both kinds of row are the same width: no text shift.
            VBox rows = new VBox(header, plain);
            StackPane editor = new StackPane(rows);
            editor.getStyleClass().add("editor-area");
            Scene scene = new Scene(editor, 400, 200);
            scene.getStylesheets()
                    .add(EditorBuffer.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            editor.applyCss();
            editor.layout();
            assertTrue(header.prefWidth(-1) > 19);
            assertEquals(header.prefWidth(-1), plain.prefWidth(-1), 0.01, "the chevron column keeps its width");
        });

        Method tint = Class.forName("com.editora.editor.FoldManager").getDeclaredMethod("blameTint", String.class);
        tint.setAccessible(true);
        Object a = tint.invoke(null, "rgba(240,165,70,0.123)");
        assertNotNull(a);
        assertSame(a, tint.invoke(null, "rgba(240,165,70,0.123)"), "one Background per heatmap tint");
        assertNull(tint.invoke(null, "linear-gradient(red, blue)"), "anything else is left to the CSS parser");
    }

    private static long labels(Parent row, String styleClass) {
        return row.getChildrenUnmodifiable().stream()
                .filter(n -> n instanceof Label && n.getStyleClass().contains(styleClass))
                .count();
    }

    // ---------------------------------------------------------------- log overlay, selection bars

    @Test
    void repaintingALogViewportScansNoLineTwice() throws Exception {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            log.append("2026-10-05 10:00:").append(String.format("%02d", i % 60));
            log.append(i % 7 == 0 ? " ERROR " : " INFO ")
                    .append("request ")
                    .append(i)
                    .append(" handled\n");
            if (i % 7 == 0) {
                log.append("java.lang.IllegalStateException: boom ").append(i).append('\n');
                log.append("\tat com.example.Service.handle(Service.java:")
                        .append(i)
                        .append(")\n");
            }
        }
        EditorBuffer b = open("server.log", log.toString());
        FxTestSupport.runOnFx(() -> {
            b.setLogViewForced(true);
            b.setLogHighlightEnabled(true);
            b.getFocusedArea().showParagraphAtTop(120);
        });
        settle();
        Object overlay = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "logOverlay"));
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.invoke(overlay, "redraw");
            int scans = (int) FxTestSupport.call(overlay, "levelScansForTest", new Class[] {});
            assertTrue(scans > 0, "the viewport's lines were scanned once");
            for (int i = 0; i < 5; i++) {
                FxTestSupport.invoke(overlay, "redraw");
            }
            assertEquals(
                    scans,
                    (int) FxTestSupport.call(overlay, "levelScansForTest", new Class[] {}),
                    "a repaint of unchanged lines (stack-trace lines included) runs no level pattern");
        });
    }

    @Test
    void scrollingWithoutASelectionQueuesNoSelectionBarUpdate() throws Exception {
        EditorBuffer b = open("bars.md", "# Title\n\n" + "paragraph of text\n".repeat(200));
        FxTestSupport.runOnFx(() -> {
            b.setFormatBarEnabled(true);
            b.setAiActionsEnabled(true);
        });
        settle();
        FxTestSupport.runOnFx(() -> {
            CodeArea a = b.getFocusedArea();
            a.deselect();
            a.scrollYBy(60);
            a.moveTo(5);
            assertFalse((boolean) FxTestSupport.field(b, "formatBarUpdatePending"), "no selection, no format bar");
            assertFalse((boolean) FxTestSupport.field(b, "aiActionsBarUpdatePending"), "nor an AI actions bar");
            a.selectRange(2, 6);
            assertTrue((boolean) FxTestSupport.field(b, "formatBarUpdatePending"), "a selection can show the bar");
            assertTrue((boolean) FxTestSupport.field(b, "aiActionsBarUpdatePending"));
        });
        settle();
    }

    // ---------------------------------------------------------------- helpers

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
        return new MouseEvent(
                type,
                1,
                1,
                1,
                1,
                javafx.scene.input.MouseButton.NONE,
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
                null);
    }

    private static int staticInt(String className, String field) {
        try {
            Field f = Class.forName(className).getDeclaredField(field);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AtomicInteger staticCounter(String className, String field) throws Exception {
        Field f = Class.forName(className).getDeclaredField(field);
        f.setAccessible(true);
        return (AtomicInteger) f.get(null);
    }

    private static void settle() throws Exception {
        FxTestSupport.drainFx();
        Thread.sleep(150);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    private void select(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> {
            TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
            for (var tab : tabs.getTabs()) {
                if (tab.getContent() == b.getNode()) {
                    tabs.getSelectionModel().select(tab);
                    return;
                }
            }
            throw new IllegalStateException("no tab shows " + b.getPath());
        });
        settle();
        settle();
    }

    /** Opens {@code content} as a real file and waits for it to be the active, loaded buffer. */
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
}
