package com.editora.ui;

import java.util.List;
import java.util.function.Supplier;

import com.editora.editor.EditorBuffer;
import com.editora.editor.fold.StickyScroll;
import org.fxmisc.richtext.CodeArea;

/**
 * What a frame after a jump must show, for {@link com.editora.editor.FrameWatch}: the caret's line on screen, the
 * sticky-scroll rows for the line on top, and the minimap's box where the viewport is. A jump that scrolled a
 * pulse late, pinned the rows a pulse late or moved the box a pulse late showed a frame that breaks one of them.
 */
final class JumpFrameCheck {

    private JumpFrameCheck() {}

    /** Null when the frame is right, else what is wrong with it. FX thread. */
    static Supplier<String> of(EditorBuffer buffer) {
        CodeArea area = buffer.getArea();
        Object minimap = FxTestSupport.field(buffer, "minimap");
        return () -> {
            int first = area.firstVisibleParToAllParIndex();
            int last = area.lastVisibleParToAllParIndex();
            int caret = area.getCurrentParagraph();
            if (caret < first || caret > last) {
                return "caret on line " + caret + ", viewport " + first + ".." + last;
            }
            List<Integer> pinned =
                    StickyScroll.headerLines(buffer.getFoldManager().regions(), first, StickyScroll.DEFAULT_MAX);
            List<?> shown = FxTestSupport.field(buffer, "stickyLines");
            if (!pinned.equals(shown)) {
                return "pinned " + shown + " over line " + first + ", which pins " + pinned;
            }
            double[] painted = FxTestSupport.field(minimap, "paintedViewport");
            double[] live = (double[]) FxTestSupport.call(minimap, "viewportBox", new Class<?>[] {});
            if (painted == null || live == null || Math.abs(painted[0] - live[0]) > 0.5) {
                return "minimap box painted at " + (painted == null ? null : painted[0]) + ", viewport at "
                        + (live == null ? null : live[0]);
            }
            return null;
        };
    }

    /** A class of {@code methods} twelve-line methods: {@code m<i>} starts on line {@code 1 + 12 * i}. */
    static String javaSource(int methods) {
        StringBuilder sb = new StringBuilder("public class Big {\n");
        for (int i = 0; i < methods; i++) {
            sb.append("    void m").append(i).append("() {\n");
            for (int k = 0; k < 10; k++) {
                sb.append("        int v").append(k).append(" = ").append(k).append(";\n");
            }
            sb.append("    }\n");
        }
        return sb.append("}\n").toString();
    }
}
