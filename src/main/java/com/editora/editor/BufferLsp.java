package com.editora.editor;

import com.editora.editor.completion.CompletionEditTracker;
import com.editora.editor.lsp.LspEditPlacement;
import com.editora.editor.lsp.LspEditShift;
import com.editora.editor.undo.CompletionUndoManager;
import com.editora.text.LspDiagnostic;
import com.editora.text.LspTextEdit;
import org.jspecify.annotations.Nullable;

/**
 * A buffer's side of the language-server integration: turning the editor surface on and off for a server,
 * showing its diagnostics, sending the text when it changed, and applying the edits a server returns (completion
 * additional edits, formatting, code actions). The controller drives the server; this only gates and paints the
 * editor, so {@code editor} stays free of lsp4j.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferLsp {

    private final EditorBuffer b;

    BufferLsp(EditorBuffer b) {
        this.b = b;
    }

    private java.util.function.@Nullable Consumer<String> lspChangeListener;
    /** {@link EditorBuffer#docVersion} of the document text last sent to the server, so a send whose content hasn't changed
     *  since (the completion flush and the debounced pulse both fire for one edit) skips the whole-document
     *  {@code getText()} + {@code didChange}. Reset to -1 on LSP (de)activation so a re-attach always re-sends. */
    private long lastLspSentVersion = -1;
    /** Language ids that have a language server (Java, JS/TS/JSX/TSX, Python, XML, JSON, shell, YAML, Go,
     *  Rust, PHP, Ruby, C/C++, HTML, CSS, Kotlin, Lua, Dockerfile, SQL, Terraform, TOML, Typst, Astro). Hardcoded here
     *  so {@code editor} need not depend on the {@code lsp} package (kept in sync with
     *  {@code LspServerRegistry}). */
    private static final java.util.Set<String> LSP_LANGUAGES = java.util.Set.of(
            "java",
            "javascript",
            "javascriptreact",
            "typescript",
            "typescriptreact",
            "python",
            "xml",
            "json",
            "shell",
            "yaml",
            "go",
            "rust",
            "php",
            "ruby",
            "c",
            "cpp",
            "html",
            "css",
            "kotlin",
            "groovy",
            "scala",
            "clojure",
            "lua",
            "dockerfile",
            "sql",
            "terraform",
            "toml",
            "csharp",
            "typst",
            "astro",
            "dart",
            "elixir");
    /** Whether this buffer's language has a language server. */
    boolean isLspLanguage() {
        return LSP_LANGUAGES.contains(b.language)
                || com.editora.text.HtmlElements.HANDLEBARS_LANGUAGE.equals(b.language);
    }
    /** Injects the debounced didChange sink (text → controller → server); null disables change notices. */
    void setLspChangeListener(java.util.function.Consumer<String> listener) {
        this.lspChangeListener = listener;
    }
    /**
     * Sends the current document text to the language server — unless it already has this version (the same
     * {@link EditorBuffer#docVersion} as the last send). Both the completion flush and the debounced edit pulse call this for
     * one edit; the guard drops the second, avoiding a redundant whole-document {@code getText()} + {@code
     * didChange}. Split views share the document, so {@code area.getText()} is the canonical full text.
     */
    void sendLspChange() {
        if (lspChangeListener == null || b.docVersion == lastLspSentVersion) {
            return;
        }
        lastLspSentVersion = b.docVersion;
        lspChangeListener.accept(b.documentTextSnapshot());
    }
    /** Whether {@link #sendLspChange} has a sink: the buffer was wired to the language servers. */
    boolean sendsLspChanges() {
        return lspChangeListener != null;
    }
    /**
     * The document offset each line starts at, for translating an LSP line/character position to an offset
     * (#739). Index {@code i} is line {@code i}'s start; the array has one entry per paragraph.
     */
    int[] lineStartOffsets() {
        int count = b.area.getParagraphs().size();
        int[] starts = new int[count];
        int offset = 0;
        for (int i = 0; i < count; i++) {
            starts[i] = offset;
            offset += b.area.getParagraph(i).length() + 1; // + the newline
        }
        return starts;
    }
    /** A language whose server copes with this file despite the large-source tier (Python, review B10). */
    private boolean heavyFileLspAllowed;

    void setHeavyFileLspAllowed(boolean allowed) {
        heavyFileLspAllowed = allowed;
    }
    /** Turns LSP rendering (diagnostics overlay + hover) on/off for this buffer. The controller drives
     *  document open/close + requests; this only gates the editor surface. */
    void setLspActive(boolean on) {
        boolean was = b.lspActive;
        // Large-file mode disables LSP just like syntax highlighting and the minimap: a document of hundreds of
        // thousands of lines
        // would flood the FX thread with a full-text didOpen and tens/hundreds of thousands of diagnostics
        // to map + render (the Problems tree, minimap + scrollbar stripes), freezing the editor. largeFile
        // is implied by hugeFile (see setReadOnly), so this single guard covers both.
        b.lspActive = on && isLspLanguage() && !b.largeFile && (!b.heavyFile || heavyFileLspAllowed);
        lastLspSentVersion = -1; // an (de)activation re-syncs via didOpen; force the next change-send to fire
        if (b.lspActive || b.lspOverlay != null) {
            b.lspOverlay().setActive(b.lspActive);
        }
        // The minimap stripes only draw while LSP is active for this file, regardless of any stale list.
        b.minimap.setDiagnosticsEnabled(b.lspActive);
        if (!b.lspActive) {
            if (b.lspOverlay != null) {
                b.lspOverlay.setDiagnostics(java.util.List.of());
            }
            b.minimap.setDiagnostics(java.util.List.of());
            if (b.diagnosticStripe != null) {
                b.diagnosticStripe.setDiagnostics(java.util.List.of());
            }
        }
        b.updateDiagnosticStripe();
        b.problemCounter.update(was != b.lspActive); // on or off a server: nothing checked yet (fifth review CV1)
        b.bulb.schedule();
        b.httpVariableMarks.update(); // an HTML page's own markup marks yield to its server's diagnostics (D5)
        if (!b.lspActive) b.setColorSwatches(null); // they came from the server
    }
    /** Pushes the latest diagnostics for this buffer into the overlay + minimap/scrollbar stripes. */
    void setLspDiagnostics(java.util.List<LspDiagnostic> diagnostics) {
        if (b.lspActive || b.lspOverlay != null) {
            b.lspOverlay().setDiagnostics(diagnostics);
        }
        if (b.lspActive) {
            b.problemCounter.show(diagnostics);
        }
        b.bulb.schedule(); // a problem that arrived on the caret line may bring a fix
        b.minimap.setDiagnostics(diagnostics);
        if (b.lspActive || b.diagnosticStripe != null) {
            b.diagnosticStripe().setDiagnostics(diagnostics);
        }
    }
    /** Clears the diagnostic overlay/stripe/minimap on an edit so their line/col-anchored marks don't paint on
     *  shifted lines until the server re-pushes (#417). A no-op cost beyond the repaint the edit already does. */
    void suppressStaleDiagnostics() {
        if (b.lspOverlay != null) {
            b.lspOverlay.setDiagnostics(java.util.List.of());
        }
        b.minimap.setDiagnostics(java.util.List.of());
        if (b.diagnosticStripe != null) {
            b.diagnosticStripe.setDiagnostics(java.util.List.of());
        }
    }
    /** The inclusive 0-based line range currently visible in the editor (for a viewport semantic-tokens
     *  request). Falls back to the whole document if the viewport indices aren't available yet. */
    int[] visibleLineWindow() {
        int paragraphs = b.area.getParagraphs().size();
        try {
            int first = b.area.firstVisibleParToAllParIndex();
            int last = b.area.lastVisibleParToAllParIndex();
            if (last >= first && first >= 0) {
                return new int[] {first, Math.min(last, Math.max(0, paragraphs - 1))};
            }
        } catch (RuntimeException ignore) {
            // viewport not laid out yet — fall through to the whole-document window
        }
        return new int[] {0, Math.max(0, paragraphs - 1)};
    }
    /** Injects the LSP actions surfaced in the right-click menu while {@link EditorBuffer#isLspActive()} (Format and
     *  Code Actions are shown only when the server advertises them — see {@link BufferContextMenu#setLspFormatAvailable}/
     *  {@link #setLspCodeActionsAvailable}). */
    void setLspNavActions(
            Runnable gotoDefinition,
            Runnable findReferences,
            Runnable hover,
            Runnable format,
            Runnable codeActions,
            Runnable rename,
            Runnable gotoImplementation,
            Runnable gotoTypeDefinition) {
        b.lspGotoDefinitionAction = gotoDefinition == null ? () -> {} : gotoDefinition;
        b.editorMenu.lspFindReferencesAction = findReferences == null ? () -> {} : findReferences;
        b.editorMenu.lspHoverAction = hover == null ? () -> {} : hover;
        b.editorMenu.lspFormatAction = format == null ? () -> {} : format;
        b.lspCodeActionsAction = codeActions == null ? () -> {} : codeActions;
        b.editorMenu.lspRenameAction = rename == null ? () -> {} : rename;
        b.editorMenu.lspGotoImplementationAction = gotoImplementation == null ? () -> {} : gotoImplementation;
        b.editorMenu.lspGotoTypeDefinitionAction = gotoTypeDefinition == null ? () -> {} : gotoTypeDefinition;
    }
    /** Whether to offer "Code Actions" in the right-click menu (the server's codeAction capability, #670). */
    void setLspCodeActionsAvailable(boolean available) {
        b.lspCodeActionsAvailable = available;
        b.bulb.schedule();
    }
    /** Capture the acceptance independently of future completions and track safe typing until resolve lands. */
    java.util.function.Consumer<java.util.List<LspTextEdit>> trackCompletionAdditionalEdits() {
        var shift = b.completionActions.pendingCompletionShift();
        int start = shift == null
                ? b.area.getCaretPosition()
                : LspEditPlacement.offset(b.area, shift.startLine(), shift.startCol());
        int end = start;
        String line = b.area.getParagraph(
                        b.area.offsetToPosition(start, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                                .getMajor())
                .getText();
        int column = LspEditPlacement.position(b.area, start)[1];
        while (column < line.length() && Character.isJavaIdentifierPart(line.charAt(column++))) end++;
        var tracker = new CompletionEditTracker(shift, start, end);
        if (b.completionEditTrackers.size() >= 8) b.completionEditTrackers.removeFirst();
        b.completionEditTrackers.add(tracker);
        var undo = CompletionUndoManager.captureAdditionalEdits(b.area, b.area2);
        return edits -> {
            boolean tracked = b.completionEditTrackers.remove(tracker);
            if (tracked && !b.disposed) {
                undo.accept(() -> applyLspEdits(tracker.translate(edits), true));
                b.completionActions.suppressCompletionAt(b.docVersion);
            }
        };
    }
    /**
     * Applies the {@code additionalTextEdits} a {@code completionItem/resolve} returned for the completion this
     * buffer last accepted (an auto-import line). Unlike {@link #applyLspEdits}, the positions are first
     * translated across the accept's own insertion, which moved everything below the caret after the server
     * computed them — see {@link LspEditShift}.
     */
    void applyCompletionAdditionalEdits(java.util.List<LspTextEdit> edits) {
        // Keep the caret where the accept left it. The import lands *above* the caret, and both apply paths
        // (replaceText, and MultiChangeBuilder.commit) leave the caret at the end of what they inserted — so
        // without this, accepting `List<Item` threw the caret onto the newly inserted `import` line (#834).
        applyLspEdits(LspEditShift.shift(edits, b.completionActions.pendingCompletionShift()), true);
    }
    /**
     * Applies language-server text edits (e.g. Format Document's whole-file edit set, or a completion's
     * {@code additionalTextEdits} — the {@code import} line an auto-import adds). Edits apply as one undoable
     * commit. Column positions beyond a line's length are clamped (the LSP spec's rule), and the position one
     * past the last line, column 0, is the document end. An edit that <em>starts</em> further out than that
     * is skipped, not clamped — no valid edit begins there, so the server computed against a stale
     * revision, and clamping would apply the edit at a wrong place (#667). Inert when not editable.
     */
    void applyLspEdits(java.util.List<LspTextEdit> edits) {
        applyLspEdits(edits, false);
    }
    /**
     * As {@link #applyLspEdits(java.util.List)}, but all-or-nothing: when any edit cannot be placed — it
     * overlaps another, starts or ends outside the document, or carries a negative position — <b>nothing</b>
     * is applied and {@code false} is returned. A workspace edit spans files the user is not looking at, so
     * an edit dropped there is a half-applied refactoring reported as done; the lenient variant stays for
     * the single-buffer paths that have always skipped a stale edit.
     */
    boolean applyLspEditsAtomically(java.util.List<LspTextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return true;
        }
        return b.isEditable() && applyLspEditsNow(edits, false, true);
    }
    /** Whether {@link #applyLspEditsAtomically} would apply {@code edits}; changes nothing. */
    boolean canPlaceLspEdits(java.util.List<LspTextEdit> edits) {
        if (edits == null || edits.isEmpty()) {
            return true;
        }
        return b.isEditable()
                && LspEditPlacement.place(b.focusedArea != null ? b.focusedArea : b.area, edits, true) != null;
    }
    /**
     * As {@link #applyLspEdits(java.util.List)}, but optionally restoring the caret to the position it
     * addressed <em>before</em> the edits. Used by the auto-import path, where the inserted line sits above
     * the caret and would otherwise drag it away from what the user was typing (#834).
     */
    private void applyLspEdits(java.util.List<LspTextEdit> edits, boolean preserveCaret) {
        if (preserveCaret) b.snippetSession.withExternalEdits(() -> applyLspEditsNow(edits, true, false));
        else applyLspEditsNow(edits, false, false);
    }
    /** Returns whether the edits were placed; lenient mode always answers true. */
    private boolean applyLspEditsNow(java.util.List<LspTextEdit> edits, boolean preserveCaret, boolean strict) {
        if (edits == null || edits.isEmpty() || !b.isEditable()) {
            return !strict;
        }
        return LspEditPlacement.apply(b.focusedArea != null ? b.focusedArea : b.area, edits, preserveCaret, strict);
    }
}
