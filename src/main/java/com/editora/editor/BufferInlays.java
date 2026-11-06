package com.editora.editor;

import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * What a buffer draws inline between its characters without it being text: the language server's inlay hints and
 * colour swatches, the code lenses after their lines (kept on their declarations through edits, clickable, lit on
 * hover), and a {@code .http} request's last result. One inlay factory per view reads the current maps, so a new
 * set is a map swap plus a refresh.
 */
final class BufferInlays {

    private final EditorBuffer b;

    BufferInlays(EditorBuffer b) {
        this.b = b;
    }

    /** 0-based line → the hints on it. Read live by the areas' inlay factory. */
    private java.util.Map<Integer, java.util.List<org.fxmisc.richtext.Inlay>> inlayHintsByLine = java.util.Map.of();

    boolean inlayFactoryInstalled;
    private final ColorSwatchInlays swatchInlays = new ColorSwatchInlays(); // colour literals' swatches (D9)

    /**
     * Sets the LSP inlay hints, rendered <b>inline at their own column</b> — each hint displaces the glyphs
     * after it instead of being parked at the end of the line (#824).
     *
     * <p>Hints are <em>decorations, not text</em>: they never enter the document, never shift an offset, and
     * never appear in a selection or a copy. See {@code org.fxmisc.richtext.Inlay}.
     */
    void setInlayHints(java.util.@Nullable List<EditorBuffer.InlayHint> hints) {
        if (b.largeFile || hints == null || hints.isEmpty()) {
            if (!inlayHintsByLine.isEmpty()) {
                inlayHintsByLine = java.util.Map.of();
                refreshInlayAreas();
            }
            return;
        }
        java.util.Map<Integer, java.util.List<org.fxmisc.richtext.Inlay>> byLine = new java.util.HashMap<>();
        for (EditorBuffer.InlayHint h : hints) {
            byLine.computeIfAbsent(h.line(), k -> new java.util.ArrayList<>())
                    .add(new org.fxmisc.richtext.Inlay(h.col(), h.label(), "inlay-hint"));
        }
        inlayHintsByLine = byLine;
        ensureInlayFactory();
        refreshInlayAreas();
    }

    /**
     * Installs the inlay factory on both views, once. The factory closes over the field rather than the
     * value, so pushing new hints is a map swap plus a refresh — no rebinding, and the split view stays in
     * step for free.
     */
    void ensureInlayFactory() {
        if (inlayFactoryInstalled) {
            return;
        }
        inlayFactoryInstalled = true;
        java.util.function.IntFunction<java.util.List<org.fxmisc.richtext.Inlay>> factory = this::inlaysOn;
        b.area.setInlayFactory(factory);
        if (b.area2 != null) {
            b.area2.setInlayFactory(factory);
        }
    }

    /** The inlays of one line: its hints, its code lens, then a {@code .http} request's last result. */
    java.util.@Nullable List<org.fxmisc.richtext.Inlay> inlaysOn(int line) {
        java.util.List<org.fxmisc.richtext.Inlay> lsp = lspInlaysOn(line);
        return b.httpResults.isEmpty()
                        || line < 0
                        || line >= b.area.getParagraphs().size()
                ? lsp
                : b.httpResults.inlays(line, lsp, b.area.getParagraphLength(line));
    }

    /** The LSP inlays of one line: its colour swatches and hints, then — after the last character — its code lens. */
    private java.util.@Nullable List<org.fxmisc.richtext.Inlay> lspInlaysOn(int line) {
        java.util.List<org.fxmisc.richtext.Inlay> hints = swatchInlays.merged(line, inlayHintsByLine.get(line));
        java.util.List<EditorBuffer.CodeLens> lenses = codeLensByLine.get(line);
        return lenses == null || line < 0 || line >= b.area.getParagraphs().size()
                ? hints
                : CodeLensShift.withLens(
                        hints, lenses, EditorBuffer.CodeLens::label, b.area.getParagraphLength(line), CODE_LENS_STYLE);
    }

    /** Draws the server's colour swatches (HTML review D9) before their literals; null or empty clears them. */
    void setColorSwatches(java.util.@Nullable List<com.editora.text.ColorSwatch> swatches) {
        if (swatchInlays.set(b.largeFile ? null : swatches, b.root)) {
            ensureInlayFactory();
            refreshInlayAreas();
        }
    }

    private static final String CODE_LENS_STYLE = "code-lens";

    /** 0-based line → the lenses drawn after it, joined. Read live by the areas' inlay factory. */
    private java.util.Map<Integer, java.util.List<EditorBuffer.CodeLens>> codeLensByLine = java.util.Map.of();

    private java.util.function.BiConsumer<Integer, java.util.List<EditorBuffer.CodeLens>> codeLensHandler =
            (line, lenses) -> {};

    /**
     * Sets the code lenses, each drawn after the end of its line through the same inlay mechanism as the
     * hints (so it is not part of the document either). Several lenses on one line are joined. Null or
     * empty clears them.
     */
    void setCodeLenses(java.util.@Nullable List<EditorBuffer.CodeLens> lenses) {
        java.util.Map<Integer, java.util.List<EditorBuffer.CodeLens>> byLine =
                CodeLensShift.byLine(lenses, EditorBuffer.CodeLens::line);
        if (byLine.equals(codeLensByLine)) {
            return;
        }
        codeLensByLine = byLine.isEmpty() ? java.util.Map.of() : byLine;
        ensureInlayFactory();
        refreshInlayAreas();
    }

    /** What a click on a line's code lens does: gets the line as it is now, and the lenses on it. */
    void setCodeLensHandler(java.util.function.BiConsumer<Integer, java.util.List<EditorBuffer.CodeLens>> handler) {
        this.codeLensHandler = handler == null ? (line, lenses) -> {} : handler;
    }

    /** The lenses on {@code line} (0-based) — empty when it has none. */
    java.util.List<EditorBuffer.CodeLens> codeLensesOn(int line) {
        return codeLensByLine.getOrDefault(line, java.util.List.of());
    }

    /** Runs the click action of the lenses on {@code line}, as a click on them does. */
    void activateCodeLens(int line) {
        java.util.List<EditorBuffer.CodeLens> lenses = codeLensByLine.get(line);
        if (lenses != null) {
            codeLensHandler.accept(line, lenses);
        }
    }

    /** Keeps the lenses on their declarations until the next answer arrives ({@link CodeLensShift}). */
    void shiftCodeLenses(java.util.List<org.fxmisc.richtext.model.PlainTextChange> changes) {
        var moved = CodeLensShift.afterChanges(codeLensByLine, changes, b.area);
        if (moved != null) {
            codeLensByLine = moved;
            refreshInlayAreas();
        }
    }

    private static final javafx.css.PseudoClass LENS_HOVER = javafx.css.PseudoClass.getPseudoClass("lens-hover");

    /** The lens label under the pointer, lit and given the hand cursor; null when there is none. */
    private @Nullable Node hoveredLens;

    private javafx.scene.@Nullable Cursor cursorBeforeLens;

    /**
     * A click on a code lens runs its action instead of placing the caret. The area's inlay labels are
     * mouse-transparent, so the pointer never picks one: the lens is found by its bounds, and the hover
     * look and hand cursor a pick would have given it are set here.
     */
    void installCodeLensClick(CodeArea a) {
        b.httpResults.install(a); // and on a .http request's result
        a.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() != MouseButton.PRIMARY || !e.isStillSincePress() || lensAt(a, e) == null) {
                return;
            }
            e.consume();
            activateCodeLens(lensLineAt(a, e));
        });
        a.addEventFilter(MouseEvent.MOUSE_MOVED, e -> hoverLens(a, lensAt(a, e)));
        a.addEventFilter(MouseEvent.MOUSE_EXITED, e -> hoverLens(a, null));
    }

    /**
     * The line whose lens is under the pointer. The character under it, not the insertion point beside
     * it: a lens follows the end of its line, and the insertion point nearest its right half is the start
     * of the next line — that half of the label ran the lenses of the line below, or nothing.
     */
    private static int lensLineAt(CodeArea a, MouseEvent e) {
        var hit = a.hit(e.getX(), e.getY());
        int offset = hit.getCharacterIndex().orElse(hit.getInsertionIndex());
        return a.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Backward)
                .getMajor();
    }

    /** The lens label the pointer is over, or null. Looks for one only on a line that has lenses. */
    private @Nullable Node lensAt(CodeArea a, MouseEvent e) {
        if (codeLensByLine.isEmpty() || !codeLensByLine.containsKey(lensLineAt(a, e))) {
            return null;
        }
        for (Node n : a.lookupAll("." + CODE_LENS_STYLE)) {
            // Not the gap that sets the lens apart from the code: a click there places the caret.
            javafx.geometry.Point2D at = n.sceneToLocal(e.getSceneX(), e.getSceneY());
            double gap = n instanceof Region r ? r.snappedLeftInset() : 0;
            if (at.getX() >= gap && n.contains(at)) {
                return n;
            }
        }
        return null;
    }

    private void hoverLens(CodeArea a, @Nullable Node lens) {
        if (lens == hoveredLens) {
            return;
        }
        if (hoveredLens != null) {
            hoveredLens.pseudoClassStateChanged(LENS_HOVER, false);
            a.setCursor(cursorBeforeLens);
        }
        if (lens != null) {
            lens.pseudoClassStateChanged(LENS_HOVER, true);
            cursorBeforeLens = a.getCursor();
            a.setCursor(javafx.scene.Cursor.HAND);
        }
        hoveredLens = lens;
    }

    void refreshInlayAreas() {
        if (!inlayFactoryInstalled) {
            return;
        }
        b.area.refreshInlays();
        if (b.area2 != null) {
            b.area2.refreshInlays();
        }
    }
}
