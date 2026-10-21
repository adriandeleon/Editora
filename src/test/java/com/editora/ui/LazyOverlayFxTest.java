package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;

import com.editora.editor.EditorBuffer;
import com.editora.editor.SearchMatches;
import com.editora.editor.TodoMark;
import com.editora.editor.lsp.OccurrenceSpan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the lazy feature-overlay attachment in {@link EditorBuffer}: an off-feature buffer never
 * builds the overlays, stripes and popups of features it does not use (no Canvas, no subscriptions, no
 * popup scene), and activating a feature attaches its overlay <em>in the correct z-order</em> — by its
 * {@code Layer}, whatever else is or is not attached and in whatever sequence. This is the reusable
 * "feature ⇄ overlay" harness pattern for the buffer surface.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LazyOverlayFxTest {

    /** Every overlay, stripe and popup a plain buffer does without. */
    private static final List<String> LAZY = List.of(
            "searchOverlay",
            "occurrenceOverlay",
            "logOverlay",
            "lintOverlay",
            "lspOverlay",
            "aceJump",
            "snippetOverlay",
            "whitespace",
            "indentGuides",
            "mdLintOverlay",
            "inlineValues",
            "todoOverlay",
            "diagnosticStripe",
            "mdLintStripe",
            "todoStripe",
            "contextMenu",
            "noteTip");

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer newBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> new EditorBuffer());
    }

    private static int layer(Node node) {
        return (int) node.getProperties().get("editora.overlayLayer");
    }

    private static AnchorPane root(EditorBuffer b) {
        return FxTestSupport.field(b, "root");
    }

    private static List<String> classesOf(List<Node> nodes) {
        List<String> classes = new ArrayList<>();
        for (Node node : nodes) {
            classes.add(node.getClass().getSimpleName());
        }
        return classes;
    }

    private static TodoMark mark(int start, int end) {
        return new TodoMark(start, end, 0, "TODO: later", "#E5C07B");
    }

    private static long materializations(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(
                () -> (long) FxTestSupport.call(b, "documentSnapshotMaterializations", new Class[] {}));
    }

    @Test
    void offFeatureBufferBuildsNoLazyOverlays() throws Exception {
        EditorBuffer b = newBuffer();
        for (String f : LAZY) {
            assertNull(FxTestSupport.field(b, f), f + " must not be built for an off-feature buffer");
        }
        assertNull(
                FxTestSupport.field(b.getFoldManager(), "linePreview"),
                "the fold preview popup is built on the first hover over a collapsed line");
        // What the pane holds for a plain buffer: the text, the two on-by-default overlays, the minimap and
        // the ruler (a Line) — nothing that holds a viewport texture for a feature that is off.
        assertEquals(
                List.of("VirtualizedScrollPane", "NoteHighlightOverlay", "SpellCheckOverlay", "Minimap", "ColumnRuler"),
                classesOf(root(b).getChildren()));
    }

    @Test
    void searchActivationAttachesOverlayBelowTodo() throws Exception {
        EditorBuffer b = newBuffer();
        FxTestSupport.runOnFx(() -> {
            b.setSearchMatches(SearchMatches.of(0, 0), -1);
            FxTestSupport.invokeWith(b, "applyTodoMarks", List.class, List.of(mark(0, 4)));
        });

        Node search = FxTestSupport.field(b, "searchOverlay");
        Node todo = FxTestSupport.field(b, "todoOverlay");
        AnchorPane root = root(b);
        assertNotNull(search, "searching attaches the search overlay");
        assertTrue(root.getChildren().contains(search), "search overlay attached to the editor pane");
        assertTrue(
                root.getChildren().indexOf(search) < root.getChildren().indexOf(todo),
                "search overlay sits below the todo overlay (original z-order preserved)");
    }

    @Test
    void aceJumpActivationAttachesOverlay() throws Exception {
        EditorBuffer b = newBuffer();
        assertNull(FxTestSupport.field(b, "aceJump"));

        FxTestSupport.runOnFx(b::startAceJump);

        Node ace = FxTestSupport.field(b, "aceJump");
        AnchorPane root = root(b);
        assertNotNull(ace, "AceJump attaches its overlay on first use");
        assertTrue(root.getChildren().contains(ace), "AceJump overlay attached to the editor pane");
    }

    @Test
    void logActivationAttachesOverlayBelowWhitespace() throws Exception {
        EditorBuffer b = newBuffer();
        FxTestSupport.runOnFx(() -> {
            b.setWhitespaceVisible(true);
            b.setLogViewForced(true); // make isLog() true without a file
            b.setLogHighlightEnabled(true);
        });

        Node log = FxTestSupport.field(b, "logOverlay");
        Node whitespace = FxTestSupport.field(b, "whitespace");
        AnchorPane root = root(b);
        assertNotNull(log, "log highlighting on a log buffer attaches the overlay");
        assertNotNull(whitespace, "show-whitespace attaches the whitespace overlay");
        assertTrue(
                root.getChildren().indexOf(log) < root.getChildren().indexOf(whitespace),
                "log overlay sits below the whitespace overlay (original z-order preserved)");
    }

    @Test
    void disablingAnUnbuiltOverlayStaysNull() throws Exception {
        EditorBuffer b = newBuffer();
        // Turning a feature OFF on a buffer that never had it must not allocate the overlay.
        FxTestSupport.runOnFx(() -> {
            b.setLogHighlightEnabled(false);
            b.setLspActive(false);
            b.clearSearchMatches();
            b.clearOccurrenceSpans();
            b.setWhitespaceVisible(false);
            b.setMarkdownLintEnabled(false);
            b.setInlineValues(null, -1);
            b.setInlineValues(Map.of(), -1);
            b.setLspDiagnostics(List.of());
            b.setTodoHighlightEnabled(false);
            b.setTodoHighlightEnabled(true);
            FxTestSupport.invokeWith(b, "applyTodoMarks", List.class, List.of()); // an empty scan result
            b.setIndentGuidesVisible(false);
            b.setTabSize(2); // refreshes the indent guides, if any
            b.setLargeFile(true); // suppresses whitespace markers — on an overlay that does not exist
            b.setLargeFile(false);
            b.setFont("Monospaced", 13); // re-fonts the whitespace and inline-value overlays, if any
            b.setMinimapVisible(false); // re-anchors every over-text overlay and re-positions the stripes
            b.setMinimapVisible(true);
        });
        for (String f : LAZY) {
            assertNull(FxTestSupport.field(b, f), f + " must stay unbuilt when its feature is switched off");
        }
    }

    @Test
    void overlaysStackByLayerWhateverTheOrderTheyAreAttachedIn() throws Exception {
        EditorBuffer b = newBuffer();
        FxTestSupport.runOnFx(() -> {
            b.setContent("alpha beta\n");
            // Deliberately top-down and interleaved: the stack must not depend on this sequence.
            b.setInlineValues(Map.of("alpha", "1"), 0);
            b.setWhitespaceVisible(true);
            b.setIndentGuidesVisible(true);
            b.setLspActive(true); // not an LSP language: stays off, so no squiggles and no stripe
            b.setSearchMatches(SearchMatches.of(0, 5), 0);
            FxTestSupport.call(b, "mdLintOverlay", new Class[] {});
            FxTestSupport.call(b, "todoStripe", new Class[] {});
            b.setOccurrenceSpans(List.of(new OccurrenceSpan(0, 0, 0, 5, false)));
            FxTestSupport.call(b, "todoOverlay", new Class[] {});
            FxTestSupport.call(b, "diagnosticStripe", new Class[] {});
            b.startAceJump();
        });
        List<Node> children = root(b).getChildren();
        int previous = -1;
        for (Node child : children) {
            assertTrue(layer(child) > previous, "ascending layers: " + classesOf(children));
            previous = layer(child);
        }
        assertEquals(
                List.of(
                        "VirtualizedScrollPane",
                        "IndentGuideOverlay",
                        "NoteHighlightOverlay",
                        "SearchHighlightOverlay",
                        "OccurrenceHighlightOverlay",
                        "TodoHighlightOverlay",
                        "WhitespaceOverlay",
                        "SpellCheckOverlay",
                        "MarkdownLintOverlay",
                        "InlineValuesOverlay",
                        "AceJumpOverlay",
                        "Minimap",
                        "DiagnosticStripe",
                        "TodoStripe",
                        "ColumnRuler"),
                classesOf(children));
        assertNull(FxTestSupport.field(b, "lspOverlay"), "LSP stayed off for a plain buffer");
    }

    @Test
    void aLazilyAttachedOverlayTakesTheFontAndTheLargeFileGuardItMissed() throws Exception {
        EditorBuffer b = newBuffer();
        FxTestSupport.runOnFx(() -> {
            b.setFont("Serif", 21);
            b.setLargeFile(true);
            b.setWhitespaceVisible(true);
            b.setInlineValues(Map.of("x", "1"), 0);
        });
        Object whitespace = FxTestSupport.field(b, "whitespace");
        javafx.scene.text.Font wsFont = FxTestSupport.field(whitespace, "font");
        assertEquals(21, wsFont.getSize(), 0.01, "the whitespace overlay draws in the editor font");
        assertTrue((boolean) FxTestSupport.field(whitespace, "suppressed"), "and honours large-file mode");
        assertFalse((boolean) FxTestSupport.field(whitespace, "active"));
        javafx.scene.text.Font ivFont = FxTestSupport.field(FxTestSupport.field(b, "inlineValues"), "font");
        assertEquals(21, ivFont.getSize(), 0.01, "so does the inline-values overlay");

        FxTestSupport.runOnFx(() -> b.setLargeFile(false));
        assertTrue((boolean) FxTestSupport.field(whitespace, "active"), "and shows again when the guard lifts");
    }

    @Test
    void todoMarksAttachTheOverlayAndTheStripeOnlyWhenThereAreAny() throws Exception {
        EditorBuffer b = newBuffer();
        FxTestSupport.runOnFx(() -> {
            b.setContent("plain\nTODO: later\n");
            b.setTodoHighlightEnabled(true);
            FxTestSupport.invokeWith(b, "applyTodoMarks", List.class, List.of());
        });
        assertNull(FxTestSupport.field(b, "todoOverlay"), "an empty scan builds nothing");
        assertNull(FxTestSupport.field(b, "todoStripe"));

        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(b, "applyTodoMarks", List.class, List.of(mark(6, 10))));
        Node overlay = FxTestSupport.field(b, "todoOverlay");
        Node stripe = FxTestSupport.field(b, "todoStripe");
        assertNotNull(overlay, "the first mark attaches the overlay");
        assertNotNull(stripe, "and the stripe");
        assertTrue((boolean) FxTestSupport.field(stripe, "active"), "which is on, as TODO highlighting is");
        assertEquals(
                AnchorPane.getRightAnchor(FxTestSupport.field(b, "scrollPane")),
                AnchorPane.getRightAnchor(stripe),
                "docked just inside the minimap, where an eager stripe would have been moved to");

        FxTestSupport.runOnFx(() -> b.setMinimapVisible(false));
        assertEquals(0d, AnchorPane.getRightAnchor(stripe), "and it follows the minimap toggle");
        FxTestSupport.runOnFx(() -> b.setTodoHighlightEnabled(false));
        assertFalse((boolean) FxTestSupport.field(stripe, "active"), "switched off with the feature");
    }

    @Test
    void aSplitOpenedLaterGetsTwinsOfTheLazilyAttachedOverlaysAndStripes() throws Exception {
        EditorBuffer b = newBuffer();
        FxTestSupport.runOnFx(() -> {
            b.setContent("alpha beta\n".repeat(20));
            new javafx.scene.Scene(new StackPane(b.getNode()), 400, 300);
            b.setWhitespaceVisible(true);
            b.setSearchMatches(SearchMatches.of(0, 5), 0);
            FxTestSupport.invokeWith(b, "applyTodoMarks", List.class, List.of(mark(0, 5)));
            b.setSplit(EditorBuffer.Split.SIDE_BY_SIDE);
            // ...and one attached after the split must reach the second pane too.
            b.setInlineValues(Map.of("alpha", "1"), 0);
        });
        AnchorPane pane2 = FxTestSupport.field(b, "root2");
        List<String> twins = classesOf(pane2.getChildren());
        for (String expected : List.of(
                "SearchHighlightOverlay",
                "TodoHighlightOverlay",
                "WhitespaceOverlay",
                "InlineValuesOverlay",
                "TodoStripe")) {
            assertTrue(twins.contains(expected), expected + " has a twin in the second pane: " + twins);
        }
        assertEquals(
                twins.indexOf("SearchHighlightOverlay") + 1,
                twins.indexOf("TodoHighlightOverlay"),
                "stacked in the primary pane's order: " + twins);
        assertEquals(twins.indexOf("TodoHighlightOverlay") + 1, twins.indexOf("WhitespaceOverlay"), twins.toString());
    }

    @Test
    void theContextMenuIsBuiltByTheFirstRequestAndReusedAfter() throws Exception {
        EditorBuffer b = newBuffer();
        javafx.stage.Stage stage = FxTestSupport.callOnFx(javafx.stage.Stage::new);
        FxTestSupport.runOnFx(() -> {
            b.setContent("alpha\n");
            stage.setScene(new javafx.scene.Scene(new StackPane(b.getNode()), 400, 300));
            stage.show();
            b.getArea()
                    .getOnContextMenuRequested()
                    .handle(new javafx.scene.input.ContextMenuEvent(
                            javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, false, null));
        });
        javafx.scene.control.ContextMenu menu = FxTestSupport.field(b, "contextMenu");
        assertNotNull(menu, "the first request builds the menu");
        assertTrue(menu.getStyleClass().contains("editor-context-menu"));
        assertFalse(menu.getItems().isEmpty(), "and fills it");
        FxTestSupport.runOnFx(() -> {
            menu.hide();
            b.getArea()
                    .getOnContextMenuRequested()
                    .handle(new javafx.scene.input.ContextMenuEvent(
                            javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, false, null));
        });
        assertSame(menu, FxTestSupport.field(b, "contextMenu"), "one menu per buffer, as before");
        FxTestSupport.runOnFx(() -> {
            menu.hide();
            stage.hide();
        });
    }

    @Test
    void aTabHiddenLongEnoughGivesBackItsSnapshotCopyAndSpellGeometry() throws Exception {
        EditorBuffer b = newBuffer();
        FxTestSupport.runOnFx(() -> {
            b.setContent("alpha beta\ngamma\n");
            b.text();
        });
        // Clean: the snapshot IS the saved baseline, so releasing it would free nothing and cost a copy.
        long clean = materializations(b);
        FxTestSupport.runOnFx(() -> {
            b.setRenderingActive(false);
            FxTestSupport.call(b, "releaseHiddenCaches", new Class[] {});
            b.text();
        });
        assertEquals(clean, materializations(b), "a clean buffer keeps the String it shares with its baseline");

        // Dirty: the snapshot is a second whole-document String; a hidden tab lets it go, and the next
        // consumer rebuilds it.
        FxTestSupport.runOnFx(() -> {
            b.setRenderingActive(true);
            b.getArea().appendText("delta\n");
            b.text();
        });
        long dirty = materializations(b);
        Object spell = FxTestSupport.field(b, "spellOverlay");
        Map<Object, Object> geometry = FxTestSupport.field(spell, "geometry");
        Map<Object, Object> flagged = FxTestSupport.field(spell, "flaggedCache");
        FxTestSupport.runOnFx(() -> {
            geometry.put(new Object(), null);
            flagged.put("alpha beta", new int[0][]);
            FxTestSupport.call(b, "releaseHiddenCaches", new Class[] {}); // still shown: a no-op
        });
        assertEquals(1, geometry.size(), "nothing is released while the tab is on screen");
        FxTestSupport.runOnFx(() -> {
            b.setRenderingActive(false);
            FxTestSupport.call(b, "releaseHiddenCaches", new Class[] {});
        });
        assertTrue(geometry.isEmpty(), "squiggle geometry is dropped");
        assertTrue(flagged.isEmpty(), "and the per-line flagged spans");
        assertEquals(dirty, materializations(b), "releasing materializes nothing by itself");
        FxTestSupport.runOnFx(b::text);
        assertEquals(dirty + 1, materializations(b), "the next consumer rebuilds the snapshot on demand");
        assertEquals("alpha beta\ngamma\ndelta\n", FxTestSupport.callOnFx(b::text));
        assertTrue(b.isDirty(), "the baseline, and so the dirty state, are untouched");
    }
}
