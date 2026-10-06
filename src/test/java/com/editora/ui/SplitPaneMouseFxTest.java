package com.editora.ui;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mouse in a split's <em>second</em> pane: what {@link EditorMouseFxTest} pins for the editor holds there
 * too, on that pane's own caret and selection, and leaves the first pane's alone. Same event delivery as that
 * test (see its class comment).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SplitPaneMouseFxTest {

    private static final String PROSE =
            "alpha beta gamma\nlist.add(item_count);\nwe don't stop at 3.14\n" + "  indented line here\n" + lines(40);

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
            stage.setHeight(700);
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

    /** Splits the active buffer side by side and returns its second view; pane 1 keeps the focus and caret 0. */
    private CodeArea split(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> registry.run("view.splitVertical"));
        settle();
        CodeArea second = FxTestSupport.callOnFx(() -> FxTestSupport.field(b, "area2"));
        assertNotNull(second, "the split created a second view");
        FxTestSupport.runOnFx(() -> {
            b.getArea().requestFocus();
            b.getArea().moveTo(0);
        });
        settle();
        return second;
    }

    @Test
    void aSelectionDraggedInTheSecondPaneMovesThereAndOnlyThere() throws Exception {
        EditorBuffer b = open("move.txt", PROSE);
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        int from = PROSE.indexOf("alpha ");
        FxTestSupport.runOnFx(() -> {
            second.selectRange(from, from + 6);
            Bounds to = second.getCharacterBoundsOnScreen(PROSE.indexOf("gamma"), PROSE.indexOf("gamma") + 1)
                    .orElseThrow();
            dragSelection(second, from + 2, to.getMinX() + 1, (to.getMinY() + to.getMaxY()) / 2);
        });
        assertEquals("beta alpha gamma", FxTestSupport.callOnFx(() -> second.getText(0)));
        assertEquals("alpha ", FxTestSupport.callOnFx(second::getSelectedText), "the moved text stays selected");
        assertEquals("", FxTestSupport.callOnFx(first::getSelectedText), "pane 1 selects nothing");

        // Released over the other pane or outside the editor: nothing moves, the selection is kept.
        String moved = FxTestSupport.callOnFx(second::getText);
        int stop = moved.indexOf("don't");
        Bounds other = FxTestSupport.callOnFx(() -> first.localToScreen(first.getBoundsInLocal()));
        FxTestSupport.runOnFx(() -> {
            second.selectRange(stop, stop + 10);
            dragSelection(second, stop + 2, other.getMinX() + 200, other.getMinY() + 200);
        });
        assertEquals(moved, FxTestSupport.callOnFx(second::getText), "released over pane 1: nothing moves");
        assertEquals("don't stop", FxTestSupport.callOnFx(second::getSelectedText));
        assertEquals(stop + 10, FxTestSupport.callOnFx(second::getCaretPosition));
        FxTestSupport.runOnFx(() -> dragSelection(second, stop + 2, other.getMinX() + 300, other.getMinY() - 60));
        assertEquals(moved, FxTestSupport.callOnFx(second::getText), "released above the editor: nothing moves");
    }

    @Test
    void aDoubleClickInTheSecondPaneUsesTheSameWordRules() throws Exception {
        EditorBuffer b = open("words.txt", PROSE);
        CodeArea second = split(b);
        assertEquals("don't", doubleClick(second, PROSE.indexOf("don't") + 1));
        assertEquals("3.14", doubleClick(second, PROSE.indexOf("3.14") + 3));
        assertEquals("item_count", doubleClick(second, PROSE.indexOf("item_count") + 2));
        assertEquals("", FxTestSupport.callOnFx(() -> b.getArea().getSelectedText()), "pane 1 selects nothing");

        String js = "let s = 'it'+'s';\nlet n = 3.14;\n";
        EditorBuffer code = open("quotes.js", js);
        CodeArea codeSecond = split(code);
        assertEquals("it", doubleClick(codeSecond, js.indexOf("it")), "an apostrophe stays a quote in code");
        assertEquals("3.14", doubleClick(codeSecond, js.indexOf("14")));
    }

    @Test
    void aGutterClickInTheSecondPaneLeavesBothPanesCaretsAndSelectionsAlone() throws Exception {
        String js = "function one() {\n  let a = 1;\n  let b = 2;\n  return a + b;\n}\n\nfunction two() {\n"
                + "  let c = 3;\n  return c;\n}\n\nconst tail = 'end of file here';\nconst more = 'another line';\n";
        EditorBuffer b = open("gutter.js", js);
        CodeArea first = b.getArea();
        FxTestSupport.runOnFx(() -> b.setBreakpointsEnabled(true));
        CodeArea second = split(b);
        Node chevron = null;
        for (int i = 0; i < 100 && chevron == null; i++) {
            settle();
            chevron = FxTestSupport.callOnFx(
                    () -> chevrons(second).stream().findFirst().orElse(null));
        }
        assertNotNull(chevron, "pane 2 shows a fold chevron for the function on line 1");
        int tail = js.indexOf("tail");
        int more = js.indexOf("more");
        int two = js.indexOf("two");
        FxTestSupport.runOnFx(() -> {
            first.selectRange(two, two + 3);
            second.selectRange(tail, tail + 4);
            carets2(b).addCaretAt(more);
        });
        Node target = chevron;
        FxTestSupport.runOnFx(() -> robotClick(target));
        settle();
        assertTrue(FxTestSupport.callOnFx(() -> second.isFolded(1)), "the chevron folds (in both views)");
        assertTrue(FxTestSupport.callOnFx(() -> first.isFolded(1)));
        assertEquals("tail", FxTestSupport.callOnFx(second::getSelectedText), "pane 2's selection survives");
        assertTrue(FxTestSupport.callOnFx(b::hasMultipleCarets), "so do its extra carets");
        assertEquals("two", FxTestSupport.callOnFx(first::getSelectedText), "and pane 1's selection");
        assertSame(second, FxTestSupport.callOnFx(b::getFocusedArea), "the clicked pane is the one in use");

        FxTestSupport.runOnFx(() -> robotClick(chevrons(second).getFirst()));
        settle();
        assertFalse(FxTestSupport.callOnFx(() -> second.isFolded(1)), "and unfolds");
        assertEquals("tail", FxTestSupport.callOnFx(second::getSelectedText));

        FxTestSupport.runOnFx(
                () -> robotClick(gutterNodes(second, ".breakpoint-slot").get(1)));
        settle();
        assertEquals(
                List.of(1),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(b.getBreakpointManager().lines())),
                "the strip toggles the clicked line");
        assertEquals("tail", FxTestSupport.callOnFx(second::getSelectedText), "and moves nothing");
        assertEquals(tail + 4, FxTestSupport.callOnFx(second::getCaretPosition));
        assertTrue(FxTestSupport.callOnFx(b::hasMultipleCarets));
        assertEquals("two", FxTestSupport.callOnFx(first::getSelectedText));
    }

    private static List<Node> chevrons(CodeArea view) {
        return gutterNodes(view, ".fold-chevron").stream()
                .filter(n -> n instanceof Label l && !l.getText().isBlank())
                .toList();
    }

    @Test
    void anImageDroppedOnTheSecondPaneIsInsertedAtTheDropPoint() throws Exception {
        String md = "# Title\n\nfirst paragraph\n\nsecond paragraph\n" + lines(20);
        EditorBuffer b = open("drop.md", md);
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        Path image = fx.configDir.resolve("shot" + files + ".png");
        Files.write(image, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        int second2 = md.indexOf("second");
        FxTestSupport.runOnFx(() -> {
            second.moveTo(md.indexOf("first")); // neither pane's caret is at the drop point; pane 1 has the focus
            Bounds at = second.getCharacterBoundsOnScreen(second2, second2 + 1).orElseThrow();
            Point2D local = second.screenToLocal(at.getMinX() + 1, (at.getMinY() + at.getMaxY()) / 2);
            Event.fireEvent(
                    second,
                    new DragEvent(
                            second,
                            second,
                            DragEvent.DRAG_DROPPED,
                            dragboardWith(image.toFile()),
                            local.getX(),
                            local.getY(),
                            at.getMinX() + 1,
                            at.getMinY() + 1,
                            TransferMode.COPY,
                            null,
                            second,
                            null));
        });
        settle();
        assertEquals("# Title", FxTestSupport.callOnFx(() -> second.getText(0)), "nothing at pane 1's caret");
        assertEquals("first paragraph", FxTestSupport.callOnFx(() -> second.getText(2)), "nor at pane 2's old one");
        String line = FxTestSupport.callOnFx(() -> second.getText(4));
        assertTrue(line.startsWith("![") && line.endsWith("second paragraph"), "inserted at the drop: " + line);
        assertEquals(0, FxTestSupport.callOnFx(first::getCaretPosition), "pane 1's caret did not move");
        assertSame(second, FxTestSupport.callOnFx(b::getFocusedArea), "the pane dropped on is the one in use");
    }

    @Test
    void aColumnSelectionUnderWordWrapInTheSecondPaneCoversEveryRowItCrosses() throws Exception {
        String wrapped = "word ".repeat(120).trim();
        String text = "short one\n" + wrapped + "\nshort two\nshort three\n";
        EditorBuffer b = open("wrap.txt", text);
        CodeArea second = split(b);
        FxTestSupport.runOnFx(() -> b.setWordWrap(true));
        settle();
        int rows = FxTestSupport.callOnFx(() -> second.getParagraphLinesCount(1));
        assertTrue(rows > 2, "the long paragraph wraps in pane 2: " + rows);

        FxTestSupport.runOnFx(() -> {
            Bounds from = second.getCharacterBoundsOnScreen(2, 3).orElseThrow();
            int end = text.indexOf("short two") + 7;
            Bounds to = second.getCharacterBoundsOnScreen(end, end + 1).orElseThrow();
            double x0 = from.getMinX() + 1;
            double y0 = (from.getMinY() + from.getMaxY()) / 2;
            double x1 = to.getMinX() + 1;
            double y1 = (to.getMinY() + to.getMaxY()) / 2;
            altMouse(second, MouseEvent.MOUSE_PRESSED, x0, y0);
            altMouse(second, MouseEvent.DRAG_DETECTED, x0 + 6, y0 + 6);
            altMouse(second, MouseEvent.MOUSE_DRAGGED, (x0 + x1) / 2, (y0 + y1) / 2);
            altMouse(second, MouseEvent.MOUSE_DRAGGED, x1, y1);
            altMouse(second, MouseEvent.MOUSE_RELEASED, x1, y1);
        });
        assertEquals(
                rows + 1,
                FxTestSupport.callOnFx(() -> carets2(b).extraCount()),
                "one caret per visual row: 2 short lines + " + rows + " wrapped rows, less the primary");

        FxTestSupport.runOnFx(() -> carets2(b).typeText("X"));
        settle();
        assertEquals("shXne", FxTestSupport.callOnFx(() -> second.getText(0)));
        assertEquals("shXwo", FxTestSupport.callOnFx(() -> second.getText(2)));
        assertEquals(
                (long) rows,
                FxTestSupport.callOnFx(
                        () -> second.getText(1).chars().filter(c -> c == 'X').count()),
                "every wrapped row inside the rectangle was edited");
        FxTestSupport.runOnFx(() -> b.setWordWrap(false));
    }

    @Test
    void cutAndCopyFollowTheSelectionOfThePaneInUse() throws Exception {
        EditorBuffer b = open("buttons.txt", PROSE);
        CodeArea first = b.getArea();
        CodeArea second = split(b);
        Button copy = FxTestSupport.field(fx.controller, "copyButton");
        Button cut = FxTestSupport.field(fx.controller, "cutButton");
        assertTrue(FxTestSupport.callOnFx(copy::isDisable), "nothing selected anywhere");

        FxTestSupport.runOnFx(second::requestFocus);
        settle();
        FxTestSupport.runOnFx(() -> second.selectRange(0, 5));
        settle();
        assertFalse(FxTestSupport.callOnFx(copy::isDisable), "a selection made in pane 2 enables Copy");
        assertFalse(FxTestSupport.callOnFx(cut::isDisable), "and Cut");

        FxTestSupport.runOnFx(first::requestFocus); // pane 1 has no selection
        settle();
        assertTrue(FxTestSupport.callOnFx(copy::isDisable), "back in pane 1 there is nothing to copy");
        FxTestSupport.runOnFx(second::requestFocus);
        settle();
        assertFalse(FxTestSupport.callOnFx(copy::isDisable));
        FxTestSupport.runOnFx(() -> second.deselect());
        settle();
        assertTrue(FxTestSupport.callOnFx(copy::isDisable));
    }

    // --- helpers ---------------------------------------------------------------------------------------
    /** A drag board carrying {@code file}, as one arriving from a file manager does. */
    private static Dragboard dragboardWith(File file) {
        try {
            Class<?> peerType = Class.forName("com.sun.javafx.tk.TKClipboard");
            Object peer = Proxy.newProxyInstance(
                    peerType.getClassLoader(),
                    new Class<?>[] {peerType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "hasContent" -> args[0] == DataFormat.FILES;
                        case "getContent" -> args[0] == DataFormat.FILES ? List.of(file) : null;
                        case "getContentTypes" -> Set.of(DataFormat.FILES);
                        case "getTransferModes" -> Set.of(TransferMode.COPY);
                        case "putContent" -> false;
                        case "getDragViewOffsetX", "getDragViewOffsetY" -> 0d;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "test drag board";
                        default -> null;
                    });
            return (Dragboard) Class.forName("com.sun.javafx.scene.input.DragboardHelper")
                    .getMethod("createDragboard", peerType)
                    .invoke(null, peer);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The multi-caret manager of the buffer's second view, for placing extra carets the way Ctrl+click and Alt+drag do. */
    private static org.fxmisc.richtext.multi.MultiCaretManager<?, ?, ?> carets2(EditorBuffer b) {
        Object layer = FxTestSupport.field(b, "multiCaret2");
        assertNotNull(layer, "multiple carets are enabled");
        return (org.fxmisc.richtext.multi.MultiCaretManager<?, ?, ?>)
                FxTestSupport.call(layer, "getManager", new Class<?>[] {});
    }

    private static void mouse(CodeArea area, EventType<MouseEvent> type, double sx, double sy, int clicks) {
        Point2D scene = area.localToScene(area.screenToLocal(sx, sy));
        boolean down = type != MouseEvent.MOUSE_RELEASED;
        Event.fireEvent(
                area,
                new MouseEvent(
                        type,
                        scene.getX(),
                        scene.getY(),
                        sx,
                        sy,
                        MouseButton.PRIMARY,
                        clicks,
                        false,
                        false,
                        false,
                        false,
                        down,
                        false,
                        false,
                        false,
                        false,
                        !down,
                        null));
    }

    private static void altMouse(CodeArea area, EventType<MouseEvent> type, double sx, double sy) {
        Point2D scene = area.localToScene(area.screenToLocal(sx, sy));
        boolean down = type != MouseEvent.MOUSE_RELEASED;
        Event.fireEvent(
                area,
                new MouseEvent(
                        type,
                        scene.getX(),
                        scene.getY(),
                        sx,
                        sy,
                        MouseButton.PRIMARY,
                        1,
                        false,
                        false,
                        true,
                        false,
                        down,
                        false,
                        false,
                        false,
                        false,
                        !down,
                        null));
    }

    /** Press on {@code from}, drag, release at a screen point — delivered to the pressed area, as a real one is. */
    private static void dragSelection(CodeArea area, int from, double toX, double toY) {
        Bounds at = area.getCharacterBoundsOnScreen(from, from + 1).orElseThrow();
        double x = at.getMinX() + 1;
        double y = (at.getMinY() + at.getMaxY()) / 2;
        mouse(area, MouseEvent.MOUSE_PRESSED, x, y, 1);
        mouse(area, MouseEvent.DRAG_DETECTED, x + 12, y, 1);
        mouse(area, MouseEvent.MOUSE_DRAGGED, x + 12, y, 1);
        mouse(area, MouseEvent.MOUSE_DRAGGED, (x + toX) / 2, (y + toY) / 2, 1);
        mouse(area, MouseEvent.MOUSE_DRAGGED, toX, toY, 1);
        mouse(area, MouseEvent.MOUSE_RELEASED, toX, toY, 1);
    }

    private String doubleClick(CodeArea area, int offset) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Bounds at = area.getCharacterBoundsOnScreen(offset, offset + 1).orElseThrow();
            double x = at.getMinX() + 1;
            double y = (at.getMinY() + at.getMaxY()) / 2;
            for (int clicks = 1; clicks <= 2; clicks++) {
                mouse(area, MouseEvent.MOUSE_PRESSED, x, y, clicks);
                mouse(area, MouseEvent.MOUSE_RELEASED, x, y, clicks);
            }
        });
        return FxTestSupport.callOnFx(area::getSelectedText);
    }

    /** The laid-out gutter nodes of one kind, top to bottom. */
    private static List<Node> gutterNodes(CodeArea area, String selector) {
        return area.lookupAll(selector).stream()
                .filter(n -> n.getScene() != null && n.localToScreen(n.getBoundsInLocal()) != null)
                .sorted(Comparator.comparingDouble(
                        n -> n.localToScreen(n.getBoundsInLocal()).getMinY()))
                .toList();
    }

    private static void robotClick(Node node) {
        Bounds at = node.localToScreen(node.getBoundsInLocal());
        Robot robot = new Robot();
        robot.mouseMove((at.getMinX() + at.getMaxX()) / 2, (at.getMinY() + at.getMaxY()) / 2);
        robot.mouseClick(MouseButton.PRIMARY);
    }

    private static void settle() throws Exception {
        FxTestSupport.drainFx();
        Thread.sleep(150);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }

    /** Opens {@code content} as a real file and waits for it to be the active, laid-out buffer. */
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

    private static String lines(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append("line ").append(i).append(" lorem ipsum\n");
        }
        return sb.toString();
    }
}
