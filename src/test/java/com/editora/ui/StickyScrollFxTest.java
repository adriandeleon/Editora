package com.editora.ui;

import java.util.List;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sticky scroll end to end: the pinned lines are produced from the buffer's real fold regions and its real
 * viewport, which is the half {@code StickyScrollTest} cannot cover — that test knows the decision is
 * right given a set of regions, this one knows the buffer hands it the right regions and the right first
 * visible line, and that turning the feature off actually clears the bar.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StickyScrollFxTest {

    private FxWindowFixture fx;

    @Test
    void longHtmlHeaderKeepsMinimapInsideViewport() throws Exception {
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            try {
                // Like generated HTML, the enclosing scope starts on one very long logical line.
                b.setLanguageOverride("html");
                b.setContent("<!DOCTYPE html><html data-description=\"" + "metadata ".repeat(300) + "\"><body>\n"
                        + "    <p>content</p>\n".repeat(100) + "</body></html>\n");
                b.setWordWrap(false);
                b.setStickyScrollEnabled(true);
                b.getFoldManager().recompute();
                call(b, "stickyLinesFor", int.class, 0);
                javafx.scene.layout.StackPane host = new javafx.scene.layout.StackPane(b.getNode());
                new javafx.scene.Scene(host, 1100, 700);
                host.applyCss();
                host.layout();
                javafx.scene.layout.Region root = FxTestSupport.field(b, "root");
                double initialMin = root.minWidth(-1);
                double initialPref = root.prefWidth(-1);
                call(b, "stickyLinesFor", int.class, 6);
                host.applyCss();
                host.layout();

                assertFalse(
                        ((List<?>) FxTestSupport.field(b, "stickyLines")).isEmpty(),
                        "the long HTML scope must actually be pinned");
                assertEquals(
                        initialMin,
                        root.minWidth(-1),
                        0.01,
                        "pinning a long header must not increase the editor's minimum width");
                assertEquals(
                        initialPref,
                        root.prefWidth(-1),
                        0.01,
                        "pinning a long header must not increase the editor's preferred width");

                // Also shrink and grow while pinned: a clip does not by itself constrain HBox/VBox sizing.
                for (double width : new double[] {1100, 600, 1400}) {
                    host.resize(width, 700);
                    host.layout();
                    javafx.scene.layout.Region mm = FxTestSupport.field(b, "minimap");
                    javafx.scene.layout.Region bar = (javafx.scene.layout.Region) call(b, "stickyScrollNode");
                    var viewport = host.localToScene(host.getLayoutBounds());
                    var overview = mm.localToScene(mm.getLayoutBounds());
                    assertEquals(width, root.getWidth(), 0.01, "the editor must fit its allocated viewport");
                    assertTrue(mm.isVisible() && mm.getWidth() > 0);
                    assertTrue(
                            overview.getMinX() >= viewport.getMinX() && overview.getMaxX() <= viewport.getMaxX() + 0.01,
                            "the minimap must stay inside the visible viewport");
                    assertTrue(bar.isVisible() && bar.getHeight() > 0);
                    for (javafx.scene.Node row : bar.getChildrenUnmodifiable()) {
                        var rowBounds = row.localToScene(row.getLayoutBounds());
                        assertTrue(
                                rowBounds.getMaxX() <= overview.getMinX(),
                                "each clipped header row must stop before the minimap");
                    }
                }
            } finally {
                b.dispose();
            }
        });
    }

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

    /** A class with a long method, so scrolling into the body leaves both headers above the viewport. */
    private static String source() {
        StringBuilder sb = new StringBuilder("class Outer {\n    void body() {\n");
        for (int i = 0; i < 200; i++) {
            sb.append("        int v").append(i).append(" = ").append(i).append(";\n");
        }
        return sb.append("    }\n}\n").toString();
    }

    private EditorBuffer buffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent(source());
            b.setStickyScrollEnabled(true);
            // Fold regions normally land on a 250 ms debounce after the text change. Waiting on real time
            // in a test is a flake waiting to happen, so force exactly what the debounce would have done.
            b.getFoldManager().recompute();
            return b;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> pinned(EditorBuffer b) throws Exception {
        return (List<Integer>) FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "stickyLines"));
    }

    /** Calls a package-private EditorBuffer member — this test lives in com.editora.ui. */
    private static Object call(Object target, String method, Object... argsAndTypes) {
        try {
            java.lang.reflect.Method m = argsAndTypes.length == 0
                    ? EditorBuffer.class.getDeclaredMethod(method)
                    : EditorBuffer.class.getDeclaredMethod(method, (Class<?>) argsAndTypes[0]);
            m.setAccessible(true);
            return argsAndTypes.length == 0 ? m.invoke(target) : m.invoke(target, argsAndTypes[1]);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** Drives the buffer's own update path for a viewport starting at {@code firstVisible}. */
    private static List<Integer> pinnedFor(EditorBuffer b, int firstVisible) throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                java.lang.reflect.Method m = EditorBuffer.class.getDeclaredMethod("stickyLinesFor", int.class);
                m.setAccessible(true);
                m.invoke(b, firstVisible);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        });
        return pinned(b);
    }

    @Test
    void deepInAMethodBothEnclosingHeadersArePinned() throws Exception {
        EditorBuffer b = buffer();
        assertEquals(List.of(0, 1), pinnedFor(b, 100), "the class and the method are both off screen above");
    }

    @Test
    void atTheTopOfTheFileNothingIsPinned() throws Exception {
        EditorBuffer b = buffer();
        assertTrue(pinnedFor(b, 0).isEmpty());
    }

    @Test
    void turningItOffClearsTheBar() throws Exception {
        EditorBuffer b = buffer();
        pinnedFor(b, 100);
        assertFalse(pinnedFor(b, 100).isEmpty());
        FxTestSupport.runOnFx(() -> b.setStickyScrollEnabled(false));
        assertTrue(pinned(b).isEmpty(), "disabling must clear what is pinned, not leave it frozen on screen");
    }

    /**
     * The bar is actually on screen: in the scene graph, visible, and with real width and height after a
     * layout pass.
     *
     * <p>This is the test that was missing. Every other assertion here is about the model — which lines
     * ought to be pinned — and the model was right while the feature was invisible, because the node was
     * unmanaged and {@code AnchorPane} lays out only managed children. "Correct and not rendered" is the
     * failure mode a model-level test cannot see, so this one measures the node.
     */
    @Test
    void theBarIsLaidOutWithRealBoundsWhenSomethingIsPinned() throws Exception {
        EditorBuffer b = buffer();
        javafx.scene.Node bar = FxTestSupport.callOnFx(() -> {
            javafx.scene.layout.StackPane host = new javafx.scene.layout.StackPane(b.getNode());
            javafx.scene.Scene scene = new javafx.scene.Scene(host, 900, 600);
            scene.getStylesheets()
                    .add(EditorBuffer.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
            host.applyCss();
            host.layout();
            call(b, "stickyLinesFor", int.class, 100);
            host.applyCss();
            host.layout();
            return (javafx.scene.Node) call(b, "stickyScrollNode");
        });
        assertTrue(bar.getScene() != null, "the bar was never added to the scene graph");
        assertTrue(bar.isVisible(), "the bar is in the graph but hidden");
        // The REGION's own width/height, not getBoundsInParent. Bounds come from the children, so an
        // unmanaged box still reports a non-zero box while the parent has never sized it — verified by
        // re-introducing the bug: the bounds assertion passed, this one fails. Width and height are what
        // AnchorPane actually sets, so they are what "is it laid out" means here.
        javafx.scene.layout.Region region = (javafx.scene.layout.Region) bar;
        double width = FxTestSupport.callOnFx(region::getWidth);
        double height = FxTestSupport.callOnFx(region::getHeight);
        assertTrue(width > 0, "the parent never sized the bar — it is anchored but not laid out");
        assertTrue(height > 0, "the bar has no height, so its background and border draw nothing");
    }

    @Test
    void fileSpecificCornerControlsStayAboveTheLazilyAttachedBar() throws Exception {
        EditorBuffer markdown = buffer();
        EditorBuffer html = buffer();
        FxTestSupport.runOnFx(() -> {
            javafx.scene.control.Button viewMode = new javafx.scene.control.Button("Editor / Split / Preview");
            javafx.scene.control.Button openInBrowser = new javafx.scene.control.Button("Open in Browser");
            markdown.setViewModeControl(viewMode);
            html.setHtmlPreviewControl(openInBrowser);

            // Reproduce the failing order: the file controls exist before the first scroll makes the sticky
            // bar attach itself. AnchorPane paints children in list order, so each control must follow it.
            assertCornerControlAboveSticky(markdown, viewMode, "the view-mode control must paint above it");
            assertCornerControlAboveSticky(html, openInBrowser, "the open-in-browser control must paint above it");
        });
    }

    private static void assertCornerControlAboveSticky(EditorBuffer b, javafx.scene.Node control, String message) {
        call(b, "stickyLinesFor", int.class, 100);
        javafx.scene.layout.AnchorPane root = FxTestSupport.field(b, "root");
        javafx.scene.Node bar = (javafx.scene.Node) call(b, "stickyScrollNode");
        int barIndex = root.getChildren().indexOf(bar);
        assertTrue(barIndex >= 0, "the sticky bar must be attached for this z-order check");
        assertTrue(root.getChildren().indexOf(control) > barIndex, message);
    }

    @Test
    void aLongHeaderRemainsOneVisualRow() throws Exception {
        String longHeader = "    void body(" + "String parameter, ".repeat(250) + "String last) {\n";
        StringBuilder source = new StringBuilder("class Outer {\n").append(longHeader);
        for (int i = 0; i < 200; i++) {
            source.append("        int v").append(i).append(" = ").append(i).append(";\n");
        }
        source.append("    }\n}\n");

        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setLanguageOverride("java");
            x.setContent(source.toString());
            x.setStickyScrollEnabled(true);
            x.getFoldManager().recompute();
            return x;
        });
        javafx.scene.layout.Region bar = FxTestSupport.callOnFx(() -> {
            javafx.scene.layout.StackPane host = new javafx.scene.layout.StackPane(b.getNode());
            new javafx.scene.Scene(host, 600, 400);
            host.applyCss();
            host.layout();
            call(b, "stickyLinesFor", int.class, 100);
            host.applyCss();
            host.layout();
            return (javafx.scene.layout.Region) call(b, "stickyScrollNode");
        });

        double height = FxTestSupport.callOnFx(bar::getHeight);
        assertTrue(height < 80, "a long logical header wrapped into an oversized sticky overlay: " + height);
    }

    @Test
    void aBufferWithNoNestingPinsNothing() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setLanguageOverride("plaintext");
            x.setContent("just\nsome\nprose\n".repeat(50));
            x.setStickyScrollEnabled(true);
            x.getFoldManager().recompute();
            return x;
        });
        assertTrue(pinnedFor(b, 40).isEmpty(), "there is nothing to pin in prose");
    }
}
