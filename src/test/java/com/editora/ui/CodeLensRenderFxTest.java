package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Code lenses through a real {@link EditorBuffer}: drawn after the end of their line, never part of the
 * document, carried along by edits, and clickable.
 */
@Tag("fx")
class CodeLensRenderFxTest {

    private static final String TEXT = "class A {\n    void run() {}\n    int size() { return 0; }\n}\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer buffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent(TEXT);
            return b;
        });
    }

    private static List<EditorBuffer.CodeLens> lenses() {
        return List.of(
                new EditorBuffer.CodeLens(1, "2 references", "run"),
                new EditorBuffer.CodeLens(2, "1 reference", "size"),
                new EditorBuffer.CodeLens(2, "3 implementations", "size-impl"));
    }

    @Test
    void aLensFollowsTheEndOfItsLineAndStaysOutOfTheDocument() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> b.setCodeLenses(lenses()));

        var onRun = FxTestSupport.callOnFx(() -> b.getArea().getInlayFactory().apply(1));
        assertEquals(1, onRun.size());
        assertEquals("    void run() {}".length(), onRun.get(0).getColumn());
        assertEquals("2 references", onRun.get(0).getText());
        assertEquals("code-lens", onRun.get(0).getStyleClass());
        assertEquals(
                "1 reference  ·  3 implementations",
                FxTestSupport.callOnFx(
                        () -> b.getArea().getInlayFactory().apply(2).get(0).getText()),
                "lenses on one line are joined");
        assertEquals(TEXT, FxTestSupport.callOnFx(b::getContent), "a lens must not reach the document");
    }

    @Test
    void aLensSharesItsLineWithInlayHints() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.setInlayHints(List.of(new EditorBuffer.InlayHint(2, 4, "hint:")));
            b.setCodeLenses(lenses());
        });

        var onSize = FxTestSupport.callOnFx(() -> b.getArea().getInlayFactory().apply(2));
        assertEquals(
                List.of("inlay-hint", "code-lens"),
                onSize.stream().map(i -> i.getStyleClass()).toList());

        FxTestSupport.runOnFx(() -> b.setCodeLenses(null));
        assertEquals(
                1,
                FxTestSupport.callOnFx(
                        () -> b.getArea().getInlayFactory().apply(2).size()),
                "clearing the lenses leaves the hints");
    }

    @Test
    void editsCarryTheLensesWithTheirLines() throws Exception {
        EditorBuffer b = buffer();
        FxTestSupport.runOnFx(() -> {
            b.setCodeLenses(lenses());
            b.getArea().insertText(0, "// header\n\n"); // two lines in front of everything
        });
        assertTrue(FxTestSupport.callOnFx(() -> b.codeLensesOn(1).isEmpty()));
        assertEquals(
                "2 references",
                FxTestSupport.callOnFx(() -> b.codeLensesOn(3).get(0).label()));
        assertEquals(2, FxTestSupport.callOnFx(() -> b.codeLensesOn(4).size()));

        // Delete the run() line whole: its lens goes, size()'s moves up.
        FxTestSupport.runOnFx(() -> {
            int start = b.getArea().getAbsolutePosition(3, 0);
            b.getArea().deleteText(start, b.getArea().getAbsolutePosition(4, 0));
        });
        assertEquals(
                List.of("1 reference", "3 implementations"),
                FxTestSupport.callOnFx(() -> b.codeLensesOn(3).stream()
                        .map(EditorBuffer.CodeLens::label)
                        .toList()));
        assertTrue(FxTestSupport.callOnFx(() -> b.codeLensesOn(4).isEmpty()));
    }

    @Test
    void clickingALensRunsItsHandlerWithTheLineAndItsLenses() throws Exception {
        EditorBuffer b = buffer();
        List<Object> clicked = new ArrayList<>();
        Stage[] shown = new Stage[1];
        FxTestSupport.runOnFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(b.getNode()), 800, 600));
            stage.show();
            shown[0] = stage;
            b.setCodeLensHandler((line, onLine) -> {
                clicked.add(line);
                onLine.forEach(l -> clicked.add(l.token()));
            });
            b.setCodeLenses(lenses());
        });
        try {
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                b.getNode().applyCss();
                b.getNode().layout();
            });
            FxTestSupport.drainFx();
            Node label = FxTestSupport.callOnFx(() -> b.getArea().lookupAll(".code-lens").stream()
                    .filter(n -> n instanceof javafx.scene.control.Label l
                            && l.getText().startsWith("2 ref"))
                    .findFirst()
                    .orElse(null));
            assertNotNull(label, "the lens is a node in the paragraph");

            // Anywhere on the label is that line's lens. Its right half is nearer to the start of the next
            // line than to the end of its own; where the middle falls depends on the platform's text layout.
            for (double across : new double[] {0.05, 0.5, 0.95}) {
                clicked.clear();
                FxTestSupport.runOnFx(() -> {
                    var bounds = label.localToScene(label.getBoundsInLocal());
                    Point2D inArea = b.getArea()
                            .sceneToLocal(bounds.getMinX() + across * bounds.getWidth(), bounds.getCenterY());
                    Point2D onScreen = b.getArea().localToScreen(inArea);
                    // The label is mouse-transparent: a real click picks the area, never the lens.
                    Event.fireEvent(
                            b.getArea(),
                            new MouseEvent(
                                    b.getArea(),
                                    b.getArea(),
                                    MouseEvent.MOUSE_CLICKED,
                                    inArea.getX(),
                                    inArea.getY(),
                                    onScreen == null ? 0 : onScreen.getX(),
                                    onScreen == null ? 0 : onScreen.getY(),
                                    MouseButton.PRIMARY,
                                    1,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    false,
                                    true,
                                    false,
                                    true,
                                    new PickResult(b.getArea(), inArea.getX(), inArea.getY())));
                });
                assertEquals(List.of(1, "run"), clicked, "clicked " + across + " of the way across the lens");
            }
            // The gap before the label belongs to the code: a click there places the caret.
            clicked.clear();
            FxTestSupport.runOnFx(() -> {
                ((javafx.scene.layout.Region) label).setPadding(new javafx.geometry.Insets(0, 0, 0, 20));
                b.getNode().applyCss();
                b.getNode().layout();
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                var bounds = label.localToScene(label.getBoundsInLocal());
                Event.fireEvent(b.getArea(), mouse(b, MouseEvent.MOUSE_CLICKED, bounds.getMinX() + 5, bounds));
            });
            assertEquals(List.of(), clicked);

            // Hovering lights the lens and shows the hand; leaving it puts the cursor back.
            FxTestSupport.runOnFx(() -> {
                var bounds = label.localToScene(label.getBoundsInLocal());
                var before = b.getArea().getCursor();
                Event.fireEvent(b.getArea(), mouse(b, MouseEvent.MOUSE_MOVED, bounds.getMaxX() - 4, bounds));
                assertEquals(javafx.scene.Cursor.HAND, b.getArea().getCursor());
                assertTrue(label.getPseudoClassStates().stream()
                        .anyMatch(c -> c.getPseudoClassName().equals("lens-hover")));
                Event.fireEvent(b.getArea(), mouse(b, MouseEvent.MOUSE_MOVED, bounds.getMaxX() + 200, bounds));
                assertEquals(before, b.getArea().getCursor());
                assertTrue(label.getPseudoClassStates().stream()
                        .noneMatch(c -> c.getPseudoClassName().equals("lens-hover")));
            });
        } finally {
            FxTestSupport.runOnFx(() -> shown[0].close());
        }
    }

    /** A primary-button mouse event at scene x on the row of {@code row}, picked on the area as a real one is. */
    private static MouseEvent mouse(
            EditorBuffer b, javafx.event.EventType<MouseEvent> type, double sceneX, javafx.geometry.Bounds row) {
        Point2D inArea = b.getArea().sceneToLocal(sceneX, row.getCenterY());
        return new MouseEvent(
                b.getArea(),
                b.getArea(),
                type,
                inArea.getX(),
                inArea.getY(),
                0,
                0,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                new PickResult(b.getArea(), inArea.getX(), inArea.getY()));
    }
}
