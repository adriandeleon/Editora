package com.editora.ui;

import java.io.File;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.test.JavaTestScanner;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the mouse does in the editor text and its gutter, against a wired window: where a context menu acts,
 * what a gutter click leaves alone, where a dragged selection and a dropped image land, and what a
 * double-click takes for a word.
 *
 * <p>Events are delivered to the editor's own nodes and handled by its real handlers. A context-menu request
 * and a press-drag-release are sent as the events the toolkit delivers for them (a headless window re-targets
 * a robot drag); gutter clicks go through the FX robot.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EditorMouseFxTest {

    private static final String PROSE =
            "alpha beta gamma\nlist.add(item_count);\nwe don't stop at 3.14\n" + "  indented line here\n" + lines(40);

    private FxWindowFixture fx;
    private int files;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            Stage stage = FxTestSupport.field(fx.controller, "stage");
            stage.setWidth(1100);
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

    // --- context menu ----------------------------------------------------------------------------------

    @Test
    void aRightClickOutsideTheSelectionMovesTheCaretSoPasteLandsThere() throws Exception {
        EditorBuffer b = open("paste.txt", PROSE);
        CodeArea area = b.getArea();
        int stop = PROSE.indexOf("stop");
        FxTestSupport.runOnFx(() -> {
            ClipboardContent clip = new ClipboardContent();
            clip.putString("CLIP");
            Clipboard.getSystemClipboard().setContent(clip);
            area.selectRange(0, 5);
            rightClick(area, stop);
        });
        assertEquals(stop, FxTestSupport.callOnFx(area::getCaretPosition), "the caret is where the click was");
        assertEquals("", FxTestSupport.callOnFx(area::getSelectedText), "a click elsewhere drops the selection");

        fire(b, "editmenu.paste");
        assertEquals("we don't CLIPstop at 3.14", FxTestSupport.callOnFx(() -> area.getText(2)));
        assertEquals("alpha beta gamma", FxTestSupport.callOnFx(() -> area.getText(0)), "nothing at the old caret");
    }

    @Test
    void aRightClickInsideTheSelectionKeepsItForCut() throws Exception {
        EditorBuffer b = open("cut.txt", PROSE);
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> {
            area.selectRange(0, 10); // "alpha beta"
            rightClick(area, 7);
        });
        assertEquals("alpha beta", FxTestSupport.callOnFx(area::getSelectedText));

        fire(b, "editmenu.cut");
        assertEquals(" gamma", FxTestSupport.callOnFx(() -> area.getText(0)));
    }

    @Test
    void aRightClickCollapsesExtraCaretsOnlyWhenItMissesEverySelection() throws Exception {
        EditorBuffer b = open("carets.txt", PROSE);
        CodeArea area = b.getArea();
        int beta = PROSE.indexOf("beta");
        int item = PROSE.indexOf("item_count");
        FxTestSupport.runOnFx(() -> {
            area.selectRange(beta, beta + 4);
            carets(b).addCaretWithSelection(item, item + 10);
            rightClick(area, item + 3); // inside the extra caret's selection, not the primary one
        });
        assertTrue(FxTestSupport.callOnFx(b::hasMultipleCarets), "a click on a selected range keeps every caret");
        assertEquals("beta", FxTestSupport.callOnFx(area::getSelectedText));

        FxTestSupport.runOnFx(() -> {
            hideMenu(b);
            rightClick(area, PROSE.indexOf("indented"));
        });
        assertFalse(FxTestSupport.callOnFx(b::hasMultipleCarets), "a click elsewhere leaves one caret, at the click");
        assertEquals(PROSE.indexOf("indented"), FxTestSupport.callOnFx(area::getCaretPosition));
        FxTestSupport.runOnFx(() -> hideMenu(b));
    }

    /**
     * In a buffer of its own rather than the window's: there the test gutter belongs to the controller, which
     * turns it off for a file outside a JVM project.
     */
    @Test
    void theTestOfferedIsTheOneThatWasRightClicked() throws Exception {
        String src = "class CalcTest {\n    @org.junit.jupiter.api.Test\n    void first() {\n    }\n\n"
                + "    @org.junit.jupiter.api.Test\n    void second() {\n        int x = 1;\n    }\n}\n";
        List<JavaTestScanner.TestTarget> debugged = new ArrayList<>();
        Stage stage = FxTestSupport.callOnFx(Stage::new);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(fx.configDir.resolve("CalcTest.java"));
            buffer.setTestGutterEnabled(true);
            buffer.setContent(src);
            buffer.setTestDebugHandler(debugged::add);
            stage.setScene(new Scene(new StackPane(buffer.getNode()), 700, 500));
            stage.show();
            return buffer;
        });
        try {
            settle();
            CodeArea area = b.getArea();
            // Inside a selection the caret stays put, in first(): the items that act on a position must
            // still follow the click.
            FxTestSupport.runOnFx(() -> {
                area.selectRange(src.length(), src.indexOf("void first"));
                rightClick(area, src.indexOf("int x"));
            });
            assertEquals(src.indexOf("void first"), FxTestSupport.callOnFx(area::getCaretPosition));
            fire(b, "testrunner.menu.debugTest");
            assertEquals(1, debugged.size());
            assertEquals("second", debugged.get(0).methodName());

            FxTestSupport.runOnFx(() -> {
                area.moveTo(src.indexOf("void first") + 6);
                rightClick(area, src.indexOf("int x"));
            });
            fire(b, "testrunner.menu.debugTest");
            assertEquals("second", debugged.get(1).methodName(), "outside a selection the caret went to the click");
        } finally {
            FxTestSupport.runOnFx(stage::hide);
        }
    }

    @Test
    void aKeyboardOpenedMenuActsOnTheCaretAndOpensThere() throws Exception {
        EditorBuffer b = open("keys.txt", PROSE);
        CodeArea area = b.getArea();
        ContextMenu menu = FxTestSupport.field(b, "contextMenu");
        FxTestSupport.runOnFx(() -> {
            area.requestFocus();
            area.moveTo(2, 3);
            // What Scene.processMenuEvent sends for the Menu key / Shift+F10: a point inside the focus owner.
            Bounds local = area.getBoundsInLocal();
            double x = local.getMinX() + local.getWidth() / 4;
            double y = local.getMinY() + local.getHeight() / 2;
            Point2D screen = area.localToScreen(x, y);
            Point2D scene = area.localToScene(x, y);
            Event.fireEvent(
                    area,
                    new ContextMenuEvent(
                            ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                            scene.getX(),
                            scene.getY(),
                            screen.getX(),
                            screen.getY(),
                            true,
                            null));
        });
        Bounds caret = FxTestSupport.callOnFx(() -> area.getCaretBounds().orElseThrow());
        assertTrue(FxTestSupport.callOnFx(menu::isShowing));
        assertEquals(caret.getMinX(), FxTestSupport.callOnFx(menu::getAnchorX), 1.0, "opens at the caret");
        assertEquals(caret.getMaxY(), FxTestSupport.callOnFx(menu::getAnchorY), 1.0, "opens just below the caret");
        assertEquals(area.getAbsolutePosition(2, 3), FxTestSupport.callOnFx(area::getCaretPosition), "the caret stays");

        fire(b, "editmenu.addBookmark");
        settle();
        List<Integer> marked = FxTestSupport.callOnFx(() -> {
            List<Integer> out = new ArrayList<>();
            for (int i = 0; i < area.getParagraphs().size(); i++) {
                if (b.getBookmarkManager().isBookmarked(i)) {
                    out.add(i);
                }
            }
            return out;
        });
        assertEquals(List.of(2), marked, "the bookmark goes on the caret's line, not the middle of the editor");
    }

    // --- gutter ----------------------------------------------------------------------------------------

    @Test
    void aGutterClickLeavesTheCaretSelectionAndExtraCaretsAlone() throws Exception {
        String js = "function one() {\n  let a = 1;\n  let b = 2;\n  return a + b;\n}\n\nfunction two() {\n"
                + "  let c = 3;\n  return c;\n}\n\nconst tail = 'end of file here';\nconst more = 'another line';\n";
        EditorBuffer b = open("gutter.js", js);
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> {
            b.setBreakpointsEnabled(true);
            area.requestFocus();
        });
        Node chevron = null;
        for (int i = 0; i < 100 && chevron == null; i++) {
            settle();
            chevron = FxTestSupport.callOnFx(() -> gutterNodes(area, ".fold-chevron").stream()
                    .filter(n -> n instanceof Label l && !l.getText().isBlank())
                    .findFirst()
                    .orElse(null));
        }
        assertNotNull(chevron, "the function on line 1 has a fold chevron");
        int tail = js.indexOf("tail");
        int more = js.indexOf("more");
        FxTestSupport.runOnFx(() -> {
            area.selectRange(tail, tail + 4);
            carets(b).addCaretAt(more);
        });
        Node target = chevron;
        FxTestSupport.runOnFx(() -> robotClick(target));
        settle();
        assertTrue(FxTestSupport.callOnFx(() -> area.isFolded(1)), "the chevron still folds");
        assertEquals("tail", FxTestSupport.callOnFx(area::getSelectedText), "the selection survives a fold click");
        assertTrue(FxTestSupport.callOnFx(b::hasMultipleCarets), "so do the extra carets");

        // The fold rebuilt the header's gutter row, so this is a new chevron node.
        FxTestSupport.runOnFx(() -> robotClick(gutterNodes(area, ".fold-chevron").stream()
                .filter(n -> n instanceof Label l && !l.getText().isBlank())
                .findFirst()
                .orElseThrow()));
        settle();
        assertFalse(FxTestSupport.callOnFx(() -> area.isFolded(1)), "and unfolds");
        assertEquals("tail", FxTestSupport.callOnFx(area::getSelectedText), "the selection survives an unfold click");

        FxTestSupport.runOnFx(
                () -> robotClick(gutterNodes(area, ".breakpoint-slot").get(1)));
        settle();
        assertFalse(
                FxTestSupport.callOnFx(() -> b.getBreakpointManager().lines().isEmpty()), "the strip still toggles");
        assertEquals("tail", FxTestSupport.callOnFx(area::getSelectedText), "and a breakpoint click moves nothing");
        assertEquals(tail + 4, FxTestSupport.callOnFx(area::getCaretPosition));
        assertTrue(FxTestSupport.callOnFx(b::hasMultipleCarets));
    }

    // --- dragging a selection --------------------------------------------------------------------------

    @Test
    void aSelectionReleasedOutsideTheEditorIsNotMoved() throws Exception {
        EditorBuffer b = open("drag.txt", PROSE);
        CodeArea area = b.getArea();
        int from = PROSE.indexOf("don't");
        Bounds editor = FxTestSupport.callOnFx(() -> area.localToScreen(area.getBoundsInLocal()));

        FxTestSupport.runOnFx(() -> {
            area.selectRange(from, from + 10);
            dragSelection(area, from + 2, editor.getMinX() + 300, editor.getMinY() - 60); // over the tab bar
        });
        assertEquals(PROSE, FxTestSupport.callOnFx(area::getText), "released above the editor: nothing moves");
        assertEquals("don't stop", FxTestSupport.callOnFx(area::getSelectedText), "and the selection is kept");
        assertEquals(from + 10, FxTestSupport.callOnFx(area::getCaretPosition), "with the caret back on its end");

        FxTestSupport.runOnFx(
                () -> dragSelection(area, from + 2, editor.getMaxX() + 200, editor.getMinY() + 200)); // beside it
        assertEquals(PROSE, FxTestSupport.callOnFx(area::getText), "released beside the editor: nothing moves");
        assertFalse(FxTestSupport.callOnFx(b::isDirty));
    }

    @Test
    void aSelectionReleasedOverTheTextIsStillMoved() throws Exception {
        EditorBuffer b = open("move.txt", PROSE);
        CodeArea area = b.getArea();
        int from = PROSE.indexOf("alpha ");
        FxTestSupport.runOnFx(() -> {
            area.selectRange(from, from + 6);
            Bounds to = area.getCharacterBoundsOnScreen(PROSE.indexOf("gamma"), PROSE.indexOf("gamma") + 1)
                    .orElseThrow();
            dragSelection(area, from + 2, to.getMinX() + 1, (to.getMinY() + to.getMaxY()) / 2);
        });
        assertEquals("beta alpha gamma", FxTestSupport.callOnFx(() -> area.getText(0)));
    }

    // --- column selection ------------------------------------------------------------------------------

    @Test
    void aColumnSelectionUnderWordWrapCoversEveryRowItCrosses() throws Exception {
        String wrapped = "word ".repeat(120).trim();
        String text = "short one\n" + wrapped + "\nshort two\nshort three\n";
        EditorBuffer b = open("wrap.txt", text);
        CodeArea area = b.getArea();
        FxTestSupport.runOnFx(() -> b.setWordWrap(true));
        settle();
        int rows = FxTestSupport.callOnFx(() -> area.getParagraphLinesCount(1));
        assertTrue(rows > 2, "the long paragraph wraps: " + rows);

        // From column 2 of "short one" down to column 7 of "short two": straight across the wrapped paragraph.
        FxTestSupport.runOnFx(() -> {
            Bounds from = area.getCharacterBoundsOnScreen(2, 3).orElseThrow();
            int end = text.indexOf("short two") + 7;
            Bounds to = area.getCharacterBoundsOnScreen(end, end + 1).orElseThrow();
            double x0 = from.getMinX() + 1;
            double y0 = (from.getMinY() + from.getMaxY()) / 2;
            double x1 = to.getMinX() + 1;
            double y1 = (to.getMinY() + to.getMaxY()) / 2;
            altMouse(area, MouseEvent.MOUSE_PRESSED, x0, y0);
            altMouse(area, MouseEvent.DRAG_DETECTED, x0 + 6, y0 + 6);
            altMouse(area, MouseEvent.MOUSE_DRAGGED, (x0 + x1) / 2, (y0 + y1) / 2);
            altMouse(area, MouseEvent.MOUSE_DRAGGED, x1, y1);
            altMouse(area, MouseEvent.MOUSE_RELEASED, x1, y1);
        });
        assertEquals(
                rows + 1,
                FxTestSupport.callOnFx(() -> carets(b).extraCount()),
                "one caret per visual row: 2 short lines + " + rows + " wrapped rows, less the primary");

        FxTestSupport.runOnFx(() -> carets(b).typeText("X"));
        settle();
        assertEquals("shXne", FxTestSupport.callOnFx(() -> area.getText(0)));
        assertEquals("shXwo", FxTestSupport.callOnFx(() -> area.getText(2)));
        assertEquals(
                (long) rows,
                FxTestSupport.callOnFx(
                        () -> area.getText(1).chars().filter(c -> c == 'X').count()),
                "every wrapped row inside the rectangle was edited");
        FxTestSupport.runOnFx(() -> b.setWordWrap(false));
    }

    // --- double-click ----------------------------------------------------------------------------------

    @Test
    void aDoubleClickTakesAContractionAndADecimalAsOneWord() throws Exception {
        EditorBuffer b = open("words.txt", PROSE);
        CodeArea area = b.getArea();
        assertEquals("don't", doubleClick(area, PROSE.indexOf("don't") + 1));
        assertEquals("3.14", doubleClick(area, PROSE.indexOf("3.14") + 3));
        assertEquals("item_count", doubleClick(area, PROSE.indexOf("item_count") + 2));
    }

    @Test
    void anApostropheStaysAQuoteInCode() throws Exception {
        String js = "let s = 'it'+'s';\nlet n = 3.14;\n";
        EditorBuffer b = open("quotes.js", js);
        CodeArea area = b.getArea();
        assertEquals("it", doubleClick(area, js.indexOf("it")));
        assertEquals("3.14", doubleClick(area, js.indexOf("14")));
    }

    // --- dropping an image -----------------------------------------------------------------------------

    @Test
    void aDroppedImageIsInsertedAtTheDropPoint() throws Exception {
        String md = "# Title\n\nfirst paragraph\n\nsecond paragraph\n" + lines(20);
        EditorBuffer b = open("drop.md", md);
        CodeArea area = b.getArea();
        Path image = fx.configDir.resolve("shot" + files + ".png");
        Files.write(image, new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        int second = md.indexOf("second");
        FxTestSupport.runOnFx(() -> {
            area.moveTo(0);
            Bounds at = area.getCharacterBoundsOnScreen(second, second + 1).orElseThrow();
            // The event's pick is on the area itself, so its coordinates are the area's own.
            Point2D local = area.screenToLocal(at.getMinX() + 1, (at.getMinY() + at.getMaxY()) / 2);
            Event.fireEvent(
                    area,
                    new DragEvent(
                            area,
                            area,
                            DragEvent.DRAG_DROPPED,
                            dragboardWith(image.toFile()),
                            local.getX(),
                            local.getY(),
                            at.getMinX() + 1,
                            at.getMinY() + 1,
                            TransferMode.COPY,
                            null,
                            area,
                            null));
        });
        settle();
        assertEquals("# Title", FxTestSupport.callOnFx(() -> area.getText(0)), "nothing at the old caret");
        String line = FxTestSupport.callOnFx(() -> area.getText(4));
        assertTrue(line.startsWith("![") && line.endsWith("second paragraph"), "inserted at the drop: " + line);
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

    /** The buffer's multi-caret manager, for placing extra carets the way Ctrl+click and Alt+drag do. */
    private static org.fxmisc.richtext.multi.MultiCaretManager<?, ?, ?> carets(EditorBuffer b) {
        Object layer = FxTestSupport.field(b, "multiCaret");
        assertNotNull(layer, "multiple carets are enabled");
        return (org.fxmisc.richtext.multi.MultiCaretManager<?, ?, ?>)
                FxTestSupport.call(layer, "getManager", new Class<?>[] {});
    }

    private static void rightClick(CodeArea area, int offset) {
        Bounds at = area.getCharacterBoundsOnScreen(offset, offset + 1).orElseThrow();
        double sx = at.getMinX() + 1;
        double sy = (at.getMinY() + at.getMaxY()) / 2;
        Point2D scene = area.localToScene(area.screenToLocal(sx, sy)); // an event carries scene coordinates
        Event.fireEvent(
                area,
                new ContextMenuEvent(
                        ContextMenuEvent.CONTEXT_MENU_REQUESTED, scene.getX(), scene.getY(), sx, sy, false, null));
    }

    /** Fires the open context menu's item titled {@code key}, searching one level of submenus, and closes it. */
    private void fire(EditorBuffer b, String key) throws Exception {
        FxTestSupport.runOnFx(() -> {
            ContextMenu menu = FxTestSupport.field(b, "contextMenu");
            MenuItem item = menu.getItems().stream()
                    .filter(i -> tr(key).equals(i.getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no '" + tr(key) + "' in "
                            + menu.getItems().stream().map(MenuItem::getText).toList()));
            item.fire();
            menu.hide();
        });
        settle();
    }

    private static void hideMenu(EditorBuffer b) {
        ((ContextMenu) FxTestSupport.field(b, "contextMenu")).hide();
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
