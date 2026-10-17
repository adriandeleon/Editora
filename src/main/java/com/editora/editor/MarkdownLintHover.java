package com.editora.editor;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;

import com.editora.markdown.MarkdownLint;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;

/**
 * Shows the lint message(s) in a tooltip while the pointer is over a squiggled span — the overlay itself is
 * mouse-transparent. One instance per editor view; the tooltip is built on the first hit.
 */
final class MarkdownLintHover {

    private final CodeArea area;
    /** The overlay to read, or null while linting is off for the buffer. */
    private final Supplier<MarkdownLintOverlay> overlay;
    /** A line under the messages for the marks hovered (how to fix them), or null for none. */
    private final Function<List<MarkdownLint.Diagnostic>, String> footer;

    private Tooltip tooltip;
    /** Text currently shown — skips a re-{@code show()} (flicker) on each pointer move. */
    private String shown;

    private MarkdownLintHover(
            CodeArea area,
            Supplier<MarkdownLintOverlay> overlay,
            Function<List<MarkdownLint.Diagnostic>, String> footer) {
        this.area = area;
        this.overlay = overlay;
        this.footer = footer == null ? hits -> null : footer;
    }

    static void install(CodeArea area, Supplier<MarkdownLintOverlay> overlay) {
        install(area, overlay, null);
    }

    /** {@link #install(CodeArea, Supplier)}, with {@code footer} adding a line under the messages (null: none). */
    static void install(
            CodeArea area,
            Supplier<MarkdownLintOverlay> overlay,
            Function<List<MarkdownLint.Diagnostic>, String> footer) {
        MarkdownLintHover hover = new MarkdownLintHover(area, overlay, footer);
        area.addEventHandler(MouseEvent.MOUSE_MOVED, hover::onMove);
        area.addEventHandler(MouseEvent.MOUSE_EXITED, e -> hover.hide());
    }

    private void onMove(MouseEvent e) {
        MarkdownLintOverlay o = overlay.get();
        if (o == null || o.diagnostics().isEmpty()) {
            hide();
            return;
        }
        try {
            var hit = area.hit(e.getX(), e.getY());
            var pos = area.offsetToPosition(hit.getInsertionIndex(), TwoDimensional.Bias.Forward);
            List<MarkdownLint.Diagnostic> hits = o.at(pos.getMajor(), pos.getMinor());
            if (hits.isEmpty()) {
                hide();
                return;
            }
            String extra = footer.apply(hits);
            String text = extra == null || extra.isEmpty() ? text(hits) : text(hits) + "\n" + extra;
            if (tooltip != null && tooltip.isShowing() && text.equals(shown)) {
                return;
            }
            if (tooltip == null) {
                tooltip = new Tooltip();
                tooltip.getStyleClass().add("mermaid-lint-tooltip");
                tooltip.setWrapText(true);
                tooltip.setMaxWidth(420);
            }
            tooltip.setText(text);
            shown = text;
            tooltip.show(area, e.getScreenX() + 12, e.getScreenY() + 16);
        } catch (RuntimeException ignored) {
            // viewport mid-layout / hit miss — ignore
        }
    }

    /** One {@code CODE: message} line per diagnostic, in the UI language. */
    static String text(List<MarkdownLint.Diagnostic> hits) {
        StringBuilder sb = new StringBuilder();
        for (MarkdownLint.Diagnostic d : hits) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(MarkdownLintText.labelled(d));
        }
        return sb.toString();
    }

    private void hide() {
        if (tooltip != null) {
            tooltip.hide();
        }
        shown = null;
    }
}
