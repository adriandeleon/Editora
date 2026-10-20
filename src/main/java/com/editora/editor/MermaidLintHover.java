package com.editora.editor;

import java.util.function.Supplier;

import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;
import org.jspecify.annotations.Nullable;

/**
 * Shows the maid message(s) in a tooltip while the pointer is over a squiggled span of a Mermaid diagram — the
 * overlay itself is mouse-transparent. One instance per editor view; the tooltip is built on the first hit. The
 * Mermaid sibling of {@link MarkdownLintHover}.
 */
final class MermaidLintHover {

    private final CodeArea area;
    /** The overlay to read, or null while Mermaid linting is off for the buffer. */
    private final Supplier<@Nullable MermaidLintOverlay> overlay;

    private @Nullable Tooltip tooltip;
    /** Text currently shown — skips a re-{@code show()} (flicker) on each pointer move. */
    private @Nullable String shown;

    private MermaidLintHover(CodeArea area, Supplier<@Nullable MermaidLintOverlay> overlay) {
        this.area = area;
        this.overlay = overlay;
    }

    static void install(CodeArea area, Supplier<@Nullable MermaidLintOverlay> overlay) {
        MermaidLintHover hover = new MermaidLintHover(area, overlay);
        area.addEventHandler(MouseEvent.MOUSE_MOVED, hover::onMove);
        area.addEventHandler(MouseEvent.MOUSE_EXITED, e -> hover.hide());
    }

    private void onMove(MouseEvent e) {
        MermaidLintOverlay o = overlay.get();
        if (o == null || o.diagnostics().isEmpty()) {
            hide();
            return;
        }
        try {
            var hit = area.hit(e.getX(), e.getY());
            var pos = area.offsetToPosition(hit.getInsertionIndex(), TwoDimensional.Bias.Forward);
            var hits = o.at(pos.getMajor(), pos.getMinor());
            if (hits.isEmpty()) {
                hide();
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (var d : hits) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(d.message());
            }
            String text = sb.toString();
            // Already showing this exact message → don't re-show (re-positioning per pixel = flicker).
            if (tooltip != null && tooltip.isShowing() && text.equals(shown)) {
                return;
            }
            Tooltip t = tooltip;
            if (t == null) {
                t = new Tooltip();
                t.getStyleClass().add("mermaid-lint-tooltip");
                t.setWrapText(true);
                t.setMaxWidth(420);
                tooltip = t;
            }
            t.setText(text);
            shown = text;
            t.show(area, e.getScreenX() + 12, e.getScreenY() + 16);
        } catch (RuntimeException ignored) {
            // viewport mid-layout / hit miss — ignore
        }
    }

    private void hide() {
        if (tooltip != null) {
            tooltip.hide();
        }
    }
}
