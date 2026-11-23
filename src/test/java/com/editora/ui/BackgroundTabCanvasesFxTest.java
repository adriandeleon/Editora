package com.editora.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.TabPane;

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
 * A tab in the background holds no canvas texture (R1, perf review 2026-11-23). Shrinking an overlay's canvas to
 * 1x1 does not free the texture JavaFX's {@code NGCanvas} rendered it with, and a hidden tab is never rendered
 * again, so each background tab used to keep a full-window texture per overlay that had painted (about 4.7 MB for
 * the indent guides at 1480x831). The texture is read through the canvas's peer: {@code Node.peer} →
 * {@code NGCanvas.cv} → {@code RenderBuf.tex}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackgroundTabCanvasesFxTest {

    private FxWindowFixture fx;
    private int files;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create(Files.createTempDirectory("editora-fx-test"), shared -> {
            shared.getSettings().setMermaidSupport(false);
            shared.getSettings().setMarkdownLint(false);
        });
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** Indented Java, so the indent guides draw, with trailing spaces for the whitespace markers. */
    private static String indented() {
        StringBuilder sb = new StringBuilder("class Outer {\n    void body() {\n");
        for (int i = 0; i < 80; i++) {
            sb.append("        if (v")
                    .append(i)
                    .append(" > 0) {\n            v")
                    .append(i)
                    .append("--;  \n        }\n");
        }
        return sb.append("    }\n}\n").toString();
    }

    @Test
    void backgroundTabsHoldNoCanvasTexture() throws Exception {
        EditorBuffer first = open("First.java", indented());
        FxTestSupport.runOnFx(() -> {
            first.setIndentGuidesVisible(true);
            first.setWhitespaceVisible(true);
        });
        settle();
        settle();
        List<String> shown = FxTestSupport.callOnFx(() -> textured(first.getNode()));
        assertFalse(shown.isEmpty(), "precondition: the shown tab's overlays render into a texture");

        EditorBuffer second = open("Second.java", indented());
        EditorBuffer third = open("Third.java", indented());
        FxTestSupport.runOnFx(() -> third.setWhitespaceVisible(true));
        settle();
        settle();

        // Background tabs: no canvas texture of any overlay or the minimap. The window renders meanwhile (the
        // third tab), which is when the dropped canvases leave the render tree.
        assertEquals(
                List.of(), FxTestSupport.callOnFx(() -> textured(first.getNode())), "first tab, in the background");
        assertEquals(List.of(), FxTestSupport.callOnFx(() -> textured(second.getNode())), "second tab");
        assertTrue(FxTestSupport.callOnFx(() -> !textured(third.getNode()).isEmpty()), "the shown tab still renders");

        // Shown again, the first tab paints again.
        select(first);
        assertFalse(FxTestSupport.callOnFx(() -> textured(first.getNode())).isEmpty(), "the first tab paints again");
        assertEquals(List.of(), FxTestSupport.callOnFx(() -> textured(third.getNode())), "and the third lets go");
    }

    /** The canvases under {@code root} holding a texture larger than 1x1, as "Owner WxH" (FX thread). */
    private static List<String> textured(Node root) throws ReflectiveOperationException {
        List<String> found = new ArrayList<>();
        ArrayDeque<Node> queue = new ArrayDeque<>(List.of(root));
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            if (n instanceof Parent p) {
                queue.addAll(p.getChildrenUnmodifiable());
            }
            if (n instanceof Canvas c) {
                int[] size = textureSize(c);
                if (size != null && size[0] * size[1] > 1) { // a 1x1 placeholder's own texture is 4 bytes
                    String owner = c.getParent() == null
                            ? "?"
                            : c.getParent().getClass().getSimpleName();
                    found.add(owner + " " + size[0] + "x" + size[1]);
                }
            }
        }
        return found;
    }

    /** The physical size of the texture {@code canvas}'s peer holds, or null when it holds none. */
    private static int[] textureSize(Canvas canvas) throws ReflectiveOperationException {
        Field peerField = Node.class.getDeclaredField("peer");
        peerField.setAccessible(true);
        Object peer = peerField.get(canvas);
        if (peer == null) {
            return null;
        }
        Field cvField = peer.getClass().getDeclaredField("cv");
        cvField.setAccessible(true);
        Object buffer = cvField.get(peer);
        if (buffer == null) {
            return null;
        }
        Field texField = buffer.getClass().getDeclaredField("tex");
        texField.setAccessible(true);
        Object tex = texField.get(buffer);
        if (tex == null) {
            return null;
        }
        Method w = tex.getClass().getMethod("getPhysicalWidth");
        Method h = tex.getClass().getMethod("getPhysicalHeight");
        w.setAccessible(true);
        h.setAccessible(true);
        return new int[] {(int) w.invoke(tex), (int) h.invoke(tex)};
    }

    private static void settle() throws Exception {
        FxTestSupport.drainFx();
        OnFx.pulses(3);
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
