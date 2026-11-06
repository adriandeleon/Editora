package com.editora.editor;

import javafx.scene.input.MouseEvent;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * Language-server touches on a buffer's surface outside completion: the diagnostic tooltip over a squiggle, the
 * caret-idle document-highlight request, and the auto-import request after a paste.
 */
final class BufferLspAssists {

    private final EditorBuffer b;

    BufferLspAssists(EditorBuffer b) {
        this.b = b;
    }

    private javafx.scene.control.@Nullable Tooltip lspTooltip;
    /** Message currently shown by {@link #lspTooltip} — skips a re-{@code show()} (flicker) on each move. */
    private @Nullable String lspTooltipText;

    /** Injects the document-highlight request (the coordinator asks the server and pushes spans back);
     *  null disables the caret-idle trigger entirely. */
    void setOccurrenceRequester(Runnable requester) {
        this.occurrenceRequester = requester;
    }

    /** Fired ~300 ms after the caret comes to rest while LSP-active; null = feature off. */
    private @Nullable Runnable occurrenceRequester;

    /** Debounces the caret-rest trigger for document highlight (#675) — one per buffer, both views. */
    private final javafx.animation.PauseTransition occurrenceIdle =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));

    /**
     * Caret-idle trigger for document highlight: any caret move clears the wash at once (the old spans
     * are for the old symbol — leaving them paints the wrong word) and re-arms the 300 ms idle timer;
     * on rest, the injected requester asks the server. Three field checks per caret move when off.
     */
    void installOccurrenceTrigger(CodeArea a) {
        a.caretPositionProperty().addListener((o, ov, nv) -> {
            if (occurrenceRequester == null || !b.lspActive || b.largeFile) {
                return;
            }
            b.clearOccurrenceSpans();
            occurrenceIdle.setOnFinished(ev -> {
                if (occurrenceRequester != null && b.lspActive && a.isFocused()) {
                    occurrenceRequester.run();
                }
            });
            occurrenceIdle.playFromStart();
        });
    }

    private EditorBuffer.@Nullable LspPasteImportsRequester lspPasteImportsRequester;
    /** Master gate for paste auto-import ({@code Settings.lspPasteImports}), pushed from the controller. */
    private boolean lspPasteImportsEnabled = true;

    void setLspPasteImportsRequester(EditorBuffer.LspPasteImportsRequester requester) {
        this.lspPasteImportsRequester = requester;
    }

    void setLspPasteImportsEnabled(boolean enabled) {
        this.lspPasteImportsEnabled = enabled;
    }

    /**
     * After a paste inserted {@code [start..end)} (absolute offsets), asks the language server for the
     * imports the pasted code needs (#742). No-op unless the feature is on, a requester is wired, the
     * buffer is an editable, non-narrowed LSP buffer (a narrowed buffer's coordinates are region-relative
     * — the same reason LSP sync is suspended there), and the span is non-empty.
     *
     * <p>The pending didChange is flushed FIRST ({@link EditorBuffer#sendLspChange}) so the server computes against
     * the post-paste document — JSON-RPC preserves order, the {@code requestLspCompletion} idiom. The
     * {@code stillValid} supplier the requester must consult before applying: an edit before or inside the pasted
     * text since drops the answer, never applied to text it wasn't computed for, while typing after it does not,
     * so a paste followed by Enter keeps its imports (twelfth review AL2). The answer's edit then lands above the
     * paste, in text that has not changed.
     */
    void requestLspPasteImports(int start, int end) {
        if (!lspPasteImportsEnabled
                || lspPasteImportsRequester == null
                || !b.lspActive
                || b.isNarrowed()
                || !b.isEditable()
                || end <= start
                || end > b.area.getLength()) {
            return;
        }
        b.sendLspChange();
        long version = b.docVersion;
        String pasted = b.area.getText(start, end);
        boolean[] touched = {false};
        var watch = b.area.plainTextChanges().subscribe(c -> {
            if (c.getPosition() < end) {
                touched[0] = true; // before or inside the pasted text: its lines moved, or it changed
            }
        });
        var stop = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(30));
        stop.setOnFinished(e -> {
            watch.unsubscribe();
            touched[0] = true; // no longer watched: only an unchanged document still takes the answer
        });
        stop.play();
        var s = b.area.offsetToPosition(start, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
        var e = b.area.offsetToPosition(end, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward);
        lspPasteImportsRequester.request(
                s.getMajor(),
                s.getMinor(),
                e.getMajor(),
                e.getMinor(),
                pasted,
                () -> b.docVersion == version || !touched[0]);
    }

    /** Shows the LSP diagnostic message(s) in a tooltip when hovering a squiggled span. */
    void installLspHover(CodeArea a) {
        a.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
            if (!b.lspActive
                    || b.lspOverlay == null
                    || b.lspOverlay.diagnostics().isEmpty()) {
                if (lspTooltip != null) {
                    lspTooltip.hide();
                }
                return;
            }
            try {
                var hit = a.hit(e.getX(), e.getY());
                var pos = a.offsetToPosition(
                        hit.getInsertionIndex(), org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
                var hits = b.lspOverlay.at(pos.getMajor(), pos.getMinor());
                if (hits.isEmpty()) {
                    if (lspTooltip != null) {
                        lspTooltip.hide();
                    }
                    lspTooltipText = null;
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (var d : hits) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    String origin = d.origin();
                    sb.append(d.message());
                    if (!origin.isEmpty()) {
                        sb.append("  (").append(origin).append(')');
                    }
                }
                String text = sb.toString();
                // Already showing this exact message → don't re-show; re-showing on every MOUSE_MOVED
                // re-positions the popup to the cursor each pixel, which reads as flicker.
                if (lspTooltip != null && lspTooltip.isShowing() && text.equals(lspTooltipText)) {
                    return;
                }
                if (lspTooltip == null) {
                    lspTooltip = new javafx.scene.control.Tooltip();
                    lspTooltip.getStyleClass().add("lsp-diagnostic-tooltip");
                    lspTooltip.setWrapText(true);
                    lspTooltip.setMaxWidth(480);
                }
                lspTooltip.setText(text);
                lspTooltipText = text;
                lspTooltip.show(a, e.getScreenX() + 12, e.getScreenY() + 16);
            } catch (RuntimeException ignored) {
                // viewport mid-layout / hit miss — ignore
            }
        });
        a.addEventHandler(MouseEvent.MOUSE_EXITED, e -> {
            if (lspTooltip != null) {
                lspTooltip.hide();
            }
        });
    }

    // --- Debugger editor surfaces: inline values + hover value tooltip --------------------------
}
